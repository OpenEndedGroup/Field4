#!/usr/bin/env python3
"""Run FIELD_TEST catalog entries through tools/field_backdoor.py."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from PIL import Image, ImageChops, ImageStat

from discover_field_tests import DEFAULT_ROOTS, discover
from field_backdoor import gradle_command, health_check, make_unverified_ssl_context
from verification_runtime import run_with_watchdog, terminate_process_tree, verification_lock


TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
DEFAULT_CATALOG_PATH = REPO_ROOT / "build" / "field-tests" / "catalog.json"
DEFAULT_RESULTS_PATH = REPO_ROOT / "build" / "field-tests" / "results.json"
GENERATED_SNIPPETS_ROOT = REPO_ROOT / "build" / "field-tests" / "generated"
CAPTURES_ROOT = REPO_ROOT / "build" / "field-tests" / "captures"
GENERATED_DOC_ASSETS_ROOT = REPO_ROOT / "build" / "field-tests" / "generated-doc-assets"
BACKDOOR_PATH = TOOLS_ROOT / "field_backdoor.py"

PLAYWRIGHT_TIMEOUT_FLOOR = 180.0
DEFAULT_AUTOMATION_PORT = 9477


class RunnerError(RuntimeError):
    pass


def probe_field_responsive(port: int, *, timeout: float = 20.0) -> bool:
    """Functional liveness probe.

    ``health_check`` only proves the HTTP layer answers -- a wedged long-session
    JVM (dead GPU context / stalled event loop) keeps returning ``/health`` "ok"
    while every real backdoor round-trip times out. That stale Field, reused off
    the default port, silently poisons a whole run. So drive ONE real round-trip
    (``snapshot``) with a short budget: responsive == exit 0 and a parseable ok
    result. A hang trips the subprocess timeout and reads as unresponsive.
    """
    cmd = [
        sys.executable, str(BACKDOOR_PATH), "--json",
        "--port", str(port), "--timeout", str(timeout),
        "--command", "snapshot",
    ]
    try:
        proc = subprocess.run(
            cmd, cwd=str(REPO_ROOT), capture_output=True, text=True, timeout=timeout + 15.0,
        )
    except (subprocess.TimeoutExpired, OSError):
        return False
    if proc.returncode != 0 or not proc.stdout.strip():
        return False
    try:
        results = json_stream_loads(proc.stdout, test_id="probe")
    except RunnerError:
        return False
    return bool(results) and bool(results[-1].get("ok"))


def _pids_listening_on(port: int) -> list[int]:
    try:
        out = subprocess.run(
            ["lsof", f"-tiTCP:{port}", "-sTCP:LISTEN"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    pids: list[int] = []
    for token in out.split():
        try:
            pids.append(int(token))
        except ValueError:
            pass
    return pids


def recycle_field(port: int, ctx: Any, *, grace: float = 12.0) -> None:
    """Retire a Field already serving the port so we can boot a fresh one.

    SIGINT (kill -2) first so it runs its shutdown hooks and disposes GPU/window
    resources (a bare SIGKILL leaks those); wait for the port to free; escalate to
    SIGKILL only if it won't go. Raise rather than reuse or double-bind the port.
    """
    if os.name == "nt":
        raise SystemExit(
            f"Field on port {port} must be recycled for a clean boot, but automatic "
            f"recycle is not implemented on Windows; kill it manually and re-run."
        )
    pids = _pids_listening_on(port)
    for pid in pids:
        try:
            os.kill(pid, signal.SIGINT)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not health_check(port, ctx):
            return
        time.sleep(0.5)
    for pid in _pids_listening_on(port):
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if not health_check(port, ctx):
            return
        time.sleep(0.5)
    raise SystemExit(f"Could not free wedged Field on port {port} (SIGINT and SIGKILL both failed).")


def ensure_persistent_field(port: int, *, boot_timeout: float = 180.0) -> subprocess.Popen[Any] | None:
    """Guarantee one warm, *responsive* Field for the whole run.

    Historically the first test's backdoor process cold-booted the JVM and then
    KILLED it on close (a self-started server is torn down at exit), so every
    subsequent test cold-booted again -- ~16s of JVM boot per test. Starting one
    persistent Field up front means each test's backdoor reuses it (within THIS
    run) and does not tear it down. Returns the process we started.

    We never reuse a Field left over from a PRIOR run. Cross-run reuse is unsafe:
    even when the snapshot probe reports the event loop healthy, a reused JVM's
    GPU/raytracing pipeline wedges -- heavy renders then hang to their full timeout,
    silently corrupting the run (this is the failure the nightly kept hitting). So
    if any Field is already on the port we probe it (for telemetry only) and then
    recycle it (SIGINT + fresh boot), guaranteeing a clean GPU context every run.
    """
    ctx = make_unverified_ssl_context()
    if health_check(port, ctx):
        state = "responsive" if probe_field_responsive(port, timeout=8.0) else "wedged (failed snapshot probe)"
        print(
            f"Field already serving port {port} ({state}); recycling it for a clean boot "
            f"(SIGINT + restart) -- never reusing a cross-run JVM.",
            file=sys.stderr, flush=True,
        )
        recycle_field(port, ctx)
    log_path = REPO_ROOT / ".field-backdoor" / "logs" / f"smoke-prewarm-field-{port}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    GENERATED_DOC_ASSETS_ROOT.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8")
    popen_kwargs: dict[str, Any] = {
        "cwd": str(REPO_ROOT),
        "stdout": log_file,
        "stderr": subprocess.STDOUT,
        "stdin": subprocess.DEVNULL,
        "text": True,
        "env": {
            **os.environ,
            "PORT": str(port),
            # Gallery smoke tests must never rewrite tracked docs captures. The
            # production docs runner leaves this unset and continues to own
            # public/docs/assets.
            "AFIELD_GENERATED_DOC_ASSET_ROOT": str(GENERATED_DOC_ASSETS_ROOT),
        },
    }
    if os.name == "nt":
        popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
    else:
        popen_kwargs["start_new_session"] = True
    proc = subprocess.Popen(gradle_command("runJvm"), **popen_kwargs)
    deadline = time.monotonic() + boot_timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise SystemExit(f"Prewarm Field exited early (code {proc.returncode}); see {log_path}")
        if health_check(port, ctx):
            return proc
        time.sleep(0.5)
    terminate_process_tree(proc)
    if health_check(port, ctx):
        recycle_field(port, ctx)
    raise SystemExit(f"Prewarm Field did not become healthy on port {port} within {boot_timeout:.0f}s; see {log_path}")


def stop_persistent_field(proc: subprocess.Popen[Any] | None, *, port: int) -> None:
    if proc is not None and proc.poll() is None:
        terminate_process_tree(proc, grace=10.0)
    ctx = make_unverified_ssl_context()
    if health_check(port, ctx):
        recycle_field(port, ctx)


def load_or_refresh_catalog(
    *,
    catalog_path: Path,
    refresh_catalog: bool,
    roots: list[str] | None,
) -> dict[str, Any]:
    if refresh_catalog or not catalog_path.exists():
        catalog = discover(REPO_ROOT, roots or DEFAULT_ROOTS)
        catalog_path.parent.mkdir(parents=True, exist_ok=True)
        catalog_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        return catalog
    return json.loads(catalog_path.read_text(encoding="utf-8"))


def filter_tests(
    tests: list[dict[str, Any]],
    *,
    ids: set[str],
    tags: set[str],
    runner_names: set[str],
) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    for test in tests:
        test_id = str(test.get("id") or "")
        if ids and test_id not in ids:
            continue
        test_tags = {str(tag) for tag in test.get("tags") or []}
        if tags and not tags.issubset(test_tags):
            continue
        runner = str(test.get("runner") or "")
        if runner_names and runner not in runner_names:
            continue
        selected.append(test)
    return selected


def current_platform_tokens() -> set[str]:
    """Case-insensitive aliases for the running platform, matched against a test's
    `skipOn` list."""
    if sys.platform == "darwin":
        return {"darwin", "mac", "macos", "osx"}
    if sys.platform.startswith("linux"):
        return {"linux"}
    if sys.platform.startswith("win") or os.name == "nt":
        return {"win", "win32", "windows"}
    return {sys.platform}


def platform_skip_reason(test: dict[str, Any]) -> str | None:
    """A FIELD_TEST may declare `skipOn: [darwin, ...]` to opt out on platforms
    where it cannot run reliably (e.g. raytracing on macOS). Returns the matching
    token if this test should be skipped on the current platform, else None."""
    raw = test.get("skipOn")
    if raw is None:
        return None
    entries = [raw] if isinstance(raw, str) else list(raw)
    tokens = current_platform_tokens()
    for entry in entries:
        if str(entry).strip().lower() in tokens:
            return str(entry)
    return None


def sanitize_test_id(test_id: str) -> str:
    cleaned = "".join(char if char.isalnum() else "-" for char in test_id).strip("-").lower()
    return cleaned or "field-test"


def extension_for_test(test: dict[str, Any]) -> str:
    property_name = str(test.get("property") or "code")
    language = str(test.get("snippetLanguage") or "").lower()
    if property_name in {"fragment", "vertex", "compute", "task", "mesh"}:
        return "glsl"
    if language == "forth":
        return "kt"
    return {
        "kotlin": "kt",
        "kt": "kt",
        "glsl": "glsl",
        "javascript": "js",
        "js": "js",
        "python": "py",
        "html": "html",
        "yaml": "yaml",
        "text": "txt",
    }.get(language, "txt")


def wrap_snippet_for_backdoor(test: dict[str, Any], snippet: str) -> str:
    language = str(test.get("snippetLanguage") or "").lower()
    if language == "forth":
        source_literal = json.dumps(snippet)
        return f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
import afield.docs.renderForthDocsSnippet

val __forthSource = {source_literal}
renderForthDocsSnippet(__forthSource)
""".strip()
    return snippet


def playwright_timeout(seconds: float) -> float:
    return max(seconds, PLAYWRIGHT_TIMEOUT_FLOOR)


def json_stream_loads(text: str, *, test_id: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    index = 0
    results: list[dict[str, Any]] = []
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        try:
            value, index = decoder.raw_decode(text, index)
        except json.JSONDecodeError as exc:
            raise RunnerError(
                f"{test_id}: backdoor returned invalid JSON: {exc}\nstdout:\n{text}"
            ) from exc
        if not isinstance(value, dict):
            raise RunnerError(f"{test_id}: backdoor returned a non-object JSON value")
        results.append(value)
    if not results:
        raise RunnerError(f"{test_id}: backdoor returned no JSON results")
    return results


def parse_backdoor_results(process: subprocess.CompletedProcess[str], *, test_id: str) -> list[dict[str, Any]]:
    stdout = process.stdout.strip()
    if not stdout:
        raise RunnerError(f"{test_id}: backdoor returned no JSON result")
    try:
        return json_stream_loads(process.stdout, test_id=test_id)
    except RunnerError as exc:
        raise RunnerError(f"{exc}\nstderr:\n{process.stderr}") from exc


def write_generated_artifact(filename: str, content: str) -> Path:
    GENERATED_SNIPPETS_ROOT.mkdir(parents=True, exist_ok=True)
    path = GENERATED_SNIPPETS_ROOT / filename
    path.write_text(content.rstrip() + "\n", encoding="utf-8")
    return path


def ensure_snippet_artifact(test: dict[str, Any]) -> Path:
    snippet = test.get("snippet")
    if not isinstance(snippet, str) or not snippet.strip():
        raise RunnerError(f"{test.get('id')}: backdoor-box tests require a non-empty snippet")
    return write_generated_artifact(
        f"{sanitize_test_id(str(test.get('id')))}.{extension_for_test(test)}",
        wrap_snippet_for_backdoor(test, snippet),
    )


def graphics_context_config(test: dict[str, Any]) -> dict[str, Any] | None:
    raw = test.get("graphicsContext")
    if raw is None or raw is False:
        return None
    if raw is True:
        return {}
    if isinstance(raw, dict):
        return dict(raw)
    raise RunnerError(f"{test.get('id')}: graphicsContext must be true/false or a mapping")


def screenshot_expectation(test: dict[str, Any]) -> dict[str, Any] | None:
    expect = test.get("expect")
    if not isinstance(expect, dict):
        return None
    screenshot = expect.get("screenshot")
    if screenshot is True:
        return {"kind": "box"}
    if isinstance(screenshot, dict):
        return dict(screenshot)
    return None


def image_expectation(test: dict[str, Any]) -> dict[str, Any] | None:
    expect = test.get("expect")
    if not isinstance(expect, dict):
        return None
    image = expect.get("image")
    if isinstance(image, dict):
        return dict(image)
    return None


def image_expectation_path(test: dict[str, Any]) -> Path | None:
    image = image_expectation(test)
    if not image:
        return None
    raw_path = image.get("path")
    if not raw_path:
        return None
    path = Path(str(raw_path))
    return path if path.is_absolute() else (REPO_ROOT / path)


def build_screenshot_command(test: dict[str, Any], capture_path: Path, *, timeout: float) -> str:
    screenshot = screenshot_expectation(test) or {}
    kind = str(screenshot.get("kind") or "box")
    if kind == "box":
        padding = float(screenshot.get("padding") or 12.0)
        parts = [
            "screenshot-box",
            "@last",
            str(capture_path),
            "--padding",
            str(padding),
            "--timeout",
            str(playwright_timeout(timeout)),
        ]
        if screenshot.get("panIntoView") is False:
            parts.append("--no-pan")
        return shlex.join(parts)

    if kind == "editor":
        padding = float(screenshot.get("padding") or 12.0)
        parts = [
            "screenshot-editor",
            "@last",
            str(capture_path),
            "--property",
            str(test.get("property") or "code"),
            "--padding",
            str(padding),
            "--timeout",
            str(playwright_timeout(timeout)),
        ]
        if screenshot.get("openOutputs"):
            parts.append("--open-outputs")
        if screenshot.get("panIntoView") is False:
            parts.append("--no-pan")
        return shlex.join(parts)

    parts = [
        "screenshot-window",
        str(capture_path),
        "--uid",
        "@last",
        "--timeout",
        str(playwright_timeout(timeout)),
    ]
    if kind == "window-editor" or screenshot.get("withEditor"):
        parts.append("--with-editor")
    if screenshot.get("openOutputs", True):
        parts.append("--open-outputs")
    if screenshot.get("panIntoView") is False:
        parts.append("--no-pan")
    return shlex.join(parts)


def build_backdoor_command(
    test: dict[str, Any],
    *,
    port: int,
    workspace: str | None,
) -> tuple[list[str], dict[str, Any]]:
    runner = str(test.get("runner") or "")
    timeout = float(test.get("timeoutSec") or 20.0)
    cmd = [sys.executable, str(BACKDOOR_PATH), "--json", "--port", str(port), "--timeout", str(timeout)]
    artifacts: dict[str, Any] = {}
    if workspace:
        cmd.extend(["--workspace", workspace])

    if runner == "jvm-main":
        entrypoint = test.get("entrypoint")
        if not isinstance(entrypoint, str) or not entrypoint:
            raise RunnerError(f"{test.get('id')}: missing jvm-main entrypoint")
        command_parts = ["run-jvm-main", "--timeout", str(timeout), entrypoint]
        for arg in test.get("args") or []:
            command_parts.extend(["--arg", str(arg)])
        cmd.extend(["--command", shlex.join(command_parts)])
        return cmd, artifacts

    if runner == "backdoor-box":
        snippet_path = ensure_snippet_artifact(test)
        artifacts["snippetPath"] = str(snippet_path)
        graphics_context = graphics_context_config(test)

        screenshot = screenshot_expectation(test)
        if screenshot is not None:
            CAPTURES_ROOT.mkdir(parents=True, exist_ok=True)
            capture_path = CAPTURES_ROOT / f"{sanitize_test_id(str(test.get('id')))}.png"
            artifacts["capturePath"] = str(capture_path)
        else:
            capture_path = None

        if test.get("document"):
            cmd.extend(["--document", str(test["document"])])
        elif bool(test.get("scratch")):
            cmd.append("--scratch")

        if graphics_context is not None:
            cmd.append("--graphics-context")

        if bool(test.get("withPlaywright")) or screenshot is not None:
            cmd.append("--with-playwright")

        name = str(test.get("boxName") or str(test.get("id"))).replace('"', "'")
        x = float(test.get("x") or 0.0)
        y = float(test.get("y") or 0.0)
        w = float(test.get("w") or 140.0)
        h = float(test.get("h") or 48.0)
        property_name = str(test.get("property") or "code")
        execution = str(test.get("execution") or "run").strip().lower()
        if execution not in {"run", "launch"}:
            raise RunnerError(f"{test.get('id')}: unsupported execution '{execution}'")
        wait_seconds = float(test.get("waitSec") or test.get("wait") or 0.0)

        commands: list[str] = []
        commands.append(
            shlex.join(
                [
                    "new-box",
                    "--name",
                    name,
                    "--x",
                    str(x),
                    "--y",
                    str(y),
                    "--w",
                    str(w),
                    "--h",
                    str(h),
                    "--timeout",
                    str(timeout),
                ]
            )
        )
        editor_capture = screenshot is not None and str(screenshot.get("kind") or "box") == "editor"
        if editor_capture:
            commands.append(
                shlex.join(
                    [
                        "set",
                        "@last",
                        property_name,
                        "--file",
                        str(snippet_path),
                    ]
                )
            )
            commands.append(
                shlex.join(
                    [
                        "editor-open",
                        "@last",
                        "--property",
                        property_name,
                        "--padding",
                        str(float(screenshot.get("padding") or 12.0)),
                        "--timeout",
                        str(playwright_timeout(timeout)),
                    ]
                )
            )
            commands.append(
                shlex.join(
                    [
                        "launch" if execution == "launch" else "run",
                        "@last",
                        "--property",
                        property_name,
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
        else:
            commands.append(
                shlex.join(
                    [
                        "launch" if execution == "launch" else "run",
                        "@last",
                        "--property",
                        property_name,
                        "--file",
                        str(snippet_path),
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
        image_path = image_expectation_path(test)
        if image_path is not None:
            commands.append(
                shlex.join(
                    [
                        "wait-file",
                        str(image_path),
                        "--timeout",
                        str(timeout),
                        "--fail-on-error-uid",
                        "@last",
                    ]
                )
            )
        elif wait_seconds > 0:
            # Tests without a concrete readiness artifact still need a bounded
            # settle period. Image-producing tests wait for the freshly deleted
            # output file above instead of sleeping for their worst-case budget.
            commands.append(shlex.join(["sleep", str(wait_seconds)]))
        if capture_path is not None:
            commands.append(build_screenshot_command(test, capture_path, timeout=timeout))
        if execution == "launch":
            commands.append(
                shlex.join(
                    [
                        "stop",
                        "@last",
                        "--timeout",
                        str(timeout),
                    ]
                )
            )
        for command_line in commands:
            cmd.extend(["--command", command_line])
        return cmd, artifacts

    raise RunnerError(f"{test.get('id')}: unsupported runner '{runner}'")


def collect_backdoor_events(backdoor_results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for result in backdoor_results:
        result_events = result.get("events") or []
        if isinstance(result_events, list):
            events.extend(event for event in result_events if isinstance(event, dict))
    return events


def collect_execution_problems(backdoor_results: list[dict[str, Any]]) -> list[str]:
    problems: list[str] = []
    for result in backdoor_results:
        command = str(result.get("command") or "<unknown command>")
        data = result.get("data")
        best_effort_timeout = (
            bool(result.get("timedOut"))
            and bool(result.get("ok", False))
            and isinstance(data, dict)
            and bool(data.get("bestEffort"))
        )
        if result.get("timedOut") and not best_effort_timeout:
            problems.append(f"{command}: timed out")
        if not bool(result.get("ok", False)):
            error_text = None
            if isinstance(data, dict):
                raw_error = data.get("error") or data.get("message")
                if raw_error is not None:
                    error_text = str(raw_error)
            problems.append(f"{command}: {error_text or 'command failed'}")

        events = result.get("events")
        if not isinstance(events, list):
            continue
        for event in events:
            if not isinstance(event, dict):
                continue
            name = event.get("name") or event.get("messageName")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if name in {"output", "peer-output"} and payload.get("kind") in {"error", "err"}:
                line = payload.get("line")
                line_suffix = f" line {line}" if line is not None else ""
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                problems.append(f"{command}{line_suffix}: {html or 'execution error'}")
                continue
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(f"{command}: {text}")

    return list(dict.fromkeys(problems))


def collect_output_gap(test: dict[str, Any], backdoor_results: list[dict[str, Any]]) -> str | None:
    snippet = test.get("snippet")
    if not isinstance(snippet, str) or not snippet.strip():
        return None

    events = collect_backdoor_events(backdoor_results)
    if not events:
        return None

    set_output_events: list[dict[str, Any]] = []
    for event in events:
        name = event.get("name") or event.get("messageName")
        if name != "set-output":
            continue
        payload = event.get("payload")
        if isinstance(payload, dict):
            set_output_events.append(payload)

    has_output_area = bool(set_output_events)
    has_svg_output = any("<svg" in str(payload.get("value", "")).lower() for payload in set_output_events)

    expects_svg = re.search(r"\blines\s*\(", snippet) is not None
    expects_display = re.search(r"\bdisplay\s*\(", snippet) is not None
    expects_output_area = expects_svg or expects_display

    if expects_output_area and not has_output_area:
        return (
            "No output-area events were emitted (missing set-output messages). "
            "Likely causes: code did not call display/lines, output proxy failed, or execution stopped before rendering."
        )

    if expects_svg and not has_svg_output:
        channels = sorted(
            {
                str(payload.get("channel", ""))
                for payload in set_output_events
                if payload.get("channel") is not None
            }
        )
        channel_text = f" Channels seen: {', '.join(channels)}." if channels else ""
        return "Code appears to call lines(...) but no SVG output was observed in set-output payloads." + channel_text

    return None


def render_event_text(event: dict[str, Any]) -> str:
    payload = event.get("payload")
    if not isinstance(payload, dict):
        return json.dumps(payload, ensure_ascii=False)
    name = event.get("name")
    if name == "output":
        return str(payload.get("html") or "")
    if name == "set-output":
        return str(payload.get("value") or "")
    if name == "notify":
        return str(payload.get("text") or "")
    if name == "output-control":
        action = str(payload.get("action") or "")
        channel = str(payload.get("channel") or "")
        return f"{action} {channel}".strip()
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)


def evaluate_expectations(
    test: dict[str, Any],
    backdoor_results: list[dict[str, Any]],
    *,
    process: subprocess.CompletedProcess[str],
    artifacts: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    expect = test.get("expect") or {}
    if not isinstance(expect, dict):
        expect = {}

    failed_results = [result for result in backdoor_results if not result.get("ok")]
    if failed_results:
        for result in failed_results:
            command = result.get("command") or "<unknown>"
            data = result.get("data") or {}
            if not isinstance(data, dict):
                data = {"value": data}
            error = data.get("error") or data.get("message") or "backdoor command failed"
            failures.append(f"{command}: {error}")
    elif process.returncode != 0:
        failures.append(f"backdoor process exited with code {process.returncode}")

    last_result = backdoor_results[-1]
    last_data = last_result.get("data") or {}
    if not isinstance(last_data, dict):
        last_data = {}

    if "exitCode" in expect:
        exit_code = last_data.get("exitCode")
        if exit_code != expect.get("exitCode"):
            failures.append(f"expected exitCode={expect.get('exitCode')}, got {exit_code}")

    stdout = str(last_data.get("stdout") or "")
    stderr = str(last_data.get("stderr") or "")

    for needle in expect.get("stdoutContains") or []:
        if str(needle) not in stdout:
            failures.append(f"stdout missing substring: {needle}")

    for needle in expect.get("stdoutNotContains") or []:
        if str(needle) in stdout:
            failures.append(f"stdout unexpectedly contained: {needle}")

    for needle in expect.get("stderrContains") or []:
        if str(needle) not in stderr:
            failures.append(f"stderr missing substring: {needle}")

    for needle in expect.get("stderrNotContains") or []:
        if str(needle) in stderr:
            failures.append(f"stderr unexpectedly contained: {needle}")

    events = collect_backdoor_events(backdoor_results)
    event_texts = [render_event_text(event) for event in events]
    output_blob = "\n".join(event_texts)
    event_names = {str(event.get("name")) for event in events}

    for needle in expect.get("outputContains") or []:
        if str(needle) not in output_blob:
            failures.append(f"output missing substring: {needle}")

    for needle in expect.get("outputNotContains") or []:
        if str(needle) in output_blob:
            failures.append(f"output unexpectedly contained: {needle}")

    for event_name in expect.get("eventKinds") or []:
        if str(event_name) not in event_names:
            failures.append(f"missing event kind: {event_name}")

    if not bool(expect.get("allowExecutionErrors", False)):
        failures.extend(collect_execution_problems(backdoor_results))

    if not bool(expect.get("allowMissingOutput", False)):
        output_gap = collect_output_gap(test, backdoor_results)
        if output_gap is not None:
            failures.append(output_gap)

    screenshot = screenshot_expectation(test)
    if screenshot is not None:
        capture_path = artifacts.get("capturePath")
        if not capture_path:
            failures.append("expected a screenshot artifact, but none was configured")
        elif not Path(capture_path).exists():
            failures.append(f"expected screenshot was not created: {capture_path}")

    image = image_expectation(test)
    if image is not None:
        failures.extend(evaluate_image_expectation(test, image, artifacts=artifacts))

    return list(dict.fromkeys(failures))


def resolve_image_path(image_expect: dict[str, Any], artifacts: dict[str, Any]) -> Path:
    raw_path = image_expect.get("path") or artifacts.get("capturePath")
    if not raw_path:
        raise RunnerError("image expectation requires a path or a screenshot artifact")
    return Path(str(raw_path))


def rect_to_box(raw: Any, *, width: int, height: int) -> tuple[int, int, int, int]:
    if not isinstance(raw, list) or len(raw) != 4:
        raise RunnerError(f"image region must be a 4-item list, got {raw!r}")
    try:
        values = [float(v) for v in raw]
    except (TypeError, ValueError) as exc:
        raise RunnerError(f"image region contains a non-numeric value: {raw!r}") from exc

    normalized = all(0.0 <= value <= 1.0 for value in values)
    if normalized:
        x = round(values[0] * width)
        y = round(values[1] * height)
        w = round(values[2] * width)
        h = round(values[3] * height)
    else:
        x, y, w, h = (round(value) for value in values)

    left = max(0, min(width - 1, x))
    top = max(0, min(height - 1, y))
    right = max(left + 1, min(width, left + max(1, w)))
    bottom = max(top + 1, min(height, top + max(1, h)))
    return left, top, right, bottom


def region_mean_rgb(image: Image.Image, raw_rect: Any) -> tuple[float, float, float]:
    box = rect_to_box(raw_rect, width=image.width, height=image.height)
    crop = image.crop(box).convert("RGB")
    mean = ImageStat.Stat(crop).mean
    return float(mean[0]), float(mean[1]), float(mean[2])


def region_mean_luma(image: Image.Image, raw_rect: Any) -> float:
    r, g, b = region_mean_rgb(image, raw_rect)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def region_mean_abs_diff(image: Image.Image, rect_a: Any, rect_b: Any) -> float:
    box_a = rect_to_box(rect_a, width=image.width, height=image.height)
    box_b = rect_to_box(rect_b, width=image.width, height=image.height)
    crop_a = image.crop(box_a).convert("RGB")
    crop_b = image.crop(box_b).convert("RGB").resize(crop_a.size)
    diff = ImageChops.difference(crop_a, crop_b)
    mean = ImageStat.Stat(diff).mean
    return float(sum(mean) / len(mean))


def evaluate_image_expectation(
    test: dict[str, Any],
    image_expect: dict[str, Any],
    *,
    artifacts: dict[str, Any],
) -> list[str]:
    failures: list[str] = []
    path = resolve_image_path(image_expect, artifacts)
    if not path.exists():
        return [f"expected image was not created: {path}"]

    with Image.open(path) as raw_image:
        image = raw_image.convert("RGBA")

        for check in image_expect.get("regionsDifferent") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid regionsDifferent entry: {check!r}")
                continue
            rect_a = check.get("a")
            rect_b = check.get("b")
            if rect_a is None or rect_b is None:
                failures.append(f"regionsDifferent requires 'a' and 'b': {check!r}")
                continue
            diff = region_mean_abs_diff(image, rect_a, rect_b)
            threshold = check.get("meanAbsDiffGt")
            if threshold is not None and diff <= float(threshold):
                failures.append(
                    f"{test.get('id')}: image diff {diff:.2f} did not exceed {float(threshold):.2f}"
                )

        for check in image_expect.get("lumaComparisons") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid lumaComparisons entry: {check!r}")
                continue
            darker = check.get("darker")
            lighter = check.get("lighter")
            if darker is None or lighter is None:
                failures.append(f"lumaComparisons requires 'darker' and 'lighter': {check!r}")
                continue
            margin = float(check.get("marginGt") or 0.0)
            darker_luma = region_mean_luma(image, darker)
            lighter_luma = region_mean_luma(image, lighter)
            if not darker_luma + margin < lighter_luma:
                failures.append(
                    f"{test.get('id')}: darker region luma {darker_luma:.2f} "
                    f"was not at least {margin:.2f} below lighter region {lighter_luma:.2f}"
                )

        for check in image_expect.get("regionLuma") or []:
            if not isinstance(check, dict):
                failures.append(f"invalid regionLuma entry: {check!r}")
                continue
            rect = check.get("rect")
            if rect is None:
                failures.append(f"regionLuma requires 'rect': {check!r}")
                continue
            luma = region_mean_luma(image, rect)
            if "gt" in check and luma <= float(check["gt"]):
                failures.append(
                    f"{test.get('id')}: region luma {luma:.2f} did not exceed {float(check['gt']):.2f}"
                )
            if "lt" in check and luma >= float(check["lt"]):
                failures.append(
                    f"{test.get('id')}: region luma {luma:.2f} was not below {float(check['lt']):.2f}"
                )

    return failures


def outer_process_timeout(test: dict[str, Any], command: list[str]) -> float:
    """Budget the wrapper, not just the in-Field operation.

    Commands execute serially. Give each its declared timeout plus fixed startup
    and shutdown headroom; Playwright commands already carry a 180s first-run
    floor. This watchdog is deliberately outside field_backdoor.py so it catches
    the successful-command/non-exiting-wrapper failure mode as well.
    """
    per_command = float(test.get("timeoutSec") or 20.0)
    if "--with-playwright" in command:
        per_command = max(per_command, PLAYWRIGHT_TIMEOUT_FLOOR)
    return 45.0 + per_command


def run_one_test(
    test: dict[str, Any],
    *,
    port: int,
    workspace: str | None,
    progress: Any = None,
    heartbeat_interval: float = 15.0,
) -> dict[str, Any]:
    test_id = str(test.get("id"))
    command, artifacts = build_backdoor_command(test, port=port, workspace=workspace)
    image_path = image_expectation_path(test)
    if image_path is not None:
        try:
            image_path.unlink()
        except FileNotFoundError:
            pass
    outer_timeout = outer_process_timeout(test, command)
    expected_results = command.count("--command")

    def commands_complete(stdout: str) -> bool:
        if not stdout.strip():
            return False
        try:
            return len(json_stream_loads(stdout, test_id=test_id)) >= expected_results
        except RunnerError:
            return False

    process, duration_ms, timed_out = run_with_watchdog(
        command,
        cwd=REPO_ROOT,
        timeout=outer_timeout,
        heartbeat_interval=heartbeat_interval,
        heartbeat=progress,
        label=test_id,
        completion_probe=commands_complete,
        exit_grace=10.0,
    )

    result: dict[str, Any] = {
        "id": test_id,
        "runner": test.get("runner"),
        "sourceFile": test.get("sourceFile"),
        "sourceLine": test.get("sourceLine"),
        "entrypoint": test.get("entrypoint"),
        "durationMs": duration_ms,
        "command": command,
        "artifacts": artifacts,
        "processExitCode": process.returncode,
        "stdout": process.stdout,
        "stderr": process.stderr,
        "outerTimeoutSec": outer_timeout,
        "runnerTimedOut": timed_out,
    }

    try:
        backdoor_results = parse_backdoor_results(process, test_id=test_id)
        result["backdoorResults"] = backdoor_results
        result["backdoorResult"] = backdoor_results[-1]
        failures = evaluate_expectations(test, backdoor_results, process=process, artifacts=artifacts)
        if timed_out:
            completed_text = (
                "all commands produced JSON results, but the backdoor process did not exit"
                if len(backdoor_results) >= expected_results
                else f"only {len(backdoor_results)} of {expected_results} commands produced JSON results"
            )
            failures.append(
                f"{test_id}: runner watchdog fired after {duration_ms / 1000.0:.1f}s "
                f"(outer limit {outer_timeout:.0f}s); {completed_text}"
            )
    except RunnerError as exc:
        failures = [str(exc)]
        result["backdoorResults"] = []
        result["backdoorResult"] = None

    result["ok"] = not failures
    result["failures"] = failures
    return result


def format_result_line(result: dict[str, Any]) -> str:
    status = "PASS" if result["ok"] else "FAIL"
    return f"{status} {result['id']} ({result['durationMs']} ms)"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", default=str(DEFAULT_CATALOG_PATH))
    parser.add_argument("--results", default=str(DEFAULT_RESULTS_PATH))
    parser.add_argument("--refresh-catalog", action="store_true")
    parser.add_argument("--root", action="append", dest="roots")
    parser.add_argument("--id", action="append", dest="ids")
    parser.add_argument("--tag", action="append", dest="tags")
    parser.add_argument("--runner", action="append", dest="runners")
    parser.add_argument("--workspace")
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_AUTOMATION_PORT,
        help=f"Dedicated Field port (default: {DEFAULT_AUTOMATION_PORT}; never the developer port 9092).",
    )
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--heartbeat-interval", type=float, default=15.0)
    parser.add_argument(
        "--no-prewarm",
        action="store_true",
        help="Do not start/keep a persistent Field; rely on an already-running server on --port.",
    )
    return parser.parse_args()


def run(args: argparse.Namespace) -> int:
    catalog_path = Path(args.catalog)
    if not catalog_path.is_absolute():
        catalog_path = REPO_ROOT / catalog_path
    results_path = Path(args.results)
    if not results_path.is_absolute():
        results_path = REPO_ROOT / results_path

    catalog = load_or_refresh_catalog(
        catalog_path=catalog_path,
        refresh_catalog=args.refresh_catalog,
        roots=args.roots,
    )
    selected = filter_tests(
        list(catalog.get("tests") or []),
        ids=set(args.ids or []),
        tags=set(args.tags or []),
        runner_names=set(args.runners or []),
    )

    if not selected:
        raise SystemExit("No tests matched the requested filters.")

    # In --json mode stdout is reserved for the final report blob, so per-test
    # progress goes to stderr (flushed per line) instead of being suppressed.
    # `2>&1 | tee` then streams it live; a bare stdout pipe still parses as JSON.
    def progress(message: str) -> None:
        stream = sys.stderr if args.json else sys.stdout
        print(message, file=stream, flush=True)

    # Per-platform opt-outs (e.g. `skipOn: [darwin]` for raytracing on macOS,
    # where the GPU RT pipeline is unreliable). Skips are reported, never silently
    # dropped, and are counted separately from pass/fail.
    tests: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    for test in selected:
        reason = platform_skip_reason(test)
        if reason:
            skipped.append(test)
            progress(f"SKIP {test.get('id')} (skipOn '{reason}' matches {sys.platform})")
        else:
            tests.append(test)

    results: list[dict[str, Any]] = []
    passed = 0
    failed = 0
    total = len(tests)

    field_proc: subprocess.Popen[Any] | None = None
    if tests and not args.no_prewarm:
        field_proc = ensure_persistent_field(args.port)
        progress(f"Started persistent Field on port {args.port} for this run")

    try:
        for index, test in enumerate(tests, start=1):
            result = run_one_test(
                test,
                port=args.port,
                workspace=args.workspace,
                progress=progress,
                heartbeat_interval=args.heartbeat_interval,
            )
            results.append(result)
            if result["ok"]:
                passed += 1
            else:
                failed += 1
            progress(f"[{index}/{total}] {format_result_line(result)}")
            for failure in result["failures"]:
                progress(f"  {failure}")
            if failed and args.fail_fast:
                break
            # Graphics-context tests wedge the GPU/window state in the persistent server.
            # Recycle after each such test so the next test gets a clean JVM.
            if not args.no_prewarm and graphics_context_config(test) is not None and index < total:
                progress(f"  recycling Field after graphics-context test (GPU state reset)")
                ctx = make_unverified_ssl_context()
                recycle_field(args.port, ctx)
                field_proc = ensure_persistent_field(args.port)
    finally:
        stop_persistent_field(field_proc, port=args.port)

    report = {
        "catalog": str(catalog_path),
        "selectedCount": len(tests),
        "passed": passed,
        "failed": failed,
        "skippedCount": len(skipped),
        "skipped": [
            {"id": t.get("id"), "reason": f"skipOn {platform_skip_reason(t)} on {sys.platform}"}
            for t in skipped
        ],
        "results": results,
    }
    results_path.parent.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        skip_note = f", {len(skipped)} skipped" if skipped else ""
        print(f"Summary: {passed} passed, {failed} failed{skip_note}")
        print(f"Results: {results_path}")

    return 0 if failed == 0 else 1


def main() -> int:
    args = parse_args()
    stream = sys.stderr if args.json else sys.stdout
    with verification_lock(
        REPO_ROOT,
        label="FIELD_TEST smoke catalog",
        progress=lambda message: print(message, file=stream, flush=True),
        heartbeat_interval=args.heartbeat_interval,
    ):
        return run(args)


if __name__ == "__main__":
    raise SystemExit(main())

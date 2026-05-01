#!/usr/bin/env python3
"""Run FIELD_TEST catalog entries through tools/field_backdoor.py."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from PIL import Image, ImageChops, ImageStat

from discover_field_tests import DEFAULT_ROOTS, discover


TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
DEFAULT_CATALOG_PATH = REPO_ROOT / "build" / "field-tests" / "catalog.json"
DEFAULT_RESULTS_PATH = REPO_ROOT / "build" / "field-tests" / "results.json"
GENERATED_SNIPPETS_ROOT = REPO_ROOT / "build" / "field-tests" / "generated"
CAPTURES_ROOT = REPO_ROOT / "build" / "field-tests" / "captures"
BACKDOOR_PATH = TOOLS_ROOT / "field_backdoor.py"

PLAYWRIGHT_TIMEOUT_FLOOR = 180.0


class RunnerError(RuntimeError):
    pass


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
        if wait_seconds > 0:
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


def run_one_test(test: dict[str, Any], *, port: int, workspace: str | None) -> dict[str, Any]:
    test_id = str(test.get("id"))
    command, artifacts = build_backdoor_command(test, port=port, workspace=workspace)
    started = time.monotonic()
    process = subprocess.run(
        command,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
    )
    duration_ms = round((time.monotonic() - started) * 1000.0)

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
    }

    try:
        backdoor_results = parse_backdoor_results(process, test_id=test_id)
        result["backdoorResults"] = backdoor_results
        result["backdoorResult"] = backdoor_results[-1]
        failures = evaluate_expectations(test, backdoor_results, process=process, artifacts=artifacts)
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
    parser.add_argument("--port", type=int, default=9092)
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
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
    tests = filter_tests(
        list(catalog.get("tests") or []),
        ids=set(args.ids or []),
        tags=set(args.tags or []),
        runner_names=set(args.runners or []),
    )

    if not tests:
        raise SystemExit("No tests matched the requested filters.")

    results: list[dict[str, Any]] = []
    passed = 0
    failed = 0

    for test in tests:
        result = run_one_test(test, port=args.port, workspace=args.workspace)
        results.append(result)
        if result["ok"]:
            passed += 1
        else:
            failed += 1
        if not args.json:
            print(format_result_line(result))
            for failure in result["failures"]:
                print(f"  {failure}")
        if failed and args.fail_fast:
            break

    report = {
        "catalog": str(catalog_path),
        "selectedCount": len(tests),
        "passed": passed,
        "failed": failed,
        "results": results,
    }
    results_path.parent.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"Summary: {passed} passed, {failed} failed")
        print(f"Results: {results_path}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

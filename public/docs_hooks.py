from __future__ import annotations

import atexit
import hashlib
import json
import logging
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
from dataclasses import dataclass, replace
from types import SimpleNamespace
from pathlib import Path
from typing import Any
import urllib.error
import urllib.request
import ssl

TOOLS_DIR = Path(__file__).resolve().parent.parent / "tools"
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))
from verification_runtime import run_with_watchdog

# Shared with tools/discover_field_tests.py; see public/fabricate_docs_scenes.py.
# public/ is already on sys.path when the Zensical build imports this module, so
# this stays a clean leaf import with no sys.path manipulation.
from fabricate_docs_scenes import (
    FABRICATE_DOCS_SCENES,
    compute_scene_inputs_digest,
    validate_scenes_against_harness,
)


LOG = logging.getLogger("zensical.field_runner")

RUNNER_TARGETS = {
    "diffstatic_runner": "diffstatic",
    "diff_runner": "diff",
    "diffstatic": "diffstatic",
    "diff": "diff",
}
RUNNER_FENCES = {"diffstatic_runner", "diff_runner"}
DIFF_FENCES = {"diff", "diff_runner"}
STATIC_FENCES = {"diffstatic", "diffstatic_runner"}
FENCE_NAMES = sorted(RUNNER_TARGETS, key=len, reverse=True)
SCRIPT_VERSION = 17
RUNNER_RETRIES = 3
RUNNER_SUBPROCESS_GRACE_SEC = 5.0
# A shared Field server (re)start can be slow to become healthy on a busy machine,
# and — after many docs-capture start/stop cycles in one run — a cold JVM start can
# legitimately exceed a single fixed deadline (TIME_WAIT port churn, file-descriptor
# and JVM-startup pressure). A one-shot start that raised on the first timeout aborted
# the whole HTML build even when every capture had already succeeded. Retry the start
# a few times, each with its own deadline, before giving up.
SERVER_START_DEADLINE_SEC = float(os.environ.get("AFIELD_DOCS_SERVER_START_TIMEOUT", "120"))
SERVER_START_ATTEMPTS = max(1, int(os.environ.get("AFIELD_DOCS_SERVER_START_ATTEMPTS", "3")))
SERVER_START_RETRY_BACKOFF_SEC = 3.0
# Kept consistent with tools/field_backdoor.py: the first Playwright attach on a
# machine may download browser binaries. While that marker is absent, give the
# capture subprocess enough headroom to finish the download instead of killing it
# at ~50s and retrying with full JVM restarts.
PLAYWRIGHT_READY_MARKER = Path(__file__).resolve().parent.parent / ".field-backdoor" / "playwright-ready"
PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC = float(os.environ.get("AFIELD_PLAYWRIGHT_ATTACH_TIMEOUT", "180"))
# Opt-in warm-session capture path: reuse ONE persistent field_session daemon
# (JVM + WebSocket + Playwright page attached once) across non-graphics captures
# instead of spawning a fresh field_backdoor.py process per capture. Measured
# ~3.8x faster on non-graphics captures. Default OFF; the default docs build is
# byte-for-byte unchanged. Graphics-context and video captures always use the
# per-process path (a graphics-context warm session needs the renderer-dispose
# fix first). Reset the shared page every N captures to bound box accumulation.
RUNNER_SESSION_ENV = "FIELD_DOCS_RUNNER_SESSION"
# Every warm-session capture now creates its box at the ORIGIN, runs, screenshots,
# then deletes it (via the `delete-box` backdoor command) so exactly zero capture
# boxes linger between captures. That keeps every box inside the browser viewport
# render-zone (so its output area actually renders) and removes the old need to
# stagger boxes off-screen. Retained only as the origin all captures share.
SESSION_BOX_ORIGIN_X = 0.0
SESSION_BOX_ORIGIN_Y = 0.0
# Cap each warm-session ATTEMPT low so a genuinely-incompatible capture (a snippet
# that errors or emits no output -- which also fails the per-process path) fails
# fast and falls back to the per-process path, which re-runs with the full
# options.timeout. Keeps a failed fast-path attempt cheap.
SESSION_ATTEMPT_TIMEOUT_SEC = float(os.environ.get("FIELD_DOCS_RUNNER_SESSION_ATTEMPT_TIMEOUT", "30"))
# Circuit breaker: after this many warm-session misses in a run, stop the daemon
# and use the (authoritative) per-process path for the rest of the build. This
# MUST stay at 1: the warm daemon and a per-process capture cannot safely share
# one Field -- a per-process fallback opens its own scratch document on the same
# server, which breaks the display-output pipeline for BOTH (and, once the shared
# server is wedged, later fallbacks cold-boot their own JVMs). So the first miss
# has to free the Field by stopping the daemon before any fallback runs. delete-box
# cleanup removes the *box-accumulation* constraint (each capture's box is created
# at the origin and deleted after the shot, so an unbounded run of compatible
# captures reuses one never-re-reset doc), but display-channel wait-output captures
# still miss in a warm session, and each such miss must end the daemon cleanly.
# Measured: raising this let the daemon coexist with fallbacks and made a full
# forth+fline build SLOWER and crash; at 1 the warm path accelerates the
# compatible-capture prefix and then falls back safely. See docs-capture-perf-lever.
SESSION_MAX_FAILURES = int(os.environ.get("FIELD_DOCS_RUNNER_SESSION_MAX_FAILURES", "1"))
RUNNER_MODE_ENV = "FIELD_DOCS_RUNNER_MODE"
RUNNER_CONTINUE_ON_ERROR_ENV = "FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR"
RUNNER_PORT_ENV = "FIELD_DOCS_RUNNER_PORT"
RUNNER_WORKSPACE_ENV = "FIELD_DOCS_RUNNER_WORKSPACE"
DEFAULT_RUNNER_PORT = 9477
RUNNER_MODE_ALIASES = {
    "incremental": "incremental",
    "cache": "incremental",
    "cached": "incremental",
    "strict": "strict",
    "fresh": "strict",
    "rebuild": "strict",
    "always": "always",
    "never": "never",
    "skip": "never",
    "off": "never",
}
FENCE_OPEN_RE = re.compile(
    rf"^(?P<indent>[ ]{{0,3}})(?P<fence>`{{3,}})(?P<runner>{'|'.join(re.escape(name) for name in FENCE_NAMES)})"
    r"(?P<rest>.*?)(?P<newline>\r?\n)?$"
)
GENERIC_FENCE_OPEN_RE = re.compile(r"^(?P<indent>[ ]{0,3})(?P<fence>`{3,}|~{3,}).*(?:\r?\n)?$")
MARKER_PREFIXES = (":::static", ":::before", ":::after", ":::")
TRUE_VALUES = {"1", "true", "yes", "on"}
FALSE_VALUES = {"0", "false", "no", "off"}
RUNNER_DIGEST_SOURCE_FILES = (
    "public/docs_hooks.py",
    "src/jsMain/kotlin/afield/Main.kt",
    "src/jvmMain/kotlin/afield/docs/screenshot.kt",
    "src/jvmMain/kotlin/afield/graphics/FLine.kt",
    "src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt",
    "src/jvmMain/kotlin/afield/four/fabricated/StandardRendererDocsHarness.kt",
    "src/jvmMain/kotlin/afield/nano/NanoVisualization.kt",
    "src/jvmMain/kotlin/afield/graphics/someSkiko.kt",
    "src/commonMain/resources/outputareas2.js",
    "tools/field_backdoor.py",
    "tools/run_field_tests.py",
)


@dataclass(frozen=True)
class RunnerOptions:
    screenshot: str
    source_image: str | None
    capture: str
    execution: str
    wait: float
    padding: float
    timeout: float
    property_name: str
    embed: bool
    alt: str | None
    document: str | None
    source_box: str | None
    source_box_properties: tuple[str, ...]
    source_box_wait: float
    run_box: str | None
    capture_box: str | None
    setup_boxes: tuple[str, ...]
    macro_file: str | None
    macro_name: str | None
    macro_selection: tuple[str, ...]
    fabricate_scene: str | None
    native_wait_frames: int
    trim_native: bool
    box_name: str
    box_x: float
    box_y: float
    box_w: float
    box_h: float
    open_outputs: bool
    wait_output: bool
    wait_output_kind: str
    wait_output_channel: str | None
    wait_output_contains: str | None
    with_editor: bool
    graphics_context: bool
    force: bool
    video: str | None  # path to .mp4 output; when set, records video instead of screenshot
    duration: float  # video duration in seconds (default 5.0)
    fps: int  # video frame rate (default 30)
    region: str  # for capture="ui": what the drive recording/still is clipped to
    clip_selector: str | None  # for capture="ui": clip to a CSS selector (overlays not backed by a box)
    warm_completion: bool  # for capture="ui": pre-warm the K1 completion engine (autocomplete captures)
    audio: str | None  # path to .wav output; offline-renders a registered synth scene to WAV
    audio_spec: str | None  # name of the DoughDspSoundDocs scene to offline-render for audio=
    hidden: bool
    language_key: str


@dataclass(frozen=True)
class CacheEntry:
    digest: str
    metadata: dict[str, Any] | None = None


class RunnerState:
    def __init__(self, config: Any) -> None:
        self.config = config
        config_path = Path(config.config_file_path).resolve()
        self.repo_root = config_path.parent.parent
        self.docs_dir = Path(config["docs_dir"]).resolve()
        self.cache_path = config_path.parent / ".field-docs-runner-cache.json"
        self.shared_log_path = config_path.parent / ".field-docs-runner-field.log"
        self.runner_mode = resolve_runner_mode(os.environ.get(RUNNER_MODE_ENV))
        self.backdoor_port = int(os.environ.get(RUNNER_PORT_ENV, str(DEFAULT_RUNNER_PORT)))
        self.continue_on_error = resolve_continue_on_error(
            os.environ.get(RUNNER_CONTINUE_ON_ERROR_ENV),
            runner_mode=self.runner_mode,
        )
        self._shared_field_process: subprocess.Popen[str] | None = None
        self._shared_field_started_by_runner = False
        self._shared_field_started_at: float | None = None
        self._shared_field_jvm_pid: int | None = None
        self._closed = False
        self.failures: list[str] = []
        # Captures that failed but had a committed asset already on disk to fall back
        # to (HTML still references a real, previously-captured image) vs. failures
        # with no asset at all (the HTML will carry a broken reference). Tracked
        # separately so continue-on-error runs report the difference honestly.
        self.fallback_failures: list[str] = []
        self.broken_failures: list[str] = []
        validate_scenes_against_harness(self.repo_root)
        self.runner_source_digest = compute_runner_source_digest(self.repo_root)
        self.cache, self.cache_metadata = self._load_cache()
        self.dirty = False
        self.started_at = time.monotonic()
        self.summary_logged = False
        self.screenshots_run = 0
        self.videos_run = 0
        self.captures_started = 0
        self.cache_hits = 0
        self.assets_reused = 0
        self.missing_assets = 0
        self.server_starts = 0
        self.server_restarts = 0
        # Warm-session (opt-in) state.
        self.session_enabled = (
            os.environ.get(RUNNER_SESSION_ENV, "").strip().lower() in TRUE_VALUES
            and self.runner_mode != "never"
        )
        self._session_process: subprocess.Popen[str] | None = None
        self._session_sock_path = self.repo_root / ".field-backdoor" / f"session-{self.backdoor_port}.sock"
        self._session_box_count = 0
        self._session_failures = 0
        self.session_captures = 0
        self._captures_since_server_restart = 0
        LOG.info("Field docs runner mode: %s", self.runner_mode)
        LOG.info("Field docs runner continue on error: %s", self.continue_on_error)
        if self.session_enabled:
            LOG.info("Field docs runner warm-session path ENABLED (experimental; fast path with per-process fallback)")
        if self.runner_mode != "never":
            self._ensure_runner_owned_field_server(reason=f"{self.runner_mode} docs capture run")
        atexit.register(self.close)

    def handle_capture_error(self, *, page_path: Path, output_path: Path, error: Exception) -> None:
        message = f"{page_path.name} -> {output_path}: {error}"
        self.failures.append(message)
        # A committed asset already on disk means the generated HTML still points at a
        # real, previously-captured image -- degraded (stale) but not broken. No asset
        # at all means the HTML will carry a broken reference: a louder problem.
        if output_path.exists():
            self.fallback_failures.append(message)
            LOG.warning(
                "Field docs runner capture FAILED, falling back to committed asset on disk: %s",
                message,
            )
        else:
            self.broken_failures.append(message)
            LOG.error(
                "Field docs runner capture FAILED with NO asset on disk (HTML will have a "
                "broken reference): %s",
                message,
            )

    def diagnose_capture_failure(self, *, page_path: Path, output_path: Path) -> str:
        """Capture BOTH sides of the WebSocket after a capture failed.

        A backdoor error like "no close frame received or sent" only reports the
        *client* side of a dropped socket -- it means the connection was severed with
        no WebSocket close handshake, i.e. the Field JVM almost certainly died. The
        real cause lives on the server. This gathers, correlates, and PERSISTS both
        ends so an unattended CI run leaves a full post-mortem behind rather than an
        opaque one-liner:

          - whether the shared Field process is still alive (or its exit code),
          - the Field server (JVM stdout+stderr) log tail,
          - any JVM crash dumps (hs_err_pid*/replay_pid*) written during THIS server's
            lifetime, matched to the JVM PID when possible,
          - the newest backdoor client session log's error/exception events.

        Returns a concise summary (with the path to the full saved report) suitable for
        embedding in the raised RuntimeError / failure message.
        """
        sections: list[str] = []

        # --- server process liveness ---
        process = self._shared_field_process
        if process is None:
            sections.append("Field server process: not tracked (no runner-owned server handle).")
        elif process.poll() is None:
            sections.append(f"Field server process: still alive (pid {process.pid}, jvm pid {self._shared_field_jvm_pid}).")
        else:
            sections.append(
                f"Field server process: EXITED with code {process.returncode} "
                f"(pid {process.pid}, jvm pid {self._shared_field_jvm_pid}) -- the JVM died mid-capture."
            )

        # --- JVM crash dumps written during this server's lifetime ---
        crash_reports = self._collect_jvm_crash_dumps()
        if crash_reports:
            for path, head in crash_reports:
                sections.append(f"JVM crash dump {path}:\n{head}")
        else:
            sections.append("JVM crash dumps: none found for this server (no hs_err/replay file since start).")

        # --- server-side log (JVM stdout+stderr) ---
        server_log = tail_text(self.shared_log_path, max_lines=120)
        if server_log:
            sections.append(f"Field server log tail ({self.shared_log_path}):\n{server_log}")
        else:
            sections.append(f"Field server log: empty or unreadable ({self.shared_log_path}).")

        # --- client-side backdoor log (the other end of the WebSocket) ---
        client = self._latest_backdoor_client_error()
        if client is not None:
            client_path, client_tail = client
            sections.append(f"Backdoor client log ({client_path}):\n{client_tail}")
        else:
            sections.append("Backdoor client log: no recent error/exception events found.")

        full_report = (
            f"Capture failure diagnosis for {page_path.name} -> {output_path}\n"
            + "\n\n".join(sections)
            + "\n"
        )

        # Persist the full post-mortem so an unattended run leaves evidence behind even
        # when continue-on-error keeps going and the exception message is truncated.
        saved_path = self._save_failure_report(output_path=output_path, report=full_report)
        summary_lines = [f"Both-sides-of-WebSocket diagnosis saved to {saved_path}" if saved_path else full_report]
        summary_lines.append(sections[0])  # liveness -- the single most telling line
        if crash_reports:
            summary_lines.append(f"JVM crash dump present: {crash_reports[0][0]}")
        return "\n".join(summary_lines)

    def _collect_jvm_crash_dumps(self) -> list[tuple[str, str]]:
        """Find JVM crash artifacts written since this server started, newest first.

        The JVM writes hs_err_pid<PID>.log (and replay_pid<PID>.log) to its working
        directory (repo_root) on a fatal native error. We match by PID when known, and
        otherwise fall back to any such file modified after the server started.
        """
        started = self._shared_field_started_at
        found: list[tuple[float, str, str]] = []
        try:
            candidates = list(self.repo_root.glob("hs_err_pid*.log")) + list(self.repo_root.glob("replay_pid*.log"))
        except OSError:
            return []
        for path in candidates:
            try:
                mtime = path.stat().st_mtime
            except OSError:
                continue
            pid_match = self._shared_field_jvm_pid is not None and f"pid{self._shared_field_jvm_pid}." in path.name
            # Accept a dump if it matches this JVM's PID, or was written after the server
            # started (a small negative slack absorbs clock/mtime granularity).
            if not pid_match and started is not None and mtime < started - 2.0:
                continue
            found.append((mtime, str(path), head_text(path, max_lines=60)))
        found.sort(key=lambda item: item[0], reverse=True)
        return [(path, head) for _mtime, path, head in found]

    def _latest_backdoor_client_error(self) -> tuple[str, str] | None:
        """Return (path, tail) of error/exception events from the newest backdoor log.

        The backdoor client (field_backdoor.py SessionLogger) writes one JSONL file per
        invocation under .field-backdoor/logs. On a dropped socket it records a
        `receiver_loop_error` (or websocket_*_error) event with the full client-side
        traceback -- the other end of the WebSocket.
        """
        log_dir = self.repo_root / ".field-backdoor" / "logs"
        try:
            logs = sorted(log_dir.glob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
        except OSError:
            return None
        started = self._shared_field_started_at
        for log_path in logs[:4]:  # only the few most-recent invocations
            try:
                if started is not None and log_path.stat().st_mtime < started - 2.0:
                    continue
            except OSError:
                continue
            error_lines: list[str] = []
            try:
                with log_path.open("r", encoding="utf-8", errors="replace") as handle:
                    for raw in handle:
                        if '"error"' in raw or '"traceback"' in raw or "_error" in raw:
                            error_lines.append(raw.rstrip("\n"))
            except OSError:
                continue
            if error_lines:
                return str(log_path), "\n".join(error_lines[-12:])
        return None

    def _save_failure_report(self, *, output_path: Path, report: str) -> str | None:
        """Write the full both-sides post-mortem to a per-failure file; best-effort."""
        try:
            failures_dir = self.shared_log_path.parent / "docs-runner-failures"
            failures_dir.mkdir(parents=True, exist_ok=True)
            safe = re.sub(r"[^A-Za-z0-9._-]+", "_", output_path.name) or "capture"
            report_path = failures_dir / f"{safe}.log"
            report_path.write_text(report, encoding="utf-8")
            return str(report_path)
        except OSError as exc:
            LOG.warning("Field docs runner could not save failure report: %s", exc)
            return None

    def _http_health_ok(self, port: int, timeout: float = 1.5) -> bool:
        url = f"https://localhost:{port}/health"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen(url, timeout=timeout, context=ctx) as response:
                body = response.read().decode("utf-8", errors="replace").strip().lower()
                return body == "ok"
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            return False

    def _listener_pids(self) -> list[int]:
        if os.name == "nt":
            return []
        try:
            result = subprocess.run(
                ["lsof", f"-tiTCP:{self.backdoor_port}", "-sTCP:LISTEN"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return []
        return [int(token) for token in result.stdout.split() if token.isdigit()]

    def _request_shared_field_shutdown(self) -> None:
        url = f"https://localhost:{self.backdoor_port}/api/shutdown-instance"
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        request = urllib.request.Request(url, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=3.0, context=ctx) as response:
                response.read()
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
            LOG.debug("Field docs runner shutdown request failed: %s", exc)

    def _ensure_shared_field_server(self) -> None:
        if self._http_health_ok(self.backdoor_port):
            LOG.info("Field docs runner using existing Field server on port %s", self.backdoor_port)
            return

        last_error: str | None = None
        for attempt in range(1, SERVER_START_ATTEMPTS + 1):
            outcome = self._start_shared_field_server_once(attempt=attempt)
            if outcome is None:
                return
            last_error = outcome
            # Tear down whatever partially started and let the port drain before retrying;
            # a lingering half-up JVM or a socket in TIME_WAIT poisons the next attempt.
            self._kill_shared_field_process()
            if attempt < SERVER_START_ATTEMPTS:
                LOG.warning(
                    "Field docs runner shared Field server start attempt %s/%s failed (%s); retrying",
                    attempt,
                    SERVER_START_ATTEMPTS,
                    last_error,
                )
                time.sleep(SERVER_START_RETRY_BACKOFF_SEC)

        raise RuntimeError(
            f"Field docs runner could not start shared Field server on port {self.backdoor_port} "
            f"after {SERVER_START_ATTEMPTS} attempt(s): {last_error}. See {self.shared_log_path}"
        )

    def _start_shared_field_server_once(self, *, attempt: int) -> str | None:
        """Start one shared Field server and wait for health.

        Returns None on success; otherwise a short reason string describing why the
        attempt failed (so the caller can retry or surface it).
        """
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        cmd = [str(self.repo_root / gradle_wrapper), "runJvm"]
        workspace = os.environ.get(RUNNER_WORKSPACE_ENV)
        if workspace:
            cmd.append(f"--args=--workspace {workspace}")

        self.shared_log_path.parent.mkdir(parents=True, exist_ok=True)
        log_handle = self.shared_log_path.open("w", encoding="utf-8")

        popen_kwargs: dict[str, Any] = {
            "cwd": str(self.repo_root),
            "stdout": log_handle,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
            "text": True,
            "env": {**os.environ, "PORT": str(self.backdoor_port)},
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True

        LOG.info(
            "Field docs runner starting shared Field server on port %s (attempt %s/%s)",
            self.backdoor_port,
            attempt,
            SERVER_START_ATTEMPTS,
        )
        self._shared_field_process = subprocess.Popen(cmd, **popen_kwargs)
        self._shared_field_started_by_runner = True
        self._shared_field_started_at = time.time()
        self._shared_field_jvm_pid = None
        self.server_starts += 1

        deadline = time.monotonic() + SERVER_START_DEADLINE_SEC
        while time.monotonic() < deadline:
            process = self._shared_field_process
            if process is None:
                return "start process handle was cleared"
            if process.poll() is not None:
                return f"process exited with code {process.returncode}"
            if self._http_health_ok(self.backdoor_port):
                LOG.info("Field docs runner shared Field server ready on port %s", self.backdoor_port)
                # The JVM is a child of the gradle wrapper, so its PID differs from
                # self._shared_field_process.pid. Capture the port listener's PID now,
                # while it is alive, so a later crash dump (hs_err_pid<PID>.log) can be
                # matched to this exact server even after the port listener is gone.
                listeners = self._listener_pids()
                self._shared_field_jvm_pid = listeners[0] if listeners else None
                return None
            time.sleep(0.5)

        return f"timed out after {SERVER_START_DEADLINE_SEC:.0f}s waiting for health"

    def _kill_shared_field_process(self) -> None:
        """Best-effort teardown of the current shared Field process (for start retries).

        Unlike `_stop_shared_field_server`, this does not require a graceful backdoor
        shutdown or assert the port is released -- it just makes sure the process group
        we launched is gone so the next start attempt begins from a clean slate.
        """
        process = self._shared_field_process
        self._shared_field_process = None
        if process is None:
            return
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except (ProcessLookupError, OSError):
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except (ProcessLookupError, OSError):
                    pass
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass

    def _ensure_runner_owned_field_server(self, *, reason: str) -> None:
        if self._shared_field_started_by_runner and self._http_health_ok(self.backdoor_port):
            return

        if self._shared_field_started_by_runner:
            self._stop_shared_field_server()
            self._ensure_shared_field_server()
            return

        if self._http_health_ok(self.backdoor_port):
            LOG.warning(
                "Field docs runner needs a fresh runner-owned Field server for %s; "
                "requesting shutdown of existing server on port %s",
                reason,
                self.backdoor_port,
            )
            self._request_shared_field_shutdown()
            deadline = time.monotonic() + 12.0
            while time.monotonic() < deadline:
                if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                    break
                time.sleep(0.25)
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                raise RuntimeError(
                    "Field docs runner could not take ownership of the Field server on "
                    f"port {self.backdoor_port} for {reason}. Stop the stale Field process "
                    "or choose a fresh FIELD_DOCS_RUNNER_PORT."
                )

        self._ensure_shared_field_server()

    def _stop_shared_field_server(self) -> None:
        if not self._shared_field_started_by_runner:
            return
        process = self._shared_field_process
        if process is None:
            return
        LOG.info("Field docs runner stopping shared Field server")
        self._request_shared_field_shutdown()
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if process.poll() is not None and not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            if not self._http_health_ok(self.backdoor_port, timeout=0.5):
                self._shared_field_process = None
                return
            time.sleep(0.25)
        if process.poll() is None:
            try:
                if os.name == "nt":
                    process.terminate()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)

        # A Gradle daemon can parent the actual Field JVM outside the wrapper's
        # process group. Never report shutdown merely because the wrapper exited.
        if self._http_health_ok(self.backdoor_port, timeout=0.5):
            if os.name == "nt":
                raise RuntimeError(
                    f"Field docs runner wrapper exited but port {self.backdoor_port} is still serving; "
                    "stop that Field instance manually."
                )
            for pid in self._listener_pids():
                try:
                    os.kill(pid, signal.SIGINT)
                except ProcessLookupError:
                    pass
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline and self._http_health_ok(self.backdoor_port, timeout=0.5):
                time.sleep(0.25)
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                for pid in self._listener_pids():
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            if self._http_health_ok(self.backdoor_port, timeout=0.5):
                raise RuntimeError(f"Field docs runner could not release port {self.backdoor_port}")
        self._shared_field_process = None

    def _restart_shared_field_server(self, *, reason: str) -> None:
        if not self._shared_field_started_by_runner:
            return
        LOG.warning("Field docs runner restarting shared Field server: %s", reason)
        self.server_restarts += 1
        self._captures_since_server_restart = 0
        # The warm-session daemon holds a WebSocket to this server; it is now stale.
        self._stop_session(reason="shared server restart")
        self._stop_shared_field_server()
        self._ensure_shared_field_server()

    # ----- Warm-session daemon (opt-in) -------------------------------------
    def _ensure_session(self) -> Path | None:
        """Start (once) and return the socket path for the warm-session daemon.

        The daemon reuses the already-running shared Field on backdoor_port and
        keeps one Playwright page attached, so eligible captures become cheap
        socket batches instead of fresh field_backdoor.py processes. Returns None
        (disabling the fast path) if it cannot be brought up.
        """
        if not self.session_enabled:
            return None
        if self._session_process is not None and self._session_process.poll() is None:
            return self._session_sock_path
        field_session = self.repo_root / "tools" / "field_session.py"
        if not field_session.exists():
            LOG.warning("Field docs runner warm-session disabled: %s not found", field_session)
            self.session_enabled = False
            return None
        if self._session_sock_path.exists():
            try:
                self._session_sock_path.unlink()
            except OSError:
                pass
        cmd = [
            sys.executable, str(field_session), "serve",
            "--port", str(self.backdoor_port),
            "--with-playwright",
            "--timeout", "45",
        ]
        self._session_log_path = self.repo_root / ".field-backdoor" / "logs" / f"docs-session-{self.backdoor_port}.log"
        self._session_log_path.parent.mkdir(parents=True, exist_ok=True)
        session_log = self._session_log_path.open("w", encoding="utf-8")
        LOG.info("Field docs runner starting warm-session daemon on port %s (log: %s)", self.backdoor_port, self._session_log_path)
        self._session_process = subprocess.Popen(
            cmd, cwd=str(self.repo_root),
            stdout=session_log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            text=True,
            **({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}),
        )
        deadline = time.monotonic() + PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC + 30.0
        while time.monotonic() < deadline:
            if self._session_process.poll() is not None:
                LOG.warning(
                    "Field docs runner warm-session daemon exited early; disabling fast path (see %s)",
                    self._session_log_path,
                )
                self._session_process = None
                self.session_enabled = False
                return None
            if self._session_sock_path.exists():
                try:
                    pong = self._session_request({"command": "ping"}, timeout=5.0)
                    if pong.get("pong"):
                        self._session_box_count = 0
                        return self._session_sock_path
                except OSError:
                    pass
            time.sleep(0.25)
        LOG.warning("Field docs runner warm-session daemon did not become ready; disabling fast path")
        self._stop_session(reason="daemon startup timeout")
        self.session_enabled = False
        return None

    def _session_request(self, request: dict[str, Any], *, timeout: float) -> dict[str, Any]:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            sock.connect(str(self._session_sock_path))
            sock.sendall((json.dumps(request) + "\n").encode("utf-8"))
            chunks: list[bytes] = []
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                chunks.append(chunk)
                if b"\n" in chunk:
                    break
        raw = b"".join(chunks)
        if not raw:
            raise OSError("empty response from warm-session daemon")
        return json.loads(raw.split(b"\n", 1)[0].decode("utf-8"))

    def _stop_session(self, *, reason: str) -> None:
        process = self._session_process
        if process is None:
            return
        LOG.info("Field docs runner stopping warm-session daemon: %s", reason)
        try:
            if process.poll() is None and self._session_sock_path.exists():
                self._session_request({"command": "shutdown"}, timeout=5.0)
        except OSError:
            pass
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            try:
                if os.name == "nt":
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
        self._session_process = None
        if self._session_sock_path.exists():
            try:
                self._session_sock_path.unlink()
            except OSError:
                pass

    def _try_session_capture(
        self,
        *,
        page_path: Path,
        block_index: int,
        screenshot_path: Path,
        options: RunnerOptions,
        source_image_path: Path | None,
        source_image_min_mtime: float | None,
        code: str,
    ) -> Any | None:
        """Run one eligible capture through the warm session.

        Returns a subprocess.CompletedProcess-like object ONLY when the capture
        is fully clean (rc 0, no execution error, no output gap, file written);
        otherwise returns None so the caller falls back to the authoritative
        per-process path. This never lets warm-session/page-reuse state produce a
        false failure.
        """
        sock = self._ensure_session()
        if sock is None:
            return None
        # One scratch doc for the whole session (the daemon opened it at startup;
        # re-resetting mid-session breaks the output pipeline). Every capture's box
        # is created at the ORIGIN -- in the viewport render-zone -- then deleted
        # right after the screenshot, so exactly zero capture boxes accumulate and
        # an unbounded number of captures reuse one never-re-reset doc. Cap the
        # per-command timeout low so a capture the warm page can't produce fails
        # fast and the process fallback re-runs it with the full options.timeout.
        attempt_timeout = min(options.timeout, SESSION_ATTEMPT_TIMEOUT_SEC)
        session_options = replace(
            options, box_x=SESSION_BOX_ORIGIN_X, box_y=SESSION_BOX_ORIGIN_Y, timeout=attempt_timeout,
        )
        invocation = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=screenshot_path,
            options=session_options,
            source_image_path=source_image_path,
            source_image_min_mtime=source_image_min_mtime,
        )
        # Reset the scratch doc only on the very first capture of the run (this
        # establishes the output pipeline). Never re-reset mid-run -- a second
        # open_document in a warm session breaks display output entirely.
        request = {
            "commands": invocation.backdoor_commands,
            "stdin": code,
            "withPlaywright": True,
            "resetDoc": self._session_box_count == 0,
            "timeout": attempt_timeout,
            "document": invocation.document,
        }
        try:
            resp = self._session_request(
                request, timeout=runner_subprocess_timeout(options=session_options, is_video=False) + 10.0,
            )
        except (OSError, ValueError) as exc:
            self._stop_session(reason="request failure")
            return self._session_fallback(f"request failed: {exc}")
        self._session_box_count += 1
        results = resp.get("results") or []
        # Delete every box this capture created (parsed from the batch results) so
        # the board is clean before the next capture stamps its box at the same
        # origin -- otherwise a leftover box there would ghost the next screenshot.
        # Runs on success AND on a miss (the batch may have stopped before an
        # in-band delete), so overlap is impossible either way.
        self._session_cleanup_boxes(self._session_created_box_uids(results))
        stdout = "\n".join(json.dumps(r) for r in results)
        completed = SimpleNamespace(returncode=0 if resp.get("ok") else 1, stdout=stdout, stderr=resp.get("error", "") or "")
        if completed.returncode != 0:
            failing = next((r for r in results if not r.get("ok")), None)
            detail = resp.get("error") or (
                f"{failing.get('command')}: {(failing.get('data') or {}).get('error', 'failed')}"
                if isinstance(failing, dict) else "unknown"
            )
            return self._session_fallback(f"batch not ok ({detail})")
        if extract_backdoor_execution_error(stdout) is not None:
            return self._session_fallback("execution error in batch")
        if extract_backdoor_output_gap(stdout=stdout, code=code, options=options) is not None:
            return self._session_fallback("output gap in batch")
        if not screenshot_path.exists():
            return self._session_fallback("no output file produced")
        self._session_failures = 0
        self.session_captures += 1
        return completed

    @staticmethod
    def _session_created_box_uids(results: list[Any]) -> list[str]:
        """Uids of the boxes a batch created, in order, parsed from new-box results."""
        uids: list[str] = []
        for result in results:
            if not isinstance(result, dict) or not str(result.get("command", "")).startswith("new-box"):
                continue
            data = result.get("data")
            box = data.get("box") if isinstance(data, dict) else None
            uid = box.get("uid") if isinstance(box, dict) else None
            if isinstance(uid, str) and uid:
                uids.append(uid)
        return uids

    def _session_cleanup_boxes(self, uids: list[str]) -> None:
        """Best-effort delete of a capture's boxes so nothing lingers at the origin.

        A separate, cheap socket batch (no Playwright, no doc reset). Failure here
        never fails the capture -- the screenshot is already on disk -- but a clean
        board is required for the next origin box not to overlap, so we log misses.
        """
        if not uids:
            return
        if self._session_process is None or self._session_process.poll() is not None:
            return
        try:
            resp = self._session_request(
                {
                    "commands": [shlex.join(["delete-box", *uids, "--timeout", "15"])],
                    "withPlaywright": False,
                    "resetDoc": False,
                    "timeout": 15.0,
                },
                timeout=30.0,
            )
        except OSError as exc:
            LOG.warning("Field docs runner warm-session cleanup delete failed: %s", exc)
            return
        if not resp.get("ok"):
            LOG.warning("Field docs runner warm-session cleanup delete not ok: %s", json.dumps(resp)[:300])

    def _session_fallback(self, reason: str) -> None:
        """Record a warm-session miss and trip the circuit breaker if needed."""
        self._session_failures += 1
        LOG.warning(
            "Field docs runner warm-session miss (%s/%s): %s; using per-process path",
            self._session_failures, SESSION_MAX_FAILURES, reason,
        )
        if self._session_failures >= SESSION_MAX_FAILURES:
            LOG.warning(
                "Field docs runner disabling warm-session fast path after %s misses; "
                "remaining captures use the per-process path",
                self._session_failures,
            )
            self.session_enabled = False
            self._stop_session(reason="circuit breaker tripped")
        return None

    def _load_cache(self) -> tuple[dict[str, str], dict[str, dict[str, Any]]]:
        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {}, {}
        except Exception as exc:
            LOG.warning("Field docs runner ignored unreadable cache %s: %s", self.cache_path, exc)
            return {}, {}
        if not isinstance(payload, dict):
            return {}, {}
        entries = payload.get("entries")
        if not isinstance(entries, dict):
            return {}, {}

        cache: dict[str, str] = {}
        metadata: dict[str, dict[str, Any]] = {}
        for key, value in entries.items():
            cache_key = str(key)
            if isinstance(value, str):
                cache[cache_key] = value
                continue
            if not isinstance(value, dict):
                continue
            digest = value.get("digest")
            if not isinstance(digest, str):
                continue
            cache[cache_key] = digest
            entry_metadata = value.get("metadata")
            if isinstance(entry_metadata, dict):
                metadata[cache_key] = entry_metadata
        return cache, metadata

    def save(self) -> None:
        if not self.dirty:
            return
        entries: dict[str, Any] = {}
        for cache_key, digest in self.cache.items():
            metadata = self.cache_metadata.get(cache_key)
            if metadata is None:
                entries[cache_key] = digest
            else:
                entries[cache_key] = {
                    "digest": digest,
                    "metadata": metadata,
                }
        payload = {
            "version": SCRIPT_VERSION,
            "mode": self.runner_mode,
            "entries": entries,
        }
        self.cache_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        self.dirty = False

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self.log_summary()
        self.save()
        self._stop_session(reason="runner close")
        self._stop_shared_field_server()

    def log_summary(self) -> None:
        if self.summary_logged:
            return
        self.summary_logged = True
        elapsed = time.monotonic() - self.started_at
        regenerated = self.screenshots_run + self.videos_run
        skipped = self.cache_hits + self.assets_reused + self.missing_assets + len(self.failures)
        LOG.info(
            "Field docs runner summary: regenerated=%s (screenshots=%s videos=%s), "
            "skipped=%s (cache_hits=%s reused_existing=%s missing=%s failures=%s), "
            "server_starts=%s server_restarts=%s elapsed=%.1fs",
            regenerated,
            self.screenshots_run,
            self.videos_run,
            skipped,
            self.cache_hits,
            self.assets_reused,
            self.missing_assets,
            len(self.failures),
            self.server_starts,
            self.server_restarts,
            elapsed,
        )
        if self.failures:
            # Distinguish honestly: fallbacks still render a real (committed) image;
            # broken failures leave the HTML pointing at a missing asset.
            LOG.warning(
                "Field docs runner had %s capture failure(s): %s used a committed fallback, "
                "%s left NO asset (broken HTML reference). Per-failure diagnoses saved under %s.",
                len(self.failures),
                len(self.fallback_failures),
                len(self.broken_failures),
                self.shared_log_path.parent / "docs-runner-failures",
            )
            for message in self.broken_failures:
                LOG.error("Field docs runner BROKEN asset (no fallback): %s", message)

    def _run_capture_child(
        self,
        command: list[str],
        *,
        options: RunnerOptions,
        label: str,
        is_video: bool,
        input_text: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """Run one backdoor capture with process-group and exit-grace safety."""
        expected_results = command.count("--command")

        def commands_complete(stdout: str) -> bool:
            return len(parse_backdoor_results(stdout)) >= expected_results

        outer_timeout = runner_subprocess_timeout(options=options, is_video=is_video)
        completed, duration_ms, timed_out = run_with_watchdog(
            command,
            cwd=self.repo_root,
            timeout=outer_timeout,
            heartbeat_interval=8.0,
            heartbeat=lambda message: LOG.info("Field docs runner%s", message),
            label=label,
            completion_probe=commands_complete,
            exit_grace=10.0,
            input_text=input_text,
        )
        if timed_out:
            emitted = len(parse_backdoor_results(completed.stdout))
            reason = (
                "all command results arrived, but field_backdoor.py did not exit within 10s"
                if emitted >= expected_results
                else f"only {emitted}/{expected_results} command results arrived before the outer limit"
            )
            stderr = completed.stderr + f"\nDocs capture watchdog: {reason}.\n"
            raise subprocess.TimeoutExpired(
                command,
                duration_ms / 1000.0,
                output=completed.stdout,
                stderr=stderr,
            )
        return completed

    def ensure_video(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """Record a short video clip instead of taking a screenshot."""
        assert options.video is not None
        # Docs videos historically always recorded the editor pane. Respect a fence
        # that explicitly asks for a different surface — a box output-area card
        # (capture="box"), a native window (capture="native-window"), or a window —
        # so, e.g., a live GPU-probe card can be recorded directly. Fences that do not
        # name a video surface still record the editor.
        video_capture = options.capture if options.capture in {"box", "window", "native-window"} else "editor"
        video_options = replace(options, capture=video_capture)
        video_path = resolve_output_path(page_path, options.video)
        video_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        cache_metadata = compute_cache_metadata(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code,
            options=video_options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
            scene_inputs_digest=scene_inputs,
        )
        cache_key = str(video_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and video_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit (video): %s", video_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=video_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing video asset after runner-only cache change: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and video_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in video asset without cache entry: %s", video_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s (video): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=video_path,
                output_exists=video_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        if video_options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context video for {page_path.name} -> {video_path}"
            )
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=video_path,
            options=video_options,
            is_video=True,
        ).process_command
        LOG.info("Field docs runner recording video %s for %s", page_path.name, video_path)
        completed: subprocess.CompletedProcess[str] | None = None
        for attempt in range(1, RUNNER_RETRIES + 1):
            try:
                completed = self._run_capture_child(
                    command,
                    options=video_options,
                    label=f"video {video_path.name}",
                    is_video=True,
                    input_text=code,
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning(
                        "Field docs runner video attempt %s/%s timed out for %s -> %s; retrying\n%s",
                        attempt,
                        RUNNER_RETRIES,
                        page_path.name,
                        video_path,
                        format_timeout_failure_details(
                            exc,
                            command=command,
                            field_log_path=self.shared_log_path,
                        ),
                    )
                    self._restart_shared_field_server(
                        reason=f"timeout while recording {page_path.name} -> {video_path}"
                    )
                    continue
                self._restart_shared_field_server(
                    reason=f"final timeout while recording {page_path.name} -> {video_path}"
                )
                raise RuntimeError(
                    f"Field docs runner video timed out for {page_path.name} -> {video_path}.\n"
                    f"Command: {shlex.join(command)}\n"
                    f"Timed out after {exc.timeout:.1f}s"
                ) from exc
            deterministic = deterministic_execution_error(
                completed, page_path=page_path, output_path=video_path, command=command
            )
            if deterministic is not None:
                diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
                self._restart_shared_field_server(
                    reason=f"execution error while recording {page_path.name} -> {video_path}"
                )
                raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning(
                    "Field docs runner video attempt %s/%s failed for %s -> %s; retrying\n%s",
                    attempt,
                    RUNNER_RETRIES,
                    page_path.name,
                    video_path,
                    format_completed_failure_details(
                        completed,
                        command=command,
                        field_log_path=self.shared_log_path,
                    ),
                )
                self._restart_shared_field_server(
                    reason=f"failed video capture attempt {attempt}/{RUNNER_RETRIES} for {page_path.name} -> {video_path}"
                )
            else:
                self._restart_shared_field_server(
                    reason=f"final failed video capture attempt for {page_path.name} -> {video_path}"
                )
        assert completed is not None
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
            raise RuntimeError(
                f"Field docs runner video failed for {page_path.name} -> {video_path}.\n"
                f"{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=video_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}\n\n{diagnosis}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=video_options)
        if output_gap is not None:
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {video_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.videos_run += 1
        self.save()

    def ensure_audio(self, *, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """audio="…": OFFLINE-render a registered synth scene to a real 16-bit PCM WAV.

        Parallel to `ensure_video`, but the "capture" is a deterministic, headless
        DSP render rather than a browser recording. The runner generates the box code
        (`DoughDspSoundDocs.renderWav("<scene>", "<abs wav>")`), runs it in the shared
        Field JVM, and `wait-file`s the WAV into existence (with --fail-on-error-uid so
        a box error surfaces fast). No realtime engine, no audio device, no browser —
        the WAV is produced by the same offline block-render the plot gallery uses, so
        the committed asset regenerates byte-stably at docs-build.
        """
        assert options.audio is not None and options.audio_spec is not None
        audio_path = resolve_output_path(page_path, options.audio)
        audio_path.parent.mkdir(parents=True, exist_ok=True)

        # The box code the runner generates: an offline render straight to the WAV path.
        generated_code = (
            "afield.four.synth.DoughDspSoundDocs.renderWav("
            f"{json.dumps(options.audio_spec)}, {json.dumps(str(audio_path))})"
        )

        cache_allowed = self.runner_mode != "always"
        cache_metadata = compute_cache_metadata(
            code=generated_code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
        )
        digest = compute_digest(
            code=generated_code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
        )
        cache_key = str(audio_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and audio_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit (audio): %s", audio_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=audio_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing audio asset after runner-only cache change: %s", audio_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and audio_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in audio asset without cache entry: %s", audio_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s (audio): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=audio_path,
                output_exists=audio_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )

        command = build_audio_backdoor_command(
            repo_root=self.repo_root,
            audio_path=audio_path,
            options=options,
            newer_than=time.time(),
        )
        LOG.info("Field docs runner rendering audio %s -> %s (scene %r)", page_path.name, audio_path, options.audio_spec)
        completed: subprocess.CompletedProcess[str] | None = None
        for attempt in range(1, RUNNER_RETRIES + 1):
            try:
                completed = self._run_capture_child(
                    command,
                    options=options,
                    label=f"audio {audio_path.name}",
                    is_video=False,
                    input_text=generated_code,
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning(
                        "Field docs runner audio attempt %s/%s timed out for %s -> %s; retrying",
                        attempt, RUNNER_RETRIES, page_path.name, audio_path,
                    )
                    self._restart_shared_field_server(reason=f"timeout while rendering audio {audio_path}")
                    continue
                self._restart_shared_field_server(reason=f"final timeout while rendering audio {audio_path}")
                raise RuntimeError(
                    f"Field docs runner audio timed out for {page_path.name} -> {audio_path}.\n"
                    f"Command: {shlex.join(command)}\nTimed out after {exc.timeout:.1f}s"
                ) from exc
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning(
                    "Field docs runner audio attempt %s/%s failed for %s -> %s; retrying\n%s",
                    attempt, RUNNER_RETRIES, page_path.name, audio_path,
                    format_completed_failure_details(completed, command=command, field_log_path=self.shared_log_path),
                )
                self._restart_shared_field_server(
                    reason=f"failed audio render attempt {attempt}/{RUNNER_RETRIES} for {audio_path}"
                )
            else:
                self._restart_shared_field_server(reason=f"final failed audio render attempt for {audio_path}")
        assert completed is not None
        if completed.returncode != 0:
            details = format_completed_failure_details(completed, command=command, field_log_path=self.shared_log_path)
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=audio_path)
            raise RuntimeError(
                f"Field docs runner audio failed for {page_path.name} -> {audio_path}.\n{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=audio_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {audio_path}.\n"
                f"Command: {shlex.join(command)}\n{execution_error}\n\n{diagnosis}"
            )
        # A real WAV must exist and carry a valid RIFF/WAVE header with a non-empty
        # data chunk — a zero-length or headerless file is a failed capture.
        if not audio_path.exists():
            raise RuntimeError(f"Field docs runner audio produced no file for {page_path.name} -> {audio_path}.")
        validate_wav_file(audio_path)
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.videos_run += 1
        self.save()

    def ensure_ui_drive(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        """capture="ui": drive the live editor with the fence's Kotlin script and
        capture a video and/or a still in one run. The `drive` transcript op does the
        recording + screenshotting itself, so both outputs come from a single Field run.
        """
        outputs: list[Path] = []
        if options.video:
            outputs.append(resolve_output_path(page_path, options.video))
        if options.screenshot:
            outputs.append(resolve_output_path(page_path, options.screenshot))
        if not outputs:
            raise RuntimeError(f"{page_path.name} capture=ui requires screenshot= and/or video=")
        for out in outputs:
            out.parent.mkdir(parents=True, exist_ok=True)
        primary = outputs[0]

        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        cache_metadata = compute_cache_metadata(
            code=code, options=options, page_path=page_path, block_index=block_index,
            runner_source_digest=self.runner_source_digest, scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code, options=options, page_path=page_path, block_index=block_index,
            runner_mode=self.runner_mode, runner_source_digest=self.runner_source_digest,
            scene_inputs_digest=scene_inputs,
        )
        cache_key = str(primary)
        cached_digest = self.cache.get(cache_key)
        if (cache_allowed and not options.force and cached_digest == digest
                and all(out.exists() for out in outputs)):
            LOG.info("Field docs runner cache hit (ui drive): %s", primary)
            self.cache_hits += 1
            return
        LOG.info(
            "Field docs runner cache %s (ui drive): %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            primary,
        )
        command = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=primary,
            options=options,
            code=code,
        ).process_command
        LOG.info("Field docs runner driving UI %s for %s", page_path.name, primary)
        completed: subprocess.CompletedProcess[str] | None = None
        for attempt in range(1, RUNNER_RETRIES + 1):
            try:
                completed = self._run_capture_child(
                    command,
                    options=options,
                    label=f"ui drive {primary.name}",
                    is_video=True,
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning("Field docs runner ui drive attempt %s/%s timed out for %s; retrying",
                                attempt, RUNNER_RETRIES, primary)
                    self._restart_shared_field_server(reason=f"timeout ui drive {primary}")
                    continue
                self._restart_shared_field_server(reason=f"final timeout ui drive {primary}")
                raise RuntimeError(
                    f"Field docs runner ui drive timed out for {page_path.name} -> {primary}.\n"
                    f"Command: {shlex.join(command)}\nTimed out after {exc.timeout:.1f}s"
                ) from exc
            deterministic = deterministic_execution_error(
                completed, page_path=page_path, output_path=primary, command=command
            )
            if deterministic is not None:
                diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
                self._restart_shared_field_server(reason=f"execution error ui drive {primary}")
                raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning("Field docs runner ui drive attempt %s/%s failed for %s; retrying\n%s",
                            attempt, RUNNER_RETRIES, primary,
                            format_completed_failure_details(completed, command=command, field_log_path=self.shared_log_path))
                self._restart_shared_field_server(reason=f"failed ui drive attempt {attempt} for {primary}")
            else:
                self._restart_shared_field_server(reason=f"final failed ui drive attempt for {primary}")
        assert completed is not None
        if completed.returncode != 0:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
            raise RuntimeError(
                f"Field docs runner ui drive failed for {page_path.name} -> {primary}.\n"
                f"{format_completed_failure_details(completed, command=command, field_log_path=self.shared_log_path)}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=primary)
            raise RuntimeError(
                f"Field docs runner ui drive execution error for {page_path.name} -> {primary}.\n"
                f"Command: {shlex.join(command)}\n{execution_error}\n\n{diagnosis}"
            )
        missing = [str(out) for out in outputs if not out.exists()]
        if missing:
            raise RuntimeError(
                f"Field docs runner ui drive produced no output for {page_path.name}: missing {', '.join(missing)}.\n"
                f"Command: {shlex.join(command)}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.videos_run += 1
        self.save()

    def ensure_screenshot(self, *, code: str, options: RunnerOptions, page_path: Path, block_index: int) -> None:
        screenshot_path = resolve_output_path(page_path, options.screenshot)
        screenshot_path.parent.mkdir(parents=True, exist_ok=True)
        cache_allowed = self.runner_mode != "always"
        scene_inputs = compute_scene_inputs_digest(self.repo_root, options.fabricate_scene)
        cache_metadata = compute_cache_metadata(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_source_digest=self.runner_source_digest,
            scene_inputs_digest=scene_inputs,
        )
        digest = compute_digest(
            code=code,
            options=options,
            page_path=page_path,
            block_index=block_index,
            runner_mode=self.runner_mode,
            runner_source_digest=self.runner_source_digest,
            scene_inputs_digest=scene_inputs,
        )
        cache_key = str(screenshot_path)
        cached_digest = self.cache.get(cache_key)
        cached_metadata = self.cache_metadata.get(cache_key)
        if cache_allowed and not options.force and screenshot_path.exists() and cached_digest == digest:
            LOG.info("Field docs runner cache hit: %s", screenshot_path)
            self.cache_hits += 1
            return
        if reuse_existing_asset_for_incremental(
            runner_mode=self.runner_mode,
            output_path=screenshot_path,
            options=options,
            cached_metadata=cached_metadata,
            current_metadata=cache_metadata,
        ):
            LOG.info("Field docs runner using existing screenshot asset after runner-only cache change: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        if cache_allowed and not options.force and self.runner_mode == "incremental" and screenshot_path.exists() and cached_digest is None:
            LOG.info("Field docs runner using existing checked-in screenshot asset without cache entry: %s", screenshot_path)
            self.assets_reused += 1
            self.cache[cache_key] = digest
            self.cache_metadata[cache_key] = cache_metadata
            self.dirty = True
            self.save()
            return
        LOG.info(
            "Field docs runner cache %s: %s",
            cache_status_label(cache_allowed=cache_allowed, force=options.force),
            format_cache_reason(
                output_path=screenshot_path,
                output_exists=screenshot_path.exists(),
                cached_digest=cached_digest,
                computed_digest=digest,
                cached_metadata=cached_metadata,
                current_metadata=cache_metadata,
                cache_allowed=cache_allowed,
                force=options.force,
                runner_mode=self.runner_mode,
            ),
        )
        source_image_path = (
            resolve_auxiliary_output_path(page_path, options.source_image)
            if options.source_image
            else None
        )
        source_image_min_mtime: float | None = None
        if source_image_path is not None:
            if path_is_relative_to(source_image_path, self.docs_dir):
                source_image_min_mtime = time.time()
            else:
                try:
                    source_image_path.unlink()
                except FileNotFoundError:
                    pass
        if not options.graphics_context and self._captures_since_server_restart >= 50:
            self._restart_shared_field_server(
                reason=f"periodic restart after {self._captures_since_server_restart} captures"
            )
        if options.graphics_context:
            self._ensure_runner_owned_field_server(
                reason=f"graphics-context capture for {page_path.name} -> {screenshot_path}"
            )
        invocation = build_backdoor_command(
            repo_root=self.repo_root,
            page_path=page_path,
            block_index=block_index,
            screenshot_path=screenshot_path,
            options=options,
            source_image_path=source_image_path,
            source_image_min_mtime=source_image_min_mtime,
        )
        command = invocation.process_command
        self.captures_started += 1
        LOG.info(
            "Field docs runner [%d] capturing %s%s -> %s",
            self.captures_started,
            page_path.name,
            " (graphics-context)" if options.graphics_context else "",
            screenshot_path.name,
        )
        capture_label = f"{page_path.name} -> {screenshot_path.name}"
        completed: subprocess.CompletedProcess[str] | None = None
        # Warm-session fast path (opt-in): reuse one persistent daemon + attached
        # page across non-graphics captures. On any failure, fall through to the
        # unchanged per-process retry loop below so correctness never regresses.
        if self.session_enabled and invocation.session_eligible:
            completed = self._try_session_capture(
                page_path=page_path,
                block_index=block_index,
                screenshot_path=screenshot_path,
                options=options,
                source_image_path=source_image_path,
                source_image_min_mtime=source_image_min_mtime,
                code=code,
            )
            if completed is not None:
                LOG.info("Field docs runner warm-session capture ok: %s", screenshot_path)
        for attempt in range(1, RUNNER_RETRIES + 1):
            if completed is not None:
                break
            try:
                completed = self._run_capture_child(
                    command,
                    options=options,
                    label=capture_label,
                    is_video=False,
                    input_text=code,
                )
            except subprocess.TimeoutExpired as exc:
                if attempt < RUNNER_RETRIES:
                    LOG.warning(
                        "Field docs runner attempt %s/%s timed out for %s -> %s; retrying\n%s",
                        attempt,
                        RUNNER_RETRIES,
                        page_path.name,
                        screenshot_path,
                        format_timeout_failure_details(
                            exc,
                            command=command,
                            field_log_path=self.shared_log_path,
                        ),
                    )
                    self._restart_shared_field_server(
                        reason=f"timeout while capturing {page_path.name} -> {screenshot_path}"
                    )
                    continue
                self._restart_shared_field_server(
                    reason=f"final timeout while capturing {page_path.name} -> {screenshot_path}"
                )
                raise RuntimeError(
                    f"Field docs runner timed out for {page_path.name} -> {screenshot_path}.\n"
                    f"Command: {shlex.join(command)}\n"
                    f"Timed out after {exc.timeout:.1f}s"
                ) from exc
            deterministic = deterministic_execution_error(
                completed, page_path=page_path, output_path=screenshot_path, command=command
            )
            if deterministic is not None:
                # Diagnose BEFORE restarting: the restart reopens shared_log_path in
                # "w" mode, erasing the crashed server's log. Capture both ends first.
                diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
                self._restart_shared_field_server(
                    reason=f"execution error while capturing {page_path.name} -> {screenshot_path}"
                )
                raise RuntimeError(f"{deterministic}\n\n{diagnosis}")
            if completed.returncode == 0:
                break
            if attempt < RUNNER_RETRIES:
                LOG.warning(
                    "Field docs runner attempt %s/%s failed for %s -> %s; retrying\n%s",
                    attempt,
                    RUNNER_RETRIES,
                    page_path.name,
                    screenshot_path,
                    format_completed_failure_details(
                        completed,
                        command=command,
                        field_log_path=self.shared_log_path,
                    ),
                )
                self._restart_shared_field_server(
                    reason=f"failed capture attempt {attempt}/{RUNNER_RETRIES} for {page_path.name} -> {screenshot_path}"
                )
            else:
                self._restart_shared_field_server(
                    reason=f"final failed capture attempt for {page_path.name} -> {screenshot_path}"
                )
        assert completed is not None
        if completed.returncode != 0:
            details = format_completed_failure_details(
                completed,
                command=command,
                field_log_path=self.shared_log_path,
            )
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
            raise RuntimeError(
                f"Field docs runner failed for {page_path.name} -> {screenshot_path}.\n"
                f"{details}\n\n{diagnosis}"
            )
        execution_error = extract_backdoor_execution_error(completed.stdout)
        if execution_error is not None:
            diagnosis = self.diagnose_capture_failure(page_path=page_path, output_path=screenshot_path)
            raise RuntimeError(
                f"Field docs runner execution error for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{execution_error}\n\n{diagnosis}"
            )
        output_gap = extract_backdoor_output_gap(stdout=completed.stdout, code=code, options=options)
        if output_gap is not None:
            field_log = tail_text(self.shared_log_path, max_lines=80)
            if field_log:
                output_gap = f"{output_gap}\n\nField server log tail:\n{field_log}"
            raise RuntimeError(
                f"Field docs runner output validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{output_gap}"
            )
        if options.source_image:
            copy_source_image(
                source_path=resolve_auxiliary_output_path(page_path, options.source_image),
                destination_path=screenshot_path,
                timeout_sec=options.timeout,
                min_mtime=source_image_min_mtime,
            )
        trim_native_graphics_screenshot(screenshot_path=screenshot_path, options=options)
        visual_gap = validate_screenshot_pixels(screenshot_path=screenshot_path, options=options)
        if visual_gap is not None:
            raise RuntimeError(
                f"Field docs runner visual validation failed for {page_path.name} -> {screenshot_path}.\n"
                f"Command: {shlex.join(command)}\n"
                f"{visual_gap}"
            )
        self.cache[cache_key] = digest
        self.cache_metadata[cache_key] = cache_metadata
        self.dirty = True
        self.screenshots_run += 1
        self._captures_since_server_restart += 1
        self.save()
        if options.graphics_context:
            # Restart to leave a fresh server for the NEXT graphics-context capture --
            # this is leave-it-clean cleanup, not correctness-critical for the capture we
            # just finished: it already succeeded and its asset is on disk. The next
            # graphics-context capture's leading `_ensure_runner_owned_field_server`
            # re-establishes the server if this restart leaves it down, and the final
            # capture of a run has no successor to serve. So a slow/failed restart here
            # must never abort the build (which would discard every capture already made).
            try:
                self._restart_shared_field_server(
                    reason=f"completed graphics-context capture for {page_path.name} -> {screenshot_path}"
                )
            except RuntimeError as exc:
                LOG.warning(
                    "Field docs runner post-capture server restart failed (non-fatal; "
                    "next graphics-context capture will re-establish the server): %s",
                    exc,
                )


STATE: RunnerState | None = None


def on_post_build(config: Any) -> None:
    del config
    if STATE is not None:
        if STATE.failures:
            LOG.warning("Field docs runner skipped %s asset(s) due to capture failures", len(STATE.failures))
            for failure in STATE.failures:
                LOG.warning("Field docs runner skipped asset detail: %s", failure)
        STATE.save()
        STATE.close()


def ensure_state(config: Any) -> RunnerState:
    global STATE
    if STATE is None:
        STATE = RunnerState(config)
    return STATE


def process_markdown(*, markdown: str, page_path: Path, state: RunnerState, files: Any) -> str:
    lines = markdown.splitlines(keepends=True)
    out: list[str] = []
    block_index = 0
    passthrough_fence: str | None = None
    previous_code_by_language: dict[str, str] = {}
    i = 0
    while i < len(lines):
        line = lines[i]
        if passthrough_fence is not None:
            out.append(line)
            if re.match(rf"^[ ]{{0,3}}{re.escape(passthrough_fence)}\s*(?:\r?\n)?$", line):
                passthrough_fence = None
            i += 1
            continue

        open_match = FENCE_OPEN_RE.match(line)
        if open_match is None:
            out.append(line)
            generic_match = GENERIC_FENCE_OPEN_RE.match(line)
            if generic_match is not None:
                passthrough_fence = generic_match.group("fence")
            i += 1
            continue

        start_line = i + 1
        block_index += 1
        open_line = line
        fence_name = open_match.group("runner")
        i += 1
        body: list[str] = []
        closing_line: str | None = None
        closing_re = re.compile(rf"^[ ]{{0,3}}{re.escape(open_match.group('fence'))}\s*(?:\r?\n)?$")
        while i < len(lines):
            candidate = lines[i]
            if closing_re.match(candidate):
                closing_line = candidate
                i += 1
                break
            body.append(candidate)
            i += 1
        if closing_line is None:
            raise RuntimeError(f"Unclosed {fence_name} fence in {page_path}:{start_line}")

        language_key = fence_language_key(open_line=open_line, fence_name=fence_name)
        previous_code = previous_code_by_language.get(language_key)
        rewritten_body = maybe_inherit_before(
            lines=body,
            fence_name=fence_name,
            previous_code=previous_code,
        )

        if fence_name in RUNNER_FENCES:
            options = parse_runner_options(
                runner_name=fence_name,
                open_line=open_line,
                page_path=page_path,
                line_number=start_line,
                block_index=block_index,
            )
            code = extract_runnable_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
                options=options,
            )
            code = wrap_runnable_code_for_language(code=code, language_key=language_key)
            options = maybe_enable_output_wait(options=options, code=code)
            runner_code = code_with_macro_digest(
                code=code,
                options=options,
                repo_root=state.repo_root,
                page_path=page_path,
            )
            screenshot_succeeded = False
            video_succeeded = False
            audio_succeeded = False
            if options.capture == "ui":
                # One drive run produces the video and/or the still together.
                ui_screenshot_path = resolve_output_path(page_path, options.screenshot) if options.screenshot else None
                ui_video_path = resolve_output_path(page_path, options.video) if options.video else None
                if state.runner_mode == "never":
                    screenshot_succeeded = bool(ui_screenshot_path and ui_screenshot_path.exists())
                    video_succeeded = bool(ui_video_path and ui_video_path.exists())
                    for ok, path in ((screenshot_succeeded, ui_screenshot_path), (video_succeeded, ui_video_path)):
                        if path is None:
                            continue
                        state.assets_reused += 1 if ok else 0
                        state.missing_assets += 0 if ok else 1
                else:
                    primary_ui_path = ui_video_path or ui_screenshot_path
                    try:
                        state.ensure_ui_drive(code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        screenshot_succeeded = bool(options.screenshot)
                        video_succeeded = bool(options.video)
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=primary_ui_path, error=exc)
                site_dir = Path(state.config["site_dir"]).resolve()
                use_dir_urls = bool(state.config["use_directory_urls"])
                if screenshot_succeeded and ui_screenshot_path:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=site_dir, use_directory_urls=use_dir_urls, output_path=ui_screenshot_path)
                if video_succeeded and ui_video_path:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=site_dir, use_directory_urls=use_dir_urls, output_path=ui_video_path)
            elif options.screenshot:
                screenshot_path = resolve_output_path(page_path, options.screenshot)
                if state.runner_mode == "never":
                    screenshot_succeeded = screenshot_path.exists()
                    if screenshot_succeeded:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s screenshot asset %s",
                        "using existing" if screenshot_succeeded else "skipping missing",
                        screenshot_path,
                    )
                else:
                    try:
                        state.ensure_screenshot(code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        screenshot_succeeded = True
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=screenshot_path, error=exc)
                if screenshot_succeeded:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=screenshot_path)
            if options.video and options.capture != "ui":
                video_path = resolve_output_path(page_path, options.video)
                if state.runner_mode == "never":
                    video_succeeded = video_path.exists()
                    if video_succeeded:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s video asset %s",
                        "using existing" if video_succeeded else "skipping missing",
                        video_path,
                    )
                else:
                    try:
                        state.ensure_video(code=runner_code, options=options, page_path=page_path, block_index=block_index)
                        video_succeeded = True
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=video_path, error=exc)
                if video_succeeded:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=video_path)
            if options.audio and options.capture != "ui":
                audio_path = resolve_output_path(page_path, options.audio)
                if state.runner_mode == "never":
                    audio_succeeded = audio_path.exists()
                    if audio_succeeded:
                        state.assets_reused += 1
                    else:
                        state.missing_assets += 1
                    LOG.info(
                        "Field docs runner mode=never: %s audio asset %s",
                        "using existing" if audio_succeeded else "skipping missing",
                        audio_path,
                    )
                else:
                    try:
                        state.ensure_audio(options=options, page_path=page_path, block_index=block_index)
                        audio_succeeded = True
                    except RuntimeError as exc:
                        if not state.continue_on_error:
                            raise
                        state.handle_capture_error(page_path=page_path, output_path=audio_path, error=exc)
                if audio_succeeded:
                    register_generated_file(files=files, docs_dir=state.docs_dir, site_dir=Path(state.config["site_dir"]).resolve(), use_directory_urls=bool(state.config["use_directory_urls"]), output_path=audio_path)

        hide_source = fence_name in RUNNER_FENCES and options.hidden
        if not hide_source:
            out.append(open_line.replace(fence_name, RUNNER_TARGETS[fence_name], 1))
            out.extend(rewritten_body)
            out.append(closing_line)
        if fence_name in RUNNER_FENCES and options.embed:
            if options.screenshot and screenshot_succeeded:
                out.append("\n")
                out.append(f"![{options.alt or options.box_name}]({markdown_path_for(page_path, options.screenshot)})\n")
            if options.video and video_succeeded:
                # Emit a docs-root-relative path (e.g. "assets/foo.mp4"), the same
                # convention used for the screenshot embed above. zensical rewrites
                # raw-HTML src attributes for the page's URL depth just like it does
                # markdown links; handing it an already page-relative "../assets/..."
                # gets double-rewritten to "../../assets/..." and 404s on a site
                # served under a base path (e.g. /field4/).
                video_rel = markdown_path_for(page_path, options.video)
                out.append("\n")
                out.append(f'<video controls autoplay loop muted playsinline style="max-width:100%">\n')
                out.append(f'  <source src="{video_rel}" type="video/mp4">\n')
                out.append(f'</video>\n')
            if options.audio and audio_succeeded:
                # Page-relative path, same convention as the screenshot/video embeds above;
                # zensical rewrites raw-HTML src attributes for the page's URL depth.
                audio_rel = markdown_path_for(page_path, options.audio)
                out.append("\n")
                out.append('<audio controls preload="metadata" style="width:100%">\n')
                out.append(f'  <source src="{audio_rel}" type="audio/wav">\n')
                out.append('</audio>\n')

        if not hide_source:
            previous_code_by_language[language_key] = extract_effective_code(
                lines=rewritten_body,
                fence_name=fence_name,
                page_path=page_path,
                line_number=start_line,
            )
        continue

    return "".join(out)


def parse_runner_options(*, runner_name: str, open_line: str, page_path: Path, line_number: int, block_index: int) -> RunnerOptions:
    raw_attrs = open_line.split(runner_name, 1)[1].strip()
    attrs = parse_attrs(raw_attrs)
    screenshot = first_attr(attrs, "screenshot", "path")
    source_image = first_attr(attrs, "source-image", "source_image", "native-image", "native_image")
    video = first_attr(attrs, "video")
    audio = first_attr(attrs, "audio")
    audio_spec = first_attr(attrs, "audio-spec", "audio_spec", "audioSpec", "sound", "scene")
    if not screenshot and not video and not audio:
        raise RuntimeError(f"{page_path}:{line_number} {runner_name} requires screenshot=\"...\", video=\"...\" and/or audio=\"...\"")
    if audio and not audio_spec:
        raise RuntimeError(
            f"{page_path}:{line_number} audio=\"...\" requires audio-spec=\"<scene>\" "
            f"(a registered afield.four.synth.DoughDspSoundDocs scene)"
        )

    capture = (first_attr(attrs, "capture", "mode") or "box").strip().lower()
    if capture not in {"box", "window", "window-editor", "editor", "native-window", "ui"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported capture={capture!r}")

    # capture="ui" runs the fence body as a `drive` transcript that scripts the live
    # editor (keypresses/clicks) via Playwright and captures a video and/or still. The
    # region= attribute selects what the recording/still is clipped to.
    region = (first_attr(attrs, "region") or "window-editor").strip().lower()
    if region not in {"box", "window", "window-editor", "editor"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported region={region!r}")

    execution = (first_attr(attrs, "execution", "execute", "run-mode") or "run").strip().lower()
    if execution not in {"run", "launch"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported execution={execution!r}")

    with_editor = capture == "window-editor" or parse_bool(first_attr(attrs, "with-editor", "with_editor"), default=False)
    open_outputs = parse_bool(first_attr(attrs, "open-outputs", "open_outputs"), default=True)
    wait_output = parse_bool(first_attr(attrs, "wait-output", "wait_output", "waitForOutput", "wait-for-output"), default=False)
    wait_output_kind = (first_attr(attrs, "wait-output-kind", "wait_output_kind") or "display").strip().lower()
    if wait_output_kind not in {"inline", "display", "all"}:
        raise RuntimeError(f"{page_path}:{line_number} unsupported wait-output-kind={wait_output_kind!r}")

    if capture == "window-editor":
        default_box_w = 1200.0
        default_box_h = 720.0
    elif capture == "window":
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0
    elif capture == "editor":
        default_box_w = 820.0
        default_box_h = 720.0
    else:
        # Keep box captures compact by default; output-aware clipping handles content sizing.
        if open_outputs:
            default_box_w = 960.0
            default_box_h = 180.0
        else:
            default_box_w = 420.0
            default_box_h = 120.0

    return RunnerOptions(
        screenshot=screenshot,
        source_image=source_image,
        capture=capture,
        execution=execution,
        wait=parse_float(first_attr(attrs, "wait"), default=0.75),
        padding=parse_float(first_attr(attrs, "padding"), default=16.0),
        timeout=parse_float(first_attr(attrs, "timeout"), default=45.0),
        property_name=first_attr(attrs, "property") or "code",
        embed=parse_bool(first_attr(attrs, "embed"), default=False),
        alt=first_attr(attrs, "alt"),
        document=first_attr(attrs, "document"),
        source_box=first_attr(attrs, "source-box", "source_box"),
        source_box_properties=parse_box_list(first_attr(attrs, "source-box-properties", "source_box_properties", "source-properties", "source_properties")),
        source_box_wait=parse_float(first_attr(attrs, "source-box-wait", "source_box_wait"), default=0.0),
        run_box=first_attr(attrs, "run-box", "run_box", "box-uid", "box_uid", "uid"),
        capture_box=first_attr(attrs, "capture-box", "capture_box", "target-box", "target_box", "target-uid", "target_uid"),
        setup_boxes=parse_box_list(first_attr(attrs, "setup-boxes", "setup_boxes", "setup")),
        macro_file=first_attr(attrs, "macro-file", "macro_file", "transcript-file", "transcript_file", "transcript"),
        macro_name=first_attr(attrs, "macro-name", "macro_name", "transcript-name", "transcript_name"),
        macro_selection=parse_box_list(first_attr(attrs, "macro-selection", "macro_selection", "selection")),
        fabricate_scene=parse_fabricate_scene(
            first_attr(attrs, "fabricate-scene", "fabricate_scene", "fabricateScene"),
            source_image=source_image,
            page_path=page_path,
            line_number=line_number,
        ),
        native_wait_frames=int(parse_float(first_attr(attrs, "native-wait-frames", "native_wait_frames"), default=30.0)),
        trim_native=parse_bool(first_attr(attrs, "trim-native", "trim_native", "native-trim", "native_trim", "auto-trim", "auto_trim"), default=False),
        box_name=first_attr(attrs, "name") or f"{page_path.stem}-{block_index}",
        box_x=parse_float(first_attr(attrs, "x"), default=24.0),
        box_y=parse_float(first_attr(attrs, "y"), default=24.0),
        box_w=parse_float(first_attr(attrs, "w"), default=default_box_w),
        box_h=parse_float(first_attr(attrs, "h"), default=default_box_h),
        open_outputs=open_outputs,
        wait_output=wait_output,
        wait_output_kind=wait_output_kind,
        wait_output_channel=first_attr(attrs, "wait-output-channel", "wait_output_channel"),
        wait_output_contains=first_attr(attrs, "wait-output-contains", "wait_output_contains", "output-contains", "contains"),
        with_editor=with_editor,
        graphics_context=parse_bool(
            first_attr(attrs, "graphics-context", "graphics_context", "graphicsContext"),
            default=False,
        ),
        force=parse_bool(first_attr(attrs, "force"), default=False),
        video=video,
        duration=parse_float(first_attr(attrs, "duration"), default=5.0),
        fps=int(parse_float(first_attr(attrs, "fps"), default=30.0)),
        region=region,
        clip_selector=first_attr(attrs, "clip-selector", "clip_selector", "clipSelector"),
        warm_completion=parse_bool(first_attr(attrs, "warm-completion", "warm_completion", "warmCompletion"), default=False),
        audio=audio,
        audio_spec=audio_spec,
        hidden=parse_bool(first_attr(attrs, "hidden", "hide-source", "hide_source"), default=False),
        language_key=fence_language_key(open_line=open_line, fence_name=runner_name),
    )


def parse_attrs(raw: str) -> dict[str, str]:
    flattened = raw.replace("{", " ").replace("}", " ")
    attrs: dict[str, str] = {}
    for token in shlex.split(flattened):
        if token.startswith(".") or token.startswith("#"):
            continue
        if "=" in token:
            key, value = token.split("=", 1)
            attrs[key.strip()] = value.strip()
        elif token:
            attrs[token.strip()] = "true"
    return attrs


def first_attr(attrs: dict[str, str], *names: str) -> str | None:
    for name in names:
        if name in attrs:
            return attrs[name]
    return None


def parse_bool(raw: str | None, *, default: bool) -> bool:
    if raw is None:
        return default
    lowered = raw.strip().lower()
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    raise RuntimeError(f"Unsupported boolean value {raw!r}")


def parse_float(raw: str | None, *, default: float) -> float:
    if raw is None:
        return default
    return float(raw)


def parse_box_list(raw: str | None) -> tuple[str, ...]:
    if raw is None:
        return ()
    return tuple(part for part in re.split(r"[\s,]+", raw.strip()) if part)


def resolve_runner_mode(raw: str | None) -> str:
    if raw is None or not raw.strip():
        return "incremental"
    lowered = raw.strip().lower()
    resolved = RUNNER_MODE_ALIASES.get(lowered)
    if resolved is None:
        supported = ", ".join(sorted(RUNNER_MODE_ALIASES))
        raise RuntimeError(f"Unsupported {RUNNER_MODE_ENV}={raw!r}; expected one of: {supported}")
    return resolved


def resolve_continue_on_error(raw: str | None, *, runner_mode: str) -> bool:
    if raw is None or not raw.strip():
        return runner_mode == "incremental"
    return parse_bool(raw, default=runner_mode == "incremental")


def fence_language_key(*, open_line: str, fence_name: str) -> str:
    attrs = parse_attrs(open_line.split(fence_name, 1)[1].strip())
    return first_attr(attrs, "data-lang", "data-language", "lang", "language") or "__default__"


def wrap_runnable_code_for_language(*, code: str, language_key: str) -> str:
    language = language_key.strip().lower()
    if language == "nano":
        source_literal = json.dumps(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: false
import afield.nano.nanoRunReportHtml

val __nanoSource = {source_literal}

display(
    "nano",
    nanoRunReportHtml(
        source = __nanoSource,
        title = "Nano state",
        includeSource = false,
        includeDisassembly = true,
        includeProgramBytes = true,
        rngSeed = 1
    )
)
""".strip()
        return wrapped + "\n"

    if language == "forth":
        source_literal = json.dumps(code)
        expects_svg = forth_source_likely_produces_svg(code)
        wrapped = f"""
// FIELD_DOCS_EXPECT_OUTPUT_AREA: true
// FIELD_DOCS_EXPECT_SVG: {"true" if expects_svg else "false"}
import afield.docs.renderForthDocsSnippet

val __forthSource = {source_literal}
renderForthDocsSnippet(__forthSource)
""".strip()
        return wrapped + "\n"

    return code


def forth_source_likely_produces_svg(source: str) -> bool:
    lowered = source.lower()
    direct_tokens = (
        " fline",
        "\nfline",
        " moveto",
        " moveto3",
        " lineto",
        " lineto3",
        " cubicto",
        " cubicto3",
        "fline-",
        's"fline"',
        " moveto ",
        " lineto ",
        " cubicto ",
    )
    return any(token in lowered for token in direct_tokens)


def parse_fabricate_scene(
    raw_scene: str | None,
    *,
    source_image: str | None,
    page_path: Path,
    line_number: int,
) -> str | None:
    if raw_scene is None or not raw_scene.strip():
        return None
    scene = raw_scene.strip()
    if scene not in FABRICATE_DOCS_SCENES:
        supported = ", ".join(sorted(FABRICATE_DOCS_SCENES))
        raise RuntimeError(f"{page_path}:{line_number} unsupported fabricate-scene={scene!r}; expected one of: {supported}")
    if not source_image:
        raise RuntimeError(f"{page_path}:{line_number} fabricate-scene requires source-image=\"...\"")
    return scene


def fabricate_docs_harness_code(*, scene: str, output_path: Path) -> str:
    method_name, ready_message = FABRICATE_DOCS_SCENES[scene]
    output_literal = json.dumps(str(output_path))
    ready_literal = json.dumps(ready_message)
    return f"""
import afield.four.Renderer
import afield.four.fabricated.StandardRendererDocsHarness
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()

val captureScene: suspend () -> Unit = {{
    StandardRendererDocsHarness.{method_name}({output_literal}, renderer = renderer)
    println({ready_literal})
}}
start(captureScene)
""".strip() + "\n"


def extract_runnable_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int, options: RunnerOptions) -> str:
    if options.fabricate_scene:
        assert options.source_image is not None
        return fabricate_docs_harness_code(
            scene=options.fabricate_scene,
            output_path=resolve_auxiliary_output_path(page_path, options.source_image),
        )
    if fence_name == "diff_runner":
        code = extract_diff_after_code(lines=lines, page_path=page_path, line_number=line_number)
    else:
        code = extract_effective_code(lines=lines, fence_name=fence_name, page_path=page_path, line_number=line_number)
    if not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} {fence_name} fence did not contain runnable code")
    return code + "\n"


def extract_effective_code(*, lines: list[str], fence_name: str, page_path: Path, line_number: int) -> str:
    if fence_name in DIFF_FENCES:
        return extract_diff_result_code(lines=lines)
    return extract_static_code(lines=lines)


def extract_static_code(*, lines: list[str]) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    filtered = [line for line in code_lines if line.strip() not in MARKER_PREFIXES]
    return normalize_code_lines(filtered)


def extract_diff_result_code(*, lines: list[str]) -> str:
    before_lines, after_lines, _has_before, has_after = split_diff_sections(lines)
    selected = after_lines if has_after else before_lines
    return normalize_code_lines([line.rstrip("\r\n") for line in selected])


def extract_diff_after_code(*, lines: list[str], page_path: Path, line_number: int) -> str:
    code_lines = [line.rstrip("\r\n") for line in lines]
    mode = "before"
    saw_after = False
    after_lines: list[str] = []
    for line in code_lines:
        stripped = line.strip()
        if stripped == ":::before":
            mode = "before"
            continue
        if stripped == ":::after":
            mode = "after"
            saw_after = True
            continue
        if stripped in {":::static", ":::"}:
            continue
        if mode == "after":
            after_lines.append(line)

    while after_lines and not after_lines[0].strip():
        after_lines.pop(0)
    while after_lines and not after_lines[-1].strip():
        after_lines.pop()

    code = "\n".join(after_lines).rstrip()
    if not saw_after or not code.strip():
        raise RuntimeError(f"{page_path}:{line_number} diff-style fences require a non-empty :::after section")
    return code


def maybe_inherit_before(*, lines: list[str], fence_name: str, previous_code: str | None) -> list[str]:
    if fence_name not in DIFF_FENCES or previous_code is None:
        return lines

    before_lines, after_lines, has_before, has_after = split_diff_sections(lines)
    if has_before or not has_after:
        return lines
    if any(line.strip() for line in before_lines):
        return lines

    return [":::before\n", *code_to_lines(previous_code), ":::after\n", *after_lines]


def split_diff_sections(lines: list[str]) -> tuple[list[str], list[str], bool, bool]:
    before_lines: list[str] = []
    after_lines: list[str] = []
    has_before = False
    has_after = False
    mode = "before"
    for line in lines:
        stripped = line.strip()
        if stripped == ":::before":
            has_before = True
            mode = "before"
            continue
        if stripped == ":::after":
            has_after = True
            mode = "after"
            continue
        if stripped in {":::static", ":::"}:
            continue
        if mode == "after":
            after_lines.append(line)
        else:
            before_lines.append(line)
    return before_lines, after_lines, has_before, has_after


def code_to_lines(code: str) -> list[str]:
    if not code:
        return []
    return [f"{line}\n" for line in code.split("\n")]


def normalize_code_lines(lines: list[str]) -> str:
    trimmed = list(lines)
    while trimmed and not trimmed[0].strip():
        trimmed.pop(0)
    while trimmed and not trimmed[-1].strip():
        trimmed.pop()
    return "\n".join(trimmed).rstrip()


def resolve_output_path(page_path: Path, screenshot: str) -> Path:
    path = Path(screenshot)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def resolve_auxiliary_output_path(page_path: Path, path_value: str) -> Path:
    path = Path(path_value)
    if path.is_absolute():
        return path
    return (page_path.parent / path).resolve()


def markdown_path_for(page_path: Path, screenshot: str) -> str:
    absolute = resolve_output_path(page_path, screenshot)
    return Path(os.path.relpath(absolute, start=page_path.parent)).as_posix()



def register_generated_file(*, files: Any, docs_dir: Path, site_dir: Path, use_directory_urls: bool, output_path: Path) -> None:
    # Obsolete under Zensical: generated capture assets are copied into the built
    # site by tools/build_zensical_docs.py:copy_non_markdown_files, so there is no
    # live Files collection to register with (the build passes a no-op stub). Kept
    # as a no-op so the capture call sites need no change. The retired hook-based
    # build path used this to register each generated asset with the served site.
    del files, docs_dir, site_dir, use_directory_urls, output_path


def path_is_relative_to(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def compute_runner_source_digest(repo_root: Path) -> str:
    hasher = hashlib.sha256()
    for relative in RUNNER_DIGEST_SOURCE_FILES:
        path = (repo_root / relative).resolve()
        hasher.update(relative.encode("utf-8"))
        hasher.update(b"\0")
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            hasher.update(b"<missing>")
            continue
        hasher.update(hashlib.sha256(data).digest())
    return hasher.hexdigest()


def options_digest_payload(options: RunnerOptions) -> dict[str, Any]:
    payload = {
        "sourceImage": options.source_image,
        "capture": options.capture,
        "effectiveCapture": effective_capture_mode(options),
        "execution": options.execution,
        "wait": options.wait,
        "padding": options.padding,
        "timeout": options.timeout,
        "property": options.property_name,
        "document": options.document,
        "sourceBox": options.source_box,
        "sourceBoxProperties": list(options.source_box_properties),
        "sourceBoxWait": options.source_box_wait,
        "runBox": options.run_box,
        "captureBox": options.capture_box,
        "setupBoxes": list(options.setup_boxes),
        "macroFile": options.macro_file,
        "macroName": options.macro_name,
        "macroSelection": list(options.macro_selection),
        "fabricateScene": options.fabricate_scene,
        "nativeWaitFrames": options.native_wait_frames,
        "trimNative": options.trim_native,
        "boxName": options.box_name,
        "boxX": options.box_x,
        "boxY": options.box_y,
        "boxW": options.box_w,
        "boxH": options.box_h,
        "openOutputs": options.open_outputs,
        "withEditor": options.with_editor,
        "graphicsContext": options.graphics_context,
        "video": options.video,
        "duration": options.duration,
        "fps": options.fps,
        "region": options.region,
        "clipSelector": options.clip_selector,
        "warmCompletion": options.warm_completion,
    }
    if options.wait_output:
        payload.update({
            "waitOutput": True,
            "waitOutputKind": options.wait_output_kind,
            "waitOutputChannel": options.wait_output_channel,
            "waitOutputContains": options.wait_output_contains,
        })
    # Present only for audio= fences, so every other fence hashes byte-identically.
    if options.audio:
        payload["audio"] = options.audio
        payload["audioSpec"] = options.audio_spec
    return payload


def digest_string(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def digest_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf-8")).hexdigest()


def compute_cache_metadata(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_source_digest: str,
    scene_inputs_digest: str = "",
) -> dict[str, Any]:
    options_payload = options_digest_payload(options)
    metadata = {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "codeDigest": digest_string(code),
        "optionsDigest": digest_json(options_payload),
        "options": options_payload,
    }
    # Only bulk-render gallery scenes declare inputs; omitting the key otherwise
    # keeps every other fence's metadata byte-for-byte identical.
    if scene_inputs_digest:
        metadata["sceneInputs"] = scene_inputs_digest
    return metadata


def compute_digest(
    *,
    code: str,
    options: RunnerOptions,
    page_path: Path,
    block_index: int,
    runner_mode: str,
    runner_source_digest: str,
    scene_inputs_digest: str = "",
) -> str:
    payload = {
        "page": str(page_path),
        "block": block_index,
        "scriptVersion": SCRIPT_VERSION,
        "runnerSourceDigest": runner_source_digest,
        "code": code,
        "options": options_digest_payload(options),
    }
    # See compute_cache_metadata: the key is present only for gallery scenes, so
    # non-gallery fences hash to exactly the same digest as before this feature.
    if scene_inputs_digest:
        payload["sceneInputs"] = scene_inputs_digest
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def short_digest(value: str | None) -> str:
    if not value:
        return "missing"
    return value[:12]


def copy_source_image(
    *,
    source_path: Path,
    destination_path: Path,
    timeout_sec: float,
    min_mtime: float | None = None,
) -> None:
    deadline = time.monotonic() + max(timeout_sec, 1.0)
    last_size = -1
    stable_reads = 0
    while time.monotonic() < deadline:
        if source_path.exists():
            stat = source_path.stat()
            size = stat.st_size
            if size > 0:
                if min_mtime is not None and stat.st_mtime < min_mtime:
                    time.sleep(0.1)
                    continue
                if size == last_size:
                    stable_reads += 1
                else:
                    stable_reads = 0
                last_size = size
                if stable_reads >= 1:
                    destination_path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source_path, destination_path)
                    return
        time.sleep(0.1)
    raise RuntimeError(f"Timed out waiting for source image {source_path}")


def tail_text(path: Path, *, max_lines: int) -> str:
    try:
        text = path.read_text(errors="replace")
    except FileNotFoundError:
        return ""
    lines = text.splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def head_text(path: Path, *, max_lines: int) -> str:
    """First `max_lines` lines of a file (for crash-dump headers). Best-effort."""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            lines = [next_line.rstrip("\n") for _, next_line in zip(range(max_lines), handle)]
    except OSError:
        return ""
    return "\n".join(lines)


def tail_inline_text(text: str, *, max_lines: int) -> str:
    lines = text.strip().splitlines()
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def process_output_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def format_completed_failure_details(
    completed: subprocess.CompletedProcess[str],
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Return code: {completed.returncode}",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(completed.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(completed.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def format_timeout_failure_details(
    exc: subprocess.TimeoutExpired,
    *,
    command: list[str],
    field_log_path: Path | None,
    max_lines: int = 80,
) -> str:
    parts = [
        f"Timed out after {exc.timeout:.1f}s",
        f"Command: {shlex.join(command)}",
    ]
    stdout = tail_inline_text(process_output_text(exc.stdout), max_lines=max_lines)
    stderr = tail_inline_text(process_output_text(exc.stderr), max_lines=max_lines)
    if stdout:
        parts.append(f"Backdoor stdout tail before timeout:\n{stdout}")
    if stderr:
        parts.append(f"Backdoor stderr tail before timeout:\n{stderr}")
    field_log = tail_text(field_log_path, max_lines=max_lines) if field_log_path is not None else ""
    if field_log:
        parts.append(f"Field server log tail:\n{field_log}")
    return "\n\n".join(parts)


def cache_status_label(*, cache_allowed: bool, force: bool) -> str:
    if not cache_allowed:
        return "bypassed"
    if force:
        return "forced"
    return "miss"


def format_cache_reason(
    *,
    output_path: Path,
    output_exists: bool,
    cached_digest: str | None,
    computed_digest: str,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
    cache_allowed: bool,
    force: bool,
    runner_mode: str,
) -> str:
    reasons: list[str] = []
    if not cache_allowed:
        reasons.append(f"runner mode {runner_mode}")
    if force:
        reasons.append("force=true")
    if not output_exists:
        reasons.append("output file missing")
    if cached_digest is None:
        reasons.append("no cache entry")
    elif cached_digest != computed_digest:
        reasons.extend(compare_cache_metadata(cached_metadata=cached_metadata, current_metadata=current_metadata))
        reasons.append(f"digest {short_digest(cached_digest)} -> {short_digest(computed_digest)}")
    if not reasons:
        reasons.append("cache digest changed")
    return f"{output_path} ({', '.join(dict.fromkeys(reasons))})"


def compare_cache_metadata(
    *,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> list[str]:
    if not isinstance(cached_metadata, dict):
        return ["digest changed", "previous cache metadata unavailable"]

    reasons: list[str] = []
    if cached_metadata.get("scriptVersion") != current_metadata.get("scriptVersion"):
        reasons.append("script version changed")
    if cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest"):
        reasons.append("runner source changed")
    if cached_metadata.get("codeDigest") != current_metadata.get("codeDigest"):
        reasons.append("runnable code changed")
    if cached_metadata.get("optionsDigest") != current_metadata.get("optionsDigest"):
        reasons.append("capture options changed")
    if cached_metadata.get("page") != current_metadata.get("page"):
        reasons.append("page path changed")
    if cached_metadata.get("block") != current_metadata.get("block"):
        reasons.append("block index changed")
    if not reasons:
        reasons.append("digest changed")
    return reasons


def reuse_existing_asset_for_incremental(
    *,
    runner_mode: str,
    output_path: Path,
    options: RunnerOptions,
    cached_metadata: dict[str, Any] | None,
    current_metadata: dict[str, Any],
) -> bool:
    if runner_mode != "incremental" or options.force or not output_path.exists():
        return False
    if not isinstance(cached_metadata, dict):
        return False
    stable_keys = ("page", "block", "scriptVersion", "codeDigest", "optionsDigest", "sceneInputs")
    if any(cached_metadata.get(key) != current_metadata.get(key) for key in stable_keys):
        return False
    return cached_metadata.get("runnerSourceDigest") != current_metadata.get("runnerSourceDigest")


def parse_backdoor_results(stdout: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    idx = 0
    size = len(stdout)
    results: list[dict[str, Any]] = []
    while idx < size:
        while idx < size and stdout[idx].isspace():
            idx += 1
        if idx >= size:
            break
        try:
            payload, next_idx = decoder.raw_decode(stdout, idx)
        except json.JSONDecodeError:
            next_newline = stdout.find("\n", idx)
            if next_newline < 0:
                break
            idx = next_newline + 1
            continue
        if isinstance(payload, dict) and "command" in payload and "ok" in payload:
            results.append(payload)
        idx = next_idx
    return results


def deterministic_execution_error(
    completed: subprocess.CompletedProcess[str],
    *,
    page_path: Path,
    output_path: Path,
    command: list[str],
) -> RuntimeError | None:
    """A clean error to raise when a capture failed on a box compile/runtime error.

    Such an error is deterministic — retrying (with a full server restart) cannot
    fix it and only buries the diagnostic under repeated bare-"failed"/"timed out"
    messages. When the backdoor stdout carries a real execution error, return a
    RuntimeError the caller can raise immediately instead of retrying.
    """
    if completed.returncode == 0:
        return None
    # Only a *diagnosed* failure is deterministic: an error output/notify event, or a real
    # command error message — proof the box's code is broken, where retrying cannot help. A
    # bare per-command timeout (server too slow to `new-box` under load) or an opaque
    # "command failed" with no diagnostic (e.g. a transient `macro run graphics_renderer_launch`
    # hiccup after many GPU server restarts — the same launch succeeds dozens of times around
    # it) is an infrastructure hiccup a server restart + retry can recover from, exactly like
    # the outer subprocess-timeout path. Require a diagnostic so those fall through to retry.
    error = extract_backdoor_execution_error(completed.stdout, require_diagnostic=True)
    if error is None:
        return None
    return RuntimeError(
        f"Field docs runner execution error for {page_path.name} -> {output_path}.\n"
        f"Command: {shlex.join(command)}\n"
        f"{error}"
    )


def extract_backdoor_execution_error(stdout: str, *, require_diagnostic: bool = False) -> str | None:
    """Summarize execution problems found in backdoor stdout, or None if clean.

    With `require_diagnostic=True`, only *diagnosed* problems count -- a real command
    error message (`data.error`) or an error output/notify event. Bare timeouts and bare
    "command failed" results (no diagnostic) are excluded, so callers deciding whether a
    failure is deterministic (non-retryable) don't treat transient infrastructure hiccups
    as broken examples. The default (False) reports every problem, including bare ones.
    """
    results = parse_backdoor_results(stdout)
    if not results:
        return None

    shader_clear_properties = {
        "vertex",
        "fragment",
        "compute",
        "task",
        "mesh",
        "raygen",
        "miss",
        "closesthit",
        "anyhit",
        "intersection",
        "callable",
        "vertex_fragment",
        "vertex_s",
        "fragment_s",
        "compute_s",
        "task_s",
        "mesh_s",
        "raygen_s",
        "miss_s",
        "closesthit_s",
        "anyhit_s",
        "intersection_s",
        "callable_s",
    }
    problems: list[str] = []
    for result in results:
        command = str(result.get("command", "<unknown command>"))
        data = result.get("data")
        best_effort_timeout = (
            isinstance(data, dict)
            and command.startswith("stop ")
            and "--best-effort" in command
            and bool(data.get("bestEffort"))
        )
        timed_out = bool(result.get("timedOut")) and not best_effort_timeout
        if timed_out and not require_diagnostic:
            problems.append(f"{command}: timed out")
        if not bool(result.get("ok", False)):
            error_text = None
            if isinstance(data, dict):
                raw_error = data.get("error")
                if raw_error is not None:
                    error_text = str(raw_error)
            if error_text:
                # A real command error message is a genuine (diagnosed) failure.
                problems.append(f"{command}: {error_text}")
            elif not require_diagnostic:
                # A bare "command failed" with no diagnostic (opaque or timeout-only) is not
                # proof of a broken example; exclude it when a diagnostic is required so a
                # transient hiccup isn't misread as a deterministic execution error.
                problems.append(f"{command}: command failed")

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
            if name in {"output", "peer-output"}:
                if payload.get("kind") not in {"error", "err"}:
                    continue
                line = payload.get("line")
                line_suffix = f" line {line}" if line is not None else ""
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                property_name = str(payload.get("property", ""))
                if not html and (property_name in shader_clear_properties or not property_name):
                    continue
                problems.append(f"{command}{line_suffix}: {html or 'execution error'}")
                continue
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(f"{command}: {text}")

    if not problems:
        return None

    deduped = list(dict.fromkeys(problems))
    return "\n".join(deduped)


def extract_backdoor_output_gap(*, stdout: str, code: str, options: RunnerOptions) -> str | None:
    if options.graphics_context:
        return None

    results = parse_backdoor_results(stdout)
    if not results:
        return None

    events: list[dict[str, Any]] = []
    for result in results:
        result_events = result.get("events")
        if isinstance(result_events, list):
            events.extend(e for e in result_events if isinstance(e, dict))

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

    expects_svg = runner_code_expects_svg(code)
    expects_output_area = runner_code_expects_output_area(code)

    if expects_output_area and not has_output_area:
        return (
            "No output-area events were emitted (missing set-output messages). "
            "Likely causes: code did not call display/lines, output proxy failed, or execution stopped before rendering."
        )

    if expects_svg and not has_svg_output:
        channels = sorted({str(payload.get("channel", "")) for payload in set_output_events if payload.get("channel") is not None})
        channel_text = f" Channels seen: {', '.join(channels)}." if channels else ""
        return "Code appears to call lines(...) but no SVG output was observed in set-output payloads." + channel_text

    return None


def maybe_enable_output_wait(*, options: RunnerOptions, code: str) -> RunnerOptions:
    if options.graphics_context or options.wait_output:
        return options
    if not runner_code_expects_output_area(code):
        return options
    return replace(options, wait_output=True, wait_output_kind="display")


def runner_code_expects_svg(code: str) -> bool:
    expects_svg_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_SVG")
    if expects_svg_override is not None:
        return expects_svg_override
    return re.search(r"\blines\s*\(", code) is not None


def runner_code_expects_output_area(code: str) -> bool:
    expects_output_area_override = parse_codegen_expectation(code=code, key="FIELD_DOCS_EXPECT_OUTPUT_AREA")
    if expects_output_area_override is not None:
        return expects_output_area_override
    expects_display = re.search(r"\bdisplay\s*\(", code) is not None
    return runner_code_expects_svg(code) or expects_display


def validate_screenshot_pixels(*, screenshot_path: Path, options: RunnerOptions) -> str | None:
    if options.source_image:
        return None
    try:
        from PIL import Image
    except Exception:
        LOG.warning("Field docs runner skipped native screenshot pixel validation; Pillow is unavailable")
        return None
    try:
        with Image.open(screenshot_path) as image:
            width, height = image.size
            extrema = image.convert("RGB").getextrema()
    except Exception as exc:
        return f"Could not inspect screenshot pixels: {exc}"
    if options.capture == "box" and (width < 96 or height < 80):
        return (
            f"Box screenshot is implausibly small ({width}x{height}). This usually means the box "
            "or its expected output was not present when the crop was measured."
        )
    if not options.graphics_context or options.capture != "native-window":
        return None
    if all(high <= 1 for _low, high in extrema):
        return (
            "Native graphics screenshot is all black. This usually means the capture ran before "
            "the shader reflection/code execution finished, or the graphics example did not draw."
        )
    return None


def trim_native_graphics_screenshot(*, screenshot_path: Path, options: RunnerOptions) -> None:
    if not options.trim_native or not options.graphics_context or effective_capture_mode(options) != "native-window" or options.source_image:
        return
    try:
        from PIL import Image, ImageChops, ImageStat
    except Exception:
        LOG.warning("Field docs runner skipped native screenshot trim; Pillow is unavailable")
        return

    try:
        with Image.open(screenshot_path) as source:
            image = source.copy()
    except Exception as exc:
        LOG.warning("Field docs runner could not open native screenshot for trimming %s: %s", screenshot_path, exc)
        return

    if image.width <= 4 or image.height <= 4:
        return

    rgb = image.convert("RGB")
    sample = max(1, min(32, image.width // 12, image.height // 12))
    corner_boxes = (
        (0, 0, sample, sample),
        (image.width - sample, 0, image.width, sample),
        (0, image.height - sample, sample, image.height),
        (image.width - sample, image.height - sample, image.width, image.height),
    )
    corner_medians: list[tuple[int, int, int]] = []
    for box in corner_boxes:
        stat = ImageStat.Stat(rgb.crop(box))
        median = stat.median
        corner_medians.append((int(median[0]), int(median[1]), int(median[2])))

    def median_channel(index: int) -> int:
        values = sorted(sample[index] for sample in corner_medians)
        return values[len(values) // 2]

    background = (median_channel(0), median_channel(1), median_channel(2))
    diff = ImageChops.difference(rgb, Image.new("RGB", rgb.size, background))
    red, green, blue = diff.split()
    max_diff = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    tolerance = 6
    mask = max_diff.point(lambda value: 255 if value > tolerance else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return

    left, top, right, bottom = bbox
    margin = max(16, min(96, int(round(max(image.width, image.height) * 0.015))))
    left = max(0, left - margin)
    top = max(0, top - margin)
    right = min(image.width, right + margin)
    bottom = min(image.height, bottom + margin)
    crop_width = right - left
    crop_height = bottom - top
    if crop_width <= 4 or crop_height <= 4:
        return

    original_area = image.width * image.height
    crop_area = crop_width * crop_height
    if crop_area >= original_area * 0.96:
        return

    try:
        image.crop((left, top, right, bottom)).save(screenshot_path)
    except Exception as exc:
        LOG.warning("Field docs runner failed to trim native screenshot %s: %s", screenshot_path, exc)
        return
    LOG.info(
        "Field docs runner trimmed native screenshot: %s (%dx%d -> %dx%d)",
        screenshot_path,
        image.width,
        image.height,
        crop_width,
        crop_height,
    )

    LOG.info(
        "Field docs runner trimmed native graphics screenshot %s: %sx%s -> %sx%s",
        screenshot_path,
        image.width,
        image.height,
        crop_width,
        crop_height,
    )


def parse_codegen_expectation(*, code: str, key: str) -> bool | None:
    match = re.search(rf"{re.escape(key)}:\s*(true|false)", code, re.IGNORECASE)
    if match is None:
        return None
    return match.group(1).lower() == "true"


def effective_capture_mode(options: RunnerOptions) -> str:
    if options.capture == "window" and options.open_outputs and not options.with_editor:
        return "box"
    return options.capture


SOURCE_BOX_PROPERTIES: tuple[tuple[str, str], ...] = (
    ("preamble", "kt"),
    ("code", "kt"),
    ("stackSL", "stacksl"),
    ("vertex", "glsl"),
    ("fragment", "glsl"),
    ("compute", "glsl"),
    ("vertex_fragment", "slang"),
    ("vertex_s", "slang"),
    ("fragment_s", "slang"),
    ("compute_s", "slang"),
)


def source_box_set_commands(
    *,
    repo_root: Path,
    source_box: str,
    target_uid: str,
    source_box_properties: tuple[str, ...] = (),
) -> list[str]:
    source_ref = Path(source_box)
    if not source_ref.is_absolute():
        source_ref = repo_root / "public" / "docs" / source_ref
    source_dir = source_ref.parent
    source_uid = source_ref.name
    allowed = set(source_box_properties) if source_box_properties else None
    commands: list[str] = []
    for property_name, extension in SOURCE_BOX_PROPERTIES:
        if allowed is not None and property_name not in allowed:
            continue
        path = source_dir / f"{source_uid}_{property_name}_.{extension}"
        if path.exists():
            commands.append(shlex.join([
                "set",
                target_uid,
                property_name,
                "--file", str(path),
            ]))
    return commands


def resolve_macro_file_path(*, repo_root: Path, page_path: Path, macro_file: str) -> Path:
    raw = Path(macro_file)
    if raw.is_absolute():
        return raw
    page_relative = (page_path.parent / raw).resolve()
    if page_relative.exists():
        return page_relative
    return (repo_root / raw).resolve()


def macro_name_for_options(*, options: RunnerOptions, page_path: Path, block_index: int) -> str:
    if options.macro_name:
        return options.macro_name
    if options.macro_file:
        return Path(options.macro_file).stem
    return f"{page_path.stem}-{block_index}"


def safe_transcript_id(text: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_]+", "_", text.strip())
    cleaned = re.sub(r"_+", "_", cleaned).strip("_")
    if not cleaned:
        cleaned = "box"
    if cleaned[0].isdigit():
        cleaned = f"box_{cleaned}"
    return cleaned[:80]


def docs_runner_target_uid(*, page_path: Path, block_index: int, options: RunnerOptions) -> str:
    if options.run_box:
        return options.run_box
    return safe_transcript_id(f"docs_{page_path.stem}_{block_index}_{options.box_name}")


def docs_runner_fieldml_root_alias(*, target_uid: str) -> str:
    return safe_transcript_id(f"{target_uid}_fieldml_root")


def graphics_renderer_transcript_path(repo_root: Path) -> Path:
    return repo_root / "dev_docs" / "agents" / "transcripts" / "graphics_renderer_launch.yaml"


def generated_graphics_child_transcript(*, target_uid: str, options: RunnerOptions) -> str:
    transcript: dict[str, Any] = {
        "name": f"{target_uid}_setup",
        "description": "Generated by public/docs_hooks.py for a native graphics docs capture.",
        "operations": [
            {
                "create": {
                    "box": target_uid,
                    "name": options.box_name,
                    "frame": {
                        "x": options.box_x,
                        "y": options.box_y,
                        "w": options.box_w,
                        "h": options.box_h,
                    },
                }
            },
            {
                "connect": {
                    "parent": "agent_graphics_context",
                    "child": target_uid,
                }
            },
        ],
    }
    return json.dumps(transcript, separators=(",", ":"))


def code_with_macro_digest(*, code: str, options: RunnerOptions, repo_root: Path, page_path: Path) -> str:
    digest_lines: list[str] = []
    if options.graphics_context:
        renderer_path = graphics_renderer_transcript_path(repo_root)
        try:
            renderer_digest = hashlib.sha256(renderer_path.read_bytes()).hexdigest()
        except FileNotFoundError:
            renderer_digest = "<missing>"
        digest_lines.extend([
            f"// FIELD_DOCS_RENDERER_MACRO_FILE: {renderer_path.relative_to(repo_root)}",
            f"// FIELD_DOCS_RENDERER_MACRO_DIGEST: {renderer_digest}",
        ])
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        try:
            macro_bytes = macro_path.read_bytes()
        except FileNotFoundError:
            macro_digest = "<missing>"
        else:
            macro_digest = hashlib.sha256(macro_bytes).hexdigest()
        digest_lines.extend([
            f"// FIELD_DOCS_MACRO_FILE: {options.macro_file}",
            f"// FIELD_DOCS_MACRO_DIGEST: {macro_digest}",
        ])
    if not digest_lines:
        return code
    return "\n".join([code, *digest_lines])


def runner_subprocess_timeout(*, options: RunnerOptions, is_video: bool) -> float:
    base = max(options.timeout, options.duration + 30.0) if is_video else options.timeout
    budget = base + RUNNER_SUBPROCESS_GRACE_SEC
    if not PLAYWRIGHT_READY_MARKER.exists():
        budget = max(budget, PLAYWRIGHT_FIRST_RUN_HEADROOM_SEC + RUNNER_SUBPROCESS_GRACE_SEC)
    return budget


@dataclass
class BackdoorInvocation:
    process_command: list[str]        # full `python field_backdoor.py --command ...` argv
    backdoor_commands: list[str]      # just the backdoor command lines (for the warm-session path)
    session_eligible: bool            # safe to run through the warm-session daemon?
    port: int
    document: str | None


def validate_wav_file(path: Path) -> None:
    """Assert [path] is a real, non-empty 16-bit PCM WAV (not a silent/headerless stub)."""
    data = path.read_bytes()
    if len(data) <= 44:
        raise RuntimeError(f"WAV {path} too small ({len(data)} bytes) — not a real audio capture")
    if data[0:4] != b"RIFF" or data[8:12] != b"WAVE" or data[12:16] != b"fmt ":
        raise RuntimeError(f"WAV {path} missing RIFF/WAVE/fmt header — not a real audio capture")


def build_audio_backdoor_command(
    *,
    repo_root: Path,
    audio_path: Path,
    options: RunnerOptions,
    newer_than: float,
) -> list[str]:
    """Backdoor argv that creates a box, runs the generated offline-render code (piped on
    stdin), and waits for the WAV to land. No Playwright/screenshot — a pure JVM render."""
    port = int(os.environ.get(RUNNER_PORT_ENV, str(DEFAULT_RUNNER_PORT)))
    command = [
        sys.executable,
        str((repo_root / "tools" / "field_backdoor.py").resolve()),
        "--json",
        "--port", str(port),
        "--timeout", str(options.timeout),
    ]
    if options.document:
        command.extend(["--document", options.document])
    backdoor_commands = [
        shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]),
        shlex.join([
            "run", "@last",
            "--property", options.property_name,
            "--file", "-",
            "--timeout", str(options.timeout),
        ]),
        shlex.join([
            "wait-file", str(audio_path),
            "--timeout", str(options.timeout),
            "--newer-than", str(newer_than),
            "--fail-on-error-uid", "@last",
        ]),
    ]
    for command_line in backdoor_commands:
        command.extend(["--command", command_line])
    return command


def build_backdoor_command(
    *,
    repo_root: Path,
    page_path: Path,
    block_index: int,
    screenshot_path: Path,
    options: RunnerOptions,
    is_video: bool = False,
    source_image_path: Path | None = None,
    source_image_min_mtime: float | None = None,
    code: str | None = None,
) -> BackdoorInvocation:
    port = int(os.environ.get(RUNNER_PORT_ENV, str(DEFAULT_RUNNER_PORT)))
    capture_mode = effective_capture_mode(options)
    command = [
        sys.executable,
        str((repo_root / "tools" / "field_backdoor.py").resolve()),
        "--json",
        "--with-playwright",
        "--port",
        str(port),
        "--timeout",
        str(options.timeout),
    ]
    if options.document:
        command.extend(["--document", options.document])
    backdoor_commands: list[str] = []

    if options.capture == "ui":
        # Optional setup: run a transcript= file of create/launch ops FIRST, in the same
        # backdoor session, so its boxes exist when the drive runs. Needed for captures
        # that require scene setup a drive fragment can't do itself (a drive fragment runs
        # on the main runloop thread where thisSessionInfo is unset, so box(...) throws) —
        # e.g. code-driven UI like showLayers(), or a separate demo box to type into.
        if options.macro_file:
            setup_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
            setup_name = f"{safe_transcript_id(options.box_name)}_ui_setup"
            backdoor_commands.append(shlex.join([
                "macro", "write", setup_name, "--file", str(setup_path), "--timeout", str(options.timeout),
            ]))
            backdoor_commands.append(shlex.join([
                "macro", "run", setup_name, "--timeout", str(options.timeout),
            ]))
        # Run the fence body as a single `drive` transcript op: it scripts the live
        # editor via Playwright (the `ui` driver is in scope) and produces the video
        # and/or still itself, so no separate screenshot-/record- command is appended.
        drive_op: dict[str, Any] = {
            "script": code or "",
            "capture": options.region,
            "fps": options.fps,
            "padding": options.padding,
        }
        if options.video:
            drive_op["video"] = str(resolve_output_path(page_path, options.video))
        if options.screenshot:
            drive_op["screenshot"] = str(resolve_output_path(page_path, options.screenshot))
        capture_uid = options.capture_box or options.run_box
        if capture_uid:
            drive_op["captureUid"] = capture_uid
        if options.clip_selector:
            drive_op["clipSelector"] = options.clip_selector
        if options.warm_completion:
            drive_op["warmCompletion"] = True
        transcript_name = f"{safe_transcript_id(options.box_name)}_ui_drive"
        transcript = {
            "name": transcript_name,
            "description": "Generated by public/docs_hooks.py capture=ui drive.",
            "operations": [{"drive": drive_op}],
        }
        backdoor_commands.append(shlex.join([
            "macro", "write", transcript_name,
            "--content", json.dumps(transcript, separators=(",", ":")),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro", "run", transcript_name,
            "--timeout", str(options.timeout),
        ]))
        for command_line in backdoor_commands:
            command.extend(["--command", command_line])
        return BackdoorInvocation(
            process_command=command,
            backdoor_commands=backdoor_commands,
            session_eligible=False,
            port=port,
            document=options.document or None,
        )
    generated_target_uid = docs_runner_target_uid(page_path=page_path, block_index=block_index, options=options)
    default_target_uid = options.capture_box or options.run_box or (generated_target_uid if options.graphics_context else "@last")
    language = options.language_key.strip().lower()
    if options.graphics_context:
        renderer_macro_path = graphics_renderer_transcript_path(repo_root)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            "graphics_renderer_launch",
            "--file", str(renderer_macro_path),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            "graphics_renderer_launch",
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "wait-native-renderer",
            "agent_graphics_context",
            "--frames", "3",
            "--timeout", str(options.timeout),
        ]))
    if options.macro_file:
        macro_path = resolve_macro_file_path(repo_root=repo_root, page_path=page_path, macro_file=options.macro_file)
        macro_name = macro_name_for_options(options=options, page_path=page_path, block_index=block_index)
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            macro_name,
            "--file", str(macro_path),
            "--timeout", str(options.timeout),
        ]))
        macro_run_parts = [
            "macro",
            "run",
            macro_name,
            "--timeout", str(options.timeout),
        ]
        for selection in options.macro_selection:
            macro_run_parts.extend(["--selection", selection])
        backdoor_commands.append(shlex.join(macro_run_parts))
    elif options.run_box:
        if options.graphics_context:
            connect_transcript = {
                "name": f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "description": "Generated by public/docs_hooks.py to connect an existing box to the docs renderer.",
                "operations": [
                    {"connect": {"parent": "agent_graphics_context", "child": options.run_box}},
                ],
            }
            backdoor_commands.append(shlex.join([
                "macro",
                "write",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--content", json.dumps(connect_transcript, separators=(",", ":")),
                "--timeout", str(options.timeout),
            ]))
            backdoor_commands.append(shlex.join([
                "macro",
                "run",
                f"{safe_transcript_id(options.run_box)}_graphics_parent",
                "--timeout", str(options.timeout),
            ]))
        for setup_box in options.setup_boxes:
            backdoor_commands.append(shlex.join([
                "run",
                setup_box,
                "--timeout", str(options.timeout),
            ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            options.run_box,
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.graphics_context:
        setup_macro_name = f"{generated_target_uid}_setup"
        backdoor_commands.append(shlex.join([
            "macro",
            "write",
            setup_macro_name,
            "--content", generated_graphics_child_transcript(target_uid=generated_target_uid, options=options),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "macro",
            "run",
            setup_macro_name,
            "--timeout", str(options.timeout),
        ]))
    elif language == "fieldml":
        root_alias = docs_runner_fieldml_root_alias(target_uid=generated_target_uid)
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", f"{options.box_name}-root",
            "--alias", root_alias,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", "120",
            "--h", "80",
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "set",
            root_alias,
            "mainLanguage",
            "fieldml",
        ]))
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y + 120.0),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "connect",
            root_alias,
            "@last",
            "--timeout", str(options.timeout),
        ]))
    else:
        backdoor_commands.append(shlex.join([
            "new-box",
            "--name", options.box_name,
            "--x", str(options.box_x),
            "--y", str(options.box_y),
            "--w", str(options.box_w),
            "--h", str(options.box_h),
            "--timeout", str(options.timeout),
        ]))
    if options.source_box and not options.macro_file:
        backdoor_commands.extend(source_box_set_commands(
            repo_root=repo_root,
            source_box=options.source_box,
            target_uid=options.run_box or (generated_target_uid if options.graphics_context else "@last"),
            source_box_properties=options.source_box_properties,
        ))
        if options.source_box_wait > 0:
            backdoor_commands.append(shlex.join(["sleep", str(options.source_box_wait)]))
    if options.macro_file:
        pass
    elif options.run_box is None and capture_mode == "editor":
        # Target the box explicitly rather than "@last": under graphics-context the
        # box is created via a macro (so "@last" is stale) and must match the box
        # that record-editor records below (default_target_uid). In the non
        # graphics-context case default_target_uid is "@last", preserving behaviour.
        editor_target = default_target_uid
        backdoor_commands.append(shlex.join([
            "set",
            editor_target,
            options.property_name,
            "--file", "-",
        ]))
        backdoor_commands.append(shlex.join([
            "editor-open",
            editor_target,
            "--property", options.property_name,
            "--padding", str(options.padding),
            "--timeout", str(options.timeout),
        ]))
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            editor_target,
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None and (
        options.source_box is None
        or (options.source_box_properties and "code" not in options.source_box_properties)
    ):
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--file", "-",
            "--timeout", str(options.timeout),
        ]))
    elif options.run_box is None:
        backdoor_commands.append(shlex.join([
            "launch" if options.execution == "launch" else "run",
            generated_target_uid if options.graphics_context else "@last",
            "--property", options.property_name,
            "--timeout", str(options.timeout),
        ]))
    if options.wait > 0 and source_image_path is None:
        backdoor_commands.append(shlex.join(["sleep", str(options.wait)]))
    if options.wait_output:
        wait_output_parts = [
            "wait-output",
            default_target_uid,
            "--kind", options.wait_output_kind,
            "--timeout", str(options.timeout),
        ]
        if options.wait_output_channel:
            wait_output_parts.extend(["--channel", options.wait_output_channel])
        if options.wait_output_contains:
            wait_output_parts.extend(["--contains", options.wait_output_contains])
        backdoor_commands.append(shlex.join(wait_output_parts))
    if source_image_path is not None:
        wait_file_parts = [
            "wait-file",
            str(source_image_path),
            "--timeout",
            str(options.timeout),
            "--fail-on-error-uid",
            default_target_uid,
        ]
        if source_image_min_mtime is not None:
            wait_file_parts.extend(["--newer-than", str(source_image_min_mtime)])
        backdoor_commands.append(shlex.join(wait_file_parts))

    if is_video:
        # Use record-* commands instead of screenshot-*
        if capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "record-box",
                target_uid,
                str(screenshot_path),
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "record-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        else:
            parts = [
                "record-window",
                str(screenshot_path),
                "--uid", default_target_uid,
                "--property", options.property_name,
                "--duration", str(options.duration),
                "--fps", str(options.fps),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))
    else:
        if options.source_image:
            pass
        elif capture_mode == "native-window":
            backdoor_commands.append(shlex.join([
                "native-screenshot",
                str(screenshot_path),
                "--wait-frames", str(options.native_wait_frames),
                "--timeout", str(options.timeout),
            ]))
        elif capture_mode == "box":
            target_uid = default_target_uid
            parts = [
                "screenshot-box",
                target_uid,
                str(screenshot_path),
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        elif capture_mode == "editor":
            target_uid = default_target_uid
            parts = [
                "screenshot-editor",
                target_uid,
                str(screenshot_path),
                "--property", options.property_name,
                "--padding", str(options.padding),
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            backdoor_commands.append(shlex.join(parts))
        else:
            target_uid = default_target_uid
            parts = [
                "screenshot-window",
                str(screenshot_path),
                "--uid", target_uid,
                "--property", options.property_name,
                "--timeout", str(options.timeout),
            ]
            if options.open_outputs:
                parts.append("--open-outputs")
            if options.with_editor:
                parts.append("--with-editor")
            backdoor_commands.append(shlex.join(parts))

    if options.execution == "launch" and not options.macro_file:
        backdoor_commands.append(shlex.join([
            "stop",
            default_target_uid,
            "--timeout", str(options.timeout),
        ]))
    for command_line in backdoor_commands:
        command.extend(["--command", command_line])
    session_eligible = not options.graphics_context and not is_video and not options.source_image
    return BackdoorInvocation(
        process_command=command,
        backdoor_commands=backdoor_commands,
        session_eligible=session_eligible,
        port=port,
        document=options.document or None,
    )

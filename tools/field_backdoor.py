#!/usr/bin/env python3

from __future__ import annotations

import argparse
import asyncio
import datetime
import json
import math
import os
import re
import shlex
import signal
import socket
import ssl
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import uuid
from collections import deque
from pathlib import Path
from typing import Any, TextIO

try:
    from .field_python_env import ensure_repo_python
except ImportError:  # Executed as tools/field_backdoor.py rather than imported as a package.
    from field_python_env import ensure_repo_python

if __name__ == "__main__":
    ensure_repo_python()

import websockets  # noqa: E402
from verification_runtime import (  # noqa: E402
    automation_server_environment,
    automation_workspace,
    field_instance_info,
    field_instance_problem,
)

try:
    import yaml
except ImportError:  # pragma: no cover - optional dependency guard
    yaml = None


TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
LOG_ROOT = REPO_ROOT / ".field-backdoor" / "logs"
DEFAULT_PORT = 9092
DEFAULT_TIMEOUT = 20.0
# JDWP is opt-in / off by default (see build.gradle.kts). The backdoor turns it on
# for the Field instances IT launches, so `field_debug_probe` works out of the box
# against backdoor-managed servers. Manual `./gradlew runJvm` stays closed.
DEFAULT_JDWP_PORT = "5005"
# ...but a JDWP bind failure is FATAL to JVM startup ("transport error 202: bind
# failed" -> the JVM refuses to boot, Gradle exits 1 in ~1s). Because 5005 is a
# single machine-global port, any second Field JVM -- a sibling worktree, or the
# predecessor this one is replacing that has not finished shutting down -- makes
# the new one unbootable. That surfaces as "backdoor returned no JSON result"
# ~1.8s failures scattered through a smoke run. A debug convenience must never
# prevent Field from starting, so scan for a free port and fall back to off.
JDWP_PORT_SCAN_RANGE = 64
# Generous budget for the FIRST Playwright attach on a machine, where the browser
# binaries may still need to download. After that, attaches are fast, so ordinary
# ops honor the caller's --timeout and a hung attach fails fast instead of blocking
# for minutes (which previously caused docs captures to be killed at ~50s and then
# retried with full JVM restarts). A marker file records that the browser is ready.
PLAYWRIGHT_ATTACH_FIRST_RUN_FLOOR = float(os.environ.get("AFIELD_PLAYWRIGHT_ATTACH_TIMEOUT", "180"))
PLAYWRIGHT_READY_MARKER = REPO_ROOT / ".field-backdoor" / "playwright-ready"
MAX_EVENTS = 4000
# Log values WHOLE by default. 0 means "do not truncate"; set a positive value to cap.
#
# These were 512 and 32, and the cost of that is not hypothetical: on 2026-09-25 a nightly CI run
# hit `petri.gallery` failing with an IllegalStateException whose message enumerated every
# cheatsheet operator lacking a worked example. The message was 2366 characters, the log saved the
# first 512 as a `truncated-string` preview, and the list — the entire content of the diagnosis —
# was destroyed at write time and could only be recovered by re-running the test.
#
# Truncating when a human or agent READS a log is free and reversible. Truncating when the log is
# WRITTEN is neither. The defence against a runaway producer belongs one layer out, in the byte
# budgets below, which drop whole records and say how many they dropped, rather than silently
# amputating the one value someone needed.
LOG_STRING_PREVIEW = int(os.environ.get("AFIELD_BACKDOOR_LOG_STRING_PREVIEW", "0"))
# Each backdoor invocation writes a session log; historically these accumulated
# unbounded (thousands of files / >100MB). Keep only the most recent N of each
# kind. Override with AFIELD_BACKDOOR_LOG_KEEP, or 0 to disable pruning.
LOG_KEEP = int(os.environ.get("AFIELD_BACKDOOR_LOG_KEEP", "300"))
LOG_COLLECTION_PREVIEW = int(os.environ.get("AFIELD_BACKDOOR_LOG_COLLECTION_PREVIEW", "0"))
# A busy graphics document can emit thousands of telemetry packets per second. Individual values are
# now written whole, so the byte budgets below are the ONLY bound on a session log, and they carry
# the whole load that per-value truncation used to share. They drop entire records once a budget is
# reached and record `droppedRecords`, which is a legible, countable loss rather than a silent one.
# Logging must never be able to stop the websocket receiver or fill the disk.
LOG_MAX_BYTES = int(os.environ.get("AFIELD_BACKDOOR_LOG_MAX_BYTES", str(16 * 1024 * 1024)))
LOG_TOTAL_MAX_BYTES = int(os.environ.get("AFIELD_BACKDOOR_LOG_TOTAL_MAX_BYTES", str(256 * 1024 * 1024)))
GRAPHICS_CONTEXT_WAIT_SEC = 0.5
GRAPHICS_CONTEXT_BOX_NAME = "renderer"
GRADLE_WRAPPER = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
GRADLE_JVMARGS = "-Xmx8g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"


class BackdoorError(RuntimeError):
    pass


class CommandParseError(BackdoorError):
    pass


def exception_message(exc: BaseException) -> str:
    """Never turn a useful exception type (notably asyncio.TimeoutError) into a blank CLI error."""
    return str(exc).strip() or type(exc).__name__


def make_unverified_ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def http_get_json(url: str, ssl_context: ssl.SSLContext, timeout: float) -> Any:
    with urllib.request.urlopen(url, context=ssl_context, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def http_get_text(url: str, ssl_context: ssl.SSLContext, timeout: float) -> str:
    with urllib.request.urlopen(url, context=ssl_context, timeout=timeout) as response:
        return response.read().decode("utf-8")


def health_check(port: int, ssl_context: ssl.SSLContext, timeout: float = 1.5) -> bool:
    url = f"https://localhost:{port}/health"
    try:
        body = http_get_text(url, ssl_context=ssl_context, timeout=timeout)
        return body.strip().lower() == "ok"
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        return False


def _same_directory(left: str, right: str) -> bool:
    """Do two workspace paths name the same directory?

    `~` is expanded and symlinks are resolved, because the server reports a canonical path
    (`displayWorkspacePath`) while a caller types whatever is convenient -- on macOS
    `/tmp/x` and `/private/tmp/x` are the same directory. A path that does not exist cannot be
    resolved, so that case falls back to a normalised string comparison rather than declaring a
    mismatch on a technicality.
    """
    def normalize(path: str) -> str:
        expanded = Path(path).expanduser()
        try:
            return str(expanded.resolve())
        except OSError:
            return os.path.normpath(str(expanded.absolute()))

    return normalize(left) == normalize(right)


def jdwp_port_is_bindable(port: int, host: str = "127.0.0.1") -> bool:
    """True if the JDWP agent could bind ``host:port`` right now.

    Deliberately does NOT set SO_REUSEADDR: the probe has to be at least as
    strict as the JDWP agent's own bind, or we hand back a port that the JVM
    then dies on.
    """
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind((host, port))
    except OSError:
        return False
    return True


def resolve_jdwp_port(host: str = "127.0.0.1") -> str | None:
    """Pick a free JDWP port, or None to leave remote debugging off.

    An explicit AFIELD_JDWP_PORT is honored verbatim (including "off" and
    friends): the caller may be waiting to attach a debugger on exactly that
    port, so silently moving it would be worse than failing. Only the default
    is allowed to drift.

    Inherently racy -- a port free at check time can be taken before the JVM
    binds it -- but the window is small and the scan makes a repeat collision
    on the next port unlikely.
    """
    explicit = os.environ.get("AFIELD_JDWP_PORT")
    if explicit is not None:
        return explicit

    preferred = int(DEFAULT_JDWP_PORT)
    for candidate in range(preferred, preferred + JDWP_PORT_SCAN_RANGE):
        if jdwp_port_is_bindable(candidate, host):
            return str(candidate)
    return None


def gradle_command(*tasks_and_args: str) -> list[str]:
    return [
        str(GRADLE_WRAPPER),
        f"-Dorg.gradle.jvmargs={GRADLE_JVMARGS}",
        *tasks_and_args,
    ]


def parse_agent_check(spec: str) -> dict[str, Any]:
    """`kind:arg:arg` -> an AgentCheck payload; mirrors AgentCheck.parse on the server.

    Kept in step with the Kotlin parser by hand; the server is the authority and re-validates the
    kind, so a spelling this accepts and the server does not fails the task with an unknown-kind check.
    """
    kind, _, rest = spec.partition(":")
    kind = kind.strip()
    if kind in ("box-exists", "last-run-ok", "running", "not-running"):
        return {"kind": kind, "box": rest}
    if kind == "output-contains":
        box, _, text = rest.partition(":")
        return {"kind": kind, "box": box, "text": text}
    if kind == "output-ints-include":
        box, _, ints = rest.partition(":")
        values = [int(x) for x in ints.replace(" ", ",").split(",") if x.strip()]
        return {"kind": kind, "box": box, "ints": values}
    if kind in ("property-contains", "property-lacks"):
        parts = rest.split(":", 2)
        if len(parts) != 3:
            raise BackdoorError(f"{kind} needs <box>:<property>:<text>, got {spec!r}")
        return {"kind": kind, "box": parts[0], "property": parts[1], "text": parts[2]}
    if kind == "connected":
        parent, _, child = rest.partition(":")
        return {"kind": kind, "parent": parent, "child": child}
    raise BackdoorError(f"unknown agent-task check kind {kind!r}")


def command_result(command: str, ok: bool, data: Any = None, events: list[dict[str, Any]] | None = None, timed_out: bool = False) -> dict[str, Any]:
    return {
        "ok": ok,
        "command": command,
        "data": data,
        "events": events or [],
        "timedOut": timed_out,
    }


def _is_error_output_event(event: dict[str, Any]) -> bool:
    name = event.get("name") or event.get("messageName")
    payload = event.get("payload")
    return (
        isinstance(payload, dict)
        and name in {"output", "peer-output"}
        and payload.get("kind") in {"error", "err"}
    )


def _is_error_clear_event(event: dict[str, Any]) -> bool:
    """Field encodes "clear this property's error" as an empty error output at line -1."""
    if not _is_error_output_event(event):
        return False
    payload = event["payload"]
    text = payload.get("html") or payload.get("text") or payload.get("message")
    return payload.get("line") == -1 and not str(text or "").strip()


def _is_execution_error_event(event: dict[str, Any]) -> bool:
    if _is_error_output_event(event):
        return not _is_error_clear_event(event)
    name = event.get("name") or event.get("messageName")
    payload = event.get("payload")
    return (
        isinstance(payload, dict)
        and name == "notify"
        and str(payload.get("category", "")).strip().lower() == "error"
        and bool(str(payload.get("text", "")).strip())
    )


def _execution_error_key(event: dict[str, Any]) -> tuple[str, str] | None:
    uid = event.get("uid")
    payload = event.get("payload")
    if not isinstance(uid, str) or not isinstance(payload, dict):
        return None
    return uid, str(payload.get("property") or "__general__")


def grade_execution(
    client: "FieldBackdoor",
    data: dict[str, Any],
    events: list[dict[str, Any]],
    *,
    timed_out: bool,
    fail_on_error: bool,
) -> tuple[dict[str, Any], bool]:
    """Fold a box's own error output into an execution verb's ``data`` and ``ok``.

    Until 2026-09-14 ``run``/``launch``/``continue``/``launch-batch`` answered "did this
    box run?" with ``ok = not timed_out`` and never read the events they had just
    collected. A box with a hard Kotlin compile error therefore returned ``ok: true``,
    ``running: false``, process exit 0, with the diagnostic sitting unread in the same
    result. The two sibling verbs in ``dispatch_command`` already did better: ``action``
    calls ``execution_errors()`` and ``wait-output`` calls ``_execution_error_event()``.
    This is the shared form of that, so the four execution verbs cannot drift apart again.

    ``execution_errors()`` -- not a fresh predicate -- is what decides, so Field's
    "clear the marker" packet (an empty error at line -1, which DatalogExecution emits
    on every success) stays a non-event here exactly as it already does there.

    ``fail_on_error=False`` suppresses the verdict, never the evidence: ``data["errors"]``
    is still populated and ``data["executionErrorsIgnored"]`` says the verdict was waived.
    """
    problems = client.execution_errors(events)
    for event in events:
        payload = event.get("payload") or {}
        if event.get("name") == "execution-verdict" and payload.get("success") is False:
            detail = payload.get("error") or "Execution did not complete setup"
            problems.append(f"{payload.get('uid')}/{payload.get('property')} [{payload.get('revision')}]: {detail}")
    if not problems:
        return data, not timed_out
    data = dict(data)
    data["errors"] = problems
    if fail_on_error:
        # One flat string as well as the list: `run_field_tests.collect_execution_problems`
        # and `docs_hooks`' require_diagnostic path both read `data["error"]` to tell a
        # diagnosed failure from an opaque one, and a bare "command failed" is not proof.
        data["error"] = "; ".join(problems)
        return data, False
    data["executionErrorsIgnored"] = True
    return data, not timed_out


def playwright_attach_budget(timeout: float | None, default_timeout: float) -> float:
    """Effective wait for a Playwright *attach*.

    The first attach on a machine may download browser binaries, so allow a
    generous one-time floor (recorded by PLAYWRIGHT_READY_MARKER). Once the
    browser is known-ready, attaches are near-instant and we honor the caller's
    timeout so a genuine hang fails fast instead of blocking for minutes.
    """
    requested = default_timeout if timeout is None else timeout
    if not PLAYWRIGHT_READY_MARKER.exists():
        return max(requested, PLAYWRIGHT_ATTACH_FIRST_RUN_FLOOR)
    return requested


def mark_playwright_ready() -> None:
    try:
        PLAYWRIGHT_READY_MARKER.parent.mkdir(parents=True, exist_ok=True)
        PLAYWRIGHT_READY_MARKER.touch()
    except OSError:
        pass


def command_timeout(timeout: float | None, default_timeout: float) -> float:
    if timeout is None:
        return default_timeout
    return timeout


def parse_repeated_string_values(values: list[str]) -> list[str]:
    result: list[str] = []
    for raw in values:
        for part in re.split(r"[\s,]+", raw):
            cleaned = part.strip()
            if cleaned:
                result.append(cleaned)
    return result


def parse_repeated_int_values(values: list[str]) -> list[int]:
    result: list[int] = []
    for value in parse_repeated_string_values(values):
        try:
            result.append(int(value))
        except ValueError as exc:
            raise CommandParseError(f"expected integer index, got {value!r}") from exc
    return result


def utc_timestamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _summarize_for_log(value: Any) -> Any:
    """Prepare a value for the session log, writing it whole unless a cap is configured.

    With the default caps of 0 this preserves content exactly and only normalises dict keys to
    strings, which JSON requires anyway. See the LOG_STRING_PREVIEW comment for why the default
    is "write everything": a log exists to be read after the fact by someone who cannot re-run
    the failure, and the value most worth keeping is precisely the long one.
    """
    if isinstance(value, str):
        if LOG_STRING_PREVIEW <= 0 or len(value) <= LOG_STRING_PREVIEW:
            return value
        return {
            "__type__": "truncated-string",
            "length": len(value),
            "preview": value[:LOG_STRING_PREVIEW],
        }
    if isinstance(value, dict):
        items = list(value.items())
        keep = len(items) if LOG_COLLECTION_PREVIEW <= 0 else LOG_COLLECTION_PREVIEW
        summarized = {str(k): _summarize_for_log(v) for k, v in items[:keep]}
        if len(items) > keep:
            summarized["__truncated_items__"] = len(items) - keep
        return summarized
    if isinstance(value, (list, tuple)):
        items = list(value)
        keep = len(items) if LOG_COLLECTION_PREVIEW <= 0 else LOG_COLLECTION_PREVIEW
        summarized = [_summarize_for_log(v) for v in items[:keep]]
        if len(items) > keep:
            summarized.append({"__truncated_items__": len(items) - keep})
        return summarized
    return value


class SessionLogger:
    @staticmethod
    def prune(
        root: Path,
        keep: int = LOG_KEEP,
        max_total_bytes: int = LOG_TOTAL_MAX_BYTES,
    ) -> None:
        """Bound both file count and total bytes; diagnostic-log cleanup is best-effort."""
        if keep <= 0 or not root.exists():
            return
        try:
            for pattern in ("*.jsonl", "*.managed-server.log"):
                files = sorted(root.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
                for stale in files[keep:]:
                    try:
                        stale.unlink()
                    except OSError:
                        pass
            if max_total_bytes > 0:
                files = sorted(
                    [*root.glob("*.jsonl"), *root.glob("*.managed-server.log")],
                    key=lambda p: p.stat().st_mtime,
                    reverse=True,
                )
                total = sum(path.stat().st_size for path in files)
                for stale in reversed(files):
                    if total <= max_total_bytes or stale == files[0]:
                        break
                    try:
                        size = stale.stat().st_size
                        stale.unlink()
                        total -= size
                    except OSError:
                        pass
        except OSError:
            pass

    def __init__(self, root: Path, argv: list[str], max_bytes: int = LOG_MAX_BYTES) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self.prune(root)
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.session_id = f"{stamp}-pid{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self.path = self.root / f"{self.session_id}.jsonl"
        self.handle = self.path.open("a", encoding="utf-8")
        self.max_bytes = max_bytes
        self.bytes_written = 0
        self.dropped_records = 0
        self.write_error: str | None = None
        self.capped = False
        self.log(
            "session_start",
            argv=argv,
            cwd=os.getcwd(),
            pid=os.getpid(),
            python=sys.version,
            logPath=str(self.path),
        )

    def _record(self, event: str, fields: dict[str, Any], *, force: bool = False) -> None:
        record = {"timestamp": utc_timestamp(), "event": event}
        record.update(_summarize_for_log(fields))
        line = json.dumps(record, ensure_ascii=True, default=str) + "\n"
        encoded_size = len(line.encode("utf-8"))
        if not force and self.max_bytes > 0 and self.bytes_written + encoded_size > self.max_bytes:
            self.capped = True
            self.dropped_records += 1
            return
        try:
            self.handle.write(line)
            self.handle.flush()
            self.bytes_written += encoded_size
        except OSError as exc:
            # Diagnostics are never allowed to kill the actual backdoor receiver. In particular,
            # ENOSPC used to close the websocket and turn a healthy long JVM probe into "error: ''".
            self.write_error = f"{type(exc).__name__}: {exc}"

    def log(self, event: str, **fields: Any) -> None:
        if self.write_error is None:
            self._record(event, fields)

    def exception(self, event: str, exc: BaseException, **fields: Any) -> None:
        if self.write_error is not None:
            return
        self._record(
            event,
            {
                "errorType": type(exc).__name__,
                "error": exception_message(exc),
                "traceback": "".join(traceback.format_exception(type(exc), exc, exc.__traceback__)),
                **fields,
            },
            force=True,
        )

    def close(self) -> None:
        if self.handle.closed:
            return
        if self.write_error is None and self.capped:
            self._record(
                "session_log_capped",
                {"maxBytes": self.max_bytes, "bytesWritten": self.bytes_written, "droppedRecords": self.dropped_records},
                force=True,
            )
        if self.write_error is None:
            self._record("session_end", {}, force=True)
        try:
            self.handle.close()
        except OSError:
            pass


class FieldBackdoor:
    def __init__(
        self,
        *,
        port: int,
        workspace: str | None,
        default_timeout: float,
        logger: SessionLogger,
        attach_existing: bool = False,
    ) -> None:
        self.port = port
        # A backdoor-managed Field is automation, even when the caller omitted
        # --workspace. Never let it inherit the machine-global interactive
        # preference, which may name a deleted sibling worktree.
        self.workspace = workspace or str(automation_workspace(REPO_ROOT))
        # Kept apart from the effective value above, because "the caller asked for this" and
        # "a server is using this" are different claims and `status` used to make only the
        # first while looking like it made the second. None means the caller said nothing.
        self.requested_workspace = workspace
        # Read back from GET /api/instance-info, never echoed from the request. None when the
        # server is older than that field, which is a reason to distrust rather than to assume.
        self.server_workspace: str | None = None
        self.default_timeout = default_timeout
        self.attach_existing = attach_existing
        self.logger = logger
        self.session_id = logger.session_id
        self.ssl_context = make_unverified_ssl_context()
        self.websocket: websockets.WebSocketClientProtocol | None = None
        self.receiver_task: asyncio.Task[None] | None = None
        self.condition = asyncio.Condition()
        # Serializes all websocket sends: the receiver loop acks output messages
        # (see _maybe_ack_output) concurrently with command sends, and a single
        # connection is not safe for interleaved writes from two coroutines.
        self._send_lock = asyncio.Lock()
        self.event_seq = 0
        self.event_log: deque[dict[str, Any]] = deque(maxlen=MAX_EVENTS)
        self.reply_contexts: dict[str, dict[str, Any]] = {}
        self.document_name: str | None = None
        self.scratch = False
        self.read_only = False
        # Tri-state on purpose: None is "no server has told us", not "no".
        self.document_existed: bool | None = None
        self.attached_to_live_document: bool | None = None
        self.session_key: str | None = None
        self.boxes: dict[str, dict[str, Any]] = {}
        self._running_state_seen: dict[tuple[str, str], tuple[int, bool]] = {}
        self._active_execution_errors: dict[tuple[str, str], dict[str, Any]] = {}
        self.last_box_uid: str | None = None
        self.box_aliases: dict[str, str] = {}
        self.playwright_state: dict[str, Any] | None = None
        self.managed_process: subprocess.Popen[Any] | None = None
        self.managed_log_path: Path | None = None
        self.managed_log_file: TextIO | None = None
        self.closed_reason: str | None = None
        self.reused_existing_server = False
        self.stdin_cache: str | None = None
        self.graphics_context_enabled = False
        self.graphics_context_uid: str | None = None
        self.logger.log(
            "client_initialized",
            sessionId=self.session_id,
            port=self.port,
            workspace=self.workspace,
            defaultTimeout=self.default_timeout,
            attachExisting=self.attach_existing,
        )

    def adopt_server_workspace(self, info: dict[str, Any] | None) -> None:
        """Record the workspace the SERVER reports, which is the one documents resolve against.

        `GET /api/instance-info` grew a `workspace` field for this. A server older than that
        change reports nothing, and nothing is what gets recorded: an absent fact must not be
        replaced by the request, which is the whole bug being fixed here.
        """
        workspace = (info or {}).get("workspace")
        self.server_workspace = workspace if isinstance(workspace, str) and workspace else None

    def refuse_ignored_workspace(self) -> None:
        """Hard-error when an explicit `--workspace` disagrees with the attached server's.

        `--workspace` reaches exactly one place: the `--args=--workspace ...` of a server this
        process LAUNCHES. Attaching to a server someone else started, it decided nothing, and
        `status` reported it anyway. On 2026-09-11 an agent wrote five documents into a
        *different checkout's* `dev_docs/agents/demos/` across four runs while believing it had
        verified the workspace, because the value it verified was its own argument.

        Forwarding it instead of refusing is not available: the server's workspace is the
        process-global `home` option, fixed when the JVM booted, and moving a live server's
        workspace restarts it (`POST /api/workspace-switch`). So the honest outcomes are "agree"
        or "refuse", and refusing early costs one command instead of five misplaced documents.

        Silent when the caller passed no `--workspace` (nothing was claimed), and when the
        server does not report one (nothing to compare); `status` reports both cases as
        `serverWorkspace: null`, which is a fact rather than a guess.
        """
        if self.requested_workspace is None or not self.reused_existing_server:
            return
        if self.server_workspace is None:
            self.logger.log(
                "workspace_unverifiable",
                port=self.port,
                requestedWorkspace=self.requested_workspace,
                reason="server does not report a workspace (older than /api/instance-info workspace)",
            )
            return
        if _same_directory(self.requested_workspace, self.server_workspace):
            return
        self.logger.log(
            "workspace_mismatch_refused",
            port=self.port,
            requestedWorkspace=self.requested_workspace,
            serverWorkspace=self.server_workspace,
        )
        raise BackdoorError(
            f"--workspace {self.requested_workspace} disagrees with the Field already running on "
            f"port {self.port}, whose workspace is {self.server_workspace}. An attached server's "
            "workspace is fixed at its launch, so --workspace cannot be applied to it and used to "
            "be ignored in silence -- documents then resolved under the SERVER's workspace while "
            "status echoed yours back. Either pass --workspace "
            f"{self.server_workspace} with a --document path relative to it, drop --workspace "
            "entirely, or use a --port where this session starts its own Field."
        )

    async def ensure_server(self) -> None:
        self.logger.log("ensure_server_start", port=self.port, workspace=self.workspace)
        initial_health = health_check(self.port, self.ssl_context)
        self.logger.log("health_check", phase="initial", port=self.port, healthy=initial_health)
        if initial_health:
            info = field_instance_info(self.port, self.ssl_context)
            problem = field_instance_problem(
                info,
                REPO_ROOT,
                allow_stale_source=self.attach_existing,
            )
            if problem is not None:
                self.logger.log("server_reuse_refused", port=self.port, reason=problem, instance=info)
                raise BackdoorError(
                    f"Refusing to reuse Field on port {self.port}: {problem}. "
                    "Choose a fresh --port or stop that server explicitly."
                )
            self.reused_existing_server = True
            self.adopt_server_workspace(info)
            self.logger.log(
                "server_reused",
                port=self.port,
                instance=info,
                attachExisting=self.attach_existing,
                serverWorkspace=self.server_workspace,
                requestedWorkspace=self.requested_workspace,
                ignoredFreshnessProblem=(
                    field_instance_problem(info, REPO_ROOT)
                    if self.attach_existing
                    else None
                ),
            )
            self.refuse_ignored_workspace()
            return

        if self.attach_existing:
            raise BackdoorError(
                f"--attach-existing requested, but no Field server is listening on port {self.port}"
            )

        self.managed_log_path = self.logger.root / f"{self.session_id}.managed-server.log"
        self.managed_log_file = self.managed_log_path.open("w", encoding="utf-8")

        cmd = gradle_command("runJvm")
        quoted_workspace = shlex.quote(self.workspace)
        cmd.append(f"--args=--workspace {quoted_workspace}")

        # Enable remote debugging for backdoor-launched Field so agent probes work.
        # Honors an explicit AFIELD_JDWP_PORT already in the env (incl. "off"), so
        # the caller can still opt out; otherwise picks a port that is actually
        # free, because a JDWP bind failure kills the JVM outright.
        jdwp_port = resolve_jdwp_port()
        if jdwp_port is None:
            self.logger.log("jdwp_disabled", reason="no free port in scan range", preferred=DEFAULT_JDWP_PORT)
        else:
            self.logger.log("jdwp_port_selected", port=jdwp_port, preferred=DEFAULT_JDWP_PORT)

        popen_kwargs: dict[str, Any] = {
            "cwd": str(REPO_ROOT),
            "stdout": self.managed_log_file,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
            "text": True,
            "env": automation_server_environment(
                self.port,
                extra={"AFIELD_JDWP_PORT": jdwp_port if jdwp_port is not None else "off"},
            ),
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True

        self.logger.log("managed_server_spawn", command=cmd, managedLog=str(self.managed_log_path))
        self.managed_process = subprocess.Popen(cmd, **popen_kwargs)

        deadline = time.monotonic() + 120.0
        attempt = 0
        while time.monotonic() < deadline:
            attempt += 1
            if self.managed_process.poll() is not None:
                self.logger.log(
                    "managed_server_exited_early",
                    returnCode=self.managed_process.returncode,
                    managedLog=str(self.managed_log_path),
                )
                raise BackdoorError(
                    f"Managed Field server exited early with code {self.managed_process.returncode}. "
                    f"See {self.managed_log_path}."
                )
            healthy = health_check(self.port, self.ssl_context)
            self.logger.log(
                "health_check",
                phase="managed_wait",
                port=self.port,
                healthy=healthy,
                attempt=attempt,
                elapsedSeconds=round(120.0 - (deadline - time.monotonic()), 3),
            )
            if healthy:
                # Read the workspace back even on the path that supplied it. `--args=--workspace`
                # can be overridden or normalised server-side, and a fact worth verifying is
                # worth verifying in the one case where the answer is expected.
                self.adopt_server_workspace(field_instance_info(self.port, self.ssl_context))
                self.logger.log(
                    "managed_server_ready",
                    attempt=attempt,
                    managedLog=str(self.managed_log_path),
                    serverWorkspace=self.server_workspace,
                    requestedWorkspace=self.requested_workspace,
                )
                return
            await asyncio.sleep(0.5)

        self.logger.log(
            "managed_server_health_timeout",
            port=self.port,
            managedLog=str(self.managed_log_path),
        )
        raise BackdoorError(
            f"Field server did not become healthy on https://localhost:{self.port}/health within 120 seconds. "
            f"See {self.managed_log_path}."
        )

    async def connect(self) -> None:
        url = f"wss://localhost:{self.port}/ws"
        self.logger.log("websocket_connect_start", url=url)
        try:
            self.websocket = await websockets.connect(
                url,
                ssl=self.ssl_context,
                ping_interval=10,
                # A cold document can compile its box imports on the RunLoop longer than
                # 30 seconds. Keep transport liveness within the caller's startup budget;
                # individual commands still enforce their own response deadlines.
                ping_timeout=max(30.0, self.default_timeout),
                max_size=None,
            )
        except Exception as exc:
            self.logger.exception("websocket_connect_error", exc, url=url)
            raise
        self.logger.log("websocket_connect_ok", url=url)
        self.receiver_task = asyncio.create_task(self._receiver_loop(), name="field-backdoor-receiver")

    async def close(self) -> None:
        self.logger.log(
            "close_start",
            websocketOpen=self.websocket is not None,
            managedServer=self.managed_process is not None,
        )
        if self.websocket is not None and self.graphics_context_uid and self.graphics_context_uid in self.boxes:
            try:
                await self.stop_box(self.graphics_context_uid, timeout=min(self.default_timeout, 5.0))
            except Exception as exc:
                self.logger.exception("graphics_context_stop_error", exc, uid=self.graphics_context_uid)
        if self.websocket is not None:
            self.logger.log("websocket_close_start")
            await self.websocket.close()
            self.logger.log("websocket_close_ok")
            self.websocket = None
        if self.receiver_task is not None:
            try:
                await self.receiver_task
            except Exception as exc:
                self.logger.exception("receiver_task_join_error", exc)
            self.receiver_task = None
        if self.managed_process is not None:
            self._stop_managed_process()
            self.managed_process = None
        if self.managed_log_file is not None:
            self.managed_log_file.close()
            self.managed_log_file = None
        self.logger.log("close_complete")

    def _stop_managed_process(self) -> None:
        process = self.managed_process
        if process is None:
            return
        if process.poll() is not None:
            self.logger.log("managed_server_already_stopped", returnCode=process.returncode)
            return
        self.logger.log("managed_server_stop_start", pid=process.pid)
        if os.name == "nt":
            process.terminate()
            self.logger.log("managed_server_signal", signal="terminate")
        else:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                self.logger.log("managed_server_signal", signal="SIGTERM", pid=process.pid)
            except ProcessLookupError:
                self.logger.log("managed_server_missing_process", pid=process.pid)
                return
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.logger.log("managed_server_stop_timeout", pid=process.pid)
            if os.name == "nt":
                process.kill()
                self.logger.log("managed_server_signal", signal="kill")
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                    self.logger.log("managed_server_signal", signal="SIGKILL", pid=process.pid)
                except ProcessLookupError:
                    self.logger.log("managed_server_missing_process", pid=process.pid)
                    return
            process.wait(timeout=5)
        self.logger.log("managed_server_stop_complete", returnCode=process.returncode)

    async def _receiver_loop(self) -> None:
        assert self.websocket is not None
        self.logger.log("receiver_loop_start")
        try:
            async for raw in self.websocket:
                self.logger.log("ws_receive_raw", raw=raw)
                message = json.loads(raw)
                event = self._build_event(message)
                self._apply_event(event)
                acks = 1 if self._is_backpressured_output(message) else 0
                payload = event.get("payload")
                if event.get("name") == "message-batch" and isinstance(payload, dict):
                    for child in payload.get("messages") or []:
                        if isinstance(child, dict):
                            child_event = self._build_event(child)
                            child_event["batchSeq"] = event["seq"]
                            self._apply_event(child_event)
                            if self._is_backpressured_output(child):
                                acks += 1
                # The server applies per-session output back-pressure: after
                # maxPending unacked output messages it buffers, then silently
                # skips further set-output/output/peer-output. A short-lived
                # per-process backdoor never hits this, but a warm/reused session
                # (docs runner daemon) accumulates unacked outputs across captures
                # and, once saturated, stops seeing display output entirely --
                # making wait-output time out. Ack every back-pressured message so
                # the server releases the slot, exactly as the real frontend does.
                for _ in range(acks):
                    await self._send_output_ack()
                async with self.condition:
                    self.condition.notify_all()
        except Exception as exc:
            # A bare str(exc) like "no close frame received or sent" loses the fact that
            # this is an ABNORMAL socket drop (the server severed the connection without a
            # WebSocket close handshake -- almost always a dead/crashed JVM). Tag the
            # exception class, and any WebSocket close code/reason, so the docs runner's
            # both-sides diagnosis can tell a crash apart from a protocol error.
            detail = f"{type(exc).__name__}: {exc}"
            for attr in ("code", "rcvd", "sent", "rcvd_then_sent"):
                value = getattr(exc, attr, None)
                if value is not None:
                    detail += f" [{attr}={value}]"
            self.closed_reason = detail
            self.logger.exception("receiver_loop_error", exc)
            async with self.condition:
                self.condition.notify_all()
        finally:
            self.logger.log("receiver_loop_end", closedReason=self.closed_reason)

    def _build_event(self, message: dict[str, Any]) -> dict[str, Any]:
        raw_name = message.get("name")
        original_name = message.get("originalName")
        effective_name = original_name or raw_name
        payload = message.get("payload")
        self.event_seq += 1
        event = {
            "seq": self.event_seq,
            "receivedAt": time.time(),
            "messageName": raw_name,
            "name": effective_name,
            "originalName": original_name,
            "payload": payload,
        }

        reply_context = self.reply_contexts.get(raw_name)
        if reply_context is not None:
            event["replyContext"] = reply_context

        uid = None
        channel = None
        if isinstance(payload, dict):
            uid = payload.get("uid")
            channel = payload.get("channel")
        if uid is None and reply_context is not None:
            uid = reply_context.get("uid")
        if uid is not None:
            event["uid"] = uid
        if channel is not None:
            event["channel"] = channel
        if effective_name == "output" and isinstance(payload, dict):
            event["kind"] = payload.get("kind")
        self.event_log.append(event)
        self.logger.log("ws_event", eventData=event)
        return event

    @staticmethod
    def _is_backpressured_output(message: dict[str, Any]) -> bool:
        # Mirror Server.kt isBackPressuredOutputMessage: these are the messages the
        # server gates behind per-session output back-pressure and expects an
        # `output-ack` for.
        name = message.get("name")
        return name in {"set-output", "output", "peer-output"} or message.get("originalName") == "output"

    async def _send_output_ack(self) -> None:
        if self.websocket is None:
            return
        try:
            async with self._send_lock:
                await self.websocket.send(json.dumps({"name": "output-ack"}))
        except Exception as exc:  # noqa: BLE001 - an ack failure must never kill the receiver loop
            self.logger.exception("output_ack_send_error", exc)

    def _apply_event(self, event: dict[str, Any]) -> None:
        name = event["name"]
        payload = event.get("payload")
        if not isinstance(payload, dict):
            return

        if name in {"agent-open-document", "agent-snapshot"}:
            self._replace_snapshot(payload)
            return

        if name == "agent-create-box":
            box = payload.get("box")
            if isinstance(box, dict):
                self._upsert_box_from_snapshot(box)
                self.last_box_uid = box.get("uid")
            self.document_name = payload.get("documentName", self.document_name)
            self.scratch = bool(payload.get("scratch", self.scratch))
            return

        if name == "agent-open-playwright":
            self.playwright_state = dict(payload)
            self.document_name = payload.get("documentName", self.document_name)
            self.scratch = bool(payload.get("scratch", self.scratch))
            self.session_key = payload.get("sessionKey", self.session_key)
            return

        uid = event.get("uid")
        if uid is None:
            return

        error_key = _execution_error_key(event)
        if error_key is not None:
            if _is_error_clear_event(event):
                self._active_execution_errors.pop(error_key, None)
            elif _is_execution_error_event(event):
                self._active_execution_errors[error_key] = event

        if name == "set-string":
            box = self._ensure_box(uid)
            prop = payload.get("property")
            value = payload.get("value")
            if prop == "_tombstone" and self._truthy(value):
                self._drop_box(uid)
                return
            if prop == "name":
                box["name"] = value
            else:
                box[prop] = value
        elif name == "set-rect" and payload.get("property") == "frame":
            box = self._ensure_box(uid)
            box["frame"] = payload.get("value")
        elif name == "set-rects":
            values = payload.get("values") or []
            for value in values:
                if isinstance(value, dict) and value.get("property") == "frame":
                    box = self._ensure_box(value.get("uid"))
                    box["frame"] = value.get("value")
        elif name == "set-number":
            box = self._ensure_box(uid)
            box[payload.get("property")] = payload.get("number")
        elif name == "set-boolean":
            box = self._ensure_box(uid)
            prop = payload.get("property")
            value = payload.get("number")
            if prop == "_tombstone" and self._truthy(value):
                self._drop_box(uid)
                return
            box[prop] = value
        elif name == "set-null":
            box = self._ensure_box(uid)
            prop = payload.get("property")
            box[prop] = None
        elif name == "set-topology":
            box = self._ensure_box(uid)
            kind = payload.get("kind")
            value = payload.get("value") or []
            if kind == "box_children":
                box["children"] = value
            elif kind == "box_nested":
                box["nested"] = value
            elif kind == "box_nestedIn":
                box["nestedIn"] = value[0] if value else None
        elif name == "describe-action":
            value = payload.get("value") or []
            box = self._ensure_box(uid)
            box["actionDescriptions"] = {
                str(item.get("id")): dict(item)
                for item in value
                if isinstance(item, dict) and item.get("id") is not None
            }
        elif name == "is-running":
            box = self._ensure_box(uid)
            strand = payload.get("strand", "default")
            watermark = payload.get("watermark")
            running = box.setdefault("runningStrands", {})
            current = running.get(strand)
            is_running = bool(payload.get("isRunning"))
            if isinstance(uid, str) and isinstance(watermark, int):
                key = (uid, strand)
                seen = self._running_state_seen.get(key)
                if seen is not None:
                    seen_watermark, seen_running = seen
                    if watermark < seen_watermark or (
                        watermark == seen_watermark and not seen_running and is_running
                    ):
                        return
                self._running_state_seen[key] = (watermark, is_running)
            if is_running:
                running[strand] = watermark
            else:
                if current is None or (watermark is not None and current <= watermark):
                    running.pop(strand, None)
        elif name == "agent-open-document":
            self._replace_snapshot(payload)

    def _replace_snapshot(self, snapshot: dict[str, Any]) -> None:
        previous_identity = (self.session_key, self.document_name)
        next_session_key = snapshot.get("sessionKey")
        next_document_name = snapshot.get("documentName")
        self.document_name = next_document_name
        self.scratch = bool(snapshot.get("scratch"))
        self.execution_verdicts = bool(snapshot.get("executionVerdicts"))
        # Reported by the server rather than echoed back from the request, so a caller can tell
        # that read-only was actually honoured.
        self.read_only = bool(snapshot.get("readOnly"))
        # Kept as None when the key is absent, because absent means "this Field predates the
        # field" and must not collapse into "the document was not there". `bool()` here would
        # have thrown that distinction away exactly as the missing @EncodeDefault did.
        self.document_existed = snapshot.get("documentExisted")
        self.attached_to_live_document = snapshot.get("attachedToLiveDocument")
        self.session_key = next_session_key
        self.boxes = {}
        self._running_state_seen = {}
        if previous_identity != (next_session_key, next_document_name):
            self._active_execution_errors = {}
        for box in snapshot.get("boxes", []):
            if isinstance(box, dict):
                if self._truthy(box.get("_tombstone")):
                    uid = box.get("uid")
                    if isinstance(uid, str):
                        self._drop_box(uid)
                    continue
                self._upsert_box_from_snapshot(box)
        if self.boxes:
            self.last_box_uid = next(reversed(self.boxes))
        self.box_aliases = {
            alias: uid
            for alias, uid in self.box_aliases.items()
            if uid in self.boxes
        }
        if self.graphics_context_uid not in self.boxes:
            self.graphics_context_uid = None
        self.logger.log(
            "snapshot_replaced",
            documentName=self.document_name,
            scratch=self.scratch,
            sessionKey=self.session_key,
            boxCount=len(self.boxes),
            lastBoxUid=self.last_box_uid,
            aliases=self.box_aliases,
        )

    def _truthy(self, value: Any) -> bool:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value != 0
        if isinstance(value, str):
            return value.strip().lower() in {"true", "yes", "1", "on"}
        return bool(value)

    def _drop_box(self, uid: str) -> None:
        self.boxes.pop(uid, None)
        self._running_state_seen = {
            key: value for key, value in self._running_state_seen.items() if key[0] != uid
        }
        self._active_execution_errors = {
            key: value for key, value in self._active_execution_errors.items() if key[0] != uid
        }
        self.box_aliases = {
            alias: target
            for alias, target in self.box_aliases.items()
            if target != uid
        }
        for box in self.boxes.values():
            for key in ("children", "parents", "nested"):
                value = box.get(key)
                if isinstance(value, list):
                    box[key] = [item for item in value if item != uid]
            if box.get("nestedIn") == uid:
                box["nestedIn"] = None
        if self.last_box_uid == uid:
            self.last_box_uid = next(reversed(self.boxes), None)

    async def open_playwright(self, timeout: float) -> dict[str, Any]:
        self.logger.log("open_playwright_start", timeout=timeout, documentName=self.document_name, scratch=self.scratch, sessionKey=self.session_key)
        event = await self.call_for_single_reply(
            "agent-open-playwright",
            {},
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-open-playwright returned an invalid payload")
        self.playwright_state = dict(payload)
        if not payload.get("ok", False):
            message = payload.get("error") or "Playwright failed without an error message"
            raise BackdoorError(message)
        self.logger.log("open_playwright_complete", payload=payload)
        return payload

    async def open_editor(
        self,
        *,
        uid: str,
        property_name: str,
        open_outputs: bool,
        padding: float,
        pan_into_view: bool,
        timeout: float,
    ) -> dict[str, Any]:
        resolved_uid, _ = self.require_box_reference(uid)
        self.logger.log(
            "open_editor_start",
            uid=resolved_uid,
            property=property_name,
            openOutputs=open_outputs,
            padding=padding,
            panIntoView=pan_into_view,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-open-editor",
            {
                "uid": resolved_uid,
                "property": property_name,
                "panIntoView": pan_into_view,
                "openOutputs": open_outputs,
                "padding": padding,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-open-editor returned an invalid payload")
        self.logger.log("open_editor_complete", payload=payload)
        return payload

    async def playwright_input(
        self,
        *,
        action: str,
        timeout: float,
        key: str | None = None,
        selector: str | None = None,
        uid: str | None = None,
        dx: float = 0.0,
        dy: float = 0.0,
        hold: bool = False,
        steps: int = 12,
        filename: str | None = None,
        script: str | None = None,
        script_mode: str = "expression",
        wait_ms: float = 0.0,
    ) -> dict[str, Any]:
        resolved_uid = self.require_box_reference(uid)[0] if uid is not None else None
        self.logger.log(
            "playwright_input_start",
            action=action,
            key=key,
            selector=selector,
            uid=resolved_uid,
            dx=dx,
            dy=dy,
            hold=hold,
            steps=steps,
            filename=filename,
            scriptLength=len(script) if script is not None else None,
            scriptMode=script_mode,
            waitMs=wait_ms,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-playwright-input",
            {
                "action": action,
                "key": key,
                "selector": selector,
                "uid": resolved_uid,
                "dx": dx,
                "dy": dy,
                "hold": hold,
                "steps": steps,
                "filename": filename,
                "script": script,
                "scriptMode": script_mode,
                "waitMs": wait_ms,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-playwright-input returned an invalid payload")
        self.logger.log("playwright_input_complete", payload=payload)
        return payload

    async def screenshot_window(
        self,
        *,
        path: str,
        uid: str | None,
        property_name: str,
        show_editor: bool,
        editor_only: bool,
        open_outputs: bool,
        expect_outputs: str = "panel",
        padding: float,
        pan_into_view: bool,
        wait_ms: float,
        timeout: float,
        preserve_selection: bool = False,
        include_notifications: bool = False,
    ) -> dict[str, Any]:
        resolved_uid = self.require_box_reference(uid)[0] if uid is not None else None
        normalized_path = str(Path(path).expanduser().resolve())
        if show_editor and resolved_uid is None:
            raise BackdoorError("screenshot-window --with-editor requires --uid")
        if editor_only and resolved_uid is None:
            raise BackdoorError("screenshot-editor requires --uid")
        self.logger.log(
            "screenshot_window_start",
            path=normalized_path,
            uid=resolved_uid,
            property=property_name,
            showEditor=show_editor,
            editorOnly=editor_only,
            openOutputs=open_outputs,
            expectOutputs=expect_outputs,
            padding=padding,
            panIntoView=pan_into_view,
            waitMs=wait_ms,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-window-screenshot",
            {
                "path": normalized_path,
                "uid": resolved_uid,
                "property": property_name,
                "panIntoView": pan_into_view,
                "showEditor": show_editor,
                "openOutputs": open_outputs,
                "expectOutputs": expect_outputs,
                "editorOnly": editor_only,
                "padding": padding,
                "waitMs": wait_ms,
                "preserveSelection": preserve_selection,
                "includeNotifications": include_notifications,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-window-screenshot returned an invalid payload")
        self.logger.log("screenshot_window_complete", payload=payload)
        return payload

    async def screenshot_box(
        self,
        *,
        uid: str,
        path: str,
        padding: float,
        pan_into_view: bool,
        open_outputs: bool,
        expect_outputs: str = "panel",
        timeout: float,
        preserve_selection: bool = False,
        include_notifications: bool = False,
    ) -> dict[str, Any]:
        resolved_uid, _ = self.require_box_reference(uid)
        normalized_path = str(Path(path).expanduser().resolve())
        self.logger.log(
            "screenshot_box_start",
            path=normalized_path,
            uid=resolved_uid,
            padding=padding,
            panIntoView=pan_into_view,
            openOutputs=open_outputs,
            expectOutputs=expect_outputs,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-box-screenshot",
            {
                "uid": resolved_uid,
                "path": normalized_path,
                "padding": padding,
                "panIntoView": pan_into_view,
                "openOutputs": open_outputs,
                "expectOutputs": expect_outputs,
                "preserveSelection": preserve_selection,
                "includeNotifications": include_notifications,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-box-screenshot returned an invalid payload")
        self.logger.log("screenshot_box_complete", payload=payload)
        return payload

    async def native_window_screenshot(
        self,
        *,
        path: str,
        wait_frames: int,
        timeout: float,
        uid: str | None = None,
    ) -> dict[str, Any]:
        self.logger.log(
            "native_window_screenshot_start",
            path=path,
            waitFrames=wait_frames,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-native-window-screenshot",
            {
                "path": path,
                "waitFrames": wait_frames,
                "captureTimeoutMs": max(1, math.ceil(timeout * 1000)),
                "uid": self.require_box_reference(uid)[0] if uid is not None else None,
            },
            # The authored budget bounds server work; allow its terminal reply to
            # arrive instead of racing the server's timeout at the same deadline.
            timeout=timeout + 1.0,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-native-window-screenshot returned an invalid payload")
        self.logger.log("native_window_screenshot_complete", payload=payload)
        return payload

    async def wait_native_renderer(
        self,
        *,
        uid: str | None,
        frames: int,
        timeout: float,
    ) -> dict[str, Any]:
        resolved_uid = self.require_box_reference(uid)[0] if uid is not None else None
        safe_frames = max(1, int(frames))
        deadline = time.monotonic() + timeout
        wait_path = LOG_ROOT / f"{self.session_id}-native-renderer-ready.png"
        last_payload: dict[str, Any] | None = None
        last_error: str | None = None
        attempts = 0
        wait_path.parent.mkdir(parents=True, exist_ok=True)
        self.logger.log(
            "wait_native_renderer_start",
            uid=resolved_uid,
            frames=safe_frames,
            timeout=timeout,
            path=str(wait_path),
        )

        def terminal_error() -> dict[str, Any] | None:
            # Without a target uid there is no defensible way to attribute a box error to
            # this renderer. Use the same active-error/clear semantics as wait-output.
            event = self._execution_error_event(resolved_uid) if resolved_uid is not None else None
            if event is None:
                return None
            problems = self.execution_errors([event])
            result = {
                "ok": False,
                "uid": resolved_uid,
                "frames": safe_frames,
                "attempts": attempts,
                "path": str(wait_path),
                "message": problems[0] if problems else "Renderer box reported an execution error",
                "error": problems[0] if problems else "Renderer box reported an execution error",
                "errorEvent": event,
                "terminal": True,
                "screenshot": last_payload,
            }
            self.logger.log("wait_native_renderer_failed", **result)
            return result

        while time.monotonic() < deadline:
            if failure := terminal_error():
                return failure
            attempts += 1
            remaining = max(0.0, deadline - time.monotonic())
            capture = asyncio.create_task(self.native_window_screenshot(
                path=str(wait_path), wait_frames=safe_frames, timeout=remaining, uid=resolved_uid,
            ))
            try:
                # A screenshot reply may itself take the remaining budget. Observe box
                # errors while it is in flight, not just between screenshot attempts.
                while not capture.done():
                    if failure := terminal_error():
                        return failure
                    await asyncio.wait({capture}, timeout=min(0.05, max(0.0, deadline - time.monotonic())))
                    if time.monotonic() >= deadline and not capture.done():
                        raise asyncio.TimeoutError("Timed out waiting for native renderer")
                if failure := terminal_error():
                    return failure
                payload = await capture
                last_payload = payload
                if payload.get("terminal"):
                    result = {
                        "ok": False, "uid": resolved_uid, "frames": safe_frames,
                        "attempts": attempts, "path": str(wait_path),
                        "message": payload.get("message", "Native renderer capture failed"),
                        "error": payload.get("message", "Native renderer capture failed"),
                        "terminal": True, "screenshot": payload,
                    }
                    self.logger.log("wait_native_renderer_failed", **result)
                    return result
                if payload.get("ok") and wait_path.exists() and wait_path.stat().st_size > 0:
                    result = {
                        "ok": True,
                        "uid": resolved_uid,
                        "frames": safe_frames,
                        "attempts": attempts,
                        "path": str(wait_path),
                        "message": "",
                        "screenshot": payload,
                    }
                    self.logger.log("wait_native_renderer_complete", **result)
                    return result
                last_error = str(payload.get("message") or "native renderer screenshot was not ready")
            except Exception as exc:
                last_error = str(exc)
                self.logger.log(
                    "wait_native_renderer_attempt_error",
                    attempt=attempts,
                    error=last_error,
                )
            finally:
                if not capture.done():
                    capture.cancel()
                # Retrieve exceptions too, including a reply racing a terminal box error.
                await asyncio.gather(capture, return_exceptions=True)
            await asyncio.sleep(0.05)

        result = {
            "ok": False,
            "uid": resolved_uid,
            "frames": safe_frames,
            "attempts": attempts,
            "path": str(wait_path),
            "message": last_error or "Timed out waiting for native renderer",
            "screenshot": last_payload,
        }
        self.logger.log("wait_native_renderer_timeout", **result)
        return result

    async def record_window(
        self,
        *,
        path: str,
        uid: str | None,
        property_name: str,
        show_editor: bool,
        editor_only: bool,
        open_outputs: bool,
        expect_outputs: str = "panel",
        padding: float,
        pan_into_view: bool,
        duration: float,
        fps: int,
        frame_synchronous: bool,
        restart_target: bool,
        timeout: float,
    ) -> dict[str, Any]:
        resolved_uid = self.require_box_reference(uid)[0] if uid is not None else None
        normalized_path = str(Path(path).expanduser().resolve())
        if show_editor and resolved_uid is None:
            raise BackdoorError("record-window --with-editor requires --uid")
        if editor_only and resolved_uid is None:
            raise BackdoorError("record-editor requires --uid")
        self.logger.log(
            "record_window_start",
            path=normalized_path,
            uid=resolved_uid,
            property=property_name,
            showEditor=show_editor,
            editorOnly=editor_only,
            openOutputs=open_outputs,
            expectOutputs=expect_outputs,
            padding=padding,
            panIntoView=pan_into_view,
            duration=duration,
            fps=fps,
            frameSynchronous=frame_synchronous,
            restartTarget=restart_target,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-window-video",
            {
                "path": normalized_path,
                "uid": resolved_uid,
                "property": property_name,
                "panIntoView": pan_into_view,
                "showEditor": show_editor,
                "openOutputs": open_outputs,
                "expectOutputs": expect_outputs,
                "editorOnly": editor_only,
                "padding": padding,
                "durationSec": duration,
                "fps": fps,
                "frameSynchronous": frame_synchronous,
                "restartTarget": restart_target,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-window-video returned an invalid payload")
        self.logger.log("record_window_complete", payload=payload)
        return payload

    async def record_box(
        self,
        *,
        uid: str,
        path: str,
        property_name: str,
        padding: float,
        pan_into_view: bool,
        duration: float,
        fps: int,
        frame_synchronous: bool,
        restart_target: bool,
        timeout: float,
    ) -> dict[str, Any]:
        resolved_uid, _ = self.require_box_reference(uid)
        normalized_path = str(Path(path).expanduser().resolve())
        self.logger.log(
            "record_box_start",
            path=normalized_path,
            uid=resolved_uid,
            property=property_name,
            padding=padding,
            panIntoView=pan_into_view,
            duration=duration,
            fps=fps,
            frameSynchronous=frame_synchronous,
            restartTarget=restart_target,
            timeout=timeout,
        )
        event = await self.call_for_single_reply(
            "agent-box-video",
            {
                "uid": resolved_uid,
                "path": normalized_path,
                "property": property_name,
                "padding": padding,
                "panIntoView": pan_into_view,
                "openOutputs": True,
                "durationSec": duration,
                "fps": fps,
                "frameSynchronous": frame_synchronous,
                "restartTarget": restart_target,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-box-video returned an invalid payload")
        self.logger.log("record_box_complete", payload=payload)
        return payload

    def _upsert_box_from_snapshot(self, box_snapshot: dict[str, Any]) -> None:
        uid = box_snapshot.get("uid")
        if uid is None:
            return
        self.boxes[uid] = dict(box_snapshot)

    def _ensure_box(self, uid: str | None) -> dict[str, Any]:
        if uid is None:
            raise BackdoorError("Missing box uid in message")
        box = self.boxes.get(uid)
        if box is None:
            box = {
                "uid": uid,
                "name": "",
                "frame": None,
                "runningStrands": {},
                "children": [],
                "nested": [],
                "nestedIn": None,
            }
            self.boxes[uid] = box
        return box

    def clear_events(self) -> None:
        self.logger.log("events_cleared", previousCount=len(self.event_log))
        self.event_log.clear()

    def events_since(self, seq: int) -> list[dict[str, Any]]:
        return [event for event in self.event_log if event["seq"] > seq]

    async def wait_for(self, predicate, timeout: float, description: str):
        deadline = time.monotonic() + timeout
        self.logger.log("wait_for_start", description=description, timeout=timeout)
        while True:
            result = predicate()
            if result is not None:
                self.logger.log(
                    "wait_for_complete",
                    description=description,
                    elapsedSeconds=round(timeout - (deadline - time.monotonic()), 3),
                )
                return result
            if self.receiver_task is not None and self.receiver_task.done():
                self.logger.log("wait_for_receiver_stopped", description=description, closedReason=self.closed_reason)
                raise BackdoorError(self.closed_reason or "WebSocket receiver stopped")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                self.logger.log("wait_for_timeout", description=description, timeout=timeout)
                raise asyncio.TimeoutError()
            async with self.condition:
                await asyncio.wait_for(self.condition.wait(), remaining)

    async def send_message(self, name: str, payload: Any = None, *, reply_to: str | None = None, reply_context: dict[str, Any] | None = None) -> str:
        if self.websocket is None:
            raise BackdoorError("Not connected to Field")
        uid = f"py_{uuid.uuid4().hex}"
        message: dict[str, Any] = {"name": name, "uid": uid}
        if reply_to is not None:
            message["replyTo"] = reply_to
            if reply_context is not None:
                self.reply_contexts[reply_to] = dict(reply_context)
        if payload is not None:
            message["payload"] = payload
        self.logger.log("ws_send", message=message)
        async with self._send_lock:
            await self.websocket.send(json.dumps(message))
        return uid

    async def call_for_single_reply(self, name: str, payload: Any, *, timeout: float, reply_context: dict[str, Any] | None = None) -> dict[str, Any]:
        reply_name = f"reply_{uuid.uuid4().hex}"
        start_seq = self.event_seq
        self.logger.log("call_for_single_reply_start", name=name, replyName=reply_name, timeout=timeout)
        await self.send_message(name, payload, reply_to=reply_name, reply_context=reply_context)

        def predicate():
            for event in self.events_since(start_seq):
                if event["messageName"] == reply_name:
                    return event
            return None

        try:
            return await self.wait_for(predicate, timeout, f"reply:{name}:{reply_name}")
        finally:
            self.reply_contexts.pop(reply_name, None)
            self.logger.log("call_for_single_reply_end", name=name, replyName=reply_name)

    async def open_document(
        self,
        document: str | None,
        *,
        scratch: bool,
        timeout: float,
        read_only: bool = False,
        must_exist: bool = False,
        keep_running: bool = False,
    ) -> dict[str, Any]:
        # keep_running asks Field to leave this named document's windows and running boxes up
        # after this client disconnects (by default a document's last client leaving cleans them
        # up), so a later invocation finds the window it left. Ignored for scratch documents.
        # read_only is not scratch. scratch throws the document name away and synthesises a new
        # directory under ~/.agent-scratch/; read_only opens the real named document with Field's
        # write-back path switched off, which is what inspecting a demo committed to a repository
        # needs. See ReadOnlyDocuments in Server.kt.
        self.logger.log(
            "open_document_start", document=document, scratch=scratch, readOnly=read_only, timeout=timeout
        )
        # The third known cause of an inexplicable `boxes: []` / `boxCount: 0`. The server
        # resolves a document name as `workspace + "/" + name` unconditionally, so an absolute
        # path produces a nonsense session key with the workspace glued in front of it -- an
        # empty new document that will never be the one you meant. Cheap to say out loud here;
        # `status` now also shows it, because `sessionKey` can be read against `serverWorkspace`.
        if document and not scratch and (os.path.isabs(document) or os.sep in document):
            self.logger.log("open_document_absolute_name", document=document)
            print(
                f"field_backdoor.py: --document {document} is an absolute path, and document names "
                "are resolved relative to the server's workspace. Split it: --workspace "
                f"{os.path.dirname(document)} --document {os.path.basename(document)} when this "
                "session starts the server, or make --document relative to serverWorkspace when "
                "attaching.",
                file=sys.stderr,
                flush=True,
            )
        event = await self.call_for_single_reply(
            "agent-open-document",
            {
                "documentName": document,
                "scratch": scratch,
                "username": "agent",
                "readOnly": read_only,
                "keepRunning": keep_running,
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-open-document returned an invalid payload")
        self._replace_snapshot(payload)
        self.clear_events()
        self.logger.log(
            "open_document_complete",
            documentName=self.document_name,
            scratch=self.scratch,
            readOnly=self.read_only,
        )
        result = dict(payload)
        if self.scratch:
            result["scratchLifetime"] = "invocation"
            result["scratchPortReuse"] = False
        # `documentExisted` is the fourth and last cause of an inexplicable `boxes: []`, and the
        # only one the tool used to be unable to name: opening a document that is not there is
        # how a document gets CREATED in Field, so the server answers `ok` either way and an
        # agent that meant to open `alife_walls` receives a new empty one that looks identical
        # to a load that worked. Absent (None) means the server predates the field -- unverified,
        # not fine, exactly like `serverWorkspace`.
        existed = payload.get("documentExisted")
        if existed is None:
            result["documentExistedKnown"] = False
        elif not self.scratch and not existed:
            result["createdNewDocument"] = True
            # Silent under --must-exist: the caller has already said this must not happen and is
            # about to get a refusal that says the same thing, so advising them to pass the flag
            # they just passed is noise on top of the real message.
            if not must_exist:
                print(
                    f"field_backdoor.py: --document {self.document_name} did not exist in the "
                    f"server's workspace; Field created a new EMPTY document at "
                    f"{self.session_key}. If you meant to open an existing one, check "
                    "`serverWorkspace` in `status` and the spelling of the name; pass "
                    "`open --must-exist` to make this a hard error.",
                    file=sys.stderr,
                    flush=True,
                )
        return result

    async def refresh_snapshot(self, timeout: float) -> dict[str, Any]:
        self.logger.log("refresh_snapshot_start", timeout=timeout)
        event = await self.call_for_single_reply(
            "agent-snapshot",
            {},
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-snapshot returned an invalid payload")
        self._replace_snapshot(payload)
        self.logger.log("refresh_snapshot_complete", boxCount=len(self.boxes))
        return payload

    async def create_box(
        self,
        *,
        name: str,
        x: float,
        y: float,
        w: float,
        h: float,
        timeout: float,
        properties: dict[str, Any] | None = None,
        auto_graphics_parent: bool = True,
    ) -> dict[str, Any]:
        self.logger.log("create_box_start", name=name, frame={"x": x, "y": y, "w": w, "h": h}, timeout=timeout)
        event = await self.call_for_single_reply(
            "agent-create-box",
            {
                "name": name,
                "frame": {"x": x, "y": y, "w": w, "h": h},
                "properties": properties or {},
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-create-box returned an invalid payload")
        box = payload.get("box")
        if isinstance(box, dict):
            self._upsert_box_from_snapshot(box)
            self.last_box_uid = box.get("uid")
            created_uid = box.get("uid")
        else:
            created_uid = None
        if (
            auto_graphics_parent
            and self.graphics_context_enabled
            and self.graphics_context_uid
            and isinstance(created_uid, str)
            and created_uid
            and created_uid != self.graphics_context_uid
        ):
            _, _, timed_out = await self.change_connections(
                action="connect",
                parent_uid=self.graphics_context_uid,
                child_uids=[created_uid],
                timeout=timeout,
            )
            if timed_out:
                raise BackdoorError(f"Timed out connecting {created_uid} to graphics context {self.graphics_context_uid}")
        self.logger.log("create_box_complete", box=box, lastBoxUid=self.last_box_uid)
        return payload

    async def ensure_graphics_context(self, *, timeout: float) -> dict[str, Any]:
        if not self.graphics_context_enabled:
            raise BackdoorError("Graphics context mode is not enabled")
        if self.graphics_context_uid and self.graphics_context_uid in self.boxes:
            box = self.boxes[self.graphics_context_uid]
            payload = {
                "ok": True,
                "uid": self.graphics_context_uid,
                "name": box.get("name") or GRAPHICS_CONTEXT_BOX_NAME,
                "reused": True,
                "documentName": self.document_name,
                "scratch": self.scratch,
                "sessionKey": self.session_key,
            }
            self.logger.log("graphics_context_reused", payload=payload)
            return payload

        event = await self.call_for_single_reply(
            "agent-ensure-graphics-context",
            {},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-ensure-graphics-context returned an invalid payload")
        if not payload.get("ok", False):
            raise BackdoorError(str(payload.get("error") or "Graphics context setup failed"))
        box = payload.get("box")
        uid = box.get("uid") if isinstance(box, dict) else None
        if not isinstance(uid, str) or not uid:
            raise BackdoorError("Graphics context setup did not return a box uid")
        self._upsert_box_from_snapshot(box)
        self.graphics_context_uid = uid
        if GRAPHICS_CONTEXT_WAIT_SEC > 0:
            await asyncio.sleep(GRAPHICS_CONTEXT_WAIT_SEC)
        result = {
            "ok": True,
            "uid": uid,
            "name": box.get("name") or GRAPHICS_CONTEXT_BOX_NAME,
            "reused": bool(payload.get("reused", False)),
            "documentName": payload.get("documentName", self.document_name),
            "scratch": bool(payload.get("scratch", self.scratch)),
            "sessionKey": self.session_key,
            "waitSec": GRAPHICS_CONTEXT_WAIT_SEC,
        }
        self.logger.log("graphics_context_ready", payload=result)
        return result

    async def dispose_graphics_context(self, *, timeout: float) -> dict[str, Any]:
        event = await self.call_for_single_reply(
            "agent-dispose-graphics-context",
            {},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-dispose-graphics-context returned an invalid payload")
        if not payload.get("ok", False):
            raise BackdoorError(str(payload.get("error") or "Graphics context dispose failed"))
        disposed = bool(payload.get("reused", False))
        previous_uid = self.graphics_context_uid
        self.graphics_context_uid = None
        result = {
            "ok": True,
            "disposed": disposed,
            "uid": previous_uid,
            "documentName": payload.get("documentName", self.document_name),
            "scratch": bool(payload.get("scratch", self.scratch)),
            "sessionKey": self.session_key,
        }
        self.logger.log("graphics_context_disposed", payload=result)
        return result

    def assign_box_alias(self, alias: str, uid: str) -> None:
        cleaned = alias.strip()
        if not cleaned:
            raise BackdoorError("box alias must not be empty")
        self.box_aliases[cleaned] = uid
        self.logger.log("box_alias_assigned", alias=cleaned, uid=uid)

    def resolve_uid(self, token: str) -> str:
        if token == "@last":
            if not self.last_box_uid:
                raise BackdoorError("@last is not available yet")
            return self.last_box_uid
        if token.startswith("@"):
            alias = token[1:]
            if alias in self.box_aliases:
                return self.box_aliases[alias]
        return token

    def resolve_box_token(self, token: Any) -> str:
        if token is None:
            raise BackdoorError("operation is missing a box uid/name")
        text = str(token).strip()
        if not text:
            raise BackdoorError("operation has an empty box uid/name")
        resolved = self.resolve_uid(text)
        if resolved in self.boxes:
            return resolved
        if text in self.box_aliases:
            return self.box_aliases[text]

        prefix_matches = [uid for uid in self.boxes if uid.startswith(text)]
        if len(prefix_matches) == 1:
            return prefix_matches[0]

        name_matches = [
            uid
            for uid, box in self.boxes.items()
            if str(box.get("name") or "") == text
        ]
        if len(name_matches) == 1:
            return name_matches[0]
        if len(name_matches) > 1:
            raise BackdoorError(f"Box name {text!r} is ambiguous ({len(name_matches)} matches)")

        lowered = text.lower()
        folded_matches = [
            uid
            for uid, box in self.boxes.items()
            if str(box.get("name") or "").lower() == lowered
        ]
        if len(folded_matches) == 1:
            return folded_matches[0]
        if len(folded_matches) > 1:
            raise BackdoorError(f"Box name {text!r} is ambiguous ({len(folded_matches)} case-insensitive matches)")

        return resolved

    def get_box(self, uid: str) -> dict[str, Any]:
        _, box = self.require_box_reference(uid)
        return box

    def require_box_reference(self, token: str) -> tuple[str, dict[str, Any]]:
        resolved = self.resolve_box_token(token)
        box = self.boxes.get(resolved)
        if box is None:
            detail = f"Unknown box uid: {resolved}."
            if self.scratch:
                detail += (
                    " Scratch documents last for one field_backdoor.py invocation; "
                    "reusing --port does not reopen the previous scratch document. "
                    "Combine dependent commands in one invocation or use --document <name>."
                )
            raise BackdoorError(detail)
        return resolved, box

    def validate_box_reference(self, token: str) -> str:
        resolved = self.resolve_box_token(token)
        if resolved == "root":
            return resolved
        validated, _ = self.require_box_reference(resolved)
        return validated

    def _set_local_string(self, uid: str, property_name: str, value: str) -> None:
        box = self._ensure_box(uid)
        if property_name == "name":
            box["name"] = value
        else:
            box[property_name] = value

    def read_text_source(self, source: str, *, command_line: str) -> str:
        if source == "-":
            if self.stdin_cache is None:
                self.logger.log("command_read_stdin_start", commandLine=command_line)
                self.stdin_cache = sys.stdin.read()
                self.logger.log(
                    "command_read_stdin_complete",
                    commandLine=command_line,
                    length=len(self.stdin_cache),
                    cached=False,
                )
            else:
                self.logger.log(
                    "command_read_stdin_complete",
                    commandLine=command_line,
                    length=len(self.stdin_cache),
                    cached=True,
                )
            return self.stdin_cache

        self.logger.log("command_read_file", commandLine=command_line, path=source)
        return Path(source).read_text(encoding="utf-8")

    async def set_string(self, uid: str, property_name: str, value: str) -> dict[str, Any]:
        resolved, _ = self.require_box_reference(uid)
        self.logger.log("set_string_start", uid=resolved, property=property_name, valueLength=len(value))
        self._set_local_string(resolved, property_name, value)
        await self.send_message(
            "set-string",
            {"uid": resolved, "property": property_name, "value": value},
        )
        self.logger.log("set_string_complete", uid=resolved, property=property_name, valueLength=len(value))
        return {"uid": resolved, "property": property_name, "value": value}

    async def set_frame(self, uid: str, frame: dict[str, float]) -> dict[str, Any]:
        resolved, box = self.require_box_reference(uid)
        self.logger.log("set_frame_start", uid=resolved, frame=frame)
        box["frame"] = dict(frame)
        await self.send_message(
            "set-rect",
            {"uid": resolved, "property": "frame", "value": frame},
        )
        self.logger.log("set_frame_complete", uid=resolved, frame=frame)
        return {"uid": resolved, "property": "frame", "value": frame}

    async def wait_frames(self, frames: int, *, timeout: float) -> dict[str, Any]:
        safe_frames = max(0, int(frames))
        event = await self.call_for_single_reply(
            "agent-wait-frames",
            {"frames": safe_frames, "timeoutSec": timeout},
            timeout=timeout + 2.0,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-wait-frames returned an invalid payload")
        return payload

    async def macro_transcript_command(
        self,
        *,
        command: str,
        name: str = "",
        label: str = "",
        selection: list[str] | None = None,
        scope: str = "document",
        project_name: str = "",
        content: str = "",
        offset: int = 0,
        limit: int = 80,
        indices: list[int] | None = None,
        mode: str = "",
        fail_on_error: bool = True,
        timeout: float,
    ) -> dict[str, Any]:
        event = await self.call_for_single_reply(
            "macro-transcript-command",
            {
                "command": command,
                "name": name,
                "label": label,
                "selection": selection or [],
                "scope": scope,
                "projectName": project_name,
                "content": content,
                "offset": offset,
                "limit": limit,
                "indices": indices or [],
                "mode": mode,
            },
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("macro-transcript-command returned an invalid payload")
        # Macro execution mutates the server-side document on a background dispatcher. Its reply
        # can reach this client before the asynchronous topology/property events that update our
        # local box index. Refresh from source of truth before the next command resolves a uid;
        # otherwise a successful create followed immediately by `set <new-box> ...` races and
        # reports "Unknown box uid" even though the box appears milliseconds later.
        if command.strip().lower() in {"run", "undo"} and payload.get("success") is True:
            await self.refresh_snapshot(timeout=timeout)
        if command.strip().lower() in {"run", "undo"}:
            # The server's success counts applied operations. A launch operation can apply
            # successfully while its box fails; MacroOutputLine carries that diagnostic.
            output = payload.get("output") or []
            events = [
                {"name": "output", "uid": line.get("uid"), "payload": line}
                for line in output if isinstance(line, dict)
            ]
            problems = self.execution_errors(events)
            if problems:
                payload = dict(payload)
                payload["operationSuccess"] = bool(payload.get("success"))
                payload["executionErrors"] = problems
                payload["errors"] = list(dict.fromkeys([*(payload.get("errors") or []), *problems]))
                payload["error"] = "\n".join(problems)
                if fail_on_error:
                    payload["success"] = False
                    payload["message"] = f"{payload.get('message', '')} Execution failed: {problems[0]}".strip()
                else:
                    payload["executionErrorsIgnored"] = True
        return payload

    async def _run_probe(
        self,
        *,
        message_name: str,
        payload: dict[str, Any],
        reply_context: dict[str, Any] | None,
        timeout: float,
        log_fields: dict[str, Any],
    ) -> dict[str, Any]:
        """Send a probe message and collect its streamed events until terminal.

        Both probe handlers (``debug-probe`` / ``debug-source-probe``) stream
        ``debug-probe-event`` messages (ack, sample*, then a terminal complete or
        error). The server renames each reply onto our reply channel (``toName``),
        so we match on ``messageName == reply_name`` and return the payload of the
        one flagged ``done`` — which carries the structured ``samples`` / ``scope``
        / ``error`` mirror of the editor's HTML card.
        """
        reply_name = f"reply_{uuid.uuid4().hex}"
        start_seq = self.event_seq
        self.logger.log("debug_probe_start", replyName=reply_name, timeout=timeout, **log_fields)
        await self.send_message(
            message_name, payload, reply_to=reply_name, reply_context=reply_context
        )

        def predicate():
            terminal = None
            for event in self.events_since(start_seq):
                if event["messageName"] != reply_name:
                    continue
                event_payload = event.get("payload")
                if isinstance(event_payload, dict) and event_payload.get("done"):
                    terminal = event
            return terminal

        try:
            event = await self.wait_for(predicate, timeout, f"{message_name}:{reply_name}")
        finally:
            self.reply_contexts.pop(reply_name, None)
            self.logger.log("debug_probe_end", replyName=reply_name)

        result = event.get("payload")
        if not isinstance(result, dict):
            raise BackdoorError(f"{message_name} returned an invalid payload")
        return result

    async def debug_probe(
        self,
        *,
        uid: str,
        symbol: str,
        line: int,
        all_code: str,
        property_name: str = "code",
        position: int = 0,
        thread_name: str | None = None,
        max_hits: int = 16,
        timeout_ms: int = 5_000,
        timeout: float,
    ) -> dict[str, Any]:
        """Non-suspending JDWP variable probe on a running Kotlin box.

        Watches ``symbol`` at ``line`` (1-based, within ``property_name``) and
        samples its value each time that source line executes, up to ``max_hits``
        or until ``timeout_ms`` elapses. Never suspends the VM (SUSPEND_EVENT_THREAD,
        resume immediately).
        """
        return await self._run_probe(
            message_name="debug-probe",
            payload={
                "requestId": f"probe_{uuid.uuid4().hex}",
                "uid": uid,
                "line": line,
                "position": position,
                "symbol": symbol,
                "allCode": all_code,
                "property": property_name,
                "threadName": thread_name,
                "maxHits": max_hits,
                "timeoutMs": timeout_ms,
            },
            reply_context={"uid": uid},
            timeout=timeout,
            log_fields={"uid": uid, "symbol": symbol, "line": line},
        )

    async def debug_source_probe(
        self,
        *,
        symbol: str,
        line: int,
        class_name: str | None = None,
        source_name: str | None = None,
        thread_name: str | None = None,
        max_hits: int = 16,
        timeout_ms: int = 5_000,
        timeout: float,
    ) -> dict[str, Any]:
        """Non-suspending JDWP variable probe at a source location anywhere in src/.

        Unlike :meth:`debug_probe` this is not tied to a box or REPL snippet: the JDI
        helper resolves candidate classes by ``class_name`` (fully qualified) and/or
        ``source_name`` (file basename, e.g. ``Server.kt``) and sets the breakpoint at
        the real 1-based ``line``. At least one of class_name / source_name is required.
        """
        return await self._run_probe(
            message_name="debug-source-probe",
            payload={
                "requestId": f"probe_{uuid.uuid4().hex}",
                "symbol": symbol,
                "line": line,
                "className": class_name,
                "sourceName": source_name,
                "threadName": thread_name,
                "maxHits": max_hits,
                "timeoutMs": timeout_ms,
            },
            reply_context=None,
            timeout=timeout,
            log_fields={"className": class_name, "sourceName": source_name, "symbol": symbol, "line": line},
        )

    async def set_property(
        self, *, uid: str, property_name: str, value: str, timeout: float
    ) -> dict[str, Any]:
        """Persist one string property and wait for Field to acknowledge the edit.

        This is the agent-facing mutation path for code and separated shader properties as
        well as small strings.  Unlike the browser-style ``set-string`` notification, the
        direct request is acknowledged only after Field has applied its normal sidecar and
        metadata persistence rules.
        """
        resolved, _ = self.require_box_reference(uid)
        self.logger.log("set_property_start", uid=resolved, property=property_name, valueLength=len(value))
        event = await self.call_for_single_reply(
            "agent-set-property",
            {"uid": resolved, "property": property_name, "value": value},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-set-property returned an invalid payload")
        if not payload.get("ok"):
            raise BackdoorError(str(payload.get("message") or f"Could not set {property_name} on {resolved}"))
        self._set_local_string(resolved, property_name, value)
        self.logger.log("set_property_complete", uid=resolved, property=property_name, valueLength=len(value))
        return payload

    async def get_property(
        self, *, uid: str, property_name: str, timeout: float
    ) -> str:
        """Fetch a string property omitted from the compact box snapshot."""
        resolved, _ = self.require_box_reference(uid)
        event = await self.call_for_single_reply(
            "agent-get-property",
            {"uid": resolved, "property": property_name},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-get-property returned an invalid payload")
        if not payload.get("ok") or not isinstance(payload.get("value"), str):
            raise BackdoorError(str(payload.get("message") or f"Could not read {property_name} from {resolved}"))
        value = payload["value"]
        self._set_local_string(resolved, property_name, value)
        return value

    async def execution_code(
        self, resolved: str, box: dict[str, Any], property_name: str, timeout: float
    ) -> str:
        # A source patch, another client or the editor may have changed the live property
        # without updating this compact snapshot. Execute the server's source, never a
        # stale cached preview. Explicit inline source is handled by the callers.
        return await self.get_property(uid=resolved, property_name=property_name, timeout=timeout)

    async def ai_tools_list(self, *, timeout: float, focus_box: str | None = None) -> list[dict[str, Any]]:
        """List the ai4 agent tools available for the attached document.

        ``focus_box`` (a uid or unique name) stands in for the box whose chat is open, so tools that
        scope themselves to the focused box are listed as that box's chat would see them.
        """
        event = await self.call_for_single_reply("agent-ai-tools-list", {"focusBox": focus_box}, timeout=timeout)
        payload = event.get("payload")
        tools = payload.get("tools") if isinstance(payload, dict) else None
        return tools if isinstance(tools, list) else []

    async def ai_tool_call(
        self,
        *,
        name: str,
        arguments: dict[str, Any] | None,
        timeout: float,
        focus_box: str | None = None,
    ) -> dict[str, Any]:
        """Invoke a named ai4 tool via the provider-neutral registry."""
        event = await self.call_for_single_reply(
            "agent-ai-tool-call",
            {"name": name, "arguments": arguments or {}, "focusBox": focus_box},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-ai-tool-call returned an invalid payload")
        return payload

    async def change_connections(
        self,
        *,
        action: str,
        parent_uid: str,
        child_uids: list[str],
        timeout: float,
    ) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        parent = self.validate_box_reference(parent_uid)
        requested_children = [self.validate_box_reference(uid) for uid in child_uids]
        unique_children: list[str] = []
        seen: set[str] = set()
        for child in requested_children:
            if child == parent or child in seen:
                continue
            unique_children.append(child)
            seen.add(child)
        if not unique_children:
            raise BackdoorError(f"{action} requires at least one child different from the parent")

        self.logger.log(
            "change_connections_start",
            action=action,
            parent=parent,
            children=unique_children,
            timeout=timeout,
        )
        start_seq = self.event_seq
        current_children = set(self._ensure_box(parent).get("children") or [])
        desired = set(unique_children)
        already_satisfied = desired.issubset(current_children) if action == "connect" else desired.isdisjoint(current_children)

        await self.send_message(
            "perform-action",
            {
                "uid_currentSelection": [parent] + unique_children,
                "uid_action": "-connect" if action == "connect" else "-disconnect",
            },
        )

        timed_out = False
        if already_satisfied:
            events = self.events_since(start_seq)
        else:
            def predicate():
                updated_children = set(self._ensure_box(parent).get("children") or [])
                if action == "connect":
                    if desired.issubset(updated_children):
                        return self.events_since(start_seq)
                else:
                    if desired.isdisjoint(updated_children):
                        return self.events_since(start_seq)
                return None

            try:
                events = await self.wait_for(predicate, timeout, f"{action}:{parent}")
            except asyncio.TimeoutError:
                timed_out = True
                events = self.events_since(start_seq)

        data = {
            "action": action,
            "parent": parent,
            "children": unique_children,
            "childCount": len(unique_children),
        }
        self.logger.log(
            "change_connections_complete",
            action=action,
            parent=parent,
            children=unique_children,
            timedOut=timed_out,
            eventCount=len(events),
        )
        return data, events, timed_out

    async def delete_boxes(self, tokens: list[Any], *, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        uids: list[str] = []
        seen: set[str] = set()
        for token in tokens:
            uid = self.validate_box_reference(str(token))
            if uid == "root" or uid in seen:
                continue
            uids.append(uid)
            seen.add(uid)
        if not uids:
            return {"action": "delete", "boxes": [], "boxCount": 0}, [], False

        self.logger.log("delete_boxes_start", boxes=uids, timeout=timeout)
        start_seq = self.event_seq
        await self.send_message(
            "perform-action",
            {
                "uid_currentSelection": uids,
                "uid_action": "-delete-box",
            },
        )

        def predicate():
            remaining = [
                uid
                for uid in uids
                if uid in self.boxes and not self._truthy(self.boxes[uid].get("_tombstone"))
            ]
            if not remaining:
                return self.events_since(start_seq)
            return None

        timed_out = False
        events: list[dict[str, Any]] = []
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            current = predicate()
            if current is not None:
                events = current
                break
            remaining_time = deadline - asyncio.get_running_loop().time()
            if remaining_time <= 0:
                timed_out = True
                events = self.events_since(start_seq)
                break
            try:
                events = await self.wait_for(predicate, min(0.5, remaining_time), f"delete:{','.join(uids)}")
                break
            except asyncio.TimeoutError:
                pass
            remaining_time = deadline - asyncio.get_running_loop().time()
            if remaining_time <= 0:
                timed_out = True
                events = self.events_since(start_seq)
                break
            try:
                await self.refresh_snapshot(timeout=max(0.25, min(2.0, remaining_time)))
            except Exception as exc:
                self.logger.exception("delete_boxes_snapshot_poll_error", exc)

        data = {
            "action": "delete",
            "boxes": uids,
            "boxCount": len(uids),
        }
        self.logger.log("delete_boxes_complete", boxes=uids, timedOut=timed_out, eventCount=len(events))
        return data, events, timed_out

    async def run_box(self, uid: str, *, property_name: str, value: str | None, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        if not getattr(self, "execution_verdicts", False):
            raise BackdoorError("This server cannot report an awaited execution verdict. Restart Field with this checkout.")
        resolved, box = self.require_box_reference(uid)
        self.logger.log("run_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = value if value is not None else await self.execution_code(resolved, box, property_name, timeout)
        reply_name = f"exec_{uuid.uuid4().hex}"
        start_seq = self.event_seq
        await self.send_message(
            "execute-all",
            {
                "uid": resolved,
                "type": property_name,
                "line": 0,
                "code": code,
                "singleLine": False,
                "property": property_name,
                "requestId": reply_name,
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "run"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                payload = event.get("payload") or {}
                if event.get("name") == "execution-verdict" and payload.get("requestId") == reply_name and payload.get("uid") == resolved:
                    return events
            return None

        timed_out = False
        try:
            events = await self.wait_for(predicate, timeout, f"run:{resolved}")
        except asyncio.TimeoutError:
            timed_out = True
            events = self.events_since(start_seq)
        finally:
            self.reply_contexts.pop(reply_name, None)

        data = {
            "uid": resolved,
            "property": property_name,
            "codeLength": len(code),
            "running": False,
        }
        self.logger.log("run_box_complete", uid=resolved, timedOut=timed_out, eventCount=len(events))
        return data, events, timed_out

    async def launch_box(self, uid: str, *, property_name: str, value: str | None, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        if not getattr(self, "execution_verdicts", False):
            raise BackdoorError("This server cannot report an awaited launch verdict. Restart Field with this checkout; a running stripe does not prove compilation succeeded.")
        resolved, box = self.require_box_reference(uid)
        self.logger.log("launch_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = value if value is not None else await self.execution_code(resolved, box, property_name, timeout)
        reply_name = f"launch_{uuid.uuid4().hex}"
        start_seq = self.event_seq
        await self.send_message(
            "launch-all",
            {
                "uid": resolved,
                "type": property_name,
                "line": 0,
                "code": code,
                "singleLine": False,
                "property": property_name,
                "requestId": reply_name,
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "launch"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                payload = event.get("payload") or {}
                if event.get("name") == "execution-verdict" and payload.get("requestId") == reply_name and payload.get("uid") == resolved:
                    return events, bool(self.boxes.get(resolved, {}).get("runningStrands"))
            return None

        timed_out = False
        running = False
        try:
            events, running = await self.wait_for(predicate, timeout, f"launch:{resolved}")
        except asyncio.TimeoutError:
            timed_out = True
            events = self.events_since(start_seq)
        finally:
            self.reply_contexts.pop(reply_name, None)

        data = {
            "uid": resolved,
            "property": property_name,
            "codeLength": len(code),
            "running": running,
            "runningObservation": "timeout" if timed_out else "setup-complete",
            "runningMayBeTransient": True,
            "verdict": next((e.get("payload") for e in events if e.get("name") == "execution-verdict" and (e.get("payload") or {}).get("requestId") == reply_name), None),
        }
        self.logger.log("launch_box_complete", uid=resolved, timedOut=timed_out, running=running, eventCount=len(events))
        return data, events, timed_out

    async def launch_batch(
        self,
        uids: list[str],
        *,
        property_name: str,
        strand: str,
        timeout: float,
    ) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        """Submit one ordered multi-box chord through Field's native ``launch-batch`` path.

        The initial ``is-running`` packets are pending/queue acknowledgements, not a promise that
        every box will keep a stripe. The server owns the important semantic barrier: launches
        evaluate in the supplied order, with runloop yields between boxes, and another batch sent
        while this one is active extends the same queue.
        """
        if not uids:
            raise BackdoorError("launch-batch requires at least one box")

        resolved_boxes = [self.require_box_reference(uid) for uid in uids]
        resolved = [uid for uid, _box in resolved_boxes]
        if len(set(resolved)) != len(resolved):
            raise BackdoorError("launch-batch cannot contain the same box more than once")

        if not getattr(self, "execution_verdicts", False):
            raise BackdoorError("This server cannot report awaited execution verdicts; restart Field with this checkout.")
        reply_name = f"launch_batch_{uuid.uuid4().hex}"
        executions = [
            {
                "uid": uid,
                "type": "launch",
                "line": 0,
                "code": str(box.get(property_name) or ""),
                "singleLine": False,
                "property": property_name,
                "strand": strand,
                "requestId": reply_name,
            }
            for uid, box in resolved_boxes
        ]
        start_seq = self.event_seq
        self.logger.log(
            "launch_batch_start",
            uids=resolved,
            property=property_name,
            strand=strand,
            timeout=timeout,
        )
        await self.send_message(
            "launch-batch",
            {"executions": executions},
            reply_to=reply_name,
            reply_context={"uids": resolved, "command": "launch-batch"},
        )

        def predicate():
            events = self.events_since(start_seq)
            completed = {
                (event.get("payload") or {}).get("uid") for event in events
                if event.get("name") == "execution-verdict"
                and (event.get("payload") or {}).get("requestId") == reply_name
            }
            if set(resolved).issubset(completed):
                return events, completed
            return None

        timed_out = False
        acknowledged: set[str] = set()
        try:
            events, acknowledged = await self.wait_for(
                predicate,
                timeout,
                f"launch-batch:{','.join(resolved)}",
            )
        except asyncio.TimeoutError:
            timed_out = True
            events = self.events_since(start_seq)
            acknowledged = {
                (event.get("payload") or {}).get("uid") for event in events
                if event.get("name") == "execution-verdict"
                and (event.get("payload") or {}).get("requestId") == reply_name
            }
        finally:
            self.reply_contexts.pop(reply_name, None)

        data = {
            "uids": resolved,
            "boxCount": len(resolved),
            "property": property_name,
            "strand": strand,
            "queued": True,
            "setupComplete": len(acknowledged) == len(resolved),
            "acknowledgedUids": [uid for uid in resolved if uid in acknowledged],
            "runningObservation": "awaiting-setup" if timed_out else "setup-complete",
            "runningMayBeTransient": True,
        }
        self.logger.log(
            "launch_batch_complete",
            uids=resolved,
            timedOut=timed_out,
            queued=data["queued"],
            eventCount=len(events),
        )
        return data, events, timed_out

    async def wait_running(
        self,
        uid: str,
        *,
        desired_running: bool,
        settle: float,
        timeout: float,
    ) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        """Wait until a box continuously holds the desired running state for ``settle`` seconds."""
        resolved, _ = self.require_box_reference(uid)
        settle = max(0.0, settle)
        start_seq = self.event_seq
        deadline = asyncio.get_running_loop().time() + timeout
        stable_since: float | None = None

        while True:
            now = asyncio.get_running_loop().time()
            box = self._ensure_box(resolved)
            running = bool(box.get("runningStrands"))
            if running == desired_running:
                if stable_since is None:
                    stable_since = now
                if now - stable_since >= settle:
                    data = {
                        "uid": resolved,
                        "running": running,
                        "desiredRunning": desired_running,
                        "settledSeconds": settle,
                        "runningStrands": dict(box.get("runningStrands") or {}),
                    }
                    return data, self.events_since(start_seq), False
            else:
                stable_since = None

            if now >= deadline:
                data = {
                    "uid": resolved,
                    "running": running,
                    "desiredRunning": desired_running,
                    "settledSeconds": settle,
                    "runningStrands": dict(box.get("runningStrands") or {}),
                }
                return data, self.events_since(start_seq), True
            await asyncio.sleep(min(0.05, max(0.0, deadline - now)))

    async def perform_action(
        self,
        uid: str,
        *,
        action_id: str,
        property_name: str,
        settle: float,
        timeout: float,
    ) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        """Invoke the same box action the Field UI exposes (for example ``moduleHere_reload``)."""
        resolved, _ = self.require_box_reference(uid)
        start_seq = self.event_seq
        reply = await self.call_for_single_reply(
            "perform-action",
            {
                "uid_currentSelection": [resolved],
                "uid_action": action_id,
                "uid_property": property_name,
            },
            timeout=timeout,
            reply_context={"uid": resolved, "command": "action", "action": action_id},
        )
        if settle > 0:
            await asyncio.sleep(settle)
        events = self.events_since(start_seq)
        payload = reply.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError(f"perform-action returned an invalid reply for {action_id!r}")
        data = dict(payload)
        data.update(
            {
                "uid": resolved,
                "action": action_id,
                "property": property_name,
                "settledSeconds": max(0.0, settle),
            }
        )
        return data, events

    async def continue_box(self, uid: str, *, property_name: str, value: str | None, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        if not getattr(self, "execution_verdicts", False):
            raise BackdoorError("This server cannot report awaited execution verdicts; restart Field with this checkout.")
        resolved, box = self.require_box_reference(uid)
        self.logger.log("continue_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = value if value is not None else await self.execution_code(resolved, box, property_name, timeout)
        reply_name = f"continue_{uuid.uuid4().hex}"
        start_seq = self.event_seq
        await self.send_message(
            "continue-all",
            {
                "uid": resolved,
                "type": property_name,
                "line": 0,
                "code": code,
                "singleLine": False,
                "property": property_name,
                "requestId": reply_name,
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "continue"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                payload = event.get("payload") or {}
                if event.get("name") == "execution-verdict" and payload.get("requestId") == reply_name and payload.get("uid") == resolved:
                    return events, bool(self.boxes.get(resolved, {}).get("runningStrands"))
            return None

        timed_out = False
        running = False
        try:
            events, running = await self.wait_for(predicate, timeout, f"continue:{resolved}")
        except asyncio.TimeoutError:
            timed_out = True
            events = self.events_since(start_seq)
        finally:
            self.reply_contexts.pop(reply_name, None)

        data = {
            "uid": resolved,
            "property": property_name,
            "codeLength": len(code),
            "running": running,
        }
        self.logger.log("continue_box_complete", uid=resolved, timedOut=timed_out, running=running, eventCount=len(events))
        return data, events, timed_out

    async def stop_box(self, uid: str, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        resolved, box = self.require_box_reference(uid)
        self.logger.log("stop_box_start", uid=resolved, timeout=timeout)
        start_seq = self.event_seq
        await self.send_message("stop-box", {"uid": resolved})

        def predicate():
            if not box.get("runningStrands"):
                return self.events_since(start_seq)
            return None

        timed_out = False
        try:
            events = await self.wait_for(predicate, timeout, f"stop:{resolved}")
        except asyncio.TimeoutError:
            timed_out = True
            events = self.events_since(start_seq)
        data = {
            "uid": resolved,
            "running": bool(box.get("runningStrands")),
        }
        self.logger.log("stop_box_complete", uid=resolved, timedOut=timed_out, running=data["running"], eventCount=len(events))
        return data, events, timed_out

    def collect_output(self, uid: str, *, channel: str | None, kind: str) -> list[dict[str, Any]]:
        resolved, _ = self.require_box_reference(uid)
        collected = self._matching_output_events(resolved, channel=channel, kind=kind, contains=None)
        self.logger.log("collect_output", uid=resolved, channel=channel, kind=kind, count=len(collected))
        return collected

    def _matching_output_events(self, resolved: str, *, channel: str | None, kind: str, contains: str | None) -> list[dict[str, Any]]:
        accepted_names = {
            "inline": {"output"},
            "display": {"set-output", "output-control"},
            "all": {"output", "set-output", "output-control"},
        }[kind]
        collected = []
        for event in self.event_log:
            if _is_error_clear_event(event):
                continue
            if event.get("uid") != resolved:
                continue
            if event["name"] not in accepted_names:
                continue
            if channel is not None and event.get("channel") != channel:
                continue
            if contains is not None:
                payload_text = json.dumps(event.get("payload"), ensure_ascii=False, sort_keys=True)
                if contains not in payload_text:
                    continue
            collected.append(event)
        return collected

    def _execution_error_event(self, resolved: str) -> dict[str, Any] | None:
        """The newest error that is still active after applying Field's clear-error packets."""
        active = [
            event
            for (uid, _property), event in self._active_execution_errors.items()
            if uid == resolved
        ]
        return max(active, key=lambda event: int(event.get("seq", 0))) if active else None

    async def wait_output(
        self,
        uid: str,
        *,
        channel: str | None,
        kind: str,
        contains: str | None,
        timeout: float,
        fail_on_error: bool = True,
    ) -> tuple[list[dict[str, Any]], bool, dict[str, Any] | None]:
        resolved, _ = self.require_box_reference(uid)
        self.logger.log(
            "wait_output_start",
            uid=resolved,
            channel=channel,
            kind=kind,
            contains=contains,
            timeout=timeout,
            failOnError=fail_on_error,
        )

        # Distinguish "found the output" from "the box errored" so a failed box
        # aborts the wait immediately instead of blocking for the full timeout
        # (and then getting killed by the caller's subprocess timeout, which
        # discards the error entirely). See docs-capture silent-timeout fix.
        def predicate():
            events = self._matching_output_events(resolved, channel=channel, kind=kind, contains=contains)
            if events:
                return ("ok", events)
            if fail_on_error:
                error = self._execution_error_event(resolved)
                if error is not None:
                    return ("error", error)
            return None

        timed_out = False
        error_event: dict[str, Any] | None = None
        try:
            outcome, detail = await self.wait_for(predicate, timeout, f"wait-output:{resolved}")
            if outcome == "error":
                error_event = detail
                events = self._matching_output_events(resolved, channel=channel, kind=kind, contains=contains)
            else:
                events = detail
        except asyncio.TimeoutError:
            timed_out = True
            events = self._matching_output_events(resolved, channel=channel, kind=kind, contains=contains)
            if fail_on_error:
                # A late-arriving error (after the deadline) is still worth reporting.
                error_event = self._execution_error_event(resolved)

        self.logger.log(
            "wait_output_complete",
            uid=resolved,
            channel=channel,
            kind=kind,
            contains=contains,
            timedOut=timed_out,
            hasError=error_event is not None,
            count=len(events),
        )
        return events, timed_out, error_event

    def execution_errors(self, events: list[dict[str, Any]]) -> list[str]:
        problems: list[str] = []
        for event in events:
            if not isinstance(event, dict):
                continue
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if _is_execution_error_event(event) and _is_error_output_event(event):
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                if html:
                    problems.append(html)
            elif _is_execution_error_event(event):
                text = str(payload.get("text", "")).replace("\n", " ").strip()
                if text:
                    problems.append(text)
        return list(dict.fromkeys(problems))

    async def raw_send(self, name: str, payload: Any, *, reply_name: str | None, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        start_seq = self.event_seq
        self.logger.log("raw_send_start", name=name, replyName=reply_name, timeout=timeout, payload=payload)
        if reply_name:
            await self.send_message(name, payload, reply_to=reply_name)

            def predicate():
                for event in self.events_since(start_seq):
                    if event["messageName"] == reply_name:
                        return event
                return None

            timed_out = False
            try:
                reply_event = await self.wait_for(predicate, timeout, f"raw-send:{name}:{reply_name}")
                data = {"reply": reply_event}
            except asyncio.TimeoutError:
                timed_out = True
                data = {"reply": None}
            finally:
                self.reply_contexts.pop(reply_name, None)
            self.logger.log("raw_send_complete", name=name, replyName=reply_name, timedOut=timed_out)
            return data, self.events_since(start_seq), timed_out

        await self.send_message(name, payload)
        self.logger.log("raw_send_complete", name=name, replyName=None, timedOut=False)
        return {"reply": None}, self.events_since(start_seq), False

    async def run_jvm_main(
        self,
        entrypoint: str,
        *,
        jvm_args: list[str],
        timeout: float,
        include_command: bool = False,
    ) -> dict[str, Any]:
        self.logger.log("run_jvm_main_start", entrypoint=entrypoint, args=jvm_args, timeout=timeout)
        event = await self.call_for_single_reply(
            "agent-run-jvm-main",
            {
                "entrypoint": entrypoint,
                "args": jvm_args,
                "timeoutSec": timeout,
            },
            timeout=timeout + 5.0,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-run-jvm-main returned an invalid payload")
        if not include_command and "command" in payload:
            payload = dict(payload)
            payload.pop("command", None)
            payload["commandOmitted"] = True
            payload["commandOmittedReason"] = "Use run-jvm-main --include-command to include the full JVM command/classpath."
        self.logger.log(
            "run_jvm_main_complete",
            entrypoint=entrypoint,
            ok=payload.get("ok"),
            exitCode=payload.get("exitCode"),
            timedOut=payload.get("timedOut"),
        )
        return payload


def build_command_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="field-backdoor", add_help=False)
    subparsers = parser.add_subparsers(dest="command", required=True)

    help_parser = subparsers.add_parser("help", add_help=False)
    help_parser.add_argument("topic", nargs="?")

    open_parser = subparsers.add_parser("open", add_help=False)
    open_parser.add_argument("document", nargs="?")
    open_parser.add_argument("--scratch", action="store_true")
    open_parser.add_argument(
        "--read-only",
        action="store_true",
        help="open the named document without writing back to it (not the same as --scratch)",
    )
    open_parser.add_argument(
        "--must-exist",
        action="store_true",
        help=(
            "fail instead of creating a new empty document when the name is not in the "
            "server's workspace. Say this whenever you mean an existing document."
        ),
    )
    open_parser.add_argument(
        "--keep-running",
        action="store_true",
        help=(
            "leave the document's windows and running boxes up after this invocation ends, so a "
            "later invocation finds them (by default they are cleaned up when the last client leaves)"
        ),
    )

    subparsers.add_parser("status", add_help=False)
    subparsers.add_parser("snapshot", add_help=False)
    subparsers.add_parser("boxes", add_help=False)
    sleep_parser = subparsers.add_parser("sleep", add_help=False)
    sleep_parser.add_argument("seconds", type=float)
    playwright_open = subparsers.add_parser("playwright-open", add_help=False)
    playwright_open.add_argument("--timeout", type=float)

    dispose_graphics = subparsers.add_parser("dispose-graphics-context", add_help=False)
    dispose_graphics.add_argument("--timeout", type=float)

    editor_open = subparsers.add_parser("editor-open", add_help=False)
    editor_open.add_argument("uid")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    editor_open.add_argument("--property", default=None)
    editor_open.add_argument("--open-outputs", action="store_true")
    editor_open.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    editor_open.add_argument("--padding", type=float, default=12.0)
    editor_open.add_argument("--no-pan", action="store_true")
    editor_open.add_argument("--timeout", type=float)

    screenshot_window = subparsers.add_parser("screenshot-window", add_help=False)
    screenshot_window.add_argument("path")
    screenshot_window.add_argument("--uid")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    screenshot_window.add_argument("--property", default=None)
    screenshot_window.add_argument("--with-editor", action="store_true")
    screenshot_window.add_argument("--open-outputs", action="store_true")
    screenshot_window.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    screenshot_window.add_argument("--no-pan", action="store_true")
    screenshot_window.add_argument("--wait-ms", type=float, default=0.0)
    screenshot_window.add_argument("--preserve-selection", action="store_true")
    screenshot_window.add_argument("--include-notifications", action="store_true")
    screenshot_window.add_argument("--timeout", type=float)

    screenshot_editor = subparsers.add_parser("screenshot-editor", add_help=False)
    screenshot_editor.add_argument("uid")
    screenshot_editor.add_argument("path")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    screenshot_editor.add_argument("--property", default=None)
    screenshot_editor.add_argument("--padding", type=float, default=12.0)
    screenshot_editor.add_argument("--open-outputs", action="store_true")
    screenshot_editor.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    screenshot_editor.add_argument("--no-pan", action="store_true")
    screenshot_editor.add_argument("--timeout", type=float)

    screenshot_box = subparsers.add_parser("screenshot-box", add_help=False)
    screenshot_box.add_argument("uid")
    screenshot_box.add_argument("path")
    screenshot_box.add_argument("--padding", type=float, default=12.0)
    screenshot_box.add_argument("--open-outputs", dest="open_outputs", action="store_true", default=True)
    screenshot_box.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    screenshot_box.add_argument("--no-open-outputs", dest="open_outputs", action="store_false")
    screenshot_box.add_argument("--no-pan", action="store_true")
    screenshot_box.add_argument("--preserve-selection", action="store_true")
    screenshot_box.add_argument("--include-notifications", action="store_true")
    screenshot_box.add_argument("--timeout", type=float)

    native_screenshot = subparsers.add_parser("native-screenshot", add_help=False)
    native_screenshot.add_argument("path")
    native_screenshot.add_argument("--uid")
    native_screenshot.add_argument("--wait-frames", type=int, default=30)
    native_screenshot.add_argument("--timeout", type=float)

    wait_native_renderer = subparsers.add_parser("wait-native-renderer", add_help=False)
    wait_native_renderer.add_argument("uid", nargs="?")
    wait_native_renderer.add_argument("--frames", type=int, default=3)
    wait_native_renderer.add_argument("--timeout", type=float)

    record_window = subparsers.add_parser("record-window", add_help=False)
    record_window.add_argument("path")
    record_window.add_argument("--uid")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    record_window.add_argument("--property", default=None)
    record_window.add_argument("--with-editor", action="store_true")
    record_window.add_argument("--open-outputs", action="store_true")
    record_window.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    record_window.add_argument("--no-pan", action="store_true")
    record_window.add_argument("--duration", type=float, default=5.0)
    record_window.add_argument("--fps", type=int, default=30)
    record_window.add_argument("--frame-synchronous", action="store_true")
    record_window.add_argument("--restart-target", action="store_true")
    record_window.add_argument("--timeout", type=float)

    record_editor = subparsers.add_parser("record-editor", add_help=False)
    record_editor.add_argument("uid")
    record_editor.add_argument("path")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    record_editor.add_argument("--property", default=None)
    record_editor.add_argument("--padding", type=float, default=12.0)
    record_editor.add_argument("--open-outputs", action="store_true")
    record_editor.add_argument("--expect-outputs", dest="expect_outputs", default="panel", choices=["panel", "inline", "none"])
    record_editor.add_argument("--no-pan", action="store_true")
    record_editor.add_argument("--duration", type=float, default=5.0)
    record_editor.add_argument("--fps", type=int, default=30)
    record_editor.add_argument("--frame-synchronous", action="store_true")
    record_editor.add_argument("--restart-target", action="store_true")
    record_editor.add_argument("--timeout", type=float)

    record_box = subparsers.add_parser("record-box", add_help=False)
    record_box.add_argument("uid")
    record_box.add_argument("path")
    # None: the box's own default_property (a RectSL scene shows its rectSL), else code.
    record_box.add_argument("--property", default=None)
    record_box.add_argument("--padding", type=float, default=12.0)
    record_box.add_argument("--no-pan", action="store_true")
    record_box.add_argument("--duration", type=float, default=5.0)
    record_box.add_argument("--fps", type=int, default=30)
    record_box.add_argument("--frame-synchronous", action="store_true")
    record_box.add_argument("--restart-target", action="store_true")
    record_box.add_argument("--timeout", type=float)

    input_key = subparsers.add_parser("input-key", add_help=False)
    input_key.add_argument("mode", choices=("press", "down", "up"))
    input_key.add_argument("key")
    input_key.add_argument("--selector")
    input_key.add_argument("--wait-ms", type=float, default=0.0)
    input_key.add_argument("--timeout", type=float)

    inspection = subparsers.add_parser("inspect-view", add_help=False)
    inspection.add_argument("view_id")
    inspection.add_argument("--state")
    inspection.add_argument("--save")
    inspection.add_argument("--evidence-dir")
    inspection.add_argument("--received", action="store_true")
    inspection.add_argument("--include-resolved", action="store_true")
    review_note = subparsers.add_parser("review-note", add_help=False)
    review_note.add_argument("view_id")
    review_note.add_argument("note_id")
    review_note.add_argument("--status", choices=("open", "resolved"), required=True)
    review_note.add_argument("--summary", required=True)
    review_note.add_argument("--actor", required=True)
    review_note.add_argument("--revision", required=True)
    review_note.add_argument("--expected-version", type=int, required=True)
    review_note.add_argument("--timeout", type=float)
    reply_note = subparsers.add_parser("reply-note", add_help=False)
    reply_note.add_argument("view_id")
    reply_note.add_argument("note_id")
    reply_text = reply_note.add_mutually_exclusive_group(required=True)
    reply_text.add_argument("--text")
    reply_text.add_argument("--file")
    reply_note.add_argument("--actor", required=True)
    reply_note.add_argument("--revision", required=True)
    reply_note.add_argument("--expected-version", type=int, required=True)
    reply_note.add_argument("--timeout", type=float)
    inspection.add_argument("--pick", nargs=2, type=float, metavar=("X", "Y"))
    inspection.add_argument("--feature", choices=("point", "face", "edge", "vertex"), default="face")
    inspection.add_argument("--note")
    inspection.add_argument("--region", nargs=5, type=float, metavar=("CX","CY","WIDTH","HEIGHT","ANGLE"))
    inspection.add_argument("--section", nargs=4, type=float, metavar=("X", "Y", "Z", "FRACTION"))
    inspection.add_argument("--hide", action="append", default=[])
    inspection.add_argument("--show", action="append", default=[])
    inspection.add_argument("--assembly", type=float)
    inspection.add_argument("--timeout", type=float)

    page_eval = subparsers.add_parser("page-eval", add_help=False)
    page_eval.add_argument("script", nargs="?")
    page_eval.add_argument("--file")
    page_eval.add_argument("--body", action="store_true")
    page_eval.add_argument("--wait-ms", type=float, default=0.0)
    page_eval.add_argument("--timeout", type=float)

    click_box = subparsers.add_parser("click-box", add_help=False)
    click_box.add_argument("uid")
    click_box.add_argument("--wait-ms", type=float, default=0.0)
    click_box.add_argument("--timeout", type=float)

    drag_box = subparsers.add_parser("drag-box", add_help=False)
    drag_box.add_argument("uid")
    drag_box.add_argument("--dx", type=float, required=True)
    drag_box.add_argument("--dy", type=float, required=True)
    drag_box.add_argument("--hold", action="store_true")
    drag_box.add_argument("--steps", type=int, default=12)
    drag_box.add_argument("--wait-ms", type=float, default=0.0)
    drag_box.add_argument("--timeout", type=float)

    mouse_up = subparsers.add_parser("mouse-up", add_help=False)
    mouse_up.add_argument("--wait-ms", type=float, default=0.0)
    mouse_up.add_argument("--timeout", type=float)

    show_history = subparsers.add_parser("show-history", add_help=False)
    show_history.add_argument("filename", nargs="?")
    show_history.add_argument("--wait-ms", type=float, default=250.0)
    show_history.add_argument("--timeout", type=float)

    new_box = subparsers.add_parser("new-box", add_help=False)
    new_box.add_argument("--name", default="Untitled")
    new_box.add_argument("--alias")
    new_box.add_argument("--x", type=float, default=0.0)
    new_box.add_argument("--y", type=float, default=0.0)
    new_box.add_argument("--w", type=float, default=100.0)
    new_box.add_argument("--h", type=float, default=100.0)
    new_box.add_argument("--timeout", type=float)

    delete_box = subparsers.add_parser("delete-box", add_help=False)
    delete_box.add_argument("uids", nargs="+")
    delete_box.add_argument("--timeout", type=float)

    for command_name in ("connect", "disconnect"):
        graph_parser = subparsers.add_parser(command_name, add_help=False)
        graph_parser.add_argument("parent")
        graph_parser.add_argument("children", nargs="+")
        graph_parser.add_argument("--timeout", type=float)

    set_parser = subparsers.add_parser("set", add_help=False)
    set_parser.add_argument("uid")
    set_parser.add_argument("property")
    set_parser.add_argument("value", nargs="?")
    set_parser.add_argument("--file")
    set_parser.add_argument("--timeout", type=float)

    for command_name in ("macro", "transcript"):
        macro_parser = subparsers.add_parser(command_name, add_help=False)
        macro_parser.add_argument("macro_command")
        macro_parser.add_argument("name", nargs="?")
        macro_parser.add_argument("--label", default="")
        macro_parser.add_argument("--selection", action="append", default=[])
        macro_parser.add_argument("--scope", default="document")
        macro_parser.add_argument("--project-name", default="")
        macro_parser.add_argument("--content")
        macro_parser.add_argument("--file")
        macro_parser.add_argument("--offset", type=int, default=0)
        macro_parser.add_argument("--limit", type=int, default=80)
        macro_parser.add_argument("--index", dest="index_values", action="append", default=[])
        macro_parser.add_argument("--indices", dest="index_values", action="append", default=[])
        macro_parser.add_argument("--mode", default="")
        macro_parser.add_argument("--timeout", type=float)
        macro_parser.add_argument("--no-fail-on-error", action="store_true")

    for command_name in ("run", "launch", "continue"):
        exec_parser = subparsers.add_parser(command_name, add_help=False)
        exec_parser.add_argument("uid")
        # None: the box's own default_property (a StackSL program box runs its stackSL), else code.
        exec_parser.add_argument("--property", default=None)
        exec_parser.add_argument("--value")
        exec_parser.add_argument("--file")
        exec_parser.add_argument("--timeout", type=float)
        exec_parser.add_argument(
            "--no-fail-on-error",
            action="store_true",
            help=(
                "Report ok even when the box emits a compile/runtime error. The diagnostics "
                "still appear in data.errors. Same name and meaning as wait-output's flag."
            ),
        )

    launch_batch_parser = subparsers.add_parser("launch-batch", add_help=False)
    launch_batch_parser.add_argument("uids", nargs="+")
    launch_batch_parser.add_argument("--property", default="code")
    launch_batch_parser.add_argument("--strand", default="default")
    launch_batch_parser.add_argument("--timeout", type=float)
    launch_batch_parser.add_argument(
        "--no-fail-on-error",
        action="store_true",
        help=(
            "Report ok even when a box in the chord emits a compile/runtime error. The "
            "diagnostics still appear in data.errors."
        ),
    )

    wait_running_parser = subparsers.add_parser("wait-running", add_help=False)
    wait_running_parser.add_argument("uid")
    wait_running_parser.add_argument("--state", choices=("running", "stopped"), default="running")
    wait_running_parser.add_argument("--settle", type=float, default=0.25)
    wait_running_parser.add_argument("--timeout", type=float)

    action_parser = subparsers.add_parser("action", add_help=False)
    action_parser.add_argument("uid")
    action_parser.add_argument("action_id")
    action_parser.add_argument("--property", default="code")
    action_parser.add_argument("--settle", type=float, default=0.25)
    action_parser.add_argument("--timeout", type=float)

    stop_parser = subparsers.add_parser("stop", add_help=False)
    stop_parser.add_argument("uid")
    stop_parser.add_argument("--timeout", type=float)
    stop_parser.add_argument("--best-effort", action="store_true")

    wait_file = subparsers.add_parser("wait-file", add_help=False)
    wait_file.add_argument("path")
    wait_file.add_argument("--timeout", type=float)
    wait_file.add_argument("--stable-reads", type=int, default=2)
    wait_file.add_argument("--newer-than", type=float)
    wait_file.add_argument("--fail-on-error-uid")

    output_parser = subparsers.add_parser("output", add_help=False)
    output_parser.add_argument("uid")
    output_parser.add_argument("--channel")
    output_parser.add_argument("--kind", choices=("inline", "display", "all"), default="all")

    wait_output_parser = subparsers.add_parser("wait-output", add_help=False)
    wait_output_parser.add_argument("uid")
    wait_output_parser.add_argument("--channel")
    wait_output_parser.add_argument("--kind", choices=("inline", "display", "all"), default="all")
    wait_output_parser.add_argument("--contains")
    wait_output_parser.add_argument("--timeout", type=float)
    wait_output_parser.add_argument(
        "--no-fail-on-error",
        action="store_true",
        help="Do not abort early if the target box emits a compile/runtime error while waiting.",
    )

    agent_task_parser = subparsers.add_parser("agent-task", add_help=False)
    agent_task_parser.add_argument("--goal", required=True)
    agent_task_parser.add_argument("--check", dest="checks", action="append", default=[])
    agent_task_parser.add_argument("--mode", choices=("live", "scripted"), default="live")
    agent_task_parser.add_argument("--script", help="Path (or - for stdin) to a JSON script for --mode scripted")
    agent_task_parser.add_argument("--setup", help="Path to a JSON list of setup tool calls applied before the turn")
    agent_task_parser.add_argument("--max-rounds", type=int, default=24)
    agent_task_parser.add_argument("--model", default="")
    agent_task_parser.add_argument("--focus-box", default="")
    agent_task_parser.add_argument("--timeout", type=float)

    agent_eval_parser = subparsers.add_parser("agent-eval", add_help=False)
    agent_eval_parser.add_argument("--mode", choices=("live", "scripted"), default="scripted")
    agent_eval_parser.add_argument("--ids", default="", help="Comma-separated task ids; empty = all")
    agent_eval_parser.add_argument("--max-rounds", type=int, default=24)
    agent_eval_parser.add_argument("--model", default="")
    agent_eval_parser.add_argument("--timeout", type=float)

    raw_send = subparsers.add_parser("raw-send", add_help=False)
    raw_send.add_argument("name")
    raw_send.add_argument("payload")
    raw_send.add_argument("--reply")
    raw_send.add_argument("--timeout", type=float)

    run_jvm_main = subparsers.add_parser("run-jvm-main", add_help=False)
    run_jvm_main.add_argument("entrypoint")
    run_jvm_main.add_argument("--arg", dest="entry_args", action="append", default=[])
    run_jvm_main.add_argument("--timeout", type=float)
    run_jvm_main.add_argument("--include-command", action="store_true")

    return parser


# The commands whose --property, when not given, is the box's own default_property.
PROPERTY_FOLLOWING_COMMANDS = {"editor-open", "screenshot-window", "screenshot-editor", "record-window", "record-editor", "record-box"}


def default_property_of(client: "FieldBackdoor", uid: str | None) -> str:
    """The property a box shows in its editor: its default_property (a RectSL scene's rectSL), else code."""
    if not uid:
        return "code"
    try:
        _, target = client.require_box_reference(uid)
    except BackdoorError:
        return "code"
    return target.get("default_property") or "code"


def parse_command(command_line: str) -> argparse.Namespace:
    parser = build_command_parser()
    try:
        return parser.parse_args(shlex.split(command_line))
    except SystemExit as exc:
        raise CommandParseError(command_line) from exc


async def ensure_playwright_attached(client: FieldBackdoor, *, timeout: float, reason: str) -> dict[str, Any]:
    # Upgrade to the generous first-run floor only when the browser might still
    # be downloading on this machine; otherwise honor the caller's timeout so a
    # hung attach fails fast rather than blocking for the old 180s floor.
    attach_timeout = playwright_attach_budget(timeout, client.default_timeout)
    client.logger.log(
        "ensure_playwright_attached_start",
        reason=reason,
        timeout=timeout,
        attachTimeout=attach_timeout,
        firstRun=not PLAYWRIGHT_READY_MARKER.exists(),
        documentName=client.document_name,
        scratch=client.scratch,
        sessionKey=client.session_key,
    )
    payload = await client.open_playwright(timeout=attach_timeout)
    mark_playwright_ready()
    client.logger.log(
        "ensure_playwright_attached_complete",
        reason=reason,
        ready=payload.get("ready"),
        url=payload.get("url"),
        sessionKey=payload.get("sessionKey"),
    )
    return payload


async def dispatch_command(
    client: FieldBackdoor,
    args: argparse.Namespace,
    command_line: str,
    *,
    auto_playwright: bool = False,
    auto_playwright_timeout: float | None = None,
) -> dict[str, Any]:
    client.logger.log("command_dispatch_start", commandLine=command_line, command=args.command)
    if args.command == "help":
        parser = build_command_parser()
        choices = next(a.choices for a in parser._actions if isinstance(a, argparse._SubParsersAction))
        if args.topic and args.topic not in choices:
            raise BackdoorError(f"Unknown command {args.topic!r}")
        text = choices[args.topic].format_help() if args.topic else parser.format_help()
        return command_result(command_line, True, data={"help": text})

    if args.command == "open":
        data = await client.open_document(
            args.document,
            scratch=args.scratch or not args.document,
            timeout=client.default_timeout,
            read_only=getattr(args, "read_only", False),
            must_exist=getattr(args, "must_exist", False),
            keep_running=getattr(args, "keep_running", False),
        )
        if getattr(args, "must_exist", False):
            if args.scratch or not args.document:
                raise BackdoorError("open --must-exist is meaningless with --scratch: a scratch document is new by construction")
            if data.get("documentExisted") is None:
                raise BackdoorError(
                    f"open --must-exist cannot be honoured: the Field on port {client.port} predates "
                    "`documentExisted` and cannot say whether the document was there. Restart it from "
                    "this checkout, or drop --must-exist and check boxCount by hand."
                )
            if not data.get("documentExisted"):
                raise BackdoorError(
                    f"open --must-exist: no document named {args.document!r} in the server's workspace "
                    f"{client.server_workspace!r}. Field would have created a new empty one at "
                    f"{data.get('sessionKey')!r}. Check `serverWorkspace` in `status` -- it is the "
                    "server's, not your --workspace -- and the spelling of the name."
                )
        if client.graphics_context_enabled:
            data = dict(data)
            data["graphicsContext"] = await client.ensure_graphics_context(timeout=client.default_timeout)
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            documentExisted=data.get("documentExisted"),
            attachedToLiveDocument=data.get("attachedToLiveDocument"),
        )
        return result

    if args.command == "status":
        running = [uid for uid, box in client.boxes.items() if box.get("runningStrands")]
        data = {
            "connected": client.websocket is not None,
            "managedServer": client.managed_process is not None,
            "reusedExistingServer": client.reused_existing_server,
            "attachExisting": client.attach_existing,
            # Three keys where there was one called `workspace`, because one of them was a fact
            # about the world and the other two are facts about this invocation, and the old
            # single key silently answered the first question with the third answer. `AGENTS.md`
            # told an agent to "verify workspace ... before editing", and the value it verified
            # was its own `--workspace` argument, so the check could not fail. Renamed rather
            # than redefined: nothing reads this key programmatically (0 hits for
            # `.get("workspace")` across tools/), and a loud rename beats a quiet lie.
            #
            # serverWorkspace is the only one that decides where documents resolve
            # (`agent_backdoor.kt` builds a session key as `home + "/" + documentName`), and it
            # is read back from GET /api/instance-info. null means the server predates that
            # field -- distrust it, do not substitute the request.
            "serverWorkspace": client.server_workspace,
            "requestedWorkspace": client.requested_workspace,
            "workspaceAppliedToServer": client.managed_process is not None,
            "sessionId": client.session_id,
            "logFile": str(client.logger.path),
            "managedLog": str(client.managed_log_path) if client.managed_log_path else None,
            "documentName": client.document_name,
            "scratch": client.scratch,
            "readOnly": client.read_only,
            # The fifth field that can disagree with you, and the one that answers the
            # `boxCount: 0` you are being told to sanity-check two fields below. Both are
            # read back from the server's agent-open-document reply; `null` means the
            # server predates them, which is "unverified", not "no".
            "documentExisted": client.document_existed,
            "attachedToLiveDocument": client.attached_to_live_document,
            "scratchLifetime": "invocation" if client.scratch else None,
            "scratchPortReuse": False if client.scratch else None,
            "sessionKey": client.session_key,
            "boxCount": len(client.boxes),
            "runningBoxes": running,
            "lastBoxUid": client.last_box_uid,
            "aliases": dict(client.box_aliases),
            "playwright": client.playwright_state,
            "graphicsContextEnabled": client.graphics_context_enabled,
            "graphicsContextBoxUid": client.graphics_context_uid,
            "graphicsContextBoxName": GRAPHICS_CONTEXT_BOX_NAME if client.graphics_context_enabled else None,
        }
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "snapshot":
        data = await client.refresh_snapshot(timeout=client.default_timeout)
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "boxes":
        boxes = list(client.boxes.values())
        boxes.sort(key=lambda box: ((box.get("name") or "").lower(), box.get("uid") or ""))
        result = command_result(command_line, True, data=boxes, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], boxCount=len(boxes))
        return result

    if args.command == "sleep":
        seconds = max(0.0, float(args.seconds))
        client.logger.log("sleep_start", seconds=seconds)
        await asyncio.sleep(seconds)
        data = {"seconds": seconds}
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], sleptSeconds=seconds)
        return result

    if args.command == "playwright-open":
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await ensure_playwright_attached(client, timeout=timeout, reason="command:playwright-open")
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "dispose-graphics-context":
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.dispose_graphics_context(timeout=timeout)
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command in PROPERTY_FOLLOWING_COMMANDS and getattr(args, "property", "code") is None:
        args.property = default_property_of(client, getattr(args, "uid", None))

    if args.command == "editor-open":
        timeout = command_timeout(args.timeout, client.default_timeout)
        await ensure_playwright_attached(client, timeout=timeout, reason=f"command:editor-open:{args.uid}")
        data = await client.open_editor(
            uid=client.resolve_box_token(args.uid),
            property_name=args.property,
            open_outputs=args.open_outputs,
            padding=args.padding,
            pan_into_view=not args.no_pan,
            timeout=timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "screenshot-window":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:screenshot-window:{args.uid or 'window'}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.screenshot_window(
            path=args.path,
            uid=client.resolve_box_token(args.uid) if args.uid else None,
            property_name=args.property,
            show_editor=args.with_editor,
            editor_only=False,
            open_outputs=args.open_outputs,
            expect_outputs=getattr(args, "expect_outputs", "panel"),
            padding=12.0,
            pan_into_view=not args.no_pan,
            wait_ms=args.wait_ms,
            timeout=timeout,
            preserve_selection=args.preserve_selection,
            include_notifications=args.include_notifications,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "screenshot-editor":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:screenshot-editor:{args.uid}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.screenshot_window(
            path=args.path,
            uid=args.uid,
            property_name=args.property,
            show_editor=True,
            editor_only=True,
            open_outputs=args.open_outputs,
            expect_outputs=getattr(args, "expect_outputs", "panel"),
            padding=args.padding,
            pan_into_view=not args.no_pan,
            wait_ms=0.0,
            timeout=timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "screenshot-box":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:screenshot-box:{args.uid}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.screenshot_box(
            uid=args.uid,
            path=args.path,
            padding=args.padding,
            pan_into_view=not args.no_pan,
            open_outputs=args.open_outputs,
            expect_outputs=getattr(args, "expect_outputs", "panel"),
            timeout=timeout,
            preserve_selection=args.preserve_selection,
            include_notifications=args.include_notifications,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "native-screenshot":
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.native_window_screenshot(
            path=args.path,
            wait_frames=args.wait_frames,
            timeout=timeout,
            uid=args.uid,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "wait-native-renderer":
        timeout = command_timeout(args.timeout, client.default_timeout)
        data = await client.wait_native_renderer(
            uid=args.uid,
            frames=args.frames,
            timeout=timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "record-window":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:record-window:{args.uid or 'window'}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        # Add duration to timeout so the server has enough time to record + encode
        effective_timeout = max(timeout, args.duration + 30.0)
        data = await client.record_window(
            path=args.path,
            uid=args.uid,
            property_name=args.property,
            show_editor=args.with_editor,
            editor_only=False,
            open_outputs=args.open_outputs,
            expect_outputs=getattr(args, "expect_outputs", "panel"),
            padding=12.0,
            pan_into_view=not args.no_pan,
            duration=args.duration,
            fps=args.fps,
            frame_synchronous=args.frame_synchronous,
            restart_target=args.restart_target,
            timeout=effective_timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "record-editor":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:record-editor:{args.uid}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        effective_timeout = max(timeout, args.duration + 30.0)
        data = await client.record_window(
            path=args.path,
            uid=args.uid,
            property_name=args.property,
            show_editor=True,
            editor_only=True,
            open_outputs=args.open_outputs,
            expect_outputs=getattr(args, "expect_outputs", "panel"),
            padding=args.padding,
            pan_into_view=not args.no_pan,
            duration=args.duration,
            fps=args.fps,
            frame_synchronous=args.frame_synchronous,
            restart_target=args.restart_target,
            timeout=effective_timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "record-box":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:record-box:{args.uid}",
            )
        timeout = command_timeout(args.timeout, client.default_timeout)
        effective_timeout = max(timeout, args.duration + 30.0)
        data = await client.record_box(
            uid=args.uid,
            path=args.path,
            property_name=args.property,
            padding=args.padding,
            pan_into_view=not args.no_pan,
            duration=args.duration,
            fps=args.fps,
            frame_synchronous=args.frame_synchronous,
            restart_target=args.restart_target,
            timeout=effective_timeout,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "new-box":
        timeout = args.timeout or client.default_timeout
        data = await client.create_box(name=args.name, x=args.x, y=args.y, w=args.w, h=args.h, timeout=timeout)
        if args.alias:
            box = data.get("box")
            uid = box.get("uid") if isinstance(box, dict) else None
            if not isinstance(uid, str) or not uid:
                raise BackdoorError("new-box --alias requires the server to return a box uid")
            client.assign_box_alias(args.alias, uid)
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "delete-box":
        timeout = args.timeout or client.default_timeout
        data, events, timed_out = await client.delete_boxes(args.uids, timeout=timeout)
        # Drop stale references so a later @last/alias cannot resolve to a box we
        # just removed (the warm-session docs path deletes each capture's box and
        # then creates the next one at the same origin).
        deleted = set(data.get("boxes") or [])
        if client.last_box_uid in deleted:
            client.last_box_uid = None
        for alias, uid in list(client.box_aliases.items()):
            if uid in deleted:
                del client.box_aliases[alias]
        result = command_result(command_line, not timed_out, data=data, events=events, timed_out=timed_out)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out, eventCount=len(events))
        return result

    if args.command in {"connect", "disconnect"}:
        timeout = args.timeout or client.default_timeout
        data, events, timed_out = await client.change_connections(
            action=args.command,
            parent_uid=args.parent,
            child_uids=args.children,
            timeout=timeout,
        )
        result = command_result(command_line, not timed_out, data=data, events=events, timed_out=timed_out)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out, eventCount=len(events))
        return result

    if args.command == "set":
        if (args.value is None) == (args.file is None):
            raise BackdoorError("set requires exactly one of a literal value or --file")
        if args.file:
            value = client.read_text_source(args.file, command_line=command_line)
        else:
            value = args.value
        timeout = args.timeout or client.default_timeout
        data = await client.set_property(
            uid=args.uid,
            property_name=args.property,
            value=value,
            timeout=timeout,
        )
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command in {"macro", "transcript"}:
        timeout = args.timeout or client.default_timeout
        if args.content is not None and args.file is not None:
            raise BackdoorError(f"{args.command} accepts --content or --file, not both")
        if args.macro_command in {"write"} and args.content is None and args.file is None:
            raise BackdoorError(f"{args.command} write requires --content or --file")
        content = args.content or ""
        if args.file:
            content = client.read_text_source(args.file, command_line=command_line)
        data = await client.macro_transcript_command(
            command=args.macro_command,
            name=args.name or "",
            label=args.label,
            selection=[client.resolve_uid(value) for value in parse_repeated_string_values(args.selection)],
            scope=args.scope,
            project_name=args.project_name,
            content=content,
            offset=args.offset,
            limit=args.limit,
            indices=parse_repeated_int_values(args.index_values),
            mode=args.mode,
            fail_on_error=not args.no_fail_on_error,
            timeout=timeout,
        )
        ok = bool(data.get("success"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            macroCommand=args.macro_command,
            macroName=args.name or "",
            operationCount=data.get("operationCount"),
        )
        return result

    if args.command in {"run", "launch", "continue"}:
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:{args.command}:{args.uid}",
            )
        if args.value is not None and args.file is not None:
            raise BackdoorError(f"{args.command} accepts --value or --file, not both")
        value = args.value
        if args.file:
            value = client.read_text_source(args.file, command_line=command_line)
        timeout = args.timeout or client.default_timeout
        if args.property is None:
            _, target = client.require_box_reference(args.uid)
            args.property = target.get("default_property") or "code"
        if args.command == "run":
            data, events, timed_out = await client.run_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        elif args.command == "launch":
            data, events, timed_out = await client.launch_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        else:
            data, events, timed_out = await client.continue_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        data, ok = grade_execution(
            client,
            data,
            events,
            timed_out=timed_out,
            fail_on_error=not getattr(args, "no_fail_on_error", False),
        )
        result = command_result(command_line, ok, data=data, events=events, timed_out=timed_out)
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            timedOut=timed_out,
            eventCount=len(events),
            executionErrors=data.get("errors"),
        )
        return result

    if args.command == "launch-batch":
        if auto_playwright:
            await ensure_playwright_attached(
                client,
                timeout=auto_playwright_timeout or command_timeout(args.timeout, client.default_timeout),
                reason=f"command:launch-batch:{','.join(args.uids)}",
            )
        timeout = args.timeout or client.default_timeout
        data, events, timed_out = await client.launch_batch(
            args.uids,
            property_name=args.property,
            strand=args.strand,
            timeout=timeout,
        )
        data, ok = grade_execution(
            client,
            data,
            events,
            timed_out=timed_out,
            fail_on_error=not getattr(args, "no_fail_on_error", False),
        )
        result = command_result(command_line, ok, data=data, events=events, timed_out=timed_out)
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            timedOut=timed_out,
            eventCount=len(events),
            executionErrors=data.get("errors"),
        )
        return result

    if args.command == "wait-running":
        timeout = args.timeout or client.default_timeout
        data, events, timed_out = await client.wait_running(
            args.uid,
            desired_running=args.state == "running",
            settle=args.settle,
            timeout=timeout,
        )
        result = command_result(command_line, not timed_out, data=data, events=events, timed_out=timed_out)
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            timedOut=timed_out,
            eventCount=len(events),
        )
        return result

    if args.command == "action":
        data, events = await client.perform_action(
            args.uid,
            action_id=args.action_id,
            property_name=args.property,
            settle=max(0.0, args.settle),
            timeout=args.timeout or client.default_timeout,
        )
        problems = client.execution_errors(events)
        if problems:
            data = dict(data)
            data["errors"] = problems
        ok = bool(data.get("success")) and not problems
        result = command_result(command_line, ok, data=data, events=events)
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            eventCount=len(events),
        )
        return result

    if args.command == "stop":
        timeout = args.timeout or client.default_timeout
        if args.best_effort:
            timeout = min(timeout, 5.0)
        data, events, timed_out = await client.stop_box(args.uid, timeout=timeout)
        # Stopping the graphics-context box disposes its renderer server-side
        # (Server.stop -> disposeBoxRenderer), so drop the client cache too; a later
        # ensure_graphics_context() must then recreate it instead of reusing a dead box.
        if not timed_out and client.graphics_context_uid:
            stopped_uid = data.get("uid") if isinstance(data, dict) else None
            if stopped_uid == client.graphics_context_uid:
                client.graphics_context_uid = None
        if args.best_effort:
            data = dict(data)
            data["bestEffort"] = True
        result = command_result(
            command_line,
            (not timed_out) or bool(args.best_effort),
            data=data,
            events=events,
            timed_out=timed_out,
        )
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out, eventCount=len(events))
        return result

    if args.command == "wait-file":
        timeout = args.timeout or client.default_timeout
        path = Path(args.path)
        deadline = time.monotonic() + timeout
        stable_reads = max(1, int(args.stable_reads))
        observed_stable_reads = 0
        last_size = -1
        start_seq = client.event_seq
        fail_on_error_uid = client.require_box_reference(args.fail_on_error_uid)[0] if args.fail_on_error_uid else None

        def error_event(events):
            if fail_on_error_uid is None:
                return None
            for event in events:
                if event.get("uid") != fail_on_error_uid:
                    continue
                if _is_execution_error_event(event):
                    return event
            return None

        while time.monotonic() < deadline:
            events = client.events_since(start_seq)
            failed = error_event(events)
            if failed is not None:
                payload = failed.get("payload") or {}
                html = str(payload.get("html") or payload.get("text") or "execution error").replace("\n", " ").strip()
                data = {
                    "path": str(path),
                    "error": f"target {fail_on_error_uid} emitted an error while waiting for file: {html}",
                    "failedEvent": failed,
                }
                result = command_result(command_line, False, data=data, events=events)
                client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], eventCount=len(events), error=data["error"])
                return result
            if path.exists():
                stat = path.stat()
                size = stat.st_size
                if size > 0:
                    if args.newer_than is not None and stat.st_mtime < args.newer_than:
                        await asyncio.sleep(0.1)
                        continue
                    if size == last_size:
                        observed_stable_reads += 1
                    else:
                        observed_stable_reads = 0
                    last_size = size
                    if observed_stable_reads >= stable_reads:
                        data = {
                            "path": str(path),
                            "size": size,
                            "mtime": stat.st_mtime,
                            "stableReads": observed_stable_reads,
                        }
                        result = command_result(command_line, True, data=data, events=client.events_since(start_seq))
                        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], size=size)
                        return result
            await asyncio.sleep(0.1)
        data = {"path": str(path), "error": f"timed out waiting for file after {timeout:.1f}s"}
        result = command_result(command_line, False, data=data, events=client.events_since(start_seq), timed_out=True)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=True)
        return result

    if args.command == "output":
        events = client.collect_output(args.uid, channel=args.channel, kind=args.kind)
        data = {"uid": client.resolve_uid(args.uid), "kind": args.kind, "channel": args.channel, "count": len(events)}
        result = command_result(command_line, True, data=data, events=events)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], eventCount=len(events))
        return result

    if args.command == "wait-output":
        timeout = args.timeout or client.default_timeout
        events, timed_out, error_event = await client.wait_output(
            args.uid,
            channel=args.channel,
            kind=args.kind,
            contains=args.contains,
            timeout=timeout,
            fail_on_error=not args.no_fail_on_error,
        )
        data = {
            "uid": client.resolve_uid(args.uid),
            "kind": args.kind,
            "channel": args.channel,
            "contains": args.contains,
            "count": len(events),
        }
        if error_event is not None:
            payload = error_event.get("payload") or {}
            html = str(payload.get("html") or payload.get("text") or "execution error").replace("\n", " ").strip()
            data["error"] = f"target {client.resolve_uid(args.uid)} emitted an error while waiting for output: {html}"
            data["failedEvent"] = error_event
            # Surface the error event downstream (docs runner's
            # extract_backdoor_execution_error scans each command result's events).
            events = list(events) + [error_event]
        ok = (not timed_out) and (error_event is None)
        result = command_result(command_line, ok, data=data, events=events, timed_out=timed_out)
        client.logger.log(
            "command_dispatch_complete",
            commandLine=command_line,
            ok=result["ok"],
            eventCount=len(events),
            timedOut=timed_out,
            hasError=error_event is not None,
        )
        return result

    if args.command == "input-key":
        timeout = args.timeout or client.default_timeout
        action = {
            "press": "keyPress",
            "down": "keyDown",
            "up": "keyUp",
        }[args.mode]
        data = await client.playwright_input(action=action, key=args.key, selector=args.selector, timeout=timeout, wait_ms=args.wait_ms)
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "reply-note":
        from field_inspection import reply_review_note
        text = client.read_text_source(args.file, command_line=command_line) if args.file else args.text
        data = await reply_review_note(client, args.view_id, note_id=args.note_id, text=text, actor=args.actor,
            checked_revision=args.revision, expected_version=args.expected_version, timeout=args.timeout or client.default_timeout)
        return command_result(command_line, bool(data.get("ok")), data=data, events=[])

    if args.command == "review-note":
        from field_inspection import update_review_note
        data = await update_review_note(client, args.view_id, note_id=args.note_id, status=args.status,
            summary=args.summary, actor=args.actor, checked_revision=args.revision, expected_version=args.expected_version,
            timeout=args.timeout or client.default_timeout)
        return command_result(command_line, bool(data.get("ok")), data=data, events=[])

    if args.command == "inspect-view":
        from field_inspection import inspect_view
        state = json.loads(Path(args.state).read_text()) if args.state else None
        data = await inspect_view(client, args.view_id, state=state,
            section={"normal": args.section[:3], "fraction": args.section[3]} if args.section else None,
            hidden={**{key: True for key in args.hide}, **{key: False for key in args.show}},
            assembly=args.assembly,
            pick={"x":args.pick[0],"y":args.pick[1],"kind":args.feature} if args.pick else None,
            region=dict(zip(("cx","cy","width","height","angle"),args.region)) if args.region else None,
            note=args.note, received=args.received, evidence_dir=args.evidence_dir, include_resolved=args.include_resolved, timeout=args.timeout or client.default_timeout)
        if args.save and data.get("ok"):
            Path(args.save).write_text(json.dumps(data.get("result"), indent=2))
        return command_result(command_line, bool(data.get("ok")), data=data, events=[])

    if args.command == "page-eval":
        if (args.script is None) == (args.file is None):
            raise BackdoorError("page-eval requires exactly one of a literal script or --file")
        if args.file:
            script = client.read_text_source(args.file, command_line=command_line)
        else:
            script = args.script
        timeout = args.timeout or client.default_timeout
        data = await client.playwright_input(
            action="pageEval",
            script=script,
            script_mode="body" if args.body else "expression",
            timeout=timeout,
            wait_ms=args.wait_ms,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "click-box":
        timeout = args.timeout or client.default_timeout
        data = await client.playwright_input(action="clickBox", uid=args.uid, timeout=timeout, wait_ms=args.wait_ms)
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "drag-box":
        timeout = args.timeout or client.default_timeout
        data = await client.playwright_input(
            action="dragBox",
            uid=args.uid,
            dx=args.dx,
            dy=args.dy,
            hold=args.hold,
            steps=args.steps,
            timeout=timeout,
            wait_ms=args.wait_ms,
        )
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "mouse-up":
        timeout = args.timeout or client.default_timeout
        data = await client.playwright_input(action="mouseUp", timeout=timeout, wait_ms=args.wait_ms)
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "show-history":
        timeout = args.timeout or client.default_timeout
        data = await client.playwright_input(action="showHistory", filename=args.filename, timeout=timeout, wait_ms=args.wait_ms)
        ok = bool(data.get("ok"))
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "agent-task":
        # Hands a whole task to Field's own agent. The server runs the turn (live provider or a
        # scripted replay) in this session's document and replies with an AgentTaskResult. `ok` is
        # the task's verdict, not the transport's: anything but "pass" exits 1, like `run` does for a
        # box that failed (2026-09-14).
        checks = []
        for spec in args.checks:
            checks.append(parse_agent_check(spec))
        payload: dict[str, Any] = {
            "goal": args.goal,
            "checks": checks,
            "mode": args.mode,
            "maxToolRounds": args.max_rounds,
            "model": args.model,
            "focusBox": args.focus_box,
        }
        if args.script:
            payload["script"] = client.read_text_source(args.script, command_line=command_line)
        if args.setup:
            payload["setup"] = client.read_text_source(args.setup, command_line=command_line)
        timeout = args.timeout or max(client.default_timeout, 600.0)
        event = await client.call_for_single_reply("agent-task", payload, timeout=timeout)
        data = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        try:
            await client.refresh_snapshot(timeout=min(30.0, timeout))
        except Exception as exc:  # the result is still the result
            client.logger.exception("agent_task_snapshot_refresh_error", exc)
        ok = data.get("verdict") == "pass"
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=ok, verdict=data.get("verdict"))
        return result

    if args.command == "agent-eval":
        payload = {
            "ids": [x.strip() for x in args.ids.split(",") if x.strip()],
            "mode": args.mode,
            "maxToolRounds": args.max_rounds,
            "model": args.model,
        }
        timeout = args.timeout or max(client.default_timeout, 3600.0)
        event = await client.call_for_single_reply("agent-eval", payload, timeout=timeout)
        data = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        ok = bool(data) and data.get("passed") == data.get("population")
        result = command_result(command_line, ok, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=ok)
        return result

    if args.command == "raw-send":
        try:
            payload = json.loads(args.payload)
        except json.JSONDecodeError as exc:
            raise BackdoorError(f"raw-send payload is not valid JSON: {exc}") from exc
        timeout = args.timeout or client.default_timeout
        data, events, timed_out = await client.raw_send(args.name, payload, reply_name=args.reply, timeout=timeout)
        result = command_result(command_line, not timed_out, data=data, events=events, timed_out=timed_out)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out, eventCount=len(events))
        return result

    if args.command == "run-jvm-main":
        timeout = args.timeout or client.default_timeout
        data = await client.run_jvm_main(
            args.entrypoint,
            jvm_args=args.entry_args,
            timeout=timeout,
            include_command=args.include_command,
        )
        timed_out = bool(data.get("timedOut"))
        ok = bool(data.get("ok")) and not timed_out
        result = command_result(command_line, ok, data=data, events=[], timed_out=timed_out)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out)
        return result

    raise BackdoorError(f"Unhandled command: {args.command}")


def render_event(event: dict[str, Any]) -> str:
    name = event.get("name")
    uid = event.get("uid")
    payload = event.get("payload")
    prefix = f"[{event.get('seq')}] {name}"
    if uid:
        prefix += f" uid={uid}"
    if name == "output" and isinstance(payload, dict):
        html = payload.get("html", "")
        return f"{prefix} kind={payload.get('kind')} line={payload.get('line')} {html}"
    if name == "set-output" and isinstance(payload, dict):
        return f"{prefix} channel={payload.get('channel')} {payload.get('value', '')}"
    if name == "output-control" and isinstance(payload, dict):
        return f"{prefix} action={payload.get('action')} channel={payload.get('channel')}"
    if name == "notify" and isinstance(payload, dict):
        return f"{prefix} {payload.get('text', '')}"
    return f"{prefix} {json.dumps(payload, ensure_ascii=True)}"


def print_result(result: dict[str, Any], *, json_mode: bool, logger: SessionLogger | None = None) -> None:
    if logger is not None:
        logger.log("print_result", jsonMode=json_mode, result=result)
    if json_mode:
        print(json.dumps(result, indent=2, ensure_ascii=True))
        return

    status = "ok" if result["ok"] else "error"
    print(f"{status}: {result['command']}")
    data = result.get("data")
    if data is not None:
        print(json.dumps(data, indent=2, ensure_ascii=True))
    events = result.get("events") or []
    if events:
        print("events:")
        for event in events:
            print(render_event(event))
    if result.get("timedOut"):
        print("timed out")


async def repl(
    client: FieldBackdoor,
    *,
    json_mode: bool,
    auto_playwright: bool = False,
    auto_playwright_timeout: float | None = None,
) -> int:
    while True:
        try:
            line = await asyncio.to_thread(input, "field> ")
        except EOFError:
            client.logger.log("repl_eof")
            return 0
        line = line.strip()
        client.logger.log("repl_input", line=line)
        if not line:
            continue
        if line in {"quit", "exit"}:
            client.logger.log("repl_exit_command", line=line)
            return 0
        if line == "help":
            client.logger.log("repl_help")
            print("Commands: open, status, snapshot, boxes, sleep, playwright-open, editor-open, inspect-view, review-note, reply-note, page-eval, screenshot-window, screenshot-editor, screenshot-box, native-screenshot, wait-native-renderer, record-window, record-editor, record-box, new-box, delete-box, connect, disconnect, set, macro, transcript, run, launch, launch-batch, continue, wait-running, action, stop, wait-file, output, wait-output, raw-send, run-jvm-main, exit")
            continue
        try:
            parsed = parse_command(line)
            result = await dispatch_command(
                client,
                parsed,
                line,
                auto_playwright=auto_playwright,
                auto_playwright_timeout=auto_playwright_timeout,
            )
        except Exception as exc:
            client.logger.exception("repl_command_error", exc, commandLine=line)
            result = command_result(line, False, data={"error": exception_message(exc)})
        print_result(result, json_mode=json_mode, logger=client.logger)


def collect_command_lines(args: argparse.Namespace) -> list[str]:
    commands = list(args.command or [])
    if args.single_command:
        commands.append(" ".join(args.single_command).strip())
    return [command for command in commands if command]


async def async_main(argv: list[str], logger: SessionLogger) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--document")
    parser.add_argument("--scratch", action="store_true")
    parser.add_argument(
        "--read-only",
        action="store_true",
        help="open the document without writing back to it (not the same as --scratch)",
    )
    parser.add_argument("--graphics-context", action="store_true")
    parser.add_argument("--with-playwright", action="store_true")
    parser.add_argument("--command", action="append")
    parser.add_argument("--workspace")
    parser.add_argument(
        "--attach-existing",
        action="store_true",
        help=(
            "Attach to an already-running Field from this checkout even when source changed "
            "after it started; never starts a server and never accepts a foreign checkout."
        ),
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("single_command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    logger.log(
        "args_parsed",
        json=args.json,
        document=args.document,
        scratch=args.scratch,
        graphicsContext=args.graphics_context,
        withPlaywright=args.with_playwright,
        commandCount=len(args.command or []),
        workspace=args.workspace,
        attachExisting=args.attach_existing,
        port=args.port,
        timeout=args.timeout,
        singleCommand=args.single_command,
    )

    client = FieldBackdoor(
        port=args.port,
        workspace=args.workspace,
        default_timeout=args.timeout,
        logger=logger,
        attach_existing=args.attach_existing,
    )
    client.graphics_context_enabled = bool(args.graphics_context)
    try:
        await client.ensure_server()
        await client.connect()

        command_lines = collect_command_lines(args)
        logger.log("command_lines_collected", commandLines=command_lines)
        auto_playwright_timeout = command_timeout(args.timeout, client.default_timeout)
        should_auto_open = True
        if command_lines and not args.document and not args.scratch:
            try:
                first = parse_command(command_lines[0])
                if first.command in {"open", "run-jvm-main"}:
                    should_auto_open = False
            except Exception:
                pass
        logger.log("auto_open_decision", shouldAutoOpen=should_auto_open)

        if should_auto_open:
            await client.open_document(
                args.document,
                scratch=args.scratch or not args.document,
                timeout=args.timeout,
                read_only=args.read_only,
            )
            if client.graphics_context_enabled:
                await client.ensure_graphics_context(timeout=args.timeout)
            if args.with_playwright:
                await ensure_playwright_attached(
                    client,
                    timeout=auto_playwright_timeout,
                    reason="startup:--with-playwright",
                )

        if command_lines:
            for line in command_lines:
                try:
                    parsed = parse_command(line)
                    result = await dispatch_command(
                        client,
                        parsed,
                        line,
                        auto_playwright=args.with_playwright,
                        auto_playwright_timeout=auto_playwright_timeout,
                    )
                except Exception as exc:
                    logger.exception("batch_command_error", exc, commandLine=line)
                    result = command_result(line, False, data={"error": exception_message(exc)})
                print_result(result, json_mode=args.json, logger=logger)
                if not result["ok"]:
                    logger.log("async_main_exit", exitCode=1)
                    return 1
            logger.log("async_main_exit", exitCode=0)
            return 0

        exit_code = await repl(
            client,
            json_mode=args.json,
            auto_playwright=args.with_playwright,
            auto_playwright_timeout=auto_playwright_timeout,
        )
        logger.log("async_main_exit", exitCode=exit_code)
        return exit_code
    finally:
        await client.close()


def main() -> int:
    logger = SessionLogger(LOG_ROOT, sys.argv[1:])
    try:
        exit_code = asyncio.run(async_main(sys.argv[1:], logger))
        logger.log("process_exit", exitCode=exit_code)
        return exit_code
    except KeyboardInterrupt:
        logger.log("keyboard_interrupt", exitCode=130)
        return 130
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 1
        logger.log("system_exit", exitCode=code)
        return code
    except Exception as exc:
        logger.exception("fatal_error", exc)
        # A refusal nobody can see is not a refusal. Every failure out of `ensure_server` or
        # `connect` -- the foreign-checkout refusal, the workspace-mismatch refusal, a dropped
        # websocket -- used to exit 1 with empty stdout AND empty stderr, leaving the reason
        # only in `.field-backdoor/logs/<session>.jsonl` for whoever thought to look.
        print(f"field_backdoor.py failed: {exception_message(exc)}", file=sys.stderr)
        print(f"  session log: {logger.path}", file=sys.stderr, flush=True)
        return 1
    finally:
        logger.close()


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3

from __future__ import annotations

import argparse
import asyncio
import datetime
import json
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

def _is_running_interpreter(candidate: Path) -> bool:
    """True if ``candidate`` is the interpreter executing this process.

    ``os.path.samefile`` rather than string comparison: a venv ships ``python``, ``python3`` and
    ``python3.12`` as links to one binary, so the path a caller constructs rarely matches the
    spelling in ``sys.executable``.
    """
    try:
        return os.path.samefile(str(candidate), sys.executable)
    except OSError:
        return False


def _reexec_under_repo_env_if_needed() -> None:
    """Self-heal the "wrong Python interpreter" trap.

    Agents/humans naturally type ``python tools/field_backdoor.py``, but the
    ambient interpreter (e.g. system/conda Python) usually lacks ``websockets``.
    Rather than dying with a pip hint, transparently re-exec the *current* entry
    script under the repo's ``.venv`` (or via ``uv run``), which has the deps.
    Guarded by an env var so it can never loop.
    """
    try:
        import websockets  # noqa: F401
        return
    except ImportError:
        pass
    if os.environ.get("FIELD_BACKDOOR_REEXEC") == "1":
        raise SystemExit(
            "field_backdoor: 'websockets' is unavailable even after re-exec into the repo environment.\n"
            "Run: uv sync --group backdoor   (or: python3 -m pip install websockets)"
        )
    import shutil
    repo_root = Path(__file__).resolve().parent.parent
    entry = os.path.abspath(sys.argv[0]) if sys.argv and sys.argv[0] else str(Path(__file__).resolve())
    env = {**os.environ, "FIELD_BACKDOOR_REEXEC": "1"}
    venv_py = repo_root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python3")
    # Only re-exec the repo venv if we are not *already* running it. `uv run` without a
    # `--group` builds a base-only `.venv` and then runs `.venv/bin/python`, so websockets is
    # missing in the very interpreter this branch would re-exec into: the re-exec cannot help,
    # and because the module runs this at import time it re-runs the whole *entry script* first.
    # On 2026-08-22 that entry script was `tools/run_python_tests.py`, so the twelve tool suites
    # ran a second time and the runner then died inside an import, discarding its own summary —
    # the gate reported three PIL errors and no totals for what was really a missing dependency
    # group. Compare by inode, not by path: `sys.executable` is `.venv/bin/python` while this
    # builds `.venv/bin/python3`, two names for one file.
    if venv_py.exists() and not _is_running_interpreter(venv_py):
        os.execve(str(venv_py), [str(venv_py), entry, *sys.argv[1:]], env)
    uv = shutil.which("uv")
    if uv:
        os.execvpe(
            uv, [uv, "run", "--project", str(repo_root), "--group", "backdoor",
                 "python", entry, *sys.argv[1:]], env,
        )
    raise SystemExit(
        "field_backdoor.py requires the 'websockets' package and no repo .venv or uv was found.\n"
        "Run via: uv run --project . --group backdoor python tools/field_backdoor.py ..."
    )


_reexec_under_repo_env_if_needed()

import websockets  # noqa: E402  (guaranteed importable after the shim above)
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
LOG_STRING_PREVIEW = 512
# Each backdoor invocation writes a session log; historically these accumulated
# unbounded (thousands of files / >100MB). Keep only the most recent N of each
# kind. Override with AFIELD_BACKDOOR_LOG_KEEP, or 0 to disable pruning.
LOG_KEEP = int(os.environ.get("AFIELD_BACKDOOR_LOG_KEEP", "300"))
LOG_COLLECTION_PREVIEW = 32
GRAPHICS_CONTEXT_WAIT_SEC = 0.5
GRAPHICS_CONTEXT_BOX_NAME = "renderer"
GRADLE_WRAPPER = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
GRADLE_JVMARGS = "-Xmx8g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"


class BackdoorError(RuntimeError):
    pass


class CommandParseError(BackdoorError):
    pass


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


def command_result(command: str, ok: bool, data: Any = None, events: list[dict[str, Any]] | None = None, timed_out: bool = False) -> dict[str, Any]:
    return {
        "ok": ok,
        "command": command,
        "data": data,
        "events": events or [],
        "timedOut": timed_out,
    }


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
    if isinstance(value, str):
        if len(value) <= LOG_STRING_PREVIEW:
            return value
        return {
            "__type__": "truncated-string",
            "length": len(value),
            "preview": value[:LOG_STRING_PREVIEW],
        }
    if isinstance(value, dict):
        items = list(value.items())
        summarized = {str(k): _summarize_for_log(v) for k, v in items[:LOG_COLLECTION_PREVIEW]}
        if len(items) > LOG_COLLECTION_PREVIEW:
            summarized["__truncated_items__"] = len(items) - LOG_COLLECTION_PREVIEW
        return summarized
    if isinstance(value, (list, tuple)):
        items = list(value)
        summarized = [_summarize_for_log(v) for v in items[:LOG_COLLECTION_PREVIEW]]
        if len(items) > LOG_COLLECTION_PREVIEW:
            summarized.append({"__truncated_items__": len(items) - LOG_COLLECTION_PREVIEW})
        return summarized
    return value


class SessionLogger:
    @staticmethod
    def prune(root: Path, keep: int = LOG_KEEP) -> None:
        """Keep only the newest `keep` logs of each kind; best-effort."""
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
        except OSError:
            pass

    def __init__(self, root: Path, argv: list[str]) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self.prune(root)
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.session_id = f"{stamp}-pid{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self.path = self.root / f"{self.session_id}.jsonl"
        self.handle = self.path.open("a", encoding="utf-8")
        self.log(
            "session_start",
            argv=argv,
            cwd=os.getcwd(),
            pid=os.getpid(),
            python=sys.version,
            logPath=str(self.path),
        )

    def log(self, event: str, **fields: Any) -> None:
        record = {"timestamp": utc_timestamp(), "event": event}
        record.update(_summarize_for_log(fields))
        self.handle.write(json.dumps(record, ensure_ascii=True, default=str) + "\n")
        self.handle.flush()

    def exception(self, event: str, exc: BaseException, **fields: Any) -> None:
        self.log(
            event,
            errorType=type(exc).__name__,
            error=str(exc),
            traceback="".join(traceback.format_exception(type(exc), exc, exc.__traceback__)),
            **fields,
        )

    def close(self) -> None:
        if self.handle.closed:
            return
        self.log("session_end")
        self.handle.close()


class FieldBackdoor:
    def __init__(self, *, port: int, workspace: str | None, default_timeout: float, logger: SessionLogger) -> None:
        self.port = port
        # A backdoor-managed Field is automation, even when the caller omitted
        # --workspace. Never let it inherit the machine-global interactive
        # preference, which may name a deleted sibling worktree.
        self.workspace = workspace or str(automation_workspace(REPO_ROOT))
        self.default_timeout = default_timeout
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
        self.session_key: str | None = None
        self.boxes: dict[str, dict[str, Any]] = {}
        self._running_state_seen: dict[tuple[str, str], tuple[int, bool]] = {}
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
        )

    async def ensure_server(self) -> None:
        self.logger.log("ensure_server_start", port=self.port, workspace=self.workspace)
        initial_health = health_check(self.port, self.ssl_context)
        self.logger.log("health_check", phase="initial", port=self.port, healthy=initial_health)
        if initial_health:
            info = field_instance_info(self.port, self.ssl_context)
            problem = field_instance_problem(info, REPO_ROOT)
            if problem is not None:
                self.logger.log("server_reuse_refused", port=self.port, reason=problem, instance=info)
                raise BackdoorError(
                    f"Refusing to reuse Field on port {self.port}: {problem}. "
                    "Choose a fresh --port or stop that server explicitly."
                )
            self.reused_existing_server = True
            self.logger.log("server_reused", port=self.port, instance=info)
            return

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
                self.logger.log("managed_server_ready", attempt=attempt, managedLog=str(self.managed_log_path))
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
                ping_timeout=30,
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
        self.document_name = snapshot.get("documentName")
        self.scratch = bool(snapshot.get("scratch"))
        self.session_key = snapshot.get("sessionKey")
        self.boxes = {}
        self._running_state_seen = {}
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
            },
            timeout=timeout,
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

        while time.monotonic() < deadline:
            attempts += 1
            remaining = max(0.25, deadline - time.monotonic())
            try:
                payload = await self.native_window_screenshot(
                    path=str(wait_path),
                    wait_frames=safe_frames,
                    timeout=remaining,
                )
                last_payload = payload
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

    async def open_document(self, document: str | None, *, scratch: bool, timeout: float) -> dict[str, Any]:
        self.logger.log("open_document_start", document=document, scratch=scratch, timeout=timeout)
        event = await self.call_for_single_reply(
            "agent-open-document",
            {
                "documentName": document,
                "scratch": scratch,
                "username": "agent",
            },
            timeout=timeout,
        )
        payload = event["payload"]
        if not isinstance(payload, dict):
            raise BackdoorError("agent-open-document returned an invalid payload")
        self._replace_snapshot(payload)
        self.clear_events()
        self.logger.log("open_document_complete", documentName=self.document_name, scratch=self.scratch)
        result = dict(payload)
        if self.scratch:
            result["scratchLifetime"] = "invocation"
            result["scratchPortReuse"] = False
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
        """Set a single string property on a box (e.g. notes). Direct setter."""
        resolved, _ = self.require_box_reference(uid)
        event = await self.call_for_single_reply(
            "agent-set-property",
            {"uid": resolved, "property": property_name, "value": value},
            timeout=timeout,
        )
        payload = event.get("payload")
        if not isinstance(payload, dict):
            raise BackdoorError("agent-set-property returned an invalid payload")
        return payload

    async def ai_tools_list(self, *, timeout: float) -> list[dict[str, Any]]:
        """List the ai4 agent tools available for the attached document."""
        event = await self.call_for_single_reply("agent-ai-tools-list", {}, timeout=timeout)
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
        resolved, box = self.require_box_reference(uid)
        self.logger.log("run_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = str(box.get(property_name) or "")
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
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "run"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                if event["name"] != "is-running" or event.get("uid") != resolved:
                    continue
                payload = event.get("payload") or {}
                if payload.get("strand") == "__fragment__" and payload.get("isRunning") is False:
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
        resolved, box = self.require_box_reference(uid)
        self.logger.log("launch_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = str(box.get(property_name) or "")
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
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "launch"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                if event["name"] != "is-running" or event.get("uid") != resolved:
                    continue
                payload = event.get("payload") or {}
                strand = payload.get("strand") or "default"
                if strand != "default":
                    continue
                if payload.get("isRunning") is True:
                    return events, True
                if payload.get("isRunning") is False:
                    return events, False
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
        }
        self.logger.log("launch_box_complete", uid=resolved, timedOut=timed_out, running=running, eventCount=len(events))
        return data, events, timed_out

    async def continue_box(self, uid: str, *, property_name: str, value: str | None, timeout: float) -> tuple[dict[str, Any], list[dict[str, Any]], bool]:
        resolved, box = self.require_box_reference(uid)
        self.logger.log("continue_box_start", uid=resolved, property=property_name, timeout=timeout, hasInlineValue=value is not None)
        if value is not None:
            await self.set_string(resolved, property_name, value)
        code = str(box.get(property_name) or "")
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
            },
            reply_to=reply_name,
            reply_context={"uid": resolved, "command": "continue"},
        )

        def predicate():
            events = self.events_since(start_seq)
            for event in events:
                if event["name"] != "is-running" or event.get("uid") != resolved:
                    continue
                payload = event.get("payload") or {}
                strand = payload.get("strand") or "default"
                if strand != "default":
                    continue
                if payload.get("isRunning") is True:
                    return events, True
                if payload.get("isRunning") is False:
                    return events, False
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
        """The first execution-error event this box has emitted, if any.

        Mirrors the error detection `wait-file --fail-on-error-uid` uses: a box
        compile/runtime failure surfaces as an `output`/`peer-output` with
        `kind` in {error, err}, or a `notify` with category `error`.
        """
        for event in self.event_log:
            if event.get("uid") != resolved:
                continue
            name = event.get("name") or event.get("messageName")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if name in {"output", "peer-output"} and payload.get("kind") in {"error", "err"}:
                return event
            if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
                return event
        return None

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
            name = event.get("name") or event.get("messageName")
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue
            if name in {"output", "peer-output"} and payload.get("kind") in {"error", "err"}:
                html = str(payload.get("html", "")).replace("\n", " ").strip()
                if html:
                    problems.append(html)
            elif name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
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

    open_parser = subparsers.add_parser("open", add_help=False)
    open_parser.add_argument("document", nargs="?")
    open_parser.add_argument("--scratch", action="store_true")

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
    editor_open.add_argument("--property", default="code")
    editor_open.add_argument("--open-outputs", action="store_true")
    editor_open.add_argument("--padding", type=float, default=12.0)
    editor_open.add_argument("--no-pan", action="store_true")
    editor_open.add_argument("--timeout", type=float)

    screenshot_window = subparsers.add_parser("screenshot-window", add_help=False)
    screenshot_window.add_argument("path")
    screenshot_window.add_argument("--uid")
    screenshot_window.add_argument("--property", default="code")
    screenshot_window.add_argument("--with-editor", action="store_true")
    screenshot_window.add_argument("--open-outputs", action="store_true")
    screenshot_window.add_argument("--no-pan", action="store_true")
    screenshot_window.add_argument("--wait-ms", type=float, default=0.0)
    screenshot_window.add_argument("--preserve-selection", action="store_true")
    screenshot_window.add_argument("--include-notifications", action="store_true")
    screenshot_window.add_argument("--timeout", type=float)

    screenshot_editor = subparsers.add_parser("screenshot-editor", add_help=False)
    screenshot_editor.add_argument("uid")
    screenshot_editor.add_argument("path")
    screenshot_editor.add_argument("--property", default="code")
    screenshot_editor.add_argument("--padding", type=float, default=12.0)
    screenshot_editor.add_argument("--open-outputs", action="store_true")
    screenshot_editor.add_argument("--no-pan", action="store_true")
    screenshot_editor.add_argument("--timeout", type=float)

    screenshot_box = subparsers.add_parser("screenshot-box", add_help=False)
    screenshot_box.add_argument("uid")
    screenshot_box.add_argument("path")
    screenshot_box.add_argument("--padding", type=float, default=12.0)
    screenshot_box.add_argument("--open-outputs", dest="open_outputs", action="store_true", default=True)
    screenshot_box.add_argument("--no-open-outputs", dest="open_outputs", action="store_false")
    screenshot_box.add_argument("--no-pan", action="store_true")
    screenshot_box.add_argument("--preserve-selection", action="store_true")
    screenshot_box.add_argument("--include-notifications", action="store_true")
    screenshot_box.add_argument("--timeout", type=float)

    native_screenshot = subparsers.add_parser("native-screenshot", add_help=False)
    native_screenshot.add_argument("path")
    native_screenshot.add_argument("--wait-frames", type=int, default=30)
    native_screenshot.add_argument("--timeout", type=float)

    wait_native_renderer = subparsers.add_parser("wait-native-renderer", add_help=False)
    wait_native_renderer.add_argument("uid", nargs="?")
    wait_native_renderer.add_argument("--frames", type=int, default=3)
    wait_native_renderer.add_argument("--timeout", type=float)

    record_window = subparsers.add_parser("record-window", add_help=False)
    record_window.add_argument("path")
    record_window.add_argument("--uid")
    record_window.add_argument("--property", default="code")
    record_window.add_argument("--with-editor", action="store_true")
    record_window.add_argument("--open-outputs", action="store_true")
    record_window.add_argument("--no-pan", action="store_true")
    record_window.add_argument("--duration", type=float, default=5.0)
    record_window.add_argument("--fps", type=int, default=30)
    record_window.add_argument("--frame-synchronous", action="store_true")
    record_window.add_argument("--restart-target", action="store_true")
    record_window.add_argument("--timeout", type=float)

    record_editor = subparsers.add_parser("record-editor", add_help=False)
    record_editor.add_argument("uid")
    record_editor.add_argument("path")
    record_editor.add_argument("--property", default="code")
    record_editor.add_argument("--padding", type=float, default=12.0)
    record_editor.add_argument("--open-outputs", action="store_true")
    record_editor.add_argument("--no-pan", action="store_true")
    record_editor.add_argument("--duration", type=float, default=5.0)
    record_editor.add_argument("--fps", type=int, default=30)
    record_editor.add_argument("--frame-synchronous", action="store_true")
    record_editor.add_argument("--restart-target", action="store_true")
    record_editor.add_argument("--timeout", type=float)

    record_box = subparsers.add_parser("record-box", add_help=False)
    record_box.add_argument("uid")
    record_box.add_argument("path")
    record_box.add_argument("--property", default="code")
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

    for command_name in ("run", "launch", "continue"):
        exec_parser = subparsers.add_parser(command_name, add_help=False)
        exec_parser.add_argument("uid")
        exec_parser.add_argument("--property", default="code")
        exec_parser.add_argument("--value")
        exec_parser.add_argument("--file")
        exec_parser.add_argument("--timeout", type=float)

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
    if args.command == "open":
        data = await client.open_document(args.document, scratch=args.scratch or not args.document, timeout=client.default_timeout)
        if client.graphics_context_enabled:
            data = dict(data)
            data["graphicsContext"] = await client.ensure_graphics_context(timeout=client.default_timeout)
        result = command_result(command_line, True, data=data, events=[])
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"])
        return result

    if args.command == "status":
        running = [uid for uid, box in client.boxes.items() if box.get("runningStrands")]
        data = {
            "connected": client.websocket is not None,
            "managedServer": client.managed_process is not None,
            "reusedExistingServer": client.reused_existing_server,
            "sessionId": client.session_id,
            "logFile": str(client.logger.path),
            "managedLog": str(client.managed_log_path) if client.managed_log_path else None,
            "documentName": client.document_name,
            "scratch": client.scratch,
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
        data = await client.set_string(args.uid, args.property, value)
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
        if args.command == "run":
            data, events, timed_out = await client.run_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        elif args.command == "launch":
            data, events, timed_out = await client.launch_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        else:
            data, events, timed_out = await client.continue_box(args.uid, property_name=args.property, value=value, timeout=timeout)
        result = command_result(command_line, not timed_out, data=data, events=events, timed_out=timed_out)
        client.logger.log("command_dispatch_complete", commandLine=command_line, ok=result["ok"], timedOut=timed_out, eventCount=len(events))
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
                name = event.get("name") or event.get("messageName")
                payload = event.get("payload")
                if not isinstance(payload, dict):
                    continue
                if name in {"output", "peer-output"} and payload.get("kind") in {"error", "err"}:
                    return event
                if name == "notify" and str(payload.get("category", "")).strip().lower() == "error":
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
            print("Commands: open, status, snapshot, boxes, sleep, playwright-open, editor-open, page-eval, screenshot-window, screenshot-editor, screenshot-box, native-screenshot, wait-native-renderer, record-window, record-editor, record-box, new-box, delete-box, connect, disconnect, set, macro, transcript, run, launch, continue, stop, wait-file, output, wait-output, raw-send, run-jvm-main, exit")
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
            result = command_result(line, False, data={"error": str(exc)})
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
    parser.add_argument("--graphics-context", action="store_true")
    parser.add_argument("--with-playwright", action="store_true")
    parser.add_argument("--command", action="append")
    parser.add_argument("--workspace")
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
        port=args.port,
        timeout=args.timeout,
        singleCommand=args.single_command,
    )

    client = FieldBackdoor(port=args.port, workspace=args.workspace, default_timeout=args.timeout, logger=logger)
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
            await client.open_document(args.document, scratch=args.scratch or not args.document, timeout=args.timeout)
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
                    result = command_result(line, False, data={"error": str(exc)})
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
        return 1
    finally:
        logger.close()


if __name__ == "__main__":
    raise SystemExit(main())

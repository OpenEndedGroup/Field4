#!/usr/bin/env python3
"""Warm-session daemon + client for the Field backdoor (SPIKE).

Motivation
----------
Today every agent capture (docs screenshot, smoke test, ad-hoc check) spawns a
fresh ``field_backdoor.py`` process that reconnects a WebSocket, opens a scratch
document, and re-attaches Playwright before doing ~one useful thing, then tears
it all down. The expensive resource -- the JVM -- is shared and warm, but the
per-call setup is paid ~236 times in a full docs build, serially.

This module keeps ONE ``FieldBackdoor`` connection (JVM + WebSocket + Playwright)
warm and serves many command batches over a Unix-domain socket. It reuses the
existing ``field_backdoor`` machinery verbatim -- the same ``dispatch_command``,
``parse_command``, ``open_document`` and ``ensure_playwright_attached`` the REPL
already drives -- so behaviour matches the one-shot path; only the process /
connect / attach overhead is removed.

Subcommands
-----------
  serve   Start the warm session and listen on a Unix socket.
  call    Send one command batch to a running daemon and print the JSON result.
  bench   A/B benchmark: N captures via process-per-call vs. warm session.

This is a spike: it does NOT modify field_backdoor.py, docs_hooks.py, or
run_field_tests.py. If the benchmark proves the win, the daemon graduates into
those callers.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

# field_backdoor.py lives in the same directory; sys.path[0] is tools/ when run
# as `python tools/field_session.py`.
sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from .field_python_env import ensure_repo_python  # noqa: E402
except ImportError:  # Executed as tools/field_session.py rather than imported as a package.
    from field_python_env import ensure_repo_python  # noqa: E402

ensure_repo_python()

import field_backdoor as fb  # noqa: E402


def socket_path_for(port: int) -> Path:
    return fb.REPO_ROOT / ".field-backdoor" / f"session-{port}.sock"


# --------------------------------------------------------------------------- #
# Daemon
# --------------------------------------------------------------------------- #
async def _run_batch(client: fb.FieldBackdoor, request: dict[str, Any]) -> dict[str, Any]:
    """Execute one command batch against the already-warm client.

    request keys (all optional except commands):
      commands        list[str]  backdoor command lines
      stdin           str        payload for any `--file -` reference
      timeout         float      per-batch timeout / open_document budget
      withPlaywright  bool       attach/keep the browser for this batch
      resetDoc        bool       open a fresh scratch doc first (default True)
      scratch         bool       scratch vs named document on reset (default True)
      document        str|None   named document on reset
      graphicsContext bool       ensure a graphics context after reset
    """
    timeout = float(request.get("timeout") or client.default_timeout)
    with_pw = bool(request.get("withPlaywright"))
    apw_timeout = fb.playwright_attach_budget(timeout, client.default_timeout)
    timings: dict[str, float] = {}

    if request.get("resetDoc", True):
        document = request.get("document")
        scratch = bool(request.get("scratch", True)) or not document
        t = time.monotonic()
        await client.open_document(document, scratch=scratch, timeout=timeout)
        timings["reset_doc"] = time.monotonic() - t
        if request.get("graphicsContext"):
            client.graphics_context_enabled = True
            t = time.monotonic()
            await client.ensure_graphics_context(timeout=timeout)
            timings["graphics_context"] = time.monotonic() - t
        if with_pw:
            t = time.monotonic()
            await fb.ensure_playwright_attached(client, timeout=apw_timeout, reason="batch:reset")
            timings["playwright_attach"] = time.monotonic() - t

    # Feed `--file -` without touching the daemon's real stdin.
    client.stdin_cache = request.get("stdin")

    results: list[dict[str, Any]] = []
    ok = True
    for line in request.get("commands", []):
        t = time.monotonic()
        try:
            parsed = fb.parse_command(line)
            result = await fb.dispatch_command(
                client,
                parsed,
                line,
                auto_playwright=with_pw,
                auto_playwright_timeout=apw_timeout,
            )
        except Exception as exc:  # noqa: BLE001 - mirror field_backdoor batch loop
            client.logger.exception("session_batch_command_error", exc, commandLine=line)
            result = fb.command_result(line, False, data={"error": str(exc)})
        verb = line.split()[0] if line.split() else "?"
        timings[f"cmd:{verb}"] = timings.get(f"cmd:{verb}", 0.0) + (time.monotonic() - t)
        results.append(result)
        if not result["ok"]:
            ok = False
            break

    # Reset so a stray later `--file -` cannot reuse this batch's stdin.
    client.stdin_cache = None
    return {"ok": ok, "results": results, "timings": timings}


async def serve(
    *,
    port: int,
    workspace: str | None,
    default_timeout: float,
    with_playwright: bool,
    sock_path: Path,
) -> int:
    logger = fb.SessionLogger(fb.LOG_ROOT, ["field_session", "serve", f"--port={port}"])
    client = fb.FieldBackdoor(
        port=port, workspace=workspace, default_timeout=default_timeout, logger=logger
    )
    await client.ensure_server()
    await client.connect()
    await client.open_document(None, scratch=True, timeout=default_timeout)
    if with_playwright:
        await fb.ensure_playwright_attached(
            client,
            timeout=fb.playwright_attach_budget(default_timeout, default_timeout),
            reason="serve:startup",
        )

    # The client is single-connection and not concurrency-safe: serialize batches.
    lock = asyncio.Lock()

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            raw = await reader.readline()
            if not raw:
                return
            try:
                request = json.loads(raw.decode("utf-8"))
            except Exception as exc:  # noqa: BLE001
                response = {"ok": False, "results": [], "error": f"bad request: {exc}"}
            else:
                if request.get("command") == "ping":
                    response = {"ok": True, "results": [], "pong": True}
                elif request.get("command") == "shutdown":
                    response = {"ok": True, "results": [], "shutdown": True}
                    writer.write((json.dumps(response) + "\n").encode("utf-8"))
                    await writer.drain()
                    _server_ref["stop"].set()
                    return
                else:
                    async with lock:
                        try:
                            response = await _run_batch(client, request)
                        except Exception as exc:  # noqa: BLE001 - keep the daemon alive across bad batches
                            client.logger.exception("session_batch_failed", exc)
                            response = {"ok": False, "results": [], "error": f"batch failed: {exc}"}
            writer.write((json.dumps(response) + "\n").encode("utf-8"))
            await writer.drain()
        except (ConnectionResetError, BrokenPipeError):
            pass
        finally:
            writer.close()

    if sock_path.exists():
        sock_path.unlink()
    sock_path.parent.mkdir(parents=True, exist_ok=True)
    server = await asyncio.start_unix_server(handle, path=str(sock_path))
    _server_ref["stop"] = asyncio.Event()

    print(json.dumps({"ready": True, "socket": str(sock_path), "port": port}), flush=True)
    try:
        async with server:
            stop_task = asyncio.create_task(_server_ref["stop"].wait())
            serve_task = asyncio.create_task(server.serve_forever())
            await asyncio.wait({stop_task, serve_task}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        if sock_path.exists():
            sock_path.unlink()
        await client.close()
    return 0


_server_ref: dict[str, Any] = {}


# --------------------------------------------------------------------------- #
# Client
# --------------------------------------------------------------------------- #
async def send_request(sock_path: Path, request: dict[str, Any], *, timeout: float) -> dict[str, Any]:
    # A single response can carry screenshot/event payloads far larger than the
    # asyncio StreamReader default (64 KiB); raise the line limit so readline()
    # doesn't raise LimitOverrunError on a big-but-valid response.
    reader, writer = await asyncio.open_unix_connection(path=str(sock_path), limit=256 * 1024 * 1024)
    try:
        writer.write((json.dumps(request) + "\n").encode("utf-8"))
        await writer.drain()
        raw = await asyncio.wait_for(reader.readline(), timeout=timeout)
    finally:
        writer.close()
    if not raw:
        raise RuntimeError("empty response from daemon")
    return json.loads(raw.decode("utf-8"))


# --------------------------------------------------------------------------- #
# Benchmark
# --------------------------------------------------------------------------- #
def _capture_commands(shot_path: Path, *, x: float = 0.0) -> tuple[list[str], str]:
    """A realistic docs-style capture: new box, run a snippet, screenshot the box."""
    snippet = 'display("main", "<div style=\\"padding:24px;background:#ffe08a\\">warm-session bench</div>")'
    commands = [
        f"new-box --name BenchBox --x {x} --y 0 --w 320 --h 160",
        "run @last --file -",
        f"screenshot-box @last {shot_path} --padding 12",
    ]
    return commands, snippet


def _run_process_per_call(port: int, iterations: int, scratch_dir: Path, timeout: float) -> float:
    """Mode A: today's path -- spawn field_backdoor.py once per capture."""
    backdoor = Path(__file__).resolve().parent / "field_backdoor.py"
    start = time.monotonic()
    for i in range(iterations):
        shot = scratch_dir / f"proc_{i}.png"
        commands, snippet = _capture_commands(shot)
        cmd = [
            sys.executable, str(backdoor), "--json", "--with-playwright",
            "--port", str(port), "--timeout", str(timeout),
        ]
        for c in commands:
            cmd += ["--command", c]
        proc = subprocess.run(
            cmd, cwd=str(fb.REPO_ROOT), input=snippet, text=True,
            capture_output=True, timeout=timeout + 60.0,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"process-per-call iter {i} failed: {proc.stderr[-500:]}")
    return time.monotonic() - start


async def _run_warm_session(
    port: int, iterations: int, scratch_dir: Path, timeout: float,
    *, reset: bool, prefix: str,
) -> tuple[float, dict[str, float]]:
    """Mode B: warm session -- one connection, N socket batches over a live daemon.

    reset=True mirrors today's capture isolation (fresh scratch doc + Playwright
    re-attach per capture). reset=False reuses the same warm doc/page and only
    stamps a fresh, non-overlapping box each time -- the "lean" floor.
    """
    sock_path = socket_path_for(port)
    serve_task = asyncio.create_task(
        serve(
            port=port, workspace=None, default_timeout=timeout,
            with_playwright=True, sock_path=sock_path,
        )
    )
    # Wait for the daemon to be ready.
    for _ in range(240):
        if sock_path.exists():
            try:
                pong = await send_request(sock_path, {"command": "ping"}, timeout=5.0)
                if pong.get("pong"):
                    break
            except (ConnectionRefusedError, FileNotFoundError, OSError):
                pass
        await asyncio.sleep(0.25)
    else:
        serve_task.cancel()
        raise RuntimeError("warm-session daemon did not become ready")

    last_timings: dict[str, float] = {}
    try:
        start = time.monotonic()
        for i in range(iterations):
            shot = scratch_dir / f"{prefix}_{i}.png"
            commands, snippet = _capture_commands(shot, x=0.0 if reset else i * 360.0)
            request = {
                "commands": commands,
                "stdin": snippet,
                "withPlaywright": True,
                "resetDoc": reset,
                "timeout": timeout,
            }
            resp = await send_request(sock_path, request, timeout=timeout + 60.0)
            if not resp.get("ok"):
                raise RuntimeError(f"warm-session iter {i} failed: {json.dumps(resp)[:500]}")
            last_timings = resp.get("timings") or {}
        elapsed = time.monotonic() - start
    finally:
        try:
            await send_request(sock_path, {"command": "shutdown"}, timeout=5.0)
        except Exception:  # noqa: BLE001
            pass
        try:
            await asyncio.wait_for(serve_task, timeout=15.0)
        except Exception:  # noqa: BLE001
            serve_task.cancel()
    return elapsed, last_timings


def _start_persistent_field(port: int, timeout: float) -> subprocess.Popen[Any]:
    """Start ONE long-lived Field JVM and keep it healthy for the whole bench.

    Crucial for fairness: a backdoor process that *self-starts* the JVM kills it
    on close, so a one-shot warm-up leaves nothing warm and every Mode A call
    would cold-boot. By keeping an external JVM alive, BOTH modes see a healthy
    server (`reused_existing_server=True`) and leave it running -- matching how
    the docs build shares one warm JVM across all captures.
    """
    log_path = fb.LOG_ROOT / f"bench-persistent-field-{port}.log"
    fb.LOG_ROOT.mkdir(parents=True, exist_ok=True)
    log_file = log_path.open("w", encoding="utf-8")
    workspace = str(fb.automation_workspace(fb.REPO_ROOT))
    cmd = fb.gradle_command("runJvm")
    cmd.append(f"--args=--workspace {shlex.quote(workspace)}")
    proc = subprocess.Popen(
        cmd, cwd=str(fb.REPO_ROOT), stdout=log_file, stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL, text=True,
        env=fb.automation_server_environment(port),
        start_new_session=True,
    )
    ssl_ctx = fb.make_unverified_ssl_context()
    deadline = time.monotonic() + 180.0
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"persistent Field exited early (code {proc.returncode}); see {log_path}")
        if fb.health_check(port, ssl_ctx):
            return proc
        time.sleep(0.5)
    proc.terminate()
    raise RuntimeError(f"persistent Field did not become healthy on port {port} within 180s; see {log_path}")


def _stop_persistent_field(proc: subprocess.Popen[Any]) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


async def bench(*, port: int, iterations: int, timeout: float) -> int:
    scratch_dir = fb.REPO_ROOT / ".field-backdoor" / "bench"
    scratch_dir.mkdir(parents=True, exist_ok=True)

    # Hold ONE warm JVM for the whole run so both modes attach-and-reuse. This
    # isolates the per-call overhead (process spawn + WS connect + scratch open
    # + Playwright attach + teardown) -- the thing the warm session removes --
    # instead of accidentally measuring JVM boot time.
    print(f"Starting one persistent Field JVM on port {port} (shared by both modes) ...", flush=True)
    field_proc = _start_persistent_field(port, timeout)
    try:
        print(f"Mode A (process-per-call):     {iterations} captures ...", flush=True)
        proc_secs = _run_process_per_call(port, iterations, scratch_dir, timeout)

        print(f"Mode B (warm session, reset):  {iterations} captures ...", flush=True)
        warm_secs, warm_timings = await _run_warm_session(
            port, iterations, scratch_dir, timeout, reset=True, prefix="warm",
        )

        print(f"Mode C (warm session, lean):   {iterations} captures ...", flush=True)
        lean_secs, lean_timings = await _run_warm_session(
            port, iterations, scratch_dir, timeout, reset=False, prefix="lean",
        )
    finally:
        _stop_persistent_field(field_proc)

    def rate(total: float) -> str:
        return f"{total:8.2f} s  ({total / iterations:6.2f} s/capture)"

    print("\n=== field warm-session spike benchmark (one shared warm JVM) ===")
    print(f"iterations                     : {iterations}")
    print(f"A process-per-call             : {rate(proc_secs)}")
    print(f"B warm session (reset+reattach): {rate(warm_secs)}   {proc_secs / warm_secs:.2f}x vs A")
    print(f"C warm session (lean, reuse)   : {rate(lean_secs)}   {proc_secs / lean_secs:.2f}x vs A")
    print("\nPer-capture phase breakdown (last iteration, seconds):")
    print(f"  B reset+reattach: {json.dumps({k: round(v, 3) for k, v in sorted(warm_timings.items())})}")
    print(f"  C lean reuse    : {json.dumps({k: round(v, 3) for k, v in sorted(lean_timings.items())})}")
    return 0


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)

    p_serve = sub.add_parser("serve", help="Start the warm-session daemon.")
    p_serve.add_argument("--port", type=int, default=fb.DEFAULT_PORT)
    p_serve.add_argument("--workspace")
    p_serve.add_argument("--timeout", type=float, default=fb.DEFAULT_TIMEOUT)
    p_serve.add_argument("--with-playwright", action="store_true")

    p_call = sub.add_parser("call", help="Send one command batch to a running daemon.")
    p_call.add_argument("--port", type=int, default=fb.DEFAULT_PORT)
    p_call.add_argument("--command", action="append", default=[])
    p_call.add_argument("--stdin-file", help="Read `--file -` payload from this path ('-' for stdin).")
    p_call.add_argument("--with-playwright", action="store_true")
    p_call.add_argument("--no-reset", action="store_true", help="Do not open a fresh scratch doc first.")
    p_call.add_argument("--timeout", type=float, default=fb.DEFAULT_TIMEOUT)

    p_bench = sub.add_parser("bench", help="A/B benchmark process-per-call vs warm session.")
    p_bench.add_argument("--port", type=int, default=9477)
    p_bench.add_argument("--iterations", type=int, default=8)
    p_bench.add_argument("--timeout", type=float, default=45.0)

    args = parser.parse_args()

    if args.mode == "serve":
        sock_path = socket_path_for(args.port)
        return asyncio.run(serve(
            port=args.port, workspace=args.workspace, default_timeout=args.timeout,
            with_playwright=args.with_playwright, sock_path=sock_path,
        ))

    if args.mode == "call":
        stdin_payload: str | None = None
        if args.stdin_file:
            stdin_payload = sys.stdin.read() if args.stdin_file == "-" else Path(args.stdin_file).read_text(encoding="utf-8")
        request = {
            "commands": args.command,
            "stdin": stdin_payload,
            "withPlaywright": args.with_playwright,
            "resetDoc": not args.no_reset,
            "timeout": args.timeout,
        }
        sock_path = socket_path_for(args.port)
        resp = asyncio.run(send_request(sock_path, request, timeout=args.timeout + 60.0))
        print(json.dumps(resp, indent=2))
        return 0 if resp.get("ok") else 1

    if args.mode == "bench":
        return asyncio.run(bench(port=args.port, iterations=args.iterations, timeout=args.timeout))

    return 2


if __name__ == "__main__":
    raise SystemExit(main())

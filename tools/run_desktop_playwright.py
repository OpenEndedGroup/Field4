#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import signal
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parent.parent
LOG_ROOT = REPO_ROOT / ".field-backdoor" / "logs"


def port_is_open(port: int) -> bool:
    for host in ("127.0.0.1", "::1"):
        try:
            family = socket.AF_INET6 if ":" in host else socket.AF_INET
            with socket.socket(family, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.25)
                sock.connect((host, port))
                return True
        except OSError:
            pass
    return False


def require_free_port(port: int, label: str) -> None:
    if port_is_open(port):
        raise SystemExit(f"{label} port {port} is already in use")


def ssl_context() -> ssl.SSLContext:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def get_text(url: str, *, timeout: float, context: ssl.SSLContext | None = None) -> str:
    with urllib.request.urlopen(url, timeout=timeout, context=context) as response:
        return response.read().decode("utf-8", errors="replace")


def wait_for_url(url: str, *, timeout: float, context: ssl.SSLContext | None = None, contains: str | None = None) -> None:
    deadline = time.monotonic() + timeout
    last_error: str | None = None
    while time.monotonic() < deadline:
        try:
            body = get_text(url, timeout=1.5, context=context)
            if contains is None or contains in body:
                return
            last_error = f"response from {url!r} did not contain {contains!r}"
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
            last_error = str(error)
        time.sleep(0.5)
    raise TimeoutError(f"timed out waiting for {url}: {last_error}")


def stop_process_tree(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        process.terminate()
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
    try:
        process.wait(timeout=8)
        return
    except subprocess.TimeoutExpired:
        pass
    if os.name == "nt":
        process.kill()
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            return
    process.wait(timeout=8)


def timestamp() -> str:
    return _dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Launch ./gradlew runDesktop with deterministic ports and print a Playwright-friendly URL."
    )
    parser.add_argument("--port", type=int, default=9577, help="HTTPS Field port passed as PORT")
    parser.add_argument("--http-port", type=int, default=19577, help="Loopback HTTP mirror passed as AFIELD_HTTP_PORT")
    parser.add_argument("--document", default="scratch", help="Document name for the printed automation URL")
    parser.add_argument("--timeout", type=float, default=150.0, help="Seconds to wait for Field to become reachable")
    parser.add_argument("--no-hold", action="store_true", help="Exit after readiness instead of keeping runDesktop alive")
    parser.add_argument("--extra-url-param", action="append", default=[], help="Additional raw URL parameter, e.g. user=playwright")
    parser.add_argument("--with-devtools", action="store_true", help="Pass -PwithDevTools=true to runDesktop")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    require_free_port(args.port, "HTTPS")
    require_free_port(args.http_port, "HTTP mirror")

    LOG_ROOT.mkdir(parents=True, exist_ok=True)
    log_path = LOG_ROOT / f"run-desktop-playwright-{timestamp()}-p{args.port}.log"
    log_file = log_path.open("w", encoding="utf-8")

    command = ["./gradlew", "runDesktop"]
    if args.with_devtools:
        command.append("-PwithDevTools=true")

    env = {
        **os.environ,
        "PORT": str(args.port),
        "AFIELD_HTTP_PORT": str(args.http_port),
        "AFIELD_PLAYWRIGHT_VISIBLE": os.environ.get("AFIELD_PLAYWRIGHT_VISIBLE", "false"),
    }
    popen_kwargs: dict[str, Any] = {
        "cwd": str(REPO_ROOT),
        "stdout": log_file,
        "stderr": subprocess.STDOUT,
        "stdin": subprocess.DEVNULL,
        "text": True,
        "env": env,
    }
    if os.name == "nt":
        popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
    else:
        popen_kwargs["start_new_session"] = True

    process = subprocess.Popen(command, **popen_kwargs)
    encoded_doc = urllib.parse.quote(args.document)
    extra_params = ["user=playwright", *args.extra_url_param]
    query = "&".join(["file=" + encoded_doc, *extra_params])
    https_url = f"https://localhost:{args.port}/index.html?{query}"
    http_url = f"http://localhost:{args.http_port}/index.html?{query}"
    status_path = LOG_ROOT / f"run-desktop-playwright-{args.port}.json"
    status = {
        "ok": False,
        "pid": process.pid,
        "port": args.port,
        "httpPort": args.http_port,
        "document": args.document,
        "httpsUrl": https_url,
        "httpUrl": http_url,
        "logFile": str(log_path),
        "statusFile": str(status_path),
    }
    status_path.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")

    try:
        context = ssl_context()
        wait_for_url(f"https://localhost:{args.port}/health", timeout=args.timeout, context=context, contains="ok")
        wait_for_url(http_url, timeout=30.0)
        status["ok"] = True
        status["readyAt"] = _dt.datetime.now(_dt.timezone.utc).isoformat()
        status_path.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(status, indent=2), flush=True)
        if args.no_hold:
            return 0
        print("runDesktop is ready for Playwright. Press Ctrl-C to stop it.", flush=True)
        while process.poll() is None:
            time.sleep(1.0)
        return process.returncode or 0
    except KeyboardInterrupt:
        print("Stopping runDesktop...", file=sys.stderr)
        return 130
    except Exception as error:
        status["error"] = str(error)
        status_path.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(status, indent=2), file=sys.stderr, flush=True)
        return 1
    finally:
        if args.no_hold:
            # In --no-hold mode the caller owns cleanup, which is useful for smoke scripts that
            # only need the URL and PID.
            pass
        else:
            stop_process_tree(process)
        log_file.close()


if __name__ == "__main__":
    raise SystemExit(main())

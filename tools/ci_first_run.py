#!/usr/bin/env python3
"""Qualify the real source launcher on a fresh CI machine, without optional services or a GPU.

Run from the checkout: python tools/ci_first_run.py [--timeout 1800].
The workflow runs this in a clone with LFS and empty project build output. A local run reuses
whatever toolchain/dependency caches exist; it must not be reported as a cold-download test.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    context = ssl._create_unverified_context()  # Field's local development certificate only.
    base = f"https://127.0.0.1:{port}"

    def request(path: str, *, origin: str | None = None, method: str = "GET") -> tuple[int, bytes]:
        headers = {"Origin": origin, "Sec-Fetch-Site": "cross-site"} if origin else {}
        req = urllib.request.Request(base + path, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, context=context, timeout=3) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    with tempfile.TemporaryDirectory(prefix="field-first-run-workspace-") as scratch:
        workspace = Path(scratch)
        report_dir = root / "build" / "first-run-evidence"
        report_dir.mkdir(parents=True, exist_ok=True)
        log_path = report_dir / "launcher.log"
        env = dict(os.environ, PORT=str(port), AFIELD_HTTP_PORT="off", AFIELD_FIRST_RUN="skip",
                   AFIELD_CODE_USE_SEMANTIC_SIDECAR="off", AFIELD_FIELD_SH_NO_TERMINAL="1",
                   AFIELD_FIELD_PS1_NO_PAUSE="1",
                   OPENAI_API_KEY="", ANTHROPIC_API_KEY="")
        env.pop("AFIELD_BIND_HOST", None)
        env.pop("AFIELD_API_REQUIRE_SAME_SITE", None)
        env.pop("AFIELD_SKIP_BUILD", None)
        command = (["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(root / "field.ps1")]
                   if os.name == "nt" else [str(root / "field.sh")]) + ["--server-only", str(workspace)]
        print(f"Starting real launcher; log: {log_path}", flush=True)
        with log_path.open("wb") as log:
            proc = subprocess.Popen(command, cwd=root, env=env, stdin=subprocess.DEVNULL,
                                    stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=os.name != "nt")
            try:
                deadline = time.monotonic() + args.timeout
                while time.monotonic() < deadline:
                    if proc.poll() is not None:
                        raise RuntimeError(f"Launcher exited before health (exit {proc.returncode}); inspect {log_path}")
                    try:
                        if request("/health") == (200, b"ok"):
                            break
                    except (OSError, TimeoutError):
                        pass
                    time.sleep(1)
                else:
                    raise TimeoutError(f"No health response within {args.timeout}s; inspect {log_path}")
                code, body = request("/api/instance-info")
                assert code == 200, ("instance-info", code)
                identity = json.loads(body)
                assert Path(identity["repoRoot"]).resolve() == root.resolve(), identity
                assert Path(identity["workspace"]).resolve() == workspace.resolve(), identity
                for path in ("/welcome", "/index.html?file=firstRunSmoke", "/afield.mjs"):
                    code, body = request(path)
                    assert code == 200 and len(body) > 100, (path, code, len(body))
                code, _ = request("/api/instance-info", origin="https://foreign.example")
                assert code == 403, ("cross-site read", code)
                evidence = {"ok": True, "platform": os.name, "repo": str(root), "serverPid": identity["pid"],
                            "checks": ["health", "checkout/workspace identity", "welcome HTML", "editor HTML",
                                       "built browser module", "default cross-site refusal"],
                            "limits": "Server-only smoke; no desktop, GPU, Python/JEP, AI or optional services qualified."}
                (report_dir / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
                print(json.dumps(evidence, indent=2), flush=True)
                return 0
            finally:
                if proc.poll() is None:
                    try:
                        request("/api/shutdown-instance", method="POST")
                    except OSError:
                        pass
                    try:
                        proc.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        if os.name == "nt":
                            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], check=False)
                        else:
                            os.killpg(proc.pid, signal.SIGTERM)
                        proc.wait(timeout=10)


if __name__ == "__main__":
    raise SystemExit(main())

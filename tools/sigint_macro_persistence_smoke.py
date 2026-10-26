#!/usr/bin/env python3
"""Macro replay, then SIGINT within about a second: does metadata.edn hold the result? (N1020-1)

Replays an OperationTranscript macro (create, connect, edit) into a fresh document of a Field you
started yourself, signals that Field with SIGINT as soon as the macro returns, waits for it to exit,
and reads the document's metadata.edn and sidecars from disk. Exit 0 when every created box, its
name, its frame, the connection and the edited code are there.

The Field is the one listening on --port; it must be yours, because this script stops it.
Start one with, e.g.

    PORT=9611 AFIELD_HTTP_PORT=off AFIELD_SKIP_WORKSPACE_PERSIST=1 \\
        ./gradlew runJvm --args="--workspace /tmp/sigint-ws" &

then

    uv run --project . --group backdoor python tools/sigint_macro_persistence_smoke.py \\
        --port 9611 --workspace /tmp/sigint-ws

See dev_docs/SIGINT_SHUTDOWN.md ("Macro replay, then SIGINT").
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

MACRO = """\
name: sigint_persistence
operations:
  - create:
      box: sp_source
      name: sigint source
      frame: { x: 333.0, y: 120.0, w: 300.0, h: 100.0 }
      properties:
        code: "val sigintValue = 1"
  - create:
      box: sp_sink
      name: sigint sink
      frame: { x: 333.0, y: 300.0, w: 300.0, h: 100.0 }
  - connect: { parent: sp_source, child: sp_sink }
  - edit: { box: sp_source, property: code, from: "= 1", to: "= 42" }
"""


def listening_pid(port: int) -> int | None:
    out = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--workspace", required=True, help="the Field's workspace (its serverWorkspace)")
    ap.add_argument("--document", default=f"sigint_macro_{int(time.time())}")
    ap.add_argument("--exit-timeout", type=float, default=20.0)
    args = ap.parse_args()

    pid = listening_pid(args.port)
    if pid is None:
        print(f"FAIL: nothing listens on {args.port}; start your own Field first", file=sys.stderr)
        return 2
    workspace = Path(args.workspace).resolve()
    doc_dir = workspace / args.document

    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        f.write(MACRO)
        macro_path = f.name
    cmd = [sys.executable, str(REPO / "tools" / "field_backdoor.py"), "--json", "--port", str(args.port),
           "--attach-existing", "--workspace", str(workspace),
           "--command", f"open {args.document} --create --keep-running",
           "--command", f"macro write sigint_persistence --file {macro_path}",
           "--command", "macro run sigint_persistence"]
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)
    finished = time.monotonic()
    os.kill(pid, signal.SIGINT)
    signalled_after = time.monotonic() - finished
    os.unlink(macro_path)

    results = []
    dec, s, i = json.JSONDecoder(), proc.stdout, 0
    while s[i:].strip():
        rest = s[i:].lstrip()
        obj, n = dec.raw_decode(rest)
        i = len(s) - len(rest) + n
        results.append(obj)
    if proc.returncode != 0 or not results or not all(r.get("ok") for r in results):
        print(f"FAIL: the macro did not run cleanly (exit {proc.returncode}):\n{proc.stdout[-2000:]}\n{proc.stderr[-2000:]}", file=sys.stderr)
        return 1

    deadline = time.monotonic() + args.exit_timeout
    while listening_pid(args.port) == pid and time.monotonic() < deadline:
        time.sleep(0.1)
    exited = listening_pid(args.port) != pid
    print(f"SIGINT {signalled_after * 1000:.0f} ms after the macro returned; Field exited: {exited}")
    if not exited:
        print("FAIL: Field did not exit after SIGINT", file=sys.stderr)
        return 1

    metadata = (doc_dir / "metadata.edn").read_text(encoding="utf-8")
    code_files = list(doc_dir.glob("sp_source_code_.kt"))
    code = code_files[0].read_text(encoding="utf-8") if code_files else metadata
    checks = {
        "source box": '"sp_source"' in metadata,
        "sink box": '"sp_sink"' in metadata,
        "source name": "sigint source" in metadata,
        "sink name": "sigint sink" in metadata,
        "sink frame (y=300)": "333.0 300.0" in metadata,
        "connection": '#afield/box "sp_sink"' in metadata,
        "edited code (= 42)": "sigintValue = 42" in code,
    }
    for name, ok in checks.items():
        print(f"  {'ok  ' if ok else 'LOST'} {name}")
    if all(checks.values()):
        print(f"PASS: {doc_dir}/metadata.edn holds the macro's result")
        return 0
    print(f"FAIL: {doc_dir}/metadata.edn is missing part of the macro's result", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

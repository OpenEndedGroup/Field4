"""``python -m fieldresolve``: answer requests about the running Resolve, one JSON object per line.

Field's ``afield.io.resolve.Resolve`` starts this as a worker and keeps it running, so the
connection to Resolve is made once. Each request on stdin is ``{"id": n, "op": "...", ...}``;
each reply on stdout is ``{"id": n, "ok": true, "result": ...}`` or
``{"id": n, "ok": false, "error": "...", "kind": "unavailable" | "missing" | "error"}``.

Ops: ``status``, ``timelines``, ``timeline`` / ``markers`` / ``clipMarkers`` / ``playhead`` (each
takes an optional ``"timeline": name``), and ``close``. Every op takes an optional ``"host"``, another
machine whose Resolve Studio allows network scripting. ``python -m fieldresolve markers [name]
[--host H]`` runs one op and prints its result, which is handy from a terminal.
"""

from __future__ import annotations

import json
import sys
import traceback

from . import ResolveUnavailable, clip_markers, connect, markers, playhead, status, timeline, timelines

OPS = {
    "status": lambda req: status(req.get("host")),
    "timelines": lambda req: timelines(req.get("markers", True), req.get("host")),
    "timeline": lambda req: timeline(req.get("timeline"), req.get("host")),
    "markers": lambda req: markers(req.get("timeline"), req.get("host")),
    "clipMarkers": lambda req: clip_markers(req.get("timeline"), req.get("host")),
    "playhead": lambda req: playhead(req.get("timeline"), req.get("host")),
}


def answer(req: dict) -> dict:
    rid = req.get("id")
    op = req.get("op")
    try:
        if op not in OPS:
            raise ValueError(f"unknown op {op!r}; ops: {sorted(OPS)}")
        try:
            result = OPS[op](req)
        except (ResolveUnavailable, KeyError, ValueError):
            raise
        except Exception:
            # A Resolve that was quit and restarted leaves a dead connection behind; try once more.
            connect(req.get("host"), fresh=True)
            result = OPS[op](req)
        return {"id": rid, "ok": True, "result": result}
    except ResolveUnavailable as e:
        return {"id": rid, "ok": False, "kind": "unavailable", "error": str(e)}
    except KeyError as e:
        return {"id": rid, "ok": False, "kind": "missing", "error": e.args[0] if e.args else str(e)}
    except Exception as e:
        return {"id": rid, "ok": False, "kind": "error", "error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()}


def serve() -> None:
    out = sys.stdout
    sys.stdout = sys.stderr  # anything Resolve's module prints must not corrupt the reply stream
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            reply = {"id": None, "ok": False, "kind": "error", "error": f"bad request: {e}"}
        else:
            if req.get("op") == "close":
                out.write(json.dumps({"id": req.get("id"), "ok": True, "result": None}) + "\n")
                out.flush()
                return
            reply = answer(req)
        out.write(json.dumps(reply) + "\n")
        out.flush()


def main(argv: list[str]) -> int:
    if not argv or argv[0] == "serve":
        serve()
        return 0
    args = list(argv)
    req = {"id": 0, "op": args.pop(0)}
    if "--host" in args:
        i = args.index("--host")
        req["host"] = args[i + 1]
        del args[i:i + 2]
    if args:
        req["timeline"] = args[0]
    reply = answer(req)
    print(json.dumps(reply.get("result") if reply["ok"] else reply, indent=2))
    return 0 if reply["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

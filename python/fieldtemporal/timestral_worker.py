# /// script
# requires-python = ">=3.10,<3.14"
# dependencies = [
#     "numpy>=1.24",
#     "torch>=2.4",
#     "timestral @ git+https://github.com/mistralai/timestral@a534bdbd244d64f3d9d743f46c7bf6fc330ed4ff",
# ]
# ///
"""Timestral forecasting worker for Field's `afield.math.temporal.TimestralSession`.

Runs in its own uv-managed environment (`uv run --script`), so Torch never loads into the
Field JVM or into the venv Python boxes use. Field starts one worker per session and talks to
it over stdin/stdout with length-prefixed frames; logs go to stderr.

Frame: 4-byte big-endian header length, a UTF-8 JSON header, then the arrays the header lists
under "arrays" — each {"name", "dtype": "f4"|"u1", "shape": [...]} — as raw little-endian bytes
in that order. Every reply echoes the request's "id" and "generation".

Requests:
  {"op": "hello"}                       → versions, model, device, load seconds
  {"op": "forecast", "horizon": H, ...} with arrays context (V,T) f4, optional mask (V,T) u1,
                                          optional known (C,T+H) f4
                                        → arrays quantiles (V,H,Q) f4, median (V,H) f4
  {"op": "close"}

`--install --model <repo>` resolves the environment and downloads the checkpoint, then exits.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import time
import traceback

PROTOCOL = 1


def log(*a):
    print("[timestral-worker]", *a, file=sys.stderr, flush=True)


def read_exact(n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sys.stdin.buffer.read(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return bytes(buf)


def read_frame(np):
    (hlen,) = struct.unpack(">I", read_exact(4))
    header = json.loads(read_exact(hlen).decode("utf-8"))
    arrays = {}
    for spec in header.get("arrays", []):
        dtype = np.dtype("<f4") if spec["dtype"] == "f4" else np.dtype("u1")
        count = int(np.prod(spec["shape"])) if spec["shape"] else 1
        arrays[spec["name"]] = np.frombuffer(read_exact(count * dtype.itemsize), dtype=dtype).reshape(spec["shape"])
    return header, arrays


def write_frame(header: dict, arrays: dict | None = None):
    arrays = arrays or {}
    header = dict(header, protocol=PROTOCOL, arrays=[
        {"name": k, "dtype": "f4", "shape": list(v.shape)} for k, v in arrays.items()
    ])
    raw = json.dumps(header).encode("utf-8")
    out = sys.stdout.buffer
    out.write(struct.pack(">I", len(raw)))
    out.write(raw)
    for v in arrays.values():
        out.write(v.astype("<f4", copy=False).tobytes())
    out.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="mistralai/Timestral-28M")
    ap.add_argument("--revision", default=None)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--threads", type=int, default=0)
    args = ap.parse_args()

    import numpy as np
    import torch
    from importlib import metadata

    from timestral import TimestralForecaster

    if args.threads > 0:
        torch.set_num_threads(args.threads)

    t0 = time.perf_counter()
    forecaster = TimestralForecaster.from_pretrained(
        args.model, device=args.device, revision=args.revision,
        local_files_only=(not args.install) and os.environ.get("AFIELD_OFFLINE") == "1",
    )
    load_seconds = time.perf_counter() - t0
    info = {
        "model": args.model,
        "revision": args.revision,
        "device": args.device,
        "loadSeconds": load_seconds,
        "timestral": metadata.version("timestral"),
        "torch": torch.__version__,
        "numpy": np.__version__,
        "python": sys.version.split()[0],
        "platform": sys.platform,
        "quantileLevels": [float(q) for q in forecaster.forecast(np.zeros(64, dtype=np.float32), 1).quantile_levels],
    }
    if args.install:
        print(json.dumps(info))
        return
    log("ready", json.dumps(info))

    while True:
        try:
            header, arrays = read_frame(np)
        except EOFError:
            return
        rid, gen = header.get("id"), header.get("generation")
        op = header.get("op")
        try:
            if op == "hello":
                write_frame({"id": rid, "generation": gen, "ok": True, "info": info})
            elif op == "close":
                write_frame({"id": rid, "generation": gen, "ok": True})
                return
            elif op == "forecast":
                ctx = arrays["context"].astype(np.float32)
                mask = arrays.get("mask")
                known = arrays.get("known")
                horizon = int(header["horizon"])
                t1 = time.perf_counter()
                with torch.inference_mode():
                    f = forecaster.forecast(
                        ctx[None], prediction_length=horizon,
                        observation_mask=None if mask is None else (mask[None] != 0),
                        known_covariates=None if known is None else known[None].astype(np.float32),
                        symmetric_averaging=bool(header.get("symmetricAveraging", True)),
                        clip_nonnegative=bool(header.get("clipNonnegative", False)),
                    )
                q = np.asarray(f.quantiles)[0]
                med = np.asarray(f.median)[0]
                write_frame({"id": rid, "generation": gen, "ok": True,
                             "modelSeconds": time.perf_counter() - t1},
                            {"quantiles": q, "median": med})
            else:
                write_frame({"id": rid, "generation": gen, "ok": False, "error": f"unknown op {op!r}"})
        except Exception as e:  # report and keep serving
            log(traceback.format_exc())
            write_frame({"id": rid, "generation": gen, "ok": False, "error": f"{type(e).__name__}: {e}"})


if __name__ == "__main__":
    main()

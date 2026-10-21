#!/usr/bin/env python3
"""Regenerate the reference fixture that `VmoReferenceTest` checks `afield.math.oracle` against.

Builds oracles with Cheng-i Wang's `vmo` package (GPL-3.0) on a few fixed frame sequences
and records each one's suffix links, repeated-suffix lengths and information rate, so the
Kotlin port can be compared to the reference without Python at test time.

    uv run --project . --group vmo python tools/vmo_reference_fixture.py

Writes src/jvmTest/resources/afield/math/oracle/vmo_reference.json.
"""

from __future__ import annotations

import json
import math
from importlib import metadata
from pathlib import Path

import numpy as np
from vmo.VMO import oracle as vmo_oracle

OUT = Path(__file__).resolve().parent.parent / "src/jvmTest/resources/afield/math/oracle/vmo_reference.json"


def cases() -> list[tuple[str, np.ndarray, list[float]]]:
    t = np.arange(240)
    rng = np.random.default_rng(7)
    # two coupled oscillators: position and velocity of a sine, with a phrase that repeats
    osc = np.stack([np.sin(2 * math.pi * t / 40), np.cos(2 * math.pi * t / 40) * 0.5], axis=1)
    # a phrase vocabulary recombined: rise, hesitation, burst, pause
    rise = np.linspace(0, 1, 20)
    hes = np.full(10, 1.0) + 0.02 * np.sin(np.arange(10))
    burst = np.sin(np.linspace(0, 6 * math.pi, 24)) * 0.6 + 1.0
    pause = np.zeros(12)
    order = [rise, hes, burst, pause, rise, burst, pause, rise, hes, hes, burst, pause]
    phr = np.concatenate(order)[:, None]
    # a noisy walk: few repeats
    walk = np.cumsum(rng.normal(0, 0.3, size=(150, 2)), axis=0)
    return [
        ("oscillators", osc, [0.05, 0.1, 0.2, 0.4]),
        ("phrases", phr, [0.02, 0.05, 0.1, 0.3]),
        ("walk", walk, [0.1, 0.3, 0.6]),
    ]


def main() -> None:
    out = {
        "generator": "tools/vmo_reference_fixture.py",
        "vmo": metadata.version("vmo"),
        "numpy": np.__version__,
        "cases": [],
    }
    for name, frames, thresholds in cases():
        for th in thresholds:
            o = vmo_oracle.build_oracle(frames, flag="a", threshold=th, dfunc="euclidean", dim=frames.shape[1])
            ir, _, _ = o.IR(ir_type="cum")
            out["cases"].append({
                "name": name,
                "threshold": th,
                "frames": frames.round(12).tolist(),
                "sfx": [-1 if s is None else int(s) for s in o.sfx],
                "lrs": [int(x) for x in o.lrs],
                "irSum": float(ir.sum()),
            })
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=1) + "\n")
    print(f"wrote {OUT} ({len(out['cases'])} cases, vmo {out['vmo']})")


if __name__ == "__main__":
    main()

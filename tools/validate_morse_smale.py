#!/usr/bin/env python3
"""Check field_morse_smale's persistence diagram against independent implementations.

The native library computes the persistence diagram of the lower-star filtration on the
Freudenthal triangulation of the grid. This script builds the same filtered simplicial complex in
gudhi (always), and in TTK when a TTK Python environment is supplied, then compares the finite
pairs, as multisets of (birth value, death value), in each dimension.

    uv run --no-project --with gudhi --with numpy python tools/validate_morse_smale.py
    uv run ... python tools/validate_morse_smale.py --ttk-python /path/to/ttk-env/bin/python

Neither gudhi nor TTK is a runtime dependency of Field; they are oracles for this check only.
Fields have distinct sample values, except the plateau cases, where only the gudhi comparison of
dimension-0 pair counts is meaningful because tie-breaking differs between implementations.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import math
import os
import platform
import subprocess
import sys
from collections import Counter
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parent.parent


def library_path() -> Path:
    override = os.environ.get("AFIELD_MORSE_SMALE_LIBRARY")
    if override:
        return Path(override)
    name = {"Darwin": "libfield_morse_smale.dylib", "Windows": "field_morse_smale.dll"}.get(platform.system(), "libfield_morse_smale.so")
    for sub in ("build", "build/Release"):
        p = REPO / "build" / "field-morse-smale" / sub / name
        if p.exists():
            return p
    raise SystemExit(f"{name} not found; run ./gradlew buildMorseSmaleNative")


def native_pairs(lib, field: np.ndarray):
    h, w = field.shape
    data = np.ascontiguousarray(field, dtype=np.float32)
    err = ctypes.create_string_buffer(256)
    lib.fms_build.restype = ctypes.c_void_p
    lib.fms_build.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32, ctypes.c_int32, ctypes.c_int32, ctypes.c_char_p, ctypes.c_int32]
    c = lib.fms_build(data.ctypes.data, w, h, 1, w, err, 256)
    if not c:
        raise RuntimeError(err.value.decode())
    for name in ("fms_pair_count", "fms_critical_count"):
        getattr(lib, name).argtypes = [ctypes.c_void_p]
    n = lib.fms_pair_count(c)
    birth = np.zeros(n, np.int32); death = np.zeros(n, np.int32); dim = np.zeros(n, np.int32); pers = np.zeros(n, np.float64)
    lib.fms_pairs.argtypes = [ctypes.c_void_p] + [ctypes.c_void_p] * 4
    lib.fms_pairs(c, birth.ctypes.data, death.ctypes.data, dim.ctypes.data, pers.ctypes.data)
    m = lib.fms_critical_count(c)
    cell = np.zeros(m, np.int32); typ = np.zeros(m, np.int32); val = np.zeros(m, np.float32); at = np.zeros(m, np.float64); xy = np.zeros(2 * m, np.float32)
    lib.fms_critical_points.argtypes = [ctypes.c_void_p] + [ctypes.c_void_p] * 5
    lib.fms_critical_points(c, cell.ctypes.data, typ.ctypes.data, val.ctypes.data, at.ctypes.data, xy.ctypes.data)
    lib.fms_free.argtypes = [ctypes.c_void_p]
    lib.fms_free(c)
    value = dict(zip(cell.tolist(), val.tolist()))
    out = {0: [], 1: []}
    for b, d, k in zip(birth.tolist(), death.tolist(), dim.tolist()):
        if d < 0:
            continue
        out[k].append((value[b], value[d]))
    return out, {"minima": int((typ == 0).sum()), "saddles": int((typ == 1).sum()), "maxima": int((typ == 2).sum())}


def freudenthal_simplices(w: int, h: int):
    idx = lambda x, y: y * w + x
    for y in range(h):
        for x in range(w):
            yield (idx(x, y),)
            if x < w - 1:
                yield (idx(x, y), idx(x + 1, y))
            if y < h - 1:
                yield (idx(x, y), idx(x, y + 1))
            if x < w - 1 and y < h - 1:
                yield (idx(x, y), idx(x + 1, y + 1))
                yield (idx(x, y), idx(x + 1, y), idx(x + 1, y + 1))
                yield (idx(x, y), idx(x, y + 1), idx(x + 1, y + 1))


def gudhi_pairs(field: np.ndarray):
    import gudhi
    h, w = field.shape
    f = field.astype(np.float32).ravel()
    st = gudhi.SimplexTree()
    for s in freudenthal_simplices(w, h):
        st.insert(list(s), filtration=float(max(f[v] for v in s)))
    st.make_filtration_non_decreasing()
    st.compute_persistence(homology_coeff_field=2, persistence_dim_max=True)
    out = {0: [], 1: []}
    for k, (b, d) in st.persistence():
        if k in out and math.isfinite(d):
            out[k].append((b, d))
    return out


TTK_SCRIPT = r"""
import json, sys, numpy as np
from paraview import simple  # noqa: F401  (registers TTK filters when pvpython is used)
"""


def ttk_pairs(python: str, field: np.ndarray):
    """Runs TTK's persistence diagram on the same grid in a separate interpreter (TTK's VTK stack
    lives in its own environment) and returns finite pairs per dimension."""
    script = Path(__file__).with_name("validate_morse_smale_ttk.py")
    tmp = REPO / "build" / "field-morse-smale" / "ttk_field.npy"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    np.save(tmp, field.astype(np.float32))
    out = subprocess.run([python, str(script), str(tmp)], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"TTK run failed:\n{out.stderr[-2000:]}")
    raw = json.loads(out.stdout.strip().splitlines()[-1])
    return {int(k): [tuple(p) for p in v] for k, v in raw.items()}


def nonzero(pairs, eps=0.0):
    return Counter((round(b, 5), round(d, 5)) for b, d in pairs if d - b > eps)


def fields():
    rng = np.random.default_rng(7)
    yield "noise 48x40", rng.random((40, 48))
    yield "noise 64x64", rng.random((64, 64))
    ys, xs = np.mgrid[-1:1:57j, -1:1:61j]
    yield "two hills, one pit", np.exp(-8 * ((xs - .4) ** 2 + ys ** 2)) + .7 * np.exp(-8 * ((xs + .4) ** 2 + (ys - .3) ** 2)) - .5 * np.exp(-12 * (xs ** 2 + (ys + .5) ** 2)) + 1e-4 * rng.random(xs.shape)
    yield "egg crate", np.sin(3 * np.pi * xs) * np.sin(2 * np.pi * ys) + 1e-4 * rng.random(xs.shape)
    yield "monkey saddle + noise", xs ** 3 - 3 * xs * ys ** 2 + 0.05 * rng.random(xs.shape)
    g = np.hypot(*np.gradient(np.sin(4 * xs) * np.cos(3 * ys) + .1 * rng.random(xs.shape)))
    yield "gradient magnitude", g


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ttk-python", help="a python whose environment has topologytoolkit")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    lib = ctypes.CDLL(str(library_path()))
    results, failed = [], False
    for name, field in fields():
        native, counts = native_pairs(lib, field)
        row = {"field": name, "shape": list(field.shape), **counts}
        oracles = {"gudhi": gudhi_pairs(field)}
        if args.ttk_python:
            oracles["ttk"] = ttk_pairs(args.ttk_python, field)
        for oracle, pairs in oracles.items():
            for k in (0, 1):
                a, b = nonzero(native[k]), nonzero(pairs[k])
                same = a == b
                row[f"{oracle}_dim{k}"] = "match" if same else f"MISMATCH native {sum(a.values())} vs {sum(b.values())}, {sum((a - b).values())} extra / {sum((b - a).values())} missing"
                failed |= not same
        results.append(row)
    if args.json:
        print(json.dumps(results, indent=2))
    else:
        for r in results:
            print(r)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

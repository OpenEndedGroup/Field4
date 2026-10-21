#!/usr/bin/env python3
"""Check field_polyvector against the authors' reference implementation, point for point.

Builds the reference command-line program (Bessmeltsev & Solomon's PolyVectorization, the same
pinned commit field_polyvector is built from) with only the patches current compilers and OpenCV 5
need, runs it on its six sample drawings, runs field_polyvector on the same pixels, and compares
the final curves.

    uv run --no-project --with numpy --with pillow --with opencv-python-headless \\
        python tools/validate_polyvector.py

Needs the dependencies field_polyvector needs (OpenCV, Boost, Eigen) and ./gradlew
buildPolyvectorNative first. Each drawing takes 10-70 s on each side. The drawings are converted to
grayscale first so both sides read the same bytes. field_polyvector drops the single-point "curves"
the reference sometimes emits, so those are ignored in the comparison.
"""

from __future__ import annotations

import ctypes
import platform
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
from build_native_geometry import POLYVECTOR_COMMIT  # noqa: E402
from native_download_cache import native_download_cache  # noqa: E402

WORK = REPO / "build" / "field-polyvector" / "reference"

# Compatibility patches only: the algorithm and its constants are untouched.
REFERENCE_PATCHES = [
    ("CMakeLists.txt", "set(CMAKE_CXX_STANDARD 11)", "set(CMAKE_CXX_STANDARD 17)\nadd_compile_options(-Wno-c++11-narrowing -include ${CMAKE_CURRENT_SOURCE_DIR}/src/omp_compat.h)"),
    ("CMakeLists.txt", "set(CMAKE_AUTOMOC ON)\nset(CMAKE_AUTOUIC ON)\n", ""),
    ("CMakeLists.txt", "find_package(Qt5 REQUIRED COMPONENTS Core Widgets Gui)", "find_package(Qt5 QUIET COMPONENTS Core Widgets Gui)"),
    ("CMakeLists.txt", "include_directories ( ${EIGEN3_INCLUDE_DIRS} )", "include_directories ( ${EIGEN3_INCLUDE_DIRS} )\ntarget_link_libraries(polyvector_thing PUBLIC Eigen3::Eigen)"),
    ("src/IsLoopContractible.cpp", '#include "IsLoopContractible.h"',
     '#include "IsLoopContractible.h"\n#include "opencv2/imgproc.hpp"\n#if __has_include("opencv2/geometry/2d.hpp")\n#include "opencv2/geometry/2d.hpp"\n#endif'),
]


def build_reference() -> Path:
    exe = WORK / "build" / "polyvector_thing"
    if exe.exists():
        return exe
    archive = native_download_cache("polyvector") / f"PolyVectorization-{POLYVECTOR_COMMIT[:12]}.tar.gz"
    if not archive.exists():
        raise SystemExit("run ./gradlew buildPolyvectorNative first (it downloads the pinned source)")
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        tar.extractall(WORK, filter="data")
    root = next(WORK.glob("PolyVectorization-*"))
    shutil.copy(REPO / "native" / "field-polyvector" / "src" / "omp_compat.h", root / "src" / "omp_compat.h")
    for name, before, after in REFERENCE_PATCHES:
        path = root / name
        text = path.read_text()
        if text.count(before) != 1:
            raise SystemExit(f"reference patch target not found exactly once in {path}: {before!r}")
        path.write_text(text.replace(before, after))
    subprocess.run(["cmake", "-S", str(root), "-B", str(WORK / "build"), "-DCMAKE_BUILD_TYPE=Release"], check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(WORK / "build"), "--parallel"], check=True, capture_output=True)
    return exe


def library() -> ctypes.CDLL:
    name = {"Darwin": "libfield_polyvector.dylib", "Windows": "field_polyvector.dll"}.get(platform.system(), "libfield_polyvector.so")
    lib = ctypes.CDLL(str(REPO / "build" / "field-polyvector" / "build" / name))
    lib.fpv_field.restype = ctypes.c_void_p
    lib.fpv_field.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32]
    lib.fpv_trace.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int32]
    lib.fpv_curve_count.argtypes = [ctypes.c_void_p, ctypes.c_int32]
    lib.fpv_point_total.argtypes = [ctypes.c_void_p, ctypes.c_int32]
    lib.fpv_point_total.restype = ctypes.c_int64
    lib.fpv_curves.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_void_p, ctypes.c_void_p]
    lib.fpv_free.argtypes = [ctypes.c_void_p]
    return lib


def ours(lib, gray: np.ndarray) -> list[np.ndarray]:
    ev = np.ascontiguousarray(((255 - gray.astype(np.float64)) / 255).astype(np.float32))
    err = ctypes.create_string_buffer(256)
    r = lib.fpv_field(ev.ctypes.data, ev.shape[1], ev.shape[0], None, err, 256)
    if not r:
        raise RuntimeError(err.value.decode())
    lib.fpv_trace(r, None, err, 256)
    n = lib.fpv_curve_count(r, 1)
    lengths = np.zeros(n, np.int32)
    xy = np.zeros(2 * lib.fpv_point_total(r, 1), np.float32)
    lib.fpv_curves(r, 1, lengths.ctypes.data, xy.ctypes.data)
    lib.fpv_free(r)
    out, o = [], 0
    for k in lengths:
        out.append(xy[o:o + 2 * k].reshape(-1, 2))
        o += 2 * k
    return out


def main() -> int:
    import cv2

    exe = build_reference()
    lib = library()
    samples = sorted((next(WORK.glob("PolyVectorization-*")) / "sample_inputs").glob("*.png"))
    out_dir = WORK / "runs"
    out_dir.mkdir(exist_ok=True)
    failed = False
    for png in samples:
        gray = cv2.cvtColor(cv2.imread(str(png), cv2.IMREAD_COLOR), cv2.COLOR_BGR2GRAY)
        g = out_dir / png.name
        cv2.imwrite(str(g), gray)
        subprocess.run([str(exe), str(g)], check=True, capture_output=True)
        svg = Path(str(g) + ".svg").read_text()
        ref = [np.array([[float(a) for a in q.split(",")] for q in m.group(1).split()])
               for m in re.finditer(r'<polyline points="([^"]+)"', svg)]
        ref = [c for c in ref if len(c) >= 2]
        mine = ours(lib, gray)
        same = len(mine) == len(ref) and all(a.shape == b.shape and np.allclose(a, b, atol=2e-3) for a, b in zip(mine, ref))
        failed |= not same
        print(f"{png.stem}: field_polyvector {len(mine)} curves, reference {len(ref)} curves, {'identical' if same else 'DIFFERENT'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

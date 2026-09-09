#!/usr/bin/env python3
"""Demonstrate why `afield.tetgen` never asks TetGen for second-order elements.

TetGen has a `-o2` switch that adds a node at the midpoint of every edge, turning 4-node tetrahedra
into 10-node ones. Field wants exactly that — it is what cures the constant-strain tetrahedron's
shear locking in `structural.bodyFitted` — and instead does the promotion itself in
`afield.tetgen.TetMesh.quadratic`. This script is the reason, run rather than asserted.

    python3 tools/tetgen_o2_use_after_free.py

`highorder()` in TetGen 1.6.0 allocates a `highordertable`, hands each tetrahedron a pointer into
it, and then `delete[]`s the table before returning. `outelements()` dereferences those pointers
afterwards. Driven from the command line the freed block is usually still intact; driven through
`tetgenio` it is not, because the library path allocates its output arrays first and the allocator
hands back the block just freed. TetGen then reads its own vertex coordinates as pointers.

Two programs are built from the same TetGen source, differing in one line:

  * **stock** — TetGen exactly as downloaded. Expected to die on a signal.
  * **patched** — the `delete[] highordertable;` statement commented out, and nothing else. Expected
    to succeed, which is what makes this a diagnosis rather than a guess: if some unrelated fault
    were killing the stock build, removing that one line would not fix it.

Both are compiled at `-O0` so that the failure is TetGen's and not an optimiser's, and this is a
diagnostic rather than part of any build: nothing in Field links it and no gate runs it.
"""

from __future__ import annotations

import platform
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE = REPO_ROOT / "build" / "field-tetgen" / "tetgen-src"

# The statement that frees the table each tetrahedron still points into.
OFFENDING_LINE = "delete [] highordertable;"

# A cube as a PLC, tetrahedralized in library mode with -o2. Deliberately the smallest input that
# reaches the second-order path: the bug is in how the result is written out, not in meshing.
PROGRAM = r"""
#include <cstdio>
#include "tetgen.h"

int main() {
    const double corners[24] = {
        0,0,0,  1,0,0,  1,1,0,  0,1,0,
        0,0,1,  1,0,1,  1,1,1,  0,1,1};
    const int faces[36] = {
        0,1,2, 0,2,3,   4,6,5, 4,7,6,   0,4,5, 0,5,1,
        1,5,6, 1,6,2,   2,6,7, 2,7,3,   3,7,4, 3,4,0};

    tetgenio in, out;
    in.firstnumber = 0;
    in.numberofpoints = 8;
    in.pointlist = new REAL[24];
    for (int i = 0; i < 24; ++i) in.pointlist[i] = corners[i];
    in.numberoffacets = 12;
    in.facetlist = new tetgenio::facet[12];
    in.facetmarkerlist = new int[12];
    for (int t = 0; t < 12; ++t) {
        tetgenio::facet& f = in.facetlist[t];
        f.numberofpolygons = 1;
        f.polygonlist = new tetgenio::polygon[1];
        f.numberofholes = 0;
        f.holelist = NULL;
        f.polygonlist[0].numberofvertices = 3;
        f.polygonlist[0].vertexlist = new int[3];
        for (int k = 0; k < 3; ++k) f.polygonlist[0].vertexlist[k] = faces[3 * t + k];
        in.facetmarkerlist[t] = 0;
    }

    tetgenbehavior behavior;
    char switches[] = "pzQq1.2a0.02o2";
    if (!behavior.parse_commandline(switches)) { std::printf("SWITCHES REJECTED\n"); return 2; }

    std::printf("calling tetrahedralize with -o2 through tetgenio\n");
    std::fflush(stdout);
    tetrahedralize(&behavior, &in, &out);
    std::printf("SURVIVED: %d nodes, %d tetrahedra, %d nodes per tetrahedron\n",
                out.numberofpoints, out.numberoftetrahedra, out.numberofcorners);
    return out.numberofcorners == 10 ? 0 : 3;
}
"""


def compiler() -> str:
    for candidate in ("c++", "clang++", "g++"):
        found = shutil.which(candidate)
        if found:
            return found
    raise SystemExit("no C++ compiler on PATH")


def build(work: Path, name: str, patch: bool) -> Path:
    """Compile the probe against TetGen, optionally with the offending free removed."""
    tetgen = (SOURCE / "tetgen.cxx").read_text()
    if patch:
        if tetgen.count(OFFENDING_LINE) != 1:
            raise SystemExit(
                f"expected exactly one {OFFENDING_LINE!r} in tetgen.cxx, found "
                f"{tetgen.count(OFFENDING_LINE)}; this TetGen is not the one this script describes")
        tetgen = tetgen.replace(OFFENDING_LINE, "/* leaked on purpose: */ // " + OFFENDING_LINE)
    source = work / name
    source.mkdir()
    (source / "tetgen.cxx").write_text(tetgen)
    (source / "main.cpp").write_text(PROGRAM)
    binary = source / "probe"
    # -O0 throughout: predicates.cxx requires it for correctness, and the rest is compiled the same
    # way so that nothing here can be blamed on an optimiser.
    command = [
        compiler(), "-std=c++17", "-O0", "-DTETLIBRARY", f"-I{SOURCE}",
        str(source / "tetgen.cxx"), str(SOURCE / "predicates.cxx"), str(source / "main.cpp"),
        "-o", str(binary),
    ]
    print(f"[{name}] compiling (this takes a moment; tetgen.cxx is large)", flush=True)
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit(f"[{name}] compile failed")
    return binary


def report(name: str, binary: Path) -> int:
    result = subprocess.run([str(binary)], capture_output=True, text=True)
    for line in result.stdout.splitlines():
        print(f"[{name}] {line}")
    if result.returncode < 0:
        caught = signal.Signals(-result.returncode)
        print(f"[{name}] KILLED BY {caught.name} ({caught.value})")
    else:
        print(f"[{name}] exited {result.returncode}")
    return result.returncode


def main() -> int:
    if not (SOURCE / "tetgen.cxx").exists():
        raise SystemExit(
            f"TetGen source is not unpacked at {SOURCE}.\n"
            "Run `python3 tools/build_tetgen_native.py` once to download it.")
    print(f"TetGen source: {SOURCE}")
    print(f"platform: {platform.platform()}")
    with tempfile.TemporaryDirectory(prefix="tetgen-o2-") as directory:
        work = Path(directory)
        stock = report("stock", build(work, "stock", patch=False))
        patched = report("patched", build(work, "patched", patch=True))

    print()
    if stock < 0 and patched == 0:
        print("CONFIRMED: -o2 through tetgenio dies on a signal, and removing the single")
        print(f"           `{OFFENDING_LINE}` in highorder() is enough to fix it.")
        print("           That is a use-after-free, and it is why afield.tetgen.TetMesh.quadratic")
        print("           does the promotion instead of asking TetGen for it.")
        return 0
    print(f"NOT REPRODUCED on this platform: stock={stock}, patched={patched}.")
    print("A use-after-free is allowed to look survivable — whether the freed block has been")
    print("handed out again depends on the allocator. The read is invalid either way, and")
    print("dev_docs/TETGEN.md records the JVM-hosted crash where it was first seen.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

# Native mesh import: edge swaps across a crease

2026-10-02 diagnostic and candidate repair. These files contain only a generated box,
general mesh logic and an upstream-library patch; no project geometry or recipes.

## Reproducer

`regression.cpp` constructs a 2 × 3 × 7 box, then replaces one triangle with a coplanar
three-triangle fan. Some fan vertices lie outside the original triangle, so overlapping
triangles cancel in the oriented boundary. The topology stays closed and the material
should remain the original box. This does not claim support for arbitrary self-intersecting
meshes.

The installed Manifold 3.5.2 and a clean build of its release reproduce the defect: an import
can report `NoError` but increase the box volume from 42 to 49. There is no Field serialization
in this test. Native differences against the original box expose the extra material.

The regression exercises every box triangle, 25 fan placements and three poses (untransformed,
rotated, and rotated/translated), with three native imports per case. On this macOS arm64 run:

| Library | Cases | Failing cases | Largest directed-difference sum |
| --- | ---: | ---: | ---: |
| Installed 3.5.2 | 900 | 16 | 7.000000000000064 |
| Candidate patch, parallel build | 900 | 0 | 6.394884621840902e-13 |

Each generation checks status, both directed native differences against the original handle,
and the known analytic volume. The test exits nonzero on any failure. Equal scalar volumes
alone would not prove conservation.

## Cause and candidate

`SetNormalsAndCoplanar` propagates one face normal across coplanar triangles, including folded
triangles with the opposite orientation. `RecursiveEdgeSwap` treats negative projected winding
like degeneracy. It can then flip an edge into a neighboring noncoplanar face, exchanging a
tetrahedron of real volume. Instrumentation measured a seven-unit volume change in the small
box before the swap.

`manifold-3.5.2-edge-swap.patch` checks the four vertex positions against the neighboring
triangle's plane before the projected edge-swap logic. It uses the existing native tolerance;
it does not enlarge it or suppress an acceptance check. Coplanar cancellation remains allowed.
The candidate passed all **441** upstream C++/C tests with parallel execution and CrossSection
enabled, plus the 900-case regression above. It remains a candidate pending application-level
qualification; the installed library was not replaced.

## Fold cleanup is a separate check

The edge-swap guard preserves material but can leave coplanar folds whose vertices inflate
bounds. `manifold-3.5.2-fold-collapse.patch` applies after the first patch. The edge-collapse
inversion guard continues to reject a newly inverted triangle; it permits collapse when the
triangle was already inverted in that projection. Existing redundant-edge and face checks
still apply. This is a candidate change, not a claim that all folded meshes are supported.

The regression now also runs `AsOriginal().Simplify()`, checking the box's analytic bounds,
outward triangle orientation and native material difference after each generation. Both
patches together pass 900 cases / 2700 boundary checks and all 441 upstream tests. Actual
logs are retained in `evidence/`. Application qualification is independent of these tests.

## Reproduction

Use the [official 3.5.2 release](https://github.com/elalish/manifold/releases/tag/v3.5.2).
The source archive SHA-256 is
`ce5f4d87877daca99a910d0af73c028f2a36b9288dd2bf3cd1cecf8faff9f7c8`.
Apply the edge-swap patch, then the fold-collapse patch, in an isolated source directory
with `patch -p1`, then configure CMake with
`MANIFOLD_TEST=ON`, `MANIFOLD_PAR=ON`, `MANIFOLD_CROSS_SECTION=ON`, `MANIFOLD_CBIND=ON`,
and `BUILD_SHARED_LIBS=ON`. Build and run `ctest --output-on-failure` there.

Compile `regression.cpp` with C++17, that source's `include` directory, and the exact built
Manifold shared library. Set its runtime library search path explicitly; verify the loaded
binary rather than accidentally testing the system installation. Compile a separate executable
against the unpatched library to preserve the failing control.

The local test build discovered a pre-existing Conda GTest installation without a usable runtime
search path. Setting `CMAKE_BUILD_RPATH` to that installation's library directory repaired the
test harness; this was distinct from the geometry defect. No system package was replaced.

Field already supports `AFIELD_MANIFOLD_LIBRARY` (or `-Dafield.manifold.library`) for testing an
isolated C binding. Changing the library requires a fresh JVM because native handles and symbol
bindings survive for the process lifetime. Preserve input, source, library and result identities
when qualifying a candidate runtime.

# nightly/2026-09-09/3-tetgen-public-page

Kind C — documentation / explainer. Base `6658f4d65`.

## Premise check

Confirmed. `grep -rln -i tetgen public/docs/` returns nothing at the base commit. TetGen landed
in `daa117503` with `native/field-tetgen`, `afield.tetgen.Tetgen` (243 lines), `TetgenTest`
(6 tests) and `dev_docs/TETGEN.md` — and no published page.

## What runs on this machine

Verified before writing a word, because the mission says re-derive rather than quote:

- `python3 tools/build_tetgen_native.py` built
  `build/field-tetgen/build/libfield_tetgen_jvm.dylib` (792 KB) from the pinned, checksummed
  TetGen 1.6.0 tarball. Exit 0, no network failure, no vendored source.
- `libmanifoldc.dylib` is installed at `/opt/homebrew/lib` (Homebrew manifold 3.5.2).
- `./gradlew --no-daemon jvmTest --tests 'afield.tetgen.TetgenTest'` → **6 tests, 0 failures,
  0 skipped**, 0.821 s. No `SKIPPED TetGen tests:` line, so every test really exercised the
  native path rather than short-circuiting on a missing library.

So every number and every figure on the page can be generated here.

## Plan

A new published page `public/docs/tetgen.md`, in the voice and fence conventions of
`public/docs/manifold.md` (its nearest neighbour — same FFM shape, same optional-native story,
same `requires=` gating).

Shape:

1. **What it is for.** A surface bounds; a tetrahedral mesh fills. Field could already make and
   boolean closed surfaces; nothing could stress one. `structural.bodyFitted` needs the interior
   because Kratos has no immersed structural element.
2. **The first call**, with a cutaway figure of a real tet mesh.
3. **Quality**, measured: radius-edge ratio (what TetGen's `-q` actually bounds) and radius
   ratio (what conditioning cares about), with a histogram figure.
4. **Sizing**, with a live convergence sweep: edge length against node count, cell count, wall
   time and volume error.
5. **Sharp edges**, re-derived here rather than quoted — the cube's exact volume, and the same
   box put through a level-set march for contrast, since that is the route this replaced and it
   is reproducible on this machine without MMG.
6. **Failure modes** — the open surface, the `preserveSurface` trap, the refusals that happen
   before TetGen is called, with the real message text.
7. **Building it**, the AGPL note, and the two compile flags that are load-bearing.

Supporting code I own and will add:

- `src/jvmMain/kotlin/afield/tetgen/TetMeshViews.kt` — `qualities()`, `radiusEdgeRatios()`,
  `centroids()`, `filtered()`, `boundary()`, `toTriMesh(shrink)`, `toFLines()`. Without these
  every figure on the page is thirty lines of index arithmetic, which is the wrong thing for a
  page to teach. With them the examples are short and the page is about meshing.
- tests for that file in `src/jvmTest/kotlin/afield/tetgen/`.
- a `"tetgen"` entry in `NATIVE_PREREQUISITES` in `tools/build_zensical_docs.py`, so
  `requires="tetgen"` is a known key. This is outside my stated file partition but is a
  two-function addition to docs tooling that nobody else is touching tonight, and the page
  cannot declare its prerequisite without it.
- a nav entry appended to `public/zensical.yml`.

## Status

Plan written. Execution follows.

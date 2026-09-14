# Slicing a polytope: the measurement, and what it found

The method behind `public/docs/chain_ceres_manifold_contours.md` and
`src/jvmMain/kotlin/afield/examples/manifold/PolytopeLayerContours.kt` (`FIELD_TEST` id
`manifold.polytope.contours`, 37 checks). The published page is for a reader; this is for whoever
has to re-run it, extend it, or argue with it.

Every number below comes from one run on 2026-09-14: Manifold 3.5.2 at
`/opt/homebrew/lib/libmanifoldc.dylib`, the Ceres wrapper from `./gradlew buildCeresRedistributable`
(80 s, 8.0 MB) — `buildCeresNative` is the faster 14 s build but it lands in `build/ken-ceres/`,
which is not where `CeresNativeLibrary.defaultPath` looks, so it needs `AFIELD_CERES_LIBRARY` or
`-Dafield.ceres.library` to be found.

## The two confounds, and why they come first

A layer stack is a statement about a *placed* object, so two things have to be settled before any
comparison means anything.

**Scale.** `canonicalize()` carries a gauge: it drives the mean tangency radius to one and puts the
tangency centroid at the origin. Comparing the raw and canonical forms as they arrive measures that
gauge. Every arm here is therefore re-gauged to **enclosed volume exactly 1** and translated so
`z = 0` is its lowest point — the print-farm normalisation, the same amount of material sitting on
the bed. The raw `gC` solid arrives at signed volume 8.000000 and the canonical one at 3.838591;
without the re-gauge, every area below would be about that ratio.

**Orientation.** Canonicalisation keeps the vertex indexing, so the angle each vertex's direction
from the centroid turns through is a true per-vertex number rather than a shape descriptor. Worst
turn: **9.581°** for `gC`, **5.388°** for `gD`. Small enough that one z-ladder cuts both forms
comparably, which is what licenses the layer-by-layer comparison. On `kkC` the same measurement
reads **151.6°** for `canonicalize()`, and there the comparison would have been meaningless — which
is the point of measuring it rather than assuming it.

**Refusing rather than rescuing.** `1 / cbrt(v)` of a negative `v` is a reflection, so a solid with
non-positive signed volume would be silently mirrored into looking fine by the bed gauge. The
harness `require`s a positive volume instead and reports the refusal. That is what catches
`canonicalize()` on `kkC`.

## Finding 1: `Solid.slice` is half-open in z, upward

Population: 6 geometries with closed-form answers, 13 cuts.

`slice(z)` returns the cross-section of the material **at and above** `z`. A face lying in the
cutting plane counts when the solid is above it and does not count when the solid is below it.

| geometry | cut | result |
| --- | --- | --- |
| 2×2×2 cube centred on the origin | `z = -1` (bottom face plane) | area 4.0, 1 contour |
| the same | `z = +1` (top face plane) | empty, though that face also has area 4 |
| the same | `z = 1 - 1e-9` | area 4.0 |
| the same | `z = 1 + 1e-9` | empty |
| a 2×2×2 cube sitting on `z = 0` | `z = 0` / `z = 2` | area 4.0 / empty |
| 4×4×1 slab, 2×2×1 block on top | `z = 0.999999` / `z = 1.0` | 16.0 / **4.0** |
| cone | exactly through the apex vertex | empty |
| tetrahedron | on either horizontal edge | empty |
| two unit cubes touching along one edge | on that edge, `z = 0.5` | area 1.0 at `x = 1.0` |

The step is the crispest statement: at `z = 1.0` the slab, entirely below the plane, has stopped
contributing and only the block answers. The two touching cubes are the *check* rather than another
example — the rule predicts, before running, that exactly one square answers and that it is the
upper cube's, and the measurement agrees on a geometry sharing nothing with the cube the rule came
from.

Consequence for any layer ladder in this repo: **cut at layer midpoints**,
`zMin + h * (i + 0.5) / n`. A boundary ladder on a part whose height is an exact multiple of the
layer height loses its top layer. This is now also in the KDoc on `Solid.slice`.

## Finding 2: `gC`'s raw realization is exactly a cube

Population: `gC` (38 vertices, 60 edges, 24 faces) and `gD` (92 / 150 / 60), 48 layers, both forms
at volume 1.

Gyro adds one vertex per face and two per edge, so `gC` has the pentagonal icositetrahedron's
combinatorics — and every new vertex lands on the cube's own surface. The solid the operator
returns has signed volume 8.000000, and at volume 1 its height is 1.000000, its surface 6.000000,
and all 48 of its layer areas are 1.000000. The gyro is real and, until canonicalisation, entirely
combinatorial. `dev_docs/POLYTOPE_ALGEBRA.md` says this in words ("`gyro` leaves every group of new
faces coplanar with the source face it subdivided"); this is the same fact with a surface area
attached.

| form | height | surface | Σ area·h/n | smallest layer | largest layer |
| --- | --- | --- | --- | --- | --- |
| `gC` raw | 1.000000 | 6.000000 | 1.000000 | 1.000000 | 1.000000 |
| `gC` canonical | 1.394283 | 5.060666 | 0.999889 | 0.002203 | 1.257946 |
| `gD` raw | 1.327925 | 5.311614 | 1.000076 | 0.023470 | 1.209705 |
| `gD` canonical | 1.252050 | 4.926473 | 1.000228 | 0.025617 | 1.226145 |

Worst per-layer area disagreement between raw and canonical: **99.8%** at layer 0 of 48 for `gC`,
**44.4%** at layer 45 of 48 for `gD`. So the commissioning premise — canonicalisation changes the
printable contours — survives, emphatically.

Two things worth carrying forward:

- **The layer topology is untouched.** 48 contours, 48 islands, 0 holes for every one of the four
  forms. Canonicalisation moved every contour and split none of them, so a slicer's hardest
  questions (how many islands, does this layer need support) get the same answers from both.
- **The surface ratio is an isoperimetric reading with no free parameter.** At equal volume a
  sphere has surface 4.835976. Canonical `gD` reaches 4.926473, 1.87% above that bound.
  Canonicalisation is, among other things, a rounding.

## Finding 3: the layer stack detects a self-intersection nothing else does

Population: `kkC` (38 vertices, 108 edges, 72 faces), three embeddings, 192 layers for the
fill-rule test and a 12→768 layer sweep for the Riemann sum.

| embedding | tangency spread | status | genus | signed volume | Σ area·h/n at 768 | layers POSITIVE ≠ EVEN_ODD | overlap |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `kkC` as built | 3.386e-01 | NO_ERROR | 0 | +26.3032 | 1.00000 | 0 / 192 | 0.000000 |
| `canonicalize()` | 4.306e-01 | NO_ERROR | 0 | **−254.0791** | — | — | — |
| `relaxWithCeres()` | 2.682e-08 | NO_ERROR | 0 | +12.4853 | **0.90029** | **130 / 192** | **0.049855** |

The middle row is the easy failure: `canonicalize()` turns a +26.3032 signed volume into −254.0791
while Manifold reports `NO_ERROR` and genus 0 throughout, because a self-intersecting surface is
still a combinatorial manifold and `status` is not asked to notice. The sign alone refuses it.

The bottom row is the finding. Every canonical diagnostic reports success — 12 iterations, about
5 ms, `SPARSE_NORMAL_CHOLESKY`, a residual at `2.682e-08` — and the slicer disagrees, two ways:

- The Riemann sum **converges to the wrong number**: 0.88300, 0.89592, 0.89935, 0.90006, 0.90023,
  0.90028, 0.90029 over 12 to 768 layers. A sum that is short because the layers are too coarse
  keeps climbing; this one plateaus. `kkC` as built is the control and climbs to 1.00000 over the
  same sweep.
- `FillRule.POSITIVE` and `FillRule.EVEN_ODD` disagree on 130 of 192 layers, for an implied
  overlapped volume of 0.049855. On the five other arms measured here they disagree on 0 of 192 and
  the overlap is exactly 0.000000.

And `1 − 2(0.049855) = 0.90029`, the plateau, to five decimal places — from two measurements that
share no code. `volume` integrates the boundary and counts an overlapped region twice; the slicer
resolves each layer into a region and counts it once; the gap is exactly twice the overlap.

**One route that reports nothing.** Intersecting the solid with a box that already contains it is
the identity on a clean body, and it looked like a cheaper detector. It returns 1.000000 unchanged:
Manifold's Boolean takes its input's manifoldness as given rather than resolving the crossings.
Only the fill-rule comparison works.

## Traps hit while building this

- **Nested named functions in a box snippet die in fir2ir.** `fun mid` inside `fun directionTurn`,
  and `fun pixel` / `fun seg` inside `fun plan`, produced
  `IrGenerationExtensionException: IrFunctionImpl ... has no callableId`, with the reported line
  number pointing at the end of the snippet rather than at the declaration. A vararg spread
  (`*list.map { … }.toTypedArray()`) into `showSignals` failed the same way. Hoisting every helper
  to snippet top level and passing the series positionally fixes both. Same family as
  `dev_docs`-recorded K2 REPL snippet-declaration defects: the snippet path missing what the `.kts`
  script path has. The same code compiled and ran without complaint as ordinary `jvmMain` Kotlin,
  which is how it was isolated.
- **`embed=true` on a `diffstatic_runner` fence already emits the image.** An `![...](...)` line
  after the fence is a second copy of the same asset. The alt text belongs in an `alt="..."` fence
  attribute, and `alt` is not part of the capture digest — changing it re-runs the build with cache
  hits and byte-identical PNGs, which is the check that the change is display-only.
- **The reference generator's "Used in" is name-matched, and a false claim is cheap to make.**
  The first `generateCodebaseReference` after this page landed claimed it used `autodiff.kt` and
  pushed `functions_examples.md` off that row, on three collisions: a local `fun dot(...)` pixel
  plotter, `List<Double>.sum()` and `maxOf`. `AFIELD_EVIDENCE_DEBUG=stdlib/autodiff.kt` prints what
  each page matched. Renaming the local and writing `sumOf { it }` dropped the page to one hit,
  under the two-hit threshold.
- **`discover_field_tests.py` reports 248 tests / 0 skipped** in this worktree with the one new test
  added, so 247 at the base commit `2fe5b17b6` — not the 265 that the night's brief carried. Exit
  code 0 either way. Recorded because the discrepancy is in the brief, not in the tool.
- **The docs runner and `run_field_tests.py` both find Ceres only at the default path.** Neither
  forwards `-Dafield.ceres.library` to the Field JVM it starts, so the `requires="ceres"` fence and
  the smoke's third arm both need `build/ken-ceres-redistributable/` populated. The fence's
  `requires=` attribute fails the build in a second rather than 38 minutes in, which is what it is
  for.

## Re-running it

```bash
brew install manifold ceres-solver
./gradlew buildCeresRedistributable
python3 tools/run_field_tests.py --id manifold.polytope.contours --port <a free port>
```

and for the figures, with `uv` resolved rather than typed as a path:

```bash
FIELD_DOCS_RUNNER_PORT=<a free port> FIELD_DOCS_RUNNER_MODE=always \
    uv run --group docs python tools/build_zensical_docs.py --page chain_ceres_manifold_contours
```

All three captures are box captures — a Markdown table, a `PngEncoder` raster and two Plotly plots.
None of them opens a window, so none needs a GPU, a renderer or a video recorder, and the whole
page re-captures in about 85 seconds.

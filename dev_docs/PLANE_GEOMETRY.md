# The plane-geometry family: what each file actually is

Written 2026-07-29, because the generated codebase reference makes this look like a tidy
2D-geometry family and it is not. Four of five things assumed about it turned out to be wrong, and
the assumption came from the filenames plus the reference index, so it will be made again.

The counterpart for meshes is [MESH_GEOMETRY.md](MESH_GEOMETRY.md); the public worked example is
`public/docs/spatial_geometry_examples.md`.

## The map

| file | what it actually is | what it is *not* |
| --- | --- | --- |
| `graph_connectivity.kt` | **this is where 2D Delaunay and 2D Voronoi live.** `vec2DelaunayGraph`, `vec2DelaunayTriangles`, `vec2VoronoiDiagram`, `vec2VoronoiDualGraph`, `vec2ConvexHull`. JTS-backed, exact | — |
| `voronoi.kt` | **3D only.** `voronoi3d` clips half-spaces into `VoronoiCell3` polyhedra (`Vec3` throughout), plus a 3D Apollonius `CircularDirichlet3d` | not 2D anything; **contains no Delaunay at all** |
| `dirichlet.kt` | 2D **multiplicatively weighted Voronoi (Apollonius)** — `CircularDirichlet`. Cells are bounded by *circular arcs*. Pure Kotlin | not Dirichlet-process anything, and **not Poisson-disc sampling**; it does no sampling of any kind |
| `clustering.kt` | agglomerative (Smile, 7 linkages), k-medoids, k-medians | **there is no k-means anywhere in Field**, on purpose. `grep -ri kmeans src/jvmMain` = 0 hits |
| `contours.kt` | 2D marching squares. `generateIsoContours` and friends, plus `pointEdgeScalarField2D` | — |
| `spatial_field.kt` | the `FLine` bridges for all of the above, plus `showPlane` / `showPlaneGrid` | — |

If you are searching for "Voronoi" and land in `voronoi.kt`, you are in the 3D file. If you are
searching for "Delaunay" you will not find it in either `voronoi.kt` or `dirichlet.kt`.

## `contours.kt` — the three invariants a change here must preserve

The marcher was corrected twice on 2026-07-29 and each correction had a control arm. All of this is
asserted in `SpatialGeometryTest`; the numbers come from a sweep of 392 (field, resolution, iso)
combinations over eight fields.

1. **`inside(v)` must be two-valued.** A corner is inside iff `v < iso`; a sample exactly *at* `iso`
   is outside. Not "below / equal / above": with a three-way classification the crossing count
   around a cell can be odd, the extracted curve is not a closed 1-manifold, and contours vanish or
   shatter. `f(x, y) = x` at `iso = 0` on an even-column grid returned **nothing at all**; the unit
   circle over `[-2, 2]` at `nx = 16` returned four disconnected arcs.

   The *correctness* argument, and the test worth keeping, is that `v < iso` is exactly the limit of
   contouring at `iso - δ`: the answer on a degenerate field must equal the answer on an
   infinitesimally perturbed one. Reach for `contouringADegenerateFieldMatchesTheGenericLimit`.

   The over-correction to avoid is relaxing the edge test so "touching" counts. That lights up every
   cell of a field identically equal to `iso` and rings a field that merely grazes it —
   `aFieldThatOnlyTouchesTheIsoLevelStillYieldsNoContour` catches it.

2. **`ISO_CROSSING_EPSILON` displaces a crossing by at most `1e-3` of a *cell edge*.** That is
   analytic, not sampled — it is a `coerceIn` on the interpolation parameter. Quoting the drift as a
   fraction of the *domain* is meaningless without also quoting `nx`. Sweep-attained maximum:
   `9.93e-4` of a cell. On top of that sits one Float rounding, because a marched point is a `Vec2`
   and therefore single precision: nothing in the output is meaningful below ~1e-7 relative.

3. **The saddle decider has a tie, and the tie must not be left to floating point.** The asymptotic
   decider uses the sign of `(s0*s2 - s1*s3) / (s0 + s2 - s1 - s3)`. A zero *numerator* means the
   saddle sits exactly on `iso` — the level set is an X — and both pairings are equally correct.
   That is not rare: it is **every field that is itself bilinear over the cell**. For `f = x*y` the
   numerator is `(ab)(cd) - (cb)(ad)`, zero in exact arithmetic and ±1e-20 in doubles, so the
   pairing flipped with resolution (Q at `nx` = 4, 8, 16; P at 5, 32, 64) and re-routed two segments
   by a whole cell each time. It even came out differently in the gradle-test JVM and the
   docs-capture JVM on the same input. `SADDLE_TIE_RELATIVE_EPSILON` settles it on the pre-decider
   pairing.

   The corollary is a claim not to make: **equal branch lengths on `f = x*y` are not evidence the
   decider works.** They were round-off. `nx = 8` gives 8 and 10 points, which is what the
   pre-decider pairing gave, because on a tie there is nothing to improve on. Where the decider does
   earn its keep is a cell whose saddle value is genuinely off `iso` — see
   `theSaddleDeciderFollowsTheBilinearSaddleAndFlipsWithTheField`, one cell with prescribed corner
   values and its sign-flipped twin.

Still rough: the stitcher joins crossings by proximity (tolerance `gridScale * 1e-6`) and is O(m²).
`marchingCubes` in `mesh_sdf.kt` keys shared vertices on grid-edge identity instead, which would
make the 2D marcher exact *and* linear.

## Blast radius of a change to the marcher

Consumers of `generateIsoContours` / `generateSmoothIsoContours` / `Img.isoContourFLines`, complete
as of 2026-07-29 (`git grep -l` over the whole tree, excluding the generated reference):

| consumer | how to check it |
| --- | --- |
| `src/jvmMain/kotlin/afield/examples/stdlib/WfcGallery.kt` | `python3 tools/run_field_tests.py --id stdlib.wfc.gallery` |
| `public/docs/wfc_examples.md` §7 — a real `capture="box"` fence that runs `Img.isoContourFLines` | regenerate `assets/wfc_examples/scalar-field.png` with `FIELD_DOCS_RUNNER_MODE=always`, then include the page in the two-run determinism audit |
| `public/docs/spatial_geometry_examples.md` — eight capture fences | `--page spatial_geometry_examples.md --strict`, `MODE=always` |
| `dev_docs/agents/transcripts/spatial_geometry_examples.yaml`, `wfc_worked_example.yaml` | `run_field_tests.py --id examples.demo-transcript.exec` |
| `public/docs/imaging_examples.md` | prose only — mentions `isoContourFLines`, does not execute it |
| `ImagingFeaturesTest`, `SpatialGeometryTest` | `./gradlew jvmTest` |

`clustering.kt` has **zero callers** anywhere in the repo outside its own tests and this page, so its
API shape can still be changed cheaply.

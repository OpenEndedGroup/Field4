# Nightly worklog — mesh-geometry-family

**Branch:** `nightly/2026-07-28/1-mesh-geometry-family` (based on `1a347dc8`)
**Kind:** A — the night's big swing
**Mission:** a dependency-free CPU triangle-mesh / 3D-geometry family for `afield.stdlib`.

## Plan

### Why (confirmed against the tree)

`src/jvmMain/kotlin/afield/stdlib/` has strong 2D + imaging layers — `contours.kt` (marching
squares over a `ScalarField2D`), `voronoi.kt` (real 3D Voronoi by half-space clipping, yielding
`VoronoiCell3` / `VoronoiFace` polygons), `imaging*.kt` (~7k lines of CPU imaging/CV) — and
**nothing** for triangle meshes. `grep` finds no marching cubes, half-edge, subdivision,
decimation, mesh smoothing, convex hull, mesh measurement or OBJ/STL I/O anywhere under
`afield/stdlib`. The only mesh types are renderer-side buffers (`afield/four/modules/Mesh.kt`,
`MeshMesh.kt`, `simpleGLTFSupport.kt`, `simplePLYSupport.kt`) — not a geometry algebra you can
compute with inside a box. Same shape of gap the imaging family filled, in the other target domain.

### Surfaces confirmed before writing anything

- `Vec3` = `afield.stdlib.linalg.Vector3f`, **mutable**, `@JvmField x/y/z`, in-place `add/sub/mul/
  cross/normalize/lerp/...` plus `(dest)` overloads. Mutability makes it a bad *storage* type for a
  mesh (aliasing bugs), so `TriMesh` stores flat `FloatArray`s and hands out fresh `Vec3`s.
- `FLine`: build via `polylineFrom(points: List<Vec3>, closed: Boolean)` in `FLineGeometry.kt`.
- `Img` (`imaging.kt`): `width/height/channels/data`, `Img.generate(w,h){x,y->Float}`, `at(x,y)`,
  `index(x,y,c)`, `toPngBytes()`. Good heightfield source.
- `voronoi.kt`: `voronoi3d(List<Vec3>) -> VoronoiLattice{cells}`, `VoronoiCell3{site, faces}`,
  `VoronoiFace{polygon: List<Vec3> wound CCW along plane.normal}`. Direct bridge to `TriMesh`.
- Rasterizer: `afield.graphics.VisionGeometryPreview` — perspective, z-buffer, perspective-correct
  attributes, near-plane clip, depth-tested wireframe, two-light lambert, AWT-free `renderPixels` /
  `renderPng` and an AWT `render`/`renderContactSheet` for headless galleries. It consumes
  `CapturedMesh(packed: FloatArray /*16 floats/vertex*/, indices)`. So `TriMesh.toCapturedMesh()`
  is a ~20-line bridge and the whole renderer comes for free. Its default caption is about Vision
  geometry, so the gallery passes its own.
- Gallery pattern: `ImagingGallery` / `FunctionsPlotGallery` — `/* FIELD_TEST id: … runner:
  jvm-main entrypoint: … */` block comment, `@JvmStatic fun main`, out dir from `args[0]` or an
  env var else `java.io.tmpdir`, `check(...)` invariants per panel, prints a sentinel line.
- Transcript demo: `dev_docs/agents/transcripts/*.yaml`, classified into `EXECUTE` /
  `COMPILE_ONLY` / `KOTLINJS` in `afield/examples/DemoTranscriptExecSmokeTest.kt`, and
  materialized into `public/docs/assets/agent_transcripts/<name>/` by
  `AgentTranscriptMaterializer` whose **drift guard fails the build** if the committed bundle is
  stale. So a new transcript needs: the yaml, an entry in `EXECUTE`, and a regenerated committed
  document dir.

### Files to add (mirroring the imaging family's several-focused-files layout)

| file | content |
|---|---|
| `mesh.kt` | the `TriMesh` value type (flat arrays), `TriMeshBuilder` + `triMesh {}`, accessors, transforms, concat, flip, per-vertex attribute helpers |
| `mesh_topology.kt` | `MeshTopology`: edge map, vertex→triangle, edge→triangle, boundary loops, components, `isManifold`, `isWatertight`, orientation, `eulerCharacteristic`, `genus`; `weld`, `fixWinding`, cleanup |
| `mesh_generate.kt` | box, plane grid, UV sphere, icosphere, torus, cylinder, cone, capsule, platonics; `extrude`, `revolve`, `tube` (polyline / `FLine`), `heightfield` (lambda or `Img`) |
| `mesh_sdf.kt` | `ScalarField3D = (Vec3) -> Float`, SDF primitives + combinators (union/intersect/subtract/smooth/shell/transform), and **marching cubes** with shared edge vertices |
| `mesh_process.kt` | Loop subdivision, Laplacian + Taubin smoothing (uniform & cotangent), area/angle-weighted normals, QEM edge-collapse decimation, triangle quality |
| `mesh_measure.kt` | area, signed volume, centroid, AABB, mean/Gaussian curvature, `MeshBvh` (ray, closest point, inside test) |
| `mesh_io.kt` | OBJ read/write, STL ASCII + binary read/write |
| `mesh_field.kt` | `toFLines` (wireframe / boundary / normals), `VoronoiCell3`/`VoronoiLattice` → `TriMesh`, `TriMesh` → `CapturedMesh`, `Box.showMesh` / `showMeshGrid` |

### Verification plan

1. `./gradlew compileKotlinJvm compileTestKotlinJvm` from the worktree.
2. Invariant tests in `src/jvmTest/kotlin/afield/stdlib/`: χ = 2 for closed genus-0 solids, χ = 0
   for a torus (genus 1); `volume(marchingCubes(sphere))` → 4/3π *and monotonically converging*;
   subdivision preserving volume within a bound; QEM hitting a target count with bounded error;
   marching-cubes watertightness; ray hits vs analytic answers; OBJ/STL round-trip.
3. `mesh.gallery` FIELD_TEST → committed PNGs in `dev_docs/assets/mesh_gallery/`, eyeballed.
4. A transcript demo replayed for real through `examples.demo-transcript.exec`.
5. `dev_docs/MESH_GEOMETRY.md` guide.

---

## What was done

Four commits on `nightly/2026-07-28/1-mesh-geometry-family`, 41 files, +10204 / −328.

### 1. `46c71c43` — the family (8 files, 5284 lines of Kotlin) + 101 tests

`src/jvmMain/kotlin/afield/stdlib/`:

| file | content |
|---|---|
| `mesh.kt` | `TriMesh` (flat `FloatArray` storage, fresh-`Vec3` accessors so a returned vector cannot mutate the mesh), `TriMeshBuilder` / `triMesh {}`, transforms, `plus`, `filterTriangles`, `ToHTML` thumbnail |
| `mesh_topology.kt` | `MeshTopology` — edge/vertex adjacency, boundary loops, components, manifoldness (edges *and* vertex stars), Euler characteristic, genus; `weld`, `fixWinding`, `removeDegenerateTriangles`, `repaired`, `largestComponent` |
| `mesh_generate.kt` | box, plane, UV sphere, icosphere, torus, cylinder, cone, capsule, all five platonics (dodecahedron as the icosahedron's dual, via the topology layer); ear-clipping `triangulatePolygon`, `polygonMesh`, `extrudeMesh`, `revolveMesh`, rotation-minimising `tubeMesh`, `heightfieldMesh`, `Img.toHeightfieldMesh` |
| `mesh_sdf.kt` | `ScalarField3D`, 10 SDF primitives + 11 combinators, `marchingCubes` (shared edge vertices, asymptotic decider, gradient-derived winding), `Img.asHeightSdf` |
| `mesh_process.kt` | `withVertexNormals` / `flatShaded` / `shadedNormals`, `loopSubdivide`, `laplacianSmooth`, `taubinSmooth` (uniform + cotangent), `decimate` (QEM), `quality` |
| `mesh_measure.kt` | area, signed volume, surface + solid centroids, mixed Voronoi areas, Gaussian + mean curvature, `MeshBvh` (rays, closest point, inside test, signed distance, `asSdf`), `convexHullMesh`, `deviationFrom` / `maxDeviation`, `samplePoints` |
| `mesh_io.kt` | OBJ + STL (ASCII and binary) read/write, `meshFromFile` |
| `mesh_field.kt` | `toFLines`, `tubeMesh(FLine)`, `VoronoiCell3`/`VoronoiLattice` → `TriMesh`, `coloredByScalar`, `MeshView` / `renderPng` / `toFigureHtml`, `Box.showMesh` / `showMeshGrid` / `showMeshTurntable`, `summary` |

Tests: `Mesh{Core,Marching,Process,Query,Io}Test.kt`, 101 cases.

### 2. `06a055ec` — `mesh.gallery`, 10 committed PNGs

`src/jvmMain/kotlin/afield/examples/mesh/MeshGallery.kt`, FIELD_TEST `mesh.gallery`.
Captures committed in `dev_docs/assets/mesh_gallery/`.

### 3. `21df4510` — the loadable demo + `dev_docs/MESH_GEOMETRY.md`

`dev_docs/agents/transcripts/mesh_geometry_examples.yaml`, added to
`DemoTranscriptExecSmokeTest.EXECUTE`, materialized into
`public/docs/assets/agent_transcripts/mesh_geometry_examples/`.

### 4. `6b45c056` — regenerated `public/docs/reference/` (the `check` drift guard) + closed KDoc gaps

## Three real bugs the tests found (and one the gallery did, and one more)

1. **Marching cubes leaked.** A grid corner whose sample is *exactly* at the iso level is not exotic
   — a unit sphere on a 0.2 grid has corners at `(1,0,0)` and `(0.6,0.8,0)` precisely on it. Every
   crossing out of such a corner interpolated to the corner itself; the triangles between the
   coincident vertices were degenerate, got dropped, and the surface tore: **24 boundary loops,
   χ = −22** in an otherwise perfect res-16 sphere. Fixed by nudging the crossing parameter 1e-3 of a
   cell off the endpoints — three orders of magnitude below the marcher's own O(h²) accuracy.
2. **`tubeMesh` was inside-out**, and its end cap disagreed with its own sides
   (`tangent × radial` is the *inward* normal).
3. **QEM decimation drained its own queue.** It bumped the version stamp of every *neighbour* of a
   collapse, retiring queue entries between two untouched vertices that were still valid and were
   never re-pushed. The decimation was then forced into whatever was left: a 200-triangle "sphere"
   with a **1.96-long edge and 27% radial deviation**. Only `u` and `v` change, so only their stamps
   may move. With that plus a shape veto that defers sliver-producing collapses, 5120 → 200 now keeps
   a 12.6° minimum angle and 3.0% deviation.
4. **`voronoi.kt`'s per-face polygons contain T-junctions.** Where four bisector planes nearly meet,
   the clipper lists that corner on one face and drops it as degenerate on the neighbour: one edge on
   one side of the seam, two on the other. No weld tolerance fixes it (identical 22-of-36 interior
   cells closed at every tolerance from 1e-6 to 3e-3 of the cell diagonal) because the problem is a
   *missing* vertex. Healed in the bridge via the convex hull, which for a half-space intersection is
   exact rather than approximate. **Not fixed in `voronoi.kt` itself** — see risks.
5. **`convexHullMesh` could leave a hole.** It took *every* face passing the visibility test as the
   visible set; with coplanar-heavy input float noise lets a far-side face pass, the visible region is
   two disjoint patches, the horizon is two loops, and coning them to one point leaves a hole. Now
   flood-fills a *connected* region from the most-visible face.

## Verification — what was actually observed

**Compile.** `./gradlew compileKotlinJvm compileTestKotlinJvm checkCodebaseReference` — BUILD
SUCCESSFUL, no new warnings from the new files.

**Tests.** `./gradlew jvmTest` — **602 tests, 0 failures, 0 skipped, 55 classes, 59s**. The 101 new
mesh cases are invariant-based; real numbers printed by the run:

```
marchingCubes sphere convergence:
  res=16  tris=872    volume=4.088352  relErr=0.02398
  res=32  tris=4328   volume=4.163585  relErr=0.00602
  res=64  tris=18408  volume=4.182542  relErr=0.00149     (4/3 pi = 4.1887902)
  watertight and chi=2 at every resolution; error asserted strictly decreasing

decimate (icosphere(4), 5120 tris):
  target=2000 got=2000 deviation=0.00558
  target=800  got=800  deviation=0.01542
  target=200  got=200  deviation=0.03027    all watertight, chi=2

smoothing (noisy icosphere): noisy V=4.16566
  laplacian(30) -> 2.25311  (deflates, as a diffusion must)
  taubin(30)    -> 4.27371  (holds, within 3%)

loopSubdivide (icosahedron): 1.66368 / 1.50096 / 1.46290 / 1.45353
  deltas -0.872 / -0.163 / -0.038 / -0.0094  — monotone, each under 0.6x the last

quality: icosphere minAngle 54.10 deg, aspect mean 1.014
         uvSphere(64,8) minAngle 5.47 deg  (slivers at the poles, as expected)

gyroid: 122286 tris, chi=-6, genus 4, manifold
```

**Gallery pixels.** `python3 tools/run_field_tests.py --id mesh.gallery --port 9700 --no-reap` —
**PASS**, 10 panels. Regenerated into `dev_docs/assets/mesh_gallery/` with `MESH_GALLERY_DIR=…` and
**every one of the ten was opened and looked at**. What I see:

- `01-primitives` — a cube with three distinctly shaded faces and sharp edges (V=1.0000 exactly); a
  smooth UV sphere with a lat/long wireframe; a geodesic icosphere; a torus with a visible hole
  (χ=0 g=1); a smooth-sided cylinder; a cone with a real apex; a capsule with rounded ends; tetra,
  octa, icosa and dodeca all recognisable; an L-shaped prism. Nothing black, nothing garbled,
  every silhouette correct.
- `02-marching-cubes` — three visibly smoother spheres, error captions 4.735% / 1.217% / 0.303%; a
  torus; a drilled solid seen from 58° above so the hole through it is actually visible; a
  three-blob metaball merge.
- `03-sdf-modelling` — the sharp union shows the box stub poking out of the sphere, the smooth union
  shows a clear fillet, and the shell is **cut open by a half-space so the wall thickness reads**.
  The twist and the gyroid are both correct and clean.
- `04-subdivision` — icosahedron → sphere over three rounds; a coarse torus → a smooth one, still
  genus 1.
- `05-smoothing` — the noisy ball is visibly lumpy; the last cell puts the Laplacian and Taubin
  results in **one frame** and the Laplacian ball is unmistakably smaller.
- `06-decimation` — progressively coarser but still round spheres; the CSG solid and its decimation
  both show the drill hole.
- `07-derived-generators` — L-prism, goblet lathe, a **helical tube with no twisting** (the frames
  are clean), a dune heightfield, and an `Img` heightfield where the test card's disc and bar appear
  as raised plateaus.
- `08-voronoi-bridge` — one interior cell as a proper convex polyhedron (χ=2), the 72-cell packed
  block, and the interior cells exploded into separate crystals.
- `09-measure-and-query` — the Gaussian-curvature torus is textbook: red outside, blue in the hole.
  Mean curvature is a smooth gradient. The hull is a clean ellipsoid.
- `10-shading-and-io` — the flat/creased/smooth trio differ as they should (facets → smooth side with
  a crisp rim → rounded rim), and the three spheres either side of the OBJ and STL round trips are
  indistinguishable.

**Demo replay — through the real engine, not by inspection.**
`AFIELD_DEMO_TRANSCRIPT_VERBOSE=1 DEMO_TRANSCRIPT_ONLY=mesh_geometry_examples python3
tools/run_field_tests.py --id examples.demo-transcript.exec --port 9701 --no-reap` — **PASS**,
`[execute] PASS mesh_geometry_examples (applied=14, output lines=62)`, **zero error-kind output
lines**. All 62 lines were read back. Highlights:

```
cube              8 verts    12 tris  chi= 2 genus=0 watertight=true  volume=1.00000
torus          1152 verts  2304 tris  chi= 0 genus=1 watertight=true  volume=0.56508
unit cube      volume 1.0  area 6.0
marched sphere 12/24/48/64 -> relErr 0.03259 / 0.00815 / 0.00204 / 0.00114, all chi=2
drilled sphere: chi=0, genus 1, watertight        gyroid: chi=-20, genus 4, open(20)
Loop steps -0.44671 / -0.08331 / -0.01949, chi=2 throughout
laplacian volume 1.15085   taubin volume 2.18401   (from noisy 2.12931)
decimation 2000/600/150 exact, deviation 0.00468 / 0.01661 / 0.03256, watertight
Gaussian curvature mean 2.04325 against 1/r^2 = 2.04082
mean curvature     mean 1.42857 against 1/r   = 1.42857
Gauss-Bonnet: icosphere 12.566400 / torus 0.000024 / cube 12.566370  vs 2 pi chi = 12.566371 / 0
ray hits at 4.300000011920928 (analytic 4.3); closest point (0.7,0,0) at 2.3
signedDistance (2,0,0) = 1.30000 (analytic 1.30000)
48 of 48 Voronoi cells closed; volumes sum 81.839634 vs clip box 81.839487 (1.79e-06 relative)
OBJ round trip 642 verts watertight; STL 3840 (soup) -> welded 642 watertight
```

**Materializer drift guard.** `python3 tools/run_field_tests.py --id
examples.agent-transcripts.materialize --port 9701 --no-reap` — **PASS** (the new starting point is
committed and byte-identical to what the transcript produces; no churn on the existing 22).

**Not verified.** No public `public/docs/` page and no docs build was run (the dev guide, gallery and
demo came first, per the mission's priority). No performance measurement of any kind — the machine had
two other nightly agents on it, so timing would have been meaningless. The `.h` inline visualizer path
exists (`TriMesh` implements `ToHTML` and `MeshIoTest` asserts the HTML embeds a real PNG and reports
the real counts) but was never looked at in a live editor.

## Risks and unknowns

- **The Voronoi T-junction is worked around, not fixed.** `voronoi.kt`'s `buildFaces` really does emit
  inconsistent face polygons for ~40% of interior cells in a random lattice. The bridge heals it
  exactly (a Voronoi cell is convex), but anything else consuming `VoronoiFace.polygon` directly still
  sees the T-junctions. Worth a follow-up in `voronoi.kt` itself; I did not touch it, because that
  file has its own callers and its own tolerance story and 2am is the wrong time.
- **`convexHullMesh` is now load-bearing for the Voronoi bridge.** Its flood-fill fix is tested
  (hull of 300 sphere points, 500 ball points, an icosphere's own vertices, degenerate input) and the
  72-of-72 Voronoi result exercises it hard, but incremental hulls are notoriously input-sensitive
  and a pathological cloud could still find a hole. `isWatertight()` on the result is cheap insurance
  for a caller that cares.
- **Marching-cubes orientation from one gradient per cube** will get the second sheet of a
  two-sheet cell backwards. Documented in the file. Only bites at resolutions far below the field's
  own feature size.
- **`mesh_sdf.kt` reports 35 of 36 symbols documented** and I could not identify the 36th from the
  generator's output. It is one symbol out of 4252 and the drift guard passes; noted rather than
  chased.
- **No parallelism anywhere.** `marchingCubes` grid sampling and `MeshBvh` construction are the two
  obvious candidates if a real workload ever needs them.
- **Nothing GPU.** `toCapturedMesh` bridges to the *CPU* rasterizer. A `TriMesh` →
  `afield.four.modules.Mesh` upload bridge is the obvious next step and is not here.

## Recommendation: **merge**

It fills a gap the tree genuinely had, in one of Field's two stated target domains, with no new
dependencies. The whole 602-test suite is green, the ten gallery panels have been looked at rather
than assumed, the demo was replayed through the real engine with its 62 printed numbers read back
against analytic answers, and both CI drift guards (codebase reference, transcript materializer) pass.
It also leaves the tree strictly better in two places it did not have to touch: `convexHullMesh` is
robust where it was not, and the `voronoi.kt` T-junction is now a *documented, measured* defect with a
reproduction instead of an unknown.

The one thing I would want a human to glance at before it lands: `dev_docs/assets/mesh_gallery/`, ten
PNGs, thirty seconds. Everything else is machine-checked.

Suggested follow-ups, in order of value:

1. Fix `voronoi.kt`'s face construction so the T-junctions never appear, then simplify
   `VoronoiCell3.toTriMesh`'s healing path to an assertion.
2. A `public/docs/mesh_examples.md` worked-example page, modelled on `imaging_examples.md`, using the
   gallery captures.
3. A `TriMesh` → `afield.four.modules.Mesh` GPU upload bridge; the packing is already written for the
   CPU rasterizer.
4. Isotropic remeshing and geodesic distance were on the stretch list and did not land.

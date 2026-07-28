# The mesh / 3D geometry family

A dependency-free CPU triangle-mesh layer in `afield.stdlib`, landed 2026-07-28. Eight source files,
101 invariant tests, a ten-panel rendered gallery, and a loadable demo transcript.

## Why it exists

`afield.stdlib` had a strong 2D and imaging story — `contours.kt` (marching squares over a
`ScalarField2D`), `voronoi.kt` (real 3D Voronoi by half-space clipping), `imaging*.kt` (~7k lines of
CPU imaging / CV) — and **nothing at all** for triangle meshes. No marching cubes, no adjacency
structure, no subdivision, no decimation, no smoothing, no convex hull, no mesh measurement, no
OBJ/STL. The only mesh types in the tree were renderer-side buffers (`afield/four/modules/Mesh.kt`,
`MeshMesh.kt`, `simpleGLTFSupport.kt`, `simplePLYSupport.kt`) — GPU upload targets, not a geometry
algebra you can compute with inside a box. 3D geometry is one of Field's target domains; this is the
same shaped gap the imaging family filled, in the other one.

## The files

| file | what it holds |
|---|---|
| `mesh.kt` | `TriMesh` — flat `FloatArray` attribute storage plus an `IntArray` of triangle indices. `TriMeshBuilder` / `triMesh {}`, whole-mesh transforms, concatenation, `filterTriangles`, the `ToHTML` inline thumbnail. |
| `mesh_topology.kt` | `MeshTopology` — edge/vertex adjacency, boundary loops, connected components, manifoldness (edges *and* vertex stars), Euler characteristic, genus. Repairs: `weld`, `fixWinding`, `removeDegenerateTriangles`, `repaired`, `largestComponent`. |
| `mesh_generate.kt` | box, plane grid, UV sphere, icosphere, torus, cylinder, cone, capsule, all five platonics; `triangulatePolygon` (ear clipping), `polygonMesh`, `extrudeMesh`, `revolveMesh`, `tubeMesh`, `heightfieldMesh`, `Img.toHeightfieldMesh`. |
| `mesh_sdf.kt` | `ScalarField3D = (Vec3) -> Float`, the SDF primitives and combinators, and `marchingCubes`. |
| `mesh_process.kt` | `withVertexNormals` / `flatShaded` / `shadedNormals`, `loopSubdivide`, `laplacianSmooth`, `taubinSmooth`, `decimate` (QEM), `quality`. |
| `mesh_measure.kt` | area, signed volume, surface and solid centroids, mixed Voronoi areas, Gaussian and mean curvature, `MeshBvh` (rays, closest point, inside test, signed distance), `convexHullMesh`, `deviationFrom` / `maxDeviation`, `samplePoints`. |
| `mesh_io.kt` | OBJ and STL (ASCII + binary), read and write, plus `meshFromFile`. |
| `mesh_field.kt` | the Field bridges — `toFLines`, `tubeMesh(FLine)`, `VoronoiCell3`/`VoronoiLattice` → `TriMesh`, `coloredByScalar`, `MeshView` / `renderPng` / `toFigureHtml`, `Box.showMesh` / `showMeshGrid` / `showMeshTurntable`, `summary`. |

Tests: `src/jvmTest/kotlin/afield/stdlib/Mesh{Core,Marching,Process,Query,Io}Test.kt`.
Gallery: `src/jvmMain/kotlin/afield/examples/mesh/MeshGallery.kt` (FIELD_TEST `mesh.gallery`).
Demo: `dev_docs/agents/transcripts/mesh_geometry_examples.yaml`.

## Two conventions that everything depends on

**Winding is counter-clockwise seen from outside.** So `faceNormal` points out of a closed solid and
`signedVolume` of one is positive. That makes `signedVolume` the cheapest orientation check there is,
and it is what `fixWinding` uses to decide whether a consistently-oriented component is inside-out.

**Generators are welded and minimal; shading is a separate step.** A `boxMesh()` is 8 vertices, not
24, so its Euler characteristic is exactly 2 and `isWatertight()` is true. It therefore carries **no
normals** — the eight corners are each shared by three faces that want different ones, and you cannot
have both a shared vertex and two normals. You pick:

```kotlin
boxMesh().flatShaded()          // one normal per triangle: every edge hard, 3 verts per tri
boxMesh().shadedNormals(35.0)   // split only where faces disagree by more than 35 degrees
boxMesh().withVertexNormals()   // one averaged normal per shared vertex: rounded corners
```

`shadedNormals` is the one to reach for: a cylinder comes out smooth along its side and crisp at its
rim from one call, and only the vertices that actually straddle a crease are duplicated. The render
bridges call it for you when a mesh arrives without normals, so a bare `boxMesh()` still looks like a
box. Generators that *are* smooth everywhere — sphere, icosphere, torus, capsule, tube — ship with
normals already.

## The Euler characteristic is the test

`V - E + F = 2 - 2g` for a closed orientable surface. That single integer catches a duplicated seam, a
missing cap, an unwelded weld, an off-by-one in a ring index and a marching-cubes leak — all of which
still look completely plausible in a render. Every closed primitive asserts `χ = 2`, the torus asserts
`0` and genus `1`, the plane grid asserts `1`, and the gyroid asserts `χ < 0`.

Alongside it, the analytic volumes: a unit cube is exactly `1.0` and area exactly `6.0`; a sphere of
radius `r` is `4/3 π r³` and `4 π r²`, approached from below (an inscribed polyhedron is always
smaller); `Σ K_v A_v = 2π χ` (Gauss-Bonnet) holds to float precision.

## How the pieces compose

The intended arc, and the one the demo transcript walks:

```kotlin
// 1. write a shape as an expression
val body = sdfSmoothUnion(sdfSphere(0.8), sdfBox(vec(2.2, 0.34, 0.34)), k = 0.3)
val drilled = sdfSmoothSubtract(body, sdfCylinder(0.28, 4.0), k = 0.1)

// 2. march it into a watertight mesh
val mesh = marchingCubes(drilled, boundsAround(radius = 1.35), resolution = 96)
println(mesh.summary())        // chi=0, genus 1, watertight, volume 1.88

// 3. regularise and budget it
val ready = mesh.taubinSmooth(iterations = 8).decimate(4000)

// 4. measure, query, or export it
ready.signedVolume(); ready.bvh().contains(vec(0, 0, 0)); ready.saveStl(File("/tmp/part.stl"))

// 5. look at it, in a box, with no renderer set up
showMesh(ready)                        // rasterised, shaded, depth-buffered, into the box output
lines(ready.toFLines(MeshLines.CREASES))   // or as drawable FLines
```

The two ends are where the family earns its place in Field specifically: `Img.toHeightfieldMesh` and
`Img.asHeightSdf` bring the imaging family in; `toFLines`, `tubeMesh(FLine)` and `Box.showMesh` take
the result out into things Field already draws.

## Marching cubes: what it actually does

Not the 1987 lookup table.

- The field is sampled once per grid corner, so the cost is `(n+1)³` evaluations however many
  triangles come out.
- Every crossing sits on a grid **edge**, and edges are keyed globally, so a vertex is created once
  and shared by all the cubes that touch it. That is what makes the output watertight rather than a
  soup needing `weld`.
- Instead of a 256-entry triangle table, each cube's crossings are joined into closed loops on the
  cube's own boundary and each loop is triangulated. On a face where four edges cross — the ambiguous
  case the classic table resolves arbitrarily — the pairing is chosen by the **asymptotic decider**
  (the sign of the bilinear saddle value `(ac - bd) / (a + c - b - d)`). Because that is a pure
  function of the face's four corner values, and is invariant under the dihedral relabelling the two
  cubes see, they always agree. That is exactly the property plain marching cubes lacks, and the
  reason it leaks.
- Winding comes from the field: each triangle is oriented against the gradient of the trilinear
  interpolant, so normals point toward increasing field value — outward for the SDF convention.
  `positiveOutside = false` for a density field.

Stated limits: a cube containing two separate sheets can be oriented from one gradient and get the
second sheet backwards; and the loop triangulation reproduces the sign pattern faithfully but is not
guaranteed to reproduce the trilinear interpolant's exact topology in the rarest configurations.

### The leak that was found and fixed

At `resolution = 16` a marched unit sphere came out with **24 boundary loops** and `χ = -22`. The
cause: a grid corner whose sample is *exactly* at the iso level is not exotic. A unit sphere marched
on a 0.2 grid has corners at `(1, 0, 0)` and `(0.6, 0.8, 0)` sitting precisely on it. Every crossing
on every edge out of such a corner interpolates to the corner itself, several vertices land on the
same point, the triangles between them are degenerate, they get dropped — and the surface tears.
Nudging the interpolation parameter `1e-3` of a cell off the endpoints removes the whole failure mode,
at a geometric cost three orders of magnitude below the marcher's own `O(h²)` accuracy.

## Decimation: the three guards, and the queue bug

`decimate(targetTriangles)` is Garland & Heckbert QEM edge collapse with:

1. **The link condition** — refuse unless `u` and `v`'s common neighbours are exactly the corners
   opposite their shared triangles. Without it, collapsing a shortcut edge silently creates a
   non-manifold vertex and everything downstream misbehaves.
2. **A normal-flip veto** — refuse if any surviving triangle's normal would rotate more than 90°.
3. **A shape veto** — defer (do not drop) a collapse that would produce a triangle whose normalised
   shape measure falls below 0.25, re-queueing it at an inflated cost so it is still available if
   nothing better ever is. On a sphere every edge carries almost the same quadric error, so without
   this the queue order is effectively arbitrary and the collapses cascade into long thin strips.

Plus boundary constraint planes at 1000× the face weight, so an open mesh keeps its border curve.

The bug worth remembering: an early draft bumped the version stamp of **every neighbour** of a
collapse, not just the two endpoints. Only `u` and `v` actually change — `u` moves and absorbs `v`'s
quadric — so an entry `(w, z)` between two untouched vertices is still perfectly valid. Retiring it
without re-pushing drained the queue, and the decimation was forced into whatever was left: a
200-triangle "sphere" with a 1.96-long edge and 27% radial deviation. Fixed, plus the shape veto,
`5120 → 200` triangles now keeps a 12.6° minimum angle and 3.0% deviation.

## The Voronoi bridge, and the T-junction it exposed

`VoronoiCell3.toTriMesh` fans each face polygon and welds at a cell-relative tolerance. Fanning alone
does **not** always close: where four bisector planes nearly meet, `voronoi.kt`'s per-face clipping
lists that corner on one face and drops it as degenerate on the neighbour, leaving a **T-junction** —
one edge on one side of the seam, two on the other. No weld tolerance fixes it, because the problem is
a *missing* vertex rather than a mismatched one (measured: an identical 22-of-36 interior cells closed
at every tolerance from `1e-6` to `3e-3` of the cell diagonal).

A Voronoi cell is an intersection of half-spaces, hence convex, hence equal to its own convex hull —
so `healToConvexHull` rebuilds the cells that need it from the same corner set. That is an exact
repair, not an approximation.

Doing so exposed a robustness hole in `convexHullMesh`: it took *every* face passing the visibility
test as the visible set. With coplanar-heavy input — Voronoi faces, a box's corners — float noise lets
a far-side face pass too, the "visible region" is then two disjoint patches, the horizon is two loops,
and coning them to one point leaves a hole. It now flood-fills a **connected** visible region from the
most-visible face. With both fixes, all 72 cells of a 72-site lattice close and their volumes sum to
the clip box to `1.8e-6` relative — up from 59 of 72 and `6e-3`.

## The gallery

`python3 tools/run_field_tests.py --id mesh.gallery --port <free>` renders ten panels to a temp dir
and fails on any broken invariant. `MESH_GALLERY_DIR=<dir>` regenerates the committed captures in
`dev_docs/assets/mesh_gallery/`; the FIELD_TEST run writes nothing into the tree.

Rendering path: real `TriMesh` pipelines → `TriMesh.renderPixels` (the CPU rasterizer in
`afield.graphics.VisionGeometryPreview` — perspective, z-buffer, perspective-correct attributes,
near-plane clip, two-light lambert, depth-tested wireframe) → AWT compositing → `ImageIO` PNG, under
`java.awt.headless = true`. AWT only draws the frames, captions and measurement strips.

Every cell also carries **pixel** guards — a painted-fraction floor and a distinct-colour floor —
because a mesh can be geometrically perfect and still render as a black rectangle.

| panel | what it shows |
|---|---|
| `01-primitives` | twelve generators with their χ, genus and volume |
| `02-marching-cubes` | a sphere at three resolutions with the volume error falling 4.7% → 1.2% → 0.3%; a genus-1 torus; a drilled solid seen from above so the hole is visible; metaballs |
| `03-sdf-modelling` | sharp vs smooth union, a shell cut open by a half-space so the wall thickness reads, a twist, a gyroid |
| `04-subdivision` | an icosahedron over three rounds, and a coarse torus keeping genus 1 |
| `05-smoothing` | clean / noisy / Laplacian / Taubin, plus both results in **one frame** so the Laplacian deflation is visible and not merely a number (every other cell is framed by its own bounding sphere, which hides exactly that) |
| `06-decimation` | 5120 → 2000 → 800 → 200 with deviations, and a marched CSG solid keeping its genus |
| `07-derived-generators` | a concave extrusion, a lathe, a helical tube, a lambda heightfield, an `Img` heightfield |
| `08-voronoi-bridge` | one interior cell, all 72 cells tiling the clip box, and the interior cells exploded |
| `09-measure-and-query` | Gaussian curvature on a torus (positive outside, negative in the hole), mean curvature, a hull |
| `10-shading-and-io` | flat / crease-aware / smooth normals, and OBJ and STL round trips |

## The demo

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --command 'macro write mesh_geometry_examples --file dev_docs/agents/transcripts/mesh_geometry_examples.yaml' \
  --command 'macro run mesh_geometry_examples'
```

Six pure-compute boxes, no `Window` and no `Renderer`. Registered in
`DemoTranscriptExecSmokeTest.EXECUTE`, so `examples.demo-transcript.exec` replays it through the real
engine every CI run and fails on any diagnostic; `AFIELD_DEMO_TRANSCRIPT_VERBOSE=1` echoes the 62
lines the boxes print so the numbers can be read back. It is also materialized into
`public/docs/assets/agent_transcripts/mesh_geometry_examples/` as a welcome-screen starting point (the
`examples.agent-transcripts.materialize` drift guard fails the build if that goes stale — regenerate
with `field_backdoor.py run-jvm-main afield.examples.AgentTranscriptMaterializer --arg
<repo>/public/docs/assets/agent_transcripts`).

## Deliberately absent

- **Catmull-Clark.** It is a quad scheme, `TriMesh` is triangles-only, and running it on a
  triangulated quad mesh gives a different (worse) surface than running it on the quads. Loop is the
  triangle scheme and it is the one here.
- **Mesh booleans on the meshes.** Do them in the field (`sdfSubtract` and friends) and re-march. A
  robust polygonal boolean is its own project.
- **PLY and glTF.** `afield.four` has loaders (`simplePLYSupport.kt`, `simpleGLTFSupport.kt`), but they
  return renderer buffers; bridging them would mean reading their formats again here, so `meshFromFile`
  says what it does not read rather than half-doing it.
- **A public `public/docs/` page.** The dev guide, the gallery and the demo came first. The imaging
  family's `public/docs/imaging_examples.md` is the template if someone wants one.

## Known limits

- Discrete curvature on a **raw** marching-cubes surface is noisy, because the marcher legitimately
  emits sliver triangles wherever a crossing lands near a grid corner and the operator divides by
  their areas. A few Taubin passes regularise the triangles without moving the surface; the gallery
  does that and says so.
- `deviationFrom` is a **vertex-sampled, one-sided** Hausdorff distance, so it under-estimates the
  true one. Valid as a lower bound and a regression test, not as a proof of closeness. `maxDeviation`
  runs it both ways.
- `MeshBvh` is a median split with no surface-area heuristic. Build time is fine and the queries are
  `O(log n)`, which is the whole point, but it is not a production ray tracer's tree.
- Nothing here is parallel or SIMD. The imaging family has `imaging_simd.kt` as precedent if a hot
  path ever needs it; `marchingCubes` sampling and `MeshBvh` construction are the obvious candidates.

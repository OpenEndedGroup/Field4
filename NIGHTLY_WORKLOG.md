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

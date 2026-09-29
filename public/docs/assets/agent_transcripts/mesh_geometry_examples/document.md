# Field Document: mesh_geometry_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Marching cubes — a watertight surface from a field

<!-- field-box id="mesh_marching" index="0" -->

### Narrative

From `code`

A ScalarField3D is just (Vec3) -> Float. marchingCubes samples it once per grid corner,
shares one vertex per crossed grid edge, and resolves the ambiguous four-crossing face
with the asymptotic decider — which is a pure function of that face's four corner
values, so the two cubes either side of the face always agree. That agreement is why
the output is closed; plain marching cubes leaks exactly there.

```edn [field-box id="mesh_marching" role="properties"]
{"id" "mesh_marching" "index" 0 "name" "Marching cubes — a watertight surface from a field" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Marching cubes — a watertight surface from a field" "_tombstone" false "frame" #afield/rect [80.0 520.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_marching_code_.kt" "lines" 42 "characters" 1992 "sha256" "071b72347a0660e739959b124221b741558c2b9e428c785de66b4584878526fb"}]}
```

```kotlin [field-property box="mesh_marching" name="code" path="mesh_marching_code_.kt"]
// A ScalarField3D is just (Vec3) -> Float. marchingCubes samples it once per grid corner,
// shares one vertex per crossed grid edge, and resolves the ambiguous four-crossing face
// with the asymptotic decider — which is a pure function of that face's four corner
// values, so the two cubes either side of the face always agree. That agreement is why
// the output is closed; plain marching cubes leaks exactly there.
val exact = 4.0 / 3.0 * PI
println("resolution   tris    volume     relative error")
for (resolution in listOf(12, 24, 48, 64)) {
    val m = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), resolution)
    val error = abs(m.signedVolume() - exact) / exact
    println("%10d %7d %10.6f %14.5f   watertight=%s chi=%d".format(
        resolution, m.triangleCount, m.signedVolume(), error,
        m.isWatertight(), m.eulerCharacteristic()))
}
println("the analytic answer is 4/3 pi = $exact")

val coarse = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), 16)
val fine = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.4), 64)

// Drilling straight through a solid adds a handle, so the genus goes from 0 to 1. That is
// the topology layer telling you something a picture cannot.
val drilled = marchingCubes(
    sdfSubtract(sdfSphere(1.0), sdfCylinder(0.3, 4.0)),
    boundsAround(radius = 1.3), 64,
)
println()
println("drilled sphere: ${drilled.summary()}")

// A gyroid is a triply-periodic minimal surface: one connected sheet with many handles,
// so its Euler characteristic is strongly negative. The marcher does not mind that the
// gyroid is not a distance function at all — it only ever looks at signs.
val gyroid = marchingCubes(sdfGyroid(4.0, 0.3), boundsAround(radius = 1.0), 56)
println("gyroid: ${gyroid.summary()}")

showMeshGrid(
    "sdfSphere marched at 16" to coarse,
    "...at 64" to fine,
    "sdfSubtract(sphere, cylinder) — genus 1" to drilled,
    "sdfGyroid(4.0, 0.3)" to gyroid,
    columns = 2,
    cellWidth = 380,
)
```

## 2. Bridges — Voronoi, FLines, OBJ and STL

<!-- field-box id="mesh_bridges" index="1" -->

### Narrative

From `code`

Field already computed a real 3D Voronoi diagram by half-space clipping (voronoi.kt);
it just could not turn it into geometry. Each cell's polygonal faces triangulate into a
closed solid, and because a Voronoi tessellation partitions its clip box exactly, the
cell volumes must sum to the box's own volume. That is the strongest single check on
the bridge, and it is one line.

```edn [field-box id="mesh_bridges" role="properties"]
{"id" "mesh_bridges" "index" 1 "name" "Bridges — Voronoi, FLines, OBJ and STL" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Bridges — Voronoi, FLines, OBJ and STL" "_tombstone" false "frame" #afield/rect [1020.0 960.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_bridges_code_.kt" "lines" 78 "characters" 3967 "sha256" "b9eeb3de1b120d232a170855a52592af215093976c6440cb518d4e75b323b567"}]}
```

```kotlin [field-property box="mesh_bridges" name="code" path="mesh_bridges_code_.kt"]
// Field already computed a real 3D Voronoi diagram by half-space clipping (voronoi.kt);
// it just could not turn it into geometry. Each cell's polygonal faces triangulate into a
// closed solid, and because a Voronoi tessellation partitions its clip box exactly, the
// cell volumes must sum to the box's own volume. That is the strongest single check on
// the bridge, and it is one line.
val rand = java.util.Random(31)
val sites = List(48) {
    vec(rand.nextFloat() * 1.6 - 0.8, rand.nextFloat() * 1.6 - 0.8, rand.nextFloat() * 1.6 - 0.8)
}
val lattice = Voronoi3d.build(sites, clipPadding = 0.15f)
var total = 0.0
var closed = 0
for (cell in lattice.cells) {
    val m = cell.toTriMesh(shrink = 1.0)
    total += abs(m.signedVolume())
    if (m.isWatertight()) closed++
}
val b = lattice.clipBounds
val boxVolume = (b.max.x - b.min.x).toDouble() * (b.max.y - b.min.y) * (b.max.z - b.min.z)
println("$closed of ${lattice.cells.size} Voronoi cells came out closed")
println("their volumes sum to %.6f against a clip box of %.6f (%.2e relative)".format(
    total, boxVolume, abs(total - boxVolume) / boxVolume))
val interior = lattice.cells.filter { it.bounded }
println("${interior.size} of them are interior cells (no face touches the clip box)")

// A tube swept along an FLine: rotation-minimising frames, so it does not twist where the
// path's curvature swings through vertical.
val path = FLine()
for (i in 0..120) {
    val t = i / 120.0
    val a = 2.0 * PI * 2.0 * t
    val p = vec(cos(a) * 0.6, (t - 0.5) * 1.3, sin(a) * 0.6)
    if (i == 0) path.moveTo(p) else path.lineTo(p)
}
val coil = tubeMesh(path, radius = 0.1, sides = 18, samples = 120, caps = true)
println()
println("tube along an FLine: ${coil.summary()}")

// TriMesh -> FLine, so a mesh is drawable in Field with no renderer set up at all. The
// crease mode keeps only the edges where the two faces genuinely disagree, which is the
// difference between a readable drawing and a grey mat of 4800 lines.
// A coarse torus, so there really are creases to keep: at 28x14 the faces meet at well
// under 30 degrees everywhere and the crease set is empty, which is the correct answer and
// a dull demonstration.
val ring = torusMesh(14, 8, 0.6, 0.22)
println("torus: ${ring.toFLines(MeshLines.EDGES).size} edges, " +
    "${ring.toFLines(MeshLines.CREASES, creaseAngleDegrees = 30.0).size} creases at 30 degrees, " +
    "${ring.toFLines(MeshLines.NORMALS).size} normals")
val patch = planeMesh(6, 6, 1.2, 1.2)
println("plane patch: ${patch.toFLines(MeshLines.BOUNDARY).size} boundary loop(s), " +
    "${patch.toFLines(MeshLines.BOUNDARY).firstOrNull()?.nodes?.size} nodes around it")

// OBJ keeps the index structure so a watertight mesh round-trips watertight; STL is
// triangle soup by design, so it comes back with three unshared vertices per triangle and
// needs a weld. Both must recover the volume exactly.
val source = icosphereMesh(3, 0.7)
val viaObj = objToTriMesh(source.toObj("ball"))
val viaStl = stlToTriMesh(source.toStlBinary())
println()
println("source     ${source.vertexCount} verts  volume %.6f".format(source.signedVolume()))
println("via OBJ    ${viaObj.vertexCount} verts  volume %.6f  watertight ${viaObj.isWatertight()}".format(
    viaObj.signedVolume()))
println("via STL    ${viaStl.vertexCount} verts  volume %.6f  watertight ${viaStl.isWatertight()}".format(
    viaStl.signedVolume()))
println("STL welded ${viaStl.weld(1e-5).vertexCount} verts  watertight ${viaStl.weld(1e-5).isWatertight()}")

showMeshGrid(
    "48 Voronoi cells, shrink = 1.0" to lattice.toTriMesh(shrink = 1.0),
    "…the interior cells, shrink = 0.6"
        to lattice.toTriMesh(shrink = 0.6, boundedOnly = true),
    "tubeMesh(FLine, sides = 18)" to coil,
    columns = 3,
    cellWidth = 300,
)

// The last line is the family's shape in one call: mesh in, FLines out, and the FLines go
// to lines() like anything else you draw in Field.
lines(ring.toFLines(MeshLines.EDGES))
```

## 3. SDF modelling — a shape as an expression

<!-- field-box id="mesh_modelling" index="2" -->

### Narrative

From `code`

The point of writing shapes as expressions: every combinator is one line, and the
smooth versions fillet where the surfaces meet, which is what makes this feel like clay
rather than CSG. Change a k, re-run this box, look again.

```edn [field-box id="mesh_modelling" role="properties"]
{"id" "mesh_modelling" "index" 2 "name" "SDF modelling — a shape as an expression" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "SDF modelling — a shape as an expression" "_tombstone" false "frame" #afield/rect [1020.0 80.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_modelling_code_.kt" "lines" 44 "characters" 2126 "sha256" "4efa4b502e4e346c4cde02e078d7e5f4de6777bbc8b8e2eeafe034148759b33f"}]}
```

```kotlin [field-property box="mesh_modelling" name="code" path="mesh_modelling_code_.kt"]
// The point of writing shapes as expressions: every combinator is one line, and the
// smooth versions fillet where the surfaces meet, which is what makes this feel like clay
// rather than CSG. Change a k, re-run this box, look again.
val ball = sdfSphere(0.8)
val bar = sdfBox(vec(2.2, 0.34, 0.34))
val sharp = sdfUnion(ball, bar)
val soft = sdfSmoothUnion(ball, bar, k = 0.3)
val drilled = sdfSmoothSubtract(soft, sdfCylinder(0.28, 4.0), k = 0.1)
val twisted = sdfBox(vec(1.2, 1.8, 0.4)).twisted(0.35)

val window = boundsAround(radius = 1.35)
val sharpMesh = marchingCubes(sharp, window, 72)
val softMesh = marchingCubes(soft, window, 72)
val drilledMesh = marchingCubes(drilled, window, 72)
val twistedMesh = marchingCubes(twisted, boundsAround(radius = 1.2), 72)

// A fillet only ever ADDS material, so the smooth union must enclose more than the sharp
// one. That is a property you can assert rather than eyeball.
println("sharp union  volume %.5f".format(sharpMesh.signedVolume()))
println("smooth union volume %.5f  (the fillet adds %.5f)".format(
    softMesh.signedVolume(), softMesh.signedVolume() - sharpMesh.signedVolume()))
println("drilled      ${drilledMesh.summary()}")
println("twisted      ${twistedMesh.summary()}")

// A shell looked at from outside is indistinguishable from the solid, so cut it open with
// a half-space: intersecting with sdfPlane keeps only the half you want, and the wall
// thickness becomes visible. chi = 2 for the half shell, 4 for the closed one (two
// nested surfaces).
val shell = soft.shell(0.07)
val closedShell = marchingCubes(shell, window, 88)
val cutaway = marchingCubes(sdfIntersect(shell, sdfPlane(vec(0, 0, -1), 0.0)), window, 88)
println("closed shell ${closedShell.summary()}")
println("cut open     ${cutaway.summary()}")

showMeshGrid(
    "sdfUnion(sphere, box)" to sharpMesh,
    "sdfSmoothUnion(…, k = 0.3)" to softMesh,
    "…then sdfSmoothSubtract(cylinder)" to drilledMesh,
    "sdfBox(…).twisted(0.35)" to twistedMesh,
    "shell(0.07) — closed, chi = 4" to closedShell,
    "…cut open by sdfPlane" to cutaway,
    columns = 3,
    cellWidth = 300,
)
```

## 4. Subdivide, smooth, decimate

<!-- field-box id="mesh_process" index="3" -->

### Narrative

From `code`

Loop subdivision quadruples the triangles and preserves the Euler characteristic
exactly, so a watertight input stays watertight at the same genus. The volume falls
monotonically toward the Loop limit surface — it converges on that, NOT on the sphere
its control vertices sit on — in geometrically shrinking steps.

```edn [field-box id="mesh_process" role="properties"]
{"id" "mesh_process" "index" 3 "name" "Subdivide, smooth, decimate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Subdivide, smooth, decimate" "_tombstone" false "frame" #afield/rect [1020.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_process_code_.kt" "lines" 64 "characters" 2945 "sha256" "1a929b0aeb6fb4ae6af46281cb235205b8b5a3ca1d82062f05e1d248e9f46ae1"}]}
```

```kotlin [field-property box="mesh_process" name="code" path="mesh_process_code_.kt"]
// Loop subdivision quadruples the triangles and preserves the Euler characteristic
// exactly, so a watertight input stays watertight at the same genus. The volume falls
// monotonically toward the Loop limit surface — it converges on that, NOT on the sphere
// its control vertices sit on — in geometrically shrinking steps.
var control = icosahedronMesh(0.8)
println("round  tris    volume     step")
var previous = control.signedVolume()
println("%5d %6d %10.5f".format(0, control.triangleCount, previous))
val rounds = ArrayList<TriMesh>()
for (round in 1..3) {
    control = control.loopSubdivide(1)
    rounds.add(control)
    val v = control.signedVolume()
    println("%5d %6d %10.5f %9.5f   chi=%d".format(
        round, control.triangleCount, v, v - previous, control.eulerCharacteristic()))
    previous = v
}

// Smoothing: a Laplacian pass is a diffusion, so it deflates the shape; Taubin's lambda /
// mu pair alternates a shrink with an inflate and holds the volume. Both remove the same
// noise, and the numbers are the whole point.
val rng = java.util.Random(7)
val clean = icosphereMesh(3, 0.8)
val noisy = clean.transformed { p ->
    val s = 1f + (rng.nextFloat() - 0.5f) * 0.18f
    vec(p.x * s, p.y * s, p.z * s)
}.withVertexNormals()
val laplacian = noisy.laplacianSmooth(iterations = 30, lambda = 0.6)
val taubin = noisy.taubinSmooth(iterations = 30, lambda = 0.6, mu = -0.63)
println()
println("clean     volume %.5f".format(clean.signedVolume()))
println("noisy     volume %.5f".format(noisy.signedVolume()))
println("laplacian volume %.5f  <- a diffusion deflates the ball".format(laplacian.signedVolume()))
println("taubin    volume %.5f  <- the lambda/mu pair holds it".format(taubin.signedVolume()))

// QEM decimation: quadric error metric edge collapse with the link condition (so a
// collapse cannot make the mesh non-manifold), a normal-flip veto and a shape veto. The
// deviation is the largest distance either mesh's vertices sit from the other's surface.
val detailed = icosphereMesh(4, 0.8)
println()
println("target    got   deviation   min angle   watertight")
val budgets = ArrayList<Pair<String, TriMesh>>()
for (target in listOf(2000, 600, 150)) {
    val small = detailed.decimate(target)
    val deviation = maxDeviation(detailed, small)
    println("%6d %6d %11.5f %11.1f   %s".format(
        target, small.triangleCount, deviation,
        small.quality().minAngleDegrees, small.isWatertight()))
    budgets.add("decimate($target)" to small)
}

showMeshGrid(
    "icosahedronMesh() control" to icosahedronMesh(0.8),
    "loopSubdivide(1)" to rounds[0],
    "loopSubdivide(3)" to rounds[2],
    "+18% radial noise" to noisy,
    "laplacianSmooth(30) — deflated" to laplacian,
    "taubinSmooth(30) — volume held" to taubin,
    budgets[0].first to budgets[0].second,
    budgets[1].first to budgets[1].second,
    budgets[2].first to budgets[2].second,
    columns = 3,
    cellWidth = 290,
)
```

## 5. Curvature, rays, closest points, inside

<!-- field-box id="mesh_measure" index="4" -->

### Narrative

From `code`

Discrete curvature: the angle deficit for Gaussian, the cotangent Laplacian's magnitude
for mean, both divided by the mixed Voronoi area. On a sphere of radius r they are
1/r^2 and 1/r; on a torus the Gaussian curvature has both signs, positive on the
outside and negative in the hole, which is why a torus is the shape to look at.

```edn [field-box id="mesh_measure" role="properties"]
{"id" "mesh_measure" "index" 4 "name" "Curvature, rays, closest points, inside" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Curvature, rays, closest points, inside" "_tombstone" false "frame" #afield/rect [80.0 960.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_measure_code_.kt" "lines" 59 "characters" 2856 "sha256" "9f00b9d7aaa7557c1437bf2b65b28aa2d5f0dfe72076e02623d5f9448d013ce2"}]}
```

```kotlin [field-property box="mesh_measure" name="code" path="mesh_measure_code_.kt"]
// Discrete curvature: the angle deficit for Gaussian, the cotangent Laplacian's magnitude
// for mean, both divided by the mixed Voronoi area. On a sphere of radius r they are
// 1/r^2 and 1/r; on a torus the Gaussian curvature has both signs, positive on the
// outside and negative in the hole, which is why a torus is the shape to look at.
val r = 0.7
val ball = icosphereMesh(4, r)
println("sphere radius $r:")
println("  Gaussian curvature mean %.5f against 1/r^2 = %.5f".format(
    ball.gaussianCurvature().average(), 1.0 / (r * r)))
println("  mean curvature     mean %.5f against 1/r   = %.5f".format(
    ball.meanCurvature().average(), 1.0 / r))

// Gauss-Bonnet is a global identity: the integral of Gaussian curvature over a closed
// surface is 2 pi times its Euler characteristic. No local bug survives it.
for ((label, m) in listOf("icosphere" to ball, "torus" to torusMesh(64, 32, 0.6, 0.24),
                          "cube" to boxMesh())) {
    val k = m.gaussianCurvature()
    val areas = m.mixedVertexAreas()
    var total = 0.0
    for (v in 0 until m.vertexCount) total += k[v] * areas[v]
    println("Gauss-Bonnet on the %-10s integral %10.6f against 2 pi chi = %10.6f".format(
        label, total, 2.0 * PI * m.eulerCharacteristic()))
}

// The BVH turns every useful query from O(n) into O(log n). Each answer below has an
// analytic counterpart, printed beside it.
val tree = ball.bvh()
val hit = tree.raycast(vec(0, 0, -5), vec(0, 0, 1))
println()
println("ray from 5 units out at a sphere of radius $r hits at ${hit?.distance} " +
    "(analytic ${5.0 - r})")
val near = tree.closestPoint(vec(3, 0, 0))
println("closest surface point to (3,0,0) is ${near?.point} at ${near?.distance} " +
    "(analytic ${3.0 - r})")
println("contains (0,0,0)      = ${tree.contains(vec(0, 0, 0))}   (inside)")
println("contains (0.9r,0,0)   = ${tree.contains(vec(0.9 * r, 0, 0))}   (inside)")
println("contains (1.1r,0,0)   = ${tree.contains(vec(1.1 * r, 0, 0))}   (outside)")
println("signedDistance (2,0,0) = %.5f (analytic %.5f)".format(
    tree.signedDistance(vec(2, 0, 0)), 2.0 - r))

// And a convex hull, with no dependencies: 200 points on a squashed ellipsoid.
val rand = java.util.Random(19)
val cloud = List(200) {
    val n = vec(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).normalize()
    vec(n.x * 0.8f, n.y * 0.45f, n.z * 0.6f)
}
val hull = convexHullMesh(cloud)
println()
println("convex hull of 200 points: ${hull.summary()}")

val torus = torusMesh(80, 40, 0.6, 0.24)
showMeshGrid(
    "gaussianCurvature() — red out, blue in the hole"
        to torus.coloredByScalar(torus.gaussianCurvature(), "coolwarm"),
    "meanCurvature()" to torus.coloredByScalar(torus.meanCurvature(), "viridis"),
    "convexHullMesh(200 points)" to hull.withColor(0.85, 0.62, 0.32),
    columns = 3,
    cellWidth = 300,
)
```

## 6. The inline view — evaluate a mesh, look at it

<!-- field-box id="mesh_inline_view" index="5" -->

### Narrative

From `code`

Every other box here pushes a picture to its output on purpose, with showMeshGrid. This one
does not: it just *ends on a TriMesh*, and the value visualizer draws it. That is the whole
point — a mesh is a value you can look at, the way a number or an Img is.

What the card shows: the mesh through the same CPU rasterizer the docs gallery uses, from a
fixed three-quarter view, with a depth-tested wireframe over two-light lambert shading; and
beside it the facts that decide whether a mesh is usable — counts, whether it is closed and
manifold, chi and genus, area, signed volume, and the bounding box. Back faces are culled as
a GPU would cull them, so an OPEN mesh wound inside-out reads as holes. A closed one wound
inside-out looks fine and reports a NEGATIVE volume instead; try `.flipped()` below.

Above 40k triangles the picture is drawn from a decimation, and says so, while the numbers
stay the real mesh's.

Build something worth looking at: a sphere with a hole drilled through it, so the card has a
genus to report, then smooth it so the drill rim is not a hard edge.

```edn [field-box id="mesh_inline_view" role="properties"]
{"id" "mesh_inline_view" "index" 5 "name" "The inline view — evaluate a mesh, look at it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The inline view — evaluate a mesh, look at it" "_tombstone" false "frame" #afield/rect [80.0 1400.0 410.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_inline_view_code_.kt" "lines" 30 "characters" 1683 "sha256" "c9bda1edd25fbb2d4f227966fa5fb5d08c772c15e4fca90b13e22a41609518c4"}]}
```

```kotlin [field-property box="mesh_inline_view" name="code" path="mesh_inline_view_code_.kt"]
// Every other box here pushes a picture to its output on purpose, with showMeshGrid. This one
// does not: it just *ends on a TriMesh*, and the value visualizer draws it. That is the whole
// point — a mesh is a value you can look at, the way a number or an Img is.
//
// What the card shows: the mesh through the same CPU rasterizer the docs gallery uses, from a
// fixed three-quarter view, with a depth-tested wireframe over two-light lambert shading; and
// beside it the facts that decide whether a mesh is usable — counts, whether it is closed and
// manifold, chi and genus, area, signed volume, and the bounding box. Back faces are culled as
// a GPU would cull them, so an OPEN mesh wound inside-out reads as holes. A closed one wound
// inside-out looks fine and reports a NEGATIVE volume instead; try `.flipped()` below.
//
// Above 40k triangles the picture is drawn from a decimation, and says so, while the numbers
// stay the real mesh's.

// Build something worth looking at: a sphere with a hole drilled through it, so the card has a
// genus to report, then smooth it so the drill rim is not a hard edge.
val drilled = marchingCubes(
    sdfSubtract(sdfSphere(0.8), sdfCylinder(0.32, 2.0)),
    boundsAround(radius = 1.0),
    48,
).taubinSmooth(12)

println(drilled.summary())
println("the card below reports the same numbers; the drill added a handle, so genus is 1")
println()
println("try: replace the last line with `drilled.flipped()` — the picture will look identical")
println("     and the volume will go negative, which is the only thing that tells you")

// The last expression is the value the box shows. No display(), no showMesh, no lines().
drilled
```

## 7. Primitives — welded, watertight, and χ = 2

<!-- field-box id="mesh_primitives" index="6" -->

### Narrative

From `code`

A TriMesh is flat FloatArray attribute storage plus an IntArray of triangle indices.
Every generator returns the MINIMAL closed mesh for its shape, which is a much stronger
guarantee than "it renders": the Euler characteristic V - E + F is exactly 2 for any
closed genus-0 solid and 0 for a torus, and that single integer catches a duplicated
seam, a missing cap or an unwelded weld — all of which still look plausible in a render.

```edn [field-box id="mesh_primitives" role="properties"]
{"id" "mesh_primitives" "index" 6 "name" "Primitives — welded, watertight, and χ = 2" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Primitives — welded, watertight, and χ = 2" "_tombstone" false "frame" #afield/rect [80.0 80.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mesh_primitives_code_.kt" "lines" 49 "characters" 2462 "sha256" "d252b0b419eed842632297130bc565e066441c26f8f895680f1a2a555c85bf99"}]}
```

```kotlin [field-property box="mesh_primitives" name="code" path="mesh_primitives_code_.kt"]
// A TriMesh is flat FloatArray attribute storage plus an IntArray of triangle indices.
// Every generator returns the MINIMAL closed mesh for its shape, which is a much stronger
// guarantee than "it renders": the Euler characteristic V - E + F is exactly 2 for any
// closed genus-0 solid and 0 for a torus, and that single integer catches a duplicated
// seam, a missing cap or an unwelded weld — all of which still look plausible in a render.
val cube = boxMesh(1, 1, 1)
val ball = icosphereMesh(3, 0.6)
val ring = torusMesh(48, 24, 0.6, 0.22)
val pill = capsuleMesh(32, 8, 0.3, 0.5)
val horn = coneMesh(32, 0.45, 0.9)
val d12 = dodecahedronMesh(0.6)

for ((label, m) in listOf("cube" to cube, "icosphere" to ball, "torus" to ring,
                          "capsule" to pill, "cone" to horn, "dodecahedron" to d12)) {
    val t = m.topology()
    println("%-13s %5d verts %5d tris  chi=%2d genus=%s watertight=%s  volume=%.5f".format(
        label, m.vertexCount, m.triangleCount, t.eulerCharacteristic, t.genus,
        t.isWatertight, m.signedVolume()))
}

// A unit cube is exactly 1 and 6; a sphere of radius r is 4/3 pi r^3 and 4 pi r^2. The
// sphere is an inscribed polyhedron, so it is always a little under.
println()
println("unit cube      volume ${cube.signedVolume()}  area ${cube.surfaceArea()}")
println("icosphere(3)   volume %.6f against 4/3 pi r^3 = %.6f".format(
    ball.signedVolume(), 4.0 / 3.0 * PI * 0.6 * 0.6 * 0.6))
println("icosphere(3)   area   %.6f against 4 pi r^2   = %.6f".format(
    ball.surfaceArea(), 4.0 * PI * 0.6 * 0.6))

// The generators are already welded, so weld() finds nothing to merge and returns the
// identical object. flatShaded() is the opposite: three unshared vertices per triangle,
// which is what a renderer wants and what makes the mesh no longer topologically closed.
println()
println("boxMesh().weld() is the same object: ${cube.weld(1e-6) === cube}")
val faceted = cube.flatShaded()
println("flatShaded(): ${faceted.vertexCount} verts, watertight ${faceted.isWatertight()}")
println("...welded back: ${faceted.weld(1e-5).vertexCount} verts, " +
    "watertight ${faceted.weld(1e-5).isWatertight()}")

showMeshGrid(
    "boxMesh() — 8 verts, not 24" to cube,
    "icosphereMesh(3)" to ball,
    "torusMesh(48, 24) — genus 1" to ring,
    "capsuleMesh(32, 8)" to pill,
    "coneMesh(32)" to horn,
    "dodecahedronMesh() — dual of the icosahedron" to d12,
    columns = 3,
    cellWidth = 300,
)
```


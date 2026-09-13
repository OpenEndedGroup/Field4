// extrudeMesh and triangulatePolygon in mesh_generate.kt say outright that they cannot do
// holes, and a letter is a ring SYSTEM. So toMesh triangulates the caps with FloatEarcut and
// hole indices, and builds the walls from the same flattened vertices the caps used — which
// is what makes the result watertight, and what makes genus a meaningful question about a
// letter.
val face = typeface("SansSerif", 170.0)
val o = layoutText("o", face).toMesh(depth = 46.0, centre = true)
val l = layoutText("l", face).toMesh(depth = 46.0, centre = true)
val word = layoutText("mesh", typeface("SansSerif", 130.0)).toMesh(depth = 36.0, centre = true)

// A 2D count against a 3D one. The hole count comes from ring nesting in the plane; the
// genus comes from counting the mesh's vertices, edges and faces.
for ((name, mesh, holes) in listOf(
    Triple("o", o, layoutText("o", face).holeCount),
    Triple("l", l, layoutText("l", face).holeCount),
    Triple("mesh", word, layoutText("mesh", typeface("SansSerif", 130.0)).holeCount),
)) {
    println("'$name': ${mesh.triangleCount} triangles, watertight ${mesh.isWatertight()}, " +
        "manifold ${mesh.isManifold()}, chi ${mesh.eulerCharacteristic()}, genus ${mesh.genus()} " +
        "(2D hole count $holes)")
}

// A 2D area against a 3D volume: a shoelace sum times the depth, against the divergence
// theorem over the triangles. A dropped cap triangle or a wall wound the wrong way breaks it.
val flat = layoutText("o", face)
val expected = flat.glyphs.sumOf { it.glyph.inkArea } * 46.0
val measured = layoutText("o", face).toMesh(depth = 46.0, yUp = false).volume()
println("'o': ink area x depth = ${"%.0f".format(expected)}, mesh volume ${"%.0f".format(measured)}, " +
    "relative difference ${"%.2e".format(kotlin.math.abs(expected - measured) / expected)}")

// Back-face culling is on in MeshView, so a wall wound inside out would be a MISSING wall
// here rather than merely odd shading. Note what is NOT here: flatShaded(). showMeshGrid
// computes topology() on what you hand it and prints it in the caption, and flatShaded()
// un-welds every triangle — so a flat-shaded copy would caption these as open, directly
// under a title stating their genus. It is also unnecessary: the render path applies
// shadedNormals to an unshaded mesh for you (mesh_field.kt:246).
showMeshGrid(
    "o — genus ${o.genus()}" to o,
    "l — genus ${l.genus()}" to l,
    columns = 2, cellWidth = 400,
    view = MeshView(yawDegrees = 26.0, pitchDegrees = 16.0, distance = 3.2)
)
displayOpen("word")
showMesh(
    word, channel = "word",
    view = MeshView(yawDegrees = 22.0, pitchDegrees = 14.0, distance = 2.6, width = 820, height = 340),
    title = "extruded from the same contours the raster filled"
)

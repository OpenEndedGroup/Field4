// What comes back from a file is not what went out, and this is the round trip that shows it.
// Nothing here writes into the repository: a temp directory, deleted at the end.
val dir = java.nio.file.Files.createTempDirectory("mesh-round-trip").toFile()
val source = icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0)

val objFile = java.io.File(dir, "ball.obj").also { source.saveObj(it) }
val stlFile = java.io.File(dir, "ball.stl").also { source.saveStl(it) }
println("ball.obj  ${objFile.length()} bytes")
println("ball.stl  ${stlFile.length()} bytes   (binary STL is exactly 84 + 50 x ${source.triangleCount})")
println("ASCII STL would be ${source.toStlAscii().length} bytes — about five times binary")
println()

val viaObj = meshFromFile(objFile.path)
val viaStl = meshFromFile(stlFile.path)

// Change this and watch the last row move: too small and the soup stays soup, too large and
// distinct features merge. mesh.meanEdgeLength() is the scale to judge it against.
val weldTolerance = 1e-5
val welded = viaStl.weld(weldTolerance)

// A lambda rather than a local `fun`: inside a box `println` is rewritten onto the box's own
// receiver, and a nested function does not have it.
val row = { label: String, m: TriMesh ->
    "%-18s %5d verts %5d tris  normals=%-5s colours=%-5s chi=%-5d %-11s volume %.6f".format(
        label, m.vertexCount, m.triangleCount, m.hasNormals, m.hasColors,
        m.topology().eulerCharacteristic,
        if (m.isWatertight()) "watertight" else "open",
        m.signedVolume())
}
println(row("source", source))
println(row("via ball.obj", viaObj))
println(row("via ball.stl", viaStl))
println(row("via ball.stl, welded", welded))
println()
println("mean edge length ${"%.5f".format(source.meanEdgeLength())} — the scale that makes")
println("$weldTolerance a safe weld tolerance rather than a guess")
println()
println("OBJ indexes positions, normals and uvs separately, so it keeps the structure and")
println("the normals — and drops the colours, because OBJ has no standard for them.")
println("STL has no vertex sharing at all: 3 x ${source.triangleCount} unshared vertices,")
println("every edge a boundary, and no per-vertex normal to recover when you weld it back.")
println("The volume is identical down every route. If the SHAPE ever changes on a round")
println("trip, that is a bug rather than a format.")

showMeshGrid(
    "source — coloured" to source,
    "via ball.obj — colours gone" to viaObj,
    "via ball.stl, welded — normals gone" to welded,
    columns = 3,
    cellWidth = 235,
)
dir.deleteRecursively()

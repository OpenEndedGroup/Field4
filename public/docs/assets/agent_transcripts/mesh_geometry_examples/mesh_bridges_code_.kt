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

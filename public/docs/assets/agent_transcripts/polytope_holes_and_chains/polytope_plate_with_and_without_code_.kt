import afield.stdlib.polytope.*
import afield.stdlib.MeshView
import afield.stdlib.Vec3

// ***** CHANGE THIS LINE ***** the hole's half-width; 0 or more, less than 1.
val hole = 0.45f

// A single square face, built by hand, optionally with one inner ring. This is the whole of
// the hole API from a caller's side: record the ring's edges as incidences of the same face,
// then declare the ring with addFaceHole. Winding is a convention — outer counter-clockwise
// about the normal, inner clockwise — and nothing downstream leans on it, because the
// tessellator decides which ring is the outer one geometrically.
fun plate(withRing: Boolean): PolyhedralObject {
    val outer = listOf(Vec3(-1f, -1f, 0f), Vec3(1f, -1f, 0f), Vec3(1f, 1f, 0f), Vec3(-1f, 1f, 0f))
    val inner = listOf(
        Vec3(-hole, -hole, 0f), Vec3(-hole, hole, 0f), Vec3(hole, hole, 0f), Vec3(hole, -hole, 0f),
    )
    // buildList, not `outer + inner`: a box has afield.graphics in scope, where
    // `List<Vec3> + List<Vec3>` is element-wise vector addition and returns four points,
    // not eight.
    val points = buildList { addAll(outer); if (withRing) addAll(inner) }
    val rings = if (withRing) listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)) else listOf(listOf(0, 1, 2, 3))

    val builder = PolytopeTopologyBuilder(rank = 3)
    val vertices = points.map { builder.addElement(0) }
    val face = builder.addElement(2)
    for (ring in rings) for (i in ring.indices) {
        val edge = builder.addElement(1)
        builder.addIncidence(vertices[ring[i]], edge)
        builder.addIncidence(vertices[ring[(i + 1) % ring.size]], edge)
        builder.addIncidence(edge, face)
    }
    builder.setFaceCycle(face, rings.first().map { vertices[it] })
    rings.drop(1).forEach { ring -> builder.addFaceHole(face, ring.map { vertices[it] }) }
    builder.addIncidence(face, builder.addElement(3))
    return PolyhedralObject(builder.buildValidated(), Realization3(points))
}

// cullBackFaces, deliberately: a rim triangle wound the wrong way would be *missing* here
// exactly as it would be missing on a GPU, so this picture is a winding test as well as a
// hole test. The wireframe is on so the eight rim triangles can be counted.
val view = MeshView(
    yawDegrees = 0.0, pitchDegrees = 0.0, distance = 2.2,
    wireframe = true, cullBackFaces = true,
)
val cells = listOf(true, false).map { withRing ->
    val object3 = plate(withRing)
    val mesh = object3.toTriMesh()
    val face = object3.topology.elements(2).single()
    ThreeMeshPanel(
        label = if (withRing) "addFaceHole(face, ring)" else "the same plate, no ring",
        mesh = mesh,
        facts = "${object3.topology.elements(0).size}v ${object3.topology.elements(1).size}e" +
            " · holes ${object3.topology.faceHoles(face.id).size}" +
            " · ${mesh.triangleCount} triangles" +
            " · area ${"%.3f".format(4.0 - if (withRing) 4.0 * hole * hole else 0.0)}",
    )
}
threeGrid(cells, channel = "plates", name = "plates", columns = 2, height = 260, cellWidth = 300, view = view)
println(
    """
    left: one face, two boundary rings, ${plate(true).toTriMesh().triangleCount} triangles
    right: the identical outline with the ring dropped, ${plate(false).toTriMesh().triangleCount} triangles
    the filled area differs by exactly the ring: ${4.0 * hole * hole}
    """.trimIndent(),
)

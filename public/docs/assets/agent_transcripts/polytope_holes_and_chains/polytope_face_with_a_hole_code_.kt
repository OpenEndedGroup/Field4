import afield.stdlib.polytope.*

// ***** CHANGE THIS LINE ***** the window's half-width, anywhere inside (0, 1).
val solid = cubeWithWindow(side = 0.45f)

val topology = solid.topology
val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
val geometry = solid.triangulate()
val rimTriangles = (0 until geometry.triangleCount).count { geometry.sourceFace(it) == windowed.id }

// Everything a holed face refuses, and the message it refuses with. These are values, not
// accidents: a Conway operator, a dualization and an OFF export are all defined for closed
// manifolds, and a face with a hole is a surface with boundary.
val refusals = listOf<Pair<String, () -> Any>>(
    "topology.dual()" to { topology.dual() },
    "solid.bevel()" to { solid.bevel() },
    "solid.toOff()" to { solid.toOff() },
    "triangulate(FanFaceTriangulator)" to { solid.triangulate(FanFaceTriangulator) },
).map { (name, call) ->
    name to (runCatching { call() }.exceptionOrNull()?.message ?: "DID NOT THROW")
}

println(
    """
    face ${windowed.id.value} boundary: outer ${topology.faceCycle(windowed.id).size} vertices,
        holes ${topology.faceHoles(windowed.id).map { it.size }}, rings ${topology.faceRings(windowed.id).size}
    chi counting elements = ${topology.eulerCharacteristic()}, counting cells = ${topology.cellularEulerCharacteristic()}
        (a disk with one hole is 1 - 1, so a sphere with a window is 1)
    closed manifold = ${topology.validate(checkManifold = true).isValid}, and the only complaints are
        ${topology.validate(checkManifold = true).issues.size} one-sided window edges
    triangles cut from the windowed face = $rimTriangles (a solid square would be 2)
    """.trimIndent(),
)
println("\nrefused, by name:")
refusals.forEach { (call, message) -> println("  $call -> $message") }
println("\norbit the view below; the window is a window, and the far wall is behind it")

// One FLine per face. A face's holes are further closed contours on that same line — one
// line is still one face, and a hole is not a face — which is what the viewer's own
// outer-ring-plus-holes grouping then subtracts.
val faceLines = solid.toFaceFLines { face, line ->
    val holes = topology.faceHoles(face.id)
    line.filled = true
    line.stroked = true
    line.thickness = if (holes.isEmpty()) 1.1 else 2.0
    line.color = if (holes.isEmpty()) vec(0.26, 0.42, 0.58, 0.70) else vec(1.00, 0.62, 0.24, 0.88)
    line.hoverColor = vec(0.96, 0.98, 1.00, 0.98)
    line.onClick = { hit ->
        markdown(
            "face detail",
            """
            **face ${face.id.value}**

            - outer ring: `${topology.faceCycle(face.id).size}` vertices
            - holes: `${holes.map { it.size }}`
            - triangles: `${(0 until geometry.triangleCount).count { geometry.sourceFace(it) == face.id }}`
            - clicked at: `${hit.position}`
            """.trimIndent(),
        )
    }
}

threeLines(
    faceLines,
    channel = "polytope-holes",
    name = "cube-with-window",
    height = 520,
    autoFrame = true,
    editable = false,
)
displayOpen("polytope-holes")
viz("topology", topology.summary())

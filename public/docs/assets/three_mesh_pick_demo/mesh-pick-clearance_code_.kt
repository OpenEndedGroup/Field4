// A SOLID and its ANNOTATIONS in one scene -- and, from tonight, a measurement
// BETWEEN them.
//
// Run this box. A concrete slab appears with a conduit run floating in front of
// it, and the viewer's toolbar carries the measure / section / explode tools
// that until now only an all-FLine figure could have.
//
//   [ruler]   press it, then click the SLAB and then the CONDUIT. The dimension
//             that appears is the closest approach between a triangle of a mesh
//             and a segment of an FLine. Until 2026-08-16 the ruler could not
//             see the solid at all: the viewer built its pick list out of the
//             FLine half of the payload, so a ray went straight through every
//             mesh in the scene.
//
//             Where you click on the slab decides what you measure to. Near a
//             corner of a triangle it snaps to the VERTEX; along an edge, to
//             the EDGE; in the middle, the whole TRIANGLE is the element. Three
//             shapes -- the same three an FLine hit resolves to, which is
//             exactly why one measurement can span the two.
//
//   [section] cuts the slab as well as the conduit, and draws the cut line
//             through both.
//
// With the ruler OFF, clicking either object prints its hit record below.

val slab = boxMesh(1200.0, 800.0, 60.0).withColor(0.44, 0.50, 0.58)

// The conduit run, 150 mm proud of the slab's +z face, in the slab's own
// millimetres.
val conduit = fline {
    moveTo(-500.0, -260.0, 180.0)
    lineTo(300.0, -260.0, 180.0)
    lineTo(300.0, 240.0, 180.0)
    lineTo(520.0, 240.0, 180.0)
    label = "conduit A"
    color = vec(1.0, 0.72, 0.25, 1.0)
    thickness = 3.0
    pointed = true
    pointSize = 8.0
    hoverColor = vec(1.0, 0.94, 0.66, 1.0)
    onClick = { hit ->
        println("conduit hit: ${hit.kind} node ${hit.nodeIndex} u=${"%.3f".format(hit.u)} at ${hit.onElement}")
    }
}

// The slab's +z face and the conduit's first leg, named from Kotlin, so the
// figure carries one dimension from the moment it is drawn rather than waiting
// to be asked. 180 - 30 = 150 mm, and the ruler should agree with that.
val slabFace = ThreeMeasureElement(
    "polygon",
    listOf(
        floatArrayOf(-600f, -400f, 30f),
        floatArrayOf(600f, -400f, 30f),
        floatArrayOf(600f, 400f, 30f),
        floatArrayOf(-600f, 400f, 30f),
    ),
)
val firstLeg = ThreeMeasureElement.segment(-500.0, -260.0, 180.0, 300.0, -260.0, 180.0)

three(channel = "main", name = "clearance", height = 560, width = 900) {
    // `pickable` is the new word and `ThreeSurfaceHit` is the new record. Before
    // tonight a mesh in this viewer was a picture: it drew, and nothing could
    // touch it.
    mesh(
        slab,
        label = "slab",
        id = "slab",
        wireframe = true,
        pickable = true,
        hoverColor = vec(0.34, 0.66, 0.98, 1.0),
        onClick = { hit: ThreeSurfaceHit ->
            println(
                "slab hit: ${hit.kind} face ${hit.faceIndex}" +
                    (if (hit.vertexIndex >= 0) " vertex ${hit.vertexIndex}" else "") +
                    (if (hit.edgeIndex >= 0) " edge ${hit.edgeIndex}" else "") +
                    " bary ${"%.3f %.3f %.3f".format(hit.u, hit.v, hit.w)}" +
                    " on ${hit.onElement}" +
                    (if (hit.frontFacing) "" else " [back face]")
            )
        },
    )
    lines(conduit)
    camera(yaw = 30.0, pitch = 22.0, distance = 3.0)
    inspect(
        units = "mm",
        decimals = 0,
        measurements = listOf(ThreeMeasurement(slabFace, firstLeg, "clearance ")),
    )
    title("slab + conduit -- press the ruler and measure from one to the other")
}

println("press the ruler, click the slab, then click the conduit: it should read 150 mm")

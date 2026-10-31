/*
A solid and its annotations share one scene and one measurement tool.
Run this box to see a slab and a conduit floating in front of it.

Ruler: click the slab, then the conduit. The dimension measures the closest
approach between the selected mesh and line features. A mesh click selects
a vertex, edge or triangle, according to where the pointer lands.
Section: cut both objects and see their section lines.
With the ruler off, click either object to print its hit record below.
*/

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
        println("conduit hit: ${hit.kind} node ${hit.nodeIndex} u=${hit.u.f3} at ${hit.onElement}")
    }
}

/*
The slab's +z face and the conduit's first leg, named from Kotlin, so the
figure carries one dimension from the moment it is drawn rather than waiting
to be asked. 180 - 30 = 150 mm, and the ruler should agree with that.
*/
val slabFace = ThreeMeasureElement(
    "polygon",
    [
        floatArrayOf(-600f, -400f, 30f),
        floatArrayOf(600f, -400f, 30f),
        floatArrayOf(600f, 400f, 30f),
        floatArrayOf(-600f, 400f, 30f),
    ],
)
val firstLeg = ThreeMeasureElement.segment(-500.0, -260.0, 180.0, 300.0, -260.0, 180.0)

three(channel = "main", name = "clearance", height = 560, width = 900) {
    /*
    `pickable` is the new word and `ThreeSurfaceHit` is the new record. Before
    tonight a mesh in this viewer was a picture: it drew, and nothing could
    touch it.
    */
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
                    (if (hit.frontFacing) "" else " [back face]"),
            )
        },
    )
    lines(conduit)
    camera(yaw = 30.0, pitch = 22.0, distance = 3.0)
    inspect(
        units = "mm",
        decimals = 0,
        measurements = [ThreeMeasurement(slabFace, firstLeg, "clearance ")],
    )
    title("slab + conduit -- press the ruler and measure from one to the other")
}

println("press the ruler, click the slab, then click the conduit: it should read 150 mm")

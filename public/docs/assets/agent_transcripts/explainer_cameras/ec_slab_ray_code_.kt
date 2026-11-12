import afield.simulation.optics.*

// One ray through a slab of glass. It bends going in and bends back coming out, so it leaves
// parallel to where it was going, but shifted. The faint line is the path it would have taken.
val glass = 1.5
val unit = 100.0
val top = 100.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var slab = 1.0
var angle = 45.0

fun draw() {
    val theta = angle * PI / 180
    val inside = Refraction.snellAngle(theta, 1.0, glass)
    val bottom = top + slab * unit
    val entryX = 200.0
    val exitX = entryX + slab * unit * tan(inside)
    val endY = 360.0
    val start = frameExit(entryX, top, -sin(theta), -cos(theta), 640.0, endY)
    val guideEnd = frameExit(entryX, top, sin(theta), cos(theta), 640.0, endY)
    val parts = [
        fline {
            rect(0, top, 640, slab * unit)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(entryX, top)
            lineTo(guideEnd[0], guideEnd[1])
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(start[0], start[1])
            lineTo(entryX, top)
            lineTo(exitX, bottom)
            frameExit(exitX, bottom, sin(theta), cos(theta), 640.0, endY).let { lineTo(it[0], it[1]) }
            color = cyan
            thickness = 2.5
        },
    ]
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, endY),
        interactive = false,
    )
    // How far apart the two parallel paths are, measured square to them.
    val shift = slab * sin(theta - inside) / cos(inside)
    markdown("readout", "slab ${slab.f2} thick: the ray comes out shifted ${shift.f2} sideways")
}

draw()
controlGroup {
    slider("thickness", 0.0, 2.2, step = 0.02, initialValue = 1.0) {
        slab = it
        draw()
    }
    slider("angle", 0.0, 75.0, step = 0.5, initialValue = 45.0) {
        angle = it
        draw()
    }
}

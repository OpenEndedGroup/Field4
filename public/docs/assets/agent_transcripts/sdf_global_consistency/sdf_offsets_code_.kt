// A box with a disc cut from its corner by max: the zero set is right, the values beside it are
// only bounds, so offset curves drawn from them come out in the wrong place.
fun disc(cx: Double, cy: Double, r: Double) = { x: Double, y: Double -> hypot(x - cx, y - cy) - r }
fun rect(hx: Double, hy: Double) = { x: Double, y: Double ->
    val qx = abs(x) - hx
    val qy = abs(y) - hy
    hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
}
val square = rect(0.8, 0.6)
val hole = disc(0.8, 0.6, 0.5)
val cut = { x: Double, y: Double -> max(square(x, y), -hole(x, y)) }

val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.5, xMax = 1.5, yMax = 1.5, n = 121, field = cut)
val fixed = pseudo.repaired()
println("pseudo   ${pseudo.consistency()}")
println("repaired ${fixed.consistency()}")

// Offsets at +-0.2 and +-0.4 from each grid; orange is the shape itself.
val scale = 120.0
fun offsets(grid: SdfGrid2D, dx: Double) = buildList {
    for (iso in [-0.4, -0.2, 0.0, 0.2, 0.4]) {
        val contours = generateIsoContours(grid::bilinear, -1.5, 1.5, -1.5, 1.5, 240, 240, iso)
        for (c in contours) {
            add(
                fline {
                    moveTo((c[0].x + dx) * scale, c[0].y * scale)
                    for (p in c) lineTo((p.x + dx) * scale, p.y * scale)
                    lineTo((c[0].x + dx) * scale, c[0].y * scale)
                }
                    .apply {
                        color = if (iso == 0.0) vec(1.0, 0.67, 0.0, 1.0) else vec(0.0, 0.67, 1.0, 0.8)
                    },
            )
        }
    }
}
lines(offsets(pseudo, 0.0) + offsets(fixed, 3.2), channel = "offsets", w = 640.0, h = 300.0, fitToSize = true)

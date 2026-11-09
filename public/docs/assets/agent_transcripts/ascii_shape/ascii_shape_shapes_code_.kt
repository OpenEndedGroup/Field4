// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing =
    Img.zeros(440, 200, 3)
        .drawFLines(
            [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
            color = Vec4(1f, 1f, 1f, 1f),
        )
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(drawing, 44, shape = false),
    "shape" to ascii.convert(drawing, 44),
)

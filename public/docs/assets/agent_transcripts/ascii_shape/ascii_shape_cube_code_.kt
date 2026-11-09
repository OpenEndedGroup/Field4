// A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.
val ascii = ShapeAscii()
// Corner i has x, y, z = ±1 from its three bits; an edge joins corners one bit apart.
val corners =
    (0 until 8).map { i ->
        Triple(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0)
    }
val edges = (0 until 8).flatMap { i -> [1, 2, 4].filter { i and it == 0 }.map { i to (i or it) } }

charGrid(cols = 60, rows = 26, glyphs = ascii.glyphs) {
    val a = frame * 0.03
    fun project(p: Triple<Double, Double, Double>): Pair<Double, Double> {
        val (px, py, pz) = p
        val x1 = px * cos(a) - pz * sin(a)
        val z1 = px * sin(a) + pz * cos(a)
        val y1 = py * cos(a * 0.7) - z1 * sin(a * 0.7)
        val z2 = py * sin(a * 0.7) + z1 * cos(a * 0.7)
        val s = 150.0 / (z2 + 4.0)
        return (240 + x1 * s) to (130 + y1 * s)
    }
    val lines = edges.map { (i, j) ->
        fline {
            moveTo(project(corners[i]).first, project(corners[i]).second)
            lineTo(project(corners[j]).first, project(corners[j]).second)
            thickness = 5.0
        }
    }
    val img = Img.zeros(480, 260, 3).drawFLines(lines, color = Vec4(1f, 1f, 1f, 1f))
    drawAscii(ascii.convert(img, COLS, contrast = 3.0, rows = ROWS))
}

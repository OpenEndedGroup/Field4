import afield.geometry.topology.morsesmale.*

/*
Launch this one (Alt+Up). Six hills circle at different speeds; every frame recomputes
the field and its complex, simplified at 0.05. A critical point's colour comes from its
id, so it stays the same colour for as long as the feature stays on the same grid cell.
*/
val hills = (0 until 6).map { i -> Triple(0.4 + 0.25 * i, 30.0 + 9.0 * i, if (i % 3 == 2) -0.7 else 1.0) }

fun colourOf(id: Int): Vec4 {
    val h = (id * -0x61c88647) ushr 8
    return Vec4(
        0.4f + 0.6f * ((h and 0xff) / 255f),
        0.4f + 0.6f * (((h shr 8) and 0xff) / 255f),
        0.4f + 0.6f * (((h shr 16) and 0xff) / 255f),
        1f,
    )
}

loop {
    val t = frameNumber / 60.0
    val field =
        Img.generate(200, 150) { x, y ->
            hills
                .withIndex()
                .sumOf { (i, h) ->
                    val (speed, radius, amplitude) = h
                    val cx = 100 + radius * cos(speed * t + i)
                    val cy = 75 + radius * 0.7 * sin(speed * t * 1.3 + i)
                    amplitude * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 500.0)
                }
                .toFloat()
        }
    val r = MorseSmale.compute(field, persistence = 0.05)
    val dots =
        r.criticalPointsFLine(7.0).also { line ->
            line.nodes.forEach { it.color = colourOf(it.properties["nodeId"] as Int) }
        }
    showImgGrid(
        "frame $frameNumber: ${r.criticalPoints.size} critical points" to
            field.normalized().colormap("viridis").drawFLines(r.toFLines() + dots, scale = 2.0),
        columns = 1,
        cellSide = 400,
    )
}

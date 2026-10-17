import afield.graphics.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// One take, as a pen records it: an S across the pad, drawn slowly through its first 40%
// (1.1 s) and flicked through the rest (0.25 s), pressing hard at the start and lifting
// as it goes, the pen laid further over toward the end.
val take = (0 until 160).map { i ->
    val u = i / 159.0
    val s = if (u < 0.75) 0.4 * u / 0.75 else 0.4 + 0.6 * (u - 0.75) / 0.25   // how far along
    PenSample(if (u < 0.75) 1.1 * u / 0.75 else 1.1 + 0.25 * (u - 0.75) / 0.25, -0.8 + 1.6 * s, 0.4 * sin(2 * PI * s),
        pressure = 1.0 - 0.95 * s, tiltX = 10.0 + 50.0 * s, tiltY = 0.0)
}
// The same path at an even pace: what is left if the recording kept no time.
val even = run {
    val arc = take.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.runningFold(0.0) { acc, d -> acc + d }
    take.mapIndexed { i, s -> s.copy(t = take.last().t * arc[i] / arc.last()) }
}
val ways = listOf(
    "position and time, as a mouse records it" to take.map { PenSample(it.t, it.x, it.y) },
    "with the pen's pressure" to take.map { it.copy(tiltX = 0.0) },
    "with pressure and tilt" to take,
    "everything but the time" to even,
)
val canvases = ways.map { (_, samples) ->
    PaintCanvas(300, aspect = 1.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 120.0, seed = 5).also { c ->
        c.stroke(c.gesture(samples), c.dynamicBrush(BrushKind.Filbert, 50.0, hairs = 32).load(rgb(0.15, 0.2, 0.35), stiff = 0.5), seed = 5)
    }
}
display("main", pngRow(canvases))
canvases.forEach { it.close() }
markdown("ways", ways.mapIndexed { i, (name, _) -> "${i + 1}. $name" }.joinToString("\n"))
}

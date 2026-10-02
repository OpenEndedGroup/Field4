// Five steps of the morph: matched on top, by list index below.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)
val cx = from.sumOf { it.x.toDouble() } / from.size; val cy = from.sumOf { it.y.toDouble() } / from.size
fun dot(p: Vec2, dx: Double, dy: Double, colour: Vec4) = FLine().also { f ->
    val x = (p.x - cx) * 0.5 + dx; val y = (p.y - cy) * 0.5 + dy
    f.moveTo(x - 2, y - 2).lineTo(x + 2, y - 2).lineTo(x + 2, y + 2).lineTo(x - 2, y + 2).lineTo(x - 2, y - 2)
    f.color = colour; f.filled = true; f.stroked = false
}
val steps = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
lines(steps.indices.flatMap { s ->
    morphBetween(from, partner, steps[s]).map { dot(it, s * 200.0, 0.0, vec(0.1, 0.45, 0.85, 1.0)) } +
        morphBetween(from, to, steps[s]).map { dot(it, s * 200.0, 200.0, vec(0.88, 0.2, 0.2, 1.0)) }
}, w = 1000.0, h = 400.0, fitToSize = true)

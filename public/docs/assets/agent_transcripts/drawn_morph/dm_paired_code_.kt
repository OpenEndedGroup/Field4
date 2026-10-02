// Read the strokes back, give each 240 evenly spaced points, and pair them.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val match = matchPoints(from, to)
val partner = match.reorder(to)
val cx = from.sumOf { it.x.toDouble() } / from.size; val cy = from.sumOf { it.y.toDouble() } / from.size
fun chord(a: Vec2, b: Vec2, dx: Double) = FLine().moveTo(a.x + dx, a.y).lineTo(b.x + dx, b.y).also {
    it.thickness = 0.8
    it.color = okhsl(atan2(a.y - cy, a.x - cx) * 180 / PI + 360, 0.85, 0.6, 0.9)
}
// Left: the matching. Right: point i of one stroke to point i of the other.
lines(from.indices.map { chord(from[it], partner[it], -220.0) } + from.indices.map { chord(from[it], to[it], 220.0) },
    w = 760.0, h = 380.0, fitToSize = true)
val byIndex = from.indices.sumOf { (from[it].x - to[it].x).toDouble().pow(2) + (from[it].y - to[it].y).toDouble().pow(2) }
println("squared travel: matched %.0f, by list index %.0f".format(match.squaredCost, byIndex))

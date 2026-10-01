// Run it. 600 points scattered in a disc and 600 on a ring around it. matchPoints gives
// every disc point a ring point of its own, keeping the total squared travel small.
val rnd = java.util.Random(4)
val disc = List(600) {
    val r = 150 * sqrt(rnd.nextDouble()); val a = 2 * PI * rnd.nextDouble()
    vec(r * cos(a), r * sin(a))
}
val ring = List(600) { val a = 2 * PI * it / 600; vec(300 * cos(a), 300 * sin(a)) }

val partner = matchPoints(disc, ring).reorder(ring)

// Left: the matching. Right: ring point i for disc point i, which is what you get by
// interpolating two point lists without matching them first.
fun chord(from: Vec2, to: Vec2, dx: Double) = FLine().moveTo(from.x + dx, from.y).lineTo(to.x + dx, to.y).also {
    it.thickness = 0.8
    it.color = okhsl(atan2(to.y.toDouble(), to.x.toDouble()) * 180 / PI + 360, 0.85, 0.65, 0.8)
}
lines(disc.indices.map { chord(disc[it], partner[it], -340.0) } +
      disc.indices.map { chord(disc[it], ring[it], 340.0) },
      w = 1000.0, h = 500.0, fitToSize = true)
displayOpen("lines")

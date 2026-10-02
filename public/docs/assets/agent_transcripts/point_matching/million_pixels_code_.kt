// Run it; a few seconds. Every pixel of a 1024 x 1024 square is a source point, and a
// million targets are scattered through three discs, each holding a share proportional
// to its area. Each pixel gets a target of its own, and is painted with that target's
// place in its disc: hue for the angle, lightness for the distance from the centre.
val side = 1024
val n = side * side
val ax = FloatArray(n) { it % side + 0.5f }
val ay = FloatArray(n) { it / side + 0.5f }

val discs = listOf(Triple(330.0, 400.0, 250.0), Triple(740.0, 300.0, 160.0), Triple(640.0, 760.0, 200.0))
val area = discs.sumOf { it.third * it.third }
val rnd = java.util.Random(9)
val home = IntArray(n)
val bx = FloatArray(n); val by = FloatArray(n)
for (j in 0 until n) {
    var pick = rnd.nextDouble() * area
    var d = 0
    while (pick > discs[d].third * discs[d].third) { pick -= discs[d].third * discs[d].third; d++ }
    val r = discs[d].third * sqrt(rnd.nextDouble()); val a = 2 * PI * rnd.nextDouble()
    home[j] = d
    bx[j] = (discs[d].first + r * cos(a)).toFloat(); by[j] = (discs[d].second + r * sin(a)).toFloat()
}

val start = System.nanoTime()
val match = BspOt2D.match(ax, ay, bx, by, BspOt2D.Options(trees = 8))
val seconds = (System.nanoTime() - start) / 1e9

val px = IntArray(n) { i ->
    val j = match.targetOfSource[i]
    val (cx, cy, radius) = discs[home[j]]
    val dx = bx[j] - cx; val dy = by[j] - cy
    val c = okhsl(atan2(dy, dx) * 180 / PI + 360, 0.85, 0.25 + 0.6 * sqrt(dx * dx + dy * dy) / radius)
    (255 shl 24) or ((c.x * 255).toInt() shl 16) or ((c.y * 255).toInt() shl 8) or (c.z * 255).toInt()
}
display("main", afield.util.PngEncoder.encode(px, side, side))
displayOpen("main")
println("matched %,d to %,d in %.1f s, rms travel %.1f px".format(n, n, seconds, sqrt(match.meanSquaredDistance)))

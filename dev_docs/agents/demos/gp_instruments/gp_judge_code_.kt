import kotlin.math.*

// The same loop with the human replaced by a judge whose taste we know: it likes dark-blue
// five-petalled rosettes, deep and barely curled. Twenty-four answers, and we draw the
// model's favourite after every six, ending beside the judge's ideal (grey, right).
fun rosette(p: Map<String, Double>, dx: Double) = FLine().also { f ->
    val petals = p["petals"]!!.roundToInt()
    for (i in 0..600) {
        val t = 2 * PI * i / 600
        val r = 1 + p["depth"]!! * cos(petals * t) + 0.15 * p["depth"]!! * cos(2 * petals * t + 3 * p["curl"]!!)
        val x = dx + 90 * r * cos(t + p["curl"]!! * sin(petals * t) * 0.3)
        val y = -90 * r * sin(t + p["curl"]!! * sin(petals * t) * 0.3)
        if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
    }
    f.color = okhsl(p["hue"]!!, 0.8, 0.55, 1.0); f.thickness = 2.0
}
val ideal = mapOf("petals" to 5.0, "depth" to 0.6, "curl" to 0.1, "hue" to 250.0)
fun utility(p: Map<String, Double>) =
    -((p["petals"]!! - 5.0) / 6).pow(2) - ((p["depth"]!! - 0.6) / 0.7).pow(2) -
        (p["curl"]!! - 0.1).pow(2) - ((p["hue"]!! - 250.0) / 360).pow(2)

val taste = GpPreference("petals" to 3.0..9.0, "depth" to 0.1..0.8, "curl" to 0.0..1.0, "hue" to 0.0..360.0)
val snapshots = mutableListOf<Map<String, Double>>()
repeat(24) { round ->
    val pair = taste.nextPair()
    taste.choose(pair, aWins = utility(pair.first) > utility(pair.second))
    if ((round + 1) % 6 == 0) snapshots += taste.best!!
}
lines(snapshots.mapIndexed { i, p -> rosette(p, 380.0 * i) } +
    rosette(ideal, 380.0 * snapshots.size).also { it.color = vec(0.55, 0.55, 0.55, 1.0) },
    w = 1000.0, h = 200.0, fitToSize = true)
snapshots.forEachIndexed { i, p ->
    println("after %2d answers: %s  utility %.3f".format(6 * (i + 1), p.mapValues { "%.2f".format(it.value) }, utility(p)))
}
println("ideal utility 0; a random rosette averages %.3f".format(
    (0 until 200).map { java.util.Random(it.toLong()).let { r -> mapOf("petals" to 3 + 6 * r.nextDouble(),
        "depth" to 0.1 + 0.7 * r.nextDouble(), "curl" to r.nextDouble(), "hue" to 360 * r.nextDouble()) } }
        .map { utility(it) }.average()))

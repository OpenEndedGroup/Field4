

// A rosette with three dials. You do not say what you want; you only say which of two you
// like better, and the model proposes the next two.
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

val taste = GpPreference("petals" to 3.0..9.0, "depth" to 0.1..0.8, "curl" to 0.0..1.0, "hue" to 0.0..360.0)
var pair = taste.nextPair()

fun show() {
    lines(rosette(pair.first, 0.0), rosette(pair.second, 300.0), w = 600.0, h = 280.0, fitToSize = true)
    val fav = taste.best
    display("status", "<div>%d answers. A (left) vs B (right).%s</div>".format(taste.comparisons.size,
        if (fav == null) "" else " Favourite so far: " + fav.mapValues { "%.2f".format(it.value) }))
}
fun answer(aWins: Boolean) { taste.choose(pair, aWins); pair = taste.nextPair(); show() }

controlGroup {
    button("A is better") { down -> if (down) answer(true) }
    button("B is better") { down -> if (down) answer(false) }
}
show()

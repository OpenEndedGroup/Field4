// A crescendo read by cutting it. A vertical line at a beat crosses the hairpin's two sides,
// and the gap between the crossings is how loud it is: nothing at the point, most at the mouth.
// Run this to draw one; then draw your own in the frame with the pen (Alt+D, then P) and this
// box reads everything in the frame again. Two hairpins, or a diminuendo, read the same way.
notation("hairpin") {
    frame("dynamics", 100, 1060, 800, 200)
    val hand = { i: Int -> 2.5 * sin(i * 2.3) + 1.5 * sin(i * 0.7) }     // a hand is not a ruler
    val hairpin = (0..60).map { i ->
        val k = if (i <= 30) i else 60 - i                                // out along the top, back along the bottom
        val side = if (i <= 30) -1 else 1
        vec(860 - k * 23.0, 1160 + side * (75 - k * 2.5) + hand(i))
    }
    stroke(hairpin, name = "hairpin", stroke = "#e03131")
}
val dynamics = notation.space("dynamics", x = 0.0..8.0, y = 0.0..1.0)
val markings = listOf("niente", "pp", "p", "mp", "mf", "f", "ff")
fun read() = (0..7).forEach { beat ->
    val ys = dynamics.vertical(beat.toDouble()).filter { !it.isSpan }.map { it.value }
    val gap = if (ys.size >= 2) ys.max() - ys.min() else 0.0
    println("beat $beat: %.2f %s".format(gap, markings[(gap / 0.75 * 6).roundToInt().coerceIn(0, 6)]))
}
read()
notation.onStroke("crescendo") { read() }

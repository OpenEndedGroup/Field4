// A tablet-like stroke: a point every 0.6 units, and pressure with a little sensor wobble.
val rnd = java.util.Random(1)
val raw =
    (0 until 1000).map { i ->
        val s = i / 999.0
        val p = (0.15 + 0.85 * sin(PI * s).pow(0.7) + 0.03 * rnd.nextGaussian()).coerceIn(0.0, 1.0)
        vec(600 * s, 60 * sin(9 * s) + 0.1 * rnd.nextGaussian(), 12 * p.pow(1.75))
    }
val thin = thinPenSamples(raw, tolerance = 0.5)

val rawOutline = sweptCircleStroke(raw, samplesPerSegment = 4).shell
val thinOutline = sweptCircleStroke(thin).shell
println("raw: ${raw.size} samples, ${sweptCircleDegenerateSpans(raw).size} spans without an envelope")
println("thinned: ${thin.size} samples, ${sweptCircleDegenerateSpans(thin).size} spans without an envelope")

fun ink(f: FLine, dy: Double) = (f + vec(0.0, dy)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}
fun dots(knots: List<Vec3>, dy: Double, size: Double) = knots.map { k ->
    fline {
        moveTo(k.x, k.y + dy)
        lineTo(k.x + 0.01, k.y + dy)
    }
        .apply {
            pointed = true
            pointSize = size
            color = vec(1.0, 0.4, 0.3, 1.0)
        }
}
lines(
    [ink(rawOutline, 0.0), ink(thinOutline, 170.0)] + dots(raw, 0.0, 0.5) + dots(thin, 170.0, 1.2),
    w = 900.0,
    h = 380.0,
    fitToSize = true,
)

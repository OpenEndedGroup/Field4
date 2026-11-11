// One cubic whose radius swells from 2 to about 17 and back to 2: the envelope against the plain ribbon.
val centre = fline {
    moveTo(0, 0)
    cubicTo(40, 45, 80, -30, 120, 20)
}
val radius = parameterFromLambda { t, _ -> 2f + 26f * t * t * (1f - t) * 4f }

val swept = centre.bySweptCircleEnveloping(radius, samples = 200, flatness = 0.02)
val ribbon = centre.byVariableRibboning(parameterFromLambda { t, p -> 2f * radius.evaluate(t, p) }, samples = 200)
// reference: the union of 1500 discs along the line
val reference = centre.byJtsVariableBuffering(
    numSamples = 1500,
    distance = { t -> radius.evaluate(t.toFloat(), vec(0f, 0f, 0f)).toDouble() },
)

println("swept envelope: %.3f from the union of discs".format(swept.shell.outlineDistanceTo(reference)))
println("byVariableRibboning: %.3f".format(ribbon.outlineDistanceTo(reference)))

val circles =
    (0..16).map { i ->
        val s = centre.sampleSeries(17)[i]
        val r = radius.evaluate(s.t, s.point).toDouble()
        fline { circle(s.point.x.toDouble(), s.point.y.toDouble(), r) }
            .apply {
                thickness = 0.8
                color = vec(1.0, 1.0, 1.0, 0.35)
            }
    }
lines(
    circles +
        listOf(
            centre.copy().apply { color = vec(1.0, 1.0, 1.0, 0.5) },
            ribbon.copy().apply {
                color = vec(1.0, 0.45, 0.3, 1.0)
                thickness = 1.5
            },
            swept.left.copy().apply {
                color = vec(0.4, 0.9, 1.0, 1.0)
                thickness = 2.0
            },
            swept.right.copy().apply {
                color = vec(0.4, 0.9, 1.0, 1.0)
                thickness = 2.0
            },
        ),
    w = 900.0,
    h = 380.0,
    fitToSize = true,
)

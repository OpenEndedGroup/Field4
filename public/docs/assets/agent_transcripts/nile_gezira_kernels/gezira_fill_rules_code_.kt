import afield.graphics.nile.*

// A pentagram drawn as one outline that crosses itself, and two overlapping circles, on 56 x 40.
val star = FLine()
for (k in 0..5) {
    val a = -PI / 2 + k * 4 * PI / 5
    val x = 16.0 + 14.0 * cos(a)
    val y = 21.0 + 14.0 * sin(a)
    if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
}
val shape = Gezira.beziers(star) + Gezira.circle(36.0, 17.0, 10.3) + Gezira.circle(43.0, 25.0, 10.3)

val nonZero = Gezira.rasterize(shape, 56, 40, FillRule.NON_ZERO)
val evenOdd = Gezira.rasterize(shape, 56, 40, FillRule.EVEN_ODD)
val edgeSamples = Gezira.coverage(Gezira.edgeSampleRasterizer(shape), 56, 40)
val reference = Gezira.supersampledCoverage(shape, 56, 40, n = 64)
val error = DoubleArray(edgeSamples.size) { minOf(1.0, 10 * abs(edgeSamples[it] - reference[it])) }
println("winding, non-zero, vs 64 x 64 supersample: max |diff| %.4f".format(Gezira.error(nonZero, reference).first))
println("edge samples vs the same: max |diff| %.4f".format(Gezira.error(edgeSamples, reference).first))

val s = 6.0
lines(Gezira.pixels(nonZero, 56, s), channel = "non-zero")
lines(Gezira.pixels(evenOdd, 56, s), channel = "even-odd")
lines(Gezira.pixels(error, 56, s, 1.0, 0.4, 0.3), channel = "edge samples, error x10")

import afield.graphics.nile.*

// A five-pointed star and a circle of four cubics, on a 48 x 40 grid.
val star = FLine().moveTo(24.0 + 17.0, 20.0)
for (k in 1 until 10) {
    val a = PI * k / 5
    val r = if (k % 2 == 0) 17.0 else 7.0
    star.lineTo(24.0 + r * cos(a), 20.0 + r * sin(a))
}
val beziers = Gezira.beziers(star) + Gezira.circle(30.3, 26.1, 9.4)

val ours = Gezira.rasterize(beziers, 48, 40)
val skia = Gezira.skiaCoverage(beziers, 48, 40)
val diff = DoubleArray(ours.size) { minOf(1.0, 10 * abs(ours[it] - skia[it])) }
val (worst, mean) = Gezira.error(ours, skia)
println("kernels vs Skia: max |diff| %.4f, mean %.4f".format(worst, mean))

val s = 8.0
lines(Gezira.pixels(ours, 48, s), channel = "kernels")
lines(Gezira.pixels(skia, 48, s), channel = "skia")
lines(Gezira.pixels(diff, 48, s, 1.0, 0.4, 0.3), channel = "difference x10")

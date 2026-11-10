import afield.math.distribution.*
import afield.math.gp.*

val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
val gp = gp1D(xs, ys, GpKernels.rbf(lengthScale = 1.0), noiseStd = 0.1)

val grid = DoubleArray(41) { it * 0.2 }
val f = gp.dists(grid)

fun px(x: Double) = 20 + x * 45
fun py(y: Double) = 150 - y * 70
val curves = jointSamples(*f.toTypedArray(), n = 40, seed = 3)
val drawn = (0 until 40).map { c ->
    fline {
        moveTo(px(grid[0]), py(curves[0][c]))
        for (i in 1 until grid.size) lineTo(px(grid[i]), py(curves[i][c]))
        color = vec(0.0, 0.67, 1.0, 0.3)
    }
}
val readings = xs.indices.map { i ->
    fline { circle(px(xs[i]), py(ys[i]), 4.0) }.apply { filled = true; color = vec(1.0, 0.67, 0.0, 1.0) }
}
lines(drawn + readings, channel = "curves", w = 400.0, h = 260.0)

val peakAt = argmax(f) * 0.2
println("P(tops 1.2 somewhere) = ${P(maximum(f) gt 1.2)}")
println("E(where the peak is)  = ${E(peakAt)}")
display("peak", distributionSvg(Progressive(peakAt).draw(20_000), title = "x of the curve's highest point"))

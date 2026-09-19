// The animated twin of "The two decisions inside marching squares". That box argues about a
// single ambiguous cell; this one shows why the cell matters. Two Gaussian bumps, and the
// iso level swept from 0.18 to 0.78 and back through the saddle between the peaks. Below
// the saddle there is ONE contour around both peaks, above it there are TWO, one per peak,
// and the changeover is a frame where the marcher's four corner values decide the topology.
// Contours are coloured by index, so the merge reads as a colour vanishing.
val field: ScalarField2D = { x, y ->
    exp(-((x - 1.4) * (x - 1.4) + y * y) / 1.2) + exp(-((x + 1.4) * (x + 1.4) + y * y) / 1.2)
}
val x0 = -3.2; val x1 = 3.2; val y0 = -1.9; val y1 = 1.9
val window = Rect(-3.0, -1.7, 6.0, 3.4)       // fixed, or the loops never appear to shrink
val peakDots = pointCloudFLine(
    listOf(vec(1.4, 0.0), vec(-1.4, 0.0)), color = vec(1.0, 1.0, 1.0, 0.85), pointSize = 1.4,
)
val saddleDot = pointCloudFLine(
    listOf(vec(0.0, 0.0)), color = vec(1.0, 0.72, 0.32, 0.98), pointSize = 1.9,
)
val byIndex = listOf(vec(0.42, 0.80, 1.0, 0.95), vec(1.0, 0.45, 0.62, 0.95))
val sweepSeconds = 8.0

// The saddle level is the field's own value at the col.
val saddle = field(0.0, 0.0)
println("saddle level %.3f — below it one contour, above it two".format(saddle))
val started = AnimationClock.nowSeconds()

// The line that turns elapsed time into the swept parameter, straddling the saddle.
fun isoAt(elapsed: Double): Double {
    val phase = (elapsed / sweepSeconds) % 1.0
    return 0.18 + 0.60 * (1.0 - abs(1.0 - 2.0 * phase))
}

animate {
    val iso = isoAt(AnimationClock.nowSeconds() - started)
    val contours = generateIsoContours(field, x0, x1, y0, y1, 100, 60, iso).filter { it.size >= 2 }
    val lines = contours.mapIndexed { i, c ->
        polylineFrom(c, 0, closed = isoContourClosed(c, x0, x1, y0, y1, boundaryEpsilon = 0.05)).also {
            it.color = byIndex[i % byIndex.size]
            it.thickness = 2.0
        }
    }
    showPlane(
        lines + peakDots + saddleDot,
        title = "iso %.3f — %d contour(s)".format(iso, contours.size),
        caption = if (iso > saddle) "above the saddle %.3f — one loop per peak".format(saddle)
        else "below the saddle %.3f — one loop around both".format(saddle),
        width = 560,
        height = 320,
        window = window,
    )
}

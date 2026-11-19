// A looping stroke whose loops swell and shrink, and a wobbling zigzag.
val loops =
    FLine().also { f ->
        for (i in 0..420) {
            val t = i / 420.0 * 2 * PI * 6
            val r = 45.0 * (1 + 0.3 * sin(t * 0.4))
            val (x, y) = 14.0 * t - r * sin(t) to r * cos(t)
            if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
        }
    }
val zigzag =
    FLine().also { f ->
        for (i in 0..300) {
            val (x, y) = i * 2.0 to 25.0 * sin(i * 0.35) + 10.0 * sin(i * 0.09)
            if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
        }
    }

val fit = loops.lpc(order = 8, samples = 300)
val other = zigzag.lpc(order = 8, samples = 300)

lines(
    fit.resampled().also {
        it.color = vec(1.0, 1.0, 1.0, 0.9)
        it.thickness = 1.5
    },
    fit.continued(150, includeStroke = false).also {
        it.color = vec(1.0, 0.6, 0.1, 1.0)
        it.thickness = 2.5
    },
    fit.drivenBy(other, startX = 0.0, startY = 220.0).also {
        it.color = vec(0.3, 0.8, 1.0, 1.0)
        it.thickness = 1.5
    },
    w = 900.0,
    h = 420.0,
    fitToSize = true,
)
println("residual / step energy: loops ${fit.residualRatio.f3}, zigzag ${other.residualRatio.f3}")

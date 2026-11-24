val white = vec(1.0, 1.0, 1.0, 1.0)
val blues = contrastScale(colorHex("#3b82f6"), white)
val ambers = contrastScale(colorHex("#eab308"), white)

fun bands(scale: ContrastScale, x0: Double, phase: Double): List<FLine> =
    (0 until 6)
        .map { k ->
            val top = { x: Double -> 260.0 - k * 34.0 - 26.0 * sin(x * 0.018 + phase + k * 0.5) }
            fline {
                moveTo(x0, 300.0)
                for (i in 0..100) {
                    val x = x0 + i * 5.0
                    lineTo(x, top(x))
                }
                lineTo(x0 + 500.0, 300.0)
                lineTo(x0, 300.0)
                filled = true
                color = scale.colors[k]
            }
        }
        .reversed()

fun edges(scale: ContrastScale, x0: Double, phase: Double): List<FLine> =
    (0 until 6).map { k ->
        fline {
            for (i in 0..100) {
                val x = x0 + i * 5.0
                val y = 260.0 - k * 34.0 - 26.0 * sin(x * 0.018 + phase + k * 0.5)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            stroked = true
            color = scale.colors[k + 4]
            thickness = 1.5
        }
    }

lines(
    bands(blues, 20.0, 0.0) + edges(blues, 20.0, 0.0) + bands(ambers, 560.0, 1.3) + edges(ambers, 560.0, 1.3),
    channel = "contrast-bands",
    w = 1080.0,
    h = 320.0,
    fitToSize = true,
)

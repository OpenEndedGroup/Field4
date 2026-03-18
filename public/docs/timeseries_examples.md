# Timeseries to FLine examples

Signal processing in this file is easiest to learn by drawing the raw stream and transformed stream together.

## Filter chains as layered curves

```diffstatic_runner {data-lang=kotlin screenshot="assets/timeseries_examples/filter_chains.png" wait="1.0" capture="box" open-outputs="true" padding="20" embed=true}
:::static
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.PI
import kotlin.math.sin

fun drawSeries(values: List<Float>, color: Vec4, yBias: Double = 0.0, yScale: Double = 120.0): FLine {
    val f = FLine()
    val n = values.size.coerceAtLeast(2)
    for (i in values.indices) {
        val x = i.toDouble() / (n - 1).toDouble() * 1000.0
        val y = 250.0 + yBias - values[i] * yScale
        if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
    }
    f.color = color
    f.thickness = 2.0
    return f
}

val raw = mutableListOf<Float>()
val smooth = mutableListOf<Float>()
val filtered = mutableListOf<Float>()

val ema = EMAFilter(alpha = 0.08f)
val oneEuro = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
val band = BandPassFilter(frequency = 5f, sampleRate = 120f, q = 0.8f)

for (i in 0 until 420) {
    val t = i / 120f
    val signal = (
        sin(2.0 * PI * 1.2 * t).toFloat() * 0.7f +
        sin(2.0 * PI * 4.7 * t + 0.3).toFloat() * 0.22f +
        (-0.12f + random().toFloat() * 0.24f)
    )
    raw += signal
    val s = oneEuro(ema(signal))
    smooth += s
    filtered += band(s)
}

val guides = listOf(
    FLine().apply { moveTo(0, 250); lineTo(1000, 250); color = vec(1,1,1,0.22); thickness = 1.2 },
    FLine().apply { moveTo(0, 120); lineTo(1000, 120); color = vec(1,1,1,0.08); thickness = 1.0 },
    FLine().apply { moveTo(0, 380); lineTo(1000, 380); color = vec(1,1,1,0.08); thickness = 1.0 }
)

lines(
    guides + listOf(
        drawSeries(raw, vec(1.0, 0.62, 0.36, 0.45)),
        drawSeries(smooth, vec(0.58, 0.95, 1.0, 0.95)),
        drawSeries(filtered, vec(0.55, 1.0, 0.65, 0.9), yBias = 120.0, yScale = 90.0)
    ),
    channel = "timeseries-filters",
    w = 1080.0,
    h = 560.0,
    fitToSize = true
)
```

## Distribution-aware event flags

```diffstatic_runner {data-lang=kotlin screenshot="assets/timeseries_examples/distribution_events.png" wait="1.0" capture="box" embed=true}
:::static
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.PI
import kotlin.math.sin

val gammaEvents = GammaEventDetector(windowSize = 64, tailProbabilityThreshold = 0.03f, influence = 0.15f)
val betaEvents = BetaEventDetector(windowSize = 64, supportMin = -1f, supportMax = 1f, lowerTailThreshold = 0.04f, upperTailThreshold = 0.04f)

val raw = mutableListOf<Float>()
val eg = mutableListOf<Float>()
val eb = mutableListOf<Float>()

for (i in 0 until 360) {
    val t = i / 120f
    var x = (sin(2.0 * PI * 1.1 * t) * 0.55 + (-0.08 + random() * 0.16)).toFloat()
    if (i % 83 == 0) x += 0.95f
    if (i % 127 == 0) x -= 0.8f
    raw += x
    eg += gammaEvents(kotlin.math.abs(x))
    eb += betaEvents(x)
}

fun line(values: List<Float>, y0: Double, amp: Double, color: Vec4, thickness: Double): FLine = FLine().apply {
    val n = values.size.coerceAtLeast(2)
    for (i in values.indices) {
        val x = i.toDouble() / (n - 1).toDouble() * 1000.0
        val y = y0 - values[i] * amp
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    this.color = color
    this.thickness = thickness
}

lines(
    listOf(
        line(raw, 260.0, 130.0, vec(0.86, 0.9, 1.0, 0.75), 1.8),
        line(eg, 420.0, 50.0, vec(1.0, 0.7, 0.32, 0.95), 2.2),
        line(eb, 500.0, 50.0, vec(0.56, 1.0, 0.66, 0.95), 2.2)
    ),
    channel = "timeseries-events",
    w = 1080.0,
    h = 620.0,
    fitToSize = true
)
```

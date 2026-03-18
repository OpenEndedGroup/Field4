# Correlations to FLine examples

Correlation coefficients become much easier to trust when they are rendered as moving traces.

## Rolling distance correlation and xi

```diffstatic_runner {data-lang=kotlin screenshot="assets/correlations_examples/rolling_dependence.png" wait="1.0" capture="window" open-outputs="true" embed=true}
:::static
import afield.graphics.FLine
import afield.stdlib.*
import kotlin.math.PI
import kotlin.math.sin

val rollingD = RollingDistanceCorrelation(windowSize = 84)
val rollingXi = RollingXiCorrelation(windowSize = 84)

val dVals = mutableListOf<Float>()
val xiVals = mutableListOf<Float>()

for (i in 0 until 420) {
    val t = i / 420.0
    val x = t * 2.0 - 1.0
    val y = x * x + sin(t * PI * 8.0) * 0.08 + random(-0.04, 0.04)
    dVals += (rollingD.push(x, y) ?: 0.0).toFloat()
    xiVals += (rollingXi.push(x, y) ?: 0.0).toFloat()
}

fun line(values: List<Float>, y0: Double, amp: Double, c: Vec4): FLine = FLine().apply {
    val n = values.size.coerceAtLeast(2)
    values.forEachIndexed { i, v ->
        val x = 50.0 + i.toDouble() / (n - 1).toDouble() * 980.0
        val y = y0 - v * amp
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = c
    thickness = 2.3
}

val guide = FLine().apply { moveTo(50, 500); lineTo(1030, 500); color = vec(1,1,1,0.2); thickness = 1.1 }

lines(
    listOf(
        guide,
        line(dVals, 500.0, 330.0, vec(0.56, 0.92, 1.0, 0.95)),
        line(xiVals, 500.0, 330.0, vec(1.0, 0.74, 0.35, 0.9))
    ),
    channel = "correlations-rolling",
    w = 1080.0,
    h = 640.0,
    fitToSize = true
)
```

## StatsDependence metric profile on one dataset

```diffstatic_runner {data-lang=kotlin screenshot="assets/correlations_examples/metric_profile.png" wait="1.0" capture="box" embed=true}
:::static
import afield.graphics.FLine
import afield.stdlib.*

val n = 320
val x = DoubleArray(n) { i -> i.toDouble() / (n - 1).toDouble() * 2.0 - 1.0 }
val y = DoubleArray(n) { i -> x[i] * x[i] + random(-0.05, 0.05) }

val metrics = listOf(
    "pearson" to StatsDependence.pearson(x, y),
    "spearman" to StatsDependence.spearman(x, y),
    "kendall" to StatsDependence.kendallTauB(x, y),
    "xi" to StatsDependence.xi(x, y),
    "dcor" to StatsDependence.distanceCorrelation(x, y)
)

val bars = metrics.mapIndexed { i, (_, v) ->
    val vv = v.coerceIn(-1.0, 1.0)
    val x0 = 120.0 + i * 170.0
    val x1 = x0 + 110.0
    val y0 = 520.0
    val y1 = 520.0 - vv * 340.0
    FLine().apply {
        moveTo(x0, y0)
        lineTo(x1, y0)
        lineTo(x1, y1)
        lineTo(x0, y1)
        close()
        filled = true
        stroked = true
        color = if (vv >= 0.0) vec(0.56, 0.94, 1.0, 0.6) else vec(1.0, 0.54, 0.46, 0.6)
        thickness = 1.4
    }
}

lines(bars, channel = "correlations-bars", w = 1080.0, h = 640.0, fitToSize = true)
```

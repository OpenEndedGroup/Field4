import kotlin.math.exp
import kotlin.math.sin

// One call. `showChart` builds it, puts it on screen, and hands it back so you can
// keep asking it questions — the same shape as `showTable` and `showSignals`.
val t = (0..240).map { it / 240.0 * 6.2831853 }
val chart = showChart(
    "damped sine" to t.map { exp(-it * 0.25) * sin(it * 2.0) },
    "envelope" to t.map { exp(-it * 0.25) },
    x = t,
    title = "a chart, in one call",
    xLabel = "t (radians)",
    yLabel = "amplitude",
)

// The ticks are not this box's problem, and they are not the renderer's either:
// `niceTicks` is one public copy of Heckbert's algorithm that five files each had
// privately. The plot frame it landed on is readable too.
println(chart)
println("y ticks: " + niceTicks(-1.0, 1.0, 5))
println("plot frame: " + chart.plotRect())

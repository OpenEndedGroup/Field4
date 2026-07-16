# Interpolants to FLine examples

Interpolants are for values that should move instead of snap. In animation that means smoother motion. In UI it means less mechanical transitions. In music and interaction it means gestures that settle instead of click. These examples draw that behavior.

## smoothTo for scalar envelopes

Suppose a parameter needs to move decisively without snapping: opacity, brightness, a filter cutoff, a camera move, a gesture-derived control signal. `smoothTo(...)` gives the value a controlled ramp instead of an instantaneous jump.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interpolants_examples/smooth_scalar.png" wait="1.0" capture="box"}
:::static
import afield.graphics.FLine
import afield.stdlib.NeedsStartingValue

val ramp = smoothTo<Float>(steps = 180) { 1.0f }
(ramp as NeedsStartingValue<Float>).startAt(0.0f)

val values = List(220) { ramp() }

val curve = fline {
    values.forEachIndexed { i, v ->
        val x = 40.0 + i.toDouble() / (values.size - 1).toDouble() * 980.0
        val y = 560.0 - v * 420.0
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = vec(0.55, 0.92, 1.0, 0.95)
    thickness = 2.6
}

val guides = listOf(
    fline { moveTo(40, 560); lineTo(1020, 560); color = vec(1,1,1,0.24); thickness = 1.2 },
    fline { moveTo(40, 140); lineTo(1020, 140); color = vec(1,1,1,0.14); thickness = 1.0 }
)

lines(guides + curve, channel = "interpolants-scalar", w = 1080.0, h = 640.0, fitToSize = true)
```

The code gives one interpolant a starting value, lets it chase a target, and draws the trace. The guide lines make the climb and settling behavior easier to read.

![](assets/interpolants_examples/smooth_scalar.png)

The returned shape climbs steadily, arrives, and then clamps at the target. That makes `smoothTo(...)` handy for animation parameters, fades, controller smoothing, and values that should feel less binary. To apply the same idea to live signals rather than a synthetic ramp, continue with [Timeseries examples](fline_examples.md#drawing-timeseries-as-layered-curves) and [Stats examples](stats_examples.md).

## Smoothing over steps vs. over time

Smooth can mean "over the next 140 evaluations" or "over 1.2 seconds of real time." Those are different promises: `smoothTo(steps = 140)` counts evaluations, while `smoothTo(time = 1.2, steps = null)` tracks the wall clock. The distinction matters as soon as frame rate, scheduling, or sampling cadence starts to drift — the two curves below start together and then diverge once the sampling cadence slows.

```diff_runner {data-lang=kotlin screenshot="assets/interpolants_examples/smooth_duration.png" wait="1.0" capture="box" open-outputs="true"}
:::before
import afield.graphics.FLine
import afield.stdlib.NeedsStartingValue

val q = smoothTo<Float>(steps = 140) { 1.0f }
(q as NeedsStartingValue<Float>).startAt(0.0f)
val y = List(180) { q() }

val f = fline {
    y.forEachIndexed { i, v ->
        val x = 40.0 + i.toDouble() / (y.size - 1).toDouble() * 980.0
        val yy = 560.0 - v * 420.0
        if (i == 0) moveTo(x, yy) else lineTo(x, yy)
    }
    color = vec(1.0, 0.72, 0.35, 0.95)
    thickness = 2.2
}
lines(f, channel = "interpolants-diff", w = 1080.0, h = 640.0, fitToSize = true)
:::after
import afield.graphics.FLine
import afield.stdlib.NeedsStartingValue

data class Sample(val elapsed: Double, val byStep: Float, val byTime: Float)

val byStep = smoothTo<Float>(steps = 140) { 1.0f }
(byStep as NeedsStartingValue<Float>).startAt(0.0f)

val byTime = smoothTo<Float>(time = 1.2, steps = null) { 1.0f }
(byTime as NeedsStartingValue<Float>).startAt(0.0f)

val started = System.nanoTime()
val samples = List(220) { i ->
    val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
    val sample = Sample(elapsed, byStep(), byTime())
    Thread.sleep(if (i < 150) 2L else 18L)
    sample
}

val maxTime = 1.6
val duration = 1.2
fun x(t: Double) = 40.0 + (t / maxTime).coerceIn(0.0, 1.0) * 980.0
fun y(v: Float) = 560.0 - v * 420.0

fun trace(values: List<Pair<Double, Float>>, rgba: Vec4, width: Double) = fline {
    values.forEachIndexed { i, (t, v) ->
        val xx = x(t)
        val yy = y(v)
        if (i == 0) moveTo(xx, yy) else lineTo(xx, yy)
    }
    color = rgba
    thickness = width
}

val guides = listOf(
    fline { moveTo(40, 560); lineTo(1020, 560); color = vec(1,1,1,0.22); thickness = 1.2 },
    fline { moveTo(40, 140); lineTo(1020, 140); color = vec(1,1,1,0.14); thickness = 1.0 },
    fline { moveTo(x(duration), 110); lineTo(x(duration), 580); color = vec(1,1,1,0.18); thickness = 1.0 }
)

val stepLine = trace(samples.map { it.elapsed to it.byStep }, vec(1.0, 0.72, 0.35, 0.95), 2.6)
val timeLine = trace(samples.map { it.elapsed to it.byTime }, vec(0.56, 1.0, 0.68, 0.95), 3.0)

lines(guides + stepLine + timeLine, channel = "interpolants-diff", w = 1080.0, h = 640.0, fitToSize = true)
display(
    "legend",
    "<div style='padding:10px 12px;background:#111;color:#d7d7d7;font-family:monospace'>" +
        "<span style='color:#ffb85a'>orange</span>: fixed 140 calls; " +
        "<span style='color:#8fffad'>green</span>: 1.2 seconds; " +
        "vertical guide = requested duration" +
    "</div>"
)
```

The diff isolates that choice. The first version counts calls. The second version keeps the step-count line, adds a duration-based line, and samples both under an intentionally uneven cadence. The orange trace arrives as soon as 140 calls have happened; the green trace arrives at the requested wall-clock duration.

![](assets/interpolants_examples/smooth_duration.png)

The time-based version behaves like a duration, not a loop counter. Use the step-based form when the world is discrete and counted; use the time-based form when the world is musical, animated, interactive, or otherwise measured in seconds. For more timing-oriented examples, see [Visualizers](visualizers.md) and [Timeseries examples](fline_examples.md#drawing-timeseries-as-layered-curves).

# Interpolants to FLine examples

The interpolants file is about smooth state transitions, which map naturally to drawn trajectories.

## smoothTo for scalar envelopes

```diffstatic_runner {data-lang=kotlin screenshot="assets/interpolants_examples/smooth_scalar.png" wait="1.0" capture="box" embed=true}
:::static
import afield.graphics.FLine
import afield.stdlib.*
import afield.stdlib.NeedsStartingValue

val ramp = smoothTo<Float>(steps = 180) { 1.0f }
(ramp as NeedsStartingValue<Float>).startAt(0.0f)

val values = List(220) { ramp() }

val curve = FLine().apply {
    values.forEachIndexed { i, v ->
        val x = 40.0 + i.toDouble() / (values.size - 1).toDouble() * 980.0
        val y = 560.0 - v * 420.0
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = vec(0.55, 0.92, 1.0, 0.95)
    thickness = 2.6
}

val guides = listOf(
    FLine().apply { moveTo(40, 560); lineTo(1020, 560); color = vec(1,1,1,0.24); thickness = 1.2 },
    FLine().apply { moveTo(40, 140); lineTo(1020, 140); color = vec(1,1,1,0.14); thickness = 1.0 }
)

lines(guides + curve, channel = "interpolants-scalar", w = 1080.0, h = 640.0, fitToSize = true)
```

## Diff: retime interpolation by duration

```diff_runner {data-lang=kotlin screenshot="assets/interpolants_examples/smooth_duration.png" wait="1.0" capture="window" open-outputs="true" embed=true}
:::before
import afield.graphics.FLine
import afield.stdlib.*
import afield.stdlib.NeedsStartingValue

val q = smoothTo<Float>(steps = 140) { 1.0f }
(q as NeedsStartingValue<Float>).startAt(0.0f)
val y = List(180) { q() }

val f = FLine().apply {
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
import afield.stdlib.*
import afield.stdlib.NeedsStartingValue

val q = smoothTo<Float>(time = 1.2, steps = null) { 1.0f }
(q as NeedsStartingValue<Float>).startAt(0.0f)
val y = List(220) {
    val v = q()
    Thread.sleep(6)
    v
}

val f = FLine().apply {
    y.forEachIndexed { i, v ->
        val x = 40.0 + i.toDouble() / (y.size - 1).toDouble() * 980.0
        val yy = 560.0 - v * 420.0
        if (i == 0) moveTo(x, yy) else lineTo(x, yy)
    }
    color = vec(0.56, 1.0, 0.68, 0.95)
    thickness = 2.6
}
lines(f, channel = "interpolants-diff", w = 1080.0, h = 640.0, fitToSize = true)
```

# Values over time: interpolants, keyframes & estimation

Some values are supposed to *move*: a control that eases to a target instead of
snapping, a whole parameter state morphing from one saved look to another, a
relationship between two signals drifting as they play, a hidden quantity being
estimated from noisy measurements. This page collects the tools for those cases
and does the one thing that makes them legible — it draws the motion as an
`FLine` (or, for the filters, a live Plotly trace). Four families, one idea: a
value you can *see* moving is a value you can reason about.

## Interpolants: easing a value to a target

An interpolant is a value that chases a target over a controlled number of
evaluations rather than jumping. `smoothTo(...)` is the workhorse — opacity,
brightness, a filter cutoff, a camera move, a gesture-derived control. The single
knob that shapes the approach is `steps`: fewer steps arrive sooner and sharper,
more steps settle later and softer. Drawing a *family* of them makes that shaping
obvious at a glance:

```diffstatic_runner {data-lang=kotlin screenshot="assets/interpolants_examples/smooth_family.png" wait="1.0" capture="box" open-outputs="true" name="interpolants-family"}
:::static
import afield.graphics.FLine
import afield.stdlib.NeedsStartingValue

val stepCounts = listOf(40, 90, 150, 220)
val samples = 240

fun trace(steps: Int, c: Vec4): FLine {
    val interp = smoothTo<Float>(steps = steps) { 1.0f }
    (interp as NeedsStartingValue<Float>).startAt(0.0f)
    return fline {
        for (i in 0 until samples) {
            val v = interp()
            val x = 60.0 + i.toDouble() / (samples - 1).toDouble() * 960.0
            val y = 560.0 - v * 460.0
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        color = c
        thickness = 2.4
    }
}

val palette = listOf(
    vec(0.55, 0.92, 1.0, 0.95),
    vec(0.55, 1.0, 0.75, 0.90),
    vec(1.0, 0.85, 0.40, 0.90),
    vec(1.0, 0.60, 0.40, 0.90)
)
val curves = stepCounts.mapIndexed { i, s -> trace(s, palette[i]) }

val target = fline { moveTo(60, 100); lineTo(1020, 100); color = vec(1,1,1,0.22); thickness = 1.0 }
val base   = fline { moveTo(60, 560); lineTo(1020, 560); color = vec(1,1,1,0.16); thickness = 1.0 }

lines(listOf(base, target) + curves, channel = "interpolants-family", w = 1080.0, h = 640.0, fitToSize = true)
```

Each curve chases the same target (the faint upper guide) from the same start
(the lower guide). Blue is the shortest ramp and orange the longest; every one
climbs decisively and then flattens onto the target rather than crossing it. Read
top to bottom, `steps` is a legibility control: pick the ramp whose settling time
matches the gesture.

![Four smoothTo interpolants with different step counts fanning up to a target](assets/interpolants_examples/smooth_family.png)

### Diff: retime interpolation by duration

"Smooth" can mean "over the next 140 evaluations" or "over 1.2 seconds of real
time." Those are different promises, and the difference matters the moment frame
rate, scheduling, or sampling cadence drifts. The diff below keeps a step-counted
line and adds a duration-based one, sampling both under an intentionally uneven
cadence:

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

![Step-based versus time-based smoothing under an uneven sampling cadence](assets/interpolants_examples/smooth_duration.png)

The orange trace arrives as soon as 140 calls have happened; the green trace
arrives at the requested wall-clock duration regardless of cadence. Use the
step form when the world is discrete and counted, the time form when it is
musical, animated, or otherwise measured in seconds.

## Keyframes: morphing a whole parameter state — including code

An interpolant moves one number. A `KeyframeScope` moves a whole named *state*
between saved snapshots — and its values are typed: numbers and vectors ease
continuously, quaternions slerp, and **code values** (`StackSLString`, source
blocks) switch *discretely* at the end of the transition. That mix is the point:
a patch can crossfade its continuous parameters while its actual synthesis code
snaps over at the moment the crossfade completes. Two `Key`s and `interpolate`
are all it takes; here the numeric `gain` is eased through a smoothstep `remap`,
the `pos` vector eases linearly, and the `voice` code steps:

```diffstatic_runner {data-lang=kotlin screenshot="assets/interpolants_examples/keyframe_morph.png" wait="1.0" capture="box" open-outputs="true" name="interpolants-keyframe"}
:::static
import afield.graphics.FLine
import afield.stdlib.KeyframeScope
import afield.stdlib.stackSLString
import afield.stdlib.linalg.Vector2f

val scope = KeyframeScope()
val a = scope.Key(mutableMapOf(
    "gain"  to 0.12,
    "pos"   to Vector2f(80f, 500f),
    "voice" to stackSLString("saw 110 *")
))
val b = scope.Key(mutableMapOf(
    "gain"  to 0.94,
    "pos"   to Vector2f(960f, 150f),
    "voice" to stackSLString("sin 220 * fold")
))

// Ease only the gain with a smoothstep; pos stays linear.
scope.remap["gain"] = { t -> t * t * (3.0 - 2.0 * t) }

val steps = 220
val gain = fline {
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val g = (scope.interpolate(a, b, t)["gain"] as Number).toDouble()
        val x = 60.0 + t * 960.0
        val y = 560.0 - g * 460.0
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = vec(0.55, 0.92, 1.0, 0.95)
    thickness = 2.8
}

// The position vector traces an eased path across the frame.
val path = fline {
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val p = scope.interpolate(a, b, t)["pos"] as Vector2f
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    color = vec(1.0, 0.70, 0.32, 0.55)
    thickness = 2.0
}

// The code value is discrete: it stays "a" until t reaches 1, then snaps to "b".
// Draw that as a step trace to contrast it with the smooth gain.
val code = fline {
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val isEnd = scope.interpolate(a, b, t)["voice"] == b["voice"]
        val x = 60.0 + t * 960.0
        val y = if (isEnd) 120.0 else 540.0
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = vec(0.65, 1.0, 0.55, 0.95)
    thickness = 2.2
}

lines(listOf(path, gain, code), channel = "keyframe-morph", w = 1080.0, h = 640.0, fitToSize = true)
display(
    "keys",
    "<div style='padding:10px 12px;background:#111;color:#d7d7d7;font-family:monospace'>" +
        "<span style='color:#8fe6ff'>blue</span>: gain, eased (smoothstep); " +
        "<span style='color:#ffb185'>orange</span>: pos, linear path; " +
        "<span style='color:#a6ff8c'>green</span>: voice code — one discrete switch at the end" +
    "</div>"
)
```

![A KeyframeScope morph: an eased gain curve, a linear vector path, and a code value that steps once at the end](assets/interpolants_examples/keyframe_morph.png)

The green step trace sits low for the whole transition and jumps exactly once, at
the very end — that is `StackSLString`/source-block interpolation, which never
produces a half-parsed intermediate. The blue gain is the smoothstep, the orange
path is the linear vector ease. A `Key` also knows how to `diff` against another
and emit the `update { ... }` code that reproduces it, so a captured look is both
a value you can interpolate *toward* and a snippet you can paste back into a box.

## Correlations: a relationship drawn instead of summarised

A correlation compresses a relationship into one number, which is useful and also
the problem — it can hide a nonlinear dependency or flatten timing into a
statistic that sounds more certain than it is. Two signals can clearly belong
together without a straight-line relationship; a rolling metric is the way to
watch that dependence drift over time:

```diffstatic_runner {data-lang=kotlin screenshot="assets/correlations_examples/rolling_dependence.png" wait="1.0" capture="box" open-outputs="true"}
:::static
import afield.graphics.FLine

val rollingD = RollingDistanceCorrelation(windowSize = 84)
val rollingXi = RollingXiCorrelation(windowSize = 84)

val dVals = mutableListOf<Float>()
val xiVals = mutableListOf<Float>()

for (i in 0 until 420) {
    val t = i / 420.0
    val x = t * 2.0 - 1.0
    val y = x * x + sin(t * PI * 8.0) * 0.08 + (-0.04 + random() * 0.08)
    dVals += (rollingD.push(x, y) ?: 0.0).toFloat()
    xiVals += (rollingXi.push(x, y) ?: 0.0).toFloat()
}

fun line(values: List<Float>, y0: Double, amp: Double, c: Vec4): FLine = fline {
    val n = values.size.coerceAtLeast(2)
    values.forEachIndexed { i, v ->
        val x = 50.0 + i.toDouble() / (n - 1).toDouble() * 980.0
        val y = y0 - v * amp
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    color = c
    thickness = 2.3
}

val guide = fline { moveTo(50, 500); lineTo(1030, 500); color = vec(1,1,1,0.2); thickness = 1.1 }

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

The same nonlinear stream feeds `RollingDistanceCorrelation` and
`RollingXiCorrelation`; the window stays fixed and both traces share a baseline.

![Rolling distance-correlation and xi traces on one nonlinear stream](assets/correlations_examples/rolling_dependence.png)

The two traces track the same evolving dependency without coinciding. Use this
kind of sanity check before trusting a rolling metric on sensor streams, musical
features, or exploratory data. When you instead want a fast comparison of several
metrics on one *fixed* dataset — which one notices the shape, which punishes you
for asking a linear question of a nonlinear cloud — draw them as bars:

```diffstatic_runner {data-lang=kotlin screenshot="assets/correlations_examples/metric_profile.png" wait="1.0" capture="box" open-outputs="true"}
:::static
import afield.graphics.FLine

val n = 320
val x = DoubleArray(n) { i -> i.toDouble() / (n - 1).toDouble() * 2.0 - 1.0 }
val y = DoubleArray(n) { i -> x[i] * x[i] + (-0.05 + random() * 0.10) }

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
    fline {
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

![Five dependence metrics on one nonlinear dataset, drawn as bars](assets/correlations_examples/metric_profile.png)

The metrics visibly disagree about the same sample — `pearson` shrugs at the
parabola while `xi` and `dcor` notice it. That mismatch is worth seeing before
choosing a statistic. For neighborhood and graph structure on related point sets,
see [Neighbors & graph connectivity examples](graph_connectivity_examples.md).

## Estimation: tracking a hidden value with filters

The last family estimates a value you cannot measure directly. `pf.kt` gives you
a small `UnscentedKalmanFilter` for nonlinear continuous state and a
`UKFParticleFilter` for keeping several model hypotheses alive at once, each
carrying its own UKF. These examples use Field's Plotly bridge — a `Plot` goes
straight to an output with `display(plot)`.

The smallest useful shape: one hidden point evolves through a nonlinear delayed
map, the only measurement is that point plus Gaussian error, and the UKF predicts
the motion then corrects from the scalar measurement.

```diffstatic_runner {data-lang=kotlin screenshot="assets/pf_examples/ukf_mackey_glass.png" wait="1.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.darkMode
import kotlin.random.Random
import space.kscience.plotly.*

fun diag(vararg values: Double): Matrix =
    Array(values.size) { r -> DoubleArray(values.size) { c -> if (r == c) values[r] else 0.0 } }

fun Random.gaussian(): Double {
    var u1 = nextDouble()
    while (u1 <= Double.MIN_VALUE) u1 = nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}

fun mackeyGlassStep(state: DoubleArray, beta: Double = 0.28): DoubleArray {
    val x = state[0].coerceIn(0.02, 3.0)
    val delayed = state[2].coerceIn(0.02, 3.0)
    val dx = beta * delayed / (1.0 + delayed.pow(10.0)) - 0.10 * x
    val next = (x + dx).coerceIn(0.02, 3.0)
    return doubleArrayOf(next, x, state[1])
}

val rng = Random(12)
val ukf = UnscentedKalmanFilter(
    initialState = doubleArrayOf(1.00, 0.96, 0.92),
    initialCovariance = diag(0.08, 0.08, 0.08),
    options = UKFOptions(alpha = 0.55)
)

val trueX = mutableListOf<Double>()
val measuredX = mutableListOf<Double>()
val estimatedX = mutableListOf<Double>()
var truth = doubleArrayOf(1.12, 1.05, 0.98)

repeat(360) { i ->
    val forcing = 0.020 * sin(i * 0.045)
    truth = mackeyGlassStep(truth, beta = 0.20 + forcing)
    truth[0] = (truth[0] + 0.003 * rng.gaussian()).coerceIn(0.02, 3.0)

    val measurement = truth[0] + 0.006 * rng.gaussian()

    ukf.predict(processNoise = diag(0.004, 0.004, 0.004)) { state ->
        mackeyGlassStep(state, beta = 0.20)
    }
    ukf.update(
        measurement = doubleArrayOf(measurement),
        measurementNoise = diag(0.05 * 0.05)
    ) { state ->
        doubleArrayOf(state[0])
    }

    trueX += truth[0]
    measuredX += measurement
    estimatedX += ukf.stateCopy()[0]
}

val sample = trueX.indices.toList()
val plot = Plotly.plot {
    trace {
        name = "measurement"
        x.set(sample)
        y.set(measuredX)
        line.width = 0.1
    }
    trace {
        name = "hidden x"
        x.set(sample)
        y.set(trueX)
        line.width = 0.5
    }
    trace {
        name = "UKF estimate"
        x.set(sample)
        y.set(estimatedX)
        line.width = 1.0
    }
    layout {
        title = "UKF tracking a noisy Mackey-Glass point"
        xaxis {
            title = "time step"
        }
        yaxis {
            title = "x"
        }
    }
}.darkMode()

display(plot)
```

![A UKF estimate following a noisy hidden Mackey-Glass point](assets/pf_examples/ukf_mackey_glass.png)

The measurement jitters around the hidden state while the UKF estimate follows
the nonlinear trend without chasing every sample. Note that the observation model
returns only `state[0]` while the filter still carries the delayed coordinates it
needs to predict.

When the *system itself* is uncertain, reach for `UKFParticleFilter`: each
particle carries a different Mackey-Glass `beta` hypothesis and its own UKF, the
measurements only see `x`, and the particle weights gradually favour hypotheses
whose dynamics explain the observations.

```diffstatic_runner {data-lang=kotlin screenshot="assets/pf_examples/ukf_particle_filter_mackey_glass.png" wait="1.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.darkMode
import kotlin.random.Random
import space.kscience.plotly.*
import space.kscience.plotly.models.ScatterMode

data class MackeyGlassHypothesis(val beta: Double)
data class ParticleSnapshot(
    val step: Int,
    val beta: List<Double>
)

fun diag(vararg values: Double): Matrix =
    Array(values.size) { r -> DoubleArray(values.size) { c -> if (r == c) values[r] else 0.0 } }

fun Random.gaussian(): Double {
    var u1 = nextDouble()
    while (u1 <= Double.MIN_VALUE) u1 = nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}

fun mackeyGlassStep(state: DoubleArray, beta: Double): DoubleArray {
    val x = state[0].coerceIn(0.02, 3.0)
    val delayed = state[2].coerceIn(0.02, 3.0)
    val dx = beta * delayed / (1.0 + delayed.pow(10.0)) - 0.10 * x
    val next = (x + dx).coerceIn(0.02, 3.0)
    return doubleArrayOf(next, x, state[1])
}

val rng = Random(31)
val particleCount = 96
val particles = (0 until particleCount).map { i ->
    val beta = 0.08 + 0.76 * i.toDouble() / (particleCount - 1).toDouble()
    UKFParticle(
        ukf = UnscentedKalmanFilter(
            initialState = doubleArrayOf(
                0.80 + 0.18 * rng.gaussian(),
                0.80 + 0.18 * rng.gaussian(),
                0.80 + 0.18 * rng.gaussian()
            ),
            initialCovariance = diag(0.35, 0.35, 0.35),
            options = UKFOptions(alpha = 0.55)
        ),
        hypothesis = MackeyGlassHypothesis(beta),
        logWeight = 0.0
    )
}

val pf = UKFParticleFilter<MackeyGlassHypothesis>(
    initialParticles = particles,
    copyHypothesis = { it.copy() }
)

fun betaEstimate(): Double {
    val weights = pf.weights()
    return pf.particles.indices.sumOf { i -> weights[i] * pf.particles[i].hypothesis.beta }
}

fun betaStdDev(mean: Double): Double {
    val weights = pf.weights()
    val variance = pf.particles.indices.sumOf { i ->
        val d = pf.particles[i].hypothesis.beta - mean
        weights[i] * d * d
    }
    return sqrt(variance)
}

val trueX = mutableListOf<Double>()
val measuredX = mutableListOf<Double>()
val estimatedX = mutableListOf<Double>()
val trueBeta = mutableListOf<Double>()
val estimatedBeta = mutableListOf<Double>()
val betaSigma = mutableListOf<Double>()
val particleSnapshots = mutableListOf<ParticleSnapshot>()
val snapshotSteps = setOf(0, 350, 700, 1055, 1200, 1600, 2200, 2800, 3199)

var truth = doubleArrayOf(1.10, 1.08, 1.05)
var lastMeasurement = truth[1]
var olderMeasurement = truth[2]
repeat(3200) { i ->
    val baseBeta = if (i < 1055) 0.23 else 0.64
    val actualBeta = baseBeta + 0.006 * sin(i * 0.037)

    truth = mackeyGlassStep(truth, actualBeta)
    truth[0] = (truth[0] + 0.006 * rng.gaussian()).coerceIn(0.00, 3.0)
    val measurement = truth[0] + 0.006 * rng.gaussian()

    pf.predict(
        processNoise = { diag(0.003, 0.003, 0.003) },
        transition = { hypothesis, state ->
            mackeyGlassStep(state, hypothesis.beta)
        },
        evolveHypothesis = { hypothesis ->
            val broadProposalRate = if (i in 980..1240) 0.004 else 0.00025
            val beta = if (rng.nextDouble() < broadProposalRate) {
                0.05 + 0.85 * rng.nextDouble()
            } else {
                hypothesis.beta + 0.0002 * rng.gaussian()
            }
            hypothesis.copy(beta = beta.coerceIn(0.0, 1.0))
        }
    )
    pf.update(
        measurement = doubleArrayOf(measurement, lastMeasurement, olderMeasurement),
        measurementNoise = { diag(0.010 * 0.010, 0.014 * 0.014, 0.018 * 0.018) },
        observe = { _, state -> doubleArrayOf(state[0], state[1], state[2]) }
    )

    olderMeasurement = lastMeasurement
    lastMeasurement = measurement

    val resampled = pf.resampleIfNeeded(
        thresholdFraction = 0.55,
        random = rng,
        roughening = RougheningOptions(factor = 0.012, minimumStdDev = 1e-5)
    )
    if (resampled) {
        val centerBeta = betaEstimate()
        pf.roughenHypotheses(rng) { hypothesis, random ->
            val contracted = centerBeta + 0.82 * (hypothesis.beta - centerBeta)
            hypothesis.copy(beta = (contracted + 0.0007 * random.gaussian()).coerceIn(0.0, 1.0))
        }
    }

    trueX += truth[0]
    measuredX += measurement
    estimatedX += pf.estimateMean()[0]
    trueBeta += actualBeta
    val betaMean = betaEstimate()
    estimatedBeta += betaMean
    betaSigma += betaStdDev(betaMean)
    if (i in snapshotSteps) {
        particleSnapshots += ParticleSnapshot(
            step = i,
            beta = pf.particles.map { it.hypothesis.beta }
        )
    }
}

val sample = trueX.indices.toList()

val statePlot = Plotly.plot {
    trace {
        name = "measurement"
        x.set(sample)
        y.set(measuredX)
        line.width = 0.75
    }
    trace {
        name = "hidden x"
        x.set(sample)
        y.set(trueX)
        line.width = 0.5
    }
    trace {
        name = "PF estimate"
        x.set(sample)
        y.set(estimatedX)
        line.width = 0.5
    }
}.darkMode()

val betaPlot = Plotly.plot {
    trace {
        name = "true beta"
        x.set(sample)
        y.set(trueBeta)
        line.width = 1.5
    }
    trace {
        name = "particle beta estimate"
        x.set(sample)
        y.set(estimatedBeta)
        line.width = 0.5
    }
    trace {
        name = "estimate + weighted sd"
        x.set(sample)
        y.set(estimatedBeta.indices.map { estimatedBeta[it] + betaSigma[it] })
        line.width = 0.6
    }
    trace {
        name = "estimate - weighted sd"
        x.set(sample)
        y.set(estimatedBeta.indices.map { estimatedBeta[it] - betaSigma[it] })
        line.width = 0.6
    }
}.darkMode()

val particlePlot = Plotly.plot {
    layout {
        title = "Particle beta hypotheses over time"
        xaxis {
            title = "time step"
        }
        yaxis {
            title = "beta hypothesis"
        }
    }
    particleSnapshots.forEach { snapshot ->
        scatter {
            name = "t=${snapshot.step}"
            x.set(List(snapshot.beta.size) { snapshot.step })
            y.set(snapshot.beta)
            mode = ScatterMode.markers
            marker {
                size = 4.0
                opacity = 0.28
            }
        }
    }
    trace {
        name = "true beta"
        x.set(sample)
        y.set(trueBeta)
        line.width = 2.0
    }
    trace {
        name = "weighted estimate"
        x.set(sample)
        y.set(estimatedBeta)
        line.width = 2.0
    }
}.darkMode()

display("state tracking", statePlot)
display("model estimate", betaPlot)
display("particles", particlePlot)
```

![Particle-filter panels: state tracking, beta estimate with a weighted envelope, and the particle beta cloud over time](assets/pf_examples/ukf_particle_filter_mackey_glass.png)

The state panel repeats the hidden/measurement/estimate comparison; the beta
panel shows the true `beta`, the weighted particle estimate, and a weighted
one-standard-deviation envelope; the particle panel scatters each hypothesis at
saved time steps, so darker vertical bands mean particles have clumped. Observing
the current point plus the two previous measurements pins the delayed state
coordinates, so a wrong `beta` cannot hide behind a different latent delay. The
broad-proposal rate is raised only around the known transition window, preserving
just enough diversity to catch the jump without endlessly repopulating far-away
hypotheses once the estimate has settled.

---

Four families, one habit: whether a value eases, morphs, correlates, or is
estimated, draw the motion. For raw and filtered *signals* rather than these
derived quantities, continue with [Timeseries examples](timeseries_examples.md).

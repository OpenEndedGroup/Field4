# RectSL Inverse Animation

A RectSL animation is normally read forwards: you write the tracks, and the sampler tells you where
the rectangles went. This page runs that arrow backwards. You say **where a rect should be, and
when**, and a gradient works out the numbers that put it there.

The box below asks a rect to pass through three points at three given times. Its motion is a
circle with six unknown numbers (an amplitude, a phase and a centre on each axis), and
`fitParameters` solves for them. The box draws the figure beside it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-fit-box.png" alt="an output panel labelled figure holding three stacked strips on a dark grid. The first, TARGET 3 WAYPOINTS, shows three empty white squares: upper left, upper right, and one below centre. The second, SEED WORST MISS 7.72M, adds a blurred orange blob sitting near the middle and touching none of them. The third, FITTED WORST MISS 4.7E-15M, replaces it with a trail of teal squares sweeping in an arc from the upper left, down through the bottom centre and back up to the upper right, passing through all three white squares on the way" wait="9.0" capture="box" open-outputs="true" w="1020" h="1080" padding="14" name="inverse-animation-fit-box" embed=true}
import afield.rectsl.*
import afield.stdlib.Functions
import java.util.Base64

// A clamped identity in time — RectSL's own spelling of "follow the clock".
fun clock(d: Double): Track<Double> =
    LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(d, d)))

val duration = 4.0

// amp * trig(rate*t + phase) + centre, with amp / phase / centre all unknown.
fun channel(axis: String, rate: Double, op: TrackOp1): Track<Double> =
    BinaryDoubleTrack(
        BinaryDoubleTrack(
            MappedDoubleTrack(
                BinaryDoubleTrack(
                    BinaryDoubleTrack(clock(duration), constantTrack(rate), TrackOp2.MUL),
                    FreeParamTrack("$axis-phase", 0.0),
                    TrackOp2.ADD
                ),
                op
            ),
            FreeParamTrack("$axis-amp", 1.0),
            TrackOp2.MUL
        ),
        FreeParamTrack("$axis-centre", 0.0),
        TrackOp2.ADD
    )

val seedX = channel("x", 1.1, TrackOp1.COS)
val seedY = channel("y", 1.1, TrackOp1.SIN)

// Where the rect must be, and when.
val waypoints = listOf(
    RectSLFitFigure.Marker(0.4, -6.5, 2.6),
    RectSLFitFigure.Marker(1.8, 0.5, -2.4),
    RectSLFitFigure.Marker(3.4, 6.2, 1.8)
)
val window = Functions.Domain(0.0, duration)
val fitX = seedX.fitParameters(waypoints.map { TrackFitTarget(it.time, it.x) }, window, iterations = 1500)
val fitY = seedY.fitParameters(waypoints.map { TrackFitTarget(it.time, it.y) }, window, iterations = 1500)

fun miss(x: Track<Double>, y: Track<Double>): Double =
    waypoints.maxOf { kotlin.math.hypot(x.sample(it.time) - it.x, y.sample(it.time) - it.y) }
val seedMiss = miss(seedX, seedY)
val fittedMiss = miss(fitX.fitted, fitY.fitted)

val png = RectSLFitFigure.render(
    bands = listOf(
        RectSLFitFigure.Band(
            "target", constantTrack(0.0), constantTrack(0.0),
            afield.Color4(0.86, 0.88, 0.91), note = "3 waypoints", strobe = false
        ),
        RectSLFitFigure.Band(
            "seed", seedX, seedY, afield.Color4(1.0, 0.42, 0.28),
            note = "worst miss %.2fm".format(seedMiss)
        ),
        RectSLFitFigure.Band(
            "fitted", fitX.fitted, fitY.fitted, afield.Color4(0.29, 0.83, 0.7),
            note = "worst miss %.1em".format(fittedMiss)
        )
    ),
    markers = waypoints,
    wall = WallSpec(18.0, 8.0, UnitKind.Meters),
    rectSize = Vec2d(1.5, 1.5),
    times = (0..32).map { it * duration / 32.0 }
)
display(
    "figure",
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' " +
        "style='width:100%;border-radius:5px;display:block'/>"
)

"""x: ${fitX.initialParameters} -> ${fitX.parameters}
|   residual ${fitX.initialResidual} -> ${fitX.finalResidual}
|y: ${fitY.initialParameters} -> ${fitY.parameters}
|   residual ${fitY.initialResidual} -> ${fitY.finalResidual}
|worst waypoint miss: seed $seedMiss m -> fitted $fittedMiss m""".trimMargin()
```

The top band shows where the rect was asked to go: three waypoints at three instants. The middle
band is what the starting values do: a small circle near the origin that misses every waypoint. The
bottom band is the same animation with the six numbers solved, passing through all three. The faint
rectangle is the wall. The solved arc leaves it, which is allowed, so the figure draws a little more
than the wall rather than cropping the answer.

## Declaring an unknown

In RectSL source, `free-param` turns a number into an unknown. This line moves a bar along a sweep
track, with its scale and offset left for the fitter:

```diffstatic {data-lang=rectsl}
# a bar whose height and offset are to be solved for, not authored
"sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
```

Until you fit it, a `free-param` is just its current value, so a scene with unknowns in it plays and
renders exactly as written.

From Kotlin, any track with `FreeParamTrack` leaves in it can be solved with `fitParameters`. The box
below builds the same sweep track and fits it twice, once with the defaults and once with more
iterations. The defaults are `iterations = 300` and `lr = 0.05`, and each step moves a parameter by
about `lr`, so a `bias` that has to travel from 0 to −8 cannot get there in 300 steps. The default
call stops with a residual near 0.1: much better than where it started, and still not solved. With
2000 iterations it lands on the exact answer.

!!! note "Check the residual"
    A fit that returns has not necessarily converged. Read `finalResidual` before trusting the
    parameters.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-api.png" alt="an output panel labelled fit, holding two blocks of results for the same track. At the default 300 iterations the solved gain is 3.8945617454461527 and the bias -7.738554034635415, taking the residual from 3.605551275463989 down to 0.11693799822760528. At 2000 iterations the gain is 3.999999999999997 and the bias -7.999999999999994, and the residual is 2.8951074449790722E-15. The last line prints the fitted IR with those numbers substituted in, and notes that it is the same track, solved: samplable, renderable, emittable as GLSL" wait="5.0" capture="box" open-outputs="true" w="1000" h="360" padding="14" name="inverse-animation-api" embed=true}
import afield.rectsl.*

// The Kotlin API under the RectSL word: any Track carrying FreeParamTrack leaves can be solved
// directly, without going through `#rectsl` source at all. Same track as the scene below —
// a sweep that is the identity in time, times gain, plus bias.
val track = BinaryDoubleTrack(
    BinaryDoubleTrack(
        LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(4.0, 4.0))),
        FreeParamTrack("gain", 1.0),
        TrackOp2.MUL
    ),
    FreeParamTrack("bias", 0.0),
    TrackOp2.ADD
)

val targets = listOf(TrackFitTarget(1.0, -4.0), TrackFitTarget(3.0, 4.0))

// The defaults are iterations = 300, lr = 0.05. Adam's step is about `lr` per iteration, so a
// parameter that has to travel 8 units cannot get there in 300 of them — which is visible below
// and is the one thing to know before trusting a residual from a default call.
val quick = track.fitParameters(targets)
val patient = track.fitParameters(targets, iterations = 2000)

display(
    "fit",
    "<pre style='margin:0;font-size:12.5px;line-height:1.6;color:#cfd6dd'>" +
        "                     default (300 iterations)\n" +
        "fit.parameters       ${quick.parameters}\n" +
        "fit.initialResidual  ${quick.initialResidual}\n" +
        "fit.finalResidual    ${quick.finalResidual}\n\n" +
        "                     iterations = 2000\n" +
        "fit.parameters       ${patient.parameters}\n" +
        "fit.finalResidual    ${patient.finalResidual}\n" +
        "fit.fitted           ${patient.fitted.describe()}\n" +
        "                     — the same track, solved: samplable, renderable, emittable as GLSL" +
        "</pre>"
)
"${patient.parameters}"
```

The next box does the same from RectSL source. It compiles a scene with two `free-param` numbers in
it, prints the track before and after the fit, and draws the scene as authored and as solved. The
authored band is what you get without fitting (`gain=1.0, bias=0.0`): the marker sweeps the wrong
span and misses both waypoints. The solved band lands on `gain=4.0, bias=-8.0`, the exact answer for
`t·gain + bias` through both waypoints.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-free-param.png" alt="an output panel labelled before / after, with two strips over a text block. The authored strip, in orange and headed gain 1.0, bias 0.0, worst miss 5.00M, holds a short bar that reaches the right-hand white waypoint square and leaves the left one untouched. The solved strip, in teal and headed gain 3.999999999999997, bias -7.999999999999994, worst miss 3.1E-15M, holds a long stepped bar spanning the strip and passing through both squares. Beneath them the text lists the IR before and after, the free parameters, and a residual falling from 3.605551275463989 to 2.8951074449790722E-15, with x at 1.0 and x at 3.0 landing on -4 and 4 to fifteen decimal places" wait="7.0" capture="box" open-outputs="true" w="1020" h="1240" padding="14" name="inverse-animation-free-param" embed=true}
import afield.rectsl.*
import afield.stdlib.Functions
import java.util.Base64

// `free-param` is the RectSL word for a number you would rather have solved for. Every
// backend still reads it as the constant it presently is, so the scene plays as authored.
val program = RectAnimationOracle.compile(
    """
    #rectsl
    ->wall 18 8 meters
    ->duration 4
    ->fps 30

    linear-track "sweep" '( 0=0   4=4 )

    "marker" rect
      0 0 ->xy
      1.4 1.4 ->wh
      "sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
      0x44d4b4 solid ->fill
    ;
    """.trimIndent()
).programOrNull!!

val node = program.nodes.filterIsInstance<RectNode>().single()
val x = node.x

// The IR prints as its own algebra now, not as nested constructors.
val before = x.describe()

// Two waypoints, two unknowns: solve exactly. The sweep is the identity in time, so asking for
// -4 at t=1 and +4 at t=3 has the closed-form answer gain=4, bias=-8 — which is the point: the
// gradient is being checked against arithmetic, not against its own previous output.
val targets = listOf(TrackFitTarget(1.0, -4.0), TrackFitTarget(3.0, 4.0))
val fit = x.fitParameters(targets, Functions.Domain(0.0, 4.0), iterations = 2000)

// The same fit as motion. Same renderer as the figure at the top of this page, two bands instead
// of three: the scene as authored, and the scene solved.
// `->x` is the only channel with unknowns in it, so y is the node's own track in both bands.
fun missOf(track: Track<Double>): Double =
    targets.maxOf { kotlin.math.abs(track.sample(it.time) - it.value) }

val png = RectSLFitFigure.render(
    bands = listOf(
        RectSLFitFigure.Band(
            "authored", x, node.y, afield.Color4(1.0, 0.42, 0.28),
            note = "${x.freeParameters()} — worst miss %.2fm".format(missOf(x))
        ),
        RectSLFitFigure.Band(
            "solved", fit.fitted, node.y, afield.Color4(0.29, 0.83, 0.7),
            note = "${fit.parameters} — worst miss %.1em".format(missOf(fit.fitted))
        )
    ),
    markers = targets.map { RectSLFitFigure.Marker(it.time, it.value, 0.0) },
    wall = WallSpec(18.0, 8.0, UnitKind.Meters),
    rectSize = Vec2d(1.4, 1.4),
    times = (0..32).map { it * 4.0 / 32.0 }
)
val report = """IR before:   $before
|free params: ${x.freeParameters()}
|solved:      ${fit.parameters}
|IR after:    ${fit.fitted.describe()}
|residual:    ${fit.initialResidual} -> ${fit.finalResidual}
|x(1.0) = ${fit.fitted.sample(1.0)}   x(3.0) = ${fit.fitted.sample(3.0)}""".trimMargin()

// Both halves go in ONE output channel. Returned as a bare String the report is a
// 377-character value and the inline viewer collapses it to "+277 chars" — true, and useless to
// read — while a second `display` channel would arrive collapsed, since only one pane opens.
display(
    "before / after",
    "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' " +
        "style='width:100%;border-radius:5px;display:block'/>" +
        "<pre style='margin:10px 0 0;font-size:12.5px;line-height:1.6;white-space:pre-wrap;" +
        "color:#cfd6dd'>" + report.replace("&", "&amp;").replace("<", "&lt;") + "</pre>"
)
"solved: ${fit.parameters}"
```

Use `fitParameters` on a track, not `fitGradient`. `fitGradient` moves every number in the graph,
including key times, clamp bounds and easing constants, and moving those breaks the track.
`fitParameters` moves only the named `free-param`s.

## Unknowns inside keys

A key's value can be a `free-param` too, so a fit can change a keyed track and not only the
arithmetic around it. Here the top of a ramp is the unknown:

```diffstatic {data-lang=text}
'( 0=0  2=4 free-param peak ) linear
```

A key value can be any track that does not change with time: a constant, a `free-param`, or
arithmetic over them. A key's *time* cannot be a `free-param`; only its value can.

## Open it

The two Kotlin boxes from this page are on the welcome screen's starting points, as RectSL inverse
animation. The first box solves the six-parameter arc at the top of the page; the second fits the `free-param` scene from
RectSL source.

## How the fit works

The rest of this page explains how a track becomes something a gradient can be taken of, and what
cannot be fitted. You do not need it to use `free-param`.

### The three backends

A RectSL track is built from named operations rather than Kotlin closures, so it can be printed,
compared, rewritten and turned into GLSL. Each operation has three implementations:

| column | what it is | where it runs |
| --- | --- | --- |
| `eval:` | `(Double) -> Double` | the CPU sampler |
| `glsl:` | a shader expression | [the SDF backend](rectsl_cheatsheet.md) |
| `fn:` | an `afield.stdlib.Functions.Fn` transform | [the exact gradient](autodiff_examples.md) |

`Fn` is Field's own function type, and Field can differentiate an `Fn` exactly. Turning a track into
an `Fn` (lowering it) is what lets a fit take its gradient.

### Two identities

`Fn` lacks two things a track needs: applying a function to another function's value, and a
piecewise definition. An identity supplies each.

**`Fn` has no "apply a unary function to another `Fn`'s value" op.** Its differentiable value
transforms are `flip`, `abs`, `exp`, `log`, `pow`, `clamp`, `scaleY`, `offsetY` and pointwise
arithmetic. There is no `sin`: `Functions.sin` is an *oscillator* over the sample position,
`offset + amplitude·sin(2π·frequency·x + phase)`, which is not a composition. But its `phase` child
is evaluated at x, so a zero-frequency oscillator is a composition. The box below checks that
`sin(frequency = 0, phase = u)` matches `sin(u(x))` across a range of x:

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-sin-identity.png" alt="an output panel labelled identity, comparing sin with frequency 0, phase u, amplitude 1 and offset 0 against a plain sin of u of x. It reports 4001 samples over x in the range -2 to 2, a worst difference of 0.0, and 0 samples not bit-equal" wait="4.0" capture="box" open-outputs="true" w="1000" h="330" padding="14" name="inverse-animation-sin-identity" embed=true}
import afield.stdlib.Functions

// `Fn` has no "apply sin to another Fn" op. But `sin`'s `phase` child is evaluated at x, so a
// zero-frequency oscillator *is* composition: 0 + 1·sin(2π·0·x + u) collapses term by term.
// This box is that claim, checked, rather than that claim, asserted.
val (composed, u) = with(Functions) {
    val u = identity(Functions.Domain.ALL)                     // any inner function will do; u(x) = x
    sin(
        frequency = 0.0.c, phase = u, amplitude = 1.0.c, offset = 0.0.c,
        domain = Functions.Domain.ALL
    ) to u
}

val xs = (0..4000).map { -2.0 + it * 4.0 / 4000.0 }
val worst = xs.maxOf { x -> kotlin.math.abs(composed.eval(x) - kotlin.math.sin(u.eval(x))) }
val notBitEqual = xs.count { x -> composed.eval(x) != kotlin.math.sin(u.eval(x)) }

display(
    "identity",
    "<pre style='margin:0;font-size:12.5px;line-height:1.6;color:#cfd6dd'>" +
        "sin(frequency = 0, phase = u, amplitude = 1, offset = 0)   vs   sin(u(x))\n" +
        "samples:               ${xs.size} over x in [-2, 2]\n" +
        "worst difference:      $worst\n" +
        "samples not bit-equal: $notBitEqual" + "</pre>"
)
"worst $worst over ${xs.size} samples"
```

The two agree exactly, because `0 + 1·sin(2π·0·x + u)` involves no rounding. This identity covers
the trigonometric functions, and with them most of the Penner easings.

**`Fn` has no piecewise-in-x op, and every keyframed track is piecewise.** A sum of clamped ramps
stands in for it. For keys `(tᵢ, vᵢ)` and any segment shape `Sᵢ` with `Sᵢ(0) = vᵢ` and `Sᵢ(1) = vᵢ₊₁`,

```diffstatic {data-lang=text}
v(t)  =  v₀ + Σᵢ [ Sᵢ(clamp01((t - tᵢ) / (tᵢ₊₁ - tᵢ))) - vᵢ ]
```

is *identically* the clamped piecewise interpolant. Inside span *j* every earlier term is
`vᵢ₊₁ - vᵢ` and every later one is zero, so the sum telescopes to `vⱼ + (vⱼ₊₁ - vⱼ)·Sⱼ(u)`; before
the first key everything is 0 and after the last everything is 1, which is exactly what the sampler's
clamping does. It needs only clamp, divide, multiply and add, so linear, eased and TCB tracks all
lower, and so do the `*-in-out` easings, which are piecewise but continuous.

### Hard edges

Gates, holds and looped clocks all jump, and they lower through `Fn.floor`. The derivative of
`floor` is zero wherever it exists, so a parameter that reaches the output only through a `floor`
cannot be fitted. Used inside a larger expression, though, it gives an exact step. The box below
checks `clamp01(floor(x) + 1)` against the ideal step, including at the boundary:

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-exact-step.png" alt="an output panel labelled exact step, comparing clamp01 of floor of x plus 1 against the ideal step that is 1 where x is at least 0 and 0 below. Over 8009 samples, a grid across -4 to 4 plus the integer boundaries themselves, it reports 0 disagreements, and confirms the boundary by hand: step at 0.0 is 1.0 and step at minus 1e-16 is 0.0" wait="4.0" capture="box" open-outputs="true" w="1000" h="360" padding="14" name="inverse-animation-exact-step" embed=true}
import afield.stdlib.Functions

// clamp01(floor(x) + 1) is claimed to be an EXACT step: 1 where x >= 0, 0 below it, for every x.
// The check below includes x = 0 and the negative integers, which are the only places a
// floor-based step can get the boundary wrong.
val step = with(Functions) { identity(Functions.Domain.ALL).floor().offsetY(1.0).clamp(0.0, 1.0) }

val grid = (-4000..4000).map { it / 1000.0 }
val edges = listOf(-3.0, -2.0, -1.0, -1e-16, 0.0, 1e-16, 1.0, 2.0)
val xs = grid + edges

val wrong = xs.filter { x -> step.eval(x) != (if (x >= 0.0) 1.0 else 0.0) }
val atZero = step.eval(0.0)
val justBelow = step.eval(-1e-16)

display(
    "exact step",
    "<pre style='margin:0;font-size:12.5px;line-height:1.6;color:#cfd6dd'>" +
        "clamp01(floor(x) + 1)  vs  1 where x >= 0, 0 below\n" +
        "samples:           ${xs.size} (grid over [-4, 4] plus the integer boundaries)\n" +
        "disagreements:     ${wrong.size}${if (wrong.isEmpty()) "" else " at " + wrong.take(6)}\n" +
        "step(0.0):         $atZero\n" +
        "step(-1e-16):      $justBelow" + "</pre>"
)
"${wrong.size} disagreements over ${xs.size} samples"
```

The same step handles the `expo-*` and `elastic-*` easings, whose formulas do not quite reach their
own endpoints (`expo-in` is 2⁻¹⁰ at u=0, not 0):
`closed(u)·(1−floor(u))·(1−floor(1−u)) + floor(u)`.

`->face-travel` builds a `HeadingTrack`, the `atan2` of a central finite difference, so `Fn` also
has `atan`, `atan2` and `hypot`. A heading agrees with the sampler less closely than other tracks,
because differencing two nearby positions magnifies their rounding error.

### Refusals

`Track.toFn()` never guesses. A track it cannot lower comes back as
`RectSLFnLowering.Refused(why, closable, missing)`. `closable` tells you whether a lowering is
possible but not written yet, or impossible; for a closable refusal, `missing` says what it would
take.

| refused | closable? | why |
| --- | --- | --- |
| `mod` | yes | Kotlin's `%` on doubles is the exact remainder, and rebuilding it from `floor` gives a different answer for very large quotients. A `mod` of two constants still works. |
| `bounce-*` easings | yes | possible with the clamped-ramp sum, not written yet. |
| the pattern tracks | no | a pattern is interpreted, not an expression of fixed shape. |

Division by a varying value and `smoothstep` with varying edges do lower, with a guard against
dividing by zero.

`fitParameters` refuses in the same way. A parameter that reaches the output only through a `floor`,
a gate or a hold has a zero derivative, and `fitParameters` names it instead of returning a residual
that never moved. A parameter alongside a gate, rather than under it, fits normally.

Every lowered track is tested against the sampler, and the gradient against finite differences, so a
poor residual means a poor fit and not a lowering error.

### Printing, comparing and simplifying tracks

Because the track IR is data, a few other things come with it:

- `Track.describe()` prints a track as algebra, for example
  `ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)`.
- Two tracks built the same way are `==`.
- `Track.simplify()` shrinks a track, mostly by folding constants, and `substituteParameters`
  replaces free parameters with values, as a fit does.

`simplify()` only makes rewrites that sample identically, so it leaves `x + 0`, `0 - x` and
`mix(a, b, 1)` alone: in floating point they can differ from `x`, `-x` and `b` in the sign of a zero
or the last bit, and a signed zero can turn a `->face-travel` rect half a turn.

??? aside "The opening figure is a CPU raster, not a GPU capture"

    Its rectangles are sampled from the tracks at the instants shown, so they are the `eval:`
    column's answer and not the shader's. Measurements of the lowering against the gallery are in
    `dev_docs/RECTSL_FN_BACKEND_MEASUREMENTS.md`.

### Keyed tracks

A key's value is itself a track, so it can be a free parameter too. TCB tracks recompute their
tangents from the fitted key values, so a moved key bends the spline the way the sampler would.

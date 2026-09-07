# RectSL Inverse Animation

A RectSL animation is normally read forwards: you write the tracks, and the sampler tells you where
the rectangles went. This page runs that arrow backwards. You say **where a rect should be, and
when**, and a gradient works out the numbers that put it there.

The figure below comes out of the box beside it, captions and all.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-fit-box.png" wait="9.0" capture="box" open-outputs="true" w="1020" h="1080" padding="14" name="inverse-animation-fit-box" embed=true}
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

Three bands, one image. The top band is where the rect was *asked* to go: three waypoints, three
instants. The middle band is what the seed parameters actually do — a small, badly-phased circle near
the origin that misses every waypoint, worst miss 7.72 wall metres. The bottom band is the same
animation with the six numbers solved, threading all three waypoints to within 5·10⁻¹⁵ metres. The
faint rectangle is the wall, and the solved arc leaves it, which is allowed: the figure shows a
fifth more than the wall rather than cropping the answer.

## The three backends

RectSL's track IR is **symbolic**: every scalar op in it names itself rather than hiding in a Kotlin
closure. A closure cannot be printed, compared, serialized, rewritten, or emitted as GLSL; a named op
can be all five. Each one owns three columns:

| column | what it is | where it runs |
| --- | --- | --- |
| `eval:` | `(Double) -> Double` | the CPU sampler |
| `glsl:` | a shader expression | [the SDF backend](rectsl_cheatsheet.md) |
| `fn:` | an `afield.stdlib.Functions.Fn` transform | [the exact gradient](autodiff_examples.md) |

The third column does not change *where* RectSL runs; it changes what RectSL can do. `Fn` is Field's
own expression type, and `autodiff_fn.kt` differentiates an `Fn` graph exactly with respect to its
numeric leaves. A track that lowers to an `Fn` is a track you can take the derivative of, and
therefore a track you can **solve**.

## Two identities make it possible

`Fn` was not designed as a general expression language, and two of its gaps look fatal. Each one
closes on an identity.

**`Fn` has no "apply a unary function to another `Fn`'s value" op.** Its differentiable value
transforms are `flip`, `abs`, `exp`, `log`, `pow`, `clamp`, `scaleY`, `offsetY` and pointwise
arithmetic. There is no `sin`: `Functions.sin` is an *oscillator* over the sample position,
`offset + amplitude·sin(2π·frequency·x + phase)`, which is not a composition. But its `phase` child
is evaluated **at x**, so

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-sin-identity.png" wait="4.0" capture="box" open-outputs="true" w="1000" h="330" padding="14" name="inverse-animation-sin-identity" embed=true}
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

The agreement is exact rather than close: `0 + 1·sin(2π·0·x + u)` collapses term by term with no
rounding at all, so the box reports a worst difference of zero and no sample that differs even in its
last bit. That one identity carries the whole trigonometric family, and with it most of the Penner
easings.

**`Fn` has no piecewise-in-x op, and every keyframed track is piecewise.** A clamped-ramp sum fixes
it. For keys `(tᵢ, vᵢ)` and any segment shape `Sᵢ` with `Sᵢ(0) = vᵢ` and `Sᵢ(1) = vᵢ₊₁`,

```diffstatic {data-lang=text}
v(t)  =  v₀ + Σᵢ [ Sᵢ(clamp01((t - tᵢ) / (tᵢ₊₁ - tᵢ))) - vᵢ ]
```

is *identically* the clamped piecewise interpolant. Inside span *j* every earlier term is
`vᵢ₊₁ - vᵢ` and every later one is zero, so the sum telescopes to `vⱼ + (vⱼ₊₁ - vⱼ)·Sⱼ(u)`; before
the first key everything is 0 and after the last everything is 1, which is exactly what the sampler's
clamping does. It needs only clamp / divide / multiply / add, so linear, eased and TCB tracks all
lower. So do the `*-in-out` easings, which are piecewise but continuous at the halfway point, and
continuity is all the sum asks of them.

## Declaring an unknown

```diffstatic {data-lang=rectsl}
# a bar whose height and offset are to be solved for, not authored
"sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
```

`free-param` marks a number you are willing to have chosen for you. Every backend still reads it as
the constant it presently is, so a scene with free parameters plays exactly as written and renders
exactly as written; only the differentiable backend treats it specially, labelling its `Fn` leaf so a
fitter can find which θ slot it landed in.

The box below runs the same fit twice, once on the defaults and once with room to converge.
`fitParameters` defaults to `iterations = 300` at `lr = 0.05`, and Adam moves a parameter by roughly
`lr` per step, so a `bias` that has to travel from 0 to −8 cannot arrive in 300 steps. The default
call stops short at a residual near 0.1 — a 97% reduction, enough to pass a "did it improve?" check
and nowhere near solved. Given 2000 iterations the same call lands on the closed-form answer. **Read
the residual, not the fact that a fit returned.**

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-api.png" wait="5.0" capture="box" open-outputs="true" w="1000" h="360" padding="14" name="inverse-animation-api" embed=true}
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

The next box runs the whole loop from RectSL source: a scene with two `free-param` numbers in it, the
IR printed as its own algebra before and after, and the scene drawn **as authored and as solved** by
the same renderer as the figure at the top of this page — two bands there instead of three. The
authored band is the animation you get if you never fit anything (`gain=1.0, bias=0.0`), with the
marker sweeping the wrong span and missing both waypoints. The solved band is the same scene after
the gradient has chosen for you, and it lands on `gain=4.0, bias=-8.0`, which is the analytic answer
to `t·gain + bias` through both waypoints.

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-free-param.png" wait="7.0" capture="box" open-outputs="true" w="1020" h="1240" padding="14" name="inverse-animation-free-param" embed=true}
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

// The numbers are the point, but they are easier to believe as motion. Same renderer as the figure
// at the top of this page, two bands instead of three: the scene as authored, and the scene solved.
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

`fitParameters` is not `fitGradient`, and the difference matters. `fitGradient` moves **every**
numeric leaf, which is right for a whole graph and wrong for a lowered track: most of its leaves are
not parameters at all but key times, clamp bounds, the `0.5` in an easing, the `-1.0` that mirrors a
reversed clock. Left to descend freely, Adam walks the `0.0` and the `1.0` of a keyed track's
`clamp01` past each other and `coerceIn` throws. So the loss and the Adam update are `fitGradient`'s
shape over a **mask**: only the named parameters ever move. Everything under the mask is borrowed.
The gradient is `Fn.gradientAt`, one backward sweep through the same `FnFormulas` table `Fn.eval` is
pinned against.

## One primitive, and every hard edge in the language

Five different things could stop a track from lowering, and four of them come down to a single
primitive: `Fn.floor`, a first-class node that is also differentiable. The derivative of a staircase
is zero everywhere it exists, and zero is what `floor` reports. On its own that makes it a dead end,
exactly as `sign` is. Inside something else it is the opposite, and the reason is one line of
arithmetic:

```diffstatic_runner {data-lang=kotlin screenshot="assets/rectsl/inverse-animation-exact-step.png" wait="4.0" capture="box" open-outputs="true" w="1000" h="360" padding="14" name="inverse-animation-exact-step" embed=true}
import afield.stdlib.Functions

// clamp01(floor(x) + 1) is claimed to be an EXACT step: 1 where x >= 0, 0 below it, for every x.
// "Exact" is a strong word, so it is checked here rather than asserted — including at x = 0 and at
// the negative integers, which are the only places a floor-based step can get the boundary wrong.
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

That is an exact step function, and a step is *every hard edge in an animation language*. RectSL has
three of them: a visibility gate, a hold track, and the wrap of a looped clock. All three lower
through that one expression, and so does a fourth case. The `expo-*` and `elastic-*` easings have
closed forms that do not reach their own endpoints (`expo-in` is 2⁻¹⁰ at u=0, not 0), and in a keyed
track they spend most of their time exactly at those endpoints, so the branch has to be arithmetic
too: `closed(u)·(1−floor(u))·(1−floor(1−u)) + floor(u)`.

That is what takes the easings that lower from 29 of 38 to 35 of 38. The fifth obstruction is a
single construction: `->face-travel` builds a `HeadingTrack` — `atan2` of a central finite
difference — and it accounts for every non-pattern track in the gallery corpus that `floor` alone
cannot reach. `Fn` therefore carries `atan`, `atan2` and `hypot`, with the near-zero guards on `/`
and `smoothstep` alongside them.

Two things about a heading are easy to misread. Its worst disagreement with the sampler is two orders
of magnitude larger than anything else on this page, because a heading *differences* two positions a
240th of a second apart: a one-ulp error in a position is a ~10⁻¹⁴ error in the angle. That is
amplification rather than a fresh approximation, and a heading over an unnested trajectory is
bit-identical at all 4001 samples of a winding circle. And `atan2` is a **primitive** here rather
than `atan(y/x)` with a quadrant fix-up, because a fix-up divides first: `-0.0 / -1.0` is `+0.0`, so
the answer comes out `+pi` where the truth is `-pi`. Half a turn, from two inputs that compare equal.

## What is still refused, and whether that is permanent

A wrong derivative is worse than no derivative, so the lowering never guesses. `Track.toFn()` returns
`RectSLFnLowering.Refused(why, closable, missing)` rather than a null or a plausible-looking zero.
`closable` is borrowed from the stdlib's own `FnOpStatus`, and it separates two facts a reader has to
be able to tell apart: **not yet written**, and **no correct lowering exists**.

| refused | closable? | why, and what it would take |
| --- | --- | --- |
| `mod` | yes | not for want of a sign primitive — `trunc(z) = s·floor(z) − (1−s)·floor(−z)` with `s` the exact step is exact at every double. What defeats it is that Kotlin's `%` on doubles is the *exact* remainder, and `a − b·trunc(a/b)` is a different function once the quotient outgrows 53 bits: `1e17 % 3` is `1.0` and the reconstruction says `0.0`, silently, *as a zero* — indistinguishable from the near-zero guard having fired. A constant pair still folds. |
| `bounce-*` easings | yes | four parabolic arcs, each join needing its own clamped ramp. Expressible with the machinery above; not yet expressed. |
| the pattern tracks | **no** | an interpreter over a mini-notation tree, not an expression of fixed shape — the same reason `FnNonDifferentiable` refuses `pat` itself. |

Division by a non-constant is not on that table, and neither is `smoothstep` with non-constant edges,
because one identity covers both: guard the *divisor*, not the result. Masking the result
(`[|b| > 1e-12] · (a/b)`) still divides on the suppressed branch, so `0·inf` is NaN. `b·live + (1 −
live)`, on the other hand, is `b` where the guard passes and exactly `1.0` where it does not, so no
infinity is ever created to be masked. Adding `−0.0` then repairs the sign of the suppressed zero,
`−0.0` being the true additive identity on doubles where `+0.0` is not. Without it a negative
numerator lowers to `−0.0` where the sampler says `0.0`, and a signed zero is enough to spin a
`->face-travel` rect through half a turn.

A row leaves that table by being tried, not by being argued with: `missing` names what a lowering
would take, and the way to close it is to write the lowering and compare it against the sampler.

The fitter has a matching refusal. A staircase's derivative really is zero, so a parameter reachable
*only* through a `floor`, a gate or a hold has no path to the value at all — present in the graph and
unsolvable. `fitParameters` refuses that by name instead of printing a residual that never moved and
calling it convergence. A parameter *beside* a staircase is still fine: under a floor it is refused,
beside a gate it solves from residual 3.0 to 6.2·10⁻¹⁵.

## How much of it is checked

The differentiable backend is checked against an oracle that shares **no code** with it: a full
double-precision capture of every sampled rect of the gallery's 38 programs, at 41 times each. The
backend recomputes each rect's nine numeric channels and is held to that capture:

<div class="field-data-table" markdown>

| measurement | value |
| --- | --- |
| gallery tracks that lower | **4993 of 5020**, with all 27 refusals pattern tracks |
| channel values compared | **578,059 of 580,068**, across all 37 compilable programs |
| worst relative disagreement | **7.7·10⁻¹⁴** (a `cascade-split` rotation; see the amplification note above) |
| worst absolute disagreement | **8.3·10⁻¹⁴** |
| easings lowered | 35 of 38, worst disagreement 8.9·10⁻¹⁶ over a 1001-point grid |
| exact gradient vs central difference | worst **7.1·10⁻¹¹** over twenty-one parameterised shapes; **6.8·10⁻⁹** for a heading |
| the exact step, at its own boundary | exact, including at `-0.0` |
| a looped clock across 4.5 periods | **bit-identical** (0.0); ping-pong 8.9·10⁻¹⁶; a hold track 5.6·10⁻¹⁷ |
| a heading round a winding circle | **bit-identical at 4001 of 4001 samples**, across two branch-cut crossings |
| a heading whose velocity vanishes | **bit-identical at 801 of 801**, with 400 of them needing a wider probe than the first |
| the guarded `/` and `smoothstep` | **bit-identical at 2001 of 2001 each**, including the sample where the guard fires |

</div>

The first row is asserted rather than reported, and what it asserts is a classification rather than a
count. A pattern track is the only thing that may refuse; refused and pattern-built have to be the
same set, checked in both directions from the track's own structure; and that set has to be
non-empty, since a predicate matching nothing would pass the other two vacuously. A refusal for any
other reason fails the build, and so does a `pat` the backend claims to have lowered.

The last few rows are the ones that matter for a fit. Agreeing on values proves nothing about a
derivative, because a second interpreter can reproduce `eval` everywhere and still differentiate the
wrong function. So the gradient is checked against a central finite difference — its only oracle
that does not involve trusting the gradient. 7.1·10⁻¹¹ is the finite difference's own truncation
error at a 1e-6 relative step, not the gradient's.

The figure at the top of this page is a CPU raster rather than a GPU capture. Its rectangles are
sampled from the tracks at the instants shown, but they are not the shader's own answer.

??? aside "Where the oracle is, and how good the two halves of it are"
    The golden is a 64,490-line characterization corpus and `RectSLFnBackendTest` reads it as data.
    Its first 37,057 lines were generated from pre-refactor code, so for those rows the oracle came
    out of a generator nobody can run today. The remaining rows cover the four musicality examples
    and come from the current sampler, which shares no code path with the symbolic IR either but is
    not independent of a sampler bug. Read which half a disagreement lands in before deciding what
    it proves. There is one further transcription in the arrangement: the differential test has to
    compose group and rect transforms somewhere, and the `Fn` backend is scalar-only, so that
    composition is copied from `SceneProgram.sample`. A companion test runs the same copy with the
    *original* tracks and requires bit-equality with the golden across all 580,068 channel values,
    which is what stops the copy from rotting.

## The rest of what a symbolic IR is good for

Of the five things a closure cannot be (printed, compared, serialized, rewritten, emitted as GLSL),
GLSL and this page's differentiable backend are the two big ones. The remaining three come almost free
once the IR is data.

- `Track.describe()` prints a tree as its algebra —
  `ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)` — rather than as nested constructors.
- Comparison is free. The IR is data classes, so two tracks built by the same `array` body are
  already `==`, which is what the GLSL emitter's function deduplication runs on.
- `Track.simplify()` is the general rewriter, and `substituteParameters` the targeted one a fit uses.

The simplifier is most interesting where it *refuses*. Its contract is that a rewritten track samples
**bit-identically**, and the obvious reflexive rules do not clear that bar. `0 - x` is not `-x`,
because `0.0 - 0.0` is `+0.0` while `-(0.0)` is `-0.0`, and a signed zero is observable through the
`atan2` in a `HeadingTrack`, so a rewrite that "only" changes the sign of a zero can spin a rectangle
through 180°. Five reflexive rewrites are therefore refused, and asserted as refused: `x + 0`,
`0 - x`, and all three `mix` degeneracies (`mix(a, b, 1)` is `a + (b - a)`, which is simply not `b`
in floating point). What survives still shrinks the tree: over a 2754-channel snapshot of the
gallery, `RectSLSimplify` records 10,621 nodes down to 8865 — 16.5% smaller — because the constant
fold does the heavy lifting.

## Keys are tracks too

`TcbKey` holds a `Track<Double>` rather than a `Double`, so a key *value* can be a free parameter and
a fit can reach *inside* a keyed track rather than only composing around one. Written in RectSL, the
top of a ramp is the unknown:

```diffstatic {data-lang=text}
'( 0=0  2=4 free-param peak ) linear
```

Any time-invariant track will do (a constant, a `free-param`, or arithmetic over them), and that is
checked when the key is built, because a key whose value varied with time would have no answer to
"the value *at* this key". Every CPU sampler and the shader read only the number the key currently
stands at, so a scene with an unknown in it plays and renders exactly as written; the differentiable
backend is the only thing that looks at the value's tree, where a `FreeParamTrack` arrives as a
*named* leaf and the gradient with respect to it falls out of the same backward sweep as everything
else.

For linear, eased and hold tracks that is exact by construction: the lowering is
`v₀ + Σ shape(u)·(vᵢ₊₁ - vᵢ)`, which is linear in the key values, so the clamped-ramp sum above
carries them unchanged.

TCB takes more care. A TCB tangent is *derived* from the neighbouring key values, so **baking** it —
which is what the constant-key lowering and the shader both do — stops being faithful the moment a
key can move: a fit would solve for the key, `fitted` would recompute its tangents from the solved
value, `fittedFn` would still carry the seed ones, and one fit would have two answers. A fitter that
returns two animations for one solve is worse than one that refuses, so a TCB track with a
parameterised key lowers its **tangents symbolically too**, and a moved key moves its tangents the
way the sampler says it should. That is a second transcription of the TCB formula, which is exactly
the kind of thing that drifts, so the two paths are pinned against each other on constant keys where
both are defined. On a four-key spline with per-key tension, continuity and bias they agree to the
last bit across 401 instants, and the fitted graph and the fitted track differ by 2·10⁻¹⁶ rather
than by a spline.

What is still a plain `Double` is a key's *time*. A keyed track sorts by time and selects a span from
it, so a parameterised time would have to move the *structure* of the track rather than only its
numbers — a different kind of change from this one, and one nobody has made.

## See it moving

Replay `dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` through the agent backdoor, or
open it from the welcome screen's starting points. Box one solves the six-parameter arc above and
draws the three-band figure into its own output; box two does the same from RectSL source using
`free-param`, and prints the track IR as its own algebra before and after the solve.

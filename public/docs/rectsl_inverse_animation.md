# RectSL Inverse Animation

A RectSL animation is normally read forwards: you write the tracks, and the sampler tells you where
the rectangles went. This page is about running that arrow backwards — saying **where a rect should
be, and when**, and letting a gradient work out the numbers that put it there.

The figure below is not an illustration of that idea; it is the idea, run. Every number in its captions
was produced by the code beside it — nothing here was drawn by hand.

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

Three bands, one image. The top band is where the rect was *asked* to go — three waypoints, three
instants. The middle band is what the seed parameters actually do: a small, badly-phased circle near
the origin that misses every waypoint, worst miss 7.72 wall metres. The bottom band is the same
animation with the six numbers solved, threading all three waypoints to within 5·10⁻¹⁵ metres. The
faint rectangle is the wall; the solved arc leaves it, which is allowed and is why the figure shows a
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

The third one is the interesting one, because it does not change *where* RectSL runs — it changes
what RectSL can do. `Fn` is Field's own expression type, and `autodiff_fn.kt` differentiates an `Fn`
graph exactly with respect to its numeric leaves. A track that lowers to an `Fn` is a track you can
take the derivative of, and therefore a track you can **solve**.

## Two identities make it possible

`Fn` was not designed as a general expression language, and two gaps look fatal until they are not.

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

The claim is not "close enough": `0 + 1·sin(2π·0·x + u)` collapses term by term with no rounding at
all, so the box above should report a worst difference of exactly zero and zero samples that differ in
their last bit. That one identity carries the whole trigonometric family, and with it most of the
Penner easings.

**`Fn` has no piecewise-in-x op, and every keyframed track is piecewise.** A clamped-ramp sum fixes
it. For keys `(tᵢ, vᵢ)` and any segment shape `Sᵢ` with `Sᵢ(0) = vᵢ` and `Sᵢ(1) = vᵢ₊₁`,

```diffstatic {data-lang=text}
v(t)  =  v₀ + Σᵢ [ Sᵢ(clamp01((t - tᵢ) / (tᵢ₊₁ - tᵢ))) - vᵢ ]
```

is *identically* the clamped piecewise interpolant. Inside span *j* every earlier term is
`vᵢ₊₁ - vᵢ` and every later one is zero, so the sum telescopes to `vⱼ + (vⱼ₊₁ - vⱼ)·Sⱼ(u)`; before
the first key everything is 0 and after the last everything is 1, which is exactly what the sampler's
clamping does. It needs only clamp / divide / multiply / add, so linear, eased and TCB tracks all
lower — and so do the `*-in-out` easings, which are piecewise but continuous at the halfway point and
therefore yield to the same trick.

## Declaring an unknown

```diffstatic {data-lang=rectsl}
# a bar whose height and offset are to be solved for, not authored
"sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
```

`free-param` marks a number you are willing to have chosen for you. Every backend still reads it as
the constant it presently is, so a scene with free parameters plays exactly as written and renders
exactly as written; only the differentiable backend treats it specially, labelling its `Fn` leaf so a
fitter can find which θ slot it landed in.

The box below runs the same fit twice, and the pair is the useful part. `fitParameters` defaults to
`iterations = 300` at `lr = 0.05`; Adam moves a parameter by roughly `lr` per step, so a `bias` that
must travel from 0 to −8 cannot possibly arrive in 300 steps, and the default call stops short with a
residual near 0.1 — a 97% reduction, which would satisfy a "did it improve?" check while being nowhere
near solved. Given 2000 iterations the same call lands on the closed-form answer. **Read the residual,
not the fact that a fit returned.**

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

Those four values are worth seeing arrive rather than taking on trust, so here is the whole loop in one
box, from RectSL source: a scene with two `free-param` numbers in it, the IR printed as its own algebra
before and after, and — because a residual is easier to believe as motion than as a number — the scene
drawn **as authored and as solved**, in the same two-band figure the top of this page uses. The authored
band is the animation you get if you never fit anything: `gain=1.0, bias=0.0`, the marker sweeping the
wrong span and missing both waypoints. The solved band is the same scene, same renderer, after the
gradient has chosen for you.

`gain=4.0, bias=-8.0` is the analytic answer to `t·gain + bias` through both waypoints, so the solver is
not being graded against its own output.

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
shape over a **mask**: only the named parameters ever move. Everything under the mask is borrowed —
the gradient is `Fn.gradientAt`, one backward sweep through the same `FnFormulas` table `Fn.eval` is
pinned against.

## One primitive, and every hard edge in the language

Five different things could stop a track from lowering, and four of them were the *same* missing
primitive — one the stdlib had already named as its own biggest gap. `FnNonDifferentiable`'s KDoc said
as much: the piecewise oscillators "need only an `FnExpr.Floor`".

So `Fn` gained a `floor`. It is a first-class node **and** differentiable, which reads like a
contradiction and is not: the derivative of a staircase is zero everywhere it exists, and zero is
what it reports. On its own that makes it a dead end, exactly as `sign` is. Inside something else it
is the opposite, and the reason is one line of arithmetic:

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
three: a visibility gate, a hold track, and the wrap of a looped clock. All three lower through that
one expression, plus a fourth thing — the `expo-*` and `elastic-*` easings, whose closed forms do not
reach their own endpoints (`expo-in` is 2⁻¹⁰ at u=0, not 0) and which in a keyed track spend most of
their time exactly at those endpoints. `closed(u)·(1−floor(u))·(1−floor(1−u)) + floor(u)` is that
branch structure written as arithmetic.

Two primitives account for the whole of it, and it is worth seeing which unlocked what:

| | no `floor` | with `floor` | with `atan2` |
| --- | --- | --- | --- |
| gallery tracks that lower | 2363 / 2754 (85.8%) | 2638 / 2754 (95.8%) | **2754 / 2754 (100%)** |
| channel values compared | 302,293 | 328,451 of 333,207 (98.6%) | **333,207 of 333,207 (100%)** |
| easings that lower | 29 / 38 | **35 / 38** | 35 / 38 |
| distinct refusal reasons | 5 | 1 | **0** |
| worst relative disagreement | 6.6·10⁻¹⁶ | 6.6·10⁻¹⁶ | 7.7·10⁻¹⁴ |

The last 4.2% was one construction. `->face-travel` builds a `HeadingTrack` — `atan2` of a central
finite difference — and that was *every* remaining refusal in the corpus that was not a pattern track:
116 tracks. `Fn` gained `atan`, `atan2` and `hypot`, and the near-zero guards on `/` and `smoothstep`
came with them.

Two things in that last column are worth not glossing. The worst disagreement rose two orders of
magnitude, and it should have: a heading *differences* two positions a 240th of a second apart, so a
one-ulp error in a position is a ~10⁻¹⁴ error in the angle. It is amplification, not a new
approximation — a heading over an unnested trajectory is bit-identical at all 4001 samples of a
winding circle. And `atan2` is a **primitive** rather than `atan(y/x)` with a quadrant fix-up, which
is what the refusal text had predicted: the fix-up divides first, `-0.0 / -1.0` is `+0.0`, and the
answer comes out `+pi` where the truth is `-pi`. Half a turn, from two inputs that compare equal — and
that failure is kept as a measurement rather than as a remark.

## What is still refused, and whether that is permanent

A wrong derivative is worse than no derivative, so the lowering never guesses. `Track.toFn()` returns
`RectSLFnLowering.Refused(why, closable, missing)` rather than a null or a plausible-looking zero —
and `closable` is the distinction a reader actually needs, borrowed from the stdlib's own
`FnOpStatus`: **not yet written** is a different fact from **no correct lowering exists**.

| refused | closable? | why, and what it would take |
| --- | --- | --- |
| `mod` | yes | not for want of a sign primitive — `trunc(z) = s·floor(z) − (1−s)·floor(−z)` with `s` the exact step is exact at every double. What defeats it is that Kotlin's `%` on doubles is the *exact* remainder, and `a − b·trunc(a/b)` is a different function once the quotient outgrows 53 bits: `1e17 % 3` is `1.0` and the reconstruction says `0.0`, silently, *as a zero* — indistinguishable from the near-zero guard having fired. A constant pair still folds. |
| `bounce-*` easings | yes | four parabolic arcs, each join needing its own clamped ramp. Expressible with the machinery that is now here; not yet expressed. |
| the pattern tracks | **no** | an interpreter over a mini-notation tree, not an expression of fixed shape — the same reason `FnNonDifferentiable` refuses `pat` itself. |

Two entries that used to be on this table are not: `/` by a non-constant, and `smoothstep` with
non-constant edges. Both had looked impossible for the same wrong reason — masking the
*result* (`[|b| > 1e-12] · (a/b)`) does still divide on the suppressed branch, so `0·inf` is NaN. The
answer is to guard the *divisor*: `b·live + (1 − live)` is `b` where the guard passes and exactly
`1.0` where it does not, so no infinity is ever created to be masked. The sign of the suppressed zero
is then repaired by adding `−0.0`, which is the true additive identity on doubles where `+0.0` is
not — without it a negative numerator lowers to `−0.0` where the sampler says `0.0`, and a stray
signed zero is exactly what can spin a `->face-travel` rect through half a turn.

??? aside "Why the refusals are measured rather than reasoned out"
    Three of the rows above have carried a wrong reason at some point, `mod`'s for longest: it said `%`
    needed a sign primitive, and the exact-step identity had made that false for months. `/` and
    `smoothstep` were refused on a real observation with the wrong conclusion drawn from it. Each was
    closed by trying the lowering and comparing it against the sampler, not by arguing about it.

The fitter has a matching refusal. A staircase's derivative really is zero, so a
parameter reachable *only* through a `floor`, a gate or a hold has no path to the value at all —
present in the graph and unsolvable. `fitParameters` refuses that by name instead of printing a
residual that never moved and calling it convergence. A parameter *beside* a staircase is still fine:
under a floor it is refused, beside a gate it solves from residual 3.0 to 6.2·10⁻¹⁵.

## How much of it is checked

Everything on this page is checked against an oracle that shares **no code** with it: a full
double-precision capture of every sampled rect of all 34 gallery programs, at 41 times each, taken
before the symbolic IR existed. The differentiable backend recomputes each rect's nine numeric channels
and is held to that capture:

| measurement | value |
| --- | --- |
| gallery tracks that lower | **2754 of 2754 (100%)** |
| channel values compared | **333,207 of 333,207** across all 33 compilable programs |
| worst relative disagreement | **7.7·10⁻¹⁴** (a `cascade-split` rotation; see the amplification note above) |
| worst absolute disagreement | **8.3·10⁻¹⁴** |
| easings lowered | 35 of 38, worst disagreement 8.9·10⁻¹⁶ over a 1001-point grid |
| exact gradient vs central difference | worst **7.1·10⁻¹¹** over twenty-one parameterised shapes; **6.8·10⁻⁹** for a heading |
| the exact step, at its own boundary | exact, including at `-0.0` |
| a looped clock across 4.5 periods | **bit-identical** (0.0); ping-pong 8.9·10⁻¹⁶; a hold track 5.6·10⁻¹⁷ |
| a heading round a winding circle | **bit-identical at 4001 of 4001 samples**, across two branch-cut crossings |
| a heading whose velocity vanishes | **bit-identical at 801 of 801**, with 400 of them needing a wider probe than the first |
| the guarded `/` and `smoothstep` | **bit-identical at 2001 of 2001 each**, including the sample where the guard fires |

The first row is an assertion rather than a report: the build fails if a single gallery track refuses to
lower.

The last few rows are the ones that matter for a fit. Agreeing on values proves nothing about a
derivative — a second interpreter can reproduce `eval` everywhere and still differentiate the wrong
function — so the gradient is checked against a central finite difference, which is its only oracle
that does not involve trusting the gradient. 7.1·10⁻¹¹ is the finite difference's own truncation
error at a 1e-6 relative step, not the gradient's.

One caveat on the figure at the top of this page: it is a CPU raster rather than a GPU capture. Every
rectangle in it is real geometry sampled from the real track at a real instant, but it is not the
shader's own answer.

??? aside "Where the oracle is, and the one seam in it"
    The golden is a 37,057-line characterization corpus generated from pre-refactor code, and
    `RectSLFnBackendTest` reads it as data. The one seam: the differential test has to compose group and
    rect transforms somewhere, and the `Fn` backend is scalar-only, so that composition is transcribed
    from `SceneProgram.sample`. A companion test runs the same transcription with the *original* tracks
    and requires bit-equality with the golden across all 333,207 channel values, which is what stops the
    transcription from rotting.

## The rest of what a symbolic IR bought

Of the five things a closure cannot be — printed, compared, serialized, rewritten, emitted as GLSL —
GLSL and this page's differentiable backend are the two big ones. The remaining three come almost free
once the IR is data.

- `Track.describe()` prints a tree as its algebra —
  `ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)` — rather than as nested constructors.
- **Comparison came free.** The IR is data classes, so two tracks built by the same `array` body are
  already `==` — which is what the GLSL emitter's function deduplication runs on.
- `Track.simplify()` is the general rewriter, and `substituteParameters` the targeted one a fit uses.

The simplifier is worth a paragraph because of what it *refuses*. Its contract is that a rewritten
track samples **bit-identically**, and the gallery corpus broke the first reflexive rule within a
minute: `0 - x` is not `-x`, because `0.0 - 0.0` is `+0.0` while `-(0.0)` is `-0.0`. Signed zero is
observable here — `HeadingTrack` takes `atan2`, and `atan2(+0.0, -1.0)` is `+π` while
`atan2(-0.0, -1.0)` is `-π`, half a turn apart — so a rewrite that "only" changes the sign of a zero
can spin a rectangle through 180°. Five reflexive rewrites are therefore refused and asserted as
refused: `x + 0`, `0 - x`, and all three `mix` degeneracies (`mix(a, b, 1)` is `a + (b - a)`, which is
simply not `b` in floating point). What survives still takes the gallery from 10,621 nodes to 8865
(16.5% smaller), because the constant fold does the heavy lifting.

## Keys are tracks too

`TcbKey` holds a `Track<Double>` rather than a `Double`, so a key *value* can be a free parameter and
a fit can reach *inside* a keyed track rather than only composing around one. Written in RectSL, the
top of a ramp is the unknown:

```diffstatic {data-lang=text}
'( 0=0  2=4 free-param peak ) linear
```

Any time-invariant track will do — a constant, a `free-param`, or arithmetic over them — and that is
checked when the key is built, because a key whose value varied with time would have no answer to
"the value *at* this key". Every CPU sampler and the shader read only the number the key currently
stands at, so a scene with an unknown in it plays and renders exactly as written; the differentiable
backend is the only thing that looks at the value's tree, where a `FreeParamTrack` arrives as a
*named* leaf and the gradient with respect to it falls out of the same backward sweep as everything
else.

For linear, eased and hold tracks that is exact by construction: the lowering is
`v₀ + Σ shape(u)·(vᵢ₊₁ - vᵢ)`, which is linear in the key values, and the same clamped-ramp sum that
was already there carries them.

TCB needed more care, and it is the interesting half. A TCB tangent is *derived* from the
neighbouring key values, so **baking** it — which this backend has always done, and which the shader
still does — stops being faithful the moment a key can move: a fit would solve for the key, `fitted`
would recompute its tangents from the solved value, `fittedFn` would still carry the seed ones, and
one fit would have two answers. A fitter that returns two animations for one solve is worse than one
that refuses, so a TCB track with a parameterised key lowers its **tangents symbolically too**, and a
moved key moves its tangents the way the sampler says it should. That is a second transcription of
the TCB formula, which is exactly the kind of thing that drifts, so the two paths are pinned against
each other on constant keys where both are defined: on a four-key spline with per-key tension,
continuity and bias they agree to the last bit across 401 instants, and the fitted graph and the
fitted track now differ by 2·10⁻¹⁶ rather than by a spline.

What is still a plain `Double` is a key's *time*. A keyed track sorts by time and selects a span from
it, so a parameterised time would have to move the *structure* of the track rather than only its
numbers — a different kind of change from this one, and not made.

## See it moving

Replay `dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` through the agent backdoor, or
open it from the welcome screen's starting points. Box one solves the six-parameter arc above and
draws the three-band figure into its own output; box two does the same from RectSL source using
`free-param`, and prints the track IR as its own algebra before and after the solve.

# RectSL Inverse Animation

A RectSL animation is normally read forwards: you write the tracks, and the sampler tells you where
the rectangles went. This page is about running that arrow backwards — saying **where a rect should
be, and when**, and letting a gradient work out the numbers that put it there.

![Target, seed and fitted, in one frame](assets/rectsl/rectsl-inverse-animation.png)

Three bands, one image. The top band is where the rect was *asked* to go — three waypoints, three
instants. The middle band is what the seed parameters actually do: a small, badly-phased circle near
the origin that misses every waypoint, worst miss 7.72 wall metres. The bottom band is the same
animation with the six numbers solved, threading all three waypoints to within 5·10⁻¹⁵ metres. The
faint rectangle is the wall; the solved arc leaves it, which is allowed and is why the figure shows a
fifth more than the wall rather than cropping the answer.

## The three backends

`ddf732260` made the RectSL track IR symbolic, and said why: a Kotlin closure "cannot be printed,
compared, serialized, rewritten, or emitted as GLSL". Every scalar op in the IR now names itself, and
each one owns three columns:

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

```
sin(frequency = 0, phase = u, amplitude = 1, offset = 0)   ==   sin(u(x))
```

exactly — `0 + 1·sin(2π·0·x + u)` collapses term by term with no rounding at all. That one identity
carries the whole trigonometric family, and with it most of the Penner easings.

**`Fn` has no piecewise-in-x op, and every keyframed track is piecewise.** A clamped-ramp sum fixes
it. For keys `(tᵢ, vᵢ)` and any segment shape `Sᵢ` with `Sᵢ(0) = vᵢ` and `Sᵢ(1) = vᵢ₊₁`,

```
v(t)  =  v₀ + Σᵢ [ Sᵢ(clamp01((t - tᵢ) / (tᵢ₊₁ - tᵢ))) - vᵢ ]
```

is *identically* the clamped piecewise interpolant. Inside span *j* every earlier term is
`vᵢ₊₁ - vᵢ` and every later one is zero, so the sum telescopes to `vⱼ + (vⱼ₊₁ - vⱼ)·Sⱼ(u)`; before
the first key everything is 0 and after the last everything is 1, which is exactly what the sampler's
clamping does. It needs only clamp / divide / multiply / add, so linear, eased and TCB tracks all
lower — and so do the `*-in-out` easings, which are piecewise but continuous at the halfway point and
therefore yield to the same trick.

## Declaring an unknown

```
# a bar whose height and offset are to be solved for, not authored
"sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
```

`free-param` marks a number you are willing to have chosen for you. Every backend still reads it as
the constant it presently is, so a scene with free parameters plays exactly as written and renders
exactly as written; only the differentiable backend treats it specially, labelling its `Fn` leaf so a
fitter can find which θ slot it landed in.

```kotlin
val fit = track.fitParameters(
    listOf(TrackFitTarget(1.0, -6.0), TrackFitTarget(3.0, 6.0))
)
fit.parameters        // {gain=4.0, bias=-8.0}
fit.initialResidual   // 3.606
fit.finalResidual     // 2.6e-15
fit.fitted            // the same track, solved — samplable, renderable, emittable as GLSL
```

`fitParameters` is not `fitGradient`, and the difference matters. `fitGradient` moves **every**
numeric leaf, which is right for a whole graph and wrong for a lowered track: most of its leaves are
not parameters at all but key times, clamp bounds, the `0.5` in an easing, the `-1.0` that mirrors a
reversed clock. Left to descend freely, Adam walks the `0.0` and the `1.0` of a keyed track's
`clamp01` past each other and `coerceIn` throws. So the loss and the Adam update are `fitGradient`'s
shape over a **mask**: only the named parameters ever move. Everything under the mask is borrowed —
the gradient is `Fn.gradientAt`, one backward sweep through the same `FnFormulas` table `Fn.eval` is
pinned against.

## What is refused, and why

A wrong derivative is worse than no derivative, so the lowering never guesses. Every op declares
either a lowering or a refusal *with a reason*, and `Track.toFn()` returns
`RectSLFnLowering.Refused(why)` rather than a null or a plausible-looking zero.

| refused | why |
| --- | --- |
| `floor` `ceil` `fract` `mod` | an integer lattice: the derivative is zero on every open interval and undefined on each jump |
| `loop-time` `pingpong-time` | folding the clock is piecewise in time — but a window that stays inside one period *is* the identity, and lowers exactly |
| a hold track with 2+ keys | a step |
| a gate with an edge inside the window | a hard 0/1 discontinuity — wholly inside or wholly outside, it is a constant and lowers |
| `->face-travel` headings | `atan2` has no `Fn` op |
| the pattern tracks | an interpreter over a mini-notation tree, which is why `FnNonDifferentiable` refuses `pat` too |
| `/` and `smoothstep` with non-constant guards | RectSL's `/` returns 0 where `\|divisor\| <= 1e-12`; a *constant* divisor folds the guard exactly, anything else is refused rather than silently dropped |
| `expo-*` `elastic-*` `bounce-*` easings | exact-endpoint special cases the closed form does not reach, and four parabolic arcs |

## How much of it is checked

`ddf732260` shipped a 37,057-line characterization golden: every sampled rect of all 34 gallery
programs at 41 times each, to full double precision, **generated from pre-refactor code**. So it is
an oracle with no code path in common with the symbolic IR, let alone with a backend written after
it. `RectSLFnBackendTest` reads it as data and recomputes each rect's nine numeric channels through
Fn-lowered tracks:

| measurement | value |
| --- | --- |
| gallery tracks that lower | 2363 of 2754 (85.8%) |
| channel values compared | 302,293 across all 33 compilable programs (30,914 declined) |
| worst relative disagreement | **6.6·10⁻¹⁶** |
| worst absolute disagreement | **1.3·10⁻¹⁵** |
| easings lowered | 29 of 38, worst disagreement 8.9·10⁻¹⁶ over a 1001-point grid |
| exact gradient vs central difference | worst **7.1·10⁻¹¹** over sixteen parameterised shapes |

That last row is the one that matters for a fit. Agreeing on values proves nothing about a
derivative — a second interpreter can reproduce `eval` everywhere and still differentiate the wrong
function — so the gradient is checked against a central finite difference, which is its only oracle
that does not involve trusting the gradient. 7.1·10⁻¹¹ is the finite difference's own truncation
error at a 1e-6 relative step, not the gradient's.

Two honest notes about the evidence. The differential test has to compose group and rect transforms
somewhere, and the `Fn` backend is scalar-only, so that composition is transcribed from
`SceneProgram.sample`; a companion test runs the same transcription with the *original* tracks and
requires bit-equality with the golden across 333,207 channel values, which is what stops the
transcription from rotting. And the figure at the top of this page is a CPU raster rather than a GPU
capture — every rectangle in it is real geometry sampled from the real track at a real instant, but
it is not the shader's own answer.

## A limitation worth knowing

`TcbKey` holds a `Double`, so a keyed track's key *values* cannot themselves be free parameters.
Parameters compose *around* a keyed track — gain, bias, phase, frequency, a `mix` weight — rather
than inside one. Making keys track-valued is a larger change to the IR and has not been made.

TCB tangents are also baked at lowering time. They are derived from the key values and times, so a
fit that moved a key value ought to move the tangents with it; the lowered derivative is exact for
the spline whose tangents are the ones it baked.

## See it moving

Replay `dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` through the agent backdoor, or
open it from the welcome screen's starting points. Box one solves the six-parameter arc above and
draws the three-band figure into its own output; box two does the same from RectSL source using
`free-param`, and prints the track IR as its own algebra before and after the solve.

# RectSL's track IR becomes differentiable — the third backend

Nightly agent, 2026-08-06, kind A. Branch `nightly/2026-08-06/1-rectsl-differentiable-tracks`,
based on `50d838851`. Worked 03:26–07:35.

## Plan (as written before starting, kept for the record)

`ddf732260` made the RectSL track IR symbolic and named four capabilities the change unlocked —
printing, comparison, serialization, rewriting — then delivered exactly one of them (GLSL).
`TrackOp1/2/3` and `TimeWarpKind` each own an `eval:` and a `glsl:`. This adds the third column,
`fn:`, lowering a track to an `afield.stdlib.Functions.Fn`, which is Field's exact-autodiff
expression type. That turns every RectSL animation channel into something a gradient can *solve*.

The plan survived contact with the code essentially unchanged. Four things I did not expect at
planning time are described under "What I learned that the plan did not know".

## What changed

**The backend** (`src/jvmMain/kotlin/afield/rectsl/RectSLFn.kt`, new, 700 lines;
`src/jvmMain/kotlin/afield/rectsl/RectSL.kt`)

`Track<Double>.toFn(over)` returns `RectSLFnLowering.Lowered(fn)` or `Refused(why)`. The refusal is
in the type, with a sentence, because a wrong derivative is worse than no derivative — the same
discipline `FnNonDifferentiable` applies inside the stdlib. `TrackOp1/2/3` and `TimeWarpKind` each
gained an `fn:` column beside their `eval:` and `glsl:`.

Two identities make it possible at all:

- `Fn` has no "apply a unary function to another `Fn`'s value" op, but `Functions.sin`'s `phase`
  child is evaluated **at x**, so `sin(frequency = 0, phase = u, amplitude = 1, offset = 0)` is
  exactly `sin(u(x))` with no rounding. That carries the whole trigonometric family.
- `Fn` has no piecewise-in-x op and every keyframed track is piecewise, but for keys `(tᵢ, vᵢ)` and
  any segment shape with `S(0) = vᵢ`, `S(1) = vᵢ₊₁`,
  `v(t) = v₀ + Σᵢ [ Sᵢ(clamp01((t−tᵢ)/(tᵢ₊₁−tᵢ))) − vᵢ ]` is *identically* the clamped piecewise
  interpolant. Linear, eased and TCB tracks all lower through it, as do the `*-in-out` easings.

Also here: `FreeParamTrack` (a named unknown every backend still reads as its current constant),
`substituteParameters` (the *rewriting* `ddf732260` promised), `describe()` (the *printing*), and
`trackConstantValue` lifted out of the GLSL emitter so both backends share one constant fold.

**The fitter** (`src/jvmMain/kotlin/afield/rectsl/RectSLFit.kt`, new)

`Track.fitParameters(targets)` — inverse animation. Masked Adam on the exact gradient
(`Fn.gradientAt`), moving only the named free parameters. It is deliberately *not* `fitGradient`;
see the hazard below. `gradientAgreement(times)` is public because the exact gradient's only honest
oracle is a central difference and a caller with a bespoke track has the same right to that number
as a test does. RectSL gained the word `free-param`.

**The picture** (`src/jvmMain/kotlin/afield/rectsl/RectSLFitFigure.kt`, new;
`public/docs/assets/rectsl/rectsl-inverse-animation.png`)

Three stacked bands — waypoints, seed, fitted — strobed over the wall, with each band's measured
worst miss printed into its own caption. A CPU raster, because it has to run in a unit test and in a
Field box. `PLOT_NUMERAL_GLYPHS` became `PLOT_GLYPHS` and gained the uppercase alphabet, since
neither RectSL nor a pixel buffer has a text primitive and there should be one dot-matrix font in the
package rather than two.

**Tests** `RectSLFnBackendTest`, `RectSLFitTest`, `RectSLFitFigureTest`.

**Docs** `public/docs/rectsl_inverse_animation.md` + nav; regenerated `public/docs/reference/`.

**Demo** `dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` (+ its materialized bundle).

**Two incidental fixes** a redundant `else` on an exhaustive `when` in `RectSLSdfBakeTest`; and
`build.gradle.kts` now forwards `-Dafield.*` into test workers — a test worker is a fresh JVM, so
`-Dafield.rectsl.characterization=write`, which `RectSLTrackCharacterizationTest` documents as the
way to regenerate its 37k-line golden, had never reached the test at all.

## Verification — what I observed, with numbers

`./gradlew --no-daemon check` is **green, exit 0, 3m38s, zero Kotlin warnings** on the final tree.
(It failed twice on the way and both were mine: a stale codebase reference, which I own and
regenerated, and two KDoc blocks the attachment guard could not attach.)

**The value oracle.** `ddf732260` shipped `src/jvmTest/resources/rectsl/track_characterization.txt`
— every sampled rect of all 34 gallery programs at 41 times, full double precision, **generated from
pre-refactor code**. The differential test reads it as data and recomputes each rect's nine numeric
channels through Fn-lowered tracks:

| | |
| --- | --- |
| gallery tracks lowered | 2363 of 2754 (85.8%) |
| channel values compared | 302,293 across all 33 compilable programs; 30,914 declined |
| every program contributes | yes (asserted) |
| **worst relative disagreement** | **6.617e-16** |
| **worst absolute disagreement** | **1.332e-15** |
| worst at | `auto-orient/pip-copy-8@5.625 center.x: golden=-1.0133003897732542 fn=-1.0133003897732529` |
| refusals | 215 LOOP, 116 heading/atan2, 56 gate-edge-in-window, 3 PINGPONG, 1 hold |

**The gradient oracle.** Exact vs central finite difference over sixteen parameterised shapes
(keyed linear, eased, TCB, every unary and binary op, both usable ternaries, all three affine
warps), at times chosen off segment boundaries and off the kinks of abs/clamp/min/max: **worst
relative 7.123e-11**, which is the finite difference's own O(h²) truncation error at a 1e-6 relative
step rather than the gradient's.

**The easings.** 29 of 38 lower; worst disagreement **8.882e-16** against each easing's own closure
over a 1001-point grid, and every lowered easing is 0 at u=0 and 1 at u=1 to 1e-12 (which is what
makes the telescoping sum exact).

**Fits, residual before → after, all measured:**

- three-waypoint `amp·cos(1.4t+phase)+centre`: **1.885 → 5.87e-16**, all three waypoints hit to 1e-15.
- the `free-param` word end to end from RectSL source, two unknowns and two targets: gain 1.0 → 4.0,
  bias 0.0 → −8.0 (the exact answer to the linear system), residual **3.606 → 2.59e-15**.
- the figure's six-parameter arc: worst waypoint miss **7.717 wall metres → 4.72e-15**.
- `fitGradient(gradient = EXACT)` accepts a lowered track and reports `usedExactGradient = true`,
  which is the proof these graphs are inside `FnAutoDiff.differentiableOps` rather than near it.

**Pixels.** I rendered the figure and **read the PNG back and looked at it**, twice. The first
render was correct but clipped the fitted arc off the bottom of the frame (the solved animation
leaves the wall), so I added a view margin and drew the wall's own bounds as a rule, then looked
again. The committed asset shows: three waypoints on the target band; an orange blob near the origin
on the seed band missing all three; a teal arc on the fitted band passing exactly through all three.

**Demo.** Replayed into a live Field launched from this worktree on **port 9601** (pid 22020, cwd
verified against the checkout before driving it). Both boxes executed; box one emitted a `display`
event on channel `figure` carrying a `data:image/png;base64,…` figure; box two's output reads
`IR before: ADD(MUL(linear[0.0=0.0 4.0=4.0], ?gain=1.0), ?bias=0.0)`. Field stopped afterwards —
SIGINT did not take within 8s (as the orchestrator warned), escalated to SIGTERM on the pid I
started, and `lsof -nP -iTCP:9601 -sTCP:LISTEN` now reports nothing.

## Weaknesses in my own evidence

Volunteered, in descending order of how much they should worry a reader.

1. **The differential test transcribes part of the code under test.** `SceneProgram.sample`'s
   group/rect transform composition has to happen somewhere and the Fn backend is scalar-only, so
   `walkScene` in the test re-implements it. I mitigated this rather than merely disclosing it: a
   companion test runs the *same* walker with the original `Track.sample` and requires bit-equality
   with the golden across **333,207** channel values. So the transcription is pinned. But it is
   still a transcription, and if both the walker and the lowering were wrong in the same way, this
   would not catch it.
2. **The figure is a CPU raster, not the shader's answer.** Every rectangle in it is real geometry
   sampled from the real track at a real instant — nothing is drawn by hand or faked — but it is not
   a GPU capture, and it therefore does not cross-check the GLSL backend at all.
3. **I did not byte-compare the live box's PNG against the committed asset.** The replay used a
   scratch document, which had been discarded by the time I went back for the bytes. What I actually
   observed is that the box emitted a display PNG; that it is the *same* PNG rests on it being the
   same code, not on a comparison.
4. **14.2% of gallery tracks do not lower**, and the biggest single reason (215 LOOP refusals) is an
   artefact of the golden deliberately sampling out to 1.25× duration, past the wrap. Over `[0,
   duration)` a `loop-time` at the scene duration lowers exactly. So real coverage on a scene's own
   clock is higher than 85.8%, and I did not measure that number.
5. **TCB tangents are baked at lowering time.** They are derived from the key values and times, so a
   fit that moved a key value ought to move the tangents with it and this lowering does not. The
   derivative is exact for the spline whose tangents are the ones it baked. Stated in the code and
   on the docs page.
6. **The 1e-12 tolerance in the differential test is 4 orders of magnitude looser than the measured
   6.6e-16.** Deliberate slack against JDK `Math.pow` variation, but it means a real regression of
   up to ~1e-12 would slip past.

## What I learned that the plan did not know

- **`fitGradient` cannot be used unmasked on a lowered track, and fails loudly rather than badly.**
  It moves every numeric leaf; a keyed track's `clamp01` bounds are leaves; Adam walks the `0.0` and
  the `1.0` past each other and `coerceIn` throws. This is recorded in a test rather than hidden,
  and it is the whole justification for `fitParameters` masking θ.
- **`param` was already a RectSL word** (the pattern layer's mini-notation lookup), and registering a
  second one silently shadowed it. Found by an end-to-end test failing with "Expected string" — a
  good argument for having written the end-to-end test.
- **The documented golden-regenerate recipe never worked**, for every characterization test in the
  repo, because Gradle test workers do not inherit build system properties.
- **`*-in-out` easings are piecewise but continuous**, so the same clamped-ramp trick that lowers a
  keyed track lowers them too. I had planned to refuse all nine.

## Risks

The only production behaviour change to existing paths is `PLOT_NUMERAL_GLYPHS` → `PLOT_GLYPHS`
(pure rename, plus letters the old callers never look up) and the GLSL emitter's constant folder
delegating to a shared function (same logic, `FreeParamTrack` folded at its value). Everything else
is additive. `build.gradle.kts` gains a `systemProperty` loop, which very slightly widens what
counts as a test task input — if a stray `-Dafield.*` is on a future build command line, test tasks
will re-run. That is the intent.

## Recommendation: **merge**

The third backend is real, the oracle is the strongest one in the repository and it was used rather
than admired, the derivative was checked against the only oracle that does not presuppose it, and
the fit produces both a measured residual and a picture with its counterfactual in the frame. Full
`check` is green with zero warnings. The weaknesses above are disclosures, not defects.

If continued, the three things I would do next, in order: make `TcbKey` track-valued so a keyframe's
*value* can be an unknown (the single biggest limitation); measure coverage over each scene's own
clock rather than the golden's 1.25× window; and push the fit up to the SDF, where a target *image*
is within reach because the CPU sampler is already proven bit-comparable to the shader.

## Demo artifact

`dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` — replay this into a fresh Field4 (or
open it from the welcome screen's starting points) and look at box one: the top band is where the
rect was asked to go, the middle band is a seed that misses every waypoint by 7.7 metres, and the
bottom band is a solved arc threading all three.

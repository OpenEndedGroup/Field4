# RectSL's track IR becomes differentiable — the third backend

Nightly agent, 2026-08-06, kind A. Branch `nightly/2026-08-06/1-rectsl-differentiable-tracks`,
based on `50d838851`. Worked 03:26–05:15 in two sessions: the backend and the fitter, then a
coverage-and-rewriting pass after the orchestrator pointed at the refusal number.

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

| | first pass | after the second session |
| --- | --- | --- |
| gallery tracks lowered | 2363 of 2754 (85.8%) | **2638 of 2754 (95.8%)** |
| channel values compared | 302,293; 30,914 declined | **328,451 of 333,207 (98.6%); 4,756 declined** |
| every program contributes | yes (asserted) | yes |
| **worst relative disagreement** | **6.617e-16** | **6.617e-16** (unchanged) |
| **worst absolute disagreement** | **1.332e-15** | **1.332e-15** (unchanged) |
| worst at | `auto-orient/pip-copy-8@5.625 center.x: golden=-1.0133003897732542 fn=-1.0133003897732529` | same |
| refusal kinds | 215 LOOP, 116 heading, 56 gate, 3 PINGPONG, 1 hold | **116 heading/atan2, and nothing else** |

**The gradient oracle.** Exact vs central finite difference over **twenty-one** parameterised shapes
(keyed linear, eased, TCB, every unary and binary op, both usable ternaries, all three affine warps,
and after the second session `floor`, `fract`, both wrapping warps and a gate), at times chosen off
segment boundaries and off the kinks of abs/clamp/min/max: **worst relative 7.123e-11**, which is the
finite difference's own O(h²) truncation error at a 1e-6 relative step rather than the gradient's.
`floor` and `fract` report a disagreement of exactly 0.0, which is the interesting entry: what is
being checked there is that the exact path reports the *same* zero a central difference does rather
than something plausible-looking, since a wrong non-zero derivative through a staircase would be the
hardest kind to notice.

**The easings.** **35** of 38 lower (29 before the second session); worst disagreement **8.882e-16**
against each easing's own closure over a 1001-point grid, and every lowered easing is 0 at u=0 and 1
at u=1 to 1e-12 (which is what makes the telescoping sum exact).

**The primitives the second session added.** The exact step `clamp01(floor(x) + 1)` is exact at its
own boundary and at `-0.0`. A looped clock agrees with the CPU sampler **bit-identically (0.0)**
across 4.5 periods; ping-pong to 8.9e-16; a gate exactly at both edges; a hold track to 5.6e-17.

**The simplifier.** Over the gallery: 2754 channels, **8197 nodes to 6442 (21.4% smaller)**, 112,914
samples compared **bit-for-bit**, and a fixed point on a second pass. Five reflexive rewrites are
refused rather than kept, and asserted as refused.

**The stdlib.** `Fn.floor()` takes op coverage from 31/122 (25.4%) to 32/124 (25.8%). The existing
guards did the verifying, which is the point of having them: the raw-bit formula-transcription oracle
covers two new graphs (a bare floor, and the modulo it was added for), and two vocabulary guards went
red and were satisfied properly rather than relaxed — the transform family's pinned size (19 to 20,
which reseeds `evolve`) is declared in `dev_docs/stdlib_behaviour_changes.md`, and `floor`/`floorY`
were added to the editor's `opWords`.

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

## Second session (04:30–05:15): closing the 14.2%

The orchestrator's read was right that the refusal number was the most interesting thing in the first
report, and it turned out to be mostly *one missing primitive* rather than anything genuinely
non-differentiable.

**What I added**

- `Fn.floor()` in the stdlib (`autodiff_formula.kt`, `Functions.kt`, `FunctionOps.kt`), with
  `FnExpr.Floor` in both walkers. `FnNonDifferentiable`'s own KDoc had been naming this as the single
  highest-value gap in its backlog, in three separate refusal groups. Its AD rule needs no new tape
  node: a floor is recorded as a *constant*, so the adjoint through it is exactly zero.
- The one line that made it worth it: `clamp01(floor(x) + 1)` is `1` where `x >= 0` and `0` below,
  **exactly**, for every finite or infinite x, with the half-open boundary falling the right way
  round. A step is every hard edge in an animation language, and RectSL has three (a gate, a hold, the
  wrap of a looped clock) plus a fourth use (the exact endpoints the expo/elastic easings need).
- `RectSLFnLowering.Refused` now carries `closable` and `missing`, borrowed from `FnOpStatus`, so
  "not yet written" is distinguishable from "no honest lowering exists" without reading the code. All
  six remaining refusals are classified and five are closable with a named next step.
- `fitParameters` refuses a fit whose every parameter has an exactly zero derivative at every target,
  naming the parameter. That is the honesty follow-through for having lowered the staircases: a
  staircase's derivative really is zero, so a parameter reachable only through one is present in the
  graph and unsolvable, and descending on it would print a residual that never moved.
- `Track.simplify()` and `nodeCount()` — the general rewriter, the last of the capabilities
  `ddf732260` named. Comparison, the other one, turned out to have come free with the data classes.

**Coverage, before → after** (the golden differential re-verified all of it for free, which is the
leverage the first session was spent building):

| | before | after |
| --- | --- | --- |
| gallery tracks lowered | 2363 / 2754 = **85.8%** | 2638 / 2754 = **95.8%** |
| channel values compared | 302,293 | 328,451 of 333,207 = **98.6%** |
| easings lowered | 29 / 38 | **35 / 38** |
| distinct refusal reasons | 5 | **1** (atan2 headings) |
| worst relative disagreement | 6.617e-16 | **6.617e-16, unchanged** |
| stdlib op coverage | 31/122 (25.4%) | **32/124 (25.8%)** |

**What I attempted and abandoned**

*Fitting a scene to a target image, through the SDF.* Abandoned deliberately, and not for lack of
time — for a reason I should have seen at planning time. My lowering is per *track*: a scalar function
of time. Fitting to a raster needs `d(pixel)/d(parameter)`, which means differentiating the
**rasterizer**, and a rasterizer's coverage function is a step in screen space. That is the soft-
rasterization problem, a genuinely separate piece of research with its own literature, and the
honest version of it needs a differentiable coverage approximation whose bias you can characterize.
There is no version of it I could have landed and verified in an hour, and a half-landed one would
have been the most impressive-looking and least trustworthy thing in the branch. Noted here so the
next attempt starts from the right problem statement rather than from "we nearly had it".

**What the corpus caught that I would not have**

The simplifier's first run, within a minute of the test existing: `0 - x` is not `-x`, because
`0.0 - 0.0` is `+0.0` and `-(0.0)` is `-0.0`. That is not pedantry in this IR — `HeadingTrack` takes
`atan2`, and `atan2(+0.0, -1.0)` is `+π` while `atan2(-0.0, -1.0)` is `-π`, half a turn apart. So a
rewrite that "only" changes the sign of a zero can spin a rectangle through 180°. Five reflexive
rewrites are refused as a result (`x + 0`, `0 - x`, and all three `mix` degeneracies — `mix(a, b, 1)`
is `a + (b - a)`, which is not `b` in floating point at all), each asserted as refused so a tidying
pass cannot re-add one. The rewriter is smaller than the one I set out to write and provably exact
instead of probably exact, which is the better trade.

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
4. ~~14.2% of gallery tracks do not lower~~ — **closed in the second session**: 95.8% now lower and
   the only remaining refusal is `atan2` headings. What is left of this weakness is smaller and
   sharper: the exact step and the floored modulo are *bit-exact against the CPU sampler* (measured
   0.0 for a looped clock across 4.5 periods), but their **derivative** is zero almost everywhere, so
   coverage of the *value* backend grew by 10 points while coverage of things you can usefully *fit*
   grew by much less. The fitter's new zero-gradient refusal makes that visible at the call site
   rather than hiding it, but "95.8% lowers" should not be read as "95.8% is solvable".
5. **TCB tangents are baked at lowering time.** They are derived from the key values and times, so a
   fit that moved a key value ought to move the tangents with it and this lowering does not. The
   derivative is exact for the spline whose tangents are the ones it baked. Stated in the code and
   on the docs page.
6. **The 1e-12 tolerance in the differential test is 4 orders of magnitude looser than the measured
   6.6e-16.** Deliberate slack against JDK `Math.pow` variation, but it means a real regression of
   up to ~1e-12 would slip past.
7. **The demo transcript was replayed before the second session's changes, not after.** The figure
   asset is byte-identical after everything (re-rendered and `git status` clean, which is a real
   check), and box two's code path is covered by `theFreeParamWordReachesTheSceneAndSolves`, but I did
   not launch Field a second time. The claim "it replays" rests on the first replay plus the tests.
8. **`bounce-*` is the one easing family I claimed was expressible and did not express.** The claim is
   reasoned (four parabolic arcs, continuous at their joins, and the clamped-ramp fold handles exactly
   that shape) but it is a claim, not a measurement.

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

**The rename risk is closed.** `PLOT_NUMERAL_GLYPHS` → `PLOT_GLYPHS` was my only non-additive change
to a shared surface, and the symbol is `internal`, which in this build *is* visible to `src/personal`
(a separate compilation associated with `jvmMain`) — a tree that is gitignored and therefore invisible
from this worktree. I grepped the primary checkout read-only: `/Users/marc/field4/src/personal` has
**no reference** to either spelling, and the only hits anywhere are the two the rename fixed. No alias
needed.

**The one real behaviour change is now `Fn.floor()`, not the rename.** Growing the `transform` family
from 19 to 20 reseeds every `evolve` run that mutates a transform node — same seed, different
replacement op, different output, nothing thrown. That is declared in
`dev_docs/stdlib_behaviour_changes.md` with the three other surfaces it touches. The subtler one on
that list: `fitGradient(gradient = EXACT)` now accepts a graph containing a `floor` and will descend a
gradient of exactly zero through it. RectSL's `fitParameters` refuses that case by name; a bare
`fitGradient` does not, and I did not change `fitGradient` to.

Everything else is additive: the GLSL emitter's constant folder delegates to a shared function (same
logic, `FreeParamTrack` folded at its value), and `build.gradle.kts` gains a `systemProperty` loop
that slightly widens what counts as a test-task input — a stray `-Dafield.*` on a future command line
will re-run tests. That is the intent.

## Recommendation: **merge**

The third backend is real, the oracle is the strongest one in the repository and it was used rather
than admired, the derivative was checked against the only oracle that does not presuppose it, and
the fit produces both a measured residual and a picture with its counterfactual in the frame. Full
`check` is green with zero warnings. The weaknesses above are disclosures, not defects.

If continued, the four things I would do next, in order:

1. Make `TcbKey` track-valued so a keyframe's *value* can be an unknown. Still the single biggest
   limitation, and the one users would hit first.
2. `FnExpr.Atan`, which closes the last RectSL refusal (`atan2` headings, the whole remaining 4.2%)
   and is a *real* derivative rather than a zero — the quadrant fix-up atan2 needs is now cheap,
   because the exact step exists.
3. Transcribe `saw` / `triangle` / `square` / the pulses in the stdlib. That backlog entry has said
   "needs only an `FnExpr.Floor`" for months; the primitive now exists, so it is pure transcription,
   and oscillator fitting is `fitGradient`'s flagship use.
4. The image fit — but starting from the soft-rasterization problem, not from this branch. See the
   abandonment note above.

## Demo artifact

`dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` — replay this into a fresh Field4 (or
open it from the welcome screen's starting points) and look at box one: the top band is where the
rect was asked to go, the middle band is a seed that misses every waypoint by 7.7 metres, and the
bottom band is a solved arc threading all three.

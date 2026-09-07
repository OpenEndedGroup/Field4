# Stdlib behaviour changes — a running ledger

Some fixes in `afield.stdlib` change what already-written box code *computes*, not just what the docs
*say*. Those cannot be quietly absorbed: a saved workspace, a shipped transcript or a committed docs
capture may be sitting on the old numbers. This file is the durable record — the nightly worklog is
routinely stripped at merge time, so release notes do not belong there.

Entries are oldest first. Each one says what changed, in which direction, and what was swept for
callers.

---

## 2026-07-14 — `ramp` became a value line, and the normalizer is now `remap` (`3883d558e`)

`Functions.ramp(start, end)` was a **domain normalizer**: `(x - from) / (to - from)`, mapping an
x-window onto `0..1`. It is now a **value line** — it blends `start` to `end` as x sweeps the
domain, the linear sibling of `tween`. The old normalizer survives unchanged under the name
`remap(from, to)`.

| Symbol | Change | Magnitude | Old behaviour reachable from |
| --- | --- | --- | --- |
| `Functions.ramp` | a value line from `start` to `end` instead of `(x - from) / (to - from)` | bit-identical for `ramp(0.0, 1.0)` on the default `0.0..1.0` domain, which is why the mismatch stayed hidden for so long; unrelated otherwise — `ramp(1.0, 6.0)` over `0..1` used to give `-0.2..0` and now gives `1..6` | `remap(from, to)`, which is the old function under a new name |

What was swept: the chebyshev demo sweep and about twenty doc examples, every one of which had been
written expecting the value-line reading. `OpRegistry` gained a `remap` `OpSpec` and `fnrewrite.js`
gained the word to `opWords`, which is what keeps the editor-to-registry sync test green.

A saved workspace calling `ramp` over any domain other than `0.0..1.0` computes something different
after this change, and `remap` is the like-for-like replacement.

## 2026-07-28 — the KDoc-truth pass (`nightly/2026-07-28/2-kdoc-truth-and-guard`)

Twenty-two code defects were fixed by making the code keep a promise its published KDoc was already
making. Most are pure bug fixes with no reachable old behaviour worth preserving; the ones below
change output for input that used to "work".

### The output-changing set

| Symbol | Change | Magnitude | Old behaviour reachable from |
| --- | --- | --- | --- |
| `Functions.fbm`, `Functions.fbmPerlin` | the `amplitude` parameter is now honoured. It used to cancel out of `Σ aᵢvᵢ / Σ aᵢ` exactly, so the output was always the unit-amplitude shape. | **bit-identical at the default `amplitude = 1.0`** (the loop accumulates at unit amplitude and multiplies by exactly 1.0 at the end); scales linearly otherwise, so `amplitude = 0.01` is now 100× quieter than `amplitude = 1.0` instead of identical to it | any explicit non-1.0 amplitude |
| `Fn.convolve(normalize = true)` | divides the step-scaled sum by the step-scaled weight. It used to divide a scaled sum by an unscaled weight. | ~63× **up** at the defaults; exactly `1.0` for a flat unit signal at every width × sample-count pair tested | any `normalize = true` call |
| `Fn.movingAverage` | is now a mean. A constant `1.0` came back as `2.0` at `samples = 2` and `1.0158…` at the default 64. | up to 2×; 1.6% at the default. `Functions.lowPass` / `highPass` move with it | every call |
| `List<Fn>.palindrome` | `[a, b, c]` now plays `a b c b a` — five slots, domain `0..5`. It used to drop both ends of the return leg and span `0..4`. | one slot longer for lists of three or more; any timeline arithmetic downstream of the domain shifts | every call on a list of 3+ |
| `Fn.tile` | declares the span its copies occupy rather than its source's domain, so `f.tile(n).plot()` draws all of it | declared domain only; sample values unchanged | every call |
| `Fn.sampleZeroCrossings` | interpolates from the correct side of the step (`x - step*(1-t)`, not `x - step*t`) | crossings move by up to one step, mirrored about the step's midpoint; correct at `t = 0.5` before and after | every call |
| `Fn.toDoubleArray(step, …)` | samples every `step` units. It used to forward to the count-based overload, which re-spaces to land on `max`. | a step of 0.3 over `0..1` used to sample at 0, ⅓, ⅔, 1 and now samples at 0, 0.3, 0.6, 0.9 (+1.0 with `includeEnd`) | every call with a step that does not divide the domain |
| `PeakDetector` | holds at the current sample instead of decaying below it | the envelope no longer sawtooths between `A` and `A·decay` on a constant input | every call |
| `DynamicRangeCompressor(kneeWidthDb = 0f)` | a hard knee is a hard knee. It used to compute `0 / (2*0)` and return **NaN** for a sample exactly on the threshold — which digital silence reaches, because the envelope floors at exactly −100 dB. | NaN → a real number | `kneeWidthDb = 0f` |
| `Collection<Vec3>.obbBounds` | returns a box that contains its own points (it used to use half-widths about the centroid) and no longer NaNs its axes on a collinear cloud | box is up to 2× larger per axis | every call |
| `PairContext.axis` (interaction nets) | a *coincident* pair falls back to `(1, 0)` instead of normalising a zero vector into `(NaN, NaN)`. The old guard compared the floored length against the same literal it was floored with, so the fallback was dead code. | only for a pair whose two nodes are at the same position — every node in a `net { }` built without explicit positions starts at the origin. **Separated pairs take a bit-identical path.** | a positionless `net { }` that fires a rule using `axis` |
| `FragmentBuilder.node` / `RewriteBuilder.node` / `AutoRuleBuilder.node` | the duplicate-name `require` now actually fires (it compared a fresh identity-keyed `NodeRef` against the map's keys and never matched) | a rule that reused a node name used to quietly build two nodes and now throws | a rule set with a repeated node name |
| `keyframer`'s Kotlin export | a captured `Float` emits `1.5f`, not `1.5` | pasted code stores a `Float` where it used to store a `Double`; a `1.5f` literal will not compile where a `Double` is required | copying a Float-valued keyframe as Kotlin |
| `File.safeSave` call sites in `saved.kt` | both now pass `atomic = true`, as three KDoc blocks already promised. The non-atomic path renames the existing file away *before* writing, so an interruption left nothing at the path. | no output change; a different failure mode | crash during a `saved` write |
| `Fn.stretchBy` | the `outside` parameter is **gone** — it was unreachable under the `EdgeMode.CLAMP` the function uses | source-incompatible for anyone passing it (no in-repo caller did) | `stretchBy(outside = …)` |
| `String.abslutePath` | `@Deprecated` with `ReplaceWith("absolutePath")`, still working | a deprecation warning | the misspelling |
| `UKFOptions` | rejects a non-finite `kappa` at construction | an exception instead of silent NaN | `kappa = NaN` |

### What was swept, and the verdict

Whole-repository sweep for call sites of every symbol above (`src/`, `dev_docs/`, `public/docs/`,
`templates/`, `config/`, `server/`), reading each hit rather than counting it:

- **`src/` Kotlin** — the only production call sites of `fbm`/`fbmPerlin` are `RectSL.kt`'s `fbm` word
  (always the default amplitude, so bit-identical) and `FunctionOps.buildFbm`/`buildFbmPerlin`, which
  forward whatever the graph carries and are therefore *supposed* to move. `jvmTest` covers the rest.
  StackSL's `fbm` and `ImagingGallery`'s `tile`/`tiles` are unrelated names in other languages.
  `TidalPatterns.palindrome` and `dynamic_graph_systems.kNearest` are different types entirely.
- **Committed docs captures** — every `fbm`/`fbmPerlin` call in `public/docs` was read for its
  amplitude. Twenty of them pass a non-1.0 amplitude or a changed function and all twenty have been
  regenerated through the real capture pipeline (17 + 2 + `diff_runner_after.png`). The rest pass
  `amplitude = 1.0` explicitly or by default and are bit-identical.
- **`public/docs/rectsl_examples.md`** and the RectSL gallery stills — the RectSL `fbm` word never
  passes an amplitude, so nothing in that gallery can move.
- **`public/docs/interaction_nets_examples.md`** — all four examples position every node explicitly
  with distinct `at =` vectors, so no pair is coincident and the `axis` fix takes the identical
  branch. Captures unaffected; verified by re-running `stdlib.interaction-nets.smoke`.
- **`dev_docs/agents/transcripts/`** (28 transcripts) and their materialized starting points — **no
  hits at all** for any changed symbol.
- **`templates/field.sketch2_examples.edn`** — one hit, `pointsOut`, inside a commented-out line; the
  box hand-rolls the same loop below it.

## 2026-07-29 / 2026-08-03 — marching squares: the `inside` test, the crossing clamp, the saddle tie

Recorded here on 2026-09-07, lifted out of `contours.kt`'s file KDoc, which was publishing the whole
comparison onto `public/docs/reference/graphics.md`. Three changes to `generateIsoContours` landed
across `df75721c5`, `102c2f77e` and `c1580d70b`; the first two move geometry a caller may have
captured.

| change | when it can alter the output | how much |
| --- | --- | --- |
| the two-valued `inside` test (`df75721c5`) | **only** when some grid sample is exactly equal to `iso` | unbounded — a whole contour appears where there was none, or four arcs become one loop |
| `ISO_CROSSING_EPSILON` (`df75721c5`) | when a crossing lands within a thousandth of a cell of a grid corner | at most `1e-3` of a cell edge, an analytic bound |
| the saddle decider (`102c2f77e`) | any cell with four crossings whose bilinear saddle disagrees with the old fixed pairing, including on perfectly smooth fields | two segments re-routed within that one cell, so up to about a cell |

Only the third reaches ordinary, non-degenerate input. A one-off sweep of 392 (field, resolution,
iso) combinations across eight fields put 96 cases in the first row, **0** cases with no
exactly-on-`iso` sample, a worst attained crossing drift of `9.93e-4` of a cell, and 4 cases in the
third row — all four the single saddle cell of `f = x*y`. **That sweep is not in the tree.** What is
pinned lives in `SpatialGeometryTest`:
`theCrossingClampMovesNothingByMoreThanAThousandthOfACell` asserts the `1e-3` bound and that the
worst case attains it, and `theSaddleTieIsBrokenTheSameWayAtEveryResolution` asserts pairing `P` at
eight resolutions. The other figures above have no guard and should be re-measured, not cited.

`c1580d70b` is source-compatibility only: the `pointWeights`/`edgeWeights` `List<Double>?`
parameters became the `PointEdgeWeight` / `FLinePointWeight` callbacks. No in-repo caller passed
them, and a caller who did got an `IllegalArgumentException` on the `FLine` overloads more often
than a contour, because the list had to be sized against an internal flattening.
## 2026-07-30 — `chatterjeeXi` stopped scoring arrival order through an x-tie (`70413eba3`, `fcfa970c6`)

`StatsDependence.chatterjeeXi` broke ties among the `x` values by input index, so wherever `x` had
ties part of what it measured was how nearly `y` was a function of *the order the samples arrived
in*. It now returns the exact expectation of xi over all random tie-breakings, which is what
Chatterjee (2021) leaves free, and stays O(n log n).

| Symbol | Change | Magnitude | Old behaviour reachable from |
| --- | --- | --- | --- |
| `StatsDependence.chatterjeeXi` (and `xi`) | ties among `x` are resolved by expectation instead of by input index | **bit-identical when every `x` is distinct** (every tie run has m = 1 and the arithmetic reduces term for term). On tied `x`, measured against the pre-fix code: a constant `x` with `y` in arrival order read `0.9702970297029703` and now reads exactly `0.0`; `x = i % 2` with `y` the arrival index at n = 300 read `0.9702330025889176`; `x = i % 3` read `0.950566`; ten blocks of thirty with a real relationship read `0.990033`, inflated from `0.891` | nowhere — the old numbers were scoring arrival order, and there is no reason to want them back |

What was swept: every published xi on `statistics_examples.md` and `interpolants_examples.md` was
checked and none moved, because every `x` in both pages is tie-free and continuous.

**A recorded xi over tied `x` from before 2026-07-30 is wrong**, not merely differently
conventioned. Quantised sensors, binned covariates and categorical predictors are all tied `x`.

The companion commit `fcfa970c6` corrects the claim that went with it: the constant-predictor case
is exactly zero in the closed form, and in `Double` it lands within one ulp — over n = 2..400, 54
values of n come back non-zero, the largest being `2.2e-16`.

## 2026-08-06 — `Fn.floor()` joins the transform family

One op added, and one declared reproducibility consequence.

`Fn.floor()` is new: a first-class, **differentiable** staircase node (derivative 0, which is the true
derivative everywhere it exists). It was added because `FnNonDifferentiable`'s own KDoc had been naming
`FnExpr.Floor` as the single highest-value gap in the backlog — the piecewise oscillators need nothing
else — and because RectSL's `loop-time`, `pingpong-time`, visibility gates and hold tracks are all the
same problem: `clamp01(floor(x) + 1)` is an exact step function.

| surface | change | who notices | how to spot it |
| --- | --- | --- | --- |
| `OpRegistry.opsInFamily("transform")` | size 19 -> 20 | **seeded `evolve` runs that mutate a transform node**. `FnMutations.substituteOp` indexes the family by `rng.nextInt(size)`, so the same seed now picks a different replacement op. Fitness and output change; nothing throws. | any `evolve(..., rngSeed = n)` whose trace touches a transform node |
| `FnAutoDiff.differentiableOps` | 31 -> 32 (25.4% -> 25.8% of canonical ops) | `fitGradient(gradient = EXACT)` now *accepts* a graph containing a `floor` instead of refusing it. A fit whose only path to a parameter runs through one will descend a gradient of exactly zero and report convergence without moving — the same trap `sign` is deliberately refused to avoid. RectSL's `Track.fitParameters` detects and refuses that case by name; a bare `fitGradient` does not. | `EXACT` on a graph with a `floor` |
| `FnNonDifferentiable` `missing` prose | three groups (piecewise oscillators; the x-axis folds; `wrapY`/`quantize`/`crush`) reworded from "needs `FnExpr.Floor`" to "needs only the transcription" | nobody at runtime; it is a docs/planning fact | reading the backlog |
| `fnrewrite.js` `opWords` | gains `floor`, `floorY` | the editor highlights and completes them after `op=` | typing `op="floor"` in a rewrite rule |

Not a change: `Fn.eval` for every existing graph, every existing formula, and the finite-difference
fit path are all untouched.

## 2026-08-19 — the angle family: `Fn.atan()`, `Fn.atan2()`, `Fn.hypot()`

Three ops added, one arithmetic fix, and one declared reproducibility consequence — the same shape of
entry as the `floor` pass above, and for the same underlying reason: RectSL needed a primitive.

`RectSL`'s `->face-travel` builds a `HeadingTrack`, which is `atan2` of a central finite difference,
and it was the last closable refusal in the differentiable backend — 116 of the gallery corpus's 2754
tracks, and every refusal in that corpus that was not a pattern track. Closing it took an `atan2`
that `Fn` did not have.

Worth recording *why* it is a primitive rather than the composition the old refusal text predicted
(`FnExpr.Atan` plus a quadrant fix-up). `atan(y/x) ± pi` discards the sign of a zero in its first
operation — `-0.0 / -1.0` is `+0.0` — so it answers `+pi` where `atan2(-0.0, -1.0)` is `-pi`. Half a
turn, from two inputs that compare equal, and no `FnExpr` comparison can tell them apart afterwards.
`RectSLHeadingTest.theQuadrantFixUpIsHalfATurnOutAtANegativeZero` keeps that as a measurement.

| surface | change | who notices | how to spot it |
| --- | --- | --- | --- |
| `OpRegistry.opsInFamily("transform")` | size 20 -> 23 | **seeded `evolve` runs that mutate a transform node**, exactly as in the 2026-08-06 entry: `FnMutations.substituteOp` indexes the family by `rng.nextInt(size)`, so the same seed now picks a different replacement op. Fitness and output change; nothing throws. | any `evolve(..., rngSeed = n)` whose trace touches a transform node |
| `FnAutoDiff.differentiableOps` | 32 -> 35 (of 127 canonical ops, 27.6%) | `fitGradient(gradient = EXACT)` now accepts a graph containing an angle op instead of refusing it. Unlike `floor`, these carry a *real* derivative, so this is a straightforward gain | `EXACT` on a graph with an `atan`/`atan2`/`hypot` |
| `RectSLFnLowering` for `HeadingTrack` | refusal -> exact lowering | `Track.toFn` and `Track.fitParameters` now succeed on any track containing a `->face-travel`, where they used to throw with a stated reason. The gallery differential went 95.8% -> 100% of tracks lowered | any RectSL scene with `->face-travel` or `->face-travel-at` |
| `RectSLFnLowering` for `TrackOp2.DIV` / `TrackOp3.SMOOTHSTEP` | refusal -> exact lowering for **non-constant** operands too. The near-zero guard is expressed by guarding the *divisor* (`b*live + (1-live)`, so nothing ever divides by a near-zero and no infinity is created to mask), with the sign of the suppressed zero repaired by adding `-0.0`/`+0.0` — `x + (-0.0)` is the true additive identity on doubles and `x + (+0.0)` is not | `Track.toFn` and `Track.fitParameters` succeed on a `/` or a `smoothstep` whose operands vary in time, where they used to throw. Bit-identical to the sampler across 2001 samples including the sample where the guard fires | any RectSL track dividing by, or smoothstepping between, non-constant tracks |
| `TrackOp2.MOD`'s refusal text | rewritten, because it was **wrong**. It blamed a missing sign primitive; the sign is expressible (`trunc(z) = s*floor(z) - (1-s)*floor(-z)`, exact at every double). The real obstacle is that Kotlin's `%` on doubles is the *exact* remainder while `a - b*trunc(a/b)` is not once the quotient outgrows 53 bits — measured: `1e17 % 3` is `1.0` and the reconstruction says `0.0` | nobody at runtime; `MOD` refused before and refuses now. It is a planning fact, and the reason it is recorded is that acting on the old text would have shipped a silent wrong answer | reading the backlog |
| `RectSL` `Track.fitParameters` | a named free parameter occupying several θ slots is now moved as one variable (gradients summed, step applied to all slots) | a fit whose parameter appeared more than once in the *lowered graph* used to move only the last occurrence, so `TrackFitResult.fitted` and `.fittedFn` could disagree. Reachable before today only through a track like `x + x` over one `FreeParamTrack`; a heading makes it the common case (36 slots) | any fit where `freeParameterSlots(fn)` reports a list longer than 1 |
| `afield.stdlib.hypot(AD, AD)` | the value is `Math.hypot`, not `sqrt(x*x + y*y)`, which is what its own KDoc had always claimed | overflowed for components above ~1.3e154 and underflowed below ~1.5e-162; up to one ulp elsewhere. Anything reading a tracked length moves by at most an ulp | `hypot` on extreme components |
| `fnrewrite.js` `opWords` | gains `atan`, `arctan`, `atan2`, `arctan2`, `hypot`, `length` | the editor highlights and completes them after `op=` | typing `op="atan2"` in a rewrite rule |
| `RectSLFitFigure.Band` | gains an optional `rotation: ((Double) -> Double)?`, drawing the strobed rect rotated with a nose pip | additive; a band without it draws exactly as before | passing `rotation =` |

Not a change: `Fn.eval` for every existing graph, every existing formula, the sampled output of every
existing RectSL scene (the CPU sampler and the GLSL emitter are untouched), and the finite-difference
fit path.

---

## 2026-08-26 — `fromContext` stopped seeing this box's own variables (`nightly/2026-08-26/2-salvage-verification`)

The change landed in bafa9ee5f (`afield.four.modules`, `Mesh.kt`), whose commit message says the
headline fix "is committed on the strength of its reasoning and the fact that its test compiles, not
on a green run." This entry is what a green run, and a red one, then said.

`fromContext` / `fromContextWithNameOrNull` used to search, in order: this box if it is an `A`, this
box's **properties**, this box's **top-level (`ki`) variables**, then each ancestor generation's
properties and variables. Step 3 is now skipped unless `includeLocalVariables = true`.

### What the bug was, reproduced rather than argued

`examples.context.local-variable-shadowing` was run against `Mesh.kt` reverted to its pre-bafa9ee5f
state. Box output, verbatim, three boxes over two passes:

```
[normal] shadow_parent: parent set 1
[normal] shadow_child:  child pass1 holder=1
[normal] shadow_parent: parent set 2
[normal] shadow_child:  child pass2 holder=1      <-- parent says 2
```

The child inherited `1` correctly on its first execution, left an `AtomicInteger`-typed variable
behind, and on its second execution found that instead of the parent's new `2`. Against the committed
`Mesh.kt` the same line reads `child pass2 holder=2`. So the defect was real, the fix works, and the
test is not a tautology — it is red on the old code with exactly the diagnosis it names.

### Who else is affected: the search order, measured step by step

`examples.context.search-order-ledger` stages the two steps that had no committed artifact anywhere,
in tree or in the published transcript corpus. Observed:

```
[normal] own_property: own-property pass1 found=11
[normal] own_property: own-property pass2 found=11
[normal] own_variable: own-variable pass1 default=absent
[normal] own_variable: own-variable pass2 default=absent
[normal] own_variable: own-variable pass2 optin=22
```

| shape | before | after | who is in this shape |
| --- | --- | --- | --- |
| child box reading a value its **ancestor** holds, as a property or a top-level variable | worked on pass 1, self-pinned from pass 2 on | works on every pass | `val renderer: Renderer = fromContext()` — the idiom `dev_docs/agents/AGENT_QUICK_REF.md` teaches and every graphics box in `public/docs/afield_four_graphics.md` opens with (10 occurrences on that page alone) |
| box reading a value **it holds as a property** | worked | **still works** — step 2 was not touched | the documented renderer box: `public/docs/running_code.md` publishes with `prop<Window>(window)` / `prop<Renderer>(renderer)`, which is this bag. This is why nothing on the graphics surface broke |
| box reading a value it holds **only as a bare top-level `val`/`var`**, with no ancestor holding one | worked from pass 2 on | **throws** `IllegalArgumentException: must pass in a value for context parameter …` | the cost of the change. No call site in `src/`, no committed transcript and no published docs box is in this shape; `includeLocalVariables = true` is the fix if one appears |

### What was swept, and the verdict

Enumerated rather than counted — and then the count had to be given a predicate, because three
different true numbers were in circulation within an hour and none of them was wrong. This is the
`"exactly four registrations"` failure mode (a count quoted against ~56) arriving from the other
direction, so the rule is worth restating: **a count of `fromContext` is meaningless without the
tree, the revision, the path set, and whether it counts matching lines or call sites.**

| number | tree | revision | path set | unit |
| --- | --- | --- | --- | --- |
| **188** | this worktree | `730440b80` | `src`, minus `four/modules/Mesh.kt` | matching **lines** (50 files) |
| 201 | this worktree | branch HEAD | same | matching lines (51 files) |
| 210 | primary **working directory** | `730440b80` | same | matching lines |
| 217 | primary working directory | `730440b80` | `src`, *including* `Mesh.kt` | matching lines |
| 293 | this worktree | branch HEAD | `src` + `dev_docs` + `public/docs` | matching lines (78 files) |

They reconcile exactly, and each gap is a different question being asked:

- **210 − 188 = 22**, all in `src/personal/`, which `.gitignore:243` excludes. A grep of a *working
  directory* is not a grep of the repository; use `git grep <rev> -- src` when the answer is meant to
  be about committed code.
- **201 − 188 = 13**, all in `ContextSearchOrderLedgerSmokeTest.kt` — the test *this very entry*
  added. The number moved because the measurer moved it. Any count of a symbol quoted in a commit
  that also adds uses of that symbol has to name the revision it was taken at.

Of the 188 at `730440b80`: 30 `import` lines, 16 KDoc/comment prose lines, 5 in JavaScript (editor
completion word lists), and **137 real Kotlin**. "Call sites" is a smaller number again and was not
counted — 137 is lines of Kotlin mentioning the symbol, which includes multi-`fromContext` parameter
lists as one and spans two functions (`fromContext` and `fromContextWithNameOrNull`) throughout.

Of the 137, essentially all are **default parameter values in library constructors and functions** —
`Camera(val renderer: Renderer = fromContext())`, `ComputeMesh`, `MeshMesh`, `KeyCamera`,
`OrbitControls`, `KeyEvents`, `Projection`, `VideoTexture`, `WebcamTexture`, `TextureSampler`,
`standardRenderer.build`, and the seven `moduleHere`/`shadertoyHere`/`computeModuleHere` family
entries in `shaders.kt`. Those run *inside the calling box's* context, so they are the sites where
step 3's removal could have bitten. Four are name-matched lookups into an ancestor
(`magic.kt:282`, `Module.kt:106`, and two in `CrossBoxContextInheritanceSmokeTest`).

Driven, not reasoned about — every one of these passed against the committed `Mesh.kt`:

- `examples.demo-transcript.exec` (193.7s) — which runs `two_box_context`, the published
  parent/child context demo at `public/docs/assets/agent_transcripts/two_box_context/`, plus 20-odd
  other demos. This is the **negative control**: its child reads an ancestor *property*, the half the
  change did not touch, and it must still pass byte for byte. It does.
- `docs.fabricate.default-scene`, `docs.fabricate.shadows` — `standardRenderer.build(renderer =
  fromContext())` through the real GPU path.
- `docs.stacksl.gallery` — `stacksl.kt`'s two `renderer: Renderer = fromContext()` entry points.
- `examples.context.cross-box-inheritance` — the ancestor ki-var path specifically (`ctx_kivar`).
- `examples.exports.batch-launch`, `core.compiled-snippet.smoke`, `core.repl-bootstrap-gate.smoke`.

The rest of the published corpus, driven after the first pass showed the gap. `fromContext` appears
in five published pages and three transcript bundles, and `examples.demo-transcript.exec` classifies
`afield_four_traditional_triangle`, `rectsl_sdf_live` and `imgui_vr_panel` as **`COMPILE_ONLY`** —
every box compiled through the real K2/preamble path, **none evaluated**. So passing that smoke does
not mean `fromContext` was ever *called* for those three. Their real consumers were run instead:

- `examples.four.imgui-vr-panel-smoke` — `ImGuiStereoQuadExample`'s three defaults in one parameter
  list (`window`, `renderer`, `sbs`), the `imgui_vr_panel` bundle's evaluating consumer.
- `rectsl.examples.smoke`, `rectsl.language.workout`, `rectsl.dataviz.proof` — `RectSL.kt`'s six
  `renderer: Renderer = fromContext()` entry points, evaluated rather than compiled.
- `docs.rectsl.gallery`, `docs.rectsl.fit` — the same six through the docs-capture path.
- `docs.fabricate.toy-car-gltf` — `simpleGLTFSupport.applyToMesh(camera: Camera = fromContext())`.
- `four.ply-loader.smoke` — `simplePLYSupport`'s two `camera: Camera = fromContext()` overloads.

`afield_four_traditional_triangle` remains **compile-only everywhere in the smoke catalog**; its
pixels are only checked by the `afield_four_graphics.md` docs capture, which is a docs-build step and
not a catalog id. That page opens ten of its examples with `val renderer: Renderer = fromContext()`,
so it is the largest single consumer of this idiom with no smoke behind it. Named, not closed.

### `docs.rectsl.sdf` is a third pre-existing red on `main`, and nothing can record it

Not a consequence of this change. Not a consequence of this branch. Measured at `730440b80` itself,
in this worktree detached to that commit with **none** of this branch's commits present (checked:
`tools/test_field_sh_launcher.py` and `ContextSearchOrderLedgerSmokeTest.kt` both absent,
`git rev-parse HEAD` = `730440b8088cd5cdc751787be50d61d7d0279b19` during the run):

```
[1/1] FAIL docs.rectsl.sdf (9987 ms)
  wait-file /tmp/docs-rectsl-sdf-hero.png --timeout 900.0 --fail-on-error-uid @last:
    target 009aa8bf-1e45-4fd3-9c5d-ee5c6709edbf emitted an error while waiting for file:
    Out of stack space.
  wait-file ... line 348: Out of stack space.
  expected image was not created: /tmp/docs-rectsl-sdf-hero.png
Summary: 0 passed, 1 failed
```

Character-for-character the same failure as on branch HEAD, and the same again with `Mesh.kt`
reverted to pre-bafa9ee5f — three measurements, one result. `Out of stack space.` is LWJGL's
`MemoryStack`, not glslang, and it bites *generated* shaders; `RectSL.kt`'s SDF lowering emits one.
Worth measuring rather than asserting, because `RectSL.kt` has six
`renderer: Renderer = fromContext()` entry points that `docs.rectsl.fit`, `docs.rectsl.gallery` and
`docs.stacksl.gallery` share — and all three of those pass.

This is a **third** pre-existing red, distinct from the two known `jvmTest` ones in `afield.rectsl`
(`RectSLGlslSdfTest > gallery emission reports no fidelity caveats` and
`RectSLTrackCharacterizationTest > gallery programs sample identically`).

And it cannot be registered anywhere, which is why a third red could sit unrecorded:
`dev_docs/testing/expected_test_failures.json` is an empty array, and
`tools/check_expected_test_failures.py` globs `build/test-results/**/TEST-*.xml` and keys on JUnit
`classname`/`name`. `docs.rectsl.sdf` is a **FIELD_TEST catalog id** run by `tools/run_field_tests.py`,
which emits `results.json` and no JUnit XML. The smoke runner's only opt-out is `skipOn:` for
platforms. So there is no mechanism on the smoke side that can hold a known red at all — a gap in
the harness, not a gap in anyone's diligence.

Not a change: this box's properties, every ancestor's properties, every ancestor's top-level
variables, and `fromContextWithNameOrNull`'s name and type filtering.

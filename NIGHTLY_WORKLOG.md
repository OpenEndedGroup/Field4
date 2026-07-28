# Nightly worklog — kdoc-truth-and-guard

**Branch:** `nightly/2026-07-28/2-kdoc-truth-and-guard` (based on `1a347dc8`)
**Kind:** B — defect / ergonomics
**Mission:** make the generated documentation *true*, and make it stay true. A build-enforced KDoc
attachment guard; then fix the wrong published claims and the real code defects that last night's
KDoc-coverage campaign surfaced.

## Plan (written before execution, revised against the real code)

### Strand 1 — the durable one: a build-enforced KDoc attachment guard

`KDocExtractor.extractKDocs` attaches a comment by walking *forward* until `parseDeclaration`
recognises something. It does not stop at a line it cannot read — it skips it. So a KDoc above an
unparseable declaration is not merely dropped, it **slides onto the next declaration** and is
published as that symbol's prose (shipped on 2026-07-26: a `seg(...)` row captioned with
`FLine.hoverColor`).

New: `src/jvmMain/kotlin/afield/docs/KDocAttachmentGuard.kt`, Gradle task `checkKDocAttachment`,
wired into `check` beside `checkCodebaseReference`. Reports `DROPPED` and `STACKED` findings over
every `.kt` under `src/`; needs no server, no GPU, no network. Source-lexed (not grepped) so a
KDoc-shaped *string literal* is not mistaken for a comment and a closing marker *inside* a comment
really does end the block. Known-benign, with reasons in the file's own KDoc: file headers above
`package`, enum constants, and a file overview stacked above the first declaration's own KDoc.

### Strand 2 — the wrong published claims

Verified against the code before touching anything; case-by-case call on doc-vs-code.

### Strand 3 — the real code defects

`fbm`/`fbmPerlin`'s inert `amplitude`; `sampleZeroCrossings`'s interpolation; `kNearest`'s
`return@forEach`; `mat3`'s ignored `t`; `UKFOptions.kappa`; the stacked-KDoc sites.

Findings, decisions and evidence are recorded below as the work happened.

## Guard: first real hit list (before any source fix)

`./gradlew checkKDocAttachment`, over 624 files / 6750 KDoc blocks: **32 findings**.

Two systematic classes came out of that list and were fixed in `KDocExtractor.parseDeclaration`
itself rather than site by site:

- **`typealias` was not a declaration this parser could see** — 13 blocks. Every documented
  `typealias` in the tree (`linalg.kt`'s `Vec2`/`Vec3`/`Vec4`/`Quat`/`DualQuat`, `contours.kt`'s
  `ScalarField2D`, `stdlib.kt`'s `Vec2h`/`Vec3h`/`Vec4h`, `FunctionGraph.kt`'s `FnGraphRebuilder`,
  `ReturnValueRegistry`, `BooleanNetworkSequencing`, `units.kt`, `pf.kt`) had its KDoc dropped and
  its prose slid onto the next symbol — and `typealias` is in the reference generator's `TYPE_KINDS`,
  so those rows were published with the wrong summary or none.
- **`actual` / `expect` were missing from the modifier list** — a documented `actual fun` in a
  platform source set slid.
- Plus `constructor`, now recognised as itself purely so a KDoc above a secondary constructor *stops*
  there instead of sliding.

Guard fidelity fixes made while reading the list (both were false positives, both real behaviour of
the extractor): a multi-line annotation argument list (`@Target(` + a list of targets) is stepped
over as a unit, and an annotated enum constant (`@SerialName("wire") WIRE,`) is benign.

**32 → 11** after those. The remaining 11 are genuine per-site defects, fixed one at a time below.

## What landed

Seven commits on `nightly/2026-07-28/2-kdoc-truth-and-guard`, 53 files.

### 1. `checkKDocAttachment` — the durable piece (`834966ad`)

`src/jvmMain/kotlin/afield/docs/KDocAttachmentGuard.kt`, Gradle task `checkKDocAttachment`,
`dependsOn`'d from `check` beside `checkCodebaseReference` (`./gradlew check --dry-run` lists both).

It lexes each `.kt` file rather than grepping it — a KDoc-shaped *string literal* is not a comment, a
closing marker inside a comment really does end the block, and a declaration-shaped line inside a
block comment is a finding rather than a pass — then, for each block, replays
`KDocExtractor.extractKDocs`'s forward walk and judges the line it stops on with
`KDocExtractor.parseDeclaration` itself. `parseDeclaration` went from `private` to `internal` for
exactly that reason: it *is* the definition of attachment, so the guard has to ask the same question,
not a lookalike.

Benign cases are enumerated with reasons in the object's own KDoc (file headers above `package`,
enum constants, and a file overview stacked above the first declaration's KDoc — that last one only
when the blank line the generator needs is actually there). There is deliberately no suppression
comment.

**Hit list: 32 → 0.**

- Two whole classes were *parser* gaps, fixed in `parseDeclaration`: `typealias` (13 blocks — and
  `typealias` is in the reference generator's `TYPE_KINDS`, so those rows were published with another
  symbol's summary or none), and the `actual`/`expect` modifiers. `constructor` was added too, purely
  so a KDoc above a secondary constructor stops there instead of sliding.
- Two guard false positives were fixed while reading the list, both real extractor behaviour: a
  multi-line annotation argument list (`@Target(` + a list of targets) is stepped over as a unit, and
  an annotated enum constant (`@SerialName("wire") WIRE,`) is benign.
- The remaining **11** were per-site defects (`47528205`): `ValueVisualizer`'s `formatFn` and
  `formatDomain` KDocs had both piled up above `formatFnFitRun`, three deep; `resample`'s `@param`
  block sat above `fromKeyframeEditor`'s KDoc so `fun resample` had none; `Cursor.d` and `Cursor.t`
  had their KDoc written above the `get()`, which meant `d`'s description was published on `setD`;
  `FloatEarcut.earcut` had two copies of its KDoc, the second missing two `@param`s;
  `KotlinToJsCompiler.compile` had a lone `@param cacheDir` block orphaning the real description;
  `PythonInterface` had a KDoc on an `init { }` block sliding onto the private function below;
  `Tools.kt` had a section banner written as a KDoc, silently eating the next tool's summary; and
  `playlist_example.kt` — a file with no declarations at all — had its overview attached to
  `val box1 = box("box1")` *inside* an example block comment.

**Demonstrated catch-then-restore**: a probe KDoc above a statement inside `mat3` was reported as
`DROPPED — line 581 is not a declaration KDocExtractor.parseDeclaration can read (\`if (s != null)
m.scale(s)\`)`, and reverted. An earlier probe shape (a KDoc immediately above a `//` line) is
correctly *not* a finding, because the extractor skips comment lines and still lands on `fun mat3` —
it surfaced instead as a `STACKED` report against the block above it, which is the right answer.

### 2. The wrong published claims (`f1d65ca5`)

Each verified against the code before deciding which side was broken. New test
`afield.stdlib.DocumentedBehaviourTest`, written first and watched failing.

| claim | verdict | measured before | after |
| --- | --- | --- | --- |
| `fbm`/`fbmPerlin` "stays within roughly ±amplitude" | **code** — the parameter cancelled out of `Σvᵢgⁱ / Σgⁱ` | peak 0.7389438601763199 at *both* amplitude 0.1 and 10.0 (ratio 1.000000×) | ratio exactly 100×, peak ≤ 1 at amplitude 1 |
| `sampleZeroCrossings` linear interpolation | **code** — `x - step*t` where t is measured from the left sample | `x - 0.25` reported its crossing at **0.75** | 0.25, 0.5, 0.75 all exact |
| `palindrome` "`[a, b, c]` plays `a b c b a`" | **code** — dropped both ends of the return leg | played `a b c b`, domain `0..4` | `a b c b a`, domain `0..5`; `includeEndsTwice` gives `a b c c b a` |
| `convolve(normalize = true)` "keeps the level" | **code** — divided the step-scaled sum by the unscaled weight | constant 2.0 came back as **0.0333** (samples 4, width 0.05); ~63× down at the defaults | 2.0 for every width × sample-count pair tested |
| `c(value, domain)` "every factory that accepts a `Number` … wraps it like this" | **doc** — they wrap as unbounded parameter signals, which is the whole point of the `c` / `.c` asymmetry | — | rewritten, with the `sin(frequency = …)` consequence spelled out |
| `stretchBy`'s `outside` fallback | **both** — `outside` is unreachable under `EdgeMode.CLAMP` | — | dead argument dropped, the clamp documented, and `Fn.stretchBy` given the KDoc it never had |
| `mat3`'s ignored `t` | **neither** — the KDoc already says it is ignored | — | pinned by a characterization test so it cannot change silently |
| `String.abslutePath` | published misspelling | — | `@Deprecated` + `ReplaceWith("absolutePath")`, still working |
| `kNearest`'s `return@forEach` | **code**, perf only — a `LinkedHashSet` keeps the order the first `kEff` arrived in, so the answer was already right; the walk was O(n) inserts long | — | `for`/`break` |
| `UKFOptions.kappa` unvalidated | **both** | — | non-finite rejected at construction; the dimension-dependent half now fails with the actual arithmetic in the message |
| `imaging_simd.kt`'s empty index row | **code**, sort of — the overview was flush against `internal object ImgSimd`, so the generator read it as that object's summary, and being internal it was published nowhere | "What it is" cell empty | one blank line; the essay now reads on `imaging.md` |

### 3. Extended audit — 15 more wrong claims (`9fcbd66e`)

Beyond the mission list: `timeseries.kt`, `numpy.kt`, `randoms.kt`, `keyframer.kt`,
`interpolants.kt`, `correlations.kt` and `math.kt` read in full, plus ~30 falsifiable numeric claims
in `Functions.kt` driven through a throwaway probe test.

**29 of those 30 `Functions.kt` claims measured exactly as documented** — `gaussianWindow`'s 0.458
ends and 0.6065 at one sigma, `fold`'s ±threshold bound, `select`'s rounding and clamping,
`monotoneCubic`'s zero overshoot, `catmullRomSpline` passing exactly through its points,
`Domain.EMPTY.length`, the count-based sampler's endpoints and `includeEnd` spacing, `perlinNoise`
peaking at exactly half `valueNoise`. That ratio is the useful result: the campaign's prose is mostly
sound and the exceptions are specific.

Code was wrong in four more places: `Fn.toDoubleArray(step, …)` did not sample every `step` units (a
step of 0.3 across `0..1` sampled at 0, 1/3, 2/3, 1, because the count went to the count-based
overload which re-spaces to reach `max`); `PeakDetector` decayed whenever the sample was not a *new*
high, so a constant input sawtoothed between `A` and `A·decay` and the output could sit below the
sample it was enveloping; `DynamicRangeCompressor` with `kneeWidthDb = 0` ("0 gives a hard knee")
computed `0 / (2 * 0)` and returned **NaN** for any sample exactly on the threshold, which silence
reaches because the envelope floors at exactly −100 dB; and `keyframer`'s `kotlinNumberLiteral`
emitted a `Float` as `1.5`, a *Double* literal, against a KDoc promising "numbers keep their type".

Eleven doc-side claims were false and are rewritten — `AGC`'s "frozen during exact silence" (the
envelope decays multiplicatively and never reaches zero, so the gain climbs toward `maxGain` for the
whole gap), `MedianFilter`'s parity claim (it is the *count*'s parity, not the window's),
`PercentileThresholder`'s denominator, `DynamicRangeCompressor`'s "minutes" (3.7 s), `noise1D`'s
"roughly `-1..1`" (it is bounded by ±0.5 — doc, not code, because doubling it would silently change
the amplitude of every box already using it), `noise1D`'s "independent" `seedOffset`/`extraHash`
(summed into one scalar), `noiseForward`'s units, four numpy claims (uint64 throws rather than
wrapping; a complex dtype throws rather than yielding its real part; a short payload raises
`EOFException`; `asFloatArray` peaks at 3× not 2×), `KValue.toAny`'s quaternion normalisation, and
`smoothTo`'s off-by-one.

### 4. The published pages

`./gradlew generateCodebaseReference` after every prose change; `checkCodebaseReference` green.
Documented-symbol count 2141 → 2153 of 4011 — that is KDoc which already existed and was being
dropped, not new prose. `imaging_simd.kt`'s index row filled in, and the generator now strips
*trailing* block tags from a file overview so its `@see` lines stopped rendering as body text.

`public/docs/functions_examples.md` had 17 committed captures whose output the `fbm` and `convolve`
fixes move. Deleted exactly those PNGs and re-ran the real capture pipeline for that page:
`regenerated=17, reused_existing=270, failures=0`, 56.6 s, one shared Field server on port 9722,
nothing else on the page touched. Then *looked* at them: the `sin(3.0) + fbm(1, 7.0, 0.25)` figure is
now a unit sine with a quiet noise ride (it used to be full-amplitude noise), the normalized
convolution reads on a real signal scale instead of a 1/333 sliver, and the palindrome figure renders
its five slots through `repeatEvery`.

### 5. `dev_docs/kdoc_reference_contract.md` (`ab463258`)

The pipeline, the exact attachment rule, a table of what `parseDeclaration` can and cannot see with
what happens in each case, the load-bearing blank line under a file overview, both guards, and the
rule that cost a build on 2026-07-27: never write an opening or closing comment marker inside a KDoc,
because Kotlin block comments nest. Linked from `dev_docs/README.md` and named in the guard's own
failure message.

### 6. Two further audit passes (`415472fd`, `b35fe91c`, `9bfbbd9f`)

Kept going in the same spirit rather than stopping at the mission list. Method that worked best, and
worth reusing: write a throwaway probe test that *prints measurements* of every falsifiable numeric
claim in a file, run it, and read the numbers. It found `movingAverage` and the step sampler in
minutes, and — as importantly — cleared 29 claims that reading alone would have left uncertain.

The second pass (`415472fd`) found `movingAverage` was not a mean (the constant 1.0 came back as 2.0
at `samples = 2`, 1.0158730158730158 at the default 64, so the high-pass identity `f -
f.movingAverage(w)` returned −0.0159 on a flat signal), and `tile` declaring its source's domain
rather than the span its copies occupy. Two apparent findings in that batch were **my own API
misreadings**, recorded so the next pass does not chase them: `repeat`'s second argument is a period,
not a count, and `softClip`'s `amount` is drive, not a threshold — both exactly as documented.

The third pass (`b35fe91c`) covered `shaders.kt`, `stacksl.kt`, `linalg.kt`, `stdlib.kt`, `colors.kt`,
`StandardDrawing.kt`, `FunctionGraph.kt`, both `FunctionRewrite` files, `spectral.kt` and
`smile_neighbors.kt`: 22 claims, five of them code. The pick of them:

- `obbBounds` returned a box that **did not contain its own points** (half-widths about the centroid),
  and NaN'd its axes outright on a collinear cloud.
- `graphics.pointsOut` threw `IndexOutOfBoundsException` for any `pointed` line containing a
  `close()` — every rect and every arrowhead — because `FLine.Close` carries no coordinate. Its
  `FLineMap` overload also forwarded colours into the point-size buffer.
- `String.times`'s documented "zero or fewer gives the empty string" was the one case that threw.
- `FnGraphEdge`'s entire KDoc described `FnGraphNode`. Worth noting: **the attachment guard cannot see
  this** — the block parses and attaches perfectly, it is simply about the wrong thing. Guarding
  attachment is not the same as guarding truth.
- `fnRewriteNumber`'s KDoc still told readers that returning null falls through to source hydration
  and fails, two commits after `86edb81b` made null mean "leave this value alone" — with the code's own
  inline comment saying the opposite of the KDoc six lines above it.

A fourth pass (`0b72639c`) took the files none of the others reached — `interactionnets.kt`,
`FileManager.kt`, `saved.kt`, `plotly.kt`, and `jsonparsing.kt` / `props.kt`, which came back clean
claim-by-claim. Three more code defects, and the first of them is the sharpest illustration of the
whole night:

- `PairContext.axis` promised "(1, 0) for a coincident pair rather than being NaN" and the guard read
  `if (max(delta.length(), 0.0001f) < 1e-4f)` — **the floor compared against itself**, so the fallback
  was unreachable and a coincident pair normalised a zero vector into `(NaN, NaN)`. Reachable from the
  file's own documented usage, because `NetBuilder.node`'s `at` defaults to the origin. Proving it took
  three attempts: the beta rule computes the NaN and never uses it, so the test only discriminates once
  it fires a *duplication* rule, which is the one that throws its new nodes apart with
  `branchImpulse`. Pre-fix: "node rootL came out of the rewrite with velocity ((NaN, NaN))".
- `saved.kt` promised atomic writes in three places and passed `File.safeSave`'s default
  `atomic = false` at both call sites — and the non-atomic path *renames the existing file away* before
  writing, so an interruption left nothing at the path at all. Both call sites now pass `atomic = true`.
- The three rule-builder `node(name, …)` overloads each `require`d a unique name against an
  identity-keyed `NodeRef`, so the check never fired and three `@throws` clauses were false.

`imgFromFile`'s inline `@param` (`9bfbbd9f`) was the only KDoc in `afield.stdlib` /
`afield.visualization` publishing a block tag as prose; a scan also confirmed no block *starts* with a
tag, which would publish an empty summary while the worklist called the symbol undocumented.

## Verification — what was checked, and how

Verified:

- `./gradlew compileKotlinJvm compileTestKotlinJvm` — green, no new warnings.
- `./gradlew jvmTest` — **514 tests, 0 failures** (501 on the base; 13 new). Every new case was
  watched failing on the pre-fix code first, with the failure text recorded: "got NaN", "got [0.75,
  0.7425]", "node rootL came out of the rewrite with velocity ((NaN, NaN))", "measured 1.000000× (quiet peak 0.7389438601763199, loud peak 0.73894386017632)",
  "sampleZeroCrossings said 0.75", "expected 5 unit slots, domain was Domain(0..4)", "got
  0.03333333333333333", "point (0.0, 0.0, 0.0) is NaN along axis 0".
- `./gradlew checkKDocAttachment` — **32 findings → 0**, over 625 files and 6767 blocks, plus a
  demonstrated catch-then-restore. `--args=--verbose` lists the 84 exempted sites and why: 52 file
  overviews, 29 enum constants, 3 file headers above `package`. Runtime is about a second, which is why
  it can live in `check` unconditionally.
- `./gradlew checkCodebaseReference` — green after each regeneration.
- `./gradlew check --dry-run` — lists `:checkCodebaseReference` and `:checkKDocAttachment`, so the
  wiring is real.
- The 19 regenerated docs captures (17 + 2) — driven through the real pipeline and **looked at**, not
  just counted.
- The published index now has **no empty "What it is" cell**: all 67 documented source files carry a
  file overview that the generator actually prints. `imaging_simd.kt` was the last one, and a scan
  confirmed no other file has a block above its first declaration that the generator is failing to pick
  up. Worth a guard of its own one day, but it would be a coverage rule rather than a truth rule, and
  the pages already report coverage honestly.

Not verified, honestly:

- **`./gradlew check` as a whole does not pass on this branch, and did not on the base either**: it
  fails in `:tools:linalg-bench:compileKotlin`, where a *generated* `Vectors.kt` under
  `tools/linalg-bench/build/` cannot see `floatToHalfBits`. Nothing on this branch touches `tools/` or
  linalg generation (the only `build.gradle.kts` change is the new task plus two `dependsOn` lines).
  `checkKDocAttachment` ran and passed inside that same invocation; `checkCodebaseReference` was
  reached and passed only when run directly, because the bench failure aborts the build first.
- No GPU, window or Field-server test was run beyond the docs capture pass. `pointsOut`'s `Close`
  guard is **not** covered by a regression test: the function has no in-repo caller (it is reached
  from live-coded box code) and faking a `DynamicallyWriteable` GPU buffer for one assertion was not
  worth the weight. It is a two-line guard that mirrors what the Skiko and SVG writers already do.
- Capture staleness was chased by grepping every `diffstatic_runner` fence in `public/docs` for the
  changed functions (`fbm`/`fbmPerlin` with a non-default amplitude, `convolve(normalize)`,
  `palindrome`, `tile`, `movingAverage`, `lowPass`, `highPass`) and regenerating exactly those. I did
  **not** re-run the full docs build, so a capture that reaches a changed function through a helper I
  did not grep for would still be stale.
- `deflateCovariance`'s unscaled deflation is now described accurately but not fixed; a proper
  `λ·vvᵀ` deflation would change which minor axis is which for some clouds and I had no way to
  validate that visually tonight.
- Every file the campaign touched has now had at least one pass over it, but "a pass" is not "every
  block": roughly two thirds of the 1550 blocks have been read against the code. The unread
  remainder is mostly `Functions.kt`'s prose (the numeric claims in it *were* probed),
  `FunctionGraphBrowser.kt`, `numpy`/`timeseries` beyond what the passes covered, and `keyframer.kt`'s
  HTML rendering.

## Risks

1. **`fbm` / `fbmPerlin` `amplitude` is a behaviour change** — the release note. Bit-identical at the
   default `amplitude = 1.0` (the loop accumulates at unit amplitude and scales at the end, which is
   the same arithmetic), and up to 100× different for an explicit non-default amplitude. Anything a
   user has already built with `fbm(seed, freq, 0.3)` will get quieter noise — which is what the
   parameter, the KDoc and the graph edge all always said it would do.
2. **`convolve(normalize = true)` is a ~63× level change** at the defaults, in the direction of the
   documented behaviour. `movingAverage` moves by 1.6% at the default sample count (2× at
   `samples = 2`), and `Functions.lowPass` / `highPass` with it.
3. **`palindrome` is one slot longer** for lists of three or more: `[a, b, c]` now spans 5 slots, not
   4. Any timeline arithmetic downstream of a palindrome's domain shifts accordingly.
4. **`tile`'s declared domain widened** to the span it fills, so `f.tile(n).plot()` now draws all of
   it. The two captures this moved (`repeat-mirrorrepeat-tile-pad-echo.png` for `tile`,
   `transform-palette.png` for `highPass`) were regenerated and looked at as well, so every committed
   capture in `public/docs` whose snippet touches a changed function is current.
5. `KDocExtractor.parseDeclaration` now recognises `typealias`, `actual`/`expect` and `constructor`.
   That moves summaries onto symbols that previously had none — visible in the regenerated diff and in
   the coverage count — and could in principle change a completion card somewhere. It cannot *remove*
   a summary.
6. The attachment guard is now in `check`. If someone writes a KDoc above a `get()` the build will
   fail rather than silently publishing the wrong caption; that is the intent, and the failure message
   names the file, line, offending text and the fix.

## Recommendation

**MERGE.** The guard is the durable half and it is cheap, honest and already green; the twelve
regression tests pin claims that were wrong on `main` this morning; and every published page carrying
a corrected claim has been regenerated, including the seventeen screenshots that the `fbm` and
`convolve` fixes moved.

Read the release note in risk 1 before merging — `fbm(…, amplitude = 0.3)` really does something
different now — and if the `functions_examples.md` `transform-palette` figure matters, regenerate it
(`rm` the PNG, then `FIELD_DOCS_RUNNER_PORT=<free> uv run --group docs python3
tools/build_zensical_docs.py --page functions_examples --skip-build`).

The obvious next piece of work, if the pattern is worth continuing: nothing here guards a KDoc that
attaches perfectly and is simply *untrue*, which is what `FnGraphEdge` was. The two things that
actually caught those tonight were (a) a probe test that prints measurements of every numeric claim in
a file and (b) reading the implementation immediately below each block. The first of those could be
generalised — a `DocumentedBehaviourTest` per file, growing as claims are checked.

---

# Second pass — blast radius, `check`, and a fifth audit

A second agent picked the branch up at `51bd4b0c` to answer the question the first pass left open:
**what else in the repository depends on the behaviour these fixes changed?** Three commits
(`bdffc5fa`, `323157d3`, `1836c7e8`).

## 1. The blast radius, enumerated

The sweep covered every changed symbol, not only the five the mission named — `sampleZeroCrossings`,
`Fn.toDoubleArray(step, …)` and `stretchBy`'s dropped `outside` parameter are behaviour/API changes
too and were not on the first pass's list. Method: grep each symbol across `src/`, `dev_docs/`,
`public/docs/`, `templates/`, `config/`, `server/`, then **read every hit**; then, for the docs, parse
every fence, extract its code and match it against the changed set. That last step is what found the
stale capture, because it lives in a `diff_runner` fence rather than the `diffstatic_runner` fences the
first pass grepped for.

| site | verdict |
| --- | --- |
| `public/docs/functions_examples.md` → `diff_runner_after.png` | **STALE → regenerated.** Renders `fbm(seed = 77L, frequency = 4.0, amplitude = 0.12, octaves = 4)`; the committed PNG had that detail layer at full unit amplitude, swamping the carrier the prose says survives. `regenerated=1 failures=0`, 11.7 s, and looked at. The 18th capture the branch moves |
| the 19 captures the first pass regenerated | verified as the right set — every `fbm`/`fbmPerlin` call in `public/docs` was read for its amplitude, and every non-1.0 one is in that set plus the row above |
| the other 13 `fbm`/`fbmPerlin` docs fences | **unaffected — bit-identical.** The old loop accumulated from `amplitude` and divided by the same accumulated weight; the new one accumulates at 1.0 and multiplies at the end. At `amplitude = 1.0` the accumulation sequence is unchanged and the final multiply is by exactly 1.0. Re-derived from the diff, not assumed |
| `rectsl/RectSL.kt:2491` and the whole RectSL gallery | **unaffected.** The RectSL `fbm` word calls `Functions.fbm(seed, frequency = 4.0)` — it has no amplitude to pass, so nothing in `rectsl_examples.md` or its stills can move |
| `stdlib/FunctionOps.kt` `buildFbm` / `buildFbmPerlin` | **intentionally affected** — they forward `childOr(1.0, "amplitude")` from the graph, so a graph carrying a non-default amplitude now produces the documented output. Covered by `jvmTest`; `evolve-variety.png` was already regenerated |
| `public/docs/interaction_nets_examples.md` (4 captures) | **unaffected.** All four give every node an explicit, distinct `at =`, so no pair is coincident and `PairContext.axis` takes the identical branch — the fix only revives a comparison that was dead. `stdlib.interaction-nets.smoke` re-run: PASS |
| `dev_docs/agents/transcripts/` (28 files) + materialized starting points | **unaffected — zero hits** for any changed symbol |
| `templates/field.sketch2_examples.edn` | **unaffected.** One hit, `pointsOut`, on a commented-out line; the box hand-rolls the same loop underneath |
| `timeseries.kt`'s `PeakDetector` / `DynamicRangeCompressor` | no docs capture and no gallery reaches them; only `DocumentedBehaviourTest` constructs them |
| `stretchBy(outside = …)` | **no caller anywhere** — every `outside =` in the tree belongs to `evalSafe`. Source-incompatible but unreached |
| `String.abslutePath` | one caller, `FileManagerSmokeTest`, already suppressed by the first pass. Smoke re-run: PASS |
| `UKFOptions.kappa` validation | no caller passes `kappa` at all |
| `saved.kt`'s `atomic = true` | `kotlin.safe-save-atomic.smoke` re-run: PASS |
| `keyframer`'s `1.5f` literal | no in-repo consumer of the generated string; the risk is user-facing (a `Float` literal will not compile where a `Double` is wanted) and is in the release note |
| `obbBounds`, `graphics.pointsOut` | definition plus test only; no other call site in the tree |
| `TidalPatterns.palindrome`, `dynamic_graph_systems.kNearest`, StackSL's `fbm` word, `ImagingGallery`'s `tiles`, `Collection.toDoubleArray()` | **false positives — different symbols entirely.** Recorded so the next sweep does not re-chase them |

The release note now lives in **`dev_docs/stdlib_behaviour_changes.md`**, a running ledger linked from
`dev_docs/README.md`, rather than only here — this file is routinely stripped at merge time.

## 2. `check`, actually run

`./gradlew check --continue` at `1836c7e8`. Every check task was reached: `compileKotlinJvm`,
`compileTestKotlinJvm`, `jvmTest` (**executed**, not up-to-date — 514 tests, 0 failures, 0 errors, 0
skipped, counted out of `build/test-results/jvmTest/*.xml`), `allTests`, `checkCodebaseReference`
(green: 4011 symbols across 9 pages, drift-check passed) and `checkKDocAttachment` (green: 625 files,
6768 blocks, 0 findings). `jsTest` is NO-SOURCE and `jsBrowserTest` SKIPPED on this machine — the base's
configuration, not something this branch changed. `jvmTest` was also run standalone with `--rerun`
earlier: same 514 / 0 / 0 in 1 m 2 s.

**One task fails, and it is provably pre-existing.** `:tools:linalg-bench:compileKotlin` cannot resolve
`afield.four.util.floatToHalfBits` / `halfBitsToFloat`. The failing file is
`tools/linalg-bench/build/linalg-sources/.../Vectors.kt`, a `Copy` of the *committed*
`src/generatedLinalg/kotlin/.../Vectors.kt`, which imports `afield.four.util.*` at line 6; the bench's
`assembleLinalgSources` copies only `afield/stdlib/linalg/**`, so that import can never resolve. The
import arrived in `41b116d2` (2026-07-16, "Add generated Vector8h"), which `git merge-base
--is-ancestor 41b116d2 1a347dc8` confirms is an ancestor of the base, and
`git diff 1a347dc8..HEAD -- src/generatedLinalg tools/` is empty. Worth fixing; not here.

## 3. Guard coverage — closed rather than extended

The guard walks `File(repo, "src")` recursively, and `find src -name '*.kt' | wc -l` is **625** —
exactly what it reports scanning. It therefore already covers `jsMain` (20 files), `commonMain` (2),
`jsVision` (1), `generatedLinalg` (9) and `jvmTest` (51) as well as `jvmMain`; 34 of those non-`jvmMain`
files carry KDoc and pass. Nothing to extend, and the guard's own claim ("every `.kt` file under `src/`")
was already the honest one.

`tools/` is outside it by design: ~102 KDoc blocks across 15 files, none of which the extractor reads,
so a mis-attached block there publishes nothing. The one part of `tools/` that *becomes* published prose
— `tools/linalg-generator/src/main/resources/templates/*.kt` — is guarded at its destination in
`src/generatedLinalg`, which the guard does scan.

## 4. Fifth audit pass

`timeseries.kt` from `LeakyIntegrator` to `DynamicRangeCompressor` (~15 classes, ~40 blocks), the
stretch the first four passes did not reach. **One wrong claim**: `BimodalThresholder`'s "the first
sample seeds the low centroid" — it seeds `c1`, but the pair is ordered as soon as the second, different
value arrives, so a lower second value takes the low slot. Fixed, plus a documented range for `alpha`
and the reason it matters: the `-1f`/`+1f` label is read from the distances taken *before* the centroid
moves, and the swap that follows is unreachable for any `alpha` at or below 2, so an `alpha` above 2
would report the two clusters the wrong way round.

Everything else in the slice was checked arithmetically rather than skimmed: `LeakyIntegrator`'s
`x/(1-leakRate)` = 100× DC gain, `DCBlocker`'s `(1-r)/2π` corner and its 0.0955 Hz at 120 Hz,
`PeakDetector`'s `0.99¹⁰⁰ ≈ 1/3`, `CooldownFilter`'s `cooldownSamples + 1` spacing and full initial
counter, `StreamingWhitener`'s systematic `(1 - alpha)` factor (`value - mean_new = (1 - alpha)·diff`
exactly), `PercentileThresholder`'s `count - 1` denominator and 0.5 first sample, `MedianFilter`'s
count-parity rule, `RollingNormalizer`'s flat-window midpoint, `AdaptiveThresholder`'s (n−1) sample
deviation and its fallback divisor of 1. Also checked: the ring buffers in `RollingNormalizer`,
`RollingOneDollarNormalizer`, `AdaptiveThresholder`, `MedianFilter` and `PercentileThresholder` all
iterate `0 until count`, which is correct both while filling and once wrapped.

`FunctionGraphBrowser.kt` (~20 blocks, never audited) came back **clean** — including a refutation of my
own strongest suspicion in it. The file overview and three more blocks promise that an out-of-domain
sample "becomes `NaN` and is simply left undrawn", while the sampler calls
`evalSafe(x, outside = Double.NaN, edge = EdgeMode.ZERO)`, which reads as though `ZERO` would win and
draw a line at zero. It does not: `EdgeMode.ZERO` means "return `outside` verbatim", so NaN is honoured
and the claim is true. Recorded because the enum name invites exactly that misreading, and the same call
shape appears in `ValueVisualizer`, `FunctionsPlotGallery` and `WaveshapeWorkedExample`.

Two omissions noted and left alone as too small to be worth a reference regeneration: `graphTrace`'s
`samples` is silently clamped to `16..4096` and `sensitivitySteps` to `2..64`, and `heightPx` is floored
at 360 (`graphBrowseHtml`) / 320 (`rewriteBrowseHtml`), none of it documented.

## Verification — second pass

Verified tonight, by running it:

- `./gradlew check --continue` — every check task reached; the only failure is the pre-existing
  `:tools:linalg-bench:compileKotlin`, traced to a base commit with a git-provable argument.
- `jvmTest` — **514 / 0 failures / 0 errors / 0 skipped**, executed twice (once standalone with
  `--rerun`, once inside `check`), never trusted up-to-date.
- `checkKDocAttachment` — 625 files, 6768 blocks, 0 findings.
- `generateCodebaseReference` + `checkCodebaseReference` — green, and no page drifted after the
  `timeseries.kt` edit (the changed paragraph is not the published summary line).
- `tools/run_field_tests.py --no-reap --port 9720` for the four smokes the behaviour changes touch —
  `kotlin.safe-save-atomic.smoke`, `stdlib.file-manager.smoke`, `stdlib.interaction-nets.smoke`,
  `stdlib.smile.smoke`: **4 passed, 0 failed**. None of them are in `jvmTest`, so nothing else on the
  branch had exercised them.
- `diff_runner_after.png` regenerated through the real pipeline and **looked at**.

Carried over from the first pass and *not* re-derived here: the 100× amplitude ratio, the ~63×
`convolve` level change, the 1.6% `movingAverage` figure, the 3.7 s compressor attack, and the visual
inspection of the other 19 captures. The bit-identity-at-`amplitude = 1.0` claim, by contrast, *was*
re-derived from the diff, because the entire capture argument rests on it.

Still not verified: no GPU/window test beyond that one docs capture; the full docs build was not re-run,
so a capture reaching a changed function through a helper neither pass grepped for could still be stale;
`deflateCovariance` remains described-but-not-fixed; and roughly a third of the campaign's ~1550 blocks
have still never been read against the code.

## Recommendation — second pass

**MERGE**, on the first pass's reasoning plus one thing it could not say: the blast radius is now
enumerated rather than assumed, and enumerating it turned up exactly one more dishonest artifact, which
is fixed. `check` has been run rather than predicted, and its single failure is provably older than the
branch — a human running `./gradlew check` on merge will hit that failure and should not read it as this
branch's.

Read `dev_docs/stdlib_behaviour_changes.md` before merging; it is the part of this record meant to
survive the merge.

---

# Third pass (agent 3) — the docs build as a staleness detector, and a sixth audit

## 1. The remaining risk is closed — and the method that was supposed to close it does not work

The brief was: run a full docs build, and treat every asset it changes as a stale capture, because the
build regenerates captures from the real code. **The premise is false, and establishing that is the main
result of this pass.**

`public/docs_hooks.py` keys each capture's cache digest (`compute_digest` / `compute_cache_metadata`) on
the fence's own code text, its options, the page path, block index, `SCRIPT_VERSION`, a
`runnerSourceDigest`, and — for gallery scenes only — `sceneInputs`. `runnerSourceDigest` hashes
`RUNNER_DIGEST_SOURCE_FILES`, a hard-coded list of eleven files: `docs_hooks.py`, `Main.kt`,
`screenshot.kt`, `FLine.kt`, `FLineGeometry.kt`, `StandardRendererDocsHarness.kt`,
`NanoVisualization.kt`, `someSkiko.kt`, `outputareas2.js`, `field_backdoor.py`, `run_field_tests.py`.
**Not one stdlib file is in it,** and `git diff --name-only 1a347dc8..HEAD` intersects it in zero files.
So every digest on this branch is byte-identical to the base's: an ordinary (incremental) build is a
100% cache hit and rewrites nothing at all. `reuse_existing_asset_for_incremental` goes further and
reuses the committed asset even when `runnerSourceDigest` is the only thing that moved.

The lever that does work is `FIELD_DOCS_RUNNER_MODE=always`, which sets `cache_allowed = False` per
capture.

## 2. What was actually run

Port discipline: 9477 was free at 05:27, but rather than race the sibling agent I ran on **my own ports
(9722, then 9723)** via `FIELD_DOCS_RUNNER_PORT`, which removes the shared-server hazard entirely rather
than managing it. `verification_lock` is per-checkout, so it does not serialise across worktrees — the
port is the only shared resource, and this sidesteps it.

Two full runs, `FIELD_DOCS_RUNNER_MODE=always`, over the six pages that contain a fence reaching any
file this branch changed — `functions_examples` (106 fences), `graph_connectivity_examples`,
`interaction_nets_examples`, `state_estimation_examples`, `timeseries_examples`, `functions_domains`:

- **Run 1**: `regenerated=121 (screenshots=121 videos=0), failures=0`, elapsed 335.7 s, exit 0. Full
  site build on top: `checked 66 page(s), 536 asset ref(s) … OK — every referenced asset is committed`,
  `Build finished in 6.49s`, **145 pages processed, 6 of them with runner mode always**.
- **Run 2**: identical capture set, `regenerated=121 … failures=0`, elapsed 298.1 s, exit 0.

Not attempted, and honestly so: the other 195 capture fences (`fline_examples` 51, `fabricate_renderer`
12, `dough_dsp_sound` 12, the RectSL/StackSL galleries, `field_probes`, …). `always` mode over all 316
would not have fitted the window, and `imaging_examples` is owned by an unmerged sibling branch. The six
pages chosen are the ones whose fences reach the changed files; the rest were argued unaffected by the
earlier passes and that argument is unchanged.

## 3. The finding list — and why none of it is committed

`git status --short public/docs/assets` after run 1: **121 modified files.** Committing that set is what
the brief implies, and it would have been wrong. Every one of the 121 differs, so file-level status
carries no information here; the question is what *kind* of difference.

**A measurement trap first, because it nearly inverted the answer.** Pillow's `Image.getbbox()` defaults
to `alpha_only=True` on RGBA, so on two fully-opaque images it inspects only the alpha channel and
returns `None` — indistinguishable from "identical" — however much the RGB differs. My first pass
reported *118 of 121 pixel-identical, byte churn only*, and I nearly wrote that down. It was visible only
because the same script printed a contradiction next to it (`bbox=None` beside `nonzero=414489`). Redone
with `ImageChops.difference(...).getextrema()` plus a histogram on `RGB`-converted images: **none of the
121 was pixel-identical.**

**The determinism control is what makes the rest interpretable.** Run 1 vs run 2 — same worktree, same
commit, same code, ten minutes apart:

| run 1 vs run 2 | count |
| --- | --- |
| bit-identical | **5** of 121 |
| differ | **113** of 121 |
| output height differs | 3 |

Most of that is text/antialiasing jitter at ~0.005–0.01% of pixels and a maximum channel delta of ≤19.
So "the build changed this asset" is, by itself, evidence of nothing. Anything inside that band is noise
and committing it is pure churn.

Correlating each asset's delta *against `HEAD`* with the date it was last regenerated splits almost
perfectly:

| delta vs committed asset | count | last regenerated |
| --- | --- | --- |
| **stable ~49% of pixels, Δ204** | **54** | 2026-07-09 (2) / 2026-07-10 (52) |
| 3–13% | 34 | 2026-07-14 (1), 07-18 (2), 07-19 (30), 07-28 (1) |
| height changed | 4 | 2026-07-19 |
| ≤0.02%, Δ≤19 (noise band) | 29 | 07-19 (5) / 07-21 (5) / **07-28 (19)** |

Three things fall out of that table.

**(a) The branch's stated residual risk is closed, negative.** The 19 captures dated 2026-07-28 are
exactly the 19 the earlier passes regenerated on this branch (`26482634`, `68f2ce17`, `bdffc5fa`), and
**every one of them lands inside the noise band.** Two independent `always`-mode runs agree with what
those passes committed. No capture on these pages is stale because of this branch, and the earlier
grep-driven blast radius was right about the pages it covered.

**(b) The 54-file ~49% cluster is real staleness, and it is not this branch's.** It is stable across both
runs, so not jitter; and every one of the 54 was last captured on 2026-07-09/10 — before `bced282b`
(2026-07-14) *"Fix `Fn.graphBrowseHtml` layout and constant-delay domain"* changed the plot browser's
layout and y-autoscale. Attribution argued at source level, not assumed: `git diff 1a347dc8..HEAD -- src/`
contains no occurrence of `crossfade`, `fun ramp`, or any autoscale symbol, and
`FunctionGraphBrowser.kt` — which does the autoscaling — is not in the branch's changed-file list at all.
The clearest case is `reference/ramp.png`, whose fence is
`ramp(0.1, 0.9).crossfade(ramp(0.9, 0.1), center = 0.5, width = 0.25)`: the committed capture autoscales
to roughly −0.1 … 0.51, the current code to **exactly 0.100 … 0.500** — 0.1 at both ends and 0.5 at the
centre, which is the arithmetically correct range for that expression. The committed one is wrong and has
been since 2026-07-14, four days before this branch's base. Both images were opened and read, not just
differenced.

**(c) Nothing is committed.** Not the 54 (pre-existing `main` staleness, 18 days older than the branch,
and folding a 54-file asset churn into a KDoc-truth branch buries the actual change and invites conflicts
with the unmerged sibling); not the 34 medium or the 4 height-unstable ones (undiagnosed, and inseparable
from jitter in the time available); not the 29 noise-band ones (churn by definition). `git checkout --
public/docs/assets` — the tree is clean. The finding is recorded instead, durably, in
**`dev_docs/docs_capture_staleness.md`**, with the reproduction recipe, the noise-band thresholds, the
`getbbox` trap, and the recommendation that the 54 be fixed in their own commit on `main`.

## 4. Sixth audit pass — ten wrong claims, in the two least-audited files

`colors.kt` (15 blocks) and `shaders.kt` (163 symbols, ~166 blocks read across both halves), plus
`randoms.kt` (16 blocks). Every finding below was re-verified against the code by hand before editing,
including the arithmetic.

**`colors.kt` — 3.** The file overview claimed `okhsl` *and* `okhsv` "hold perceived lightness far
steadier across hue"; only `okhsl` does (`lOk = toeInv(ll)`, hue-independent), while `okhsvToSrgb`
derives lightness from the per-hue gamut cusp — and the `okhsv` block already said so, so the overview
contradicted its own file. It also called `hsl` "ordinary HSL", but `hslToSrgb` returns a `LinearRgb`
that `linearToVec4` then pushes through `srgbTransfer`, so `hsl(0, 0, 0.5)` is 0.735 grey, not 0.5. And
`hsl`'s own "the pure hues match CSS's `hsl()`" holds only at the six 60°-multiple corners at `l = 0.5`,
where every component is 0 or 1 and the transfer has a fixed point — `hsl(30, 1, 0.5)` is (1, 0.735, 0)
against CSS's (1, 0.502, 0), re-derived by hand.

**`shaders.kt` — 7.** `insertForShader` promised `Mesh`/`MeshGroup` accessors "for every push-constant
and UBO member": UBO members get only a bare `var Structure.<name>`. Both `fromViews` overloads described
the wrong sampler fallback — the `List` one scans all views rather than taking the first view's, and the
`HasImageViewArray` one never consults the views at all, so a provider lacking a default sampler needs a
live graphics context where the doc implied it would not. `Box.task` said `meshModuleHere` seeds "an
`EmitMeshTasksEXT` default into all three": the three stages are seeded independently and only the task
stub is that shader. `reloadShader` said `forceReresolve` re-resolves "every texture and buffer":
`Module.reload` gates only the *texture* resolver on it, while UBOs and SSBOs are deliberately carried
over. And two blocks documented the wrong declaration outright — `CompileResult`, an inert two-field
holder, carried `compileStage`'s description, and `preamblePrefixRegex`, a one-line marker regex, carried
"Applies an Insert's preamble(s) into the target box."

**A guard limitation worth knowing:** those last two passed `checkKDocAttachment` cleanly. The guard
verifies that a block *attaches to a declaration*, not that it describes the *right* one — a section
heading that a later insertion turns into a KDoc block is attached, and therefore invisible to it. Both
are `private`, so neither published anything, which is why the reference never showed the error. `git
blame` dates both to 2025-11-06, i.e. they are **pre-campaign**, not sweep output.

**Refutations — `randoms.kt` came back clean, and that is a result.** 16 blocks, no falsifiable claim.
Checked and *correct*, recorded so no seventh pass re-chases them: the `±0.5` bound on `noise1D`
(maximised analytically — the extremum really is 0.5 at `xf = 0.5`), `fade` being smootherstep
(6t⁵−15t⁴+10t³), the Box–Muller formula and its `u1 <= Double.MIN_VALUE` guard against `ln(0)`, the
"re-running a box replays the same sequence" claim (`resetStdlibRandom()` at `execution.kt:1252`, `:1912`,
`:1960`), `STDLIB_RANDOM_DEFAULT_SEED == 1L`, and the `extraHash`-collides-with-the-neighbouring-channel
warning. In `shaders.kt`, ~35 further suspicious-looking claims were checked and found correct — among
them all four `NullPointerException` claims, both `{% copyPC %}` stage-ordering claims, the
`shadertoyVertexShader` full-screen-triangle bit-twiddle, `fieldProbeHere`'s "clamped to 1..262144"
(`FIELD_PROBE_MAX_SAMPLES = 1024 * 256`), `fieldStatsHere`'s three `require` ranges, and
`Box.meshShader` being genuinely vestigial. Lines 1–2450 of `shaders.kt` yielded little because
`b35fe91c` had already audited them.

## Verification — third pass

Verified tonight, by running it:

- **Two `always`-mode docs runs**, 121 captures each, 0 failures, exit 0; plus one full site build —
  145 pages, 536 asset refs checked, `No issues found`.
- **The determinism control** — 113 of 121 captures differ run-to-run at the same commit. This is new
  information and it is what disqualifies file-level `git status` as a staleness test.
- **Pixel measurement redone** after catching the `getbbox(alpha_only=True)` trap; both `ramp.png`
  versions opened and read visually, not only differenced.
- **Attribution** of the 54-file cluster to `bced282b` (2026-07-14) by date correlation *and* by a
  source-level argument that the branch cannot have caused it.
- `./gradlew generateCodebaseReference` → `graphics.md` only; `checkCodebaseReference` **green** (4011
  symbols, 9 pages, no drift); `checkKDocAttachment` **green** (625 files, 6768 blocks, 0 findings).
- `jvmTest` — **514 tests / 0 failures / 0 errors / 0 skipped**, counted out of
  `build/test-results/jvmTest/*.xml` after the edits.
- `df -h /` checked before trusting any capture: 45 Gi available throughout.

Carried over from the earlier passes and **not** re-derived here: the `check --continue` run and the
pre-existing `:tools:linalg-bench:compileKotlin` failure; the four smoke runs; the amplitude/convolve/
`movingAverage` magnitude figures; and the unaffectedness arguments for the ~195 capture fences on the
ten pages I did not run.

Still not verified: those ~195 fences have never been re-derived in `always` mode, so the same class of
pre-2026-07-14 staleness may sit in `fline_examples` and the galleries — likely, given that 54 of 121
were stale on the pages I did check. The 34 medium-band and 4 height-unstable captures are
characterised but undiagnosed. `deflateCovariance` remains described-but-not-fixed. Roughly a quarter of
the campaign's ~1550 blocks have still never been read against the code — `linalg.kt`, `stdlib.kt`,
`keyframer.kt` and `StandardDrawing.kt` were on my list and I did not reach them.

## Recommendation — third pass

**MERGE.** The one risk the branch admitted to is now closed with a negative result: two independent
cache-bypassing capture runs agree with everything the branch committed, so nothing in the docs is stale
*because of this branch*. The branch is unchanged in scope — ten more KDoc corrections, one new dev_docs
note, no code behaviour touched, no asset churn, `jvmTest` and both guards green.

Two things for whoever merges:

1. **`dev_docs/docs_capture_staleness.md` is the part of this pass meant to survive.** It documents that
   a docs build does *not* detect stale captures by default, which is a trap the next agent would
   otherwise fall into exactly as this brief did.
2. **The 54 stale `functions_examples` captures are a real, separate bug on `main`,** dating to
   2026-07-14 and independent of this branch. They should be regenerated in their own commit — and
   whoever does it must sift them out of the run-to-run jitter rather than committing all 121.

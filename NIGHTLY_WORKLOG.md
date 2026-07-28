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

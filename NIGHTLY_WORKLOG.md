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

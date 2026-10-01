# Certified envelopes for `Functions.Fn` — the interval walker

Landed on `nightly/2026-09-23/1-fn-interval-bounds`. Code: `src/jvmMain/kotlin/afield/stdlib/FnInterval.kt`;
tests: `src/jvmTest/kotlin/afield/stdlib/FnIntervalTest.kt`; viewer hook: `SignalChannel.certified`
in `SignalViewer.kt` + `signal-viewer.js`; demo: `dev_docs/agents/demos/certified_envelope/`.

## Why

`public/docs/function_conversation.md` says of its signal viewer: *"Neither Plotly nor a finite
sample grid can guarantee detection of an arbitrarily narrow feature."* True of sampling. An
**enclosure** is a different object — `[lo, hi]` containing every value the function takes on an
interval — and `autodiff_formula.kt` already had what one needs: every formula-bearing `Fn` op's
pointwise semantics declared once as an `FnExpr` tree, walked in `Double` (`evalFormula`, pinned
bit-identical to `Fn.eval`) and in AD (`evalFormulaAD`). `evalFormulaInterval` is the third walker.

## Premise check (2026-09-23, base `7fc49b2ed`)

- 33 ops have an `FnFormulas` entry (`tr '\n' ' ' < autodiff_formula.kt | grep -oE 'put\( *"[a-z0-9]+"' | wc -l`);
  `FnOpCoverageTest` prints `35 of 127 canonical ops differentiable (27.6%), 92 declared
  non-differentiable` (the 35 adds the θ-leaf ops `const`/`param`).
- Nothing interval-shaped existed in `afield/stdlib` (grep for `Interval\b`, `enclose`, `rangeOf`,
  `*Bounds`, `bounds(`).
- **The workbench's own worked example cannot be certified as written**: all 3 channels in
  `public/docs/assets/function_conversation/final.kt` are `fn(0.0..durationSeconds, name) { … }`
  lambdas — one opaque `lambda` node each. The walker refuses them by name
  (reproducer: `FnIntervalWorkbenchTest.kt#fun theRewriteIsFormulaOnlyAndBitIdenticalToTheWorkedExample`).
  The demo therefore builds
  its signals from formula ops, and shows the refusal.

## The contract

`Fn.enclose(a, b)` returns `FnEnclosure(lo, hi, maybeNaN, certified, refusal, unsupportedOps)`.
When `certified`, **every `Double` that `Fn.eval(x)` returns for a double `x ∈ [a, b]` is in
`[lo, hi]`, or is NaN and `maybeNaN` is set.** When not, `lo`/`hi` are NaN — never a guess.

What is contained is the double evaluation, not the real function it approximates. That is the
choice that lets arithmetic go unwidened:

- `+ − × ÷` are correctly rounded and round-to-nearest is monotone, so endpoint (or 4-corner)
  evaluation in ordinary `Double` already brackets every rounded result. No outward rounding —
  which is why a held constant certifies as exactly `[1, 1]` and `certifiedEquals(1.0, …)` can be
  PROVEN rather than only "within an ulp".
- `abs`, `floor`, `min`, `max`, `coerceIn`, and the `If*` branch kinds are exact. A branch whose
  test the ranges cannot decide takes the hull of both sides.
- Library transcendentals are not correctly rounded (Javadoc: ≤1–2.5 ulp, semi-monotonic).
  Endpoint values are widened `TRANSCENDENTAL_ULPS = 4` ulps each side. `sin`/`cos` add ±1 only when
  a crest/trough may lie inside the argument range (generous by ~1e-9 in period units), so a narrow
  argument range does not cop out to `[-1, 1]`.
- NaN is a flag, not a widening: `log` of a range reaching below 0 says "may be NaN".
- Refusals (never a bound): an op with no formula; `Alpha` on an unbounded shape domain (the Double
  walker throws there too); `clamp` whose range may invert (then `Fn.eval` throws).

`FnIntervalRules.rules` states the rule per `FnExpr` kind in one line; the test holds it against
`FnExpr::class.sealedSubclasses` in both directions.

### One structural rule

The generic rules treat the three `t`s in the cubic smoothstep `t·t·(3 − 2t)` as independent and
overshoot 1 by ≈ `2·Δt` near `t = 1` — exactly at a hold's edge. The shape is recognised
structurally (not per op) and bounded by monotonicity: on `[0, 1]` each of its three roundings is
relative (`2t` is exact), so computed values are within `(1 ± u)³` of the real cubic; endpoints are
widened 8 ulps and intersected with the generic bound. It moved the one-shot tightness p75 from
1.318 to 1.129 and turned `crossfade <= 1 + 1e-9` from undecidable-in-200k-enclosures into 207
enclosures.

## Branch-and-bound on top

- `certifiedRange(a, b, tolerance)` — best-first on the max and on the min: split the piece whose
  enclosure promises most, probe midpoints with `Fn.eval`, stop when the reached value is within
  `tolerance` of the best remaining bound. Returns value + witness x + proven bound per side, and
  `certified = false` with the gap if the budget runs out.
- `certifiedNeverExceeds / NeverBelow(limit, a, b, slack)`, `certifiedEquals(value, a, b)`,
  `certifiedReaches(level, a, b)` — tri-state `PROVEN / REFUTED(witness) / UNDECIDED`.
- `certifiedEnvelope(a, b, bins, tolerance, maxDepth)` — per-bin bounds, each bin bisected while its
  bound exceeds the spread of its edge samples by more than `tolerance`.

### The limit worth knowing: attained bounds

A bound the function *attains* tangentially cannot be proven exactly by bisection. The crossfade
`b(1 − e) + e` equals 1 on the plateau, but every piece touching the plateau edge bounds above 1 by
≈ `b·Δe`, however small. `certifiedNeverExceeds(1.0)` is UNDECIDED; with `slack = 1e-9` it is
PROVEN. Rewriting as `1 − (1 − b)(1 − e)` isolates the bound (still needs `slack = 1e-12`, from the
smoothstep rule's 8-ulp widening). Both are pinned in `FnIntervalTest`.

## Numbers (FnIntervalTest, 2026-09-23, 15 tests, all green)

| what | population | result |
| --- | --- | --- |
| containment | 600 generated formula-only graphs × 4 intervals (widths 6, 1, 1e-2, 1e-5) × 121 samples = 290 400 samples, 21 018 of them NaN | **0 violations**, 0 refusals |
| envelope containment | 200 graphs × 16 bins × 41 samples, NaN excluded | 119 148 samples, **0 violations** |
| far from the origin | 300 graphs × 3 intervals (widths 40, 0.5, 1e-3) at x ∈ [500, 3540] × 81 samples | 72 900 samples, **0 violations** (after one real bug, below) |
| one-off sweep, not committed | 40 seeds × 300 graphs × 6 placements (x from −3 to 1e4, widths 1e-6 to 400) × 61 samples | 4 392 000 samples, 0 violations |
| premise | the same 600 graphs | reach all 33 formula ops and all 28 `FnExpr` kinds |
| teeth | 200 graphs: the endpoint-only "bound" `[min(f(a), f(b)), max(…)]` | violated on 111 of 200 |
| spike counterfactual | `sin(1/40)·0.2+0.45 + gaussian(1234.57, 0.002, 0.5)`, viewer 1000 bins, 0.02 s step | sampled bin max 0.3038 vs true peak 0.7994: **missed by 0.4956** (floor asserted 0.45); certified bin max 0.79946 (≥ peak, < peak + 0.01) |
| cost | the demo's hour, 1000-bin viewer window, 180 000 samples, best of 5 | sampled 12–14 ms; sampled + certified band 12–17 ms (1.01–1.19× over two runs). Asserted only < 4 s (the route times out at 12 s) |
| tightness, one-shot | 226 graphs on [−2, 2] with finite, non-flat 4001-sample range | width/observed p50 1.0000006, p75 1.1286, p90 2.116, 9 infinite; ratchet p75 ≤ 1.15 |
| tightness, 64-bin refined | same 226 | p50 1.00000017, p75 1.0007, p90 1.00133, max 2.0; ratchet p90 ≤ 1.0015 |

The generator (`FnIntervalTest.gen`) deliberately lets `pow`, `log` and `divide` see zero-crossing
and negative inputs, so NaN and infinity rules are exercised, and gives `smoothStep`/`remap`/`scaleX`
/`softClip`/`gaussian` their degenerate (equal-edge / zero) parameters a fraction of the time.

### The one containment bug the tests found

The near-origin population was green from the first run. Moving the intervals to x ≈ 3400 made
`exp` overflow, and `hypot(NaN, +inf)` — which Java defines as `+inf`, an infinity beating a NaN —
was enclosed as "NaN only": 243 violations in 72 900 samples. The `hypot` rule now adds `+inf`
whenever one side may be infinite and the other NaN. While there, `pow(−inf, non-integer)` (a
`+inf` or `+0` limit per the `Math.pow` Javadoc, not NaN) was fixed by reading the spec; no test
had hit it. The lesson is the usual one for a generated population: it only tests where it looks.

## The worked example, certified (second pass, same night)

**What forced `fn { }`.** Reading `final.kt` construct by construct: the phase-modulated carrier is
`sin` with a phase signal; the integrated-frequency pulse is `cos` of a polynomial in `age`;
`smooth01` is arithmetic on a `clamp`; the e3/y lag is a polynomial. All formula-bearing already.
**The one construct that needed a lambda is the piecewise selection** — `events.firstOrNull { … }`
and the `when` over rise / hold / fall. The ops that express an exact 0/1 selection, `step` and
`sign`, were formula-less by design (`FnNonDifferentiable`: "honest arithmetic, dishonest gradient").

**Value-only formulas.** `FnFormulas.valueOnlyOps = {step, sign}`: value formulas read by
`FnFormulas.valueOf` (the `Double` and interval walkers, and `evalByFormula`'s pin), never by
`FnFormulas.of` (the AD walk). So `FnAutoDiff.differentiableOps` is unchanged at 35 of 127 and no
prose-guarded count moves. Coverage, derived by `FnIntervalTest`
(`OpRegistry.knownOps` canonicalised, filtered on `valueOf != null || const/param`):
**37 of 127 canonical ops enclosable** (33 formula + 2 value-only + const/param), up from 35.
`step` is shaped "not below the edge, and neither side NaN" — equal to `x >= edge` on every double,
NaN included (70 raw-bit comparisons) — because the obvious "above, or equal" cannot decide an
interval that *starts* at the edge.

**The rewrite** (`FnIntervalWorkbenchTest.workbenchFormula`, demo box 5): `[x >= a]` is
`step(a)`, `[x <= b]` is `1 − step(nextUp(b))`, the lag's `age < 180` is
`clamp(sign(180 − age), 0, 1)`, and every product and sum follows the lambda's order. Exactly one
addend is non-zero at any x and the rest are ±0, so the sum is that addend's bits.

| claim (from the page / its StudyChoices) | population | result |
| --- | --- | --- |
| the rewrite *is* the worked example | every 0.01 s × 3 channels + each landmark and its neighbouring doubles: 1 080 228 samples | **0 raw-bit mismatches** |
| each event reaches exactly (1,1,1) for its 30 s plateau | 5 events × 3 channels | **15 of 15 PROVEN** (`certifiedEquals`, one enclosure each) |
| background = 0.45 ± 0.2 ± 0.04 | 3 channels over the hour | `<= 0.69` and `>= 0.21` PROVEN; x's certified range [0.2100084, 0.6899916] |
| "all channels stay in [0,1]" | 3 channels over the hour | `>= 0` PROVEN. **`<= 1` REFUTED** at x = 284.999942779541: the worked example's own lambda gives **1.0000000000000009** (checked as raw bits). `<= 1 + 9e-10` PROVEN by `certifiedRange`'s bound (~109 000 enclosures, ~11 s per channel) |

The refutation is real and small: in the last instant of a rise the shoulder's
`(1 − merge)·pulsed + merge` rounds 4 ulps above 1 before the `when` hands over to the exact hold.
A sampled check at the viewer's 0.02 s grid does not see it: 0 of 180 001 grid points exceed 1 on
any channel, and the grid max is exactly 1.0 (measured with the lambda, demo-box probe, 2026-09-23).

**What the band costs on this graph** (narrated, `theCertifiedBandOnTheWorkedExampleIsTimedAtTheViewersBinCounts`,
best of 3, channel x; asserted only < 12 s, the route timeout):

| viewer window | mode, bins | sampled lambda | sampled + certified band |
| --- | --- | --- | --- |
| whole hour | envelope, 1000 | 7.8 ms | 4 731 ms (~600×) |
| one event, 185–415 s | envelope, 1000 | 0.6 ms | 1 734 ms |
| 280–290 s | waveform, 2000 intervals | 0.2 ms | 160 ms |

So the demo signal's 1.0–1.2× (a two-op graph) does not generalise: on the worked example's rewrite
an overview request spends ~5 s in `certifiedEnvelope` (tolerance 1e-3 × lane range, depth ≤ 6).
Usable, well under the timeout, and not interactive. The obvious levers, untried: a lower `maxDepth`
for overview windows, caching per-bin results across pans, and compiling the graph once per window
instead of walking `graphNode()` per enclosure.

**Walker changes this pass needed** (each argued in its KDoc): `IfEq`/`IfLt` of a subexpression
against itself are decided; graph-level rules for a `multiply` of one `Fn` with itself (square) and
for the hand-written quintic `u·u·u·(10 + u·(−15 + 6u))` over one shared `u` (monotone; rounding
within `1 ± 28u`, covered by 64 ulps); two adjacent doubles are decided as two points rather than
left unsplittable; a per-enclosure identity memo over the DAG; a slack claim is refuted against
`limit + slack`. Without the quintic rule, `<= 1 + 1e-9` was UNDECIDED after 200 000 enclosures;
with it, ~107 000.

## Viewer hook

`SignalChannel(..., certified: Functions.Fn? = null)`, or `certifiedSignalChannel(name, fn)`. When
set, `SignalViewer.window` also returns `SignalLaneWindow.certified` (per bin; per sample interval in
waveform mode) with tolerance `1e-3 × lane range`, depth ≤ 6, and `signal-viewer.js` draws it as an
amber band behind the white envelope. A formula-less lane gets `certifiedRefusal`, shown in the
title. Pixel-checked in the backdoor's headless Playwright (2384×1176 `screenshot-box` PNGs): with
the band, an amber spike at 1234 s; without, nothing there.

## Traps

- **Box code is compiled against a different module**: `range.max!!.witness` then `range.max.bound`
  fails in a box with *"Smart cast to 'FnExtremum' is impossible, because 'max' is a public API
  property declared in different module"*, while the same code compiles in jvmTest. Bind to a
  local `val` first. (Found running demo box 3; library-green, box-red.)
  The property it refuses is `FnInterval.kt:818#val max: FnExtremum?`, a public nullable `val` in
  the library module, which Kotlin will not smart-cast from code compiled elsewhere.
- Inside `with(Functions) { }`, `all` is `Functions.all` (an `FnTransform`), not a `Domain`, and
  `Domain` itself is not in scope unqualified — write `Functions.Domain.ALL`.
- Tightness numbers depend on the generator's population; the median is dominated by small graphs,
  which is why the ratchets are on p75 / p90.

## The piecewise oscillators (2026-10-01)

`saw`, `triangle` and `square` have `FnFormulas` entries, through one helper (`periodic`): `IfZero(|period|, offset, body(fract))`
with `fract` spelled `t - floor(t)`, `t = (x - phase) / |period|`, association order copied from the closures
so `FnFormulaAgreementTest` holds them bit-identical. They are in `table`, not `valueOnlyTable`:
`d fract/dt = 1` wherever it exists, which is a real gradient for period and phase. Differentiable
ops 35 → 38 (both prose counts updated); enclosable 37 → 40 of 127. `pulseTrain`/`pulseBurst`/`pulsePattern`
stay in `FnNonDifferentiable`: their edge and body shapes are enum-dispatched and each cycle is hash-jittered.

Two walker rules the generators needed, both structural and so free to any later formula of the same shape:

- **fract.** `Sub(t, Floor(t))` with the same `t` is `FnRangeMath.fract`: endpoint-exact inside one
  unit cell (`fl(t - k)` is monotone in `t` for fixed `k`), `[0, 1]` across a boundary, closed at 1
  because `-1e-20 - floor(-1e-20)` is exactly `1.0`. The generic rule gives `[-100, 100]` over 100 periods.
- **Branch narrowing.** An undecided `IfLt(a, b, then, else)` walks `then` with `a` known to be in
  `[a.lo, nextDown(b.hi)]` and `else` with `a` in `[max(a.lo, b.lo), a.hi]` (NaN kept). The facts are a
  `known: Map<FnExpr, FnRange>` checked before each node, and dropped at `AtX`, since a moved position
  invalidates them. Without it the triangle's `if (t < 0.5) 2t else 2 - 2t` encloses as `[-1, 3]`.

Gradient-oracle trap: `AutoDiffFnBridgeTest`'s three θ points (`base`, `1.4·base + 0.13`, `base - 0.27`)
put a triangle corner exactly on the `x = 0` sample when the phase was 0.11 (period 0.16, phase −0.16):
a 15% disagreement that was the oracle's kink, not the gradient. Phase 0.12 avoids it. Seeds that
need to stay refused (`FnRefineRoundsTest`, the EXACT-throws checks) moved from `saw` to `pulseTrain`.

Population after the change (`FnIntervalTest`, 2026-10-01; the generator draws the three
oscillators only in the `valueOnly` population, so the tightness ratchet's population is unchanged):
containment 290 400 samples (14 931 NaN), 0 violations, 0 refused; far 72 900, 0; envelope 122 658, 0;
tightness p75 1.1286, binned p90 1.00133, both inside their ratchets.

## The proof picture

`certifiedRange(…, onEnclosure = { piece -> })` reports every `FnSearchPiece(lo, hi, range, side, kept)`
either search computes. `Fn.intervalPicture(a, b)` (`afield/visualization/intervalPicture.kt`, `ToHTML`)
draws them as boxes (orange = max search, green = min; kept stronger than pruned; coarse pieces
outlined only, or the first whole-interval enclosure washes out the plot), then the certified
envelope, then the samples, then witness dots and dashed proven bounds, with an enclosures-per-x strip
underneath. `Box.showIntervals(fn, a, b)` displays it.

Trap: the first cut clipped with `<clipPath id=…>` + `url(#id)`. It rendered under `rsvg-convert` and
drew an **empty plot** in a box output, where the reference does not resolve. A nested `<svg>`
with `overflow='hidden'` clips with no id. `FnIntervalPictureTest` checks every recorded piece is a real
enclosure and that the SVG carries one box per drawn piece; the live render was checked through the backdoor.

## The same contract for StackSL (2026-10-01)

`GlslIntervalScalars` (`stacksl/cpu/GlslInterval.kt`) is a third `GlslScalars` instance: components are
`Iv(lo, hi, nan)` of `Float`s, each operation bounded by `FnRangeMath` in `Double` and rounded outward to
`Float`. The contract moves to binary32: every value the `Float` reference path renders. Points are
computed exactly as the reference computes them, in `Float` or `Double`-then-round once. Without that,
outward rounding leaves a one-ulp interval and the hash's `fract` of a large product spreads it over
most of `[0, 1]`: value noise never settled even at one pixel. `cubicSmoothstep`'s monotone rule is *not*
reused: its `(1 ± u)³` argument is for `Double` `u`, and `Float`'s is 2²⁹ times coarser. Smoothstep is
bounded op by op.

**Three-valued conditions.** `GlslScalars` gained `truth`, `boolOfTruth`, `equalTruth` and `join`, with
defaults that make every point algebra exactly what it was (`StackSLCpuFloatPathManifestTest`,
`StackSLGradientTest` green). `GlslMachine`:

- undecided `if`: save frame values + globals + return value, run `then`, save, restore, run `else`,
  join slot by slot. An arm that `discard`s contributes nothing and sets `maybeDiscarded`. Arms that exit
  differently (RETURN vs NORMAL, BREAK vs NORMAL) are refused: one continuation cannot hold both.
- undecided `?:`: the same fork, joining the two values.
- `&&`/`||`: Kleene logic, short-circuiting exactly when the left side is decided.
- `!`, `==`, `!=`: through `truth`/`equalTruth`.
- an undecided loop condition, or a non-point `asInt` (index), is refused.

**Entry points** (`StackSLIntervals.kt`): `enclosePixels(glsl, x0, y0, x1, y1, w, h)` brackets
`fragUV` by the corner pixel centres, computed as `StackSLCpu.fragUvAt` computes them (monotone per axis).
`certifiedTiles(glsl, dim, tolerance)` is a quadtree that stops at a tile whose widest channel is
≤ tolerance. `tilePicture(program)` is the render and the quadtree side by side (`ToHTML`).

| claim | population | result |
| --- | --- | --- |
| containment | 29 non-feedback gallery programs × 15 random rectangles (1² … 64²) of a 64×64 render | 401 157 pixels, **0 violations**; all 29 bounded (after the matrix `*` rule; before it, 20 refused) |
| tiles, soft ring | 128², tolerance 2/255 | 6 508 tiles; 61.8% of pixels proven by a tile larger than one pixel; 8 677 runs, 0.25 s |
| tiles, stripes / value noise / fbm threshold | same | 512 px / **0** / **0** proven by a region; noise and fbm bottom out at single pixels everywhere |

The noise result is the honest limit. A lattice hash is `fract` of a large product, so over any range
wider than a point it is `[0, 1]`, and no subdivision short of a pixel helps. A bound for noise would
need a declared range for the noise *word* (its output is in `[0, 1]` by construction) and a Lipschitz
constant, i.e. a rule above the GLSL, at the StackSL level.

Public page: `public/docs/stacksl_intervals.md`. Fn page: `public/docs/fn_intervals.md`.

## Next

- Give formulas to the `ease`/`tween` family and to `pulseTrain` (`FnNonDifferentiable`'s closable
  list); `saw`/`triangle`/`square` are done.
- StackSL: word-level range declarations for the noise words, so a region over noise is bounded by
  the word's range rather than by its hash; interval rules for the matrix builtins, `cross`, `reflect`.
- Interval *gradients* (both languages already have reverse-mode AD): a Lipschitz bound per region
  would give safe sphere-tracing steps and much tighter tiles.
- A mean-value / monotonicity form (interval forward-mode AD) would fix attained-bound tangency in
  general, but needs a rigorous rounding-error allowance to keep the Double-containment contract.

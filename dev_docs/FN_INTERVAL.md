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
  lambdas — one opaque `lambda` node each. The walker refuses them by name. The demo therefore builds
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

## Numbers (FnIntervalTest, 2026-09-23, 13 tests, all green)

| what | population | result |
| --- | --- | --- |
| containment | 600 generated formula-only graphs × 4 intervals (widths 6, 1, 1e-2, 1e-5) × 121 samples = 290 400 samples, 21 018 of them NaN | **0 violations**, 0 refusals |
| envelope containment | 200 graphs × 16 bins × 41 samples, NaN excluded | 119 148 samples, **0 violations** |
| premise | the same 600 graphs | reach all 33 formula ops and all 28 `FnExpr` kinds |
| teeth | 200 graphs: the endpoint-only "bound" `[min(f(a), f(b)), max(…)]` | violated on 111 of 200 |
| spike counterfactual | `sin(1/40)·0.2+0.45 + gaussian(1234.57, 0.002, 0.5)`, viewer 1000 bins, 0.02 s step | sampled bin max 0.3038 vs true peak 0.7994: **missed by 0.4956** (floor asserted 0.45); certified bin max 0.79946 (≥ peak, < peak + 0.01) |
| tightness, one-shot | 226 graphs on [−2, 2] with finite, non-flat 4001-sample range | width/observed p50 1.0000006, p75 1.1286, p90 2.116, 9 infinite; ratchet p75 ≤ 1.15 |
| tightness, 64-bin refined | same 226 | p50 1.00000017, p75 1.0007, p90 1.00133, max 2.0; ratchet p90 ≤ 1.0015 |

The generator (`FnIntervalTest.gen`) deliberately lets `pow`, `log` and `divide` see zero-crossing
and negative inputs, so NaN and infinity rules are exercised, and gives `smoothStep`/`remap`/`scaleX`
/`softClip`/`gaussian` their degenerate (equal-edge / zero) parameters a fraction of the time.

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
- Inside `with(Functions) { }`, `all` is `Functions.all` (an `FnTransform`), not a `Domain`, and
  `Domain` itself is not in scope unqualified — write `Functions.Domain.ALL`.
- Tightness numbers depend on the generator's population; the median is dominated by small graphs,
  which is why the ratchets are on p75 / p90.

## Next

- Give formulas to the ops the workbench actually uses (`saw`/`triangle`/`square` and the
  `ease`/`tween` family per `FnNonDifferentiable`'s closable list): each one also gains an exact
  gradient and becomes certifiable.
- A mean-value / monotonicity form (interval forward-mode AD) would fix attained-bound tangency in
  general, but needs a rigorous rounding-error allowance to keep the Double-containment contract.

# Nightly worklog — Waveshaping / distortion transform family (kind A)

Branch: `nightly/2026-07-13/1-fn-waveshaping-transforms` (base `614e2adf`)

## Plan

**Goal:** Add a small, coherent waveshaping/distortion `FnTransform` family to the
Functions DSL so these classic value-domain shapers become composable in
`through` / `pipe` / `/` chains and surface automatically in the type-aware infix
completion (return type `FnTransform`).

**Findings from reading `src/jvmMain/kotlin/afield/stdlib/Functions.kt`:**
- The value-domain shapers already exist as pure `Fn` extensions: `Fn.fold(threshold)`
  (west-coast wavefolder, ~line 5501), `Fn.rectify()` (half-wave, clamps negatives,
  ~5485), `Fn.abs()` (full-wave, ~5426), `Fn.quantize(levels)` (amplitude staircase,
  ~5556), `Fn.bitCrush(bits)` (~5564).
- BUT the composable `FnTransform` vocabulary is missing them. Only `crush(bits)`
  (~4684) wraps a value shaper (`bitCrush`) as an `FnTransform`. There is no `fold`,
  `rectify`, or amplitude `quantize` `FnTransform` factory. That is exactly the niche.
- No name collision: `fun fold(...)`, `fun rectify(...)`, `fun quantize(...)` (no
  receiver, returning `FnTransform`) are distinct from the `Fn.*` extension members
  (which need a receiver). Bare `fold(0.7)` inside `with(Functions)` resolves to the
  factory.
- Idiom to match: `FnTransform("name") { source -> source.<shaper>(...).withName("name") }`,
  with `Number` + `Fn` overloads where params are signals (cf. `gain`/`clipAt`/`softClipBy`),
  placed beside the related dynamics/`crush` transforms. Enums live at the top of the
  class (`EdgeMode`, `StretchShape`).

**Design (added beside `crush`):**
- `fold(threshold: Number)` / `fold(threshold: Fn)` — wavefolder, reuses `Fn.fold`.
- `enum class RectifyMode { HALF, FULL }`; `rectify(mode = FULL)` — half-wave via
  `Fn.rectify()`, full-wave via `Fn.abs()`.
- `quantize(levels: Int)` — amplitude staircase, reuses `Fn.quantize`. Distinct from the
  time-domain `hold`/`stepHold` (which quantize *time*).

Not adding `saturate`/`drive`: `softClipBy` already covers soft saturation.

**Verify:**
- New unit test `src/jvmTest/kotlin/afield/stdlib/FnWaveshapeTransformTest.kt` — CPU
  sampling asserting exact behaviour: full-wave rectify == |x|; half-wave clamps
  negatives to 0; fold past threshold reflects and stays within the fold band and is
  continuous; quantize(n) yields exactly n distinct output levels; determinism.
- Pixel proof: a committed SVG montage of each transform applied to a ramp/sine, via a
  small `WaveshapeWorkedExample` render harness mirroring `EvolveWorkedExample`'s
  dependency-free SVG builder. Inspect the rendered SVG.

**Document + demo:**
- New subsection in `public/docs/functions_examples.md`.
- Loadable demo transcript `public/docs/assets/functions/waveshape-transforms.operations.yaml`
  (replayable via `field_run_transcript` / backdoor) that plots the family in a fresh doc.

---

## Execution — what changed

- `src/jvmMain/kotlin/afield/stdlib/Functions.kt`
  - Added `enum class RectifyMode { HALF, FULL }` beside the other enums.
  - Added `FnTransform` factories beside `crush`: `fold(Number)` / `fold(Fn)`,
    `rectify(RectifyMode = FULL)`, `quantize(Int)`. All return `FnTransform`, so they
    compose in `through`/`pipe`/`/` and surface in infix completion automatically.
  - **Fixed `Fn.fold`** (the shaper the new `fold` transform wraps). The old body
    `value % (4*th)` triangle was discontinuous (jumped ~2*th at every 2*th), unbounded
    for negative inputs (only sign-reflected), and asymmetric. Replaced with
    `(2*th/PI) * asin(sin(PI*value/(2*th)))` — a true continuous, odd-symmetric
    west-coast wavefolder bounded to `[-th, th]`, identity within the band.
- `src/jvmMain/kotlin/afield/examples/functions/WaveshapeWorkedExample.kt` — new CPU-only
  render harness (mirrors `EvolveWorkedExample`): sweeps a ramp `-2..2` through each
  transform and emits a dependency-free SVG transfer-curve montage.
- `build.gradle.kts` — registered `runWaveshapeWorkedExample` (JavaExec), mirroring
  `runEvolveWorkedExample`.
- `src/jvmTest/kotlin/afield/stdlib/FnWaveshapeTransformTest.kt` — new unit test (5 cases).
- `public/docs/functions_examples.md` — new `#### fold / rectify / quantize (waveshaping)`
  subsection with Simple + Creative snippets, the committed montage image, and a replay
  pointer to the demo transcript.
- `public/docs/assets/functions/waveshape-transforms.svg` — committed rendered proof.
- `public/docs/assets/functions/waveshape-transforms.operations.yaml` — loadable demo.

## Verification

- **Numeric (unit tests):** `./gradlew compileTestKotlinJvm` (green) then
  `./gradlew jvmTest --tests afield.stdlib.FnWaveshapeTransformTest` — **5/5 PASSED**
  (report `build/test-results/jvmTest/TEST-afield.stdlib.FnWaveshapeTransformTest.xml`,
  tests=5 failures=0 errors=0). Asserted: full-wave rectify == |x|; half-wave clamps
  negatives; fold stays within [-th, th] for inputs far outside it, passes through
  below threshold, folds a large input (5.0) back into the band, and is continuous
  (max sample-to-sample jump < 0.05, vs the old ~2*th discontinuity); quantize(n) snaps
  to exactly n distinct levels for n in {2,3,4,6,8}; chained transforms are deterministic.
- **Pixel:** `./gradlew runWaveshapeWorkedExample` wrote
  `public/docs/assets/functions/waveshape-transforms.svg`; rendered to PNG with
  `rsvg-convert` and inspected. Correct shapes: rising input line, fold triangle,
  rectify-FULL V (|x|), rectify-HALF clamped ramp, quantize-6 clean 6-step staircase,
  and a loud sine folded into a richer waveform. Not blank/garbled.
- **Compile:** `./gradlew compileKotlinJvm` green.
- **Demo transcript:** YAML parses (safe_load) and matches the proven
  `variety-evolution.operations.yaml` op structure (create + launch). Its Kotlin API
  usage (identity/*/-/over///fold/rectify(RectifyMode)/quantize) is the same exercised
  by the passing tests; `.plot(channel,title,heightPx)` signature verified against source.
- **NOT verified:** live replay of the transcript in a running Field (Field not running;
  booting a GPU/graphics-context session for replay was judged disproportionate and
  flaky per the known capture-flakiness notes). The docs `diffstatic_runner` screenshot
  `fold-rectify-quantize.png` is not generated here (needs the GPU docs-capture pipeline);
  the committed SVG montage is the standing visual proof instead.

## Risks / unknowns

- `Fn.fold` behaviour changed. Only one caller (a docs creative example, regenerated at
  capture time) used it and no test pinned its numbers, so risk is low; but any
  in-flight art relying on the old asymmetric/discontinuous fold would shift. This is a
  deliberate correctness fix, documented above.
- Live transcript replay and the docs screenshot capture are unverified (see above).

## Recommendation

**Merge.** Small, self-contained, in-family addition that fills the stated waveshaping
niche; the new transforms compile, are unit-tested to their exact math, and are proven
visually. It also corrects a real bug in `Fn.fold`. The only unverified pieces are the
GPU-dependent live replay / screenshot, which are consistent with existing demos and
low-risk.

## Demo artifact

`public/docs/assets/functions/waveshape-transforms.operations.yaml` — replay it into a
FRESH Field document to see the family plotted:
`field_run_transcript` (paste the YAML), or
`tools/field_backdoor.py --scratch --command 'macro write waveshape-transforms --file public/docs/assets/functions/waveshape-transforms.operations.yaml' --command 'macro run waveshape-transforms'`.

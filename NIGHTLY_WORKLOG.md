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

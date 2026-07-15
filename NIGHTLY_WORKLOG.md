# Nightly worklog — 2026-07-15/1 — Fn waveshaper family, evolvable

Kind: A (feature / generalization) — "swing for the fences".

## Goal
Promote the three remaining opaque waveshapers — `rectify`, `quantize`, `crush`
(backed by `bitCrush`) — from opaque `mapY(name){…}` lambda nodes to first-class
rebuildable graph `node(...)`s with `OpRegistry` `OpSpec`s, so the
graph-rewrite/evolve/transmutation system can reach and swap them like
`clip`/`fold`/`chebyshev`.

## Plan (refined against the real code)

Verified in the worktree:
- `Fn.rectify()` (Functions.kt ~5613) was `mapY(name){ if (it<0) 0 else it }` —
  half-wave only, opaque. The public `Functions.rectify(mode: RectifyMode)`
  FnTransform (~4780) dispatched HALF→`source.rectify()`, FULL→`source.abs()`
  (`abs` is itself an opaque `mapY`). So FULL rectify never even produced a
  rectify-shaped node.
- `Fn.quantize(levels: Int)` (~5713) and `Fn.bitCrush(bits: Int)` (~5721) were
  opaque `mapY`. Their FnTransforms `quantize(levels)` / `crush(bits)` wrap them
  with `.withName(...)`.
- Pattern to follow: `clip`/`fold`/`chebyshev` = `graphFn(d, name, node(op,
  children=listOf(fnEdge("source",this), fnEdge(param...)), rebuild={…}))`.
  Numeric params ride as `param(n)` child edges; enums ride as `strings=` (see
  `withEdge`'s `EdgeMode`). `OpRegistry.OpSource.childOr(default, role)` /
  `str(key)` / `parseEnum` read them back on transmutation.

Steps:
1. `Fn.rectify(mode: RectifyMode = HALF)` → `node("rectify", source child,
   strings={"mode"}, rebuild)`; HALF keeps negatives→0, FULL = |x|. No-arg keeps
   the historical half-wave meaning. Route the FnTransform (both modes) through it.
2. `Fn.quantize(levels: Fn)` + `Fn.quantize(Int)=quantize(param)` →
   `node("quantize", source+levels children, rebuild)`; identical math, reading
   `levels.eval(x).roundToInt()`.
3. `Fn.bitCrush(bits: Fn)` + `Fn.bitCrush(Int)` → `node("crush", source+bits
   children, rebuild)`; identical math.
4. Add three `OpSpec`s (category `transform`) in FunctionOps.kt: `rectify`
   (alias `rectifier`; mode from `str("mode")`, default FULL), `quantize`
   (alias `quant`; `childOr(8,"levels")`), `crush` (aliases `bitcrush`/`bitCrush`;
   `childOr(8,"bits")`). Avoided reusing `rect` (already `square`'s alias).
5. Add `rectify`/`quantize`/`crush`/`bitCrush` to `fnrewrite.js` `opWords` to keep
   the editor↔registry sync test (`editorOpVocabularyMirrorsTheRegistry…`) green.
6. New test `WaveshaperFamilyTest`: for each op assert (a) graph round-trip
   (deepCopy preserves op + param children + eval) and (b) OpRegistry
   transmutability by canonical + alias, plus an SVG visual-proof montage.
7. Loadable demo transcript + docs mention.

(Everything promotable IS promotable — no pivot needed.)

## Execution log — DONE

### Files changed & why
- `src/jvmMain/kotlin/afield/stdlib/Functions.kt`
  - `Fn.rectify(mode = HALF)` — was opaque `mapY`; now `node("rectify")` with a
    `mode` string tag + rebuild lambda. No-arg keeps historical half-wave meaning.
  - `Fn.quantize(Fn)` (+ `Int` overload → `param`) — `node("quantize")` with a
    numeric `levels` param child.
  - `Fn.bitCrush(Fn)` (+ `Int` overload) — `node("crush")` with a numeric `bits`
    param child.
  - `Functions.rectify(mode)` FnTransform now routes BOTH modes through the new
    rectify node (FULL was previously `abs()`, an opaque mapY, so FULL rectify
    never produced a rectify-shaped node). Numeric behaviour identical.
- `src/jvmMain/kotlin/afield/stdlib/FunctionOps.kt` — three transform-family
  `OpSpec`s: `rectify` (alias `rectifier`, mode via `str("mode")`, default FULL),
  `quantize` (alias `quant`, `childOr(8,"levels")`), `crush` (aliases
  `bitcrush`/`bitCrush`, `childOr(8,"bits")`). Deliberately did NOT reuse `rect`
  (already `square`'s alias — checked).
- `src/commonMain/resources/fnrewrite.js` — added `rectify`/`quantize`/`crush`/
  `bitCrush` to `opWords` (editor↔registry sync).
- `src/jvmTest/kotlin/afield/stdlib/WaveshaperFamilyTest.kt` — NEW (7 tests):
  per-op graph round-trip (deepCopy preserves op + param/mode children + eval),
  OpRegistry transmutability (canonical + alias + rewrite DSL), exact numeric
  behaviour, and a visual-proof montage SVG.
- `waveshaper-family-proof.svg` — committed visual proof (rendered by the test).
- `public/docs/assets/functions/waveshaper-family-evolvable.operations.yaml` —
  NEW loadable demo transcript.
- `public/docs/functions_examples.md` — documented the three as first-class
  transmutation targets; added crush bullet + family summary; added the trio to
  the `op=` value-transform list; linked the new demo.

### Verification (observed, honest)
- `./gradlew compileKotlinJvm -q` — clean.
- `./gradlew jvmTest --rerun-tasks` on the five relevant suites — BUILD
  SUCCESSFUL. Counts from `build/test-results/jvmTest/*.xml`:
  FunctionsGraphRewriteTest 18/0/0, WaveshaperFamilyTest 7/0/0,
  FnWaveshapeTransformTest 5/0/0, ChebyshevWaveshaperTest 5/0/0,
  RampRemapTest 5/0/0 (tests/failures/errors). 40 tests, 0 failures.
- The editor↔registry sync test (`editorOpVocabularyMirrorsTheRegistryInBothDirections`,
  inside FunctionsGraphRewriteTest) passes with the new ops.
- Visual proof `waveshaper-family-proof.svg` rendered to PNG and eyeballed:
  rectify(FULL) flips negatives up, rectify(HALF) clamps them to 0, quantize(6)
  stairsteps, crush(3) bit-reduces — all correct, none flat/garbled.
- NOT live-replayed: the demo transcript (needs a full Field graphics-context +
  Playwright runtime; heavy, and this Mac's disk risk per memory notes). It uses
  the identical proven pattern as the existing `waveshape-transforms.operations.yaml`
  demo, and its transmutation lines (`clip => op=rectify/quantize/crush`) are the
  exact expressions asserted green in WaveshaperFamilyTest.

### Evolve montage
Shipped the per-op montage + a demo that shows evolve-reachability via `op=`
transmutation of a clipped sine into each op (the same vocabulary the evolve loop
mutates). A full multi-generation evolve *search* montage was scoped out to stay
under budget; the transmutation demo covers the "now reachable" claim directly.

### Risks / unknowns
- Low risk. Numeric behaviour is pinned by tests; graph structure change (FULL
  rectify is now a `rectify` node instead of `abs`) is internal and value-identical.
- `crush`/`quantize`/`rectify` params are numeric `param` children read with
  `roundToInt()`; a non-integer animated `levels`/`bits` would now round per-sample
  (previously impossible — Int only). This is a superset, backward-compatible.
- Demo transcript not executed live (see above).

### Recommendation: MERGE
Self-contained, additive, all targeted tests green, visual proof verified, docs +
loadable demo shipped. Completes the waveshaper family in the OpRegistry exactly
as the mission specified.

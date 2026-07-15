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

## Execution log
(filled in below as work proceeds)

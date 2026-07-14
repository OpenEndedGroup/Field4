# Nightly Worklog — 2026-07-14 / 1 — First-class Chebyshev waveshaper op

Kind: A (feature / generalization)
Branch: `nightly/2026-07-14/1-fn-chebyshev-waveshaper` (base 127fc7d0)

## Plan

Goal: add a `chebyshev(order)` waveshaper to the Functions DSL as a genuinely
first-class op — reachable by `op=` transmutation and `evolveVariety`, not just a
`mapY` lambda like `rectify`/`quantize`/`crush`.

Chebyshev is the canonical harmonic-generating shaper: Tₙ(cos θ) = cos(nθ), so
feeding a unit-amplitude cosine through Tₙ produces the n-th harmonic. Recurrence:
T₀(x)=1, T₁(x)=x, Tₙ(x)=2x·Tₙ₋₁(x)−Tₙ₋₂(x). Standard waveshaper guard: clamp input
to [-1,1] before evaluating (matches how `fold`/`softClip` bound their input).

Design decision — model `order` as a **param child** (an `Fn`), exactly like
`fold`'s `threshold`. This:
  - round-trips through serialize→rebuild (it is a real graph edge, not baked scalar);
  - gives both `chebyshev(order: Int)` and `chebyshev(order: Fn)` overloads for free,
    mirroring `fold(Number)` / `fold(Fn)`;
  - lets the OpRegistry builder read it with `it.childOr(2.0, "order")` just like
    `fold` reads `it.childOr(1.0, "threshold")`.
Order is rounded to the nearest non-negative int at eval time (`roundToInt`), so a
swept/animated order is even a bonus generative feature.

Three surfaces:
  (a) `Fn.chebyshev(order: Fn)` + `Fn.chebyshev(order: Int)` emitting a rebuildable
      `node("chebyshev", children=[fnEdge("source",this), fnEdge("order", order)],
      rebuild={ it[0].chebyshev(it[1]) })`. Placed next to `Fn.fold`.
  (b) `chebyshev(order)` `FnTransform` next to the fold/rectify/quantize transforms,
      so `signal through chebyshev(3)` composes.
  (c) `OpSpec("chebyshev", "transform", …)` in `OpRegistry.specs` using
      `buildUnary { f -> f.chebyshev(it.childOr(2.0, "order")) }`.

Sync obligations (kept green):
  - `FunctionsGraphRewriteTest.editorOpVocabularyMirrorsTheRegistryInBothDirections`
    requires every `transmutableOps` canonical name to appear in `fnrewrite.js`
    `opWords` → add `"chebyshev"` there.
  - `chebyshev` becomes a `specByName` key so `knownOps` covers it automatically.

Verification:
  - `./gradlew compileKotlinJvm`
  - New `ChebyshevWaveshaperTest`: (i) Tₙ identity (2cos²θ−1 = cos2θ) at sampled θ;
    (ii) graph serialize→rebuild (deepCopy) preserves op `chebyshev` and evaluates
    identically; (iii) `OpRegistry.isTransmutable("chebyshev")` true and
    `OpRegistry.rebuild(...)` non-null.
  - `*FunctionsGraphRewriteTest*` stays green.
  - Visual proof: render sine → chebyshev(2/3/4/5) to an SVG in the worktree, eyeball it.

Docs + demo:
  - Extend the "Shaping signals" waveshaping doc + `waveshape-transforms` assets.
  - Leave a self-contained replayable transcript demo in the worktree.

# Nightly worklog — 2026-07-18 / 1 / Differentiable Fn-graph fitting (kind A)

Branch: `nightly/2026-07-18/1-fn-differentiable-fit`
Base: `1ea64a2d` (green: `compileKotlinJvm` exit 0)

## Plan

Build the differentiable counterpart to the genetic `evolve` in
`src/jvmMain/kotlin/afield/stdlib/FunctionEvolve.kt`: a gradient-based optimizer over
`Functions.Fn` graphs that tunes the *numeric leaves* (the θ-vector) of a fixed-structure
seed graph to match a target `Fn`.

### θ extract / apply — verified against the real API

`Functions.Fn.mapGraphNumbers(preserveSharing, transform)` (FunctionGraph.kt:146) drives
`rewriteGraph` → `deepCopy` → `copyRec`, a **post-order DFS**. The `transform` is invoked
once for every node that carries a `numbers["value"]` key (that is exactly the `const` and
`param` leaves — both tagged `"number"`). Traversal order is stable and deterministic with
`preserveSharing = false` (each positional visit is independent; no memo reordering).

- **extract** (`Fn -> DoubleArray`): call `mapGraphNumbers`, append each `value` to a list,
  return it unchanged (so the returned graph is identical — we only want the ordered read).
- **apply** (`Fn, DoubleArray -> Fn`): call `mapGraphNumbers` driven by an index counter,
  returning `theta[i++]`. Same traversal ⇒ same order ⇒ slot `i` always maps to the same
  leaf. Note `mapGraphNumbers` swaps a changed leaf for a fresh `const` node; unchanged
  leaves keep their node. We always apply to the *unchanged base seed*, so the leaf count /
  order is invariant across iterations.

### Loss

Reuse `matchFitness(target, domain, samples)`'s grid semantics (even sample grid over a
`Domain`, NaN-safe RMSE) by negating it: `loss(θ) = -matchFitness(...)(base.apply(θ))`
= RMSE ≥ 0, or `+∞` when no grid point is jointly finite.

### Gradient

Central (symmetric) finite differences per parameter with a **relative** step
`h = fdStep * (1 + |θ_i|)`. Any parameter whose ±perturbation yields a non-finite loss has
its gradient frozen to 0 for that step (guards structural / degenerate leaves).

### Descent — Adam

Standard Adam (lr, β1, β2, ε, iterations), fully deterministic. All randomness (only an
optional init jitter) goes through a single seeded `kotlin.random.Random`. Returns an
`FnFitRun` provenance record mirroring `FnEvolveRun`: fitted `Fn`, per-step loss trajectory,
initial vs final RMSE, config/seed, evaluation count.

### Entry points

- `fitGradient(target, seed, domain, iterations, lr, ...)` — top-level, matching the
  all-top-level style of `FunctionEvolve.kt`.
- `fitHybrid(...)` — `evolve` to find structure, then gradient-polish the numeric params.
- `Functions.Fn.extractParameters()` / `applyParameters(θ)` / `rmseLossAgainst(...)`.

### Verify

`FunctionFitTest`: (a) RMSE drops far below initial on a sine target and a 2-oscillator sum;
(b) determinism (same seed ⇒ identical trajectory); (c) beats a genetic-only `evolve`
baseline of comparable budget. Plus a Java2D PNG montage (target vs seed vs fitted) rendered
from the *real* fitted `Fn` — inspected by eye.

### Demo artifact

`dev_docs/agents/transcripts/fn_differentiable_fit_demo.yaml` — replayable into a fresh
Field4: builds a box that fits a wobbly seed to a target sine and plots both.

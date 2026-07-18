# Fitting `Functions.Fn` graphs: genetic and differentiable

Field's `Functions.Fn` is a function *and* an inspectable graph (see
`afield.stdlib.FunctionGraph`). Two complementary optimizers tune that graph toward a
target signal:

| | searches over | file | entry point |
| --- | --- | --- | --- |
| **Genetic** | *structure* (operators + topology) | `FunctionEvolve.kt` | `evolve(...)` |
| **Differentiable** | *numbers* (the numeric leaves) | `FunctionFit.kt` | `fitGradient(...)` |

## The θ-vector

The tunable "genes" of a graph are its numeric leaves — every `const`/`param` node carrying
`numbers["value"]`. `FunctionFit.kt` reads and writes them through the stable post-order DFS
of `Functions.Fn.mapGraphNumbers`:

- `fn.extractParameters(): DoubleArray` — the leaves in traversal order (θ).
- `fn.applyParameters(theta): Fn` — the same graph with leaf *i* set to `theta[i]`.

Because the read and the write share one deterministic traversal, slot *i* always maps to
the same leaf: a clean θ↔graph bijection. `parameterCount()` gives dim(θ).

## `fitGradient` — Adam over the leaves

```kotlin
with(Functions) {
    val target = sin(1.5, amplitude = 0.8, offset = 0.2)      // a·sin(freq·x)+b
    val seed   = sin(1.0, amplitude = 1.0, offset = 0.0)      // same shape, wrong numbers
    val run    = fitGradient(target, seed, domain = Domain(0.0, 1.0), iterations = 300, lr = 0.05)
    // run.finalRmse ≈ 0.008 (from an initial ≈ 0.96); run.fitted overlays the target.
}
```

- **Loss** — `rmseLossAgainst(target, domain, samples)` reuses `matchFitness`'s NaN-safe even
  sample grid, negated to an RMSE (lower is better; `+∞` when nothing is jointly finite).
- **Gradient** — central (symmetric) finite differences with a *relative* step
  `fdStep·(1+|θ_i|)`; a parameter whose ±perturbation goes non-finite is frozen for that step.
- **Descent** — textbook **Adam** (`lr, beta1, beta2, epsilon`), fully deterministic (only an
  optional `initJitter` draws from a single seeded `Random`).
- **Provenance** — returns `FnFitRun`: the fitted `Fn`, the full loss trajectory
  (`losses`, start-inclusive), initial vs final RMSE, evaluation count, and the `FnFitConfig`
  seed/config — mirroring `evolve`'s `FnEvolveRun`.

## `fitHybrid` — structure then numbers

`fitHybrid(target, seed, ...)` runs `evolve` to discover a *shape*, then hands its numeric
leaves to `fitGradient` for a precise polish — genetic search finds the right operators,
Adam nails the constants. Returns `FnHybridRun(evolveRun, fitRun)` with `seedRmse`,
`evolvedRmse`, `finalRmse`.

## Verify / see it

- Tests: `afield.stdlib.FunctionFitTest` (RMSE reduction, determinism, a win over a
  comparable-budget genetic baseline, hybrid polish, θ round-trip) — it also renders
  `fn_fit_montage.png` from the real fitted `Fn`.
- Loadable demo: replay `dev_docs/agents/transcripts/fn_differentiable_fit_demo.yaml` into a
  fresh Field4 (agent backdoor / `field_run_transcript`) — it plots a target and its
  gradient-fitted match in one box.

# Fitting `Functions.Fn` graphs: genetic and differentiable

Field's `Functions.Fn` is a function *and* an inspectable graph (see
`afield.stdlib.FunctionGraph`). Two complementary optimizers tune that graph toward a
target signal:

| | searches over | file | entry point |
| --- | --- | --- | --- |
| **Genetic** | *structure* (operators + topology) | `FunctionEvolve.kt` | `evolve(...)` |
| **Differentiable** | *numbers* (the numeric leaves) | `FunctionFit.kt` | `fitBest(...)` / `fitGradient(...)` |
| **Both** | structure then numbers | `FunctionFit.kt` | `fitHybrid(...)` |

`domain` accepts a bare range (`0.0 .. 1.0`) or a `Domain`, and defaults to `Domain.DEFAULT`
(`0..1`) — no `Functions.Domain(...)` at the call site. (Oscillator `Fn`s report `Domain.ALL`,
which can't be sampled, so the default is the bounded unit domain, not the target's own domain.)

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
    val target = sin(1.5, amplitude = 0.8, offset = 0.2)   // a·sin(freq·x)+b
    val seed   = sin(1.0, amplitude = 1.0, offset = 0.0)   // same shape, wrong numbers
    fitGradient(target, seed, iterations = 300, lr = 0.05)  // domain defaults to 0..1
    // .finalRmse ≈ 0.008 (from an initial ≈ 0.96); .fitted overlays the target.
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
  seed/config — mirroring `evolve`'s `FnEvolveRun`. Just `return run` in a box: the
  `ValueVisualizer` renders it as a compact `RMSE before → after` line with a loss sparkline.

## `fitBest` — restarts that escape local minima *(recommended)*

`fitGradient` is *local*. Amplitude/offset enter the loss ~linearly, so one shot finds them —
but **oscillator frequency/phase enter the loss periodically**, so `RMSE`-vs-frequency is
multimodal. From a wrong frequency, a lone descent drifts into a spurious basin and stalls.
Concretely, fitting `cos(2)*sin(1.5,…)` from a `cos(3.4)*sin(1.0,…)` seed: `fitGradient` sticks
at RMSE ≈ 0.19 (and *more* iterations don't help — it's a floor, not undertraining), while the
global optimum is ≈ 0.006.

```kotlin
with(Functions) {
    val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
    val seed   = cos(3.4) * sin(1.0, amplitude = 2.0)
    fitBest(target, seed, restarts = 16)   // finalRmse ≈ 0.006
}
```

`fitBest` runs `restarts` independent fits — restart 0 from the clean seed (so it's never worse
than a plain fit), the rest from `±restartJitter*(1+|θ|)` shakes seeded from `rngSeed + k` — and
keeps the lowest-RMSE result. Deterministic given `rngSeed`. When the seed is *structurally*
wrong (missing operators), use `fitHybrid` instead — it searches shape first.

## `fitHybrid` — structure then numbers

`fitHybrid(target, seed, ...)` runs `evolve` to discover a *shape*, then hands its numeric
leaves to `fitGradient` for a precise polish — genetic search finds the right operators,
Adam nails the constants. Returns `FnHybridRun(evolveRun, fitRun)` with `seedRmse`,
`evolvedRmse`, `finalRmse`.

## Verify / see it

- Tests: `afield.stdlib.FunctionFitTest` (RMSE reduction, determinism, θ round-trip, bare-range
  domain, the concise `FnFitRun` render, and the honest hard case: a single-shot gradient
  *stalls* in a frequency local-minimum while `fitBest` escapes it) — it also renders
  `fn_fit_montage.png` from the real `fitBest` result on that hard target.
- Loadable demo: replay `dev_docs/agents/transcripts/fn_differentiable_fit_demo.yaml` into a
  fresh Field4 (agent backdoor / `field_run_transcript`) — it plots a target and its
  gradient-fitted match in one box.

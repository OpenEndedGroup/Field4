# Nightly worklog — 2026-07-12/1 evolve-variety-search

## Plan

**Mission (kind A):** add genetic *variety generation* (behavioural novelty / diversity
search) to the Functions evolve harness, built on the existing graph primitives and the
same seeded-`Random` determinism.

**Refined against the real code** (`src/jvmMain/kotlin/afield/stdlib/FunctionEvolve.kt`):

The file already gives us everything we need:
- `evolve(seed, iterations, lambda, rngSeed, mutations, fitness): FnEvolveRun` — a
  deterministic (µ+λ) hill-climb; fitness is an arbitrary `(Fn) -> Double`, higher = better.
- `FnMutations.defaults()` — the mutation palette.
- `matchFitness(...)` — samples `evalSafe` across a `Domain` into an RMSE objective.
- `graphMetrics().cost` for parsimony.

So variety search is a *fitness + orchestration* layer, not new graph machinery. Design:

1. **Behaviour characterization.** `Fn.behaviour(domain, samples): FnBehaviour` — sample
   `evalSafe` on a fixed grid across the domain (same grid convention as `matchFitness`),
   record a fixed-length descriptor (non-finite → 0.0) plus a `finiteFraction`. Add
   `FnBehaviour.distanceTo(other)` = RMS of descriptor differences.

2. **Novelty fitness.** `noveltyFitness(archive, domain, samples, neighbours, validityWeight)`
   returns a `(Fn) -> Double` that rewards a candidate for being far (mean of k-nearest
   archive behaviours) from an archive of already-seen behaviours, plus a small validity
   term so all-NaN degenerates don't win. Reuses `evolve` unchanged.

3. **`evolveVariety(seed, count, …): FnVarietyRun`.** Grows a set of `count` behaviourally
   distinct `Fn`s: for each slot, run `evolve` with `noveltyFitness(archive)`, adopt the
   best, append its behaviour to the archive, then drift the seed to that result so the
   next slot has rich material to mutate away from. Deterministic: per-slot
   `rngSeed = rngSeed*31 + k`. Exposes `meanPairwiseDistance` as the diversity metric.

Everything pure-JVM and deterministic: same `rngSeed` + inputs → identical set.

**Success criterion / verification.** New deterministic test in
`src/jvmTest/kotlin/afield/stdlib/` asserting (a) same seed → identical set
(via `graphSignature`), and (b) the variety set's `meanPairwiseDistance` is strictly
greater than that of a baseline set built from `count` plain `evolve` runs toward one
`matchFitness` target (which cluster near the target). Metric: mean pairwise behavioural
distance.

## Execution

(filled in below)

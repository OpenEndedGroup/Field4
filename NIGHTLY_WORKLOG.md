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

Implemented as a fitness + orchestration layer on the existing harness — no new graph
machinery needed.

### Files changed and why
- `src/jvmMain/kotlin/afield/stdlib/FunctionEvolve.kt` (+~180 lines): added
  `FnBehaviour` (value-based `equals`/`hashCode` because it wraps a `DoubleArray`) with
  `distanceTo`; `Fn.behaviour(domain, samples)` (even-grid `evalSafe`, non-finite → 0.0,
  plus `finiteFraction`); `noveltyFitness(archive, …)` (k-nearest behavioural novelty +
  validity bonus, a drop-in `(Fn) -> Double` for `evolve`); `FnVarietyRun` (with
  `meanPairwiseDistance`); and `evolveVariety(seed, count, …)` (per-slot
  `rngSeed*31 + slot`, archive-growing novelty search, seed drift onto each winner).
  Matches the file's doc-comment style, `require(...)` guards, and `FnEvolveRun` data-class
  shape.
- `src/jvmTest/kotlin/afield/stdlib/FunctionVarietyTest.kt` (new): 4 tests — descriptor
  distance sanity, reproducibility (same seed → identical `graphSignature`s and spread),
  diversity vs. baseline, and argument guards.
- `public/docs/functions_examples.md`: new "Variety generation — novelty search" subsection
  after the `evolve(...)` prose (~line 3494), with a runnable `evolveVariety` snippet.

### Verification (verified)
Ran the module-scoped test from the worktree root:
`./gradlew jvmTest --tests "afield.stdlib.FunctionVarietyTest"` → **BUILD SUCCESSFUL**,
`tests="4" skipped="0" failures="0" errors="0"` (all four passed, incl. the strict
`variety.meanPairwiseDistance > baselineSpread` diversity assertion and same-seed
reproducibility).

**Important caveat on how I ran it:** the committed `jvmTest` source set does **not**
compile as-is — two pre-existing, unrelated broken test files
(`src/jvmTest/kotlin/organic/FLineLinearizerTest.kt`,
`PointMatcherTest.kt`) reference symbols (`linearize`, `PointMatcher`,
`FLINE_LINEAR_VERTEX_STRIDE`) that exist nowhere in the source tree. They fail to compile
on the base commit, independent of my change. To observe my test pass I temporarily moved
those two files out of the tree, ran, and moved them back (they are unchanged and remain
committed as-is; `git status` for `organic/` is clean). My production code
(`compileKotlinJvm`) compiled cleanly with no new errors (only pre-existing warnings).

### Risks & unknowns
- The diversity assertion uses a fixed seed and a `matchFitness`-toward-a-constant
  baseline; the margin was comfortably positive but is not enormous — a future change to
  the mutation palette or RNG stream could narrow it. It is a genuine behavioural property,
  not a tautology, so it should stay robust.
- I could not run the full `jvmTest` suite because of the two pre-existing broken
  `organic/` tests; only my new test class was exercised.

### Recommendation
**Merge.** The feature is self-contained, deterministic, matches the file's API/style, and
its verifying test passes. It directly serves the user's stated #1 theme (genetic variety
generation) and adds no new dependencies or graph primitives. The only wrinkle is the
pre-existing broken `organic/` tests, which are out of scope here and worth a separate
fix.

# Nightly worklog — 3b: variety montage (post-review follow-up)

_Branch `nightly/2026-07-12/3b-variety-montage`, based on branch 3 (`3-evolve-worked-example`)
with branch 1 (`1-evolve-variety-search`) merged in. This is a follow-up requested after the
nightly run: revise the worked example (C) to showcase the variety feature (A). The original
branch-3 worklog is preserved below the divider._

## Plan

C's worked example demonstrated only the single-target `matchFitness` hill-climb; A shipped
the marquee `evolveVariety` feature with a unit test but **no rendered evidence**. Close the
gap: extend the CPU-only harness to also render an `evolveVariety` small-multiples montage,
so the flagship example shows the on-theme capability (a *set* of diverse gestures).

## What happened — and the finding that reshaped it

Building the montage surfaced a real limitation in A as shipped: `noveltyFitness` measures
distance in **raw sampled-value space**, so novelty search "wins" most cheaply by scaling
amplitude. Observed directly:
- Default mutation palette → `meanPairwiseDistance = 6.66e28`; the montage was one giant
  spike with the other five cells collapsed to flat lines.
- Structure-only palette (`substituteOp` + `prune`) tamed the range (`spread = 2.48`) but a
  simple seed has too few reachable bounded shapes, so 3 of 6 cells rendered identically.

The fix (chosen by the human when asked): make novelty able to measure **shape**.

## Changes

**`src/jvmMain/kotlin/afield/stdlib/FunctionEvolve.kt`** (enhances branch 1/A, backward-compatible):
- `Fn.behaviour(domain, samples, normalize = false)` — new `normalize` z-scores the
  descriptor (subtract mean, divide by RMS; flat signals → zeros) so distance captures shape
  independent of amplitude/offset.
- `noveltyFitness(..., normalize = false)` and `evolveVariety(..., normalize = false)` thread
  the flag through (archive descriptors built with the same flag). Default `false` keeps A's
  original semantics, so **A's 4 tests are unchanged and still pass**.

**`src/jvmMain/kotlin/afield/examples/functions/EvolveWorkedExample.kt`** (extends C):
- Runs `evolveVariety(seed = sin(2.0) * 0.5, count = 6, iterations = 48, rngSeed = 7,
  neighbours = 3, normalize = true)` with the default mutation palette (safe now — magnitude
  is no longer rewarded, so `duplicateSubtree` can't run away).
- New `svgMontage(...)` renders a 3×2 grid, one polyline per result, plotting the stored
  **normalized behaviours** (shared z-scored scale → cells are directly comparable as shape),
  with a header caption reporting `meanPairwiseDistance`.
- `render()` returns the new `variety`/`varietySvg` artifacts; `main()` prints + `check()`s them.

**`src/jvmTest/.../EvolveWorkedExampleTest.kt`** — asserts the montage: `VARIETY_COUNT` results,
`meanPairwiseDistance > 0`, ≥6 polylines, and byte-identical re-render (determinism).

**`public/docs/functions_examples.md`** — updated A's variety snippet to pass `normalize = true`,
added a "Shape vs amplitude" explainer (why the metric matters in open-ended generation), and
embedded the montage with its caption.

**`public/docs/assets/evolve/evolve-variety.svg`** — committed montage evidence.

## Verification (observed)

- `./gradlew runEvolveWorkedExample` → `evolve worked example passed`;
  `evolveVariety ... results=6 meanPairwiseDistance=1.5129269673411734`.
- Montage: all **6 polylines distinct** (unique point strings), every cell has real vertical
  extent (no flat lines), `spread = 1.513`, viewBox 720×324.
- Determinism: two consecutive runs produced **byte-identical** montage, overlay, and trace.
- Tests (with the pre-existing broken `organic/*` files parked, then restored unchanged —
  `git status` clean afterward): `FunctionVarietyTest` 4/0/0, `FunctionsGraphRewriteTest`
  17/0/0, `EvolveWorkedExampleTest` 1/0/0 — all `failures=0 errors=0`.
- `df -h /`: ~31 GiB free.

## Risks & unknowns

- The `normalize` enhancement lives on this branch, not on branch 1 — if branch 1 is merged
  and this is not, the flag won't exist. This branch has branch 1 as an ancestor, so merging
  #1 then this branch is clean.
- Still could not run the *full* jvmTest suite (the base `organic/*` compile break, backlog).
  Scoped runs are honest but not a whole-suite green.
- `normalize` collapses amplitude/offset entirely; a caller wanting amplitude-aware variety
  should leave it off. Documented.

## Recommendation

**Merge** (after branch 1). Net effect: A's variety feature gains a shape-aware `normalize`
option that makes it do what "variety generation" should, plus committed visual evidence and a
doc explainer. All touched tests pass; the enhancement is backward-compatible.

---

_Below: original branch-3 worklog, preserved for provenance._

# (branch 3) evolve worked example — see git history for full text.

# DiscreteEnergy: discrete optimisation by energy descent

After Hoover, Blayney and Whittington, "Classical Energies Learn to Reason 1,000× Faster than Looped
Models", arXiv 2611.17309 (CC-BY).
- Code: `src/jvmMain/kotlin/afield/math/energy/DiscreteEnergy.kt` (`DiscreteEnergy`, `EnergyState`,
  `MeanFieldDescent`, `GreedyMoves`, `GibbsAnnealing`, `PseudolikelihoodLearner`).
- Test: `DiscreteEnergyTest`.
- Page: `public/docs/discrete_energy.md`.
- Demo: `dev_docs/agents/transcripts/discrete_energy.yaml`.

## Design
- Variables each take one of `domains[v]` values. Each value is a unit, and a variable is a one-hot group. The energy is
  `½ pᵀWp + B·p`, with W symmetric and dense `units × units` (fine up to about 1000 units). Builders: notEqual, equal,
  allDifferent, pairCost, prefer, forbid, fix. All of them keep W symmetric.
- Solvers work in place on an `EnergyState` (v, p, assignment). Each has `step` and `settle`, so a box can draw every step.
- The GPU shader in the transcript is generic: one invocation per variable, reading offset, size and fixed value per
  variable from the `gpuState` layout. Its update is identical to `MeanFieldDescent.step`.
- Box scope: `afield.math.energy.*` is in the box default imports. It holds only the prefixed class names above and no
  top-level functions.

## Method, checked against the paper (2026-11-25)
- Energy: the paper's Eq. 1. Dynamics: the paper's Eq. 3 (η = 0.5, β = 1).
- Training: pseudolikelihood on solutions only, with no solver in the loop, as in the paper. Mine is per-sample SGD with
  an L1 proximal step. The paper uses full-batch momentum SGD with a cosine schedule.
- Search: the paper uses a focused Gibbs search with several chains; mine is plain annealed Gibbs. On Sudoku the
  schedule matters a great deal: 300 sweeps from 0.5 to 0.02 solved 0 of 10 runs on the 30-clue demo puzzle, and
  5000 sweeps from 0.6 to 0.15 solved 10 of 10 (about 2.5 s each).
- My first attempt used a contrastive rule against the solver's fixed point, which is not the paper's. It is replaced.

## Numbers (DiscreteEnergyTest, CPU)
- Sudoku, stated, on 20 puzzles per row (2 chains, 2000 sweeps, temperature 1.0 → 0.1): 20, 20, 14 and 11 of 20 at
  30/40/50/60 blanks (65/80).
- Sudoku, learned from 1000 grids over 20 epochs (55 s): 20, 16, 5 and 5 of 20 (46/80).
- Learned-vs-stated same-value image correlation: 0.989.
- N-queens: 10/10 solved for n = 8 and n = 12.
- Graph colouring (40 points, 3-nearest-neighbour graph, 74 edges): 4 colours reach 0 conflicts; 3 colours leave 3.
- TSP, 10 cities: 10/10 runs give valid tours; the best is 2.95 against a brute-force optimum of 2.69 (+9.5%).
  Single-variable moves are weak on permutations.
- GPU/CPU parity: 1.04e-7 largest belief difference after 40 steps, same board.

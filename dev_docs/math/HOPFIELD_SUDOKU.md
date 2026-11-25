# Learned Hopfield–Tank energies for Sudoku

After Hoover, Blayney and Whittington, "Classical Energies Learn to Reason 1,000× Faster than Looped
Models", arXiv 2611.17309 (CC-BY). Code `src/jvmMain/kotlin/afield/math/energy/HopfieldSudoku.kt`;
test `HopfieldSudokuTest`; page `public/docs/energy_puzzles.md`; demo
`dev_docs/agents/transcripts/hopfield_sudoku.yaml`.

## Method (checked against the paper on 2026-11-25)
- Energy: the paper's Eq. 1 mean-field free energy over softmax cell beliefs, with W symmetric and zero within a cell.
- Descent: the paper's Eq. 3 Euler step, v ← (1−η)v − η(Wp+B), with η = 0.5 and β = 1.
- Search: greedy single-cell moves, then annealed Gibbs (T falls geometrically from 0.5 to 0.02 × max|W|, 300 sweeps),
  over up to 4 chains, stopping at energy ≤ max training-solution energy + 1e-3. The paper's focused Gibbs search
  is more thorough (local-energy cell choice, larger budget), and this is the main gap.
- Training: pseudolikelihood on solved grids, per-sample SGD, q − y on each cell's local conditional, random digit
  relabelling every update, and an L1 proximal step (1e-4). The paper uses full-batch SGD with momentum and a cosine
  schedule.
- First attempt, superseded: a contrastive rule (s sᵀ − v* v*ᵀ against the solver's fixed point). It was not the paper's rule.
- Generator: randomized backtracking. A pattern-shuffle generator gave a narrow family of grids whose spurious
  couplings let invalid boards score lower energy than true solutions, and 9×9 solved 0%.

## Numbers
- 4×4: 200 grids, 10 epochs, 57 ms. 399/400 held out (100, 100, 100, 99 at 4/6/8/10 blanks).
- 9×9: 1000 grids, 20 epochs, about 54–73 s CPU, pseudo-NLL 0.051. 153/300 held out: 49, 43, 29, 12, 10, 10 of 50 at
  30/40/45/50/55/60 blanks. Mean descent steps 31 → 253, rising with blanks.
- 9×9, 400 grids and 10 epochs (demo and docs): 13 s. 10, 7, 2, 0, 0 of 10 at 30/40/45/50/55.
- GPU/CPU parity (live, transcript check box): largest belief difference after 40 steps 2.98e-7, same decoded board.
- GPU descent settled a 40-blank puzzle in about 33 steps to a valid board.

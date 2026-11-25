# Learned Hopfield–Tank energies for Sudoku (first cut)

After "Classical Energies Learn to Reason 1k× Faster Than Looped Models", arXiv 2611.17309.
Code: `src/jvmMain/kotlin/afield/math/energy/HopfieldSudoku.kt`; test `HopfieldSudokuTest`.

Method: units v[cell*n+digit] in [0,1]; E = -½vᵀWv - bᵀv, W symmetric with zero diagonal. Solve by projected
gradient descent with clue units clamped, stopping when no unit moves more than 1e-4. Train contrastively,
W += lr(ssᵀ - v*v*ᵀ), with s the solution and v* the solver's settled state, so no gradient passes through the solver.
This training rule is my reading of the method; it has not been checked against the paper's text.

Measured (4×4, 400 training puzzles with 4–10 blanks, 10 epochs, lr 0.02, training 747 ms CPU), on 100 held-out puzzles per row:

| blanks | solved | mean steps |
| --- | --- | --- |
| 4 | 94 | 39 |
| 6 | 88 | 79 |
| 8 | 67 | 180 |
| 10 | 57 | 157 |

Not done yet: 9×9, GPU compute descent and its parity number, the weight image, the docs page and the demo transcript.

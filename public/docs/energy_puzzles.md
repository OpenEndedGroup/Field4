# Learned energies that solve puzzles

A Sudoku can be solved by rolling downhill. Give every cell a belief over its nine digits, write
down an energy that is low when the beliefs form a valid grid, and let gradient descent settle
them. Nobody has to write that energy by hand: its couplings can be learned from solved grids,
and the learned couplings turn out to *be* the rules of Sudoku. The model is never told about
rows, columns or boxes. This page follows Hoover, Blayney and Whittington,
[Classical Energies Learn to Reason 1,000× Faster than Looped Models](https://arxiv.org/abs/2611.17309)
(CC-BY), whose method `afield.math.energy.HopfieldSudoku` implements.

## The energy and how it learns

Each cell `i` holds a belief `p_i`, the softmax of a state `v_i` over its digits. The energy is
the paper's Hopfield–Tank free energy,

`E(p) = β⁻¹ Σ p_i·log p_i + ½ Σ_{i≠j} p_iᵀ W_ij p_j + Σ B_i·p_i`

with `W` symmetric. Solving means descending it: `v ← (1−η)v − η(W p + B)`, then the softmax
again, with the clue cells held fixed, until the beliefs stop moving. Then greedy single-cell
moves and an annealed Gibbs search push the board down further. The search stops once the
board's energy is as low as that of the training solutions.

Training never runs the solver. For each solved grid and each cell, it asks how likely the
energy makes the true digit if every other cell is held at the solution (a softmax over that
cell's nine local energies), and nudges `W` and `B` to make it more likely. This is
*pseudolikelihood*. Every update also shuffles the digit labels, so the model learns what the
digits do and not which digit is which.

This box trains a 9×9 energy on 400 solved grids, which takes about 13 seconds, and draws what it
learned. For every pair of cells, the image shows how much more energy it costs the two to hold
the same digit than different ones:

```diffstatic_runner {data-lang=kotlin screenshot="assets/energy_puzzles/weights.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
val model = HopfieldSudoku(3)
val grids = kotlin.random.Random(1).let { r -> List(400) { model.generate(r, 0).second } }
val ms = model.train(grids, epochs = 10, lr = 0.2f, l1 = 1e-4f)
println("trained on ${grids.size} grids in $ms ms")

val cost = model.sameDigitCost()
showHeatmap(
    Array(model.cells) { a -> DoubleArray(model.cells) { b -> cost[a * model.cells + b].toDouble() } },
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 486,
    channel = "same-digit cost, cell × cell",
)
```

![An 81 by 81 heatmap in magma colours. Along the diagonal sit three 27-cell bands patterned in dark and bright, each holding three bright 9-by-9 squares on its own diagonal; thin bright diagonal stripes nine cells apart cross the whole image; the rest is a flat purple](assets/energy_puzzles/weights.png)

Cells are numbered row by row. The bright 9×9 squares on the diagonal are pairs of cells in the
same row. The patterned 27-cell bands around them are pairs in the same three rows, and their
bright blocks are the pairs that share a box. The
diagonal stripes nine cells apart are pairs in the same column. Everything else, pairs that
constrain each other not at all, stays flat. The L1 penalty in `train` keeps those couplings
near zero. Without enough training grids they pick up chance correlations, and the solver then
finds invalid boards with lower energy than the real solution.

## How well it solves

Held-out puzzles come from the same generator (random valid grids, cells blanked at random).
A puzzle with many blanks can have more than one solution, so any valid grid that keeps the
clues counts as solved. This box solves ten puzzles at each of five difficulties:

```diffstatic_runner {data-lang=kotlin screenshot="assets/energy_puzzles/rate.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
val model = HopfieldSudoku(3)
val grids = kotlin.random.Random(1).let { r -> List(400) { model.generate(r, 0).second } }
model.train(grids, epochs = 10, lr = 0.2f, l1 = 1e-4f)

val test = kotlin.random.Random(99)
val blanks = [30, 40, 45, 50, 55]
val rates = mutableListOf<Double>()
val steps = mutableListOf<Double>()
for (b in blanks) {
    var ok = 0
    var s = 0
    repeat(10) { k ->
        val (p, _) = model.generate(test, b)
        val r = model.solve(p, seed = k.toLong())
        if (model.isSolution(p, r.grid)) ok++
        s += r.descentSteps
    }
    rates += ok / 10.0
    steps += s / 10.0
}
val xs = blanks.map { it.toDouble() }
display(
    "steps to settle",
    Chart(title = "harder puzzles take more steps", xLabel = "blanks", yLabel = "mean descent steps")
        .line("steps", xs, steps)
        .points("steps", xs, steps),
)
display(
    "solve rate",
    Chart(title = "solve rate", xLabel = "blanks", yLabel = "fraction solved")
        .line("solved", xs, rates)
        .points("solved", xs, rates),
)
```

![Two charts against the number of blanks, 30 to 55. Top: mean descent steps rising from about 45 at 30 blanks to about 190 at 50, then falling back to about 130 at 55. Bottom: the fraction solved falling from 1 at 30 blanks through 0.7 at 40 and 0.2 at 45 to 0 at 50 and 55](assets/energy_puzzles/rate.png)

The descent stops by itself, and it takes longer on harder puzzles. Past 50 blanks it gives up
sooner, because the extra chains and the Gibbs search are cut off by their budgets. The test
`HopfieldSudokuTest` trains longer, on 1,000 grids for 20 epochs (about 70 seconds on the CPU),
and solves 153 of 300 held-out puzzles: 49 of 50 at 30 blanks, 43 at 40, 29 at 45, then 10 to 12
of 50 from 50 to 60 blanks. A 4×4 energy solves 399 of 400. The paper reports essentially every
Sudoku-Extreme puzzle solved, using a more thorough focused Gibbs search with several chains and
a much larger search budget; the search here is the weaker half.

## The descent on the GPU

Each descent step is one matrix–vector product, which suits a compute shader with one
invocation per cell. The [`hopfield_sudoku` transcript](agent_transcripts.md#hopfield_sudoku)
runs it that way under a renderer. It uploads the learned `W` and a `gpuState` buffer that holds
two halves of beliefs, ping-ponged each step. A `BondedClock` steps the shader once per frame, and
the box redraws the board as it settles, each blank's strongest digit brightening as its belief
sharpens. A check box freezes the GPU after 40 steps and compares it with
`HopfieldSudoku.beliefsAfter`, the CPU reference: the largest belief difference was 3·10⁻⁷, and
both decode to the same board.

## Where to go next

- [Agent transcripts: hopfield_sudoku](agent_transcripts.md#hopfield_sudoku) replays the whole
  thing, GPU descent included, into a fresh document.
- [Gaussian-process optimisation](gaussian_process_optimization.md) is the other way this
  section learns a function from examples.
- [Field 4 graphics](afield_four_graphics.md) explains compute shaders, buffers and the renderer
  topology the GPU box relies on.

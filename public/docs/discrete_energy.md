# Discrete optimisation by energy descent

Some problems are choices rather than numbers. Each region of a map gets one of four colours,
each queen one of eight columns, each Sudoku cell one of nine digits. `DiscreteEnergy` states
such a problem as an *energy*: every variable takes one of a few values, and each choice, or pair
of choices, has a cost. A good answer is a low-energy assignment, found by letting the energy roll
downhill. The method follows Hoover, Blayney and Whittington,
[Classical Energies Learn to Reason 1,000× Faster than Looped Models](https://arxiv.org/abs/2611.17309)
(CC-BY). Their point, reproduced at the end of this page, is that the costs need not be written
by hand: they can be learned from solved examples.

## Stating a problem

Make a `DiscreteEnergy` with the number of variables and how many values each can take, then add
costs:

| builder | cost |
| --- | --- |
| `notEqual(a, b)` / `equal(a, b)` | when `a` and `b` take the same (different) values |
| `allDifferent(vars)` | `notEqual` on every pair of `vars` |
| `pairCost(a, b) { x, y -> ... }` | any cost of `a` taking `x` while `b` takes `y` |
| `prefer(v, x)` / `forbid(v, x)` | lowers (raises) the cost of `v` taking `x` |
| `fix(v, x)` | clamps `v` to `x`; solvers leave it alone |

Then make an `EnergyState`, the beliefs and assignment the solvers work on, and settle it. There
are four solvers, each with a `step` you can use to draw its progress:

- `MeanFieldDescent` treats each variable as a soft belief over its values and moves all of them
  downhill together. It is the Hopfield–Tank network, and it stops by itself when the beliefs stop
  moving.
- `GreedyMoves` makes the single change that lowers the energy most, until none does.
- `GibbsAnnealing` resamples each variable in turn from its local energies at a temperature that
  falls sweep by sweep, so it can climb out of the dead ends the other two stop in.
- `MetropolisAnnealing` anneals too, but proposes moves from a list of *move sets*.
  `GreedyMoves` takes the same list. `ChangeOneValue` is the default. `SwapValues(group)` and
  `ReverseSegment(group)` change several variables at once and keep a group a permutation, which
  a tour or an ordering needs.

Colouring a graph is the shortest example. Forty points are each joined to their three nearest
neighbours, and every edge costs 1 if its two ends share a colour. The box runs mean-field descent
one step per frame, then Gibbs sweeps until no edge conflicts. Each dot shows its strongest colour,
larger as its belief sharpens:

```diffstatic_runner {data-lang=kotlin video="assets/discrete_energy/colouring.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="7" fps="20" timeout="240" embed=true name="energy-colouring-video" alt="Forty dots joined by grey edges start small and mixed, grow and settle into four colours over a few dozen steps, a handful of neighbours still clashing, then the Gibbs sweeps recolour them until the caption reads 0 conflicts"}
:::static
val rnd = kotlin.random.Random(5)
val pts = List(40) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
val edges = HashSet<Pair<Int, Int>>()
for (i in pts.indices) pts.indices
    .filter { it != i }
    .sortedBy { pts[it].distance(pts[i]) }
    .take(3)
    .forEach { edges += minOf(i, it) to maxOf(i, it) }

// the whole problem: 40 variables of 4 values, and no edge may join two of the same
val colouring = DiscreteEnergy(pts.size, 4)
for ((a, b) in edges) colouring.notEqual(a, b)

val s = EnergyState(colouring).reset(rnd)
val descent = MeanFieldDescent(colouring)
val gibbs = GibbsAnnealing(colouring, sweeps = 100, startTemperature = 1.0, endTemperature = 0.05, seed = 2)

val palette = [vec(0.95, 0.4, 0.35, 1.0), vec(0.35, 0.75, 1.0, 1.0), vec(0.98, 0.85, 0.3, 1.0), vec(0.5, 0.9, 0.5, 1.0)]
fun draw(caption: String) {
    val wires = fline {
        for ((a, b) in edges) {
            moveTo(pts[a].x, pts[a].y)
            lineTo(pts[b].x, pts[b].y)
        }
        color = vec(0.6, 0.6, 0.6, 0.6)
    }
    val dots =
        pts.indices.map { i ->
            fline {
                val r = 3.0 + 5.0 * s.p[colouring.unit(i, s.assignment[i])]
                moveTo(pts[i].x - r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y - r)
                lineTo(pts[i].x + r, pts[i].y + r)
                lineTo(pts[i].x - r, pts[i].y + r)
                close()
                filled = true
                stroked = false
                color = palette[s.assignment[i]]
            }
        }
    lines(wires, *dots.toTypedArray(), w = 440.0, h = 340.0, fitToSize = true)
    display("conflicts", caption)
}

// one recorded frame per Gibbs sweep, three per descent step, so the settling can be watched
frames {
    draw("start: ${s.energy.toInt()} conflicts")
    repeat(6) { frame() }
    var step = 0
    while (descent.step(s) > 1e-4f && step < 200) {
        step++
        draw("mean-field step $step: ${s.energy.toInt()} conflicts")
        repeat(3) { frame() }
    }
    while (gibbs.step(s)) {
        s.beliefsFromAssignment()
        draw("Gibbs sweep ${gibbs.sweep}: ${s.energy.toInt()} conflicts")
        frame()
    }
    draw("${edges.size} edges, ${s.energy.toInt()} conflicts")
}
```

Mean-field descent alone leaves a few edges in conflict, and the Gibbs sweeps, hot at first and
then cooling, clear them. In
`DiscreteEnergyTest`'s version of this graph, four colours reach zero conflicts and three leave
three.

### Queens and tours

N-queens is one variable per row, whose value is the column of that row's queen. The columns must
all differ, and `pairCost` forbids diagonals: two rows `b - a` apart may not have queens that many
columns apart.

```diffstatic_runner {data-lang=kotlin screenshot="assets/discrete_energy/queens.png" wait="1.5" capture="box" open-outputs="true" padding="20" embed=true name="energy-queens" alt="A 12 by 12 chequerboard with twelve gold queens, one in each row and column, none sharing a diagonal; above it the caption 0 attacks"}
:::static
val n = 12
val queens = DiscreteEnergy(n, n)
queens.allDifferent((0 until n).toList())
for (a in 0 until n) for (b in a + 1 until n) queens.pairCost(a, b) { x, y ->
    if (kotlin.math.abs(x - y) == b - a) 1.0 else 0.0
}

val s = EnergyState(queens).reset(kotlin.random.Random(4))
MeanFieldDescent(queens).settle(s)
GreedyMoves(queens).settle(s)
GibbsAnnealing(queens, sweeps = 300, startTemperature = 1.0, endTemperature = 0.05, seed = 4).settle(s)

display(
    "board",
    "<div style='font:14px monospace;color:#ccc'>${s.energy.toInt()} attacks</div>" +
        "<div style='display:grid;grid-template-columns:repeat($n,22px);grid-auto-rows:22px;width:max-content'>" +
        (0 until n * n).joinToString("") { i ->
            val r = i / n
            val c = i % n
            "<div style='background:${if ((r + c) % 2 == 0) "#333" else "#222"};color:#ffcf6b;text-align:center;font:16px monospace'>" +
                (if (s.assignment[r] == c) "♛" else "") +
                "</div>"
        } +
        "</div>",
)
```

The travelling salesman needs costs that are not just 0 or 1. Variable `k` is the city visited
`k`-th: all different, as before, with the distance between consecutive stops as the pairwise
cost. Single-value moves suit a permutation badly: changing one stop breaks `allDifferent` until a
second stop changes too. So this box searches with moves that keep it a permutation.
`SwapValues` exchanges two stops, and `ReverseSegment` reverses a run of them, which is the 2-opt
move that uncrosses two edges. `MetropolisAnnealing` and `GreedyMoves` both take a list of such
move sets.

```diffstatic_runner {data-lang=kotlin screenshot="assets/discrete_energy/tour.png" wait="1.5" capture="box" open-outputs="true" padding="20" embed=true name="energy-tour" alt="Ten gold squares joined by a blue closed tour that does not cross itself; below, the lengths of eight annealing runs, all the same"}
:::static
val rnd = kotlin.random.Random(3)
val n = 10
val city = List(n) { vec(rnd.nextDouble() * 400, rnd.nextDouble() * 300) }
fun d(x: Int, y: Int) = city[x].distance(city[y]).toDouble()

val tour = DiscreteEnergy(n, n)
val stops = (0 until n).toList()
tour.allDifferent(stops, weight = 1000.0)
for (k in 0 until n) tour.pairCost(k, (k + 1) % n) { x, y -> d(x, y) }
tour.fix(0, 0) // start in city 0

// moves that keep the stops a permutation: exchange two, or reverse a run (2-opt)
val moves = [SwapValues(stops), ReverseSegment(stops)]
val runs =
    (0 until 8).map { seed ->
        val s = EnergyState(tour)
        for (k in 0 until n) s.assignment[k] = k // any permutation to start from
        MetropolisAnnealing(
            tour,
            moves,
            sweeps = 300,
            startTemperature = 200.0,
            endTemperature = 2.0,
            seed = seed.toLong(),
        ).settle(s)
        GreedyMoves(tour, moves).settle(s)
        s.assignment.copyOf()
    }
fun length(t: IntArray) = (0 until n).sumOf { d(t[it], t[(it + 1) % n]) }
val best = runs.minBy { length(it) }

val path = fline {
    moveTo(city[best[0]].x, city[best[0]].y)
    for (k in 1..n) lineTo(city[best[k % n]].x, city[best[k % n]].y)
    color = vec(0.35, 0.75, 1.0, 1.0)
    thickness = 2.0
}
val dots = fline {
    for (c in city) {
        moveTo(c.x - 4, c.y - 4)
        lineTo(c.x + 4, c.y - 4)
        lineTo(c.x + 4, c.y + 4)
        lineTo(c.x - 4, c.y + 4)
        close()
    }
    filled = true
    stroked = false
    color = vec(1.0, 0.85, 0.3, 1.0)
}
lines(path, dots, w = 440.0, h = 340.0, fitToSize = true)
display("runs", "tour lengths: " + runs.map { length(it) }.sorted().joinToString(", ") { it.f0 })
```

With single-value moves, the best of eight runs was 1179 and the tour crossed itself. With swaps
and reversals, on `DiscreteEnergyTest`'s ten cities, every one of ten runs finds the brute-force
optimum and none crosses itself; single-value moves on the same cities came out 20% longer.

## Sudoku, stated

A Sudoku is 81 variables of 9 values with an `allDifferent` over each row, column and box, and the
clues fixed. That is the whole statement. This box settles a well-known 30-clue puzzle: mean-field
descent first, one step per frame, then Gibbs sweeps on the board it leaves. Each free cell shows
its strongest digit in blue, brighter as its belief sharpens:

```diffstatic_runner {data-lang=kotlin video="assets/discrete_energy/sudoku.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="8" fps="20" timeout="240" embed=true name="energy-sudoku-video" alt="A Sudoku board whose blank cells start as dim, uncertain digits, sharpen over the descent steps with clashes left, then flicker through Gibbs sweeps until the caption reads energy 0; above it a line chart of the energy, falling steeply during the descent, then jagged and drifting down to zero through the sweeps"}
:::static
val clues = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
val sudoku = DiscreteEnergy(81, 9)
for (k in 0 until 9) {
    sudoku.allDifferent((0 until 9).map { k * 9 + it }) // row k
    sudoku.allDifferent((0 until 9).map { it * 9 + k }) // column k
    sudoku.allDifferent((0 until 9).map { (k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3 }) // box k
}
clues.forEachIndexed { cell, ch -> if (ch != '0') sudoku.fix(cell, ch - '1') }

val s = EnergyState(sudoku)
fun board(caption: String) =
    "<div style='font:14px monospace;color:#ccc'>$caption</div>" +
        "<div style='display:grid;grid-template-columns:repeat(9,24px);grid-auto-rows:24px;font:16px monospace;border:2px solid #888;width:max-content'>" +
        (0 until 81).joinToString("") { c ->
            val clue = sudoku.fixed[c] >= 0
            val belief = s.p[sudoku.unit(c, s.assignment[c])]
            val edge =
                (if (c % 9 == 2 || c % 9 == 5) "border-right:2px solid #888;" else "") +
                    (if (c / 9 == 2 || c / 9 == 5) "border-bottom:2px solid #888;" else "")
            "<div style='${edge}text-align:center;line-height:24px;color:${if (clue) "#fff" else "#7fd4ff"};" +
                "opacity:${if (clue) 1.0 else 0.25 + 0.75 * belief};font-weight:${if (clue) 700 else 400}'>${s.assignment[c] + 1}</div>"
        } +
        "</div>"

val descent = MeanFieldDescent(sudoku)
val gibbs = GibbsAnnealing(sudoku, sweeps = 5000, startTemperature = 0.6, endTemperature = 0.15, seed = 0)

// the energy at every drawn frame, plotted above the board
val trace = mutableListOf<Double>()
fun show(caption: String) {
    trace += s.energy
    val chart = Chart(
        title = "energy (broken constraints)",
        xLabel = "frame: descent steps, then every 25 Gibbs sweeps",
        yLabel = "energy",
    ).line("energy", trace.indices.map { it.toDouble() }, trace)
    // the chart as FLines in a fixed-size panel, drawn first so the video frames it from the first step
    lines(*chart.toFLines().toTypedArray(), channel = "energy", w = 460.0, h = 260.0, fitToSize = true)
    display("board", board(caption))
}

frames {
    var step = 0
    while (descent.step(s) > 1e-4f) {
        step++
        show("descent step $step, energy ${s.energy.toInt()}")
        frame()
    }
    GreedyMoves(sudoku).settle(s)
    while (s.energy > 0 && gibbs.step(s)) {
        if (gibbs.sweep % 25 == 0) {
            s.beliefsFromAssignment()
            show("Gibbs sweep ${gibbs.sweep}, energy ${s.energy.toInt()}")
            frame()
        }
    }
    s.beliefsFromAssignment()
    show("solved: energy ${s.energy.toInt()}")
}
```

The schedule matters. With 300 sweeps from temperature 0.5 to 0.02, the annealer solved none of
ten runs on this puzzle; 5,000 sweeps from 0.6 to 0.15 solved all ten, in about 2.5 seconds each.
On generated puzzles, with two chains of 2,000 sweeps each, `DiscreteEnergyTest` solves all 20 with
30 blanks and all 20 with 40, 14 of 20 with 50, and 11 of 20 with 60.

## Sudoku, learned

Now the same 81 × 9 energy with nothing stated. `PseudolikelihoodLearner` fits its costs to solved
grids without ever running a solver. For each cell of an example, holding every other cell at the
example, the energy implies a probability for each digit (a softmax over its local energies). Each
update moves the costs to make the example's digit more likely. Relabelling the digits at random
on every example teaches what the digits do, not which digit is which, and the L1 step keeps
couplings that no example supports at zero.

`sameValueImage` draws, for every pair of variables, how much more it costs them to take the same
value than different ones. Above is the stated energy; below, the one learned from 3,000 grids
over 10 epochs, which takes about 7 seconds. The box then solves the 30-clue puzzle with the
learned energy and counts the stated constraints its board breaks:

```diffstatic_runner {data-lang=kotlin screenshot="assets/discrete_energy/learned.png" wait="1.5" capture="box" open-outputs="true" padding="20" timeout="120" embed=true name="energy-learned" alt="Two 81 by 81 heatmaps, one above the other. Above, the stated energy: three bright 27-cell bands on the diagonal, each patterned with 9-cell row squares and 3-by-3 box blocks, and thin diagonal stripes nine cells apart on a black ground. Below, the learned energy: the same bands, blocks and stripes in the same places, on a near-black ground; under it, a line saying the board solved with the learned energy breaks 0 stated constraints"}
:::static
val stated = DiscreteEnergy(81, 9)
for (k in 0 until 9) {
    stated.allDifferent((0 until 9).map { k * 9 + it })
    stated.allDifferent((0 until 9).map { it * 9 + k })
    stated.allDifferent((0 until 9).map { (k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3 })
}

// solved grids to learn from, by randomized backtracking (digits 0..8)
val rnd = kotlin.random.Random(1)
fun solvedGrid(): IntArray {
    val g = IntArray(81) { -1 }
    fun ok(c: Int, d: Int) =
        (0 until 9).none { m ->
            g[c / 9 * 9 + m] == d || g[m * 9 + c % 9] == d || g[(c / 27 * 3 + m / 3) * 9 + c % 9 / 3 * 3 + m % 3] == d
        }
    fun fill(c: Int): Boolean {
        if (c == 81) return true
        for (d in (0 until 9).shuffled(rnd)) if (ok(c, d)) {
            g[c] = d
            if (fill(c + 1)) return true
            g[c] = -1
        }
        return false
    }
    fill(0)
    return g
}
val grids = List(3000) { solvedGrid() }

val learned = DiscreteEnergy(81, 9)
val learner = PseudolikelihoodLearner(
    learned,
    rate = 0.2f,
    l1 = 1e-4f,
    augment = { g, r ->
        val relabel = (0 until 9).shuffled(r)
        IntArray(81) { relabel[g[it]] }
    },
)
repeat(10) { learner.epoch(grids) }

showHeatmap(stated.sameValueImage(), colormap = "magma", smooth = false, flipY = false, width = 324, channel = "stated")
showHeatmap(
    learned.sameValueImage(),
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 324,
    channel = "learned",
)

// solve the 30-clue puzzle with the learned energy, stopping at its training solutions' energy
val clues = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
clues.forEachIndexed { cell, ch -> if (ch != '0') learned.fix(cell, ch - '1') }
val target = grids.maxOf { learned.energy(it) } + 1e-3
val scale = learned.constraintScale() // the learned constraints' strength sets the temperatures
val s = EnergyState(learned)
MeanFieldDescent(learned).settle(s)
GreedyMoves(learned).settle(s)
for (attempt in 0 until 3) if (s.energy > target) {
    GibbsAnnealing(learned, 5000, 0.6 * scale, 0.15 * scale, seed = attempt.toLong()).settle(s)
    GreedyMoves(learned).settle(s)
}
display(
    "learned solve",
    "constraint scale ${scale.f2}; the learned energy's board breaks ${stated.energy(s.assignment).toInt()} stated constraints",
)
```

Cells are numbered row by row. The bright 9 × 9 squares on the diagonal are cells in the same row;
the patterned 27-cell bands around them are cells in the same three rows, whose bright blocks
share a box; the stripes nine cells apart are cells in the same column. Nobody told the learner
about rows, columns or boxes. The learned image correlates with the stated one at 0.989 over all
cell pairs.

On held-out puzzles, `DiscreteEnergyTest` solves 55 of 80 with this learned energy and 74 of 80
with the stated one. The learned energy matches at 30 blanks (20 of 20 each), and the gap opens
at 50 and 60 blanks (12 and 6 of 20, against 17 and 17). Two causes, measured separately:

- **Too few examples.** Trained on 400 grids, the learned energy is sometimes simply wrong: an
  invalid board scored lower than the true solution in 4 of 20 hard puzzles. From 1,000 grids up,
  that never happened.
- **A rougher landscape.** With enough data, every remaining failure is the search stopping above
  the true solution's energy. The learned energy keeps small couplings between unrelated cells, so
  valid grids no longer all share one energy (their energies spread over about 6 units), and
  annealing has more false valleys to fall into. Setting the temperatures from
  `constraintScale()`, the learned constraints' typical strength, rather than from the largest
  coupling, helps a little; more data helps most.

## On the GPU

A mean-field step is one matrix–vector product, `W p`, so it suits a compute shader with one
invocation per variable. The [`discrete_energy` transcript](agent_transcripts.md#discrete_energy)
runs it for any `DiscreteEnergy` beneath a renderer. It uploads `pairwise` and a `gpuState` buffer
that holds two halves of beliefs, ping-ponged step by step, and steps the shader once per frame
with a `BondedClock`. A check box freezes it after 40 steps and compares the result with 40 steps
of `MeanFieldDescent`. The largest belief difference was 1.0·10⁻⁷, and both decode to the same
board.

## Where to go next

- [Agent transcripts: discrete_energy](agent_transcripts.md#discrete_energy) replays every box on
  this page into a document, plus the GPU descent and its check.
- [Wave Function Collapse](wfc_examples.md) solves another kind of discrete constraint problem, by
  propagation instead of descent.
- [Shapes from constraints: Ceres](ceres.md) is the continuous counterpart: points moved until
  geometric statements hold.
- [Field 4 graphics](afield_four_graphics.md) explains compute shaders and the renderer topology
  the GPU box uses.

## API reference

- Stating and solving the energy: [`DiscreteEnergy`](reference/math.md#discreteenergy-kt-discreteenergy), [`DiscreteEnergy.allDifferent`](reference/math.md#discreteenergy-kt--discreteenergy--alldifferent), [`DiscreteEnergy.pairCost`](reference/math.md#discreteenergy-kt--discreteenergy--paircost), [`EnergyState`](reference/math.md#discreteenergy-kt-energystate), [`MeanFieldDescent`](reference/math.md#discreteenergy-kt-meanfielddescent), [`MeanFieldDescent.settle`](reference/math.md#discreteenergy-kt--meanfielddescent--settle), [`GreedyMoves`](reference/math.md#discreteenergy-kt-greedymoves), [`GreedyMoves.settle`](reference/math.md#discreteenergy-kt--greedymoves--settle), [`GibbsAnnealing`](reference/math.md#discreteenergy-kt-gibbsannealing), [`GibbsAnnealing.step`](reference/math.md#discreteenergy-kt--gibbsannealing--step), [`PseudolikelihoodLearner`](reference/math.md#discreteenergy-kt-pseudolikelihoodlearner).

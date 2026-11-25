/*
Fits the couplings W and biases B of a 9×9 Sudoku energy to 400 solved grids. The model is
told nothing about rows, columns or boxes; the image shows what it learned: for each pair
of cells, how much more it costs them to hold the same digit than different ones.
*/
val model = HopfieldSudoku(3)
val grids = kotlin.random.Random(1).let { r -> List(400) { model.generate(r, 0).second } }
val ms = model.train(grids, epochs = 10, lr = 0.2f, l1 = 1e-4f)
println("trained on ${grids.size} grids in $ms ms, pseudolikelihood loss ${model.pseudoNll(grids)}")
prop<HopfieldSudoku>(sudokuModel) = model

val cost = model.sameDigitCost()
showHeatmap(
    Array(model.cells) { a -> DoubleArray(model.cells) { b -> cost[a * model.cells + b].toDouble() } },
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 486,
    channel = "same-digit cost, cell × cell",
)

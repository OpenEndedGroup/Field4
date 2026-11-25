/*
Sudoku stated by hand: 81 variables (cells) of 9 values (digits), and an allDifferent over each
row, column and box. The clues are fixed. The image shows, for every pair of cells, how much it
costs them to hold the same digit: rows, columns and boxes are the bright structure.
*/
val clues = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
val sudoku = DiscreteEnergy(81, 9)
for (k in 0 until 9) {
    sudoku.allDifferent((0 until 9).map { k * 9 + it }) // row k
    sudoku.allDifferent((0 until 9).map { it * 9 + k }) // column k
    sudoku.allDifferent((0 until 9).map { (k / 3 * 3 + it / 3) * 9 + k % 3 * 3 + it % 3 }) // box k
}
clues.forEachIndexed { cell, ch -> if (ch != '0') sudoku.fix(cell, ch - '1') }
prop<DiscreteEnergy>(sudokuEnergy) = sudoku

// a board drawn from beliefs: each free cell shows its strongest digit, brighter as it sharpens
val board = { s: EnergyState, caption: String ->
    val cells =
        (0 until 81).joinToString("") { c ->
            val d = s.assignment[c]
            val a = s.p[sudoku.unit(c, d)]
            val clue = sudoku.fixed[c] >= 0
            val edge =
                (if (c % 9 == 2 || c % 9 == 5) "border-right:2px solid #888;" else "") +
                    (if (c / 9 == 2 || c / 9 == 5) "border-bottom:2px solid #888;" else "")
            "<div style='${edge}display:flex;align-items:center;justify-content:center;" +
                "color:${if (clue) "#fff" else "#7fd4ff"};opacity:${if (clue) 1.0 else 0.25 + 0.75 * a};" +
                "font-weight:${if (clue) 700 else 400}'>${d + 1}</div>"
        }
    "<div style='font:14px monospace;color:#ccc'>$caption</div>" +
        "<div style='display:grid;grid-template-columns:repeat(9,24px);grid-auto-rows:24px;" +
        "font:16px monospace;border:2px solid #888;width:max-content'>$cells</div>"
}
prop<(EnergyState, String) -> String>(sudokuBoard) = board

showHeatmap(
    sudoku.sameValueImage(),
    colormap = "magma",
    smooth = false,
    flipY = false,
    width = 405,
    channel = "stated: same-digit cost",
)

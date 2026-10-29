import afield.ai.learning.pen.*

/*
The "mixed" pen learned words in two hands, loops (hand 0) and zigzags (hand 1). probe shows
it forty labelled drawings it has never seen and ranks its cells by how well each tracks the
hand. The top row is the pen's own writing coloured by the best of them. Below, pairs of rows
from the same seed with the top 1, 4 and 16 cells held towards loops (green) or zigzags (red).
*/
val pen = PenModel.bundled("mixed")
val hands = (0 until 40).map { mutableListOf<Int>() }
val drawings = (0 until 40).map { PenDrawings.twoHands(5000L + it, hands = hands[it]) }
val ranked = pen.probe(drawings, hands)
print("best cells: " + ranked.take(5).joinToString { (cell, r) -> "$cell (r = ${r.f2})" })

fun held(towards: Int, cells: Int) =
    PenLine(model = pen, seed = 3)
        .also { writer ->
            repeat(700) {
                for ((cell, r) in ranked.take(cells)) (writer.pen as Pen).setCell(
                    cell,
                    if ((r > 0) == (towards == 1)) 3.0 else -3.0,
                )
                writer.grow()
            }
        }
        .line
val own = PenLine(model = pen, seed = 3, recordMemory = true).also { it.grow(700) }
val rows =
    [own.coloredByCell(ranked[0].first, thickness = 2.0, range = null)] +
        [1, 4, 16].flatMap { n ->
            [
                [held(0, n).apply { color = vec(0.5, 1.0, 0.6, 1.0) }],
                [held(1, n).apply { color = vec(1.0, 0.55, 0.6, 1.0) }],
            ]
        }
lines(stacked(rows, gap = 14.0), w = 520.0, h = 520.0, fitToSize = true)

package afield.math.energy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class HopfieldSudokuTest {

    private fun run(boxSize: Int, trainCount: Int, epochs: Int, lr: Float, l1: Float, blanksList: List<Int>, perRow: Int): Double {
        val h = HopfieldSudoku(boxSize)
        val r = Random(1)
        val train = List(trainCount) { h.generate(r, 0).second }
        assertTrue(train.all { h.isSolution(it, it) }, "generator makes valid grids")
        val ms = h.train(train, epochs = epochs, lr = lr, l1 = l1)
        println("HOPFIELD ${h.n}x${h.n} train=$trainCount epochs=$epochs lr=$lr ${ms}ms pnll=${"%.4f".format(h.pseudoNll(train))} threshold=${"%.3f".format(h.threshold)}")
        val test = Random(99)
        var total = 0; var solved = 0
        for (blanks in blanksList) {
            var ok = 0; var steps = 0; var moves = 0; var t = 0L
            repeat(perRow) { k ->
                val (p, _) = h.generate(test, blanks)
                val t0 = System.nanoTime()
                val s = h.solve(p, seed = k.toLong())
                t += System.nanoTime() - t0
                if (h.isSolution(p, s.grid)) ok++
                steps += s.descentSteps; moves += s.moves
            }
            total += perRow; solved += ok
            println("HOPFIELD ${h.n}x${h.n} blanks=$blanks solved=$ok/$perRow meanSteps=${steps / perRow} meanMoves=${moves / perRow} meanMs=${t / perRow / 1_000_000}")
        }
        return solved.toDouble() / total
    }

    @Test
    fun learnedEnergySolvesHeldOut4x4Sudokus() {
        assertTrue(run(2, 200, 10, 0.05f, 1e-5f, listOf(4, 6, 8, 10), 100) > 0.5)
    }

    @Test
    fun learnedEnergySolvesHeldOut9x9Sudokus() {
        val rate = run(3, 1000, 20, 0.2f, 1e-4f, listOf(30, 40, 45, 50, 55, 60), 50)
        println("HOPFIELD 9x9 overall=$rate")
        assertTrue(rate > 0.5, "9x9 held-out solve rate $rate")
    }
}

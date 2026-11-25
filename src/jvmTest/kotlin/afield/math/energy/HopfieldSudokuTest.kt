package afield.math.energy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class HopfieldSudokuTest {

    @Test
    fun learnedEnergySolvesHeldOut4x4Sudokus() {
        val h = HopfieldSudoku(2)
        val train = Random(1).let { r -> List(400) { h.generate(r, 4 + r.nextInt(7)) } }
        assertTrue(train.all { h.isSolution(it.first, it.second) }, "generator makes valid grids")
        val ms = h.train(train, epochs = 10, lr = 0.02)
        val test = Random(99)
        val report = StringBuilder("train ${ms} ms\n")
        var total = 0; var solved = 0
        for (blanks in listOf(4, 6, 8, 10)) {
            var ok = 0; var steps = 0
            repeat(100) {
                val (p, _) = h.generate(test, blanks)
                val s = h.solve(p)
                if (h.isSolution(p, s.grid)) ok++
                steps += s.steps
            }
            total += 100; solved += ok
            report.append("blanks=$blanks solved=$ok/100 meanSteps=${steps / 100}\n")
        }
        println(report)
        assertTrue(solved > total / 4, "solved $solved/$total")
    }
}

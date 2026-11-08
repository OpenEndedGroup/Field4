package afield.simulation.agents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins Particle Lenia (`alife_particle_lenia.kt`): the analytic gradient against finite differences,
 * energy descent, and the article's default rule settling into a bounded rotating creature.
 */
class ParticleLeniaTest {
    @Test fun analyticGradientMatchesFiniteDifferences() {
        val pl = ParticleLenia.random(60, side = 10.0, seed = 3L)
        val h = 1e-5
        var worst = 0.0
        for ((x, y) in listOf(0.3 to -0.7, 2.0 to 1.5, -3.1 to 0.2, 5.0 to 5.0)) {
            val s = pl.sample(x, y)
            val fx = (pl.sample(x + h, y).e - pl.sample(x - h, y).e) / (2 * h)
            val fy = (pl.sample(x, y + h).e - pl.sample(x, y - h).e) / (2 * h)
            worst = maxOf(worst, abs(fx - s.gx), abs(fy - s.gy))
        }
        assertTrue(worst < 1e-5, "worst gradient error $worst")
    }

    @Test fun defaultRuleDescendsAndStaysBounded() {
        val pl = ParticleLenia.random(200, seed = 1L)
        val e0 = pl.energyHistory.first()
        pl.stepTimes(3000)
        println("PL default: e0=$e0 $pl")
        assertTrue(pl.energyHistory.last() < e0 - 0.1, "energy fell: $pl")
        assertTrue(pl.spread() < 15.0, "bounded: $pl")
    }
}

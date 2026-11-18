package afield.simulation.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the numbers behind "The path through the weight cube" in `public/docs/braitenberg_vehicles.md`
 * §4: where trial-and-error learners in the three-number cube end up under each reward.
 */
class WeightCubeLearnerTest {
    private val lights = listOf(Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0))

    private fun learn(r: CubeReward, seed: Long) = WeightCubeLearner(lights, r, seed = seed).train(2000)

    @Test fun seekingLearnsAnInhibitoryUncrossedWiring() {
        for (seed in 1L..3L) {
            val l = learn(CubeReward.SEEK, seed)
            assertTrue(l.wiring.same < -2.0, "seed $seed: ${l.wiring}")
            assertTrue(l.measure(l.wiring, 99_999).light > 0.6, "seed $seed")
            assertTrue(l.measure(l.path[0], 99_999).light < 0.32, "seed $seed")
        }
    }

    @Test fun fleeingLearnsAnExcitatoryUncrossedWiring() {
        for (seed in 1L..3L) {
            val l = learn(CubeReward.FLEE, seed)
            assertTrue(l.wiring.same > 2.0, "seed $seed: ${l.wiring}")
            assertTrue(l.measure(l.wiring, 99_999).light < 0.22, "seed $seed")
        }
    }

    @Test fun freezingOnlyLowersTheDrive() {
        for (seed in 1L..3L) {
            val l = learn(CubeReward.FREEZE, seed)
            assertTrue(l.wiring.drive < -1.3, "seed $seed: ${l.wiring}")
            assertTrue(l.measure(l.wiring, 99_999).speed < 0.18, "seed $seed")
        }
    }

    @Test fun chargingRaisesTheDrive() {
        for (seed in 1L..3L) {
            val l = learn(CubeReward.CHARGE, seed)
            assertTrue(l.wiring.drive > 1.2, "seed $seed: ${l.wiring}")
        }
    }

    @Test fun theRunIsDeterministic() {
        assertEquals(learn(CubeReward.SEEK, 7L).path.takeLast(5), learn(CubeReward.SEEK, 7L).path.takeLast(5))
    }
}

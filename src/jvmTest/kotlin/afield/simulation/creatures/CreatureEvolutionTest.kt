package afield.simulation.creatures

import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatureEvolutionTest {
    /** The water-surface grip from the `evolving_water_bugs` transcript, kept here so the library is tested against a real world. */
    private class Grip : ContactModel {
        override fun foot(index: Int) = object : ContactModel.Foot {
            var planted: Float2? = null
            override var grip = 0f
            override val anchor get() = planted

            override fun force(step: FootStep): Float2 {
                val g = step.genome
                grip = step.contact
                if (step.contact <= 0.02f) {
                    planted = null
                    return step.velocity * -g.freeDrag
                }
                val at = planted ?: step.position.also { planted = it }
                val drag = max(g.freeDrag, g.footDrag * step.contact)
                return (at - step.position) * (g.footStickiness * step.contact) - step.velocity * drag
            }
        }
    }

    private fun config(contact: ContactModel, fitness: ((CreatureEvaluation) -> Float)? = null) = EvolutionConfig(
        populationSize = 8,
        generations = 3,
        eliteCount = 2,
        minPoints = 5,
        maxPoints = 7,
        alwaysUpFootFraction = 0.5f,
        cyclingFootFraction = 0.5f,
        simulation = CreatureSimulationConfig(measuredCycles = 3, contact = contact, fitness = fitness),
        randomSeed = 7,
    )

    @Test
    fun randomGenomesAreValidAndSimulate() {
        val random = Random(3)
        repeat(20) {
            val genome = CreatureEvolution.randomGenome(random)
            assertEquals(emptyList(), genome.validationIssues())
            val e = CreatureEvolution.simulate(genome, CreatureSimulationConfig(measuredCycles = 2, contact = Grip()))
            assertTrue(e.fitness.isFinite())
        }
    }

    @Test
    fun withoutGripNothingGetsFar() {
        // In pure drag there is nothing to push against, so a body can only wobble about its centre.
        val genome = CreatureEvolution.randomGenome(Random(11))
        val dragged = CreatureEvolution.simulate(genome, CreatureSimulationConfig(measuredCycles = 3))
        assertFalse(dragged.exploded)
        assertTrue(dragged.netCentroidMotion < 0.5f, "drag-only motion ${dragged.netCentroidMotion}")
    }

    @Test
    fun evolutionIsDeterministicAndReportsEveryGeneration() {
        val updates = mutableListOf<EvolutionUpdate>()
        val a = CreatureEvolution.evolve(config(Grip())) { updates += it }
        val b = CreatureEvolution.evolve(config(Grip()))
        assertEquals(3, updates.size)
        assertEquals(a.best.fitness, b.best.fitness)
        assertTrue(a.best.frames.isNotEmpty())
        // The champion never gets worse.
        updates.zipWithNext().forEach { (p, n) -> assertTrue(n.champion.fitness >= p.champion.fitness) }
    }

    @Test
    fun customFitnessReplacesTheObjective() {
        val result = CreatureEvolution.evolve(config(Grip()) { e -> -e.displacement.x })
        val e = result.best
        assertEquals(-e.displacement.x, e.fitness)
    }

    @Test
    fun controllerStopsEarly() {
        val controller = EvolutionController()
        var seen = 0
        CreatureEvolution.evolve(config(Grip()).copy(generations = 50), controller) {
            seen++
            controller.requestStop()
        }
        assertEquals(1, seen)
    }

    @Test
    fun halvesOfTheMeasuredRunAddUpToTheDisplacement() {
        val e = CreatureEvolution.simulate(
            CreatureEvolution.randomGenome(Random(4), minPoints = 6, maxPoints = 6),
            CreatureSimulationConfig(measuredCycles = 6, contact = Grip()),
        )
        val sum = e.firstHalfDisplacement + e.secondHalfDisplacement
        assertEquals(e.displacement.x, sum.x, 1e-4f)
        assertEquals(e.displacement.y, sum.y, 1e-4f)
        val d = Float2(1f, 0f)
        assertEquals(minOf(e.firstHalfDisplacement.x, e.secondHalfDisplacement.x), e.sustainedTravel(d), 1e-6f)
    }
}

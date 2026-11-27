package afield.simulation.creatures

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreatureMarathonTest {
    private val tiny = MarathonPlan(
        exploreRuns = 3,
        explorePopulation = 6,
        exploreGenerations = 2,
        champions = 2,
        refinementsPerChampion = 2,
        refinePopulation = 6,
        refineGenerations = 2,
        measuredCycles = 2,
        longMeasuredCycles = 4,
    )

    @Test
    fun marathonSavesResumesAndRefinesItsChampions() {
        val dir = Files.createTempDirectory("creature-marathon").toFile()
        try {
            val lines = mutableListOf<String>()
            val state = CreatureMarathon.run(dir, tiny, threads = 2, progressEvery = 1) { lines += it }
            assertEquals(3, state.explored.size)
            assertEquals(4, state.refined.size)
            assertEquals(state.champions().map { it.id }.toSet(), state.refined.map { it.parent }.toSet())
            for (child in state.refined) {
                // Elites carry forward and the seed is in the first generation, so a refinement never loses ground.
                assertTrue(child.fitness >= state[child.parent!!]!!.fitness - 1e-4f, "${child.id} fell below its parent")
            }

            val loaded = CreatureMarathon.load(dir.path)
            assertEquals(state.creatures, loaded.creatures)
            val replay = loaded.best(1).single().simulate(loaded.plan, cycles = 2)
            assertTrue(replay.frames.isNotEmpty())

            // A second run of the same folder has nothing to do; a bigger plan only runs what is new.
            val again = mutableListOf<String>()
            CreatureMarathon.run(dir, null, threads = 2) { again += it }
            assertTrue(again.none { it.startsWith("done ") }, again.joinToString("\n"))
            val more = CreatureMarathon.run(dir, tiny.copy(refinementsPerChampion = 3), threads = 2) {}
            assertEquals(6, more.refined.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun seededEvolutionStartsFromTheSeed() {
        val seed = CreatureEvolution.randomGenome(Random(5), minPoints = 5, maxPoints = 7)
        val config = EvolutionConfig(
            populationSize = 6,
            generations = 1,
            immigrantsPerGeneration = 0,
            immigrantFraction = 0f,
            simulation = CreatureWorlds.water(measuredCycles = 2),
            seedGenomes = [seed],
        )
        val parent = CreatureEvolution.simulate(seed, config.simulation)
        val best = CreatureEvolution.evolve(config).best
        assertTrue(best.fitness >= parent.fitness - 1e-4f)
    }
}

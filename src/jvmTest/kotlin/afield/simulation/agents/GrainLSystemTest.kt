package afield.simulation.agents

import afield.study.search.PopulationSearch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrainLSystemTest {
    private val t = GrainTurtle(basePitch = 60.0, pitchStep = 2.0, timeStep = 0.1, grainDuration = 0.1, branchAmp = 0.5, branchDuration = 0.5)

    @Test fun aLineOfGrainsStepsInTimeAndPitch() {
        val g = decodeGrainString("G+G+G", t)
        assertEquals(listOf(60.0, 62.0, 64.0), g.map { it.pitch })
        assertEquals(listOf(0.0, 0.1, 0.2), g.map { (it.onset * 1000).toInt() / 1000.0 })
    }

    @Test fun aBranchRestoresTimeAndPitchSoItIsAVoice() {
        val g = decodeGrainString("G[+G+G]G", t)
        // trunk G at 0, branch starts at t=0.1 (two half-length grains), trunk resumes at t=0.1 and pitch 60
        assertEquals(4, g.size)
        val trunk = g.filter { it.depth == 0 }
        assertEquals(listOf(0.0, 0.1), trunk.map { (it.onset * 1000).toInt() / 1000.0 })
        assertEquals(listOf(60.0, 60.0), trunk.map { it.pitch })
        val branch = g.filter { it.depth == 1 }
        assertEquals(listOf(62.0, 64.0), branch.map { it.pitch })
        assertEquals(0.5, branch[0].amp)
        assertTrue(abs(branch[1].onset - 0.15) < 1e-9, "branch steps by the halved time step")
        assertTrue(abs(branch[0].duration - 0.05) < 1e-9)
    }

    @Test fun restsAndUnknownSymbolsAndUnmatchedPops() {
        val g = decodeGrainString("]XfG", t)
        assertEquals(1, g.size)
        assertTrue(abs(g[0].onset - 0.1) < 1e-9)
    }

    @Test fun decodingADerivation() {
        val s = lSystem("G", 'G' to "G[+G]G")
        assertEquals(decodeGrainString(s.derive(2), t), decodeGrains(s, 2, t))
        assertEquals(9, decodeGrains(s, 2, t).size)
    }

    @Test fun trapezoidCorners() {
        val z = Trapezoid(0.0, 1.0, 2.0, 4.0)
        assertEquals(0.0, z(-1.0)); assertEquals(0.5, z(0.5)); assertEquals(1.0, z(1.5))
        assertEquals(0.5, z(3.0)); assertEquals(0.0, z(5.0)); assertEquals(0.0, z(Double.NaN))
        assertEquals(1.0, Trapezoid(1.0, 1.0, 1.0, 1.0)(1.0))
        assertEquals(Trapezoid(0.0, 1.0, 2.0, 3.0), Trapezoid.sorted(3.0, 1.0, 0.0, 2.0))
    }

    @Test fun statisticsOfAKnownSet() {
        val st = GrainSetStats(decodeGrainString("G+G+G", t))
        assertEquals(3, st.count); assertEquals(62.0, st.medianPitch)
        assertTrue(abs(st.pitchSpread - kotlin.math.sqrt(8.0 / 3.0)) < 1e-9)
        assertTrue(abs(st.duration - 0.3) < 1e-9)
        assertTrue(abs(st.density - 10.0) < 1e-9)
        assertTrue(abs(st.polyphony - 1.0) < 1e-9)
        assertEquals(0.0, grainSetFitness(GrainSetStats(emptyList()), defaultGrainTerms()))
    }

    @Test fun fitnessIsAWeightedMeanOfTrapezoids() {
        val st = GrainSetStats(decodeGrainString("G+G+G", t))
        val terms = listOf(
            GrainFitnessTerm(GrainStat.COUNT, Trapezoid(0.0, 2.0, 4.0, 5.0), weight = 3.0),   // 1
            GrainFitnessTerm(GrainStat.MEDIAN_PITCH, Trapezoid(70.0, 80.0, 90.0, 100.0), 1.0) // 0
        )
        assertEquals(0.75, grainSetFitness(st, terms))
    }

    @Test fun renderIsSampleAccurateAndNotSilent() {
        val a = renderGrains(listOf(Grain(0.5, 0.1, 69.0, 1.0, 0)), sampleRate = 1000, tail = 0.0, minSeconds = 0.0)
        assertEquals(600, a.left.size)
        assertTrue(a.left.take(500).all { it == 0f }, "nothing before the onset")
        assertTrue(a.left.drop(500).any { it != 0f })
        assertTrue(a.wav().size > 44)
    }

    @Test fun seededEvolutionImprovesAndBeatsItsFirstDraw() {
        val run = PopulationSearch.evolve(grainLSystemGenome(), grainSetObjective(), budget = 240, seed = 3L, crossoverRate = 0.3, immigrantRate = 0.1)
        println(run.summary()); println(run.improvements.joinToString("\n"))
        assertTrue(run.bestFitness > run.initialFitness, "best ${run.bestFitness} vs first ${run.initialFitness}")
        assertTrue(run.improvements.size >= 2)
        assertTrue(run.recombined > 0)
        assertEquals(run.trace.toList(), run.trace.toList().sorted(), "best-so-far is monotone")
    }

    @Test fun timeScaleStretchesTimeAndRenderHasAFloor() {
        val slow = decodeGrainString("GG", t.copy(timeScale = 8.0))
        assertTrue(abs(slow[1].onset - 0.8) < 1e-9 && abs(slow[0].duration - 0.8) < 1e-9)
        assertTrue(abs(GrainTurtle().timeStep - 0.72) < 1e-12)
        assertTrue(renderGrains(listOf(Grain(0.0, 0.1, 60.0, 1.0, 0))).seconds >= 2.0)
    }

    @Test fun aOnePitchDroneNoLongerScoresTop() {
        val drone = GrainSetStats(decodeGrains(lSystem("G", 'G' to "GGG[---G]G"), 3))
        assertTrue(drone.modeShare > 0.4, "drone share ${drone.modeShare}")
        assertTrue(grainSetFitness(drone, defaultGrainTerms()) < 0.9)
    }

    @Test fun breederKeepsParentsAndReplays() {
        val a = GrainBreeder(seed = 5L); val b = GrainBreeder(seed = 5L)
        val p = a.population[2]
        a.breed(listOf(2, 4)); b.breed(listOf(2, 4))
        assertEquals(p.productions, a.population[0].productions)
        assertEquals(a.population.map { it.productions }, b.population.map { it.productions })
        assertEquals(1, a.generation); assertEquals(6, a.scores().size)
    }
}

package afield.study.search

import afield.media.image.savePng
import afield.simulation.agents.LSystem
import afield.simulation.agents.LSystemGenome
import afield.simulation.agents.LeniaGenome
import afield.simulation.agents.LifeRuleGenome
import afield.simulation.agents.VehicleBrainGenome
import afield.simulation.agents.VehicleBrainTask
import afield.simulation.agents.VehicleSearchDriver
import afield.simulation.agents.caEdgeOfChaosObjective
import afield.simulation.agents.compareVehicleSearch
import afield.simulation.agents.lSystemDimensionObjective
import afield.simulation.agents.leniaSolitonObjective
import afield.simulation.agents.randomSoup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [compareSearch], the generic equal-budget comparison: every arm spends exactly the budget, the
 * result is a pure function of its arguments whether or not seeds run in parallel, reading a long run
 * at a shorter budget equals running the shorter budget, and [compareVehicleSearch] — now a thin caller
 * — gives exactly the runs the vehicle-specific code made with direct [PopulationSearch] calls.
 *
 * With `-Dafield.searchComparisonSweep=true` it also runs the `alife_examples.md` comparison on three
 * genomes (Life-like rules, Lenia, L-systems) at 1000 evaluations x 12 seeds and writes the tables,
 * read at 100/300/1000, to `build/search-comparison-sweep.txt`. That takes minutes.
 */
class SearchComparisonTest {
    /** A cheap objective: maximise the count of 'F' in a short L-system, nothing to simulate. */
    private val lOps = LSystemGenome(maxLength = 8)
    private val lCheap = objectiveOf<LSystem> { s -> s.productions.sumOf { p -> p.replacement.count { it == 'F' } }.toDouble() }

    @Test fun everyArmSpendsExactlyTheBudget() {
        val c = compareSearch(lOps, lCheap, budget = 50, seeds = listOf(1L, 2L, 3L), label = "F count")
        assertEquals(listOf("random", "hillclimb", "evolve", "immigrants"), c.arms)
        assertEquals(12, c.cells.size)
        for (cell in c.cells) assertEquals(50, cell.run.trace.size, "${cell.arm} seed ${cell.seed}")
        assertTrue(c.table().startsWith("F count, 50 evals x 3 seeds"))
    }

    @Test fun parallelAndSequentialAgree() {
        val a = compareSearch(lOps, lCheap, budget = 40, seeds = listOf(5L, 6L, 7L))
        val b = compareSearch(lOps, lCheap, budget = 40, seeds = listOf(5L, 6L, 7L), parallel = false)
        for (i in a.cells.indices) assertContentEquals(a.cells[i].run.trace, b.cells[i].run.trace)
        assertEquals(a.table(), b.table())
        assertEquals(a.perSeed(), b.perSeed())
    }

    @Test fun readingAtAShorterBudgetEqualsRunningIt() {
        val long = compareSearch(lOps, lCheap, budget = 120, seeds = listOf(1L, 2L))
        val short = compareSearch(lOps, lCheap, budget = 45, seeds = listOf(1L, 2L))
        for (a in long.arms) assertContentEquals(short.best(a), long.bestAt(a, 45), a)
    }

    @Test fun vehicleResultsAreUnchanged() {
        val task = VehicleBrainTask.forage().let { VehicleBrainTask(it.name, it.lights, it.score, count = 4, steps = 300, scored = 100) }
        val hidden = 2
        val seeds = listOf(1L, 2L, 3L)
        val c = compareVehicleSearch(task, hidden, budget = 60, seeds = seeds, immigrantRate = 0.25)
        // What the vehicle-specific compareVehicleSearch called before it was lifted, verbatim.
        val ops = VehicleBrainGenome(hidden)
        val objective = task.objective(hidden)
        for (s in seeds) {
            val expected = mapOf(
                VehicleSearchDriver.RANDOM to PopulationSearch.random(ops, objective, 60, seed = s),
                VehicleSearchDriver.HILL_CLIMB to PopulationSearch.hillClimb(ops, objective, 60, seed = s, patience = 30),
                VehicleSearchDriver.EVOLVE to PopulationSearch.evolve(ops, objective, 60, seed = s, mu = 6, lambda = 12, crossoverRate = 0.3, immigrantRate = 0.25),
            )
            for ((d, run) in expected) {
                val got = c.of(d).single { it.seed == s }.run
                assertContentEquals(run.trace, got.trace, "$d seed $s")
                assertContentEquals(run.best!!.genome, got.best!!.genome, "$d seed $s")
            }
        }
        assertTrue(c.table().startsWith("forage, 2 hidden (${ops.size} genes), 60 evals x 3 seeds\n  random"))
        assertEquals(3, c.table().lines().count { "beats random" in it } + 1)
        val img = c.plot()
        assertEquals(300, img.width)
    }

    @Test fun sweep() {
        System.getProperty("afield.searchComparisonSweep") ?: return
        val budget = 1000
        val seeds = (1L..12L).toList()
        val out = StringBuilder()
        fun report(c: SearchComparison<*>, png: String) {
            out.append(c.table()).append(c.budgetTable(listOf(100, 300, 1000))).append(c.perSeed()).append('\n')
            File("build/search-comparison-sweep.txt").writeText(out.toString())
            c.plot(360, 260).savePng("build/$png")
        }
        val soups = listOf(randomSoup(48, 48, density = 0.35, seed = 101L), randomSoup(48, 48, density = 0.12, seed = 202L))
        report(compareSearch(LifeRuleGenome(radius = 2), caEdgeOfChaosObjective(soups, warmup = 20, scored = 30), budget, seeds, label = "life rule, edge of chaos"), "search-comparison-life.png")
        report(compareSearch(LSystemGenome(), lSystemDimensionObjective(), budget, seeds, label = "l-system, dimension 1.7"), "search-comparison-lsystem.png")
        report(compareSearch(LeniaGenome(radius = 6, dt = 0.1, shells = 3), leniaSolitonObjective(width = 48, height = 48, steps = 90, initialSeed = 5L, driftScale = 8.0), budget, seeds, label = "lenia, soliton"), "search-comparison-lenia.png")
    }
}

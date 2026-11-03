package afield.simulation.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `compareVehicleSearch` (braitenberg_vehicles.md §10): every driver spends exactly the budget,
 * the comparison is a pure function of its arguments, and the brain genome decodes as laid out.
 *
 * With `-Dafield.vehicleBrainSweep=true` (or `=immigrants`, µ=12 λ=24 with a quarter of the children
 * fresh draws; `=immigrants-mid` for 2 and 8 hidden; `=budgets` for orbit at 100/300/1000) it also runs the page's sweep and writes the tables to `build/vehicle-brain-sweep-<mode>.txt`; that is how the page's numbers were measured, and it takes minutes.
 */
class VehicleBrainSearchTest {
    private val small = VehicleBrainTask.forage().let {
        VehicleBrainTask(it.name, it.lights, it.score, count = 4, steps = 300, scored = 100)
    }

    @Test fun everyDriverSpendsExactlyTheBudget() {
        val c = compareVehicleSearch(small, hidden = 2, budget = 60, seeds = listOf(1L, 2L, 3L))
        assertEquals(9, c.cells.size)
        for (cell in c.cells) {
            assertEquals(60, cell.run.evaluations, "${cell.driver} seed ${cell.seed}")
            assertEquals(60, cell.run.trace.size, "${cell.driver} seed ${cell.seed}")
        }
    }

    @Test fun theComparisonIsDeterministic() {
        val a = compareVehicleSearch(small, hidden = 1, budget = 40, seeds = listOf(5L, 6L))
        val b = compareVehicleSearch(small, hidden = 1, budget = 40, seeds = listOf(5L, 6L))
        for (i in a.cells.indices) assertContentEquals(a.cells[i].run.trace, b.cells[i].run.trace)
        assertEquals(a.table(), b.table())
    }

    @Test fun genomeSizesAndTheClassicLover() {
        assertEquals(8, VehicleBrainGenome(0).size)
        assertEquals(112, VehicleBrainGenome(8).size)
        val brain = VehicleBrain.decode(VehicleBrain.classic(VehicleWorld.LOVER), 0)
        assertEquals(0.6, brain.sensorAngle, 1e-9)
        assertContentEquals(VehicleWorld.LOVER.toList(), brain.direct.toList())
        assertTrue(small.objective(0).evaluate(VehicleBrain.classic(VehicleWorld.LOVER)).fitness.isFinite())
    }

    @Test fun fullSweep() {
        val mode = System.getProperty("afield.vehicleBrainSweep") ?: return
        val immigrants = mode.startsWith("immigrants")
        val out = StringBuilder()
        if (mode == "budgets") {
            // Orbit, 4 hidden: does the ranking change with the budget?
            for (b in listOf(100, 300, 1000)) {
                for (wide in listOf(false, true)) {
                    val c = if (wide) {
                        compareVehicleSearch(VehicleBrainTask.orbit(), 4, budget = b, seeds = (1L..12L).toList(), mu = 12, lambda = 24, immigrantRate = 0.25)
                    } else {
                        compareVehicleSearch(VehicleBrainTask.orbit(), 4, budget = b, seeds = (1L..12L).toList())
                    }
                    out.append(if (wide) "[mu 12, lambda 24, immigrants 0.25] " else "[mu 6, lambda 12] ").append(c.table()).append('\n')
                    File("build/vehicle-brain-sweep-$mode.txt").writeText(out.toString())
                }
            }
            return
        }
        for (task in listOf(VehicleBrainTask.forage(), VehicleBrainTask.orbit(), VehicleBrainTask.track())) {
            for (h in if (mode == "immigrants-mid") listOf(2, 8) else if (immigrants) listOf(0, 4, 16) else listOf(0, 2, 4, 8, 16)) {
                val c = if (immigrants) {
                    compareVehicleSearch(task, h, budget = 300, seeds = (1L..12L).toList(), mu = 12, lambda = 24, immigrantRate = 0.25)
                } else {
                    compareVehicleSearch(task, h, budget = 300, seeds = (1L..12L).toList())
                }
                out.append(c.table()).append(c.perSeed()).append('\n')
                File("build/vehicle-brain-sweep-$mode.txt").writeText(out.toString())
            }
        }
    }
}

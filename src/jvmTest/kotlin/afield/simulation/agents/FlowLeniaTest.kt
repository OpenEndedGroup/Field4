package afield.simulation.agents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins Flow Lenia (`alife_flow_lenia.kt`): total mass conserved to rounding over hundreds of steps
 * under both mixing rules, per-species mass conserved under mass-weighted mixing, tracers that stay
 * fractions, and a species alone in a shared-kernel world behaving exactly as in its own world.
 */
class FlowLeniaTest {
    private fun threeSpecies(mixing: FlowLeniaMixing) = flowLeniaWorld(
        64, 64,
        listOf(FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()),
        positions = listOf(20.0 to 20.0, 44.0 to 24.0, 32.0 to 46.0),
        seedRadius = 10.0,
        mixing = mixing,
    )

    @Test fun totalMassIsConservedUnderBothMixingRules() {
        for (mixing in FlowLeniaMixing.entries) {
            val w = threeSpecies(mixing)
            val m0 = w.totalMass()
            var worst = 0.0
            repeat(300) {
                w.step()
                worst = maxOf(worst, abs(w.totalMass() - m0) / m0)
            }
            println("FlowLenia $mixing: m0=$m0 worst relative drift $worst; $w")
            assertTrue(m0 > 100.0, "seeded something: $m0")
            assertTrue(worst < 1e-10, "$mixing: relative mass drift $worst")
            assertTrue(w.matter.all { it >= 0.0 }, "no negative matter")
        }
    }

    @Test fun meanMixingConservesEachSpeciesAndKeepsTracersFractions() {
        val w = threeSpecies(FlowLeniaMixing.MEAN)
        val s0 = w.speciesMasses()
        w.steps(300)
        val s1 = w.speciesMasses()
        for (i in s0.indices) assertTrue(abs(s1[i] - s0[i]) / s0[i] < 1e-10, "species $i: ${s0[i]} -> ${s1[i]}")
        val n = w.width * w.height
        for (c in 0 until n) if (w.matter[c] > 1e-9) {
            val sum = (0 until w.speciesCount).sumOf { w.species[it * n + c] }
            assertTrue(abs(sum - 1.0) < 1e-9, "tracer sum $sum at $c")
        }
    }

    @Test fun matterMovesAndClassicLeniaDoesNotConserve() {
        val w = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.wanderers()))
        val before = w.matter.copyOf()
        w.steps(100)
        val moved = before.indices.sumOf { abs(before[it] - w.matter[it]) }
        assertTrue(moved > 10.0, "the field changed: $moved")
        // Control: the clamped growth update of ordinary Lenia changes the total freely.
        val orbium = LeniaPresets.orbium()
        val a = orbium.seed(64, 64)
        val m0 = a.data.sum()
        val m1 = a.leniaTimes(orbium.rule, 20).data.sum()
        assertTrue(abs(m1 - m0) / m0 > 1e-3, "classic Lenia's mass moved: $m0 -> $m1")
    }

    @Test fun aSpeciesAloneIgnoresOtherSpeciesKernels() {
        val alone = flowLeniaWorld(48, 48, listOf(FlowLeniaPresets.rings()))
        val shared = FlowLeniaWorld(48, 48, FlowLeniaRule(FlowLeniaPresets.cell().kernels + FlowLeniaPresets.rings().kernels), 1)
        shared.seed(0, DoubleArray(4) + FlowLeniaPresets.rings().h, 24.0, 24.0)
        alone.steps(40); shared.steps(40)
        val worst = alone.matter.indices.maxOf { abs(alone.matter[it] - shared.matter[it]) }
        assertEquals(0.0, worst, 1e-9, "same rule with zero-weighted extra kernels")
    }
}

package afield.simulation.agents

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the bonded-organism layer (`alife_bonded.kt`): a stretched bond relaxes to its rest length,
 * a bond past its strain limit breaks at both ends, internal forces conserve momentum, energy
 * diffusion conserves energy, and a genome worm swims where its two controls (no travelling wave,
 * isotropic drag) do not.
 */
class BondedOrganismTest {
    private fun pair(len: Float, limit: Float = 10f): BondedWorld {
        val w = BondedWorld(2)
        w.addCell(0f, 0f); w.addCell(len, 0f)
        w.bond(0, 1, 1f, strainLimit = limit)
        return w
    }

    private fun dist(w: BondedWorld) = hypot((w.x[1] - w.x[0]).toDouble(), (w.y[1] - w.y[0]).toDouble())

    @Test fun stretchedBondRelaxesToRest() {
        val w = pair(1.5f)
        w.run(2000)
        println("bond relaxed to ${dist(w)}")
        assertEquals(1.0, dist(w), 1e-3)
        assertEquals(1, w.bondCount())
    }

    @Test fun bondPastItsLimitBreaksAtBothEnds() {
        val w = pair(1.5f, limit = 0.3f)
        w.step()
        assertEquals(0, w.bondCount())
        assertTrue((0 until 8).all { w.partner[it] == -1 }, "both slots cleared")
        val kept = pair(1.2f, limit = 0.3f)
        kept.step()
        assertEquals(1, kept.bondCount())
    }

    @Test fun internalForcesConserveMomentumWithoutDrag() {
        val w = BondedWorld(64)
        w.params.dragNormal = 0f; w.params.dragTangent = 0f
        w.grow("SMMMMMMS", 0f, 0f)
        w.vx[0] = 1f // a kick
        val (p0x, p0y) = w.momentum()
        w.run(500)
        val (px, py) = w.momentum()
        println("momentum ($p0x, $p0y) -> ($px, $py)")
        assertEquals(p0x, px, 1e-3); assertEquals(p0y, py, 1e-3)
    }

    @Test fun energyDiffusionConservesEnergyAndEvensOut() {
        val w = BondedWorld(64)
        val o = w.grow("SSSSSSSS", 0f, 0f, energy = 0f)
        w.energy[o.first] = 16f
        val e0 = w.totalEnergy()
        w.run(3000)
        val spread = o.cells.maxOf { w.energy[it] } - o.cells.minOf { w.energy[it] }
        println("energy $e0 -> ${w.totalEnergy()}, spread $spread")
        assertEquals(e0, w.totalEnergy(), 1e-3)
        assertTrue(spread < 0.05f, "spread $spread")
    }

    /** Centroid displacement of a 12-segment worm over 60 time units. */
    private fun swim(waveStep: Float, dragTangent: Float = 0.3f, steps: Int = 3000): Pair<Double, Double> {
        val w = BondedWorld(64)
        w.params.dragTangent = dragTangent
        val o = w.grow("SMMMMMMMMMMS", 0f, 0f, waveStep = waveStep)
        val (x0, y0) = w.centroid(o)
        w.run(steps)
        val (x1, y1) = w.centroid(o)
        val n = o.count / 2
        assertEquals(n + 3 * (n - 1), w.bondCount(), "no bond broke")
        return (x1 - x0) to (y1 - y0)
    }

    private fun Pair<Double, Double>.len() = hypot(first, second)

    @Test fun travellingWaveSwimsAndItsControlsDoNot() {
        val wave = swim(0.8f)
        val reversed = swim(-0.8f)
        val standing = swim(0f)
        val isotropic = swim(0.8f, dragTangent = 3f)
        println("worm displacement over 60 time units: wave $wave (${wave.len()}), reversed wave $reversed, " +
            "standing wave ${standing.len()}, isotropic drag ${isotropic.len()}")
        assertTrue(wave.len() > 5.0, "travelling wave moved ${wave.len()}")
        assertTrue(standing.len() < wave.len() / 5, "standing wave moved ${standing.len()}")
        assertTrue(isotropic.len() < wave.len() / 100, "isotropic drag moved ${isotropic.len()}")
        assertTrue(wave.first * reversed.first + wave.second * reversed.second < 0.0, "reversing the wave reverses the swim")
    }

    @Test fun runningOutOfEnergyStopsTheWorm() {
        val w = BondedWorld(64)
        w.params.muscleCost = 0.2f
        val o = w.grow("SMMMMMMMMMMS", 0f, 0f, energy = 1f)
        w.run(6000)
        val (xa, ya) = w.centroid(o)
        w.run(1000)
        val (xb, yb) = w.centroid(o)
        println("energy left ${w.totalEnergy()}, late drift ${hypot(xb - xa, yb - ya)}")
        assertTrue(w.totalEnergy() < 0.01 * o.count, "spent: ${w.totalEnergy()}")
        assertTrue(hypot(xb - xa, yb - ya) < 0.05)
    }

    @Test fun shaderBuffersMirrorTheWorld() {
        val w = BondedWorld(8)
        w.grow("SMS", 0f, 0f)
        val c = w.cellFloats(); val b = w.bondFloats()
        assertEquals(8 * 8 * 2, c.size)
        assertEquals(w.x[3], c[3 * 8]); assertEquals(w.x[3], c[(8 + 3) * 8])
        assertEquals(w.partner[2 * 4 + 1].toFloat(), b[2 * 24 + 1])
        assertEquals(sqrt(2f), b.toList().chunked(24)[0].subList(4, 8).max())
    }
}

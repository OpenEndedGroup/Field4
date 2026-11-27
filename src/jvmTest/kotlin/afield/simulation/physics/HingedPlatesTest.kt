package afield.simulation.physics

import afield.examples.chain.KirigamiPopUp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HingedPlatesTest {
    private fun pair(): HingedPlates {
        val plates = HingedPlates([doubleArrayOf(0.5, 0.5, 0.0), doubleArrayOf(1.5, 0.5, 0.0)])
        plates.hinge(0, 1, at = doubleArrayOf(1.0, 0.5, 0.0), along = doubleArrayOf(0.0, 1.0, 0.0), stiffness = HingedPlates.Stiffness(100.0, 2.0, 3.0, 5.0))
        return plates
    }

    /** Turning one plate about each hinge axis stores half the matching stiffness times the angle squared. */
    @Test
    fun energyIsHalfStiffnessAngleSquared() {
        for ((axis, k) in listOf(doubleArrayOf(1.0, 0.0, 0.0) to 2.0, doubleArrayOf(0.0, 1.0, 0.0) to 3.0, doubleArrayOf(0.0, 0.0, 1.0) to 5.0)) {
            val plates = pair()
            // Turn plate 1 about the hinge point, so the anchors stay together.
            val r = HingedPlates.exp(DoubleArray(3) { axis[it] * 0.01 })
            for (i in 0 until 9) plates.rotation[9 + i] = r[i]
            val arm = doubleArrayOf(0.5, 0.0, 0.0)
            val turned = DoubleArray(3) { r[3 * it] * arm[0] + r[3 * it + 1] * arm[1] + r[3 * it + 2] * arm[2] }
            for (i in 0 until 3) plates.position[3 + i] = doubleArrayOf(1.0, 0.5, 0.0)[i] + turned[i]
            assertEquals(0.5 * k * 1e-4, plates.energy(), 1e-9)
        }
    }

    /** A free plate relaxes back to rest, and exp and log invert each other. */
    @Test
    fun relaxReturnsToRest() {
        val plates = pair()
        plates.fixed[0] = true
        plates.position[5] = 0.05
        val r = HingedPlates.exp(doubleArrayOf(0.02, -0.03, 0.01))
        for (i in 0 until 9) plates.rotation[9 + i] = r[i]
        val relaxed = plates.relax()
        assertTrue(relaxed.energy < 1e-18, "energy ${relaxed.energy}")
        assertTrue(abs(plates.position[3] - 1.5) < 1e-8)
        val w = HingedPlates.log(HingedPlates.exp(doubleArrayOf(0.3, -0.2, 0.1)))
        assertEquals(0.3, w[0], 1e-12)
        assertEquals(-0.2, w[1], 1e-12)
        assertEquals(0.1, w[2], 1e-12)
    }

    /** The gradient on a clamped plate is the reaction: pulling a stretched pair apart takes stretch times the gap. */
    @Test
    fun gradientIsTheReaction() {
        val plates = pair()
        plates.position[3] += 0.001
        assertEquals(-0.1, plates.gradient()[0], 1e-6)
        assertEquals(0.1, plates.gradient()[6], 1e-6)
    }

    /** A small sheet stays flat at a small strain and tilts at a large one. */
    @Test
    fun smallSheetBuckles() {
        val sheet = KirigamiPopUp.sheet(n = 5, thickness = 0.06)
        val states = sheet.sweep([0.0, 0.001, 0.01, 0.02, 0.03])
        assertTrue(states[1].meanTilt < 0.1, "tilt ${states[1].meanTilt}")
        assertTrue(states.last().meanTilt > 3.0, "tilt ${states.last().meanTilt}")
        assertTrue(KirigamiPopUp.threshold(states).isFinite())
        assertEquals(sheet.mesh().parts.size, sheet.mesh().mesh.triangleCount)
    }
}

package afield.graphics.scene.fabricated

import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The albedo remaps against an independent half-space Monte Carlo reference (not the standard table). */
class SubsurfaceRemapTest {
    private val reference = HalfSpaceAlbedo(walks = 200_000, maxScatters = 3000, seed = 7L)
    private val albedos = (1..18).map { it * 0.05 }

    @Test fun monteCarloMatchesTheExactSingleScatteringTerm() {
        // One isotropic collision, then escape: (1/2)(1 - μ0 ln(1 + 1/μ0)) for incidence μ0,
        // averaged over cosine-weighted μ0 that is 1/2 - ∫ μ² ln(1 + 1/μ) dμ.
        val exact = 0.5 - (2 * ln(2.0) / 3 - 5.0 / 18 + 1.0 / 9)
        assertEquals(0.0, reference.escapes[0])
        assertEquals(exact, reference.escapes[1], 0.004)
        assertEquals(1.0, reference.reflectance(1.0), 1e-9)
        assertEquals(0.0, reference.reflectance(0.0))
    }

    @Test fun vanDeHulstReproducesTheRequestedColour() {
        for (a in albedos) {
            val got = reference.reflectance(AlbedoRemap.VAN_DE_HULST.singleScatterAlbedo(a))
            assertEquals(a, got, 0.006, "VAN_DE_HULST at A=$a")
        }
    }

    @Test fun expCubicAgreesLowAndUndershootsHigh() {
        for (a in albedos.filter { it <= 0.6 }) {
            assertEquals(a, reference.reflectance(AlbedoRemap.EXP_CUBIC.singleScatterAlbedo(a)), 0.01, "EXP_CUBIC at A=$a")
        }
        val high = reference.reflectance(AlbedoRemap.EXP_CUBIC.singleScatterAlbedo(0.9))
        assertTrue(0.9 - high > 0.02, "EXP_CUBIC at A=0.9 gave $high")
    }

    @Test fun noRemapIsMuchDarkerThanAsked() {
        for (a in albedos) assertTrue(reference.reflectance(AlbedoRemap.NONE.singleScatterAlbedo(a)) < 0.85 * a)
    }

    @Test fun referenceRemapRoundTripsThroughAnIndependentTable() {
        for (a in albedos) {
            assertEquals(a, reference.reflectance(AlbedoRemap.REFERENCE.singleScatterAlbedo(a)), 0.006, "REFERENCE at A=$a")
        }
    }

    @Test fun coefficientsSplitExtinctionByTheRemappedAlbedo() {
        val c = subsurfaceCoefficients(Vector3f(0.8f, 0.5f, 0.3f), Vector3f(1f, 0.5f, 0.25f), AlbedoRemap.VAN_DE_HULST)
        for (i in 0..2) {
            val a = doubleArrayOf(0.8, 0.5, 0.3)[i]
            val alpha = AlbedoRemap.VAN_DE_HULST.singleScatterAlbedo(a)
            val sigmaT = c.extinction[i].toDouble()
            assertEquals(AlbedoRemap.VAN_DE_HULST.extinction(a, doubleArrayOf(1.0, 0.5, 0.25)[i]), sigmaT, 1e-5)
            assertTrue(abs(c.scattering[i] / sigmaT - alpha) < 1e-5)
        }
        val m = PathTraceMaterial.subsurface(c)
        assertTrue(m.scatters && m.transmission)
        val scene = PathTraceScene()
        scene.material(m)
        scene.triangle(Vector3f(), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), 0)
        val bytes = java.nio.ByteBuffer.allocate(PathTraceMaterial.STRIDE).order(java.nio.ByteOrder.nativeOrder())
        scene.pack().writeMaterials(bytes)
        assertEquals(PathTraceMaterial.STRIDE, bytes.position())
        // The absorption written for the shader is sigma_a itself.
        for (i in 0..2) assertEquals(c.absorption[i], bytes.getFloat(48 + 4 * i), 1e-4f * (1f + c.absorption[i]))
        assertEquals(c.scattering.x, bytes.getFloat(112), 1e-6f)
    }
}

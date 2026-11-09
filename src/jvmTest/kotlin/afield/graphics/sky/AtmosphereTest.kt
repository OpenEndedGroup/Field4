package afield.graphics.sky

import afield.graphics.sky.Atmosphere.V3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CPU twin of `sky/atmosphere.glsl`: its transmittance against the closed-form optical depth of the
 * exponential and tent profiles, and the colours a sky must have (blue overhead at noon, red toward a
 * setting sun, dark from the planet's night side).
 */
class AtmosphereTest {
    private val a = Atmosphere.Params()
    private val ground = V3(0.0, a.planetRadius + 0.001, 0.0)

    @Test
    fun zenithTransmittanceMatchesClosedForm() {
        val top = a.atmosphereRadius - a.planetRadius
        val h0 = 0.001
        fun expDepth(scale: Double) = scale * (exp(-h0 / scale) - exp(-top / scale))
        val ozone = a.ozoneHalfWidth // area of a tent of unit peak and this half-width
        val depth = a.rayleighScattering * expDepth(a.rayleighHeight) +
            V3(1.0, 1.0, 1.0) * ((a.mieScattering + a.mieAbsorption) * expDepth(a.mieHeight)) +
            a.ozoneAbsorption * ozone
        val expected = depth.map { exp(-it) }
        val got = Atmosphere.transmittanceToTop(a, ground, V3(0.0, 1.0, 0.0), steps = 512)
        for ((e, g) in listOf(expected.x to got.x, expected.y to got.y, expected.z to got.z))
            assertTrue(abs(e - g) / e < 2e-3, "transmittance $got, closed form $expected")
        // Blue is the colour removed most on the way up.
        assertTrue(got.z < got.y && got.y < got.x, "$got")
    }

    @Test
    fun noonZenithIsBlue() {
        val l = Atmosphere.radiance(a, ground, V3(0.0, 1.0, 0.0), Atmosphere.direction(Math.toRadians(60.0)))
        assertTrue(l.z > l.y && l.y > l.x && l.x > 0, "noon zenith $l")
    }

    @Test
    fun sunsetHorizonIsRed() {
        val sun = Atmosphere.direction(Math.toRadians(1.0))
        val l = Atmosphere.radiance(a, ground, Atmosphere.direction(Math.toRadians(2.0)), sun, steps = 64)
        assertTrue(l.x > l.z && l.x.isFinite(), "sunset horizon $l")
    }

    @Test
    fun nightSideFromSpaceIsDark() {
        val camera = V3(0.0, 0.0, a.planetRadius * 3)
        val towardPlanet = V3(0.0, 0.0, -1.0)
        val lit = Atmosphere.radiance(a, camera, towardPlanet, V3(0.0, 0.0, 1.0))
        val night = Atmosphere.radiance(a, camera, towardPlanet, V3(0.0, 0.0, -1.0))
        assertTrue(lit.x > 100 * night.x + 1e-6 && night.x >= 0, "lit $lit, night $night")
    }

    @Test
    fun includeIsPackaged() {
        val glsl = Atmosphere.source("atmosphere.glsl")
        for (name in listOf("earthAtmosphere", "skyRadiance", "skyTransmittanceToTop", "skyMiePhase"))
            assertTrue(name in glsl, name)
    }
}

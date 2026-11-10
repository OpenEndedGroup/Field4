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

    /** `directionToEquirect` from the fabricate lighting shader, rotation 0, transcribed. */
    private fun shaderUv(d: V3): Pair<Double, Double> {
        val u = kotlin.math.atan2(d.z, d.x) / (2 * Math.PI) + 0.5
        val v = kotlin.math.asin(d.y.coerceIn(-1.0, 1.0)) / Math.PI + 0.5
        return (u - kotlin.math.floor(u)) to (1 - v)
    }

    @Test
    fun equirectTexelsSitWhereTheRendererSamplesThem() {
        val w = 32; val h = 16
        for (j in 0 until h) for (i in 0 until w) {
            val (u, v) = shaderUv(Atmosphere.equirectDirection(i, j, w, h))
            assertTrue(abs(u * w - (i + 0.5)) < 1e-6 && abs(v * h - (j + 0.5)) < 1e-6, "texel $i,$j sampled at ${u * w},${v * h}")
        }
    }

    @Test
    fun bakedTexelsAreTheRadianceAlongTheirDirection() {
        val w = 16; val h = 8
        val sun = Atmosphere.direction(Math.toRadians(10.0), 0.3)
        val baked = Atmosphere.bakeEquirect(w, h, sun, a, altitude = 0.2, scale = 0.5, steps = 12, lightSteps = 4)
        val ro = V3(0.0, a.planetRadius + 0.2, 0.0)
        for ((i, j) in listOf(0 to 0, 5 to 2, 11 to 3, 15 to 7)) {
            val l = Atmosphere.radiance(a, ro, Atmosphere.equirectDirection(i, j, w, h), sun, sunAngularRadius = 0.0, steps = 12, lightSteps = 4) * 0.5
            val o = (j * w + i) * 4
            for ((e, g) in listOf(l.x to baked[o], l.y to baked[o + 1], l.z to baked[o + 2]))
                assertTrue(abs(e - g) <= 1e-6 * maxOf(1.0, abs(e)), "texel $i,$j: $g vs $e")
            assertTrue(baked[o + 3] == 1f)
        }
    }

    @Test
    fun sunLightReddensTowardTheHorizonAndGoesOutBelowIt() {
        val noon = Atmosphere.sunLight(Atmosphere.direction(Math.toRadians(70.0)), a)
        val low = Atmosphere.sunLight(Atmosphere.direction(Math.toRadians(2.0)), a)
        val night = Atmosphere.sunLight(Atmosphere.direction(Math.toRadians(-6.0)), a)
        assertTrue(noon.z / noon.x > 0.7, "noon sun $noon should be near white")
        assertTrue(low.z / low.x < 0.2, "low sun $low should be red-orange")
        assertTrue(night.x == 0.0 && night.y == 0.0 && night.z == 0.0, "sun below the horizon $night")
    }

    @Test
    fun sunDiscKeepsItsPowerInOneTexel() {
        val w = 64; val h = 32
        val sun = Atmosphere.direction(Math.toRadians(30.0), 1.0)
        val rgba = FloatArray(w * h * 4)
        Atmosphere.addSunDisc(rgba, w, h, sun, a)
        var power = 0.0; var lit = 0
        for (j in 0 until h) for (k in 0 until w) {
            val r = rgba[(j * w + k) * 4].toDouble()
            if (r > 0) lit++
            power += r * 2 * Math.PI / w * (kotlin.math.cos(Math.PI * j / h) - kotlin.math.cos(Math.PI * (j + 1) / h))
        }
        val expected = Atmosphere.sunLight(sun, a).x * 20.0 * 1000.0 * 2 * Math.PI * (1 - kotlin.math.cos(0.00465))
        assertTrue(lit == 1 && abs(power - expected) / expected < 1e-5, "sun power $power in $lit texels, expected $expected")
        val night = FloatArray(w * h * 4)
        Atmosphere.addSunDisc(night, w, h, Atmosphere.direction(Math.toRadians(-5.0)), a)
        assertTrue(night.all { it == 0f })
    }
}

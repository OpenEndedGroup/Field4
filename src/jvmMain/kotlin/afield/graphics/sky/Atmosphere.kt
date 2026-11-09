/**
 * Physically based sky: single scattering through a spherical atmosphere, as a shader include and its
 * CPU twin.
 *
 * `#include "sky/atmosphere.glsl"` gives any Field shader `earthAtmosphere()`, `skyRadiance(...)`,
 * `skyTransmittanceToTop(...)` and the phase functions; the resolver in `shaders.kt` serves it with no
 * setup. This object holds the same functions in Kotlin, for tests and for reading a sky colour on the
 * CPU (a light's colour at a given sun elevation, say). Method: Nishita et al. 1993; Earth's
 * coefficients from Hillaire 2020. See `public/docs/atmospheric_sky.md`.
 */
package afield.graphics.sky

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** The GLSL include and the CPU reference of the same atmosphere model. */
object Atmosphere {
    /** Names served under the `sky/` include root. */
    private val names = setOf("atmosphere.glsl")

    /** Whether `sky/<name>` is a packaged include. */
    fun has(name: String) = name in names

    /** The packaged GLSL for `sky/<name>`. */
    fun source(name: String): String {
        require(name in names) { "Unknown sky shader include: $name" }
        return checkNotNull(javaClass.getResourceAsStream("/afield/four/sky/$name")) {
            "Missing packaged sky shader: $name"
        }.bufferedReader().use { it.readText() }
    }

    /** A colour or position, in the units of the call (km for positions). */
    data class V3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
        operator fun times(o: V3) = V3(x * o.x, y * o.y, z * o.z)
        operator fun times(s: Double) = V3(x * s, y * s, z * s)
        infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        val length get() = sqrt(this dot this)
        fun map(f: (Double) -> Double) = V3(f(x), f(y), f(z))
        fun normalized() = this * (1.0 / length)
    }

    /** Earth's atmosphere by default; every field matches the GLSL `Atmosphere` struct. */
    data class Params(
        val planetRadius: Double = 6360.0,
        val atmosphereRadius: Double = 6460.0,
        val rayleighScattering: V3 = V3(5.802e-3, 13.558e-3, 33.1e-3),
        val rayleighHeight: Double = 8.0,
        val mieScattering: Double = 3.996e-3,
        val mieAbsorption: Double = 4.40e-3,
        val mieHeight: Double = 1.2,
        val mieG: Double = 0.8,
        val ozoneAbsorption: V3 = V3(0.650e-3, 1.881e-3, 0.085e-3),
        val ozoneCenter: Double = 25.0,
        val ozoneHalfWidth: Double = 15.0,
        val groundAlbedo: V3 = V3(0.3, 0.3, 0.3),
    )

    /** Crossings of the sphere |p| = r by ro + t rd, or (-1, -1). */
    fun raySphere(ro: V3, rd: V3, r: Double): Pair<Double, Double> {
        val b = ro dot rd
        val d = b * b - ((ro dot ro) - r * r)
        if (d < 0) return -1.0 to -1.0
        val s = sqrt(d)
        return (-b - s) to (-b + s)
    }

    /** Relative Rayleigh, Mie and ozone densities at altitude [h] km. */
    fun density(a: Params, h: Double) =
        V3(exp(-h / a.rayleighHeight), exp(-h / a.mieHeight), max(0.0, 1 - abs(h - a.ozoneCenter) / a.ozoneHalfWidth))

    /** Extinction per km for the given relative densities. */
    fun extinction(a: Params, d: V3): V3 =
        a.rayleighScattering * d.x + V3(1.0, 1.0, 1.0) * ((a.mieScattering + a.mieAbsorption) * d.y) + a.ozoneAbsorption * d.z

    /** Rayleigh phase function of the cosine [mu]. */
    fun rayleighPhase(mu: Double) = 3.0 / (16 * Math.PI) * (1 + mu * mu)

    /** Cornette-Shanks phase function of the cosine [mu]. */
    fun miePhase(mu: Double, g: Double): Double {
        val g2 = g * g
        return 3.0 / (8 * Math.PI) * (1 - g2) * (1 + mu * mu) / ((2 + g2) * (1 + g2 - 2 * g * mu).pow(1.5))
    }

    /** Transmittance from [p] to the top of the atmosphere along [dir]; zero inside the planet's shadow. */
    fun transmittanceToTop(a: Params, p: V3, dir: V3, steps: Int = 32): V3 {
        if (raySphere(p, dir, a.planetRadius).first > 0) return V3(0.0, 0.0, 0.0)
        val t = raySphere(p, dir, a.atmosphereRadius).second
        if (t <= 0) return V3(1.0, 1.0, 1.0)
        val dt = t / steps
        var depth = V3(0.0, 0.0, 0.0)
        for (i in 0 until steps) {
            val q = p + dir * ((i + 0.5) * dt)
            depth += extinction(a, density(a, q.length - a.planetRadius)) * dt
        }
        return depth.map { exp(-it) }
    }

    /**
     * What a camera at [ro] (km from the planet's centre) sees along unit [rd]: the same
     * `skyRadiance` as the shader, sun disc included.
     */
    fun radiance(
        a: Params, ro: V3, rd: V3, sunDir: V3, sunColor: V3 = V3(20.0, 20.0, 20.0),
        sunAngularRadius: Double = 0.00465, steps: Int = 32, lightSteps: Int = 8,
    ): V3 {
        val zero = V3(0.0, 0.0, 0.0)
        val disc = (rd dot sunDir) > cos(sunAngularRadius)
        val top = raySphere(ro, rd, a.atmosphereRadius)
        if (top.second <= 0) return if (disc) sunColor * 1000.0 else zero
        val t0 = max(top.first, 0.0)
        val ground = raySphere(ro, rd, a.planetRadius)
        val hitGround = ground.first > 0
        val t1 = if (hitGround) ground.first else top.second
        val mu = rd dot sunDir
        val pr = rayleighPhase(mu)
        val pm = miePhase(mu, a.mieG)
        val dt = (t1 - t0) / steps
        var sum = zero
        var depth = zero
        for (i in 0 until steps) {
            val p = ro + rd * (t0 + (i + 0.5) * dt)
            val d = density(a, p.length - a.planetRadius)
            val ext = extinction(a, d)
            val viewT = (depth + ext * (0.5 * dt)).map { exp(-it) }
            val sunT = transmittanceToTop(a, p, sunDir, lightSteps)
            val scatter = a.rayleighScattering * (d.x * pr) + V3(1.0, 1.0, 1.0) * (a.mieScattering * d.y * pm)
            sum += viewT * sunT * scatter * dt
            depth += ext * dt
        }
        val trans = depth.map { exp(-it) }
        var l = sum * sunColor
        if (hitGround) {
            val p = ro + rd * t1
            val n = p.normalized()
            val sunT = transmittanceToTop(a, p + n * 0.01, sunDir, lightSteps)
            l += trans * a.groundAlbedo * (1 / Math.PI) * sunColor * sunT * max(n dot sunDir, 0.0)
        } else if (disc) {
            l += trans * sunColor * 1000.0
        }
        return l
    }

    /** Unit direction at [elevation] above the horizon (radians) and [azimuth] about +y, for a camera whose up is +y. */
    fun direction(elevation: Double, azimuth: Double = 0.0) =
        V3(cos(elevation) * kotlin.math.sin(azimuth), kotlin.math.sin(elevation), -cos(elevation) * cos(azimuth))
}

package afield.graphics.scene.fabricated

import afield.math.linalg.Vector3f
import java.util.SplittableRandom
import kotlin.math.*

/**
 * Albedo remapping for random-walk subsurface scattering: from the colour an artist asks for to the
 * coefficients a volume walk needs.
 *
 * An artist sets two things per channel, a colour and a radius. A random walk needs two different
 * things, the single-scattering albedo `sigma_s / sigma_t` and the extinction `sigma_t`. The two
 * albedos are not the same number: a walk whose every collision keeps 80% of the light comes back
 * out of a thick slab with far less than 80%, because a returning path collides many times. The
 * colour is the *multiple*-scattering albedo, what the surface looks like, and a remap inverts it.
 *
 * [AlbedoRemap] holds the published inversions plus Field's own: [HalfSpaceAlbedo] computes, by
 * Monte Carlo, how much light a half-space of a given single-scattering albedo reflects, and
 * [AlbedoRemap.REFERENCE] inverts that table numerically. `SubsurfaceRemapTest` checks the closed
 * forms against it. [subsurfaceCoefficients] turns a colour and radius into the absorption and
 * scattering coefficients [PathTraceMaterial.subsurface] stores.
 */

/** How a multiple-scattering albedo (the colour you see) becomes a single-scattering one. */
enum class AlbedoRemap {
    /** The colour is used as the single-scattering albedo directly. Renders much darker than asked. */
    NONE,

    /**
     * The closed-form inversion of van de Hulst's half-space albedo, from Chiang, Kutz and Burley,
     * "Practical and Controllable Subsurface Scattering for Production Path Tracing" (2016), and
     * the remap Blender Cycles uses for random-walk SSS.
     */
    VAN_DE_HULST,

    /**
     * The exponential–cubic fit `1 - exp(-5.09406 A + 2.61188 A² - 4.31805 A³)` that circulates
     * with Disney's normalized-diffusion work. It agrees with the others up to A ≈ 0.78 and asks
     * for too little scattering above that, so pale colours render darker than asked.
     */
    EXP_CUBIC,

    /** Field's own numerical inversion of [HalfSpaceAlbedo.standard], the reference the others are measured against. */
    REFERENCE;

    /** Single-scattering albedo for the multiple-scattering albedo [albedo], both in [0, 1]. */
    fun singleScatterAlbedo(albedo: Double): Double {
        val a = albedo.coerceIn(0.0, MAX_ALBEDO)
        return when (this) {
            NONE -> a
            VAN_DE_HULST -> {
                val b = 4.09712 + 4.20863 * a - sqrt(9.59217 + 41.6808 * a + 17.7126 * a * a)
                1.0 - b * b
            }
            EXP_CUBIC -> 1.0 - exp(-5.09406 * a + 2.61188 * a * a - 4.31805 * a * a * a)
            REFERENCE -> HalfSpaceAlbedo.standard.invert(a)
        }.coerceIn(0.0, 1.0)
    }

    /**
     * Extinction `sigma_t` per world unit for a channel of colour [albedo] and scattering
     * [radius]. [NONE] reads the radius as the mean free path; the others divide by Chiang et
     * al.'s scale `1.9 - A + 3.5 (A - 0.8)²`, which makes the radius roughly the distance the
     * colour visibly travels under the surface at every albedo.
     */
    fun extinction(albedo: Double, radius: Double): Double {
        require(radius.isFinite() && radius > 0.0) { "Scattering radius must be positive" }
        val a = albedo.coerceIn(0.0, MAX_ALBEDO)
        val scale = if (this == NONE) 1.0 else 1.9 - a + 3.5 * (a - 0.8) * (a - 0.8)
        return 1.0 / (radius * scale)
    }

    companion object {
        /** Colours are clamped below one: an albedo of exactly one needs an infinitely long walk. */
        const val MAX_ALBEDO = 0.999
    }
}

/** Per-channel coefficients per world unit, the form [PathTraceMaterial.subsurface] stores. */
data class SubsurfaceCoefficients(val absorption: Vector3f, val scattering: Vector3f) {
    /** `sigma_a + sigma_s`, per channel. */
    val extinction: Vector3f get() = Vector3f(absorption).add(scattering)
}

/** Absorption and scattering coefficients for a [color] and per-channel [radius] through [remap]. */
fun subsurfaceCoefficients(color: Vector3f, radius: Vector3f, remap: AlbedoRemap = AlbedoRemap.VAN_DE_HULST): SubsurfaceCoefficients {
    val absorption = Vector3f()
    val scattering = Vector3f()
    for (c in 0..2) {
        val albedo = color[c].toDouble()
        val sigmaT = remap.extinction(albedo, radius[c].toDouble())
        val alpha = remap.singleScatterAlbedo(albedo)
        scattering[c] = (alpha * sigmaT).toFloat()
        absorption[c] = ((1.0 - alpha) * sigmaT).toFloat()
    }
    return SubsurfaceCoefficients(absorption, scattering)
}

/**
 * The diffuse reflectance of a semi-infinite, index-matched, isotropically scattering medium as a
 * function of its single-scattering albedo, by Monte Carlo.
 *
 * Light arrives cosine-weighted (uniform diffuse illumination), walks with unit extinction, and
 * the walk records how many collisions it had when it left through the surface. A path that left
 * after `n` collisions carries `alpha^n` for any albedo, so one set of albedo-one walks gives the
 * whole curve: `R(alpha) = sum_n p_n alpha^n`. Walks still inside after [maxScatters] collisions
 * are accounted for by the half-space's known `n^(-3/2)` tail, scaled to the surviving mass.
 */
class HalfSpaceAlbedo(val walks: Int = 400_000, val maxScatters: Int = 4000, seed: Long = 1L) {
    /** `escapes[n]`: fraction of walks that left after exactly n collisions. */
    val escapes = DoubleArray(maxScatters + 1)
    /** Fraction of walks still inside after [maxScatters] collisions. */
    val survived: Double

    init {
        require(walks > 0 && maxScatters > 0)
        val random = SplittableRandom(seed)
        val counts = LongArray(maxScatters + 1)
        var inside = 0L
        repeat(walks) {
            var mu = sqrt(random.nextDouble()) // cosine-weighted inward direction
            var z = 0.0
            var n = 0
            while (true) {
                z += mu * -ln(1.0 - random.nextDouble())
                if (z < 0.0) { counts[n]++; break }
                if (n == maxScatters) { inside++; break }
                n++
                mu = 1.0 - 2.0 * random.nextDouble()
            }
        }
        for (i in counts.indices) escapes[i] = counts[i].toDouble() / walks
        survived = inside.toDouble() / walks
    }

    /** Reflectance, the multiple-scattering albedo, of a half-space whose single-scattering albedo is [alpha]. */
    fun reflectance(alpha: Double): Double {
        require(alpha in 0.0..1.0)
        var sum = 0.0
        var power = 1.0
        for (n in escapes.indices) { sum += escapes[n] * power; power *= alpha }
        if (survived <= 0.0) return sum
        if (alpha >= 1.0) return sum + survived
        if (alpha <= 0.0) return sum
        // P(N > n) ~ c n^(-1/2), so p_n ~ (c/2) n^(-3/2), with c fixed by the surviving mass. The
        // tail sum is the integral (c/2) ∫_N^∞ x^(-3/2) e^(-λx) dx with λ = -ln(alpha), which is
        // c (e^(-λN)/√N - √(πλ) erfc(√(λN))) and tends to the surviving mass as alpha → 1.
        val bigN = maxScatters.toDouble()
        val c = survived * sqrt(bigN)
        val lambda = -ln(alpha)
        val tail = c * (exp(-lambda * bigN) / sqrt(bigN) - sqrt(PI * lambda) * erfc(sqrt(lambda * bigN)))
        return sum + max(tail, 0.0)
    }

    /** Complementary error function, Numerical Recipes' Chebyshev fit (relative error below 1.2e-7). */
    private fun erfc(x: Double): Double {
        val t = 1.0 / (1.0 + 0.5 * abs(x))
        val r = t * exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
            t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
            t * (-0.82215223 + t * 0.17087277)))))))))
        return if (x >= 0) r else 2.0 - r
    }

    /** The single-scattering albedo whose half-space reflectance is [albedo], by bisection. */
    fun invert(albedo: Double): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(48) {
            val mid = 0.5 * (lo + hi)
            if (reflectance(mid) < albedo) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    companion object {
        /** The table [AlbedoRemap.REFERENCE] inverts: 400 000 walks, fixed seed, built on first use. */
        val standard: HalfSpaceAlbedo by lazy { HalfSpaceAlbedo() }
    }
}

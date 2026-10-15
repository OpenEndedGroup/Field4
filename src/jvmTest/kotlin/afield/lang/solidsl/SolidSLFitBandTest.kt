package afield.lang.solidsl

import afield.math.autodiff.OptimizeMethod
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What [SolidFitOptions.band] does, pinned.
 *
 * `band` clamps both the prediction and the target into `[-band, +band]` before differencing. The
 * KDoc makes three claims about that, and each has a test here:
 *
 *  1. it is a no-op when nothing reaches the clamp — so a huge band is bit-identical to `null`;
 *  2. it kills the gradient from every sample whose **prediction** is outside the band, whatever
 *     the target says there — so a target the model cannot see within `band` of its own surface
 *     leaves every parameter dead;
 *  3. it makes the objective about the surface — so on an approximation problem the banded fit has
 *     the smaller residual *near the target's surface*, and the larger one over the whole field.
 *
 * And a fourth that the KDoc did not say and that matters in use: because only samples near the
 * **model's** surface contribute, a banded fit can still walk a long way. A sphere seeded at half the
 * target's radius — five bands away — recovers it, pulled along by samples near its own surface.
 */
class SolidSLFitBandTest {

    private fun ball(): SolidTemplate = sphere(1.0).parameterize { param("r") { it.node == "Sphere" } }.orThrow()

    /** Points in a spherical shell `[inner, outer]` about the origin, with the true distance to a ball of [radius]. */
    private fun shellSamples(radius: Double, inner: Double, outer: Double, count: Int, seed: Int): SolidSamples {
        val rng = Random(seed)
        val xs = DoubleArray(count); val ys = DoubleArray(count); val zs = DoubleArray(count); val ds = DoubleArray(count)
        for (i in 0 until count) {
            var x: Double; var y: Double; var z: Double; var n: Double
            do {
                x = rng.nextDouble(-1.0, 1.0); y = rng.nextDouble(-1.0, 1.0); z = rng.nextDouble(-1.0, 1.0)
                n = sqrt(x * x + y * y + z * z)
            } while (n < 1e-6 || n > 1.0)
            val r = inner + rng.nextDouble() * (outer - inner)
            xs[i] = x / n * r; ys[i] = y / n * r; zs[i] = z / n * r; ds[i] = r - radius
        }
        return SolidSamples(xs, ys, zs, ds)
    }

    @Test
    fun `a band nothing reaches is bit-identical to no band`() {
        val target = sphere(0.8).sampleField(count = 800, seed = 3, margin = 0.5)
        val start = doubleArrayOf(1.1)
        val plain = ball().fitParameters(target, start)
        val wide = ball().fitParameters(target, start, SolidFitOptions(band = 1e6))
        assertEquals(plain.initialLoss, wide.initialLoss)
        assertEquals(plain.loss, wide.loss)
        assertContentEquals(plain.values, wide.values)
        assertEquals(plain.run.iterations, wide.run.iterations)
    }

    @Test
    fun `a band no positive number is refused`() {
        val target = sphere(0.8).sampleField(count = 50, seed = 3)
        for (bad in listOf(0.0, -0.1)) {
            assertFailsWith<IllegalArgumentException> {
                ball().fitParameters(target, doubleArrayOf(1.0), SolidFitOptions(band = bad))
            }
        }
    }

    @Test
    fun `samples whose prediction is outside the band give no gradient, whatever the target says`() {
        // Every sample sits 0.5 to 1.0 outside a unit ball, and the target is a ball of radius 0.95:
        // the target is *different* there, so an unbanded fit has something to learn.
        val target = shellSamples(radius = 0.95, inner = 1.5, outer = 2.0, count = 400, seed = 11)
        val start = doubleArrayOf(1.0)

        val plain = ball().fitParameters(target, start)
        assertEquals(emptyList(), plain.deadParameters)
        assertTrue(abs(plain.values[0] - 0.95) < 1e-6, "the unbanded fit should recover 0.95, got ${plain.values[0]}")

        // With band = 0.1 both sides clamp to +0.1 at every sample: the loss is exactly zero and
        // its gradient is exactly zero. The fit reports the radius dead and does not move it.
        val banded = ball().fitParameters(target, start, SolidFitOptions(band = 0.1))
        println(banded.report())
        assertEquals(0.0, banded.initialLoss)
        assertEquals(listOf("r"), banded.deadParameters)
        assertEquals(1.0, banded.values[0])

        // And it is the PREDICTION's saturation that kills it, not agreement: target distances
        // inside the band (radius 1.45, so the samples read 0.05..0.55) still give nothing,
        // because the model's own value is 0.5..1.0 and the clamp's derivative there is zero.
        val near = shellSamples(radius = 1.45, inner = 1.5, outer = 2.0, count = 400, seed = 11)
        val stillDead = ball().fitParameters(near, start, SolidFitOptions(band = 0.1))
        assertTrue(stillDead.initialLoss > 0.0, "premise: the banded residual is not zero here")
        assertEquals(listOf("r"), stillDead.deadParameters)
    }

    @Test
    fun `a banded fit walks five bands to its target along its own surface`() {
        val target = sphere(1.0).sampleField(count = 2000, seed = 7, margin = 0.4)
        val fit = ball().fitParameters(target, doubleArrayOf(0.5), SolidFitOptions(band = 0.1, iterations = 400))
        println(fit.report())
        assertEquals(emptyList(), fit.deadParameters)
        assertTrue(abs(fit.values[0] - 1.0) < 1e-6, "banded fit from 0.5 reached ${fit.values[0]}, wanted 1.0")
    }

    @Test
    fun `on an approximation the band trades the far field for the surface`() {
        // Three spheres cannot be a rounded rod (SolidSLApproximationTest), so the minimiser depends
        // on what the loss weighs. Score both fits on the same two yardsticks.
        val rod = cylinder(2.2, 0.5).rounded(0.12)
        val target = rod.sampleField(count = 3000, seed = 17, margin = 0.3)
        val template = smoothUnion(
            smoothUnion(sphere(0.5).at(0.0, 0.0, 0.7), sphere(0.5), 0.35),
            sphere(0.5).at(0.0, 0.0, -0.7),
            0.35,
        ).parameterize {
            param("endRadius") { it.node == "Sphere" && it.path.contains("Translate") }
            param("middleRadius", "SmoothUnion/a/SmoothUnion/b/Sphere.radius")
            param("topZ", "SmoothUnion/a/SmoothUnion/a/Translate.z")
            param("bottomZ", "SmoothUnion/b/Translate.z")
        }.orThrow()

        val band = 0.05
        val opts = SolidFitOptions(method = OptimizeMethod.LBFGS, iterations = 500)
        val plain = template.fitParameters(target, options = opts)
        val banded = template.fitParameters(target, options = opts.copy(band = band))

        fun rms(fit: SolidFit, keep: (Double) -> Boolean): Double {
            val f = fit.fitted.toField3()
            var sum = 0.0; var n = 0
            for (i in 0 until target.size) if (keep(target.distances[i])) {
                val e = f.at(target.xs[i], target.ys[i], target.zs[i]) - target.distances[i]
                sum += e * e; n++
            }
            return sqrt(sum / n)
        }
        val nearPlain = rms(plain) { abs(it) < band }
        val nearBanded = rms(banded) { abs(it) < band }
        val allPlain = rms(plain) { true }
        val allBanded = rms(banded) { true }
        println("near-surface rms: plain %.5f banded %.5f; whole-field rms: plain %.5f banded %.5f"
            .format(nearPlain, nearBanded, allPlain, allBanded))
        println(plain.report()); println(banded.report())

        assertTrue(nearBanded < nearPlain, "the banded fit is not closer to the surface ($nearBanded vs $nearPlain)")
        assertTrue(allBanded > allPlain, "the banded fit is not worse over the whole field ($allBanded vs $allPlain)")
    }
}

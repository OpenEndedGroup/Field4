package afield.math.functions

import afield.math.linalg.Vec2
import java.util.SplittableRandom
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims in `RbfField.kt`: it passes through its samples, reproduces a plane exactly with the
 * trend on, smooths when asked, follows adds and removes, and its leave-one-out residuals equal
 * the ones from actually leaving each sample out and refitting.
 */
class RbfFieldTest {

    private fun scattered(n: Int, seed: Long): List<Vec2> {
        val r = SplittableRandom(seed)
        return List(n) { Vec2(r.nextDouble().toFloat() * 2f, r.nextDouble().toFloat() * 2f) }
    }

    private fun f(p: Vec2) = sin(p.x * 2.0) * cos(p.y * 1.5)

    @Test
    fun passesThroughItsSamples() {
        for (kernel in listOf(RbfKernels.thinPlate, RbfKernels.gaussian(2.0), RbfKernels.multiquadric(0.5))) {
            val points = scattered(30, 1)
            val field = rbfField(points.map { it to f(it) }, kernel)
            points.forEach { assertEquals(f(it), field(it), 1e-7, "$kernel at $it") }
        }
    }

    @Test
    fun reproducesAPlaneExactlyWithTheTrend() {
        val points = scattered(12, 2)
        val plane = { p: Vec2 -> 0.3 + 1.5 * p.x - 0.7 * p.y }
        val field = rbfField(points.map { it to plane(it) })
        for (q in scattered(20, 3)) assertEquals(plane(q), field(q), 1e-8)
    }

    @Test
    fun approximatesASmoothFunctionBetweenSamples() {
        val field = rbfField(scattered(200, 4).map { it to f(it) })
        val inside = scattered(50, 5).filter { it.x in 0.2f..1.8f && it.y in 0.2f..1.8f }
        val worst = inside.maxOf { kotlin.math.abs(field(it) - f(it)) }
        assertTrue(worst < 0.02, "worst interior error $worst")
    }

    @Test
    fun smoothingStopsPassingThroughTheSamples() {
        val r = SplittableRandom(6)
        val points = scattered(60, 7)
        val noisy = points.map { it to f(it) + (r.nextDouble() - 0.5) * 0.2 }
        val exact = rbfField(noisy)
        val smooth = rbfField(noisy, smoothing = 0.5)
        val missExact = noisy.maxOf { (p, v) -> kotlin.math.abs(exact(p) - v) }
        val missSmooth = noisy.maxOf { (p, v) -> kotlin.math.abs(smooth(p) - v) }
        assertTrue(missExact < 1e-6 && missSmooth > 0.01, "exact $missExact, smoothed $missSmooth")
    }

    @Test
    fun leaveOneOutEqualsRefittingWithoutEachSample() {
        val points = scattered(25, 8)
        for (kernel in listOf(RbfKernels.thinPlate, RbfKernels.gaussian(1.5))) for (smoothing in listOf(0.0, 0.1)) {
            val field = rbfField(points.map { it to f(it) }, kernel, smoothing)
            val fast = field.leaveOneOut()
            for (i in points.indices) {
                val without = rbfField(points.filterIndexed { k, _ -> k != i }.map { it to f(it) }, kernel, smoothing)
                assertEquals(without(points[i]), fast[i], 1e-7, "$kernel smoothing $smoothing sample $i")
            }
        }
    }

    @Test
    fun followsAddsAndRemoves() {
        val field = RbfField(2)
        assertEquals(0.0, field(0.5, 0.5))
        field.add(0.0, 0.0, 1.0)
        field.add(1.0, 0.0, 2.0)
        field.add(0.0, 1.0, 3.0)
        val removed = field.add(1.0, 1.0, 10.0)
        assertEquals(10.0, field(1.0, 1.0), 1e-9)
        field.removeAt(removed)
        // three samples and an affine trend: the plane through them
        assertEquals(4.0, field(1.0, 1.0), 1e-9)
        assertEquals(3, field.size)
    }
}

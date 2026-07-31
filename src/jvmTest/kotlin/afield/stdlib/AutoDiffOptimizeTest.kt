package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [minimize] and its three descents against problems whose exact minimum is known, so
 * "converged" means *arrived at the right answer*, not merely "the loss went down".
 *
 * The Rosenbrock case is the load-bearing one: its curved valley is exactly what separates a
 * curvature-aware line-searched method from a first-order one, and it is where an incorrect
 * gradient shows up as a stall rather than as a wrong number.
 */
class AutoDiffOptimizeTest {

    private val quietAdam = OptimizeOptions(method = OptimizeMethod.ADAM, showProgress = false)
    private val quietSgd = OptimizeOptions(method = OptimizeMethod.SGD, showProgress = false)
    private val quietLbfgs = OptimizeOptions(method = OptimizeMethod.LBFGS, showProgress = false)

    /** `(x-3)² + 2(y+1)²`, minimised at (3, -1) with value 0. */
    private fun bowl(p: List<AD>): AD = (p[0] - 3.0).sq() + 2.0 * (p[1] + 1.0).sq()

    /** The classic Rosenbrock banana, minimised at (1, 1) with value 0. */
    private fun rosenbrock(p: List<AD>): AD = (1.0 - p[0]).sq() + 100.0 * (p[1] - p[0].sq()).sq()

    @Test
    fun allThreeDescentsFindTheBowlMinimum() {
        val start = doubleArrayOf(-4.0, 5.0)
        val cases = listOf(
            "adam" to quietAdam.copy(iterations = 4000, learningRate = 0.05),
            "sgd" to quietSgd.copy(iterations = 2000, learningRate = 0.02, momentum = 0.9),
            "lbfgs" to quietLbfgs.copy(iterations = 200)
        )
        for ((name, options) in cases) {
            val run = minimize(start, options) { bowl(it) }
            assertTrue(run.improved, "$name should end below where it started")
            assertTrue(
                (run.x[0] - 3.0).absoluteValue < 1e-4 && (run.x[1] + 1.0).absoluteValue < 1e-4,
                "$name landed at ${run.x.toList()}, expected ~[3, -1] (loss ${run.value})"
            )
            assertEquals(run.iterations + 1, run.losses.size, "$name: losses must be start-inclusive")
            assertEquals(run.initialValue, run.losses.first(), 0.0, "$name")
            assertEquals(run.value, run.losses.last(), 0.0, "$name")
        }
    }

    @Test
    fun lbfgsSolvesRosenbrockToMachinePrecision() {
        val run = minimize(doubleArrayOf(-1.2, 1.0), quietLbfgs.copy(iterations = 300)) { rosenbrock(it) }
        assertTrue(
            (run.x[0] - 1.0).absoluteValue < 1e-6 && (run.x[1] - 1.0).absoluteValue < 1e-6,
            "L-BFGS landed at ${run.x.toList()} with loss ${run.value} after ${run.iterations} iterations"
        )
        assertTrue(run.value < 1e-12, "final Rosenbrock value ${run.value} should be ~0")
        assertTrue(run.converged, "should stop on a tolerance, not on the iteration cap (${run.reason})")
    }

    @Test
    fun lbfgsReachesRosenbrockFarCheaperThanAdam() {
        val start = doubleArrayOf(-1.2, 1.0)
        val l = minimize(start, quietLbfgs.copy(iterations = 300)) { rosenbrock(it) }
        val a = minimize(start, quietAdam.copy(iterations = 5000, learningRate = 0.02)) { rosenbrock(it) }
        assertTrue(l.value < a.value, "L-BFGS ${l.value} should beat Adam ${a.value} on a smooth valley")
        assertTrue(
            l.evaluations < a.evaluations,
            "L-BFGS used ${l.evaluations} evaluations, Adam ${a.evaluations}"
        )
    }

    @Test
    fun everyDescentIsDeterministic() {
        val start = doubleArrayOf(0.7, -2.3)
        for (options in listOf(quietAdam, quietSgd, quietLbfgs)) {
            val a = minimize(start, options.copy(iterations = 120)) { rosenbrock(it) }
            val b = minimize(start, options.copy(iterations = 120)) { rosenbrock(it) }
            assertEquals(a.losses, b.losses, "${options.method}: identical config must give an identical trajectory")
            assertTrue(a.x.contentEquals(b.x), "${options.method}: identical config must give identical parameters")
        }
    }

    @Test
    fun initJitterIsSeededAndReproducible() {
        val start = doubleArrayOf(1.0, 1.0)
        val a = minimize(start, quietAdam.copy(iterations = 50, initJitter = 0.5, rngSeed = 7L)) { bowl(it) }
        val b = minimize(start, quietAdam.copy(iterations = 50, initJitter = 0.5, rngSeed = 7L)) { bowl(it) }
        val c = minimize(start, quietAdam.copy(iterations = 50, initJitter = 0.5, rngSeed = 8L)) { bowl(it) }
        assertTrue(a.initialX.contentEquals(b.initialX), "same seed must jitter identically")
        assertTrue(!a.initialX.contentEquals(c.initialX), "a different seed must jitter differently")
        assertTrue(!a.initialX.contentEquals(start), "jitter must actually move the start")
    }

    @Test
    fun zeroIterationsReportsTheStartingPointHonestly() {
        val run = minimize(doubleArrayOf(2.0, 2.0), quietAdam.copy(iterations = 0)) { bowl(it) }
        assertEquals(0, run.iterations)
        assertEquals(1, run.losses.size)
        assertEquals("no iterations", run.reason)
        assertEquals(bowlValue(2.0, 2.0), run.value, 1e-12)
        // The reported gradient is the real one at the start, not zeros.
        assertEquals(-2.0, run.gradient[0], 1e-12)
        assertEquals(12.0, run.gradient[1], 1e-12)
    }

    private fun bowlValue(x: Double, y: Double) = (x - 3.0) * (x - 3.0) + 2.0 * (y + 1.0) * (y + 1.0)

    @Test
    fun nesterovMomentumAlsoConverges() {
        val run = minimize(
            doubleArrayOf(-4.0, 5.0),
            quietSgd.copy(iterations = 2000, learningRate = 0.02, momentum = 0.9, nesterov = true)
        ) { bowl(it) }
        assertTrue(
            (run.x[0] - 3.0).absoluteValue < 1e-4 && (run.x[1] + 1.0).absoluteValue < 1e-4,
            "nesterov landed at ${run.x.toList()}"
        )
    }

    @Test
    fun aFourParameterCurveFitConvergesToTheGeneratingParameters() {
        // y = a·exp(b·x) + c·sin(d·x); recover (a,b,c,d) from noiseless samples.
        val truth = doubleArrayOf(2.3, -1.7, 0.6, 4.0)
        val xs = DoubleArray(48) { it / 47.0 * 2.0 }
        val ys = xs.map { x ->
            truth[0] * kotlin.math.exp(truth[1] * x) + truth[2] * kotlin.math.sin(truth[3] * x)
        }
        val run = minimize(
            doubleArrayOf(1.0, -1.0, 1.0, 3.5),
            quietLbfgs.copy(iterations = 500)
        ) { p ->
            mean(xs.indices.map { i -> (p[0] * exp(p[1] * xs[i]) + p[2] * sin(p[3] * xs[i]) - ys[i]).sq() })
        }
        for (i in truth.indices) {
            assertTrue(
                (run.x[i] - truth[i]).absoluteValue < 1e-4,
                "parameter $i recovered as ${run.x[i]}, truth ${truth[i]} (final loss ${run.value})"
            )
        }
        assertTrue(run.value < 1e-14, "a noiseless fit should reach ~0 loss, got ${run.value}")
    }

    @Test
    fun minimizeWithGradientAcceptsAnExternalGradient() {
        // Hand-derived gradient of the bowl — the seam other gradient sources plug into.
        val run = minimizeWithGradient(doubleArrayOf(0.0, 0.0), quietLbfgs.copy(iterations = 100)) { p ->
            ADGradient(
                bowlValue(p[0], p[1]),
                doubleArrayOf(2.0 * (p[0] - 3.0), 4.0 * (p[1] + 1.0))
            )
        }
        assertTrue((run.x[0] - 3.0).absoluteValue < 1e-8, "x0 = ${run.x[0]}")
        assertTrue((run.x[1] + 1.0).absoluteValue < 1e-8, "x1 = ${run.x[1]}")
    }

    @Test
    fun lossTrajectoriesAreMonotoneForTheLineSearchedMethod() {
        val run = minimize(doubleArrayOf(-1.2, 1.0), quietLbfgs.copy(iterations = 200)) { rosenbrock(it) }
        for (i in 1 until run.losses.size) {
            assertTrue(
                run.losses[i] <= run.losses[i - 1] + 1e-15,
                "Armijo guarantees monotone descent, but loss rose at step $i: " +
                    "${run.losses[i - 1]} -> ${run.losses[i]}"
            )
        }
    }
}

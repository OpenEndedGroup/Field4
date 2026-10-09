package afield.math.gp

import kotlin.math.*
import kotlin.test.*

class GpDerivativeTest {
    private val kernels: Map<String, GpKernel> = mapOf(
        "rbf ARD" to GpKernels.rbf(doubleArrayOf(0.7, 1.3), 2.0),
        "matern 3/2" to GpKernels.matern(1.5, doubleArrayOf(0.6, 0.9), 1.5),
        "matern 5/2" to GpKernels.matern(2.5, 0.8, 0.7),
        "rational quadratic" to GpKernels.rationalQuadratic(doubleArrayOf(0.5, 1.1), alpha = 0.8, variance = 1.2),
        "periodic" to GpKernels.periodic(period = 1.3, lengthScale = 0.7, variance = 0.9),
        "octave periodic" to GpKernels.octavePeriodic(1.0, components = 4, lengthScale = 0.8),
        "linear" to GpKernels.linear(0.6, doubleArrayOf(0.2, -0.1)),
        "sum" to GpKernels.rbf(0.5) + GpKernels.matern(1.5, 1.2) * 0.3,
        "product" to GpKernels.periodic(1.0, 0.6) * GpKernels.rbf(2.0) + GpKernels.constant(0.2),
    )

    @Test fun `kernel derivatives match central differences`() {
        val rng = java.util.Random(3)
        val h = 1e-5
        for ((name, kernel) in kernels) {
            val k = assertIs<GpDifferentiableKernel>(kernel, name)
            repeat(20) {
                val a = DoubleArray(2) { rng.nextGaussian() }
                val b = DoubleArray(2) { a[it] + 0.7 * rng.nextGaussian() }
                for (j in 0..1) {
                    val up = b.copyOf().also { it[j] += h }; val down = b.copyOf().also { it[j] -= h }
                    val fd = (k.covariance(a, up) - k.covariance(a, down)) / (2 * h)
                    assertEquals(fd, k.covarianceGradient(a, b, j), 1e-6 * max(1.0, abs(fd)), "$name gradient $j")
                    for (i in 0..1) {
                        val au = a.copyOf().also { it[i] += h }; val ad = a.copyOf().also { it[i] -= h }
                        val fd2 = (k.covarianceGradient(au, b, j) - k.covarianceGradient(ad, b, j)) / (2 * h)
                        assertEquals(fd2, k.covarianceHessian(a, b, i, j), 1e-5 * max(1.0, abs(fd2)), "$name hessian $i$j")
                    }
                }
            }
        }
    }

    @Test fun `derivative prior variance at a point is finite for once-differentiable kernels`() {
        val p = doubleArrayOf(0.3)
        assertEquals(2.0 / 0.25, GpKernels.rbf(0.5, 2.0).covarianceHessian(p, p, 0, 0), 1e-12)
        assertEquals(3.0 / 0.25, (GpKernels.matern(1.5, 0.5) as GpDifferentiableKernel).covarianceHessian(p, p, 0, 0), 1e-12)
        assertEquals(5.0 / 3 / 0.25, (GpKernels.matern(2.5, 0.5) as GpDifferentiableKernel).covarianceHessian(p, p, 0, 0), 1e-12)
        assertFalse(GpKernels.matern(0.5) is GpDifferentiableKernel)
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(0.0), doubleArrayOf(1.0), GpKernels.matern(0.5)).derivative(0, 0.2) }
    }

    @Test fun `derivative posterior is the limit of differenced value posteriors`() {
        val gp = gp1D(doubleArrayOf(-1.0, -0.2, 0.4, 1.1), doubleArrayOf(0.3, -0.5, 0.2, 0.9), GpKernels.rbf(0.6), noiseStd = 0.05)
        val xs = doubleArrayOf(-0.6, 0.0, 0.7)
        val d = gp.derivativePosterior(xs)
        val h = 1e-4
        val values = gp.posterior(DoubleArray(2 * xs.size) { xs[it / 2] + if (it % 2 == 0) -h else h })
        val difference = values.linearTransform(Array(xs.size) { i -> DoubleArray(2 * xs.size) { j -> if (j == 2 * i) -0.5 / h else if (j == 2 * i + 1) 0.5 / h else 0.0 } })
        for (i in xs.indices) {
            assertEquals(difference.mean[i], d.mean[i], 1e-6)
            for (j in xs.indices) assertEquals(difference.covarianceAt(i, j), d.covarianceAt(i, j), 1e-4)
        }
    }

    @Test fun `slopes of dense sine samples recover the cosine`() {
        val xs = DoubleArray(25) { -3.0 + 6.0 * it / 24 }
        val gp = gp1D(xs, DoubleArray(xs.size) { sin(xs[it]) }, GpKernels.rbf(1.0), noiseStd = 1e-4)
        for (x in doubleArrayOf(-2.0, -0.5, 0.3, 1.7)) {
            val slope = gp.derivative(0, x)
            assertEquals(cos(x), slope.mean, 1e-3)
            assertTrue(slope.std < 1e-2)
        }
    }

    @Test fun `slope observations pin the derivative exactly`() {
        val gp = gp1D(doubleArrayOf(0.0), doubleArrayOf(0.0), GpKernels.rbf(1.0),
            slopes = listOf(GpDerivativeObservation.slope(0.0, 1.0)))
        assertEquals(2, gp.trainingSize)
        val slope = gp.derivative(0, 0.0)
        assertEquals(1.0, slope.mean, 1e-12); assertEquals(0.0, slope.variance, 1e-10)
        assertEquals(0.01, gp.mean(0.01), 1e-5)
        // The analytic posterior after one value and one slope at the origin, unit RBF:
        // the two are independent a priori, so m(x) = x e^{-x²/2}.
        assertEquals(0.5 * exp(-0.125), gp.mean(0.5), 1e-12)
        assertTrue(gp.condition(arrayOf(doubleArrayOf(2.0)), doubleArrayOf(1.0)).derivative(0, 0.0).variance < 1e-10)
    }

    @Test fun `gradient covariance joins axes and slope only fits need dimensions`() {
        val gp = GaussianProcess.fit(emptyArray(), doubleArrayOf(), GpKernels.rbf(doubleArrayOf(0.5, 2.0), 3.0), dimensions = 2,
            derivatives = listOf(GpDerivativeObservation(doubleArrayOf(0.0, 0.0), 0, 2.0, 0.1)))
        val g = gp.gradient(0.0, 0.0)
        assertEquals(2.0 * 12.0 / 12.01, g.mean[0], 1e-9)
        assertEquals(0.0, g.mean[1], 1e-12)
        assertEquals(3.0 / 4.0, g.variance[1], 1e-12)
        assertEquals(0.0, g.covariance[0][1], 1e-12)
    }

    @Test fun `evidence scan prefers the generating period`() {
        val xs = DoubleArray(40) { it * 0.11 }
        val ys = DoubleArray(xs.size) { sin(2 * PI * xs[it] / 0.7) + 0.4 * cos(4 * PI * xs[it] / 0.7) }
        val periods = DoubleArray(41) { 0.7 * 2.0.pow((it - 20) / 40.0) }
        val scan = scanGpEvidence(periods) { p -> gp1D(xs, ys, GpKernels.periodic(p, 0.8), noiseStd = 0.05) }
        assertEquals(0.7, scan.best, 1e-12)
        assertEquals(1.0, scan.posteriorWeights.sum(), 1e-12)
        assertTrue(scan.posteriorWeights.max() > 0.5)
    }

    @Test fun `spread scales a draw away from the mean`() {
        val joint = gp1D(doubleArrayOf(0.0), doubleArrayOf(1.0), noiseStd = 0.2).posterior(doubleArrayOf(-1.0, 0.5, 2.0))
        assertContentEquals(joint.mean, joint.sample(4, 0.0))
        assertContentEquals(joint.sample(4), joint.sample(4, 1.0))
        val far = joint.sample(4, 3.0); val near = joint.sample(4)
        for (i in 0 until joint.size) assertEquals(3 * (near[i] - joint.mean[i]), far[i] - joint.mean[i], 1e-12)
    }

    @Test fun `vector derivatives and velocity constraints`() {
        val ts = arrayOf(doubleArrayOf(0.0), doubleArrayOf(1.0))
        val values = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0))
        val v = GpVectorProcess.fit(ts, values, GpKernels.rbf(0.5), outputCovariance = arrayOf(doubleArrayOf(1.0, 0.3), doubleArrayOf(0.3, 1.0)),
            derivatives = listOf(GpVectorDerivativeObservation(doubleArrayOf(0.0), 0, doubleArrayOf(0.0, 2.0))))
        val d = v.derivativePosterior(arrayOf(doubleArrayOf(0.0), doubleArrayOf(0.5)))
        assertEquals(0.0, d.mean[0][0], 1e-9); assertEquals(2.0, d.mean[0][1], 1e-9)
        assertTrue(d.covarianceAt(0).all { row -> row.all { abs(it) < 1e-9 } })
        assertTrue(abs(d.covarianceAt(1)[0][1]) > 1e-6) // the output correlation reaches the velocities
    }
}

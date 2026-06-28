@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")
package afield.stdlib

import kotlin.math.*
import kotlin.math.cos
import kotlin.random.Random

typealias Matrix = Array<DoubleArray>

data class UKFOptions(
    val alpha: Double = 0.5,
    val beta: Double = 2.0,
    val kappa: Double = 0.0,
    val jitter: Double = 1e-9
) {
    init {
        require(alpha > 0.0) { "alpha must be > 0" }
        require(beta >= 0.0) { "beta must be >= 0" }
        require(jitter > 0.0) { "jitter must be > 0" }
    }
}

data class RougheningOptions(
    /**
     * Usually something small, such as 0.02 to 0.20.
     * The roughening stddev is approximately:
     * factor * stateRange * particleCount^(-1 / stateDimension)
     */
    val factor: Double = 0.08,

    /** Used when all particles have the same coordinate, or when the computed roughening is tiny. */
    val minimumStdDev: Double = 1e-9,

    /**
     * If true, the UKF covariance is inflated by the variance of the roughening noise.
     * This keeps the UKF covariance honest after we perturb its mean.
     */
    val inflateCovariance: Boolean = true
) {
    init {
        require(factor >= 0.0) { "factor must be >= 0" }
        require(minimumStdDev >= 0.0) { "minimumStdDev must be >= 0" }
    }
}

class UnscentedKalmanFilter(
    initialState: DoubleArray,
    initialCovariance: Matrix,
    val options: UKFOptions = UKFOptions()
) {
    var x: DoubleArray = initialState.clone()
        private set

    var p: Matrix = copy(initialCovariance)
        private set

    val dim: Int get() = x.size

    init {
        require(dim in 1..3) { "State dimension must be in 1..3" }
        validateSquare(initialCovariance, dim, "initialCovariance")
    }

    fun predict(
        processNoise: Matrix,
        transition: (DoubleArray) -> DoubleArray
    ) {
        validateSquare(processNoise, dim, "processNoise")

        val sigma = sigmaPoints(x, p)
        val (wm, wc) = weights(dim)

        val propagated = sigma.map { point ->
            transition(point.clone()).also {
                require(it.size == dim) {
                    "transition returned dimension ${it.size}; expected $dim"
                }
            }
        }

        val xPred = weightedMean(propagated, wm, dim)
        val pPred = copy(processNoise)

        for (i in propagated.indices) {
            val dx = subtract(propagated[i], xPred)
            addOuterInPlace(pPred, dx, dx, wc[i])
        }

        x = xPred
        p = symmetrize(pPred)
    }

    fun update(
        measurement: DoubleArray,
        measurementNoise: Matrix,
        observe: (DoubleArray) -> DoubleArray
    ) {
        updateWithLikelihood(measurement, measurementNoise, observe)
    }

    /**
     * Performs a UKF measurement update and returns log p(measurement | predicted state).
     * This likelihood is what the particle filter uses to update particle weights.
     */
    fun updateWithLikelihood(
        measurement: DoubleArray,
        measurementNoise: Matrix,
        observe: (DoubleArray) -> DoubleArray
    ): Double {
        val m = measurement.size
        require(m in 1..3) { "Measurement dimension must be in 1..3" }
        validateSquare(measurementNoise, m, "measurementNoise")

        val sigma = sigmaPoints(x, p)
        val (wm, wc) = weights(dim)

        val predictedMeasurements = sigma.map { point ->
            observe(point.clone()).also {
                require(it.size == m) {
                    "observe returned dimension ${it.size}; expected $m"
                }
            }
        }

        val zPred = weightedMean(predictedMeasurements, wm, m)

        val sRaw = copy(measurementNoise)
        val cross = zeros(dim, m)

        for (i in sigma.indices) {
            val dx = subtract(sigma[i], x)
            val dz = subtract(predictedMeasurements[i], zPred)

            addOuterInPlace(sRaw, dz, dz, wc[i])
            addOuterInPlace(cross, dx, dz, wc[i])
        }

        val s = positiveDefiniteCopyWithJitter(sRaw, options.jitter)
        val innovation = subtract(measurement, zPred)
        val logLikelihood = logGaussianLikelihood(innovation, s)

        val sInv = inverse(s)
        val k = matMul(cross, sInv)

        x = add(x, matVecMul(k, innovation))

        val kskT = matMul(matMul(k, s), transpose(k))
        p = symmetrize(subtract(p, kskT))

        return logLikelihood
    }

    /** Perturbs the UKF mean and optionally inflates covariance by the same variance. */
    fun roughenMean(
        noiseStdDev: DoubleArray,
        random: Random = Random.Default,
        inflateCovariance: Boolean = true
    ) {
        require(noiseStdDev.size == dim) {
            "noiseStdDev dimension ${noiseStdDev.size}; expected $dim"
        }

        val noise = DoubleArray(dim) { i ->
            require(noiseStdDev[i] >= 0.0) { "noiseStdDev[$i] must be >= 0" }
            random.nextGaussian() * noiseStdDev[i]
        }

        x = add(x, noise)

        if (inflateCovariance) {
            val inflated = copy(p)
            for (i in 0 until dim) {
                inflated[i][i] += noiseStdDev[i] * noiseStdDev[i]
            }
            p = symmetrize(inflated)
        }
    }

    fun stateCopy(): DoubleArray = x.clone()

    fun covarianceCopy(): Matrix = copy(p)

    private fun sigmaPoints(mean: DoubleArray, covariance: Matrix): List<DoubleArray> {
        val n = mean.size
        val lambda = options.alpha * options.alpha * (n + options.kappa) - n
        val c = n + lambda

        require(c > 0.0) {
            "n + lambda must be positive. Try increasing alpha or kappa."
        }

        val scaledCov = scale(covariance, c)
        val chol = choleskyWithJitter(scaledCov, options.jitter)

        val points = ArrayList<DoubleArray>(2 * n + 1)
        points.add(mean.clone())

        for (col in 0 until n) {
            val plus = mean.clone()
            val minus = mean.clone()

            for (row in 0 until n) {
                val d = chol[row][col]
                plus[row] += d
                minus[row] -= d
            }

            points.add(plus)
            points.add(minus)
        }

        return points
    }

    private fun weights(n: Int): Pair<DoubleArray, DoubleArray> {
        val lambda = options.alpha * options.alpha * (n + options.kappa) - n
        val c = n + lambda

        require(c > 0.0) {
            "n + lambda must be positive. Try increasing alpha or kappa."
        }

        val count = 2 * n + 1
        val wm = DoubleArray(count)
        val wc = DoubleArray(count)

        wm[0] = lambda / c
        wc[0] = wm[0] + (1.0 - options.alpha * options.alpha + options.beta)

        for (i in 1 until count) {
            wm[i] = 1.0 / (2.0 * c)
            wc[i] = wm[i]
        }

        return wm to wc
    }
}

data class UKFParticle<H>(
    val ukf: UnscentedKalmanFilter,
    var hypothesis: H,
    var logWeight: Double
)

class UKFParticleFilter<H>(
    initialParticles: List<UKFParticle<H>>,
    private val copyHypothesis: (H) -> H = { it }
) {
    var particles: MutableList<UKFParticle<H>> = initialParticles.toMutableList()
        private set

    init {
        require(particles.isNotEmpty()) { "Particle filter needs at least one particle" }
        normalizeLogWeights()
    }

    val size: Int get() = particles.size

    fun predict(
        processNoise: (UKFParticle<H>) -> Matrix,
        transition: (hypothesis: H, state: DoubleArray) -> DoubleArray,
        evolveHypothesis: ((H) -> H)? = null
    ) {
        for (particle in particles) {
            if (evolveHypothesis != null) {
                particle.hypothesis = evolveHypothesis(particle.hypothesis)
            }

            particle.ukf.predict(
                processNoise = processNoise(particle)
            ) { state ->
                transition(particle.hypothesis, state)
            }
        }
    }

    fun update(
        measurement: DoubleArray,
        measurementNoise: (UKFParticle<H>) -> Matrix,
        observe: (hypothesis: H, state: DoubleArray) -> DoubleArray
    ) {
        for (particle in particles) {
            val logLikelihood = particle.ukf.updateWithLikelihood(
                measurement = measurement,
                measurementNoise = measurementNoise(particle)
            ) { state ->
                observe(particle.hypothesis, state)
            }

            particle.logWeight += logLikelihood
        }

        normalizeLogWeights()
    }

    fun weights(): DoubleArray {
        normalizeLogWeights()
        return DoubleArray(particles.size) { i ->
            exp(particles[i].logWeight)
        }
    }

    fun effectiveSampleSize(): Double {
        val w = weights()

        var sumSquares = 0.0
        for (weight in w) {
            sumSquares += weight * weight
        }

        return 1.0 / sumSquares
    }

    /**
     * Returns true if resampling happened. If roughening is supplied, roughening is applied
     * immediately after resampling, using the pre-resampling state spread as the scale reference.
     */
    fun resampleIfNeeded(
        thresholdFraction: Double = 0.5,
        random: Random = Random.Default,
        roughening: RougheningOptions? = null
    ): Boolean {
        require(thresholdFraction in 0.0..1.0)

        val threshold = thresholdFraction * particles.size
        if (effectiveSampleSize() < threshold) {
            resampleSystematic(random, roughening)
            return true
        }

        return false
    }

    fun resampleSystematic(
        random: Random = Random.Default,
        roughening: RougheningOptions? = null
    ) {
        normalizeLogWeights()

        val n = particles.size
        val w = weights()
        val rangesBeforeResampling = stateRanges()

        val newParticles = ArrayList<UKFParticle<H>>(n)

        val step = 1.0 / n
        var u = random.nextDouble() * step

        var index = 0
        var cumulative = w[0]

        repeat(n) {
            while (u > cumulative && index < n - 1) {
                index += 1
                cumulative += w[index]
            }

            val source = particles[index]

            newParticles.add(
                UKFParticle(
                    ukf = UnscentedKalmanFilter(
                        initialState = source.ukf.stateCopy(),
                        initialCovariance = source.ukf.covarianceCopy(),
                        options = source.ukf.options
                    ),
                    hypothesis = copyHypothesis(source.hypothesis),
                    logWeight = -ln(n.toDouble())
                )
            )

            u += step
        }

        particles = newParticles

        if (roughening != null) {
            roughenStateMeans(roughening, random, rangesBeforeResampling)
        }
    }

    /**
     * Roughens only the continuous UKF means. This is useful immediately after resampling,
     * when many particles may be exact clones.
     */
    fun roughenStateMeans(
        options: RougheningOptions = RougheningOptions(),
        random: Random = Random.Default
    ) {
        roughenStateMeans(options, random, stateRanges())
    }

    /** Roughen non-UKF / discrete parameters, if your hypothesis type has mutable-like dynamics. */
    fun roughenHypotheses(
        random: Random = Random.Default,
        roughen: (H, Random) -> H
    ) {
        for (particle in particles) {
            particle.hypothesis = roughen(particle.hypothesis, random)
        }
    }

    fun estimateMean(): DoubleArray {
        val w = weights()
        val dim = particles[0].ukf.dim
        val mean = DoubleArray(dim)

        for (i in particles.indices) {
            val xi = particles[i].ukf.stateCopy()
            for (j in 0 until dim) {
                mean[j] += w[i] * xi[j]
            }
        }

        return mean
    }

    /**
     * Mixture covariance: weighted average of each UKF covariance plus covariance of UKF means.
     */
    fun estimateCovariance(): Matrix {
        val w = weights()
        val mean = estimateMean()
        val dim = mean.size

        val covariance = zeros(dim, dim)

        for (i in particles.indices) {
            val particleMean = particles[i].ukf.stateCopy()
            val particleCovariance = particles[i].ukf.covarianceCopy()
            val delta = subtract(particleMean, mean)

            for (r in 0 until dim) {
                for (c in 0 until dim) {
                    covariance[r][c] += w[i] * particleCovariance[r][c]
                    covariance[r][c] += w[i] * delta[r] * delta[c]
                }
            }
        }

        return symmetrize(covariance)
    }

    private fun roughenStateMeans(
        options: RougheningOptions,
        random: Random,
        ranges: DoubleArray
    ) {
        if (options.factor == 0.0 && options.minimumStdDev == 0.0) return

        val dim = particles[0].ukf.dim
        require(ranges.size == dim)

        val shrink = particles.size.toDouble().pow(-1.0 / dim.toDouble())
        val stdDev = DoubleArray(dim) { i ->
            max(options.minimumStdDev, options.factor * ranges[i] * shrink)
        }

        for (particle in particles) {
            particle.ukf.roughenMean(
                noiseStdDev = stdDev,
                random = random,
                inflateCovariance = options.inflateCovariance
            )
        }
    }

    private fun stateRanges(): DoubleArray {
        val dim = particles[0].ukf.dim
        val minValues = DoubleArray(dim) { Double.POSITIVE_INFINITY }
        val maxValues = DoubleArray(dim) { Double.NEGATIVE_INFINITY }
        val averageCovarianceDiagonal = DoubleArray(dim)

        for (particle in particles) {
            val state = particle.ukf.stateCopy()
            val covariance = particle.ukf.covarianceCopy()

            for (i in 0 until dim) {
                minValues[i] = kotlin.math.min(minValues[i], state[i])
                maxValues[i] = kotlin.math.max(maxValues[i], state[i])
                averageCovarianceDiagonal[i] += covariance[i][i] / particles.size
            }
        }

        return DoubleArray(dim) { i ->
            val empiricalRange = maxValues[i] - minValues[i]
            if (empiricalRange > 0.0) {
                empiricalRange
            } else {
                2.0 * sqrt(max(0.0, averageCovarianceDiagonal[i]))
            }
        }
    }

    private fun normalizeLogWeights() {
        val maxLogWeight = particles.maxOf { it.logWeight }

        if (!isFinite(maxLogWeight)) {
            resetUniformWeights()
            return
        }

        var sum = 0.0
        for (particle in particles) {
            sum += exp(particle.logWeight - maxLogWeight)
        }

        if (!isFinite(sum) || sum <= 0.0) {
            resetUniformWeights()
            return
        }

        val logNormalizer = maxLogWeight + ln(sum)

        for (particle in particles) {
            particle.logWeight -= logNormalizer
        }
    }

    private fun resetUniformWeights() {
        val uniformLogWeight = -ln(particles.size.toDouble())
        for (particle in particles) {
            particle.logWeight = uniformLogWeight
        }
    }

    private fun isFinite(x: Double): Boolean {
        return !x.isNaN() &&
                x != Double.POSITIVE_INFINITY &&
                x != Double.NEGATIVE_INFINITY
    }
}

/* ---------- Small matrix and probability helpers ---------- */

private fun zeros(rows: Int, cols: Int): Matrix =
    Array(rows) { DoubleArray(cols) }

private fun copy(a: Matrix): Matrix =
    Array(a.size) { r -> a[r].clone() }

private fun validateSquare(a: Matrix, n: Int, name: String) {
    require(a.size == n) { "$name must have $n rows" }
    for (row in a) {
        require(row.size == n) { "$name must be ${n}x$n" }
    }
}

private fun weightedMean(points: List<DoubleArray>, weights: DoubleArray, dim: Int): DoubleArray {
    val out = DoubleArray(dim)

    for (i in points.indices) {
        for (j in 0 until dim) {
            out[j] += weights[i] * points[i][j]
        }
    }

    return out
}

private fun add(a: DoubleArray, b: DoubleArray): DoubleArray {
    require(a.size == b.size)
    return DoubleArray(a.size) { i -> a[i] + b[i] }
}

private fun subtract(a: DoubleArray, b: DoubleArray): DoubleArray {
    require(a.size == b.size)
    return DoubleArray(a.size) { i -> a[i] - b[i] }
}

private fun subtract(a: Matrix, b: Matrix): Matrix {
    require(a.size == b.size)
    require(a[0].size == b[0].size)

    return Array(a.size) { r ->
        DoubleArray(a[r].size) { c ->
            a[r][c] - b[r][c]
        }
    }
}

private fun scale(a: Matrix, s: Double): Matrix =
    Array(a.size) { r ->
        DoubleArray(a[r].size) { c ->
            a[r][c] * s
        }
    }

private fun addOuterInPlace(target: Matrix, a: DoubleArray, b: DoubleArray, weight: Double) {
    require(target.size == a.size)
    require(target[0].size == b.size)

    for (i in a.indices) {
        for (j in b.indices) {
            target[i][j] += weight * a[i] * b[j]
        }
    }
}

private fun matVecMul(a: Matrix, x: DoubleArray): DoubleArray {
    require(a.isNotEmpty())
    require(a[0].size == x.size)

    return DoubleArray(a.size) { r ->
        var sum = 0.0
        for (c in x.indices) {
            sum += a[r][c] * x[c]
        }
        sum
    }
}

private fun matMul(a: Matrix, b: Matrix): Matrix {
    require(a.isNotEmpty() && b.isNotEmpty())
    require(a[0].size == b.size)

    val rows = a.size
    val mid = b.size
    val cols = b[0].size

    val out = zeros(rows, cols)

    for (r in 0 until rows) {
        for (c in 0 until cols) {
            var sum = 0.0
            for (k in 0 until mid) {
                sum += a[r][k] * b[k][c]
            }
            out[r][c] = sum
        }
    }

    return out
}

private fun transpose(a: Matrix): Matrix {
    val rows = a.size
    val cols = a[0].size

    return Array(cols) { c ->
        DoubleArray(rows) { r ->
            a[r][c]
        }
    }
}

private fun symmetrize(a: Matrix): Matrix {
    require(a.size == a[0].size)

    val n = a.size
    val out = copy(a)

    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val avg = 0.5 * (out[i][j] + out[j][i])
            out[i][j] = avg
            out[j][i] = avg
        }
    }

    return out
}

private fun positiveDefiniteCopyWithJitter(a: Matrix, jitter: Double): Matrix {
    require(a.size == a[0].size)

    var j = 0.0

    repeat(12) {
        val adjusted = symmetrize(a)

        if (j > 0.0) {
            for (i in adjusted.indices) {
                adjusted[i][i] += j
            }
        }

        try {
            cholesky(adjusted)
            return adjusted
        } catch (_: IllegalArgumentException) {
            j = if (j == 0.0) jitter else j * 10.0
        }
    }

    throw IllegalArgumentException("Matrix is not positive definite, even after jitter")
}

private fun choleskyWithJitter(a: Matrix, jitter: Double): Matrix {
    return cholesky(positiveDefiniteCopyWithJitter(a, jitter))
}

private fun cholesky(a: Matrix): Matrix {
    val n = a.size
    val l = zeros(n, n)

    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = a[i][j]

            for (k in 0 until j) {
                sum -= l[i][k] * l[j][k]
            }

            if (i == j) {
                require(sum > 0.0) { "Matrix is not positive definite" }
                l[i][j] = sqrt(sum)
            } else {
                l[i][j] = sum / l[j][j]
            }
        }
    }

    return l
}

private fun inverse(a: Matrix): Matrix {
    require(a.size == a[0].size)

    val n = a.size
    val aug = Array(n) { r ->
        DoubleArray(2 * n) { c ->
            when {
                c < n -> a[r][c]
                c - n == r -> 1.0
                else -> 0.0
            }
        }
    }

    for (col in 0 until n) {
        var pivotRow = col
        var pivotAbs = abs(aug[col][col])

        for (r in col + 1 until n) {
            val candidate = abs(aug[r][col])
            if (candidate > pivotAbs) {
                pivotAbs = candidate
                pivotRow = r
            }
        }

        require(pivotAbs > 1e-12) { "Matrix is singular or nearly singular" }

        if (pivotRow != col) {
            val tmp = aug[col]
            aug[col] = aug[pivotRow]
            aug[pivotRow] = tmp
        }

        val pivot = aug[col][col]

        for (c in 0 until 2 * n) {
            aug[col][c] /= pivot
        }

        for (r in 0 until n) {
            if (r == col) continue

            val factor = aug[r][col]
            for (c in 0 until 2 * n) {
                aug[r][c] -= factor * aug[col][c]
            }
        }
    }

    return Array(n) { r ->
        DoubleArray(n) { c ->
            aug[r][c + n]
        }
    }
}

private fun determinant(a: Matrix): Double {
    require(a.size == a[0].size)

    return when (a.size) {
        1 -> a[0][0]

        2 -> {
            a[0][0] * a[1][1] -
                    a[0][1] * a[1][0]
        }

        3 -> {
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                    a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                    a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        }

        else -> error("Only dimensions 1..3 are supported")
    }
}

private fun logGaussianLikelihood(residual: DoubleArray, covariance: Matrix): Double {
    val d = residual.size
    require(d in 1..3)
    validateSquare(covariance, d, "covariance")

    val inv = inverse(covariance)
    val solved = matVecMul(inv, residual)

    var mahalanobis = 0.0
    for (i in residual.indices) {
        mahalanobis += residual[i] * solved[i]
    }

    val det = determinant(covariance)
    require(det > 0.0) { "Covariance determinant must be positive" }

    return -0.5 * (d * ln(2.0 * PI) + ln(det) + mahalanobis)
}

private fun Random.nextGaussian(): Double {
    var u1 = nextDouble()
    while (u1 <= Double.MIN_VALUE) {
        u1 = nextDouble()
    }

    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}

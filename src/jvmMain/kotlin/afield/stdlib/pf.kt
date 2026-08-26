@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")
package afield.stdlib

import kotlin.math.*
import kotlin.random.Random

/**
 * An unscented Kalman filter, and a particle filter whose particles each carry one.
 *
 * [UnscentedKalmanFilter] keeps a mean and covariance and takes the transition and observation
 * models as lambdas; [UnscentedKalmanFilter.updateWithLikelihood] returns the log measurement
 * likelihood, which is exactly what [UKFParticleFilter] accumulates into each particle's log
 * weight. A [UKFParticle] holds a UKF for the continuous state plus a hypothesis of your own
 * type `H` for the discrete or non-Gaussian part. [UKFParticleFilter.resampleIfNeeded] does
 * systematic resampling once the effective sample size drops below a fraction of the particle
 * count, and can immediately roughen the resampled means ([RougheningOptions]) so exact clones
 * separate again.
 *
 * State and measurement dimensions are restricted to 1..3 — the private determinant helper only
 * covers those sizes and everything `require`s it. The linear algebra (`Matrix` is just
 * `Array<DoubleArray>`; Cholesky with escalating jitter, Gauss–Jordan inverse) is private to this
 * file, has no external dependency, and allocates fresh arrays on every step.
 */

typealias Matrix = Array<DoubleArray>

/**
 * The three sigma-point tuning constants of the unscented transform, plus the numerical fudge factor.
 *
 * You can usually leave these alone. [alpha] sets how far the sigma points spread from the mean — small
 * keeps them close, which is the right instinct for a strongly nonlinear model; [kappa] is the secondary
 * scaling, conventionally 0; [beta] weights the mean point's contribution to the covariance, and 2.0 is
 * optimal for a genuinely Gaussian state, which is the usual assumption. [jitter] is the diagonal nudge
 * added when a Cholesky factorisation fails, not a modelling parameter.
 *
 * @throws IllegalArgumentException unless `alpha > 0`, `beta >= 0`, `jitter > 0` and `kappa` is finite.
 * `kappa` cannot be fully checked here — the sigma-point scaling needs `n + kappa > 0` and the state
 * dimension `n` is not known until [UnscentedKalmanFilter] is built — so a sufficiently negative one is
 * caught there instead, with a message that names the arithmetic.
 */
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
        // A NaN kappa passes every inequality below it and then propagates silently through every
        // sigma point into the estimate, which is the hardest possible way to notice a typo.
        require(kappa.isFinite()) { "kappa must be finite, found $kappa" }
    }
}

/**
 * How hard to jiggle particles apart immediately after resampling — the cure for a filter that has
 * collapsed, without saying so, onto a handful of identical clones.
 *
 * Systematic resampling copies good particles, so straight after it several particles are numerically
 * *identical* and will stay identical forever, wasting the ensemble. Roughening adds a small independent
 * Gaussian nudge to each resampled mean, scaled from the spread the particles actually have and shrinking
 * as the particle count grows (`factor · stateRange · N^(-1/dim)`), so a big ensemble is perturbed less than
 * a small one.
 *
 * @throws IllegalArgumentException unless `factor >= 0` and `minimumStdDev >= 0`
 */
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
     * This keeps the UKF covariance consistent after we perturb its mean.
     */
    val inflateCovariance: Boolean = true
) {
    init {
        require(factor >= 0.0) { "factor must be >= 0" }
        require(minimumStdDev >= 0.0) { "minimumStdDev must be >= 0" }
    }
}

/**
 * An unscented Kalman filter: it tracks a quantity you cannot measure directly, keeping a best guess [x] and
 * an explicit account [p] of how unsure it is.
 *
 * Unlike a plain Kalman filter it needs no derivatives — you hand [predict] and [update] ordinary lambdas
 * for "how does the state evolve" and "what would I measure if the state were this", and it probes them at a
 * spread of sigma points to work out the rest. That makes it the right tool when the model is nonlinear but
 * still roughly unimodal; when it is genuinely multi-modal, wrap it in a [UKFParticleFilter].
 *
 * The cycle is [predict] once per time step, then [update] (or [updateWithLikelihood]) whenever a measurement
 * arrives — predicting without updating is fine and simply widens [p]. Both mutate the filter in place; [x]
 * and [p] are read-only from outside, and [stateCopy]/[covarianceCopy] are how you take a snapshot that will
 * not change under you.
 *
 * **State dimension is limited to 1..3**, checked on construction: the determinant helper in this file only
 * covers those sizes. The initial covariance is copied, so the array you pass is not retained.
 *
 * @throws IllegalArgumentException if the state dimension is outside 1..3, or the covariance is not square
 * and of that dimension
 */
class UnscentedKalmanFilter(
    initialState: DoubleArray,
    initialCovariance: Matrix,
    val options: UKFOptions = UKFOptions()
) {
    /** The current best estimate of the state. Replaced wholesale by each [predict] and [update]. */
    var x: DoubleArray = initialState.clone()
        private set

    /**
     * The current state covariance — how unsure the filter is, and in which directions the uncertainty is
     * correlated. Grows on [predict], shrinks on [update].
     */
    var p: Matrix = copy(initialCovariance)
        private set

    /** The state dimension, fixed at construction and always in 1..3. */
    val dim: Int get() = x.size

    init {
        require(dim in 1..3) { "State dimension must be in 1..3" }
        validateSquare(initialCovariance, dim, "initialCovariance")
    }

    /**
     * Advances the estimate one time step through [transition], widening the covariance by [processNoise].
     *
     * [transition] is called once per sigma point (`2·dim + 1` times) and **must return a fresh array of the
     * same dimension** — it is handed a clone, so mutating its argument is harmless, but returning the wrong
     * size throws. It must be a pure function of the state: it is evaluated several times per step and the
     * order is not something to depend on.
     *
     * [processNoise] is how much you believe the world changes on its own between steps, in state units
     * squared. Too small and the filter becomes overconfident and stops believing new measurements; too large
     * and it chases noise. It is the main thing to tune.
     *
     * @throws IllegalArgumentException if [processNoise] is not `dim × dim`, or [transition] returns the wrong
     * dimension
     */
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

    /**
     * Folds one measurement into the estimate, pulling [x] toward what was observed and shrinking [p].
     *
     * [observe] answers "if the state were this, what would the sensor read" — it is called once per sigma
     * point and must return an array of the measurement's dimension, which need not match the state's. The
     * measurement dimension is also limited to 1..3.
     *
     * [measurementNoise] is how much you distrust the sensor, in measurement units squared: raise it and the
     * filter leans on its model, lower it and it follows the data. [updateWithLikelihood] is this same call
     * with the log likelihood returned, which is what a particle filter needs.
     *
     * @throws IllegalArgumentException if the measurement dimension is outside 1..3, [measurementNoise] is not
     * square of that dimension, or [observe] returns the wrong dimension
     */
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

    /** A snapshot of the current estimate that will not change when the filter next steps. */
    fun stateCopy(): DoubleArray = x.clone()

    /** A snapshot of the current covariance, deep-copied row by row. */
    fun covarianceCopy(): Matrix = copy(p)

    private fun sigmaPoints(mean: DoubleArray, covariance: Matrix): List<DoubleArray> {
        val n = mean.size
        val lambda = options.alpha * options.alpha * (n + options.kappa) - n
        val c = n + lambda

        require(c > 0.0) {
            "sigma-point scaling n + lambda must be positive, but alpha^2 * (n + kappa) = $c for " +
                "n = $n, alpha = ${options.alpha}, kappa = ${options.kappa}. Try increasing alpha or kappa."
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
            "sigma-point scaling n + lambda must be positive, but alpha^2 * (n + kappa) = $c for " +
                "n = $n, alpha = ${options.alpha}, kappa = ${options.kappa}. Try increasing alpha or kappa."
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

/**
 * One hypothesis in a [UKFParticleFilter]: a whole [UnscentedKalmanFilter] for the continuous part of the
 * state, a value of your own type [H] for the discrete part, and how much the filter currently believes it.
 *
 * The split is the point. Put in [hypothesis] what a Kalman filter cannot represent — which target this
 * measurement belongs to, which of three gaits the walker is in, whether the sensor is currently occluded —
 * and leave the smooth numbers to [ukf]. Each particle carries its own filter, so the estimate is a mixture
 * of Gaussians rather than one.
 *
 * [logWeight] is a **log** weight, so weights multiply by addition and never underflow to zero the way a long
 * run of raw likelihoods would; the filter normalises them, so only differences between particles matter, not
 * the absolute value. Both it and [hypothesis] are mutable and are updated in place as the filter runs.
 */
data class UKFParticle<H>(
    val ukf: UnscentedKalmanFilter,
    var hypothesis: H,
    var logWeight: Double
)

/**
 * A particle filter whose particles each carry an [UnscentedKalmanFilter] — for tracking something whose
 * state is partly smooth and partly a discrete guess that might be wrong.
 *
 * A plain UKF has exactly one answer and cannot hold "either the target turned left, or it stopped". This can:
 * each particle pursues its own hypothesis, [updateWithLikelihood] scores every one against the same
 * measurement, and the ones whose story fits keep their weight while the others fade. Call [predict] then an
 * update each step, and [resampleIfNeeded] after, or the whole ensemble's weight ends up on one particle.
 *
 * [copyHypothesis] is how a hypothesis is duplicated at resampling time; it defaults to handing back the same
 * reference, which is correct for an immutable [H] and **wrong, and silently so, for a mutable one** — two particles
 * would then share and clobber each other's hypothesis. Pass a real copy function if [H] is mutable.
 *
 * @throws IllegalArgumentException if constructed with no particles
 */
class UKFParticleFilter<H>(
    initialParticles: List<UKFParticle<H>>,
    private val copyHypothesis: (H) -> H = { it }
) {
    /**
     * The live ensemble, in no meaningful order. Read it to summarise the estimate; the list itself is replaced
     * by resampling, and the particles in it are mutated in place by every step.
     */
    var particles: MutableList<UKFParticle<H>> = initialParticles.toMutableList()
        private set

    init {
        require(particles.isNotEmpty()) { "Particle filter needs at least one particle" }
        normalizeLogWeights()
    }

    /** How many particles the ensemble holds. Constant unless you resample, which preserves it. */
    val size: Int get() = particles.size

    /**
     * Advances every particle one time step, each through its own hypothesis.
     *
     * [transition] and [processNoise] both see the particle's hypothesis, so different particles can genuinely
     * follow different dynamics — that is the whole reason to use this over a single UKF. Weights are untouched
     * here; prediction alone cannot tell you which hypothesis is right.
     *
     * [evolveHypothesis], if given, runs **before** the state step and is where a discrete hypothesis changes —
     * a mode switching, an age counter ticking, a random jump. Return a new value rather than mutating, since
     * a mutable hypothesis may be shared with other particles after resampling.
     */
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

    /**
     * Scores every particle against the same measurement: each one's own UKF absorbs it, and its log weight
     * gains that particle's log likelihood — so hypotheses that predicted the measurement well gain ground on
     * those that did not.
     *
     * Both lambdas receive the particle's [UKFParticle.hypothesis], which is the whole point: a particle
     * believing the target is occluded can answer with a different observation model, or a wider noise, than one
     * that does not. Weights are renormalised before returning, so they always sum to 1 in the linear domain.
     *
     * This is where the ensemble degenerates over time — follow it with [resampleIfNeeded].
     */
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

    /**
     * The particles' weights as ordinary probabilities summing to 1, in the same order as [particles] — for
     * taking a weighted mean of the ensemble, or for drawing it.
     *
     * Renormalises as a side effect. Each call allocates and exponentiates, so hold the result rather than
     * calling it per particle.
     */
    fun weights(): DoubleArray {
        normalizeLogWeights()
        return DoubleArray(particles.size) { i ->
            exp(particles[i].logWeight)
        }
    }

    /**
     * How many particles are *effectively* still contributing: `1 / Σw²`, which is the particle count when the
     * weights are equal and approaches 1 as one particle takes over.
     *
     * The health reading for the filter. Watch it fall after a few updates — that fall is why resampling exists,
     * and [resampleIfNeeded] compares exactly this against a fraction of the population.
     */
    fun effectiveSampleSize(): Double {
        val w = weights()

        var sumSquares = 0.0
        for (weight in w) {
            sumSquares += weight * weight
        }

        return 1.0 / sumSquares
    }

    /**
     * Resamples only if the ensemble has degenerated — [effectiveSampleSize] below [thresholdFraction] of the
     * particle count — and reports whether it did.
     *
     * The call to make once per step after updating. Resampling every step throws away diversity for no reason;
     * never resampling ends with all the weight on one particle. The 0.5 default means "when half the ensemble
     * has effectively stopped counting".
     *
     * Supply [roughening] and the resampled particles are immediately jiggled apart, scaled from the spread the
     * particles had **before** resampling — without it, duplicated particles stay numerically identical forever.
     *
     * @throws IllegalArgumentException if [thresholdFraction] is outside `0..1`
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

    /**
     * Resamples unconditionally by systematic resampling: one uniform draw, then `n` evenly spaced strides
     * through the cumulative weights, so a particle with weight `w` gets about `n·w` copies.
     *
     * Lower variance than drawing `n` independent samples, and only one random number per resample.
     * [resampleIfNeeded] is normally what you want; call this directly when you have your own trigger.
     *
     * Every surviving particle gets a **fresh** [UnscentedKalmanFilter] built from a copy of the source's state
     * and covariance, and its hypothesis goes through `copyHypothesis` — so a mutable [H] with the default
     * identity copier ends up shared between clones. All weights are reset to uniform.
     */
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

    /**
     * The filter's single best answer: every particle's state mean, averaged by weight.
     *
     * Worth remembering what this throws away — the ensemble is a *mixture*, and if two hypotheses disagree the
     * weighted mean sits between them, at a value no particle believes. Check [effectiveSampleSize] or look at
     * the spread in [estimateCovariance] before treating this as the answer.
     *
     * Assumes every particle's UKF has the same state dimension, taking it from the first.
     */
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
     * The uncertainty of the whole ensemble: the weighted average of the particles' own covariances **plus** the
     * scatter of their means about [estimateMean].
     *
     * That second term is what makes this correct — it is how disagreement *between* hypotheses shows up as
     * uncertainty rather than vanishing. A filter whose particles are individually confident but point in
     * different directions reports a large covariance here, which is exactly right.
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

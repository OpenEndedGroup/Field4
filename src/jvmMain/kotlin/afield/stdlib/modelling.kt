@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.stdlib

import afield.Runloop
import java.util.*
import kotlin.math.*

private fun clamp(x: Double, lo: Double, hi: Double): Double =
    when {
        x < lo -> lo
        x > hi -> hi
        else -> x
    }

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun add(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(a.size) { i -> a[i] + b[i] }
private fun sub(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(a.size) { i -> a[i] - b[i] }
private fun scale(a: DoubleArray, k: Double): DoubleArray = DoubleArray(a.size) { i -> a[i] * k }

private fun clipInPlace(x: DoubleArray, lo: DoubleArray, hi: DoubleArray) {
    for (i in x.indices) x[i] = clamp(x[i], lo[i], hi[i])
}

private fun rateLimit(delta: DoubleArray, maxStep: DoubleArray): DoubleArray =
    DoubleArray(delta.size) { i -> clamp(delta[i], -maxStep[i], maxStep[i]) }

private fun matVec(m: Array<DoubleArray>, x: DoubleArray): DoubleArray =
    DoubleArray(m.size) { i -> dot(m[i], x) }

private fun transpose(m: Array<DoubleArray>): Array<DoubleArray> {
    val rows = m.size
    val cols = m[0].size
    return Array(cols) { j -> DoubleArray(rows) { i -> m[i][j] } }
}

private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val rows = a.size
    val inner = a[0].size
    val cols = b[0].size
    return Array(rows) { i ->
        DoubleArray(cols) { j ->
            var s = 0.0
            for (k in 0 until inner) s += a[i][k] * b[k][j]
            s
        }
    }
}

private fun identity(n: Int, scale: Double = 1.0): Array<DoubleArray> =
    Array(n) { i -> DoubleArray(n) { j -> if (i == j) scale else 0.0 } }

private fun addMat(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
    Array(a.size) { i -> DoubleArray(a[0].size) { j -> a[i][j] + b[i][j] } }

private fun inverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
    val n = matrix.size
    require(matrix.all { it.size == n }) { "Matrix must be square" }

    val aug = Array(n) { i ->
        DoubleArray(2 * n) { j ->
            when {
                j < n -> matrix[i][j]
                j - n == i -> 1.0
                else -> 0.0
            }
        }
    }

    for (col in 0 until n) {
        var pivotRow = col
        var pivotAbs = abs(aug[col][col])
        for (r in (col + 1) until n) {
            val v = abs(aug[r][col])
            if (v > pivotAbs) {
                pivotAbs = v
                pivotRow = r
            }
        }
        require(pivotAbs > 1e-12) { "Matrix is singular or ill-conditioned" }

        if (pivotRow != col) {
            val tmp = aug[col]
            aug[col] = aug[pivotRow]
            aug[pivotRow] = tmp
        }

        val pivot = aug[col][col]
        for (j in 0 until 2 * n) aug[col][j] /= pivot

        for (r in 0 until n) {
            if (r == col) continue
            val factor = aug[r][col]
            if (factor == 0.0) continue
            for (j in 0 until 2 * n) aug[r][j] -= factor * aug[col][j]
        }
    }

    return Array(n) { i -> DoubleArray(n) { j -> aug[i][j + n] } }
}

private fun formatted(v: DoubleArray): String =
    v.joinToString(prefix = "[", postfix = "]", separator = ", ") { "%.4f".format(it) }

private fun quantile(sorted: DoubleArray, p: Double): Double {
    val idx = p * (sorted.size - 1)
    val i0 = floor(idx).toInt()
    val i1 = ceil(idx).toInt()
    if (i0 == i1) return sorted[i0]
    val t = idx - i0
    return lerp(sorted[i0], sorted[i1], t)
}

private fun estimateControlStep(
    jacobian: Array<DoubleArray>,
    error: DoubleArray,
    tikhonov: Double,
    gain: Double
): DoubleArray {
    val jt = transpose(jacobian)
    val jtj = matMul(jt, jacobian)
    val reg = addMat(jtj, identity(jtj.size, tikhonov))
    val inv = inverse(reg)
    val jte = matVec(jt, error)
    return scale(matVec(inv, jte), -gain)
}

private class GaussianRng(seed: Long = 0L) {
    private val random = Random(seed)
    private var hasSpare = false
    private var spare = 0.0

    fun nextGaussian(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        var u: Double
        var v: Double
        var s: Double
        do {
            u = 2.0 * random.nextDouble() - 1.0
            v = 2.0 * random.nextDouble() - 1.0
            s = u * u + v * v
        } while (s >= 1.0 || s <= 1e-12)
        val mul = sqrt(-2.0 * ln(s) / s)
        spare = v * mul
        hasSpare = true
        return u * mul
    }
}

private class RlsRow(
    private val dim: Int,
    private val forgetting: Double,
    initialCovariance: Double
) {
    val theta = DoubleArray(dim)
    private val p = identity(dim, initialCovariance)

    fun predict(phi: DoubleArray): Double = dot(theta, phi)

    fun update(phi: DoubleArray, target: Double) {
        val pPhi = matVec(p, phi)
        val denom = forgetting + dot(phi, pPhi)
        if (denom < 1e-12) return

        val gain = DoubleArray(dim) { i -> pPhi[i] / denom }
        val error = target - dot(theta, phi)
        for (i in 0 until dim) theta[i] += gain[i] * error

        val phiTP = DoubleArray(dim) { j ->
            var s = 0.0
            for (k in 0 until dim) s += phi[k] * p[k][j]
            s
        }

        val newP = Array(dim) { DoubleArray(dim) }
        for (i in 0 until dim) {
            for (j in 0 until dim) {
                val updated = (p[i][j] - gain[i] * phiTP[j]) / forgetting
                newP[i][j] = clamp(updated, -1.0e6, 1.0e6)
            }
        }

        for (i in 0 until dim) {
            for (j in 0 until dim) p[i][j] = newP[i][j]
        }
    }

    fun averageVariance(skipBias: Boolean = false): Double {
        val start = if (skipBias) 1 else 0
        var s = 0.0
        var n = 0
        for (i in start until dim) {
            s += p[i][i]
            n += 1
        }
        return s / max(1, n)
    }
}

interface FeatureExtractor {
    val featureCount: Int
    fun errorFromSamples(samples: DoubleArray): DoubleArray
}

class UniformQuantileFeatureExtractor(
    private val targetQuantiles: DoubleArray = doubleArrayOf(0.1, 0.3, 0.5, 0.7, 0.9)
) : FeatureExtractor {
    override val featureCount: Int = targetQuantiles.size

    override fun errorFromSamples(samples: DoubleArray): DoubleArray {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        val sorted = samples.copyOf()
        sorted.sort()
        return DoubleArray(targetQuantiles.size) { i ->
            quantile(sorted, targetQuantiles[i]) - targetQuantiles[i]
        }
    }
}

data class ControllerConfig(
    val parameterNames: List<String> = listOf("offset", "logContrast", "logGamma", "cubic", "skew"),
    val paramMin: DoubleArray = doubleArrayOf(-0.35, -1.2, -1.1, -1.2, -1.2),
    val paramMax: DoubleArray = doubleArrayOf(+0.35, +1.2, +1.1, +1.2, +1.2),
    val initialParams: DoubleArray = DoubleArray(5),
    val maxStep: DoubleArray = doubleArrayOf(0.0030, 0.0100, 0.0080, 0.0100, 0.0100),
    val ditherAmplitude: DoubleArray = doubleArrayOf(0.0035, 0.0100, 0.0080, 0.0100, 0.0100),
    val ditherOmega: DoubleArray = doubleArrayOf(0.070, 0.111, 0.091, 0.133, 0.053),
    val ditherPhase: DoubleArray = doubleArrayOf(0.0, 1.4, 2.2, 0.7, 2.8),
    val forgetting: Double = 0.997,
    val initialCovariance: Double = 20.0,
    val gainBase: Double = 0.45,
    val tikhonov: Double = 0.18,
    val emaAlpha: Double = 0.10,
    val clipPenalty: Double = 0.25,
    val observationDelayFrames: Int = 3,
    val covarianceConfidenceScale: Double = 0.06,
    val residualConfidenceScale: Double = 2.0,
    val minConfidence: Double = 0.05,
    val boundarySoftZone: Double = 0.15
) {
    init {
        val n = parameterNames.size
        require(n > 0) { "At least one control parameter is required" }
        require(paramMin.size == n) { "paramMin size mismatch" }
        require(paramMax.size == n) { "paramMax size mismatch" }
        require(initialParams.size == n) { "initialParams size mismatch" }
        require(maxStep.size == n) { "maxStep size mismatch" }
        require(ditherAmplitude.size == n) { "ditherAmplitude size mismatch" }
        require(ditherOmega.size == n) { "ditherOmega size mismatch" }
        require(ditherPhase.size == n) { "ditherPhase size mismatch" }
        require(observationDelayFrames >= 1) {
            "observationDelayFrames is the number of pushes between issuing a command and seeing its effect; use 1 or greater"
        }
        for (i in 0 until n) {
            require(paramMin[i] < paramMax[i]) { "Invalid parameter bounds at index $i" }
            require(initialParams[i] in paramMin[i]..paramMax[i]) { "Initial parameter $i is out of bounds" }
        }
        require(forgetting in 0.9..1.0) { "forgetting should usually be near 1.0" }
        require(emaAlpha in 0.0..1.0) { "emaAlpha must be in [0, 1]" }
        require(boundarySoftZone > 0.0) { "boundarySoftZone must be positive" }
    }

    val parameterCount: Int get() = parameterNames.size
}

data class ControllerDiagnostics(
    val pushIndex: Int,
    val learnedFromCommandIndex: Int,
    val rawFeatureError: DoubleArray,
    val filteredFeatureError: DoubleArray,
    val objective: Double,
    val clipRate: Double,
    val confidence: Double,
    val residualRatio: Double,
    val averageVariance: Double,
    val pairedAppliedParams: DoubleArray
)

data class ControlOutput(
    val nominalParams: DoubleArray,
    val appliedParams: DoubleArray,
    val diagnostics: ControllerDiagnostics
)

fun ControlOutput.params(useNominalParams: Boolean = false): DoubleArray =
    if (useNominalParams) nominalParams.copyOf() else appliedParams.copyOf()

fun ControlOutput.namedAppliedParams(parameterNames: List<String>): Map<String, Double> {
    require(parameterNames.size == appliedParams.size) {
        "Expected ${appliedParams.size} parameter names, got ${parameterNames.size}"
    }
    return LinkedHashMap<String, Double>(parameterNames.size).also { named ->
        parameterNames.indices.forEach { index ->
            named[parameterNames[index]] = appliedParams[index]
        }
    }
}

fun ControlOutput.namedNominalParams(parameterNames: List<String>): Map<String, Double> {
    require(parameterNames.size == nominalParams.size) {
        "Expected ${nominalParams.size} parameter names, got ${parameterNames.size}"
    }
    return LinkedHashMap<String, Double>(parameterNames.size).also { named ->
        parameterNames.indices.forEach { index ->
            named[parameterNames[index]] = nominalParams[index]
        }
    }
}

data class ScalarSampleBatch(
    val samples: DoubleArray,
    val clipRate: Double = 0.0
)

private data class CommandRecord(
    val commandIndex: Int,
    val nominalParams: DoubleArray,
    val appliedParams: DoubleArray
)

class AdaptiveDistributionController(
    private val config: ControllerConfig,
    private val featureExtractor: FeatureExtractor
) {
    private val modelParameterCount = config.parameterCount + 1
    private val rows = Array(featureExtractor.featureCount) {
        RlsRow(modelParameterCount, config.forgetting, config.initialCovariance)
    }
    private val inFlightCommands = ArrayDeque<CommandRecord>()

    private var nominalParams = config.initialParams.copyOf()
    private var filteredError = DoubleArray(featureExtractor.featureCount)
    private var residualEma = 0.0
    private var featureEnergyEma = 1e-4
    private var pushIndex = 0
    private var nextCommandIndex = 0

    init {
        repeat(config.observationDelayFrames) {
            val initial = config.initialParams.copyOf()
            inFlightCommands.addLast(
                CommandRecord(
                    commandIndex = -config.observationDelayFrames + it,
                    nominalParams = initial.copyOf(),
                    appliedParams = initial.copyOf()
                )
            )
        }
    }

    fun currentNominalParams(): DoubleArray = nominalParams.copyOf()

    val parameterNames: List<String>
        get() = config.parameterNames

    val parameterCount: Int
        get() = config.parameterCount

    fun pushSamples(samples: DoubleArray, clipRate: Double = 0.0): ControlOutput {
        val featureError = featureExtractor.errorFromSamples(samples)
        return pushFeatureError(featureError, clipRate)
    }

    fun pushFeatureError(rawFeatureError: DoubleArray, clipRate: Double = 0.0): ControlOutput {
        require(rawFeatureError.size == featureExtractor.featureCount) {
            "Expected ${featureExtractor.featureCount} features, got ${rawFeatureError.size}"
        }

        val paired = inFlightCommands.removeFirst()

        for (i in rawFeatureError.indices) {
            filteredError[i] = lerp(filteredError[i], rawFeatureError[i], config.emaAlpha)
        }

        val regressor = DoubleArray(modelParameterCount) { i ->
            if (i == 0) 1.0 else paired.appliedParams[i - 1]
        }

        val predicted = DoubleArray(featureExtractor.featureCount) { k -> rows[k].predict(regressor) }
        val innovation = sub(filteredError, predicted)

        residualEma = lerp(residualEma, dot(innovation, innovation), 0.03)
        featureEnergyEma = lerp(featureEnergyEma, dot(filteredError, filteredError), 0.03)
        val residualRatio = residualEma / (1e-6 + featureEnergyEma)

        for (k in 0 until featureExtractor.featureCount) {
            rows[k].update(regressor, filteredError[k])
        }

        val averageVariance = rows.map { it.averageVariance(skipBias = true) }.average()
        val covarianceConfidence = 1.0 / (1.0 + config.covarianceConfidenceScale * averageVariance)
        val residualConfidence = 1.0 / (1.0 + config.residualConfidenceScale * residualRatio)
        val confidence = clamp(covarianceConfidence * residualConfidence, config.minConfidence, 1.0)

        val jacobian = Array(featureExtractor.featureCount) { k ->
            rows[k].theta.copyOfRange(1, modelParameterCount)
        }

        val desiredStep = estimateControlStep(
            jacobian = jacobian,
            error = filteredError,
            tikhonov = config.tikhonov,
            gain = config.gainBase * confidence
        )
        val softenedStep = softenNearBounds(desiredStep)
        val boundedStep = rateLimit(softenedStep, config.maxStep)

        nominalParams = add(nominalParams, boundedStep)
        clipInPlace(nominalParams, config.paramMin, config.paramMax)

        val appliedParams = add(nominalParams, currentDither(pushIndex))
        clipInPlace(appliedParams, config.paramMin, config.paramMax)

        val command = CommandRecord(
            commandIndex = nextCommandIndex,
            nominalParams = nominalParams.copyOf(),
            appliedParams = appliedParams.copyOf()
        )
        inFlightCommands.addLast(command)

        val objective = 0.5 * dot(filteredError, filteredError) + config.clipPenalty * clipRate * clipRate
        val diagnostics = ControllerDiagnostics(
            pushIndex = pushIndex,
            learnedFromCommandIndex = paired.commandIndex,
            rawFeatureError = rawFeatureError.copyOf(),
            filteredFeatureError = filteredError.copyOf(),
            objective = objective,
            clipRate = clipRate,
            confidence = confidence,
            residualRatio = residualRatio,
            averageVariance = averageVariance,
            pairedAppliedParams = paired.appliedParams.copyOf()
        )

        pushIndex += 1
        nextCommandIndex += 1
        return ControlOutput(
            nominalParams = nominalParams.copyOf(),
            appliedParams = appliedParams.copyOf(),
            diagnostics = diagnostics
        )
    }

    private fun currentDither(t: Int): DoubleArray =
        DoubleArray(config.parameterCount) { i ->
            config.ditherAmplitude[i] * kotlin.math.sin(config.ditherOmega[i] * t + config.ditherPhase[i])
        }

    private fun softenNearBounds(step: DoubleArray): DoubleArray {
        val softened = step.copyOf()
        for (i in softened.indices) {
            val span = config.paramMax[i] - config.paramMin[i]
            val zone = config.boundarySoftZone * span
            val distToLo = nominalParams[i] - config.paramMin[i]
            val distToHi = config.paramMax[i] - nominalParams[i]
            val scale = when {
                softened[i] > 0.0 -> clamp(distToHi / zone, 0.0, 1.0)
                softened[i] < 0.0 -> clamp(distToLo / zone, 0.0, 1.0)
                else -> 1.0
            }
            softened[i] *= scale
        }
        return softened
    }
}

class AdaptiveDistributionControllerBank<K>(
    private val factory: () -> AdaptiveDistributionController
) {
    private val controllers = LinkedHashMap<K, AdaptiveDistributionController>()

    fun get(sceneKey: K): AdaptiveDistributionController =
        controllers.getOrPut(sceneKey) { factory() }

    fun pushSamples(sceneKey: K, samples: DoubleArray, clipRate: Double = 0.0): ControlOutput =
        get(sceneKey).pushSamples(samples, clipRate)

    fun pushFeatureError(sceneKey: K, rawFeatureError: DoubleArray, clipRate: Double = 0.0): ControlOutput =
        get(sceneKey).pushFeatureError(rawFeatureError, clipRate)
}

class AdaptiveDistributionLoop(
    val controller: AdaptiveDistributionController,
    private val observe: () -> ScalarSampleBatch?,
    private val apply: (ControlOutput) -> Unit,
    private val onUpdate: ((ScalarSampleBatch, ControlOutput) -> Unit)? = null
) {
    var tickCount: Int = 0
        private set

    var lastBatch: ScalarSampleBatch? = null
        private set

    var lastOutput: ControlOutput? = null
        private set

    fun tick(): ControlOutput? {
        val batch = observe() ?: return null
        require(batch.samples.isNotEmpty()) { "ScalarSampleBatch.samples must not be empty" }

        val output = controller.pushSamples(batch.samples, batch.clipRate)
        apply(output)
        onUpdate?.invoke(batch, output)

        lastBatch = batch
        lastOutput = output
        tickCount += 1
        return output
    }

    fun installRunloopHook(
        key: String,
        runloop: Runloop = Runloop.runLoop
    ): AdaptiveDistributionLoop {
        runloop.preRun[key] = sequence {
            while (true) {
                tick()
                yield(Unit)
            }
        }
        return this
    }

    fun removeRunloopHook(
        key: String,
        runloop: Runloop = Runloop.runLoop
    ): AdaptiveDistributionLoop {
        runloop.preRun.remove(key)
        return this
    }
}


fun main() {

    data class ToyPlantObservation(
        val rawMean: Double,
        val rawSigma: Double,
        val clipRate: Double,
        val warpedSamples: DoubleArray
    )

    class DriftingGaussianToyPlant(
        private val batchSize: Int,
        seed: Long = 0L
    ) {
        private val rng = GaussianRng(seed)

        private fun warp01(x: Double, params: DoubleArray): Double {
            require(params.size == 5)
            val offset = params[0]
            val contrastLog = params[1]
            val gammaLog = params[2]
            val cubic = params[3]
            val skew = params[4]

            val shifted = x + offset
            val centered = shifted - 0.5
            val contrasted = 0.5 + exp(contrastLog) * centered
            val limited = clamp(contrasted, 0.0, 1.0)
            val gamma = exp(gammaLog)
            val curved = limited.pow(gamma)
            val z = curved - 0.5
            val withShape = 0.5 + z + cubic * z * z * z + skew * z * abs(z)
            return clamp(withShape, 0.0, 1.0)
        }

        fun render(frame: Int, params: DoubleArray): ToyPlantObservation {
            val t = frame.toDouble()/4
            val mean = 0.50 + 0.18 * kotlin.math.sin(0.0033 * t) + 0.05 * kotlin.math.sin(0.00071 * t + 1.7)
            val sigma = 0.12 + 0.04 * (0.5 + 0.5 * kotlin.math.sin(0.0021 * t + 0.9))

            val warped = DoubleArray(batchSize)
            var clips = 0
            for (i in 0 until batchSize) {
                val x = clamp(mean + sigma * rng.nextGaussian(), 0.0, 1.0)
                val y = warp01(x, params)
                if (y <= 1e-6 || y >= 1.0 - 1e-6) clips += 1
                warped[i] = y
            }

            return ToyPlantObservation(
                rawMean = mean,
                rawSigma = sigma,
                clipRate = clips.toDouble() / batchSize,
                warpedSamples = warped
            )
        }
    }

    val totalFrames = 8_000
    val batchSize = 768

    val config = ControllerConfig(
        observationDelayFrames = 3
    )

    val featureExtractor = UniformQuantileFeatureExtractor()
    val controller = AdaptiveDistributionController(config, featureExtractor)
    val plant = DriftingGaussianToyPlant(batchSize = batchSize, seed = 4L)

    // In a real system the GPU or render graph sits outside the controller.
    // We model that here with an actuator queue: commands issued now show up in
    // the observed samples `observationDelayFrames` pushes later.
    val actuatorQueue = ArrayDeque<DoubleArray>().apply {
        repeat(config.observationDelayFrames) {
            addLast(config.initialParams.copyOf())
        }
    }

    var runningObjective = 0.0
    var runningRawFeatureLoss = 0.0
    var reportCount = 0

    for (frame in 0 until totalFrames) {
        val activeParams = actuatorQueue.removeFirst()
        val plantObs = plant.render(frame, activeParams)

        val output = controller.pushSamples(
            samples = plantObs.warpedSamples,
            clipRate = plantObs.clipRate
        )

        actuatorQueue.addLast(output.appliedParams.copyOf())

        runningObjective += output.diagnostics.objective
        runningRawFeatureLoss += 0.5 * dot(output.diagnostics.rawFeatureError, output.diagnostics.rawFeatureError)
        reportCount += 1

        if (frame % 120 < 10 || frame == totalFrames - 1) {
            val avgObj = runningObjective / reportCount
            val avgFeatLoss = runningRawFeatureLoss / reportCount
            val d = output.diagnostics

            println(
                "frame=%4d  rawMean=%.3f  rawSigma=%.3f  avgLoss=%.5f  avgFeatLoss=%.5f  clip=%.4f  conf=%.3f  resid=%.3f  cov=%.2f"
                    .format(
                        frame,
                        plantObs.rawMean,
                        plantObs.rawSigma,
                        avgObj,
                        avgFeatLoss,
                        plantObs.clipRate,
                        d.confidence,
                        d.residualRatio,
                        d.averageVariance
                    )
            )
            println("  feature error          = ${formatted(d.filteredFeatureError)}")
            println("  learning from command  = ${d.learnedFromCommandIndex}")
            println("  active plant params    = ${formatted(activeParams)}")
            println("  next nominal params    = ${formatted(output.nominalParams)}")
            println("  next dithered params   = ${formatted(output.appliedParams)}")
            println()

            runningObjective = 0.0
            runningRawFeatureLoss = 0.0
            reportCount = 0
        }
    }

    println("Done.")
    println("API notes:")
    println("- Call pushSamples(samples, clipRate) whenever a new batch arrives from your render loop.")
    println("- The returned appliedParams are the next controls to send to your GPU or simulation.")
    println("- observationDelayFrames explicitly models pipeline latency; you do not need to slow the controller loop down just to align learning.")
    println("- If you already have histogram or quantile features on the GPU, call pushFeatureError(...) instead of sending raw samples.")
    println("- For multiple modes or scenes, keep one controller per mode, or use AdaptiveDistributionControllerBank.")
}

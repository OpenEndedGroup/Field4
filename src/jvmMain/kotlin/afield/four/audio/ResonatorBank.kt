@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import jdk.incubator.vector.VectorSpecies
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI = 3.14159265358979323846f
private const val TWO_PI = 2.0f * PI
private const val MIN_TRACKING_MAX_POWER = 0.001f

/**
 * Resonator EMA coefficient heuristic from Oscillators/noFFT.
 */
fun resonatorAlphaHeuristic(
    frequency: Float,
    sampleRate: Float,
    k: Float = 1.0f,
    n: Float = 1.0f
): Float {
    require(frequency >= 0.0f) { "frequency must be >= 0" }
    require(sampleRate > 0.0f) { "sampleRate must be > 0" }
    require(k > 0.0f) { "k must be > 0" }
    val denominator = sampleRate * k * log10((1.0f + frequency).toDouble()).pow(n.toDouble()).toFloat()
    return if (denominator > 0.0f) {
        (1.0 - exp((-frequency / denominator).toDouble())).toFloat()
    } else {
        1.0f
    }
}

fun resonatorAlphaHeuristics(
    frequencies: FloatArray,
    sampleRate: Float,
    k: Float = 1.0f,
    n: Float = 1.0f
): FloatArray = FloatArray(frequencies.size) { resonatorAlphaHeuristic(frequencies[it], sampleRate, k, n) }

fun trackingResonatorGammaHeuristic(
    frequency: Float,
    sampleRate: Float,
    k: Float = 1.0f,
    n: Float = 1.0f
): Float = resonatorAlphaHeuristic(frequency, sampleRate, k, n) * 0.5f

fun trackingResonatorGammaHeuristics(
    frequencies: FloatArray,
    sampleRate: Float,
    k: Float = 1.0f,
    n: Float = 1.0f
): FloatArray = FloatArray(frequencies.size) { trackingResonatorGammaHeuristic(frequencies[it], sampleRate, k, n) }

/**
 * Kotlin port of the "resonate" EMA resonator bank from noFFT.
 *
 * Split-complex layout:
 * - Real parts: [0, numResonators)
 * - Imag parts: [numResonators, 2 * numResonators)
 */
class ResonatorBank(
    private val numResonators: Int,
    frequencies: FloatArray,
    alphas: FloatArray,
    betas: FloatArray,
    private val sampleRate: Float
) {
    private val twoNumResonators = 2 * numResonators

    private val frequencies = FloatArray(numResonators)
    private val alphas = FloatArray(twoNumResonators)
    private val omAlphas = FloatArray(twoNumResonators)
    private val betas = FloatArray(twoNumResonators)
    private val omBetas = FloatArray(twoNumResonators)

    private val rPtr = FloatArray(twoNumResonators)
    private val rrPtr = FloatArray(twoNumResonators)
    private val zPtr = FloatArray(twoNumResonators)
    private val wPtr = FloatArray(twoNumResonators)

    private val alphasSample = FloatArray(twoNumResonators)
    private val smPtr = FloatArray(numResonators)
    private val rsqrtPtr = FloatArray(numResonators)

    init {
        require(frequencies.size == numResonators) { "frequencies size must be numResonators" }
        require(alphas.size == numResonators) { "alphas size must be numResonators" }
        require(betas.size == numResonators) { "betas size must be numResonators" }

        // initialize from passed frequencies
        System.arraycopy(frequencies, 0, this.frequencies, 0, numResonators)

        // duplicate alphas and betas for split complex
        System.arraycopy(alphas, 0, this.alphas, 0, numResonators)
        System.arraycopy(alphas, 0, this.alphas, numResonators, numResonators)
        System.arraycopy(betas, 0, this.betas, 0, numResonators)
        System.arraycopy(betas, 0, this.betas, numResonators, numResonators)

        // omAlphas = 1 - alphas, omBetas = 1 - betas
        for (i in 0 until twoNumResonators) {
            omAlphas[i] = 1.0f - this.alphas[i]
            omBetas[i] = 1.0f - this.betas[i]
        }

        // z starts at 1 + 0i for each resonator
        for (i in 0 until numResonators) {
            zPtr[i] = 1.0f
            zPtr[i + numResonators] = 0.0f
        }

        // w = exp(-i * 2pi * f / sr) -> cos/sin of -2pi f / sr
        val twoPiOverSampleRate = -TWO_PI / sampleRate
        for (i in 0 until numResonators) {
            val phase = twoPiOverSampleRate * this.frequencies[i]
            wPtr[i] = cos(phase)
            wPtr[i + numResonators] = sin(phase)
        }
    }

    fun frequencyValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to frequencyValue()" }
        return frequencies[index]
    }

    fun alphaValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to alphaValue()" }
        return alphas[index]
    }

    fun timeConstantValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to timeConstantValue()" }
        val alpha = alphas[index]
        return if (alpha > 0.0f) 1.0f / (sampleRate * alpha) else 0.0f
    }

    fun getComplex(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= twoNumResonators) {
            "Buffer passed to getComplex() is not large enough"
        }
        System.arraycopy(rrPtr, 0, dest, offset, twoNumResonators)
    }

    fun getPowers(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getPowers() is not large enough"
        }
        for (i in 0 until numResonators) {
            val re = rrPtr[i]
            val im = rrPtr[i + numResonators]
            dest[offset + i] = re * re + im * im
        }
    }

    fun getAmplitudes(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getAmplitudes() is not large enough"
        }
        for (i in 0 until numResonators) {
            val re = rrPtr[i]
            val im = rrPtr[i + numResonators]
            dest[offset + i] = sqrt(re * re + im * im)
        }
    }

    fun update(sample: Float) {
        // alphasSample = alphas * sample
        for (i in 0 until twoNumResonators) {
            alphasSample[i] = alphas[i] * sample
        }

        // r = r * (1 - alpha) + z * (alpha * sample)
        for (i in 0 until twoNumResonators) {
            rPtr[i] = rPtr[i] * omAlphas[i] + zPtr[i] * alphasSample[i]
        }

        // rr = rr * (1 - beta) + r * beta
        for (i in 0 until twoNumResonators) {
            rrPtr[i] = rrPtr[i] * omBetas[i] + rPtr[i] * betas[i]
        }

        // phasor update: z = z * w (complex multiply)
        for (i in 0 until numResonators) {
            val zRe = zPtr[i]
            val zIm = zPtr[i + numResonators]
            val wRe = wPtr[i]
            val wIm = wPtr[i + numResonators]
            zPtr[i] = zRe * wRe - zIm * wIm
            zPtr[i + numResonators] = zRe * wIm + zIm * wRe
        }
    }

    fun update(samples: FloatArray) {
        for (sample in samples) {
            update(sample)
        }
        stabilize()
    }

    fun update(frameData: FloatArray, frameLength: Int, sampleStride: Int) {
        update(frameData, 0, frameLength, sampleStride)
    }

    fun update(frameData: FloatArray, frameOffset: Int, frameLength: Int, sampleStride: Int) {
        var i = 0
        var idx = frameOffset
        while (i < frameLength) {
            update(frameData[idx])
            i += sampleStride
            idx += sampleStride
        }
        stabilize()
    }

    fun update(
        frameData: FloatArray,
        frameLength: Int,
        sampleStride: Int,
        powers: FloatArray?,
        amplitudes: FloatArray?
    ) {
        update(frameData, 0, frameLength, sampleStride, powers, amplitudes)
    }

    fun update(
        frameData: FloatArray,
        frameOffset: Int,
        frameLength: Int,
        sampleStride: Int,
        powers: FloatArray?,
        amplitudes: FloatArray?
    ) {
        var i = 0
        var idx = frameOffset
        while (i < frameLength) {
            update(frameData[idx])
            i += sampleStride
            idx += sampleStride
        }
        stabilize()
        // Mirrors C++ behavior: powers/amplitudes not filled here.
        if (powers != null || amplitudes != null) {
            // Intentionally left blank to preserve algorithm parity.
        }
    }

    fun stabilize() {
        for (i in 0 until numResonators) {
            val re = zPtr[i]
            val im = zPtr[i + numResonators]
            smPtr[i] = re * re + im * im
        }
        for (i in 0 until numResonators) {
            val inv = if (smPtr[i] > 0.0f) 1.0f / sqrt(smPtr[i]) else 0.0f
            rsqrtPtr[i] = inv
            zPtr[i] *= inv
            zPtr[i + numResonators] *= inv
        }
    }
}

/**
 * A phase-tracking resonator bank.
 *
 * Each resonator starts at a natural frequency, estimates the phase drift of the
 * smoothed resonator output, and retunes its phasor toward the instantaneous
 * frequency when its local power is above a dynamic threshold. Below threshold
 * it returns to its natural frequency.
 *
 * The storage is the same split-complex SoA layout as [ResonatorBank]. The
 * linear EMA, power, reset, and stabilization passes are vectorized with the
 * JVM incubator Vector API; per-resonator atan2/cos/sin stays scalar.
 */
class TrackingResonatorBank(
    private val numResonators: Int,
    naturalFrequencies: FloatArray,
    alphas: FloatArray,
    betas: FloatArray = alphas,
    private val sampleRate: Float,
    gammas: FloatArray = trackingResonatorGammaHeuristics(naturalFrequencies, sampleRate)
) {
    private val twoNumResonators = 2 * numResonators

    private val naturalFrequencies = FloatArray(numResonators)
    private val naturalOmegas = FloatArray(numResonators)
    private val omegas = FloatArray(numResonators)

    private val alphas = FloatArray(twoNumResonators)
    private val omAlphas = FloatArray(twoNumResonators)
    private val betas = FloatArray(twoNumResonators)
    private val omBetas = FloatArray(twoNumResonators)
    private val gammas = FloatArray(numResonators)

    private val rPtr = FloatArray(twoNumResonators)
    private val rrPtr = FloatArray(twoNumResonators)
    private val rrPreviousPtr = FloatArray(twoNumResonators)
    private val deltaPtr = FloatArray(twoNumResonators)
    private val zPtr = FloatArray(twoNumResonators)
    private val wPtr = FloatArray(twoNumResonators)

    private val powers = FloatArray(numResonators)
    private val smPtr = FloatArray(numResonators)
    private val rsqrtPtr = FloatArray(numResonators)

    private var sigma = 1.0f
    private var omSigma = 0.0f
    private var accPower = 0.0001f
    private var trackingPowerThreshold = MIN_TRACKING_MAX_POWER / 1000.0f

    init {
        require(numResonators >= 0) { "numResonators must be >= 0" }
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
        require(naturalFrequencies.size == numResonators) { "naturalFrequencies size must be numResonators" }
        require(alphas.size == numResonators) { "alphas size must be numResonators" }
        require(betas.size == numResonators) { "betas size must be numResonators" }
        require(gammas.size == numResonators) { "gammas size must be numResonators" }

        System.arraycopy(naturalFrequencies, 0, this.naturalFrequencies, 0, numResonators)

        System.arraycopy(alphas, 0, this.alphas, 0, numResonators)
        System.arraycopy(alphas, 0, this.alphas, numResonators, numResonators)
        System.arraycopy(betas, 0, this.betas, 0, numResonators)
        System.arraycopy(betas, 0, this.betas, numResonators, numResonators)
        System.arraycopy(gammas, 0, this.gammas, 0, numResonators)

        for (i in 0 until twoNumResonators) {
            require(this.alphas[i] in 0.0f..1.0f) { "alphas must be in [0, 1]" }
            require(this.betas[i] in 0.0f..1.0f) { "betas must be in [0, 1]" }
            omAlphas[i] = 1.0f - this.alphas[i]
            omBetas[i] = 1.0f - this.betas[i]
        }
        for (i in 0 until numResonators) {
            require(this.gammas[i] in 0.0f..1.0f) { "gammas must be in [0, 1]" }
        }

        reset()
    }

    fun naturalFrequencyValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to naturalFrequencyValue()" }
        return naturalFrequencies[index]
    }

    fun resonantFrequencyValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to resonantFrequencyValue()" }
        return -omegas[index] * sampleRate / TWO_PI
    }

    fun instantaneousFrequencyValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to instantaneousFrequencyValue()" }
        return resonantFrequencyValue(index) + atan2(deltaPtr[index + numResonators], deltaPtr[index]) * sampleRate / TWO_PI
    }

    fun alphaValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to alphaValue()" }
        return alphas[index]
    }

    fun betaValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to betaValue()" }
        return betas[index]
    }

    fun gammaValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to gammaValue()" }
        return gammas[index]
    }

    fun timeConstantValue(index: Int): Float {
        require(index in 0 until numResonators) { "Bad index passed to timeConstantValue()" }
        val alpha = alphas[index]
        return if (alpha > 0.0f) 1.0f / (sampleRate * alpha) else 0.0f
    }

    fun trackingPowerThresholdValue(): Float = trackingPowerThreshold

    fun accumulatedPowerValue(): Float = accPower

    fun getNaturalFrequencies(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getNaturalFrequencies() is not large enough"
        }
        System.arraycopy(naturalFrequencies, 0, dest, offset, numResonators)
    }

    fun getResonantFrequencies(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getResonantFrequencies() is not large enough"
        }
        for (i in 0 until numResonators) {
            dest[offset + i] = -omegas[i] * sampleRate / TWO_PI
        }
    }

    fun getInstantaneousFrequencies(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getInstantaneousFrequencies() is not large enough"
        }
        for (i in 0 until numResonators) {
            dest[offset + i] = -omegas[i] * sampleRate / TWO_PI +
                atan2(deltaPtr[i + numResonators], deltaPtr[i]) * sampleRate / TWO_PI
        }
    }

    fun getComplex(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= twoNumResonators) {
            "Buffer passed to getComplex() is not large enough"
        }
        System.arraycopy(rrPtr, 0, dest, offset, twoNumResonators)
    }

    fun getPowers(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getPowers() is not large enough"
        }
        System.arraycopy(powers, 0, dest, offset, numResonators)
    }

    fun getAmplitudes(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getAmplitudes() is not large enough"
        }
        var i = 0
        val lanes = SP.length()
        while (i + lanes <= numResonators) {
            FloatVector.fromArray(SP, powers, i)
                .lanewise(VectorOperators.SQRT)
                .intoArray(dest, offset + i)
            i += lanes
        }
        while (i < numResonators) {
            dest[offset + i] = sqrt(powers[i])
            i++
        }
    }

    fun getPhases(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getPhases() is not large enough"
        }
        for (i in 0 until numResonators) {
            dest[offset + i] = atan2(rrPtr[i + numResonators], rrPtr[i])
        }
    }

    fun getDeltaPhases(dest: FloatArray, offset: Int = 0) {
        require(dest.size - offset >= numResonators) {
            "Buffer passed to getDeltaPhases() is not large enough"
        }
        for (i in 0 until numResonators) {
            dest[offset + i] = atan2(deltaPtr[i + numResonators], deltaPtr[i])
        }
    }

    fun update(sample: Float) {
        updateResonance(sample)
        updatePowers()
        updateTracking()
        updatePhasors()
    }

    fun update(samples: FloatArray) {
        for (sample in samples) {
            update(sample)
        }
        stabilize()
    }

    fun update(frameData: FloatArray, frameLength: Int, sampleStride: Int) {
        update(frameData, 0, frameLength, sampleStride)
    }

    fun update(frameData: FloatArray, frameOffset: Int, frameLength: Int, sampleStride: Int) {
        require(sampleStride > 0) { "sampleStride must be > 0" }
        var i = 0
        var idx = frameOffset
        while (i < frameLength) {
            update(frameData[idx])
            i += sampleStride
            idx += sampleStride
        }
        stabilize()
    }

    fun update(
        frameData: FloatArray,
        frameLength: Int,
        sampleStride: Int,
        powers: FloatArray?,
        amplitudes: FloatArray?
    ) {
        update(frameData, 0, frameLength, sampleStride, powers, amplitudes)
    }

    fun update(
        frameData: FloatArray,
        frameOffset: Int,
        frameLength: Int,
        sampleStride: Int,
        powers: FloatArray?,
        amplitudes: FloatArray?
    ) {
        update(frameData, frameOffset, frameLength, sampleStride)
        powers?.let { getPowers(it) }
        amplitudes?.let { getAmplitudes(it) }
    }

    fun setPowerTimeConstant(tau: Float = 1.0f) {
        require(tau > 0.0f) { "tau must be > 0" }
        val sampleDuration = 1.0f / sampleRate
        sigma = (1.0 - exp((-sampleDuration / tau).toDouble())).toFloat()
        omSigma = 1.0f - sigma
    }

    fun reset() {
        java.util.Arrays.fill(rPtr, 0.0f)
        java.util.Arrays.fill(rrPtr, 0.0f)
        java.util.Arrays.fill(rrPreviousPtr, 0.0f)
        java.util.Arrays.fill(deltaPtr, 0.0f)
        java.util.Arrays.fill(powers, 0.0f)
        java.util.Arrays.fill(zPtr, 0.0f)

        val minusTwoPiOverSampleRate = -TWO_PI / sampleRate
        for (i in 0 until numResonators) {
            zPtr[i] = 1.0f
            zPtr[i + numResonators] = 0.0f
            val omega = minusTwoPiOverSampleRate * naturalFrequencies[i]
            naturalOmegas[i] = omega
            omegas[i] = omega
            wPtr[i] = cos(omega)
            wPtr[i + numResonators] = sin(omega)
        }
        accPower = 0.0001f
        trackingPowerThreshold = MIN_TRACKING_MAX_POWER / 1000.0f
    }

    fun stabilize() {
        var i = 0
        val lanes = SP.length()
        while (i + lanes <= numResonators) {
            val zRe = FloatVector.fromArray(SP, zPtr, i)
            val zIm = FloatVector.fromArray(SP, zPtr, i + numResonators)
            val sm = zRe.mul(zRe).add(zIm.mul(zIm))
            sm.intoArray(smPtr, i)
            val inv = FloatVector.broadcast(SP, 1.0f).div(sm.lanewise(VectorOperators.SQRT))
            inv.intoArray(rsqrtPtr, i)
            zRe.mul(inv).intoArray(zPtr, i)
            zIm.mul(inv).intoArray(zPtr, i + numResonators)
            i += lanes
        }
        while (i < numResonators) {
            val re = zPtr[i]
            val im = zPtr[i + numResonators]
            smPtr[i] = re * re + im * im
            val inv = if (smPtr[i] > 0.0f) 1.0f / sqrt(smPtr[i]) else 0.0f
            rsqrtPtr[i] = inv
            zPtr[i] *= inv
            zPtr[i + numResonators] *= inv
            i++
        }
    }

    private fun updateResonance(sample: Float) {
        val lanes = SP.length()
        val vSample = FloatVector.broadcast(SP, sample)
        var i = 0
        while (i + lanes <= twoNumResonators) {
            val r = FloatVector.fromArray(SP, rPtr, i)
            val rr = FloatVector.fromArray(SP, rrPtr, i)
            val z = FloatVector.fromArray(SP, zPtr, i)
            val alpha = FloatVector.fromArray(SP, alphas, i)
            val omAlpha = FloatVector.fromArray(SP, omAlphas, i)
            val beta = FloatVector.fromArray(SP, betas, i)
            val omBeta = FloatVector.fromArray(SP, omBetas, i)

            val nextR = r.mul(omAlpha).add(z.mul(alpha).mul(vSample))
            val nextRr = rr.mul(omBeta).add(nextR.mul(beta))
            nextR.intoArray(rPtr, i)
            nextRr.intoArray(rrPtr, i)
            i += lanes
        }
        while (i < twoNumResonators) {
            rPtr[i] = rPtr[i] * omAlphas[i] + zPtr[i] * alphas[i] * sample
            rrPtr[i] = rrPtr[i] * omBetas[i] + rPtr[i] * betas[i]
            i++
        }
    }

    private fun updatePowers() {
        val lanes = SP.length()
        var i = 0
        while (i + lanes <= numResonators) {
            val re = FloatVector.fromArray(SP, rrPtr, i)
            val im = FloatVector.fromArray(SP, rrPtr, i + numResonators)
            re.mul(re).add(im.mul(im)).intoArray(powers, i)
            i += lanes
        }
        while (i < numResonators) {
            val re = rrPtr[i]
            val im = rrPtr[i + numResonators]
            powers[i] = re * re + im * im
            i++
        }

        var maxPower = 0.0f
        for (k in 0 until numResonators) {
            if (powers[k] > maxPower) maxPower = powers[k]
        }
        accPower = omSigma * accPower + sigma * maxPower
        trackingPowerThreshold = kotlin.math.max(MIN_TRACKING_MAX_POWER, accPower) / 1000.0f
    }

    private fun updateTracking() {
        for (i in 0 until numResonators) {
            val re = rrPtr[i]
            val im = rrPtr[i + numResonators]
            val previousRe = rrPreviousPtr[i]
            val previousIm = rrPreviousPtr[i + numResonators]

            val deltaRe = re * previousRe + im * previousIm
            val deltaIm = im * previousRe - re * previousIm
            deltaPtr[i] = deltaRe
            deltaPtr[i + numResonators] = deltaIm

            val trackedOmega = omegas[i] - gammas[i] * atan2(deltaIm, deltaRe)
            omegas[i] = if (powers[i] >= trackingPowerThreshold) trackedOmega else naturalOmegas[i]
        }
        System.arraycopy(rrPtr, 0, rrPreviousPtr, 0, twoNumResonators)
    }

    private fun updatePhasors() {
        for (i in 0 until numResonators) {
            val omega = omegas[i]
            wPtr[i] = cos(omega)
            wPtr[i + numResonators] = sin(omega)
        }

        val lanes = SP.length()
        var i = 0
        while (i + lanes <= numResonators) {
            val zRe = FloatVector.fromArray(SP, zPtr, i)
            val zIm = FloatVector.fromArray(SP, zPtr, i + numResonators)
            val wRe = FloatVector.fromArray(SP, wPtr, i)
            val wIm = FloatVector.fromArray(SP, wPtr, i + numResonators)
            zRe.mul(wRe).sub(zIm.mul(wIm)).intoArray(zPtr, i)
            zRe.mul(wIm).add(zIm.mul(wRe)).intoArray(zPtr, i + numResonators)
            i += lanes
        }
        while (i < numResonators) {
            val zRe = zPtr[i]
            val zIm = zPtr[i + numResonators]
            val wRe = wPtr[i]
            val wIm = wPtr[i + numResonators]
            zPtr[i] = zRe * wRe - zIm * wIm
            zPtr[i + numResonators] = zRe * wIm + zIm * wRe
            i++
        }
    }

    companion object {
        private val SP: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED

        fun alphasHeuristic(
            frequencies: FloatArray,
            sampleRate: Float,
            k: Float = 1.0f,
            n: Float = 1.0f
        ): FloatArray = resonatorAlphaHeuristics(frequencies, sampleRate, k, n)

        fun gammasHeuristic(
            frequencies: FloatArray,
            sampleRate: Float,
            k: Float = 1.0f,
            n: Float = 1.0f
        ): FloatArray = trackingResonatorGammaHeuristics(frequencies, sampleRate, k, n)
    }
}

fun trackingResonate(
    input: FloatArray,
    samplingRate: Float,
    naturalFrequencies: FloatArray,
    alphas: FloatArray,
    betas: FloatArray = alphas,
    gammas: FloatArray = trackingResonatorGammaHeuristics(naturalFrequencies, samplingRate),
    hopLength: Int,
    powerTimeConstant: Float? = null,
    progress: ((completed: Int, total: Int) -> Unit)? = null
): TrackingResonatorAnalysis {
    require(naturalFrequencies.size == alphas.size) { "Frequencies and alphas must have same size" }
    require(alphas.size == betas.size) { "Alphas and betas must have same size" }
    require(betas.size == gammas.size) { "Betas and gammas must have same size" }
    require(hopLength > 0) { "hopLength must be > 0" }

    val numSamples = input.size
    val numResonators = naturalFrequencies.size
    val twoNumResonators = 2 * numResonators
    val numSlices = numSamples / hopLength

    val complex = FloatArray(numSlices * twoNumResonators)
    val resonantFrequencies = FloatArray(numSlices * numResonators)
    val powers = FloatArray(numSlices * numResonators)
    val bank = TrackingResonatorBank(numResonators, naturalFrequencies, alphas, betas, samplingRate, gammas)
    powerTimeConstant?.let { bank.setPowerTimeConstant(it) }

    progress?.invoke(0, numSlices)
    for (idx in 0 until numSlices) {
        val inputOffset = idx * hopLength
        bank.update(input, inputOffset, hopLength, 1)
        bank.getComplex(complex, idx * twoNumResonators)
        bank.getResonantFrequencies(resonantFrequencies, idx * numResonators)
        bank.getPowers(powers, idx * numResonators)
        progress?.invoke(idx + 1, numSlices)
    }

    return TrackingResonatorAnalysis(
        sampleRate = samplingRate,
        hopLength = hopLength,
        sourceSampleCount = numSamples,
        naturalFrequencies = naturalFrequencies,
        complex = complex,
        resonantFrequencies = resonantFrequencies,
        powers = powers,
        alphas = alphas,
        betas = betas,
        gammas = gammas
    )
}

/**
 * Port of resonate::resonate for offline analysis.
 *
 * Returns a flat array where each hop slice contains:
 * - numResonators real parts, then
 * - numResonators imag parts
 */
fun resonate(
    input: FloatArray,
    samplingRate: Float,
    freqs: FloatArray,
    alphas: FloatArray,
    betas: FloatArray,
    hopLength: Int
): FloatArray {
    require(freqs.size == alphas.size) { "Frequencies and alphas must have same size" }
    require(alphas.size == betas.size) { "Alphas and betas must have same size" }
    require(hopLength > 0) { "hopLength must be > 0" }

    val numSamples = input.size
    val numResonators = freqs.size
    val twoNumResonators = 2 * numResonators
    val numSlices = numSamples / hopLength

    val result = FloatArray(numSlices * twoNumResonators)
    val bank = ResonatorBank(numResonators, freqs, alphas, betas, samplingRate)

    for (idx in 0 until numSlices) {
        val inputOffset = idx * hopLength
        bank.update(input, inputOffset, hopLength, 1)
        val resultOffset = idx * twoNumResonators
        bank.getComplex(result, resultOffset)
    }

    return result
}


/**
 * Small standalone smoketest for [ResonatorBank]/[resonate].
 *
 * Run however you normally run JVM mains in this repo (this isn't wired as the app entrypoint).
 */
fun main() {
    val sampleRate = 48_000f
    val seconds = 1.5f
    val n = (seconds * sampleRate).toInt()

    // Two sines (plus a tiny bit of noise to avoid pathological symmetry).
    val f1 = 440f
    val f2 = 880f
    val input = FloatArray(n) { i ->
        val t = i / sampleRate
        val x = 0.7f * sin((2.0 * PI * f1 * t).toFloat()) + 0.35f * sin((2.0 * PI * f2 * t).toFloat())
        val dither = (i % 97 - 48) * (1e-6f)
        x + dither
    }

    val freqs = floatArrayOf(110f, 220f, 330f, 440f, 550f, 660f, 770f, 880f, 990f)

    // EMA coefficients; tweak these if you want more/less temporal smoothing.
    val alphas = FloatArray(freqs.size) { 0.0025f }
    val betas = FloatArray(freqs.size) { 0.02f }

    val hopLength = 256

    val result = resonate(
        input = input,
        samplingRate = sampleRate,
        freqs = freqs,
        alphas = alphas,
        betas = betas,
        hopLength = hopLength
    )

    val numRes = freqs.size
    val twoNumRes = 2 * numRes
    val numSlices = result.size / twoNumRes

    fun amplitudeAt(slice: Int, k: Int): Float {
        val base = slice * twoNumRes
        val re = result[base + k]
        val im = result[base + numRes + k]
        return sqrt(re * re + im * im)
    }

    // Inspect the last slice.
    val last = numSlices - 1
    var bestK = 0
    var bestA = -1f
    for (k in 0 until numRes) {
        val a = amplitudeAt(last, k)
        if (a > bestA) {
            bestA = a
            bestK = k
        }
    }

    println("resonate smoketest")
    println("sampleRate=$sampleRate, seconds=$seconds, hopLength=$hopLength, slices=$numSlices")
    println("peak @ ${freqs[bestK]} Hz : amplitude=$bestA")

    fun findFreqIndex(target: Float): Int {
        for (i in freqs.indices) if (freqs[i] == target) return i
        return -1
    }

    freqs.forEach {
        val i = findFreqIndex(it)
        println("$it = ${amplitudeAt(last, i)}")
    }
}

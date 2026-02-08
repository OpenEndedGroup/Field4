@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PI = 3.14159265358979323846f
private const val TWO_PI = 2.0f * PI

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
        val twoPiOverSampleRate = -TWO_PI.toFloat() / sampleRate
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

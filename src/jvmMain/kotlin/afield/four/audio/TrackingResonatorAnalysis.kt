@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TRACKING_ANALYSIS_MAGIC = 0x46345452 // F4TR
private const val TRACKING_ANALYSIS_VERSION = 1
private const val TWO_PI_DOUBLE = 2.0 * PI
private val TWO_PI_FLOAT = TWO_PI_DOUBLE.toFloat()

/**
 * Durable storage for a [TrackingResonatorBank] analysis.
 *
 * Per-frame arrays are frame-major:
 * - `resonantFrequencies[frame * numResonators + resonator]`
 * - `powers[frame * numResonators + resonator]`
 * - `complex[frame * 2 * numResonators + resonator]` for real values
 * - `complex[frame * 2 * numResonators + numResonators + resonator]` for imaginary values
 */
class TrackingResonatorAnalysis(
    val sampleRate: Float,
    val hopLength: Int,
    val sourceSampleCount: Int,
    naturalFrequencies: FloatArray,
    resonantFrequencies: FloatArray,
    powers: FloatArray,
    complex: FloatArray,
    alphas: FloatArray = FloatArray(naturalFrequencies.size),
    betas: FloatArray = FloatArray(naturalFrequencies.size),
    gammas: FloatArray = FloatArray(naturalFrequencies.size)
) {
    val naturalFrequencies: FloatArray = naturalFrequencies.copyOf()
    val resonantFrequencies: FloatArray = resonantFrequencies.copyOf()
    val powers: FloatArray = powers.copyOf()
    val complex: FloatArray = complex.copyOf()
    val alphas: FloatArray = alphas.copyOf()
    val betas: FloatArray = betas.copyOf()
    val gammas: FloatArray = gammas.copyOf()

    val numResonators: Int = this.naturalFrequencies.size
    val numSlices: Int = if (numResonators == 0) 0 else this.resonantFrequencies.size / numResonators
    val frameCount: Int
        get() = numSlices
    val durationSeconds: Float
        get() = if (sampleRate > 0.0f) sourceSampleCount / sampleRate else 0.0f

    init {
        require(sampleRate > 0.0f) { "sampleRate must be > 0" }
        require(hopLength > 0) { "hopLength must be > 0" }
        require(sourceSampleCount >= 0) { "sourceSampleCount must be >= 0" }
        require(this.resonantFrequencies.size == numSlices * numResonators) {
            "resonantFrequencies must be frame-major numSlices * numResonators"
        }
        require(this.powers.size == numSlices * numResonators) {
            "powers must be frame-major numSlices * numResonators"
        }
        require(this.complex.size == numSlices * numResonators * 2) {
            "complex must be frame-major numSlices * 2 * numResonators"
        }
        require(this.alphas.size == numResonators) { "alphas size must match naturalFrequencies" }
        require(this.betas.size == numResonators) { "betas size must match naturalFrequencies" }
        require(this.gammas.size == numResonators) { "gammas size must match naturalFrequencies" }
    }

    fun frameTime(frame: Int): Float {
        require(frame in 0 until frameCount) { "frame out of range" }
        return frame * hopLength / sampleRate
    }

    fun sampleIndexForFrame(frame: Int): Int {
        require(frame in 0 until frameCount) { "frame out of range" }
        return frame * hopLength
    }

    fun naturalFrequency(resonator: Int): Float {
        requireResonator(resonator)
        return naturalFrequencies[resonator]
    }

    fun resonantFrequency(frame: Int, resonator: Int): Float =
        resonantFrequencies[scalarIndex(frame, resonator)]

    fun power(frame: Int, resonator: Int): Float =
        powers[scalarIndex(frame, resonator)]

    fun amplitude(frame: Int, resonator: Int): Float =
        sqrt(power(frame, resonator).coerceAtLeast(0.0f))

    fun real(frame: Int, resonator: Int): Float =
        complex[complexFrameOffset(frame) + checkedResonator(resonator)]

    fun imag(frame: Int, resonator: Int): Float =
        complex[complexFrameOffset(frame) + numResonators + checkedResonator(resonator)]

    fun phase(frame: Int, resonator: Int): Float =
        atan2(imag(frame, resonator), real(frame, resonator))

    fun track(resonator: Int): TrackingResonatorTrack {
        requireResonator(resonator)
        val frequencies = FloatArray(frameCount)
        val amplitudes = FloatArray(frameCount)
        val powers = FloatArray(frameCount)
        val phases = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            frequencies[frame] = resonantFrequency(frame, resonator)
            powers[frame] = power(frame, resonator)
            amplitudes[frame] = sqrt(powers[frame].coerceAtLeast(0.0f))
            phases[frame] = phase(frame, resonator)
        }
        return TrackingResonatorTrack(resonator, naturalFrequencies[resonator], frequencies, amplitudes, powers, phases)
    }

    fun topPartials(frame: Int, count: Int, minPower: Float = 0.0f): List<TrackingPartial> {
        require(frame in 0 until frameCount) { "frame out of range" }
        require(count >= 0) { "count must be >= 0" }
        if (count == 0) return emptyList()
        return (0 until numResonators)
            .asSequence()
            .map { partialAt(frame, it) }
            .filter { it.power >= minPower }
            .sortedByDescending { it.power }
            .take(count)
            .toList()
    }

    fun resonatorsByMeanPower(
        count: Int = numResonators,
        minMeanPower: Float = 0.0f,
        localMaximaOnly: Boolean = false,
        minResonatorSpacing: Int = 0,
        selectionFrequencyTilt: Float = 0.0f,
        selectionReferenceFrequency: Float = 1000.0f
    ): IntArray {
        require(count >= 0) { "count must be >= 0" }
        require(minResonatorSpacing >= 0) { "minResonatorSpacing must be >= 0" }
        require(selectionReferenceFrequency > 0.0f) { "selectionReferenceFrequency must be > 0" }
        if (count == 0 || numResonators == 0 || frameCount == 0) return IntArray(0)

        val means = FloatArray(numResonators) { meanPower(it) }
        val candidates = (0 until numResonators)
            .asSequence()
            .filter { resonator ->
                means[resonator] >= minMeanPower &&
                    (!localMaximaOnly || isLocalMeanPowerMaximum(means, resonator))
            }
            .map { it to means[it] * frequencyWeight(it, selectionFrequencyTilt, selectionReferenceFrequency) }
            .sortedByDescending { it.second }
            .toList()

        if (minResonatorSpacing == 0) {
            return candidates.take(count).map { it.first }.toIntArray()
        }

        val selected = ArrayList<Int>(count)
        for ((resonator, _) in candidates) {
            if (selected.all { abs(it - resonator) > minResonatorSpacing }) {
                selected += resonator
                if (selected.size == count) break
            }
        }
        return selected.toIntArray()
    }

    fun meanPower(resonator: Int): Float {
        requireResonator(resonator)
        if (frameCount == 0) return 0.0f
        var sum = 0.0
        for (frame in 0 until frameCount) {
            sum += power(frame, resonator)
        }
        return (sum / frameCount).toFloat()
    }

    fun interpolatedFrequency(resonator: Int, sampleIndex: Int): Float =
        interpolatedFrequencyAt(resonator, sampleIndex.toDouble())

    fun interpolatedFrequencyAt(resonator: Int, samplePosition: Double): Float =
        interpolateBySamplePosition(samplePosition) { frame -> resonantFrequency(frame, resonator) }

    fun interpolatedPower(resonator: Int, sampleIndex: Int): Float =
        interpolatedPowerAt(resonator, sampleIndex.toDouble())

    fun interpolatedPowerAt(resonator: Int, samplePosition: Double): Float =
        interpolateBySamplePosition(samplePosition) { frame -> power(frame, resonator) }

    fun interpolatedAmplitude(resonator: Int, sampleIndex: Int): Float =
        interpolatedAmplitudeAt(resonator, sampleIndex.toDouble())

    fun interpolatedAmplitudeAt(resonator: Int, samplePosition: Double): Float =
        sqrt(interpolatedPowerAt(resonator, samplePosition).coerceAtLeast(0.0f))

    fun interpolatedPhase(resonator: Int, sampleIndex: Int): Float =
        interpolatedPhaseAt(resonator, sampleIndex.toDouble())

    fun interpolatedPhaseAt(resonator: Int, samplePosition: Double): Float =
        interpolateAngleBySamplePosition(samplePosition) { frame -> phase(frame, resonator) }

    fun resynthesizeAdditive(
        length: Int = sourceSampleCount,
        maxPartials: Int = numResonators,
        minMeanPower: Float = 0.0f,
        gain: Float = 1.0f,
        normalizePeak: Float? = null,
        pitchShiftSemitones: Float = 0.0f,
        timeStretch: Float = 1.0f,
        amplitudeScale: Float = 2.0f,
        localMaximaOnly: Boolean = false,
        minResonatorSpacing: Int = 0,
        selectionFrequencyTilt: Float = 0.0f,
        amplitudeFrequencyTilt: Float = 0.0f,
        frequencyTiltReferenceHz: Float = 1000.0f,
        progress: ((completed: Int, total: Int) -> Unit)? = null
    ): FloatArray {
        require(length >= 0) { "length must be >= 0" }
        require(maxPartials >= 0) { "maxPartials must be >= 0" }
        require(timeStretch > 0.0f) { "timeStretch must be > 0" }
        require(amplitudeScale >= 0.0f) { "amplitudeScale must be >= 0" }
        require(frequencyTiltReferenceHz > 0.0f) { "frequencyTiltReferenceHz must be > 0" }
        if (length == 0 || maxPartials == 0 || frameCount == 0 || numResonators == 0) {
            progress?.invoke(length, length)
            return FloatArray(length)
        }

        val selected = resonatorsByMeanPower(
            count = maxPartials,
            minMeanPower = minMeanPower,
            localMaximaOnly = localMaximaOnly,
            minResonatorSpacing = minResonatorSpacing,
            selectionFrequencyTilt = selectionFrequencyTilt,
            selectionReferenceFrequency = frequencyTiltReferenceHz
        )
        val carrierPhases = DoubleArray(selected.size)
        val amplitudeWeights = DoubleArray(selected.size) { partialIndex ->
            frequencyWeight(selected[partialIndex], amplitudeFrequencyTilt, frequencyTiltReferenceHz).toDouble()
        }
        val output = FloatArray(length)
        val pitchRatio = 2.0.pow(pitchShiftSemitones / 12.0)
        val progressInterval = (length / 200).coerceAtLeast(1)

        progress?.invoke(0, length)
        for (sample in 0 until length) {
            val analysisSample = (sample / timeStretch)
                .coerceIn(0.0f, (sourceSampleCount.coerceAtLeast(1) - 1).toFloat())
                .toDouble()
            var value = 0.0
            for (partialIndex in selected.indices) {
                val resonator = selected[partialIndex]
                val frequency = interpolatedFrequencyAt(resonator, analysisSample).toDouble() * pitchRatio
                val amplitude = interpolatedAmplitudeAt(resonator, analysisSample).toDouble()
                val envelopePhase = interpolatedPhaseAt(resonator, analysisSample).toDouble()
                value += amplitudeScale * amplitudeWeights[partialIndex] * amplitude *
                    cos(carrierPhases[partialIndex] + envelopePhase)
                carrierPhases[partialIndex] += TWO_PI_DOUBLE * frequency / sampleRate
            }
            output[sample] = (value * gain).toFloat()
            val completed = sample + 1
            if (completed == length || completed % progressInterval == 0) {
                progress?.invoke(completed, length)
            }
        }

        if (normalizePeak != null) {
            normalizeInPlace(output, normalizePeak)
        }
        return output
    }

    fun transformedSampleCount(timeStretch: Float): Int {
        require(timeStretch > 0.0f) { "timeStretch must be > 0" }
        return (sourceSampleCount * timeStretch).roundToInt().coerceAtLeast(0)
    }

    fun writeTo(file: File) {
        file.outputStream().use { writeTo(it) }
    }

    fun writeTo(output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output))
        out.writeInt(TRACKING_ANALYSIS_MAGIC)
        out.writeInt(TRACKING_ANALYSIS_VERSION)
        out.writeFloat(sampleRate)
        out.writeInt(hopLength)
        out.writeInt(sourceSampleCount)
        out.writeInt(numResonators)
        out.writeInt(frameCount)
        out.writeFloatArray(naturalFrequencies)
        out.writeFloatArray(alphas)
        out.writeFloatArray(betas)
        out.writeFloatArray(gammas)
        out.writeFloatArray(resonantFrequencies)
        out.writeFloatArray(powers)
        out.writeFloatArray(complex)
        out.flush()
    }

    private fun partialAt(frame: Int, resonator: Int): TrackingPartial =
        TrackingPartial(
            frame = frame,
            resonator = resonator,
            naturalFrequency = naturalFrequencies[resonator],
            frequency = resonantFrequency(frame, resonator),
            amplitude = amplitude(frame, resonator),
            power = power(frame, resonator),
            phase = phase(frame, resonator)
        )

    private fun scalarIndex(frame: Int, resonator: Int): Int {
        require(frame in 0 until frameCount) { "frame out of range" }
        return frame * numResonators + checkedResonator(resonator)
    }

    private fun complexFrameOffset(frame: Int): Int {
        require(frame in 0 until frameCount) { "frame out of range" }
        return frame * numResonators * 2
    }

    private fun checkedResonator(resonator: Int): Int {
        requireResonator(resonator)
        return resonator
    }

    private fun requireResonator(resonator: Int) {
        require(resonator in 0 until numResonators) { "resonator out of range" }
    }

    private fun isLocalMeanPowerMaximum(means: FloatArray, resonator: Int): Boolean {
        val value = means[resonator]
        val left = if (resonator > 0) means[resonator - 1] else Float.NEGATIVE_INFINITY
        val right = if (resonator + 1 < means.size) means[resonator + 1] else Float.NEGATIVE_INFINITY
        return value >= left && value >= right
    }

    private fun frequencyWeight(resonator: Int, tilt: Float, referenceHz: Float): Float {
        if (tilt == 0.0f) return 1.0f
        val ratio = (naturalFrequencies[resonator] / referenceHz).coerceIn(0.05f, 20.0f)
        return ratio.toDouble().pow(tilt.toDouble()).toFloat()
    }

    private inline fun interpolateBySamplePosition(samplePosition: Double, valueAt: (Int) -> Float): Float {
        require(samplePosition >= 0.0) { "samplePosition must be >= 0" }
        if (frameCount == 0) return 0.0f
        if (frameCount == 1) return valueAt(0)
        val framePosition = samplePosition / hopLength
        val frame0 = floor(framePosition).toInt().coerceIn(0, frameCount - 1)
        val frame1 = (frame0 + 1).coerceAtMost(frameCount - 1)
        val t = (framePosition - frame0).coerceIn(0.0, 1.0).toFloat()
        return lerp(valueAt(frame0), valueAt(frame1), t)
    }

    private inline fun interpolateAngleBySamplePosition(samplePosition: Double, valueAt: (Int) -> Float): Float {
        require(samplePosition >= 0.0) { "samplePosition must be >= 0" }
        if (frameCount == 0) return 0.0f
        if (frameCount == 1) return valueAt(0)
        val framePosition = samplePosition / hopLength
        val frame0 = floor(framePosition).toInt().coerceIn(0, frameCount - 1)
        val frame1 = (frame0 + 1).coerceAtMost(frameCount - 1)
        val t = (framePosition - frame0).coerceIn(0.0, 1.0).toFloat()
        val a = valueAt(frame0)
        val b = valueAt(frame1)
        var delta = b - a
        while (delta > PI) delta -= TWO_PI_FLOAT
        while (delta < -PI) delta += TWO_PI_FLOAT
        return a + delta * t
    }

    companion object {
        fun readFrom(file: File): TrackingResonatorAnalysis =
            file.inputStream().use { readFrom(it) }

        fun readFrom(input: InputStream): TrackingResonatorAnalysis {
            val reader = DataInputStream(BufferedInputStream(input))
            val magic = reader.readInt()
            require(magic == TRACKING_ANALYSIS_MAGIC) { "Not a Field tracking resonator analysis file" }
            val version = reader.readInt()
            require(version == TRACKING_ANALYSIS_VERSION) { "Unsupported tracking analysis version $version" }

            val sampleRate = reader.readFloat()
            val hopLength = reader.readInt()
            val sourceSampleCount = reader.readInt()
            val numResonators = reader.readInt()
            val frameCount = reader.readInt()

            val naturalFrequencies = reader.readFloatArray(numResonators)
            val alphas = reader.readFloatArray(numResonators)
            val betas = reader.readFloatArray(numResonators)
            val gammas = reader.readFloatArray(numResonators)
            val resonantFrequencies = reader.readFloatArray(frameCount * numResonators)
            val powers = reader.readFloatArray(frameCount * numResonators)
            val complex = reader.readFloatArray(frameCount * numResonators * 2)

            return TrackingResonatorAnalysis(
                sampleRate = sampleRate,
                hopLength = hopLength,
                sourceSampleCount = sourceSampleCount,
                naturalFrequencies = naturalFrequencies,
                resonantFrequencies = resonantFrequencies,
                powers = powers,
                complex = complex,
                alphas = alphas,
                betas = betas,
                gammas = gammas
            )
        }
    }
}

data class TrackingPartial(
    val frame: Int,
    val resonator: Int,
    val naturalFrequency: Float,
    val frequency: Float,
    val amplitude: Float,
    val power: Float,
    val phase: Float
)

data class TrackingResonatorTrack(
    val resonator: Int,
    val naturalFrequency: Float,
    val frequencies: FloatArray,
    val amplitudes: FloatArray,
    val powers: FloatArray,
    val phases: FloatArray
)

private fun DataOutputStream.writeFloatArray(values: FloatArray) {
    writeInt(values.size)
    for (value in values) writeFloat(value)
}

private fun DataInputStream.readFloatArray(expectedSize: Int): FloatArray {
    val size = readInt()
    require(size == expectedSize) { "Unexpected float array size $size, expected $expectedSize" }
    return FloatArray(size) { readFloat() }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun normalizeInPlace(values: FloatArray, targetPeak: Float) {
    require(targetPeak >= 0.0f) { "targetPeak must be >= 0" }
    var peak = 0.0f
    for (value in values) {
        val a = abs(value)
        if (a > peak) peak = a
    }
    if (peak <= 0.0f) return
    val scale = targetPeak / peak
    for (i in values.indices) {
        values[i] *= scale
    }
}

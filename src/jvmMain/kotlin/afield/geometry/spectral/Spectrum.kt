@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.audio.synth.FFT
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Frequency in the domain's own units, and the mean square of each basis function. */
data class SpectralMode(val frequency: Double, val primaryNorm: Double = 1.0, val quadratureNorm: Double = 0.0)

/**
 * A multichannel sum of basis functions. Mode m stores two coefficient rows, including a zero
 * quadrature row for real eigenfunctions. Rows are interleaved by channel. The domain supplies
 * the basis values, so edits and filters apply equally to phase, spatial, and mesh spectra.
 */
class Spectrum internal constructor(
    val channels: Int,
    modes: List<SpectralMode>,
    coefficients: DoubleArray,
    internal val domain: Any?,
) {
    constructor(channels: Int, modes: List<SpectralMode>, coefficients: DoubleArray) : this(channels, modes, coefficients, null)

    val modes: List<SpectralMode> = modes.toList()
    private val values = coefficients.copyOf()
    val modeCount: Int get() = modes.size

    init {
        require(channels > 0 && values.size.toLong() == 2L * channels * modeCount)
        require(values.all { it.isFinite() })
        require(modes.all {
            it.frequency.isFinite() && it.frequency >= 0 &&
                it.primaryNorm.isFinite() && it.primaryNorm >= 0 &&
                it.quadratureNorm.isFinite() && it.quadratureNorm >= 0
        })
    }

    fun primary(mode: Int, channel: Int): Double = values[index(mode, channel)]
    fun quadrature(mode: Int, channel: Int): Double = values[index(mode, channel) + channels]
    fun coefficients(): DoubleArray = values.copyOf()

    private fun index(mode: Int, channel: Int): Int {
        require(mode in modes.indices && channel in 0 until channels)
        return mode * 2 * channels + channel
    }

    fun energy(mode: Int): Double {
        val metadata = modes[mode]
        return (0 until channels).sumOf {
            val a = primary(mode, it)
            val b = quadrature(mode, it)
            metadata.primaryNorm * a * a + metadata.quadratureNorm * b * b
        }
    }

    fun edit(transform: (mode: Int, channel: Int, primary: Double, quadrature: Double) -> Pair<Double, Double>): Spectrum {
        val next = values.copyOf()
        for (mode in modes.indices) for (channel in 0 until channels) {
            val i = index(mode, channel)
            val (a, b) = transform(mode, channel, values[i], values[i + channels])
            next[i] = a
            next[i + channels] = b
        }
        return Spectrum(channels, modes, next, domain)
    }

    fun reweight(weight: (mode: Int, metadata: SpectralMode) -> Double): Spectrum {
        val next = values.copyOf()
        for (mode in modes.indices) {
            val gain = weight(mode, modes[mode])
            val start = mode * channels * 2
            for (i in start until start + channels * 2) next[i] *= gain
        }
        return Spectrum(channels, modes, next, domain)
    }

    fun bandPass(minFrequency: Double, maxFrequency: Double): Spectrum {
        require(minFrequency >= 0 && maxFrequency >= minFrequency)
        return reweight { _, mode -> if (mode.frequency in minFrequency..maxFrequency) 1.0 else 0.0 }
    }

    /** The same variance across an eigenspace gives a basis-independent distribution. */
    fun randomize(seed: Int, deviation: (SpectralMode) -> Double): Spectrum {
        val random = Random(seed)
        val next = DoubleArray(values.size)
        for ((index, mode) in modes.withIndex()) {
            val scale = deviation(mode)
            require(scale.isFinite() && scale >= 0)
            for (channel in 0 until channels) {
                val i = index * 2 * channels + channel
                next[i] = random.gaussian() * scale
                if (mode.quadratureNorm > 0) next[i + channels] = random.gaussian() * scale
            }
        }
        return Spectrum(channels, modes, next, domain)
    }

    internal fun accumulate(mode: Int, a: Double, b: Double, into: DoubleArray) {
        val start = mode * channels * 2
        for (channel in 0 until channels) {
            into[channel] += a * values[start + channel] + b * values[start + channels + channel]
        }
    }

    companion object {
        fun heat(time: Double): (SpectralMode) -> Double {
            require(time.isFinite() && time >= 0)
            return { exp(-time * it.frequency * it.frequency) }
        }
    }
}

private fun Random.gaussian(): Double = sqrt(-2 * kotlin.math.ln(1 - nextDouble())) * kotlin.math.cos(TAU * nextDouble())

internal const val TAU = 2 * PI

/** First axis varies fastest. Reuses the existing FFT on each set of parallel lines. */
internal class PeriodicGrid(shape: IntArray) {
    val shape = shape.copyOf()
    val strides = IntArray(shape.size)
    val size: Int

    init {
        require(shape.isNotEmpty() && shape.all { it > 0 && (it and (it - 1)) == 0 }) {
            "FFT axes must have positive power-of-two sample counts"
        }
        var stride = 1
        for (axis in shape.indices) {
            strides[axis] = stride
            stride = Math.multiplyExact(stride, shape[axis])
        }
        size = stride
    }

    fun coordinates(index: Int): IntArray = IntArray(shape.size) { axis -> index / strides[axis] % shape[axis] }

    /** Real Fourier convention with one representative of every conjugate pair. */
    fun decompose(samples: Array<FloatArray>, visit: (mode: IntArray, a: DoubleArray, b: DoubleArray, nyquist: Boolean) -> Unit) {
        require(samples.isNotEmpty() && samples.all { signal -> signal.size == size && signal.all { it.isFinite() } })
        val coefficients = samples.map { signal ->
            FloatArray(Math.multiplyExact(size, 2)).also { data ->
                for (i in signal.indices) data[2 * i] = signal[i]
                transform(data)
            }
        }
        for (index in 0 until size) {
            val bins = coordinates(index)
            val opposite = bins.indices.sumOf { axis ->
                ((shape[axis] - bins[axis]) % shape[axis]) * strides[axis]
            }
            if (opposite < index) continue
            val selfConjugate = index == opposite
            val scale = (if (selfConjugate) 1.0 else 2.0) / size
            val mode = IntArray(shape.size) { axis ->
                val k = bins[axis]
                if (k <= shape[axis] / 2) k else k - shape[axis]
            }
            val a = DoubleArray(coefficients.size) { coefficients[it][2 * index] * scale }
            val b = DoubleArray(coefficients.size) { if (selfConjugate) 0.0 else -coefficients[it][2 * index + 1] * scale }
            visit(mode, a, b, index != 0 && selfConjugate)
        }
    }

    private fun transform(data: FloatArray) {
        for (axis in shape.indices) {
            val stride = strides[axis]
            val length = shape[axis]
            val block = stride * length
            val fft = FFT(length)
            val line = FloatArray(length * 2)
            for (start in 0 until size step block) for (offset in 0 until stride) {
                for (i in 0 until length) {
                    val index = 2 * (start + offset + stride * i)
                    line[2 * i] = data[index]
                    line[2 * i + 1] = data[index + 1]
                }
                fft.forward(line)
                for (i in 0 until length) {
                    val index = 2 * (start + offset + stride * i)
                    data[index] = line[2 * i]
                    data[index + 1] = line[2 * i + 1]
                }
            }
        }
    }
}

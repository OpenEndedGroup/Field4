@file:Suppress("DEPRECATION")

package afield.four.synth

import kotlin.math.*

// ═══════════════════════════════════════════════════════════════════
//  FFT – radix-2 Cooley-Tukey (SIMD-accelerated butterflies)
// ═══════════════════════════════════════════════════════════════════

/**
 * In-place radix-2 FFT and IFFT on interleaved complex buffers.
 *
 * Data layout: `[re0, im0, re1, im1, …, re(N-1), im(N-1)]`
 * where N must be a power of two.
 *
 * ```kotlin
 * val fft = FFT(1024)
 * val buf = FloatArray(2048)   // 1024 complex samples
 * // fill buf with real data (im = 0) …
 * fft.forward(buf)             // now in frequency domain
 * // manipulate spectrum …
 * fft.inverse(buf)             // back to time domain
 * ```
 */
class FFT(val size: Int) {
    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of 2, got $size" }
    }

    private val log2n = Integer.numberOfTrailingZeros(size)

    // pre-computed twiddle factors
    private val twiddleRe = FloatArray(size / 2)
    private val twiddleIm = FloatArray(size / 2)

    init {
        for (k in 0 until size / 2) {
            val angle = -2.0 * PI * k / size
            twiddleRe[k] = cos(angle).toFloat()
            twiddleIm[k] = sin(angle).toFloat()
        }
    }

    /**
     * In-place forward FFT.
     * [data] length must be `2 * size` (interleaved re/im).
     */
    fun forward(data: FloatArray) {
        require(data.size >= 2 * size) { "Buffer too small: need ${2 * size}, got ${data.size}" }
        bitReverse(data)
        butterflies(data, inverse = false)
    }

    /**
     * In-place inverse FFT (with 1/N normalization).
     */
    fun inverse(data: FloatArray) {
        require(data.size >= 2 * size) { "Buffer too small" }
        bitReverse(data)
        butterflies(data, inverse = true)
        // normalize
        val invN = 1f / size
        for (i in 0 until 2 * size) data[i] *= invN
    }

    private fun bitReverse(data: FloatArray) {
        var j = 0
        for (i in 0 until size) {
            if (j > i) {
                // swap complex pair
                val ti = i * 2; val tj = j * 2
                var tmp = data[ti]; data[ti] = data[tj]; data[tj] = tmp
                tmp = data[ti + 1]; data[ti + 1] = data[tj + 1]; data[tj + 1] = tmp
            }
            var m = size shr 1
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }
    }

    private fun butterflies(data: FloatArray, inverse: Boolean) {
        var step = 1
        for (stage in 0 until log2n) {
            val halfStep = step
            step = step shl 1
            val twiddleStride = size / step

            for (group in 0 until size step step) {
                for (pair in 0 until halfStep) {
                    val twIdx = pair * twiddleStride
                    val wr = twiddleRe[twIdx]
                    val wi = if (inverse) -twiddleIm[twIdx] else twiddleIm[twIdx]

                    val evenIdx = (group + pair) * 2
                    val oddIdx = (group + pair + halfStep) * 2

                    val er = data[evenIdx]
                    val ei = data[evenIdx + 1]
                    val or_ = data[oddIdx]
                    val oi = data[oddIdx + 1]

                    // butterfly: even ± twiddle*odd
                    val tr = wr * or_ - wi * oi
                    val ti = wr * oi + wi * or_

                    data[evenIdx]     = er + tr
                    data[evenIdx + 1] = ei + ti
                    data[oddIdx]      = er - tr
                    data[oddIdx + 1]  = ei - ti
                }
            }
        }
    }

    // ── convenience: real-signal FFT ────────────────────────────────

    /**
     * Pack a real signal into interleaved complex format (imaginary = 0).
     */
    fun realToComplex(real: FloatArray, complex: FloatArray, offset: Int = 0, len: Int = size) {
        for (i in 0 until len) {
            complex[i * 2] = real[offset + i]
            complex[i * 2 + 1] = 0f
        }
        // zero-pad if len < size
        for (i in len until size) {
            complex[i * 2] = 0f
            complex[i * 2 + 1] = 0f
        }
    }

    /** Extract real part from interleaved complex. */
    fun complexToReal(complex: FloatArray, real: FloatArray, offset: Int = 0, len: Int = size) {
        for (i in 0 until len) {
            real[offset + i] = complex[i * 2]
        }
    }

    /** Compute magnitude spectrum. */
    fun magnitude(complex: FloatArray, mag: FloatArray, len: Int = size) {
        for (i in 0 until len) {
            val re = complex[i * 2]
            val im = complex[i * 2 + 1]
            mag[i] = sqrt(re * re + im * im)
        }
    }

    /** Compute phase spectrum. */
    fun phase(complex: FloatArray, phase: FloatArray, len: Int = size) {
        for (i in 0 until len) {
            phase[i] = atan2(complex[i * 2 + 1], complex[i * 2])
        }
    }

    /** Reconstruct complex from magnitude + phase. */
    fun polarToComplex(mag: FloatArray, phase: FloatArray, complex: FloatArray, len: Int = size) {
        for (i in 0 until len) {
            complex[i * 2]     = mag[i] * cos(phase[i])
            complex[i * 2 + 1] = mag[i] * sin(phase[i])
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PhaseVocoder – STFT-based time stretch & pitch shift
// ═══════════════════════════════════════════════════════════════════


class PhaseVocoder(
    val source: AudioSample,
    /** Time-stretch ratio: >1 = slower, <1 = faster. */
    val timeStretch: Param = Param(1f),
    /** Pitch-shift ratio: >1 = higher, <1 = lower. */
    val pitchShift: Param = Param(1f),
    val fftSize: Int = 2048,
    val hopFactor: Int = 4  // overlap factor (hop = fftSize / hopFactor)
) : Signal() {
    private val fft = FFT(fftSize)
    private val hopSize = fftSize / hopFactor
    private val window = FloatArray(fftSize)

    // analysis state
    private var analysisPos: Double = 0.0
    private val analysisPhase = FloatArray(fftSize)
    private val synthPhase = FloatArray(fftSize)

    // STFT buffers
    private val analysisBuf = FloatArray(2 * fftSize)
    private val synthBuf = FloatArray(2 * fftSize)
    private val frameBuf = FloatArray(fftSize)

    // overlap-add output buffer
    private val olaBuffer = FloatArray(fftSize * 2)
    private var olaWritePos = 0
    private var olaReadPos = 0
    private var samplesUntilNextHop = 0

    // param buffers
    private var tsBuf = FloatArray(0)
    private var psBuf = FloatArray(0)

    init {
        // Hann window
        for (i in 0 until fftSize) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / fftSize)).toFloat()
        }
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (tsBuf.size < numFrames) {
            tsBuf = FloatArray(numFrames); psBuf = FloatArray(numFrames)
        }
        timeStretch.render(tsBuf, numFrames, ctx)
        pitchShift.render(psBuf, numFrames, ctx)

        val srcLen = source.lengthSamples

        for (i in 0 until numFrames) {
            // Need to produce a new hop?
            if (samplesUntilNextHop <= 0) {
                processHop(tsBuf[i], psBuf[i], srcLen)
                samplesUntilNextHop = hopSize
            }

            // Read from OLA buffer
            output[i] = olaBuffer[olaReadPos % olaBuffer.size]
            olaBuffer[olaReadPos % olaBuffer.size] = 0f  // clear after reading
            olaReadPos++
            samplesUntilNextHop--
        }
    }

    private fun processHop(stretch: Float, pitchRatio: Float, srcLen: Int) {
        val analysisHop = (hopSize * stretch.toDouble().coerceAtLeast(0.1)).toInt()
            .coerceIn(1, srcLen / 2)

        // Read analysis frame from source
        val pos = analysisPos.toInt()
        for (j in 0 until fftSize) {
            val srcIdx = (pos + j) % srcLen
            frameBuf[j] = source.data[if (srcIdx >= 0) srcIdx else srcIdx + srcLen] * window[j]
        }
        analysisPos += analysisHop
        if (analysisPos >= srcLen) analysisPos -= srcLen

        // Forward FFT
        fft.realToComplex(frameBuf, analysisBuf)
        fft.forward(analysisBuf)

        // Phase processing
        val twoPi = (2.0 * PI).toFloat()
        val expectedDPhi = twoPi * hopSize / fftSize

        for (bin in 0 until fftSize / 2 + 1) {
            val re = analysisBuf[bin * 2]
            val im = analysisBuf[bin * 2 + 1]
            val mag = sqrt(re * re + im * im)
            val phi = atan2(im, re)

            // Phase difference and unwrapping
            var dphi = phi - analysisPhase[bin]
            analysisPhase[bin] = phi
            dphi -= bin * expectedDPhi
            // wrap to [-π, π]
            dphi = dphi - twoPi * kotlin.math.round(dphi / twoPi)
            dphi += bin * expectedDPhi

            // Pitch shifting: scale the frequency
            val trueFreq = dphi * pitchRatio

            // Accumulate synthesis phase
            synthPhase[bin] += trueFreq
            synthBuf[bin * 2] = mag * cos(synthPhase[bin])
            synthBuf[bin * 2 + 1] = mag * sin(synthPhase[bin])
        }

        // Mirror for negative frequencies
        for (bin in fftSize / 2 + 1 until fftSize) {
            val mirror = fftSize - bin
            synthBuf[bin * 2] = synthBuf[mirror * 2]
            synthBuf[bin * 2 + 1] = -synthBuf[mirror * 2 + 1]
        }

        // Inverse FFT
        fft.inverse(synthBuf)

        // Overlap-add into output buffer
        for (j in 0 until fftSize) {
            val idx = (olaWritePos + j) % olaBuffer.size
            olaBuffer[idx] += synthBuf[j * 2] * window[j]
        }
        olaWritePos = (olaWritePos + hopSize) % olaBuffer.size
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SpectralFilter – frequency-domain filtering
// ═══════════════════════════════════════════════════════════════════


class SpectralFilter(
    val fftSize: Int = 1024,
    val hopFactor: Int = 4,
    val transferFunction: (bin: Int, freqHz: Float) -> Float
) : SignalProcessor() {
    private val fft = FFT(fftSize)
    private val hopSize = fftSize / hopFactor
    private val window = FloatArray(fftSize)

    private val inputRing = FloatArray(fftSize * 2)
    private var inputWritePos = 0
    private val complexBuf = FloatArray(2 * fftSize)
    private val olaBuffer = FloatArray(fftSize * 2)
    private var olaWritePos = 0
    private var olaReadPos = 0
    private var hopCount = 0

    private var inBuf = FloatArray(0)

    init {
        for (i in 0 until fftSize) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / fftSize)).toFloat()
        }
    }

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) inBuf = FloatArray(numFrames)
        input.render(inBuf, numFrames, ctx)
        val binWidth = ctx.sampleRate / fftSize

        for (i in 0 until numFrames) {
            inputRing[inputWritePos % inputRing.size] = inBuf[i]
            inputWritePos++
            hopCount++

            if (hopCount >= hopSize) {
                hopCount = 0
                processFrame(binWidth)
            }

            output[i] = olaBuffer[olaReadPos % olaBuffer.size]
            olaBuffer[olaReadPos % olaBuffer.size] = 0f
            olaReadPos++
        }
    }

    private fun processFrame(binWidth: Float) {
        // Extract and window
        for (j in 0 until fftSize) {
            val idx = (inputWritePos - fftSize + j + inputRing.size) % inputRing.size
            complexBuf[j * 2] = inputRing[idx] * window[j]
            complexBuf[j * 2 + 1] = 0f
        }

        fft.forward(complexBuf)

        // Apply transfer function
        for (bin in 0 until fftSize / 2 + 1) {
            val gain = transferFunction(bin, bin * binWidth)
            complexBuf[bin * 2] *= gain
            complexBuf[bin * 2 + 1] *= gain
            if (bin > 0 && bin < fftSize / 2) {
                val mirror = fftSize - bin
                complexBuf[mirror * 2] *= gain
                complexBuf[mirror * 2 + 1] *= gain
            }
        }

        fft.inverse(complexBuf)

        // Overlap-add
        for (j in 0 until fftSize) {
            val idx = (olaWritePos + j) % olaBuffer.size
            olaBuffer[idx] += complexBuf[j * 2] * window[j]
        }
        olaWritePos = (olaWritePos + hopSize) % olaBuffer.size
    }
}

@file:afield.CompletionCategory("Synthesis")
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

// ═══════════════════════════════════════════════════════════════════
//  SpectralWavetable – morphable single-cycle frames in the frequency domain
// ═══════════════════════════════════════════════════════════════════

class SpectralWavetable(
    val frameSize: Int = 2048,
    val frameCount: Int = 8,
    val removeDcOnWrite: Boolean = true,
    val normalizeOnWrite: Boolean = true
) {
    init {
        require(frameSize > 0 && frameSize and (frameSize - 1) == 0) {
            "frameSize must be a power of 2, got $frameSize"
        }
        require(frameCount > 0) { "frameCount must be positive, got $frameCount" }
    }

    private val fft = FFT(frameSize)
    private val lock = Any()
    private val frames = Array(frameCount) { FloatArray(frameSize) }
    private val spectra = Array(frameCount) { FloatArray(2 * frameSize) }

    @Volatile private var tableVersion: Long = 0L
    @Volatile var lastAccessPosition: Float = 0f
        private set
    @Volatile var lastWritePosition: Float = 0f
        private set
    @Volatile var lastHarmonicLimit: Int = frameSize / 2
        private set

    init {
        seedClassicMorph()
    }

    fun seedClassicMorph(): SpectralWavetable {
        for (i in 0 until frameCount) {
            val t = if (frameCount == 1) 0f else i.toFloat() / (frameCount - 1).toFloat()
            setFrame(i, fn = { phase: Float ->
                val sine = sin(2.0 * PI * phase).toFloat()
                val saw = (2f * phase - 1f)
                val square = if (phase < 0.5f) 1f else -1f
                val tri = 1f - 4f * abs(phase - 0.5f)
                when {
                    t < 0.33f -> {
                        val mix = t / 0.33f
                        sine * (1f - mix) + tri * mix
                    }
                    t < 0.66f -> {
                        val mix = (t - 0.33f) / 0.33f
                        tri * (1f - mix) + saw * mix
                    }
                    else -> {
                        val mix = (t - 0.66f) / 0.34f
                        saw * (1f - mix) + square * mix
                    }
                }
            })
        }
        return this
    }

    fun version(): Long = tableVersion

    fun setFrame(
        index: Int,
        waveform: FloatArray,
        normalize: Boolean = normalizeOnWrite,
        removeDc: Boolean = removeDcOnWrite
    ): SpectralWavetable {
        val safeIndex = index.coerceIn(0, frameCount - 1)
        val prepared = FloatArray(frameSize)
        val complex = FloatArray(2 * frameSize)
        prepareWaveform(waveform, prepared, normalize, removeDc)
        fft.realToComplex(prepared, complex)
        fft.forward(complex)

        synchronized(lock) {
            System.arraycopy(prepared, 0, frames[safeIndex], 0, frameSize)
            System.arraycopy(complex, 0, spectra[safeIndex], 0, complex.size)
            tableVersion++
            lastWritePosition = framePosition(safeIndex)
        }
        return this
    }

    fun setFrame(
        index: Int,
        sample: AudioSample,
        position: Double = 0.0,
        cycleLength: Double = sample.lengthSamples.toDouble(),
        normalize: Boolean = normalizeOnWrite,
        removeDc: Boolean = removeDcOnWrite
    ): SpectralWavetable {
        val waveform = FloatArray(frameSize)
        val step = if (frameSize <= 0) 0.0 else cycleLength / frameSize.toDouble()
        for (i in waveform.indices) {
            waveform[i] = sample.readInterp(position + i * step)
        }
        return setFrame(index, waveform, normalize, removeDc)
    }

    fun setFrame(
        index: Int,
        fn: (phase: Float) -> Float,
        normalize: Boolean = normalizeOnWrite,
        removeDc: Boolean = removeDcOnWrite
    ): SpectralWavetable {
        val waveform = FloatArray(frameSize) { i -> fn(i.toFloat() / frameSize.toFloat()) }
        return setFrame(index, waveform, normalize, removeDc)
    }

    fun capture(
        position: Float,
        waveform: FloatArray,
        blend: Float = 1f,
        normalize: Boolean = normalizeOnWrite,
        removeDc: Boolean = removeDcOnWrite
    ): SpectralWavetable {
        val prepared = FloatArray(frameSize)
        val complex = FloatArray(2 * frameSize)
        prepareWaveform(waveform, prepared, normalize, removeDc)
        fft.realToComplex(prepared, complex)
        fft.forward(complex)

        val safePosition = position.coerceIn(0f, 1f)
        val safeBlend = blend.coerceIn(0f, 1f)
        val scaled = safePosition * (frameCount - 1).toFloat()
        val low = floor(scaled).toInt().coerceIn(0, frameCount - 1)
        val high = min(frameCount - 1, low + 1)
        val frac = scaled - low.toFloat()

        synchronized(lock) {
            if (low == high) {
                applyBlendToFrame(low, prepared, complex, safeBlend)
            } else {
                applyBlendToFrame(low, prepared, complex, safeBlend * (1f - frac))
                applyBlendToFrame(high, prepared, complex, safeBlend * frac)
            }
            tableVersion++
            lastWritePosition = safePosition
        }
        return this
    }

    fun frame(index: Int): FloatArray = synchronized(lock) {
        frames[index.coerceIn(0, frameCount - 1)].copyOf()
    }

    fun waveformAt(position: Float = lastAccessPosition, harmonicLimit: Int = frameSize / 2): FloatArray {
        val output = FloatArray(frameSize)
        renderMorph(
            position = position,
            harmonicLimit = harmonicLimit,
            output = output,
            spectrumA = FloatArray(2 * frameSize),
            spectrumB = FloatArray(2 * frameSize),
            morphSpectrum = FloatArray(2 * frameSize)
        )
        return output
    }

    fun snapshot(position: Float = lastAccessPosition, harmonicLimit: Int = frameSize / 2): AudioSnapshot =
        AudioSnapshot(waveformAt(position, harmonicLimit), sampleRate = frameSize)

    internal fun renderMorph(
        position: Float,
        harmonicLimit: Int,
        output: FloatArray,
        spectrumA: FloatArray,
        spectrumB: FloatArray,
        morphSpectrum: FloatArray
    ) {
        require(output.size >= frameSize) { "output must have at least $frameSize samples" }
        require(spectrumA.size >= 2 * frameSize) { "spectrumA must have at least ${2 * frameSize} floats" }
        require(spectrumB.size >= 2 * frameSize) { "spectrumB must have at least ${2 * frameSize} floats" }
        require(morphSpectrum.size >= 2 * frameSize) { "morphSpectrum must have at least ${2 * frameSize} floats" }

        val safePosition = position.coerceIn(0f, 1f)
        val safeHarmonicLimit = harmonicLimit.coerceIn(1, frameSize / 2)
        val scaled = safePosition * (frameCount - 1).toFloat()
        val low = floor(scaled).toInt().coerceIn(0, frameCount - 1)
        val high = min(frameCount - 1, low + 1)
        val frac = scaled - low.toFloat()

        synchronized(lock) {
            System.arraycopy(spectra[low], 0, spectrumA, 0, 2 * frameSize)
            if (high != low) {
                System.arraycopy(spectra[high], 0, spectrumB, 0, 2 * frameSize)
            }
            lastAccessPosition = safePosition
            lastHarmonicLimit = safeHarmonicLimit
        }

        if (high == low || frac <= 1e-6f) {
            System.arraycopy(spectrumA, 0, morphSpectrum, 0, 2 * frameSize)
        } else {
            val invFrac = 1f - frac
            for (i in 0 until 2 * frameSize) {
                morphSpectrum[i] = spectrumA[i] * invFrac + spectrumB[i] * frac
            }
        }

        limitHarmonics(morphSpectrum, safeHarmonicLimit)
        fft.inverse(morphSpectrum)
        fft.complexToReal(morphSpectrum, output)
    }

    private fun applyBlendToFrame(index: Int, waveform: FloatArray, spectrum: FloatArray, amount: Float) {
        if (amount <= 0f) return
        val safeAmount = amount.coerceIn(0f, 1f)
        if (safeAmount >= 0.9999f) {
            System.arraycopy(waveform, 0, frames[index], 0, frameSize)
            System.arraycopy(spectrum, 0, spectra[index], 0, 2 * frameSize)
            return
        }

        val inv = 1f - safeAmount
        val targetFrame = frames[index]
        val targetSpectrum = spectra[index]
        for (i in 0 until frameSize) {
            targetFrame[i] = targetFrame[i] * inv + waveform[i] * safeAmount
        }
        for (i in 0 until 2 * frameSize) {
            targetSpectrum[i] = targetSpectrum[i] * inv + spectrum[i] * safeAmount
        }
    }

    private fun framePosition(index: Int): Float =
        if (frameCount <= 1) 0f else index.toFloat() / (frameCount - 1).toFloat()

    private fun prepareWaveform(source: FloatArray, destination: FloatArray, normalize: Boolean, removeDc: Boolean) {
        if (source.isEmpty()) {
            Buf.clear(destination, 0, destination.size)
            return
        }

        if (source.size == destination.size) {
            System.arraycopy(source, 0, destination, 0, destination.size)
        } else {
            val scale = source.size.toDouble() / destination.size.toDouble()
            for (i in destination.indices) {
                destination[i] = readPeriodicHermite(source, i * scale)
            }
        }

        if (removeDc) {
            val mean = Buf.sum(destination, 0, destination.size) / destination.size.toFloat()
            if (mean != 0f) {
                for (i in destination.indices) destination[i] -= mean
            }
        }

        if (normalize) {
            val peak = Buf.peak(destination, 0, destination.size)
            if (peak > 1e-6f) {
                Buf.scale(destination, 1f / peak, 0, destination.size)
            }
        }
    }

    private fun limitHarmonics(spectrum: FloatArray, harmonicLimit: Int) {
        val safeLimit = harmonicLimit.coerceIn(1, frameSize / 2)
        val nyquistBin = frameSize / 2

        for (bin in safeLimit + 1 until nyquistBin) {
            val pos = bin * 2
            val neg = (frameSize - bin) * 2
            spectrum[pos] = 0f
            spectrum[pos + 1] = 0f
            spectrum[neg] = 0f
            spectrum[neg + 1] = 0f
        }

        if (safeLimit < nyquistBin) {
            val nyquist = nyquistBin * 2
            spectrum[nyquist] = 0f
            spectrum[nyquist + 1] = 0f
        }
    }

    private fun readPeriodicHermite(values: FloatArray, position: Double): Float {
        val size = values.size
        val base = floor(position).toInt()
        val frac = (position - base).toFloat()
        fun sample(offset: Int): Float {
            val idx = ((base + offset) % size + size) % size
            return values[idx]
        }
        val s0 = sample(-1)
        val s1 = sample(0)
        val s2 = sample(1)
        val s3 = sample(2)
        val c0 = s1
        val c1 = 0.5f * (s2 - s0)
        val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
        val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
        return ((c3 * frac + c2) * frac + c1) * frac + c0
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SpectralWavetableRecorder – live signal -> wavetable capture
// ═══════════════════════════════════════════════════════════════════

class SpectralWavetableRecorder(
    val table: SpectralWavetable,
    /** Morph position to write to, normalized 0…1 across the table. */
    val position: Param = Param(0f),
    /** Blend amount into the addressed frame(s). */
    val blend: Param = Param(0.5f),
    /** Capture updates per second. */
    val captureRate: Param = Param(16f),
    /** Gain applied before writing into the table. */
    val inputGain: Param = Param(1f),
    /** When positive, table writing is paused. */
    val freeze: Signal = Const(0f),
    val normalizeWrites: Boolean = true,
    val removeDcWrites: Boolean = true
) : SignalProcessor() {
    private var ring = FloatArray(table.frameSize)
    private var writePos = 0
    private var capturePhase = 0f
    private var captureFrame = FloatArray(table.frameSize)

    private var inBuf = FloatArray(0)
    private var positionBuf = FloatArray(0)
    private var blendBuf = FloatArray(0)
    private var rateBuf = FloatArray(0)
    private var gainBuf = FloatArray(0)
    private var freezeBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ring.size != table.frameSize) {
            ring = FloatArray(table.frameSize)
            writePos = 0
            captureFrame = FloatArray(table.frameSize)
        }
        if (inBuf.size < numFrames) {
            inBuf = FloatArray(numFrames)
            positionBuf = FloatArray(numFrames)
            blendBuf = FloatArray(numFrames)
            rateBuf = FloatArray(numFrames)
            gainBuf = FloatArray(numFrames)
            freezeBuf = FloatArray(numFrames)
        }

        input.render(inBuf, numFrames, ctx)
        position.render(positionBuf, numFrames, ctx)
        blend.render(blendBuf, numFrames, ctx)
        captureRate.render(rateBuf, numFrames, ctx)
        inputGain.render(gainBuf, numFrames, ctx)
        freeze.render(freezeBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val dry = inBuf[i]
            output[i] = dry

            ring[writePos] = dry * gainBuf[i]
            writePos = (writePos + 1) % ring.size

            if (freezeBuf[i] > 0f) continue

            capturePhase += rateBuf[i].coerceAtLeast(0f) * ctx.inverseSampleRate
            while (capturePhase >= 1f) {
                capturePhase -= 1f
                snapshotRing()
                table.capture(
                    position = positionBuf[i].coerceIn(0f, 1f),
                    waveform = captureFrame,
                    blend = blendBuf[i].coerceIn(0f, 1f),
                    normalize = normalizeWrites,
                    removeDc = removeDcWrites
                )
            }
        }
    }

    private fun snapshotRing() {
        val start = (writePos - table.frameSize + ring.size) % ring.size
        val first = min(table.frameSize, ring.size - start)
        System.arraycopy(ring, start, captureFrame, 0, first)
        if (first < table.frameSize) {
            System.arraycopy(ring, 0, captureFrame, first, table.frameSize - first)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SpectralWavetableOsc – morphable band-limited wavetable oscillator
// ═══════════════════════════════════════════════════════════════════

class SpectralWavetableOsc(
    val table: SpectralWavetable,
    val freq: Param = Param(110f),
    /** Morph position, normalized 0…1 across the table frames. */
    val position: Param = Param(0f),
    /** Phase offset in cycles. */
    val phaseOffset: Param = Param(0f),
    /** Fine detune in cents. */
    val detuneCents: Param = Param(0f),
    /** 1 = full band-limited table, lower values darken the result. */
    val brightness: Param = Param(1f),
    val segmentSize: Int = 32
) : Signal() {
    private var phase: Double = 0.0

    private var freqBuf = FloatArray(0)
    private var positionBuf = FloatArray(0)
    private var phaseOffsetBuf = FloatArray(0)
    private var detuneBuf = FloatArray(0)
    private var brightnessBuf = FloatArray(0)

    private val tableA = FloatArray(table.frameSize)
    private val tableB = FloatArray(table.frameSize)
    private val spectrumA = FloatArray(2 * table.frameSize)
    private val spectrumB = FloatArray(2 * table.frameSize)
    private val morphSpectrumA = FloatArray(2 * table.frameSize)
    private val morphSpectrumB = FloatArray(2 * table.frameSize)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (freqBuf.size < numFrames) {
            freqBuf = FloatArray(numFrames)
            positionBuf = FloatArray(numFrames)
            phaseOffsetBuf = FloatArray(numFrames)
            detuneBuf = FloatArray(numFrames)
            brightnessBuf = FloatArray(numFrames)
        }

        freq.render(freqBuf, numFrames, ctx)
        position.render(positionBuf, numFrames, ctx)
        phaseOffset.render(phaseOffsetBuf, numFrames, ctx)
        detuneCents.render(detuneBuf, numFrames, ctx)
        brightness.render(brightnessBuf, numFrames, ctx)

        val safeSegmentSize = segmentSize.coerceAtLeast(8)
        var start = 0
        while (start < numFrames) {
            val end = min(numFrames, start + safeSegmentSize)
            val positionStart = positionBuf[start].coerceIn(0f, 1f)
            val positionEnd = positionBuf[end - 1].coerceIn(0f, 1f)

            var maxFreq = 0f
            var avgBrightness = 0f
            for (i in start until end) {
                maxFreq = max(maxFreq, abs(freqBuf[i]))
                avgBrightness += brightnessBuf[i]
            }
            avgBrightness /= (end - start).toFloat()
            val harmonicLimit = harmonicLimit(maxFreq, avgBrightness, ctx)

            table.renderMorph(
                position = positionStart,
                harmonicLimit = harmonicLimit,
                output = tableA,
                spectrumA = spectrumA,
                spectrumB = spectrumB,
                morphSpectrum = morphSpectrumA
            )

            val morphingWithinSegment = abs(positionEnd - positionStart) > 1e-4f
            if (morphingWithinSegment) {
                table.renderMorph(
                    position = positionEnd,
                    harmonicLimit = harmonicLimit,
                    output = tableB,
                    spectrumA = spectrumA,
                    spectrumB = spectrumB,
                    morphSpectrum = morphSpectrumB
                )
            }

            val span = (end - start - 1).coerceAtLeast(1)
            for (i in start until end) {
                val offsetCycles = phaseOffsetBuf[i].toDouble()
                val detuneRatio = 2.0.pow(detuneBuf[i].toDouble() / 1200.0)
                val lookupPhase = wrapPhase(phase + offsetCycles)
                val sampleA = readWavetable(tableA, lookupPhase)
                val sample = if (morphingWithinSegment) {
                    val sampleB = readWavetable(tableB, lookupPhase)
                    val mix = (i - start).toFloat() / span.toFloat()
                    sampleA + (sampleB - sampleA) * mix
                } else {
                    sampleA
                }

                output[i] = sample
                phase = wrapPhase(phase + freqBuf[i].toDouble() * detuneRatio * ctx.inverseSampleRate.toDouble())
            }

            start = end
        }
    }

    private fun harmonicLimit(freqHz: Float, brightness: Float, ctx: SynthContext): Int {
        val safeFreq = abs(freqHz).coerceAtLeast(1f)
        val nyquistLimited = (ctx.nyquist / safeFreq).toInt().coerceIn(1, table.frameSize / 2)
        val brightnessScale = brightness.coerceIn(0.05f, 1f)
        return (nyquistLimited * brightnessScale).toInt().coerceIn(1, table.frameSize / 2)
    }

    private fun wrapPhase(value: Double): Double = value - floor(value)

    private fun readWavetable(waveform: FloatArray, phase: Double): Float {
        val pos = phase * waveform.size
        val idx = floor(pos).toInt()
        val frac = (pos - idx).toFloat()

        fun sample(offset: Int): Float {
            val wrapped = ((idx + offset) % waveform.size + waveform.size) % waveform.size
            return waveform[wrapped]
        }

        val s0 = sample(-1)
        val s1 = sample(0)
        val s2 = sample(1)
        val s3 = sample(2)
        val c0 = s1
        val c1 = 0.5f * (s2 - s0)
        val c2 = s0 - 2.5f * s1 + 2f * s2 - 0.5f * s3
        val c3 = 0.5f * (s3 - s0) + 1.5f * (s1 - s2)
        return ((c3 * frac + c2) * frac + c1) * frac + c0
    }
}

@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.*

/**
 * Frequency-domain / spectral analysis family for the stdlib DSP toolkit.
 *
 * This is the frequency-domain companion to [timeseries.kt] (which is entirely time-domain).
 * It is self-contained and additive: a pure-Kotlin FFT, analysis windows, a [Spectrum] and a
 * [Spectrogram] (STFT), classic spectral features (centroid / spread / rolloff / flux / flatness /
 * RMS / energy), and streaming [FloatFilter] wrappers so the spectral features drop straight into
 * the existing time-domain filter chain in `timeseries.kt` (compose with `then`).
 *
 * Everything is plain `Double`/`Float` math — no external DSP dependency — so it works headless in
 * a box, a test, or a docs capture. The FFT is an iterative Cooley–Tukey radix-2 core with a
 * Bluestein (chirp-z) fallback so arbitrary (non-power-of-two) lengths transform exactly.
 */

// ---------------------------------------------------------------------------------------------
// FFT core — iterative Cooley–Tukey radix-2 with a Bluestein (chirp-z) fallback.
//
// Conventions:
//   forward  X[k] = Σ_n x[n] · exp(-2πi·kn/N)
//   inverse  x[n] = (1/N) Σ_k X[k] · exp(+2πi·kn/N)
// The radix-2 kernel is the classic public-domain in-place decimation-in-time transform; the
// Bluestein routine reduces an arbitrary-length DFT to a power-of-two convolution.
// ---------------------------------------------------------------------------------------------

object FFT {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Smallest power of two >= [n] (>= 1). */
    fun nextPowerOfTwo(n: Int): Int {
        var m = 1
        while (m < n) m = m shl 1
        return m
    }

    /**
     * Forward FFT in place. [re]/[im] must be the same length; any length >= 1 is accepted
     * (power-of-two goes through the radix-2 kernel, otherwise Bluestein).
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == im.size) { "real/imag arrays must match length" }
        val n = re.size
        if (n == 0) return
        if (isPowerOfTwo(n)) transformRadix2(re, im) else transformBluestein(re, im)
    }

    /**
     * Inverse FFT in place, **normalized** (divides by N). By the standard identity the inverse is
     * the forward transform with real/imag swapped, then scaled by 1/N.
     */
    fun inverseTransform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n == 0) return
        transform(im, re) // swap -> unnormalized inverse
        val inv = 1.0 / n
        for (i in 0 until n) {
            re[i] *= inv
            im[i] *= inv
        }
    }

    /** In-place radix-2 decimation-in-time forward transform; requires power-of-two length. */
    fun transformRadix2(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(isPowerOfTwo(n)) { "radix-2 requires a power-of-two length, got $n" }
        if (n == 1) return
        val levels = Integer.numberOfTrailingZeros(n) // = log2(n)

        // Trig lookup tables: cos/sin of 2πk/n for k in 0 until n/2.
        val half = n / 2
        val cosTable = DoubleArray(half)
        val sinTable = DoubleArray(half)
        for (i in 0 until half) {
            val angle = 2.0 * PI * i / n
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }

        // Bit-reversed addressing.
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // Butterflies.
        var size = 2
        while (size <= n) {
            val halfsize = size / 2
            val tablestep = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + halfsize) {
                    val l = j + halfsize
                    val tpre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    j++
                    k += tablestep
                }
                i += size
            }
            if (size == n) break
            size = size shl 1
        }
    }

    /** In-place arbitrary-length forward transform via Bluestein's chirp-z algorithm. */
    fun transformBluestein(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(re.size == im.size)
        // Convolution length: a power of two >= 2n+1 so the cyclic convolution is acyclic.
        val m = nextPowerOfTwo(2 * n + 1)

        // Chirp trig tables: angle = π·(i²  mod 2n)/n.
        val cosTable = DoubleArray(n)
        val sinTable = DoubleArray(n)
        for (i in 0 until n) {
            val j = ((i.toLong() * i) % (2L * n)).toInt()
            val angle = PI * j / n
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }

        val areal = DoubleArray(m)
        val aimag = DoubleArray(m)
        for (i in 0 until n) {
            areal[i] = re[i] * cosTable[i] + im[i] * sinTable[i]
            aimag[i] = -re[i] * sinTable[i] + im[i] * cosTable[i]
        }

        val breal = DoubleArray(m)
        val bimag = DoubleArray(m)
        breal[0] = cosTable[0]
        bimag[0] = sinTable[0]
        for (i in 1 until n) {
            breal[i] = cosTable[i]; breal[m - i] = cosTable[i]
            bimag[i] = sinTable[i]; bimag[m - i] = sinTable[i]
        }

        val creal = DoubleArray(m)
        val cimag = DoubleArray(m)
        convolveComplex(areal, aimag, breal, bimag, creal, cimag)

        for (i in 0 until n) {
            re[i] = creal[i] * cosTable[i] + cimag[i] * sinTable[i]
            im[i] = -creal[i] * sinTable[i] + cimag[i] * cosTable[i]
        }
    }

    /** Cyclic complex convolution of length-m power-of-two vectors, via radix-2 FFT. */
    private fun convolveComplex(
        xre: DoubleArray, xim: DoubleArray,
        yre: DoubleArray, yim: DoubleArray,
        outRe: DoubleArray, outIm: DoubleArray,
    ) {
        val n = xre.size
        val ar = xre.copyOf(); val ai = xim.copyOf()
        val br = yre.copyOf(); val bi = yim.copyOf()
        transformRadix2(ar, ai)
        transformRadix2(br, bi)
        for (i in 0 until n) {
            val tr = ar[i] * br[i] - ai[i] * bi[i]
            ai[i] = ai[i] * br[i] + ar[i] * bi[i]
            ar[i] = tr
        }
        inverseTransform(ar, ai)
        for (i in 0 until n) {
            outRe[i] = ar[i]
            outIm[i] = ai[i]
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Analysis windows.
// ---------------------------------------------------------------------------------------------

enum class WindowType { RECTANGULAR, HANN, HAMMING, BLACKMAN }

object Windows {

    /** Build a length-[n] window of the given [type]. Symmetric (periodic=false) by default. */
    fun make(type: WindowType, n: Int, periodic: Boolean = false): DoubleArray = when (type) {
        WindowType.RECTANGULAR -> rectangular(n)
        WindowType.HANN -> hann(n, periodic)
        WindowType.HAMMING -> hamming(n, periodic)
        WindowType.BLACKMAN -> blackman(n, periodic)
    }

    fun rectangular(n: Int): DoubleArray = DoubleArray(n) { 1.0 }

    fun hann(n: Int, periodic: Boolean = false): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        val denom = if (periodic) n.toDouble() else (n - 1).toDouble()
        return DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / denom) }
    }

    fun hamming(n: Int, periodic: Boolean = false): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        val denom = if (periodic) n.toDouble() else (n - 1).toDouble()
        return DoubleArray(n) { 0.54 - 0.46 * cos(2.0 * PI * it / denom) }
    }

    fun blackman(n: Int, periodic: Boolean = false): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        val denom = if (periodic) n.toDouble() else (n - 1).toDouble()
        return DoubleArray(n) {
            val w = 2.0 * PI * it / denom
            0.42 - 0.5 * cos(w) + 0.08 * cos(2.0 * w)
        }
    }

    /** Coherent gain (mean) of a window — used to compensate amplitude when a window is applied. */
    fun coherentGain(window: DoubleArray): Double {
        if (window.isEmpty()) return 1.0
        var s = 0.0
        for (w in window) s += w
        return s / window.size
    }
}

// ---------------------------------------------------------------------------------------------
// Spectrum — the result of one FFT, with sample-rate-aware helpers.
// ---------------------------------------------------------------------------------------------

/**
 * A single complex spectrum plus the [sampleRate] it was measured at. [re]/[im] are the full
 * length-N transform; for a real input the useful ("one-sided") part is bins `0..N/2`.
 */
class Spectrum(
    val re: DoubleArray,
    val im: DoubleArray,
    val sampleRate: Double,
) {
    val size: Int get() = re.size

    /** Number of one-sided bins (DC .. Nyquist), i.e. N/2 + 1 for even N. */
    val binCount: Int get() = size / 2 + 1

    /** Frequency (Hz) of bin [k]. */
    fun binFrequency(k: Int): Double = k * sampleRate / size

    /** Frequencies (Hz) of the one-sided bins 0..N/2. */
    fun frequencies(): DoubleArray = DoubleArray(binCount) { binFrequency(it) }

    /** Full-length magnitude |X[k]|. */
    fun magnitude(): DoubleArray = DoubleArray(size) { hypot(re[it], im[it]) }

    /** One-sided magnitude (bins 0..N/2). */
    fun magnitudeOneSided(): DoubleArray = DoubleArray(binCount) { hypot(re[it], im[it]) }

    /** One-sided power |X[k]|². */
    fun powerOneSided(): DoubleArray = DoubleArray(binCount) { re[it] * re[it] + im[it] * im[it] }

    /** One-sided magnitude in decibels, floored at [floorDb]. */
    fun magnitudeDbOneSided(floorDb: Double = -120.0, ref: Double = 1.0): DoubleArray {
        val mags = magnitudeOneSided()
        return DoubleArray(mags.size) {
            val db = 20.0 * log10((mags[it] / ref).coerceAtLeast(1e-12))
            max(db, floorDb)
        }
    }

    /** Phase (radians) per bin. */
    fun phase(): DoubleArray = DoubleArray(size) { atan2(im[it], re[it]) }

    /** Bin index of the peak one-sided magnitude. */
    fun peakBin(): Int {
        val mags = magnitudeOneSided()
        var best = 0
        for (i in mags.indices) if (mags[i] > mags[best]) best = i
        return best
    }

    /** Frequency (Hz) of the dominant one-sided component. */
    fun peakFrequency(): Double = binFrequency(peakBin())

    // Feature shortcuts (see [SpectralFeatures]).
    fun centroid(): Double = SpectralFeatures.centroid(magnitudeOneSided(), sampleRate)
    fun spread(): Double = SpectralFeatures.spread(magnitudeOneSided(), sampleRate)
    fun rolloff(rolloffFraction: Double = 0.85): Double =
        SpectralFeatures.rolloff(magnitudeOneSided(), sampleRate, rolloffFraction)
    fun flatness(): Double = SpectralFeatures.flatness(magnitudeOneSided())
}

// ---------------------------------------------------------------------------------------------
// Forward / inverse convenience over real signals.
// ---------------------------------------------------------------------------------------------

/** Forward FFT of a real signal. [sampleRate] defaults to the signal length (bins == cycles/frame). */
fun fft(signal: DoubleArray, sampleRate: Double = signal.size.toDouble()): Spectrum {
    val re = signal.copyOf()
    val im = DoubleArray(signal.size)
    FFT.transform(re, im)
    return Spectrum(re, im, sampleRate)
}

fun fft(signal: FloatArray, sampleRate: Double = signal.size.toDouble()): Spectrum =
    fft(DoubleArray(signal.size) { signal[it].toDouble() }, sampleRate)

fun fft(signal: List<Float>, sampleRate: Double = signal.size.toDouble()): Spectrum =
    fft(DoubleArray(signal.size) { signal[it].toDouble() }, sampleRate)

/** Windowed forward FFT: multiplies by [window] first (amplitude compensated by coherent gain). */
fun fft(signal: DoubleArray, window: WindowType, sampleRate: Double = signal.size.toDouble()): Spectrum {
    val w = Windows.make(window, signal.size)
    val gain = Windows.coherentGain(w).coerceAtLeast(1e-9)
    val re = DoubleArray(signal.size) { signal[it] * w[it] / gain }
    val im = DoubleArray(signal.size)
    FFT.transform(re, im)
    return Spectrum(re, im, sampleRate)
}

/** Inverse FFT; returns the real part of the reconstructed signal. */
fun ifft(spectrum: Spectrum): DoubleArray {
    val re = spectrum.re.copyOf()
    val im = spectrum.im.copyOf()
    FFT.inverseTransform(re, im)
    return re
}

/** Inverse FFT keeping both real and imaginary parts. */
fun ifftComplex(spectrum: Spectrum): Pair<DoubleArray, DoubleArray> {
    val re = spectrum.re.copyOf()
    val im = spectrum.im.copyOf()
    FFT.inverseTransform(re, im)
    return re to im
}

// ---------------------------------------------------------------------------------------------
// Spectrogram (STFT).
// ---------------------------------------------------------------------------------------------

/**
 * The result of a short-time Fourier transform: a stack of per-frame one-sided magnitude spectra.
 * `magnitudes[frame][bin]`, bin in 0..fftSize/2. Complex frames are kept too so an inverse-STFT
 * overlap-add reconstruction is possible ([istft]).
 */
class Spectrogram(
    /** Complex STFT frames (full length-fftSize), kept for reconstruction. */
    val framesRe: Array<DoubleArray>,
    val framesIm: Array<DoubleArray>,
    val sampleRate: Double,
    val fftSize: Int,
    val hopSize: Int,
    val window: WindowType,
) {
    val numFrames: Int get() = framesRe.size
    val numBins: Int get() = fftSize / 2 + 1

    fun binFrequency(k: Int): Double = k * sampleRate / fftSize

    /** Center time (seconds) of frame [i]. */
    fun frameTime(i: Int): Double = (i * hopSize + fftSize / 2.0) / sampleRate

    /** One-sided magnitude of frame [i]. */
    fun magnitude(i: Int): DoubleArray {
        val re = framesRe[i]; val im = framesIm[i]
        return DoubleArray(numBins) { hypot(re[it], im[it]) }
    }

    /** All frames as one-sided magnitudes: [frame][bin]. */
    fun magnitudes(): Array<DoubleArray> = Array(numFrames) { magnitude(it) }

    /** Largest magnitude anywhere in the spectrogram (for normalizing a heatmap). */
    fun maxMagnitude(): Double {
        var m = 0.0
        for (f in 0 until numFrames) {
            val re = framesRe[f]; val im = framesIm[f]
            for (b in 0 until numBins) m = max(m, hypot(re[b], im[b]))
        }
        return m
    }

    /** Per-frame spectral centroid (Hz). */
    fun centroidTrace(): DoubleArray =
        DoubleArray(numFrames) { SpectralFeatures.centroid(magnitude(it), sampleRate) }

    /** Per-frame spectral rolloff (Hz). */
    fun rolloffTrace(rolloffFraction: Double = 0.85): DoubleArray =
        DoubleArray(numFrames) { SpectralFeatures.rolloff(magnitude(it), sampleRate, rolloffFraction) }

    /** Per-frame spectral flux (positive change vs previous frame). */
    fun fluxTrace(): DoubleArray {
        val out = DoubleArray(numFrames)
        var prev: DoubleArray? = null
        for (i in 0 until numFrames) {
            val cur = magnitude(i)
            val p = prev
            out[i] = if (p == null) 0.0 else SpectralFeatures.flux(p, cur)
            prev = cur
        }
        return out
    }
}

/**
 * Short-time Fourier transform. Frames of [fftSize] samples are windowed by [window] and advanced
 * by [hopSize]; the tail is zero-padded so the final partial frame is kept.
 */
fun stft(
    signal: DoubleArray,
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    window: WindowType = WindowType.HANN,
    sampleRate: Double = signal.size.toDouble(),
): Spectrogram {
    require(fftSize > 0) { "fftSize must be positive" }
    require(hopSize > 0) { "hopSize must be positive" }
    val w = Windows.make(window, fftSize)
    val numFrames = if (signal.isEmpty()) 0 else max(1, (signal.size + hopSize - 1) / hopSize)
    val framesRe = Array(numFrames) { DoubleArray(fftSize) }
    val framesIm = Array(numFrames) { DoubleArray(fftSize) }
    for (f in 0 until numFrames) {
        val start = f * hopSize
        val re = framesRe[f]
        val im = framesIm[f]
        for (i in 0 until fftSize) {
            val idx = start + i
            re[i] = if (idx < signal.size) signal[idx] * w[i] else 0.0
        }
        FFT.transform(re, im)
    }
    return Spectrogram(framesRe, framesIm, sampleRate, fftSize, hopSize, window)
}

fun stft(
    signal: FloatArray,
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    window: WindowType = WindowType.HANN,
    sampleRate: Double = signal.size.toDouble(),
): Spectrogram = stft(DoubleArray(signal.size) { signal[it].toDouble() }, fftSize, hopSize, window, sampleRate)

fun stft(
    signal: List<Float>,
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    window: WindowType = WindowType.HANN,
    sampleRate: Double = signal.size.toDouble(),
): Spectrogram = stft(DoubleArray(signal.size) { signal[it].toDouble() }, fftSize, hopSize, window, sampleRate)

/**
 * Inverse STFT — weighted overlap-add reconstruction of a real signal from a [Spectrogram]. Each
 * frame is inverse-transformed, re-windowed (synthesis window == analysis window) and overlap-added;
 * the running window-energy is divided out so a Hann-windowed COLA STFT reconstructs faithfully.
 */
fun istft(spec: Spectrogram): DoubleArray {
    val fftSize = spec.fftSize
    val hop = spec.hopSize
    val w = Windows.make(spec.window, fftSize)
    val length = if (spec.numFrames == 0) 0 else (spec.numFrames - 1) * hop + fftSize
    val out = DoubleArray(length)
    val norm = DoubleArray(length)
    for (f in 0 until spec.numFrames) {
        val re = spec.framesRe[f].copyOf()
        val im = spec.framesIm[f].copyOf()
        FFT.inverseTransform(re, im)
        val start = f * hop
        for (i in 0 until fftSize) {
            out[start + i] += re[i] * w[i]
            norm[start + i] += w[i] * w[i]
        }
    }
    for (i in out.indices) if (norm[i] > 1e-9) out[i] /= norm[i]
    return out
}

// ---------------------------------------------------------------------------------------------
// Spectral features — over a one-sided magnitude spectrum unless noted.
// ---------------------------------------------------------------------------------------------

object SpectralFeatures {

    /** Spectral centroid (Hz): magnitude-weighted mean frequency. */
    fun centroid(magnitude: DoubleArray, sampleRate: Double): Double {
        val n = magnitude.size
        if (n == 0) return 0.0
        val binHz = sampleRate / (2.0 * (n - 1).coerceAtLeast(1))
        var num = 0.0
        var den = 0.0
        for (k in 0 until n) {
            num += k * binHz * magnitude[k]
            den += magnitude[k]
        }
        return if (den <= 0.0) 0.0 else num / den
    }

    /** Spectral spread / bandwidth (Hz): magnitude-weighted std-dev of frequency about the centroid. */
    fun spread(magnitude: DoubleArray, sampleRate: Double): Double {
        val n = magnitude.size
        if (n == 0) return 0.0
        val binHz = sampleRate / (2.0 * (n - 1).coerceAtLeast(1))
        val c = centroid(magnitude, sampleRate)
        var num = 0.0
        var den = 0.0
        for (k in 0 until n) {
            val f = k * binHz
            num += (f - c) * (f - c) * magnitude[k]
            den += magnitude[k]
        }
        return if (den <= 0.0) 0.0 else sqrt(num / den)
    }

    /**
     * Spectral rolloff (Hz): the frequency below which [rolloffFraction] of the total spectral
     * energy (Σ magnitude) lies. Classic timbre / brightness descriptor.
     */
    fun rolloff(magnitude: DoubleArray, sampleRate: Double, rolloffFraction: Double = 0.85): Double {
        val n = magnitude.size
        if (n == 0) return 0.0
        val binHz = sampleRate / (2.0 * (n - 1).coerceAtLeast(1))
        var total = 0.0
        for (m in magnitude) total += m
        if (total <= 0.0) return 0.0
        val target = total * rolloffFraction.coerceIn(0.0, 1.0)
        var acc = 0.0
        for (k in 0 until n) {
            acc += magnitude[k]
            if (acc >= target) return k * binHz
        }
        return (n - 1) * binHz
    }

    /**
     * Spectral flux: the (positive, L2) change between two consecutive magnitude spectra. A standard
     * onset / novelty descriptor. Only positive differences are summed (half-wave rectified).
     */
    fun flux(previous: DoubleArray, current: DoubleArray): Double {
        val n = min(previous.size, current.size)
        var acc = 0.0
        for (k in 0 until n) {
            val d = current[k] - previous[k]
            if (d > 0.0) acc += d * d
        }
        return sqrt(acc)
    }

    /**
     * Spectral flatness (Wiener entropy): geometric mean / arithmetic mean of the power spectrum.
     * ~1 for noise-like (flat) spectra, ->0 for tonal (peaky) spectra.
     */
    fun flatness(magnitude: DoubleArray): Double {
        val n = magnitude.size
        if (n == 0) return 0.0
        var logSum = 0.0
        var arithSum = 0.0
        var count = 0
        for (m in magnitude) {
            val p = m * m
            if (p > 0.0) {
                logSum += ln(p)
                arithSum += p
                count++
            }
        }
        if (count == 0 || arithSum <= 0.0) return 0.0
        val geo = exp(logSum / count)
        val arith = arithSum / count
        return (geo / arith).coerceIn(0.0, 1.0)
    }

    /** Root-mean-square level of a time-domain buffer. */
    fun rms(signal: DoubleArray): Double {
        if (signal.isEmpty()) return 0.0
        var s = 0.0
        for (v in signal) s += v * v
        return sqrt(s / signal.size)
    }

    fun rms(signal: FloatArray): Double {
        if (signal.isEmpty()) return 0.0
        var s = 0.0
        for (v in signal) s += v.toDouble() * v
        return sqrt(s / signal.size)
    }

    /** Total energy Σ x² of a time-domain buffer. */
    fun energy(signal: DoubleArray): Double {
        var s = 0.0
        for (v in signal) s += v * v
        return s
    }
}

// ---------------------------------------------------------------------------------------------
// Mel scale — a perceptual log-frequency warping + triangular filter bank (bonus).
// ---------------------------------------------------------------------------------------------

object Mel {
    fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /**
     * Apply a triangular mel filter bank to a one-sided magnitude spectrum, returning [numBands]
     * mel-band energies spanning [minHz]..[maxHz].
     */
    fun filterBank(
        magnitude: DoubleArray,
        sampleRate: Double,
        numBands: Int = 26,
        minHz: Double = 0.0,
        maxHz: Double = sampleRate / 2.0,
    ): DoubleArray {
        val nBins = magnitude.size
        if (nBins == 0 || numBands <= 0) return DoubleArray(numBands.coerceAtLeast(0))
        val binHz = sampleRate / (2.0 * (nBins - 1).coerceAtLeast(1))
        val melMin = hzToMel(minHz)
        val melMax = hzToMel(maxHz)
        // numBands+2 equally-spaced mel points -> triangular band edges.
        val points = DoubleArray(numBands + 2) { melToHz(melMin + (melMax - melMin) * it / (numBands + 1)) }
        val out = DoubleArray(numBands)
        for (b in 0 until numBands) {
            val lo = points[b]; val mid = points[b + 1]; val hi = points[b + 2]
            var acc = 0.0
            for (k in 0 until nBins) {
                val f = k * binHz
                val weight = when {
                    f < lo || f > hi -> 0.0
                    f <= mid -> if (mid > lo) (f - lo) / (mid - lo) else 0.0
                    else -> if (hi > mid) (hi - f) / (hi - mid) else 0.0
                }
                acc += weight * magnitude[k]
            }
            out[b] = acc
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Streaming spectral FloatFilters — one Float in per sample, one Float out, so they compose with
// the time-domain chain in timeseries.kt (`someFloatFilter then SpectralCentroidFilter(...)`).
//
// Each maintains a ring buffer of the last [fftSize] samples; every [hopSize] samples it recomputes
// its feature and holds that value between hops (sample-and-hold), so the output is a smooth
// per-sample stream suitable for driving a control signal or an FLine.
// ---------------------------------------------------------------------------------------------

/** Base class: buffers samples, fires [onFrame] every hop with the current windowed frame. */
abstract class SpectralFrameFilter(
    val fftSize: Int = 1024,
    val hopSize: Int = fftSize / 4,
    val sampleRate: Float = fftSize.toFloat(),
    val window: WindowType = WindowType.HANN,
) : FloatFilter {
    private val ring = DoubleArray(fftSize)
    private var writePos = 0
    private var filled = 0
    private var sinceHop = 0
    private val win = Windows.make(window, fftSize)
    protected var held: Float = 0f

    /** Compute the held output value from a freshly windowed one-sided magnitude spectrum. */
    protected abstract fun onFrame(spectrum: Spectrum): Float

    private fun snapshotSpectrum(): Spectrum {
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        // Unroll the ring buffer oldest-first, applying the analysis window.
        for (i in 0 until fftSize) {
            val idx = (writePos + i) % fftSize
            re[i] = ring[idx] * win[i]
        }
        FFT.transform(re, im)
        return Spectrum(re, im, sampleRate.toDouble())
    }

    override fun invoke(value: Float): Float {
        ring[writePos] = value.toDouble()
        writePos = (writePos + 1) % fftSize
        if (filled < fftSize) filled++
        sinceHop++
        if (filled >= fftSize && sinceHop >= hopSize) {
            sinceHop = 0
            held = onFrame(snapshotSpectrum())
        }
        return held
    }

    fun reset() {
        java.util.Arrays.fill(ring, 0.0)
        writePos = 0; filled = 0; sinceHop = 0; held = 0f
    }
}

/** Streaming spectral centroid (Hz), held between hops. */
class SpectralCentroidFilter(
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    sampleRate: Float = fftSize.toFloat(),
    window: WindowType = WindowType.HANN,
) : SpectralFrameFilter(fftSize, hopSize, sampleRate, window) {
    override fun onFrame(spectrum: Spectrum): Float = spectrum.centroid().toFloat()
}

/** Streaming spectral rolloff (Hz), held between hops. */
class SpectralRolloffFilter(
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    sampleRate: Float = fftSize.toFloat(),
    window: WindowType = WindowType.HANN,
    var rolloffFraction: Double = 0.85,
) : SpectralFrameFilter(fftSize, hopSize, sampleRate, window) {
    override fun onFrame(spectrum: Spectrum): Float = spectrum.rolloff(rolloffFraction).toFloat()
}

/** Streaming spectral flux (onset novelty), held between hops. */
class SpectralFluxFilter(
    fftSize: Int = 1024,
    hopSize: Int = fftSize / 4,
    sampleRate: Float = fftSize.toFloat(),
    window: WindowType = WindowType.HANN,
) : SpectralFrameFilter(fftSize, hopSize, sampleRate, window) {
    private var prev: DoubleArray? = null
    override fun onFrame(spectrum: Spectrum): Float {
        val mag = spectrum.magnitudeOneSided()
        val p = prev
        val flux = if (p == null) 0.0 else SpectralFeatures.flux(p, mag)
        prev = mag
        return flux.toFloat()
    }
}

/**
 * Streaming windowed RMS: a rolling root-mean-square over the last [windowSize] samples. Cheap
 * O(1)-per-sample running sum-of-squares — the time-domain energy companion to the spectral filters.
 */
class RmsFilter(val windowSize: Int = 256) : FloatFilter {
    private val ring = FloatArray(windowSize)
    private var pos = 0
    private var filled = 0
    private var sumSq = 0.0
    override fun invoke(value: Float): Float {
        val old = ring[pos]
        sumSq += value.toDouble() * value - old.toDouble() * old
        ring[pos] = value
        pos = (pos + 1) % windowSize
        if (filled < windowSize) filled++
        if (sumSq < 0.0) sumSq = 0.0 // guard fp drift
        return sqrt(sumSq / filled).toFloat()
    }
    fun reset() {
        java.util.Arrays.fill(ring, 0f); pos = 0; filled = 0; sumSq = 0.0
    }
}

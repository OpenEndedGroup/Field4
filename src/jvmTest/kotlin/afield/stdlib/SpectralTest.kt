package afield.stdlib

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness + visual proof for the spectral family ([spectral.kt]).
 *
 * Asserts the FFT lands a pure tone in the right bin, round-trips (inverse∘forward ≈ id), obeys
 * Parseval, matches a hand-computed 4-point DFT, and that Bluestein (non-power-of-two) agrees with
 * a naive DFT. Then drives an actual STFT of a chirp + multitone signal and renders a spectrogram
 * heatmap with a spectral-centroid overlay to a PNG in the worktree root, so a human can eyeball a
 * rising chirp ridge (not black / not garbled).
 */
class SpectralTest {

    private val eps = 1e-9

    // ---- naive reference DFT (O(N²)) for cross-checking the fast paths ----
    private fun naiveDft(signal: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = signal.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * k * t / n
                sr += signal[t] * cos(ang)
                si += signal[t] * sin(ang)
            }
            re[k] = sr; im[k] = si
        }
        return re to im
    }

    @Test
    fun pureSineLandsEnergyInExpectedBin() {
        val n = 64
        val sampleRate = 64.0
        val freq = 8.0 // Hz -> bin 8 exactly (freq * n / sampleRate = 8)
        val signal = DoubleArray(n) { sin(2.0 * PI * freq * it / sampleRate) }
        val spec = fft(signal, sampleRate)
        assertEquals(8, spec.peakBin(), "pure 8 Hz tone must peak at bin 8")
        assertEquals(8.0, spec.peakFrequency(), 1e-9, "peak frequency must be 8 Hz")
    }

    @Test
    fun inverseRoundTripReproducesSignal() {
        val n = 128
        val signal = DoubleArray(n) { sin(2.0 * PI * 5 * it / n) + 0.3 * cos(2.0 * PI * 17 * it / n) }
        val recon = ifft(fft(signal))
        for (i in 0 until n) assertEquals(signal[i], recon[i], 1e-9, "round-trip sample $i")
    }

    @Test
    fun bluesteinNonPowerOfTwoMatchesNaiveDft() {
        // 6 and 12 are not powers of two -> exercises the Bluestein path.
        for (n in intArrayOf(6, 12, 100)) {
            val signal = DoubleArray(n) { sin(2.0 * PI * 3 * it / n) + 0.5 * cos(2.0 * PI * 1 * it / n) }
            val spec = fft(signal)
            val (re, im) = naiveDft(signal)
            for (k in 0 until n) {
                assertEquals(re[k], spec.re[k], 1e-6, "N=$n Re bin $k")
                assertEquals(im[k], spec.im[k], 1e-6, "N=$n Im bin $k")
            }
        }
    }

    @Test
    fun matchesHandComputedFourPointDft() {
        // x = [1,2,3,4]:  X = [10, -2+2i, -2, -2-2i]
        val spec = fft(doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val expectRe = doubleArrayOf(10.0, -2.0, -2.0, -2.0)
        val expectIm = doubleArrayOf(0.0, 2.0, 0.0, -2.0)
        for (k in 0..3) {
            assertEquals(expectRe[k], spec.re[k], eps, "Re[$k]")
            assertEquals(expectIm[k], spec.im[k], eps, "Im[$k]")
        }
    }

    @Test
    fun parsevalEnergyIsConserved() {
        val n = 256
        val signal = DoubleArray(n) { sin(2.0 * PI * 11 * it / n) + 0.4 * sin(2.0 * PI * 40 * it / n) }
        var timeEnergy = 0.0
        for (v in signal) timeEnergy += v * v
        val spec = fft(signal)
        var freqEnergy = 0.0
        for (k in 0 until n) freqEnergy += spec.re[k] * spec.re[k] + spec.im[k] * spec.im[k]
        // Parseval:  Σ|x|²  =  (1/N) Σ|X|²
        assertEquals(timeEnergy, freqEnergy / n, 1e-6, "Parseval energy identity")
    }

    @Test
    fun windowsHaveExpectedShape() {
        for (type in WindowType.entries) {
            val w = Windows.make(type, 64)
            assertEquals(64, w.size)
            for (v in w) assertTrue(v >= -1e-9 && v <= 1.0 + 1e-9, "$type window in [0,1]")
        }
        // Symmetric windows taper to (near) zero at the ends, peak in the middle.
        val hann = Windows.hann(64)
        assertTrue(hann[0] < 1e-6, "Hann starts at ~0")
        assertTrue(hann[32] > 0.9, "Hann peaks near the middle")
        assertTrue(Windows.rectangular(16).all { it == 1.0 }, "rectangular is all ones")
    }

    @Test
    fun spectralFeaturesAreSane() {
        val sampleRate = 1000.0
        val n = 1024
        // Low tone vs high tone: centroid must rank them correctly.
        val low = DoubleArray(n) { sin(2.0 * PI * 50 * it / sampleRate) }
        val high = DoubleArray(n) { sin(2.0 * PI * 300 * it / sampleRate) }
        val cLow = fft(low, WindowType.HANN, sampleRate).centroid()
        val cHigh = fft(high, WindowType.HANN, sampleRate).centroid()
        assertTrue(cHigh > cLow, "higher tone must have higher centroid ($cHigh vs $cLow)")
        assertTrue(abs(cLow - 50.0) < 15.0, "centroid of 50 Hz tone near 50 (got $cLow)")
        assertTrue(abs(cHigh - 300.0) < 25.0, "centroid of 300 Hz tone near 300 (got $cHigh)")

        // Flatness: white-ish noise flatter than a pure tone.
        val rng = java.util.Random(42)
        val noise = DoubleArray(n) { rng.nextDouble() * 2 - 1 }
        val tone = DoubleArray(n) { sin(2.0 * PI * 100 * it / sampleRate) }
        val fNoise = fft(noise, sampleRate).flatness()
        val fTone = fft(tone, sampleRate).flatness()
        assertTrue(fNoise > fTone, "noise flatness ($fNoise) > tone flatness ($fTone)")

        // RMS of unit sine ~ 1/sqrt(2).
        assertEquals(1.0 / sqrt(2.0), SpectralFeatures.rms(tone), 0.01, "RMS of unit sine")
    }

    @Test
    fun spreadIsWiderForTwoSeparatedTones() {
        val sampleRate = 2000.0
        val n = 2048
        val single = DoubleArray(n) { sin(2.0 * PI * 500 * it / sampleRate) }
        val split = DoubleArray(n) {
            sin(2.0 * PI * 200 * it / sampleRate) + sin(2.0 * PI * 800 * it / sampleRate)
        }
        val sSingle = fft(single, WindowType.HANN, sampleRate).spread()
        val sSplit = fft(split, WindowType.HANN, sampleRate).spread()
        assertTrue(sSplit > sSingle, "two separated tones spread wider ($sSplit > $sSingle)")
    }

    @Test
    fun melScaleRoundTripsAndBankConcentratesEnergy() {
        // hzToMel / melToHz are inverses.
        for (hz in doubleArrayOf(0.0, 100.0, 700.0, 2000.0, 8000.0)) {
            assertEquals(hz, Mel.melToHz(Mel.hzToMel(hz)), 1e-6, "mel round-trip at $hz Hz")
        }
        assertTrue(Mel.hzToMel(2000.0) > Mel.hzToMel(1000.0), "mel scale is monotonic")

        // A 1000 Hz tone: the mel band spanning 1000 Hz must carry the most energy.
        val sampleRate = 16000.0
        val n = 4096
        val tone = DoubleArray(n) { sin(2.0 * PI * 1000 * it / sampleRate) }
        val mag = fft(tone, WindowType.HANN, sampleRate).magnitudeOneSided()
        val bank = Mel.filterBank(mag, sampleRate, numBands = 20, minHz = 0.0, maxHz = 8000.0)
        assertEquals(20, bank.size)
        var peak = 0
        for (i in bank.indices) if (bank[i] > bank[peak]) peak = i
        // The band edges are mel-spaced; band `peak` should sit around 1 kHz. Just require the peak
        // band's energy dominates the total (a single tone must not smear flat across all bands).
        val total = bank.sum()
        assertTrue(total > 0.0 && bank[peak] > total * 0.15, "mel bank concentrates tone energy (peak=${bank[peak]} total=$total)")
    }

    @Test
    fun istftReconstructsHannWindowedSignal() {
        val n = 4096
        val signal = DoubleArray(n) { sin(2.0 * PI * 40 * it / n) + 0.3 * sin(2.0 * PI * 120 * it / n) }
        val spec = stft(signal, fftSize = 512, hopSize = 128, window = WindowType.HANN, sampleRate = n.toDouble())
        val recon = istft(spec)
        // Compare the stable interior (edges lack full overlap).
        var err = 0.0; var count = 0
        for (i in 512 until n - 512) { err += abs(recon[i] - signal[i]); count++ }
        assertTrue(err / count < 1e-3, "inverse-STFT interior mean error ${err / count}")
    }

    @Test
    fun streamingCentroidFilterTracksRisingChirp() {
        val sampleRate = 8000f
        val n = 16000
        val chirp = FloatArray(n) {
            val t = it / sampleRate
            val f = 200f + 2000f * (it.toFloat() / n) // sweep 200 -> 2200 Hz
            sin(2.0 * PI * f * t).toFloat()
        }
        val f = SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
        val trace = FloatArray(n) { f(chirp[it]) }
        val early = trace[3000]
        val late = trace[n - 500]
        assertTrue(late > early + 200f, "streaming centroid rises with chirp (early=$early late=$late)")
    }

    // ---------------------------------------------------------------------------------------------
    // Visual/structure proof — render a spectrogram heatmap + centroid trace of a chirp+tone signal
    // to a PNG in the worktree root and assert it contains real structure (not a flat frame). These
    // are correctness tests only; the public docs (spectral_examples.md) render their own live
    // in-Field captures via afield.util.PngEncoder, so the PNGs written here are not shipped.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun rendersSpectrogramHeatmapPng() {
        val sampleRate = 8000.0
        val n = 24000 // 3 s
        // A rising linear chirp (300 -> 3000 Hz) plus two steady tones (700 Hz, 1800 Hz).
        // Use proper phase integration phi = 2π(f0·t + 0.5·k·t²) so the *instantaneous* frequency
        // f0 + k·t sweeps monotonically and stays below Nyquist (no alias fold-back).
        val f0 = 300.0
        val f1 = 3000.0
        val dur = n / sampleRate
        val k = (f1 - f0) / dur
        val signal = DoubleArray(n) {
            val t = it / sampleRate
            0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
                0.35 * sin(2.0 * PI * 700.0 * t) +
                0.30 * sin(2.0 * PI * 1800.0 * t)
        }

        val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
        assertTrue(spec.numFrames > 10, "chirp produced ${spec.numFrames} frames")

        val png = renderSpectrogram(spec, width = 900, height = 420)
        // A temp dir by default — this used to drop a PNG at the repository root, and at the root
        // of every nightly agent worktree, on every run. Set AFIELD_PROOF_DIR to collect it.
        val out = File(afield.proofDir("spectral"), "spectral_chirp_spectrogram.png")
        ImageIO.write(png, "png", out)
        assertTrue(out.length() > 2000, "PNG must be non-trivial (${out.length()} bytes) at ${out.absolutePath}")

        // Guard: the image must contain real structure, not a flat black frame.
        var bright = 0
        for (x in 0 until png.width) for (y in 0 until png.height) {
            val c = Color(png.getRGB(x, y))
            if (c.red + c.green + c.blue > 120) bright++
        }
        val frac = bright.toDouble() / (png.width * png.height)
        assertTrue(frac > 0.02, "spectrogram must have visible structure (bright fraction=$frac)")
        println("wrote spectrogram PNG to ${out.absolutePath} (${out.length()} bytes, bright frac=$frac)")
    }

    @Test
    fun rendersMelSpectrogramPng() {
        val sampleRate = 8000.0
        val n = 24000
        // Two crossing chirps: one rising, one falling — an X across the mel plane.
        val up0 = 200.0; val up1 = 3200.0
        val dn0 = 3200.0; val dn1 = 200.0
        val dur = n / sampleRate
        val ku = (up1 - up0) / dur
        val kd = (dn1 - dn0) / dur
        val signal = DoubleArray(n) {
            val t = it / sampleRate
            0.7 * sin(2.0 * PI * (up0 * t + 0.5 * ku * t * t)) +
                0.7 * sin(2.0 * PI * (dn0 * t + 0.5 * kd * t * t))
        }
        val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
        val numBands = 48
        val mel = Array(spec.numFrames) { Mel.filterBank(spec.magnitude(it), sampleRate, numBands, 0.0, sampleRate / 2.0) }
        var maxMel = 1e-9
        for (row in mel) for (v in row) if (v > maxMel) maxMel = v

        val width = 900; val height = 360
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (px in 0 until width) {
            val frame = (px.toLong() * (spec.numFrames - 1) / (width - 1).coerceAtLeast(1)).toInt()
            for (py in 0 until height) {
                val band = ((height - 1 - py).toLong() * (numBands - 1) / (height - 1)).toInt()
                val db = 20.0 * kotlin.math.log10((mel[frame][band] / maxMel).coerceAtLeast(1e-4))
                img.setRGB(px, py, viridis(((db + 80.0) / 80.0).coerceIn(0.0, 1.0)).rgb)
            }
        }
        val out = File(afield.proofDir("spectral"), "spectral_mel_spectrogram.png")
        ImageIO.write(img, "png", out)
        assertTrue(out.length() > 2000, "mel PNG non-trivial (${out.length()} bytes)")
        var bright = 0
        for (x in 0 until width) for (y in 0 until height) {
            val c = Color(img.getRGB(x, y)); if (c.red + c.green + c.blue > 120) bright++
        }
        assertTrue(bright.toDouble() / (width * height) > 0.02, "mel spectrogram has visible structure")
        println("wrote mel spectrogram PNG to ${out.absolutePath}")
    }

    private fun renderSpectrogram(spec: Spectrogram, width: Int, height: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(8, 10, 18)
        g.fillRect(0, 0, width, height)

        val mags = spec.magnitudes()
        val maxMag = spec.maxMagnitude().coerceAtLeast(1e-9)
        // log-scaled magnitude for a useful dynamic range.
        fun norm(m: Double): Double {
            val db = 20.0 * kotlin.math.log10((m / maxMag).coerceAtLeast(1e-4))
            return ((db + 80.0) / 80.0).coerceIn(0.0, 1.0)
        }

        // Heatmap: x = frame (time), y = bin (frequency, low at bottom).
        val nBins = spec.numBins
        for (px in 0 until width) {
            val frame = (px.toLong() * (spec.numFrames - 1) / (width - 1).coerceAtLeast(1)).toInt()
            for (py in 0 until height) {
                val bin = ((height - 1 - py).toLong() * (nBins - 1) / (height - 1)).toInt()
                val v = norm(mags[frame][bin])
                img.setRGB(px, py, viridis(v).rgb)
            }
        }

        // Overlay the spectral-centroid trace as a bright line.
        val centroid = spec.centroidTrace()
        val nyquist = spec.sampleRate / 2.0
        g.color = Color(255, 240, 120)
        var prevX = -1; var prevY = -1
        for (fIdx in 0 until spec.numFrames) {
            val x = (fIdx.toDouble() / (spec.numFrames - 1).coerceAtLeast(1) * (width - 1)).toInt()
            val cy = (height - 1 - (centroid[fIdx] / nyquist).coerceIn(0.0, 1.0) * (height - 1)).toInt()
            if (prevX >= 0) g.drawLine(prevX, prevY, x, cy)
            prevX = x; prevY = cy
        }
        g.dispose()
        return img
    }

    /** Minimal viridis-ish colormap: dark blue -> teal -> green -> yellow. */
    private fun viridis(t: Double): Color {
        val x = t.coerceIn(0.0, 1.0)
        val r = (0.28 + x * (0.99 - 0.28) * (0.3 + 0.7 * x)).coerceIn(0.0, 1.0)
        val gg = (0.0 + x * 0.98).coerceIn(0.0, 1.0)
        val b = (0.33 + 0.6 * kotlin.math.sin(PI * x) - 0.2 * x).coerceIn(0.0, 1.0)
        return Color((r * 255).toInt(), (gg * 255).toInt(), (b * 255).toInt())
    }

}

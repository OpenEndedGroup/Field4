package afield.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Linear predictive coding: model each sample as a weighted sum of the ones before it, and keep
 * what the model cannot predict.
 *
 * Bishnu Atal's method, and the core of every speech codec from the 1970s Speak & Spell to CELP. A
 * short frame of signal is windowed, its autocorrelation taken, and the Levinson-Durbin recursion
 * turns that into an order-`p` predictor `x[n] ≈ -(a₁x[n-1] + … + a_p x[n-p])`. The filter
 * `A(z) = 1 + a₁z⁻¹ + … + a_p z⁻ᵖ` *whitens* the frame: its output, the residual, is what is left
 * after the spectral envelope is taken out. The all-pole filter `1/A(z)` puts the envelope back.
 * For a voice, `1/A(z)` is the vocal tract and the residual is the glottal buzz or breath driving it,
 * so you can swap either half: keep the envelope and drive it with a pulse train (a robot voice at
 * any pitch), with noise (a whisper), or with a saw chord (the talking synth).
 *
 * Coefficient convention throughout: [LpcFit.a] holds `1, a₁, …, a_p`, the residual is
 * `e[n] = x[n] + Σ a_k x[n-k]` ([residual]) and synthesis is its exact inverse ([synthesize]).
 * Levinson-Durbin also produces the reflection coefficients [LpcFit.k], one per stage of the
 * equivalent lattice filter; `|k| < 1` at every stage is the stability guarantee, and the audio
 * processors ([LpcVocoder], [LpcWhiten]) run lattices so that coefficients can be interpolated
 * sample by sample without the filter blowing up.
 *
 * The complex versions ([complexLevinsonDurbin], [analyzeComplex], [burgComplex]) take a sequence of
 * 2D points as complex numbers; `FLine.lpc(...)` uses [burgComplex] to predict a drawn stroke.
 */
object Lpc {

    /** Biased autocorrelation `r[k] = Σ x[n]·x[n-k] / N` for lags `0..maxLag`. */
    fun autocorrelation(x: DoubleArray, maxLag: Int): DoubleArray {
        val n = x.size
        return DoubleArray(maxLag + 1) { k ->
            var s = 0.0
            for (i in k until n) s += x[i] * x[i - k]
            if (n > 0) s / n else 0.0
        }
    }

    /**
     * The Levinson-Durbin recursion: solve the Toeplitz normal equations for an order-[order]
     * predictor from autocorrelation [r] (`r.size > order`). A frame of silence (`r[0] == 0`) gives
     * the identity filter and zero error rather than NaN.
     */
    fun levinsonDurbin(r: DoubleArray, order: Int): LpcFit {
        require(r.size > order) { "need ${order + 1} autocorrelation lags, got ${r.size}" }
        val c = complexLevinsonDurbin(r, DoubleArray(r.size), order)
        return LpcFit(c.aRe, c.kRe, c.error)
    }

    /**
     * Levinson-Durbin over a complex (Hermitian) autocorrelation `r[k] = Σ x[n]·conj(x[n-k]) / N`,
     * given as real and imaginary parts. Real input is the special case with [rIm] all zero.
     */
    fun complexLevinsonDurbin(rRe: DoubleArray, rIm: DoubleArray, order: Int): ComplexLpcFit {
        val aRe = DoubleArray(order + 1).also { it[0] = 1.0 }
        val aIm = DoubleArray(order + 1)
        val kRe = DoubleArray(order)
        val kIm = DoubleArray(order)
        var err = rRe[0]
        if (err <= 0.0) return ComplexLpcFit(aRe, aIm, kRe, kIm, 0.0)
        val tRe = DoubleArray(order + 1)
        val tIm = DoubleArray(order + 1)
        for (m in 1..order) {
            // acc = Σ_{i=0}^{m-1} a_i · r[m-i]
            var accRe = 0.0
            var accIm = 0.0
            for (i in 0 until m) {
                val pr = rRe[m - i]; val pi = rIm[m - i]
                accRe += aRe[i] * pr - aIm[i] * pi
                accIm += aRe[i] * pi + aIm[i] * pr
            }
            val km = -accRe / err
            val kmi = -accIm / err
            kRe[m - 1] = km; kIm[m - 1] = kmi
            for (i in 0..m) { tRe[i] = aRe[i]; tIm[i] = aIm[i] }
            for (i in 1..m) {
                // a_i += k · conj(a_{m-i})
                val cr = tRe[m - i]; val ci = -tIm[m - i]
                aRe[i] = tRe[i] + km * cr - kmi * ci
                aIm[i] = tIm[i] + km * ci + kmi * cr
            }
            err *= (1.0 - (km * km + kmi * kmi))
            if (err <= 0.0) { err = 0.0; break }
        }
        return ComplexLpcFit(aRe, aIm, kRe, kIm, err)
    }

    /** A Hann window of length [n]. */
    fun hann(n: Int): DoubleArray =
        DoubleArray(n) { i -> if (n <= 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)) }

    /**
     * Fit an order-[order] predictor to [x]: Hann-window it (when [window] is true), take the
     * autocorrelation, apply a tiny white-noise floor ([noiseFloor] × `r[0]`) for numerical safety,
     * and run [levinsonDurbin]. This is the autocorrelation method, so the result is always stable.
     */
    fun analyze(x: DoubleArray, order: Int, window: Boolean = true, noiseFloor: Double = 1e-9): LpcFit {
        val w = if (window) hann(x.size).let { h -> DoubleArray(x.size) { x[it] * h[it] } } else x
        val r = autocorrelation(w, order)
        r[0] *= 1.0 + noiseFloor
        return levinsonDurbin(r, order)
    }

    /** [analyze] for a [FloatArray], as rendered by [renderSignalsOffline]. */
    fun analyze(x: FloatArray, order: Int, window: Boolean = true): LpcFit =
        analyze(DoubleArray(x.size) { x[it].toDouble() }, order, window)

    /**
     * Fit a complex predictor to the sequence `re[n] + i·im[n]`, with no window (strokes are short
     * and their ends matter) unless [window] is true.
     */
    fun analyzeComplex(re: DoubleArray, im: DoubleArray, order: Int, window: Boolean = false, noiseFloor: Double = 1e-9): ComplexLpcFit {
        val n = re.size
        val h = if (window) hann(n) else null
        val xr = DoubleArray(n) { re[it] * (h?.get(it) ?: 1.0) }
        val xi = DoubleArray(n) { im[it] * (h?.get(it) ?: 1.0) }
        val rRe = DoubleArray(order + 1)
        val rIm = DoubleArray(order + 1)
        for (k in 0..order) {
            var sr = 0.0; var si = 0.0
            for (t in k until n) {
                // x[t] · conj(x[t-k])
                sr += xr[t] * xr[t - k] + xi[t] * xi[t - k]
                si += xi[t] * xr[t - k] - xr[t] * xi[t - k]
            }
            rRe[k] = if (n > 0) sr / n else 0.0
            rIm[k] = if (n > 0) si / n else 0.0
        }
        rRe[0] *= 1.0 + noiseFloor
        return complexLevinsonDurbin(rRe, rIm, order)
    }

    /**
     * Burg's method for the complex sequence `re[n] + i·im[n]`: each stage chooses the reflection
     * coefficient that minimises forward plus backward prediction error over the data actually
     * there, rather than over a windowed autocorrelation. Still guaranteed stable, and without the
     * autocorrelation method's pull of every pole toward the origin, which matters when a short
     * sequence is to be *continued*: a circle fitted this way keeps going round instead of spiralling
     * in.
     */
    fun burgComplex(re: DoubleArray, im: DoubleArray, order: Int): ComplexLpcFit {
        val n = re.size
        val fr = re.copyOf(); val fi = im.copyOf()
        val br = re.copyOf(); val bi = im.copyOf()
        val aRe = DoubleArray(order + 1).also { it[0] = 1.0 }
        val aIm = DoubleArray(order + 1)
        val kRe = DoubleArray(order); val kIm = DoubleArray(order)
        var err = (0 until n).sumOf { re[it] * re[it] + im[it] * im[it] } / n.coerceAtLeast(1)
        val tRe = DoubleArray(order + 1); val tIm = DoubleArray(order + 1)
        for (m in 1..order) {
            var numR = 0.0; var numI = 0.0; var den = 0.0
            for (t in m until n) {
                // f[t] · conj(b[t-1])
                numR += fr[t] * br[t - 1] + fi[t] * bi[t - 1]
                numI += fi[t] * br[t - 1] - fr[t] * bi[t - 1]
                den += fr[t] * fr[t] + fi[t] * fi[t] + br[t - 1] * br[t - 1] + bi[t - 1] * bi[t - 1]
            }
            if (den <= 0.0) break
            val km = -2 * numR / den
            val kmi = -2 * numI / den
            kRe[m - 1] = km; kIm[m - 1] = kmi
            for (i in 0..m) { tRe[i] = aRe[i]; tIm[i] = aIm[i] }
            for (i in 1..m) {
                val cr = tRe[m - i]; val ci = -tIm[m - i]
                aRe[i] = tRe[i] + km * cr - kmi * ci
                aIm[i] = tIm[i] + km * ci + kmi * cr
            }
            for (t in n - 1 downTo m) {
                val f0r = fr[t]; val f0i = fi[t]
                val b1r = br[t - 1]; val b1i = bi[t - 1]
                fr[t] = f0r + km * b1r - kmi * b1i
                fi[t] = f0i + km * b1i + kmi * b1r
                // b[t] = b[t-1] + conj(k) · f[t]
                br[t] = b1r + km * f0r + kmi * f0i
                bi[t] = b1i + km * f0i - kmi * f0r
            }
            err *= (1.0 - (km * km + kmi * kmi)).coerceAtLeast(0.0)
        }
        return ComplexLpcFit(aRe, aIm, kRe, kIm, err)
    }

    /** The prediction residual `e[n] = x[n] + Σ a_k x[n-k]`, with zero history before `x[0]`. */
    fun residual(x: DoubleArray, a: DoubleArray): DoubleArray = DoubleArray(x.size) { n ->
        var s = x[n]
        for (k in 1 until a.size) if (n - k >= 0) s += a[k] * x[n - k]
        s
    }

    /** The all-pole synthesis `x[n] = e[n] - Σ a_k x[n-k]`: the exact inverse of [residual]. */
    fun synthesize(e: DoubleArray, a: DoubleArray): DoubleArray {
        val x = DoubleArray(e.size)
        for (n in e.indices) {
            var s = e[n]
            for (k in 1 until a.size) if (n - k >= 0) s -= a[k] * x[n - k]
            x[n] = s
        }
        return x
    }

    /** Rebuild `1, a₁, …, a_p` from reflection coefficients (the step-up recursion). */
    fun reflectionToPredictor(k: DoubleArray): DoubleArray {
        val p = k.size
        val a = DoubleArray(p + 1).also { it[0] = 1.0 }
        val t = DoubleArray(p + 1)
        for (m in 1..p) {
            for (i in 0..m) t[i] = a[i]
            for (i in 1..m) a[i] = t[i] + k[m - 1] * t[m - i]
        }
        return a
    }

    /**
     * Quantize reflection coefficients to [bits] bits each in the arcsine domain, where the
     * spacing is even in perceived envelope change near `|k| → 1` — the trick that let early LPC
     * vocoders send a vocal tract in 40-odd bits per frame. The result is still stable.
     */
    fun quantizeReflection(k: DoubleArray, bits: Int): DoubleArray {
        val levels = (1 shl bits) - 1
        return DoubleArray(k.size) { i ->
            val u = asin(k[i].coerceIn(-0.999999, 0.999999)) / (PI / 2) // -1..1
            val q = ((u + 1.0) / 2.0 * levels).roundToInt().toDouble() / levels * 2.0 - 1.0
            sin(q * PI / 2).coerceIn(-0.9999, 0.9999)
        }
    }
}

/**
 * An all-pole fit: predictor polynomial [a] (`1, a₁, …, a_p`), reflection coefficients [k], and the
 * final prediction-error power [error] (the residual's mean square, for a frame normalized as
 * [Lpc.autocorrelation] does).
 */
class LpcFit(val a: DoubleArray, val k: DoubleArray, val error: Double) {
    /** The predictor order `p`. */
    val order: Int get() = a.size - 1

    /** The gain `√error` that makes `gain / A(z)` match the frame's power. */
    val gain: Double get() = sqrt(error)

    /** `|gain / A(e^{jω})|` in dB at [points] frequencies from 0 to Nyquist. */
    fun envelopeDb(points: Int = 512): DoubleArray = DoubleArray(points) { j ->
        val w = PI * j / (points - 1).coerceAtLeast(1)
        var re = 0.0; var im = 0.0
        for (n in a.indices) { re += a[n] * cos(w * n); im -= a[n] * sin(w * n) }
        20.0 * log10((gain / sqrt(re * re + im * im)).coerceAtLeast(1e-12))
    }

    /** The frequencies, in Hz, that [envelopeDb] samples at [sampleRate]. */
    fun envelopeFrequencies(points: Int = 512, sampleRate: Double = 48_000.0): DoubleArray =
        DoubleArray(points) { j -> sampleRate / 2 * j / (points - 1).coerceAtLeast(1) }

    override fun toString(): String = "LpcFit(order=$order, gain=${"%.4g".format(gain)})"
}

/** A complex all-pole fit, as [LpcFit] with real and imaginary parts kept separately. */
class ComplexLpcFit(
    /** Real parts of `1, a₁, …, a_p`. */
    val aRe: DoubleArray,
    /** Imaginary parts of `1, a₁, …, a_p`. */
    val aIm: DoubleArray,
    /** Real parts of the reflection coefficients. */
    val kRe: DoubleArray,
    /** Imaginary parts of the reflection coefficients. */
    val kIm: DoubleArray,
    /** Final prediction-error power. */
    val error: Double,
) {
    /** The predictor order `p`. */
    val order: Int get() = aRe.size - 1

    /** Residual of the complex sequence `re + i·im` under this predictor, zero history. */
    fun residual(re: DoubleArray, im: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val er = DoubleArray(n); val ei = DoubleArray(n)
        for (t in 0 until n) {
            var sr = re[t]; var si = im[t]
            for (k in 1..order) if (t - k >= 0) {
                sr += aRe[k] * re[t - k] - aIm[k] * im[t - k]
                si += aRe[k] * im[t - k] + aIm[k] * re[t - k]
            }
            er[t] = sr; ei[t] = si
        }
        return er to ei
    }

    /**
     * Run the all-pole filter on excitation `eRe + i·eIm`, starting from history [seedRe]/[seedIm]
     * (the samples just before the first output, oldest first; may be empty). The exact inverse of
     * [residual] when the seed is empty.
     */
    fun synthesize(eRe: DoubleArray, eIm: DoubleArray, seedRe: DoubleArray = DoubleArray(0), seedIm: DoubleArray = DoubleArray(0)): Pair<DoubleArray, DoubleArray> {
        val s = seedRe.size
        val xr = DoubleArray(s + eRe.size); val xi = DoubleArray(s + eRe.size)
        seedRe.copyInto(xr); seedIm.copyInto(xi)
        for (t in eRe.indices) {
            val n = s + t
            var sr = eRe[t]; var si = eIm[t]
            for (k in 1..order) if (n - k >= 0) {
                sr -= aRe[k] * xr[n - k] - aIm[k] * xi[n - k]
                si -= aRe[k] * xi[n - k] + aIm[k] * xr[n - k]
            }
            xr[n] = sr; xi[n] = si
        }
        return xr.copyOfRange(s, xr.size) to xi.copyOfRange(s, xi.size)
    }
}

/**
 * Frame-by-frame LPC analysis of a signal, kept on the audio thread: a ring of the last
 * [frameSize] samples, re-analysed every [hop] samples, with the reflection coefficients and gain
 * linearly interpolated across each hop so a lattice filter can follow them smoothly.
 */
internal class LpcTracker(val order: Int, val frameSize: Int, val hop: Int) {
    private val ring = DoubleArray(frameSize)
    private var pos = 0
    private var sinceHop = 0
    private val window = Lpc.hann(frameSize)
    private val frame = DoubleArray(frameSize)
    val kFrom = DoubleArray(order); val kTo = DoubleArray(order)
    var gFrom = 0.0; var gTo = 0.0
    /** Interpolation position within the current hop, 0..1. */
    val t: Double get() = sinceHop.toDouble() / hop

    fun push(x: Double) {
        ring[pos] = x
        pos = (pos + 1) % frameSize
        sinceHop++
        if (sinceHop >= hop) {
            sinceHop = 0
            for (i in 0 until frameSize) frame[i] = ring[(pos + i) % frameSize] * window[i]
            val r = Lpc.autocorrelation(frame, order)
            // Hann's mean-square is 3/8; undo it so gain tracks the unwindowed RMS.
            for (i in r.indices) r[i] /= 0.375
            r[0] = r[0] * (1.0 + 1e-6) + 1e-12
            // A mild Gaussian lag window (about 40 Hz at 48 kHz) keeps formant bandwidths from
            // collapsing to whistles.
            for (i in 1 until r.size) r[i] *= kotlin.math.exp(-0.5 * (2 * PI * i / 1200.0).let { it * it })
            val fit = Lpc.levinsonDurbin(r, order)
            kTo.copyInto(kFrom)
            fit.k.copyInto(kTo)
            gFrom = gTo
            gTo = fit.gain
        }
    }

    fun k(i: Int): Double = kFrom[i] + (kTo[i] - kFrom[i]) * t
    fun gain(): Double = gFrom + (gTo - gFrom) * t
}

/**
 * Cross-synthesis by linear prediction: the spectral envelope of [modulator], tracked frame by
 * frame, imposed on this processor's input (the carrier).
 *
 * With a voice as [modulator] and a saw chord as input this is the talking synth; with an
 * [Impulse] train it is a robot re-pitching of the voice, with [WhiteNoise] a whisper. When
 * [whiten] is true (the default) the carrier's own envelope is removed first by a second tracker,
 * so a dull carrier speaks as clearly as a bright one; turn it off for a pulse or noise carrier,
 * which is already flat.
 *
 * Each [hop] samples the last [frameSize] modulator samples are Hann-windowed and fitted at
 * [order] by Levinson-Durbin. Synthesis is an all-pole lattice whose reflection coefficients are
 * interpolated sample by sample, which stays stable as they move. Output level follows the
 * modulator: the carrier is scaled to unit power and multiplied by the modulator's prediction gain.
 * Both inputs are rendered from this node, so it owns them on the audio thread like any filter.
 * Latency is about half a frame.
 */
class LpcVocoder(
    /** The signal whose envelope is imposed: usually a voice. */
    val modulator: Signal,
    /** Predictor order; about the sample rate in kHz, plus four, resolves a voice's formants. */
    val order: Int = 48,
    /** Analysis frame length in samples. */
    val frameSize: Int = 1024,
    /** Samples between analyses. */
    val hop: Int = 256,
    /** Whether to flatten the carrier's own envelope before imposing the modulator's. */
    val whiten: Boolean = true,
) : SignalProcessor() {
    private val mod = LpcTracker(order, frameSize, hop)
    private val car = LpcTracker(order, frameSize, hop)
    private val synthB = DoubleArray(order + 1)
    private val whitenB = DoubleArray(order + 1)
    private val fwd = DoubleArray(order + 1)
    private var inBuf = FloatArray(0)
    private var modBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) { inBuf = FloatArray(numFrames); modBuf = FloatArray(numFrames) }
        input.render(inBuf, numFrames, ctx)
        modulator.render(modBuf, numFrames, ctx)
        for (n in 0 until numFrames) {
            mod.push(modBuf[n].toDouble())
            val x = inBuf[n].toDouble()
            car.push(x)
            // 1. carrier → residual through the analysis lattice A_car(z), normalised to unit power
            var e: Double
            if (whiten) {
                fwd[0] = x
                var bPrev = x
                for (m in 1..order) {
                    val k = car.k(m - 1)
                    val f = fwd[m - 1] + k * whitenB[m - 1]
                    val b = whitenB[m - 1] + k * fwd[m - 1]
                    whitenB[m - 1] = bPrev
                    bPrev = b
                    fwd[m] = f
                }
                whitenB[order] = bPrev
                e = fwd[order]
            } else e = x
            val cg = car.gain()
            e = if (cg > 1e-9) e / cg else 0.0
            // 2. residual → output through the synthesis lattice 1/A_mod(z), times the modulator's gain
            var f = e * mod.gain()
            for (m in order downTo 1) {
                f -= mod.k(m - 1) * synthB[m - 1]
                synthB[m] = synthB[m - 1] + mod.k(m - 1) * f
            }
            synthB[0] = f
            output[n] = if (f.isFinite()) f.toFloat().coerceIn(-4f, 4f) else 0f
            if (!f.isFinite()) synthB.fill(0.0)
        }
    }
}

/**
 * The LPC residual of this processor's input, tracked frame by frame: the input with its spectral
 * envelope removed. Speech comes out as a buzzy pulse train (voiced) or hiss (unvoiced); a resonant
 * filter sweep comes out nearly flat. Parameters as in [LpcVocoder].
 */
class LpcWhiten(
    /** Predictor order. */
    val order: Int = 48,
    /** Analysis frame length in samples. */
    val frameSize: Int = 1024,
    /** Samples between analyses. */
    val hop: Int = 256,
) : SignalProcessor() {
    private val tr = LpcTracker(order, frameSize, hop)
    private val bState = DoubleArray(order + 1)
    private val fwd = DoubleArray(order + 1)
    private var inBuf = FloatArray(0)

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (inBuf.size < numFrames) inBuf = FloatArray(numFrames)
        input.render(inBuf, numFrames, ctx)
        for (n in 0 until numFrames) {
            val x = inBuf[n].toDouble()
            tr.push(x)
            fwd[0] = x
            var bPrev = x
            for (m in 1..order) {
                val k = tr.k(m - 1)
                fwd[m] = fwd[m - 1] + k * bState[m - 1]
                val b = bState[m - 1] + k * fwd[m - 1]
                bState[m - 1] = bPrev
                bPrev = b
            }
            bState[order] = bPrev
            output[n] = fwd[order].toFloat()
        }
    }
}

/** Absolute peak of [x]; used to check offline renders are neither silent nor exploding. */
internal fun lpcPeak(x: FloatArray): Float = x.maxOfOrNull { abs(it) } ?: 0f

@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

// Ported from coopiano (https://github.com/peterc/coopiano), piano.hpp and reverb.hpp.
//
// MIT License
//
// Copyright (c) 2026 Peter Cooper
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
// What changed in the port: the sample rate is a parameter rather than a fixed 44.1 kHz; the
// random draws are coopiano's own (mt19937_64 with libc++'s uniform and normal distributions,
// reproduced in PianoRng), so at 44.1 kHz a dry note matches the C++ to rounding; the soundboard is the single shared stereo response (coopiano's
// make_soundboard_ir), not its four bridge regions; each phasor remembers its partial number and
// string so inharmonicity and decay can be modulated while it rings (see Piano.kt).

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The voicing of a [Piano]: every coopiano preset control, with coopiano's calibrated defaults.
 *
 * Scale controls multiply coopiano's pitch-dependent curves rather than replacing them, so
 * `inharmonicityScale = 2.0` doubles the stiffness of every string and keeps the register shape.
 * These are fixed when the [Piano] is built. The controls that can move while it plays, at audio
 * rate, are the [Piano] constructor's [Param]s.
 */
data class PianoVoicing(
    // hammer
    val stiffnessScale: Double = 0.90,
    val massScale: Double = 1.3,
    val feltWidthScale: Double = 1.4,
    val maximumVelocityMps: Double = 6.0,
    val velocityExponent: Double = 1.8,
    // strings
    val decayScale: Double = 1.10,
    val trebleDecayScale: Double = 0.38,
    val partialLossScale: Double = 1.0,
    val inharmonicityScale: Double = 0.85,
    val unisonDetuneScale: Double = 0.35,
    val trebleUnisonDetune: Double = 0.875,
    val fastDecayScale: Double = 0.95,
    val horizontalRatio: Double = 0.4,
    val longitudinalGain: Double = 0.22,
    val bridgeCoupling: Double = 1.0,
    // tone
    val fundamentalBoostDb: Double = 0.0,
    val lowerOvertoneCutDb: Double = 0.0,
    val bridgeCurveScale: Double = 1.0,
    val midGainDb: Double = -1.5,
    val midFrequencyHz: Double = 1200.0,
    val midWidthOctaves: Double = 0.9,
    // soundboard
    val boardBassCornerHz: Double = 70.0,
    val boardTrebleCornerHz: Double = 3800.0,
    val boardTrebleSlope: Double = 1.8,
    val boardDecayScale: Double = 1.10,
    val boardLowModeDensity: Double = 1.0,
    val boardRippleFraction: Double = 0.25,
    // sympathetic resonance
    val resonanceGain: Double = 0.18,
    val resonanceMaximumDecaySeconds: Double = 12.0,
    val resonanceDamperDecaySeconds: Double = 0.18,
    val resonanceTransitionSeconds: Double = 0.04,
    // room
    val reverbDecaySeconds: Double = 1.1,
    val reverbPredelayMs: Double = 12.0,
    val reverbDampingHz: Double = 5500.0,
    // action noises
    val knock: Boolean = true,
    val scrape: Boolean = true,
    val damperNoise: Boolean = true,
    val softenLongitudinalHighs: Boolean = true,
)

/**
 * mt19937_64 with libc++'s `uniform_real_distribution` and `normal_distribution`, so the draws that
 * tune the unisons, shape the soundboard and colour each strike are coopiano's own.
 */
internal class PianoRng(seed: Long) {
    private val mt = LongArray(312)
    private var idx = 312
    init {
        mt[0] = seed
        for (i in 1 until 312) mt[i] = 6364136223846793005L * (mt[i - 1] xor (mt[i - 1] ushr 62)) + i
    }

    fun nextLong(): Long {
        if (idx >= 312) {
            for (i in 0 until 312) {
                val x = (mt[i] and (-0x80000000L)) or (mt[(i + 1) % 312] and 0x7fffffffL)
                var xa = x ushr 1
                if (x and 1L != 0L) xa = xa xor MATRIX_A
                mt[i] = mt[(i + 156) % 312] xor xa
            }
            idx = 0
        }
        var y = mt[idx++]
        y = y xor ((y ushr 29) and 0x5555555555555555L)
        y = y xor ((y shl 17) and 0x71D67FFFEDA60000L)
        y = y xor ((y shl 37) and 0xFFF7EEE000000000uL.toLong())
        y = y xor (y ushr 43)
        return y
    }

    /** libc++ `generate_canonical<double, 53>` for a 64-bit engine: one draw over 2^64. */
    fun nextDouble(): Double = nextLong().toULong().toDouble() / 18446744073709551616.0

    /** `uniform_real_distribution<double>(a, b)`. */
    fun uniform(a: Double, b: Double): Double = (b - a) * nextDouble() + a

    /** A fresh `normal_distribution<double>(0, 1)` over this engine (libc++ polar method). */
    inner class Normal {
        private var hot = false
        private var cached = 0.0
        fun next(): Double {
            if (hot) { hot = false; return cached }
            var u: Double; var v: Double; var s: Double
            do {
                u = uniform(-1.0, 1.0); v = uniform(-1.0, 1.0); s = u * u + v * v
            } while (s > 1.0 || s == 0.0)
            val f = sqrt(-2.0 * ln(s) / s)
            cached = v * f; hot = true
            return u * f
        }
    }

    private companion object {
        val MATRIX_A = 0xB5026F5AA96619E9uL.toLong()
    }
}

internal object PianoMath {
    const val F_A4 = 440.0
    const val F_LIMIT = 17500.0
    val LN1000 = ln(1000.0)

    fun smoothstep(x0: Double): Double {
        val x = x0.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /** Railsback stretch: bass tuned flat, treble sharp. */
    fun midiToFreq(m: Double): Double {
        val x = (m - 69.0) / (if (m < 69.0) 48.0 else 39.0)
        val devCents = 14.0 * x * x * x + 16.0 * x.pow(7.0)
        return F_A4 * 2.0.pow((m - 69.0 + devCents / 100.0) / 12.0)
    }

    fun interpLog(m: Double, pts: DoubleArray): Double {
        // pts is (x0, y0, x1, y1, ...)
        if (m <= pts[0]) return pts[1]
        if (m >= pts[pts.size - 2]) return pts[pts.size - 1]
        var i = 2
        while (i < pts.size) {
            if (m <= pts[i]) {
                val t = (m - pts[i - 2]) / (pts[i] - pts[i - 2])
                return pts[i - 1] * (pts[i + 1] / pts[i - 1]).pow(t)
            }
            i += 2
        }
        return pts[pts.size - 1]
    }

    /** In-place iterative radix-2 complex FFT; [re]/[im] length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val br = re[b] * cr - im[b] * ci
                    val bi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - br; im[b] = im[a] - bi
                    re[a] += br; im[a] += bi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

/** Per-key physical description (coopiano `KeyParams`). */
internal class PianoKey(val midi: Double, val v: PianoVoicing, hardness: Double = 1.0, bridgeCouplingOverride: Double? = null) {
    val f0: Double = PianoMath.midiToFreq(midi)
    val L: Double = PianoMath.interpLog(midi, doubleArrayOf(21.0, 1.92, 36.0, 1.30, 48.0, 0.92, 60.0, 0.65, 69.0, 0.41, 84.0, 0.185, 108.0, 0.052))
    val T: Double = if (midi < 40) 760.0 else 700.0
    val mu: Double
    val ms: Double
    val B: Double
    val alpha: Double
    val T60_1: Double
    val c2: Double
    val mh: Double
    val K: Double
    val p: Double
    val fastRatio: Double
    val detScale: Double
    val fLong1: Double
    val sigmaH: Double
    val fundamentalGain: Double
    val strikeLevelGain: Double
    val lowMidDepthDb: Double
    val nStrings: Int = if (midi < 31) 1 else if (midi < 41) 2 else 3
    val hasDamper: Boolean
    val bridgeCoupling: Double = bridgeCouplingOverride ?: v.bridgeCoupling

    init {
        val c = 2.0 * L * f0
        mu = T / (c * c)
        ms = mu * L
        B = (7.0e-4 * (f0 / PianoMath.F_A4).pow(1.4) + 1.0e-4 * (55.0 / f0).coerceIn(0.0, 2.2)) * v.inharmonicityScale
        alpha = PianoMath.interpLog(midi, doubleArrayOf(21.0, 0.125, 60.0, 0.118, 84.0, 0.10, 108.0, 0.065))
        var t60 = PianoMath.interpLog(midi, doubleArrayOf(21.0, 58.0, 45.0, 48.0, 57.0, 35.0, 69.0, 24.0, 81.0, 14.0, 84.0, 10.0, 96.0, 4.2, 108.0, 1.2))
        var cc2 = 1.0e-7 * PianoMath.interpLog(midi, doubleArrayOf(21.0, 3.0, 33.0, 2.5, 44.0, 1.6, 52.0, 1.0, 60.0, 0.6, 72.0, 0.5, 108.0, 0.5))
        val trebleMix = PianoMath.smoothstep((midi - 84.0) / 6.0)
        val trebleDecay = if (midi >= 90.0) v.trebleDecayScale else 1.0 + (v.trebleDecayScale - 1.0) * trebleMix
        t60 *= trebleDecay * v.decayScale
        cc2 *= v.partialLossScale
        T60_1 = t60; c2 = cc2

        val t = ((midi - 21.0) / 87.0).coerceIn(0.0, 1.0)
        var mhh = (11.0 - 7.0 * t) * 1e-3
        p = 2.85 + 0.85 * t
        var kk = 10.0.pow(10.70 + 2.15 * t)
        var trebleHammer = (midi - 72.0).coerceIn(0.0, 36.0)
        trebleHammer = if (trebleHammer < 3.0) trebleHammer * trebleHammer / 6.0 else trebleHammer - 1.5
        kk *= 10.0.pow(4.0 * trebleHammer / 34.5)
        val gentleTreble = PianoMath.smoothstep((midi - 84.0) / 12.0)
        kk *= 10.0.pow(-0.4 * gentleTreble)
        val strikeOctaves = ((midi - 72.0) / 12.0).coerceIn(0.0, 3.0)
        val strikeDb = if (strikeOctaves <= 1.0) -3.0 * strikeOctaves * strikeOctaves else 3.0 - 6.0 * strikeOctaves
        val topTrim = PianoMath.smoothstep((midi - 84.0) / 24.0)
        strikeLevelGain = 10.0.pow((strikeDb - 2.0 * topTrim + 1.0 * gentleTreble) / 20.0)
        mhh *= v.massScale
        kk *= v.stiffnessScale * hardness
        mh = mhh; K = kk

        var fr = PianoMath.interpLog(midi, doubleArrayOf(21.0, 7.0, 69.0, 6.5, 84.0, 3.0, 96.0, 2.0, 108.0, 1.5))
        var ds = (620.0 / f0).coerceIn(0.30, 1.0)
        fr = max(1.0, fr * v.fastDecayScale)
        ds *= v.unisonDetuneScale
        val trebleUnison = PianoMath.smoothstep((midi - 79.0) / 5.0)
        ds += trebleUnison * max(0.0, v.trebleUnisonDetune - ds)
        fastRatio = fr; detScale = ds

        val wound = PianoMath.interpLog(midi, doubleArrayOf(21.0, 0.75, 33.0, 0.79, 41.0, 0.86, 47.0, 1.0, 108.0, 1.0))
        fLong1 = 5100.0 * wound / (2.0 * L)
        sigmaH = PianoMath.interpLog(midi, doubleArrayOf(21.0, 0.013, 60.0, 0.010, 84.0, 0.007, 108.0, 0.004)) * v.feltWidthScale
        val bodyRegister = PianoMath.smoothstep((midi - 33.0) / 12.0) * PianoMath.smoothstep((69.0 - midi) / 12.0)
        fundamentalGain = 10.0.pow(v.fundamentalBoostDb * bodyRegister / 20.0)
        lowMidDepthDb = v.lowerOvertoneCutDb * bodyRegister
        hasDamper = f0 < 1400.0
    }

    fun bridgeTrans(f: Double): Double {
        var db = 0.0
        if (f > FQ[0] && f < FQ[8]) {
            var i = 0
            while (f > FQ[i + 1]) ++i
            val tt = ln(f / FQ[i]) / ln(FQ[i + 1] / FQ[i])
            db = DB[i] + tt * (DB[i + 1] - DB[i])
        }
        val s = (ln(523.25 / f0) / ln(523.25 / 440.0)).coerceIn(0.0, 1.0)
        val bass = PianoMath.smoothstep(log2(PianoMath.midiToFreq(56.0) / f0) / (16.0 / 12.0))
        val low = PianoMath.smoothstep(log2(400.0 / f) / log2(400.0 / 150.0))
        val mid = exp(-0.5 * (log2(f / 850.0) / 0.7).pow(2.0))
        var shaped = (s * db * (1.0 - bass) + bass * (6.0 * low - 4.0 * mid)) * v.bridgeCurveScale
        if (v.midGainDb != 0.0) shaped += v.midGainDb * exp(-0.5 * (log2(f / v.midFrequencyHz) / v.midWidthOctaves).pow(2.0))
        return 10.0.pow(shaped / 20.0) * strikeLevelGain
    }

    fun lowerOvertoneGain(f: Double): Double {
        if (lowMidDepthDb <= 0.0 || f <= 160.0 || f >= 2000.0) return 1.0
        val rise = PianoMath.smoothstep(log2(f / 160.0) / log2(220.0 / 160.0))
        val fall = PianoMath.smoothstep(log2(2000.0 / f))
        return 10.0.pow(-lowMidDepthDb * rise * fall / 20.0)
    }

    fun trebleTilt(f: Double): Double {
        val r = PianoMath.smoothstep(log2(f0 / PianoMath.midiToFreq(79.0)) * 12.0 / 5.0)
        val x = log2(f / (1.4 * f0))
        if (r <= 0.0 || x <= 0.0) return 1.0
        return 10.0.pow(-r * min(12.0 * x, 18.0) / 20.0)
    }

    companion object {
        private val FQ = doubleArrayOf(250.0, 400.0, 600.0, 900.0, 1250.0, 1700.0, 2200.0, 3000.0, 4000.0)
        private val DB = doubleArrayOf(0.0, 1.0, 1.0, 2.0, 2.0, 8.0, 3.0, 0.0, 0.0)
    }
}

/** Soundboard driving-point admittance at the bridge, per Hz (coopiano `BridgeAdmittance`). */
internal object BridgeAdmittance {
    const val F_MAX = 12000.0
    const val MEAN_RE = 1.5e-3
    private val table: Pair<DoubleArray, DoubleArray> by lazy { build() }
    val re: DoubleArray get() = table.first
    val im: DoubleArray get() = table.second

    private fun build(): Pair<DoubleArray, DoubleArray> {
        val rng = PianoRng(0x6272696467655aL)
        val fm = ArrayList<Double>(); val sm = ArrayList<Double>(); val am = ArrayList<Double>()
        var f = 55.0
        while (f < F_MAX * 1.2) {
            val phi2 = -ln(max(1e-6, rng.nextDouble()))
            fm.add(f); sm.add(PI * 0.03 * f); am.add(phi2)
            f += 15.0 * (0.6 + 0.8 * rng.nextDouble())
        }
        val n = F_MAX.toInt() + 1
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 1 until n) {
            val w = 2 * PI * i
            var yr = 0.0; var yi = 0.0
            for (m in fm.indices) {
                val wm = 2 * PI * fm[m]
                // i*w*a / (wm^2 - w^2 + i*2*s*w)
                val dr = wm * wm - w * w; val di = 2 * sm[m] * w
                val den = dr * dr + di * di
                val nr = 0.0; val ni = w * am[m]
                yr += (nr * dr + ni * di) / den
                yi += (ni * dr - nr * di) / den
            }
            re[i] = yr; im[i] = yi
        }
        for (i in 0 until n) { val trend = 1.0 / (1.0 + i / 400.0); re[i] *= trend; im[i] *= trend }
        var sum = 0.0
        for (i in 150..300) sum += re[i]
        val scale = MEAN_RE / (sum / 151)
        for (i in 0 until n) { re[i] *= scale; im[i] *= scale }
        return re to im
    }

    fun realAt(f: Double): Double {
        if (f >= F_MAX) return MEAN_RE
        val i = f.toInt(); return re[i] + (f - floor(f)) * (re[i + 1] - re[i])
    }

    fun imagAt(f: Double): Double {
        if (f >= F_MAX) return 0.0
        val i = f.toInt(); return im[i] + (f - floor(f)) * (im[i + 1] - im[i])
    }
}

/** All modes of one string (coopiano `StringModes`), plus each mode's tuning parts for modulation. */
internal class PianoString(
    val f0s: Double,
    val n: Int,
    val w: DoubleArray,
    val wh: DoubleArray,
    val sig: DoubleArray,
    val g: DoubleArray,
    val sa: DoubleArray,
    val wBr: DoubleArray,
    /** Bridge-admittance pull on each mode, in Hz, added to the stiff-string frequency. */
    val shiftHz: DoubleArray,
) {
    val q = DoubleArray(n)
    val qd = DoubleArray(n)

    fun copyForStrike(gain: Double) = PianoString(f0s, n, w, wh, sig, g, sa, DoubleArray(n) { wBr[it] * gain }, shiftHz)

    companion object {
        fun build(k: PianoKey, detuneCents: Double): PianoString {
            val f0 = k.f0 * 2.0.pow(detuneCents / 1200.0)
            val sig1 = PianoMath.LN1000 / k.T60_1
            val zs = sqrt(k.T * k.ms / k.L)
            val w = ArrayList<Double>(); val sig = ArrayList<Double>(); val sa = ArrayList<Double>()
            val g = ArrayList<Double>(); val wbr = ArrayList<Double>(); val sh = ArrayList<Double>()
            for (n in 1..600) {
                var fn = n * f0 * sqrt(1.0 + k.B * n * n)
                if (fn > PianoMath.F_LIMIT) break
                val s = sig1 + k.c2 * (fn * fn - f0 * f0) / (1.0 + fn / 2500.0) + 0.8 * (fn / 4000.0).pow(3.0)
                var shift = 0.0
                if (k.bridgeCoupling > 0.0 && fn < BridgeAdmittance.F_MAX) {
                    shift = k.bridgeCoupling * f0 * zs * BridgeAdmittance.imagAt(fn) / PI
                    fn += shift
                }
                w.add(2 * PI * fn); sig.add(s); sh.add(shift)
                val hw = exp(-0.5 * (n * PI * k.sigmaH / k.L).pow(2.0))
                val a = sin(n * PI * k.alpha) * hw
                sa.add(a); g.add(2.0 / k.ms * a)
                wbr.add(k.T * (n * PI / k.L) * (if (n and 1 == 1) -1.0 else 1.0) *
                    k.bridgeTrans(fn) * k.trebleTilt(fn) * (if (n == 1) k.fundamentalGain else k.lowerOvertoneGain(fn)))
            }
            val wa = w.toDoubleArray()
            return PianoString(f0, wa.size, wa, DoubleArray(wa.size), sig.toDoubleArray(), g.toDoubleArray(), sa.toDoubleArray(), wbr.toDoubleArray(), sh.toDoubleArray())
        }

        /** The key's strings, tuned once per key (coopiano `prepare_key_strings`). */
        fun forKey(k: PianoKey): List<PianoString> {
            val tuning = PianoRng(0x7069616e6fL xor (k.f0 * 1048576.0).roundToLong())
            val det3 = doubleArrayOf(-1.15, 0.1, 0.7)
            val out = ArrayList<PianoString>()
            for (i in 0 until k.nStrings) {
                val d = if (k.nStrings == 1) 0.0 else det3[i] * k.detScale * (0.8 + 0.4 * tuning.uniform(-1.0, 1.0))
                out.add(build(k, d))
            }
            for (s in out) for (n in 0 until s.n) s.wh[n] = s.w[n] * (1.0 + 2.5e-6 * (1.0 + 0.8 * tuning.uniform(-1.0, 1.0)))
            return out
        }
    }
}

/**
 * Everything one strike needs before its first sample: the contact-phase attack buffer and the bank
 * of decaying phasors that takes over [coff] samples in. Struct-of-arrays so the audio loop is flat.
 */
internal class PianoNoteData(capacity: Int) {
    var count = 0
    var zr = DoubleArray(capacity); var zi = DoubleArray(capacity)
    var wOut = DoubleArray(capacity)
    /** Hold-stage decay rate (1/s) at decay scale 1. */
    var sigma = DoubleArray(capacity)
    /** Seated-damper decay floor (1/s). */
    var dampSig = DoubleArray(capacity)
    /** Partial numbers: one for a transverse mode, two (a + b) for a longitudinal phantom. */
    var na = IntArray(capacity); var nb = IntArray(capacity)
    var f0s = DoubleArray(capacity); var shA = DoubleArray(capacity); var shB = DoubleArray(capacity)
    /** Ratio of this polarization's frequency to the vertical one (the horizontal hair of detune). */
    var pol = DoubleArray(capacity)
    var longitudinal = BooleanArray(capacity)
    var stringIndex = IntArray(capacity)
    var amp0 = DoubleArray(capacity)

    var attack = DoubleArray(0)
    var coff = 0
    var dampNoise = DoubleArray(0)
    var peakAmp = 0.0
    var tonePeakAmp = 0.0
    var contactMs = 0.0
    var peakForce = 0.0
    var hammerVelocity = 0.0
    var B = 0.0
    var L = 1.0
    /** Mass of one string (kg), against which a preparation's mass is weighed. */
    var ms = 1.0

    fun add(): Int {
        if (count == zr.size) grow()
        return count++
    }

    private fun grow() {
        val n = zr.size * 2 + 16
        zr = zr.copyOf(n); zi = zi.copyOf(n); wOut = wOut.copyOf(n); sigma = sigma.copyOf(n)
        dampSig = dampSig.copyOf(n); na = na.copyOf(n); nb = nb.copyOf(n); f0s = f0s.copyOf(n)
        shA = shA.copyOf(n); shB = shB.copyOf(n); pol = pol.copyOf(n); longitudinal = longitudinal.copyOf(n)
        stringIndex = stringIndex.copyOf(n); amp0 = amp0.copyOf(n)
    }

    /** Hammer-on-string force, one value per output sample of the contact (newtons). */
    var hammerForce = DoubleArray(0)
    /** Close-mic thump: the hammer force through a short wooden body; scaled by `Piano.thump`. */
    var thump = DoubleArray(0)
    /** Key bottoming on the keybed (strike) and lifting back to the rail (release); `Piano.keyNoise`. */
    var keyNoise = DoubleArray(0)
    var releaseNoise = DoubleArray(0)

    /**
     * Angular frequency of phasor [i] for inharmonicity scale [s] (1 = as prepared).
     *
     * Any [s] is safe: [s] above zero stretches the partials by `sqrt(1 + s·B·n²)`; below zero
     * compresses them by the reciprocal of `sqrt(1 + |s|·B·n²)`, a mirror image that never reaches
     * zero. [pin] 0..1 moves the fundamental back to where it is at s = 1, so stiffness is heard as
     * timbre instead of detune (values outside 0..1 over- or under-correct, which is allowed).
     */
    fun omega(i: Int, s: Double, pin: Double = 0.0, fa: Double = 1.0, fb: Double = 1.0): Double {
        val f = f0s[i]
        var hz = (na[i] * f * pianoStiffness(s, B, na[i]) + shA[i]) * fa
        if (nb[i] > 0) hz += (nb[i] * f * pianoStiffness(s, B, nb[i]) + shB[i]) * fb
        if (pin != 0.0) hz *= Math.pow(pianoStiffness(1.0, B, 1) / pianoStiffness(s, B, 1), pin)
        return 2 * PI * hz * pol[i]
    }

    /** d(omega)/ds, for the per-sample correction inside a block (central difference). */
    fun dOmegaDs(i: Int, s: Double, pin: Double = 0.0, fa: Double = 1.0, fb: Double = 1.0): Double {
        val h = 1e-3 * max(1.0, abs(s))
        val d = (omega(i, s + h, pin, fa, fb) - omega(i, s - h, pin, fa, fb)) / (2 * h)
        return if (d.isFinite()) d else 0.0
    }
}

/** Partial-n stiffness factor for scale [s]: stretched above zero, mirrored (compressed) below; never NaN. */
internal fun pianoStiffness(s: Double, B: Double, n: Int): Double {
    val x = abs(s) * B * n * n
    val r = sqrt(1.0 + x)
    return if (s >= 0.0) r else 1.0 / r
}

/** Note preparation: hammer contact plus phasor collection (coopiano `prep_note_into`). */
internal object PianoStrike {
    private const val PHANTOM_FMAX = 4200.0
    private const val PHANTOM_PARENT_LIMIT = 0.65

    private class Parent(val ar: Double, val ai: Double, val w: Double, val sig: Double, val n: Int)

    class Contact(val durationMs: Double, val peakForce: Double, val force: DoubleArray, val hammer: DoubleArray)

    /** [hit] is how much of the hammer each string meets (1 all of it, 0 missed: the una corda shift). */
    fun runContact(k: PianoKey, strs: List<PianoString>, vh0: Double, fs: Double, hit: DoubleArray? = null): Contact {
        val os = 32
        val dt = 1.0 / (fs * os)
        val maxSteps = (0.030 * fs * os).toInt()
        var yh = 0.0; var vh = vh0
        var touched = false; var separated = false
        var peak = 0.0; var duration = 0.0
        val force = ArrayList<Double>()
        val hammer = ArrayList<Double>()
        var step = 0
        while (step < maxSteps) {
            if (separated && step % os == 0) break
            if (step % os == 0) {
                var fb = 0.0
                for (s in strs) for (n in 0 until s.n) fb += s.wBr[n] * s.q[n]
                force.add(fb)
            }
            var fTot = 0.0
            var anyContact = false
            for ((si, s) in strs.withIndex()) {
                val share = hit?.getOrNull(si) ?: 1.0
                var ys = 0.0
                for (n in 0 until s.n) ys += s.sa[n] * s.q[n]
                val delta = yh - ys
                var f = 0.0
                if (!separated && delta > 0.0 && share > 0.0) {
                    anyContact = true
                    var yds = 0.0
                    for (n in 0 until s.n) yds += s.sa[n] * s.qd[n]
                    val ddot = vh - yds
                    f = k.K * delta.pow(k.p) * (1.0 + 0.12 * ddot)
                    if (f < 0.0) f = 0.0
                    f *= share
                }
                fTot += f
                peak = max(peak, f)
                for (n in 0 until s.n) {
                    s.qd[n] += dt * (-s.w[n] * s.w[n] * s.q[n] - 2.0 * s.sig[n] * s.qd[n] + s.g[n] * f)
                    s.q[n] += dt * s.qd[n]
                }
            }
            if (step % os == 0) hammer.add(fTot)
            if (anyContact) touched = true
            if (!separated && touched && !anyContact && vh < 0.0) {
                separated = true
                duration = (step + 1) * dt * 1000.0
            }
            vh -= dt * fTot / k.mh
            yh += dt * vh
            step++
        }
        if (!separated) duration = step * dt * 1000.0
        return Contact(duration, peak, force.toDoubleArray(), hammer.toDoubleArray())
    }

    private fun aftersoundShare(partial: Int) = if (partial == 1) 0.35 else if (partial == 2) 0.6 else 1.0

    private fun fastPolarizationDecay(k: PianoKey, slowSigma: Double, fHz: Double): Double {
        val base = PianoMath.LN1000 / k.T60_1
        var prompt = base * k.fastRatio
        if (k.bridgeCoupling > 0.0 && fHz < BridgeAdmittance.F_MAX) {
            val ratio = (3.0 * BridgeAdmittance.realAt(fHz) / BridgeAdmittance.MEAN_RE).coerceIn(0.1, 8.0)
            prompt *= ratio.pow(k.bridgeCoupling)
        }
        return prompt + max(0.0, slowSigma - base) * sqrt(k.fastRatio)
    }

    private fun longitudinalGain(f: Double, fLong1: Double): Double {
        var g = 1.0
        var m = 1
        while (m * fLong1 < PHANTOM_FMAX + 800.0) {
            val fm = m * fLong1
            val bw = fm / 50.0
            g += 4.6 * bw * bw / ((f - fm) * (f - fm) + bw * bw)
            m++
        }
        return g
    }

    private fun middleLongitudinalScale(frequency: Double): Double {
        if (frequency <= PianoMath.midiToFreq(40.0)) return 1.0
        if (frequency >= PianoMath.midiToFreq(56.0)) return 0.0
        val midi = 69.0 + 12.0 * log2(frequency / 440.0)
        if (midi <= 40.0) return 1.0
        if (midi < 48.0) return 1.0 - 0.7 * PianoMath.smoothstep((midi - 40.0) / 8.0)
        return 0.3 * (1.0 - PianoMath.smoothstep((midi - 48.0) / 8.0))
    }

    private fun addPhantoms(
        k: PianoKey, par: List<Parent>, dampSigKey: Double, out: PianoNoteData, registerGain: Double,
        softenHighs: Boolean, s: PianoString, si: Int,
    ) {
        if (par.isEmpty()) return
        var maxa = 0.0
        for (p in par) maxa = max(maxa, Math.hypot(p.ar, p.ai))
        val thresh = 1e-4 * maxa
        for (i in par.indices) {
            for (j in i until min(i + 4, par.size)) {
                val wPh = par[i].w + par[j].w
                val fPh = wPh / (2 * PI)
                if (fPh > PHANTOM_FMAX) break
                val sel = 0.55.pow((j - i).toDouble())
                var zr = k.v.longitudinalGain * sel * (par[i].ar * par[j].ar - par[i].ai * par[j].ai)
                var zi = k.v.longitudinalGain * sel * (par[i].ar * par[j].ai + par[i].ai * par[j].ar)
                val tilt = k.bridgeTrans(fPh) / (k.bridgeTrans(par[i].w / (2 * PI)) * k.bridgeTrans(par[j].w / (2 * PI)))
                zr *= tilt; zi *= tilt
                // bound_phantom
                val limit = PHANTOM_PARENT_LIMIT * sqrt(Math.hypot(par[i].ar, par[i].ai) * Math.hypot(par[j].ar, par[j].ai))
                if (limit <= 0.0) continue
                val ratio = Math.hypot(zr, zi) / limit
                val bound = (1.0 + ratio.pow(8.0)).pow(-1.0 / 8.0)
                zr *= bound; zi *= bound
                val lg = longitudinalGain(fPh, k.fLong1)
                zr *= lg; zi *= lg
                if (Math.hypot(zr, zi) < thresh) continue
                var gain = k.lowerOvertoneGain(fPh) * registerGain
                if (softenHighs) {
                    val band = PianoMath.smoothstep((fPh - 500.0) / 500.0)
                    gain *= 1.0 - 0.75 * band
                }
                zr *= gain; zi *= gain
                val idx = out.add()
                val sg = par[i].sig + par[j].sig
                out.zr[idx] = zr; out.zi[idx] = zi; out.wOut[idx] = 1.0
                out.sigma[idx] = sg; out.dampSig[idx] = 2.0 * dampSigKey
                out.longitudinal[idx] = true
                out.na[idx] = par[i].n; out.nb[idx] = par[j].n
                out.f0s[idx] = s.f0s
                out.shA[idx] = s.shiftHz[par[i].n - 1]; out.shB[idx] = s.shiftHz[par[j].n - 1]
                // Both parents share one polarization; its detune ratio is per mode, so recover
                // the exact phantom frequency by scaling the sum against its nominal value.
                val nominal = 2 * PI * (par[i].n * s.f0s * sqrt(1 + k.B * par[i].n * par[i].n) + out.shA[idx] +
                    par[j].n * s.f0s * sqrt(1 + k.B * par[j].n * par[j].n) + out.shB[idx])
                out.pol[idx] = wPh / nominal
                out.stringIndex[idx] = si
                out.amp0[idx] = Math.hypot(zr, zi)
            }
        }
    }

    fun prepare(k: PianoKey, keyStrings: List<PianoString>, velocity0: Double, seed: Long, fs: Double, hit: DoubleArray? = null): PianoNoteData {
        val rng = PianoRng(seed)
        val g = rng.Normal()
        val velocity = velocity0.coerceIn(0.02, 1.0)
        val vh = k.v.maximumVelocityMps * velocity.pow(k.v.velocityExponent)
        val strs = keyStrings.mapIndexed { i, s ->
            val asym = if (keyStrings.size == 2) (if (i == 0) 0.25 else -0.25) else 0.0
            s.copyForStrike((1.0 + k.v.horizontalRatio) * (1.0 + asym + 0.13 * rng.uniform(-1.0, 1.0)))
        }
        val con = runContact(k, strs, vh, fs, hit)
        val out = PianoNoteData(256)
        out.B = k.B; out.L = k.L; out.ms = k.ms
        collectPhasors(k, strs, out)
        out.coff = con.force.size
        for (i in 0 until out.count) out.peakAmp = max(out.peakAmp, out.amp0[i])

        // attack: contact force + thump + scrape
        val klen = (12e-3 * fs).toInt()
        val slen = (0.025 * fs).toInt()
        var attack = DoubleArray(max(out.coff, max(klen, slen)))
        for (i in 0 until out.coff) attack[i] += con.force[i]
        val gain = if (!k.v.knock) 0.0 else 2.2 * out.tonePeakAmp * knockVelocity(velocity, k.f0)
        val trebleKnock = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(84.0)))
        val topOctave = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(96.0)))
        val thump = gain * (1.0 + 0.8 * trebleKnock * (1.0 - topOctave))
        var lpa = 0.0; var lpb = 0.0
        for (i in 0 until klen) {
            val x = i.toDouble() / klen
            val pulse = sin(PI * x) * sin(PI * x)
            lpa = 0.92 * lpa + 0.08 * g.next()
            lpb = 0.92 * lpb + 0.08 * lpa
            attack[i] += thump * (pulse + 3.4 * lpb * sin(PI * x))
        }
        val body = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(68.0)) * 1.5)
        if (k.v.knock && body > 0.0) {
            val wave = structuralKnock(fs)
            if (attack.size < wave.size) attack = attack.copyOf(wave.size)
            val scale = 0.20 * body * gain
            for (i in wave.indices) attack[i] += scale * wave[i]
        }
        val sgain = if (!k.v.scrape) 0.0 else 0.011 * out.tonePeakAmp * velocity.pow(1.8)
        var h1 = 0.0; var x1 = 0.0; var lp2 = 0.0
        for (i in 0 until slen) {
            val t = i.toDouble() / slen
            val x = g.next()
            lp2 = 0.45 * lp2 + 0.55 * x
            val hp = lp2 - x1 + 0.93 * h1
            x1 = lp2; h1 = hp
            attack[i] += sgain * hp * exp(-7.0 * t) * (1.0 - exp(-40.0 * t))
        }
        if (k.v.knock && trebleKnock > 0.0) {
            var ma = 0.0; var mb = 0.0; var mc = 0.0
            for (i in 0 until klen) {
                val t = i / fs
                val env = (1.0 - exp(-t / 0.0015)) * exp(-t / 0.004)
                ma = 0.8 * ma + 0.2 * g.next(); mb = 0.8 * mb + 0.2 * ma; mc = 0.8 * mc + 0.2 * mb
                attack[i] += 11.0 * trebleKnock * gain * env * mc
            }
        }
        out.attack = attack
        if (k.hasDamper && k.v.damperNoise) {
            val len = (0.030 * fs).toInt()
            val dn = DoubleArray(len)
            val gn = rng.Normal()
            val g = 0.0035 * out.tonePeakAmp * (0.4 + 0.6 * velocity) * (261.0 / k.f0).pow(0.3).coerceIn(0.5, 1.4)
            var lp = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / len
                lp = 0.85 * lp + 0.15 * gn.next()
                dn[i] = g * lp * exp(-4.0 * t) * (1 - exp(-25 * t))
            }
            out.dampNoise = dn
        }
        out.contactMs = con.durationMs; out.peakForce = con.peakForce; out.hammerVelocity = vh
        closeMicNoises(k, con, out, velocity, seed, fs)
        return out
    }

    /**
     * Mechanism sounds a close microphone hears, built from this strike's own hammer force and with
     * their own random stream, so the coopiano-matched draws above are untouched.
     */
    private fun closeMicNoises(k: PianoKey, con: Contact, out: PianoNoteData, velocity: Double, seed: Long, fs: Double) {
        val gn = PianoRng(seed xor 0x5DEECE66DL).Normal()
        val level = out.tonePeakAmp
        val hf = con.hammer
        out.hammerForce = hf
        val peak = max(1e-9, hf.maxOrNull() ?: 0.0)
        // thump: the force pulse itself (the felt's push) plus two wooden body modes it rings
        val tlen = (0.08 * fs).toInt()
        val th = DoubleArray(tlen)
        val bodies = doubleArrayOf(95.0, 210.0 * (k.f0 / 261.6).pow(0.15))
        val decays = doubleArrayOf(35.0, 60.0)
        val wts = doubleArrayOf(1.0, 0.55)
        for (b in bodies.indices) {
            val w = 2 * PI * bodies[b] / fs
            val r = exp(-decays[b] / fs)
            val c1 = 2 * r * cos(w); val c2 = -r * r
            var y1 = 0.0; var y2 = 0.0
            for (i in 0 until tlen) {
                val x = if (i < hf.size) hf[i] / peak else 0.0
                val y = x * w + c1 * y1 + c2 * y2
                y2 = y1; y1 = y
                th[i] += wts[b] * y
            }
        }
        for (i in 0 until min(tlen, hf.size)) th[i] += 0.6 * hf[i] / peak
        val tg = 0.5 * level * velocity.pow(0.8)
        for (i in 0 until tlen) th[i] *= tg
        out.thump = th
        // key noise: keybed knock a few ms after the hammer leaves, and a softer release click
        fun burst(len: Double, delay: Double, hz: DoubleArray, decay: Double, amp: Double): DoubleArray {
            val n = ((len + delay) * fs).toInt()
            val o = DoubleArray(n)
            val d0 = (delay * fs).toInt()
            for (f in hz) {
                val w = 2 * PI * f / fs; val r = exp(-1.0 / (0.004 * fs))
                val c1 = 2 * r * cos(w); val c2 = -r * r
                var y1 = 0.0; var y2 = 0.0
                for (i in d0 until n) {
                    val t = (i - d0) / fs
                    val x = gn.next() * exp(-decay * t) * (1 - exp(-t / 0.0004))
                    val y = x * (1 - r) + c1 * y1 + c2 * y2
                    y2 = y1; y1 = y
                    o[i] += amp * y
                }
            }
            return o
        }
        val kv = out.hammerVelocity / max(1e-9, k.v.maximumVelocityMps)
        out.keyNoise = burst(0.03, (con.durationMs * 1e-3) + 0.006, doubleArrayOf(380.0, 1450.0, 3100.0), 140.0, 0.9 * level * kv)
        out.releaseNoise = burst(0.025, 0.0, doubleArrayOf(620.0, 2300.0), 180.0, 0.35 * level)
    }

    private fun knockVelocity(velocity: Double, f0: Double): Double {
        val excess = max(0.0, velocity - 0.5)
        val restrained = min(velocity, 0.5) + 0.12 * tanh(excess / 0.12)
        val lowMid = log2(880.0 / f0).coerceIn(0.0, 1.0)
        return velocity + lowMid * (restrained - velocity)
    }

    private val knockCache = HashMap<Double, DoubleArray>()

    @Synchronized
    fun structuralKnock(fs: Double): DoubleArray = knockCache.getOrPut(fs) {
        val samples = DoubleArray((0.65 * fs).toInt())
        val freq = doubleArrayOf(89.0, 117.0, 163.0, 231.0, 277.0)
        val weight = doubleArrayOf(1.0, 0.65, 0.32, 0.20, 0.28)
        for (j in 0 until 5) {
            val decay = 8.0 + 0.01 * freq[j]
            for (i in samples.indices) {
                val t = i / fs
                val rise = 1.0 - exp(-t / 0.003)
                val end = ((0.65 - t) / 0.04).coerceIn(0.0, 1.0)
                samples[i] += weight[j] * rise * rise * exp(-decay * t) * sin(2.0 * PI * freq[j] * t) * end * end
            }
        }
        samples
    }

    private fun collectPhasors(k: PianoKey, strs: List<PianoString>, out: PianoNoteData) {
        val registerGain = middleLongitudinalScale(k.f0)
        val wantPhantoms = registerGain > 0.0
        val keyDampSig = if (k.hasDamper)
            PianoMath.LN1000 / (0.25 * (220.0 / k.f0).pow(0.3)).coerceIn(0.06, 0.45) else 0.0
        val topTail = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(84.0)) * 2.0)
        val lastOctave = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(96.0)))
        val upper = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(72.0)) * 3.0)
        val high = PianoMath.smoothstep(log2(k.f0 / PianoMath.midiToFreq(76.0)) * 4.0)
        for ((si, s) in strs.withIndex()) {
            val parV = ArrayList<Parent>(); val parH = ArrayList<Parent>()
            for (n in 0 until s.n) {
                val w = s.w[n]
                var sg = s.sig[n]
                val q = s.q[n]; val qd = s.qd[n]
                if (q == 0.0 && qd == 0.0) continue
                val dampSig = if (k.hasDamper) keyDampSig else sg
                var sgv = fastPolarizationDecay(k, sg, w / (2 * PI))
                sg *= 1.0 - 0.45 * topTail - 0.25 * lastOctave
                val loss = upper * 0.6 * min(n, 3)
                sg += loss; sgv += loss
                val earlyRate = (if (n == 0) 10.0 else 5.0 + 2.0 * min(n, 3) + 3.0 * high) * (1.0 + 0.6 * lastOctave)
                val earlyMix = if (n == 0) high else upper
                sgv += earlyMix * max(0.0, earlyRate - sgv)
                val tailScale = if (n == 0) 1.0 else if (n == 1) 0.85 - 0.20 * high else 0.75 - 0.25 * high
                val share = k.v.horizontalRatio * aftersoundShare(n + 1) * (1.0 + upper * (tailScale - 1.0))

                // vertical polarization: Re(z) = q, Re((-sigma + i w) z) = qd
                val shV = 1.0 / (1.0 + share)
                val iv = out.add()
                out.zr[iv] = shV * q; out.zi[iv] = shV * (-(qd + sgv * q) / w)
                out.wOut[iv] = s.wBr[n]; out.sigma[iv] = sgv; out.dampSig[iv] = dampSig
                out.na[iv] = n + 1; out.nb[iv] = 0; out.f0s[iv] = s.f0s; out.shA[iv] = s.shiftHz[n]; out.shB[iv] = 0.0
                out.pol[iv] = 1.0; out.longitudinal[iv] = false; out.stringIndex[iv] = si
                out.amp0[iv] = Math.hypot(out.zr[iv], out.zi[iv]) * abs(out.wOut[iv])

                val wh = s.wh[n]
                val shH = share / (1.0 + share)
                val ih = out.add()
                out.zr[ih] = shH * q; out.zi[ih] = shH * (-(qd + sg * q) / wh)
                out.wOut[ih] = s.wBr[n]; out.sigma[ih] = sg; out.dampSig[ih] = dampSig
                out.na[ih] = n + 1; out.nb[ih] = 0; out.f0s[ih] = s.f0s; out.shA[ih] = s.shiftHz[n]; out.shB[ih] = 0.0
                out.pol[ih] = wh / w; out.longitudinal[ih] = false; out.stringIndex[ih] = si
                out.amp0[ih] = Math.hypot(out.zr[ih], out.zi[ih]) * abs(out.wOut[ih])

                val voicing = if (n == 0) k.fundamentalGain else k.lowerOvertoneGain(w / (2 * PI))
                val parentWeight = s.wBr[n] / voicing
                out.tonePeakAmp = max(out.tonePeakAmp, max(
                    Math.hypot(out.zr[iv], out.zi[iv]) * abs(parentWeight),
                    Math.hypot(out.zr[ih], out.zi[ih]) * abs(parentWeight)))
                if (wantPhantoms && w / (2 * PI) < 3500.0) {
                    parV.add(Parent(out.zr[iv] * parentWeight, out.zi[iv] * parentWeight, w, sgv, n + 1))
                    parH.add(Parent(out.zr[ih] * parentWeight, out.zi[ih] * parentWeight, wh, sg, n + 1))
                }
            }
            if (wantPhantoms) {
                addPhantoms(k, parV, keyDampSig, out, registerGain, k.v.softenLongitudinalHighs, s, si)
                addPhantoms(k, parH, keyDampSig, out, registerGain, k.v.softenLongitudinalHighs, s, si)
            }
        }
    }
}

/** Damper contact for a sustain-pedal position (0 up .. 1 down), as a fraction of the seated rate. */
internal fun pianoDamperContact(pedal: Double): Double {
    val x = (pedal - 0.25) / (0.65 - 0.25)
    if (!(x > 0.0)) return 1.0
    if (x >= 1.0) return 0.0
    val seated = 1.0 - x * x * (3.0 - 2.0 * x)
    return seated * seated
}

/** The shared stereo soundboard impulse response (coopiano `make_soundboard_ir`). */
internal object PianoSoundboardIr {
    private val cache = HashMap<Pair<Double, PianoVoicing>, Pair<DoubleArray, DoubleArray>>()

    @Synchronized
    fun get(fs: Double, v: PianoVoicing): Pair<DoubleArray, DoubleArray> {
        val key = fs to v.copy(knock = true, scrape = true, damperNoise = true)
        return cache.getOrPut(key) { build(fs, v, 0xb0a4d5eedL) }
    }

    fun radiation(f: Double, v: PianoVoicing): Double =
        (f * f / (f * f + v.boardBassCornerHz * v.boardBassCornerHz)) / (1.0 + (f / v.boardTrebleCornerHz).pow(v.boardTrebleSlope))

    private fun modeSpacing(f: Double, density: Double): Double {
        val dense = 3.2 + f * 0.0042
        val sparse = max(dense, 24.0 * (1.0 - f / 450.0))
        if (density <= 0.0) return sparse
        if (density >= 1.0) return dense
        return sparse + density * (dense - sparse)
    }

    private fun build(fs: Double, v: PianoVoicing, seed: Long): Pair<DoubleArray, DoubleArray> {
        val n = (1.4 * v.boardDecayScale * fs).toInt()
        val irL = DoubleArray(n); val irR = DoubleArray(n)
        val rng = PianoRng(seed)
        fun um() = rng.uniform(-1.0, 1.0)
        var f = 48.0
        while (f < min(16500.0, fs * 0.45)) {
            val t60 = min(0.04 + 0.30 * (100.0 / f).pow(0.7), 0.45) * v.boardDecayScale
            val sig = PianoMath.LN1000 / t60
            val amp = radiation(f, v) * 10.0.pow(um() * 3.0 / 20.0)
            val phL = rng.nextDouble() * 2 * PI
            val coher = (f / 700.0).coerceIn(0.0, 1.0)
            val phR = phL + coher * um() * PI
            val ampR = amp * 10.0.pow(coher * um() * 3.0 / 20.0)
            var lr = amp * cos(phL); var li = amp * sin(phL)
            var rr = ampR * cos(phR); var ri = ampR * sin(phR)
            val mag = exp(-sig / fs); val sr = mag * cos(2 * PI * f / fs); val sim = mag * sin(2 * PI * f / fs)
            val len = min(n, (t60 * 1.15 * fs).toInt())
            for (i in 0 until len) {
                irL[i] += li; irR[i] += ri
                val nlr = lr * sr - li * sim; li = lr * sim + li * sr; lr = nlr
                val nrr = rr * sr - ri * sim; ri = rr * sim + ri * sr; rr = nrr
            }
            f += modeSpacing(f, v.boardLowModeDensity) * (0.75 + 0.5 * rng.nextDouble())
        }
        flatten(irL, v, fs); flatten(irR, v, fs)
        var e = 0.0
        for (i in 0 until n) e += irL[i] * irL[i] + irR[i] * irR[i]
        val s = 1.0 / sqrt(e / 2.0)
        for (i in 0 until n) { irL[i] *= s; irR[i] *= s }
        return irL to irR
    }

    /** Minimum-phase equalisation toward the radiation target (coopiano `ir_correction`). */
    private fun flatten(ir: DoubleArray, v: PianoVoicing, fs: Double) {
        var n = 1
        while (n < ir.size * 2) n = n shl 1
        val hr = DoubleArray(n); val hi = DoubleArray(n)
        System.arraycopy(ir, 0, hr, 0, ir.size)
        PianoMath.fft(hr, hi, false)
        val half = n / 2
        val logmag = DoubleArray(half + 1) { ln(Math.hypot(hr[it], hi[it]) + 1e-12) }
        val cum = DoubleArray(half + 2)
        for (i in 0..half) cum[i + 1] = cum[i] + logmag[i]
        val smooth = DoubleArray(half + 1)
        for (i in 0..half) {
            val lo = (i / 1.122).toInt(); val hiI = min(half, (i * 1.122).toInt() + 1)
            smooth[i] = if (hiI <= lo) logmag[i] else (cum[hiI + 1] - cum[lo]) / (hiI - lo + 1)
        }
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        for (i in 0..half) {
            val f = i * fs / n
            val target = max(1e-5, radiation(f, v)) / sqrt(1.0 + (f / 17500.0).pow(24.0))
            val ripple = (logmag[i] - smooth[i]).coerceIn(-ln(10.0), ln(10.0))
            val keep = v.boardRippleFraction + (1.0 - v.boardRippleFraction) * ((450.0 - f) / 250.0).coerceIn(0.0, 1.0)
            cr[i] = ln(target) - smooth[i] - (1.0 - keep) * ripple
            if (i in 1 until half) cr[n - i] = cr[i]
        }
        PianoMath.fft(cr, ci, true)
        for (i in 1 until half) { cr[i] *= 2.0; ci[i] *= 2.0 }
        for (i in half + 1 until n) { cr[i] = 0.0; ci[i] = 0.0 }
        PianoMath.fft(cr, ci, false)
        // exp of complex cepstrum spectrum, times H
        for (i in 0 until n) {
            val m = exp(cr[i]); val er = m * cos(ci[i]); val ei = m * sin(ci[i])
            val r = hr[i] * er - hi[i] * ei; val im = hr[i] * ei + hi[i] * er
            hr[i] = r; hi[i] = im
        }
        PianoMath.fft(hr, hi, true)
        for (i in ir.indices) ir[i] = hr[i]
    }
}

/**
 * Zero-latency stereo convolution of one mono input with a long stereo response: the first
 * partition direct-form, the rest uniformly partitioned in the frequency domain.
 */
internal class PianoBoardConvolver(irL: DoubleArray, irR: DoubleArray, private val p: Int = 256) {
    private val n2 = 2 * p
    private val parts = (irL.size + p - 1) / p
    private val headL = DoubleArray(p) { if (it < irL.size) irL[it] else 0.0 }
    private val headR = DoubleArray(p) { if (it < irR.size) irR[it] else 0.0 }
    private val hLr = Array(parts) { DoubleArray(n2) }; private val hLi = Array(parts) { DoubleArray(n2) }
    private val hRr = Array(parts) { DoubleArray(n2) }; private val hRi = Array(parts) { DoubleArray(n2) }
    private val xr = Array(parts) { DoubleArray(n2) }; private val xi = Array(parts) { DoubleArray(n2) }
    private var xHead = 0 // ring index of the newest input spectrum
    private val history = DoubleArray(p) // direct-form history (previous frame)
    private val frame = DoubleArray(p)   // current input frame
    private val prevFrame = DoubleArray(p)
    private var pos = 0
    private val tailL = DoubleArray(p); private val tailR = DoubleArray(p)
    private val ar = DoubleArray(n2); private val ai = DoubleArray(n2)
    private val br = DoubleArray(n2); private val bi = DoubleArray(n2)

    init {
        for (k in 1 until parts) {
            for (i in 0 until p) {
                val idx = k * p + i
                if (idx < irL.size) hLr[k][i] = irL[idx]
                if (idx < irR.size) hRr[k][i] = irR[idx]
            }
            PianoMath.fft(hLr[k], hLi[k], false)
            PianoMath.fft(hRr[k], hRi[k], false)
        }
    }

    fun reset() {
        for (a in xr) a.fill(0.0); for (a in xi) a.fill(0.0)
        history.fill(0.0); frame.fill(0.0); prevFrame.fill(0.0); tailL.fill(0.0); tailR.fill(0.0); pos = 0
    }

    /** One sample in, one stereo sample out through [out] (index 0 left, 1 right). */
    fun process(x: Double, out: DoubleArray) {
        frame[pos] = x
        var l = tailL[pos]; var r = tailR[pos]
        // direct head: y[n] += sum_{i<p} h[i] x[n-i]
        var i = 0
        var j = pos
        while (i <= pos) { val v = frame[j]; l += headL[i] * v; r += headR[i] * v; i++; j-- }
        j = p - 1
        while (i < p) { val v = prevFrame[j]; l += headL[i] * v; r += headR[i] * v; i++; j-- }
        out[0] = l; out[1] = r
        pos++
        if (pos == p) { pos = 0; frameDone() }
    }

    private fun frameDone() {
        // input spectrum of [prevFrame, frame]
        xHead = (xHead + 1) % parts
        val sr = xr[xHead]; val si = xi[xHead]
        System.arraycopy(prevFrame, 0, sr, 0, p); System.arraycopy(frame, 0, sr, p, p)
        si.fill(0.0)
        PianoMath.fft(sr, si, false)
        System.arraycopy(frame, 0, prevFrame, 0, p)
        // tail for the next frame m: sum_{k>=1} X_{m-k} H_k, newest spectrum is X_{m-1}
        ar.fill(0.0); ai.fill(0.0); br.fill(0.0); bi.fill(0.0)
        for (k in 1 until parts) {
            val xi0 = ((xHead - (k - 1)) % parts + parts) % parts
            val xrr = xr[xi0]; val xii = xi[xi0]
            val lr = hLr[k]; val li = hLi[k]; val rr = hRr[k]; val ri = hRi[k]
            for (b in 0 until n2) {
                val a = xrr[b]; val c = xii[b]
                ar[b] += a * lr[b] - c * li[b]; ai[b] += a * li[b] + c * lr[b]
                br[b] += a * rr[b] - c * ri[b]; bi[b] += a * ri[b] + c * rr[b]
            }
        }
        PianoMath.fft(ar, ai, true); PianoMath.fft(br, bi, true)
        System.arraycopy(ar, p, tailL, 0, p); System.arraycopy(br, p, tailR, 0, p)
    }
}

/** 88 keys of sympathetic string modes (coopiano `SympatheticResonance`). */
internal class PianoResonance(v: PianoVoicing, private val fs: Double) {
    private val begin = IntArray(88); private val end = IntArray(88)
    private val undamped = BooleanArray(88)
    val held = IntArray(88)
    private val openness = DoubleArray(88)
    private val re: DoubleArray; private val im: DoubleArray
    private val cs: DoubleArray; private val sn: DoubleArray
    private val openR: DoubleArray; private val closedR: DoubleArray; private val drive: DoubleArray
    private val ramp = 1.0 / (v.resonanceTransitionSeconds * fs)

    init {
        val c = ArrayList<Double>(); val s = ArrayList<Double>(); val o = ArrayList<Double>(); val cl = ArrayList<Double>(); val d = ArrayList<Double>()
        for (key in 0 until 88) {
            val k = PianoKey(21.0 + key, v)
            begin[key] = c.size
            undamped[key] = !k.hasDamper
            openness[key] = if (undamped[key]) 1.0 else 0.0
            for (partial in 1..16) {
                val f = partial * k.f0 * sqrt(1.0 + k.B * partial * partial)
                if (f > min(7000.0, fs * 0.45)) break
                val sigma = PianoMath.LN1000 / min(k.T60_1, v.resonanceMaximumDecaySeconds) + k.c2 * (f * f - k.f0 * k.f0)
                val open = exp(-sigma / fs)
                val closed = exp(-max(sigma, PianoMath.LN1000 / v.resonanceDamperDecaySeconds) / fs)
                val angle = 2.0 * PI * f / fs
                val weight = 1.0 / (partial * (1.0 + (f / 3500.0).pow(2.0)))
                c.add(cos(angle)); s.add(sin(angle)); o.add(open); cl.add(closed); d.add(2.0 * (1.0 - open) * weight)
            }
            end[key] = c.size
        }
        cs = c.toDoubleArray(); sn = s.toDoubleArray(); openR = o.toDoubleArray(); closedR = cl.toDoubleArray(); drive = d.toDoubleArray()
        re = DoubleArray(cs.size); im = DoubleArray(cs.size)
    }

    val modeCount: Int get() = cs.size

    /** One sample: returns the wet resonance for bridge-force [input] and pedal lift [pedal] 0..1. */
    fun process(input0: Double, pedal: Double, own: Array<DoubleArray>, active: BooleanArray, at: Int, latched: BooleanArray? = null): Double {
        var wet = 0.0
        for (g in 0 until 88) {
            // a key's own force does not drive its own resonators (coopiano own_drive = 0)
            val input = if (active[g]) input0 - own[g][at] else input0
            val target = if (held[g] > 0 || undamped[g] || (latched != null && latched[g])) 1.0 else pedal
            var op = openness[g]
            if (op < target) op = min(target, op + ramp) else if (op > target) op = max(target, op - ramp)
            openness[g] = op
            if (op == 0.0 && re[begin[g]] == 0.0 && im[begin[g]] == 0.0) {
                // quiet and closed: nothing to do unless any mode still rings
                var any = false
                for (m in begin[g] until end[g]) if (re[m] != 0.0 || im[m] != 0.0) { any = true; break }
                if (!any) continue
            }
            for (m in begin[g] until end[g]) {
                val radius = closedR[m] + op * (openR[m] - closedR[m])
                val r = radius * (cs[m] * re[m] - sn[m] * im[m]) + drive[m] * op * input
                val i = radius * (sn[m] * re[m] + cs[m] * im[m])
                if (abs(r) + abs(i) < 1e-30) { re[m] = 0.0; im[m] = 0.0 } else { re[m] = r; im[m] = i }
                wet += re[m]
            }
        }
        return wet
    }
}

/** The room: an 8-line feedback delay network with early taps (coopiano `room::StereoReverb`). */
internal class PianoRoom(fs: Double, v: PianoVoicing) {
    private class Line(length: Int) {
        val s = DoubleArray(max(length, 0)); var head = 0
        fun read() = s[head]
        fun tap(lag: Int) = s[(head + s.size - lag) % s.size]
        fun push(x: Double) { s[head] = if (abs(x) < 1e-30) 0.0 else x; if (++head == s.size) head = 0 }
        fun process(x: Double): Double { if (s.isEmpty()) return x; val d = read(); push(x); return d }
        fun allpass(x: Double): Double { val o = read() - 0.6 * x; push(x + 0.6 * o); return o }
    }

    private fun scaled(n: Int, fs: Double) = max(1, Math.round(n * fs / 44100.0).toInt())
    private val lowpass = exp(-2 * PI * v.reverbDampingHz / fs)
    private val highpass = exp(-2 * PI * 150.0 / fs)
    private val lines = intArrayOf(1493, 1601, 1747, 1867, 1999, 2131, 2281, 2423).map { Line(scaled(it, fs)) }
    private val feedback = DoubleArray(8) { 10.0.pow(-3.0 * lines[it].s.size / (fs * v.reverbDecaySeconds)) }
    private val predelay = Array(2) { Line(Math.round(v.reverbPredelayMs * fs / 1000.0).toInt()) }
    private val diffusion = intArrayOf(191, 337, 211, 373).map { Line(scaled(it, fs)) }
    private val taps = doubleArrayOf(7.0, 11.0, 17.0, 23.0, 29.0, 31.0).map { max(1, Math.round(it * fs / 1000.0).toInt()) }
    private val early = Array(2) { Line(taps.last() + 1) }
    private val filtered = DoubleArray(8)
    private val prevIn = DoubleArray(2); private val hpState = DoubleArray(2)
    private val mixed = DoubleArray(8)
    private val input = DoubleArray(2)

    /** Empties every delay line and filter. */
    fun reset() {
        for (l in lines + diffusion + predelay.toList() + early.toList()) { l.s.fill(0.0); l.head = 0 }
        filtered.fill(0.0); prevIn.fill(0.0); hpState.fill(0.0); mixed.fill(0.0); input.fill(0.0)
    }

    /** Adds the wet room to [lr] in place, scaled by [mix]. */
    fun process(lr: DoubleArray, mix: Double) {
        input[0] = lr[0]; input[1] = lr[1]
        for (c in 0..1) {
            val value = input[c] - prevIn[c] + highpass * hpState[c]
            prevIn[c] = input[c]
            hpState[c] = if (abs(value) < 1e-30) 0.0 else value
            input[c] = predelay[c].process(hpState[c])
        }
        val earlyL = .55 * early[0].tap(taps[0]) + .30 * early[1].tap(taps[2]) - .15 * early[0].tap(taps[4])
        val earlyR = .55 * early[1].tap(taps[1]) + .30 * early[0].tap(taps[3]) - .15 * early[1].tap(taps[5])
        early[0].push(input[0]); early[1].push(input[1])
        val l = diffusion[1].allpass(diffusion[0].allpass(input[0]))
        val r = diffusion[3].allpass(diffusion[2].allpass(input[1]))
        var lateL = 0.0; var lateR = 0.0
        for (i in 0 until 8) {
            val d = lines[i].read()
            lateL += OUT_L[i] * d; lateR += OUT_R[i] * d
            filtered[i] = (1.0 - lowpass) * d + lowpass * filtered[i]
            if (abs(filtered[i]) < 1e-30) filtered[i] = 0.0
            mixed[i] = filtered[i]
        }
        var width = 1
        while (width < 8) {
            var start = 0
            while (start < 8) {
                for (j in 0 until width) {
                    val a = mixed[start + j]; val b = mixed[start + j + width]
                    mixed[start + j] = a + b; mixed[start + j + width] = a - b
                }
                start += 2 * width
            }
            width *= 2
        }
        for (i in 0 until 8) lines[i].push(NORM * (l + IN_R[i] * r + feedback[i] * mixed[i]))
        lr[0] += mix * (.45 * earlyL + .70 * NORM * lateL)
        lr[1] += mix * (.45 * earlyR + .70 * NORM * lateR)
    }

    companion object {
        private const val NORM = .3535533905932737622
        private val IN_R = doubleArrayOf(1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0, -1.0)
        private val OUT_L = doubleArrayOf(1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0)
        private val OUT_R = doubleArrayOf(1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0)
    }
}

internal fun pianoLimitOutput(x: Double): Double {
    val knee = 0.8
    val a = abs(x)
    if (a <= knee) return x
    return Math.copySign(knee + (1.0 - knee) * tanh((a - knee) / (1.0 - knee)), x)
}

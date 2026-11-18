package afield.audio.synth

import afield.audio.analysis.fft
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ADAA shapers, the nonlinear zero-delay-feedback ladder and SVF, and the smoother: antiderivatives
 * against numerical integration, the ill-conditioned fallback against the quotient it replaces, the
 * aliasing an ADAA `tanh` removes, each filter's small-signal response against its bilinear prototype,
 * self-oscillation at the cutoff, and a smoother that neither clicks nor ramps up from zero.
 */
class NonlinearTptTest {
    private val sr = 48_000
    private val freqs = List(60) { (30.0 * 600.0.pow(it / 59.0)).roundToInt() }

    private data class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        fun db() = 20.0 * log10(hypot(re, im))
    }
    private val one = C(1.0, 0.0)
    private fun r(x: Double) = C(x, 0.0)
    private fun s(f: Double, fc: Double): C {
        val w = 2 * PI * f / sr
        val zi = C(cos(w), -sin(w))
        return ((one - zi) / (one + zi)) * r(1.0 / tan(PI * fc / sr))
    }

    /** Measured vs analytic dB wherever the analytic response is above −60 dB (the filters' −120 dB noise floor is below that). */
    private fun check(name: String, p: SignalProcessor, analytic: (Double) -> Double, tolDb: Double = 0.05) {
        val measured = fft(impulseResponse(p, sr, sr), sr.toDouble()).magnitudeDbOneSided()
        val worst = freqs.filter { analytic(it.toDouble()) > -60 }.maxOf { abs(measured[it] - analytic(it.toDouble())) }
        println("$name: worst |measured − analytic| = ${"%.4f".format(worst)} dB")
        assertTrue(worst < tolDb, "$name deviates by $worst dB")
    }

    @Test fun antiderivativesIntegrateTheCurve() {
        for (shape in AdaaShape.entries) {
            val h = 1e-5
            for (x in (-400..400).map { it / 100.0 + 0.003 }) { // off the kinks at ±1
                assertEquals(shape.f(x), (shape.f1(x + h) - shape.f1(x - h)) / (2 * h), 1e-6, "$shape F1' at $x")
                if (shape != AdaaShape.TANH)
                    assertEquals(shape.f1(x), (shape.f2(x + h) - shape.f2(x - h)) / (2 * h), 1e-6, "$shape F2' at $x")
            }
            assertEquals(0.0, shape.f1(0.0), 1e-15)
            assertTrue(shape.f1(800.0).isFinite(), "$shape F1 overflowed")
        }
    }

    @Test fun firstOrderIsContinuousAcrossTheFallback() {
        for (shape in AdaaShape.entries) for (x in listOf(-3.0, -1.0, -0.3, 0.0, 0.7, 1.0, 2.5, 40.0)) {
            // just above the threshold the quotient must already equal the midpoint value the fallback uses
            for (k in listOf(1.1, 2.0, 10.0)) {
                val d = k * AdaaShaper.EPS1
                assertEquals(shape.f(x + d / 2), AdaaShaper.first(shape, x + d, x), 1e-6, "$shape jumps at $x")
            }
            assertEquals(shape.f(x), AdaaShaper.first(shape, x, x), 0.0, "$shape constant input")
        }
    }

    @Test fun secondOrderIsContinuousAndFinite() {
        for (shape in listOf(AdaaShape.HARD_CLIP, AdaaShape.CUBIC)) for (x in listOf(-2.0, -1.0, 0.0, 0.5, 1.0, 3.0)) {
            val e = AdaaShaper.EPS2
            // constant input: the curve itself
            assertEquals(shape.f(x), AdaaShaper.second(shape, x, x, x), 1e-12)
            // straddle each fallback threshold
            for ((a, b) in listOf(0.9 to 1.1)) {
                val p = AdaaShaper.second(shape, x + a * e, x + 0.5 * e, x)
                val q = AdaaShaper.second(shape, x + b * e, x + 0.5 * e, x)
                assertEquals(p, q, 1e-4, "$shape outer fallback at $x")
                val u = AdaaShaper.second(shape, x, x + a * e, x)
                val v = AdaaShaper.second(shape, x, x + b * e, x)
                assertEquals(u, v, 1e-4, "$shape inner fallback at $x")
            }
        }
    }

    /** (loudest, total) power of bins that are not harmonics of [f0], dB relative to the fundamental, one second at 1 Hz per bin. */
    private fun aliasDb(out: FloatArray, f0: Int): Pair<Double, Double> {
        val db = fft(out, sr.toDouble()).magnitudeDbOneSided(floorDb = -200.0)
        val ref = db[f0]
        val bins = (20 until sr / 2).filter { b -> (b % f0).let { it > 2 && it < f0 - 2 } }
        val total = 10 * log10(bins.sumOf { 10.0.pow((db[it] - ref) / 10) })
        return bins.maxOf { db[it] } - ref to total
    }

    /**
     * One second of [s] after a tenth of a second of warm-up. A shaper with memory starts with its
     * history at zero, which a running sine never has; inside a one-second rectangular FFT that
     * first sample is an impulse, and it shows as a flat floor near −91 dB under every bin.
     */
    private fun warmed(s: Signal): FloatArray {
        val out = renderSignalsOffline(1.1, s)[0]
        return out.copyOfRange(out.size - sr, out.size)
    }

    /** Off the harmonics and aliases, a warmed ADAA `tanh` sits at float rounding, not at a start-up floor. */
    @Test fun adaaHasNoNoiseFloorOnceWarm() {
        val db = fft(warmed(SineOsc(Param(4100f)) / AdaaShaper(AdaaShape.TANH, Param(8f))), sr.toDouble())
            .magnitudeDbOneSided(floorDb = -200.0)
        val off = db.indices.filter { it % 100 != 0 }.map { db[it] - db[4100] }.sorted()
        val median = off[off.size / 2]
        assertTrue(median < -130.0, "median off-line bin of a warmed ADAA tanh is $median dB, expected below -130")
    }

    @Test fun adaaAliasesLessThanNaiveShaping() {
        val f0 = 4100
        fun through(p: SignalProcessor) = aliasDb(warmed(SineOsc(Param(f0.toFloat())) / p), f0)
        val tn = through(Waveshaper.tanh(Param(8f)))
        val ta = through(AdaaShaper(AdaaShape.TANH, Param(8f)))
        val c0 = through(Waveshaper.hardClip(Param(8f)))
        val c1 = through(AdaaShaper(AdaaShape.HARD_CLIP, Param(8f)))
        val c2 = through(AdaaShaper(AdaaShape.HARD_CLIP, Param(8f), order = 2))
        println("alias (worst, total) re fundamental: tanh naive $tn, ADAA $ta; hard clip naive $c0, ADAA1 $c1, ADAA2 $c2")
        assertTrue(ta.first < tn.first - 5 && ta.second < tn.second - 5, "tanh: naive $tn, ADAA $ta")
        assertTrue(c1.second < c0.second - 5 && c2.second < c1.second - 5, "hard clip: naive $c0, ADAA1 $c1, ADAA2 $c2")
    }

    @Test fun ladderSmallSignalMatchesBilinearPrototype() {
        val fc = 1500.0
        for (res in listOf(0.0, 0.5, 0.9)) {
            val k = 4 * res
            val lp4 = { f: Double -> val l = one / (one + s(f, fc)); val l4 = l * l * l * l; (l4 / (one + r(k) * l4)).db() }
            val hp4 = { f: Double -> val l = one / (one + s(f, fc)); val l4 = l * l * l * l; val hl = one - l
                (hl * hl * hl * hl / (one + r(k) * l4)).db() }
            // drive 0.01 keeps the tanh linear for a unit impulse
            check("ZdfLadder LP4 res=$res", ZdfLadder(Param(fc.toFloat()), Param(res.toFloat()), Param(0.01f)), lp4)
            check("ZdfLadder HP4 res=$res", ZdfLadder(Param(fc.toFloat()), Param(res.toFloat()), Param(0.01f), ZdfLadderMode.HP4), hp4)
        }
    }

    @Test fun svfSmallSignalMatchesLinearSvf() {
        val fc = 1200.0
        for (res in listOf(0.2, 0.6, 0.95)) {
            val R = 1 - res
            val den = { f: Double -> val ss = s(f, fc); ss * ss + r(2 * R) * ss + one }
            check("ZdfSvf LP res=$res", ZdfSvf(SVFMode.LP, Param(fc.toFloat()), Param(res.toFloat()), Param(0.01f)), { (one / den(it)).db() })
            check("ZdfSvf BP res=$res", ZdfSvf(SVFMode.BP, Param(fc.toFloat()), Param(res.toFloat()), Param(0.01f)), { (s(it, fc) / den(it)).db() })
            check("ZdfSvf HP res=$res", ZdfSvf(SVFMode.HP, Param(fc.toFloat()), Param(res.toFloat()), Param(0.01f)), { val ss = s(it, fc); (ss * ss / den(it)).db() })
        }
    }

    private fun selfOscillation(p: SignalProcessor): Pair<Double, Float> {
        val out = renderSignalsOffline(2.0, Param(0f) / p)[0]
        assertTrue(out.all { it.isFinite() })
        val tail = out.copyOfRange(sr, 2 * sr)
        return fft(tail, sr.toDouble()).peakFrequency() to tail.maxOf { abs(it) }
    }

    @Test fun ladderAndSvfSelfOscillateAtCutoffFromSilence() {
        for (fc in listOf(220f, 1000f, 5000f)) {
            val (fl, al) = selfOscillation(ZdfLadder(Param(fc), Param(1.1f)))
            val (fs, ss) = selfOscillation(ZdfSvf(SVFMode.BP, Param(fc), Param(1.05f)))
            println("cutoff $fc: ladder sings at $fl Hz peak $al; SVF at $fs Hz peak $ss")
            assertTrue(abs(fl / fc - 1) < 0.02 && al in 0.05f..3f, "ladder $fl Hz, $al")
            assertTrue(abs(fs / fc - 1) < 0.02 && ss in 0.05f..3f, "svf $fs Hz, $ss")
        }
    }

    @Test fun ladderNewtonConvergesUnderHeavyDrive() {
        val lad = ZdfLadder(Param(3000f).also { it.mod = SineOsc(Param(300f)) * 2500f }, Param(1.15f), Param(6f))
        val out = renderSignalsOffline(1.0, SawOsc(Param(80f)) * 3f / lad)[0]
        assertTrue(out.all { it.isFinite() } && lad.lastIterations < ZdfLadder.MAX_ITERATIONS)
    }

    @Test fun smootherStartsAtTheInputAndRampsSteps() {
        val stepped = SamplesSignal(FloatArray(sr) { if (it < sr / 2) 200f else 2000f })
        val lin = renderSignalsOffline(1.0, stepped / Smoother(Param(0.1f), SmootherMode.LINEAR))[0]
        assertEquals(200f, lin[0], 1e-3f, "no ramp from zero at start")
        val edge = lin.indexOfFirst { it > 200.5f }
        // reaches the target exactly 0.1 s after the step, holds it after
        val reached = lin.indexOfFirst { it >= 2000f }
        println("linear step starts at $edge, arrives at $reached")
        assertEquals(0.1 * sr, (reached - edge).toDouble(), 2.0)
        assertTrue(lin.drop(reached).all { it == 2000f })
        val ex = renderSignalsOffline(1.0, SamplesSignal(FloatArray(sr) { if (it < sr / 2) 200f else 2000f }) / Smoother(Param(0.05f)))[0]
        assertEquals(200f, ex[0], 1e-3f)
        val jump = (1 until ex.size).maxOf { abs(ex[it] - ex[it - 1]) }
        assertTrue(jump < 1800f * 0.05f, "largest per-sample jump $jump")
    }

    @Test fun docsClipsHaveNoWarmUpSpike() {
        for (name in listOf("tanh-naive", "tanh-adaa", "zdf-ladder", "zdf-svf", "stepped-raw", "stepped-smoothed")) {
            val x = renderSignalsOffline(4.0, TptFilterDocs.scene(name), sampleRate = TptFilterDocs.SR)[0]
            val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
            val head = x.take(TptFilterDocs.SR / 100).maxOf { abs(it) }
            val rest = x.drop(TptFilterDocs.SR / 100).maxOf { abs(it) }
            println("$name: first-10 ms peak $head, rest peak $rest, rms $rms, head/rms ${head / rms}")
            assertTrue(x.all { it.isFinite() } && rms > 1e-3, "$name silent or NaN")
            assertTrue(head <= rest, "$name: warm-up spike (first 10 ms peak $head > later peak $rest)")
        }
    }
}

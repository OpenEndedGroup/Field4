package afield.audio.synth

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Each evieve-family filter's measured response (impulse → DFT, [frequencyResponseDb]) against its
 * analytic one: the analog prototype mapped by the prewarped bilinear transform, which is what a
 * trapezoidal-integrator filter computes exactly. A wrong loop solve or state update shows up as dB.
 */
class TptFiltersTest {
    private val sr = 48_000
    private val freqs = logFrequencies(30.0, 18_000.0, 60)

    private data class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun minus(o: C) = C(re - o.re, im - o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
        fun db() = 20.0 * log10(hypot(re, im))
    }
    private val one = C(1.0, 0.0)
    private fun r(x: Double) = C(x, 0.0)

    /** Normalised Laplace variable s/ωc at [f] for a bilinear filter prewarped to [fc]. */
    private fun s(f: Double, fc: Double): C {
        val w = 2 * PI * f / sr
        val zi = C(cos(w), -sin(w))           // z⁻¹
        return ((one - zi) / (one + zi)) * r(1.0 / tan(PI * fc / sr))
    }

    private fun check(name: String, measured: List<Float>, analytic: (Double) -> Double, tolDb: Double = 0.05) {
        val worst = freqs.indices.maxOf { abs(measured[it] - analytic(freqs[it])) }
        println("$name: worst |measured − analytic| = ${"%.4f".format(worst)} dB")
        assertTrue(worst < tolDb, "$name deviates by $worst dB")
    }

    @Test fun tptOnePoleModesMatchBilinear() {
        val fc = 1200.0
        val lp = { f: Double -> (one / (one + s(f, fc))).db() }
        val hp = { f: Double -> (s(f, fc) / (one + s(f, fc))).db() }
        check("TptOnePole LP", frequencyResponseDb(TptOnePole(Param(fc.toFloat()), TptOnePoleMode.LP), freqs, sr), lp)
        check("TptOnePole HP", frequencyResponseDb(TptOnePole(Param(fc.toFloat()), TptOnePoleMode.HP), freqs, sr), hp)
        check("TptOnePole AP", frequencyResponseDb(TptOnePole(Param(fc.toFloat()), TptOnePoleMode.AP), freqs, sr), { 0.0 })
    }

    @Test fun tiltIsShelfPairAboutPivot() {
        val fc = 800.0; val up = 10.0.pow(6.0 / 20)
        val h = { f: Double -> val ss = s(f, fc); ((r(1 / up) + ss * r(up)) / (one + ss)).db() }
        check("TiltEQ +6", frequencyResponseDb(TiltEQ(Param(fc.toFloat()), Param(6f)), freqs, sr), h)
    }

    @Test fun korg35SmallSignalMatchesSallenKeyPrototype() {
        for (res in listOf(0.2, 0.6, 0.9)) {
            val fc = 1500.0; val K = 0.01 + 1.98 * res
            val lp = { f: Double -> val ss = s(f, fc); (one / (ss * ss + ss * r(2 - K) + one)).db() }
            val hp = { f: Double -> val ss = s(f, fc); (ss * ss / (ss * ss + ss * r(2 - K) + one)).db() }
            // tiny impulse keeps tanh linear
            check("Korg35 LP res=$res", frequencyResponseDb(Korg35(Param(fc.toFloat()), Param(res.toFloat())), freqs, sr, amplitude = 1e-4f), lp)
            check("Korg35 HP res=$res", frequencyResponseDb(Korg35(Param(fc.toFloat()), Param(res.toFloat()), Korg35Mode.HP), freqs, sr, amplitude = 1e-4f), hp)
        }
    }

    @Test fun korg35DriveCompressesResonantPeak() {
        val f = listOf(1500.0)
        val small = frequencyResponseDb(Korg35(Param(1500f), Param(0.95f)), f, sr, amplitude = 1e-4f)[0]
        val loud = frequencyResponseDb(Korg35(Param(1500f), Param(0.95f), drive = Param(4f)), f, sr, amplitude = 1f)[0]
        println("Korg35 peak: small-signal $small dB, driven $loud dB")
        assertTrue(loud < small - 3, "tanh in the loop should limit the peak")
    }

    @Test fun resonatorMatchesItsZTransformAndPeaksAtUnity() {
        val (rr, th, b0) = Resonator.resonatorCoefficients(1000.0, 0.2, sr.toDouble())
        val h = { f: Double ->
            val w = 2 * PI * f / sr; val zi = C(cos(w), -sin(w))
            (r(b0) / (one - r(2 * rr * cos(th)) * zi + r(rr * rr) * zi * zi)).db()
        }
        check("Resonator", frequencyResponseDb(Resonator(Param(1000f), Param(0.2f)), freqs, sr, frames = 48_000), h)
        val peak = frequencyResponseDb(Resonator(Param(1000f), Param(0.2f)), listOf(1000.0), sr, frames = 48_000)[0]
        assertTrue(abs(peak) < 0.05, "peak gain $peak dB")
    }

    @Test fun audioRateCutoffModulationStaysFinite() {
        val cutoff = Param(1500f).also { it.mod = SineOsc(Param(220f)) * 1200f }
        val sat = Korg35(cutoff, Param(0.97f), drive = Param(2f))
        sat.input = SawOsc(Param(110f))
        val out = renderSignalsOffline(1.0, sat)[0]
        assertTrue(out.all { it.isFinite() } && out.maxOf { abs(it) } in 0.05f..4f, "peak ${out.maxOf { abs(it) }}")
    }
}

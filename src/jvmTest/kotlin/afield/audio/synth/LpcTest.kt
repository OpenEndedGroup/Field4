package afield.audio.synth

import afield.geometry.path.FLine
import afield.geometry.path.lpc
import afield.geometry.path.samplePoints
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Linear prediction against processes whose answer is known: an AR(4) process driven by white noise
 * must give back its own coefficients, the residual must give back the signal exactly, and the
 * audio-rate lattice processors must whiten and re-colour what the offline fit says they should.
 */
class LpcTest {
    /** A stable AR(4) with poles at radius 0.95 / 0.9, in the `1 + Σ a_k z⁻ᵏ` convention. */
    private val trueA: DoubleArray = run {
        fun pair(r: Double, w: Double) = doubleArrayOf(1.0, -2 * r * cos(w), r * r)
        val p = pair(0.95, 0.3); val q = pair(0.9, 1.4)
        DoubleArray(5) { k -> (0..k).sumOf { i -> (p.getOrElse(i) { 0.0 }) * (q.getOrElse(k - i) { 0.0 }) } }
    }

    private fun arProcess(n: Int, seed: Int = 1): DoubleArray {
        val rnd = Random(seed)
        val e = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) * sqrt(3.0) } // unit variance
        return Lpc.synthesize(e, trueA)
    }

    @Test
    fun levinsonDurbinRecoversArCoefficients() {
        val x = arProcess(200_000)
        val fit = Lpc.analyze(x, 4, window = false)
        for (k in 1..4) assertEquals(trueA[k], fit.a[k], 0.02, "a[$k]")
        assertEquals(1.0, fit.error, 0.03, "innovation variance")
        assertTrue(fit.k.all { abs(it) < 1 }, "reflection coefficients inside the unit circle")
        // The step-up recursion rebuilds the same polynomial from the reflection coefficients.
        val back = Lpc.reflectionToPredictor(fit.k)
        for (k in 0..4) assertEquals(fit.a[k], back[k], 1e-12)
        // A higher order adds nothing: the extra coefficients are ~0.
        val over = Lpc.analyze(x, 8, window = false)
        for (k in 5..8) assertTrue(abs(over.a[k]) < 0.02, "a[$k]=${over.a[k]}")
    }

    @Test
    fun residualResynthesisIsExact() {
        val x = arProcess(5000, seed = 3)
        val fit = Lpc.analyze(x, 12)
        val back = Lpc.synthesize(Lpc.residual(x, fit.a), fit.a)
        for (i in x.indices) assertEquals(x[i], back[i], 1e-9 * (1 + abs(x[i])))
    }

    @Test
    fun complexLevinsonOnRealInputMatchesReal() {
        val x = arProcess(4000, seed = 5)
        val real = Lpc.analyze(x, 6, window = false)
        val cplx = Lpc.analyzeComplex(x, DoubleArray(x.size), 6)
        for (k in 0..6) {
            assertEquals(real.a[k], cplx.aRe[k], 1e-6)
            assertEquals(0.0, cplx.aIm[k], 1e-12)
        }
    }

    @Test
    fun complexResidualResynthesisIsExactAndCirclesArePredictable() {
        // A circle's steps rotate by a fixed angle: one complex coefficient predicts them all.
        val circle = FLine().also { f -> for (i in 0..400) { val t = i / 400.0 * 2 * PI * 1.5; if (i == 0) f.moveTo(cos(t) * 100, sin(t) * 100) else f.lineTo(cos(t) * 100, sin(t) * 100) } }
        val s = circle.lpc(order = 2, samples = 150)
        assertTrue(s.residualRatio < 1e-3, "circle residual ratio ${s.residualRatio}")
        // Continuing it stays on the circle (radius 100 about the origin).
        val more = s.continued(60, includeStroke = false)
        val r = more.samplePoints(30).map { hypot(it.x.toDouble(), it.y.toDouble()) }
        assertTrue(r.all { abs(it - 100) < 3 }, "continued radii $r")
        // Rebuilding from the residual is exact.
        val back = s.resynthesized().samplePoints(150)
        val orig = s.resampled().samplePoints(150)
        for (i in back.indices) assertEquals(orig[i].x.toDouble(), back[i].x.toDouble(), 1e-2)
    }

    @Test
    fun latticeWhitenFlattensAnArProcess() {
        val x = arProcess(48_000, seed = 9)
        val peak = x.maxOf { abs(it) }
        val src = object : Signal() {
            var i = 0
            override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
                for (n in 0 until numFrames) output[n] = (x.getOrElse(i++) { 0.0 } / peak).toFloat()
            }
        }
        val e = renderSignalsOffline(1.0, src / LpcWhiten(order = 8), sampleRate = 48_000)[0]
        val tail = e.copyOfRange(8000, 47_000).map { it.toDouble() * peak }
        val power = tail.sumOf { it * it } / tail.size
        val inPower = x.copyOfRange(8000, 47_000).sumOf { it * it } / 39_000
        // The residual power is the innovation variance (1), far below the AR process's own power.
        assertTrue(power in 0.8..1.3, "residual power $power (signal power $inPower)")
        assertTrue(inPower > 10 * power)
    }

    @Test
    fun vocoderImposesTheModulatorsEnvelope() {
        // Modulator: noise through the AR filter. Carrier: a flat pulse train. The output spectrum
        // should rise where the modulator's envelope rises, i.e. near the 0.3 rad resonance.
        val rnd = Random(4)
        val modulator = object : Signal() {
            val hist = DoubleArray(5)
            override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
                for (n in 0 until numFrames) {
                    var y = rnd.nextDouble(-0.01, 0.01)
                    for (k in 1..4) y -= trueA[k] * hist[k - 1]
                    for (k in 3 downTo 1) hist[k] = hist[k - 1]
                    hist[0] = y
                    output[n] = y.toFloat()
                }
            }
        }
        val out = renderSignalsOffline(1.0, WhiteNoise(2L) / LpcVocoder(modulator, order = 8, whiten = false), sampleRate = 48_000)[0]
        assertTrue(out.all { it.isFinite() } && lpcPeak(out) > 0f)
        val fit = Lpc.analyze(out.copyOfRange(8000, 48_000), 8)
        val env = fit.envelopeDb(512)
        val atResonance = env[(0.3 / PI * 511).roundToInt()]
        val far = env[(2.6 / PI * 511).roundToInt()]
        assertTrue(atResonance - far > 25, "envelope contrast ${atResonance - far} dB")
    }

    @Test
    fun quantizedReflectionsStayStableAndConverge() {
        val fit = Lpc.analyze(arProcess(20_000), 10)
        var prevErr = Double.MAX_VALUE
        for (bits in listOf(3, 5, 8)) {
            val q = Lpc.quantizeReflection(fit.k, bits)
            assertTrue(q.all { abs(it) < 1 })
            val err = fit.k.indices.sumOf { (fit.k[it] - q[it]).pow(2) }
            assertTrue(err <= prevErr + 1e-12)
            prevErr = err
        }
    }
}

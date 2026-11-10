package afield.math.distribution

import afield.audio.synth.Signal
import afield.audio.synth.PatArc
import afield.audio.synth.PatQ
import afield.audio.synth.SynthContext
import afield.audio.synth.segment
import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import afield.math.gp.GpKernels
import afield.math.gp.gp1D
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Distributions as live inputs: patterns that draw per instant, audio parameters that draw on the
 * audio thread, and Gaussian-process posteriors as correlated distributions. As elsewhere, every
 * sampled claim is "within four standard errors" of an exact value.
 */
class LiveInputsTest {

    private fun valuesOver(p: afield.audio.synth.PatPattern<Double>, cycles: Int): DoubleArray =
        (0 until cycles).flatMap { c -> p.events(PatArc(PatQ.of(c), PatQ.of(c + 1))).map { it.value } }.toDoubleArray()

    private fun meanSe(xs: DoubleArray): Pair<Double, Double> {
        val m = xs.average()
        val v = xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        return m to sqrt(v / xs.size)
    }

    @Test
    fun aPatternDrawsTheSameValueAtTheSameInstant() {
        val p = die(6).pat().segment(8)
        val a = valuesOver(p, 4)
        val b = valuesOver(p, 4)
        assertEquals(a.toList(), b.toList())
        assertEquals(32, a.size)
        // asked in halves, the same instants give the same values
        val halves = (0 until 8).flatMap { h -> p.events(PatArc(PatQ.of(h, 2), PatQ.of(h + 1, 2))).map { it.value } }
        assertEquals(a.toList(), halves)
        // a different salt is a different stream
        assertTrue(valuesOver(die(6).pat(salt = 1).segment(8), 4).toList() != a.toList())
    }

    @Test
    fun aPatternFollowsItsDistribution() {
        val xs = valuesOver(die(6).pat().segment(16), 1000)
        val (m, se) = meanSe(xs)
        assertTrue(abs(m - 3.5) < 4 * se, "mean $m ± $se")
        assertTrue(xs.all { it in 1.0..6.0 && it == kotlin.math.floor(it) })
        val sixes = xs.count { it == 6.0 }.toDouble() / xs.size
        assertEquals(1.0 / 6, sixes, 4 * sqrt((1.0 / 6) * (5.0 / 6) / xs.size))
    }

    @Test
    fun jointPatternsKeepTheirDependence() {
        val pitch = 60 + die(12)
        val loud = (pitch gt 66) * 0.5 + 0.4
        val (pp, lp) = jointPatterns(pitch, loud)
        val ps = valuesOver(pp.segment(8), 200)
        val ls = valuesOver(lp.segment(8), 200)
        for (i in ps.indices) assertEquals(if (ps[i] > 66) 0.9 else 0.4, ls[i], 1e-12, "event $i: pitch ${ps[i]}, loudness ${ls[i]}")
    }

    @Test
    fun aPosteriorPatternDrawsFromThePosterior() {
        val x = die(6); val y = die(6)
        val first = x given (x + y ge 10)
        val xs = valuesOver(first.pat().segment(8), 500)
        assertTrue(xs.all { it >= 4.0 }, "only 4, 5 and 6 can start a total of 10 or more")
        val (m, se) = meanSe(xs)
        // exact posterior mean: (4 + 2*5 + 3*6) / 6
        assertTrue(abs(m - 32.0 / 6) < 4 * se + 0.02, "mean $m ± $se")
    }

    @Test
    fun aVectorPlaysAsOneJointDrawPerCycle() {
        val steps = List(16) { bernoulli(0.4) }
        val rhythm = steps.given(steps.sum() eq 5)
        val p = rhythm.pat()
        val bars = (0 until 40).map { c -> p.events(PatArc(PatQ.of(c), PatQ.of(c + 1))).map { it.value } }
        for ((c, bar) in bars.withIndex()) {
            assertEquals(16, bar.size)
            assertEquals(5.0, bar.sum(), "cycle $c: $bar")
        }
        assertTrue(bars.distinct().size > 30, "a new five each cycle")
        // the same cycle again, asked in pieces, is the same bar
        val pieces = (0 until 4).flatMap { q -> p.events(PatArc(PatQ.of(12 * 4 + q, 4), PatQ.of(12 * 4 + q + 1, 4))).map { it.value } }
        assertEquals(bars[12], pieces)
        // and each step is equally likely to hold a hit: 5/16
        val hitRate = bars.flatten().average()
        assertEquals(5.0 / 16, hitRate, 1e-12)
    }

    @Test
    fun sharedEvidenceCountsOnce() {
        val mu = normal(0, 10)
        val e = observed(normal(mu, 1), 2.9, 3.3, 3.1)
        val (a, b) = listOf(mu, mu * 2).given(e)
        val one = E(mu given e)
        assertEquals(one.value, E(a + b * 0).value, 1e-12, "conditioning two variables on the same data is still three readings")
        assertEquals(2 * one.value, E(b).value, 1e-9)
        assertEquals(1, Regex("lw \\+= ").findAll((a + b).toGlsl()).count())
    }

    /** Renders [signal] for [seconds] at 48 kHz in 256-frame blocks, the way the engine does. */
    private fun render(signal: afield.audio.synth.Signal, seconds: Double): FloatArray {
        val ctx = SynthContext(48_000f, 256)
        val n = (seconds * 48_000).toInt() / 256 * 256
        val out = FloatArray(n)
        val buf = FloatArray(256)
        var at = 0
        while (at < n) {
            signal.render(buf, 256, ctx)
            System.arraycopy(buf, 0, out, at, 256)
            at += 256
            ctx.advanceCycle()
        }
        return out
    }

    @Test
    fun aParamHoldsEachDrawForItsPeriod() {
        val p = die(6).param(everySeconds = 0.1)
        val out = render(p, 2.0)
        assertTrue(out.all { it in 1f..6f })
        // changes happen only on 100 ms boundaries (4800 frames)
        var changes = 0
        for (i in 1 until out.size) if (out[i] != out[i - 1]) { changes++; assertEquals(0, i % 4800, "a change at frame $i") }
        assertTrue(changes in 10..19, "$changes changes in 2 s at 10 per second (a repeat is not a change)")
        assertEquals(20L, p.drawCount, "one draw at the start and one every 100 ms up to 1.9 s")
        assertEquals(p.drawCount.toInt(), p.played().size)
    }

    @Test
    fun aParamDrawsOnTriggersAndPerBlock() {
        // a pulse every 1000 frames
        val pulses = object : Signal() {
            var frame = 0L
            override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
                for (i in 0 until numFrames) { output[i] = if (frame % 1000 == 0L) 1f else 0f; frame++ }
            }
        }
        val p = uniform(0, 1).param(trigger = pulses)
        val out = render(p, 1.0)
        for (i in 1 until out.size) if (out[i] != out[i - 1]) assertEquals(0, i % 1000, "a change at frame $i")
        assertEquals((out.size + 999) / 1000 + 1L, p.drawCount, "one initial draw and one per pulse")
        // per block: the value ramps to a new draw every block, so the mean over a long run is the distribution's
        val q = normal(5, 1).param()
        val qs = render(q, 4.0)
        assertEquals(5.0, qs.average(), 0.05)
        assertEquals(750L + 1, q.drawCount)
        assertFailsWith<IllegalArgumentException> { die(6).param(everySeconds = 1.0, everyBeats = 1.0) }
    }

    @Test
    fun beatsWithoutLinkUseTheFallbackTempo() {
        // 120 bpm, a draw every beat: every half second
        val p = die(20).param(everyBeats = 1.0)
        val out = render(p, 3.0)
        for (i in 1 until out.size) if (out[i] != out[i - 1]) assertEquals(0, i % 24_000, "a change at frame $i")
        assertEquals(6L, p.drawCount)
    }

    @Test
    fun theHeardViewDrawsWhatWasPlayed() {
        val p = die(6).param(everySeconds = 0.01)
        render(p, 2.0)
        val prog = Progressive(p.dist, suppliedDraws = true).addDraws(p.played())
        assertEquals(p.drawCount, prog.drawn)
        assertTrue("exact pmf" in distributionSvg(prog))
        assertFailsWith<IllegalStateException> { prog.draw(10) }
    }

    @Test
    fun aGaussianProcessPosteriorAsDistributions() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(0.0, 0.8, 0.9, 0.1)
        val gp = gp1D(xs, ys, GpKernels.rbf(lengthScale = 1.0), noiseStd = 0.05)
        val grid = DoubleArray(31) { it * 0.1 }
        val joint = gp.posterior(grid)
        val ds = joint.toDists()
        assertEquals(31, ds.size)
        // each point: the GP's own mean and variance, and an exact normal density
        for (i in listOf(0, 7, 15, 30)) {
            val e = E(ds[i])
            assertTrue(e.within(joint.mean[i], 4.0), "E at ${grid[i]}: $e vs ${joint.mean[i]}")
            assertNotNull(exactPdf(ds[i]))
            val v = Var(ds[i]).value
            assertEquals(joint.variance[i], v, 0.03 * joint.variance[i] + 1e-6)
        }
        // the correlation between neighbours survives: draws of the curve are smooth
        val draws = jointSamples(ds[10], ds[11], n = 20_000)
        val cov = draws[0].indices.sumOf { (draws[0][it] - joint.mean[10]) * (draws[1][it] - joint.mean[11]) } / 20_000
        assertEquals(joint.covarianceAt(10, 11), cov, 0.1 * abs(joint.covarianceAt(10, 11)) + 1e-4)
        // and questions with no closed form: where the peak is, how likely the curve tops 1
        val peakAt = argmax(ds)
        val pm = exactPmf(peakAt)
        assertEquals(null, pm, "continuous leaves: argmax is sampled, not enumerated")
        val where = E(peakAt * 0.1)
        assertTrue(where.value in 1.0..2.2, "the peak is between the two high readings: $where")
        // against the GP's own whole-curve sampler
        val over = P(maximum(ds) gt 1.0)
        val curves = joint.samples(20_000, seed = 3)
        val direct = curves.count { c -> c.max() > 1.0 }.toDouble() / curves.size
        val directSe = sqrt(direct * (1 - direct) / curves.size)
        assertTrue(abs(over.value - direct) < 4 * sqrt(over.se * over.se + directSe * directSe), "P(max > 1): $over against the GP's $direct ± $directSe")
        assertEquals(gp.predict(1.5).mean, E(gp.dist(1.5)).value, 0.01)
    }

    @Test
    fun argmaxAndGpCurvesLowerToGlsl() {
        val gp = gp1D(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 0.0), GpKernels.rbf(), noiseStd = 0.05)
        val ds = gp.dists(DoubleArray(20) { it * 0.1 })
        val d = argmax(ds)
        val src = d.monteCarloComputeShader()
        val spirv = ShaderSPIRVUtils.compileShader("noiselang-gp-argmax.comp", src, ShaderKind.COMPUTE_SHADER)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0)
        // the argmax of independent dice is uniform over positions, ties to the first
        assertEquals(1.0, exactPmf(argmax(listOf(die(2), die(2)))).let { it!![0.0]!! + it[1.0]!! }, 1e-12)
        assertEquals(0.75, exactPmf(argmax(listOf(die(2), die(2))))!![0.0]!!, 1e-12)
    }
}

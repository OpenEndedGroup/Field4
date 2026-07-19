package afield.stdlib

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the differentiable fitter end to end: RMSE reduction, determinism, a win over a
 * genetic-only [evolve] baseline of comparable budget, and a rendered PNG montage of the
 * real fitted curve so a human can confirm it actually approaches the target.
 */
class FunctionFitTest {

    private val domain = Functions.Domain(0.0, 1.0)

    @Test
    fun thetaExtractApplyRoundTrips() = with(Functions) {
        val fn = sin(1.5, amplitude = 0.8, offset = 0.2) + cos(2.0, amplitude = 0.3)
        val theta = fn.extractParameters()
        assertEquals(fn.parameterCount(), theta.size)
        // Re-applying the same theta must reproduce the signal exactly.
        val rebuilt = fn.applyParameters(theta)
        for (i in 0..20) {
            val x = i / 20.0
            assertEquals(fn.eval(x), rebuilt.eval(x), 1e-12)
        }
        // Applying a shifted theta must actually move the signal (bijection is live).
        val bumped = fn.applyParameters(DoubleArray(theta.size) { theta[it] + 0.5 })
        assertTrue((0..20).any { i -> abs(fn.eval(i / 20.0) - bumped.eval(i / 20.0)) > 1e-6 })
    }

    @Test
    fun fitGradientReducesRmseFarBelowInitial() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val run = fitGradient(target, seed, domain = domain, iterations = 300, lr = 0.05)

        assertTrue(run.improved, "fit should end below its initial RMSE")
        assertTrue(
            run.finalRmse < run.initialRmse * 0.1,
            "final RMSE ${run.finalRmse} should be << initial ${run.initialRmse}"
        )
        // Loss trajectory is recorded start-inclusive.
        assertEquals(run.iterations + 1, run.losses.size)
        assertEquals(run.initialRmse, run.losses.first(), 0.0)
        assertEquals(run.finalRmse, run.losses.last(), 0.0)
        // Overall descent (allow non-strict wobble from finite differences).
        assertTrue(run.losses.last() < run.losses.first())
    }

    @Test
    fun fitGradientHandlesATwoOscillatorSum() = with(Functions) {
        val target = sin(1.0, amplitude = 0.6, offset = 0.1) + cos(2.0, amplitude = 0.4)
        val seed = sin(1.2, amplitude = 1.0, offset = 0.0) + cos(1.7, amplitude = 1.0)
        val run = fitGradient(target, seed, domain = domain, iterations = 400, lr = 0.04)
        assertTrue(
            run.finalRmse < run.initialRmse * 0.25,
            "two-oscillator fit: final ${run.finalRmse} vs initial ${run.initialRmse}"
        )
    }

    @Test
    fun fitGradientIsDeterministic() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val a = fitGradient(target, seed, domain = domain, iterations = 120, lr = 0.05, rngSeed = 42L)
        val b = fitGradient(target, seed, domain = domain, iterations = 120, lr = 0.05, rngSeed = 42L)
        assertEquals(a.losses, b.losses, "same seed/config must give an identical trajectory")
        assertTrue(a.parameters.contentEquals(b.parameters), "same seed must give identical params")
    }

    // A genuinely hard target: a *product* of two oscillators whose frequencies (2, 1.5) are
    // far from the seed's (3.4, 1.0). Amplitude/offset are easy; the frequencies sit in a
    // multimodal RMSE landscape, so a single local descent stalls in the wrong basin.
    private fun hardTarget() = with(Functions) { cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2) }
    private fun hardSeed() = with(Functions) { cos(3.4) * sin(1.0, amplitude = 2.0, offset = 0.0) }

    @Test
    fun singleShotGradientStallsInAFrequencyLocalMinimum() = with(Functions) {
        // Documents the real limitation (not a bug): one descent, even with a large budget,
        // cannot cross the frequency barriers and settles well above the global optimum (~0.005).
        val run = fitGradient(hardTarget(), hardSeed(), domain = domain, iterations = 3000, lr = 0.05)
        assertTrue(
            run.finalRmse > 0.1,
            "single-shot gradient is expected to stall here; got ${run.finalRmse} (if this " +
                "dropped, the loss landscape or defaults changed — revisit the story)"
        )
    }

    @Test
    fun fitBestEscapesWhereSingleShotStalls() = with(Functions) {
        val target = hardTarget()
        val seed = hardSeed()
        val single = fitGradient(target, seed, domain = domain, iterations = 300, lr = 0.05)
        // This oscillator target is unbounded, so an omitted domain must use the bounded fallback.
        val best = fitBest(target, seed, restarts = 16, iterations = 400, lr = 0.05, rngSeed = 0L)
        assertEquals(Functions.Domain.DEFAULT, best.config.domain)

        assertTrue(
            best.finalRmse < 0.05,
            "fitBest should reach the global basin (< 0.05); got ${best.finalRmse}"
        )
        assertTrue(
            best.finalRmse < single.finalRmse * 0.5,
            "fitBest ${best.finalRmse} should be far better than single-shot ${single.finalRmse}"
        )
        // Never worse than a plain fit: restart 0 is the un-jittered anchor.
        assertTrue(best.finalRmse <= single.finalRmse + 1e-9)
    }

    @Test
    fun fitBestIsDeterministic() = with(Functions) {
        val target = hardTarget()
        val seed = hardSeed()
        val a = fitBest(target, seed, restarts = 8, iterations = 150, rngSeed = 7L)
        val b = fitBest(target, seed, restarts = 8, iterations = 150, rngSeed = 7L)
        assertEquals(a.losses, b.losses, "same seed/config must give an identical trajectory")
        assertTrue(a.parameters.contentEquals(b.parameters), "same seed must give identical params")
    }

    @Test
    fun fitBestDefaultsToFiniteTargetDomainAndAllowsAnOverride() = with(Functions) {
        val targetDomain = Functions.Domain(2.0, 4.0)
        val target = c(1.0, targetDomain)
        val seed = c(0.0, targetDomain)

        val inferred = fitBest(target, seed, restarts = 1, iterations = 0)
        assertEquals(targetDomain, inferred.config.domain)

        val override = Functions.Domain(-1.0, 1.0)
        val explicit = fitBest(target, seed, domain = override, restarts = 1, iterations = 0)
        assertEquals(override, explicit.config.domain)

        val unbounded = fitBest(sin(1.0), sin(0.5), restarts = 1, iterations = 0)
        assertEquals(Functions.Domain.DEFAULT, unbounded.config.domain)
    }

    @Test
    fun domainAcceptsABareRange() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        // Bare range, no Functions.Domain(...) ceremony:
        val viaRange = fitGradient(target, seed, domain = 0.0..1.0, iterations = 120, lr = 0.05)
        // Omitted entirely -> Functions.Domain.DEFAULT, which is 0..1:
        val viaDefault = fitGradient(target, seed, iterations = 120, lr = 0.05)
        assertEquals(viaRange.losses, viaDefault.losses, "bare range 0..1 must match the 0..1 default")
    }

    @Test
    fun fitHybridPolishesAnEvolvedStructure() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val hybrid = fitHybrid(
            target, seed, domain = domain,
            evolveIterations = 40, fitIterations = 150, lr = 0.05, rngSeed = 3L
        )
        // The gradient polish must not worsen what evolve found.
        assertTrue(
            hybrid.finalRmse <= hybrid.evolvedRmse + 1e-9,
            "polish ${hybrid.finalRmse} must not exceed evolved ${hybrid.evolvedRmse}"
        )
    }

    @Test
    fun fnFitRunRendersConciselyNotAsAFieldDump() = with(Functions) {
        val run = fitGradient(sin(1.5, amplitude = 0.8, offset = 0.2), sin(1.0), iterations = 120, lr = 0.05)
        val html = afield.ValueVisualizer.visualize(run)
        assertTrue("FnFitRun" in html && "RMSE" in html, "should show a labelled RMSE summary")
        // The whole point: no dumping the hundreds-long steps list.
        assertTrue("FnFitStep" !in html, "concise render must not enumerate the steps list")
        assertTrue(html.length < 6000, "concise render should be compact; was ${html.length} chars")
    }

    @Test
    fun rendersFitMontagePng() = with(Functions) {
        // Render the *hard* case via the recommended path, so the eyeballed proof shows the
        // optimiser actually escaping a frequency local-minimum, not just an easy single-sine fit.
        val target = hardTarget()
        val seed = hardSeed()
        val run = fitBest(target, seed, restarts = 16, iterations = 400, lr = 0.05)

        val w = 900
        val h = 360
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x12, 0x14, 0x18)
        g.fillRect(0, 0, w, h)

        val n = 600
        fun samplesOf(fn: Functions.Fn) = DoubleArray(n) { i -> fn.eval(domain.min + domain.length * i / (n - 1.0)) }
        val ys = listOf(samplesOf(target), samplesOf(seed), samplesOf(run.fitted))
        val lo = ys.flatMap { it.asList() }.min()
        val hi = ys.flatMap { it.asList() }.max()
        val pad = 24
        fun px(i: Int) = pad + (w - 2 * pad) * i / (n - 1.0)
        fun py(v: Double) = h - pad - (h - 2 * pad) * (v - lo) / (hi - lo)

        fun draw(vals: DoubleArray, color: Color, width: Float) {
            g.color = color
            g.stroke = BasicStroke(width)
            for (i in 0 until n - 1) {
                g.drawLine(px(i).toInt(), py(vals[i]).toInt(), px(i + 1).toInt(), py(vals[i + 1]).toInt())
            }
        }
        draw(ys[0], Color(0x4c, 0xc9, 0xf0), 3.0f)   // target (cyan)
        draw(ys[1], Color(0x6c, 0x6c, 0x78), 1.5f)   // seed (grey, dashed feel)
        draw(ys[2], Color(0xf7, 0x72, 0x2f), 2.0f)   // fitted (orange)

        g.color = Color.WHITE
        g.drawString("target (cyan)  seed (grey)  fitted (orange)", pad, 18)
        g.drawString("initial RMSE=%.4f  final RMSE=%.4f".format(run.initialRmse, run.finalRmse), pad, h - 6)
        g.dispose()

        val out = File(System.getProperty("fnfit.png.dir", "."), "fn_fit_montage.png")
        ImageIO.write(img, "png", out)
        assertTrue(out.exists() && out.length() > 1000, "montage PNG should be written: ${out.absolutePath}")
        println("Wrote fit montage: ${out.absolutePath} (${out.length()} bytes)")
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
}

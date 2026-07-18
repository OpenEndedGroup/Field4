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

    @Test
    fun fitGradientBeatsGeneticBaselineOfComparableBudget() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)

        val fitRun = fitGradient(target, seed, domain = domain, iterations = 200, lr = 0.05)

        // Comparable evaluation budget for the genetic baseline: fitGradient spends
        // ~ iterations * (2*params + 1) evals; match that with evolve's lambda draws.
        val budget = fitRun.evaluations
        val fitness = matchFitness(target, domain, samples = 128)
        val lambda = 6
        val evolveIters = budget / lambda
        val evolveRun = evolve(seed, iterations = evolveIters, lambda = lambda, rngSeed = 0L, fitness = fitness)
        val evolveRmse = -evolveRun.bestScore

        assertTrue(
            fitRun.finalRmse <= evolveRmse,
            "gradient RMSE ${fitRun.finalRmse} should beat/match genetic $evolveRmse " +
                "(budget=$budget, evolveIters=$evolveIters)"
        )
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
    fun rendersFitMontagePng() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val run = fitGradient(target, seed, domain = domain, iterations = 300, lr = 0.05)

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

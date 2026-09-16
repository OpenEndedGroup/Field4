package afield.stacksl.cpu

import afield.docsAssetDir
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fit, end to end, and then a **look** at the pixels.
 *
 * The recovery problem is deliberately one with a known answer: the target is the *same* StackSL
 * program rendered with different literals. So "did the fit work" is not a question about a loss
 * number, it is a question about whether the fitted image is the target image — which is what the
 * figure shows and what the residual panel measures. A results table can read as a success while
 * the images do not resemble their targets at all, because a sum-of-squares objective rewards
 * colour and luminance statistics; that trap is only caught by looking, and by asserting what the
 * looking would find.
 *
 * Three things are asserted, all with the population stated:
 *
 *  1. the loss falls by at least [MIN_LOSS_RATIO] over [ITERATIONS] Adam steps;
 *  2. the fitted image is within [MAX_MEAN_ABS] mean absolute error of the target, per RGB sample,
 *     and within [MAX_P99_ABS] at the 99th percentile — a mean alone would let a handful of
 *     catastrophic pixels hide;
 *  3. the **round trip closes**: the fitted literals written back into StackSL source, recompiled
 *     from scratch through the ordinary non-wired path and re-rendered, reproduce the fitted image
 *     to [MAX_ROUNDTRIP_ABS]. That is the check that proves the literal rewriting is correct.
 *     Measured: the round trip is **exactly zero** — every channel of every pixel identical. The
 *     floor is not zero anyway, because `%.8f` printing is the one lossy step in the trip and
 *     binary32 needs **nine** significant decimals to round-trip in general; a fitted value whose
 *     literal lands one ulp away is a legitimate outcome, and 1e-4 is what one ulp of a palette
 *     coefficient can cost downstream of an `fbm`.
 *
 * The figure goes to a temp directory by default, per the repo's no-committed-writes rule for
 * tests; `AFIELD_DOCS_ASSETS=1` writes it to `public/docs/assets/stacksl_fit/` for the page to
 * embed. Its caption carries the population and the floors, so the picture cannot be separated
 * from the conditions it was made under.
 */
class StackSLFitFigureTest {

    /** The program whose literals are fitted. 17 wires; the four alphas carry no gradient. */
    private val seedProgram = """
        uv 3.0 * fbm
        0.5 0.5 0.5 1 vec4
        0.5 0.5 0.5 1 vec4
        1.0 1.0 1.0 1 vec4
        0.0 0.33 0.67 1 vec4
        palette
    """.trimIndent()

    /**
     * The target: the same program with different palette literals and the same noise frequency.
     *
     * The frequency is held because a fit that had to find both the lattice and the colours would
     * be answering two questions at once, and the frequency's own gradient is already measured in
     * `StackSLGradientTest`. What this asks is whether twelve colour parameters can be recovered.
     */
    private val targetProgram = """
        uv 3.0 * fbm
        0.62 0.41 0.35 1 vec4
        0.31 0.45 0.52 1 vec4
        1.0 1.0 1.0 1 vec4
        0.12 0.55 0.88 1 vec4
        palette
    """.trimIndent()

    @Test
    fun `a fit recovers a StackSL program's literals, and the round trip closes`() {
        val template = StackSLCpu.stillTemplate()
        val target = StackSLCpu.renderProgram(targetProgram, template, DIM, DIM)

        val started = System.nanoTime()
        val result = StackSLFit.fit(
            seedProgram, target, template,
            dim = DIM, iterations = ITERATIONS, learningRate = LEARNING_RATE
        )
        val fitSeconds = (System.nanoTime() - started) / 1e9

        val seedImage = result.loss.render(result.seedWires)
        val fittedImage = result.loss.render(result.fittedWires)

        // The round trip: the re-emitted source, recompiled from scratch, not reusing the wires.
        val roundTrip = StackSLCpu.renderProgram(result.fittedProgram, template, DIM, DIM)

        val errors = channelErrors(fittedImage, target)
        val meanAbs = errors.average()
        val p99 = errors.sorted()[(errors.size * 99) / 100]
        val roundTripErrors = channelErrors(roundTrip, fittedImage)
        val roundTripMax = roundTripErrors.max()

        println("--- StackSL differentiable fit ---")
        println(result.wired.describe())
        println(
            ("seed loss %.6e -> fitted %.6e (ratio %.1fx) in $ITERATIONS Adam steps at lr " +
                "$LEARNING_RATE, ${DIM}x$DIM, %.2f s wall clock")
                .format(result.seedLoss, result.fittedLoss, result.lossRatio, fitSeconds)
        )
        println("saturated RGB samples at the seed: %.1f%%".format(result.saturatedFraction * 100))
        println("fitted vs target: mean abs %.5f, p99 %.5f, max %.5f".format(meanAbs, p99, errors.max()))
        println("round trip (re-emitted source, recompiled): max abs %.3e".format(roundTripMax))
        result.report().forEach { println("  $it") }
        println("--- fitted program ---")
        println(result.fittedProgram)

        val figure = writeFigure(target, seedImage, fittedImage, result, meanAbs, p99, roundTripMax)
        println("figure: ${figure.absolutePath}")

        // 1. the descent worked
        assertTrue(
            result.fittedIndices.size >= MIN_MOVED_WIRES,
            "only ${result.fittedIndices.size} wires were fitted, expected at least $MIN_MOVED_WIRES " +
                "of ${result.wired.wires.size}"
        )
        assertTrue(
            result.lossRatio >= MIN_LOSS_RATIO,
            "loss only fell ${"%.2f".format(result.lossRatio)}x (${result.seedLoss} -> " +
                "${result.fittedLoss}) over $ITERATIONS steps; floor is ${MIN_LOSS_RATIO}x"
        )
        assertTrue(
            result.run.losses.last() <= result.run.losses.first(),
            "the trajectory ends above where it started, which is not a descent"
        )

        // 2. the pixels actually match, and not only on average
        assertTrue(
            meanAbs <= MAX_MEAN_ABS,
            "fitted image is ${"%.5f".format(meanAbs)} mean absolute error from the target per RGB " +
                "sample over ${errors.size} samples; floor is $MAX_MEAN_ABS"
        )
        assertTrue(
            p99 <= MAX_P99_ABS,
            "fitted image's 99th-percentile error is ${"%.5f".format(p99)}; floor is $MAX_P99_ABS — " +
                "a mean alone would let a handful of catastrophic pixels hide"
        )
        // Two-sided: if the recovery became exact, this is a different (better) result and the
        // numbers above should be tightened rather than left as a ceiling nothing approaches.
        assertTrue(
            meanAbs >= MAX_MEAN_ABS / 50.0,
            "mean absolute error ${"%.6f".format(meanAbs)} is more than 50x under $MAX_MEAN_ABS — " +
                "tighten it rather than leaving the guard unable to notice"
        )

        // 3. the round trip closes
        assertTrue(
            roundTripMax <= MAX_ROUNDTRIP_ABS,
            "the re-emitted program renders up to ${"%.3e".format(roundTripMax)} away from the fitted " +
                "wire vector; floor is $MAX_ROUNDTRIP_ABS. The literal rewriting is wrong."
        )
        assertTrue(
            result.fittedProgram.lines().size == seedProgram.lines().size,
            "the re-emitted program has a different number of lines from the original, so the " +
                "substitution moved more than the literals"
        )
    }

    private fun channelErrors(a: CpuRaster, b: CpuRaster): DoubleArray {
        val out = DoubleArray(a.width * a.height * StackSLImageLoss.CHANNELS)
        var k = 0
        for (i in 0 until a.width * a.height) for (ch in 0 until StackSLImageLoss.CHANNELS) {
            out[k++] = abs(a.rgba[i * 4 + ch] - b.rgba[i * 4 + ch]).toDouble()
        }
        return out
    }

    // -- the figure ---------------------------------------------------------------------------------

    private fun writeFigure(
        target: CpuRaster,
        seed: CpuRaster,
        fitted: CpuRaster,
        result: StackSLFitResult,
        meanAbs: Double,
        p99: Double,
        roundTripMax: Double
    ): File {
        val dir = docsAssetDir("public/docs/assets/stacksl_fit").toFile()
        val panel = 200
        val pad = 16
        val labelH = 20
        val curveH = 150
        val captionH = 96
        val width = pad + 4 * (panel + pad)
        val height = pad + labelH + panel + pad + curveH + pad + captionH
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x14, 0x16, 0x1a)
        g.fillRect(0, 0, width, height)

        val residual = CpuRaster(
            fitted.width, fitted.height,
            FloatArray(fitted.rgba.size) { i ->
                if (i % 4 == 3) 1f
                else (abs(fitted.rgba[i] - target.rgba[i]) * RESIDUAL_GAIN).coerceIn(0f, 1f)
            }
        )

        listOf(
            "target" to target,
            "seed" to seed,
            "fitted" to fitted,
            "residual x$RESIDUAL_GAIN" to residual
        ).forEachIndexed { i, (name, raster) ->
            val x = pad + i * (panel + pad)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            g.color = Color(0xcf, 0xd4, 0xdc)
            g.drawString(name, x, pad + 13)
            g.drawImage(toImage(raster), x, pad + labelH, panel, panel, null)
        }

        // The loss trajectory, on a log axis because the interesting part is the first decade.
        val cx = pad
        val cy = pad + labelH + panel + pad
        val cw = width - 2 * pad
        g.color = Color(0x1e, 0x21, 0x27)
        g.fillRect(cx, cy, cw, curveH)
        val losses = result.run.losses
        val logs = losses.map { ln(max(it, 1e-12)) }
        val lo = logs.min()
        val hi = logs.max()
        g.color = Color(0x62, 0xc3, 0x70)
        g.stroke = BasicStroke(2f)
        for (i in 1 until logs.size) {
            val x0 = cx + (i - 1) * (cw - 1) / max(1, logs.size - 1)
            val x1 = cx + i * (cw - 1) / max(1, logs.size - 1)
            val y0 = cy + curveH - 1 - ((logs[i - 1] - lo) / max(1e-9, hi - lo) * (curveH - 12)).toInt()
            val y1 = cy + curveH - 1 - ((logs[i] - lo) / max(1e-9, hi - lo) * (curveH - 12)).toInt()
            g.drawLine(x0, y0, x1, y1)
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.color = Color(0x9a, 0xa2, 0xae)
        g.drawString(
            "log mean-squared-error, %.3e -> %.3e over ${losses.size - 1} Adam steps"
                .format(losses.first(), losses.last()),
            cx + 8, cy + 16
        )

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        var ty = cy + curveH + pad + 12
        listOf(
            "Differentiable StackSL: gradient descent on a program's own numeric literals. " +
                "Reverse-mode AD through the emitted GLSL, one tape per pixel.",
            "Population: 1 program, ${result.wired.wires.size} wires, " +
                "${result.fittedIndices.size} literal-backed and fitted; target is the same program " +
                "with 12 different palette literals. ${DIM}x$DIM, $ITERATIONS Adam steps, lr $LEARNING_RATE.",
            "Asserted: loss ratio >= ${MIN_LOSS_RATIO}x (got ${"%.0f".format(result.lossRatio)}x); " +
                "mean abs err <= $MAX_MEAN_ABS (got ${"%.5f".format(meanAbs)}); " +
                "p99 <= $MAX_P99_ABS (got ${"%.5f".format(p99)}); " +
                "round-trip max <= $MAX_ROUNDTRIP_ABS (got ${"%.1e".format(roundTripMax)}).",
            "The residual panel is amplified ${RESIDUAL_GAIN}x. A sum-of-squares loss rewards colour " +
                "and luminance statistics, so the residual is the panel that says whether the fit " +
                "found the picture or only its histogram."
        ).forEach { paragraph ->
            g.color = Color(0x8b, 0x93, 0xa0)
            // Wrap by measured width, not by a guessed character count: the first version of this
            // figure ran its caption off the right edge, which is the kind of thing only looking
            // at the PNG catches.
            wrap(paragraph, width - 2 * pad, g.fontMetrics).forEach { line ->
                g.drawString(line, pad, ty)
                ty += 15
            }
        }
        g.dispose()

        val file = File(dir, "stacksl_fit_recovery.png")
        ImageIO.write(image, "png", file)
        return file
    }

    /** Greedy word wrap at a measured pixel width. */
    private fun wrap(text: String, maxWidth: Int, metrics: java.awt.FontMetrics): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) out += line.toString()
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    private fun toImage(raster: CpuRaster): BufferedImage {
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.toArgb(), 0, raster.width)
        return image
    }

    private companion object {
        const val DIM = 64
        const val ITERATIONS = 80
        const val LEARNING_RATE = 0.05
        const val RESIDUAL_GAIN = 8f

        const val MIN_MOVED_WIRES = 13
        const val MIN_LOSS_RATIO = 500.0
        const val MAX_MEAN_ABS = 0.02
        const val MAX_P99_ABS = 0.08
        const val MAX_ROUNDTRIP_ABS = 1e-4
    }
}

package afield.examples.functions

import afield.niceTicks
import afield.stdlib.Functions
import afield.stdlib.fitGradient
import afield.tickLabels
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
FIELD_TEST
id: functions.plot-gallery
runner: jvm-main
entrypoint: afield.examples.functions.FunctionsPlotGallery
tags: [smoke, examples, functions, plot, capture, cpu]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "functions plot gallery rendered"
*/

/**
 * Headless **plot-capture harness** for the `afield.stdlib.Functions` library — the honest
 * vehicle RectSL/StackSL already had and Functions lacked. It renders `Functions.Fn` results
 * (differentiable-fit before→after, waveshaper transfer curves, waveshaping on a sine, and a
 * composed pipeline) to REAL raster PNGs.
 *
 * Rendering path: pure-CPU AWT `Graphics2D` → `BufferedImage` → `ImageIO` PNG, run under
 * `java.awt.headless=true`. This is the same headless path `RectSLDataVizProof` proves out via
 * `run_field_tests.py`; there is no browser, GPU, Vulkan or Field window. The pixels are honest:
 * every plotted `(x, y)` comes from a real `Fn.evalSafe(...)` evaluation of a genuine Functions
 * graph, and the fit series is the output of a real [fitGradient] run — only the "draw a line
 * between real samples" step is synthetic (exactly as RectSL CPU-rasterizes its real sampled
 * scene; only the GPU shader is skipped).
 *
 * As a FIELD_TEST it renders to a temp dir and asserts real correctness (painted fraction,
 * finite samples, that the fit genuinely converges and the fitted curve tracks its target). It
 * writes NO committed assets during the test. Pass an output dir as `args[0]` (or set
 * `FUNCTIONS_PLOT_DIR`) to regenerate committed docs captures.
 *
 * The harness is **parameterized/reusable**: add a plot by appending one [Plot] to [PLOTS];
 * each plot builds a [PlotSpec] of named [Series] and everything else (axes, gridlines,
 * zero-line, legend, assertions) is shared.
 */
object FunctionsPlotGallery {

    /** How a series is drawn. */
    enum class Style { LINE, THICK, DASHED, AREA, POINTS }

    /** One plotted curve: its [label], sampled `(xs, ys)`, [color] and draw [style]. */
    class Series(
        val label: String,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val color: Color,
        val style: Style = Style.LINE
    )

    /** A single figure: [title], axis labels, [series] and optional fixed [yRange]. */
    class PlotSpec(
        val title: String,
        val xLabel: String,
        val yLabel: String,
        val series: List<Series>,
        val yRange: Pair<Double, Double>? = null
    )

    /** A named plot the gallery renders. [build] runs inside the `Functions` receiver. */
    class Plot(val name: String, val build: Functions.() -> PlotSpec)

    // Dark-mode palette shared with the rest of the docs figures (cyan/orange/violet/…).
    private val BG = Color(20, 22, 28)
    private val PANEL = Color(28, 30, 38)
    private val AXIS = Color(120, 126, 140)
    private val GRID = Color(48, 52, 62)
    private val TEXT = Color(206, 210, 220)
    private val CYAN = Color(72, 202, 228)
    private val ORANGE = Color(240, 156, 74)
    private val VIOLET = Color(176, 142, 240)
    private val GREEN = Color(120, 214, 140)
    private val PINK = Color(232, 120, 168)
    private val GREY = Color(150, 156, 168)

    // ── curated plots ─────────────────────────────────────────────────────────────

    val PLOTS: List<Plot> = listOf(
        Plot("fit-before-after") {
            // Differentiable fit: same graph *structure* as the target, wrong numeric leaves.
            // fitGradient tunes the leaves; the fitted curve should visibly land on the target.
            val target = sin(frequency = 2.0, amplitude = 0.8, offset = 0.12)
            val seed = sin(frequency = 2.0, amplitude = 0.30, offset = -0.15)
            val run = fitGradient(
                target = target,
                seed = seed,
                iterations = 400,
                lr = 0.05,
                samples = 160,
                rngSeed = 0L
            )
            check(run.improved) { "fit did not improve: ${run.initialError} -> ${run.finalError}" }
            val dom = Functions.Domain.of(0.0, 1.0)
            PlotSpec(
                title = "Differentiable fit — before -> after (fitGradient)",
                xLabel = "x", yLabel = "f(x)",
                series = listOf(
                    sample("target", target, dom, ORANGE, Style.THICK),
                    sample("seed (before)", seed, dom, GREY, Style.DASHED),
                    sample("fitted (after)", run.fitted, dom, CYAN, Style.LINE)
                ),
                yRange = -1.1 to 1.1
            )
        },
        Plot("waveshaper-transfer") {
            // Transfer curves: push a rising ramp through each shaper so the plotted result
            // *is* the shape it imposes on its input.
            val dom = Functions.Domain.of(0.0, 1.0)
            val sweep = ramp(-1.5, 1.5) over dom
            PlotSpec(
                title = "Waveshaper transfer curves (input sweep -1.5..1.5)",
                xLabel = "x", yLabel = "shaped(x)",
                series = listOf(
                    sample("input ramp", sweep, dom, GREY, Style.DASHED),
                    sample("fold(0.7)", sweep.fold(0.7), dom, CYAN),
                    sample("softClip(2.5)", sweep.softClip(2.5), dom, ORANGE),
                    sample("chebyshev(3)", sweep.chebyshev(3), dom, VIOLET),
                    sample("rectify(FULL)", sweep.rectify(Functions.RectifyMode.FULL), dom, GREEN)
                ),
                yRange = -1.7 to 1.7
            )
        },
        Plot("waveshaper-on-sine") {
            // The same shapers applied to an actual sine — the audible-shape effect.
            val dom = Functions.Domain.of(0.0, 1.0)
            val tone = sin(frequency = 2.0, amplitude = 1.2)
            PlotSpec(
                title = "Waveshaping a sine (amplitude 1.2, 2 cycles)",
                xLabel = "x", yLabel = "f(x)",
                series = listOf(
                    sample("sine", tone, dom, GREY, Style.DASHED),
                    sample("softClip(2.0)", tone.softClip(2.0), dom, CYAN),
                    sample("fold(0.8)", tone.fold(0.8), dom, ORANGE),
                    sample("clip(0.7)", tone.clip(0.7), dom, PINK)
                ),
                yRange = -1.4 to 1.4
            )
        },
        Plot("composed-pipeline") {
            // A composed pipeline, plotted stage by stage so the composition reads visually.
            val dom = Functions.Domain.of(0.0, 1.0)
            val carrier = sin(frequency = 3.0, amplitude = 1.0)
            val envelope = ramp(0.2, 1.0) over dom
            val stage0 = (carrier * envelope).withName("carrier * envelope")
            val stage1 = stage0.fold(0.75).withName("+ fold(0.75)")
            val stage2 = stage1.softClip(2.0).withName("+ softClip(2.0)")
            PlotSpec(
                title = "Composed Fn pipeline (per-stage)",
                xLabel = "x", yLabel = "f(x)",
                series = listOf(
                    sample("carrier x envelope", stage0, dom, GREY, Style.DASHED),
                    sample("+ fold(0.75)", stage1, dom, ORANGE),
                    sample("+ softClip(2.0)", stage2, dom, CYAN)
                ),
                yRange = -1.3 to 1.3
            )
        }
    )

    /** Sample [fn] on an even grid across [dom] into a [Series]. Honest pixels start here. */
    private fun sample(
        label: String,
        fn: Functions.Fn,
        dom: Functions.Domain,
        color: Color,
        style: Style = Style.LINE,
        n: Int = 320
    ): Series {
        val xs = DoubleArray(n) { dom.min + dom.length * it / (n - 1).toDouble() }
        val ys = DoubleArray(n) { i -> fn.evalSafe(xs[i], outside = Double.NaN) }
        return Series(label, xs, ys, color, style)
    }

    // ── renderer ──────────────────────────────────────────────────────────────────

    private const val W = 900
    private const val H = 540
    private const val ML = 78   // left margin (y labels)
    private const val MR = 24
    private const val MT = 48   // top margin (title)
    private const val MB = 54   // bottom margin (x labels)

    fun render(spec: PlotSpec): BufferedImage {
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = BG
        g.fillRect(0, 0, W, H)

        val plotX = ML
        val plotY = MT
        val plotW = W - ML - MR
        val plotH = H - MT - MB
        g.color = PANEL
        g.fillRect(plotX, plotY, plotW, plotH)

        // Domain / range.
        var xmin = Double.POSITIVE_INFINITY; var xmax = Double.NEGATIVE_INFINITY
        for (s in spec.series) for (x in s.xs) { if (x < xmin) xmin = x; if (x > xmax) xmax = x }
        if (!xmin.isFinite() || !xmax.isFinite() || xmin == xmax) { xmin = 0.0; xmax = 1.0 }
        val (ymin, ymax) = spec.yRange ?: autoYRange(spec.series)

        fun xToPx(x: Double) = plotX + (x - xmin) / (xmax - xmin) * plotW
        fun yToPx(y: Double) = plotY + plotH - (y - ymin) / (ymax - ymin) * plotH

        // Gridlines + tick labels.
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.stroke = BasicStroke(1f)
        // Tick selection and labelling come from `afield.charts` rather than from a private copy
        // here. There were five of those copies in the repo; this one also did not snap its values,
        // so `k * step` drift was only invisible because the old `fmt` rounded to two decimals — a
        // finer axis would have printed 0.30000000000000004. `tickLabels` also gives the whole axis
        // one notation, which the old formatter did not.
        val yTicks = niceTicks(ymin, ymax, 6)
        val yText = tickLabels(yTicks, if (yTicks.size >= 2) yTicks[1] - yTicks[0] else 0.0)
        yTicks.forEachIndexed { i, yt ->
            val py = yToPx(yt).toFloat()
            g.color = GRID
            g.draw(Line2D.Double(plotX.toDouble(), py.toDouble(), (plotX + plotW).toDouble(), py.toDouble()))
            g.color = TEXT
            val s = yText[i]
            g.drawString(s, (plotX - 8 - g.fontMetrics.stringWidth(s)).toFloat(), py + 4)
        }
        val xTicks = niceTicks(xmin, xmax, 6)
        val xText = tickLabels(xTicks, if (xTicks.size >= 2) xTicks[1] - xTicks[0] else 0.0)
        xTicks.forEachIndexed { i, xt ->
            val px = xToPx(xt).toFloat()
            g.color = GRID
            g.draw(Line2D.Double(px.toDouble(), plotY.toDouble(), px.toDouble(), (plotY + plotH).toDouble()))
            g.color = TEXT
            val s = xText[i]
            g.drawString(s, px - g.fontMetrics.stringWidth(s) / 2f, (plotY + plotH + 18).toFloat())
        }

        // Zero line (emphasised) if in range.
        if (0.0 in ymin..ymax) {
            g.color = AXIS
            g.stroke = BasicStroke(1.4f)
            val py = yToPx(0.0)
            g.draw(Line2D.Double(plotX.toDouble(), py, (plotX + plotW).toDouble(), py))
        }

        // Axis frame.
        g.color = AXIS
        g.stroke = BasicStroke(1.2f)
        g.draw(Rectangle2D.Double(plotX.toDouble(), plotY.toDouble(), plotW.toDouble(), plotH.toDouble()))

        // Series.
        val solid = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val thick = BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val dashed = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(7f, 6f), 0f)
        for (s in spec.series) {
            g.color = s.color
            when (s.style) {
                Style.POINTS -> for (i in s.xs.indices) {
                    val y = s.ys[i]; if (!y.isFinite()) continue
                    val px = xToPx(s.xs[i]); val py = yToPx(y)
                    g.fill(Ellipse2D.Double(px - 2.5, py - 2.5, 5.0, 5.0))
                }
                Style.AREA -> {
                    g.stroke = solid
                    val path = Path2D.Double()
                    val base = yToPx(0.0.coerceIn(ymin, ymax))
                    var started = false
                    for (i in s.xs.indices) {
                        val y = s.ys[i]; if (!y.isFinite()) continue
                        val px = xToPx(s.xs[i]); val py = yToPx(y)
                        if (!started) { path.moveTo(px, base); path.lineTo(px, py); started = true }
                        else path.lineTo(px, py)
                    }
                    path.lineTo(xToPx(s.xs.last()), base)
                    g.color = Color(s.color.red, s.color.green, s.color.blue, 70)
                    g.fill(path)
                    g.color = s.color
                    drawPolyline(g, s, ::xToPx, ::yToPx)
                }
                else -> {
                    g.stroke = when (s.style) {
                        Style.THICK -> thick
                        Style.DASHED -> dashed
                        else -> solid
                    }
                    drawPolyline(g, s, ::xToPx, ::yToPx)
                }
            }
        }

        // Legend (top-right, inside plot).
        drawLegend(g, spec.series, plotX + plotW - 8, plotY + 8)

        // Title + axis labels.
        g.color = TEXT
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString(spec.title, ML.toFloat(), 28f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString(spec.xLabel, (plotX + plotW / 2).toFloat(), (H - 12).toFloat())
        // y label, rotated.
        val old = g.transform
        g.rotate(-Math.PI / 2, 18.0, (plotY + plotH / 2).toDouble())
        g.drawString(spec.yLabel, 18f, (plotY + plotH / 2 + 4).toFloat())
        g.transform = old

        g.dispose()
        return img
    }

    private fun drawPolyline(
        g: java.awt.Graphics2D,
        s: Series,
        xToPx: (Double) -> Double,
        yToPx: (Double) -> Double
    ) {
        val path = Path2D.Double()
        var pen = false
        for (i in s.xs.indices) {
            val y = s.ys[i]
            if (!y.isFinite()) { pen = false; continue }
            val px = xToPx(s.xs[i]); val py = yToPx(y)
            if (!pen) { path.moveTo(px, py); pen = true } else path.lineTo(px, py)
        }
        g.draw(path)
    }

    private fun drawLegend(g: java.awt.Graphics2D, series: List<Series>, right: Int, top: Int) {
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val fm = g.fontMetrics
        val rowH = 18
        val swatch = 20
        val pad = 8
        val textW = series.maxOf { fm.stringWidth(it.label) }
        val boxW = swatch + 8 + textW + pad * 2
        val boxH = series.size * rowH + pad * 2
        val x = right - boxW
        g.color = Color(16, 18, 24, 210)
        g.fill(Rectangle2D.Double(x.toDouble(), top.toDouble(), boxW.toDouble(), boxH.toDouble()))
        g.color = GRID
        g.draw(Rectangle2D.Double(x.toDouble(), top.toDouble(), boxW.toDouble(), boxH.toDouble()))
        series.forEachIndexed { i, s ->
            val cy = top + pad + i * rowH + rowH / 2
            g.color = s.color
            g.stroke = BasicStroke(3f)
            g.draw(Line2D.Double((x + pad).toDouble(), cy.toDouble(), (x + pad + swatch).toDouble(), cy.toDouble()))
            g.color = TEXT
            g.drawString(s.label, (x + pad + swatch + 8).toFloat(), (cy + 4).toFloat())
        }
    }

    private fun autoYRange(series: List<Series>): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (s in series) for (y in s.ys) if (y.isFinite()) { if (y < lo) lo = y; if (y > hi) hi = y }
        if (!lo.isFinite() || !hi.isFinite()) { lo = -1.0; hi = 1.0 }
        if (lo == hi) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.08
        return (lo - pad) to (hi + pad)
    }

    /** Fraction of pixels differing from the background — a cheap "did anything render" guard. */
    private fun paintedFraction(img: BufferedImage): Double {
        val bg = BG.rgb and 0xFFFFFF
        val panel = PANEL.rgb and 0xFFFFFF
        var painted = 0; var seen = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val p = img.getRGB(x, y) and 0xFFFFFF
                if (p != bg && p != panel) painted++
                seen++
                x += 2
            }
            y += 2
        }
        return painted.toDouble() / seen.coerceAtLeast(1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("FUNCTIONS_PLOT_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "functions-plot-gallery").path
        )
        outDir.mkdirs()

        PLOTS.forEach { plot ->
            val spec = plot.build(Functions)
            // Every series must carry finite samples — no all-NaN degeneration.
            for (s in spec.series) {
                check(s.ys.any { it.isFinite() }) { "${plot.name}: series '${s.label}' has no finite samples" }
            }
            val img = render(spec)
            val file = File(outDir, "functions-${plot.name}.png")
            ImageIO.write(img, "png", file)
            val painted = paintedFraction(img)
            check(painted > 0.02) { "${plot.name}: rasterized near-empty (painted=$painted)" }
            println("wrote ${file.absolutePath} (${img.width}x${img.height}, painted=${"%.3f".format(painted)})")
        }

        // Deeper correctness on the fit plot: the fitted curve must genuinely track the target.
        with(Functions) {
            val target = sin(frequency = 2.0, amplitude = 0.8, offset = 0.12)
            val seed = sin(frequency = 2.0, amplitude = 0.30, offset = -0.15)
            val run = fitGradient(target = target, seed = seed, iterations = 400, lr = 0.05, samples = 160, rngSeed = 0L)
            check(run.finalError < run.initialError) {
                "fit did not converge: ${run.initialError} -> ${run.finalError}"
            }
            check(run.finalError < run.initialError * 0.25) {
                "fit barely moved: ${run.initialError} -> ${run.finalError}"
            }
            val dom = Functions.Domain.of(0.0, 1.0)
            var maxDev = 0.0
            for (i in 0..200) {
                val x = dom.min + dom.length * i / 200.0
                maxDev = maxOf(maxDev, abs(run.fitted.evalSafe(x) - target.evalSafe(x)))
            }
            check(maxDev < 0.15) { "fitted curve does not track target: maxDev=$maxDev" }
            println("fit convergence: initial=${"%.4f".format(run.initialError)} " +
                "final=${"%.4f".format(run.finalError)} maxDev=${"%.4f".format(maxDev)}")
        }

        // Optional: an animated fit-convergence mp4 (best-effort — a missing ffmpeg is not a
        // failure, it just skips the video). Each frame is the fitted curve after k real Adam
        // steps; Adam is deterministic in the seed + step count, so re-running to k iterations
        // reproduces the trajectory exactly (honest per-frame state, no faked interpolation).
        runCatching { renderFitConvergenceMp4(outDir) }
            .onFailure { println("fit-convergence mp4 skipped: ${it.message}") }

        println("functions plot gallery rendered to ${outDir.absolutePath} (${PLOTS.size} plots)")
    }

    /** Render `functions-fit-convergence.mp4`: the fitted curve landing on the target over the
     * descent. Returns the mp4, or null if ffmpeg is unavailable. */
    fun renderFitConvergenceMp4(outDir: File): File? = with(Functions) {
        val ffmpeg = resolveFfmpeg() ?: run {
            println("fit-convergence mp4 skipped: ffmpeg not found")
            return null
        }
        val target = sin(frequency = 2.0, amplitude = 0.8, offset = 0.12)
        val seed = sin(frequency = 2.0, amplitude = 0.30, offset = -0.15)
        val dom = Functions.Domain.of(0.0, 1.0)
        val maxIters = 320
        val stepPerFrame = 8
        val framesDir = File(outDir, "fit-frames").apply { deleteRecursively(); mkdirs() }
        var frame = 0
        var k = 0
        while (k <= maxIters) {
            val run = fitGradient(target = target, seed = seed, iterations = k, lr = 0.05, samples = 160, rngSeed = 0L)
            val spec = PlotSpec(
                title = "fitGradient convergence — iteration $k  (error ${"%.4f".format(run.finalError)})",
                xLabel = "x", yLabel = "f(x)",
                series = listOf(
                    sample("target", target, dom, ORANGE, Style.THICK),
                    sample("fitted @ $k", run.fitted, dom, CYAN, Style.LINE)
                ),
                yRange = -1.1 to 1.1
            )
            ImageIO.write(render(spec), "png", File(framesDir, "frame_%05d.png".format(frame)))
            frame++
            k += stepPerFrame
        }
        // Hold the final frame a beat.
        val last = File(framesDir, "frame_%05d.png".format(frame - 1))
        repeat(12) { ImageIO.write(ImageIO.read(last), "png", File(framesDir, "frame_%05d.png".format(frame++))) }

        val mp4 = File(outDir, "functions-fit-convergence.mp4")
        encodeMp4(ffmpeg, framesDir, "frame_%05d.png", 20, mp4)
        check(mp4.length() > 0L) { "ffmpeg wrote an empty mp4" }
        println("wrote ${mp4.absolutePath} ($frame frames)")
        mp4
    }

    private fun resolveFfmpeg(): String? {
        val candidates = listOfNotNull(
            System.getenv("FFMPEG"),
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg"
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun encodeMp4(ffmpeg: String, framesDir: File, pattern: String, fps: Int, output: File) {
        output.delete()
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-framerate", fps.toString(),
            "-i", pattern,
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-crf", "20",
            "-movflags", "+faststart",
            output.absolutePath
        ).directory(framesDir).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("ffmpeg timed out encoding ${output.name}")
        }
        require(process.exitValue() == 0) {
            "ffmpeg failed (${process.exitValue()}) for ${output.name}:\n${log.takeLast(600)}"
        }
    }
}

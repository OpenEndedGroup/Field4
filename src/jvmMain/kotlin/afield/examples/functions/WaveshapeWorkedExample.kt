package afield.examples.functions

import afield.stdlib.Functions
import java.io.File

/*
FIELD_TEST
id: functions.waveshape.worked-example
runner: jvm-main
entrypoint: afield.examples.functions.WaveshapeWorkedExample
tags: [smoke, examples, functions, waveshape, cpu]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "waveshape worked example passed"
*/

/**
 * A CPU-only worked example of the waveshaping / distortion `FnTransform` family
 * (`fold` / `rectify` / `quantize`).
 *
 * There is no GPU, no Vulkan and no Field window here. It applies each transform to a
 * rising test ramp (so every cell reads as the transform's *transfer curve* — the
 * shape it imposes on its input) and to a plain sine, then hand-rolls a small
 * small-multiples SVG montage (pure string building, sampling `Fn.evalSafe(x)`).
 *
 * The artifact lands at `public/docs/assets/functions/waveshape-transforms.svg` and is
 * embedded in `public/docs/functions_examples.md`. Everything is a deterministic pure
 * function of the source signals, so re-running regenerates a byte-identical montage.
 */
object WaveshapeWorkedExample {

    const val SAMPLES: Int = 240

    /** Output directory, relative to the project root (the cwd for jvm-main / jvmTest). */
    fun defaultOutDir(): File = File(System.getProperty("user.dir"), "public/docs/assets/functions")

    data class Artifacts(val montageSvg: File)

    /** The transfer-curve / signal cells the montage draws, built once so the test and the
     * render share exactly the same signals. */
    fun cells(): List<Cell> = with(Functions) {
        val domain = Functions.Domain.of(0.0, 1.0)
        // A ramp sweeping -2..2 across the domain: plotting a transform of it against x
        // draws its transfer curve directly.
        val sweep = ((identity(domain) * 4.0) - 2.0).withName("sweep -2..2")
        // A loud sine that overshoots every threshold, so the shapers visibly bite.
        val wave = (sin(frequency = 2.0, amplitude = 1.6) over domain).withName("sine x1.6")
        listOf(
            Cell("input sweep -2..2", sweep, domain),
            Cell("sweep / fold(0.7)", sweep / fold(0.7), domain),
            Cell("sweep / rectify(FULL)", sweep / rectify(Functions.RectifyMode.FULL), domain),
            Cell("sweep / rectify(HALF)", sweep / rectify(Functions.RectifyMode.HALF), domain),
            Cell("sweep / quantize(6)", sweep / quantize(6), domain),
            Cell("sine x1.6 / fold(0.7)", wave / fold(0.7), domain)
        )
    }

    data class Cell(val label: String, val fn: Functions.Fn, val domain: Functions.Domain)

    fun render(outDir: File = defaultOutDir()): Artifacts = with(Functions) {
        outDir.mkdirs()
        val svg = svgMontage(width = 720, cols = 2, cellHeight = 150, samples = SAMPLES, cells = cells())
        val montageSvg = File(outDir, "waveshape-transforms.svg")
        montageSvg.writeText(svg)
        Artifacts(montageSvg)
    }

    /**
     * A dependency-free small-multiples montage: one mini line-plot per [cells] entry.
     * Each cell auto-fits its own y-range (the transfer shapes differ in span), draws a
     * zero line, and labels the transform. Mirrors [EvolveWorkedExample]'s SVG builder.
     */
    private fun svgMontage(
        width: Int,
        cols: Int,
        cellHeight: Int,
        samples: Int,
        cells: List<Cell>
    ): String = with(Functions) {
        val headerH = 30.0
        val gap = 10.0
        val rows = (cells.size + cols - 1) / cols
        val cellW = (width - gap * (cols + 1)) / cols
        val height = (headerH + gap + rows * (cellHeight + gap)).toInt()

        val n = samples.coerceAtLeast(2)
        fun f(v: Double): String = ((v * 100.0).toLong() / 100.0).toString()

        val sb = StringBuilder()
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" " +
                "font-family=\"system-ui, sans-serif\" font-size=\"11\">\n"
        )
        sb.append("  <rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" rx=\"6\" " +
            "fill=\"var(--fig-bg, #fbfbfc)\"/>\n")
        sb.append("  <text x=\"${f(gap)}\" y=\"20\" fill=\"var(--fg, #222222)\" " +
            "font-weight=\"600\">Waveshaping transforms — fold / rectify / quantize " +
            "(each cell: transform applied to the input above it)</text>\n")

        cells.forEachIndexed { idx, cell ->
            val col = idx % cols
            val row = idx / cols
            val x0 = gap + col * (cellW + gap)
            val y0 = headerH + gap + row * (cellHeight + gap)
            sb.append("  <rect x=\"${f(x0)}\" y=\"${f(y0)}\" width=\"${f(cellW)}\" " +
                "height=\"$cellHeight\" fill=\"none\" stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\"/>\n")

            val d = cell.domain
            val xs = DoubleArray(n) { d.min + d.length * it / (n - 1).toDouble() }
            var yMin = Double.POSITIVE_INFINITY
            var yMax = Double.NEGATIVE_INFINITY
            val ys = DoubleArray(n) { i ->
                val v = cell.fn.evalSafe(xs[i], outside = Double.NaN)
                if (v.isFinite()) {
                    if (v < yMin) yMin = v
                    if (v > yMax) yMax = v
                }
                v
            }
            if (!yMin.isFinite() || !yMax.isFinite()) { yMin = -1.0; yMax = 1.0 }
            if (yMax - yMin < 1e-9) { yMin -= 1.0; yMax += 1.0 }
            val yPad = (yMax - yMin) * 0.10
            yMin -= yPad; yMax += yPad

            val padIn = 6.0
            val pw = cellW - 2 * padIn
            val ph = cellHeight - 2 * padIn - 8.0
            fun sx(x: Double): Double = x0 + padIn + d.alphaOf(x) * pw
            fun sy(y: Double): Double = y0 + padIn + 8.0 + (1.0 - (y - yMin) / (yMax - yMin)) * ph

            if (yMin < 0.0 && yMax > 0.0) {
                val z = f(sy(0.0))
                sb.append("  <line x1=\"${f(x0 + padIn)}\" y1=\"$z\" x2=\"${f(x0 + padIn + pw)}\" " +
                    "y2=\"$z\" stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\" stroke-dasharray=\"2 3\"/>\n")
            }
            val pts = StringBuilder()
            for (i in 0 until n) {
                val v = ys[i]
                if (v.isFinite()) pts.append("${f(sx(xs[i]))},${f(sy(v))} ")
            }
            sb.append("  <polyline fill=\"none\" stroke=\"var(--series-c, #1f77b4)\" " +
                "stroke-width=\"2\" points=\"${pts.toString().trim()}\"/>\n")
            sb.append("  <text x=\"${f(x0 + padIn)}\" y=\"${f(y0 + 13)}\" " +
                "fill=\"var(--muted, #666666)\">${cell.label}</text>\n")
        }
        sb.append("</svg>\n")
        sb.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) = with(Functions) {
        val artifacts = render()
        println("waveshape worked example: montage -> ${artifacts.montageSvg}")

        // Diagnostic samples so the shaping is auditable on the console too.
        val domain = Functions.Domain.of(0.0, 1.0)
        val sweep = (identity(domain) * 4.0) - 2.0
        val folded = sweep / fold(0.7)
        val rectFull = sweep / rectify(Functions.RectifyMode.FULL)
        val quant = sweep / quantize(6)
        println("fold(0.7) over sweep -2..2:")
        for (i in 0..10) {
            val x = i / 10.0
            println("  x=${"%.2f".format(x)} in=${"%.3f".format(sweep.evalSafe(x))} " +
                "fold=${"%.3f".format(folded.evalSafe(x))} " +
                "rectFull=${"%.3f".format(rectFull.evalSafe(x))} " +
                "quant6=${"%.3f".format(quant.evalSafe(x))}")
        }

        check(artifacts.montageSvg.length() > 500) { "montage svg looks too small" }
        val svg = artifacts.montageSvg.readText()
        check(svg.startsWith("<svg")) { "svg header" }
        check(Regex("<polyline").findAll(svg).count() >= cells().size) { "one polyline per cell" }
        println("waveshape worked example passed")
    }
}

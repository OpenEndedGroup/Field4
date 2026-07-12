package afield.examples.functions

import afield.stdlib.FnEvolveRun
import afield.stdlib.Functions
import afield.stdlib.evolve
import afield.stdlib.matchFitness
import java.io.File

/*
FIELD_TEST
id: functions.evolve.worked-example
runner: jvm-main
entrypoint: afield.examples.functions.EvolveWorkedExample
tags: [smoke, examples, functions, evolve, cpu]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "evolve worked example passed"
*/

/**
 * A CPU-only worked example of the Functions `evolve` harness.
 *
 * There is no GPU, no Vulkan and no Field window here. It builds a target `Fn`,
 * runs the deterministic (µ+λ) hill-climb [evolve] from a plain seed with a FIXED
 * `rngSeed`, and turns the run into committed, reproducible visual evidence: a small
 * SVG line-plot (hand-rolled, pure string building — sampling `Fn.evalSafe(x)` across
 * the `Domain`) overlaying target / seed / evolved-best, plus a Markdown trace table
 * of the accepted mutations.
 *
 * The artifacts land under `public/docs/assets/evolve/` and are embedded in
 * `public/docs/functions_examples.md`. Because everything is seeded, re-running this
 * regenerates byte-identical evidence, which is exactly the property the doc claims.
 */
object EvolveWorkedExample {

    /** Fixed so the whole run — and every emitted file — is reproducible. */
    const val RNG_SEED: Long = 3L
    const val ITERATIONS: Int = 120
    const val LAMBDA: Int = 8
    const val SAMPLES: Int = 160

    /** Output directory, relative to the project root (the cwd for jvm-main / jvmTest). */
    fun defaultOutDir(): File = File(System.getProperty("user.dir"), "public/docs/assets/evolve")

    data class Artifacts(
        val run: FnEvolveRun,
        val plotSvg: File,
        val traceMarkdown: File
    )

    /**
     * Run the example and write the evidence into [outDir]. Returns the run and the
     * files written so a test can assert on both the numbers and the artifacts.
     */
    fun render(outDir: File = defaultOutDir()): Artifacts = with(Functions) {
        outDir.mkdirs()

        // A target gesture: a half-amplitude sine. The default mutation palette
        // (jitter/nudge numeric leaves, substitute an op within its family, prune/
        // duplicate subtrees) has everything it needs to reach it from the seed.
        val target = sin(frequency = 1.0, amplitude = 0.5)

        // A plain seed: a full-amplitude cosine. Wrong op (cosine, not sine) AND wrong
        // amplitude — so a good run must both substitute the op and dial in the numbers.
        val seed = cos(frequency = 1.0, amplitude = 1.0)

        val fitness = matchFitness(target, samples = SAMPLES, complexityWeight = 0.01)
        val run = evolve(
            seed = seed,
            iterations = ITERATIONS,
            lambda = LAMBDA,
            rngSeed = RNG_SEED,
            fitness = fitness
        )

        val domain = target.domain
        val plot = svgOverlay(
            width = 720,
            height = 320,
            domain = domain,
            samples = SAMPLES,
            series = listOf(
                Series("target", target, "var(--series-a, #d62728)", 2.5),
                Series("seed", seed, "var(--series-b, #7f7f7f)", 1.5, dashed = true),
                Series("evolved best", run.best, "var(--series-c, #1f77b4)", 2.5)
            )
        )
        val plotSvg = File(outDir, "evolve-overlay.svg")
        plotSvg.writeText(plot)

        val trace = traceMarkdown(run)
        val traceFile = File(outDir, "evolve-trace.md")
        traceFile.writeText(trace)

        Artifacts(run, plotSvg, traceFile)
    }

    private data class Series(
        val label: String,
        val fn: Functions.Fn,
        val stroke: String,
        val strokeWidth: Double,
        val dashed: Boolean = false
    )

    /**
     * A dependency-free SVG line-plot. Samples every [Series] across [domain] and draws
     * one `<polyline>` per series with a shared, auto-fitted y-range plus a legend.
     */
    private fun svgOverlay(
        width: Int,
        height: Int,
        domain: Functions.Domain,
        samples: Int,
        series: List<Series>
    ): String = with(Functions) {
        val padL = 44.0
        val padR = 16.0
        val padT = 16.0
        val padB = 28.0
        val plotW = width - padL - padR
        val plotH = height - padT - padB

        val n = samples.coerceAtLeast(2)
        val xs = DoubleArray(n) { domain.min + domain.length * it / (n - 1).toDouble() }

        // Fit y-range across every finite sample of every series.
        var yMin = Double.POSITIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY
        val sampled = series.map { s ->
            DoubleArray(n) { i ->
                val v = s.fn.evalSafe(xs[i], outside = Double.NaN)
                if (v.isFinite()) {
                    if (v < yMin) yMin = v
                    if (v > yMax) yMax = v
                }
                v
            }
        }
        if (!yMin.isFinite() || !yMax.isFinite()) { yMin = -1.0; yMax = 1.0 }
        if (yMax - yMin < 1e-9) { yMin -= 1.0; yMax += 1.0 }
        val yPad = (yMax - yMin) * 0.08
        yMin -= yPad; yMax += yPad

        fun sx(x: Double): Double = padL + (domain.alphaOf(x)) * plotW
        fun sy(y: Double): Double = padT + (1.0 - (y - yMin) / (yMax - yMin)) * plotH
        fun f(v: Double): String = ((v * 100.0).toLong() / 100.0).toString()

        val sb = StringBuilder()
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" " +
                "font-family=\"system-ui, sans-serif\" font-size=\"11\">\n"
        )
        // Self-contained light figure background so the plot stays legible embedded as an
        // image on any docs theme (an <img> does not inherit the page's CSS variables).
        sb.append("  <rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" rx=\"6\" " +
            "fill=\"var(--fig-bg, #fbfbfc)\"/>\n")
        // Frame + zero line.
        sb.append("  <rect x=\"$padL\" y=\"$padT\" width=\"$plotW\" height=\"$plotH\" " +
            "fill=\"none\" stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\"/>\n")
        if (yMin < 0.0 && yMax > 0.0) {
            val z = f(sy(0.0))
            sb.append("  <line x1=\"$padL\" y1=\"$z\" x2=\"${f(padL + plotW)}\" y2=\"$z\" " +
                "stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\" stroke-dasharray=\"2 3\"/>\n")
        }
        // Y ticks (min/mid/max).
        for (t in listOf(yMin, (yMin + yMax) / 2.0, yMax)) {
            val py = f(sy(t))
            sb.append("  <text x=\"${f(padL - 6)}\" y=\"${f(sy(t) + 3)}\" text-anchor=\"end\" " +
                "fill=\"var(--muted, #666666)\">${f(t)}</text>\n")
            sb.append("  <line x1=\"${f(padL - 3)}\" y1=\"$py\" x2=\"$padL\" y2=\"$py\" " +
                "stroke=\"var(--grid, #cccccc)\"/>\n")
        }
        // X ticks (domain endpoints).
        sb.append("  <text x=\"$padL\" y=\"${f(padT + plotH + 18)}\" text-anchor=\"start\" " +
            "fill=\"var(--muted, #666666)\">${f(domain.min)}</text>\n")
        sb.append("  <text x=\"${f(padL + plotW)}\" y=\"${f(padT + plotH + 18)}\" text-anchor=\"end\" " +
            "fill=\"var(--muted, #666666)\">${f(domain.max)}</text>\n")

        // Series polylines.
        series.forEachIndexed { si, s ->
            val ys = sampled[si]
            val pts = StringBuilder()
            for (i in 0 until n) {
                val v = ys[i]
                if (v.isFinite()) pts.append("${f(sx(xs[i]))},${f(sy(v))} ")
            }
            val dash = if (s.dashed) " stroke-dasharray=\"5 4\"" else ""
            sb.append("  <polyline fill=\"none\" stroke=\"${s.stroke}\" " +
                "stroke-width=\"${s.strokeWidth}\"$dash points=\"${pts.toString().trim()}\"/>\n")
        }
        // Legend.
        var ly = padT + 6.0
        series.forEach { s ->
            sb.append("  <line x1=\"${f(padL + plotW - 116)}\" y1=\"${f(ly)}\" " +
                "x2=\"${f(padL + plotW - 96)}\" y2=\"${f(ly)}\" stroke=\"${s.stroke}\" " +
                "stroke-width=\"${s.strokeWidth}\"${if (s.dashed) " stroke-dasharray=\"5 4\"" else ""}/>\n")
            sb.append("  <text x=\"${f(padL + plotW - 90)}\" y=\"${f(ly + 3)}\" " +
                "fill=\"var(--fg, #222222)\">${s.label}</text>\n")
            ly += 16.0
        }
        sb.append("</svg>\n")
        sb.toString()
    }

    private fun traceMarkdown(run: FnEvolveRun): String {
        fun r(v: Double): String = String.format("%.4f", v)
        val sb = StringBuilder()
        sb.append("| iteration | accepted mutation | score (−RMSE − penalty) |\n")
        sb.append("| --- | --- | --- |\n")
        sb.append("| 0 (seed) | — | ${r(run.initialScore)} |\n")
        for (step in run.steps) {
            sb.append("| ${step.iteration} | ${step.mutation} | ${r(step.score)} |\n")
        }
        sb.append("\n")
        sb.append("- initial score: **${r(run.initialScore)}**\n")
        sb.append("- best score: **${r(run.bestScore)}**\n")
        sb.append("- accepted mutations: **${run.steps.size}**\n")
        sb.append("- iterations: **${run.iterations}**, evaluations: **${run.evaluations}**\n")
        return sb.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val artifacts = render()
        val run = artifacts.run
        println("evolve worked example: seed=$RNG_SEED iterations=$ITERATIONS lambda=$LAMBDA")
        println("  initialScore=${run.initialScore} bestScore=${run.bestScore} improved=${run.improved}")
        println("  acceptedMutations=${run.steps.size} evaluations=${run.evaluations}")
        println("  plot   -> ${artifacts.plotSvg}")
        println("  trace  -> ${artifacts.traceMarkdown}")
        check(run.improved) { "expected evolution to improve on the seed" }
        check(artifacts.plotSvg.length() > 0) { "empty plot svg" }
        check(artifacts.traceMarkdown.length() > 0) { "empty trace" }
        println("evolve worked example passed")
    }
}

package afield.examples.functions

import afield.stdlib.FnEvolveRun
import afield.stdlib.FnVarietyRun
import afield.stdlib.Functions
import afield.stdlib.evolve
import afield.stdlib.evolveVariety
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
 * It then does the counterpart the marquee feature deserves: [evolveVariety] grows a
 * *set* of behaviourally distinct gestures by novelty search, and [render] lays them out
 * as a small-multiples montage — the visual argument for variety generation (a grid of
 * genuinely different shapes from one seed, not one curve chasing a target).
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

    // Variety-montage run. Mirrors the `evolveVariety(...)` snippet in the docs so the
    // rendered montage and the documented call stay in lockstep.
    const val VARIETY_COUNT: Int = 6
    const val VARIETY_ITERATIONS: Int = 48
    const val VARIETY_RNG_SEED: Long = 7L
    const val VARIETY_NEIGHBOURS: Int = 3
    const val VARIETY_SAMPLES: Int = 120

    /** Output directory, relative to the project root (the cwd for jvm-main / jvmTest). */
    fun defaultOutDir(): File = File(System.getProperty("user.dir"), "public/docs/assets/evolve")

    data class Artifacts(
        val run: FnEvolveRun,
        val plotSvg: File,
        val traceMarkdown: File,
        val variety: FnVarietyRun,
        val varietySvg: File
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

        // Variety generation: grow a set of behaviourally distinct gestures from one seed.
        // The seed is a modest sine; novelty search pushes each slot away from the ones
        // already found, so the montage shows what the *set* looks like, not one match.
        val varietySeed = sin(2.0) * 0.5
        // `normalize = true` is the whole point: it z-scores each behaviour descriptor so
        // novelty measures *shape*, not amplitude. Without it the default palette drifts
        // toward ever-louder copies of one gesture (magnitude is the cheapest way to be
        // "novel" in raw value-space); with it the set spreads across genuinely different
        // waveforms, and the default mutation palette works as-is (duplicateSubtree only
        // rescales, which normalization ignores, so it can no longer run away).
        val variety = evolveVariety(
            seed = varietySeed,
            count = VARIETY_COUNT,
            iterations = VARIETY_ITERATIONS,
            rngSeed = VARIETY_RNG_SEED,
            samples = VARIETY_SAMPLES,
            neighbours = VARIETY_NEIGHBOURS,
            normalize = true
        )
        // Plot the stored *normalized* behaviours (the shape coordinate novelty explored),
        // so every cell shares one z-scored scale and the montage reads as pure shape variety.
        val montage = svgMontage(
            width = 720,
            cols = 3,
            cellHeight = 132,
            domain = domain,
            spread = variety.meanPairwiseDistance,
            cells = variety.behaviours.mapIndexed { i, b -> "variety-$i" to b.samples }
        )
        val varietySvg = File(outDir, "evolve-variety.svg")
        varietySvg.writeText(montage)

        Artifacts(run, plotSvg, traceFile, variety, varietySvg)
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

    /**
     * A small-multiples montage: one mini line-plot per [cells] entry, laid out in a grid
     * of [cols] columns. Every cell shares one auto-fitted y-range so the shapes are
     * directly comparable — the point is to *see* that the set is behaviourally spread out.
     */
    private fun svgMontage(
        width: Int,
        cols: Int,
        cellHeight: Int,
        domain: Functions.Domain,
        spread: Double,
        cells: List<Pair<String, DoubleArray>>
    ): String {
        val headerH = 30.0
        val gap = 10.0
        val rows = (cells.size + cols - 1) / cols
        val cellW = (width - gap * (cols + 1)) / cols
        val height = (headerH + gap + rows * (cellHeight + gap)).toInt()

        val n = cells.firstOrNull()?.second?.size?.coerceAtLeast(2) ?: 2
        val xs = DoubleArray(n) { domain.min + domain.length * it / (n - 1).toDouble() }

        // One shared y-range across every cell, so equal vertical space means equal value.
        var yMin = Double.POSITIVE_INFINITY
        var yMax = Double.NEGATIVE_INFINITY
        val sampled = cells.map { (_, ys) ->
            for (v in ys) if (v.isFinite()) {
                if (v < yMin) yMin = v
                if (v > yMax) yMax = v
            }
            ys
        }
        if (!yMin.isFinite() || !yMax.isFinite()) { yMin = -1.0; yMax = 1.0 }
        if (yMax - yMin < 1e-9) { yMin -= 1.0; yMax += 1.0 }
        val yPad = (yMax - yMin) * 0.08
        yMin -= yPad; yMax += yPad

        fun f(v: Double): String = ((v * 100.0).toLong() / 100.0).toString()

        val sb = StringBuilder()
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" " +
                "font-family=\"system-ui, sans-serif\" font-size=\"11\">\n"
        )
        sb.append("  <rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" rx=\"6\" " +
            "fill=\"var(--fig-bg, #fbfbfc)\"/>\n")
        sb.append("  <text x=\"${f(gap)}\" y=\"20\" fill=\"var(--fg, #222222)\" " +
            "font-weight=\"600\">evolveVariety — ${cells.size} behaviourally distinct gestures " +
            "(spread = ${String.format("%.3f", spread)})</text>\n")

        cells.forEachIndexed { idx, (label, _) ->
            val col = idx % cols
            val row = idx / cols
            val x0 = gap + col * (cellW + gap)
            val y0 = headerH + gap + row * (cellHeight + gap)
            // Cell frame.
            sb.append("  <rect x=\"${f(x0)}\" y=\"${f(y0)}\" width=\"${f(cellW)}\" " +
                "height=\"$cellHeight\" fill=\"none\" stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\"/>\n")

            val padIn = 6.0
            val pw = cellW - 2 * padIn
            val ph = cellHeight - 2 * padIn
            fun sx(x: Double): Double = x0 + padIn + domain.alphaOf(x) * pw
            fun sy(y: Double): Double = y0 + padIn + (1.0 - (y - yMin) / (yMax - yMin)) * ph

            if (yMin < 0.0 && yMax > 0.0) {
                val z = f(sy(0.0))
                sb.append("  <line x1=\"${f(x0 + padIn)}\" y1=\"$z\" x2=\"${f(x0 + padIn + pw)}\" " +
                    "y2=\"$z\" stroke=\"var(--grid, #cccccc)\" stroke-width=\"1\" stroke-dasharray=\"2 3\"/>\n")
            }
            val ys = sampled[idx]
            val pts = StringBuilder()
            for (i in 0 until n) {
                val v = ys[i]
                if (v.isFinite()) pts.append("${f(sx(xs[i]))},${f(sy(v))} ")
            }
            sb.append("  <polyline fill=\"none\" stroke=\"var(--series-c, #1f77b4)\" " +
                "stroke-width=\"2\" points=\"${pts.toString().trim()}\"/>\n")
            sb.append("  <text x=\"${f(x0 + padIn)}\" y=\"${f(y0 + 13)}\" " +
                "fill=\"var(--muted, #666666)\">$label</text>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
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
        val variety = artifacts.variety
        println("evolveVariety: count=$VARIETY_COUNT iterations=$VARIETY_ITERATIONS seed=$VARIETY_RNG_SEED")
        println("  results=${variety.results.size} meanPairwiseDistance=${variety.meanPairwiseDistance}")
        println("  montage -> ${artifacts.varietySvg}")
        check(run.improved) { "expected evolution to improve on the seed" }
        check(artifacts.plotSvg.length() > 0) { "empty plot svg" }
        check(artifacts.traceMarkdown.length() > 0) { "empty trace" }
        check(variety.results.size == VARIETY_COUNT) { "expected $VARIETY_COUNT variety results" }
        check(variety.meanPairwiseDistance > 0.0) { "variety set should be behaviourally spread out" }
        check(artifacts.varietySvg.length() > 0) { "empty variety montage svg" }
        println("evolve worked example passed")
    }
}

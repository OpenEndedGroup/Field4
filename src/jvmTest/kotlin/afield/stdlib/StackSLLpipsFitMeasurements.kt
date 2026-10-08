package afield.stdlib

import afield.stacksl.cpu.AdAnnealing
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.stacksl.cpu.StackSLFitLoss
import afield.stacksl.cpu.StackSLImageLoss
import afield.stacksl.cpu.StackSLLpipsLoss
import afield.stacksl.cpu.StackSLWires
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * MSE-fit against LPIPS-fit against LPIPS_PIXEL-fit, on real leaders: what each gains under the
 * search's own metric (skill under LPIPS at 32 px with 2x2 region cases, the main stage of a
 * scheduled run), what it does to the held-out [StackSLLayout.layoutAgreement], and what an Adam
 * step costs, at 16 and 48 steps.
 *
 * Programs: a short [StackSLEvolution] on each of the five [StackSLTargets] (LPIPS at 32 px, 2x2
 * regions, mu 12, lambda 24, 30 generations), its top three programs with a literal to move; and
 * hand-written seeds — `spot` with the wrong radius, with the wrong colours and as its own
 * negative, `bands` with the wrong stripe frequency and with washed-out colours, and `rings` as its
 * negative with the wrong frequency. The negatives probe LPIPS's blindness to inversion.
 *
 * Polarity is the Pearson correlation of the render's luminance with the target's: near 1 the
 * right way round, negative inverted. A descent that drifts toward inverted colours shows as
 * polarity falling.
 *
 * [lpipsChainAgainstFiniteDifference] checks the whole chain (wires to LPIPS) against central
 * differences, times the parts of an LPIPS step, and prints the trajectory of a pure-LPIPS fit
 * that climbs its own loss through a `threshold` surrogate.
 *
 * A measurement, not a check (it is in `jvmMeasurements`): it asserts nothing and writes
 * `build/stacksl-lpips-fit/report.txt` and `fitted.tsv`. Results of 2026-10-08 are summarised in
 * the KDoc of [StackSLFitLoss.LPIPS] and [StackSLEvolution.fit].
 */
class StackSLLpipsFitMeasurements {

    private val stage = StackSLStage(dim = 32, metric = StackSLMetric.LPIPS, lpipsGrid = 2)
    private val settings = StackSLEvolveSettings(mu = 12, lambda = 24, stages = listOf(stage))
    private val budgets = listOf(16, 48)
    private val generations = 30
    private val learningRate = 0.05

    private val handSeeds = listOf(
        "spot" to "[1,0.82,0.25] [0.03,0.04,0.12] p length 0.4 threshold mix",
        "spot" to "[0.7,0.5,0.5] [0.25,0.2,0.3] p length 0.28 threshold mix",
        "spot" to "[0.03,0.04,0.12] [1,0.82,0.25] p length 0.28 threshold mix",
        "bands" to "[0.98,0.9,0.1] [0.05,0.1,0.55] uv x 2.6 * fract 0.5 threshold mix",
        "bands" to "[0.6,0.6,0.3] [0.3,0.3,0.5] uv x 3.0 * fract 0.4 threshold mix",
        "rings" to "[0,0,0] [1,1,1] p length 34.0 * sin 0.0 threshold mix"
    )

    private fun luminance(r: CpuRaster): DoubleArray = DoubleArray(r.width * r.height) {
        0.299 * r.rgba[it * 4] + 0.587 * r.rgba[it * 4 + 1] + 0.114 * r.rgba[it * 4 + 2]
    }

    private fun polarity(program: String, target: CpuRaster): Double {
        val a = luminance(StackSLCpu.renderStill(program, dim = target.width, time = 1.75f))
        val b = luminance(target)
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb)
        }
        return if (saa == 0.0 || sbb == 0.0) 0.0 else sab / sqrt(saa * sbb)
    }

    private class Row(
        val target: String, val program: String, val loss: StackSLFitLoss,
        val skillBefore: Double, val skillAfter: Double, val layoutBefore: Double, val layoutAfter: Double,
        val polarityBefore: Double, val polarityAfter: Double, val lossBefore: Double, val lossAfter: Double,
        val msPerStep: Double, val fitted: String, val iterations: Int, val bestLoss: Double
    )

    /** The whole chain, program wires to LPIPS, against a central difference of its own value. */
    @Test
    fun lpipsChainAgainstFiniteDifference() {
        for (program in listOf("uv 3.0 * sin 0.5 + palette", "uv 2.0 * sin 0.25 * 0.5 + saturate", "p 1.7 twist 2.0 * sin palette saturate",
            "[0.7,0.5,0.5] [0.25,0.2,0.3] p length 0.28 threshold mix")) {
            for ((kind, grid) in listOf(StackSLFitLoss.LPIPS to 0, StackSLFitLoss.LPIPS to 2, StackSLFitLoss.LPIPS_PIXEL to 2)) {
                val wired = StackSLWires.lift(program, "program")
                val image = StackSLImageLoss(wired, StackSLCpu.stillTemplate(1.75f), 32, StackSLTargets.spot(32).raster)
                val loss = StackSLLpipsLoss(image, 64, grid, kind)
                val v = wired.initial
                val g = loss.valueAndGradient(v).gradient
                for (w in wired.literalWires.map { it.index }) {
                    val row = StringBuilder("  $program ${kind.label} g$grid wire $w: grad %.5e".format(g[w]))
                    for (h in listOf(1e-2, 1e-3)) {
                        val fd = (loss.value(v.copyOf().also { it[w] += h }) - loss.value(v.copyOf().also { it[w] -= h })) / (2 * h)
                        row.append("  fd(%.0e) %.5e".format(h, fd))
                    }
                    println(row)
                }
            }
        }
        // Where an LPIPS step's time goes, on one program at 32 px.
        run {
            val program = "[0.98,0.9,0.1] [0.05,0.1,0.55] uv x 2.6 * fract 0.5 threshold mix"
            val wired = StackSLWires.lift(program, "program")
            val image = StackSLImageLoss(wired, StackSLCpu.stillTemplate(1.75f), 32, StackSLTargets.bands(32).raster)
            val v = wired.initial
            val raster = image.render(v)
            val planar = StackSLLpipsLoss.enlarge(raster, 64)
            val want = StackSLLpipsLoss.enlarge(image.target, 64)
            val seed = StackSLLpipsLoss.mseSeed(raster, image.target)
            fun time(label: String, n: Int = 20, f: () -> Unit) {
                repeat(5) { f() }
                val t0 = System.nanoTime()
                repeat(n) { f() }
                println("  cost %-44s %.1f ms".format(label, (System.nanoTime() - t0) / 1e6 / n))
            }
            time("render (Float path)") { image.render(v) }
            time("MSE valueAndGradient (AD sweep)") { image.valueAndGradient(v) }
            time("seeded AD sweep") { image.seededGradient(v, seed) }
            time("LPIPS gradient, whole 64 px") { afield.onnx.Lpips.distanceAndGradientPlanar(planar, 64, 64, want) }
            time("LPIPS gradient, 4 regions of 32 px batched") {
                afield.onnx.Lpips.distancesAndGradientsPlanar(StackSLLpipsLoss.regions(planar, 64, 2), 4, 32, 32, StackSLLpipsLoss.regions(want, 64, 2), 4)
            }
            for (kind in listOf(StackSLFitLoss.LPIPS, StackSLFitLoss.LPIPS_PIXEL)) for (grid in listOf(0, 2)) {
                val loss = StackSLLpipsLoss(image, 64, grid, kind)
                time("${kind.label} g$grid valueAndGradient") { loss.valueAndGradient(v) }
            }
        }
        // The trajectory of the fit that climbed.
        val program = "[0.7,0.5,0.5] [0.25,0.2,0.3] p length 0.28 threshold mix"
        for (annealing in listOf(true, false)) for (lr in listOf(0.05, 0.01)) {
            val fit = StackSLFit.fit(
                program, StackSLTargets.spot(32).raster, template = StackSLCpu.stillTemplate(1.75f), dim = 32, iterations = 24,
                learningRate = lr, annealing = if (annealing) AdAnnealing.stepOnly(24) else null, loss = StackSLFitLoss.LPIPS, lpipsSide = 64, lpipsGrid = 2
            )
            println("  trajectory annealing=$annealing lr=$lr: " + fit.run.losses.joinToString(" ") { "%.3f".format(it) })
            println("    -> " + fit.fittedProgram)
        }
    }

    @Test
    fun mseVersusLpipsFits() {
        val out = File("build/stacksl-lpips-fit").apply { mkdirs() }
        val rows = ArrayList<Row>()
        val log = StringBuilder()
        fun say(s: String) {
            println(s); log.appendLine(s); File(out, "report.txt").writeText(log.toString())
        }
        say("StackSL fit: MSE vs LPIPS vs LPIPS_PIXEL — stage ${stage.describe()}, $budgets Adam steps, lr $learningRate, step annealing")
        val leaders = ArrayList<Pair<String, String>>()
        for (t in StackSLTargets.all(32)) {
            val evo = StackSLEvolution({ dim -> StackSLTargets.byName(t.name, dim)!! }, settings, seed = 1)
            val t0 = System.nanoTime()
            evo.step(generations)
            val picked = evo.ranked.map { it.genome.text }
                .filter { runCatching { StackSLWires.lift(it, "program").literalWires.isNotEmpty() }.getOrDefault(false) }
                .take(3)
            say("evolved ${t.name}: $generations generations, ${evo.renders} renders in %.1f s; leaders %s".format((System.nanoTime() - t0) / 1e9, picked.map { "'${it.replace("\n", " ")}'" }))
            picked.forEach { leaders += t.name to it }
        }
        leaders += handSeeds

        for ((name, program) in leaders) {
            val evo = StackSLEvolution({ dim -> StackSLTargets.byName(name, dim)!! }, settings, seed = 1)
            val target = StackSLTargets.byName(name, stage.dim)!!.raster
            val before = evo.skill(evo.objective.evaluate(StackSLProgram.of(program)).fitness)
            val layoutBefore = evo.layoutOf(StackSLProgram.of(program))
            val polBefore = polarity(program, target)
            for (iterations in budgets) for (loss in StackSLFitLoss.entries) {
                val t0 = System.nanoTime()
                val fit = runCatching {
                    StackSLFit.fit(
                        program, target, template = StackSLCpu.stillTemplate(1.75f), dim = stage.dim,
                        iterations = iterations, learningRate = learningRate,
                        annealing = AdAnnealing.stepOnly(iterations), loss = loss, lpipsSide = stage.lpipsSide(),
                        lpipsGrid = if (loss != StackSLFitLoss.MSE) stage.lpipsGrid else 0
                    )
                }.getOrElse { e ->
                    say("  $name '${program.replace("\n", " ")}' ${loss.label}: fit failed: ${e.message}")
                    continue
                }
                val ms = (System.nanoTime() - t0) / 1e6 / fit.run.evaluations.coerceAtLeast(1)
                val fitted = StackSLProgram.of(fit.fittedProgram)
                val after = evo.skill(evo.objective.evaluate(fitted).fitness)
                rows += Row(
                    name, program, loss, before, after, layoutBefore, evo.layoutOf(fitted), polBefore,
                    polarity(fit.fittedProgram, target), fit.seedLoss, fit.fittedLoss, ms, fit.fittedProgram, iterations,
                    fit.run.losses.min()
                )
                val r = rows.last()
                say(
                    "  %-9s %2d %-10s skill %+.3f -> %+.3f (%+.3f)  layout %+.3f -> %+.3f  polarity %+.2f -> %+.2f  loss %.4f -> %.4f (best %.4f)  %.0f ms/step  | %s"
                        .format(name, iterations, loss.label, r.skillBefore, r.skillAfter, r.skillAfter - r.skillBefore, r.layoutBefore, r.layoutAfter,
                            r.polarityBefore, r.polarityAfter, r.lossBefore, r.lossAfter, r.bestLoss, r.msPerStep, program.replace("\n", " "))
                )
            }
        }

        say("")
        say("summary over ${leaders.size} programs (mean; skill gained under ${stage.describe()}, layout and polarity change, ms per Adam step):")
        for (iterations in budgets) for (loss in StackSLFitLoss.entries) {
            val rs = rows.filter { it.loss == loss && it.iterations == iterations }
            if (rs.isEmpty()) continue
            say(
                "  %2d steps %-10s skill gained %+.4f (better on %d, worse on %d, literals unmoved on %d)  layout %+.4f  polarity %+.3f (fell by >0.2 on %d, ended negative on %d)  %.0f ms/step"
                    .format(iterations, loss.label, rs.map { it.skillAfter - it.skillBefore }.average(),
                        rs.count { it.skillAfter > it.skillBefore + 1e-4 }, rs.count { it.skillAfter < it.skillBefore - 1e-4 }, rs.count { it.fitted == it.program },
                        rs.map { it.layoutAfter - it.layoutBefore }.average(), rs.map { it.polarityAfter - it.polarityBefore }.average(),
                        rs.count { it.polarityAfter < it.polarityBefore - 0.2 }, rs.count { it.polarityAfter < 0 && it.polarityBefore >= 0 },
                        rs.map { it.msPerStep }.average())
            )
        }
        File(out, "fitted.tsv").writeText(buildString {
            appendLine("target\titerations\tloss\tskillBefore\tskillAfter\tlayoutBefore\tlayoutAfter\tpolarityBefore\tpolarityAfter\tmsPerStep\tprogram\tfitted")
            for (r in rows) appendLine(
                listOf(r.target, r.iterations, r.loss.label, r.skillBefore, r.skillAfter, r.layoutBefore, r.layoutAfter, r.polarityBefore, r.polarityAfter,
                    r.msPerStep, r.program.replace("\n", " "), r.fitted.replace("\n", " ")).joinToString("\t")
            )
        })
    }
}

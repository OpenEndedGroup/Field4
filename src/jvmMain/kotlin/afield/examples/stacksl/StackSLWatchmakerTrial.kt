package afield.examples.stacksl

import afield.lang.stacksl.cpu.StackSLCpu
import afield.media.image.Img
import afield.study.search.Selection
import afield.lang.stacksl.evolution.StackSLEvolution
import afield.lang.stacksl.evolution.StackSLEvolveSettings
import afield.lang.stacksl.evolution.StackSLImageObjective
import afield.lang.stacksl.evolution.StackSLLayout
import afield.lang.stacksl.evolution.StackSLMetric
import afield.lang.stacksl.evolution.StackSLProgram
import afield.lang.stacksl.evolution.StackSLTarget
import afield.lang.stacksl.evolution.StackSLTargets
import afield.lang.stacksl.evolution.StackSLWatchmaker
import afield.lang.stacksl.evolution.evolveStackSLTowards
import afield.media.image.montage
import afield.media.image.savePng
import afield.simulation.automata.savePng
import afield.graphics.text.toImg
import afield.lang.stacksl.evolution.toImg
import afield.media.image.toImg
import afield.simulation.agents.toImg
import afield.simulation.automata.toImg
import afield.media.image.upscaleNearest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Does the watchmaker help? Every arm gets the same render budget on the same targets, and every
 * result is judged by one referee none of them optimised as such (LPIPS at 32 px over the whole
 * picture) and by the held-out layout check at 32 and 128 px.
 *
 * Arms: `baseline` ([evolveStackSLTowards] with lexicase, the 2026-10-07 search), `random` (its
 * control), `wide` ([StackSLEvolveSettings.wide]), `fit` (wide, plus a gradient step on the leader
 * every 4 generations), `watchmaker` (wide, plus a model round every 8 generations and on a
 * 4-generation stall, one pass), `revise` (the same with the second pass, where the model sees
 * what its programs draw), `frequent` (revise, every 4 generations and on a 2-generation stall) and
 * `all` (frequent plus fit). The model arms call the configured AI provider and cost money; leave
 * them out with `--arms baseline,random,wide,fit`.
 *
 * Not a FIELD_TEST: it is a measurement, run by hand through the backdoor's `run-jvm-main`:
 * `--budget 1200 --targets spot,bands --arms ... --out DIR`.
 */
object StackSLWatchmakerTrial {

    data class ArmResult(
        val seed: Long,
        val target: String,
        val arm: String,
        val program: String,
        val refereeSkill: Double,
        val layout32: Double,
        val layout128: Double,
        val renders: Int,
        val seconds: Double,
        val notes: List<String> = emptyList(),
        val credit: Map<String, String> = emptyMap(),
        val usage: String = ""
    )

    /** Model calls the `llm-only` arm stops at: what `frequent` averaged per run at 1,200 renders. */
    const val LLM_ONLY_CALLS = 13

    /**
     * Targets nothing was tuned on. The watchmaker's prompt was revised while looking at runs on the
     * five built-in targets, so a result on those alone could be a prompt fitted to them; these were
     * written afterwards and never looked at before the run that measured them.
     */
    fun heldOut(name: String, dim: Int): StackSLTarget? = when (name) {
        "triangle" -> StackSLTargets.generate(name, dim) { u, v ->
            if (v > 0.2 && v < 0.8 && kotlin.math.abs(u - 0.5) < (v - 0.2) * 0.6) floatArrayOf(0.95f, 0.8f, 0.1f) else floatArrayOf(0.08f, 0.1f, 0.3f)
        }
        "diagonal" -> StackSLTargets.generate(name, dim) { u, v ->
            if (kotlin.math.floor((u + v) * 5.0).toInt() % 2 == 0) floatArrayOf(0.85f, 0.1f, 0.15f) else floatArrayOf(0.95f, 0.95f, 0.9f)
        }
        "twodiscs" -> StackSLTargets.generate(name, dim) { u, v ->
            val a = kotlin.math.hypot(u - 0.3, v - 0.5); val b = kotlin.math.hypot(u - 0.7, v - 0.5)
            if (a < 0.15 || b < 0.15) floatArrayOf(0.1f, 0.8f, 0.85f) else floatArrayOf(0.02f, 0.02f, 0.05f)
        }
        "cross" -> StackSLTargets.generate(name, dim) { u, v ->
            if (kotlin.math.abs(u - 0.5) < 0.12 || kotlin.math.abs(v - 0.5) < 0.12) floatArrayOf(1f, 1f, 1f) else floatArrayOf(0.8f, 0.05f, 0.1f)
        }
        "bullseye" -> StackSLTargets.generate(name, dim) { u, v ->
            val r = kotlin.math.hypot(u - 0.5, v - 0.5)
            if (r < 0.12 || (r > 0.25 && r < 0.35)) floatArrayOf(1f, 0.55f, 0.1f) else floatArrayOf(0.05f, 0.08f, 0.25f)
        }
        "tilt" -> StackSLTargets.generate(name, dim) { u, v ->
            if (v > 0.3 + 0.4 * u) floatArrayOf(0.2f, 0.6f, 0.2f) else floatArrayOf(0.6f, 0.8f, 0.95f)
        }
        // A smooth sky, orange at the top to violet at the bottom, with a yellow sun: many colours.
        "sunset" -> StackSLTargets.generate(name, dim) { u, v ->
            val r = kotlin.math.hypot(u - 0.5, v - 0.6)
            if (r < 0.15) floatArrayOf(1f, 0.9f, 0.3f)
            else floatArrayOf((0.95 - 0.55 * v).toFloat(), (0.55 - 0.4 * v).toFloat(), (0.2 + 0.4 * v).toFloat())
        }
        else -> null
    }

    /** A built-in target or a held-out one. */
    fun targetAt(name: String, dim: Int): StackSLTarget =
        StackSLTargets.byName(name, dim) ?: heldOut(name, dim) ?: error("no target '$name'")

    /**
     * A figure from a finished trial: one row per target, the target and then each of [arms]'s best
     * program from [seed], re-rendered at [side] px from the program text the trial recorded.
     */
    fun figure(results: File, arms: List<String>, seed: Long, targets: List<String>?, side: Int, out: File, labels: List<String> = arms) {
        val all: List<ArmResult> = jacksonObjectMapper().readValue(results, jacksonObjectMapper().typeFactory
            .constructCollectionType(List::class.java, ArmResult::class.java))
        val rows = ArrayList<Pair<String, Img>>()
        for (name in targets ?: all.map { it.target }.distinct()) {
            rows += name to targetAt(name, side).raster.toImg()
            for ((i, arm) in arms.withIndex()) {
                val r = all.firstOrNull { it.target == name && it.arm == arm && it.seed == seed } ?: continue
                // Short, so a label fits under a small panel: "both .99", "plain -.12".
                val score = "%.2f".format(r.layout128).replace("0.", ".")
                rows += "${labels.getOrElse(i) { arm }} $score" to StackSLCpu.renderStill(r.program, dim = side).toImg()
            }
        }
        montage(rows, columns = 1 + arms.size, cellSide = side + 8).savePng(out.path)
        println("stacksl watchmaker figure written to ${out.absolutePath}")
    }

    /**
     * Resemblance over time, for a plot: every saved run in [dirs] (`s<seed>-<target>-<arm>.json`),
     * every new best program it found, with the renders it took, that program's layout agreement at
     * [side] px and its skill under the trial's one referee (LPIPS at 32 px over the whole picture, the
     * same for every stage and arm), as CSV rows `arm,target,seed,generation,renders,layout,skill,origin`.
     */
    fun curves(dirs: List<File>, out: File, side: Int = 128) {
        val name = Regex("""s(\d+)-([a-z]+)-([a-z-]+)\.json""")
        val rows = ArrayList<String>()
        rows += "arm,target,seed,generation,renders,layout,skill,origin"
        val referees = HashMap<String, StackSLImageObjective>()
        for (dir in dirs) for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            val m = name.matchEntire(f.name) ?: continue
            val (seed, target, arm) = m.destructured
            val snapshot = runCatching { afield.lang.stacksl.evolution.StackSLEvolutionSnapshot.read(f) }.getOrNull() ?: continue
            val t = targetAt(target, side).raster
            val referee = referees.getOrPut(target) { StackSLImageObjective(targetAt(target, 32), metric = StackSLMetric.LPIPS) }
            for (i in snapshot.improvements) {
                val program = StackSLProgram.of(i.program)
                val layout = StackSLLayout.layoutAgreement(StackSLCpu.renderStill(i.program, dim = side), t)
                val skill = referee.skill(referee.evaluate(program).fitness)
                rows += "$arm,$target,$seed,${i.generation},${i.renders},%.4f,%.4f,${i.origin.replace(",", " ")}".format(layout, skill)
            }
        }
        out.writeText(rows.joinToString("\n") + "\n")
        println("stacksl watchmaker curves: ${rows.size - 1} rows written to ${out.absolutePath}")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        fun arg(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        arg("curves")?.let { dirs ->
            curves(dirs.split(",").map { File(it) }, File(arg("out") ?: "build/stacksl-watchmaker-curves.csv"))
            return
        }
        arg("figure")?.let { results ->
            figure(File(results), (arg("arms") ?: "baseline,frequent").split(","), arg("seed")?.toLong() ?: 1L,
                arg("targets")?.split(","), arg("side")?.toInt() ?: 128, File(arg("out") ?: "build/stacksl-watchmaker-figure.png"),
                arg("labels")?.split(",") ?: (arg("arms") ?: "baseline,frequent").split(","))
            return
        }
        val budget = arg("budget")?.toInt() ?: 1200
        val targets = (arg("targets") ?: "spot,rings,bands,checker,quadrants").split(",").map { it.trim() }
        val arms = (arg("arms") ?: "baseline,random,wide,fit,watchmaker,all").split(",").map { it.trim() }
        val seeds = (arg("seeds") ?: "1").split(",").map { it.trim().toLong() }
        val out = File(arg("out") ?: "build/stacksl-watchmaker-trial").apply { mkdirs() }
        val results = ArrayList<ArmResult>()
        val rows = ArrayList<Pair<String, Img>>()

        for (seed in seeds) for (name in targets) {
            val t32 = targetAt(name, 32)
            val t128 = targetAt(name, 128)
            val referee = StackSLImageObjective(t32, metric = StackSLMetric.LPIPS, parallel = true)
            fun judge(arm: String, program: StackSLProgram?, renders: Int, seconds: Double,
                      notes: List<String> = emptyList(), credit: Map<String, String> = emptyMap(), usage: String = ""): ArmResult {
                val p = program ?: StackSLProgram.of("uv")
                val r = ArmResult(
                    seed, name, arm, p.text,
                    referee.skill(referee.evaluate(p).fitness),
                    StackSLLayout.layoutAgreement(StackSLCpu.renderStill(p.text, dim = 32), t32.raster),
                    StackSLLayout.layoutAgreement(StackSLCpu.renderStill(p.text, dim = 128), t128.raster),
                    renders, seconds, notes, credit, usage
                )
                println("s$seed %-10s %-11s skill %+.3f  layout %.2f / %.2f  %5d renders %6.1fs  %s".format(
                    name, arm, r.refereeSkill, r.layout32, r.layout128, renders, seconds, r.program))
                results += r
                return r
            }

            if ("baseline" in arms || "random" in arms) {
                val t0 = System.nanoTime()
                val run = evolveStackSLTowards(t32, budget = budget, seed = seed, selection = Selection.LEXICASE)
                val s = (System.nanoTime() - t0) / 1e9
                if ("baseline" in arms) judge("baseline", run.best, run.evolved.evaluations, s * run.evolved.millis / (run.evolved.millis + run.control.millis))
                if ("random" in arms) judge("random", run.control.best?.genome, run.control.evaluations, s * run.control.millis / (run.evolved.millis + run.control.millis))
            }
            for (arm in listOf("wide", "fit", "watchmaker", "revise", "frequent", "frequent-lp", "llm-only", "all").filter { it in arms }) {
                val settings = when (arm) {
                    // `wide` ended in plain LPIPS when these were measured; it now ends in LPIPS_PIXEL, so
                    // `frequent` keeps the old ending explicitly and `frequent-lp` is the current default.
                    "frequent-lp" -> StackSLEvolveSettings.watchmaker()
                    // Almost no blind search: one island, one blind child a generation, a model round every
                    // generation, stopped at the model calls `frequent` averaged rather than at the render budget.
                    "llm-only" -> StackSLEvolveSettings.wide().copy(islands = 1, lambda = 1, crossoverRate = 0.0, immigrantRate = 0.0,
                        watchmakerEvery = 1, watchmakerOnStall = 0)
                    "fit" -> StackSLEvolveSettings.wide().copy(fitEvery = 4)
                    "watchmaker" -> StackSLEvolveSettings.wide().copy(watchmakerEvery = 8, watchmakerOnStall = 4, watchmakerRevise = false)
                    "revise" -> StackSLEvolveSettings.wide().copy(watchmakerEvery = 8, watchmakerOnStall = 4)
                    "frequent" -> StackSLEvolveSettings.watchmaker().let { w ->
                        w.copy(stages = w.stages.dropLast(1) + w.stages.last().copy(metric = StackSLMetric.LPIPS))
                    }
                    "all" -> StackSLEvolveSettings.wide().copy(fitEvery = 4, watchmakerEvery = 4, watchmakerOnStall = 2)
                    else -> StackSLEvolveSettings.wide()
                }
                val e = StackSLEvolution({ d -> targetAt(name, d) }, settings, seed)
                if (arm in setOf("watchmaker", "revise", "frequent", "frequent-lp", "llm-only", "all")) {
                    // Every round's prompt, picture and raw reply, numbered, so the exchange can be read afterwards.
                    val model = StackSLWatchmaker()
                    var round = 0
                    e.watchmaker = afield.lang.stacksl.evolution.StackSLProposer { request ->
                        val n = ++round
                        File(out, "s$seed-$name-$arm-round$n.png").writeBytes(request.sheetPng)
                        val preview = request.preview
                        val logged = if (preview == null) request else request.copy(preview = { programs ->
                            preview(programs).also { shown ->
                                File(out, "s$seed-$name-$arm-round$n-drafts.png").writeBytes(shown.sheetPng)
                                File(out, "s$seed-$name-$arm-round$n-drafts.txt").writeText(
                                    programs.zip(shown.status).joinToString("\n") { (p, st) -> "$st\n    $p" })
                            }
                        })
                        model.propose(logged).also { reply ->
                            File(out, "s$seed-$name-$arm-round$n.txt").writeText(
                                afield.lang.stacksl.evolution.StackSLWatchmakerPrompt.round(request) + "\n\n---- reply (${reply.usage}) ----\n" + reply.raw
                            )
                        }
                    }
                }
                val t0 = System.nanoTime()
                if (arm == "llm-only") {
                    while (e.renders < budget && e.usage.calls < LLM_ONLY_CALLS) e.step(1)
                } else e.runTo(budget)
                val s = (System.nanoTime() - t0) / 1e9
                File(out, "s$seed-$name-$arm.json").writeText(afield.lang.stacksl.evolution.StackSLEvolutionSnapshot.toJson(e.snapshot("$name $arm")))
                judge(arm, e.best?.genome, e.renders, s, e.notes.filter { "watchmaker" in it || "fit" in it }.takeLast(40),
                    e.credit.mapValues { (_, c) -> "${c.offered}/${c.survived}/${c.leads}" }, e.usage.takeIf { it.calls > 0 }?.toString() ?: "")
                println(e.summary())
            }

            // One row per target: the target, then each arm's best at 128 px.
            val mine = results.filter { it.target == name && it.seed == seed }
            rows += "s$seed $name" to t128.raster.toImg()
            mine.forEach { r -> rows += "${r.arm} %.2f".format(r.layout128) to StackSLCpu.renderStill(r.program, dim = 128).toImg() }
        }

        val columns = 1 + results.map { it.arm }.distinct().size
        montage(rows, columns = columns, cellSide = 136).savePng(File(out, "trial.png").path)
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(File(out, "trial.json"), results)
        println()
        println("arm          mean skill  mean layout128  runs with layout128 > 0.5")
        results.groupBy { it.arm }.forEach { (arm, rs) ->
            println("%-12s %10.3f %15.3f %8d of %d".format(arm, rs.map { it.refereeSkill }.average(), rs.map { it.layout128 }.average(),
                rs.count { it.layout128 > 0.5 }, rs.size))
        }
        println("stacksl watchmaker trial written to ${out.absolutePath}")
    }
}

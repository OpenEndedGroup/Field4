package afield.examples.stacksl

import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.Img
import afield.stdlib.Selection
import afield.stdlib.StackSLEvolution
import afield.stdlib.StackSLEvolveSettings
import afield.stdlib.StackSLImageObjective
import afield.stdlib.StackSLLayout
import afield.stdlib.StackSLMetric
import afield.stdlib.StackSLProgram
import afield.stdlib.StackSLTargets
import afield.stdlib.StackSLWatchmaker
import afield.stdlib.evolveStackSLTowards
import afield.stdlib.montage
import afield.stdlib.savePng
import afield.stdlib.toImg
import afield.stdlib.upscaleNearest
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

    @JvmStatic
    fun main(args: Array<String>) {
        fun arg(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val budget = arg("budget")?.toInt() ?: 1200
        val targets = (arg("targets") ?: "spot,rings,bands,checker,quadrants").split(",").map { it.trim() }
        val arms = (arg("arms") ?: "baseline,random,wide,fit,watchmaker,all").split(",").map { it.trim() }
        val seeds = (arg("seeds") ?: "1").split(",").map { it.trim().toLong() }
        val out = File(arg("out") ?: "build/stacksl-watchmaker-trial").apply { mkdirs() }
        val results = ArrayList<ArmResult>()
        val rows = ArrayList<Pair<String, Img>>()

        for (seed in seeds) for (name in targets) {
            val t32 = StackSLTargets.byName(name, 32) ?: error("no target '$name'")
            val t128 = StackSLTargets.byName(name, 128)!!
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
            for (arm in listOf("wide", "fit", "watchmaker", "revise", "frequent", "all").filter { it in arms }) {
                val settings = when (arm) {
                    "fit" -> StackSLEvolveSettings.wide().copy(fitEvery = 4)
                    "watchmaker" -> StackSLEvolveSettings.wide().copy(watchmakerEvery = 8, watchmakerOnStall = 4, watchmakerRevise = false)
                    "revise" -> StackSLEvolveSettings.wide().copy(watchmakerEvery = 8, watchmakerOnStall = 4)
                    "frequent" -> StackSLEvolveSettings.wide().copy(watchmakerEvery = 4, watchmakerOnStall = 2)
                    "all" -> StackSLEvolveSettings.wide().copy(fitEvery = 4, watchmakerEvery = 4, watchmakerOnStall = 2)
                    else -> StackSLEvolveSettings.wide()
                }
                val e = StackSLEvolution(t32, settings, seed)
                if (arm in setOf("watchmaker", "revise", "frequent", "all")) {
                    // Every round's prompt, picture and raw reply, numbered, so the exchange can be read afterwards.
                    val model = StackSLWatchmaker()
                    var round = 0
                    e.watchmaker = afield.stdlib.StackSLProposer { request ->
                        val n = ++round
                        File(out, "s$seed-$name-$arm-round$n.png").writeBytes(request.sheetPng)
                        model.propose(request).also { reply ->
                            File(out, "s$seed-$name-$arm-round$n.txt").writeText(
                                afield.stdlib.StackSLWatchmakerPrompt.round(request) + "\n\n---- reply (${reply.usage}) ----\n" + reply.raw
                            )
                        }
                    }
                }
                val t0 = System.nanoTime()
                e.runTo(budget)
                val s = (System.nanoTime() - t0) / 1e9
                File(out, "s$seed-$name-$arm.json").writeText(afield.stdlib.StackSLEvolutionSnapshot.toJson(e.snapshot("$name $arm")))
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

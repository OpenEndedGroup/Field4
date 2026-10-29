@file:afield.runtime.completion.CompletionCategory("Search")

package afield.lang.stacksl.evolution

import afield.data.collections.invoke
import afield.data.collections.times
import afield.data.graph.rules
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.properties.saved
import afield.document.workspace.file
import afield.geometry.mesh.gradient
import afield.graphics.color.rgb
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.max
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.round
import afield.math.autodiff.times
import afield.math.functions.evolve
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.rgb
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.open
import afield.runtime.execution.end
import afield.runtime.lifecycle.once
import afield.simulation.automata.history
import afield.study.ui.numbers

import afield.app.commands.action
import afield.audio.synth.*
import afield.data.collections.get
import afield.data.collections.s
import afield.geometry.mesh.summary
import afield.graphics.render.label
import afield.graphics.text.toImg
import afield.graphics.vulkan.buffers.add
import afield.math.linalg.f
import afield.math.linalg.i
import afield.math.linalg.l
import afield.math.linalg.x
import afield.media.image.toImg
import afield.media.video.image
import afield.runtime.execution.start
import afield.simulation.agents.step
import afield.simulation.agents.toImg
import afield.simulation.automata.step
import afield.simulation.automata.toImg
import afield.study.source.BoxStudies
import afield.document.graph.Box
import afield.ai.tools.BranchConflict
import afield.ai.tools.ToolEffect
import afield.ai.tools.agentExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The AI4 chat surface for a [StackSLEvolution]: [stackSLEvolveStudio] registers the `evolve_*`
 * tools on a box and ties the population to the conversation's branches.
 *
 * [stackSLEvolution] keeps one search on the box, so re-running the box keeps its population.
 */

/**
 * The StackSL search as a world the AI4 chat works in, the way `ai4_paint_studio` makes a canvas
 * one: the chat runs generations, looks at the leaders, proposes programs of its own, asks the
 * cheap model for a round, takes a gradient step on a literal, and breeds with populations it left
 * behind.
 *
 * The state that belongs to the conversation is the **population**: [branchState] saves a
 * [StackSLEvolutionSnapshot] before and after every chat turn, so rewinding the chat to a request
 * puts the population, its history and its scores back as they were before it, and switching branch
 * puts back that branch's. Every snapshot saved is also filed in a [StackSLPopulationArchive] under
 * the box's study directory, which nothing deletes, so `evolve_crossbreed` can bring in the
 * survivors of a branch the conversation is no longer on.
 *
 * ```kotlin
 * val evolution = stackSLEvolution(StackSLTargets.spot(32))
 * stackSLEvolveStudio(evolution)
 * ```
 */
fun Box.stackSLEvolveStudio(evolution: StackSLEvolution, id: String = "evolve") {
    val box = this
    val archive = StackSLPopulationArchive(File(BoxStudies.directory(box), "populations"))
    fun show() = runCatching {
        val sheet = synchronized(evolution) { evolution.contactSheet(top = 7, side = 112) }
        BoxStudies.show(box, "evolve",
            "<img style='max-width:100%;image-rendering:pixelated' src='${sheet.toPngDataUrl()}'>" +
                "<pre style='font-size:11px;white-space:pre-wrap'>${escape(synchronized(evolution) { evolution.summary() })}</pre>")
    }
    suspend fun <T> work(block: () -> T): T = withContext(Dispatchers.Default) { synchronized(evolution) { block() } }

    agentExtension(id) {
        tool("evolve_look") {
            describe("Look at the search: the target and the best programs rendered, each with its skill (0 = a flat fill of the " +
                "target's mean colour, 1 = exact), its region scores and where it came from.")
            effects(ToolEffect.CAPTURE_IMAGE)
            val top by param<Int>("how many leaders", default = 6)
            action {
                val (sheet, table) = work { evolution.contactSheet(top = top().coerceIn(1, 12), side = 112) to leaders(evolution, top()) }
                image(sheet.toPngDataUrl(), table)
            }
        }

        tool("evolve_step") {
            describe("Run the search for some generations: blind mutation and crossover, plus whatever the settings switch on " +
                "(the watchmaker, gradient steps). Returns the leaders afterwards, as evolve_look does.")
            effects(ToolEffect.CONTROL_RUNTIME)
            val generations by param<Int>("generations to run, 1..200", default = 10)
            action {
                val (sheet, table) = work {
                    evolution.step(generations().coerceIn(1, 200))
                    evolution.contactSheet(top = 6, side = 112) to (evolution.notes.takeLast(4).joinToString("\n") + "\n\n" + leaders(evolution, 6))
                }
                show()
                image(sheet.toPngDataUrl(), table)
            }
        }

        tool("evolve_propose") {
            describe("Put programs of your own into the population. Each is checked, rendered and scored like any candidate, and kept " +
                "only if it scores well enough. One line of StackSL per string; definitions allowed, texture words not.")
            effects(ToolEffect.CONTROL_RUNTIME)
            val programs by param<List<String>>("StackSL programs")
            action {
                val out = work { evolution.inject(programs(), "chat", island = -1) }
                show()
                outcomes(out)
            }
        }

        tool("evolve_watchmaker") {
            describe("One round of the cheap model: it is shown the target and the top programs rendered, and proposes edits. " +
                "Returns what it proposed and what each scored.")
            effects(ToolEffect.CONTROL_RUNTIME, ToolEffect.NETWORK)
            val proposals by param<Int>("programs to ask for, 1..16", default = 8)
            val parents by param<Int>("leaders to show it, 1..8", default = 4)
            action {
                val out = work {
                    if (evolution.watchmaker == null) evolution.watchmaker = StackSLWatchmaker()
                    evolution.askWatchmaker(proposals().coerceIn(1, 16), parents().coerceIn(1, 8))
                }
                show()
                outcomes(out) + "\n" + work { evolution.notes.last() }
            }
        }

        tool("evolve_fit") {
            describe("A round of Adam on a program's own numbers: the exact gradient of its render's pixel error toward the target, " +
                "then re-scored by the search's metric and offered to the population. Moves numbers, never words.")
            effects(ToolEffect.CONTROL_RUNTIME)
            val rank by param<Int>("which leader, 1 = the best", default = 1)
            val literals by param<List<Int>>("token indices (0-based) of the numbers that may move; empty = all", default = emptyList<Int>())
            val iterations by param<Int>("Adam steps", default = 30)
            action {
                work {
                    val program = evolution.ranked.getOrNull(rank() - 1)?.genome ?: return@work "there is no leader #${rank()}"
                    val o = evolution.fit(program, literals().takeIf { it.isNotEmpty() }, iterations().coerceIn(1, 200), origin = "chat:fit")
                    if (o.fitted == null) "no fit: ${o.problem}" else
                        "fitted: ${o.fitted}\npixel loss %.4f -> %.4f; skill %.3f -> %s".format(
                            o.lossBefore, o.lossAfter, o.skillBefore, o.skillAfter?.let { "%.3f".format(it) } ?: (o.problem ?: "?"))
                }.also { show() }
            }
        }

        tool("evolve_configure") {
            describe("Change how the search runs. Leave a value at its default to keep it. metric: LPIPS, PIXEL or LPIPS_PIXEL; " +
                "dim: the fitness resolution (16, 32 or 64); grid: LPIPS regions per side scored as cases (0, 2 or 3). " +
                "watchmakerEvery / fitEvery: generations between automatic watchmaker rounds / gradient steps (0 = off).")
            effects(ToolEffect.CONTROL_RUNTIME)
            val metric by param<String>("LPIPS, PIXEL or LPIPS_PIXEL", default = "")
            val dim by param<Int>("fitness resolution", default = 0)
            val grid by param<Int>("LPIPS region grid", default = -1)
            val mu by param<Int>("population per island", default = 0)
            val lambda by param<Int>("children per island per generation", default = 0)
            val islands by param<Int>("islands", default = 0)
            val maxTokens by param<Int>("longest program, in words", default = 0)
            val watchmakerEvery by param<Int>("", default = -1)
            val watchmakerOnStall by param<Int>("a watchmaker round after this many generations without improvement (0 = off)", default = -1)
            val fitEvery by param<Int>("", default = -1)
            action {
                work {
                    evolution.configure { s ->
                        val st = s.stageAt(evolution.generation)
                        val stage = st.copy(
                            generations = Int.MAX_VALUE,
                            metric = metric().takeIf { it.isNotBlank() }?.let { StackSLMetric.valueOf(it.trim().uppercase()) } ?: st.metric,
                            dim = dim().takeIf { it > 0 } ?: st.dim,
                            lpipsGrid = grid().takeIf { it >= 0 } ?: st.lpipsGrid
                        )
                        // A change from here on: the schedule so far stays as it ran, and this stage runs from now.
                        val past = s.stages.fold(0 to emptyList<StackSLStage>()) { (start, acc), x ->
                            if (start >= evolution.generation) start to acc
                            else (start + x.generations) to (acc + x.copy(generations = min(x.generations, evolution.generation - start)))
                        }.second
                        s.copy(
                            stages = if (stage == st) s.stages else past + stage,
                            mu = mu().takeIf { it > 0 } ?: s.mu,
                            lambda = lambda().takeIf { it > 0 } ?: s.lambda,
                            islands = islands().takeIf { it > 0 } ?: s.islands,
                            maxTokens = maxTokens().takeIf { it > 0 } ?: s.maxTokens,
                            watchmakerEvery = watchmakerEvery().takeIf { it >= 0 } ?: s.watchmakerEvery,
                            watchmakerOnStall = watchmakerOnStall().takeIf { it >= 0 } ?: s.watchmakerOnStall,
                            fitEvery = fitEvery().takeIf { it >= 0 } ?: s.fitEvery
                        )
                    }
                    if ((evolution.settings.watchmakerEvery > 0 || evolution.settings.watchmakerOnStall > 0) && evolution.watchmaker == null) {
                        evolution.watchmaker = StackSLWatchmaker()
                    }
                    settingsLine(evolution)
                }
            }
        }

        tool("evolve_states") {
            describe("The saved populations evolve_crossbreed can breed with: every state this conversation has been in, on any " +
                "branch, by id, with its generation and best program.")
            action {
                archive.list().takeLast(20).joinToString("\n") { (sid, s) ->
                    val b = s.best
                    "$sid  ${s.label}  gen ${s.generation}  ${s.renders} renders" + (b?.let { "  best skill %.3f: ${it.program}".format(it.skill) } ?: "")
                }.ifBlank { "No saved populations yet: one is saved at the end of every chat turn." }
            }
        }

        tool("evolve_crossbreed") {
            describe("Breed with a saved population (an id from evolve_states): its best programs come in as they are, and as " +
                "one-point recombinants with this population's leaders.")
            effects(ToolEffect.CONTROL_RUNTIME)
            val state by param<String>("a saved population's id, e.g. p004")
            val count by param<Int>("how many of its programs, and how many recombinants", default = 6)
            action {
                val other = archive.get(state().trim()) ?: return@action toolError("no saved population '${state()}'; see evolve_states")
                val out = work { evolution.crossbreed(other, count().coerceIn(1, 24)) }
                show()
                outcomes(out)
            }
        }

        tool("evolve_render") {
            describe("Render any StackSL program, without adding it to the population, to see what it draws.")
            effects(ToolEffect.CAPTURE_IMAGE)
            val program by param<String>("a StackSL program")
            val side by param<Int>("pixels", default = 128)
            action {
                val problem = StackSLProposals.check(program(), 96)
                if (problem != null) return@action toolError(problem)
                val img = work { afield.lang.stacksl.cpu.StackSLCpu.renderStill(StackSLProposals.program(program()).text, dim = side().coerceIn(16, 256), time = evolution.time).toImg() }
                image(img.toPngDataUrl(), program())
            }
        }

        tool("evolve_guide") {
            describe("The StackSL words guide: the section(s) mentioning a word, or the list of sections when the word is blank.")
            val word by param<String>("a word to look up, e.g. smoothstep", default = "")
            action { StackSLWatchmakerPrompt.lookup(word()) }
        }

        context("evolution") {
            synchronized(evolution) {
                val b = evolution.best
                buildString {
                    append("The Evolve box holds a StackSL search toward the target '${evolution.targetName}'. ")
                    append("Generation ${evolution.generation}, ${evolution.renders} programs rendered. ${settingsLine(evolution)}. ")
                    if (b != null) append("Best skill %.3f: `${b.genome.text}` (from ${b.origin}). ".format(evolution.skill(b.fitness)))
                    if (evolution.usage.calls > 0) append("Watchmaker so far: ${evolution.usage}. ")
                    val recent = evolution.notes.takeLast(3)
                    if (recent.isNotEmpty()) append("Recent: " + recent.joinToString("; ") + ".")
                }
            }
        }

        context("evolve-rules", changesOften = false) {
            """
            |You are steering a genetic-programming search over StackSL programs (a stack language run once per pixel; the top of
            |the stack is the colour, rgb clamped to 0..1; uv is 0..1 with y UP; p = uv - 0.5). It breeds programs whose picture
            |looks like a target. Skill 0 is a flat fill of the target's mean colour, 1 is exact; region scores are the same
            |measure over regions read row by row from the top left. Look before you act (evolve_look). You can run generations
            |(evolve_step), add programs of your own (evolve_propose), ask the cheap model for a round of edits (evolve_watchmaker),
            |take a gradient step on a leader's numbers (evolve_fit), change the metric, resolution and population
            |(evolve_configure), and breed with populations from earlier states or other branches (evolve_states,
            |evolve_crossbreed). Programs are one line; `: name ... ;` definitions are allowed if each called one consumes one value
            |and leaves one (open with >v, not =v); no '?' reads, no sample/prev/gather. Look words up
            |with evolve_guide. The population belongs to the conversation: rewinding the chat rewinds it, so be bold.
            """.trimMargin()
        }

        command("population", "the leaders, their scores and origins") { "```\n" + synchronized(evolution) { leaders(evolution, 10) + "\n\n" + evolution.summary() } + "\n```" }
        command("sheet", "the target and the leaders") { "![leaders](${synchronized(evolution) { evolution.contactSheet(top = 7, side = 112) }.toPngDataUrl()})" }

        branchState("population",
            save = { f ->
                val snapshot = synchronized(evolution) { evolution.snapshot() }
                // Without the time it was saved: Field compares this file with the state as it is now to
                // tell whether anything outside the conversation changed it, and a timestamp always would.
                snapshot.copy(savedAt = 0L).write(f)
                val last = archive.list().lastOrNull()?.second
                if (last == null || last.generation != snapshot.generation || last.renders != snapshot.renders || last.best != snapshot.best) {
                    archive.add(snapshot.copy(label = "gen ${snapshot.generation}"))
                }
            },
            restore = { f ->
                synchronized(evolution) { if (f == null) evolution.reset() else evolution.restore(StackSLEvolutionSnapshot.read(f)) }
                show()
            },
            onConflict = BranchConflict.RESTORE, resumeOnOpen = true)
    }
    show()
}

/**
 * The search kept on [this] box, so re-running the box keeps the population: made from [target]
 * and [settings] the first time, and the same object after that while the target's name is the same.
 */
fun Box.stackSLEvolution(target: StackSLTarget, settings: StackSLEvolveSettings = StackSLEvolveSettings.wide(), seed: Long = 1L): StackSLEvolution {
    val key = "stacksl.evolution"
    val existing = properties[key] as? StackSLEvolution
    if (existing != null && existing.targetName == target.name) return existing
    return StackSLEvolution(target, settings, seed).also { properties[key] = it }
}

private fun leaders(e: StackSLEvolution, top: Int): String = buildString {
    val labels = e.request(e.ranked.take(1)).caseLabels
    appendLine("rank  skill  origin            program")
    e.ranked.take(top.coerceIn(1, 12)).forEachIndexed { i, ind ->
        appendLine("#%-3d %6.3f  %-16s  %s".format(i + 1, e.skill(ind.fitness), ind.origin, ind.genome.text))
        if (ind.evaluation.cases.isNotEmpty() && labels.size == ind.evaluation.cases.size) {
            appendLine("      regions " + labels.zip(ind.evaluation.cases.toList()).joinToString(" ") { (l, c) -> "$l %.2f".format(c) })
        }
    }
}

private fun outcomes(out: List<StackSLProposalOutcome>): String = out.joinToString("\n") { o ->
    when {
        o.problem != null -> "refused  ${o.program}  — ${o.problem}"
        o.rank != null -> "kept #${o.rank}  skill %.3f  ${o.program}".format(o.skill ?: 0.0)
        else -> "dropped  skill %s  ${o.program}".format(o.skill?.let { "%.3f".format(it) } ?: "?")
    }
}

private fun settingsLine(e: StackSLEvolution): String {
    val s = e.settings
    return "Stage ${e.stage.describe()}; ${s.islands} island(s) of ${s.mu}, ${s.lambda} children each per generation, " +
        "programs up to ${s.maxTokens} words; watchmaker every ${s.watchmakerEvery} / on stall ${s.watchmakerOnStall}; fit every ${s.fitEvery}"
}

private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun min(a: Int, b: Int) = if (a < b) a else b

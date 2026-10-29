@file:afield.runtime.completion.CompletionCategory("Search")

package afield.lang.stacksl.evolution

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.data.graph.rules
import afield.document.context.Why
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.repaired
import afield.graphics.color.rgb
import afield.graphics.render.rangeTo
import afield.io.osc.main
import afield.math.autodiff.abs
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.floor
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.sin
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.functions.sin
import afield.math.linalg.d
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.rgb
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.open
import afield.media.image.threshold
import afield.runtime.execution.end
import afield.runtime.lifecycle.Once
import afield.runtime.lifecycle.cached
import afield.runtime.lifecycle.once
import afield.simulation.automata.main
import afield.study.ui.numbers

import afield.audio.synth.*
import afield.data.collections.m
import afield.data.collections.r
import afield.geometry.mesh.contains
import afield.graphics.color.rgba
import afield.graphics.shader.stackAutoShaderReport
import afield.math.autodiff.maxOf
import afield.math.autodiff.round
import afield.math.linalg.f
import afield.math.linalg.i
import afield.math.linalg.l
import afield.math.linalg.rgba
import afield.media.image.luma
import afield.media.image.split
import afield.media.video.image
import afield.runtime.execution.send
import afield.ai.models.Ai4ModelDefaults
import afield.ai.models.LlmBridge
import afield.ai.models.LlmReasoningEffort
import afield.ai.diagnostics.SilentLog
import afield.ai.models.createLlmBridge
import afield.lang.stacksl.StackSLCompiler
import afield.lang.stacksl.StackSLSeverity
import afield.lang.stacksl.cpu.StackSLCpu
import java.util.Base64
import kotlin.math.roundToInt

/**
 * A language model as a StackSL mutation operator: [StackSLWatchmaker] shows a model the target
 * and the leaders' renders and turns its reply into candidate programs.
 *
 * The prompt is built by [StackSLWatchmakerPrompt], replies are parsed and checked by
 * [StackSLProposals], and [StackSLModelUsage] sums model calls, tokens and prompt-cache reads.
 */

/**
 * The watchmaker: a language model inside a StackSL search, proposing edits to the leaders.
 *
 * Genetic programming mutates blind. A point mutation that swaps `sin` for `fbm` knows nothing
 * about what either word draws, and a disc that would take `p length 0.28 threshold` has to be
 * stumbled on token by token. A model that has read the words guide and can see the renders can
 * propose `p length 0.28 0.3 smoothstep` straight away. So every so often [StackSLEvolution] shows
 * one the target and its current leaders and puts what comes back into the population. The edits
 * pay the same render budget as any other candidate, and survive or not by the same metric.
 *
 * ### What the model is shown
 *
 *  - **Once, cached:** the job, the rules of a straight-line program, the colour convention and the
 *    whole StackSL words guide ([StackSLWatchmakerPrompt.stable]). The text is the same on every call,
 *    which makes it a reusable cached prefix. Each round starts a fresh thread with it as the
 *    developer message, so later calls read it from the provider's prompt cache ([StackSLModelUsage]
 *    reports how much was).
 *  - **Each round:** one small image of the target and the leaders at 64 px, numbered; each leader's
 *    program, its score and its per-region scores; the target's colours as numbers; and what
 *    happened to the last round's proposals. That is a few hundred words and one picture.
 *  - **Never:** [StackSLLayout.layoutAgreement], the held-out check. A watchmaker shown the referee's
 *    score is being trained by the referee. Nor the target's name: the built-in ones (`spot`,
 *    `rings`) say what to draw, and the first live round, told `spot`, drew a disc at once.
 *
 * ### What comes back
 *
 * One line per program, `parent | program | why`, `parent` 0 for a program written from scratch.
 * Each is checked ([StackSLProposals.check]) before it costs a render. A malformed line is dropped
 * and reported, never repaired into something the model did not write.
 */
fun interface StackSLProposer {
    /** Answer one round with candidate programs, and what the round cost. */
    fun propose(request: StackSLProposalRequest): StackSLProposalReply
}

/** What one watchmaker round is shown. */
data class StackSLProposalRequest(
    /**
     * The target's own name, which the prompt never includes: `spot` or `rings` would say what to
     * draw. It is here so that a person reading a request knows which target it is for.
     */
    val targetName: String,
    /** The target's colours written as numbers, which the prompt gives in place of its name. */
    val targetColours: String,
    /** The stage's metric in words — pixel, LPIPS, or both, and over what grid. */
    val metric: String,
    /**
     * A name for each entry of [StackSLParentView.cases], in the same order: one per region of the
     * LPIPS and pixel grids. Empty when the stage scores whole pictures.
     */
    val caseLabels: List<String>,
    /** The generation this round belongs to. */
    val generation: Int,
    /** Generations since the best program improved, so a model can tell a stalled search from a moving one. */
    val stalled: Int,
    /** The leaders, best first, numbered as they are labelled on the sheet. */
    val parents: List<StackSLParentView>,
    /** How many programs this round asks for. */
    val proposals: Int,
    /** The longest a proposed program may be, in words; a longer one is refused unrendered. */
    val maxTokens: Int,
    /** What became of the previous round's proposals, which the prompt reports back. */
    val lastRound: List<StackSLProposalOutcome>,
    /** The target and the parents, numbered as in [parents]: [StackSLEvolution.thumbnailSheet]. */
    val sheetPng: ByteArray,
    /**
     * What a list of programs draws, for the model to check its own proposals against before they
     * are scored: [StackSLProposer]s that do a second pass call it. `null` for no second pass.
     */
    val preview: ((List<String>) -> StackSLPreview)? = null
) {
    /** [sheetPng] as a `data:` URL, which is the form the model is sent it in. */
    val sheetDataUrl: String get() = "data:image/png;base64," + Base64.getEncoder().encodeToString(sheetPng)
}

/** What some programs draw: the target and each program at 64 px, `P1`, `P2`..., and one status line per program. */
class StackSLPreview(val sheetPng: ByteArray, val status: List<String>) {
    /** [sheetPng] as a `data:` URL, which is the form the model is sent it in. */
    val sheetDataUrl: String get() = "data:image/png;base64," + Base64.getEncoder().encodeToString(sheetPng)
}

/** One leader as the watchmaker sees it. */
data class StackSLParentView(
    /** Its place among the leaders, from 1, matching its label on the sheet. */
    val rank: Int,
    /** The program itself, as the model is asked to edit it. */
    val program: String,
    /** The objective's own score, on whatever scale the stage's metric uses. */
    val fitness: Double,
    /**
     * [fitness] rescaled so that 0 is a flat fill of the target's mean colour and 1 is exact. This
     * is the number the prompt leads with, because a raw fitness means nothing across stages.
     */
    val skill: Double,
    /** Per-region scores, named in order by [StackSLProposalRequest.caseLabels]. */
    val cases: List<Double>,
    /** The operator that produced it — a blind mutation, a crossover, or an earlier watchmaker round. */
    val origin: String
)

/** One proposed program. [parent] is a 1-based rank, `null` for one written from scratch. */
data class StackSLProposal(val parent: Int?, val program: String, val why: String = "")

/** What a round returned, and what it cost. */
data class StackSLProposalReply(
    /** The programs the round produced, in the order they were written. Empty when nothing parsed. */
    val proposals: List<StackSLProposal>,
    /** What the round cost: calls, tokens and time. */
    val usage: StackSLModelUsage = StackSLModelUsage(),
    /** Lines that could not be read, or a failure, said plainly. */
    val problem: String? = null,
    /** The reply verbatim, so a round that parsed badly can still be read. */
    val raw: String = ""
)

/** What happened to one offered program. [rank] is its place overall when it stayed, else `null`. */
data class StackSLProposalOutcome(
    /** The program as it was offered, normalised. */
    val program: String = "",
    /** The label it was offered under: `watchmaker:new` for one written from scratch, `watchmaker:edit` for an edit. */
    val origin: String = "",
    /** What it scored, or `null` if it never got a score — refused, or already in the cache. */
    val fitness: Double? = null,
    /** Its [fitness] rescaled as [StackSLParentView.skill] is, or `null` when there is no fitness. */
    val skill: Double? = null,
    /** Its place among all programs when it was kept, counting from 1; `null` when it was not. */
    val rank: Int? = null,
    /** Why it was refused, or `null` if it entered the population. */
    val problem: String? = null,
    /** The model's own one-line reason for proposing it, carried through unchanged. */
    val why: String = ""
)

/** Model calls and tokens, summed. */
data class StackSLModelUsage(
    /** Which model answered. [plus] keeps the newer name when it is not blank. */
    val model: String = "",
    /** How many times the model was called, a failed call included. */
    val calls: Int = 0,
    /** Input tokens across those calls, [cachedTokens] among them. */
    val inputTokens: Long = 0,
    /** Input tokens the provider served from its prompt cache; see [uncachedTokens]. */
    val cachedTokens: Long = 0,
    /** Tokens the model wrote. */
    val outputTokens: Long = 0,
    /** Milliseconds spent waiting on the model. */
    val millis: Long = 0
) {
    /** The two tallies added, field by field; see [model] for the one field that is not summed. */
    operator fun plus(o: StackSLModelUsage) = StackSLModelUsage(
        o.model.ifBlank { model }, calls + o.calls, inputTokens + o.inputTokens, cachedTokens + o.cachedTokens,
        outputTokens + o.outputTokens, millis + o.millis
    )

    /** Input tokens not served from the cache: the part that is paid for in full. */
    val uncachedTokens: Long get() = inputTokens - cachedTokens

    /** The model, the calls, the token split and the time, as the run's notes print it. */
    override fun toString(): String =
        "$model: $calls call(s), $inputTokens input ($cachedTokens cached, $uncachedTokens not), $outputTokens output, %.1f s".format(millis / 1000.0)
}

/**
 * The watchmaker on Field's configured AI provider, at the fast model unless told otherwise
 * (`AFIELD_AI_WATCHMAKER_MODEL`).
 */
class StackSLWatchmaker(
    /** Which model to ask; `AFIELD_AI_WATCHMAKER_MODEL` overrides the configured fast model. */
    val model: String = Ai4ModelDefaults.envOrDefault("AFIELD_AI_WATCHMAKER_MODEL", Ai4ModelDefaults.fastModel()),
    /** How much the model is asked to think per round. Low, because a round is one small picture and a few hundred words. */
    val reasoning: LlmReasoningEffort = LlmReasoningEffort.LOW,
    /** The ceiling on one reply, which is a few dozen one-line programs. */
    val maxOutputTokens: Long = 6000
) : StackSLProposer {

    private val bridge: LlmBridge by lazy { createLlmBridge(model, SilentLog, reasoning) }

    /**
     * Ask the model for one round, and the second pass when
     * [StackSLProposalRequest.preview] is given. A call that throws comes back as a reply with no
     * proposals and the failure in [StackSLProposalReply.problem], so a round the provider refused
     * costs the search a generation rather than ending it.
     */
    override fun propose(request: StackSLProposalRequest): StackSLProposalReply {
        val t0 = System.currentTimeMillis()
        val state = runCatching {
            bridge.startThread(fileKey = "stacksl:watchmaker", fileText = "", instructions = StackSLWatchmakerPrompt.stable)
        }.getOrElse { e ->
            return StackSLProposalReply(emptyList(), StackSLModelUsage(model, 1, millis = System.currentTimeMillis() - t0), "the model call failed: ${e.message ?: e}")
        }
        var usage = StackSLModelUsage(model)
        fun ask(prompt: String, image: String): String? {
            val started = System.currentTimeMillis()
            val sent = runCatching {
                bridge.send(state, prompt, maxOutputTokens = maxOutputTokens, maxToolRounds = 0, imageDataUrls = listOf(image))
            }.getOrNull()
            usage += StackSLModelUsage(model, 1, sent?.inputTokens ?: 0, sent?.cacheReadTokens ?: 0, sent?.outputTokens ?: 0,
                System.currentTimeMillis() - started)
            return sent?.text
        }
        val first = ask(StackSLWatchmakerPrompt.round(request), request.sheetDataUrl)
            ?: return StackSLProposalReply(emptyList(), usage, "the model call failed")
        val (drafts, unread) = StackSLWatchmakerPrompt.parse(first, request.parents.size)
        var proposals = drafts.take(request.proposals)
        var raw = first
        // The second pass: the model sees what its programs draw, in the same thread (so the guide is
        // still a cached prefix), and corrects them. Its first drafts are offered too, unless the
        // second pass repeats them.
        val preview = request.preview
        if (preview != null && proposals.isNotEmpty()) {
            val shown = preview(proposals.map { it.program })
            val second = ask(StackSLWatchmakerPrompt.revision(request, proposals, shown), shown.sheetDataUrl)
            if (second != null) {
                val (revised, _) = StackSLWatchmakerPrompt.parse(second, request.parents.size)
                proposals = (revised.take(request.proposals) + proposals).distinctBy { StackSLProposals.program(it.program).text }
                raw = "$first\n---- revision ----\n$second"
            }
        }
        val problem = if (unread.isEmpty()) null else "${unread.size} line(s) not in the form `parent | program | why`"
        return StackSLProposalReply(proposals, usage, problem, raw)
    }
}

/** The watchmaker's prompt: [stable] once and cached, [round] per call, and [parse] for the reply. */
object StackSLWatchmakerPrompt {

    /** The words guide, less its generated header and the section on word references. */
    private val guide: String by lazy {
        val text = StackSLWatchmakerPrompt::class.java.getResourceAsStream("/afield/stacksl/study/stacksl_words.md")
            ?.bufferedReader()?.readText().orEmpty()
        val sections = text.split(Regex("(?m)^(?=## )"))
        sections.drop(1).filterNot { it.startsWith("## Word references") }.joinToString("").trim()
    }

    /** The guide's section(s) mentioning [word] in backticks, or the section titles when [word] is blank. */
    fun lookup(word: String): String {
        val sections = guide.split(Regex("(?m)^(?=## )"))
        val w = word.trim()
        if (w.isEmpty()) return "Sections: " + sections.map { it.lineSequence().first().removePrefix("## ") }.joinToString(", ")
        val hits = sections.filter { it.contains("`$w`") || it.lineSequence().first().contains(w, ignoreCase = true) }
        return hits.joinToString("\n").ifBlank { "No section mentions `$w`." }.take(6000)
    }

    /** Everything that is the same on every call. */
    val stable: String by lazy {
        """
        |You are the watchmaker in a genetic-programming search over StackSL programs. StackSL is a stack language whose
        |program runs once per pixel and leaves a colour. The search breeds programs whose picture should look like a target
        |picture. Each round you are shown the target and the current best programs, rendered, with their scores, and you
        |propose new programs: careful edits of the good ones, and a few written fresh from what you see in the target.
        |Your programs are rendered and scored exactly like the search's own, and kept only if they score well.
        |
        |The frame and the picture:
        |- uv runs 0..1 across the frame, x to the right and y UP: uv.y is 0 at the bottom of the picture and 1 at the top.
        |  p is uv - 0.5, the same point centred on zero. Time is fixed (input.w = 1.75), so the picture is a still.
        |- The top of the stack at the end is the colour; its rgb is clamped to 0..1.
        |- The pictures you are shown are the raw rgb values, not sRGB-encoded: 0.9 is drawn as 230 of 255, 0.5 as 128.
        |  The target's colours are given to you as numbers too; you can write one as a vector literal, [0.9,0.1,0.1].
        |- A flat or nearly flat picture is refused and scores nothing.
        |
        |The program:
        |- StackSL on one line: words separated by single spaces, at most the stated number of words in all.
        |- Definitions are allowed and are the way to repeat a piece of work: `: name >v <v 4 * fract ;` then use `name`.
        |  A definition that is called must consume exactly one value and leave exactly one: open it with >v (move), not
        |  =v (copy). One that does not is refused, and so is one named after a built-in word. Put definitions first.
        |- No '?' host reads, no comments, and no texture words (sample, sample-abs, gather, prev, blur): there is no
        |  previous frame. Vector literals have no spaces inside: [0.9,0.1,0.1].
        |- A word that finds too few values on the stack is given defaults silently, so keep the arity right: count.
        |- To draw two colours split by a shape: colourA colourB mask mix, where mask is 0..1 (mix takes a b amount).
        |  Shapes come from distances and edges: p length is the distance from the centre; x edge threshold, lo hi x
        |  smoothstep, x centre width pulse; uv x and uv y are the coordinates alone; fract, floor and mod repeat things.
        |
        |The score:
        |- fitness is 1 - a distance to the target, 1 is exact. skill rescales it: 0 is a flat fill of the target's mean
        |  colour, 1 is exact. The metric is LPIPS (a learned perceptual distance that compares local features) or pixel
        |  error, as stated each round. Region scores are the same measure over regions of the picture, read row by row
        |  from the top left (r1c1 is the top-left region): they say where a program is right and where it is wrong.
        |  The search selects on them one region at a time, so a program that gets one region exactly right survives.
        |- Proposing a program the search already has costs nothing and does nothing; propose something different.
        |
        |Reply with exactly one line per program and nothing else, in this form:
        |parent | program | why
        |where parent is the number of the program you edited (#1, #2... as numbered in the picture) or 0 for a program
        |written from scratch, and why is at most eight words. Mix small edits of the best programs (a changed number, a
        |changed word, a word inserted) with bolder rewrites that build the target's main shape directly.
        |
        |The StackSL words guide follows. Stack effects read `a b -- c`, top of stack on the right.
        |
        |$guide
        """.trimMargin()
    }

    /** One round's request. */
    fun round(r: StackSLProposalRequest): String = buildString {
        // Not the target's name: `spot` or `rings` would tell the model what to draw, and a search
        // that is told the answer is not being measured. It gets the picture and its colours.
        appendLine("The target is the first picture (labelled T). Its colours: ${r.targetColours}.")
        appendLine("Metric: ${r.metric}. Generation ${r.generation}; the best has not improved for ${r.stalled} generation(s).")
        if (r.caseLabels.isNotEmpty()) appendLine("Region scores, in order: ${r.caseLabels.joinToString(" ")}.")
        appendLine()
        appendLine("The current best programs, pictured after the target in this order:")
        for (p in r.parents) {
            append("#${p.rank}  skill %.3f  fitness %.4f".format(p.skill, p.fitness))
            if (p.cases.isNotEmpty()) append("  regions " + p.cases.joinToString(" ") { "%.2f".format(it) })
            appendLine("  (from ${p.origin})")
            appendLine("    ${p.program}")
        }
        if (r.lastRound.isNotEmpty()) {
            appendLine()
            appendLine("What your last round's programs did (skill, and rank among all programs if kept):")
            for (o in r.lastRound) {
                val what = when {
                    o.problem != null -> "refused: ${o.problem}"
                    o.rank != null -> "skill %.3f, kept at #%d".format(o.skill ?: 0.0, o.rank)
                    o.skill != null -> "skill %.3f, not kept".format(o.skill)
                    else -> "not kept"
                }
                appendLine("    ${o.program}  ->  $what")
            }
        }
        appendLine()
        append("Propose ${r.proposals} programs of at most ${r.maxTokens} words each.")
    }

    /** The second pass: what each draft draws, and a request for the corrected list. */
    fun revision(r: StackSLProposalRequest, drafts: List<StackSLProposal>, shown: StackSLPreview): String = buildString {
        appendLine("This picture shows the target (T) and what each of your programs draws (P1, P2... in the order you wrote them):")
        drafts.forEachIndexed { i, p ->
            appendLine("P${i + 1}  ${shown.status.getOrElse(i) { "" }}")
            appendLine("    ${p.program}")
        }
        appendLine()
        appendLine("Compare each with what you meant and with the target. Fix the ones that do not draw what you intended")
        appendLine("(a flat picture usually means a mask that is always 0 or always 1: check each word's stack effect), keep")
        append("or refine the ones that work, and reply with ${r.proposals} programs in the same form. Parent numbers still refer to #1, #2...")
    }

    private val line = Regex("""^\s*#?\s*(\d+|new)\s*[|:]\s*(.+?)\s*(?:\|\s*(.*))?$""", RegexOption.IGNORE_CASE)

    /** The proposals in a reply, and the non-blank lines that were not proposals. */
    fun parse(text: String, parents: Int): Pair<List<StackSLProposal>, List<String>> {
        val out = ArrayList<StackSLProposal>()
        val unread = ArrayList<String>()
        for (raw in text.lines()) {
            val l = raw.trim().removePrefix("-").removePrefix("*").trim().trim('`')
            if (l.isBlank()) continue
            val m = line.matchEntire(l)
            if (m == null || !l.contains('|')) { unread += l; continue }
            val parent = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..parents }
            val program = m.groupValues[2].trim().trim('`').trim()
            if (program.isBlank()) { unread += l; continue }
            out += StackSLProposal(parent, program, m.groupValues[3].trim().take(80))
        }
        return out to unread
    }
}

/** Checking, normalising and describing programs that come from outside the blind operators. */
object StackSLProposals {

    private val refusedWords = setOf("sample", "sample-abs", "gather", "prev", "blur", "sample.w", "gather.w")

    /** [text] as a program: comments dropped, vector literals closed up, whitespace normalised. */
    fun program(text: String): StackSLProgram {
        val noComments = text.lines().joinToString(" ") { it.substringBefore('#') }
        val closed = Regex("""\[[^\]]*]""").replace(noComments) { it.value.replace(Regex("""\s+"""), "") }
        return StackSLProgram.of(closed)
    }

    /** Why [text] cannot be offered, or `null` when it can. */
    fun check(text: String, maxTokens: Int): String? {
        val p = program(text)
        if (p.size == 0) return "empty"
        if (p.size > maxTokens) return "${p.size} words, over the limit of $maxTokens"
        p.tokens.firstOrNull { it.startsWith("?") }?.let { return "'$it' reads the host, which a still render does not have" }
        p.tokens.firstOrNull { it in refusedWords }?.let { return "'$it' reads a texture, and there is no previous frame" }
        val compiled = runCatching { StackSLCompiler.compile(p.text, StackSLCpu.stackAutoOptions("program")) }
            .getOrElse { return "does not compile: ${it.message}" }
        compiled.diagnostics.firstOrNull { it.severity == StackSLSeverity.ERROR }?.let { return "does not compile: ${it.message}" }
        // A called definition whose stack effect is not one-in-one-out is pruned silently and every
        // later word reads the wrong operand: the report is what says so.
        if (p.tokens.contains(":")) {
            val report = runCatching { stackAutoShaderReport(p.text, "program") }.getOrElse { return "does not compile: ${it.message}" }
            if (!report.compiled) return "does not compile: ${report.errors.firstOrNull() ?: "unknown error"}"
            report.problems.firstOrNull()?.let { return it }
        }
        return null
    }

    /** Where each token of [text] starts, as a 1-based column, in token order. */
    fun tokenColumns(text: String): List<Int> =
        Regex("""\S+""").findAll(text).map { it.range.first + 1 }.toList()

    /**
     * The target's colours, as a model can use them: each colour and its share of the picture for
     * a target of at most [StackSLLayout.PALETTE_LIMIT] colours, otherwise the mean and the darkest
     * and brightest tenths.
     */
    fun describeColours(target: StackSLTarget): String {
        val r = target.raster
        val n = r.width * r.height
        val counts = LinkedHashMap<String, Int>()
        for (i in 0 until n) {
            val key = "[%s,%s,%s]".format(f(r.rgba[i * 4]), f(r.rgba[i * 4 + 1]), f(r.rgba[i * 4 + 2]))
            counts[key] = (counts[key] ?: 0) + 1
            if (counts.size > StackSLLayout.PALETTE_LIMIT) break
        }
        if (counts.size <= StackSLLayout.PALETTE_LIMIT) {
            return counts.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} ${(100.0 * it.value / n).roundToInt()}%" }
        }
        val luma = (0 until n).sortedBy { 0.2126 * r.rgba[it * 4] + 0.7152 * r.rgba[it * 4 + 1] + 0.0722 * r.rgba[it * 4 + 2] }
        fun meanOf(ix: List<Int>) = "[%s,%s,%s]".format(
            f(ix.map { r.rgba[it * 4] }.average().toFloat()), f(ix.map { r.rgba[it * 4 + 1] }.average().toFloat()),
            f(ix.map { r.rgba[it * 4 + 2] }.average().toFloat())
        )
        val tenth = maxOf(1, n / 10)
        return "a photograph-like picture: mean ${meanOf(luma)}, darkest tenth ${meanOf(luma.take(tenth))}, " +
            "brightest tenth ${meanOf(luma.takeLast(tenth))}"
    }

    private fun f(v: Float): String = StackSLPalette.format(v.toDouble().coerceIn(0.0, 1.0))
}

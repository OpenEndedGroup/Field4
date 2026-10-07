@file:afield.CompletionCategory("Search")

package afield.stdlib

import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.LlmBridge
import afield.ai.core.LlmReasoningEffort
import afield.ai.core.SilentLog
import afield.ai.core.createLlmBridge
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stacksl.cpu.StackSLCpu
import java.util.Base64
import kotlin.math.roundToInt

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
 *    score is being trained by the referee.
 *
 * ### What comes back
 *
 * One line per program, `parent | program | why`, `parent` 0 for a program written from scratch.
 * Each is checked ([StackSLProposals.check]) before it costs a render. A malformed line is dropped
 * and reported, never repaired into something the model did not write.
 */
fun interface StackSLProposer {
    fun propose(request: StackSLProposalRequest): StackSLProposalReply
}

/** What one watchmaker round is shown. */
data class StackSLProposalRequest(
    val targetName: String,
    val targetColours: String,
    val metric: String,
    val caseLabels: List<String>,
    val generation: Int,
    val stalled: Int,
    val parents: List<StackSLParentView>,
    val proposals: Int,
    val maxTokens: Int,
    val lastRound: List<StackSLProposalOutcome>,
    /** The target and the parents, numbered as in [parents]: [StackSLEvolution.thumbnailSheet]. */
    val sheetPng: ByteArray
) {
    val sheetDataUrl: String get() = "data:image/png;base64," + Base64.getEncoder().encodeToString(sheetPng)
}

/** One leader as the watchmaker sees it. */
data class StackSLParentView(
    val rank: Int,
    val program: String,
    val fitness: Double,
    val skill: Double,
    val cases: List<Double>,
    val origin: String
)

/** One proposed program. [parent] is a 1-based rank, `null` for one written from scratch. */
data class StackSLProposal(val parent: Int?, val program: String, val why: String = "")

/** What a round returned, and what it cost. */
data class StackSLProposalReply(
    val proposals: List<StackSLProposal>,
    val usage: StackSLModelUsage = StackSLModelUsage(),
    /** Lines that could not be read, or a failure, said plainly. */
    val problem: String? = null,
    val raw: String = ""
)

/** What happened to one offered program. [rank] is its place overall when it stayed, else `null`. */
data class StackSLProposalOutcome(
    val program: String = "",
    val origin: String = "",
    val fitness: Double? = null,
    val skill: Double? = null,
    val rank: Int? = null,
    val problem: String? = null,
    val why: String = ""
)

/** Model calls and tokens, summed. */
data class StackSLModelUsage(
    val model: String = "",
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    val millis: Long = 0
) {
    operator fun plus(o: StackSLModelUsage) = StackSLModelUsage(
        o.model.ifBlank { model }, calls + o.calls, inputTokens + o.inputTokens, cachedTokens + o.cachedTokens,
        outputTokens + o.outputTokens, millis + o.millis
    )

    /** Input tokens not served from the cache: the part that is paid for in full. */
    val uncachedTokens: Long get() = inputTokens - cachedTokens

    override fun toString(): String =
        "$model: $calls call(s), $inputTokens input ($cachedTokens cached, $uncachedTokens not), $outputTokens output, %.1f s".format(millis / 1000.0)
}

/**
 * The watchmaker on Field's configured AI provider, at the fast model unless told otherwise
 * (`AFIELD_AI_WATCHMAKER_MODEL`).
 */
class StackSLWatchmaker(
    val model: String = Ai4ModelDefaults.envOrDefault("AFIELD_AI_WATCHMAKER_MODEL", Ai4ModelDefaults.fastModel()),
    val reasoning: LlmReasoningEffort = LlmReasoningEffort.LOW,
    val maxOutputTokens: Long = 6000
) : StackSLProposer {

    private val bridge: LlmBridge by lazy { createLlmBridge(model, SilentLog, reasoning) }

    override fun propose(request: StackSLProposalRequest): StackSLProposalReply {
        val t0 = System.currentTimeMillis()
        val prompt = StackSLWatchmakerPrompt.round(request)
        val result = runCatching {
            val state = bridge.startThread(fileKey = "stacksl:watchmaker", fileText = "", instructions = StackSLWatchmakerPrompt.stable)
            bridge.send(state, prompt, maxOutputTokens = maxOutputTokens, maxToolRounds = 0, imageDataUrls = listOf(request.sheetDataUrl))
        }
        val elapsed = System.currentTimeMillis() - t0
        val sent = result.getOrElse { e ->
            return StackSLProposalReply(emptyList(), StackSLModelUsage(model, 1, millis = elapsed), "the model call failed: ${e.message ?: e}")
        }
        val usage = StackSLModelUsage(model, 1, sent.inputTokens ?: 0, sent.cacheReadTokens ?: 0, sent.outputTokens ?: 0, elapsed)
        val (proposals, unread) = StackSLWatchmakerPrompt.parse(sent.text, request.parents.size)
        val problem = if (unread.isEmpty()) null else "${unread.size} line(s) not in the form `parent | program | why`"
        return StackSLProposalReply(proposals.take(request.proposals), usage, problem, sent.text)
    }
}

/** The watchmaker's prompt: [stable] once and cached, [round] per call, and [parse] for the reply. */
object StackSLWatchmakerPrompt {

    /** The words guide sections a straight-line program can use. Definitions are out; so is the header. */
    private val guide: String by lazy {
        val text = StackSLWatchmakerPrompt::class.java.getResourceAsStream("/afield/stacksl/study/stacksl_words.md")
            ?.bufferedReader()?.readText().orEmpty()
        val sections = text.split(Regex("(?m)^(?=## )"))
        sections.drop(1).filterNot { it.startsWith("## Definitions") || it.startsWith("## Word references") }
            .joinToString("").trim()
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
        |- Straight-line StackSL on one line: words separated by single spaces, at most the stated number of words.
        |- No definitions (no ':' or ';'), no '?' host reads, no comments, and no texture words (sample, sample-abs,
        |  gather, prev, blur): there is no previous frame. Vector literals have no spaces inside: [0.9,0.1,0.1].
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
        appendLine("Target: '${r.targetName}', the first picture (labelled target). Its colours: ${r.targetColours}.")
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
        p.tokens.firstOrNull { it == ":" || it == ";" }?.let { return "definitions are not allowed" }
        p.tokens.firstOrNull { it.startsWith("?") }?.let { return "'$it' reads the host, which a still render does not have" }
        p.tokens.firstOrNull { it in refusedWords }?.let { return "'$it' reads a texture, and there is no previous frame" }
        val compiled = runCatching { StackSLCompiler.compile(p.text, StackSLCpu.stackAutoOptions("program")) }
            .getOrElse { return "does not compile: ${it.message}" }
        compiled.diagnostics.firstOrNull { it.severity == StackSLSeverity.ERROR }?.let { return "does not compile: ${it.message}" }
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

package afield.ai.conversation

import afield.ai.diagnostics.log

import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import java.security.MessageDigest

/**
 * # What a turn tells the model, as parts rather than one string
 *
 * Every AI4 turn used to prepend the whole of [buildDynamicInstructions] — fifteen to twenty thousand
 * characters of tool adverts and guidance — to the user's message, and that message is what the
 * provider keeps. The Anthropic bridge resends its history in full on every request and the OpenAI
 * Responses API chains it server-side, so a ten-turn conversation carried ten copies of the
 * instructions, each one a snapshot of a turn that was over. Pi (earendil-works/pi) builds each model
 * call instead from its parts — the session so far, the system prompt, the tools, the context in force
 * *now* — and keeps only the durable parts in the session. That is the change here.
 *
 * A [ContextFragment] is one part, tagged with a [FragmentRetention] and a [Stability]:
 *
 * - [FragmentRetention.DURABLE] parts are what the person asked — the request text, the line and error an editor
 *   turn is about — and stay in the stored message, because a later turn has to be able to see them.
 * - [FragmentRetention.EPHEMERAL] parts are instructions about the tools and the document *as of this turn*. They
 *   are sent on every request of the turn and never stored: the next turn sends its own.
 * - [Stability.STABLE] parts do not change from turn to turn (the contract, the tool adverts for a given
 *   set of tools). Bridges put them where the provider's prompt cache can reuse them. [Stability.VOLATILE]
 *   parts may change (the document guide catalogue, the attachment list).
 *
 * Where each kind goes is the bridge's business — [TurnContext] only says what is what. The OpenAI
 * Responses bridge sends the ephemeral parts as the request's `instructions`, which the API does not
 * carry over to the next response. The Anthropic bridge sends stable parts as system blocks with cache
 * breakpoints and volatile parts as a prefix on the newest message that is stripped once the turn ends.
 * A bridge that does neither (OpenRouter's stateless path, the scripted bridge) sends [legacyPrompt], which
 * is byte-for-byte the string every turn used to send.
 */
enum class FragmentRetention { DURABLE, EPHEMERAL }

enum class Stability { STABLE, VOLATILE }

data class ContextFragment(
    /** Where it came from, as `/context` shows it: `core.contract`, `tools.graph`, `guides.catalogue`, `lessons`, ... */
    val source: String,
    /** The text, including the separator that joins it to the fragment before (so a join is a plain concat). */
    val text: String,
    val retention: FragmentRetention = FragmentRetention.EPHEMERAL,
    val stability: Stability = Stability.STABLE,
) {
    val chars: Int get() = text.length

    /** A rough token count (four characters a token); for showing proportions, never for billing. */
    val approxTokens: Int get() = (text.trim().length + 3) / 4

    val sha8: String get() = sha256(text).take(8)
}

/**
 * One turn's prompt in parts: the [fragments] of instructions and request, then [rest] — what follows them
 * in the user's message (the prompt itself, the code an editor turn rewrites).
 */
data class TurnContext(val fragments: List<ContextFragment>, val rest: String) {

    /** Exactly what a turn sent before fragments existed: every fragment, then a blank line, then [rest]. */
    fun legacyPrompt(): String = fragments.joinToString("") { it.text } + "\n\n" + rest

    /** The user message to store: the durable fragments and [rest]. */
    fun durablePrompt(): String {
        val head = fragments.filter { it.retention == FragmentRetention.DURABLE }.joinToString("") { it.text }.trim()
        return if (head.isEmpty()) rest else "$head\n\n$rest"
    }

    fun ephemeral(stability: Stability): List<ContextFragment> =
        fragments.filter { it.retention == FragmentRetention.EPHEMERAL && it.stability == stability }

    /** The ephemeral fragments of one stability, joined, with the leading separator dropped. */
    fun ephemeralText(stability: Stability): String = ephemeral(stability).joinToString("") { it.text }.trim()

    /** Stable first, then volatile: the order that keeps a cached prefix valid longest. */
    fun allEphemeralText(): String =
        listOf(ephemeralText(Stability.STABLE), ephemeralText(Stability.VOLATILE)).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** A hash of the ephemeral instructions, for checkpoints and for telling two turns' instructions apart. */
    fun instructionHash(): String = sha256(allEphemeralText()).take(16)

    fun report(placement: String): ContextReport = ContextReport(
        placement = placement,
        fragments = fragments.map { ContextFragmentInfo(it.source, it.retention.name, it.stability.name, it.chars, it.approxTokens, it.sha8) },
        restChars = rest.length,
        instructionHash = instructionHash(),
    )
}

/** What `/context` shows and the tool I/O log records: fragment metadata, never the text. */
data class ContextFragmentInfo(
    val source: String,
    val retention: String,
    val stability: String,
    val chars: Int,
    val approxTokens: Int,
    val sha8: String,
)

data class ContextReport(
    /** How the bridge placed the ephemeral parts: `instructions` (OpenAI), `system+prefix` (Anthropic), `inline` (legacy). */
    val placement: String,
    val fragments: List<ContextFragmentInfo>,
    val restChars: Int,
    val instructionHash: String,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val inputTokens: Long? = null,
    /** The tools whose schemas the model was sent, by name. */
    val tools: List<String> = emptyList(),
    /** Box-authored tools in the conversation, with the box that registered each and its source revision then. */
    val dynamicTools: List<DynamicToolInfo> = emptyList(),
)

data class DynamicToolInfo(val name: String, val box: String, val sourceRevision: String)

/** The tool part of a report for [ctx]: what [registry] offers a model, and the box tools with their owners. */
internal fun ContextReport.withTools(registry: ToolRegistry, ctx: ToolExecutionContext): ContextReport = copy(
    tools = registry.describe(ctx).mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("name")?.let { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.content } },
    dynamicTools = ctx.dynamicTools.map { DynamicToolInfo(it.name, it.boxId, it.sourceRevision) },
)

/**
 * `AFIELD_AI4_EPHEMERAL_INSTRUCTIONS=off` (or `-Dafield.ai4.ephemeralInstructions=off`) puts every turn back
 * on [TurnContext.legacyPrompt]; an agent task's `context=legacy` does the same for one task (the A/B arm).
 */
object ContextPipeline {
    val enabledByDefault: Boolean
        get() {
            val raw = System.getProperty("afield.ai4.ephemeralInstructions") ?: System.getenv("AFIELD_AI4_EPHEMERAL_INSTRUCTIONS")
            return raw == null || raw.trim().lowercase() !in setOf("0", "off", "false", "no", "legacy")
        }
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Where a fragment ends up, given where the bridge put it ([ContextReport.placement]). The fragment classes
 * describe intent; the provider decides retention, and these differ: OpenAI's conversations live on its side,
 * so anything sent inside one stays there.
 */
enum class Kept(val label: String) {
    /** Stays in the conversation and is added again every turn: it accumulates. */
    EVERY_TURN("kept, added every turn"),
    /** Stays in the conversation, sent once (and again only when it changes). */
    ONCE("kept, sent once"),
    /** Stays in the conversation because it is part of the stored message. */
    STORED("kept"),
    /** Sent with every request of every turn, never stored (a cached system prompt). */
    EVERY_REQUEST("every request, not kept"),
    /** Sent with this turn's requests and then dropped. */
    THIS_TURN("this turn only"),
}

fun keptFor(placement: String, retention: String, stability: String): Kept = when {
    retention == "DURABLE" || placement == "inline" -> Kept.STORED
    placement == "developer-once+input" -> if (stability == "STABLE") Kept.ONCE else Kept.EVERY_TURN
    placement == "system+prefix" -> if (stability == "STABLE") Kept.EVERY_REQUEST else Kept.THIS_TURN
    else -> Kept.STORED
}

private fun placementNote(placement: String): String = when (placement) {
    "developer-once+input" -> "OpenAI keeps the conversation on its side. Stable instructions go into it once, as a developer " +
        "message, and again only when they change. Volatile context goes in front of each request, so it stays in the " +
        "conversation and every turn adds another copy. Sending it outside the conversation made every new conversation's " +
        "first request uncacheable, which cost more (dev_docs/ai/AI4_PI_LESSONS.md)."
    "system+prefix" -> "Anthropic: stable instructions are cached system blocks sent with every request; volatile context " +
        "is put in front of the newest request and removed from the stored conversation when the turn ends."
    "inline" -> "The instructions were sent inside the stored request, so they stay in the conversation and are resent with " +
        "every later request."
    else -> "Unknown placement: treat everything as kept."
}

/** A context report as the markdown `/context` shows: one row per fragment, largest parts easy to spot. */
fun ContextReport.toMarkdown(): String = buildString {
    appendLine("**What the last turn sent besides the conversation** (placement: `$placement`, instructions `$instructionHash`)")
    appendLine()
    appendLine("| source | kept | changes | chars | ~tokens |")
    appendLine("| --- | --- | --- | ---: | ---: |")
    fragments.groupBy { it.source }.forEach { (source, parts) ->
        val first = parts.first()
        appendLine("| `$source` | ${keptFor(placement, first.retention, first.stability).label} | " +
            "${first.stability.lowercase()} | ${parts.sumOf { it.chars }} | ${parts.sumOf { it.approxTokens }} |")
    }
    appendLine("| (request and code) | kept | — | $restChars | ${(restChars + 3) / 4} |")
    val byKept = fragments.groupBy { keptFor(placement, it.retention, it.stability) }
    fun chars(vararg k: Kept) = k.sumOf { kept -> byKept[kept].orEmpty().sumOf { it.chars } }
    appendLine()
    append("Kept in the conversation: ${chars(Kept.STORED, Kept.EVERY_TURN, Kept.ONCE) + restChars} chars")
    if (chars(Kept.EVERY_TURN) > 0) append(", of which ${chars(Kept.EVERY_TURN)} are added again with every turn")
    append(". Not kept: ${chars(Kept.EVERY_REQUEST, Kept.THIS_TURN)} chars.")
    if (inputTokens != null) append(" Provider-reported input this turn: $inputTokens tokens")
    if (cacheReadTokens != null) append(", $cacheReadTokens from cache")
    if (cacheWriteTokens != null) append(", $cacheWriteTokens written to cache")
    if (inputTokens != null || cacheReadTokens != null) append(".")
    appendLine()
    appendLine()
    appendLine(placementNote(placement))
}

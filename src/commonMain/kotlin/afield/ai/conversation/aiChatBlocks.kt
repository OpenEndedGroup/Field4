package afield.ai.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * # An `ai4_chat_history` transcript, block by block
 *
 * The chat panel and the chat archive read the same markup, so they share this parser: the panel renders the
 * blocks (`aiChatHistoryToHtml`, jsMain) and the archive indexes them (`afield.ai.conversation.chatlog`, jvmMain).
 *
 * A transcript is markdown cut into blocks by marker lines: `<<you>>`, `<<ai>>` (whose first line may be a
 * `<<model:…>>` identity, [AiChatModelIdentity]), `<<progress>>`, `<<progress-open>>` (the live item of a
 * running turn), `<<queued>>` (display only) and `<<summary>>`: a lens's third voice, a block that condenses
 * several turns of both speakers at once. Text before the first marker, or a transcript with none, is
 * one `ai` block.
 */
data class AiChatBlock(
    val role: String,
    val content: String,
    val model: AiChatModelIdentity? = null,
    /**
     * What this block stands for, when it is a block of a lens: addresses or ranges (`a..b`) in the chat the
     * lens summarizes, from `<<source:…>>` marker lines at the top of the block. Empty for an ordinary chat.
     */
    val sources: List<String> = emptyList(),
    /**
     * The block's own address in the chat it came from, from an `<<at:…>>` marker line. The chat archive adds
     * one to every block it hands the lens viewer, so a line can be drawn to it; ordinary chats have none.
     */
    val address: String? = null,
    /**
     * [model] came from the chat's `<<run-model:…>>` line, the model the whole run is recorded as using, and
     * not from this block: the block predates per-response model records.
     */
    val modelFromRun: Boolean = false,
) {
    val isProgress: Boolean get() = role.startsWith("progress")
    // Kept once: the panel's grouping reads these for every block on every streamed render.
    val title: String by lazy(LazyThreadSafetyMode.PUBLICATION) { content.lines().firstOrNull()?.trim().orEmpty() }
    val body: String by lazy(LazyThreadSafetyMode.PUBLICATION) { content.lines().drop(1).joinToString("\n").trim() }

    /** A progress block with nothing under its title: the panel shows these as one inline status row. */
    val isStatusOnly: Boolean get() = isProgress && (body.isBlank() || body == title)

    /**
     * The row a box chat's turn ends with when it waits for builds without the model (`StudyBuildAwaits`,
     * jvmMain). The panel shows it on its own, never folded into the round of tool calls before it.
     */
    val isBuildWait: Boolean get() = isProgress && title.startsWith(BUILD_WAIT_PREFIX) && title.endsWith(BUILD_WAIT_SUFFIX)

    /**
     * The row a box chat's turn ends with when the agent asks the person something (`AskUserQuestionRequest`,
     * jvmMain): the question, then the suggested answers as `- ` lines under [QUESTION_CHOICES]. The person's
     * next message is the answer. The panel shows it on its own, with a button per choice while it is unanswered.
     */
    val isQuestion: Boolean get() = isProgress && title == QUESTION_TITLE

    /** A question row's question: its body above the choices. */
    val questionText: String get() = body.substringBefore(QUESTION_CHOICES).trim()

    /** A question row's suggested answers, in order; empty when it takes free text only. */
    val questionChoices: List<String> get() = if (!body.contains(QUESTION_CHOICES)) emptyList()
        else body.substringAfter(QUESTION_CHOICES).lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }

    /**
     * A request Field wrote itself (the outcome of builds a turn waited for): the model reads it as the next
     * request, but the person did not write it, and the panel says so.
     */
    val isFieldMessage: Boolean get() = role == "you" && content.startsWith(FIELD_MESSAGE_PREFIX)

    companion object {
        /** How a request Field writes itself begins; see [isFieldMessage]. */
        const val FIELD_MESSAGE_PREFIX = "**Field:** "
        private const val BUILD_WAIT_PREFIX = "Waiting for "
        private const val BUILD_WAIT_SUFFIX = " · no model calls"

        const val QUESTION_TITLE = "Question for you · waiting for your answer"
        const val QUESTION_CHOICES = "Suggested answers:"

        /** The closed transcript block a turn ends with when it asks [question]; [isQuestion] recognises it. */
        fun questionRow(question: String, choices: List<String>): String = buildString {
            append("<<progress>>\n").append(QUESTION_TITLE).append('\n')
            append(question.trim().replace("<<", "‹‹")).append('\n')
            if (choices.isNotEmpty()) {
                append(QUESTION_CHOICES).append('\n')
                choices.forEach { append("- ").append(it.replace('\n', ' ').replace("<<", "‹‹").trim()).append('\n') }
            }
        }

        /** The title of a build-wait row for [jobs] jobs; [isBuildWait] recognises it. */
        fun buildWaitTitle(jobs: Int): String = BUILD_WAIT_PREFIX + (if (jobs == 1) "1 build" else "$jobs builds") + BUILD_WAIT_SUFFIX
    }
}

/** The marker lines at the top of a block: an `<<at:…>>` address and `<<source:…>>` citations, in any order. */
data class AiChatLensMarkers(val address: String?, val sources: List<String>, val body: String)

/**
 * Leading `<<at:address>>` and `<<source:a..b, c>>` lines. A source is a lens block's citation of the chat it
 * summarizes; a range runs `from..to` because block addresses (`<nodeId>.b<index>`) contain `-`.
 */
fun splitLensMarkers(content: String): AiChatLensMarkers {
    val sources = mutableListOf<String>()
    var address: String? = null
    var rest = content
    while (rest.startsWith("<<source:") || rest.startsWith("<<at:")) {
        val line = rest.substringBefore('\n')
        if (!line.endsWith(">>")) break
        if (line.startsWith("<<at:")) address = line.removePrefix("<<at:").removeSuffix(">>").trim()
        else sources += line.removePrefix("<<source:").removeSuffix(">>").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        rest = rest.substringAfter('\n', "").trimStart()
    }
    return AiChatLensMarkers(address, sources, rest)
}

private val chatMarkers = listOf("<<you>>", "<<ai>>", "<<summary>>", "<<progress>>", "<<progress-open>>", "<<queued>>")

/** The `<<run-model:{json}>>` line a saved chat may open with: the model its run is recorded as using. */
private const val RUN_MODEL = "<<run-model:"

fun parseAiChatBlocks(history: String?): List<AiChatBlock> = parseAiChatHistory(history).blocks

/**
 * A parsed transcript that can be resumed. A streaming chat only grows at its end, so the next parse keeps
 * every block before the last marker and reparses from there (`parseAiChatHistory(next, previous)`).
 */
class AiChatParse internal constructor(
    val blocks: List<AiChatBlock>,
    internal val header: String,
    internal val runModel: AiChatModelIdentity?,
    /** The transcript (after its header) up to its last marker; null when it has no markers. */
    internal val stablePrefix: String?,
    /** How many of [blocks] come before the last marker. */
    internal val stableBlocks: Int,
)

/**
 * Parse [history], reusing [previous] when [history] extends it: everything before [previous]'s last marker is
 * unchanged, so only the rest is tokenized. The result is the same as a fresh parse.
 */
fun parseAiChatHistory(history: String?, previous: AiChatParse? = null): AiChatParse {
    var text = (history ?: "").replace("\r\n", "\n")
    val header = if (text.startsWith(RUN_MODEL)) text.substringBefore('\n') else ""
    if (header.isNotEmpty()) text = text.substringAfter('\n', "")
    val prefix = previous?.stablePrefix
    if (previous != null && prefix != null && previous.header == header && text.startsWith(prefix)) {
        val tail = parseTranscript(text, prefix.length, previous.runModel)
        val blocks = previous.blocks.subList(0, previous.stableBlocks) + tail.blocks
        return AiChatParse(withFallback(blocks, text), header, previous.runModel,
            if (tail.lastMarkerAt >= 0) text.substring(0, tail.lastMarkerAt) else prefix,
            previous.stableBlocks + if (tail.lastMarkerAt >= 0) tail.stableBlocks else 0)
    }
    val runModel = if (header.isEmpty()) null else runCatching {
        kotlinx.serialization.json.Json.decodeFromString<AiChatModelIdentity>(header.removePrefix(RUN_MODEL).removeSuffix(">>"))
    }.getOrNull()
    val parsed = parseTranscript(text, 0, runModel)
    return AiChatParse(withFallback(parsed.blocks, text), header, runModel,
        if (parsed.lastMarkerAt >= 0) text.substring(0, parsed.lastMarkerAt) else null, parsed.stableBlocks)
}

/** Text with no block of its own (no markers, or only empty ones) shows as one ai block, as it always has. */
private fun withFallback(blocks: List<AiChatBlock>, text: String): List<AiChatBlock> =
    if (blocks.isEmpty() && text.trim().isNotEmpty()) listOf(AiChatBlock("ai", text.trim())) else blocks

private class TranscriptBlocks(val blocks: List<AiChatBlock>, val lastMarkerAt: Int, val stableBlocks: Int)

/** Blocks of [text] from [from], which is 0 or the offset of a marker. */
private fun parseTranscript(text: String, from: Int, runModel: AiChatModelIdentity?): TranscriptBlocks {
    val blocks = mutableListOf<AiChatBlock>()
    var currentRole: String? = null
    val buffer = StringBuilder()
    var lastMarkerAt = -1
    var stableBlocks = 0

    fun flush() {
        // Text before the first marker belongs to no block.
        val role = currentRole ?: run { buffer.clear(); return }
        val content = buffer.toString().trim()
        if (content.isNotEmpty()) {
            val (model, rest) = if (role == "ai") AiChatModelIdentity.splitFromContent(content) else null to content
            val markers = splitLensMarkers(rest)
            // An ai block with no model of its own takes the run's, marked as such; a lens block keeps none.
            val fromRun = model == null && role == "ai" && runModel != null && markers.sources.isEmpty()
            // A block that was only marker lines still renders (a model badge with no text yet), as it always has.
            blocks.add(AiChatBlock(role, markers.body, if (fromRun) runModel else model, markers.sources, markers.address, fromRun))
        }
        buffer.clear()
    }

    var idx = from
    // Each marker's next position, searched again only once the scan has passed it. Searching every marker
    // from every token made this quadratic: a marker absent from the rest of the text was scanned to the end
    // once per token, which in a long chat cost the panel ~70 ms per streamed chunk.
    val nextAt = IntArray(chatMarkers.size) { -2 }
    while (idx < text.length) {
        var best = -1
        for (m in chatMarkers.indices) {
            if (nextAt[m] != -1 && nextAt[m] < idx) nextAt[m] = text.indexOf(chatMarkers[m], idx)
            if (nextAt[m] != -1 && (best == -1 || nextAt[m] < nextAt[best])) best = m
        }
        if (best == -1) {
            buffer.append(text, idx, text.length)
            break
        }
        val next = nextAt[best]
        val marker = chatMarkers[best]
        buffer.append(text, idx, next)
        flush()
        currentRole = marker.removePrefix("<<").removeSuffix(">>")
        lastMarkerAt = next
        stableBlocks = blocks.size
        idx = next + marker.length
    }
    flush()
    return TranscriptBlocks(blocks, lastMarkerAt, stableBlocks)
}

/**
 * One tool call as a transcript records it: the title of a progress block (`ViewBoxOutputRequest finished
 * (2767 ms)`, `AttachmentRequest failed (0 ms)`), or a line of a tool program's body
 * (`` - ✗ `_field_services_previewEdit_5934…` `{args}` — 291 ms — Provide 1–64 atomic edits. ``).
 *
 * [tool] is the name as recorded; [name] drops what varies between documents and call sites: the 24-hex
 * suffix a box-authored tool's wire name carries, and the `Request` suffix core tools have at top level but
 * not inside a program. Arguments are not kept: the transcript clips them, and the archive does not need them.
 */
@Serializable
data class AiChatToolCall(
    val tool: String,
    val name: String,
    val ok: Boolean,
    val ms: Long? = null,
    val message: String? = null,
)

private val toolTitle = Regex("""^(\S+) (finished|failed)(?: \((\d+) ms\))?""")
private val programLine = Regex("""^- ([✓✗]) `([^`]+)`(?: `[^`]*`?)?(?: — (\d+) ms)?(?: — (.*))?$""")
private val wireHash = Regex("""_[0-9a-f]{24}$""")

fun aiChatToolName(tool: String): String = tool.replace(wireHash, "").removeSuffix("Request")

/** The tool call a progress block's title records, or null for a status, build or failure line. */
fun AiChatBlock.toolCall(): AiChatToolCall? {
    if (!isProgress) return null
    val m = toolTitle.find(title) ?: return null
    val tool = m.groupValues[1]
    return AiChatToolCall(tool, aiChatToolName(tool), m.groupValues[2] == "finished", m.groupValues[3].toLongOrNull())
}

/** The calls a tool program made, from the `- ✓ …` / `- ✗ …` lines of its block body. */
fun AiChatBlock.programCalls(): List<AiChatToolCall> {
    if (!isProgress) return emptyList()
    return body.lineSequence().mapNotNull { line ->
        val m = programLine.find(line.trim()) ?: return@mapNotNull null
        val tool = m.groupValues[2]
        AiChatToolCall(tool, aiChatToolName(tool), m.groupValues[1] == "✓", m.groupValues[3].toLongOrNull(),
            m.groupValues[4].trim().ifBlank { null })
    }.toList()
}

/**
 * An image in a block: `![alt](src)`. A tool image sits in an `ai` block under an italic caption line saying
 * what the tool was looking at (`_Output of box 'Plan' (main), 888x810 … looking for: …_`); an attachment sits
 * in a `you` block. [src] is kept only when it is a reference; a `data:` URL is reduced to its media type and
 * size, since the archive never needs the bytes inline.
 */
@Serializable
data class AiChatImage(
    val alt: String,
    val caption: String? = null,
    /** `ai4img:<sha>.<ext>` (a session-tree blob), an ordinary URL, or null for an inline `data:` image. */
    val ref: String? = null,
    val mediaType: String? = null,
    val base64Chars: Int = 0,
)

// Every `]` escaped: Kotlin/JS compiles this to a unicode-mode RegExp, where a bare `]` is a syntax error.
private val imageMarkdown = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")

fun AiChatBlock.images(): List<AiChatImage> {
    val lines = content.lines()
    val out = mutableListOf<AiChatImage>()
    for ((i, line) in lines.withIndex()) {
        for (m in imageMarkdown.findAll(line)) {
            val src = m.groupValues[2]
            val caption = (i - 1 downTo 0).asSequence().map { lines[it].trim() }.firstOrNull { it.isNotEmpty() }
                ?.takeIf { it.length > 2 && it.startsWith("_") && it.endsWith("_") }
                ?.removeSurrounding("_")
            out += if (src.startsWith("data:")) {
                AiChatImage(m.groupValues[1], caption, null, src.removePrefix("data:").substringBefore(';'),
                    src.length - src.indexOf(',') - 1)
            } else {
                AiChatImage(m.groupValues[1], caption, src)
            }
        }
    }
    return out
}

package afield

import kotlinx.serialization.Serializable

/**
 * # An `ai4_chat_history` transcript, block by block
 *
 * The chat panel and the chat archive read the same markup, so they share this parser: the panel renders the
 * blocks (`aiChatHistoryToHtml`, jsMain) and the archive indexes them (`afield.chatlog`, jvmMain).
 *
 * A transcript is markdown cut into blocks by marker lines: `<<you>>`, `<<ai>>` (whose first line may be a
 * `<<model:…>>` identity, [AiChatModelIdentity]), `<<progress>>`, `<<progress-open>>` (the live item of a
 * running turn) and `<<queued>>` (display only). Text before the first marker, or a transcript with none, is
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
) {
    val isProgress: Boolean get() = role.startsWith("progress")
    val title: String get() = content.lines().firstOrNull()?.trim().orEmpty()
    val body: String get() = content.lines().drop(1).joinToString("\n").trim()

    /** A progress block with nothing under its title: the panel shows these as one inline status row. */
    val isStatusOnly: Boolean get() = isProgress && (body.isBlank() || body == title)
}

/**
 * Leading `<<source:a..b, c>>` lines: a lens block's citations of the chat it summarizes. A range runs
 * `from..to` because block addresses (`<nodeId>.b<index>`) contain `-`.
 */
fun splitSourceMarkers(content: String): Pair<List<String>, String> {
    val sources = mutableListOf<String>()
    var rest = content
    while (rest.startsWith("<<source:")) {
        val line = rest.substringBefore('\n')
        if (!line.endsWith(">>")) break
        sources += line.removePrefix("<<source:").removeSuffix(">>").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        rest = rest.substringAfter('\n', "").trimStart()
    }
    return sources to rest
}

private val chatMarkers = listOf("<<you>>", "<<ai>>", "<<progress>>", "<<progress-open>>", "<<queued>>")

fun parseAiChatBlocks(history: String?): List<AiChatBlock> {
    val text = (history ?: "").replace("\r\n", "\n")
    val tokens = mutableListOf<String>()
    var idx = 0
    while (idx < text.length) {
        val hit = chatMarkers.mapNotNull { marker ->
            val at = text.indexOf(marker, idx)
            if (at == -1) null else at to marker
        }.minByOrNull { it.first }
        if (hit == null) {
            tokens.add(text.substring(idx))
            break
        }
        val (next, marker) = hit
        if (next > idx) tokens.add(text.substring(idx, next))
        tokens.add(marker)
        idx = next + marker.length
    }

    val blocks = mutableListOf<AiChatBlock>()
    var currentRole: String? = null
    val buffer = StringBuilder()

    fun flush() {
        val role = currentRole ?: return
        val content = buffer.toString().trim()
        if (content.isNotEmpty()) {
            val (model, rest) = if (role == "ai") AiChatModelIdentity.splitFromContent(content) else null to content
            val (sources, body) = splitSourceMarkers(rest)
            // A block that was only marker lines still renders (a model badge with no text yet), as it always has.
            blocks.add(AiChatBlock(role, body, model, sources))
        }
        buffer.clear()
    }

    for (token in tokens) {
        if (token in chatMarkers) {
            flush()
            currentRole = token.removePrefix("<<").removeSuffix(">>")
        } else {
            buffer.append(token)
        }
    }
    flush()
    if (blocks.isEmpty() && text.trim().isNotEmpty()) blocks.add(AiChatBlock("ai", text.trim()))
    return blocks
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

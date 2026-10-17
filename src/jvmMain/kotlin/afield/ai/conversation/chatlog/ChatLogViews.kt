package afield.ai.conversation.chatlog

import afield.ai.conversation.AiChatRound
import afield.ai.conversation.AiChatToolFamilies
import afield.ai.conversation.parseAiChatBlocks
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * # What an agent reads of a chat
 *
 * Text views of a [ChatTree] for AI4 agents that write lenses ([ChatlogTools]). Every view names blocks by
 * address, so what the agent reads is what it cites. Tool activity reads as round lines in the panel's own
 * words ([AiChatRound]); images read as their captions and `ai4img:` references, never as bytes.
 */
object ChatLogViews {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    private fun at(ts: Long?): String = ts?.let { time.format(Instant.ofEpochMilli(it)) } ?: "time not recorded"

    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace(Regex("""!\[([^\]]*)\]\([^)\s]+\)"""), "[image: $1]").replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    /** Consecutive progress blocks, as the panel groups them; other blocks stand alone. */
    private fun groups(blocks: List<ChatBlockEntry>): List<List<ChatBlockEntry>> {
        val out = mutableListOf<MutableList<ChatBlockEntry>>()
        for (b in blocks) {
            val progress = b.role.startsWith("progress")
            if (progress && out.lastOrNull()?.last()?.role?.startsWith("progress") == true) out.last() += b
            else out += mutableListOf(b)
        }
        return out
    }

    private fun round(group: List<ChatBlockEntry>, families: AiChatToolFamilies): AiChatRound =
        AiChatRound(group.flatMap { parseAiChatBlocks(markerFor(it) + it.text) }, families)

    private fun markerFor(b: ChatBlockEntry) = "<<${b.role}>>\n"

    private fun range(group: List<ChatBlockEntry>) =
        if (group.size == 1) group[0].address else "${group.first().address}..${group.last().address}"

    /**
     * The map of a chat: every user turn with its time, the request in full, and what followed: replies as
     * their first line, rounds as round lines, images as captions. Then each abandoned branch the same way.
     */
    fun outline(tree: ChatTree, families: AiChatToolFamilies = ChatToolFamilies.default): String = buildString {
        val stats = ChatStats.of(tree)
        appendLine("# ${tree.document} · ${tree.boxName ?: tree.box}")
        appendLine("${stats.userTurns} turns on the current branch, ${stats.abandonedUserTurns} rewound · ${stats.replies} replies · " +
            "${stats.toolCalls + stats.programCalls} tool calls · ${stats.toolImages} images · ${at(stats.firstTs)} → ${at(stats.lastTs)}")
        appendLine("Addresses are `<node>.b<index>`; cite a stretch as `first..last`.")
        appendLine()
        section(tree.headPath, families)
        val abandoned = tree.nodes.filter { !it.onHead }
        for (n in abandoned) {
            val after = n.parent?.let { p -> tree.node(p) }
            appendLine("## Abandoned branch ${n.id} (rewound away; follows ${after?.id ?: "the start"})")
            section(listOf(n), families)
        }
    }

    private fun StringBuilder.section(nodes: List<ChatNode>, families: AiChatToolFamilies) {
        var chat: String? = null
        for (node in nodes) {
            // A series runs several chats end to end: say where each begins.
            if (node.chat != null && node.chat != chat) {
                chat = node.chat
                appendLine()
                appendLine("# ${node.chat}")
            }
            for (group in groups(node.blocks)) {
                val first = group.first()
                when {
                    first.role == "you" -> {
                        appendLine()
                        appendLine("## ${first.turn?.let { "Turn $it" } ?: "Request"} · ${first.address} · finished ${at(node.ts)}")
                        first.text.lines().forEach { appendLine("> $it") }
                    }
                    first.role == "ai" || first.role == "summary" -> {
                        val images = first.images.map { it.image.caption ?: it.image.alt }
                        val text = oneLine(first.text.replace(Regex("""(?m)^_[^\n]*_$"""), ""), 180)
                        append("- ${first.address} ${if (first.role == "ai") "reply" else "summary"}: $text")
                        if (images.isNotEmpty()) append(" [${images.size} image${if (images.size > 1) "s" else ""}: ${images.joinToString("; ") { oneLine(it, 120) }}]")
                        appendLine()
                    }
                    first.role.startsWith("progress") -> {
                        val run = round(group, families)
                        if (AiChatRound.folds(run.blocks)) appendLine("- ${range(group)} round: ${run.line()}")
                        else group.filter { it.kind != "status" }.forEach { appendLine("- ${it.address} ${it.kind}: ${oneLine(it.title, 140)}") }
                    }
                }
            }
        }
    }

    /** One page of [read]: the text, and where to continue if it stopped early. */
    data class Page(val text: String, val next: String?)

    /**
     * The chat as text from [from] to [to] (the whole current branch when both are null), each block headed by
     * its address. `text` gives requests and replies in full and rounds as round lines; `full` adds every
     * tool block's own text. Stops before [maxChars] and says where to continue.
     */
    fun read(tree: ChatTree, from: String?, to: String?, detail: String = "text", maxChars: Int = 20_000,
             families: AiChatToolFamilies = ChatToolFamilies.default): Page {
        val blocks = when {
            from == null && to == null -> tree.headBlocks
            else -> {
                val end = to ?: tree.headBlocks.last().address
                val start = from ?: tree.pathTo(end.substringBeforeLast(".b")).first().blocks.first().address
                tree.span(start, end) ?: throw IllegalArgumentException("no stretch $start..$end: check both addresses, and that the first comes before the last on one branch")
            }
        }
        val out = StringBuilder()
        for (group in groups(blocks)) {
            val piece = buildString {
                val first = group.first()
                if (!first.role.startsWith("progress")) {
                    appendLine("[${first.address}] <<${first.role}>>")
                    appendLine(first.text.trim())
                } else {
                    val run = round(group, families)
                    if (AiChatRound.folds(run.blocks)) appendLine("[${range(group)}] round: ${run.line()}")
                    if (detail == "full" || !AiChatRound.folds(run.blocks)) for (b in group) {
                        if (detail != "full" && b.kind == "status") continue
                        appendLine("[${b.address}] <<${b.role}>> ${b.kind}")
                        appendLine(if (detail == "full") b.text.trim().take(1500) else b.title)
                    }
                }
            }
            if (out.isNotEmpty() && out.length + piece.length > maxChars) return Page(out.toString(), group.first().address)
            out.append(piece)
        }
        return Page(out.toString(), null)
    }

    data class Hit(val address: String, val kind: String, val snippet: String)

    /** Blocks on any branch whose text contains [query] (case-insensitive), optionally only tool calls of [family]. */
    fun search(tree: ChatTree, query: String, family: String? = null, limit: Int = 40): List<Hit> {
        val q = query.lowercase()
        return tree.nodes.asSequence().flatMap { it.blocks }.filter { b ->
            (family == null || b.allCalls.any { it.family == family }) && (q.isBlank() || b.text.lowercase().contains(q))
        }.map { b ->
            val i = b.text.lowercase().indexOf(q).coerceAtLeast(0)
            Hit(b.address, b.kind, oneLine(b.text.substring((i - 80).coerceAtLeast(0), (i + q.length + 120).coerceAtMost(b.text.length)), 240))
        }.take(limit).toList()
    }

    data class ImageEntry(val address: String, val ref: String?, val caption: String?, val conclusion: String?)

    /**
     * The images from [from] to [to] (the whole tree when both are null): where each sits, its reference, what
     * the tool was looking for, and the agent's next words about it (the rest of its block, or the next reply).
     */
    fun images(tree: ChatTree, from: String? = null, to: String? = null): List<ImageEntry> {
        val scope = if (from == null && to == null) tree.nodes.flatMap { it.blocks }
            else tree.span(from ?: tree.headBlocks.first().address, to ?: tree.headBlocks.last().address)
                ?: throw IllegalArgumentException("no stretch ${from}..${to} in this chat")
        return scope.flatMap { b ->
            b.images.map { img ->
                val after = b.text.substringAfter(img.image.ref ?: "\u0000", "").substringAfter(')').trim()
                val conclusion = after.ifBlank {
                    val along = tree.pathTo(b.node).flatMap { it.blocks }
                    along.drop(along.indexOf(b) + 1).firstOrNull { it.role == "ai" && it.images.isEmpty() }?.text.orEmpty()
                }
                ImageEntry(b.address, img.image.ref, img.image.caption, oneLine(conclusion, 300).ifBlank { null })
            }
        }
    }

    private val strayMarker = Regex("""(?m)^<<(source|at):[^\n]*>>\s*$""")

    /**
     * A lens's problems. Every block must cite what it stands for with a `<<source:…>>` line directly under its
     * `<<you>>`/`<<ai>>`/`<<summary>>` line, and every citation must resolve to a stretch of the chat. A source
     * line anywhere else (above the marker, at the end of the block before) is refused, because it would cite
     * nothing and show as text.
     */
    fun lensProblems(tree: ChatTree, lens: String): List<String> {
        val problems = mutableListOf<String>()
        val text = lens.replace("\r\n", "\n").trim()
        if (text.isNotEmpty() && !Regex("^<<(you|ai|summary)>>").containsMatchIn(text))
            problems += "the lens must begin with a <<you>>, <<ai>> or <<summary>> line; each block's <<source:…>> line goes directly under it, not above it"
        val blocks = parseAiChatBlocks(lens)
        if (blocks.isEmpty()) problems += "the lens is empty"
        blocks.forEachIndexed { i, b ->
            if (b.sources.isEmpty()) problems += "block $i (${b.role}: \"${oneLine(b.content, 60)}\") cites nothing: put a <<source:first..last>> line directly under its <<${b.role}>> line"
            if (strayMarker.containsMatchIn(b.content)) problems += "block $i has a <<source:…>> line inside its text; source lines go directly under a block's <<${b.role}>> line, never above the next block's"
            for (s in b.sources) {
                val (a, z) = if (".." in s) s.substringBefore("..") to s.substringAfter("..") else s to s
                if (tree.span(a, z) == null) problems += "block $i cites `$s`, which is not a stretch of this chat"
            }
        }
        return problems.distinct()
    }

    /** How much of the chat a lens covers, and how much shorter it is than what it cites. */
    fun lensStats(tree: ChatTree, lens: String): Map<String, Any> {
        val blocks = parseAiChatBlocks(lens)
        val cited = LinkedHashSet<String>()
        for (b in blocks) for (s in b.sources) {
            val (a, z) = if (".." in s) s.substringBefore("..") to s.substringAfter("..") else s to s
            tree.span(a, z)?.forEach { cited += it.address }
        }
        val all = tree.nodes.flatMap { it.blocks }
        val citedChars = all.filter { it.address in cited }.sumOf { it.text.length }
        val lensChars = blocks.sumOf { it.content.length }
        return mapOf(
            "blocks" to blocks.size,
            "citedBlocks" to cited.size,
            "chatBlocks" to all.size,
            "lensChars" to lensChars,
            "citedChars" to citedChars,
            "compression" to if (lensChars == 0) 0.0 else Math.round(citedChars * 10.0 / lensChars) / 10.0,
        )
    }

    private val blobRef = Regex("""ai4img:([0-9a-f]{16}\.[a-z0-9.+-]+)""")

    /** [markdown] with every `ai4img:` reference replaced by the URL [url] gives its blob name (`<sha>.<ext>`). */
    fun withImageUrls(markdown: String, url: (String) -> String): String = blobRef.replace(markdown) { url(it.groupValues[1]) }

    /**
     * [blocks] as chat markup again, each with an `<<at:address>>` line, so the chat panel's renderer draws
     * them as the panel would and tags every block with where it came from (the lens viewer's line ends).
     */
    fun addressedTranscript(blocks: List<ChatBlockEntry>): String = buildString {
        for (b in blocks) {
            append("<<").append(b.role).append(">>\n")
            b.model?.let { append(it.marker()) }
            append("<<at:").append(b.address).append(">>\n")
            if (b.sources.isNotEmpty()) append("<<source:").append(b.sources.joinToString(", ")).append(">>\n")
            append(b.text.trim()).append("\n")
        }
    }
}

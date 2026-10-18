package afield.ai.conversation.chatlog

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * # Chat archive statistics from the command line
 *
 * [ChatArchiveCli] reads saved AI4 chats from document folders through [ChatArchive] and prints a [ChatStats]
 * row per chat (shape, tool calls by family, images, tokens), as a table or JSON, or prints the outline and read
 * views an agent would see ([ChatLogViews]). It never opens a document in Field.
 */

/** Counts over one chat tree: its shape, its tool calls by family, its pictures, its cost. */
@Serializable
data class ChatStats(
    val document: String,
    val box: String,
    val boxName: String?,
    val nodes: Int,
    val offHeadNodes: Int,
    val branches: Int,
    val unrecorded: Boolean,
    val userTurns: Int,
    val abandonedUserTurns: Int,
    val replies: Int,
    val toolCalls: Int,
    val programCalls: Int,
    val failedCalls: Int,
    val failures: Int,
    val families: Map<String, Int>,
    val toolImages: Int,
    val attachments: Int,
    val missingImages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val toolMs: Long,
    val firstTs: Long?,
    val lastTs: Long?,
) {
    companion object {
        fun of(tree: ChatTree): ChatStats {
            val blocks = tree.nodes.flatMap { it.blocks }
            val calls = blocks.flatMap { it.allCalls }
            val images = blocks.flatMap { b -> b.images.map { b.role to it } }
            val times = tree.nodes.mapNotNull { it.ts }
            return ChatStats(
                document = tree.document, box = tree.box, boxName = tree.boxName,
                nodes = tree.nodes.size,
                offHeadNodes = tree.nodes.count { !it.onHead },
                branches = tree.tips.size,
                unrecorded = tree.nodes.any { it.type == "unrecorded" },
                userTurns = blocks.count { it.turn != null },
                abandonedUserTurns = tree.nodes.filter { !it.onHead }.sumOf { n -> n.blocks.count { it.role == "you" } },
                replies = blocks.count { it.kind == "reply" },
                toolCalls = blocks.count { it.call != null },
                programCalls = blocks.sumOf { it.calls.size },
                failedCalls = calls.count { !it.call.ok },
                failures = blocks.count { it.kind == "failure" },
                families = calls.groupingBy { it.family }.eachCount().toSortedMap(),
                toolImages = images.count { it.first == "ai" },
                attachments = images.count { it.first == "you" },
                missingImages = images.count { it.second.resolved == false },
                inputTokens = tree.nodes.sumOf { it.inputTokens ?: 0 },
                outputTokens = tree.nodes.sumOf { it.outputTokens ?: 0 },
                toolMs = blocks.sumOf { it.call?.call?.ms ?: 0 },
                firstTs = times.minOrNull(), lastTs = times.maxOrNull(),
            )
        }
    }
}

/**
 * `afield.ai.conversation.chatlog.ChatArchiveCli`: read saved AI4 chats from document folders and report on them. It reads
 * only; it never opens a document in Field.
 *
 * ```
 * --document <dir>            a document folder (repeatable)
 * --workspace <dir>           every document folder in a workspace...
 * --match <glob>              ...whose name matches, e.g. 'house-machine-*'
 * --out <dir>                 also write <document>__<box>.chat-index.json per chat (block index, no text)
 * --json                      print the stats as JSON instead of a table
 * --view outline|read         print what an agent reads of each chat (ChatLogViews) instead of stats
 * ```
 */
object ChatArchiveCli {
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    @JvmStatic
    fun main(args: Array<String>) {
        val documents = mutableListOf<File>()
        var workspace: File? = null
        var match: String? = null
        var out: File? = null
        var asJson = false
        var view: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i++]) {
                "--document" -> documents += File(args[i++])
                "--workspace" -> workspace = File(args[i++])
                "--match" -> match = args[i++]
                "--out" -> out = File(args[i++])
                "--json" -> asJson = true
                "--view" -> view = args[i++]
                else -> error("unknown argument $a (see ChatArchiveCli's KDoc)")
            }
        }
        workspace?.let { ws ->
            val glob = match?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
            documents += (ws.listFiles() ?: emptyArray())
                .filter { it.isDirectory && File(it, "metadata.edn").isFile }
                .filter { glob == null || glob.matches(Paths.get(it.name)) }
        }
        require(documents.isNotEmpty()) { "no documents: pass --document <dir> or --workspace <dir>" }

        if (view != null) {
            for (doc in documents.sortedWith(compareBy(::naturalKey))) for (chat in ChatArchive.chats(doc)) {
                val tree = ChatArchive.tree(chat)
                println(when (view) {
                    "outline" -> ChatLogViews.outline(tree)
                    "read" -> ChatLogViews.read(tree, null, null, maxChars = Int.MAX_VALUE).text
                    else -> error("--view outline or read")
                })
            }
            return
        }
        val stats = documents.sortedWith(compareBy(::naturalKey)).flatMap { doc ->
            ChatArchive.chats(doc).map { chat ->
                val tree = ChatArchive.tree(chat)
                out?.let { dir ->
                    dir.mkdirs()
                    File(dir, "${chat.document}__${chat.boxId}.chat-index.json").writeText(json.encodeToString(tree))
                }
                ChatStats.of(tree)
            }
        }
        if (asJson) println(json.encodeToString(stats)) else print(table(stats))
    }

    /** `house-machine-10` after `house-machine-9`. */
    private fun naturalKey(f: File): String = f.name.replace(Regex("\\d+")) { it.value.padStart(6, '0') }

    fun table(stats: List<ChatStats>): String {
        val families = stats.flatMap { it.families.keys }.distinct().sortedByDescending { f -> stats.sumOf { it.families[f] ?: 0 } }
        val header = listOf("document", "chat", "turns", "rewound", "branches", "replies", "calls", "in-prog", "failed", "images", "attach", "missing", "tok in", "tool time") + families
        val rows = stats.map { s ->
            listOf(
                s.document, s.boxName ?: s.box.take(8), "${s.userTurns}", "${s.abandonedUserTurns}", "${s.branches}" + if (s.unrecorded) "*" else "",
                "${s.replies}", "${s.toolCalls}", "${s.programCalls}", "${s.failedCalls + s.failures}", "${s.toolImages}", "${s.attachments}",
                "${s.missingImages}", "%.1fM".format(s.inputTokens / 1e6), "%.1fh".format(s.toolMs / 3.6e6),
            ) + families.map { "${s.families[it] ?: 0}" }
        }
        val widths = header.indices.map { c -> (rows.map { it[c] } + header[c]).maxOf { it.length } }
        fun line(cells: List<String>) = cells.mapIndexed { c, v -> if (c < 2) v.padEnd(widths[c]) else v.padStart(widths[c]) }.joinToString("  ")
        return buildString {
            appendLine(line(header))
            rows.forEach { appendLine(line(it)) }
            if (stats.any { it.unrecorded }) appendLine("* the transcript has text the session tree has not recorded yet")
        }
    }
}

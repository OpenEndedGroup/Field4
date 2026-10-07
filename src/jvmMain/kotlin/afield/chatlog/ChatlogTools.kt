package afield.chatlog

import afield.Box
import afield.ai.core.aiTool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * # Chat-log tools: AI4 agents that read chats and write lenses
 *
 * A box hands its document's AI4 agents a chat archive by running, once:
 * ```kotlin
 * registerChatlogTools(File("/Users/marc/house-machine/workspace"))
 * ```
 * Agents then see `chatlog.list`, `chatlog.outline`, `chatlog.read`, `chatlog.search`, `chatlog.images` and
 * `lens.write`, `lens.read`, `lens.list`. The archive is only read (see [ChatArchive]); the chats can live in
 * another workspace, including one a person is working in.
 *
 * A **lens** is a shorter chat written over a longer one: the same `<<you>>`/`<<ai>>` markup, so the chat panel
 * renders it, with a `<<source:a..b>>` line at the top of each block saying which stretch of the chat it
 * stands for. Lenses are saved in [lensDir], by default this document's `lenses/` folder, as
 * `<chat>/<name>.md` beside a `<name>.json` record of the brief that made it.
 *
 * Chats are named `<document>` when the document has one chat, otherwise `<document>/<box name or id>`. A lens is
 * itself a chat, named `<chat>#<lens>`: the tools outline, read and cite it like any other, so a lens can be written
 * over a lens (`house-machine-10#episodes`), and the viewer shows the whole chain.
 *
 * A **series** runs several chats end to end, as one chat: `series:<glob>` takes every chat whose name matches
 * (`series:house-machine-*`), and `series:<glob>@<lens>` takes each one's lens of that name instead, skipping chats
 * that have none. A series is outlined, read and cited like a chat (its addresses are its chats' own), so a lens
 * over a series (`series:house-machine-*@storyline#arc`) tells the story across them.
 */
private const val CHAT_PARAM = "chat name from chatlog.list; <chat>#<lens> for a lens; series:<glob> for several chats end to end " +
    "(series:house-machine-*), or series:<glob>@<lens> for each one's lens of that name"

fun Box.registerChatlogTools(vararg roots: File, lensDir: File? = null) {
    val store = ChatlogStore(roots.toList(), lensDir ?: File(inside.filename, "lenses"))
    ChatLensViewer.register(inside.filename, store)

    aiTool("chatlog.list") {
        describe("List the saved AI4 chats in the archive, with their size: turns, replies, tool calls, images, and a text-token estimate.")
        action {
            store.all().joinToString("\n") { (id, tree) ->
                val s = ChatStats.of(tree)
                val words = tree.nodes.flatMap { it.blocks }.filter { it.role == "you" || it.role == "ai" }.sumOf { it.text.length }
                "$id · ${s.userTurns} turns (${s.abandonedUserTurns} rewound) · ${s.replies} replies · ${s.toolCalls + s.programCalls} tool calls · " +
                    "${s.toolImages} images · ~${words / 4} tokens of conversation" +
                    store.lenses(id).takeIf { it.isNotEmpty() }?.let { names -> " · lenses: " + names.joinToString { "$id#$it" } }.orEmpty()
            }.ifBlank { "No chats under ${store.roots.joinToString()}." }
        }
    }

    aiTool("chatlog.outline") {
        describe(
            "The map of one chat: every user turn with its time and full request, then what followed, each with its address: " +
                "replies as their first line, tool activity as round lines (calls, time, failures, notes, edits, looks), images as captions; " +
                "then abandoned (rewound) branches. Start here."
        )
        val chat by param<String>(CHAT_PARAM)
        action { ChatLogViews.outline(store.tree(chat())) }
    }

    aiTool("chatlog.read") {
        describe(
            "Read a stretch of a chat as text, each block headed by its [address]. Omit from/to for the whole current branch. " +
                "detail=text gives requests and replies in full and tool activity as round lines; detail=full adds each tool block's text. " +
                "If the page stops early it says where to continue."
        )
        val chat by param<String>(CHAT_PARAM)
        val from by param<String>("first block address, or empty for the start", default = "")
        val to by param<String>("last block address, or empty for the end", default = "")
        val detail by param<String>("text or full", default = "text")
        val maxChars by param<Int>("page size in characters (at most 40000)", default = 20000)
        action {
            val page = ChatLogViews.read(store.tree(chat()), from().ifBlank { null }, to().ifBlank { null }, detail(), maxChars().coerceIn(2000, 40_000))
            page.text + (page.next?.let { "\n…continues: read again with from=$it" } ?: "")
        }
    }

    aiTool("chatlog.search") {
        describe("Find blocks on any branch whose text contains a phrase, optionally only blocks with tool calls of one family (notes, recipes, design, builds, looking, reading, computing).")
        val chat by param<String>(CHAT_PARAM)
        val query by param<String>("phrase to find (case-insensitive); empty with a family lists that family's blocks", default = "")
        val family by param<String>("tool family, or empty", default = "")
        action {
            ChatLogViews.search(store.tree(chat()), query(), family().ifBlank { null })
                .joinToString("\n") { "[${it.address}] ${it.kind}: ${it.snippet}" }.ifBlank { "No matches." }
        }
    }

    aiTool("chatlog.images") {
        describe(
            "The tool images in a chat or a stretch of it: address, reference (ai4img:…), what the tool was looking for, and what the agent " +
                "concluded about it. view=true also shows you up to 4 of the pictures themselves."
        )
        val chat by param<String>(CHAT_PARAM)
        val from by param<String>("first block address, or empty", default = "")
        val to by param<String>("last block address, or empty", default = "")
        val view by param<Boolean>("also return the first few pictures as images", default = false)
        action {
            val tree = store.tree(chat())
            val entries = ChatLogViews.images(tree, from().ifBlank { null }, to().ifBlank { null })
            val text = entries.joinToString("\n") { e ->
                "[${e.address}] ${e.ref ?: "inline image"} — looking for: ${e.caption ?: "(no caption)"}\n    concluded: ${e.conclusion ?: "(nothing said)"}"
            }.ifBlank { "No images." }
            if (!view()) return@action text
            val urls = entries.mapNotNull { e -> e.ref?.let { store.dataUrl(chat(), it) } }.take(4)
            if (urls.isEmpty()) text else images(urls, text)
        }
    }

    aiTool("lens.write") {
        describe(
            "Save a lens: a shorter chat over this one, in chat markup: <<you>> for the user's words, <<ai>> for the agent's, and " +
                "<<summary>> for a block that condenses several turns of both at once. Every block cites the stretch it stands for " +
                "with a <<source:first..last>> line DIRECTLY UNDER its marker line (comma-separate several stretches), using addresses " +
                "from chatlog.outline or chatlog.read:\n<<summary>>\n<<source:t-a1.b0..t-a1.b9>>\nWhat happened, briefly.\n<<you>>\n" +
                "<<source:t-b2.b0>>\nThe next request.\nImages can be reused as ![caption](ai4img:…). Refused, with the reasons, when a " +
                "block cites nothing or a citation does not resolve. Writing the same name again replaces it."
        )
        val chat by param<String>(CHAT_PARAM)
        val name by param<String>("lens name, e.g. storyline")
        val markdown by param<String>("the lens, in chat markup")
        val brief by param<String>("the brief this lens answers, in a sentence or two", default = "")
        action {
            val tree = store.tree(chat())
            val problems = ChatLogViews.lensProblems(tree, markdown())
            if (problems.isNotEmpty()) return@action toolError(problems)
            val stats = ChatLogViews.lensStats(tree, markdown())
            val file = store.writeLens(chat(), name(), markdown(), brief(), stats)
            "Saved ${file.name}: ${stats["blocks"]} blocks citing ${stats["citedBlocks"]} of ${stats["chatBlocks"]} chat blocks, " +
                "${stats["compression"]}× shorter than what it cites."
        }
    }

    aiTool("lens.read") {
        describe("Read a saved lens and the brief it answers.")
        val chat by param<String>(CHAT_PARAM)
        val name by param<String>("lens name")
        action { store.readLens(chat(), name()) ?: toolError("No lens '${name()}' for ${chat()}; lens.list shows what exists.") }
    }

    aiTool("lens.list") {
        describe("List the lenses saved for a chat.")
        val chat by param<String>(CHAT_PARAM)
        action { store.lenses(chat()).joinToString("\n").ifBlank { "No lenses yet for ${chat()}." } }
    }
}

private val prettyJson = Json { prettyPrint = true }

/** Finds chats under [roots], keeps each tree until its files change, and keeps lenses under [lensDir]. */
class ChatlogStore(val roots: List<File>, val lensDir: File) {
    private val cache = HashMap<String, Pair<Long, ChatTree>>()

    private fun chats(): List<Pair<String, ChatArchive.StoredChat>> {
        val all = roots.flatMap { root ->
            val docs = if (File(root, "metadata.edn").isFile) listOf(root)
                else (root.listFiles() ?: emptyArray()).filter { File(it, "metadata.edn").isFile }
            docs.sortedBy { it.name.replace(Regex("\\d+")) { m -> m.value.padStart(6, '0') } }.flatMap(ChatArchive::chats)
        }
        val perDoc = all.groupingBy { it.document }.eachCount()
        return all.map { c -> (if (perDoc[c.document] == 1) c.document else "${c.document}/${c.boxName ?: c.boxId}") to c }
    }

    private fun stamp(c: ChatArchive.StoredChat): Long =
        (c.documentDir.listFiles() ?: emptyArray()).filter { it.name.startsWith(c.boxId) || it.name == "metadata.edn" }.maxOfOrNull { it.lastModified() } ?: 0L

    fun all(): List<Pair<String, ChatTree>> = chats().map { (id, c) -> id to treeOf(id, c) }

    private fun treeOf(id: String, c: ChatArchive.StoredChat): ChatTree {
        val s = stamp(c)
        cache[id]?.let { (at, tree) -> if (at == s) return tree }
        return ChatArchive.tree(c).also { cache[id] = s to it }
    }

    private fun stored(id: String): ChatArchive.StoredChat {
        val chats = chats()
        return chats.firstOrNull { it.first == id }?.second
            ?: chats.firstOrNull { (_, c) -> "${c.document}/${c.boxId}" == id }?.second
            ?: throw IllegalArgumentException("No chat '$id'. Chats: ${chats.joinToString { it.first }}")
    }

    /** The chat [id]; for `<chat>#<lens>` (any depth), that lens read as a chat; for `series:…`, a series. */
    fun tree(id: String): ChatTree {
        if ('#' !in id && id.startsWith("series:")) return series(id)
        if ('#' !in id) return treeOf(id, stored(id))
        val below = id.substringBeforeLast('#')
        val name = id.substringAfterLast('#')
        val markdown = lensMarkdown(below, name)
            ?: throw IllegalArgumentException("No lens '$name' over $below; lens.list shows what exists.")
        // The chat at the bottom of the chain holds the pictures; a series's are in its members, and the
        // viewer's blob route looks in all of them ([blobFile]), so the first member stands in here.
        val root = id.substringBefore('#')
        val bottom = if (root.startsWith("series:")) stored(seriesMembers(root).first().substringBefore('#')) else stored(root)
        return ChatArchive.lensTree(bottom, "lens:$name", "$name lens over $below", markdown)
    }

    /** The chats a series takes, in order: `series:<glob>` or `series:<glob>@<lens>`. */
    fun seriesMembers(id: String): List<String> {
        val spec = id.removePrefix("series:")
        val glob = spec.substringBefore('@')
        val lens = spec.substringAfter('@', "").ifBlank { null }
        val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$glob")
        return chats().map { it.first }.filter { matcher.matches(java.nio.file.Paths.get(it)) }
            .filter { lens == null || lensMarkdown(it, lens) != null }
            .map { if (lens == null) it else "$it#$lens" }
    }

    /**
     * Several chats (or their lenses) as one: each member's current branch in turn, the first node of each hung
     * from the last node of the one before, so a stretch can run across chats. Addresses are the members' own;
     * lens nodes are renamed `lens:<chat>#<lens>` so two houses' storylines do not share addresses.
     */
    private fun series(id: String): ChatTree {
        val members = seriesMembers(id)
        require(members.isNotEmpty()) { "series $id matches no chats (chats: ${chats().joinToString { it.first }})" }
        val nodes = mutableListOf<ChatNode>()
        for (member in members) {
            val tree = if ('#' in member) {
                val name = member.substringAfterLast('#')
                ChatArchive.lensTree(stored(member.substringBefore('#')), "lens:$member", "$name lens over ${member.substringBeforeLast('#')}",
                    lensMarkdown(member.substringBeforeLast('#'), name)!!)
            } else tree(member)
            tree.headPath.forEachIndexed { i, n ->
                nodes += n.copy(parent = if (i == 0) nodes.lastOrNull()?.id else n.parent, chat = member)
            }
        }
        return ChatTree("series", id, id, nodes.last().id, nodes)
    }

    /** The chats of a chain from the top: `a#b#c` gives `a#b#c`, `a#b`, `a`. */
    fun chain(id: String): List<String> = generateSequence(id) { it.takeIf { '#' in it }?.substringBeforeLast('#') }.toList()

    /** An `ai4img:` reference as a data URL, from the chat's document blobs. */
    fun dataUrl(id: String, ref: String): String? {
        val file = blobFile(id, ref.removePrefix("ai4img:")) ?: return null
        val type = file.extension.let { if (it == "jpg") "jpeg" else it }
        return "data:image/$type;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }

    private fun dirFor(id: String) = File(lensDir, id.replace(Regex("[/:*?\\\\]"), "_"))

    private fun safe(name: String): String {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(name)) { "lens name '$name': use letters, digits, '-' or '_'" }
        return name
    }

    fun writeLens(id: String, name: String, markdown: String, brief: String, stats: Map<String, Any>): File {
        val dir = dirFor(id).apply { mkdirs() }
        val md = File(dir, "${safe(name)}.md")
        md.writeText(markdown.trimEnd() + "\n")
        File(dir, "$name.json").writeText(prettyJson.encodeToString(buildJsonObject {
            put("chat", id)
            put("name", name)
            put("brief", brief)
            put("written", System.currentTimeMillis())
            put("model", System.getenv("AFIELD_AI_MODEL").orEmpty())
            put("stats", buildJsonObject { stats.forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) } })
        }))
        return md
    }

    fun readLens(id: String, name: String): String? {
        val dir = dirFor(id)
        val md = File(dir, "${safe(name)}.md").takeIf { it.isFile } ?: return null
        val record = File(dir, "$name.json").takeIf { it.isFile }?.readText().orEmpty()
        return "Record: $record\n\n${md.readText()}"
    }

    /** The lens itself, or null. */
    fun lensMarkdown(id: String, name: String): String? = File(dirFor(id), "${safe(name)}.md").takeIf { it.isFile }?.readText()

    /** The record [writeLens] saved beside a lens (brief, model, stats), or null. */
    fun lensRecord(id: String, name: String): kotlinx.serialization.json.JsonObject? =
        File(dirFor(id), "${safe(name)}.json").takeIf { it.isFile }
            ?.let { runCatching { Json.parseToJsonElement(it.readText()) as? kotlinx.serialization.json.JsonObject }.getOrNull() }

    /** Where a chat's `ai4img:` pictures are. */
    fun blobDir(id: String): File = stored(id.substringBefore('#')).blobDir

    /** A picture by blob name for [id]; in a series, from whichever member chat holds it. */
    fun blobFile(id: String, blob: String): File? {
        val root = id.substringBefore('#')
        val dirs = if (root.startsWith("series:")) seriesMembers(root).map { blobDir(it) } else listOf(blobDir(root))
        return dirs.map { File(it, blob) }.firstOrNull { it.isFile }
    }

    fun lenses(id: String): List<String> =
        (dirFor(id).listFiles() ?: emptyArray()).filter { it.extension == "md" }.map { it.nameWithoutExtension }.sorted()
}

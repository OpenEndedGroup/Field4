package afield.ai.conversation.chatlog

import afield.ai.conversation.AiChatBlock
import afield.ai.conversation.AiChatImage
import afield.ai.conversation.AiChatToolCall
import afield.ai.conversation.AiChatToolFamilies
import afield.document.persistence.Edn
import afield.document.persistence.EdnValue
import afield.ai.conversation.images
import afield.ai.conversation.parseAiChatBlocks
import afield.ai.conversation.programCalls
import afield.ai.conversation.toolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * # The chat archive: a saved AI4 conversation, read from disk as a tree
 *
 * An AI4 chat is saved as two box properties (`AiBoxConversation`, `AiSessionTree`):
 * - `ai4_chat_history`, the transcript the panel shows: the current branch only, images inline.
 * - `ai4_session`, the session tree: every turn as a node with a parent, including turns rewound away, with
 *   a timestamp, the model, token usage and images as `ai4img:` references into `<document>/ai4_blobs/`.
 *
 * This reads both from a document folder without opening the document. No Field server or box is involved,
 * so it is safe to point at a document someone is working in. The tree is the source, and the transcript is
 * used to check it: transcript text past the tree's head (a verdict appended by an agent task, an
 * interrupted turn) becomes an `unrecorded` node, and a transcript that no longer starts with the head (a
 * hand edit) becomes an `unrecorded` root. That is what `AiSessionTree.reconcile` would record next time.
 *
 * ## Addresses
 *
 * Every block has an address, `<nodeId>.b<index>`: the session-tree node that added it, and its position in
 * that node's markdown. Node ids are written once and never reused, so an address stays valid as the chat
 * grows, and a block in an abandoned branch has one just as a block on the current branch does. Summaries
 * written over a chat (lenses) cite these addresses.
 */
object ChatArchive {
    const val HISTORY = "ai4_chat_history"
    const val SESSION = "ai4_session"

    /** A chat as stored: one box's transcript and session tree. */
    class StoredChat(val documentDir: File, val boxId: String, val boxName: String?, val history: String, val session: String) {
        val document: String get() = documentDir.name
        val blobDir: File get() = File(documentDir, "ai4_blobs")
    }

    /** Every box in [documentDir] with a saved AI4 chat. */
    fun chats(documentDir: File): List<StoredChat> {
        val metadata = File(documentDir, "metadata.edn")
        val fromMetadata = if (metadata.isFile) boxesOf(metadata.readText()).mapNotNull { (id, props) ->
            val history = propertyText(documentDir, props[HISTORY]).orEmpty()
            val session = propertyText(documentDir, props[SESSION]).orEmpty()
            if (history.isBlank() && session.isBlank()) null
            else StoredChat(documentDir, id, props["name"] as? String, history, session)
        } else emptyList()
        // A sidecar the metadata does not name (a document saved by a version that inlined it, or a hand copy)
        // is still a chat.
        val known = fromMetadata.map { it.boxId }.toSet()
        val orphans = (documentDir.listFiles() ?: emptyArray())
            .mapNotNull { f -> Regex("""^(.+)_${HISTORY}_\.md$""").find(f.name)?.groupValues?.get(1) }
            .filter { it !in known }
            .map { id ->
                StoredChat(documentDir, id, null, File(documentDir, "${id}_${HISTORY}_.md").readText(),
                    File(documentDir, "${id}_${SESSION}_.jsonl").takeIf { it.isFile }?.readText().orEmpty())
            }
        return (fromMetadata + orphans).sortedBy { it.boxId }
    }

    private fun boxesOf(edn: String): List<Pair<String, Map<String, Any?>>> {
        val root = runCatching { Edn.read(edn) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val boxes = (root[":boxes"] ?: root["boxes"]) as? List<*> ?: return emptyList()
        return boxes.mapNotNull { b ->
            val m = b as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] ?: m[":id"]) as? String ?: return@mapNotNull null
            val props = (m["properties"] ?: m[":properties"]) as? Map<*, *> ?: emptyMap<Any, Any>()
            id to props.entries.associate { (k, v) -> k.toString().removePrefix(":") to v }
        }
    }

    /** A property's text: inline, a `#afield/file` sidecar, or `#afield/file-inline`. */
    private fun propertyText(documentDir: File, value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is EdnValue.Tagged -> when (val payload = value.value) {
            is EdnValue.Str -> File(documentDir, payload.value).takeIf { it.isFile }?.readText()
            is EdnValue.MapVal -> {
                val fields = payload.values.entries.associate { (k, v) -> (k as? EdnValue.Str)?.value to (v as? EdnValue.Str)?.value }
                fields["path"]?.let { File(documentDir, it) }?.takeIf { it.isFile }?.readText() ?: fields["inline"]
            }
            else -> null
        }
        else -> null
    }

    private val dataUrl = Regex("data:image/([a-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)")

    /** Data URLs replaced by the `ai4img:` references `AiImageBlobs.extract` would give them. */
    internal fun asBlobRefs(markdown: String): String = dataUrl.replace(markdown) { m ->
        val (type, data) = m.destructured
        "ai4img:${sha256(data).take(16)}.$type"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun tree(chat: StoredChat, families: AiChatToolFamilies = ChatToolFamilies.default): ChatTree {
        val lines = chat.session.lineSequence().filter { it.isNotBlank() }.mapNotNull {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }.toList()
        val raw = lines.filter { it.str("t") in NODE_TYPES }.map { RawNode(it.str("id").orEmpty(), it.str("parent"), it.str("t")!!, it.str("markdown").orEmpty(), it) }
            .toMutableList()
        var head = lines.lastOrNull { it.str("t") == "head" }?.str("id")?.takeIf { id -> raw.any { it.id == id } }

        // Reconcile with the transcript, as the session tree itself would on its next turn.
        val transcript = asBlobRefs(chat.history.replace("\r\n", "\n"))
        val rendered = pathOf(raw, head).joinToString("") { it.markdown }
        if (transcript.isNotBlank() && transcript.trimEnd() != rendered.trimEnd()) {
            val grown = head != null && transcript.startsWith(rendered)
            val id = if (grown) "unrecorded-tail" else "unrecorded-root"
            raw += RawNode(id, if (grown) head else null, "unrecorded", if (grown) transcript.substring(rendered.length) else transcript, null)
            head = id
        }

        val headPath = pathOf(raw, head)
        val onHead = headPath.map { it.id }.toSet()
        val parsed = raw.associate { it.id to parseAiChatBlocks(it.markdown) }
        // User turns along the current branch, numbered from 1 as the panel shows them.
        val turnOf = HashMap<String, Int>()
        var ordinal = 0
        for (n in headPath) parsed.getValue(n.id).forEachIndexed { i, b -> if (b.role == "you") turnOf["${n.id}.b$i"] = ++ordinal }
        val nodes = raw.map { n ->
            val usage = n.json?.get("usage") as? JsonObject
            ChatNode(
                id = n.id, parent = n.parent, type = n.type, ts = n.json?.long("ts"), onHead = n.id in onHead,
                user = n.json?.str("user"),
                model = (n.json?.get("model") as? JsonObject)?.let { it.str("response") ?: it.str("requested") },
                inputTokens = usage?.long("in"), outputTokens = usage?.long("out"),
                rounds = usage?.long("rounds")?.toInt()?.takeIf { it >= 0 },
                blocks = parsed.getValue(n.id).mapIndexed { i, b ->
                    val address = "${n.id}.b$i"
                    entry(chat, address, n.id, b, families, turnOf[address])
                },
            )
        }
        return ChatTree(chat.document, chat.boxId, chat.boxName, head, nodes)
    }

    /**
     * A lens read as a chat: one node, [nodeId], holding the lens's blocks, so a lens can be outlined, read, cited
     * and written over like any chat. Its pictures are the chat's, so [chat] is the chat at the bottom of the chain.
     */
    fun lensTree(chat: StoredChat, nodeId: String, title: String, markdown: String, families: AiChatToolFamilies = ChatToolFamilies.default): ChatTree {
        var ordinal = 0
        val blocks = parseAiChatBlocks(markdown).mapIndexed { i, b ->
            entry(chat, "$nodeId.b$i", nodeId, b, families, if (b.role == "you") ++ordinal else null)
        }
        val node = ChatNode(id = nodeId, parent = null, type = "lens", onHead = true, blocks = blocks)
        return ChatTree(chat.document, chat.boxId, title, nodeId, listOf(node))
    }

    private fun entry(chat: StoredChat, address: String, nodeId: String, b: AiChatBlock, families: AiChatToolFamilies, turn: Int?): ChatBlockEntry {
        val call = b.toolCall()
        val kind = when {
            b.role == "you" -> "user"
            b.role == "ai" -> "reply"
            b.role == "summary" -> "summary"
            b.role == "queued" -> "queued"
            call != null -> "tool"
            b.title.startsWith("Failed") || b.title.startsWith("Error") -> "failure"
            b.title.contains(" · ") -> "build"
            else -> "status"
        }
        return ChatBlockEntry(
            address = address, node = nodeId, turn = turn, role = b.role, kind = kind,
            title = b.title.take(240),
            call = call?.let { ChatCall(it, families.familyOf(it.name)) },
            calls = b.programCalls().map { ChatCall(it, families.familyOf(it.name)) },
            images = b.images().map { img -> ChatImageEntry(img, resolved(chat, img)) },
            chars = b.content.length,
            model = b.model,
            sources = b.sources,
            text = b.content,
        )
    }

    private fun resolved(chat: StoredChat, img: AiChatImage): Boolean? {
        val ref = img.ref ?: return true
        if (!ref.startsWith("ai4img:")) return null
        return File(chat.blobDir, ref.removePrefix("ai4img:")).isFile
    }

    private val NODE_TYPES = setOf("import", "append", "turn")

    private class RawNode(val id: String, val parent: String?, val type: String, val markdown: String, val json: JsonObject?)

    private fun pathOf(nodes: List<RawNode>, id: String?): List<RawNode> {
        val byId = nodes.associateBy { it.id }
        val out = ArrayDeque<RawNode>()
        val seen = HashSet<String>()
        var cursor = id
        while (cursor != null && seen.add(cursor)) {
            val n = byId[cursor] ?: break
            out.addFirst(n)
            cursor = n.parent
        }
        return out.toList()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/** A tool call with the family [AiChatToolFamilies] puts it in. */
@Serializable
data class ChatCall(val call: AiChatToolCall, val family: String)

@Serializable
data class ChatImageEntry(
    val image: AiChatImage,
    /** For an `ai4img:` reference, whether its blob is on disk; true for an inline image; null otherwise. */
    val resolved: Boolean?,
)

/**
 * One block of a chat with what the archive knows about it. [kind] is `user`, `reply`, `tool` (one call; a
 * tool program's own calls are in [calls]), `build` (a study-build status line), `failure` (the turn
 * failed), `status` (progress with no tool) or `queued`.
 */
@Serializable
data class ChatBlockEntry(
    val address: String,
    val node: String,
    /** For a user block on the current branch, its turn number from 1. */
    val turn: Int? = null,
    val role: String,
    val kind: String,
    val title: String,
    val call: ChatCall? = null,
    val calls: List<ChatCall> = emptyList(),
    val images: List<ChatImageEntry> = emptyList(),
    val chars: Int,
    /** The model a reply records (`<<model:…>>`), so a re-rendered block keeps its badge. */
    val model: afield.ai.conversation.AiChatModelIdentity? = null,
    /** For a block of a lens, the stretches it stands for (`<<source:…>>`). */
    val sources: List<String> = emptyList(),
    /** The block's markdown, images as references; left out of the index file. */
    @kotlinx.serialization.Transient
    val text: String = "",
) {
    /** The call itself and a program's calls: every tool call this block records. */
    val allCalls: List<ChatCall> get() = listOfNotNull(call) + calls
    val failed: Boolean get() = kind == "failure" || allCalls.any { !it.call.ok }
}

/**
 * A session-tree node. [type] is `turn`, `append`, `import` or, for transcript text the tree has not recorded
 * yet, `unrecorded`. [onHead] says whether it is on the current branch.
 */
@Serializable
data class ChatNode(
    val id: String,
    val parent: String?,
    val type: String,
    val ts: Long? = null,
    val onHead: Boolean,
    val user: String? = null,
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val rounds: Int? = null,
    val blocks: List<ChatBlockEntry>,
    /** In a series ([ChatlogStore]), the chat this node comes from. */
    val chat: String? = null,
)

@Serializable
data class ChatTree(
    val document: String,
    val box: String,
    val boxName: String?,
    val head: String?,
    val nodes: List<ChatNode>,
) {
    fun node(id: String): ChatNode? = nodes.firstOrNull { it.id == id }
    fun children(id: String?): List<ChatNode> = nodes.filter { it.parent == id }
    val roots: List<ChatNode> get() = children(null)

    /** Tips of every branch: nodes nothing else continues from. */
    val tips: List<ChatNode> get() {
        val parents = nodes.mapNotNull { it.parent }.toSet()
        return nodes.filter { it.id !in parents }
    }

    /** Nodes along the current branch, root first. */
    val headPath: List<ChatNode> get() = nodes.filter { it.onHead }.let { on ->
        val byId = on.associateBy { it.id }
        generateSequence(head?.let(byId::get)) { n -> n.parent?.let(byId::get) }.toList().reversed()
    }

    fun block(address: String): ChatBlockEntry? =
        node(address.substringBeforeLast(".b"))?.blocks?.firstOrNull { it.address == address }

    /** Nodes from the root to [id], root first. */
    fun pathTo(id: String): List<ChatNode> {
        val byId = nodes.associateBy { it.id }
        val seen = HashSet<String>()
        return generateSequence(byId[id]) { n -> n.parent?.takeIf(seen::add)?.let(byId::get) }.toList().reversed()
    }

    /**
     * The blocks from [from] to [to] inclusive, in reading order along the branch that leads to [to]: so a range
     * may start on the shared trunk and end on an abandoned branch. Null when either end does not exist or
     * [from] does not come before [to] on that branch.
     */
    fun span(from: String, to: String): List<ChatBlockEntry>? {
        val end = block(to) ?: return null
        val along = pathTo(end.node).flatMap { it.blocks }
        val i = along.indexOfFirst { it.address == from }
        val j = along.indexOfFirst { it.address == to }
        return if (i < 0 || j < i) null else along.subList(i, j + 1)
    }

    /** The current branch's blocks in reading order. */
    val headBlocks: List<ChatBlockEntry> get() = headPath.flatMap { it.blocks }
}

/** The family table the archive uses unless given another: the `ai4-chat-tool-families.json` resource. */
object ChatToolFamilies {
    val default: AiChatToolFamilies by lazy {
        AiChatToolFamilies.parse(checkNotNull(ChatToolFamilies::class.java.getResourceAsStream("/ai4-chat-tool-families.json")) {
            "ai4-chat-tool-families.json is missing from the classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

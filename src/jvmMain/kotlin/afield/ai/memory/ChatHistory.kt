package afield.ai.memory

import afield.ai.conversation.chatlog.ChatLogViews
import afield.ai.conversation.chatlog.ChatlogStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The saved AI4 chats of one workspace, searchable from any AI4 turn: the transcript layer under [UserMemory].
 *
 * It reads the same session trees as the chat archive (`ChatArchive`, `registerChatlogTools`), straight from
 * the document folders and without opening them, every branch included. A block is cited as
 * `<chat>@<address>`, for example `firstDocument/sketch@t-a3.b2`, which is what a memory note's `sources`
 * hold; [read] takes that form back.
 */
class ChatHistory private constructor(val workspace: File) {
    // ChatlogStore caches parsed trees by file stamp and is not thread-safe; one store per workspace, locked.
    private val store = ChatlogStore(listOf(workspace), File(workspace, ".ai4-history-lenses-unused"))

    /** One matching block: the chat it is in, its address there, its kind, and a snippet around the match. */
    data class Hit(val chat: String, val address: String, val kind: String, val snippet: String) {
        val citation: String get() = "$chat@$address"
    }

    companion object {
        private val byRoot = ConcurrentHashMap<String, ChatHistory>()

        /** The history of the workspace holding the document folder [documentDir]. */
        fun forDocument(documentDir: File): ChatHistory = forWorkspace(documentDir.absoluteFile.parentFile ?: documentDir)

        fun forWorkspace(workspace: File): ChatHistory =
            byRoot.computeIfAbsent(workspace.absoluteFile.normalize().path) { ChatHistory(File(it)) }

        /**
         * The history an interactive AI4 turn on the document at [documentPath] is offered: null when memory is
         * off (`AFIELD_AI4_MEMORY`) or there is no document.
         */
        fun offeredFor(documentPath: String?): ChatHistory? =
            if (!UserMemory.enabled() || documentPath.isNullOrBlank()) null else forDocument(File(documentPath))

        /** Splits `chat@address` into its two halves; a bare chat name has a null address. */
        fun splitCitation(citation: String): Pair<String, String?> {
            val at = citation.lastIndexOf('@')
            return if (at <= 0) citation.trim() to null else citation.substring(0, at).trim() to citation.substring(at + 1).trim().ifBlank { null }
        }
    }

    /** The chats, as `name · N turns`. */
    fun chats(): List<String> = synchronized(this) { store.all().map { (id, tree) -> "$id · ${tree.nodes.size} turns" } }

    /**
     * Blocks containing [query] (case-insensitive), at most [limit], walking the chats in document-name order.
     * [chat] narrows to one chat, or to chats whose name starts with it.
     */
    fun search(query: String, chat: String = "", limit: Int = 30): List<Hit> = synchronized(this) {
        val out = mutableListOf<Hit>()
        for ((id, tree) in store.all()) {
            if (chat.isNotBlank() && id != chat && !id.startsWith(chat)) continue
            for (h in ChatLogViews.search(tree, query, null, limit - out.size)) {
                out += Hit(id, h.address, h.kind, h.snippet)
            }
            if (out.size >= limit) break
        }
        out
    }

    /**
     * A stretch of [chat] as text, each block headed by its address. [from] may be a `chat@address` citation, in
     * which case [chat] may be blank. With [to] blank and [from] set, reads from [from] to the end of its branch.
     */
    fun read(chat: String, from: String, to: String, maxChars: Int): ChatLogViews.Page = synchronized(this) {
        val (fromChat, fromAddress) = if ('@' in from) splitCitation(from) else chat to from.ifBlank { null }
        val (_, toAddress) = if ('@' in to) splitCitation(to) else chat to to.ifBlank { null }
        val name = chat.ifBlank { fromChat }
        require(name.isNotBlank()) { "name a chat, or pass from as chat@address" }
        val tree = store.tree(name)
        val end = toAddress ?: if (fromAddress != null) {
            // From a block to the end of the branch that block is on: the tip below it, else the head.
            val node = fromAddress.substringBeforeLast(".b")
            tree.tips.firstOrNull { tip -> tree.pathTo(tip.id).any { it.id == node } }?.blocks?.lastOrNull()?.address
        } else null
        ChatLogViews.read(tree, fromAddress, end, "text", maxChars)
    }
}

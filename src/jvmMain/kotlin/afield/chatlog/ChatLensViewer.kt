package afield.chatlog

import afield.Box
import afield.displayOpen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * # The lens viewer
 *
 * A lens beside the chat it summarizes, both drawn by the chat panel's renderer, with a band from each lens
 * block to the stretch of the chat it stands for (`chatLens.js`). A box shows one with
 * ```kotlin
 * showChatLens("house-machine-10", "storyline")
 * ```
 * in a box whose document has run [registerChatlogTools]: the viewer reads chats and lenses through that
 * document's [ChatlogStore], over the routes in `ChatlogRoutes.kt`, which answer only for documents that
 * registered one. The payload is built here, not there, because a `put("…")` in a route-declaring file reads
 * as a route to `ServerSurfaceLedgerTest`.
 */
object ChatLensViewer {
    private val stores = ConcurrentHashMap<String, ChatlogStore>()

    /** Called by [registerChatlogTools]: [document] is the registering document's folder. */
    fun register(document: String, store: ChatlogStore) {
        stores[document] = store
    }

    internal fun store(document: String?): ChatlogStore? = document?.let(stores::get)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /** What the viewer page fetches: the chat's current branch with addresses, and the lens with its record. */
    fun payload(document: String, chat: String, lens: String?): JsonObject {
        val store = store(document) ?: throw IllegalArgumentException("no chat archive registered for $document")
        val tree = store.tree(chat)
        val url = { blob: String -> "/api/chatlog/blob?document=${enc(document)}&chat=${enc(chat)}&ref=${enc(blob)}" }
        val lensMarkdown = lens?.let { store.lensMarkdown(chat, it) ?: throw IllegalArgumentException("no lens '$it' for $chat") }
        return buildJsonObject {
            put("chat", chat)
            put("title", "${tree.document} · ${tree.boxName ?: tree.box}")
            put("source", ChatLogViews.withImageUrls(ChatLogViews.addressedTranscript(tree.headBlocks), url))
            lens?.let { put("lens", it) }
            lensMarkdown?.let { put("lensMarkdown", ChatLogViews.withImageUrls(it, url)) }
            lens?.let { name -> store.lensRecord(chat, name)?.let { put("record", it) } }
        }
    }
}

/** Show [lens] beside [chat] in this box's `lens` output channel. */
fun Box.showChatLens(chat: String, lens: String, height: Int = 900) {
    val id = "chat-lens-" + UUID.randomUUID().toString().take(8)
    val q = { s: String -> URLEncoder.encode(s, Charsets.UTF_8) }
    val src = "/api/chatlog/lens?document=${q(inside.filename)}&chat=${q(chat)}&lens=${q(lens)}"
    display("lens", """
        <div id="$id" class="chat-lens" style="height:${height}px" data-src="$src"></div>
        <script type="module">
            import { mountChatLens } from "/chatLens.js";
            mountChatLens(document.getElementById("$id"));
        </script>
    """.trimIndent())
    // A new channel opens closed; the viewer measures itself to draw its bands, so it needs to be seen.
    displayOpen("lens")
}

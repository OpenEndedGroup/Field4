package afield.ai.conversation.chatlog

import afield.document.graph.Box
import afield.ui.output.displayOpen
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
 * registered one.
 */

/** Holds each registering document's [ChatlogStore] and builds the payload the lens viewer page fetches. */
object ChatLensViewer {
    private val stores = ConcurrentHashMap<String, ChatlogStore>()

    /** Called by [registerChatlogTools]: [document] is the registering document's folder. */
    fun register(document: String, store: ChatlogStore) {
        stores[document] = store
    }

    internal fun store(document: String?): ChatlogStore? = document?.let(stores::get)

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * What the viewer page fetches: one column per level of the chain, top first. [lens] over [chat] gives the
     * lens and the chat; over `<chat>#<lens>` it gives three columns, and so on down to the chat itself. Every
     * block carries its address, and lens blocks their citations, so the page can draw a band between each
     * pair of neighbouring columns.
     */
    fun payload(document: String, chat: String, lens: String): JsonObject {
        val store = store(document) ?: throw IllegalArgumentException("no chat archive registered for $document")
        val top = "$chat#$lens"
        val url = { blob: String -> "/api/chatlog/blob?document=${enc(document)}&chat=${enc(chat)}&ref=${enc(blob)}" }
        val base = store.tree(top.substringBefore('#'))
        return buildJsonObject {
            put("title", "${base.document} · ${base.boxName ?: base.box}")
            put("columns", kotlinx.serialization.json.buildJsonArray {
                for (id in store.chain(top)) {
                    val tree = store.tree(id)
                    val name = id.substringAfterLast('#', "")
                    add(buildJsonObject {
                        put("id", id)
                        put("kind", if ('#' in id) "lens" else "chat")
                        put("name", name.ifEmpty { "${base.document} · ${base.boxName ?: base.box}" })
                        put("markdown", ChatLogViews.withImageUrls(ChatLogViews.addressedTranscript(tree.headBlocks), url))
                        if ('#' in id) store.lensRecord(id.substringBeforeLast('#'), name)?.let { put("record", it) }
                    })
                }
            })
        }
    }
}

/**
 * Show [lens] beside [chat] in this box's `lens` output channel. [chat] may itself be a lens (`<chat>#<lens>`):
 * the viewer then shows every level of the chain, each beside the one it summarizes.
 */
fun Box.showChatLens(chat: String, lens: String, height: Int = 900, width: Int? = null) {
    // One column per level of the chain: give each its reading width unless told otherwise.
    val columns = chat.count { it == '#' } + 2
    val w = width ?: (520 * columns)
    val id = "chat-lens-" + UUID.randomUUID().toString().take(8)
    val q = { s: String -> URLEncoder.encode(s, Charsets.UTF_8) }
    val src = "/api/chatlog/lens?document=${q(inside.filename)}&chat=${q(chat)}&lens=${q(lens)}"
    display("lens", """
        <div id="$id" class="chat-lens" style="height:${height}px;width:${w}px" data-src="$src"></div>
        <script type="module">
            import { mountChatLens } from "/chatLens.js";
            mountChatLens(document.getElementById("$id"));
        </script>
    """.trimIndent())
    // A new channel opens closed; the viewer measures itself to draw its bands, so it needs to be seen.
    displayOpen("lens")
}

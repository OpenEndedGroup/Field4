package afield.lang.remotejs

import afield.app.server.globalLoopbackHttpPort
import afield.app.server.sessions
import afield.document.graph.Box
import afield.ui.output.LiveOutputIframeBridge
import afield.ui.output.OutputIframeTarget
import afield.ui.output.WEB_PAGE_NAME_PREFIX
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * RemoteJS heads inside `web(...)` pages, and which boxes run in them.
 *
 * A Javascript box connected (at any depth) under a box that shows a `web(...)` page runs in that
 * page, and only there: its code goes to the head electron/page-heads.cjs puts into the page, whose
 * id is the page's name, `field-live-iframe-web-<box uid>~<random>`. Every other Javascript box
 * goes only to ordinary heads (a RemoteJS browser tab, a phone, a headset), so those never run a box
 * meant for a page, and a page never runs theirs.
 *
 * A page gets its head the first time a box under it runs, and again after it navigates (a head
 * lasts as long as its document).
 */
internal object WebPageHeads {

    /** The uid of the box whose web() page a head is in, or null for an ordinary head. */
    fun pageBoxOf(headId: String): String? =
        if (headId.startsWith(WEB_PAGE_NAME_PREFIX)) headId.removePrefix(WEB_PAGE_NAME_PREFIX).substringBefore("~", "")
            .takeIf { it.isNotEmpty() } else null

    /** The box a RemoteJS [filename] (`box_<name>|<uid>`) names, if any. */
    fun boxNamed(filename: String): Box? {
        val uid = filename.substringAfter("|", "").takeIf { it.isNotEmpty() } ?: return null
        return sessions.values.firstNotNullOfOrNull { it.boxes[uid] }
    }

    /** The output channels of [box] that show a web() page. */
    fun pageChannels(box: Box): List<String> =
        box.output.retainedAreas.channelsContaining(WEB_PAGE_NAME_PREFIX + box.id + "~")

    /** The nearest box above [box] (not [box] itself) that shows a web() page, searching parents breadth first. */
    fun pageOwnerOf(box: Box): Box? {
        val seen = mutableSetOf(box.id)
        var generation = box.parents.toList()
        while (generation.isNotEmpty()) {
            generation.firstOrNull { pageChannels(it).isNotEmpty() }?.let { return it }
            seen += generation.map { it.id }
            generation = generation.flatMap { it.parents }.filter { it.id !in seen }.distinctBy { it.id }
        }
        return null
    }

    /**
     * Who gets code from the RemoteJS [filename]: for a box under a web() page, that page's heads
     * (putting a head into the page first if it has none); for any other box, the ordinary heads.
     * Code that names no box (event handlers, reloads) goes to every head.
     */
    fun recipientsFilter(filename: String): (String, WebSocketSession) -> Boolean {
        val box = boxNamed(filename) ?: return { _, _ -> true }
        val owner = pageOwnerOf(box) ?: return { id, _ -> pageBoxOf(id) == null }
        ensureHead(owner)
        return { id, _ -> pageBoxOf(id) == owner.id }
    }

    private fun connected(owner: Box) = knownOpenRemoteClients.entries.any { (id, session) ->
        pageBoxOf(id) == owner.id && !session.outgoing.isClosedForSend
    }

    /**
     * Puts a head into [owner]'s pages unless one is connected, and waits (up to a few seconds) for it
     * to say hello. A page that cannot have one (no Electron window, no HTTP mirror) is reported by the
     * run itself: with no recipient, the box says no browser is connected.
     */
    fun ensureHead(owner: Box) {
        knownOpenRemoteClients.entries.removeIf { it.value.outgoing.isClosedForSend }
        if (connected(owner)) return
        val port = globalLoopbackHttpPort ?: run {
            owner.displayError("A Javascript box under a web page needs Field's HTTP mirror (AFIELD_HTTP_PORT is off)", "code", 1)
            return
        }
        val params = JsonObject(mapOf("socketUrl" to JsonPrimitive("ws://127.0.0.1:$port/ws")))
        for (channel in pageChannels(owner)) {
            runCatching {
                LiveOutputIframeBridge.send(owner.inside, OutputIframeTarget(owner.id, channel), "head",
                    params = params, timeoutMillis = 20_000).get(23, TimeUnit.SECONDS)
            }.onFailure { failure ->
                owner.displayError("Could not start Javascript in the '$channel' page: ${failure.cause?.message ?: failure.message}", "code", 1)
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!connected(owner) && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }
}

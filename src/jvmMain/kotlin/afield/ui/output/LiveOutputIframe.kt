package afield.ui.output

import afield.app.protocol.Output
import afield.app.server.app
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.tools.docs.screenshot

import afield.app.protocol.LiveOutputIframeCommand
import afield.app.protocol.LiveOutputIframeReply
import afield.app.protocol.Message
import afield.app.protocol._send
import afield.app.protocol.payload
import afield.app.protocol.send
import afield.app.server.SessionInformation
import afield.app.server.sessions
import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.ui.output.templates.filter
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A resolved box/output pair in the current document; the actual DOM iframe is found per call. */
data class OutputIframeTarget(val boxUid: String, val channel: String)

/** The iframe in one of this box's own output areas. */
fun Box.outputIframe(channel: String): LiveOutputIframe = outputIframe(this, channel)

/** The iframe in another box's output area; both boxes must belong to the same document. */
fun Box.outputIframe(box: Box, channel: String): LiveOutputIframe {
    val info = inside
    require(box.inside === info) { "The target box must be in ${info.documentName}" }
    require(channel.isNotBlank()) { "Output area name cannot be empty" }
    require(channel in box.output.retainedAreas.channels()) {
        "Box '${box.name}' has no output area called '$channel'"
    }
    return LiveOutputIframe(info, OutputIframeTarget(box.id, channel))
}

/** Resolve a box by uid or unique name in this box's document. */
fun Box.outputIframe(boxName: String, channel: String): LiveOutputIframe {
    val info = inside
    val box = info.boxes[boxName] ?: info.boxes.values.filter { it.name == boxName }.let { matches ->
        when (matches.size) {
            1 -> matches.single()
            0 -> throw IllegalArgumentException("No box '$boxName' in ${info.documentName}")
            else -> throw IllegalArgumentException("More than one box is called '$boxName'; use its uid")
        }
    }
    return outputIframe(box, channel)
}

/** Commands run against the visible Electron tab. Futures keep the Field runloop unblocked. */
class LiveOutputIframe internal constructor(
    private val document: SessionInformation,
    val target: OutputIframeTarget,
) {
    companion object {
        private val polling = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "field-live-iframe-wait").apply { isDaemon = true }
        }
    }

    fun evaluate(javascript: String, timeoutMillis: Long = 10_000): CompletableFuture<JsonElement> =
        LiveOutputIframeBridge.send(document, target, "evaluate", javascript = javascript,
            timeoutMillis = timeoutMillis).thenApply { it.value }

    /** Raw CDP addresses the owning tab; pass a frame context/session id where the method needs one. */
    fun cdp(method: String, params: JsonObject = JsonObject(emptyMap()), sessionId: String? = null,
            timeoutMillis: Long = 10_000): CompletableFuture<JsonElement> =
        LiveOutputIframeBridge.send(document, target, "cdp", method = method, params = params,
            sessionId = sessionId, timeoutMillis = timeoutMillis).thenApply { it.value }

    /** Change the actual iframe's src and complete after its load event. */
    fun navigate(url: String, timeoutMillis: Long = 15_000): CompletableFuture<String> {
        require(url.isNotBlank()) { "Iframe URL cannot be empty" }
        return LiveOutputIframeBridge.send(document, target, "navigate", url = url,
            timeoutMillis = timeoutMillis).thenApply { (it.value as? JsonPrimitive)?.contentOrNull ?: it.frameUrl }
    }

    /** Screenshot the iframe's currently visible pixels in the live Electron tab. */
    fun screenshot(timeoutMillis: Long = 10_000): CompletableFuture<String> =
        LiveOutputIframeBridge.send(document, target, "screenshot", timeoutMillis = timeoutMillis)
            .thenApply { (it.value as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalStateException("Electron returned no iframe image") }

    /** Current DOM source and document metadata, or matching elements for a CSS selector. */
    fun inspect(selector: String = "", timeoutMillis: Long = 10_000): CompletableFuture<JsonElement> {
        val quoted = JsonPrimitive(selector).toString()
        val script = """
            (() => {
              const selector = $quoted;
              if (selector) return {
                url: location.href, selector,
                matches: Array.from(document.querySelectorAll(selector), element => ({
                  html: element.outerHTML, text: element.textContent,
                  bounds: (() => { const r = element.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; })()
                }))
              };
              return {
                url: location.href, title: document.title, readyState: document.readyState,
                doctype: document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '',
                html: document.documentElement?.outerHTML ?? '',
                scripts: Array.from(document.scripts, script => ({src:script.src,type:script.type,text:script.textContent}))
              };
            })()
        """.trimIndent()
        return evaluate(script, timeoutMillis)
    }

    /** Wait for a selector, then click the matching element in the live frame. */
    fun click(selector: String, timeoutMillis: Long = 10_000): CompletableFuture<JsonElement> {
        require(selector.isNotBlank()) { "Click selector cannot be empty" }
        val quoted = JsonPrimitive(selector).toString()
        return waitFor("document.querySelector($quoted)", timeoutMillis)
            .thenCompose { evaluate("""(() => { const element = document.querySelector($quoted);
                element.click(); return {selector:$quoted, html:element.outerHTML}; })()""", timeoutMillis) }
    }

    /** Wait for a text input, set its value, and emit input/change events for app frameworks. */
    fun fill(selector: String, text: String, timeoutMillis: Long = 10_000): CompletableFuture<JsonElement> {
        require(selector.isNotBlank()) { "Fill selector cannot be empty" }
        val quotedSelector = JsonPrimitive(selector).toString()
        val quotedText = JsonPrimitive(text).toString()
        val script = """
            (() => {
              const element = document.querySelector($quotedSelector);
              if (!element || !('value' in element)) throw new Error('Fill target is not a text input: ' + $quotedSelector);
              element.focus();
              const prototype = Object.getPrototypeOf(element);
              const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
              if (setter) setter.call(element, $quotedText); else element.value = $quotedText;
              element.dispatchEvent(new Event('input', {bubbles:true}));
              element.dispatchEvent(new Event('change', {bubbles:true}));
              return {selector:$quotedSelector, value:element.value};
            })()
        """.trimIndent()
        return waitFor("document.querySelector($quotedSelector)", timeoutMillis)
            .thenCompose { evaluate(script, timeoutMillis) }
    }

    fun waitFor(condition: String, timeoutMillis: Long = 10_000, pollMillis: Long = 25): CompletableFuture<Unit> {
        require(timeoutMillis in 1..120_000) { "timeoutMillis must be between 1 and 120000" }
        require(pollMillis in 1..1000) { "pollMillis must be between 1 and 1000" }
        require(condition.isNotBlank()) { "Iframe wait condition cannot be empty" }
        val source = JsonPrimitive(condition).toString()
        val javascript = """(() => { try { return Boolean((0, eval)($source)); }
            catch (error) { if (error instanceof ReferenceError || error instanceof TypeError) return false; throw error; }
        })()"""
        val done = CompletableFuture<Unit>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var lastError: Throwable? = null
        fun poll() {
            if (done.isDone) return
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) {
                done.completeExceptionally(TimeoutException("waitFor timed out: $condition" +
                    (lastError?.message?.let { " (last error: $it)" } ?: "")))
                return
            }
            evaluate(javascript, remaining.coerceIn(1, 5_000)).whenComplete { result, error ->
                if (done.isDone) return@whenComplete
                if (error == null && (result as? JsonPrimitive)?.booleanOrNull == true) {
                    done.complete(Unit)
                } else {
                    if (error != null) lastError = error
                    polling.schedule({ poll() }, pollMillis, TimeUnit.MILLISECONDS)
                }
            }
        }
        poll()
        return done
    }
}

/** One command/reply relay over the requesting Electron client's existing Field WebSocket. */
internal object LiveOutputIframeBridge {
    private data class Pending(val session: WebSocketSession, val future: CompletableFuture<LiveOutputIframeReply>)
    private val electronClients = ConcurrentHashMap.newKeySet<WebSocketSession>()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun ready(session: WebSocketSession?) { if (session != null) electronClients.add(session) }

    fun gone(session: WebSocketSession) {
        electronClients.remove(session)
        pending.entries.removeIf { (_, waiting) ->
            if (waiting.session !== session) false
            else {
                waiting.future.completeExceptionally(IllegalStateException("The Electron tab disconnected"))
                true
            }
        }
    }

    fun reply(session: WebSocketSession?, result: LiveOutputIframeReply) {
        val waiting = pending[result.id] ?: return
        if (waiting.session !== session || !pending.remove(result.id, waiting)) return
        if (result.ok) waiting.future.complete(result)
        else waiting.future.completeExceptionally(IllegalStateException(result.error))
    }

    fun send(info: SessionInformation, target: OutputIframeTarget, operation: String,
             javascript: String = "", url: String = "", method: String = "", params: JsonObject = JsonObject(emptyMap()),
             sessionId: String? = null, timeoutMillis: Long = 10_000): CompletableFuture<LiveOutputIframeReply> {
        require(timeoutMillis in 1..120_000) { "timeoutMillis must be between 1 and 120000" }
        val clients = info.sessions.filter { it in electronClients }
        val client = when (clients.size) {
            1 -> clients.single()
            0 -> return CompletableFuture.failedFuture(IllegalStateException(
                "No live Electron tab is connected to ${info.documentName}"))
            else -> return CompletableFuture.failedFuture(IllegalStateException(
                "Several Electron tabs show ${info.documentName}; choose one explicitly before driving it"))
        }
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<LiveOutputIframeReply>()
        val waiting = Pending(client, future)
        pending[id] = waiting
        future.orTimeout(timeoutMillis + 3_000, TimeUnit.MILLISECONDS)
            .whenComplete { _, _ -> pending.remove(id, waiting) }
        runCatching {
            _send(client, Message("live-output-iframe-command").payload(
                LiveOutputIframeCommand(id, target.boxUid, target.channel, operation,
                    javascript, url, method, params, sessionId, timeoutMillis)
            ))
        }.onFailure { future.completeExceptionally(it) }
        return future
    }
}

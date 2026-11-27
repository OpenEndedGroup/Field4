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
import afield.runtime.execution.go
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.io.File
import java.net.URLEncoder
import afield.document.workspace.files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Script access to the iframe inside a box's output area as the Electron tab shows it:
 * [outputIframe] resolves a box and channel to a [LiveOutputIframe], whose calls (JavaScript
 * evaluation, raw CDP, navigation) return futures so the runloop is not blocked while the tab answers.
 */


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

/**
 * A web page in this box's output area, with a bar above it: back, forward, and an address you can
 * type into. Returns the page's [LiveOutputIframe], so `web("https://github.com").devTools()` opens
 * DevTools on it, and the AI4 tools from `exposeOutputIframeTools` can drive it.
 *
 * Field's Electron window shows sites that refuse to be framed and keeps their logins; another
 * browser shows only the sites that allow framing. Back and forward step through the pages this bar
 * has seen, and the address follows links you click inside the page.
 *
 * ```kotlin
 * web("https://en.wikipedia.org/wiki/Iframe")
 * web("github.com", channel = "github", height = 800)
 * ```
 *
 * With a [profile], the page keeps its cookies, logins, local storage and cache in a directory of
 * its own instead of sharing Field's. The profile is a [files] spec, made if it does not exist: a
 * bare name such as `"github"` is `web-profiles/github` in the document's `files/` directory, and
 * `"%project/web-profiles/github"` shares one login across the documents of a project. Two pages
 * with the same profile share it; a page with another profile, or none, sees none of it. A page
 * with a profile needs Field's Electron window.
 *
 * ```kotlin
 * web("github.com", profile = "github")
 * ```
 *
 * @param url the first page; a bare host such as `github.com` gets `https://`
 * @param channel output-area channel, and its disclosure title
 * @param width page width in CSS pixels
 * @param height page height in CSS pixels, not counting the bar
 * @param profile where the page keeps its cookies and storage, as a [files] spec; null shares Field's
 */
fun Box.web(url: String, channel: String = "web", width: Int = 1000, height: Int = 640,
            profile: String? = null): LiveOutputIframe {
    require(width in 200..4000 && height in 100..4000) { "web: width must be 200..4000 and height 100..4000" }
    val directory = profile?.let { webProfileDirectory(it) }
    display(channel, webPanel(webAddress(url), width, height, directory))
    displayOpen(channel)
    return outputIframe(channel)
}

/**
 * The directory a [web] profile called [profile] keeps its cookies and storage in, made if it is
 * missing. A bare name is `web-profiles/<name>`; anything else is resolved by [files] as written.
 */
fun webProfileDirectory(profile: String): File {
    val directory = files.file(webProfileSpec(profile))
    if (!directory.isDirectory) {
        require(!directory.exists()) { "web: profile '$profile' is a file, not a directory: $directory" }
        directory.mkdirs()
    }
    return directory.absoluteFile
}

/** A bare profile name lives under `web-profiles/`; a path, `%root/` or `~/` spec stands as it is. */
internal fun webProfileSpec(profile: String): String {
    val trimmed = profile.trim()
    require(trimmed.isNotEmpty()) { "web: the profile cannot be empty" }
    val bare = !trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.startsWith("%") && !trimmed.startsWith("~")
    return if (bare) "web-profiles/$trimmed" else trimmed
}

/** What the address bar does to typed text: keep a scheme it has, otherwise add `https://`. */
internal fun webAddress(text: String): String {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "web: the address cannot be empty" }
    return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
}

/**
 * The bar and the page as output HTML; `fieldWeb.js` in the Field page runs the bar. The iframe is
 * named from the start: Electron finds a frame by the name it had when it first navigated, and a
 * framed site's address soon stops matching `src`, so a name given later would never be found.
 *
 * With a [profile] directory the page is a `<webview>` instead, whose `partition` names that
 * directory; Field's Electron window gives it a session stored there. A webview is not an iframe
 * to the panel sizer, so the panel states the page's size through `data-afield-intrinsic-*`.
 */
internal fun webPanel(url: String, width: Int, height: Int, profile: File? = null): String {
    val attribute = escapeAttribute(url)
    val name = "field-live-iframe-web-" + UUID.randomUUID()
    val page = if (profile == null) {
        """<iframe class="field-web-page" name="$name" data-field-live-name="$name" src="$attribute" data-afield-autosize="off" """ +
            """style="width:${width}px;height:${height}px"></iframe>"""
    } else {
        val partition = escapeAttribute(WEB_PROFILE_PARTITION + URLEncoder.encode(profile.absolutePath, Charsets.UTF_8))
        """<webview class="field-web-page" data-field-live-name="$name" src="$attribute" partition="$partition" """ +
            """data-field-web-profile="${escapeAttribute(profile.name)}" style="width:${width}px;height:${height}px"></webview>"""
    }
    val stated = if (profile == null) "" else """ data-afield-intrinsic-width="$width" data-afield-intrinsic-height="${height + WEB_BAR_HEIGHT}""""
    return """<div class="field-web"$stated>""" +
        """<div class="field-web-bar">""" +
        """<button type="button" data-field-web="back" title="Back" disabled>&#8249;</button>""" +
        """<button type="button" data-field-web="forward" title="Forward" disabled>&#8250;</button>""" +
        """<input class="field-web-url" type="text" spellcheck="false" autocomplete="off" value="$attribute" aria-label="Address">""" +
        (if (profile == null) "" else """<span class="field-web-profile" title="${escapeAttribute(profile.path)}">${escapeAttribute(profile.name)}</span>""") +
        """</div>""" + page + """</div>"""
}

/** The prefix of a webview `partition` that names a profile directory; electron/web-profiles.cjs reads it. */
internal const val WEB_PROFILE_PARTITION = "field-path:"

/** The bar's height in CSS pixels (`.field-web-bar`: 12px text, 6px padding, a 1px rule). */
private const val WEB_BAR_HEIGHT = 33

private fun escapeAttribute(text: String) =
    text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

/** The running [watchIframe] for each box and name, so a box run again replaces its watch. */
private val iframeWatches = ConcurrentHashMap<Pair<String, String>, Job>()

/**
 * Evaluate [expression] in [page] every [everyMillis] and call [onChange] whenever its value
 * changes, starting with its first value.
 *
 * One watch per box and [name]: running the box again replaces it, and stopping the box ends it,
 * so a box can register its watch on every run. [onChange] runs on Field's runloop and may suspend,
 * for instance to `await` another [LiveOutputIframe.evaluate]. An expression that gives `null` or
 * `undefined` means "not yet", and so does a page that is reloading or not visible in Field's
 * window: the next look tries again.
 *
 * ```kotlin
 * watchIframe(page, "document.title") { title -> println("now: $title") }
 * ```
 */
fun Box.watchIframe(
    page: LiveOutputIframe, expression: String, name: String = "watch", everyMillis: Long = 250,
    onChange: suspend (JsonElement) -> Unit,
) {
    require(everyMillis in 20..60_000) { "watchIframe: everyMillis must be 20..60000" }
    val key = id to name
    go {
        val job = coroutineContext.job
        iframeWatches.put(key, job)?.takeIf { it !== job }?.cancel()
        job.invokeOnCompletion { iframeWatches.remove(key, job) }
        var last: JsonElement? = null
        while (isActive) {
            val value = try {
                page.evaluate(expression, timeoutMillis = 5_000).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (value != null && value !is JsonNull && value != last) {
                last = value
                onChange(value)
            }
            delay(everyMillis)
        }
    }
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

    /**
     * Open DevTools on this iframe in the live Electron tab and complete with the frame's URL.
     *
     * DevTools for the Field tab opens in its own window with this `<iframe>` selected in Elements.
     * Expand it for the framed page's `#document`, whatever site it is on; to run code in the page,
     * pick its site in the Console's context menu (it starts at `top`, the Field page). Calling it
     * again brings the window back and selects the iframe again. An output area off the canvas is
     * panned into view first, as [screenshot] does.
     */
    fun devTools(timeoutMillis: Long = 10_000): CompletableFuture<String> =
        LiveOutputIframeBridge.send(document, target, "devtools", timeoutMillis = timeoutMillis)
            .thenApply { (it.value as? JsonPrimitive)?.contentOrNull ?: it.frameUrl }

    /**
     * What Field's ad and tracker blocker has done to this page: how many of its requests were
     * blocked, the most recent of them, and whether its site is filtered at all.
     *
     * The count is for the page now showing in the iframe, ads nested in frames inside it
     * included; it starts again when the page navigates to another document.
     */
    fun blocking(timeoutMillis: Long = 10_000): CompletableFuture<WebBlocking> = blockingAction("status", timeoutMillis)

    /**
     * Stop (false) or resume (true) blocking on this page's site, then reload the page. The
     * choice is remembered for the site across pages, documents and restarts of Field.
     */
    fun setBlocking(enabled: Boolean, timeoutMillis: Long = 10_000): CompletableFuture<WebBlocking> =
        blockingAction(if (enabled) "block" else "allow", timeoutMillis)

    private fun blockingAction(action: String, timeoutMillis: Long): CompletableFuture<WebBlocking> =
        LiveOutputIframeBridge.send(document, target, "blocking",
            params = JsonObject(mapOf("action" to JsonPrimitive(action))), timeoutMillis = timeoutMillis)
            .thenApply { WebBlocking.from(it.value) }

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
        // The condition is written into the script, not passed through eval: a page whose
        // Content-Security-Policy forbids eval (GitHub's) would refuse every poll.
        val javascript = """(() => { try { return Boolean(($condition
            )); }
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

/**
 * Content blocking for one web page, from [LiveOutputIframe.blocking].
 *
 * [mode] is `ads` (ads and trackers), `full` (also annoyances such as cookie banners) or `off`,
 * chosen with `AFIELD_WEB_BLOCKING` when Field starts. [enabled] is false until the filter lists
 * have loaded. [allowed] means the user turned blocking off for [site].
 */
data class WebBlocking(
    val mode: String,
    val enabled: Boolean,
    val site: String,
    val allowed: Boolean,
    val blocked: Int,
    val recent: List<BlockedRequest>,
    val totalBlocked: Int,
) {
    /** One blocked request: its [url], Chromium's resource [type], and whether it was [redirected] to a stand-in. */
    data class BlockedRequest(val url: String, val type: String, val redirected: Boolean)

    internal companion object {
        fun from(value: JsonElement): WebBlocking {
            val o = value as? JsonObject ?: JsonObject(emptyMap())
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull ?: ""
            fun bool(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull == "true"
            fun int(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val recent = (o["recent"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { entry ->
                val r = entry as? JsonObject ?: return@mapNotNull null
                BlockedRequest(
                    (r["url"] as? JsonPrimitive)?.contentOrNull ?: "",
                    (r["type"] as? JsonPrimitive)?.contentOrNull ?: "",
                    (r["redirected"] as? JsonPrimitive)?.contentOrNull == "true",
                )
            }
            return WebBlocking(str("mode").ifEmpty { "off" }, bool("enabled"), str("site"), bool("allowed"),
                int("blocked"), recent, int("totalBlocked"))
        }
    }
}

package afield.four.browser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A Chromium tab with no picture: the whole [PuppetBrowserControl] plane — navigation, pointer,
 * keyboard, IME, touch, promise-aware JavaScript, the isolated world, arbitrary CDP and the
 * allowlisted page→Field request bridge — with no [afield.four.Renderer], no Vulkan surface, no
 * native handle bridge and no readback socket.
 *
 * The same Electron producer and the same wire protocol as [FieldUiBrowserTexture]; the only
 * difference is that it is started in the producer's no-frames mode, so its paints are dropped at
 * the source. That matters beyond saving a GPU: it is what lets the browser stack be *tested*, and
 * what lets a box treat a page as a data source rather than as a texture. See
 * [text], [attribute], [table] and [waitFor] in PuppetBrowserPage.kt for that second use.
 *
 * The tab is still a real, laid-out, script-running page: offscreen rendering stays on at
 * [framesPerSecond], which keeps `requestAnimationFrame`, timers and layout behaving as they do on
 * the texture path, and the frames are thrown away at the producer.
 *
 * Close it. It owns an operating-system process.
 */
class HeadlessPuppetBrowser(
    /** Loaded as the producer starts; [loaded] completes when it has finished. */
    val url: String = "about:blank",
    width: Int = 1280,
    height: Int = 720,
    deviceScaleFactor: Double = 1.0,
    /**
     * Offscreen frames per second. Nothing consumes them, but Chromium only acknowledges a
     * dispatched `mouseMoved` on the renderer's next frame, so this is the pointer's latency floor:
     * at 1 a mouse move costs a second. Raise it if you are driving a page, drop it to 1 if you are
     * only reading one.
     */
    val framesPerSecond: Int = 8,
    projectRoot: File = locateFieldProjectRoot(),
) : PuppetBrowserControl {
    private val closed = AtomicBoolean(false)
    private val writer = AtomicReference<PrintWriter?>()
    private val browserState = AtomicReference(PuppetBrowserState(url = url, loading = true))
    private val eventListeners = CopyOnWriteArrayList<(PuppetBrowserEvent) -> Unit>()
    private val pageRequestHandlers = ConcurrentHashMap<String, (JsonElement) -> CompletableFuture<JsonElement>>()
    private val firstLoad = CompletableFuture<PuppetBrowserState>()

    /** Viewport size in CSS pixels. Nothing samples it; scripts and media queries still see it. */
    val viewport = ExternalBrowserSurfaceSpec(
        width = width,
        height = height,
        deviceScaleFactor = deviceScaleFactor,
    )

    private val session = ElectronBrowserTextureSession(
        projectRoot = projectRoot,
        surface = null,
        url = url,
        spec = viewport,
        writer = writer,
        loadedAfterFrame = AtomicLong(Long.MAX_VALUE),
        readyBoxUid = null,
        transport = null,
        stateListener = browserState::set,
        eventListener = { event -> eventListeners.forEach { listener -> runCatching { listener(event) } } },
        pageRequest = ::handlePageRequest,
        loadedListener = { state -> firstLoad.complete(state) },
        framesPerSecond = framesPerSecond,
    )

    override val state: PuppetBrowserState get() = browserState.get()

    /**
     * Completes when the tab first reaches `did-finish-load`. [navigate] is the barrier for every
     * later page; this one exists because the constructor's [url] is loaded before you can call
     * anything.
     */
    val loaded: CompletableFuture<PuppetBrowserState> get() = firstLoad

    override fun addEventListener(listener: (PuppetBrowserEvent) -> Unit): () -> Unit {
        eventListeners += listener
        return { eventListeners -= listener }
    }

    override fun navigate(url: String) = stateCommand("navigate", buildJsonObject { put("url", url) })
    override fun goBack() = stateCommand("back")
    override fun goForward() = stateCommand("forward")
    override fun reload(ignoreCache: Boolean) =
        stateCommand("reload", buildJsonObject { put("ignoreCache", ignoreCache) })
    override fun stop() = stateCommand("stop")
    override fun focus() = stateCommand("focus")
    override fun blur() = stateCommand("blur")

    override fun pointer(event: BrowserPointerEvent) = session.post("pointer", event.toCdpJson())
    override fun key(event: BrowserKeyEvent) = session.post("key", event.toCdpJson())
    override fun insertText(text: String) = session.post("insert-text", buildJsonObject { put("text", text) })

    override fun setComposition(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        replacementStart: Int?,
        replacementEnd: Int?,
    ) = session.post("composition", buildJsonObject {
        put("text", text)
        put("selectionStart", selectionStart)
        put("selectionEnd", selectionEnd)
        replacementStart?.let { put("replacementStart", it) }
        replacementEnd?.let { put("replacementEnd", it) }
    })

    override fun touch(
        type: BrowserTouchEventType,
        points: List<BrowserTouchPoint>,
        modifiers: Set<BrowserModifier>,
    ) = session.post("touch", touchToCdpJson(type, points, modifiers))

    override fun flushInput(): CompletableFuture<Unit> = session.command("flush-input").discardResult()

    override fun evaluate(
        javascript: String,
        world: BrowserJavascriptWorld,
        returnMode: BrowserJavascriptReturnMode,
        userGesture: Boolean,
        timeoutMillis: Long,
    ): CompletableFuture<BrowserJavascriptResult> = session.command(
        "evaluate",
        buildJsonObject {
            put("javascript", javascript)
            put("world", world.name)
            put("returnMode", returnMode.name)
            put("userGesture", userGesture)
            put("timeoutMillis", timeoutMillis)
        },
        timeoutMillis + 2_000,
    ).thenApply(::browserJavascriptResult)

    override fun releaseJavascriptObject(objectId: String): CompletableFuture<Unit> =
        session.command("release-object", buildJsonObject { put("objectId", objectId) }).discardResult()

    override fun cdp(method: String, params: JsonObject, sessionId: String?): CompletableFuture<JsonObject> =
        session.command("cdp", buildJsonObject {
            put("method", method)
            put("params", params)
            sessionId?.let { put("sessionId", it) }
        })

    override fun forwardCdpEvents(enabled: Boolean): CompletableFuture<Unit> =
        session.command("forward-cdp-events", buildJsonObject { put("enabled", enabled) }).discardResult()

    override fun onPageRequest(
        name: String,
        handler: (JsonElement) -> CompletableFuture<JsonElement>,
    ): () -> Unit {
        require(name.isNotBlank() && name.length <= 128) { "page request names must contain 1..128 characters" }
        pageRequestHandlers[name] = handler
        return { pageRequestHandlers.remove(name, handler) }
    }

    private fun stateCommand(
        name: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): CompletableFuture<PuppetBrowserState> = session.command(name, params).thenApply {
        puppetBrowserState(it).also(browserState::set)
    }

    private fun handlePageRequest(id: String, name: String, value: JsonElement) {
        val handler = pageRequestHandlers[name]
        if (handler == null) {
            session.pageResponse(id, null, PuppetBrowserCommandException("No Field handler is registered for '$name'"))
            return
        }
        runCatching { handler(value) }
            .fold(
                onSuccess = { future ->
                    future.whenComplete { result, error -> session.pageResponse(id, result, error) }
                },
                onFailure = { error -> session.pageResponse(id, null, error) },
            )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        firstLoad.completeExceptionally(PuppetBrowserCommandException("Puppet browser closed before it loaded"))
        session.close()
    }

    companion object {
        /**
         * Why a headless browser cannot start here, or null if one can.
         *
         * Only for callers that must degrade rather than fail — a test that skips on this must say
         * so loudly, because a suite that goes green by not running is worse than one that is red.
         */
        fun unavailableReason(projectRoot: File = locateFieldProjectRoot()): String? {
            val electron = fieldElectronBinary(projectRoot)
            if (!electron.isFile) {
                return "no Electron at ${electron.absolutePath}; run ./gradlew electronInstall"
            }
            val producer = File(projectRoot, "electron/browser-texture-producer.js")
            if (!producer.isFile) return "no producer at ${producer.absolutePath}"
            return null
        }
    }
}

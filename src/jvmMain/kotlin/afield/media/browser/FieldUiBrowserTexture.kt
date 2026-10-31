package afield.media.browser

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.texture.ImageView
import afield.graphics.vulkan.util.OS
import afield.graphics.vulkan.util.OSType
import afield.app.server.globalServerPort
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A live web page as a Field texture: a hidden Electron window renders offscreen and its paints
 * become an [ExternalBrowserSurface] a shader can sample.
 *
 * [FieldUiBrowserTexture] is the full [PuppetBrowser], so the same object takes navigation, input
 * and JavaScript. On macOS the frames move GPU-side as IOSurfaces, with no bitmap readback.
 */

/**
 * A live offscreen Chromium view exposed as an ordinary stable Field texture.
 *
 * The producer is a second, hidden Electron window. On macOS its paint callback transfers the
 * Chromium-owned IOSurface through a Mach message; [MacExternalBrowserSurface] imports it and
 * copies it on-GPU before this class acknowledges Electron's lease. There is no bitmap readback in
 * this path.
 */
class FieldUiBrowserTexture(
    private val renderer: Renderer,
    val url: String,
    width: Int = 1280,
    height: Int = 720,
    slots: Int = 3,
    readyBoxUid: String? = null,
    /**
     * Read frames back to the CPU and upload them, instead of importing Chromium's shared GPU
     * allocation. Costs about 110 MB/s at 1280x720/30 and exists because Chromium's Linux
     * shared-texture export hands over a buffer it never fills (electron#53271). Off by default;
     * see [BitmapBrowserSurface].
     */
    private val cpuBitmapFallback: Boolean = false,
) : PuppetBrowser {
    private val closed = AtomicBoolean(false)
    private val writer = AtomicReference<PrintWriter?>()
    private val loadedAfterFrame = AtomicLong(Long.MAX_VALUE)
    private val browserState = AtomicReference(PuppetBrowserState(url = url, loading = true))
    private val eventListeners = CopyOnWriteArrayList<(PuppetBrowserEvent) -> Unit>()
    private val pageRequestHandlers = ConcurrentHashMap<String, (JsonElement) -> CompletableFuture<JsonElement>>()
    private val projectRoot = locateFieldProjectRoot()
    private val specValue = ExternalBrowserSurfaceSpec(
        width = width,
        height = height,
        pixelFormat = BrowserSurfacePixelFormat.BGRA8_SRGB,
        slotCount = slots,
    )
    private val delegate: ExternalBrowserSurface = createDelegate(renderer, specValue, cpuBitmapFallback) { release ->
        writer.get()?.let { out ->
            synchronized(out) {
                out.println(
                    "{\"type\":\"release\",\"token\":\"${release.releaseToken}\"," +
                        "\"reason\":\"${release.reason}\"}"
                )
                out.flush()
            }
        }
    }
    private val session = ElectronBrowserTextureSession(
        projectRoot = projectRoot,
        surface = delegate,
        url = url,
        spec = specValue,
        writer = writer,
        loadedAfterFrame = loadedAfterFrame,
        readyBoxUid = readyBoxUid,
        transport = if (cpuBitmapFallback) CpuBitmapTransport(delegate, specValue)
        else BrowserHandleTransport.forThisPlatform(projectRoot),
        stateListener = browserState::set,
        eventListener = { event -> eventListeners.forEach { listener -> runCatching { listener(event) } } },
        pageRequest = ::handlePageRequest,
    )

    override val spec: ExternalBrowserSurfaceSpec get() = delegate.spec
    override val presentedFrame: ExternalBrowserFrame? get() = delegate.presentedFrame
    override val state: PuppetBrowserState get() = browserState.get()
    val pageReady: Boolean
        get() = delegate.presentedFrame?.frameId?.let { it > loadedAfterFrame.get() } == true
    override val imageView: ImageView get() = delegate.imageView
    override val imageViewArray get() = delegate.imageViewArray

    override fun offerFrame(frame: ExternalBrowserFrame): BrowserFrameOfferResult = delegate.offerFrame(frame)

    override fun prepareSampledImagesFor(command: CommandBuffer) = delegate.prepareSampledImagesFor(command)

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
        session.close()
        delegate.close()
    }

    companion object {
        /**
         * Whether this platform needs [cpuBitmapFallback] to show anything at all.
         *
         * On Linux Chromium's shared-texture export hands over a well-formed dma-buf that it never
         * writes into (electron#53271), so the accelerated path produces a correct-looking,
         * permanently black texture. Until that is fixed upstream, a caller that has to show
         * something on every platform passes this as the readback flag and so takes the readback
         * path on Linux alone, keeping the zero-copy one everywhere it works. The flag's own
         * default stays `false`, so every call site that turns readback on says so where it is
         * written.
         */
        val cpuBitmapFallbackRequired: Boolean get() = OS == OSType.LINUX

        /** URL for another browser view of the document containing [box]. */
        fun fieldDocumentUrl(box: Box): String {
            val session = box.inside
            val document = URLEncoder.encode(session.documentName, StandardCharsets.UTF_8)
            val scratchSession = if (session.ephemeral) {
                "&scratch=1&sessionKey=${URLEncoder.encode(session.filename, StandardCharsets.UTF_8)}"
            } else {
                ""
            }
            return "https://localhost:$globalServerPort/index.html?file=$document$scratchSession"
        }
    }
}

/** Picks the receiver that matches the transport: an importer, or the CPU-upload fallback. */
private fun createDelegate(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    cpuBitmapFallback: Boolean,
    releaseListener: BrowserFrameReleaseListener,
): ExternalBrowserSurface {
    if (cpuBitmapFallback) return BitmapBrowserSurface(renderer, spec, releaseListener)
    // Only the macOS handle transfer has been validated live; the Windows (D3D11 NT handle) and Linux
    // (dma-buf) receivers compile but their Electron bridges are unfinished, so they are refused
    // here, with the route that does work, rather than left to show a blank texture.
    if (OS != OSType.MACOS) {
        throw UnsupportedOperationException(
            "FieldUiBrowserTexture's GPU handle transfer is only working on macOS so far; on $OS pass " +
                "cpuBitmapFallback = true, which reads frames back and uploads them",
        )
    }
    return ExternalBrowserSurfaces.create(renderer, spec, releaseListener)
}


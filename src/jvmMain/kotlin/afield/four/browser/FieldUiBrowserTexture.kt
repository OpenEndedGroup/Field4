package afield.four.browser

import afield.Box
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.ImageView
import afield.four.util.OS
import afield.four.util.OSType
import afield.globalServerPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.lwjgl.system.APIUtil.apiCreateLibrary
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.SharedLibrary
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
     * shared-texture export hands over a buffer it never fills (electron#53271). Opt-in on purpose:
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
    private val projectRoot = locateProjectRoot()
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
         * permanently black texture. Until that is fixed upstream the demos ask for the readback
         * path here and keep the zero-copy one everywhere it works — which is why this is a
         * platform question rather than the parameter's default: the default stays honest about
         * what the class is for, and every call site that flips it says so in the open.
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

        private fun locateProjectRoot(): File {
            var cursor = File(System.getProperty("user.dir")).absoluteFile
            while (true) {
                if (File(cursor, "electron/browser-texture-producer.js").isFile &&
                    File(cursor, "build.gradle.kts").isFile
                ) return cursor
                cursor = cursor.parentFile ?: error(
                    "Could not locate the Field checkout from ${System.getProperty("user.dir")}"
                )
            }
        }
    }
}

/**
 * Everything about driving the Electron producer that does not depend on how a GPU handle crosses
 * the process boundary: launching it, the loopback JSON control channel, readiness, and turning a
 * `frame` message into an offered [ExternalBrowserFrame].
 *
 * Only the handle transfer is per-platform, so only that is behind [BrowserHandleTransport]. The
 * transport is a constructor parameter rather than an overridable method because it has to exist
 * before the producer process starts — the process needs the transport's address in its
 * environment.
 */
private class ElectronBrowserTextureSession(
    projectRoot: File,
    private val surface: ExternalBrowserSurface,
    url: String,
    spec: ExternalBrowserSurfaceSpec,
    private val writer: AtomicReference<PrintWriter?>,
    private val loadedAfterFrame: AtomicLong,
    readyBoxUid: String?,
    private val transport: BrowserHandleTransport,
    private val stateListener: (PuppetBrowserState) -> Unit,
    private val eventListener: (PuppetBrowserEvent) -> Unit,
    private val pageRequest: (id: String, name: String, value: JsonElement) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ready = CompletableFuture<Unit>()
    private val requestSequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val process: Process
    private val worker: Thread

    init {
        val electron = electronBinary(projectRoot)
        val producer = File(projectRoot, "electron/browser-texture-producer.js")
        listOfNotNull(electron, producer, transport.nodeBridge).forEach { file ->
            require(file.isFile) {
                "Missing browser texture runtime ${file.absolutePath}; run ./gradlew electronInstall"
            }
        }

        val streamId = (System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1)
        process = ProcessBuilder(electron.absolutePath, producer.absolutePath)
            .directory(projectRoot)
            .redirectErrorStream(true)
            .apply {
                environment()["FIELD_BROWSER_TEXTURE_URL"] = url
                environment()["FIELD_BROWSER_TEXTURE_WIDTH"] = spec.width.toString()
                environment()["FIELD_BROWSER_TEXTURE_HEIGHT"] = spec.height.toString()
                environment()["FIELD_BROWSER_TEXTURE_DEVICE_SCALE_FACTOR"] = spec.deviceScaleFactor.toString()
                environment()["FIELD_BROWSER_TEXTURE_SLOTS"] = spec.slotCount.toString()
                environment()["FIELD_BROWSER_TEXTURE_CONTROL_PORT"] = server.localPort.toString()
                transport.nodeBridge?.let { environment()["FIELD_BROWSER_TEXTURE_BRIDGE"] = it.absolutePath }
                environment()["FIELD_BROWSER_TEXTURE_STREAM_ID"] = streamId.toString()
                environment().putAll(transport.producerEnvironment)
                readyBoxUid?.takeIf { it.isNotBlank() }?.let {
                    environment()["FIELD_BROWSER_TEXTURE_READY_BOX_UID"] = it
                }
            }
            .start()

        Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { println("[field-browser-texture] $it") }
            }
        }, "field-browser-texture-output").apply {
            isDaemon = true
            start()
        }

        worker = Thread({ runControlLoop() }, "field-browser-texture-control").apply {
            isDaemon = true
            start()
        }
    }

    private fun runControlLoop() {
        try {
            server.accept().use { socket ->
                val out = PrintWriter(socket.getOutputStream(), true)
                writer.set(out)
                BufferedReader(InputStreamReader(socket.getInputStream())).useLines { lines ->
                    lines.takeWhile { !closed.get() }.forEach { line ->
                        if (line.isNotBlank()) handle(Json.parseToJsonElement(line).jsonObject)
                    }
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) System.err.println("[field-browser-texture] control failed: ${t.message}")
        } finally {
            writer.set(null)
        }
    }

    private fun handle(message: JsonObject) {
        when (message.string("type")) {
            "ready" -> {
                message.objectOrNull("state")?.let { stateListener(puppetBrowserState(it)) }
                ready.complete(Unit)
                println("[field-browser-texture] producer ready pid=${message.string("pid")}")
            }
            "loaded" -> {
                loadedAfterFrame.set(message.long("afterFrameId"))
                message.objectOrNull("state")?.let { stateListener(puppetBrowserState(it)) }
                println("[field-browser-texture] page loaded ${message.string("url")}")
            }
            "error" -> System.err.println("[field-browser-texture] ${message.string("message")}")
            "frame" -> offer(message)
            "response" -> receiveResponse(message)
            "event" -> receiveEvent(message)
            "page-request" -> pageRequest(
                message.string("id"),
                message.string("name"),
                message["value"] ?: JsonNull,
            )
        }
    }

    fun command(
        name: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMillis: Long = 12_000,
    ): CompletableFuture<JsonObject> {
        val id = requestSequence.incrementAndGet().toString()
        val result = CompletableFuture<JsonObject>()
        ready.whenComplete { _, startupError ->
            if (startupError != null) {
                result.completeExceptionally(startupError)
                return@whenComplete
            }
            if (closed.get()) {
                result.completeExceptionally(PuppetBrowserCommandException("Puppet browser is closed", name))
                return@whenComplete
            }
            pending[id] = result
            runCatching {
                send(buildJsonObject {
                    put("type", "command")
                    put("id", id)
                    put("command", name)
                    put("params", params)
                })
            }.onFailure { error ->
                pending.remove(id, result)
                result.completeExceptionally(error)
            }
        }
        result.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        result.whenComplete { _, _ -> pending.remove(id, result) }
        return result
    }

    fun post(name: String, params: JsonObject) {
        ready.thenRun {
            if (!closed.get()) send(buildJsonObject {
                put("type", "post")
                put("command", name)
                put("params", params)
            })
        }
    }

    fun pageResponse(id: String, result: JsonElement?, error: Throwable?) {
        if (closed.get()) return
        send(buildJsonObject {
            put("type", "page-response")
            put("id", id)
            put("ok", error == null)
            if (error == null) put("result", result ?: JsonNull)
            else put("error", buildJsonObject {
                put("name", error::class.simpleName ?: "Error")
                put("message", error.message ?: error.toString())
            })
        })
    }

    private fun send(message: JsonObject) {
        val out = writer.get() ?: throw PuppetBrowserCommandException("Puppet browser control channel is unavailable")
        synchronized(out) {
            out.println(message.toString())
            out.flush()
            if (out.checkError()) throw PuppetBrowserCommandException("Puppet browser control channel write failed")
        }
    }

    private fun receiveResponse(message: JsonObject) {
        val id = message.string("id")
        val future = pending.remove(id) ?: return
        if (message.boolean("ok")) {
            future.complete(message.objectOrNull("result") ?: JsonObject(emptyMap()))
        } else {
            val error = message.objectOrNull("error")
            future.completeExceptionally(
                PuppetBrowserCommandException(
                    error?.stringOrNull("message") ?: "Puppet browser command failed",
                    details = error,
                )
            )
        }
    }

    private fun receiveEvent(message: JsonObject) {
        val event = PuppetBrowserEvent(
            sequence = message["sequence"]?.jsonPrimitive?.longOrNull ?: 0,
            name = message.string("name"),
            payload = message.objectOrNull("payload") ?: JsonObject(emptyMap()),
        )
        if (event.name == "state") {
            event.payload.objectOrNull("state")?.let { stateListener(puppetBrowserState(it)) }
        }
        eventListener(event)
    }

    private fun offer(message: JsonObject) {
        val token = message.long("token")
        val handle = transport.receive(message, token, 5_000)
        val colorSpace = message.colorSpace("colorSpace")
        val isSrgb = colorSpace?.transfer.equals("srgb", ignoreCase = true)
        val pixelFormat = when (message.string("pixelFormat")) {
            "bgra" -> if (isSrgb) BrowserSurfacePixelFormat.BGRA8_SRGB else BrowserSurfacePixelFormat.BGRA8_UNORM
            "rgba" -> if (isSrgb) BrowserSurfacePixelFormat.RGBA8_SRGB else BrowserSurfacePixelFormat.RGBA8_UNORM
            "rgbaf16" -> BrowserSurfacePixelFormat.RGBA16_FLOAT
            else -> error("Unsupported Electron browser texture format ${message.string("pixelFormat")}")
        }
        surface.offerFrame(
            ExternalBrowserFrame(
                streamId = message.long("streamId"),
                frameId = message.long("frameId"),
                releaseToken = token,
                slot = message.int("slot"),
                width = message.int("width"),
                height = message.int("height"),
                pixelFormat = pixelFormat,
                handle = handle,
                colorSpace = colorSpace,
                visibleRect = message.rect("visibleRect"),
                contentRect = message.rect("contentRect"),
                timestampMicros = message["timestamp"]?.jsonPrimitive?.longOrNull,
            )
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val closedError = PuppetBrowserCommandException("Puppet browser closed")
        ready.completeExceptionally(closedError)
        pending.values.forEach { it.completeExceptionally(closedError) }
        pending.clear()
        writer.getAndSet(null)?.let { out ->
            synchronized(out) {
                out.println("{\"type\":\"close\"}")
                out.flush()
            }
        }
        runCatching { server.close() }
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        worker.join(6_000)
        runCatching { transport.close() }
    }

    private fun electronBinary(root: File): File {
        val dist = File(root, "electron/node_modules/electron/dist")
        if (OS != OSType.MACOS) return File(dist, "electron")
        return listOf(
            File(dist, "Field4.app/Contents/MacOS/Field4"),
            File(dist, "Electron.app/Contents/MacOS/Field4"),
            File(dist, "Electron.app/Contents/MacOS/Electron"),
        ).firstOrNull { it.isFile } ?: File(dist, "Electron.app/Contents/MacOS/Electron")
    }
}

private fun <T> CompletableFuture<T>.discardResult(): CompletableFuture<Unit> {
    val discarded = CompletableFuture<Unit>()
    whenComplete { _, error ->
        if (error == null) discarded.complete(Unit)
        else discarded.completeExceptionally(error)
    }
    return discarded
}

/**
 * How one platform moves a Chromium-owned GPU allocation into this process. Implementations own a
 * receiver-side endpoint whose address they publish into the producer's environment, and turn each
 * announced frame into the [ExternalBrowserImageHandle] its receiver understands.
 */
private interface BrowserHandleTransport : AutoCloseable {
    /** The Node-API addon the producer loads to send on this transport; null if it needs none. */
    val nodeBridge: File?

    /** Endpoint address and anything else the producer needs to reach this receiver. */
    val producerEnvironment: Map<String, String>

    /** Blocks for the handle the producer transferred for [token] out of band. */
    fun receive(message: JsonObject, token: Long, timeoutMs: Int): ExternalBrowserImageHandle

    companion object {
        fun forThisPlatform(projectRoot: File): BrowserHandleTransport = when (OS) {
            OSType.MACOS -> MacMachSurfaceTransport(projectRoot)
            OSType.LINUX -> LinuxDmaBufTransport(projectRoot)
            else -> error(
                "FieldUiBrowserTexture has no Electron handle transport on ${OS.name.lowercase()}; " +
                    "the receiver exists but its producer bridge does not"
            )
        }
    }
}

/** macOS: Chromium's IOSurface arrives as a Mach send right on a per-session bootstrap service. */
private class MacMachSurfaceTransport(projectRoot: File) : BrowserHandleTransport {
    private val service = "com.field4.browser-texture.${ProcessHandle.current().pid()}.${System.nanoTime()}"
    private val bridgeDir = File(projectRoot, "build/field-browser-bridge/macos")
    private val inbox = MacMachSurfaceInbox(File(bridgeDir, "libfield_browser_bridge.dylib"), service)

    override val nodeBridge = File(bridgeDir, "field_browser_bridge.node")
    override val producerEnvironment = mapOf("FIELD_BROWSER_TEXTURE_MACH_SERVICE" to service)

    override fun receive(message: JsonObject, token: Long, timeoutMs: Int): ExternalBrowserImageHandle {
        val received = inbox.receive(timeoutMs)
        require(received.token == token) {
            "Mach surface token ${received.token} did not match frame token $token"
        }
        return ExternalBrowserImageHandle.MacIOSurfaceMachPort(received.port)
    }

    override fun close() = inbox.close()
}

/**
 * Linux: Chromium's `nativePixmap` dma-buf arrives over SCM_RIGHTS, because a descriptor number is
 * meaningless in another process and a dma-buf cannot be re-opened through `/proc/<pid>/fd`.
 *
 * The control message carries only the descriptors and the frame token; each plane's stride,
 * offset, size and the allocation's DRM modifier come alongside on the JSON channel, which is
 * where they belong — they are metadata, not kernel objects. Planes name their descriptor by index
 * so that planes sharing one allocation stay one allocation after the transfer.
 */
private class LinuxDmaBufTransport(projectRoot: File) : BrowserHandleTransport {
    private val socket = "field4.browser-texture.${ProcessHandle.current().pid()}.${System.nanoTime()}"
    private val bridgeDir = File(projectRoot, "build/field-browser-bridge/linux")
    private val inbox = LinuxDmaBufInbox(File(bridgeDir, "libfield_browser_bridge.so"), socket)

    override val nodeBridge = File(bridgeDir, "field_browser_bridge.node")
    override val producerEnvironment = mapOf("FIELD_BROWSER_TEXTURE_DMABUF_SOCKET" to socket)

    override fun receive(message: JsonObject, token: Long, timeoutMs: Int): ExternalBrowserImageHandle {
        val received = inbox.receive(timeoutMs)
        if (received.token != token) {
            received.closeFds()
            error("dma-buf token ${received.token} did not match frame token $token")
        }
        return try {
            val planes = message["planes"]?.jsonArray
                ?: error("Linux browser frame carries no plane layout: $message")
            ExternalBrowserImageHandle.LinuxDmaBuf(
                planes = planes.map { element ->
                    val plane = element.jsonObject
                    val index = plane.int("fdIndex")
                    require(index in received.fds.indices) {
                        "plane names descriptor $index but only ${received.fds.size} arrived"
                    }
                    ExternalBrowserImageHandle.LinuxDmaBuf.Plane(
                        fd = received.fds[index],
                        stride = plane.int("stride"),
                        offset = plane.int("offset"),
                        size = plane.long("size"),
                    )
                },
                // Electron sends the modifier as a decimal string; DRM_FORMAT_MOD_INVALID and the
                // vendor-high-bit modifiers do not fit a signed parse.
                modifier = java.lang.Long.parseUnsignedLong(message.string("modifier")),
            )
        } catch (t: Throwable) {
            received.closeFds()
            throw t
        }
    }

    override fun close() = inbox.close()
}

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("Browser texture message has no '$name': $this")

private fun JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: string(name).toLong()

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: string(name).toInt()

private fun JsonObject.rect(name: String): BrowserSurfaceRect? =
    this[name]?.jsonObject?.let { BrowserSurfaceRect(it.int("x"), it.int("y"), it.int("width"), it.int("height")) }

private fun JsonObject.colorSpace(name: String): BrowserSurfaceColorSpace? =
    (this[name] as? JsonObject)?.let {
        BrowserSurfaceColorSpace(
            primaries = it.string("primaries"),
            transfer = it.string("transfer"),
            matrix = it.string("matrix"),
            range = it.string("range"),
        )
    }


/** Picks the receiver that matches the transport: an importer, or the CPU-upload fallback. */
private fun createDelegate(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    cpuBitmapFallback: Boolean,
    releaseListener: BrowserFrameReleaseListener,
): ExternalBrowserSurface =
    if (cpuBitmapFallback) BitmapBrowserSurface(renderer, spec, releaseListener)
    else ExternalBrowserSurfaces.create(renderer, spec, releaseListener)

/**
 * The opt-in fallback's transport: no GPU handle crosses at all, so instead of answering a `frame`
 * announcement on the control channel this one *is* the frame source, reading header-plus-pixels off
 * a second loopback socket and offering each frame to the surface directly.
 */
private class CpuBitmapTransport(
    private val surface: ExternalBrowserSurface,
    private val spec: ExternalBrowserSurfaceSpec,
) : BrowserHandleTransport {
    private val server = ServerSocketChannel.open().apply {
        bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
    }
    private val closed = AtomicBoolean(false)
    private val worker: Thread

    override val nodeBridge: File? = null
    override val producerEnvironment = mapOf(
        "FIELD_BROWSER_TEXTURE_CPU_BITMAP" to "1",
        "FIELD_BROWSER_TEXTURE_PIXEL_PORT" to
            (server.localAddress as java.net.InetSocketAddress).port.toString(),
    )

    init {
        worker = Thread({ readFrames() }, "field-browser-texture-pixels").apply {
            isDaemon = true
            start()
        }
    }

    private fun readFrames() {
        try {
            server.accept().use { channel ->
                // The producer writes the header in network order; LWJGL hands back native-order
                // buffers, which is little-endian everywhere Field runs, so say so explicitly
                // rather than reading a byte-swapped magic and calling the channel desynchronised.
                val header = MemoryUtil.memAlloc(HEADER_BYTES).order(java.nio.ByteOrder.BIG_ENDIAN)
                try {
                    while (!closed.get()) {
                        header.clear()
                        if (!readFully(channel, header)) return
                        header.flip()
                        val magic = header.int
                        check(magic == MAGIC) { "browser pixel channel desynchronised (magic 0x${magic.toString(16)})" }
                        val streamId = header.long
                        val frameId = header.long
                        val slot = header.int
                        val width = header.int
                        val height = header.int
                        val stride = header.int
                        val length = header.int
                        header.int // reserved

                        require(length in 1..MAX_FRAME_BYTES) { "browser pixel frame claims $length bytes" }
                        // Read straight into the buffer the importer will memcpy from; the handle
                        // owns it from here and frees it on close, including when the mailbox
                        // conflates this frame away before it is ever imported.
                        val pixels = MemoryUtil.memAlloc(length)
                        if (!readFully(channel, pixels)) {
                            MemoryUtil.memFree(pixels)
                            return
                        }
                        pixels.flip()
                        surface.offerFrame(
                            ExternalBrowserFrame(
                                streamId = streamId,
                                frameId = frameId,
                                releaseToken = frameId,
                                slot = slot,
                                width = width,
                                height = height,
                                pixelFormat = spec.pixelFormat,
                                handle = ExternalBrowserImageHandle.CpuBitmap(pixels, stride),
                            )
                        )
                    }
                } finally {
                    MemoryUtil.memFree(header)
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) System.err.println("[field-browser-texture] pixel channel failed: ${t.message}")
        }
    }

    private fun readFully(channel: SocketChannel, into: java.nio.ByteBuffer): Boolean {
        while (into.hasRemaining()) {
            if (channel.read(into) < 0) return false
        }
        return true
    }

    override fun receive(message: JsonObject, token: Long, timeoutMs: Int): ExternalBrowserImageHandle =
        error("the CPU bitmap transport delivers frames on its own channel, not by announcement")

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
    }

    private companion object {
        const val MAGIC = 0x46504958 // "FPIX"
        const val HEADER_BYTES = 44
        const val MAX_FRAME_BYTES = 256 * 1024 * 1024
    }
}

private data class ReceivedMachSurface(val token: Long, val port: Int)

private class MacMachSurfaceInbox(libraryFile: File, service: String) : AutoCloseable {
    private val library: SharedLibrary
    private val receiveAddress: Long
    private val destroyAddress: Long
    private var receiver: Long

    init {
        require(libraryFile.isFile) {
            "Missing ${libraryFile.absolutePath}; run ./gradlew buildFieldBrowserBridge"
        }
        library = apiCreateLibrary(libraryFile.absolutePath)
        val openAddress = library.getFunctionAddress("field_browser_mach_receiver_open")
        receiveAddress = library.getFunctionAddress("field_browser_mach_receiver_receive")
        destroyAddress = library.getFunctionAddress("field_browser_mach_receiver_destroy")
        require(openAddress != 0L && receiveAddress != 0L && destroyAddress != 0L) {
            "Native browser bridge is missing its Mach receiver exports"
        }
        MemoryStack.stackPush().use { stack ->
            receiver = JNI.callPP(MemoryUtil.memAddress(stack.UTF8(service)), openAddress)
        }
        require(receiver != 0L) { "Could not register browser texture Mach service $service" }
    }

    fun receive(timeoutMs: Int): ReceivedMachSurface {
        val output = MemoryUtil.memCalloc(16)
        try {
            val address = MemoryUtil.memAddress(output)
            val result = JNI.invokePPI(receiver, address, timeoutMs, receiveAddress)
            require(result == 0) { "Mach surface receive failed with code $result" }
            return ReceivedMachSurface(
                token = MemoryUtil.memGetLong(address),
                port = MemoryUtil.memGetLong(address + 8).toInt(),
            )
        } finally {
            MemoryUtil.memFree(output)
        }
    }

    override fun close() {
        val value = receiver
        receiver = 0L
        if (value != 0L) JNI.callPV(value, destroyAddress)
        library.free()
    }
}

/**
 * One frame's worth of descriptors, already installed in this process by the kernel. They are owned
 * here from this moment: whatever happens next must either hand them to a
 * [ExternalBrowserImageHandle.LinuxDmaBuf] or [closeFds] them.
 */
private class ReceivedDmaBuf(val token: Long, val fds: List<Int>) {
    fun closeFds() = fds.forEach { fd -> runCatching { ExternalBrowserFdClose.close(fd) } }
}

private class LinuxDmaBufInbox(libraryFile: File, socketName: String) : AutoCloseable {
    private val library: SharedLibrary
    private val receiveAddress: Long
    private val destroyAddress: Long
    private var receiver: Long

    init {
        require(libraryFile.isFile) {
            "Missing ${libraryFile.absolutePath}; run ./gradlew buildFieldBrowserBridge"
        }
        library = apiCreateLibrary(libraryFile.absolutePath)
        val openAddress = library.getFunctionAddress("field_browser_dmabuf_receiver_open")
        receiveAddress = library.getFunctionAddress("field_browser_dmabuf_receiver_receive")
        destroyAddress = library.getFunctionAddress("field_browser_dmabuf_receiver_destroy")
        require(openAddress != 0L && receiveAddress != 0L && destroyAddress != 0L) {
            "Native browser bridge is missing its dma-buf receiver exports"
        }
        MemoryStack.stackPush().use { stack ->
            receiver = JNI.callPP(MemoryUtil.memAddress(stack.UTF8(socketName)), openAddress)
        }
        require(receiver != 0L) { "Could not bind browser texture dma-buf socket @$socketName" }
    }

    fun receive(timeoutMs: Int): ReceivedDmaBuf {
        // token, descriptor count, then up to four descriptors.
        val output = MemoryUtil.memCalloc(8 * 8)
        try {
            val address = MemoryUtil.memAddress(output)
            val result = JNI.invokePPI(receiver, address, timeoutMs, receiveAddress)
            require(result == 0) { "dma-buf receive failed with code $result" }
            val count = MemoryUtil.memGetLong(address + 8).toInt()
            require(count in 1..4) { "dma-buf receive reported $count descriptors" }
            return ReceivedDmaBuf(
                token = MemoryUtil.memGetLong(address),
                fds = (0 until count).map { MemoryUtil.memGetLong(address + 16 + it * 8L).toInt() },
            )
        } finally {
            MemoryUtil.memFree(output)
        }
    }

    override fun close() {
        val value = receiver
        receiver = 0L
        if (value != 0L) JNI.callPV(value, destroyAddress)
        library.free()
    }
}

/** `close(2)`, for descriptors this file received but never handed to a Vulkan import. */
private object ExternalBrowserFdClose {
    private val libc: SharedLibrary by lazy { apiCreateLibrary("libc.so.6") }
    private val closeAddress: Long by lazy { libc.getFunctionAddress("close") }

    fun close(fd: Int) {
        check(JNI.callI(fd, closeAddress) == 0) { "close($fd) failed" }
    }
}

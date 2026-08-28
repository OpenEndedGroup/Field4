package afield.four.browser

import afield.Box
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.ImageView
import afield.four.util.OS
import afield.four.util.OSType
import afield.globalServerPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
) : ExternalBrowserSurface {
    private val closed = AtomicBoolean(false)
    private val writer = AtomicReference<PrintWriter?>()
    private val loadedAfterFrame = AtomicLong(Long.MAX_VALUE)
    private val projectRoot = locateProjectRoot()
    private val specValue = ExternalBrowserSurfaceSpec(
        width = width,
        height = height,
        pixelFormat = BrowserSurfacePixelFormat.BGRA8_SRGB,
        slotCount = slots,
    )
    private val delegate = ExternalBrowserSurfaces.create(renderer, specValue) { release ->
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
    private val session = when (OS) {
        OSType.MACOS -> MacElectronBrowserTextureSession(
            projectRoot = projectRoot,
            surface = delegate,
            url = url,
            spec = specValue,
            writer = writer,
            loadedAfterFrame = loadedAfterFrame,
            readyBoxUid = readyBoxUid,
        )

        else -> error(
            "FieldUiBrowserTexture producer transport is currently live on macOS; " +
                "the ${OS.name.lowercase()} receiver exists but still needs its Electron handle-transfer bridge"
        )
    }

    override val spec: ExternalBrowserSurfaceSpec get() = delegate.spec
    override val presentedFrame: ExternalBrowserFrame? get() = delegate.presentedFrame
    val pageReady: Boolean
        get() = delegate.presentedFrame?.frameId?.let { it > loadedAfterFrame.get() } == true
    override val imageView: ImageView get() = delegate.imageView
    override val imageViewArray get() = delegate.imageViewArray

    override fun offerFrame(frame: ExternalBrowserFrame): BrowserFrameOfferResult = delegate.offerFrame(frame)

    override fun prepareSampledImagesFor(command: CommandBuffer) = delegate.prepareSampledImagesFor(command)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.close()
        delegate.close()
    }

    companion object {
        /** URL for another browser view of the document containing [box]. */
        fun fieldDocumentUrl(box: Box): String {
            val document = URLEncoder.encode(box.inside.documentName, StandardCharsets.UTF_8)
            return "https://localhost:$globalServerPort/index.html?file=$document"
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

private class MacElectronBrowserTextureSession(
    projectRoot: File,
    private val surface: ExternalBrowserSurface,
    url: String,
    spec: ExternalBrowserSurfaceSpec,
    private val writer: AtomicReference<PrintWriter?>,
    private val loadedAfterFrame: AtomicLong,
    readyBoxUid: String?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val service = "com.field4.browser-texture.${ProcessHandle.current().pid()}.${System.nanoTime()}"
    private val bridgeDir = File(projectRoot, "build/field-browser-bridge/macos")
    private val inbox = MacMachSurfaceInbox(File(bridgeDir, "libfield_browser_bridge.dylib"), service)
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val process: Process
    private val worker: Thread

    init {
        val electron = macElectronBinary(projectRoot)
        val producer = File(projectRoot, "electron/browser-texture-producer.js")
        val nodeBridge = File(bridgeDir, "field_browser_bridge.node")
        listOf(electron, producer, nodeBridge).forEach { file ->
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
                environment()["FIELD_BROWSER_TEXTURE_SLOTS"] = spec.slotCount.toString()
                environment()["FIELD_BROWSER_TEXTURE_CONTROL_PORT"] = server.localPort.toString()
                environment()["FIELD_BROWSER_TEXTURE_MACH_SERVICE"] = service
                environment()["FIELD_BROWSER_TEXTURE_BRIDGE"] = nodeBridge.absolutePath
                environment()["FIELD_BROWSER_TEXTURE_STREAM_ID"] = streamId.toString()
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
            "ready" -> println("[field-browser-texture] producer ready pid=${message.string("pid")}")
            "loaded" -> {
                loadedAfterFrame.set(message.long("afterFrameId"))
                println("[field-browser-texture] page loaded ${message.string("url")}")
            }
            "error" -> System.err.println("[field-browser-texture] ${message.string("message")}")
            "frame" -> offer(message)
        }
    }

    private fun offer(message: JsonObject) {
        val token = message.long("token")
        val received = inbox.receive(5_000)
        require(received.token == token) {
            "Mach surface token ${received.token} did not match frame token $token"
        }
        val width = message.int("width")
        val height = message.int("height")
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
                width = width,
                height = height,
                pixelFormat = pixelFormat,
                handle = ExternalBrowserImageHandle.MacIOSurfaceMachPort(received.port),
                colorSpace = colorSpace,
                visibleRect = message.rect("visibleRect"),
                contentRect = message.rect("contentRect"),
                timestampMicros = message["timestamp"]?.jsonPrimitive?.longOrNull,
            )
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
        runCatching { inbox.close() }
    }

    private fun macElectronBinary(root: File): File {
        val dist = File(root, "electron/node_modules/electron/dist")
        return listOf(
            File(dist, "Field4.app/Contents/MacOS/Field4"),
            File(dist, "Electron.app/Contents/MacOS/Field4"),
            File(dist, "Electron.app/Contents/MacOS/Electron"),
        ).firstOrNull { it.isFile } ?: File(dist, "Electron.app/Contents/MacOS/Electron")
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

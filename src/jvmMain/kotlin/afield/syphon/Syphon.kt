@file:afield.CompletionCategory("Graphics")
package afield.syphon

import afield.Runloop
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.RenderToTexture
import afield.util.PngEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import java.awt.image.BufferedImage
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Field's end of [Syphon](https://syphon.info), the macOS standard for sharing GPU frames between
 * applications: a [SyphonServer] publishes frames that Resolume, TouchDesigner, MadMapper, OBS
 * (with its Syphon source), VDMX or Max can receive, and a [SyphonClient] receives what they publish.
 *
 * ```
 * val out = syphonServer("Field")             // appears in every Syphon client's list
 * out.publish(image)                          // a BufferedImage, RGBA bytes, or a RenderToTexture
 *
 * val inp = syphonClient("Resolume Arena")    // a server's name, app name or uuid
 * display("main", inp.png())                  // the latest frame
 * ```
 *
 * Field reaches Syphon through a small Objective-C library, `native/field-syphon`, that is optional
 * and macOS-only — build it once with `./gradlew buildSyphonNative` (needs Xcode).
 *
 * Every frame crosses the boundary through memory today: a [RenderToTexture] is read back from the
 * GPU and copied into Syphon's shared IOSurface, and a received frame is read back into a
 * `ByteArray`. At 1920 x 1080 that is about 8 MB a frame each way. Publishing an IOSurface that
 * already exists ([SyphonServer.publishIOSurface]) skips the CPU entirely.
 */
object Syphon {
    private val pumping = AtomicBoolean(false)

    /** Run-loop turns given to Syphon on the process's main thread (see [ensurePump]). */
    @Volatile
    var mainLoopTurns: Long = 0
        private set

    /** Pump calls that found themselves off the main thread, and so did nothing. */
    @Volatile
    var offMainCalls: Long = 0
        private set

    /**
     * Syphon announces and discovers servers through macOS distributed notifications, which arrive
     * on the process's main run loop. Field's main thread runs Field's own loop instead (it starts
     * with `-XstartOnFirstThread`), so this hooks a non-blocking run-loop turn into every
     * [Runloop] frame. Without it Field's servers are found only by clients that were already
     * listening when they started, and Field never sees other applications' servers.
     */
    internal fun ensurePump() {
        if (!pumping.compareAndSet(false, true)) return
        Runloop.runLoop.preRun["syphon-pump"] = sequence {
            while (true) {
                runCatching { if (SyphonNative.pump(0)) mainLoopTurns++ else offMainCalls++ }
                yield(null)
            }
        }
    }

    /** Whether the native bridge is built (and this is a Mac). */
    val isAvailable: Boolean get() = SyphonLibrary.isAvailable

    /** A sentence saying where the bridge is, or how to build it. */
    fun describe(): String = SyphonLibrary.describe()

    internal fun requireLibrary() {
        check(SyphonLibrary.isAvailable) { SyphonLibrary.describe() }
        ensurePump()
    }

    /**
     * Every Syphon server announced on this machine — Field's own included. The first call waits
     * up to [waitMs] for announcements to arrive.
     */
    fun servers(waitMs: Int = 500): List<SyphonServerInfo> {
        requireLibrary()
        val a = JSONArray(SyphonNative.list(waitMs))
        return (0 until a.length()).map { SyphonServerInfo.of(a.getJSONObject(it)) }
    }

    internal val open = ConcurrentHashMap<String, SyphonServer>()
}

/** One announced server: what [SyphonClient] can connect to. */
data class SyphonServerInfo(val name: String, val appName: String, val uuid: String) {
    companion object {
        internal fun of(o: JSONObject) = SyphonServerInfo(o.optString("name"), o.optString("appName"), o.optString("uuid"))
    }

    override fun toString(): String = if (name.isEmpty()) appName else "$appName — $name"
}

/**
 * A Syphon server: frames published here are visible to every Syphon client on the machine.
 *
 * Make one with [syphonServer], which returns the open server of that name rather than a second
 * one, so re-running a box keeps publishing to the same source. A server holds native resources
 * until [close].
 */
class SyphonServer internal constructor(val name: String) : AutoCloseable {
    private val handle: MemorySegment = SyphonNative.serverNew(name)
    private val cleanable = SyphonNative.cleaner.register(this, SyphonNative.Free(handle, SyphonNative::serverFree))
    @Volatile private var closed = false
    @Volatile private var following: RenderToTexture? = null

    /** Frames published so far. */
    @Volatile
    var framesPublished: Long = 0
        private set

    /** This server as other applications see it. */
    val info: SyphonServerInfo get() = SyphonServerInfo.of(JSONObject(SyphonNative.serverDescription(handle)))

    /** Whether any application is receiving. */
    val hasClients: Boolean get() = !closed && SyphonNative.hasClients(handle)

    private fun live() = check(!closed) { "Syphon server \"$name\" is closed" }

    /**
     * Publish [width] x [height] pixels, four bytes each, top row first: RGBA order, or BGRA when
     * [bgra] is true (what a `B8G8R8A8` render target reads back as). The bytes are copied; reuse
     * the array at once.
     */
    @JvmOverloads
    fun publish(pixels: ByteArray, width: Int, height: Int, bgra: Boolean = false) {
        live()
        synchronized(this) { SyphonNative.publish(handle, pixels, width, height, bgra) }
        framesPublished++
    }

    /** Publish ARGB ints (as `BufferedImage.getRGB` and [afield.util.PngEncoder] use), top row first. */
    fun publish(argb: IntArray, width: Int, height: Int) {
        require(argb.size >= width * height) { "need ${width * height} pixels, got ${argb.size}" }
        val bgra = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val p = argb[i]
            bgra[i * 4] = p.toByte()
            bgra[i * 4 + 1] = (p shr 8).toByte()
            bgra[i * 4 + 2] = (p shr 16).toByte()
            bgra[i * 4 + 3] = (p ushr 24).toByte()
        }
        publish(bgra, width, height, bgra = true)
    }

    /** Publish a [BufferedImage] (any type; it is converted to ARGB). */
    fun publish(image: BufferedImage) {
        val w = image.width
        val h = image.height
        publish(image.getRGB(0, 0, w, h, null, 0, w), w, h)
    }

    /**
     * Publish the next frame [target] renders: its colour attachment is read back once that frame's
     * GPU work completes, then handed to Syphon. Returns at once. The target needs 8-bit RGBA or
     * BGRA colour (the default `B8G8R8A8_SRGB` is fine) and `colorTransferSrc = true` (the
     * default).
     */
    fun publish(target: RenderToTexture) {
        live()
        val bgra = bgraOf(target)
        val w = target.parameters.width
        val h = target.parameters.height
        target.readPixelsAsync().thenAccept { bytes -> if (!closed) publish(bytes, w, h, bgra) }
    }

    /**
     * Publish every frame [target] renders, until [stopFollowing], [close], or another call to
     * [follow]. One readback is in flight at a time, so a slow consumer drops frames instead of
     * queueing them.
     */
    fun follow(target: RenderToTexture) {
        live()
        val bgra = bgraOf(target)
        gpu?.close()
        gpu = null
        following = target
        fun next() {
            if (closed || following !== target) return
            val w = target.parameters.width
            val h = target.parameters.height
            target.readPixelsAsync().whenComplete { bytes, err ->
                if (err == null && bytes != null && !closed && following === target) {
                    runCatching { publish(bytes, w, h, bgra) }
                }
                if (err == null) next()
            }
        }
        next()
    }

    /** Stop [follow]ing and [followOnGpu]. */
    fun stopFollowing() {
        following = null
        gpu?.close()
        gpu = null
    }

    @Volatile private var gpu: SyphonGpuPublisher? = null

    /**
     * Publish every frame [target] renders without a CPU readback: each frame is copied on the GPU
     * into an IOSurface and Syphon takes it from there. Needs a `B8G8R8A8` target (the default) and
     * a driver with `VK_EXT_external_memory_host` (KosmicKrisp and MoltenVK on Apple silicon); call
     * it after [target] is in the renderer's list, since it renders after whatever is already there.
     * Replaces any earlier [follow] or [followOnGpu]; [stopFollowing] ends it.
     */
    @JvmOverloads
    fun followOnGpu(target: RenderToTexture, renderer: Renderer = fromContext()): SyphonGpuPublisher {
        live()
        stopFollowing()
        val p = SyphonGpuPublisher(this, target, renderer)
        val key = "syphon:$name"
        // Remove first so the key goes to the end of the (insertion-ordered) list, after the target.
        renderer.renderList.others.remove(key)
        renderer.renderList.others[key] = p
        gpu = p
        return p
    }

    /**
     * Publish an existing BGRA8 IOSurface by its `IOSurfaceRef` address, without touching the CPU:
     * Syphon copies it GPU-side into its own shared surface. [flipped] says the surface's first row
     * is the bottom of the picture.
     */
    @JvmOverloads
    fun publishIOSurface(ioSurfaceRef: Long, flipped: Boolean = false) {
        live()
        synchronized(this) { SyphonNative.publishSurface(handle, ioSurfaceRef, flipped) }
        framesPublished++
    }

    /** Stop publishing; clients see the server disappear. */
    override fun close() {
        if (closed) return
        closed = true
        following = null
        gpu?.close()
        gpu = null
        Syphon.open.remove(name, this)
        synchronized(this) { cleanable.clean() }
    }

    override fun toString(): String = "SyphonServer(\"$name\", ${if (closed) "closed" else "$framesPublished frames"})"

    private fun bgraOf(target: RenderToTexture): Boolean = when (target.parameters.format) {
        VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_B8G8R8A8_UNORM -> true
        VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM -> false
        else -> throw IllegalArgumentException(
            "Syphon publishes 8-bit RGBA/BGRA; this RenderToTexture's format is ${target.parameters.format}")
    }
}

/**
 * A connection to another application's Syphon server. [latest] returns its most recent frame.
 * Holds native resources until [close].
 */
class SyphonClient internal constructor(private val handle: MemorySegment) : AutoCloseable {
    private val cleanable = SyphonNative.cleaner.register(this, SyphonNative.Free(handle, SyphonNative::clientFree))
    @Volatile private var closed = false
    private var buffer: ByteArray? = null

    /** The server this client is connected to. */
    val server: SyphonServerInfo = SyphonServerInfo.of(JSONObject(SyphonNative.clientDescription(handle)))

    /** Frames the server has sent since this client connected. */
    val frameCount: Long get() = if (closed) 0 else SyphonNative.frameCount(handle)

    /** Wait up to [waitMs] for a frame after the [after]th; returns the frame count then. */
    @JvmOverloads
    fun waitForFrame(after: Long = frameCount, waitMs: Int = 1000): Long = SyphonNative.waitFrame(handle, after, waitMs)

    /** The latest frame, or null if none has arrived yet. */
    fun latest(): SyphonFrame? {
        check(!closed) { "Syphon client is closed" }
        val (bytes, w, h) = synchronized(this) { SyphonNative.copyRgba(handle, null) } ?: return null
        return SyphonFrame(bytes, w, h)
    }

    internal fun copyInto(dst: MemorySegment): Pair<Int, Int>? =
        if (closed) null else synchronized(this) { SyphonNative.copyRgbaInto(handle, dst) }

    /**
     * This client's frames as a Vulkan texture that follows the server: bind
     * [SyphonTexture.texture] as a `sampler2D` like any other texture. Waits up to [waitMs] for a
     * first frame, whose size the texture keeps. Registered in [renderer]'s list, which is what
     * refreshes it each frame.
     */
    @JvmOverloads
    fun texture(renderer: Renderer = fromContext(), waitMs: Int = 2000): SyphonTexture {
        check(!closed) { "Syphon client is closed" }
        if (frameCount == 0L) waitForFrame(0, waitMs)
        val first = latest() ?: throw IllegalStateException("Syphon: no frame from $server within $waitMs ms")
        val t = SyphonTexture(this, renderer, first.width, first.height)
        val key = "syphon-in:${server.uuid}"
        renderer.renderList.others.remove(key)
        renderer.renderList.others[key] = t
        return t
    }

    /** The latest frame as a PNG (for `display`), or null if none has arrived yet. */
    fun png(): ByteArray? = latest()?.png()

    override fun close() {
        if (closed) return
        closed = true
        synchronized(this) { cleanable.clean() }
    }

    override fun toString(): String = "SyphonClient($server, ${if (closed) "closed" else "$frameCount frames"})"
}

/** One received frame: [rgba] is [width] x [height] x 4 bytes, top row first. */
class SyphonFrame(val rgba: ByteArray, val width: Int, val height: Int) {
    /** The RGBA of pixel ([x], [y]), y down from the top, as 0..255 ints. */
    fun rgbaAt(x: Int, y: Int): IntArray {
        val i = (y * width + x) * 4
        return IntArray(4) { rgba[i + it].toInt() and 0xff }
    }

    /** ARGB ints, one per pixel, top row first. */
    fun argb(): IntArray = IntArray(width * height) { i ->
        val o = i * 4
        ((rgba[o + 3].toInt() and 0xff) shl 24) or ((rgba[o].toInt() and 0xff) shl 16) or
            ((rgba[o + 1].toInt() and 0xff) shl 8) or (rgba[o + 2].toInt() and 0xff)
    }

    fun image(): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
        it.setRGB(0, 0, width, height, argb(), 0, width)
    }

    fun png(): ByteArray = PngEncoder.encode(argb(), width, height)

    override fun toString(): String = "SyphonFrame($width x $height)"
}

/**
 * The open Syphon server called [name], or a new one. Other applications list it under Field's
 * app name and this [name].
 */
fun syphonServer(name: String = "Field"): SyphonServer {
    Syphon.requireLibrary()
    return Syphon.open.compute(name) { _, existing -> existing ?: SyphonServer(name) }!!
}

/**
 * Connect to the first server whose uuid, name, or app name (tried in that order) is [match]; an
 * empty [match] takes the first server announced. Waits up to [waitMs] for it to appear, then
 * throws, listing the servers it could see.
 */
@JvmOverloads
fun syphonClient(match: String = "", waitMs: Int = 2000): SyphonClient {
    Syphon.requireLibrary()
    val h = SyphonNative.clientNew(match, waitMs) ?: run {
        val why = SyphonNative.lastErrorMessage()
        val seen = runCatching { Syphon.servers(0) }.getOrDefault(emptyList())
        throw IllegalStateException("Syphon: $why. Announced: ${if (seen.isEmpty()) "none" else seen.joinToString()}")
    }
    return SyphonClient(h)
}

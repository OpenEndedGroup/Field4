package afield.four.stream

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.targets.RenderToTexture
import afield.four.texture.MetalIOSurface
import afield.logger.lprintln
import org.json.JSONObject
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams an IOSurface-backed RTT off the GPU to the visionOS receiver via the `field-encode`
 * co-process (the *producer* seam from `dev_docs/vision_frontend.md`).
 *
 * ### Why the helper allocates the surfaces
 * MoltenVK's *export* path (`VkExportMetalObjectCreateInfoEXT`) produces IOSurfaces with
 * `pixelFormat = 0`, which CoreVideo/VideoToolbox refuse to wrap (`CVPixelBufferCreateWithIOSurface`
 * → -6661). A surface born via `IOSurfaceCreate` with a real pixelFormat *is* wrappable, and
 * IOSurface is cross-process — so on [start] we ask the helper to `alloc` a ring, look the surfaces
 * up by global ID here, and *import* them as the RTT backing (`RTTParameters.importMetalIOSurface`).
 *
 * ### Usage (from a Field box)
 * ```
 * val streamer = FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720)
 * val target = streamer.start().first()   // an imported, VideoToolbox-encodable RTT
 * renderer.renderList += "streamTarget" to target
 * // ... attach a module + mesh that draws into `target` each frame (your "6. RTT" idiom) ...
 * // stop later: streamer.stop()
 * ```
 * For a ring (`ringSize > 1`), render each frame into `surfaces[streamer.activeSlot]` so you never
 * overwrite the surface the encoder is currently reading.
 *
 * The per-frame `frame` tick is deferred with [Renderer.afterCurrentGraphicsFrameComplete] — the
 * fence-first correctness gate, so the encoder never reads a half-written frame.
 *
 * macOS / MoltenVK only.
 */
class FieldRTTStreamer(
    private val renderer: Renderer,
    private val dest: String = "127.0.0.1:9000",
    private val width: Int = 1280,
    private val height: Int = 720,
    private val ringSize: Int = 1,
    private val fps: Int = 60,
    private val bitrateKbps: Int = 20_000,
    private val codec: String = "hevc",
    private val idrIntervalMs: Int = 1000,
    private val colorFormat: Int = VK_FORMAT_B8G8R8A8_UNORM,
    private val name: String = "field-stream",
    private val encoder: FieldEncodeProcess = FieldEncodeProcess(),
) : Target {

    /** The ring of RTTs to render into, created on [start]. Empty until then. */
    val surfaces = mutableListOf<RenderToTexture>()

    @Volatile
    var streaming: Boolean = false
        private set

    private var seq: Long = 0
    private var startNanos: Long = 0
    private var lastSentNanos: Long = 0

    private val allocLatch = CountDownLatch(1)
    private val allocatedIds = AtomicReference<List<Long>>(emptyList())

    /** The ring slot the *next* rendered frame will be encoded from (render into this one). */
    val activeSlot: Int get() = (seq % maxOf(1, surfaces.size)).toInt()

    /**
     * Launch the helper, allocate + import the surface ring, open the stream, and register the
     * per-frame tick. Returns the ring RTTs to render into (empty on failure). Call from the render
     * thread (a box). Blocks briefly for the alloc round-trip.
     */
    fun start(): List<RenderToTexture> {
        if (streaming) return surfaces

        encoder.onEvent = { ev -> onEncoderEvent(ev) }
        encoder.onExit = { code -> lprintln("info,field-stream", "field-encode exited ($code)") }
        if (!encoder.start()) {
            lprintln("error,field-stream", "field-encode failed to launch"); return emptyList()
        }

        // 1. helper allocates a ring of properly-formatted IOSurfaces and returns their global ids.
        encoder.alloc(width = width, height = height, count = maxOf(1, ringSize))
        if (!allocLatch.await(5, TimeUnit.SECONDS)) {
            lprintln("error,field-stream", "timed out waiting for surface allocation"); return emptyList()
        }
        val ids = allocatedIds.get()
        if (ids.size != maxOf(1, ringSize)) {
            lprintln("error,field-stream", "expected ${maxOf(1, ringSize)} surfaces, got ${ids.size}"); return emptyList()
        }

        // 2. import each surface (looked up by id) as the backing of its own RTT.
        for (id in ids) {
            val ref = MetalIOSurface.lookup(id)
            if (ref == 0L) {
                lprintln("error,field-stream", "IOSurfaceLookup failed for id $id"); return emptyList()
            }
            val rtt = RenderToTexture(renderer) {
                importMetalIOSurface = ref
                this.width = this@FieldRTTStreamer.width
                this.height = this@FieldRTTStreamer.height
                format = colorFormat
                useDepth = false
                colorSampled = true       // so the box can also sample it to screen if it wants
            }
            surfaces += rtt
        }

        // 3. open the stream (surfaces already live in the helper from alloc) and start ticking.
        encoder.open(width = width, height = height, fps = fps, bitrateKbps = bitrateKbps,
                     dest = dest, codec = codec, idrIntervalMs = idrIntervalMs)
        renderer.renderList += name to this

        seq = 0
        startNanos = System.nanoTime()
        streaming = true
        lprintln("info,field-stream",
            "streaming ${width}x${height} @${fps}fps ($codec) -> $dest, ${surfaces.size} surface(s): $ids")
        return surfaces
    }

    /** Stop ticking, unregister from the render list, and tear down the helper. */
    @Synchronized
    fun stop() {
        if (!streaming) return
        streaming = false
        renderer.renderList.others.remove(name)
        encoder.close()
        lprintln("info,field-stream", "stopped after $seq frames")
    }

    /**
     * Called during graphics recording each frame (this is a render-list [Target]). Defers the
     * encoder's per-frame ready tick until this frame's fence signals — the correctness gate.
     */
    override fun render(command: CommandBuffer, index: Int) {
        if (!streaming) return

        // Emit at the configured `fps`, NOT at the renderer's rate. Field renders ~60fps; ticking
        // every frame floods the transport, and a receiver that can't keep up (e.g. the sim's
        // software decoder) drops fragments → P-frames decode against missing references → garbage
        // until the next IDR. Throttling to `fps` also gives the encoder a full frame-interval to
        // read each surface, so a single-surface ring is safe.
        val now = System.nanoTime()
        val intervalNanos = 1_000_000_000L / maxOf(1, fps)
        if (lastSentNanos != 0L && now - lastSentNanos < intervalNanos) return
        lastSentNanos = now

        val slot = (seq % surfaces.size).toInt()
        val s = seq
        val pts = (now - startNanos) / 1000   // microseconds since start
        val forceIdr = s == 0L
        renderer.afterCurrentGraphicsFrameComplete {
            encoder.frame(surface = slot, seq = s, ptsMicros = pts, forceIdr = forceIdr)
        }
        seq++
    }

    private fun onEncoderEvent(ev: JSONObject) {
        when (ev.optString("ev")) {
            "allocated" -> {
                val arr = ev.optJSONArray("ids")
                val ids = (0 until (arr?.length() ?: 0)).map { arr!!.getLong(it) and 0xFFFF_FFFFL }
                allocatedIds.set(ids)
                allocLatch.countDown()
            }
            "error" -> {
                lprintln("error,field-stream", "encoder: ${ev.optString("msg")}")
                allocLatch.countDown()   // unblock start() so it fails fast
            }
            "opened" -> lprintln("info,field-stream", "encoder opened")
            else -> {}
        }
    }
}

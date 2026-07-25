package afield.four.stream

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.targets.RenderToTexture
import afield.four.texture.MetalIOSurface
import afield.four.texture.UnixFdPass
import afield.four.texture.VulkanExternalMemory
import afield.four.util.OS
import afield.four.util.OSType
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
 * Attach your content module to **every** ring surface; the streamer self-registers and renders
 * exactly the active surface each tick, so do **not** add the surfaces to `renderer.renderList`:
 * ```
 * val streamer = FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720, ringSize = 3)
 * val surfaces = streamer.start()
 * val module = moduleHere { surfaces.first().apply() }; module += "mesh" to m
 * surfaces.forEach { it += "content" to module }
 * // stop later: streamer.stop()
 * ```
 * With `ringSize > 1` the streamer round-robins which surface it renders+encodes, giving the encoder
 * a full ring of tick-intervals to read each frame (render-ahead). `ringSize = 1` is fine at the
 * throttled rate; a ring adds headroom at high fps/resolution.
 *
 * The per-frame `frame` tick is deferred with [Renderer.afterCurrentGraphicsFrameComplete] — the
 * fence-first correctness gate, so the encoder never reads a half-written frame.
 *
 * The **receiver** end of this stream is the visionOS shell at `native/field-visionos`
 * (`Field4Vision/RTT/`: UDP reassembly → VideoToolbox decode → RealityKit). The wire contract is
 * `native/field-encode/WIRE_FORMAT.md`.
 *
 * macOS (MoltenVK/VideoToolbox) and Linux (NVIDIA/NVENC) — same API either side; [start] branches
 * internally on [OS]. The ownership of surface allocation is reversed between the two (see
 * WIRE_FORMAT.md's Linux addendum): Mac's helper allocates+returns IOSurface ring IDs for this
 * JVM to import (`alloc`, [MetalIOSurface]); Linux has no such round-trip because a helper
 * process can't allocate a Vulkan image — this JVM allocates the ring directly
 * (`exportOpaqueFd = true`) and hands the helper {pid,fd,size,rowPitch} handles instead
 * ([VulkanExternalMemory]).
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
    // Mark the stream as side-by-side stereo (left|right in each double-wide frame). Sets the wire
    // STEREO_SBS flag so the receiver presents it per-eye. Producing actual SBS content into the RTT
    // is the caller's job — a wider RTT (width already covers both eyes) with the two views drawn.
    private val stereo: Boolean = false,
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

        val opened = if (OS == OSType.LINUX) startLinux() else startMac()
        if (!opened) return emptyList()

        renderer.renderList += name to this
        seq = 0
        streaming = true
        lprintln("info,field-stream",
            "streaming ${width}x${height} @${fps}fps ($codec) -> $dest, ${surfaces.size} surface(s)")
        return surfaces
    }

    /** macOS/MoltenVK: helper allocates the IOSurface ring; this JVM imports each by looked-up id. */
    private fun startMac(): Boolean {
        // 1. helper allocates a ring of properly-formatted IOSurfaces and returns their global ids.
        encoder.alloc(width = width, height = height, count = maxOf(1, ringSize))
        if (!allocLatch.await(5, TimeUnit.SECONDS)) {
            lprintln("error,field-stream", "timed out waiting for surface allocation"); return false
        }
        val ids = allocatedIds.get()
        if (ids.size != maxOf(1, ringSize)) {
            lprintln("error,field-stream", "expected ${maxOf(1, ringSize)} surfaces, got ${ids.size}"); return false
        }

        // 2. import each surface (looked up by id) as the backing of its own RTT.
        for (id in ids) {
            val ref = MetalIOSurface.lookup(id)
            if (ref == 0L) {
                lprintln("error,field-stream", "IOSurfaceLookup failed for id $id"); return false
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

        // 3. open the stream (surfaces already live in the helper from alloc).
        encoder.open(width = width, height = height, fps = fps, bitrateKbps = bitrateKbps,
                     dest = dest, codec = codec, idrIntervalMs = idrIntervalMs, stereo = stereo)
        return true
    }

    /**
     * Linux/NVIDIA: no helper-side `alloc` — this JVM allocates the ring itself
     * (`exportOpaqueFd = true`) and hands the helper {pid,fd,size,rowPitch} handles so it can
     * duplicate each fd out of this process's /proc/<pid>/fd (WIRE_FORMAT.md's Linux addendum).
     */
    private fun startLinux(): Boolean {
        for (i in 0 until maxOf(1, ringSize)) {
            val rtt = RenderToTexture(renderer) {
                exportOpaqueFd = true
                this.width = this@FieldRTTStreamer.width
                this.height = this@FieldRTTStreamer.height
                format = colorFormat
                useDepth = false
                colorSampled = true
            }
            surfaces += rtt
        }
        val handles = surfaces.map { rtt ->
            val image = rtt.colorImageRefs.getOrNull(0)
                ?: run { lprintln("error,field-stream", "ring surface has no color image to export"); return false }
            VulkanExternalMemory.handle(image)
        }

        // A raw fd number means nothing to another process by value alone (unlike a Mac
        // IOSurfaceID) — hand the real kernel objects to the helper via SCM_RIGHTS on a fresh
        // Unix socket, right after telling it (over the JSON channel) to expect them there.
        val fdSocket = java.nio.file.Files.createTempFile("field-encode-", ".sock").toString()
        java.io.File(fdSocket).delete() // bind() requires the path not exist yet
        encoder.open(width = width, height = height, fps = fps, bitrateKbps = bitrateKbps,
                     dest = dest, codec = codec, idrIntervalMs = idrIntervalMs,
                     fdSurfaces = handles, fdSocket = fdSocket, stereo = stereo)

        // The helper only starts listening once it processes the `open` line above; retry the
        // connect+send briefly to absorb that race rather than assume perfect ordering.
        val fds = handles.map { it.fd }.toIntArray()
        var sent = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!sent && System.nanoTime() < deadline) {
            sent = UnixFdPass.sendFds(fdSocket, fds)
            if (!sent) Thread.sleep(20)
        }
        if (!sent) {
            lprintln("error,field-stream", "failed to hand fds to field-encode-linux over $fdSocket"); return false
        }
        return true
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
        if (!streaming || surfaces.isEmpty()) return

        // Emit at the configured `fps`, NOT at the renderer's rate. Field renders ~60-90fps; ticking
        // every frame floods the transport, and a receiver that can't keep up (e.g. the sim's
        // software decoder) drops fragments → P-frames decode against missing references → garbage
        // until the next IDR.
        val now = System.nanoTime()
        val intervalNanos = 1_000_000_000L / maxOf(1, fps)
        if (lastSentNanos == 0L) {
            lastSentNanos = now
        } else {
            val due = lastSentNanos + intervalNanos
            if (now < due) return
            // Advance on the IDEAL grid, not to `now`. We can only emit on a renderer tick, so
            // `now` is always a little past `due`; assigning `now` carries that rounding error
            // forward and re-rounds it every frame. The result beats against the render rate —
            // at 30fps target on a ~64fps renderer the gate alternates between 2 and 3 ticks
            // (31ms / 47ms), averaging ~27fps with visible judder even though nothing is lost.
            // Advancing by exactly one interval keeps the long-run rate on target and bounds the
            // jitter to a single render tick. If we've fallen more than a whole interval behind
            // (a real stall — GPU hitch, encoder backpressure), resync to `now` rather than
            // bursting several frames to catch up.
            // Advance along the grid in WHOLE intervals, never to `now`. Snapping to `now` on a late
            // tick silently discards the remainder, which is why the long-run rate measured 29.56fps
            // against a 30fps target. That 0.44fps deficit is fatal downstream: the headset displays
            // at 90Hz, so a frame occupies a whole number of refreshes and 30.00fps is exactly 3.
            // At 29.56fps each frame wants 33.83ms — 0.5ms more than 3 refreshes — so the error
            // accumulates until one frame is held for 4 refreshes instead of 3, roughly every 22
            // frames (~0.7s). No amount of receiver buffering can fix that; the rate has to be
            // exact. Skipping whole intervals keeps both the phase and the long-run rate correct.
            lastSentNanos = if (now - due > intervalNanos) {
                due + ((now - due) / intervalNanos) * intervalNanos
            } else due
        }

        // Render the box's content into exactly the active ring surface, then encode it. Rendering
        // only this one (not all N) is what makes the ring real: while the encoder reads slot N, the
        // next tick renders slot N+1, so a surface isn't overwritten for `ringSize` tick-intervals.
        // This target lives in `others` (rendered outside the main pass), so driving the RTT's own
        // render pass here is the same call path as registering it in the render list.
        val slot = (seq % surfaces.size).toInt()
        surfaces[slot].render(command, index)

        val s = seq
        // Presentation timestamp on the IDEAL grid, derived from the frame index — NOT from `now`.
        //
        // `now` is when a render tick happened to fire, and we can only emit on a tick (~8.3ms at
        // Field's ~120fps loop), so a wall-clock pts bakes the quantisation error into the stream:
        // a 30fps target lands 4 ticks apart most of the time but 3 (25ms) or 5 (41.7ms) whenever
        // phase drifts — measured on loopback as ~190 of 2530 frames off-nominal, one every 0.45s.
        // A receiver scheduling against those timestamps faithfully REPRODUCES the irregularity, so
        // its jitter buffer can absorb delivery jitter but not this. Stamping `seq * interval` makes
        // the timeline describe the cadence we intended; the receiver's buffer then has something
        // regular to schedule against, and delivery jitter is what it soaks up.
        val pts = s * 1_000_000L / maxOf(1, fps)
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

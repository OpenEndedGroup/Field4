package afield.four.stream

import afield.Handled
import afield.Message
import afield.ReleasePolicy
import afield.RunScope
import afield.four.modules.fromContext
import afield.four.Renderer
import afield.four.Target
import afield.four.browser.MacMachSurfaceInbox
import afield.four.browser.locateFieldProjectRoot
import afield.four.device.CommandBuffer
import afield.four.targets.RenderToTexture
import afield.four.texture.IOSurfaceHostBuffer
import afield.four.texture.MetalIOSurface
import afield.four.util.OS
import afield.four.util.OSType
import afield.handlers
import afield.logger.lprintln
import afield.payload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An afield.four render target drawn straight into a box's output area in the Field Electron app.
 *
 * No readback and no encode: Electron allocates a ring of IOSurfaces, keeps each imported in
 * Chromium's GPU process, and sends each here over Mach. Each tick this renders [rtt]; when a ring
 * slot is free, it copies the finished image into that slot's surface on the same command buffer
 * (one GPU copy, [IOSurfaceHostBuffer] — on KosmicKrisp an IOSurface cannot back a `VkImage`), and
 * once the frame's fence passes it tells the page which slot is ready. The page asks Electron's main
 * process for that slot, `sharedTexture.sendSharedTexture` hands it to the renderer, the preload
 * draws it into the view's canvas, and the page hands the slot back.
 *
 * ```
 * val view = ElectronRTTView(640, 360)
 * val module = moduleHere { view.rtt.apply() }
 * module += "mesh" to m
 * view.rtt += "content" to module
 * display("main", view.html())
 * ```
 *
 * A box has one view: re-running it replaces the previous one, stopping it leaves the view drawing,
 * and deleting the box (or closing its document) releases it. Another box that needs the view takes
 * it as `@exported val view` (read as `_view` in a child) or `prop<ElectronRTTView>(view)`.
 *
 * macOS (KosmicKrisp) only. On Linux and Windows the constructor refuses with a message saying so.
 * On macOS outside the Electron app (a web browser, the HTTP mirror), the widget says it needs the app.
 * Measured latencies accumulate in [stats]. The other halves: `electron/rtt-view-host.js`,
 * `electron/field-preload.js`, and `createIOSurface` in `native/field-browser-bridge/macos_bridge.cc`.
 */
class ElectronRTTView(
    val width: Int,
    val height: Int,
    private val ringSize: Int = 3,
    format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    private val renderer: Renderer = fromContext(),
    val name: String = "electron-rtt-${counter.incrementAndGet()}",
) : Target, AutoCloseable {

    // First, before the render target or the Mach service exist: the surfaces are IOSurfaces.
    init {
        require(OS == OSType.MACOS) {
            "ElectronRTTView needs the Field app on macOS; this is $OS, where Electron hands Field no IOSurfaces " +
                "(Linux dma-buf and Windows D3D11 versions are not written yet)"
        }
    }

    /** Render your content into this; it is drawn to the canvas as is. */
    val rtt = RenderToTexture(renderer) {
        this.width = this@ElectronRTTView.width
        this.height = this@ElectronRTTView.height
        this.format = format
        useDepth = true
        colorSampled = true
        colorTransferSrc = true
    }

    val viewId = "$name-${ProcessHandle.current().pid()}-${System.nanoTime()}"
    private val service = "com.field4.rtt-view.$viewId"
    private val inbox = MacMachSurfaceInbox(
        File(locateFieldProjectRoot(), "build/field-browser-bridge/macos/libfield_browser_bridge.dylib"), service
    )

    private class Slot(val ref: Long, val host: IOSurfaceHostBuffer, @Volatile var free: Boolean = true)

    @Volatile private var slots: List<Slot> = emptyList()
    @Volatile private var generation = 0
    @Volatile private var reply: ((Message) -> Unit)? = null
    private var seq = 0L

    val stats = Stats()

    private var closed = false

    init {
        installHandlers()
        views[viewId] = this
        renderer.renderList.others[name] = this
        // One view per box: a re-run replaces the box's previous view, and the box's delete or its
        // document's close releases the last one (a keyed registration, so a stop keeps it drawing).
        RunScope.executingBox()?.let { box -> byBox.put(box.id, this)?.close() }
        RunScope.current()?.own(this, ReleasePolicy.KEYED)
    }

    /** The output-area widget: a canvas the preload draws into, and the script that wires it up. */
    fun html(): String = """
        <div class="field-rtt-view">
          <canvas data-field-rtt-view="$viewId" width="$width" height="$height" style="width:100%;display:block;background:#000"></canvas>
          <div data-field-rtt-stats="$viewId" style="font:11px monospace;opacity:.6;padding:2px 0"></div>
        </div>
        <script type="module">
          import { send, sendWithReply } from "/afield.mjs";
          const viewId = "$viewId";
          const canvas = document.querySelector(`canvas[data-field-rtt-view="${'$'}{viewId}"]`);
          const label = document.querySelector(`[data-field-rtt-stats="${'$'}{viewId}"]`);
          const host = window.fieldElectron && window.fieldElectron.rttView;
          if (!host) {
            label.textContent = "RTT view: needs the Field Electron app on macOS";
          } else {
            let drawn = 0, since = performance.now(), worst = 0, sum = 0;
            canvas.addEventListener("field-rtt-drawn", (e) => {
              const d = e.detail;
              send("electron-rtt-release", { viewId, slot: d.slot, seq: d.seq, generation: d.generation,
                readyAt: d.readyAt, mainReceivedAt: d.mainReceivedAt, receivedAt: d.receivedAt, drawnAt: d.drawnAt });
              const latency = d.drawnAt - d.readyAt;
              drawn++; sum += latency; worst = Math.max(worst, latency);
              const elapsed = performance.now() - since;
              if (elapsed > 1000) {
                label.textContent = `${'$'}{(drawn * 1000 / elapsed).toFixed(1)} fps · fence→canvas ${'$'}{(sum / drawn).toFixed(2)} ms avg, ${'$'}{worst.toFixed(2)} max`;
                drawn = 0; sum = 0; worst = 0; since = performance.now();
              }
            });
            sendWithReply("electron-rtt-subscribe", { viewId }, "electron-rtt-" + viewId, (m, p) => {
              if (p.type === "frame") host.present(viewId, p.slot, p.seq, p.readyAt, p.generation);
            });
            host.open({ viewId, service: "$service", width: $width, height: $height, count: $ringSize }).then((r) => {
              if (!r.ok) label.textContent = "RTT view: " + r.error;
            });
          }
        </script>
    """.trimIndent()

    // A fresh subscription (the widget re-ran) gets a fresh ring: receive it off the render thread,
    // then swap it in; the old ring is closed once the GPU has finished with it.
    private fun subscribe(replyChannel: (Message) -> Unit) {
        reply = replyChannel
        Thread({
            val received = mutableListOf<Slot>()
            try {
                repeat(ringSize) {
                    val surface = inbox.receive(10_000)
                    val ref = MetalIOSurface.lookupFromMachPort(surface.port)
                    MetalIOSurface.deallocateMachPort(surface.port)
                    check(ref != 0L) { "IOSurfaceLookupFromMachPort failed for slot ${surface.token}" }
                    check(MetalIOSurface.width(ref) == width && MetalIOSurface.height(ref) == height) {
                        "slot ${surface.token} is ${MetalIOSurface.width(ref)}x${MetalIOSurface.height(ref)}, wanted ${width}x$height"
                    }
                    received += Slot(ref, IOSurfaceHostBuffer(renderer.device, ref, readOnly = false))
                }
            } catch (t: Throwable) {
                lprintln("error,electron-rtt", "$name: receiving the surface ring failed: ${t.message}")
                received.forEach { it.host.close(); MetalIOSurface.release(it.ref) }
                return@Thread
            }
            val old = slots
            synchronized(this) {
                generation++
                slots = received
            }
            retire(old)
            lprintln("info,electron-rtt", "$name: ${received.size} surface(s) ${width}x$height, bytesPerRow ${received[0].host.bytesPerRow}")
        }, "electron-rtt-receive-$name").apply { isDaemon = true }.start()
    }

    private fun retire(old: List<Slot>) {
        if (old.isEmpty()) return
        // Not from a recording context, so wait out a few frames rather than a specific fence.
        Thread({
            Thread.sleep(500)
            old.forEach { it.host.close(); MetalIOSurface.release(it.ref) }
        }, "electron-rtt-retire-$name").apply { isDaemon = true }.start()
    }

    private fun released(slot: Int, gen: Int, sample: Sample) {
        if (gen != generation) return
        slots.getOrNull(slot)?.free = true
        stats.add(sample)
    }

    override fun render(command: CommandBuffer, index: Int) {
        rtt.render(command, index)
        val ring = slots
        val out = reply ?: return
        val slot = ring.indexOfFirst { it.free }
        if (slot < 0) {
            stats.skipped++
            return
        }
        ring[slot].free = false
        rtt.recordColorTransferSource(command) { image -> ring[slot].host.copyFromImage(command.vkCommandBuffer, image, 4) }
        val s = seq++
        val gen = generation
        renderer.afterCurrentGraphicsFrameComplete {
            out(Message("electron-rtt-$viewId").payload(FrameReady("frame", slot, s, gen, epochMs())))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        byBox.values.remove(this)
        renderer.renderList.others.remove(name)
        views.remove(viewId)
        reply = null
        retire(slots)
        slots = emptyList()
        inbox.close()
    }

    @Serializable
    private data class FrameReady(val type: String, val slot: Int, val seq: Long, val generation: Int, val readyAt: Double)

    @Serializable
    private data class Subscribe(val viewId: String)

    @Serializable
    private data class Release(
        val viewId: String, val slot: Int, val seq: Long, val generation: Int,
        val readyAt: Double, val mainReceivedAt: Double, val receivedAt: Double, val drawnAt: Double,
    )

    /** One frame's timeline, in ms after its fence completed. */
    data class Sample(val toMain: Double, val toRenderer: Double, val toDrawn: Double)

    class Stats {
        private val samples = ArrayDeque<Sample>()
        var frames = 0L; private set
        var skipped = 0L; internal set

        @Synchronized fun add(sample: Sample) {
            frames++
            samples.addLast(sample)
            while (samples.size > 600) samples.removeFirst()
        }

        /** Percentiles over the last 600 frames: page→main IPC, main→renderer `sendSharedTexture`, draw. */
        @Synchronized
        override fun toString(): String {
            if (samples.isEmpty()) return "no frames yet (skipped $skipped)"
            fun line(label: String, f: (Sample) -> Double): String {
                val v = samples.map(f).sorted()
                fun p(q: Double) = "%.2f".format(v[((v.size - 1) * q).toInt()])
                return "$label p50 ${p(0.5)} p90 ${p(0.9)} p99 ${p(0.99)} max ${p(1.0)} ms"
            }
            return listOf(
                "frames $frames, skipped (ring full) $skipped, window ${samples.size}",
                line("fence→main     ") { it.toMain },
                line("fence→renderer ") { it.toRenderer },
                line("fence→drawn    ") { it.toDrawn },
            ).joinToString("\n")
        }
    }

    companion object {
        private val counter = AtomicInteger()
        private val views = ConcurrentHashMap<String, ElectronRTTView>()
        private var installed = false

        private val byBox = ConcurrentHashMap<String, ElectronRTTView>()

        private fun epochMs(): Double = Instant.now().let { it.epochSecond * 1000.0 + it.nano / 1_000_000.0 }

        @Synchronized private fun installHandlers() {
            if (installed) return
            installed = true
            handlers["electron-rtt-subscribe"] = mutableSetOf({ message, reply ->
                val request = Json.decodeFromJsonElement<Subscribe>(message.payload!!)
                views[request.viewId]?.subscribe(reply)
                Handled.yes
            })
            handlers["electron-rtt-release"] = mutableSetOf({ message, _ ->
                val r = Json.decodeFromJsonElement<Release>(message.payload!!)
                views[r.viewId]?.released(r.slot, r.generation,
                    Sample(r.mainReceivedAt - r.readyAt, r.receivedAt - r.readyAt, r.drawnAt - r.readyAt))
                Handled.yes
            })
        }
    }
}

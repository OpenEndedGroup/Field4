package afield.media.webrtc

import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.globalServerPort
import afield.document.context.fromContext
import afield.graphics.texture.HasImageView
import afield.graphics.texture.ImageView
import afield.graphics.texture.PreparesSampledImages
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.media.browser.FieldUiBrowserTexture
import afield.media.stream.ElectronRTTView
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebRTC video for Field 4 graphics: publish a render target to a classroom, and sample anyone's
 * published stream as a texture.
 *
 * [webrtcSink] publishes what you render into its `rtt`; [webrtcSource] is a texture showing one
 * publisher; [webrtcPublishers] is the live list of who is publishing. They join aaaframe's
 * ShaderTrinket network (set [WebRTCNetwork.default], or `AFIELD_WEBRTC_SERVER`), where browser
 * ShaderTrinkets see a Field sink as one more publisher, or Field's own relay for a LAN of Field
 * instances. Chromium does the WebRTC; Field moves the frames on the GPU and relays signalling.
 * How and why: `dev_docs/WEBRTC_SOURCES_SINKS.md`.
 */

private fun boxKey(name: String): String? = RunScope.executingBox()?.let { "${it.id}:$name" }

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

private fun jsString(value: String): String = JsonPrimitive(value).toString()

/**
 * A render target published as a WebRTC stream, named `login/name` on its network.
 *
 * Render into [rtt]; `display("main", sink.html())` shows the frames in the output area and
 * publishes them. Every viewer that watches the name gets its own peer connection, fed by
 * `canvas.captureStream` on that output-area canvas, so the frames go GPU → canvas → encoder
 * without readback. Needs the Field Electron app (macOS, or Linux under native Wayland); a
 * plain browser shows "needs the Field Electron app" and publishes nothing.
 *
 * ```
 * val sink = webrtcSink("orbit")
 * val module = moduleHere { sink.rtt.apply() }
 * module += "quad" to quad
 * sink.rtt += "content" to module
 * display("main", sink.html())
 * ```
 *
 * The sink is the box's [ElectronRTTView], so re-running the box replaces it and deleting the box
 * stops publishing.
 */
class WebRTCSink(
    /** The output-area view the stream is captured from. */
    val view: ElectronRTTView,
    /** The publish suffix: the stream is `login/name`, or just `login` when empty. */
    val name: String,
    val network: WebRTCNetwork = WebRTCNetwork.default,
    /** The frame rate asked of `canvas.captureStream`; Chromium encodes at most this many a second. */
    val framesPerSecond: Int = 30,
) {
    /** Render into this. */
    val rtt get() = view.rtt

    /** The name viewers watch, once the network has confirmed it (`login/name`); null until then. */
    @Volatile var publishedAs: String? = null; internal set

    /** The page's last status line: "publishing @…", "publishing @… to 2 viewer(s)", or an error. */
    @Volatile var status: String = "not shown yet"; internal set

    /** How many peers are receiving the stream now. */
    @Volatile var viewers: Int = 0; internal set

    init {
        installHandlers()
        sinks[view.viewId] = this
    }

    /**
     * The output-area widget: [ElectronRTTView.html], plus the script that publishes its canvas
     * and a status line naming the stream.
     */
    fun html(): String = view.html() + """
        <div data-field-webrtc-sink="${view.viewId}" style="font:11px monospace;opacity:.75;padding:2px 0">WebRTC: starting</div>
        <script type="module">
          import { send } from "/afield.mjs";
          import { publish } from "/webrtc-signal.js";
          const viewId = ${jsString(view.viewId)};
          const label = document.querySelector(`[data-field-webrtc-sink="${'$'}{viewId}"]`);
          const canvas = document.querySelector(`canvas[data-field-rtt-view="${'$'}{viewId}"]`);
          const report = (status, name, viewers) => {
            label.textContent = "WebRTC: " + status;
            send("webrtc-sink-status", { viewId, status, name: name || null, viewers: viewers || 0 });
          };
          if (!(window.fieldElectron && window.fieldElectron.rttView)) {
            report("needs the Field Electron app; not publishing");
          } else if (!canvas || !canvas.captureStream) {
            report("this canvas cannot be captured");
          } else {
            const stream = canvas.captureStream($framesPerSecond);
            let publisher = null;
            publish({ network: ${jsString(network.id)}, stream, suffix: ${jsString(name)},
                      onStatus: (s, n) => report(s, n, publisher ? publisher.viewers : 0) })
              .then((p) => { publisher = p; })
              .catch((e) => report("error: " + e.message));
            const observer = new MutationObserver(() => {
              if (!canvas.isConnected) {
                observer.disconnect();
                if (publisher) publisher.stop();
                stream.getTracks().forEach((t) => t.stop());
              }
            });
            observer.observe(document.body, { childList: true, subtree: true });
          }
        </script>
    """.trimIndent()

    companion object {
        private val sinks = ConcurrentHashMap<String, WebRTCSink>()
        private var installed = false

        /**
         * The sender's wall clock in ms modulo 2^24, exact in a `float`: what a time-code band
         * encodes so a receiver on the same machine can measure latency (`webrtc-source.html`,
         * `measure=1`). Draw bit `23 - i` of it as bar `i + 1` of 26 across the middle of the frame,
         * with bar 0 white and bar 25 black.
         */
        fun timecode(): Float = (System.currentTimeMillis() % (1L shl 24)).toFloat()

        @Synchronized private fun installHandlers() {
            if (installed) return
            installed = true
            handlers["webrtc-sink-status"] = mutableSetOf({ message, _ ->
                val p = message.payload!!.jsonObject
                sinks[p["viewId"]?.jsonPrimitive?.contentOrNull]?.let { sink ->
                    sink.status = p["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    p["name"]?.jsonPrimitive?.contentOrNull?.let { sink.publishedAs = it }
                    sink.viewers = p["viewers"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                }
                Handled.yes
            })
        }
    }
}

/**
 * Publishes a new [width]×[height] render target as `login/name`; render into the sink's `rtt` and
 * display its `html()`. See [WebRTCSink].
 */
fun webrtcSink(
    name: String,
    width: Int = 640,
    height: Int = 360,
    network: WebRTCNetwork = WebRTCNetwork.default,
    renderer: Renderer = fromContext(),
): WebRTCSink = WebRTCSink(ElectronRTTView(width, height, renderer = renderer), name, network)

/** Publishes an existing [ElectronRTTView] as `login/name`. */
fun webrtcSink(view: ElectronRTTView, name: String, network: WebRTCNetwork = WebRTCNetwork.default): WebRTCSink =
    WebRTCSink(view, name, network)

/**
 * One publisher's WebRTC stream as a Field texture: `textures("remote" to source)`.
 *
 * A hidden Electron page watches [target] and plays the video full-frame (letterboxed to keep its
 * aspect); its paints arrive as a texture, GPU to GPU on macOS and through the CPU-bitmap fallback
 * on Linux. Until the publisher is online the texture shows a status line ("waiting for @alice to
 * publish"), and it reconnects by itself when the publisher restarts. [watch] switches to another
 * publisher without reloading.
 *
 * Re-running the box keeps the same page and re-targets it, so live-editing does not restart the
 * stream; deleting the box closes it. Needs Electron installed (`./gradlew electronInstall`); the
 * Field window itself need not be the Electron app.
 */
class WebRTCSource internal constructor(
    private val renderer: Renderer,
    target: String,
    val width: Int,
    val height: Int,
    val network: WebRTCNetwork,
    private val fit: String,
    measure: Boolean,
) : HasImageView, PreparesSampledImages, AutoCloseable {
    /** The publisher shown, as normalised by the network (`alice/left`). */
    @Volatile var target: String = normalizePublishName(target); private set

    /** The page's last status: "requesting @…", "waiting for @… to publish", "receiving @…", or an error. */
    @Volatile var status: String = "starting"; private set

    /** The hidden page's browser, for anything else you want to ask it (it is a full `PuppetBrowser`). */
    val browser = FieldUiBrowserTexture(
        renderer = renderer,
        url = "https://localhost:$globalServerPort/webrtc-source.html?network=${network.id.urlEncoded()}" +
            "&target=${this.target.urlEncoded()}&fit=${fit.urlEncoded()}${if (measure) "&measure=1" else ""}",
        width = width,
        height = height,
        // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
        cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired,
    )

    /** Latency samples decoded from a sink's time-code band (only with `measure = true`). */
    val latency = LatencyStats()

    private val uploadKey = "webrtc-source-${counter.incrementAndGet()}"

    init {
        browser.addEventListener { event ->
            if (event.name != "page-event") return@addEventListener
            val name = event.payload["name"]?.jsonPrimitive?.contentOrNull
            val value = event.payload["value"]
            when (name) {
                "webrtc-status" -> (value as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull?.let { status = it }
                "webrtc-latency" -> (value as? JsonArray)?.forEach { sample ->
                    val o = sample.jsonObject
                    latency.add(
                        o["latency"]?.jsonPrimitive?.doubleOrNull ?: return@forEach,
                        o["received"]?.jsonPrimitive?.doubleOrNull,
                    )
                }
            }
        }
        renderer.renderList.preTargets[uploadKey] = Target { command, _ -> browser.prepareSampledImagesFor(command) }
    }

    override val imageView: ImageView get() = browser.imageView
    override fun prepareSampledImagesFor(command: CommandBuffer) = browser.prepareSampledImagesFor(command)

    /** True once the page has painted a frame after loading (the stream itself may still be connecting). */
    val pageReady: Boolean get() = browser.pageReady

    /** Show [name]'s stream instead (`alice`, `@alice/left`). */
    fun watch(name: String) {
        target = normalizePublishName(name)
        status = "requesting @$target"
        browser.evaluate("window.fieldWebrtc && window.fieldWebrtc.watch(${jsString(target)})")
    }

    override fun close() {
        renderer.renderList.preTargets.remove(uploadKey)
        sources.values.remove(this)
        browser.close()
    }

    internal fun matches(width: Int, height: Int, network: WebRTCNetwork, fit: String) =
        width == this.width && height == this.height && network === this.network && fit == this.fit

    /** Percentiles of decoded time codes: sender's render to this page presenting the frame. */
    class LatencyStats {
        private val samples = ArrayDeque<Pair<Double, Double?>>()
        var count = 0L; private set

        @Synchronized internal fun add(latency: Double, received: Double?) {
            count++
            samples.addLast(latency to received)
            while (samples.size > 600) samples.removeFirst()
        }

        @Synchronized fun clear() { samples.clear(); count = 0 }

        /** p50/p90/p99/max over the last 600 frames, of render → presented and render → packet received. */
        @Synchronized
        override fun toString(): String {
            if (samples.isEmpty()) return "no time codes decoded yet"
            fun line(label: String, values: List<Double>): String {
                if (values.isEmpty()) return "$label n/a"
                val v = values.sorted()
                fun p(q: Double) = "%.1f".format(v[((v.size - 1) * q).toInt()])
                return "$label p50 ${p(0.5)} p90 ${p(0.9)} p99 ${p(0.99)} max ${p(1.0)} ms"
            }
            return listOf(
                "frames $count, window ${samples.size}",
                line("render → presented", samples.map { it.first }),
                line("render → received ", samples.mapNotNull { it.second }),
            ).joinToString("\n")
        }
    }

    internal companion object {
        val counter = AtomicInteger()
        val sources = ConcurrentHashMap<String, WebRTCSource>()
    }
}

/**
 * [target]'s published stream as a [width]×[height] texture: `textures("remote" to webrtcSource("alice"))`.
 * [fit] is the video's CSS `object-fit` (`contain`, `cover` or `fill`). [key] tells several sources
 * in one box apart, so a re-run reuses each one's page; it defaults to the target. With [measure],
 * a sink's time-code band is decoded into [WebRTCSource.latency]. See [WebRTCSource].
 */
fun webrtcSource(
    target: String,
    width: Int = 640,
    height: Int = 360,
    network: WebRTCNetwork = WebRTCNetwork.default,
    fit: String = "contain",
    key: String = target,
    measure: Boolean = false,
    renderer: Renderer = fromContext(),
): WebRTCSource {
    val boxKey = boxKey(key)
    if (boxKey != null) {
        val existing = WebRTCSource.sources[boxKey]
        if (existing != null && existing.matches(width, height, network, fit) && !measure) {
            if (existing.target != normalizePublishName(target)) existing.watch(target)
            return existing
        }
        existing?.close()
    }
    val source = WebRTCSource(renderer, target, width, height, network, fit, measure)
    if (boxKey != null) {
        WebRTCSource.sources[boxKey] = source
        // Released when the box is deleted or its document closes; a re-run finds it above.
        RunScope.current()?.own(source, ReleasePolicy.KEYED)
    }
    return source
}

/**
 * Who is publishing on a network, kept live: [names] now, [onChange] as it changes, and [html] as a
 * panel with a button per publisher that calls [onPick].
 *
 * ```
 * val source = webrtcSource("alice")
 * val list = webrtcPublishers()
 * list.onPick { source.watch(it) }
 * display("publishers", list.html())
 * ```
 */
class WebRTCPublishers internal constructor(val network: WebRTCNetwork) {
    private val lobby = PublisherLobby.of(network)
    private val picks = CopyOnWriteArrayList<(String) -> Unit>()
    private val id = "webrtc-publishers-${counter.incrementAndGet()}"
    private val removers = CopyOnWriteArrayList<() -> Unit>()

    /** The publisher names now, sorted. */
    val names: List<String> get() = lobby.names

    /** Why the list is empty, if it is because the network refused or dropped the connection. */
    val error: String? get() = lobby.error

    /** Calls [listener] with the names now and on every change; returns the remover. */
    fun onChange(listener: (List<String>) -> Unit): () -> Unit = lobby.onChange(listener).also { removers += it }

    /** Calls [listener] with the name a button in [html] was clicked for. */
    fun onPick(listener: (String) -> Unit) { picks += listener }

    /** A panel listing the publishers, live; [highlight] marks the one you are showing. */
    fun html(highlight: () -> String? = { null }): String {
        lists[id] = this
        highlights[id] = highlight
        return """
            <div data-field-webrtc-publishers="$id" style="font:12px ui-monospace,monospace;display:flex;flex-wrap:wrap;gap:4px;align-items:center">
              <span style="opacity:.6">publishers:</span><span class="list" style="display:contents"></span>
            </div>
            <script type="module">
              import { send, sendWithReply } from "/afield.mjs";
              const id = ${jsString(id)};
              const root = document.querySelector(`[data-field-webrtc-publishers="${'$'}{id}"]`);
              const list = root.querySelector(".list");
              const draw = (p) => {
                list.replaceChildren();
                if (p.error) { const e = document.createElement("span"); e.textContent = p.error; e.style.color = "#e88"; list.append(e); }
                else if (!p.names.length) { const e = document.createElement("span"); e.textContent = "nobody yet"; e.style.opacity = ".5"; list.append(e); }
                for (const name of p.names) {
                  const b = document.createElement("button");
                  b.textContent = "@" + name;
                  b.style.cssText = "font:inherit;padding:1px 6px;border-radius:3px;border:1px solid #586;cursor:pointer;" +
                    (name === p.highlight ? "background:#2a6;color:#fff" : "background:transparent;color:inherit");
                  b.onclick = () => send("webrtc-publishers-pick", { id, name });
                  list.append(b);
                }
              };
              sendWithReply("webrtc-publishers-subscribe", { id }, id, (m, p) => draw(p));
            </script>
        """.trimIndent()
    }

    internal fun push(reply: (Message) -> Unit) {
        val remove = lobby.onChange { names ->
            reply(Message(id).payload(buildJsonObject {
                put("names", JsonArray(names.map(::JsonPrimitive)))
                lobby.error?.let { put("error", it) }
                highlights[id]?.invoke()?.let { put("highlight", it) }
            }))
        }
        removers += remove
    }

    internal fun picked(name: String) {
        picks.forEach { runCatching { it(name) } }
    }

    /** Stops listening; the panel stops updating. */
    fun close() {
        removers.forEach { it() }
        removers.clear()
        lists.remove(id)
        highlights.remove(id)
    }

    internal companion object {
        val counter = AtomicInteger()
        val lists = ConcurrentHashMap<String, WebRTCPublishers>()
        val highlights = ConcurrentHashMap<String, () -> String?>()
        private var installed = false

        @Synchronized fun installHandlers() {
            if (installed) return
            installed = true
            handlers["webrtc-publishers-subscribe"] = mutableSetOf({ message, reply ->
                lists[message.payload!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull]?.push(reply)
                Handled.yes
            })
            handlers["webrtc-publishers-pick"] = mutableSetOf({ message, _ ->
                val p = message.payload!!.jsonObject
                val name = p["name"]?.jsonPrimitive?.contentOrNull
                if (name != null) lists[p["id"]?.jsonPrimitive?.contentOrNull]?.picked(name)
                Handled.yes
            })
        }
    }
}

/** The live publisher list on [network]; see [WebRTCPublishers]. Closed with the box's run. */
fun webrtcPublishers(network: WebRTCNetwork = WebRTCNetwork.default): WebRTCPublishers {
    WebRTCPublishers.installHandlers()
    val publishers = WebRTCPublishers(network)
    RunScope.current()?.own(ReleasePolicy.ACCUMULATING) { publishers.close() }
    return publishers
}

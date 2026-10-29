package afield.app.canvas

import afield.app.client.allActions
import afield.app.client.flyCanvasCamera
import afield.app.client.mainZoom
import afield.app.client.updateActions
import afield.app.client.MAX_CANVAS_ZOOM
import afield.app.client.MIN_CANVAS_ZOOM
import afield.app.notifications.notify
import afield.app.protocol.Handled
import afield.app.protocol.handlers
import afield.app.protocol.put
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.asList
import kotlin.js.Promise
import kotlin.math.pow

/**
 * The canvas flight in the browser: the camera flown through bookmarks ([CanvasFlightTimeline]),
 * with every three.js view on the canvas turning about its own target while it flies.
 *
 * Two ways to fly. [play] flies live on the animation-frame clock: the palette's **Fly Through
 * Bookmarks**, and box code's `canvasFlight(...)` without `record`. Filming instead steps the
 * flight from the server, one frame at a time ([beginRecording], [seek], [endRecording], exposed as
 * `window.fieldCanvasFlight`): the server sets the clock, waits for the frame to paint, captures it
 * and only then asks for the next, so a slow machine films the same movie as a fast one
 * (`afield.tools.docs.recordCanvasFlight`).
 *
 * A leg whose straight path crosses canvas with nothing on it pulls back on the way (unless the spec
 * turns `pullBack` off), so the film never shows a stretch of empty canvas; one over boxes the whole
 * way pans straight. A stop's view is the
 * bookmark's pan and zoom; a bookmark saved in a window of another size is fitted
 * so the same part of the canvas shows ([flightView]). The flight moves the main pane only; it does not
 * restore selections, editors or output panels as restoring the bookmark does.
 *
 * A three.js view takes part through `FieldViewer.film` (`threejs_viewer.html`): begun when the
 * flight starts, or on the first frame its panel is open and loaded, set to the time since then every
 * frame, and ended, which puts its camera back, when the flight finishes or stops.
 */
object CanvasFlightClient {
    private val json = Json { ignoreUnknownKeys = true }

    private class Prepared(
        val spec: CanvasFlightSpec,
        val timeline: CanvasFlightTimeline,
        val bookmarks: List<Bookmark>,
        val views: List<CanvasFlightView>,
        /** The three.js views taking part so far, each with the flight time it joined at. */
        val viewers: MutableList<Pair<dynamic, Double>>,
        /** Legs (by the stop they fly to) whose straight path crosses canvas with nothing on it. */
        val emptyLegs: Set<Int>
    )

    private var live: Prepared? = null
    private var liveGeneration = 0
    private var recording: Prepared? = null
    private var lastRecording: dynamic = null

    /** Resolves the stops against this document's bookmarks and the window as it is now. */
    private fun prepare(spec: CanvasFlightSpec): Prepared {
        spec.problem()?.let { throw IllegalArgumentException(it) }
        val data = loadBookmarks()
        val bookmarks = spec.stops.map { stop ->
            val b = data.find(stop.bookmark) ?: throw IllegalArgumentException(data.describeMissing(stop.bookmark))
            if (b.scrollPosition == null) throw IllegalArgumentException("Bookmark \"${b.name}\" recorded no view")
            b
        }
        val r = CanvasCamera.main.rect
        val views = bookmarks.map { b ->
            val v = b.scrollPosition!!.flightView(r.x, r.y, r.w, r.h, mainZoom)
            v.copy(zoom = v.zoom.coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM))
        }
        val timeline = CanvasFlightTimeline(spec)
        return Prepared(spec, timeline, bookmarks, views, mutableListOf(), emptyLegs(views, r.w, r.h))
    }

    /** Boxes and open panels covering less of the window than this leave it empty. */
    private const val EMPTY_WINDOW_FRACTION = 0.1

    /**
     * The legs that, flown straight at log-space zoom, show a window that boxes and open output panels
     * barely cover ([EMPTY_WINDOW_FRACTION]) somewhere along the way, sampled at each tenth of the leg.
     */
    private fun emptyLegs(views: List<CanvasFlightView>, paneW: Double, paneH: Double): Set<Int> {
        val pane = CanvasCamera.main
        val things = document.getElementsByClassName("box").asList().flatMap { raw ->
            val box = raw as? org.w3c.dom.HTMLElement ?: return@flatMap emptyList()
            if (box.asDynamic().uid == "root") return@flatMap emptyList()
            (listOf(box) + box.querySelectorAll(".outputArea .disclosure").asList().mapNotNull { it as? org.w3c.dom.HTMLElement })
                .map { it.getBoundingClientRect() }.filter { it.width > 0.0 && it.height > 0.0 }.map { pane.worldRect(it) }
        }
        return (1 until views.size).filter { to ->
            val a = views[to - 1]
            val b = views[to]
            (1..9).any { k ->
                val e = k / 10.0
                val x = a.x + (b.x - a.x) * e
                val y = a.y + (b.y - a.y) * e
                val z = a.zoom * (b.zoom / a.zoom).pow(e)
                val hw = paneW / (2 * z)
                val hh = paneH / (2 * z)
                // A sliver of a panel at the edge of the window is not something to look at.
                val covered = things.sumOf { t ->
                    val w = minOf(t.x + t.width, x + hw) - maxOf(t.x, x - hw)
                    val h = minOf(t.y + t.height, y + hh) - maxOf(t.y, y - hh)
                    if (w > 0.0 && h > 0.0) w * h else 0.0
                }
                covered < EMPTY_WINDOW_FRACTION * (4 * hw * hh)
            }
        }.toSet()
    }

    /** The `FieldViewer.film` of every three.js view showing on the canvas (its panel open). */
    private fun threeViewers(): List<dynamic> =
        document.querySelectorAll("iframe").asList().mapNotNull { node ->
            val frame = node as? HTMLIFrameElement ?: return@mapNotNull null
            val r = frame.getBoundingClientRect()
            if (r.width <= 0.0 || r.height <= 0.0) return@mapNotNull null
            val film: dynamic = try { frame.contentWindow.asDynamic()?.FieldViewer?.film } catch (_: Throwable) { null }
            if (film == null || film == undefined) null else film
        }

    /** Begins every three.js view that is showing and not yet taking part, from [seconds]. */
    private fun join(p: Prepared, seconds: Double) {
        for (film in threeViewers()) {
            if (p.viewers.any { it.first === film }) continue
            if (runCatching { film.begin(obj { it.degreesPerSecond = p.spec.orbitDegreesPerSecond }) }.isSuccess) p.viewers.add(Pair(film, seconds))
        }
    }

    /** Puts the camera and every view where the flight is at [seconds]. */
    private fun apply(p: Prepared, seconds: Double, settled: Boolean): CanvasFlightView {
        val r = CanvasCamera.main.rect
        val view = p.timeline.at(seconds, p.views, r.w, r.h) { it in p.emptyLegs }
        val (tx, ty) = view.translation(r.x, r.y, r.w, r.h)
        flyCanvasCamera(tx, ty, view.zoom, settled)
        // A panel the flight has just opened onto (or that has just loaded) joins here.
        join(p, seconds)
        p.viewers.forEach { (film, since) -> runCatching { film.at(seconds - since) } }
        return view
    }

    private fun end(p: Prepared) {
        p.viewers.forEach { (film, _) -> runCatching { film.end() } }
        // Tell the server where the flight left the camera.
        flyCanvasCamera(CanvasCamera.main.tx, CanvasCamera.main.ty, mainZoom, settled = true)
    }

    /** Flies [spec] live, on the animation-frame clock. Any other camera move stops it. */
    fun play(spec: CanvasFlightSpec) {
        stopLive(null)
        val p = try { prepare(spec) } catch (e: Throwable) {
            notify("Canvas flight: ${e.message}", "error")
            return
        }
        if (recording != null) {
            notify("Canvas flight: this window is filming a flight", "error")
            return
        }
        live = p
        val generation = ++liveGeneration
        apply(p, 0.0, settled = false)
        notify("Flying through ${p.bookmarks.joinToString(" → ") { it.name }} (${formatSeconds(p.timeline.duration)})", "bookmarks")
        val start = window.performance.now()
        fun step(now: Double) {
            if (generation != liveGeneration || live !== p) return
            val t = (now - start) / 1000.0
            if (t >= p.timeline.duration) {
                apply(p, p.timeline.duration, settled = true)
                live = null
                end(p)
                return
            }
            apply(p, t, settled = false)
            window.requestAnimationFrame { step(it) }
        }
        window.requestAnimationFrame { step(it) }
    }

    /** Stops a live flight where it is, saying [why] (or nothing, when null). */
    fun stopLive(why: String?) {
        val p = live ?: return
        live = null
        liveGeneration++
        end(p)
        if (why != null) notify("Canvas flight stopped: $why", "bookmarks")
    }

    /** For the server's recorder: prepares [specJson] and starts filming. */
    fun beginRecording(specJson: String): dynamic {
        val reply: dynamic = js("({})")
        try {
            stopLive(null)
            recording?.let { end(it) }
            val p = prepare(json.decodeFromString(CanvasFlightSpec.serializer(), specJson))
            recording = p
            document.body?.classList?.add("canvas-flight-recording")
            apply(p, 0.0, settled = false)
            val r = CanvasCamera.main.rect
            reply.ok = true
            reply.duration = p.timeline.duration
            reply.viewers = p.viewers.size
            // The legs, by the stop they fly to, that pull back because their straight path crosses empty canvas.
            reply.pullBackLegs = p.emptyLegs.sorted().toTypedArray()
            reply.pane = obj { it.x = r.x; it.y = r.y; it.w = r.w; it.h = r.h }
            reply.stops = p.bookmarks.mapIndexed { i, b ->
                val (tx, ty) = p.views[i].translation(r.x, r.y, r.w, r.h)
                val stop: dynamic = js("({})")
                stop.bookmark = p.spec.stops[i].bookmark
                stop.name = b.name
                stop.slot = b.slot
                stop.arrival = p.timeline.arrivals[i]
                stop.target = camera(tx, ty, p.views[i].zoom)
                stop.saved = camera(b.scrollPosition!!.x, b.scrollPosition.y, b.scrollPosition.zoom ?: mainZoom)
                stop
            }.toTypedArray()
            lastRecording = obj { it.stops = reply.stops; it.frames = 0; it.cameras = js("[]") }
        } catch (e: Throwable) {
            reply.ok = false
            reply.error = e.message ?: e.toString()
        }
        return reply
    }

    /**
     * For the server's recorder: the flight at [seconds], resolved once the browser has painted it
     * (two animation frames: the first runs before that frame's paint, the second after it), with the
     * camera it painted.
     */
    fun seek(seconds: Double): Promise<dynamic> {
        val p = recording ?: return Promise.reject(IllegalStateException("No canvas flight is filming"))
        apply(p, seconds, settled = false)
        val shown = camera(CanvasCamera.main.tx, CanvasCamera.main.ty, mainZoom)
        shown.t = seconds
        // Each three.js view's camera in this frame, so a check can see the turn.
        shown.views = p.viewers.map { (film, _) -> try { film.status() } catch (_: Throwable) { null } }.toTypedArray()
        val report = lastRecording
        if (report != null) {
            report.frames = (report.frames as Int) + 1
            report.cameras.push(shown)
        }
        return Promise { resolve, _ ->
            window.requestAnimationFrame { window.requestAnimationFrame { resolve(shown) } }
        }
    }

    /** For the server's recorder: ends filming, leaving the camera on the last stop. */
    fun endRecording(): dynamic {
        val p = recording
        recording = null
        document.body?.classList?.remove("canvas-flight-recording")
        if (p != null) end(p)
        return obj { it.ok = p != null; it.camera = camera(CanvasCamera.main.tx, CanvasCamera.main.ty, mainZoom) }
    }

    private fun camera(x: Double, y: Double, zoom: Double): dynamic =
        obj { it.x = x; it.y = y; it.zoom = zoom }

    /** A plain JS object, filled by [init]: what the server's recorder reads back as JSON. */
    private fun obj(init: (dynamic) -> Unit): dynamic {
        val o: dynamic = js("({})")
        init(o)
        return o
    }

    private fun formatSeconds(s: Double) = if (s < 60) "${(s * 10).toInt() / 10.0} s" else "${(s / 60).toInt()} min ${(s % 60).toInt()} s"

    /** The palette's flight: every saved bookmark, in slot order. */
    private fun flyThroughBookmarks() {
        val saved = loadBookmarks().inSlotOrder().filter { it.scrollPosition != null }
        if (saved.isEmpty()) {
            notify("Save a bookmark or two first (Cmd+Shift+F1-F9)", "bookmarks")
            return
        }
        play(CanvasFlightSpec(stops = saved.map { CanvasFlightStop(it.id) }))
    }

    /** Palette actions, the server's `canvas-flight` message, and `window.fieldCanvasFlight`. */
    fun install() {
        val builtin = allActions.builtin ?: js("[]")
        val fly: dynamic = js("({})")
        fly.id = "fly-through-bookmarks"
        fly.label = "Fly Through Bookmarks"
        fly.detail = "Fly the canvas through this document's bookmarks in slot order, turning the three.js views"
        fly.run = { flyThroughBookmarks() }
        builtin.push(fly)
        val stop: dynamic = js("({})")
        stop.id = "stop-canvas-flight"
        stop.label = "Stop Canvas Flight"
        stop.detail = "Stop a canvas flight where it is"
        stop.run = { stopLive("stopped from the palette") }
        builtin.push(stop)
        allActions.builtin = builtin
        updateActions()

        handlers.put("canvas-flight") { message, _ ->
            play(json.decodeFromJsonElement<CanvasFlightSpec>(message.payload!!))
            Handled.yes
        }
        handlers.put("canvas-flight-stop") { _, _ ->
            stopLive(null)
            Handled.yes
        }
        window.addEventListener("keydown", { event ->
            if (live != null && event.asDynamic().key == "Escape") stopLive("Escape")
        }, true)

        val api: dynamic = js("({})")
        api.play = { specJson: String -> play(json.decodeFromString(CanvasFlightSpec.serializer(), specJson)) }
        api.stop = { stopLive(null) }
        api.beginRecording = { specJson: String -> beginRecording(specJson) }
        api.seek = { seconds: Double -> seek(seconds) }
        api.endRecording = { endRecording() }
        api.flying = { live != null }
        api.camera = { camera(CanvasCamera.main.tx, CanvasCamera.main.ty, mainZoom) }
        api.lastRecording = { lastRecording }
        api.viewers = { threeViewers().size }
        window.asDynamic().fieldCanvasFlight = api
    }
}

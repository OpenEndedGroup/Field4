/**
 * # Named drag handles on `lines(...)` drawings
 *
 * A drawing with `onDrag` hands its box every press, move and release. Without handles the box then has to
 * decide what the press meant, usually "the nearest thing to the pointer", and a pointer is whole pixels: two
 * lines a few pixels apart cannot be told apart, and a script that wants "this one, to here" has to aim a
 * pointer at the screen. A [LinesHandle] names the thing instead.
 *
 * - A handle is bound to an x value (a vertical line dragged sideways), a y value (a horizontal line dragged up
 *   and down) or both (a point). The box passes its handles with every display, so they follow the drawing.
 * - A press grabs the handle under the pointer: of those within their [LinesHandle.radius] (screen pixels),
 *   the highest [LinesHandle.priority], and only among equals the nearest. The drag's events then carry the
 *   handle's name, and its coordinates are where the handle is being put, at full precision.
 * - Code drags a handle by name: [Box.dragHandle] on the JVM, and `FieldLines` in the page (see
 *   `outputareas.kt`), both through the same `onDrag` a hand's drag reaches, and both exact.
 *
 * Events for one drawing are delivered one at a time and in order, whichever thread sent them; a move that
 * arrives while an earlier one is still queued replaces it (latest wins), so a slow callback never falls
 * further and further behind the pointer.
 */
package afield.ui.visualization

import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.LinesDrag
import afield.document.graph.Box
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A named drag handle on a `lines(...)` drawing, in the lines' own coordinates.
 *
 * Bound to [x] only, it is a vertical line at that x and drags along x; to [y] only, a horizontal line dragged
 * along y; to both, a point. [from] and [to] limit a line handle to part of its line (y values for an x
 * handle, x values for a y handle); by default it runs the whole drawing. A press within [radius] screen
 * pixels of the handle can take it, and when several can, the highest [priority] does, the nearest only
 * breaking a tie.
 *
 * ```kotlin
 * lines(drawing, channel = "figure", w = 600.0, h = 300.0, fitToSize = true,
 *     handles = listOf(LinesHandle.x("split", splitX, priority = 1), LinesHandle.x("guide", guideX)),
 *     onDrag = { e -> if (e.handle == "split") splitX = e.value })
 * dragHandle("split", to = 22.3, channel = "figure")   // the same callback, the same handle, exactly 22.3
 * ```
 */
data class LinesHandle(
    val name: String,
    val x: Double? = null,
    val y: Double? = null,
    val priority: Int = 0,
    val from: Double? = null,
    val to: Double? = null,
    val radius: Double = 6.0,
) {
    init {
        require(name.isNotBlank()) { "A handle needs a name" }
        require(x != null || y != null) { "Handle '$name' is bound to neither x nor y" }
        require(listOfNotNull(x, y, from, to).all { it.isFinite() }) { "Handle '$name' has a non-finite coordinate" }
        require(radius.isFinite() && radius > 0.0) { "Handle '$name' needs a positive radius" }
    }

    /** `x`, `y` or `xy`: what the handle is bound to. */
    val kind: String get() = if (x != null && y != null) "xy" else if (x != null) "x" else "y"

    companion object {
        /** A vertical line at [x], dragged along x. */
        fun x(name: String, x: Double, priority: Int = 0, from: Double? = null, to: Double? = null, radius: Double = 6.0) =
            LinesHandle(name, x = x, priority = priority, from = from, to = to, radius = radius)

        /** A horizontal line at [y], dragged along y. */
        fun y(name: String, y: Double, priority: Int = 0, from: Double? = null, to: Double? = null, radius: Double = 6.0) =
            LinesHandle(name, y = y, priority = priority, from = from, to = to, radius = radius)

        /** A point at [x], [y], dragged anywhere. */
        fun xy(name: String, x: Double, y: Double, priority: Int = 0, radius: Double = 8.0) =
            LinesHandle(name, x = x, y = y, priority = priority, radius = radius)
    }
}

/**
 * The `data-afield-handles` attribute for [handles] on a display drawn through [fit]: each handle's place in the
 * drawn SVG's coordinates, which the page hit-tests, and its own values, which a drag by name reports.
 */
internal fun linesHandlesAttribute(handles: List<LinesHandle>, fit: FitTransform?): String? {
    if (handles.isEmpty()) return null
    val dup = handles.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(dup.isEmpty()) { "Handle names must be unique on a drawing: ${dup.joinToString()}" }
    fun sx(v: Double) = if (fit == null) v else (v + fit.offsetX) * fit.scale
    fun sy(v: Double) = if (fit == null) v else (v + fit.offsetY) * fit.scale
    return buildJsonArray {
        for (h in handles) addJsonObject {
            put("name", h.name)
            put("kind", h.kind)
            h.x?.let { put("x", sx(it)); put("vx", it) }
            h.y?.let { put("y", sy(it)); put("vy", it) }
            put("priority", h.priority)
            put("radius", h.radius)
            // A line handle's extent, along the other axis, in SVG coordinates.
            val across: (Double) -> Double = if (h.kind == "x") ::sy else ::sx
            h.from?.let { put("from", JsonPrimitive(across(it))) }
            h.to?.let { put("to", JsonPrimitive(across(it))) }
        }
    }.toString()
}

/** What a drawing with `onDrag` registered on its latest display: the callback, the fit to undo, its handles. */
internal class LinesDragTarget(
    val callback: (LinesDragEvent) -> Unit,
    val fit: FitTransform?,
    val handles: Map<String, LinesHandle>,
) {
    fun deliver(drag: LinesDrag) {
        val phase = when (drag.phase) { "start" -> LinesDragEvent.Phase.START; "end" -> LinesDragEvent.Phase.END; else -> LinesDragEvent.Phase.MOVE }
        val x = if (drag.own || fit == null) drag.x else drag.x / fit.scale - fit.offsetX
        val y = if (drag.own || fit == null) drag.y else drag.y / fit.scale - fit.offsetY
        val value = if (handles[drag.handle]?.kind == "y") y else x
        callback(LinesDragEvent(x, y, phase, drag.handle, value))
    }
}

private val linesDragTargets = afield.runtime.lifecycle.OwnedCallbackRegistry<LinesDragTarget>("linesDrag")

/** One lock per drawing, so a script's drag and a hand's never interleave inside one event. */
private val linesDragLocks = ConcurrentHashMap<String, Any>()
private fun lockFor(id: String) = linesDragLocks.computeIfAbsent(id) { Any() }

/** The id a drawing's drags are sent under: its box and channel, so a redisplay replaces the callback. */
internal fun linesDragId(box: Box, channel: String) = "lines-drag:${box.id}:$channel"

/**
 * Register [callback] for drags on [box]'s [channel] and return the id the viewer sends back. The id
 * is the box and channel, so a drawing redisplayed every frame replaces its callback instead of
 * adding one; [fit] is that display's fit, undone on the way back so the callback sees the lines'
 * own coordinates, and [handles] are that display's handles.
 */
internal fun registerLinesDrag(
    box: Box, channel: String, fit: FitTransform?, callback: (LinesDragEvent) -> Unit, handles: List<LinesHandle> = emptyList(),
): String {
    val id = linesDragId(box, channel)
    linesDragTargets.register(id, LinesDragTarget(callback, fit, handles.associateBy { it.name }))
    return id
}

/** Deliver a [LinesDrag] now, on this thread; false when nothing is registered under its id. */
internal fun deliverLinesDrag(drag: LinesDrag): Boolean {
    val target = linesDragTargets[drag.id] ?: return false
    synchronized(lockFor(drag.id)) { (linesDragTargets[drag.id] ?: target).deliver(drag) }
    return true
}

private class DragLane {
    val pending = ArrayDeque<LinesDrag>()
    var draining = false
}

private val linesDragLanes = ConcurrentHashMap<String, DragLane>()
private val linesDragExecutor = Executors.newCachedThreadPool { r -> Thread(r, "lines-drag").apply { isDaemon = true } }

/**
 * A [LinesDrag] from a viewer: queued behind the drawing's earlier events and delivered in order, off the
 * message thread. A move replaces a move still waiting in the queue.
 */
internal fun receiveLinesDrag(drag: LinesDrag, executor: (Runnable) -> Unit = linesDragExecutor::execute) {
    val lane = linesDragLanes.computeIfAbsent(drag.id) { DragLane() }
    synchronized(lane) {
        val last = lane.pending.lastOrNull()
        if (last != null && last.phase == "move" && drag.phase == "move" && last.handle == drag.handle) lane.pending[lane.pending.lastIndex] = drag
        else lane.pending.addLast(drag)
        if (lane.draining) return
        lane.draining = true
    }
    executor(Runnable {
        while (true) {
            val next = synchronized(lane) { lane.pending.removeFirstOrNull().also { if (it == null) lane.draining = false } } ?: break
            try {
                if (!deliverLinesDrag(next)) lprintln("debug,fline", "No drag callback for ${next.id}")
            } catch (e: Exception) {
                lprintln("error,fline", "Error in lines drag callback: ${e.message}")
            }
        }
    })
}

/** The handles on the latest display of this box's [channel] drawing, by name; empty when it has none. */
fun Box.linesHandles(channel: String = "lines"): Map<String, LinesHandle> =
    linesDragTargets[linesDragId(this, channel)]?.handles.orEmpty()

private fun Box.handleFor(name: String, channel: String): LinesHandle {
    val id = linesDragId(this, channel)
    val target = linesDragTargets[id] ?: error("No draggable drawing on channel '$channel' of this box: draw it with lines(..., onDrag = ...) first")
    return target.handles[name]
        ?: error("No handle '$name' on channel '$channel'; it has ${target.handles.keys.joinToString().ifEmpty { "none" }}")
}

/**
 * Drag handle [name] of this box's [channel] drawing along [path] — a press where the handle is, a move to each
 * value, a release at the last — through the drawing's own `onDrag`, exactly as a hand's drag of that handle
 * arrives, and return each move's time in the callback (ms). For a handle bound to x the values are x, for one
 * bound to y they are y. [msPerStep] paces the moves like a hand on the mouse; 0 runs them back to back.
 *
 * Each event goes to the callback of the drawing's latest display, so a drawing redrawn by its own callback
 * keeps working. It runs on the calling thread, and never inside another drag's event.
 */
fun Box.dragHandle(name: String, path: List<Double>, channel: String = "lines", msPerStep: Long = 0): List<Double> {
    require(path.isNotEmpty()) { "A drag needs at least one value" }
    val h = handleFor(name, channel)
    require(h.kind != "xy") { "Handle '$name' is a point: drag it with dragHandle(name, x, y)" }
    val id = linesDragId(this, channel)
    // The other coordinate stays where the handle is drawn: its own, or the middle of its extent.
    val across = (if (h.kind == "x") h.y else h.x) ?: if (h.from != null && h.to != null) (h.from + h.to) / 2 else 0.0
    fun send(phase: String, v: Double) {
        val (x, y) = if (h.kind == "x") v to across else across to v
        if (!deliverLinesDrag(LinesDrag(id, phase, x, y, handle = name, own = true))) error("The drawing on channel '$channel' went away")
    }
    send("start", if (h.kind == "x") h.x!! else h.y!!)
    val times = path.map { v ->
        val t0 = System.nanoTime()
        send("move", v)
        val ms = (System.nanoTime() - t0) / 1e6
        if (msPerStep > 0) Thread.sleep(maxOf(0L, msPerStep - ms.toLong()))
        ms
    }
    send("end", path.last())
    return times
}

/** Drag handle [name] of this box's [channel] drawing to [to] in one move; see the path form. */
fun Box.dragHandle(name: String, to: Double, channel: String = "lines"): Double = dragHandle(name, listOf(to), channel).single()

/** Drag point handle [name] of this box's [channel] drawing to ([x], [y]) in one move. */
fun Box.dragHandle(name: String, x: Double, y: Double, channel: String = "lines") {
    val h = handleFor(name, channel)
    val id = linesDragId(this, channel)
    fun send(phase: String, px: Double, py: Double) {
        if (!deliverLinesDrag(LinesDrag(id, phase, px, py, handle = name, own = true))) error("The drawing on channel '$channel' went away")
    }
    // A line handle dragged this way moves along its own axis; the other coordinate is passed as given.
    send("start", h.x ?: x, h.y ?: y)
    send("move", x, y)
    send("end", x, y)
}

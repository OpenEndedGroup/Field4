/**
 * boxLayout.kt
 *
 * Two layout helpers for documents whose boxes carry large output panels (converted notebooks
 * especially), both measuring a box together with every panel in its output area:
 *
 * - **Pack Boxes** ([BoxLayout.pack]) moves boxes so that no box or output panel overlaps another,
 *   with [BoxLayout.PAD] between them. Boxes are visited in reading order (top, then left); the
 *   first stays where it is, and each later one keeps its place if it is clear, otherwise moves
 *   right and/or down by the least distance that clears everything already placed.
 * - **Make Room for Outputs** ([BoxLayout.makeRoom]) is a mode: whenever an output area changes
 *   size (a panel opens, closes, grows or is resized) boxes it would overlap move down out of its
 *   way, and boxes it pushed come back up, never above where they were left, when it shrinks.
 *   The mode is remembered in `localStorage` across loads.
 *
 * Only top-level boxes are moved; boxes nested in another box's output area move with it. Boxes
 * that are hidden (layers) or have no frame are left alone and are not obstacles.
 */

package afield.app.canvas

import afield.app.client.allActions
import afield.app.client.applyFrameToBox
import afield.app.client.canvasGestures
import afield.app.client.hasRenderableFrame
import afield.app.client.isBoxVisible
import afield.app.notifications.notify
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.protocol.Set_rect
import afield.app.protocol.Set_rects
import afield.app.protocol.payload
import afield.app.protocol.send
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.math.max
import kotlin.math.min

object BoxLayout {
    /** The gap left between boxes and output panels. */
    const val PAD = 10.0

    private const val STORAGE_KEY = "afield.layout.makeRoomForOutputs"
    private const val SETTLE_MS = 60

    /**
     * A box and what it occupies: [parts] are the box and each output panel, relative to the
     * box's origin, in world units.
     */
    private class Item(val box: HTMLElement, val uid: String, val frame: Rect, val parts: List<Rect>, val outputs: List<Rect>) {
        val minX = parts.minOf { it.x }
        val maxX = parts.maxOf { it.right }
        val minY = parts.minOf { it.y }
        val maxY = parts.maxOf { it.bottom }
    }

    var makeRoom: Boolean = runCatching { window.localStorage.getItem(STORAGE_KEY) == "true" }.getOrDefault(false)
        private set

    /** Set by the box drag handlers so a pass never moves a box from under the pointer. */
    var dragging = false

    /** uid -> the y a box was left at, for boxes Make Room for Outputs has pushed down. */
    private val homes = mutableMapOf<String, Double>()

    /** uid -> the y values this module recently sent, so their echoes are not taken for user moves. */
    private val sent = mutableMapOf<String, MutableList<Double>>()

    private var pending: Int? = null

    /** What the last pass measured; a pass that sees something different schedules another. */
    private var lastSignature = ""
    private var unsettledPasses = 0
    private var passes = 0

    private fun topLevelBoxes(): List<HTMLElement> =
        document.getElementsByClassName("box").asList().mapNotNull { it as? HTMLElement }.filter {
            it.asDynamic().uid != undefined &&
                hasRenderableFrame(it) &&
                isBoxVisible(it) &&
                it.parentElement?.closest(".outputArea") == null
        }

    private fun measure(box: HTMLElement): Item {
        val d = box.asDynamic()
        val frame = Rect(
            (d.data_x as? Number)?.toDouble() ?: 0.0,
            (d.data_y as? Number)?.toDouble() ?: 0.0,
            (d.data_w as? Number)?.toDouble() ?: 0.0,
            (d.data_h as? Number)?.toDouble() ?: 0.0
        )
        val zoom = CanvasCamera.paneOf(box).zoom
        val origin = box.getBoundingClientRect()
        val outputs = mutableListOf<Rect>()
        val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement
        if (outputArea != null && window.getComputedStyle(outputArea).visibility != "hidden" &&
            window.getComputedStyle(outputArea).display != "none") {
            // A `.disclosure` is as wide as its group even when closed, far wider than the label it
            // shows, so measure what is drawn: the header, its arrow and title (the arrow hangs
            // outside the header), and the content while it is open.
            outputArea.querySelectorAll(".disclosure").asList().forEach { node ->
                val wrap = node as? HTMLElement ?: return@forEach
                val header = wrap.querySelector(".disclosure-header") as? HTMLElement
                val drawn = listOfNotNull(header, wrap.querySelector(".disclosure-content") as? HTMLElement) +
                    (header?.children?.asList().orEmpty())
                val rects = drawn.map { it.getBoundingClientRect() }.filter { it.width > 0.0 && it.height > 0.0 }
                if (rects.isEmpty()) return@forEach
                val left = rects.minOf { it.left }
                val top = rects.minOf { it.top }
                val right = rects.maxOf { it.right }
                val bottom = rects.maxOf { it.bottom }
                outputs += Rect((left - origin.left) / zoom, (top - origin.top) / zoom, (right - left) / zoom, (bottom - top) / zoom)
            }
        }
        return Item(box, d.uid as String, frame, listOf(Rect(0.0, 0.0, frame.w, frame.h)) + outputs, outputs)
    }

    /** Moving sideways costs three times as much as moving down, so columns stay columns. */
    private fun sideways(dx: Double) = 9 * dx * dx

    private fun Rect.at(x: Double, y: Double) = Rect(this.x + x, this.y + y, w, h)

    private fun clashes(a: Rect, b: Rect) =
        a.x < b.right + PAD && b.x < a.right + PAD && a.y < b.bottom + PAD && b.y < a.bottom + PAD

    private fun commit(moves: List<Pair<Item, Rect>>) {
        if (moves.isEmpty()) return
        moves.forEach { (item, frame) ->
            applyFrameToBox(item.box, frame)
            sent.getOrPut(item.uid) { mutableListOf() }.apply {
                add(frame.y)
                while (size > 8) removeAt(0)
            }
        }
        send(Message("set-rects").payload(Set_rects(moves.map { (item, frame) -> Set_rect(item.uid, "frame", frame) })))
        canvasGestures.requestConnectionGroupRebuild()
    }

    /**
     * Moves every top-level box so that no box or output panel overlaps another. Returns how many
     * boxes moved.
     */
    fun pack(): Int {
        val items = topLevelBoxes().map { measure(it) }.sortedWith(compareBy({ it.frame.y }, { it.frame.x }))
        val placed = mutableListOf<Rect>()
        val moves = mutableListOf<Pair<Item, Rect>>()
        for (item in items) {
            val ox = item.frame.x
            val oy = item.frame.y
            fun free(x: Double, y: Double) = item.parts.none { part -> part.at(x, y).let { r -> placed.any { clashes(r, it) } } }
            var best = ox to oy
            if (!free(ox, oy)) {
                // Straight down past everything placed is always clear, which bounds the search.
                val floor = (placed.maxOfOrNull { it.bottom } ?: oy) + PAD - item.minY
                val limit = floor - oy
                val xs = (listOf(ox) + placed.map { it.right + PAD - item.minX }).filter { it >= ox && it - ox <= limit }.distinct()
                val ys = (listOf(oy, floor) + placed.map { it.bottom + PAD - item.minY }).filter { it >= oy && it <= floor }.distinct()
                best = xs.flatMap { x -> ys.map { y -> x to y } }
                    .sortedWith(compareBy({ (x, y) -> sideways(x - ox) + (y - oy) * (y - oy) }, { (x, _) -> x - ox }))
                    .first { (x, y) -> free(x, y) }
            }
            val (x, y) = best
            item.parts.forEach { placed += it.at(x, y) }
            if (x != ox || y != oy) moves += item to Rect(x, y, item.frame.w, item.frame.h)
        }
        // Where Pack leaves a box is where it was put; Make Room for Outputs starts from there.
        homes.clear()
        commit(moves)
        return moves.size
    }

    /**
     * One Make Room for Outputs pass. Boxes are taken in the order of where they were left; each
     * starts there and moves down past any output panel of an earlier box it would overlap, and
     * past any earlier box that has itself been pushed. A box is never moved above where it was
     * left, so closing an output panel brings the boxes it pushed back, and no further.
     */
    private fun makeRoomPass() {
        val items = topLevelBoxes().map { measure(it) }
        passes++
        // Panels open, close and auto-size through transitions that a ResizeObserver only sees the
        // start of, so passes repeat until two in a row measure the same thing.
        val signature = items.joinToString("|") { item -> item.uid + item.outputs.joinToString { "${it.x},${it.y},${it.w},${it.h}" } }
        if (signature != lastSignature && unsettledPasses < 50) {
            lastSignature = signature
            unsettledPasses++
            schedule()
        } else {
            unsettledPasses = 0
        }
        fun home(item: Item) = homes[item.uid] ?: item.frame.y
        val done = mutableListOf<Triple<Item, Double, Boolean>>()
        val moves = mutableListOf<Pair<Item, Rect>>()
        for (item in items.sortedWith(compareBy({ home(it) }, { it.frame.x }))) {
            val home = home(item)
            val x = item.frame.x
            var y = home
            var moved = true
            while (moved) {
                moved = false
                for ((other, otherY, pushed) in done) {
                    val obstacles = if (pushed) other.parts else other.outputs
                    for (part in obstacles) {
                        val r = part.at(other.frame.x, otherY)
                        val clearsX = r.x >= x + item.maxX + PAD || r.right + PAD <= x + item.minX
                        if (clearsX) continue
                        if (r.y < y + item.maxY + PAD && r.bottom + PAD > y + item.minY) {
                            y = r.bottom + PAD - item.minY
                            moved = true
                        }
                    }
                }
            }
            done += Triple(item, y, y != home)
            if (y != home) homes[item.uid] = home else homes.remove(item.uid)
            if (y != item.frame.y) moves += item to Rect(x, y, item.frame.w, item.frame.h)
        }
        commit(moves)
    }

    private fun schedule() {
        // A throttle, not a debounce: a panel that auto-sizes for a while still gets room as it grows.
        if (!makeRoom || pending != null) return
        pending = window.setTimeout({
            pending = null
            if (!makeRoom) return@setTimeout
            if (dragging) schedule() else makeRoomPass()
        }, SETTLE_MS)
    }

    fun toggleMakeRoom(): Boolean {
        makeRoom = !makeRoom
        runCatching { window.localStorage.setItem(STORAGE_KEY, makeRoom.toString()) }
        if (makeRoom) schedule() else homes.clear()
        return makeRoom
    }

    /** Watches [box]'s output area, so Make Room for Outputs hears every panel change. */
    fun watch(box: HTMLElement) {
        val group = box.querySelector(".outputArea > .disclosure-group") ?: return
        val observer: dynamic = js("(function(f) { return typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(f); })")({ schedule() })
        observer?.observe(group)
        group.addEventListener("transitionend", { schedule() })
    }

    /**
     * A frame for [uid] arrived from the server. If it is not one this module sent, someone moved
     * the box, and where they put it becomes where it was left.
     */
    fun noteIncomingFrame(uid: String, y: Double) {
        if (uid !in homes) return
        if (sent[uid]?.contains(y) == true) return
        homes.remove(uid)
    }

    /**
     * [boxes] were dropped by hand; where they now are is where they were left. No pass runs
     * here, so a box dropped onto an output panel stays there until that output next changes.
     */
    fun noteDropped(boxes: Collection<Element>) {
        boxes.forEach { (it.asDynamic().uid as? String)?.let { uid -> homes.remove(uid) } }
    }

    fun state(): dynamic {
        val s: dynamic = js("({})")
        s.makeRoom = makeRoom
        s.passes = passes
        s.signature = lastSignature
        s.homes = homes.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        return s
    }
}

fun registerBoxLayoutActions(builtin: dynamic) {
    val pack: dynamic = js("({})")
    pack.id = "pack-boxes"
    pack.label = "Pack Boxes"
    pack.detail = "Move boxes so that none of them, or their output panels, overlap; the top-left box stays put"
    pack.run = {
        val n = BoxLayout.pack()
        notify(if (n == 0) "Nothing overlaps" else "Moved $n box${if (n == 1) "" else "es"}", "command")
    }
    builtin.push(pack)
    val room: dynamic = js("({})")
    room.id = "toggle-make-room-for-outputs"
    room.label = "Toggle Make Room for Outputs"
    room.detail = "When an output panel opens or grows, move the boxes it would cover down; move them back when it shrinks"
    room.run = {
        val on = BoxLayout.toggleMakeRoom()
        notify("Make Room for Outputs is ${if (on) "on" else "off"}", "command")
    }
    builtin.push(room)
    window.asDynamic().packBoxes = { BoxLayout.pack() }
    window.asDynamic().toggleMakeRoomForOutputs = { BoxLayout.toggleMakeRoom() }
    window.asDynamic().boxLayoutState = { BoxLayout.state() }
}

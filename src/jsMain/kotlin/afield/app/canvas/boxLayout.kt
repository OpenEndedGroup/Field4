/**
 * boxLayout.kt
 *
 * Layout helpers for documents whose boxes carry large output panels (converted notebooks
 * especially), both measuring a box together with every panel in its output area:
 *
 * - **Pack Boxes** ([BoxLayout.pack]) moves boxes so that no box or output panel overlaps another,
 *   with [BoxLayout.PAD] between them. Boxes are visited in reading order (top, then left); the
 *   first stays where it is, and each later one keeps its place if it is clear, otherwise moves
 *   right and/or down by the least distance that clears everything already placed.
 * - **Clean Up Layout** ([BoxLayout.cleanUp]) lines the selection (or, with fewer than two
 *   selected, every box) up in rows and columns sized to each box's footprint, so nothing
 *   overlaps; boxes outside a selection that the grid would cover move down out of its way.
 * - **Make Room for Outputs** ([BoxLayout.makeRoom]) is a mode: whenever an output area changes
 *   size (a panel opens, closes, grows or is resized) boxes it would overlap move down out of its
 *   way, and boxes it pushed come back up, never above where they were left, when it shrinks.
 *   The mode is remembered in `localStorage` across loads.
 *
 * - **Arrange Boxes by Presentation** ([BoxLayout.arrange]) puts boxes in one row in the order a
 *   presentation uses them. The server sends the order (`arrange-boxes`, from
 *   `afield.ui.slides.PresentationArrangement`); this client measures each box with its panels and
 *   answers with frames, which the server applies, records for undo and saves.
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
import afield.app.client.selected
import afield.app.notifications.notify
import afield.app.client.LoadingTracker
import afield.app.client.figureModeActive
import afield.app.protocol.ArrangeBoxesRequest
import afield.app.protocol.ArrangeBoxesResult
import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.app.protocol.handlers
import afield.app.protocol.put
import afield.app.protocol.Set_rect
import afield.app.protocol.Set_rects
import afield.app.protocol.payload
import afield.app.protocol.send
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
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
     * Clean Up Layout: puts [boxes] (the selection, or every top-level box when fewer than two are
     * selected) on an aligned grid of rows and columns, measured with every open output panel, so nothing
     * overlaps. Rows come from where the boxes are now: in order of their tops, a box joins the
     * current row while its top is above the middle of that row's shortest frame; within a row
     * boxes keep their left-to-right order. Every column is as wide as its widest footprint and
     * every row as tall as its tallest, plus [PAD]. The grid starts at the top-left of the boxes.
     *
     * When only a selection is cleaned up, boxes outside it that the new grid would overlap move
     * down (keeping their x) until they clear it and each other. Returns how many boxes moved.
     */
    fun cleanUp(selection: Collection<HTMLElement>): Int {
        val all = topLevelBoxes()
        val chosenSet = selection.filter { it in all }.toSet().takeIf { it.size >= 2 } ?: all.toSet()
        val items = all.filter { it in chosenSet }.map { measure(it) }
        if (items.isEmpty()) return 0

        val rows = mutableListOf<MutableList<Item>>()
        var rowTop = 0.0
        var rowLimit = 0.0
        for (item in items.sortedWith(compareBy({ it.frame.y }, { it.frame.x }))) {
            if (rows.isEmpty() || item.frame.y >= rowLimit) {
                rows += mutableListOf(item)
                rowTop = item.frame.y
                rowLimit = rowTop + max(20.0, item.frame.h / 2)
            } else {
                rows.last() += item
                rowLimit = min(rowLimit, rowTop + max(20.0, item.frame.h / 2))
            }
        }
        rows.forEach { row -> row.sortBy { it.frame.x } }

        // Frames line up down each column and along each row; panels that hang left of or above
        // their box (a panel's arrow does) get room from the column's or row's largest overhang.
        val columns = rows.maxOf { it.size }
        val colLead = DoubleArray(columns)
        val colTail = DoubleArray(columns)
        rows.forEach { row -> row.forEachIndexed { c, it -> colLead[c] = max(colLead[c], -it.minX); colTail[c] = max(colTail[c], it.maxX) } }
        val originX = items.minOf { it.frame.x + it.minX }
        val originY = items.minOf { it.frame.y + it.minY }

        val frames = mutableMapOf<Item, Rect>()
        val placed = mutableListOf<Rect>()
        var y = originY
        for (row in rows) {
            val rowLead = row.maxOf { -it.minY }
            var x = originX
            row.forEachIndexed { c, item ->
                val frame = Rect(x + colLead[c], y + rowLead, item.frame.w, item.frame.h)
                frames[item] = frame
                item.parts.forEach { placed += it.at(frame.x, frame.y) }
                x += colLead[c] + colTail[c] + PAD * 2
            }
            y += rowLead + row.maxOf { it.maxY } + PAD * 2
        }

        // Boxes left out of a selection clean-up get out of the grid's way, downwards only.
        val others = all.filter { it !in chosenSet }.map { measure(it) }.sortedWith(compareBy({ it.frame.y }, { it.frame.x }))
        val otherMoves = mutableListOf<Pair<Item, Rect>>()
        for (item in others) {
            val x = item.frame.x
            var oy = item.frame.y
            var moved = true
            while (moved) {
                moved = false
                for (part in item.parts) {
                    val r = part.at(x, oy)
                    val hit = placed.filter { clashes(r, it) }.maxOfOrNull { it.bottom } ?: continue
                    oy = hit + PAD - part.y
                    moved = true
                }
            }
            item.parts.forEach { placed += it.at(x, oy) }
            if (oy != item.frame.y) otherMoves += item to Rect(x, oy, item.frame.w, item.frame.h)
        }

        val moves = frames.filter { (item, f) -> f.x != item.frame.x || f.y != item.frame.y }.map { it.key to it.value } + otherMoves
        homes.clear()
        commit(moves)
        return moves.size
    }

    /**
     * Arrange Boxes by Presentation, on this client's measurements: the boxes in the request's order,
     * in one row from where the first one is. Each box goes [ArrangeBoxesRequest.gap] after the
     * previous one's footprint (its frame and every open panel), and the second box
     * [ArrangeBoxesRequest.leadGap] after the first, leaving the presenter's notes room; every frame
     * keeps the first one's y. Boxes this client does not show are skipped. Nothing moves here: the server applies the frames.
     */
    fun arrange(request: ArrangeBoxesRequest): ArrangeBoxesResult {
        val byUid = topLevelBoxes().associateBy { it.asDynamic().uid as String }
        val items = request.boxes.mapNotNull { byUid[it] }.map { measure(it) }
        val first = items.firstOrNull() ?: return ArrangeBoxesResult(request.id, emptyList())
        val y = first.frame.y
        var x = first.frame.x + first.minX
        val frames = items.mapIndexed { i, item ->
            val frame = Rect(x - item.minX, y, item.frame.w, item.frame.h)
            x += item.maxX - item.minX + if (i == 0) request.leadGap else request.gap
            Set_rect(item.uid, "frame", frame)
        }
        return ArrangeBoxesResult(request.id, frames)
    }

    /** What [arrange] would measure, so it can wait for panels that are still opening. */
    private fun panelSignature(uids: Collection<String>): String =
        topLevelBoxes().filter { (it.asDynamic().uid as String) in uids }.joinToString("|") { box ->
            measure(box).let { item -> item.uid + item.parts.joinToString { "${it.x.toInt()},${it.y.toInt()},${it.w.toInt()},${it.h.toInt()}" } }
        }

    /** Answers [request] once two measurements 250 ms apart agree (or after three seconds). */
    fun arrangeWhenSettled(request: ArrangeBoxesRequest, answer: (ArrangeBoxesResult) -> Unit) {
        val uids = request.boxes.toSet()
        var last = ""
        var tries = 0
        fun step() {
            val now = panelSignature(uids)
            if (now == last || tries++ >= 12) answer(arrange(request))
            else { last = now; window.setTimeout({ step() }, 250) }
        }
        step()
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
    val tidy: dynamic = js("({})")
    tidy.id = "clean-up-layout"
    tidy.label = "Clean Up Layout"
    tidy.detail = "Line up the selected boxes (or the whole document) in rows and columns, with room for every open output panel"
    tidy.run = {
        val n = BoxLayout.cleanUp(selected)
        notify(if (n == 0) "Already tidy" else "Moved $n box${if (n == 1) "" else "es"}", "command")
    }
    builtin.push(tidy)
    val room: dynamic = js("({})")
    room.id = "toggle-make-room-for-outputs"
    room.keyLabel = "Toggle Make Room for Outputs"
    val roomLabel: dynamic = js("({enumerable: true, configurable: true})")
    roomLabel.get = { "Toggle Make Room for Outputs (${if (BoxLayout.makeRoom) "On" else "Off"})" }
    js("Object.defineProperty")(room, "label", roomLabel)
    room.detail = "When an output panel opens or grows, move the boxes it would cover down; move them back when it shrinks"
    room.run = {
        val on = BoxLayout.toggleMakeRoom()
        notify("Make Room for Outputs is ${if (on) "on" else "off"}", "command")
    }
    builtin.push(room)
    val byTalk: dynamic = js("({})")
    byTalk.id = "arrange-by-presentation"
    byTalk.label = "Arrange Boxes by Presentation"
    byTalk.detail = "Put the Presentation box and its figures in one row in slide order, each clear of the last one's output panels"
    byTalk.run = { send(Message("arrange-by-presentation")) }
    builtin.push(byTalk)
    // A figure page shows one box alone; only a canvas can measure and answer. A canvas still
    // loading has not drawn its boxes, so it leaves the request to the replay it asks for once loaded.
    handlers.put("arrange-boxes") { message, _ ->
        if (!figureModeActive && LoadingTracker.isFinished()) {
            val request = Json.decodeFromJsonElement(ArrangeBoxesRequest.serializer(), message.payload!!)
            BoxLayout.arrangeWhenSettled(request) { send(Message("arrange-boxes-result").payload(it)) }
        }
        Handled.yes
    }
    window.asDynamic().packBoxes = { BoxLayout.pack() }
    window.asDynamic().cleanUpLayout = { BoxLayout.cleanUp(selected) }
    window.asDynamic().toggleMakeRoomForOutputs = { BoxLayout.toggleMakeRoom() }
    window.asDynamic().boxLayoutState = { BoxLayout.state() }
}

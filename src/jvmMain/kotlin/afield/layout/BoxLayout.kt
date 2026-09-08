package afield.layout

import afield.Box
import afield.OperationTranscript
import afield.Rect
import afield.nestedIn
import afield.notify
import afield.thisSessionInfo

/**
 * "Lay out selection" — arranging boxes on the canvas the way a diagram tool would, using the
 * layout engines in `native/field-d2-layout`.
 *
 * A document's boxes and the execution edges between them are a node-link diagram that happens to
 * be editable, so the same engine that draws a Petri net can tidy one. What makes this different
 * from drawing a Petri net is that the positions here are the user's: they were placed by hand,
 * they persist, and they are what the document looks like when it is opened again. So this only
 * ever runs when asked, only over what was asked for, and every move goes through
 * [OperationTranscript.recordMove] so one undo puts everything back.
 *
 * Unlike [afield.petri.PetriLayout.TALA] there is no fallback: Field has no second box-layout
 * engine to drop to, so an absent helper is reported to the user rather than absorbed.
 */
object BoxLayout {

    /** Boxes below this are not worth rearranging, and one box has no arrangement. */
    private const val MINIMUM = 2

    /**
     * Lays [selection] out and animates the boxes into place.
     *
     * @return the number of boxes moved.
     */
    fun organize(
        selection: List<Box>,
        engine: D2Layout.Engine = D2Layout.Engine.TALA,
        // Left to right, rather than whatever scores best per graph. A document is edited and
        // re-laid-out repeatedly, so the same graph landing the same way each time is worth more
        // here than the odd crossing saved -- unlike a Petri drawing, which is produced once and
        // looked at, and does sweep the directions.
        direction: D2Layout.Direction = D2Layout.Direction.RIGHT,
        animateMs: Double = 450.0,
    ): Int {
        val hint = D2Layout.unavailableHint
        if (hint != null) {
            notify("Box layout needs the field-d2-layout helper. $hint", "layout")
            return 0
        }

        val boxes = selection.filter { it.isArrangeable() }
        if (boxes.size < MINIMUM) {
            notify("Select at least $MINIMUM boxes to lay out", "layout")
            return 0
        }

        val byId = boxes.associateBy { it.id }
        val nodes = boxes.map { box ->
            val frame = box.frame
            D2Layout.Node(
                id = box.id,
                w = frame.w,
                h = frame.h,
                // Only nest inside a container that is itself being laid out; a box whose
                // container was left out of the selection is arranged as a peer instead, which is
                // the same thing the canvas would show.
                parent = box.nestedIn?.id?.takeIf { it in byId },
            )
        }

        // The execution graph, restricted to the selection. An edge to a box that was not selected
        // says nothing about where the selected ones should sit relative to each other.
        val edges = boxes.flatMap { from ->
            from.children.filter { it.id in byId }.map { to ->
                D2Layout.Edge(src = from.id, dst = to.id, id = "${from.id}>${to.id}")
            }
        }

        val result = D2Layout.layout(
            nodes = nodes,
            edges = edges,
            engine = engine,
            // Field positions a nested box inside its container's canvas, not on the document.
            coordinates = D2Layout.Coordinates.RELATIVE,
            direction = direction,
        )
        if (result == null) {
            notify("Layout failed — see the log for what the helper said", "layout")
            return 0
        }

        val placed = result.nodes.associateBy { it.id }
        if (boxes.any { it.id !in placed }) {
            notify("Layout came back incomplete; nothing moved", "layout")
            return 0
        }

        // Tidy in place. A layout starts near the origin, and a command that also teleported the
        // selection to the top-left corner of the document would be unusable; only the boxes at
        // the top level need this, since a nested box's coordinates are already parent-local.
        val topLevel = boxes.filter { placed.getValue(it.id).parent.isEmpty() }
        var shiftX = 0.0
        var shiftY = 0.0
        if (topLevel.isNotEmpty()) {
            shiftX = topLevel.minOf { it.frame.x } - topLevel.minOf { placed.getValue(it.id).x }
            shiftY = topLevel.minOf { it.frame.y } - topLevel.minOf { placed.getValue(it.id).y }
        }

        var moved = 0
        boxes.forEach { box ->
            val at = placed.getValue(box.id)
            val nested = at.parent.isNotEmpty()
            val target = Rect(
                x = at.x + if (nested) 0.0 else shiftX,
                y = at.y + if (nested) 0.0 else shiftY,
                // The engine may resize a container around its children. Field's boxes are sized
                // by the user and by their content, so the sizes that went in are the sizes that
                // come back out.
                w = box.frame.w,
                h = box.frame.h,
            )
            val old = box.frame
            if (old == target) return@forEach
            box.setFrameAnimated(target, animateMs)
            OperationTranscript.recordMove(box, old, box.frame)
            moved++
        }

        val label = if (moved == 1) "1 box" else "$moved boxes"
        notify("Laid out $label with ${engine.wire} (${"%.0f".format(result.elapsedMs)}ms)", "layout")
        return moved
    }

    /**
     * Lays out every box in the current document, whatever is selected.
     *
     * The selection-scoped form is the safer default and the one bound to the palette entry a
     * reader reaches for; this is the "organize document" verb, and it moves everything.
     */
    fun organizeDocument(
        engine: D2Layout.Engine = D2Layout.Engine.TALA,
        direction: D2Layout.Direction = D2Layout.Direction.RIGHT,
        animateMs: Double = 450.0,
    ): Int {
        val info = thisSessionInfo.get()
        if (info == null) {
            notify("No document to lay out", "layout")
            return 0
        }
        return organize(info.boxes.values.toList(), engine, direction, animateMs)
    }

    /** A tombstoned box, the document root, or something with no size cannot be arranged. */
    private fun Box.isArrangeable(): Boolean {
        if (id == "root") return false
        if (getPropertyRaw("_tombstone") == true) return false
        val frame = this.frame
        return frame.w > 0.0 && frame.h > 0.0
    }
}

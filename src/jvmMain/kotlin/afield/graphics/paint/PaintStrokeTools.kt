@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

import afield.ai.tools.AgentExtensionBuilder
import afield.ai.tools.ToolEffect
import java.util.Base64

/**
 * [strokeTools]: AI tools over a [PaintStrokes], so an AI4 conversation reads the strokes by
 * address, edits them with JSON op batches, undoes, and answers notes pinned on the picture.
 * Used by the `ai4_paint_studio` and `addressable_paint_strokes` starting points; tests:
 * `PaintStrokesTest`.
 */

/**
 * Register the `strokes.*` tools and a `strokes` context on this agent extension.
 *
 * - `strokes.read`: every note and stroke, one line each ([PaintStrokes.describe]).
 * - `strokes.apply`: a JSON batch of ops ([PaintStrokes.apply]), one undo step.
 * - `strokes.undo` / `strokes.redo`.
 * - `strokes.near`: the strokes under a point, nearest first.
 * - `strokes.pin` / `strokes.unpin`: notes on the picture.
 * - `strokes.look`: the picture as painted from the records.
 *
 * [sheet] is the sheet to edit; [changed] runs after every edit (re-render and show it). [onto],
 * when given, is the canvas the strokes are painted over in `strokes.look`.
 */
fun AgentExtensionBuilder.strokeTools(
    sheet: () -> PaintStrokes,
    changed: () -> Unit,
    onto: () -> PaintCanvas? = { null },
) {
    fun report(touched: Any?): Map<String, Any?> =
        mapOf("ok" to true, "touched" to touched, "strokes" to sheet().strokes.size, "undoSteps" to sheet().undoDepth)

    tool("strokes.read") {
        describe("Read the addressable strokes: each pinned note with the strokes under it, then one line per stroke (address, @group, colour, brush, width, bounds).")
        effects(ToolEffect.READ_DOCUMENT)
        action { sheet().describe().ifBlank { "No strokes yet: add some with strokes_apply." } }
    }
    tool("strokes.apply") {
        describe(
            "Edit the strokes with a JSON array of ops, applied all or nothing as one undo step. Each op has \"op\" and, except add and pin, " +
                "\"at\": an address (s12), @group, *, or several joined with commas. Ops: recolor {color:#rrggbb}; restyle {width, brush, hiding, " +
                "pressure:[start,end]}; move {dx, dy}; bend {dx, dy} (the middle of the path moves, the ends stay); delete; hide; show; " +
                "group {name}; reorder {to: index, -1 is on top}; add {points:[x0,y0,x1,y1,...], color, brush, width, pressure, hiding, group} " +
                "(one point is a dab); pin {x, y, text}; unpin {text}. Canvas units, 1000 wide, y DOWN. " +
                "Brushes: ${BrushKind.entries.joinToString()}.",
        )
        effects(ToolEffect.CONTROL_RUNTIME)
        val ops by param<String>("the JSON array of ops")
        action {
            val touched = sheet().apply(ops())
            changed()
            report(touched)
        }
    }
    tool("strokes.undo") {
        describe("Take back the last strokes_apply batch (or add or pin).")
        effects(ToolEffect.CONTROL_RUNTIME)
        action { val did = sheet().undo(); if (did) changed(); mapOf("ok" to did, "undoSteps" to sheet().undoDepth) }
    }
    tool("strokes.redo") {
        describe("Put back what strokes_undo took.")
        effects(ToolEffect.CONTROL_RUNTIME)
        action { val did = sheet().redo(); if (did) changed(); mapOf("ok" to did, "undoSteps" to sheet().undoDepth) }
    }
    tool("strokes.near") {
        describe("The addresses of the strokes passing near a point, nearest first.")
        effects(ToolEffect.READ_DOCUMENT)
        val x by param<Double>("x, canvas units")
        val y by param<Double>("y, canvas units, down from the top")
        val radius by param<Double>("how far to look", default = 60.0)
        action { sheet().near(x(), y(), radius()) }
    }
    tool("strokes.look") {
        describe("Look at the picture as the strokes paint it now.")
        effects(ToolEffect.CAPTURE_IMAGE)
        action {
            val png = sheet().render(onto()).use { it.png() }
            image("data:image/png;base64," + Base64.getEncoder().encodeToString(png), "${sheet().strokes.size} strokes")
        }
    }
    context("strokes") {
        val s = sheet()
        "This painting is kept as addressable strokes (${s.strokes.size} of them, ${s.notes.size} pinned notes). Read them with " +
            "strokes_read, change them by address with strokes_apply, look with strokes_look. A pinned note is the person pointing at " +
            "part of the picture: act on it through the strokes under it, then unpin it." +
            s.notes.joinToString("") { n -> " Note at (${n.x.toInt()}, ${n.y.toInt()}): \"${n.text}\", strokes under it: ${s.near(n.x, n.y).joinToString().ifEmpty { "none" }}." }
    }
}

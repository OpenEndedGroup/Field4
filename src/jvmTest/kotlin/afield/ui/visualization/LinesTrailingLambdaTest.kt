package afield.ui.visualization

import afield.document.graph.Box
import afield.geometry.path.FLine

/**
 * Compile-time pins, not runtime tests: `lines(..., editable = true) { edited -> }` is how box code
 * receives an edited drawing, and a trailing lambda binds to the *last* parameter. bb6e3aee4b added
 * `transition` and `onView` after `onEditCommitted`, so every such lambda silently became a view
 * callback and explainer_sound's three editable drawings stopped compiling. These two functions are
 * never called; jvmTest stops compiling if `onEditCommitted` is not the last parameter again.
 */
@Suppress("unused")
private object LinesTrailingLambdaPins {
    fun Box.varargOverload() {
        lines(FLine(), editable = true) { edited: List<FLine> -> edited.size }
    }

    fun Box.listOverload() {
        lines(listOf(FLine()), editable = true) { edited: List<FLine> -> edited.size }
    }
}

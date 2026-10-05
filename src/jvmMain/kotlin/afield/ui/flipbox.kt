package afield

import afield.logger.lprintln
import afield.stdlib.Marker

/**
 * A block with a checkbox drawn in the editor over its argument: it runs when the box evaluates with the box
 * ticked, and you tick it to run the block again. Each evaluation unticks it.
 *
 * ```
 * __m("aVq3x-k2").flipbox(true) {
 *     buildTheExpensiveThing()
 * }
 * ```
 *
 * Between `once` and `aside`: with [initial] `true` the block runs on the first run of the box (as
 * `once` would) and is skipped by every run after that; clicking the checkbox runs it again, with no
 * re-run of the box (as option/alt-return on an `aside` would). With [initial] `false` it runs only
 * when clicked. Returns whether this evaluation ran the block.
 *
 * A click ticks the box and runs the block on the main runloop with this box as its context. The box
 * stays ticked until that has happened; clicking it again first cancels. The click runs the block
 * from the most recent evaluation, so edits to its body take effect once the box has run again.
 *
 * Like `once`, the block runs with the box's parent run scope current, so what it registers
 * belongs to the box rather than to one run, and a re-run does not undo it.
 *
 * The tick lives on the [Marker], in memory: [initial] applies again after Field restarts, and a
 * fresh marker uid (Cmd+Shift+. inserts one) starts fresh.
 */
fun Marker.flipbox(initial: Boolean = false, block: () -> Unit): Boolean {
    if (properties["flipbox.on"] == null) properties["flipbox.on"] = initial
    properties["flipbox.block"] = block
    val ran = properties["flipbox.on"] == true
    if (ran) {
        properties["flipbox.on"] = false
        flipboxRun(block)
    }
    flipboxDraw()
    return ran
}

private fun Marker.flipboxRun(block: () -> Unit) {
    RunScope.withCurrent(box.runScopes.parent) { block() }
}

private fun Marker.flipboxClicked() {
    if (properties["flipbox.on"] == true) {
        properties["flipbox.on"] = false
        flipboxDraw()
        return
    }
    properties["flipbox.on"] = true
    flipboxDraw()
    inMain {
        if (properties["flipbox.on"] != true) return@inMain
        properties["flipbox.on"] = false
        @Suppress("UNCHECKED_CAST")
        val block = properties["flipbox.block"] as? () -> Unit
        try {
            if (block != null) Box.withBoxContextNow(box) { flipboxRun(block) }
        } catch (t: Throwable) {
            lprintln("warning,box", "flipbox ${name} in box ${box.id} threw: $t")
            box.error("flipbox: $t", "code", locationInSource()?.line ?: -1)
        } finally {
            flipboxDraw()
        }
    }
}

private fun Marker.flipboxDraw() {
    // Found by the marker's own text, not the stack, so a click (no box frame) can redraw it.
    val at = locationInSource(".flipbox(") ?: return
    val on = properties["flipbox.on"] == true
    // Marker uids are base64 and may hold '+' and '/', which a CSS class name cannot.
    val cls = "flipbox_" + name.replace(Regex("[^A-Za-z0-9_-]"), "_") + if (on) "_on" else "_off"
    val glyph = if (on) "☑" else "☐"
    // The argument only says how the box starts, which the checkbox already shows, so the
    // checkbox is drawn over it, as the marker rune is drawn over the `__m(...)` text.
    val line = box.code?.split("\n")?.getOrNull(at.line - 1) ?: return
    val close = line.indexOf(')', at.col - 1)
    val arg = if (close < 0) "" else line.substring(at.col - 1, close)
    // Only a bare literal: Monaco splits a longer argument into tokens, and each would draw a box.
    val decoration = if (arg.trim() != "true" && arg.trim() != "false") RawDecoration(
        at, cls, mapOf(
            cls to ".$cls:before { content:'$glyph '; color:white; cursor:pointer; font-size:110%; } " +
                ".$cls:hover::before { color:#7fd; }"
        ), clickCallback = { flipboxClicked() }
    ) else {
        val start = at.col + (arg.length - arg.trimStart().length)
        RawDecoration(
            at.line, start, at.line, start + arg.trim().length, cls, mapOf(
                cls to ".$cls { font-size:0 !important; cursor:pointer; } " +
                    ".$cls:after { content:'$glyph'; color:white; font-size:16px !important; } " +
                    ".$cls:hover::after { color:#7fd; }"
            ), clickCallback = { flipboxClicked() }
        )
    }
    box.decorationState[name + "_flipbox"] = decoration
}

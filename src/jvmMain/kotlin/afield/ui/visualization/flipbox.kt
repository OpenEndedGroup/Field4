package afield.ui.visualization

import afield.app.server.option
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.checkbox

import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.RunScope
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.withCurrent
import afield.ui.output.RawDecoration
import afield.ui.output.decorationState
import afield.ui.output.locationInSource
import afield.app.diagnostics.logging.lprintln
import afield.document.graph.Marker

/**
 * A checkbox drawn in the editor that re-runs one block of a box without re-running the box.
 *
 * `flipbox` extends [Marker], so it is reached through a marker uid in the source
 * (`__m("aVq3x-k2").flipbox(true) { … }`), and the checkbox is drawn at that call: over a bare
 * `true` or `false` argument where there is one, and before the call otherwise.
 *
 * The rest of the file is that decoration. A [RawDecoration] carries the glyph, its hover colour
 * and a click callback, and is rewritten after every evaluation and after every click, so the
 * editor always shows the current state rather than the argument's.
 *
 * It belongs with the other marker decorations in `decorations.kt` (`toggle`, `beep`, `bop`,
 * `liveNumber`), and is the one that owns a block rather than a value.
 */

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
        // "_cover" tells markers.js the cursor should skip this text, as it skips a marker rune.
        val coverCls = cls + "_cover"
        RawDecoration(
            at.line, start, at.line, start + arg.trim().length, coverCls, mapOf(
                coverCls to ".$coverCls { font-size:0 !important; cursor:pointer; } " +
                    ".$coverCls:after { content:'$glyph'; color:white; font-size:16px !important; } " +
                    ".$coverCls:hover::after { color:#7fd; }"
            ), clickCallback = { flipboxClicked() }
        )
    }
    box.decorationState[name + "_flipbox"] = decoration
}

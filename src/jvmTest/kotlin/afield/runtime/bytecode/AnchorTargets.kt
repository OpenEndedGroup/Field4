package afield.runtime.bytecode

import afield.document.graph.Box
import afield.ui.output.Location
import afield.ui.output.locationForTextStringOnStack

/*
 * The "library" side of [CallSiteAnchorPassTest]: [StackAware] targets shaped like the real box
 * helpers (`Marker.history`, `Marker.pianoRoll`). They live in their own file so that the stack
 * fallback, which keeps frames whose file name contains the box id, sees only the test's "box code".
 */

/** The box the targets anchor in; the test sets it. */
lateinit var anchorBox: Box

/** Whether the last target call was answered from the slot (true) or by the stack fallback. */
var lastAnchored = false

/** Like `Marker.history`: take the slot first, fall back to the stack search. */
@StackAware
fun history(value: Int, dur: Int = 100): Location? {
    val a = CallSites.take(anchorBox, "history", "history", end = true, offset = 1)
    lastAnchored = a != null
    return a ?: locationForTextStringOnStack("history", anchorBox, end = true, offset = 1)
}

/** The old path on its own, what `history` cost on every call before the anchors. */
fun historyByStack(value: Int): Location? = locationForTextStringOnStack("history", anchorBox, end = true, offset = 1)

/** A target whose default argument throws, so the call fails after the arm and before `take`. */
@StackAware
fun pianoRoll(value: Int, seconds: Int = explode()): Location? {
    val a = CallSites.take(anchorBox, "pianoRoll", ".pianoRoll(", end = true, offset = 1)
    lastAnchored = a != null
    return a
}

fun explode(): Int = throw IllegalStateException("a default argument threw before the target was entered")

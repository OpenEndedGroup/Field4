/**
 * # Drag handles in `three { }` scenes
 *
 * A handle is a marker the person can pick up and move: `handle("tip", at = tip) { drag -> tip = drag.position }`.
 * The browser viewer and the visionOS viewer both draw it and both send the same `three-handle` message, so
 * the box cannot tell (and need not care) which one a drag came from.
 *
 * - The viewer moves the marker itself while it is held, so a drag is smooth however long the box takes;
 *   the box hears `START`, then `MOVE`s (latest wins: a slow callback never falls behind the hand), then
 *   `END`, and usually answers by drawing the scene again with the handle where it was put.
 * - A handle may be held to a line ([ThreeSceneBuilder.handle]'s `axis`) or a plane (`normal`). Field
 *   applies the constraint to every position before the callback sees it, so the two viewers agree.
 * - Other viewers of the same view see the marker move live: the drag is relayed to them as `three/handle`.
 *
 * Callbacks are kept per view and replaced on every run of the `three { }` block, so a re-run does not
 * leave the previous run's closures behind.
 */
package afield.graphics.three

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import afield.ui.output.ControlCallbackLanes
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** A draggable marker in a `three { }` scene. See [ThreeSceneBuilder.handle]. */
@Serializable
data class ThreeHandle(
    val id: String,
    /** Where the marker is, in scene coordinates. */
    val position: List<Double>,
    /** A name drawn beside the marker, or `null` for none. */
    val label: String? = null,
    /** The marker's colour as a CSS colour; `null` for the viewer's own. */
    val color: String? = null,
    /** When set, the handle moves only along this direction through [position]. */
    val axis: List<Double>? = null,
    /** When set (and [axis] is not), the handle moves only in the plane through [position] with this normal. */
    val normal: List<Double>? = null,
)

/** One step of a person dragging a [ThreeHandle]: where it has been put, after its constraint. */
data class ThreeHandleDrag(val id: String, val position: Vec3, val phase: Phase) {
    enum class Phase { START, MOVE, END }
}

internal class ThreeHandleBinding(val handle: ThreeHandle, val onDrag: (ThreeHandleDrag) -> Unit)

/** Callbacks by view uid, then handle id; each run of a view's block replaces its map. */
private val handleBindings = ConcurrentHashMap<String, Map<String, ThreeHandleBinding>>()

internal fun registerThreeHandles(viewUid: String, bindings: Map<String, ThreeHandleBinding>) {
    if (bindings.isEmpty()) handleBindings.remove(viewUid) else handleBindings[viewUid] = bindings
}

/** [p] held to [handle]'s line or plane through its position. */
internal fun constrainHandle(handle: ThreeHandle, p: List<Double>): List<Double> {
    val o = handle.position
    val d = List(3) { p[it] - o[it] }
    handle.axis?.let { a ->
        val len2 = a.sumOf { it * it }
        if (len2 > 0) {
            val t = (0 until 3).sumOf { d[it] * a[it] } / len2
            return List(3) { o[it] + a[it] * t }
        }
    }
    handle.normal?.let { n ->
        val len2 = n.sumOf { it * it }
        if (len2 > 0) {
            val t = (0 until 3).sumOf { d[it] * n[it] } / len2
            return List(3) { p[it] - n[it] * t }
        }
    }
    return p
}

/**
 * A viewer's `three-handle` message: hand the constrained position to the handle's callback, in the
 * lane of the box that owns the view. Moves coalesce, newest winning; a press and a release never do.
 * Returns the constrained position, for relaying, or `null` when no such handle is bound.
 */
internal fun deliverThreeHandle(viewUid: String, ownerBoxId: String?, id: String, position: List<Double>, phase: String): List<Double>? {
    val binding = handleBindings[viewUid]?.get(id) ?: return null
    if (position.size < 3 || position.any { !it.isFinite() }) return null
    val placed = constrainHandle(binding.handle, position)
    val p = when (phase) {
        "start" -> ThreeHandleDrag.Phase.START
        "end" -> ThreeHandleDrag.Phase.END
        else -> ThreeHandleDrag.Phase.MOVE
    }
    val event = ThreeHandleDrag(id, vec(placed[0], placed[1], placed[2]), p)
    val key = "three-handle:$viewUid:$id"
    ControlCallbackLanes.submit(ownerBoxId ?: key, key, p != ThreeHandleDrag.Phase.MOVE) { binding.onDrag(event) }
    return placed
}

package afield

import afield.stdlib.Delegation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Direction definitions for custom marking menus.
 * Angles match buttons.js polarToRect which uses sin(angle) for x, cos(angle) for y:
 * 0=South, 90=East, 180=North, 270=West (clockwise from south).
 */
object MarkingDirections {
    data class DirectionInfo(val angle: Double, val radius: Double)

    val directions = mapOf(
        // Inner ring (radius 1)
        "N" to DirectionInfo(180.0, 1.0),
        "NE" to DirectionInfo(135.0, 1.0),
        "E" to DirectionInfo(90.0, 1.0),
        "SE" to DirectionInfo(45.0, 1.0),
        "S" to DirectionInfo(0.0, 1.0),
        "SW" to DirectionInfo(315.0, 1.0),
        "W" to DirectionInfo(270.0, 1.0),
        "NW" to DirectionInfo(225.0, 1.0),
        // Outer ring (radius 2)
        "N2" to DirectionInfo(180.0, 2.0),
        "NE2" to DirectionInfo(135.0, 2.0),
        "E2" to DirectionInfo(90.0, 2.0),
        "SE2" to DirectionInfo(45.0, 2.0),
        "S2" to DirectionInfo(0.0, 2.0),
        "SW2" to DirectionInfo(315.0, 2.0),
        "W2" to DirectionInfo(270.0, 2.0),
        "NW2" to DirectionInfo(225.0, 2.0),
    )
}

// Box property for custom marking menu entries.
// Keys are Pair(direction, label) — e.g. "N" to "Play"
// Values are callbacks to invoke when that direction is selected.
var Box.customMarking by Delegation<MutableMap<Pair<String, String>, () -> Unit>>(defaultValue = { mutableMapOf() })

// Registry for custom marking callbacks (maps callbackId -> callback)
private val customMarkingCallbacks = ConcurrentHashMap<String, () -> Unit>()

internal fun registerCustomMarkingCallback(callbackId: String, callback: () -> Unit) {
    customMarkingCallbacks[callbackId] = callback
}

internal fun fireCustomMarkingCallback(callbackId: String): Boolean {
    val callback = customMarkingCallbacks.remove(callbackId)
    if (callback != null) {
        callback()
        return true
    }
    return false
}

/**
 * Prepares custom marking entries for a box by registering callbacks and returning
 * serializable entries for the frontend.
 */
internal fun prepareCustomMarkingEntries(box: Box): List<CustomMarkingEntry> {
    val marking = box.customMarking
    if (marking.isEmpty()) return emptyList()

    return marking.mapNotNull { (key, callback) ->
        val (direction, label) = key
        val info = MarkingDirections.directions[direction] ?: return@mapNotNull null
        val callbackId = "cm-${box.id}-${UUID.randomUUID()}"
        registerCustomMarkingCallback(callbackId, callback)
        CustomMarkingEntry(
            direction = direction,
            label = label,
            callbackId = callbackId,
            radius = info.radius,
            angle = info.angle
        )
    }
}

package afield.app.server

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.custom
import afield.ui.visualization.menu

import afield.app.protocol.CustomMarkingEntry
import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.document.properties.Delegation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom marking menus: [Box.customMarking] maps a direction and a label to a callback, and the frontend
 * shows the entries as a radial menu on the box.
 *
 * [MarkingDirections] names the directions and their angles. When the menu is shown the server
 * registers each callback under an id and sends the frontend serialisable entries; choosing an
 * entry runs its callback.
 */

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

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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom marking menus: [Box.markingMenu] builds callbacks and nested submenus; the older
 * [Box.customMarking] map adds flat entries. The frontend shows them as a radial menu on the box.
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

/** A callback item or a submenu created by [MarkingMenuBuilder]. */
class MarkingMenuItem internal constructor(
    val direction: String, val label: String,
    internal val callback: (() -> Unit)? = null,
    val children: List<MarkingMenuItem> = emptyList(),
    val outputChannel: String = "", val outputWidth: Int = 360, val outputHeight: Int = 240
)

/** Build a radial level with [item] callbacks and nested [submenu] levels. */
class MarkingMenuBuilder {
    internal val entries = mutableListOf<MarkingMenuItem>()

    private fun validate(direction: String, label: String) {
        require(direction in MarkingDirections.directions) { "Unknown marking direction '$direction'; use N, NE, E, SE, S, SW, W, NW, optionally followed by 2" }
        require(label.isNotBlank()) { "A marking item needs a label" }
    }

    /** Put a callback in a compass direction, e.g. `item("N", "Refresh") { println("refresh") }`. */
    fun item(direction: String, label: String, callback: () -> Unit) {
        validate(direction, label)
        entries.add(MarkingMenuItem(direction, label, callback))
    }

    /**
     * Put an existing output channel inside the menu. Controls, SVG/canvas visualizers and iframe
     * views keep their live state and callbacks. Back/Escape returns them to their output area.
     * Create the channel with the normal `controlGroup`, `display` or visualization API first.
     */
    fun output(direction: String, label: String, channel: String, width: Int = 360, height: Int = 240) {
        validate(direction, label)
        require(channel.isNotBlank() && channel != "nested" && !channel.startsWith("*")) {
            "Use a named output channel; nested canvases and inline-value channels stay on the canvas"
        }
        require(width in 80..1200 && height in 40..900) { "Menu output size must be 80..1200 by 40..900 pixels" }
        entries.add(MarkingMenuItem(direction, label, outputChannel = channel, outputWidth = width, outputHeight = height))
    }

    /** Open another radial level by hovering or releasing on this label. */
    fun submenu(direction: String, label: String, build: MarkingMenuBuilder.() -> Unit) {
        validate(direction, label)
        val children = MarkingMenuBuilder().apply(build).entries.toList()
        require(children.isNotEmpty()) { "A marking submenu needs at least one item" }
        entries.add(MarkingMenuItem(direction, label, children = children))
    }
}

private var Box.customMarkingMenu by Delegation<List<MarkingMenuItem>>(defaultValue = { emptyList() })

/**
 * Replace this box's live custom marking menu. Rerunning replaces callbacks rather than accumulating
 * entries. Shift+Alt-click opens it (Alt-click also works on text frames). N is up, E right, S down,
 * W left; append 2 for the outer ring. Use [MarkingMenuBuilder.submenu] for another radial level.
 * The older [customMarking] map remains available and its entries are included alongside this menu.
 * Menus are runtime state: run the installing code again after reopening a document.
 */
fun Box.markingMenu(build: MarkingMenuBuilder.() -> Unit) {
    val entries = MarkingMenuBuilder().apply(build).entries.toList()
    customMarkingCallbackIds.remove(markingOwnerKey())?.flatten()?.forEach { customMarkingCallbacks.remove(it) }
    customMarkingMenu = entries
}

// Registry for custom marking callbacks (maps callbackId -> callback)
private val customMarkingCallbacks = ConcurrentHashMap<String, () -> Unit>()
private val customMarkingCallbackIds = ConcurrentHashMap<String, ArrayDeque<Set<String>>>()

private fun Box.markingOwnerKey(): String =
    if (isInsideInitialized()) "${inside.filename}\u0000$id" else "unbound:${System.identityHashCode(this)}"


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
    val ids = mutableSetOf<String>()
    fun prepare(item: MarkingMenuItem): CustomMarkingEntry {
        val info = MarkingDirections.directions.getValue(item.direction)
        val callbackId = if (item.callback != null) "cm-${box.id}-${UUID.randomUUID()}" else ""
        item.callback?.let { callback ->
            ids.add(callbackId)
            registerCustomMarkingCallback(callbackId) {
                Box.withBoxContextNow(box) { callback() }
            }
        }
        return CustomMarkingEntry(item.direction, item.label, callbackId, info.radius, info.angle,
            item.children.map(::prepare), item.outputChannel, item.outputWidth, item.outputHeight)
    }
    val legacy = box.customMarking.mapNotNull { (key, callback) ->
        val (direction, label) = key
        if (direction !in MarkingDirections.directions) return@mapNotNull null
        MarkingMenuItem(direction, label, callback)
    }
    val result = (legacy + box.customMarkingMenu).map(::prepare)
    // Keep recent menus independently usable in several views, with bounded callback retention.
    customMarkingCallbackIds.compute(box.markingOwnerKey()) { _, previous ->
        val snapshots = previous ?: ArrayDeque()
        snapshots.addLast(ids.toSet())
        while (snapshots.size > 32) snapshots.removeFirst().forEach { customMarkingCallbacks.remove(it) }
        snapshots
    }
    return result
}

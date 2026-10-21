package afield.ui.output

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.protocol.Rect
import afield.app.protocol._send
import afield.app.server.thisSession
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.HasProperties
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.execution.KotlinExecution
import afield.document.properties.Delegation
import kotlinx.coroutines.CancellationException

/**
 * Per-box callback tables that run when a box's frame, viewport, name or children change.
 *
 * Box code adds a named entry to `onFrameChange`, `onViewportChange`, `onNameChange` or `onTopologyChange`. A frame or name callback receives the current and incoming value and returns the value to keep. A callback that throws is reported as an error on the box, and the remaining callbacks for that change are skipped.
 */

private fun reportCallbackException(box: Box, throwable: Throwable, context: String) {
    val originSession = runCatching { thisSession.get() }.getOrNull()
    KotlinExecution.reportEvalException(
        box = box,
        reply = { _send(originSession, it) },
        originSession = originSession,
        throwable = throwable,
        lineOffset = 1,
        fallbackProperty = "code",
        context = context
    )
}

var Box.onFrameChange by Delegation<MutableMap<String, (Box, Rect, Rect) -> Rect>> {
    // ConcurrentHashMap: these tables are iterated (`for (callback in box.onXChange.values)`) from
    // whatever thread sets the property -- the runloop, macro replay, box execution -- while box code
    // registers callbacks from its own thread. Order of callbacks was already unspecified.
    java.util.concurrent.ConcurrentHashMap()
}

fun onFrameChange(a: HasProperties, current: Rect, incoming: Rect): Rect {
    var incoming = incoming
    val box = a as Box
    for (callback in box.onFrameChange.values) {
        try {
            incoming = callback(box, current, incoming)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportCallbackException(box, t, "onFrameChange callback")
            return current
        }
    }
    return incoming
}

var Box.onViewportChange by Delegation<MutableMap<String, (Box, Rect, Rect) -> Unit>> {
    // ConcurrentHashMap: these tables are iterated (`for (callback in box.onXChange.values)`) from
    // whatever thread sets the property -- the runloop, macro replay, box execution -- while box code
    // registers callbacks from its own thread. Order of callbacks was already unspecified.
    java.util.concurrent.ConcurrentHashMap()
}

fun onViewportChange(a: HasProperties, current: Rect, incoming: Rect): Rect {
    var incoming = incoming
    val box = a as Box
    for (callback in box.onViewportChange.values) {
        try {
            callback(box, current, incoming)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportCallbackException(box, t, "onViewportChange callback")
            return current
        }
    }
    return incoming
}

var Box.onNameChange by Delegation<MutableMap<String, (Box, String, String) -> String>> {
    // ConcurrentHashMap: these tables are iterated (`for (callback in box.onXChange.values)`) from
    // whatever thread sets the property -- the runloop, macro replay, box execution -- while box code
    // registers callbacks from its own thread. Order of callbacks was already unspecified.
    java.util.concurrent.ConcurrentHashMap()
}

fun onNameChange(a: HasProperties, current: String, incoming: String): String {
    var incoming = incoming
    val box = a as Box
    for (callback in box.onNameChange.values) {
        try {
            incoming = callback(box, current, incoming)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportCallbackException(box, t, "onNameChange callback")
            return current
        }
    }
    return incoming
}

var Box.onTopologyChange by Delegation<MutableMap<String, (Box, Boolean, Box) -> String>> {
    // ConcurrentHashMap: these tables are iterated (`for (callback in box.onXChange.values)`) from
    // whatever thread sets the property -- the runloop, macro replay, box execution -- while box code
    // registers callbacks from its own thread. Order of callbacks was already unspecified.
    java.util.concurrent.ConcurrentHashMap()
}

fun onTopologyChange(a: Box, adding: Boolean, newChild: Box) {
    for (callback in a.onTopologyChange.values) {
        try {
            callback(a, adding, newChild)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportCallbackException(a, t, "onTopologyChange callback")
            return
        }
    }
}

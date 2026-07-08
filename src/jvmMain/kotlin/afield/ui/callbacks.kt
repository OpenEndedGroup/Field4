package afield

import afield.stdlib.Delegation
import kotlinx.coroutines.CancellationException

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
    mutableMapOf()
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
    mutableMapOf()
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
    mutableMapOf()
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
    mutableMapOf()
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

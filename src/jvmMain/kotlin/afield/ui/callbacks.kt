package afield

import afield.stdlib.Delegation

var Box.onFrameChange by Delegation<MutableMap<String, (Box, Rect, Rect) -> Rect>> {
    mutableMapOf()
}

fun onFrameChange(a: HasProperties, current: Rect, incoming: Rect): Rect {
    var incoming = incoming
    (a as Box).onFrameChange.values.forEach { incoming = it(a, current, incoming) }
    return incoming
}

var Box.onViewportChange by Delegation<MutableMap<String, (Box, Rect, Rect) -> Unit>> {
    mutableMapOf()
}

fun onViewportChange(a: HasProperties, current: Rect, incoming: Rect): Rect {
    var incoming = incoming
    (a as Box).onViewportChange.values.forEach { it(a, current, incoming) }
    return incoming
}

var Box.onNameChange by Delegation<MutableMap<String, (Box, String, String) -> String>> {
    mutableMapOf()
}

fun onNameChange(a: HasProperties, current: String, incoming: String): String {
    var incoming = incoming
    (a as Box).onNameChange.values.forEach { incoming = it(a, current, incoming) }
    return incoming
}

var Box.onTopologyChange by Delegation<MutableMap<String, (Box, Boolean, Box) -> String>> {
    mutableMapOf()
}

fun onTopologyChange(a: Box, adding: Boolean, newChild: Box) {
    (a as Box).onTopologyChange.values.forEach { it(a, adding, newChild) }
}


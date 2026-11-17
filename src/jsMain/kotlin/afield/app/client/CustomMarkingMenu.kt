package afield.app.client

import afield.app.canvas.invoke

import afield.app.protocol.CustomMarkingFire
import afield.app.protocol.CustomMarkingRequest
import afield.app.protocol.CustomMarkingResponse
import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.payload
import afield.app.protocol.send
import afield.app.protocol.sendWithReply
import kotlinext.js.asJsObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.js.json
import kotlinx.browser.window

/**
 * Requests and displays a custom marking menu for a box.
 * Sends a request to the server for the box's custom marking entries,
 * then shows them as a radial marking menu with Voronoi-domain targets.
 */
fun requestCustomMarkingMenu(uid: String, x: Double, y: Double, event: dynamic) {
    val callbackName = "custom-marking-response-$uid-${kotlin.random.Random.nextInt()}"
    sendWithReply(
        Message("custom-marking-request", uid = uid).payload(CustomMarkingRequest(uid)),
        callbackName
    ) { message, _ ->
        val response = Json.decodeFromJsonElement<CustomMarkingResponse>(message.payload!!)
        val builtins = mutableListOf(json(
            "text" to "New text frame below",
            "callback" to { newTextFrameBelow(uid) },
            "radius" to 1.0, "angle" to 0.0
        ))
        if (findBoxByUid(uid)?.asDynamic()?.box_kind == "text-frame") {
            builtins += json(
                "text" to "Open settings",
                // Release the marking gesture and its pointer capture before Monaco takes focus.
                "callback" to { window.setTimeout({ window.asDynamic().openTextFrameSettings(uid) }, 100) },
                "radius" to 1.0, "angle" to 90.0
            )
        }
        val menus = (response.entries.map { entry ->
            json(
                "text" to entry.label,
                "callback" to {
                    send(Message("custom-marking-fire").payload(CustomMarkingFire(entry.callbackId)))
                },
                "radius" to entry.radius,
                "angle" to entry.angle
            )
        } + builtins).toTypedArray()
        goMarkingMenu(x, y, menus.asJsObject(), event, {})
        Handled.yes_remove
    }
}

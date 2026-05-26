package afield

import kotlinext.js.asJsObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.js.json

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
        if (response.entries.isEmpty()) {
            Handled.yes_remove
        } else {
            val menus = response.entries.map { entry ->
                json(
                    "text" to entry.label,
                    "callback" to {
                        send(Message("custom-marking-fire").payload(CustomMarkingFire(entry.callbackId)))
                    },
                    "radius" to entry.radius,
                    "angle" to entry.angle
                )
            }.toTypedArray()
            goMarkingMenu(x, y, menus.asJsObject(), event, {})
            Handled.yes_remove
        }
    }
}

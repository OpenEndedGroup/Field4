package afield.app.client

import afield.app.canvas.invoke

import afield.app.protocol.CustomMarkingFire
import afield.app.protocol.CustomMarkingEntry
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
@OptIn(ExperimentalJsExport::class)
@JsExport
fun requestCustomMarkingMenu(uid: String, x: Double, y: Double, event: dynamic) {
    // Capture before the menu takes the pointer. The callback must use this particular selection.
    val capture = window.asDynamic().captureTextFrameRun
    val anchor = if (capture != null) capture(uid) as? String else null
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
            if (anchor != null && window.asDynamic().currentDocumentReadOnly != true) {
                builtins += json(
                    "text" to "New note from selection",
                    "callback" to { newTextFrameBelow(uid, anchor) },
                    "radius" to 1.0, "angle" to 135.0
                )
            }
            builtins += json(
                "text" to "Open settings",
                // Release the marking gesture and its pointer capture before Monaco takes focus.
                "callback" to { window.setTimeout({ window.asDynamic().openTextFrameSettings(uid) }, 100) },
                "radius" to 1.0, "angle" to 90.0
            )
            if (window.asDynamic().currentDocumentReadOnly != true) {
                builtins += json(
                    "text" to "Edit Markdown",
                    "callback" to { window.setTimeout({ window.asDynamic().openTextFrameMarkdown(uid) }, 100) },
                    "radius" to 1.0, "angle" to 180.0
                )
            }
        }
        if (window.asDynamic().currentDocumentReadOnly != true) {
            fun visibilityEntry(label: String, actionId: String, angle: Double) = json(
                "text" to label,
                "callback" to { send(Message("perform-action").payload(afield.app.protocol.Perform_action(listOf(uid), actionId))) },
                "radius" to 1.0, "angle" to angle
            )
            builtins += json(
                "text" to "Hide / Reveal", "radius" to 1.0,
                "angle" to if (x < 260) { if (y < 200) 45.0 else 180.0 } else 270.0,
                "children" to arrayOf(
                    visibilityEntry("Hide all children", "-hide-children", 135.0),
                    visibilityEntry("Hide this and all children", "-hide-with-children", 90.0),
                    visibilityEntry("Reveal all children", "-reveal-children", 45.0),
                    visibilityEntry("Hide all parents", "-hide-parents", 225.0),
                    visibilityEntry("Hide this and all parents", "-hide-with-parents", 270.0),
                    visibilityEntry("Reveal all parents", "-reveal-parents", 315.0)
                )
            )
        }
        fun menuEntry(entry: CustomMarkingEntry): dynamic {
            val result = json(
                "text" to entry.label,
                "callback" to {
                    send(Message("custom-marking-fire").payload(CustomMarkingFire(entry.callbackId)))
                },
                "radius" to entry.radius,
                "angle" to entry.angle
            )
            if (entry.children.isNotEmpty()) result["children"] = entry.children.map(::menuEntry).toTypedArray()
            if (entry.outputChannel.isNotBlank()) {
                result["mount"] = { host: dynamic -> window.asDynamic().mountMarkingOutput(uid, entry.outputChannel, host) }
                result["outputWidth"] = entry.outputWidth
                result["outputHeight"] = entry.outputHeight
            }
            return result
        }
        val menus = (response.entries.map { menuEntry(it) } + builtins).toTypedArray()
        goMarkingMenu(x, y, menus.asJsObject(), event, {})
        Handled.yes_remove
    }
}

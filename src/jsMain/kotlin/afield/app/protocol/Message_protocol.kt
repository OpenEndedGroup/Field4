package afield.app.protocol

import afield.app.canvas.invoke

import afield.app.client.client
import afield.app.client.clientOpen
import afield.app.client.pileup
import afield.app.editor.ReadOnlyMode
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import web.timers.clearTimeout
import web.timers.setTimeout
import kotlin.js.Date
import kotlin.js.JSON
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.js.json
import kotlin.time.DurationUnit
import kotlin.time.toDuration

actual fun _send(message: Message) {
    // The single choke point for everything this client sends, which is why the read-only edit
    // counter is hooked here rather than at the dozens of call sites that build a mutating message.
    ReadOnlyMode.noteOutgoing(message)
    val s = Json.encodeToString(message)
    if (clientOpen) {
        client.send(s)
    } else {
        pileup.add(s)
    }
}

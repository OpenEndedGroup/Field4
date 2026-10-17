package afield.app.protocol

import afield.app.canvas.invoke

import afield.app.client.latestLocalPropertySequence
import afield.app.client.propertySyncClientId
import afield.app.client.propertySyncKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.math.max

//afield.app.protocol.sendWithReply("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})
//afield.runtime.lifecycle.end("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})

private var propertySyncClientSequence = 0L
private fun markLocalSetString(payload: dynamic) {
    val uid = payload.uid as? String ?: return
    val property = payload.property as? String ?: return
    propertySyncClientSequence += 1
    val sequence = propertySyncClientSequence
    latestLocalPropertySequence[propertySyncKey(uid, property)] = sequence
    payload.clientId = propertySyncClientId
    payload.clientSequence = sequence.toDouble()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun send(name: String, payload: dynamic) {
    if (name == "set-string") markLocalSetString(payload)
    send(Message(name, payload = Json.decodeFromString<JsonElement>(JSON.stringify(payload))))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun sendWithReply(name: String, payload: dynamic, callbackname: String, callback: dynamic) {
    sendWithReply(
        Message(name, payload = Json.decodeFromString<JsonElement>(JSON.stringify(payload))),
        callbackname
    ) { message: Message, function: (Message) -> Unit ->
        val callbackResult = callback(message, JSON.parse(message.payload.toString()))
        if ((callbackResult as? String) in setOf("remove", "once", "yes_remove")) Handled.yes_remove else Handled.yes
    }
}


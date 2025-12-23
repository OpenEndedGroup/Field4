package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

//afield.sendWithReply("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})
//afield.end("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})

@OptIn(ExperimentalJsExport::class)
@JsExport
fun send(name: String, payload: dynamic) {
    send(Message(name, payload = Json.decodeFromString<JsonElement>(JSON.stringify(payload))))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun sendWithReply(name: String, payload: dynamic, callbackname: String, callback: dynamic) {
    sendWithReply(
        Message(name, payload = Json.decodeFromString<JsonElement>(JSON.stringify(payload))),
        callbackname
    ) { message: Message, function: (Message) -> Unit ->
        callback(message, JSON.parse(message.payload.toString()))
        Handled.yes
    }
}

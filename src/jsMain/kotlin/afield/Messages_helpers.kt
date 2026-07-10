package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.math.max

//afield.sendWithReply("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})
//afield.end("hello", {some:2, thing:"foo"}, "foo", (message, payload) => { window.pay = payload})

private val propertySyncClientId: String = js(
    """(() => {
        const cryptoObj = globalThis.crypto;
        if (cryptoObj && typeof cryptoObj.randomUUID === "function") return cryptoObj.randomUUID();
        return "field-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
    })()"""
) as String
private var propertySyncClientSequence = 0L
private val latestLocalPropertySequence = mutableMapOf<String, Long>()
private val latestRemotePropertySequence = mutableMapOf<String, Long>()

private fun propertySyncKey(uid: String, property: String): String = "$uid\u001f$property"

private fun markLocalSetString(payload: dynamic) {
    val uid = payload.uid as? String ?: return
    val property = payload.property as? String ?: return
    propertySyncClientSequence += 1
    val sequence = propertySyncClientSequence
    latestLocalPropertySequence[propertySyncKey(uid, property)] = sequence
    payload.clientId = propertySyncClientId
    payload.clientSequence = sequence.toDouble()
}

private fun focusedEditorValue(uid: String, property: String): String? {
    val w = js("window")
    val selected = w.currentSelectedCode
    val selectedLen = (selected?.length as? Number)?.toInt() ?: 0
    if (selectedLen == 1 &&
        (selected[0].uid as? String) == uid &&
        (w.currentSelectedProperty as? String ?: "code") == property
    ) {
        val editor = w.globalEditor
        val model = if (editor == null || editor == undefined) null else editor.getModel()
        val hasFocus = editor != null &&
            editor != undefined &&
            runCatching { editor.hasTextFocus() as? Boolean ?: false }.getOrDefault(false)
        if (hasFocus && model != null && model != undefined) return model.getValue() as? String ?: ""
    }

    val embeddedEditors = w.embeddedEditors
    if (embeddedEditors != null && embeddedEditors != undefined) {
        val entry = embeddedEditors.getFor(uid, property)
        if (entry != null && entry != undefined) {
            val editor = entry.editor
            val model = entry.model
            val hasFocus = editor != null &&
                editor != undefined &&
                runCatching { editor.hasTextFocus() as? Boolean ?: false }.getOrDefault(false)
            if (hasFocus && model != null && model != undefined) return model.getValue() as? String ?: ""
        }
    }

    return null
}

fun acceptSetStringSync(set: Set_string): Boolean {
    val key = propertySyncKey(set.uid, set.property)
    val remoteSequence = set.syncSequence
    if (remoteSequence != null) {
        val latestRemote = latestRemotePropertySequence[key]
        if (latestRemote != null && remoteSequence <= latestRemote) return false
    }

    val clientSequence = set.clientSequence
    val fromThisClient = set.clientId == propertySyncClientId && clientSequence != null
    if (fromThisClient) {
        val latestLocal = latestLocalPropertySequence[key]
        if (latestLocal != null && clientSequence < latestLocal) {
            if (remoteSequence != null) {
                latestRemotePropertySequence[key] = max(latestRemotePropertySequence[key] ?: 0L, remoteSequence)
            }
            return false
        }

        if (!set.property.endsWith("_editorState")) {
            val focusedValue = focusedEditorValue(set.uid, set.property)
            if (focusedValue != null && focusedValue.replace("\r\n", "\n") != set.value.replace("\r\n", "\n")) {
                if (remoteSequence != null) {
                    latestRemotePropertySequence[key] = max(latestRemotePropertySequence[key] ?: 0L, remoteSequence)
                }
                return false
            }
        }
    }

    if (remoteSequence != null) {
        latestRemotePropertySequence[key] = max(latestRemotePropertySequence[key] ?: 0L, remoteSequence)
    }
    return true
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

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearReplyHandler(callbackname: String) {
    handlers.remove(callbackname)
}

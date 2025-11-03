package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.jvm.JvmExposeBoxed

enum class Handled {
    yes, no, yes_remove, no_remove
}

// return true to mean 'handled'
typealias Handler = (Message, (Message) -> Unit) -> Handled
typealias RawHandler = (String) -> Handled

val handlers = mutableMapOf<String, MutableSet<Handler>>()
val rawHandlers = mutableMapOf<String, MutableSet<RawHandler>>()

fun <K, V> MutableMap<K, MutableSet<V>>.put(key: K, value: V) {
    this.computeIfAbsent(key) { mutableSetOf() }.add(value)
}

fun <K, V> MutableMap<K, V>.computeIfAbsent(key: K, v: (K) -> V): V {
    if (!containsKey(key)) {
        val vv = v(key)
        this[key] = vv
        return vv
    }
    return this[key]!!
}


expect fun _send(message: Message)

fun send(message: Message) {
    _send(message)
}

inline fun <reified T> Message.payload(payload: T): Message {
    this.payload = Json.encodeToJsonElement(payload)
    return this
}

fun sendWithReply(message: Message, callbackName: String, callback: Handler /*true to be called again */) {
    message.replyTo = callbackName
    handlers[callbackName] =
        mutableSetOf(callback) // deliberately replaces all known callbacks of this name
    _send(message)
}


fun handle(message: Message, replyChannel: (Message) -> Unit = { m -> send(m) }): Boolean {
    val m = handlers.get(message.name)
    if (m != null) {
        for (n in ArrayList(m)) {
            val ret = n(message, replyChannel)
            when (ret) {
                Handled.yes -> return true
                Handled.no -> {}
                Handled.yes_remove -> {
                    m.remove(n)
                    if (m.size == 0) handlers.remove(message.name)
                    return true
                }

                Handled.no_remove -> {
                    m.remove(n)
                    if (m.size == 0) handlers.remove(message.name)
                }
            }
        }
    } else {
        println(" unhandled message $message")
    }
    return false
}

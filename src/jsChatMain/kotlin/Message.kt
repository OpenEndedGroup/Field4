package afield

import afield.Message
import js.atomic.Atomics.notify
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import web.timers.setTimeout
import kotlin.time.DurationUnit
import kotlin.time.toDuration

external fun notify(message : String, category : String)

var client = WebSocket("ws://" + document.location?.host + "/ws")

val pileup = mutableListOf<String>()
val onOpen = mutableListOf<Message>()
var clientOpen = false

var reopen_backoff = 100L

fun reopen() {
    console.log(" attempting reopen ")
    notify("Attempting reconnect...", "connectivity")
    client = WebSocket("ws://" + document.location?.host + "/ws")
    initializeClient()
}

var isReconnect = false

fun initializeClient() {
    client.onopen = {
        console.log(" on open !")
        if (window.unsafeCast<dynamic>().notify!=null) {
            notify("Connected — loading document", "connectivity")
            if (isReconnect) {
                (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
            }
        }
        clientOpen = true
        onOpen.forEach {
            send(it)
        }
        pileup.removeAll {
            client.send(it)
            true
        }
        isReconnect = true
        true
    }
    client.onclose = {
        notify("Disconnected (try reloading this window)", "connectivity")
        console.log("socket has closed ", it)
        clientOpen = false
        reopen_backoff = (1.1*reopen_backoff).toLong()
        setTimeout(reopen_backoff.toDuration(DurationUnit.MILLISECONDS), ::reopen)
        (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = "blur(20px)"
    }
    client.onmessage = {
        val data = it.data as String
        val message: Message = Json.decodeFromString<Message>(data)
//        console.log("RECEIVE", message)
        handle(message)
    }
    client.onerror = {
        notify("Disconnected (error)", "connectivity")
        console.log("socket has error ", it)
        clientOpen = false
        reopen_backoff *= 2
        setTimeout(reopen_backoff.toDuration(DurationUnit.MILLISECONDS), ::reopen)
        (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = "blur(20px)"
    }
}


actual fun _send(message: Message) {
    val s = Json.encodeToString(message)
    if (clientOpen) {
        client.send(s)
    } else {
        pileup.add(s)
    }
}

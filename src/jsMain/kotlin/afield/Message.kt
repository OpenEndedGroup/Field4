package afield

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import web.timers.clearTimeout
import web.timers.setTimeout
import kotlin.js.Promise
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private fun wsUrl(): String {
    val proto = if (document.location?.protocol == "https:") "wss" else "ws"
    return "$proto://" + document.location?.host + "/ws"
}

var client = WebSocket(wsUrl())

val pileup = mutableListOf<String>()
val onOpen = mutableListOf<Message>()
var clientOpen = false

var reopen_backoff = 100L
private val reopen_backoff_min = 100L
private val reopen_backoff_max = 30_000L
private var reconnectTimeoutHandle: Int? = null

private fun clearReconnectTimeout() {
    reconnectTimeoutHandle?.let { window.clearTimeout(it) }
    reconnectTimeoutHandle = null
}

private fun scheduleReconnect(delayMillis: Long) {
    if (reconnectTimeoutHandle != null) return
    reconnectTimeoutHandle = window.setTimeout({
        reconnectTimeoutHandle = null
        reopen()
    }, delayMillis.toInt())
}

fun reopen() {
    clearReconnectTimeout()
    console.info("[ws] attempting reconnect")
    notify("Attempting reconnect...", "connectivity")
    client = WebSocket(wsUrl())
    initializeClient()
}

var isReconnect = false

fun initializeClient() {
    client.onopen = {
        console.info("[ws] open")
        if (window.unsafeCast<dynamic>().notify!=null) {
            notify("Connected — loading document", "connectivity")
            if (isReconnect) {
                (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
            }
        }
        
        // Hide splash screen on first connection
        if (!isReconnect) {
            hideSplashScreenAfterFontLoad()
        }
        
        clientOpen = true
        clearReconnectTimeout()
        reopen_backoff = reopen_backoff_min
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
        console.info("[ws] closed", it)
        clientOpen = false
        reopen_backoff = (1.1 * reopen_backoff).toLong().coerceAtMost(reopen_backoff_max)
        scheduleReconnect(reopen_backoff)
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
        console.info("[ws] error", it)
        clientOpen = false
        reopen_backoff = (reopen_backoff * 2).coerceAtMost(reopen_backoff_max)
        scheduleReconnect(reopen_backoff)
        (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = "blur(20px)"
    }
}

private fun hideSplashScreenAfterFontLoad() {
    val splashScreen = document.getElementById("splash-screen") as? HTMLElement ?: return

    var fadeStarted = false
    fun startFade() {
        if (fadeStarted) return
        fadeStarted = true
        splashScreen.classList.add("hidden")
        setTimeout(1000.toDuration(DurationUnit.MILLISECONDS)) {
            splashScreen.style.display = "none"
        }
    }

    // Avoid width jump when the custom font swaps in during the fade-out
    val fontSet = document.asDynamic().fonts
    val canLoadFont = js("document.fonts && typeof document.fonts.load === 'function'") as Boolean
    if (canLoadFont) {
        val timeoutHandle = setTimeout(1500.toDuration(DurationUnit.MILLISECONDS)) {
            startFade()
        }
        (fontSet.load("400 12px \"Iosevka Web\"") as Promise<*>).then({
            clearTimeout(timeoutHandle)
            startFade()
        }, {
            clearTimeout(timeoutHandle)
            startFade()
        })
    } else {
        startFade()
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

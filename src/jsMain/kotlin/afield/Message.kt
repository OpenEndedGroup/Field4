package afield

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

private fun wsUrl(): String {
    val proto = if (document.location?.protocol == "https:") "wss" else "ws"
    return "$proto://" + document.location?.host + "/ws"
}

lateinit var client: WebSocket

val pileup = mutableListOf<String>()
val onOpen = mutableListOf<Message>()
var clientOpen = false

var reopen_backoff = 100L
private val reopen_backoff_min = 100L
private val reopen_backoff_max = 30_000L
private var reconnectTimeoutHandle: Int? = null
private var splashFadeStarted = false
private val diagnosticPageId = "page-${Date.now().toLong()}"
private val diagnosticPageStartedAt = window.performance.now()
private var diagnosticSequence = 0
private var websocketGeneration = 0
private var incomingWebsocketSequence = 0

fun fieldDiagnostic(event: String, details: dynamic = null) {
    val envelope = json(
        "pageId" to diagnosticPageId,
        "sequence" to ++diagnosticSequence,
        "pageElapsedMs" to (window.performance.now() - diagnosticPageStartedAt).toInt(),
        "href" to window.location.href,
        "readyState" to document.readyState,
        "visibilityState" to document.asDynamic().visibilityState,
        "details" to details
    )
    try {
        val bridge = window.asDynamic().fieldElectron
        if (bridge != null && jsTypeOf(bridge.diagnostic) == "function") {
            bridge.diagnostic(event, envelope)
        } else {
            console.info("[field,diagnostic]", event, JSON.stringify(envelope))
        }
    } catch (error: Throwable) {
        console.warn("[field,diagnostic] failed", event, error)
    }
}

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
    fieldDiagnostic("ws-reconnect-attempt", json("backoffMs" to reopen_backoff))
    notify("Attempting reconnect...", "connectivity")
    client = WebSocket(wsUrl())
    initializeClient()
}

var isReconnect = false

fun initializeClient() {
    if (!::client.isInitialized) {
        client = WebSocket(wsUrl())
    }
    val generation = ++websocketGeneration
    window.asDynamic().fieldClientOpen = clientOpen
    window.asDynamic().fieldBackdoorDisconnectedOverlayActive = !clientOpen
    fieldDiagnostic(
        "ws-initialized",
        json(
            "generation" to generation,
            "url" to wsUrl(),
            "wasOpen" to clientOpen
        )
    )
    client.onopen = {
        console.info("[ws] open")
        if (window.unsafeCast<dynamic>().notify!=null) {
            notify("Connected — loading document", "connectivity")
            if (isReconnect) {
                (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
            }
        }
        
        clientOpen = true
        window.asDynamic().fieldClientOpen = true
        window.asDynamic().fieldBackdoorDisconnectedOverlayActive = false
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
        fieldDiagnostic(
            "ws-open",
            json(
                "generation" to generation,
                "queuedMessages" to pileup.size,
                "onOpenMessages" to onOpen.size
            )
        )
        true
    }
    client.onclose = {
        notify("Disconnected (try reloading this window)", "connectivity")
        console.info("[ws] closed", it)
        clientOpen = false
        window.asDynamic().fieldClientOpen = false
        window.asDynamic().fieldBackdoorDisconnectedOverlayActive = true
        reopen_backoff = (1.1 * reopen_backoff).toLong().coerceAtMost(reopen_backoff_max)
        scheduleReconnect(reopen_backoff)
        (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = "blur(20px)"
        fieldDiagnostic(
            "ws-close",
            json(
                "generation" to generation,
                "code" to it.asDynamic().code,
                "reason" to it.asDynamic().reason,
                "wasClean" to it.asDynamic().wasClean,
                "nextBackoffMs" to reopen_backoff
            )
        )
    }
    client.onmessage = {
        val data = it.data as String
        val incomingSequence = ++incomingWebsocketSequence
        try {
            val message: Message = Json.decodeFromString<Message>(data)
            if (!LoadingTracker.isFinished() || message.name == "expected-box-count" || message.name == "document-load-status") {
                fieldDiagnostic(
                    "ws-message",
                    json(
                        "generation" to generation,
                        "incomingSequence" to incomingSequence,
                        "name" to message.name,
                        "uid" to message.uid,
                        "bytes" to data.length
                    )
                )
            }
            handle(message)
        } catch (error: Throwable) {
            fieldDiagnostic(
                "ws-message-error",
                json(
                    "generation" to generation,
                    "incomingSequence" to incomingSequence,
                    "bytes" to data.length,
                    "error" to error.toString()
                )
            )
            throw error
        }
    }
    client.onerror = {
        notify("Disconnected (error)", "connectivity")
        console.info("[ws] error", it)
        clientOpen = false
        window.asDynamic().fieldClientOpen = false
        window.asDynamic().fieldBackdoorDisconnectedOverlayActive = true
        reopen_backoff = (reopen_backoff * 2).coerceAtMost(reopen_backoff_max)
        scheduleReconnect(reopen_backoff)
        (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = "blur(20px)"
        fieldDiagnostic(
            "ws-error",
            json(
                "generation" to generation,
                "nextBackoffMs" to reopen_backoff
            )
        )
    }
}

fun hideSplashScreenImmediately() {
    val splashScreen = document.getElementById("splash-screen") as? HTMLElement ?: return

    fun startFade() {
        if (splashFadeStarted) return
        splashFadeStarted = true
        splashScreen.classList.add("hidden")
        splashScreen.style.display = "none"
    }

    startFade()
}

fun hideSplashScreenAfterFontLoad() {
    val splashScreen = document.getElementById("splash-screen") as? HTMLElement ?: return

    fun startFade() {
        if (splashFadeStarted) return
        splashFadeStarted = true
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
        (fontSet.load("300 semi-condensed 12px \"Iosevka Web\"") as Promise<*>).then({
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

fun registerOnOpen(message: Message) {
    onOpen.removeAll { it == message }
    onOpen.add(message)
    if (clientOpen) {
        send(message)
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

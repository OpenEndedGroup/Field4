package afield.app.client

import afield.app.canvas.invoke
import afield.app.protocol.Output

import afield.app.protocol.Handled
import afield.app.protocol.LiveOutputIframeCommand
import afield.app.protocol.LiveOutputIframeReply
import afield.app.protocol.Message
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.protocol.put
import afield.app.protocol.send
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.Element
import org.w3c.dom.HTMLIFrameElement
import kotlin.js.JSON
import kotlin.js.Promise
import kotlin.js.jsTypeOf

/** Route a server command to the iframe in this exact client view, not a replayed capture page. */
fun installLiveOutputIframeClient() {
    if (window.asDynamic().fieldElectron == null) return
    registerOnOpen(Message("live-output-iframe-ready"))
    // Cookie changes in a watched web profile, passed on to the server as they happen.
    // Pages a `Box.web` page visits arrive naming the page; add its box, output area and profile.
    window.asDynamic().fieldElectron.onWebProfileEvent { change: dynamic ->
        if (change.kind == "visit" || change.kind == "title") {
            val element = webPageElement(change.page) ?: return@onWebProfileEvent
            val box = element.closest(".box") ?: return@onWebProfileEvent
            change.boxUid = box.asDynamic().uid
            change.channel = element.closest(".disclosure")?.asDynamic()?.__disclosureId ?: ""
            val partition = element.getAttribute("partition") ?: ""
            change.directory = if (partition.startsWith("field-path:"))
                js("decodeURIComponent")(partition.removePrefix("field-path:")) else ""
        }
        send(Message("web-profile-event").payload(Json.parseToJsonElement(JSON.stringify(change))))
    }
    handlers.put("live-output-iframe-command") { message, _ ->
        val command = Json.decodeFromJsonElement<LiveOutputIframeCommand>(message.payload!!)
        fun fail(reason: String) {
            send(Message("live-output-iframe-reply").payload(
                LiveOutputIframeReply(command.id, false, error = reason)
            ))
        }
        // A web profile's cookies belong to no iframe: Electron answers them directly. The
        // command's `url` names the profile directory ("" for Field's shared session) and its
        // `method` the action.
        if (command.operation == "profile") {
            val request = JSON.parse<dynamic>("{}")
            request.directory = command.url
            request.action = command.method
            request.params = JSON.parse<dynamic>(Json.encodeToString(command.params))
            val promise = window.asDynamic().fieldElectron.webProfile(request).unsafeCast<Promise<dynamic>>()
            promise.then({ answer: dynamic ->
                val value = if (jsTypeOf(answer) == "undefined") JsonNull
                    else Json.parseToJsonElement(JSON.stringify(answer))
                send(Message("live-output-iframe-reply").payload(LiveOutputIframeReply(command.id, true, value)))
            }, { error: dynamic ->
                fail(error?.message as? String ?: error.toString())
            })
            return@put Handled.yes
        }
        val boxes = document.querySelectorAll(".box")
        var box: Element? = null
        for (i in 0 until boxes.length) {
            val candidate = boxes.item(i) as? Element ?: continue
            if (candidate.asDynamic().uid == command.boxUid) {
                if (box != null) {
                    fail("More than one visible box has uid ${command.boxUid}")
                    return@put Handled.yes
                }
                box = candidate
            }
        }
        if (box == null) {
            fail("Box ${command.boxUid} is not in this Electron view")
            return@put Handled.yes
        }
        val disclosures = box.querySelectorAll(".outputArea .disclosure")
        var channel: Element? = null
        for (i in 0 until disclosures.length) {
            val candidate = disclosures.item(i) as? Element ?: continue
            if (candidate.asDynamic().__disclosureId == command.channel) {
                if (channel != null) {
                    fail("More than one output area is called '${command.channel}'")
                    return@put Handled.yes
                }
                channel = candidate
            }
        }
        if (channel == null) {
            fail("Output area '${command.channel}' is not displayed in this Electron view")
            return@put Handled.yes
        }
        // A `Box.web` page with a profile is a <webview>, which Electron reaches by its webContents id.
        val frames = channel.querySelectorAll(".disclosure-content iframe, .disclosure-content webview")
        if (frames.length != 1) {
            fail("Output area '${command.channel}' must contain exactly one iframe; found ${frames.length}")
            return@put Handled.yes
        }
        val element = frames.item(0).asDynamic()
        val isWebview = (element.tagName as String).lowercase() == "webview"
        val frame = element.unsafeCast<HTMLIFrameElement>()
        if (command.operation == "navigate") {
            if (command.url.isBlank()) {
                fail("Iframe URL cannot be empty")
                return@put Handled.yes
            }
            var timer = 0
            val loaded: (dynamic) -> Unit = {
                window.clearTimeout(timer)
                send(Message("live-output-iframe-reply").payload(
                    LiveOutputIframeReply(command.id, true,
                        kotlinx.serialization.json.JsonPrimitive(frame.src), frame.src)
                ))
            }
            frame.asDynamic().addEventListener(if (isWebview) "did-stop-loading" else "load", loaded, js("({once:true})"))
            timer = window.setTimeout({
                frame.asDynamic().removeEventListener("load", loaded)
                fail("Iframe navigation timed out after ${command.timeoutMillis}ms")
            }, command.timeoutMillis.toInt())
            frame.src = command.url
            return@put Handled.yes
        }
        // The name identifies this exact DOM iframe to Electron's WebFrameMain tree, even when
        // two output areas load the same URL. It changes no URL and survives a cross-origin page.
        // An iframe named when it was written (`Box.web`'s `data-field-live-name`) keeps that name:
        // Electron reads the name a frame had when it navigated, so renaming it now would not reach
        // Electron, and after a link clicked inside the page its URL no longer matches `src` either.
        val fixedName = frame.getAttribute("data-field-live-name")?.takeIf { it.startsWith("field-live-iframe-") }
        val frameName = fixedName ?: "field-live-iframe-${command.id}"
        if (fixedName == null) frame.name = frameName
        val request = JSON.parse<dynamic>(Json.encodeToString(command))
        request.frameName = frameName
        if (fixedName != null) request.id = fixedName.removePrefix("field-live-iframe-")
        request.frameUrl = frame.src
        if (isWebview) {
            val guestId = runCatching { element.getWebContentsId() }.getOrNull()
            if (guestId == null) {
                fail("The webview for '${command.channel}' has not attached yet")
                return@put Handled.yes
            }
            request.guestId = guestId
        }
        if (command.operation == "screenshot" || command.operation == "devtools") {
            // An output area panned off the canvas has no pixels to capture, and DevTools inspects
            // the frame at a point on screen. Bring it into view the way a reader would, by panning
            // the canvas so it is centred, then measure again.
            frame.getBoundingClientRect().let { r ->
                val offscreen = r.right <= 0.0 || r.bottom <= 0.0 ||
                    r.left >= window.innerWidth.toDouble() || r.top >= window.innerHeight.toDouble()
                if (offscreen) {
                    val (tx, ty) = mainTranslation
                    moveCanvasCamera(
                        tx + window.innerWidth / 2.0 - (r.left + r.width / 2.0),
                        ty + window.innerHeight / 2.0 - (r.top + r.height / 2.0),
                    )
                }
            }
            val rect = frame.getBoundingClientRect()
            val x = maxOf(0.0, rect.left)
            val y = maxOf(0.0, rect.top)
            val right = minOf(window.innerWidth.toDouble(), rect.right)
            val bottom = minOf(window.innerHeight.toDouble(), rect.bottom)
            if (right <= x || bottom <= y) {
                fail("Iframe is outside the visible Electron tab even after panning the canvas to it; is its output area collapsed?")
                return@put Handled.yes
            }
            request.captureRect = JSON.parse<dynamic>("{}")
            request.captureRect.x = x
            request.captureRect.y = y
            request.captureRect.width = right - x
            request.captureRect.height = bottom - y
        }
        val promise = window.asDynamic().fieldElectron.outputIframe(request).unsafeCast<Promise<dynamic>>()
        promise.then({ answer: dynamic ->
            val value = if (jsTypeOf(answer.value) == "undefined") JsonNull
                else Json.parseToJsonElement(JSON.stringify(answer.value))
            send(Message("live-output-iframe-reply").payload(
                LiveOutputIframeReply(command.id, true, value, answer.frameUrl as? String ?: "")
            ))
        }, { error: dynamic ->
            fail(error?.message as? String ?: error.toString())
        })
        Handled.yes
    }
}

/** The iframe or webview a navigation report names: `{frameName}` for an iframe, `{guestId}` for a webview. */
private fun webPageElement(page: dynamic): Element? {
    val frameName = page?.frameName as? String
    if (frameName != null) {
        val frames = document.querySelectorAll(".disclosure-content iframe")
        for (i in 0 until frames.length) {
            val frame = frames.item(i) as? Element ?: continue
            if (frame.getAttribute("data-field-live-name") == frameName) return frame
        }
        return null
    }
    val guestId = page?.guestId ?: return null
    val views = document.querySelectorAll("webview.field-web-page")
    for (i in 0 until views.length) {
        val view = views.item(i) as? Element ?: continue
        val id = runCatching { view.asDynamic().getWebContentsId() }.getOrNull()
        if (id == guestId) return view
    }
    return null
}

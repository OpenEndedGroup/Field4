package afield

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
    handlers.put("live-output-iframe-command") { message, _ ->
        val command = Json.decodeFromJsonElement<LiveOutputIframeCommand>(message.payload!!)
        fun fail(reason: String) {
            send(Message("live-output-iframe-reply").payload(
                LiveOutputIframeReply(command.id, false, error = reason)
            ))
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
        val frames = channel.querySelectorAll(".disclosure-content iframe")
        if (frames.length != 1) {
            fail("Output area '${command.channel}' must contain exactly one iframe; found ${frames.length}")
            return@put Handled.yes
        }
        val frame = frames.item(0) as HTMLIFrameElement
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
            frame.asDynamic().addEventListener("load", loaded, js("({once:true})"))
            timer = window.setTimeout({
                frame.asDynamic().removeEventListener("load", loaded)
                fail("Iframe navigation timed out after ${command.timeoutMillis}ms")
            }, command.timeoutMillis.toInt())
            frame.src = command.url
            return@put Handled.yes
        }
        // The name identifies this exact DOM iframe to Electron's WebFrameMain tree, even when
        // two output areas load the same URL. It changes no URL and survives a cross-origin page.
        val frameName = "field-live-iframe-${command.id}"
        frame.name = frameName
        val request = JSON.parse<dynamic>(Json.encodeToString(command))
        request.frameName = frameName
        request.frameUrl = frame.src
        if (command.operation == "screenshot") {
            val rect = frame.getBoundingClientRect()
            val x = maxOf(0.0, rect.left)
            val y = maxOf(0.0, rect.top)
            val right = minOf(window.innerWidth.toDouble(), rect.right)
            val bottom = minOf(window.innerHeight.toDouble(), rect.bottom)
            if (right <= x || bottom <= y) {
                fail("Iframe is outside the visible Electron tab")
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

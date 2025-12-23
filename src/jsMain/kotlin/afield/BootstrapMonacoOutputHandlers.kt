package afield

import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoSetStatus(uid: String, good: Boolean, text: String) {
    val boxes = document.querySelectorAll(".box")
    var target: dynamic = null
    for (i in 0 until boxes.length) {
        val node = boxes.item(i)
        if (node != null && node.asDynamic().uid == uid) {
            target = node
            break
        }
    }
    if (target == null) return

    val status = target.querySelector(".statusArea") ?: return
    val tooltip = target.querySelector(".tooltiptext") ?: return

    if (good) {
        status.classList.add("status-good")
        status.classList.remove("status-error")
        status.classList.remove("flash-inverted")
        window.requestAnimationFrame {
            status.classList.add("flash-inverted")
        }
    } else {
        status.classList.remove("status-good")
        status.classList.add("status-error")
        status.classList.remove("flash-inverted")
        window.requestAnimationFrame {
            status.classList.add("flash-inverted")
        }
    }

    tooltip.innerHTML = text
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoHandleOutput(payload: dynamic, message: dynamic, uid: String): Boolean {
    val original = message.originalName as? String ?: return true
    if (original != "output") return true

    val line = (payload.line as? Number)?.toInt() ?: 0
    val html = payload.html as? String ?: ""
    val kind = payload.kind as? String ?: ""
    val annotation = payload.annotation as? String
    val frame = payload.frame as? Number
    val frameMarker = frame?.toInt() ?: 0

    val w = js("window")
    val selected = w.currentSelectedCode
    val isCurrent = selected != null &&
        (selected.length as? Number)?.toInt() == 1 &&
        selected[0].uid == uid

    when (kind) {
        "normal" -> {
            monacoSetStatus(uid, true, html)
            if (isCurrent) {
                clearErrors(w.globalEditor)
                addOutput(w.globalEditor, html, line, "normal", annotation, frameMarker)
            }
        }
        "ok" -> {
            monacoSetStatus(uid, true, html)
            if (isCurrent) {
                clearErrors(w.globalEditor)
                addOutput(w.globalEditor, html, line, "ok", annotation, frameMarker)
            }
        }
        else -> {
            if (html.isEmpty()) monacoSetStatus(uid, true, html) else monacoSetStatus(uid, false, html)
            if (isCurrent) {
                if (html.isEmpty()) {
                    clearErrors(w.globalEditor)
                } else {
                    addOutput(w.globalEditor, html, line, "error", annotation, frameMarker)
                    w.lastErrorIs = arrayOf(line - 1, html)
                }
            }
        }
    }

    return true
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoLaunchAll(uid: String, code: String) {
    val payload = js("{}")
    payload.uid = uid
    payload.type = "launch"
    payload.line = 0
    payload.code = code
    payload.singleLine = false

    sendWithReply("launch-all", payload, "exec_$uid") { message : dynamic, response: dynamic ->
        monacoHandleOutput(response, message, uid)
        Handled.yes
    }
}

package afield

import kotlinx.browser.document
import kotlinx.browser.window

private var outputAckSequence = 0

private fun shouldLogOutputAck(seq: Int): Boolean {
    val w = js("window")
    if (w.fieldOutputBackpressureDebug == false) return false
    return seq <= 20 || seq % 100 == 0
}

fun acknowledgeOutputRendered(source: String = "monaco") {
    val seq = ++outputAckSequence
    if (shouldLogOutputAck(seq)) {
        console.info("[output-ack] schedule seq=$seq source=$source")
    }
    window.setTimeout({
        if (shouldLogOutputAck(seq)) {
            console.info("[output-ack] send seq=$seq source=$source")
        }
        send(Message("output-ack"))
    }, 0)
}

private fun splitDefinedIn(candidate: String): Pair<String, String?> {
    val marker = " defined in "
    val idx = candidate.lastIndexOf(marker)
    if (idx == -1) return candidate.trim() to null
    val signature = candidate.substring(0, idx).trim()
    val definedIn = candidate.substring(idx + marker.length).trim().ifBlank { null }
    return signature to definedIn
}

private fun normalizeUidForCompare(value: String): String {
    return value.lowercase().filter { it.isLetterOrDigit() }
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun findBoxNameByUid(uid: String): String? {
    val boxes = document.querySelectorAll(".box")
    val needle = normalizeUidForCompare(uid)
    for (i in 0 until boxes.length) {
        val node = boxes.item(i) ?: continue
        val dynamicNode = node.asDynamic()
        val candidateUid = dynamicNode.uid as? String ?: continue
        if (normalizeUidForCompare(candidateUid) != needle) continue
        val directName = dynamicNode.name as? String
        if (!directName.isNullOrBlank()) return directName
        val label = dynamicNode.querySelector(".doubleClickToEdit")?.textContent as? String
        if (!label.isNullOrBlank()) return label
    }
    return null
}

private fun decodeGeneratedOwnerUid(definedInRaw: String): String? {
    if (!Regex("^[A-Za-z0-9_]+__\\d+$").matches(definedInRaw)) return null
    return definedInRaw.substringBefore("__").replace('_', '-')
}

private fun renderDefinedInHtml(definedInRaw: String, currentUid: String): String {
    val decodedUid = decodeGeneratedOwnerUid(definedInRaw) ?: return definedInRaw
    if (normalizeUidForCompare(decodedUid) == normalizeUidForCompare(currentUid)) return "this box"
    val boxName = findBoxNameByUid(decodedUid)
    return if (!boxName.isNullOrBlank()) {
        "<code>${escapeHtml(boxName)}</code>"
    } else {
        "<code>${escapeHtml(decodedUid)}</code>"
    }
}

private fun formatKotlinCompilerErrorHtml(raw: String, currentUid: String): String? {
    val normalized = raw.replace("\r\n", "\n")
    val lines = normalized.split("\n")
    if (lines.size < 2) return null

    val header = lines.first().trim()
    val overloadHeaders = listOf(
        "None of the following functions can be called with the arguments supplied",
        "Overload resolution ambiguity",
        "The following declarations are not applicable",
        "The following candidates are not applicable",
        "Cannot choose among the following candidates without completing type inference"
    )
    if (overloadHeaders.none { header.startsWith(it) }) return null

    val candidates = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    if (candidates.size < 2) return null

    val openAttr = if (candidates.size <= 6) " open" else ""
    val candidatesHtml = candidates.joinToString(separator = "") { candidate ->
        val (signature, definedIn) = splitDefinedIn(candidate)
        val definedHtml = definedIn?.let {
            " <span class=\"kotlin-error-defined\">defined in ${renderDefinedInHtml(it, currentUid)}</span>"
        } ?: ""
        "<li><code>$signature</code>$definedHtml</li>"
    }

    return """
        <div class="kotlin-error kotlin-error-overload">
          <div class="kotlin-error-title">$header</div>
          <details class="kotlin-error-details"$openAttr>
            <summary>Candidates (${candidates.size})</summary>
            <ul class="kotlin-error-candidate-list">
              $candidatesHtml
            </ul>
          </details>
        </div>
    """.trimIndent()
}

private fun formatKotlinTypeMismatchHtml(raw: String): String? {
    val prefix = "Type mismatch: inferred type is "
    if (!raw.startsWith(prefix)) return null

    val rest = raw.removePrefix(prefix)
    val mid = " but "
    val suffix = " was expected"
    val midIdx = rest.indexOf(mid)
    if (midIdx == -1) return null
    val suffixIdx = rest.indexOf(suffix, startIndex = midIdx + mid.length)
    if (suffixIdx == -1) return null

    val inferred = rest.substring(0, midIdx).trim()
    val expected = rest.substring(midIdx + mid.length, suffixIdx).trim()
    if (inferred.isEmpty() || expected.isEmpty()) return null

    val after = rest.substring(suffixIdx)
    return buildString(raw.length + 64) {
        append(prefix)
        append("<span class=\"kotlin-type\">")
        append(inferred)
        append("</span>")
        append(mid)
        append("<span class=\"kotlin-type\">")
        append(expected)
        append("</span>")
        append(after)
    }
}

private fun looksQuotedToken(value: String): Boolean {
    return (value.startsWith("'") && value.endsWith("'")) ||
        (value.startsWith("&apos;") && value.endsWith("&apos;")) ||
        (value.startsWith("&#39;") && value.endsWith("&#39;")) ||
        (value.startsWith("\"") && value.endsWith("\"")) ||
        (value.startsWith("&quot;") && value.endsWith("&quot;"))
}

private fun unquoteToken(value: String): String {
    val token = value.trim()
    return when {
        token.length >= 2 && token.startsWith("'") && token.endsWith("'") -> token.substring(1, token.length - 1)
        token.length >= 2 && token.startsWith("\"") && token.endsWith("\"") -> token.substring(1, token.length - 1)
        token.startsWith("&apos;") && token.endsWith("&apos;") && token.length > 12 -> token.substring(6, token.length - 6)
        token.startsWith("&#39;") && token.endsWith("&#39;") && token.length > 10 -> token.substring(5, token.length - 5)
        token.startsWith("&quot;") && token.endsWith("&quot;") && token.length > 12 -> token.substring(6, token.length - 6)
        else -> token
    }
}

private fun normalizeExpectedToken(token: String): String {
    val unquoted = unquoteToken(token).trim()
    return when (unquoted.lowercase()) {
        "comma" -> ","
        "semicolon" -> ";"
        "colon" -> ":"
        "dot" -> "."
        else -> unquoted
    }
}

private fun renderExpectedTokenHtml(token: String): String {
    val normalized = normalizeExpectedToken(token)
    if (normalized.isEmpty()) return ""
    val shouldRenderAsCode = normalized.length == 1 ||
        normalized.any { !it.isLetterOrDigit() && !it.isWhitespace() }
    return if (shouldRenderAsCode) {
        "<code>${escapeHtml(normalized)}</code>"
    } else {
        escapeHtml(normalized)
    }
}

private fun formatKotlinSingleLineErrorHtml(raw: String, currentUid: String): String? {
    val tooManyPrefix = "Too many arguments for "
    if (raw.startsWith(tooManyPrefix)) {
        val remainder = raw.removePrefix(tooManyPrefix).trim()
        val (signature, definedInRaw) = splitDefinedIn(remainder)
        if (signature.isNotBlank()) {
            val definedInHtml = definedInRaw?.let {
                " <span class=\"kotlin-error-defined\">defined in ${renderDefinedInHtml(it, currentUid)}</span>"
            } ?: ""
            return "<div class=\"kotlin-error kotlin-error-inline\">$tooManyPrefix<code>$signature</code>$definedInHtml</div>"
        }
    }

    val noValuePrefix = "No value passed for parameter "
    if (raw.startsWith(noValuePrefix)) {
        val parameter = raw.removePrefix(noValuePrefix).trim()
        if (parameter.isNotEmpty()) {
            return "<div class=\"kotlin-error kotlin-error-inline\">$noValuePrefix<code>$parameter</code></div>"
        }
    }

    val literalConformance = Regex("^The (integer|floating-point) literal does not conform to the expected type (.+)$")
        .matchEntire(raw)
    if (literalConformance != null) {
        val literalKind = literalConformance.groupValues[1]
        val expectedType = literalConformance.groupValues[2].trim()
        if (expectedType.isNotEmpty()) {
            return "<div class=\"kotlin-error kotlin-error-inline\">The $literalKind literal does not conform to the expected type <span class=\"kotlin-type\">$expectedType</span></div>"
        }
    }

    val expectingPrefix = "Expecting "
    if (raw.startsWith(expectingPrefix)) {
        val expected = raw.removePrefix(expectingPrefix).trim()
        val expectingAlternatives = Regex("^(an?\\s+)?(.+?)\\s+or\\s+(.+)$").matchEntire(expected)
        if (expectingAlternatives != null) {
            val article = expectingAlternatives.groupValues[1]
            val first = expectingAlternatives.groupValues[2].trim()
            val second = expectingAlternatives.groupValues[3].trim()
            if (first.isNotEmpty() && second.isNotEmpty()) {
                val firstHtml = renderExpectedTokenHtml(first)
                val secondHtml = renderExpectedTokenHtml(second)
                val articleHtml = if (firstHtml.startsWith("<code>")) "" else article
                return "<div class=\"kotlin-error kotlin-error-inline\">$expectingPrefix$articleHtml$firstHtml or $secondHtml</div>"
            }
        }
        if (expected.isNotEmpty() && looksQuotedToken(expected)) {
            return "<div class=\"kotlin-error kotlin-error-inline\">$expectingPrefix${renderExpectedTokenHtml(expected)}</div>"
        }
    }

    return null
}

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

fun routeMonacoOutput(payload: Output, uidOverride: String = ""): Boolean {
    val uid = uidOverride.ifBlank { payload.uid }
    if (uid.isBlank()) return true

    val line = payload.line
    val html = payload.html
    val kind = payload.kind
    val annotation = payload.annotation
    val frameMarker = payload.frame
    val startColumn = payload.startColumn
    val endLine = payload.endLine
    val endColumn = payload.endColumn
    val property = payload.property
    val w = js("window")
    val selected = w.currentSelectedCode
    val isCurrent = selected != null &&
        (selected.length as? Number)?.toInt() == 1 &&
        selected[0].uid == uid

    // Check for an embedded editor matching this (uid, property)
    val embeddedEditor: dynamic = if (w.embeddedEditors != null && w.embeddedEditors != undefined)
        w.embeddedEditors.getFor(uid, property) else null

    // When an embedded editor exists for (uid, property), route output
    // exclusively to it — not to the main editor.
    val routeToMain = isCurrent && embeddedEditor == null

    when (kind) {
        "normal" -> {
            monacoSetStatus(uid, true, html)
            if (routeToMain) {
                clearErrors(w.globalEditor)
                addOutput(w.globalEditor, html, line, "normal", annotation, frameMarker, -1, -1, -1)
            }
            if (embeddedEditor != null) {
                clearErrors(embeddedEditor.editor)
                addOutput(embeddedEditor.editor, html, line, "normal", annotation, frameMarker, -1, -1, -1)
            }
        }
        "ok" -> {
            monacoSetStatus(uid, true, html)
            if (routeToMain) {
                clearErrors(w.globalEditor)
                addOutput(w.globalEditor, html, line, "ok", annotation, frameMarker, -1, -1, -1)
            }
            if (embeddedEditor != null) {
                clearErrors(embeddedEditor.editor)
                addOutput(embeddedEditor.editor, html, line, "ok", annotation, frameMarker, -1, -1, -1)
            }
        }
        else -> {
            if (html.isEmpty()) monacoSetStatus(uid, true, html) else monacoSetStatus(uid, false, html)
            if (routeToMain) {
                if (html.isEmpty()) {
                    clearErrors(w.globalEditor)
                } else {
                    val formatted = formatKotlinCompilerErrorHtml(html, uid)
                        ?: formatKotlinTypeMismatchHtml(html)
                        ?: formatKotlinSingleLineErrorHtml(html, uid)
                        ?: html
                    addOutput(w.globalEditor, formatted, line, "error", annotation, frameMarker, startColumn, endLine, endColumn)
                    w.lastErrorIs = arrayOf(line - 1, html)
                }
            }
            if (embeddedEditor != null) {
                if (html.isEmpty()) {
                    clearErrors(embeddedEditor.editor)
                } else {
                    val formatted = formatKotlinCompilerErrorHtml(html, uid)
                        ?: formatKotlinTypeMismatchHtml(html)
                        ?: formatKotlinSingleLineErrorHtml(html, uid)
                        ?: html
                    addOutput(embeddedEditor.editor, formatted, line, "error", annotation, frameMarker, startColumn, endLine, endColumn)
                }
            }
        }
    }

    return true
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoHandleOutput(payload: dynamic, message: dynamic, uid: String): Boolean {
    val original = message.originalName as? String ?: return true
    if (original != "output") return true

    val handled = routeMonacoOutput(
        Output(
            uid = uid,
            line = (payload.line as? Number)?.toInt() ?: 0,
            html = payload.html as? String ?: "",
            kind = payload.kind as? String ?: "",
            property = payload.property as? String ?: "code",
            annotation = payload.annotation as? String ?: "",
            frame = (payload.frame as? Number)?.toInt() ?: 0,
            startColumn = (payload.startColumn as? Number)?.toInt() ?: -1,
            endLine = (payload.endLine as? Number)?.toInt() ?: -1,
            endColumn = (payload.endColumn as? Number)?.toInt() ?: -1
        ),
        uid
    )
    acknowledgeOutputRendered("callback-output")
    return handled
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

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoLaunchBatch(items: Array<dynamic>) {
    if (items.isEmpty()) return

    val payload = js("{}")
    payload.executions = items
    val callbackName = "exec_batch_" + js("Date.now()").toString()

    sendWithReply("launch-batch", payload, callbackName) { message: dynamic, response: dynamic ->
        val outputUid = response.uid as? String ?: message.uid as? String ?: ""
        monacoHandleOutput(response, message, outputUid)
        Handled.yes
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoContinueAll(uid: String, code: String) {
    val payload = js("{}")
    payload.uid = uid
    payload.type = "continue"
    payload.line = 0
    payload.code = code
    payload.singleLine = false

    sendWithReply("continue-all", payload, "exec_$uid") { message: dynamic, response: dynamic ->
        monacoHandleOutput(response, message, uid)
        Handled.yes
    }
}

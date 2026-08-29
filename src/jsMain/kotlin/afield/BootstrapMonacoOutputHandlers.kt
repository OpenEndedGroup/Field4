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
    var sent = false
    var fallback = 0
    val sendAck = sendAck@{
        if (sent) return@sendAck
        sent = true
        window.clearTimeout(fallback)
        if (shouldLogOutputAck(seq)) {
            console.info("[output-ack] send seq=$seq source=$source")
        }
        send(Message("output-ack"))
    }

    // The server uses this ACK as a capture/back-pressure barrier. A zero-delay timer only means
    // that the handler returned; Monaco view-zones and SVG output may not have crossed a layout and
    // paint boundary yet. Two RAFs make "rendered" true for an active page. Keep a bounded timer so
    // a background/hidden tab (where RAF may be throttled indefinitely) cannot hold server output.
    fallback = window.setTimeout({ sendAck() }, 250)
    window.requestAnimationFrame {
        window.requestAnimationFrame {
            sendAck()
        }
    }
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

private val generatedFieldSourceRegex = Regex("""\b([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})__([A-Za-z0-9_]+)_${'$'}\d+(?:\.(?:kts?|java))?(?::(\d+))?""")

private fun formatGeneratedFieldSourceLinksHtml(raw: String): String? {
    var changed = false
    val formatted = generatedFieldSourceRegex.replace(raw) { match ->
        val uid = match.groupValues[1]
        val property = match.groupValues[2].ifBlank { "code" }
        val line = match.groupValues.getOrNull(3)?.toIntOrNull()
        val boxName = findBoxNameByUid(uid)?.takeIf { it.isNotBlank() } ?: uid.take(8)
        val escapedUid = escapeHtml(uid)
        val escapedProperty = escapeHtml(property)
        val label = buildString {
            append(boxName)
            append(":")
            append(property)
            if (line != null) append(":").append(line)
        }
        val href = buildString {
            append("#box:")
            append(escapedUid)
            append("?property=")
            append(escapedProperty)
            if (line != null) append("&line=").append(line)
        }
        changed = true
        "<a class=\"field-error-box-link\" href=\"$href\" data-box-uid=\"$escapedUid\" data-box-property=\"$escapedProperty\"${line?.let { " data-box-scroll-to-line=\"$it\" data-box-highlight-line=\"$it\"" } ?: ""} title=\"${escapeHtml(match.value)}\">${escapeHtml(label)}</a>"
    }
    return if (changed) formatted else null
}

private fun formatErrorOutputHtml(html: String, uid: String): String {
    return formatKotlinCompilerErrorHtml(html, uid)
        ?: formatKotlinTypeMismatchHtml(html)
        ?: formatKotlinSingleLineErrorHtml(html, uid)
        ?: formatGeneratedFieldSourceLinksHtml(html)
        ?: html
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

private fun stackLocationsFor(payload: Output, uid: String, property: String): List<OutputStackLocation> {
    return payload.stack.filter {
        it.uid == uid && it.property == property && !it.primary
    }
}

private fun stackSummaryHtml(locations: List<OutputStackLocation>): String {
    if (locations.isEmpty()) return ""
    val summary = locations.take(4).joinToString("<br>") {
        val method = if (it.method.isNotBlank()) " <code>${escapeHtml(it.method)}</code>" else ""
        "<code>${escapeHtml(it.property)}:${it.line}</code>$method"
    }
    val more = if (locations.size > 4) "<br>... ${locations.size - 4} more" else ""
    return "Stack frame:<br>$summary$more"
}

private fun markStackBoxes(payload: Output, primaryUid: String, primaryHtml: String) {
    val byBox = payload.stack
        .filter { !it.primary }
        .groupBy { it.uid }
    byBox.forEach { (uid, locations) ->
        val stackHtml = stackSummaryHtml(locations)
        if (stackHtml.isNotBlank()) {
            val html = if (uid == primaryUid && primaryHtml.isNotBlank()) {
                "$primaryHtml<br><br>$stackHtml"
            } else {
                stackHtml
            }
            monacoSetStatus(uid, false, html)
        }
    }
}

private fun markOpenStackEditors(payload: Output) {
    val w = js("window")
    val grouped = payload.stack
        .filter { !it.primary }
        .groupBy { "${it.uid}::${it.property}" }
    grouped.values.forEach { locations ->
        val first = locations.firstOrNull() ?: return@forEach
        val embeddedEditor: dynamic = if (w.embeddedEditors != null && w.embeddedEditors != undefined)
            w.embeddedEditors.getFor(first.uid, first.property) else null
        if (embeddedEditor != null) {
            setStackTraceHints(embeddedEditor.editor, locations)
            return@forEach
        }

        val selected = w.currentSelectedCode
        val selectedUid = if (selected != null && (selected.length as? Number)?.toInt() == 1) {
            selected[0].uid as? String
        } else null
        val selectedProperty = w.currentSelectedProperty as? String ?: "code"
        if (selectedUid == first.uid && selectedProperty == first.property) {
            setStackTraceHints(w.globalEditor, locations)
        }
    }
}

private fun dynamicOutputStack(raw: dynamic): List<OutputStackLocation> {
    val stack = raw?.stack ?: return emptyList()
    val length = (stack.length as? Number)?.toInt() ?: return emptyList()
    val out = mutableListOf<OutputStackLocation>()
    for (i in 0 until length) {
        val item = stack[i] ?: continue
        val uid = item.uid as? String ?: continue
        out += OutputStackLocation(
            uid = uid,
            property = item.property as? String ?: "code",
            line = (item.line as? Number)?.toInt() ?: continue,
            method = item.method as? String ?: "",
            primary = item.primary as? Boolean ?: false
        )
    }
    return out
}

private fun dynamicOutputSuggestions(raw: dynamic): List<OutputSuggestion> {
    val suggestions = raw?.suggestions ?: return emptyList()
    val length = (suggestions.length as? Number)?.toInt() ?: return emptyList()
    val out = mutableListOf<OutputSuggestion>()
    for (i in 0 until length) {
        val item = suggestions[i] ?: continue
        val kind = item.kind as? String ?: continue
        out += OutputSuggestion(
            kind = kind,
            label = item.label as? String ?: "",
            detail = item.detail as? String ?: "",
            token = item.token as? String ?: "",
            replacement = item.replacement as? String ?: "",
            importFqn = item.importFqn as? String ?: "",
            line = (item.line as? Number)?.toInt() ?: -1,
            column = (item.column as? Number)?.toInt() ?: -1,
            aiInstruction = item.aiInstruction as? String ?: ""
        )
    }
    return out
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
    // A run that reported an error and still finished sets this, so its own success does not wipe the
    // diagnostic it just placed. See Output.preserveErrors.
    val preserveErrors = payload.preserveErrors
    val w = js("window")
    if (w.LineHeatmap != null && w.LineHeatmap != undefined) {
        w.LineHeatmap.recordExecution(payload)
    }
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
                clearStackTraceHints(w.globalEditor)
                addOutput(w.globalEditor, html, line, "normal", annotation, frameMarker, -1, -1, -1)
            }
            if (embeddedEditor != null) {
                clearErrors(embeddedEditor.editor)
                clearStackTraceHints(embeddedEditor.editor)
                addOutput(embeddedEditor.editor, html, line, "normal", annotation, frameMarker, -1, -1, -1)
            }
        }
        "ok" -> {
            monacoSetStatus(uid, true, html)
            if (routeToMain) {
                if (!preserveErrors) {
                    clearErrors(w.globalEditor)
                    clearStackTraceHints(w.globalEditor)
                }
                addOutput(w.globalEditor, html, line, "ok", annotation, frameMarker, -1, -1, -1)
            }
            if (embeddedEditor != null) {
                if (!preserveErrors) {
                    clearErrors(embeddedEditor.editor)
                    clearStackTraceHints(embeddedEditor.editor)
                }
                addOutput(embeddedEditor.editor, html, line, "ok", annotation, frameMarker, -1, -1, -1)
            }
        }
        else -> {
            if (html.isEmpty()) monacoSetStatus(uid, true, html) else monacoSetStatus(uid, false, html)
            if (routeToMain) {
                if (html.isEmpty()) {
                    clearErrors(w.globalEditor)
                } else {
                    val formatted = formatErrorOutputHtml(html, uid)
                    addOutput(w.globalEditor, formatted, line, "error", annotation, frameMarker, startColumn, endLine, endColumn, payload.suggestions, uid, property)
                    setStackTraceHints(w.globalEditor, stackLocationsFor(payload, uid, property))
                    w.lastErrorIs = arrayOf(line - 1, html)
                }
            }
            if (embeddedEditor != null) {
                if (html.isEmpty()) {
                    clearErrors(embeddedEditor.editor)
                } else {
                    val formatted = formatErrorOutputHtml(html, uid)
                    addOutput(embeddedEditor.editor, formatted, line, "error", annotation, frameMarker, startColumn, endLine, endColumn, payload.suggestions, uid, property)
                    setStackTraceHints(embeddedEditor.editor, stackLocationsFor(payload, uid, property))
                }
            }
            if (html.isNotEmpty()) {
                markStackBoxes(payload, uid, html)
                markOpenStackEditors(payload)
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
            endColumn = (payload.endColumn as? Number)?.toInt() ?: -1,
            stack = dynamicOutputStack(payload),
            suggestions = dynamicOutputSuggestions(payload)
        ),
        uid
    )
    acknowledgeOutputRendered("callback-output")
    return handled
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoLaunchAll(uid: String, code: String) {
    val property = js("window").currentSelectedProperty as? String ?: "code"
    val payload = js("{}")
    payload.uid = uid
    payload.type = "launch"
    payload.line = 0
    payload.code = code
    payload.singleLine = false
    payload.property = property

    clearEditorOutputsForBoxBeforeLaunch(uid)
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

    val cleared = mutableSetOf<String>()
    items.forEach { item ->
        val uid = item.uid as? String ?: return@forEach
        if (cleared.add(uid)) clearEditorOutputsForBoxBeforeLaunch(uid)
    }
    sendWithReply("launch-batch", payload, callbackName) { message: dynamic, response: dynamic ->
        val outputUid = response.uid as? String ?: message.uid as? String ?: ""
        monacoHandleOutput(response, message, outputUid)
        Handled.yes
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoContinueAll(uid: String, code: String) {
    val property = js("window").currentSelectedProperty as? String ?: "code"
    val payload = js("{}")
    payload.uid = uid
    payload.type = "continue"
    payload.line = 0
    payload.code = code
    payload.singleLine = false
    payload.property = property

    sendWithReply("continue-all", payload, "exec_$uid") { message: dynamic, response: dynamic ->
        monacoHandleOutput(response, message, uid)
        Handled.yes
    }
}

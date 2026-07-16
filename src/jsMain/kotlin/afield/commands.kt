package afield

import handleOutputAreaRequest
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import openEditorInOutput
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import web.url.URL
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.abs

var lastMouse = 0.0 to 0.0

external fun showPickMenu_custom(options: dynamic): dynamic

private fun browserPrompt(message: String, defaultValue: String): String? =
    window.asDynamic().prompt(message, defaultValue)

private fun defaultMp4Path(kind: String): String {
    val stamp = Date.now().toLong().toString()
    return "/tmp/field4-$kind-$stamp.mp4"
}

private fun promptMp4Target(kind: String): Pair<String, Double>? {
    val path = browserPrompt("Output .mp4 path for $kind capture", defaultMp4Path(kind))?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return null

    val durationText = browserPrompt("Duration in seconds", "3")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val duration = durationText.toDoubleOrNull()
    if (duration == null || duration <= 0.0) {
        notify("Duration must be a positive number", "error")
        return null
    }

    return path to duration
}

private fun requireSingleSelectedBox(): dynamic? {
    if (selected.size != 1) {
        notify("Select exactly one box", "command")
        return null
    }
    return selected.first().unsafeCast<dynamic>()
}

private fun sendBoxVideo(request: AgentBoxVideo) {
    val callbackName = "agent-box-video-${Date.now().toLong()}"
    notify("Recording box MP4...", "command")
    sendWithReply(Message("agent-box-video").payload(request), callbackName) { message, _ ->
        val response = Json.decodeFromJsonElement<AgentVideoResponse>(message.payload!!)
        if (response.ok) {
            notify("Saved MP4 to ${response.path}", "command")
        } else {
            notify(response.message.ifEmpty { "MP4 recording failed" }, "error")
        }
        Handled.yes_remove
    }
}

private fun sendWindowVideo(request: AgentWindowVideo) {
    val callbackName = "agent-window-video-${Date.now().toLong()}"
    notify("Recording window MP4...", "command")
    sendWithReply(Message("agent-window-video").payload(request), callbackName) { message, _ ->
        val response = Json.decodeFromJsonElement<AgentVideoResponse>(message.payload!!)
        if (response.ok) {
            notify("Saved MP4 to ${response.path}", "command")
        } else {
            notify(response.message.ifEmpty { "MP4 recording failed" }, "error")
        }
        Handled.yes_remove
    }
}

private var macroPanelElement: HTMLElement? = null
private var macroPanelLastState: MacroTranscriptCommandResponse? = null
private var macroPanelRefreshTimer: Int? = null
private var macroPanelContentDirty = false
private var macroTranscriptOffset = 0
private var macroTranscriptFollowTail = true
private var macroTranscriptLastTotal = 0
private var macroTranscriptHideWaits = false
private var macroTranscriptSelectionAnchor: Int? = null
private var macroTranscriptRenderedIndices = emptySet<Int>()
private const val MACRO_TRANSCRIPT_PAGE_SIZE = 80
private val macroTranscriptSelected = mutableSetOf<Int>()
private val macroTranscriptOpenDetails = mutableSetOf<Int>()
private val macroTranscriptYamlScrollTop = mutableMapOf<Int, Double>()

private data class MacroPreviewOperation(
    val verb: String,
    val fields: Map<String, String>
)

private fun sendMacroTranscriptCommand(
    request: MacroTranscriptCommandRequest,
    announce: Boolean = true,
    after: ((MacroTranscriptCommandResponse) -> Unit)? = null
) {
    val callbackName = "macro-transcript-${Date.now().toLong()}"
    sendWithReply(Message("macro-transcript-command").payload(request), callbackName) { message, _ ->
        val response = Json.decodeFromJsonElement<MacroTranscriptCommandResponse>(message.payload!!)
        if (announce) notify(response.message, if (response.success) "macro" else "error")
        after?.invoke(response)
        Handled.yes_remove
    }
}

private fun htmlEscape(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun macroYamlQuote(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun macroContentWithName(content: String, name: String): String {
    if (content.isBlank()) return content
    val lines = content.lines().toMutableList()
    val firstName = lines.indexOfFirst { it.trimStart().startsWith("name:") }
    if (firstName >= 0) {
        lines[firstName] = "name: ${macroYamlQuote(name)}"
    } else {
        lines.add(0, "name: ${macroYamlQuote(name)}")
    }
    return lines.joinToString("\n").trimEnd() + "\n"
}

private fun macroYamlUnquote(text: String): String {
    val trimmed = text.trim()
    if (trimmed == "\"\"") return ""
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    return trimmed
}

private fun parseMacroPreview(content: String): List<MacroPreviewOperation> {
    val operations = mutableListOf<MacroPreviewOperation>()
    var verb: String? = null
    var fields = linkedMapOf<String, String>()
    var blockKey: String? = null
    var blockIndent = 0
    var nestedKey: String? = null
    var nestedIndent = 0
    val opRegex = Regex("^\\s*-\\s+([A-Za-z0-9_.-]+):\\s*$")
    val fieldRegex = Regex("^\\s{4,}([A-Za-z0-9_.-]+):(?:\\s*(.*))?$")

    fun finishOperation() {
        val currentVerb = verb ?: return
        operations += MacroPreviewOperation(currentVerb, fields.toMap())
        verb = null
        fields = linkedMapOf()
        blockKey = null
        nestedKey = null
    }

    content.lines().forEach { line ->
        val leading = line.takeWhile { it == ' ' }.length
        val activeBlock = blockKey
        if (activeBlock != null && leading >= blockIndent) {
            val old = fields[activeBlock].orEmpty()
            fields[activeBlock] = if (old.isEmpty()) line.drop(blockIndent) else old + "\n" + line.drop(blockIndent)
            return@forEach
        } else {
            blockKey = null
        }

        val activeNested = nestedKey
        if (activeNested != null && leading >= nestedIndent) {
            val nestedMatch = fieldRegex.matchEntire(line)
            if (nestedMatch != null) {
                val key = nestedMatch.groupValues[1]
                val raw = nestedMatch.groupValues.getOrNull(2).orEmpty()
                fields["$activeNested.$key"] = macroYamlUnquote(raw)
            }
            return@forEach
        } else {
            nestedKey = null
        }

        val opMatch = opRegex.matchEntire(line)
        if (opMatch != null) {
            finishOperation()
            verb = opMatch.groupValues[1]
            fields = linkedMapOf()
            nestedKey = null
            return@forEach
        }

        if (verb != null) {
            val fieldMatch = fieldRegex.matchEntire(line) ?: return@forEach
            val key = fieldMatch.groupValues[1]
            val raw = fieldMatch.groupValues.getOrNull(2).orEmpty()
            if (raw.trim() == "|") {
                fields[key] = ""
                blockKey = key
                blockIndent = leading + 2
            } else if (raw.isBlank()) {
                fields[key] = ""
                nestedKey = key
                nestedIndent = leading + 2
            } else {
                fields[key] = macroYamlUnquote(raw)
            }
        }
    }
    finishOperation()
    return operations
}

private fun macroBoxDisplayName(uid: String): String {
    val box = findBoxByUid(uid)
    val dynamicName = box?.asDynamic()?.name as? String
    if (!dynamicName.isNullOrBlank()) return dynamicName
    val inlineTitle = box?.getElementsByClassName("doubleClickToEdit")?.asList()?.firstOrNull()?.textContent
    if (!inlineTitle.isNullOrBlank()) return inlineTitle
    return "box"
}

private fun macroBoxLinkHtml(uid: String): String {
    if (uid.isBlank()) return ""
    val label = macroBoxDisplayName(uid)
    val escapedUid = htmlEscape(uid)
    val escapedLabel = htmlEscape(label)
    return "<a class=\"macro-recorder-box-link\" href=\"#box:$escapedUid\" data-box-uid=\"$escapedUid\" title=\"Select $escapedLabel\"><span class=\"macro-recorder-box-symbol\">□</span><span class=\"macro-recorder-box-name\">$escapedLabel</span></a>"
}

private fun macroTemplateHtml(text: String): String =
    "<span class=\"macro-recorder-template\">${htmlEscape(text)}</span>"

private fun macroVariableHtml(text: String): String =
    "<span class=\"macro-recorder-variable\">${htmlEscape(text)}</span>"

private fun macroNumberHtml(text: String): String =
    "<span class=\"macro-recorder-number\">${htmlEscape(text)}</span>"

private fun macroNotationHtml(text: String): String =
    "<span class=\"macro-recorder-notation\">${htmlEscape(text)}</span>"

private fun macroPropertyHtml(property: String): String =
    if (property.isBlank()) "" else macroVariableHtml(property)

private fun macroValueHtml(value: String): String {
    val compact = value
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifBlank { "empty" }
    val trimmed = if (compact.length > 48) compact.take(45).trimEnd() + "..." else compact
    return macroVariableHtml(trimmed)
}

private fun macroToClauseHtml(value: String, present: Boolean): String =
    if (!present) "" else " ${macroTemplateHtml("to")} ${macroValueHtml(value)}"

private fun macroPlural(countText: String, singular: String, plural: String = "${singular}s"): String =
    if (countText.toLongOrNull() == 1L) singular else plural

private fun macroInlineTokenHtml(text: String): String {
    if (text.isBlank()) return ""
    val out = StringBuilder()
    var cursor = 0
    while (cursor < text.length) {
        val start = cursor
        val isNumber = text[cursor].isDigit()
        if (isNumber) {
            while (cursor < text.length && text[cursor].isDigit()) cursor++
            out.append(macroNumberHtml(text.substring(start, cursor)))
        } else {
            while (cursor < text.length && !text[cursor].isDigit()) cursor++
            out.append(macroNotationHtml(text.substring(start, cursor)))
        }
    }
    return out.toString()
}

private fun macroTranscriptSummaryHtml(entry: TranscriptEntry): String {
    val box = macroBoxLinkHtml(entry.box)
    val prop = macroPropertyHtml(entry.property)
    return when (entry.verb.lowercase()) {
        "edit", "set", "set-string" -> buildString {
            append(macroTemplateHtml("edit"))
            if (prop.isNotBlank()) append(" ").append(prop)
            if (box.isNotBlank()) append(" ").append(macroTemplateHtml("of")).append(" ").append(box)
            append(macroToClauseHtml(entry.to, present = true))
        }
        "move", "frame", "set-rect" -> if (box.isBlank()) macroTemplateHtml("move") else "${macroTemplateHtml("move")} $box"
        "execute", "execute-fragment" -> buildString {
            append(macroTemplateHtml("execute"))
            val type = entry.summary.substringAfter("execute", "").trim().substringBefore(" ").trim()
            if (type.isNotBlank() && type != entry.summary) append(" ").append(macroVariableHtml(type))
            if (prop.isNotBlank()) append(" ").append(prop)
            if (box.isNotBlank()) append(" ").append(macroTemplateHtml("of")).append(" ").append(box)
        }
        "launch", "launch-all", "launch-fragment" -> buildString {
            append(macroTemplateHtml("launch"))
            if (prop.isNotBlank()) append(" ").append(prop)
            if (box.isNotBlank()) append(" ").append(macroTemplateHtml("of")).append(" ").append(box)
        }
        "stop", "stop-all", "stop-box" -> if (box.isBlank()) macroTemplateHtml("stop") else "${macroTemplateHtml("stop")} $box"
        "connect" -> if (box.isBlank()) macroTemplateHtml("connect") else "${macroTemplateHtml("connect")} $box"
        "disconnect" -> if (box.isBlank()) macroTemplateHtml("disconnect") else "${macroTemplateHtml("disconnect")} $box"
        "wait" -> {
            val frames = entry.frame.ifBlank { entry.summary.substringAfter("wait", "").substringBefore("frame").trim() }.ifBlank { "?" }
            "${macroTemplateHtml("wait")} ${macroNumberHtml(frames)} ${macroTemplateHtml(macroPlural(frames, "frame"))}"
        }
        else -> htmlEscape(entry.summary)
    }
}

private fun macroOperationSummary(index: Int, op: MacroPreviewOperation): String {
    val uid = op.fields["box"] ?: op.fields["uid"] ?: op.fields["target"] ?: ""
    val box = macroBoxLinkHtml(uid)
    val property = op.fields["property"] ?: op.fields["prop"] ?: ""
    if (op.verb == "move") {
        return "${macroNumberHtml((index + 1).toString())}${macroNotationHtml(".")} ${compactMoveSummary(op, box)}"
    }
    if (op.verb.lowercase() in setOf("edit", "set", "set-string")) {
        val target = if (box.isBlank()) "" else " ${macroTemplateHtml("of")} $box"
        val prop = macroPropertyHtml(property).let { if (it.isBlank()) "" else " $it" }
        return "${macroNumberHtml((index + 1).toString())}${macroNotationHtml(".")} ${macroTemplateHtml(op.verb)}$prop$target${macroToClauseHtml(op.fields["to"].orEmpty(), present = op.fields.containsKey("to"))}"
    }
    if (op.verb.lowercase() in setOf("execute", "execute-fragment", "launch", "launch-all", "launch-fragment")) {
        val target = if (box.isBlank()) "" else " ${macroTemplateHtml("of")} $box"
        val prop = macroPropertyHtml(property).let { if (it.isBlank()) "" else " $it" }
        val type = op.fields["type"].orEmpty().let { if (it.isBlank() || it == "launch" || it == property) "" else " ${macroVariableHtml(it)}" }
        return "${macroNumberHtml((index + 1).toString())}${macroNotationHtml(".")} ${macroTemplateHtml(op.verb.substringBefore("-"))}$type$prop$target"
    }
    if (op.verb.lowercase() in setOf("stop", "stop-all", "stop-box")) {
        val target = if (box.isBlank()) "" else " $box"
        return "${macroNumberHtml((index + 1).toString())}${macroNotationHtml(".")} ${macroTemplateHtml("stop")}$target"
    }
    val target = if (box.isBlank()) "" else " ${macroTemplateHtml("in")} $box"
    val prop = macroPropertyHtml(property).let { if (it.isBlank()) "" else " $it" }
    return "${macroNumberHtml((index + 1).toString())}${macroNotationHtml(".")} ${macroTemplateHtml(op.verb)}$prop$target"
}

private fun compactMoveSummary(op: MacroPreviewOperation, box: String): String {
    val from = rectFromFields(op.fields, "from") ?: return if (box.isBlank()) macroTemplateHtml("move") else "${macroTemplateHtml("move")} $box"
    val to = rectFromFields(op.fields, "to") ?: return if (box.isBlank()) macroTemplateHtml("move") else "${macroTemplateHtml("move")} $box"
    val target = if (box.isBlank()) "" else " $box"
    return "${macroTemplateHtml("move")}$target ${rectCompactHtml(from)} ${macroNotationHtml("->")} ${rectCompactHtml(to, omitSizeIfSameAs = from)}"
}

private data class MacroRect(val x: String, val y: String, val w: String, val h: String)

private fun rectFromFields(fields: Map<String, String>, prefix: String): MacroRect? {
    val x = fields["$prefix.x"] ?: return null
    val y = fields["$prefix.y"] ?: return null
    val w = fields["$prefix.w"] ?: return null
    val h = fields["$prefix.h"] ?: return null
    return MacroRect(x, y, w, h)
}

private fun rectCompactHtml(rect: MacroRect, omitSizeIfSameAs: MacroRect? = null): String {
    val x = compactNumber(rect.x)
    val y = compactNumber(rect.y)
    val w = compactNumber(rect.w)
    val h = compactNumber(rect.h)
    val sameSize = omitSizeIfSameAs != null &&
        w == compactNumber(omitSizeIfSameAs.w) &&
        h == compactNumber(omitSizeIfSameAs.h)
    val xy = "${macroNumberHtml(x)}${macroNotationHtml(",")}${macroNumberHtml(y)}"
    if (sameSize) return xy
    return "$xy${macroNotationHtml("+")}${macroNumberHtml(w)}${macroNotationHtml("x")}${macroNumberHtml(h)}"
}

private fun compactNumber(value: String): String {
    val numeric = value.toDoubleOrNull() ?: return value
    val rounded = kotlin.math.round(numeric)
    return if (kotlin.math.abs(numeric - rounded) < 0.0001) rounded.toInt().toString() else {
        val text = numeric.toString()
        if (text.length > 8) text.take(8).trimEnd('.') else text
    }
}

private fun macroDetailsHtml(label: String, value: String, open: Boolean = false): String {
    val openAttr = if (open) " open" else ""
    val body = if (value.isBlank()) "<span class=\"macro-recorder-empty\">empty</span>" else "<pre>${htmlEscape(value)}</pre>"
    return "<details class=\"macro-recorder-detail\"$openAttr><summary>${htmlEscape(label)}</summary>$body</details>"
}

private fun macroPanelSetFeedback(text: String, kind: String = "ok") {
    val feedback = macroPanelQuery("[data-role='feedback']") ?: return
    feedback.classList.remove("macro-recorder-feedback-pulse")
    feedback.classList.remove("has-errors")
    feedback.innerHTML = "<div class=\"macro-recorder-feedback-$kind\">${htmlEscape(text)}</div>"
    feedback.asDynamic().offsetWidth
    feedback.classList.add("macro-recorder-feedback-pulse")
}

private fun renderMacroPreview(content: String) {
    val preview = macroPanelQuery("[data-role='preview']") ?: return
    val operations = parseMacroPreview(content)
    if (operations.isEmpty()) {
        preview.innerHTML = "<div class=\"macro-recorder-empty\">No operations recognized yet.</div>"
        return
    }
    preview.innerHTML = operations.mapIndexed { index, op ->
        val priority = listOf("from", "to", "value", "text", "frame")
        val hiddenKeys = setOf("box", "uid", "target", "property", "prop") +
            if (op.verb == "move") setOf("from", "to", "from.x", "from.y", "from.w", "from.h", "to.x", "to.y", "to.w", "to.h") else emptySet()
        val detailKeys = (priority + op.fields.keys.filterNot { it in priority || it in hiddenKeys })
            .distinct()
            .filter { op.fields.containsKey(it) }
        val details = detailKeys.joinToString("") { key ->
            macroDetailsHtml(key, op.fields[key].orEmpty(), open = key == "to")
        }
        """
        <div class="macro-recorder-op">
            <div class="macro-recorder-op-head">${macroOperationSummary(index, op)}</div>
            <div class="macro-recorder-op-details">$details</div>
        </div>
        """.trimIndent()
    }.joinToString("")
}

private fun renderMacroTranscriptPage(response: MacroTranscriptCommandResponse) {
    macroPanelCaptureTranscriptDetailState()
    val page = macroPanelQuery("[data-role='transcript-page']")
    val list = macroPanelQuery("[data-role='transcript-list']") ?: return
    val wasAtBottom = macroTranscriptFollowTail || macroPanelTranscriptIsAtBottom(list)
    val previousIndices = macroTranscriptRenderedIndices
    val previousMax = previousIndices.maxOrNull() ?: -1
    val start = if (response.entries.isEmpty()) 0 else response.offset + 1
    val end = response.offset + response.entries.size
    page?.innerHTML = "${macroTemplateHtml("entries")} ${macroNumberHtml(start.toString())}${macroNotationHtml("-")}${macroNumberHtml(end.toString())} ${macroTemplateHtml("of")} ${macroNumberHtml(response.total.toString())}"
    macroTranscriptLastTotal = response.total

    if (response.entries.isEmpty()) {
        list.innerHTML = "<div class=\"macro-recorder-empty\">No transcript entries yet.</div>"
        macroTranscriptRenderedIndices = emptySet()
        macroPanelSyncControls()
        return
    }

    list.innerHTML = response.entries.joinToString("") { entry ->
        val checked = if (macroTranscriptSelected.contains(entry.index)) " checked" else ""
        val disabled = if (entry.selectable) "" else " disabled"
        val open = if (macroTranscriptOpenDetails.contains(entry.index)) " open" else ""
        val arriving = previousIndices.isNotEmpty() && wasAtBottom && entry.index !in previousIndices && entry.index > previousMax
        val rowClasses = buildList {
            add("macro-recorder-transcript-row")
            if (!entry.selectable) add("is-muted")
            if (arriving) add("is-new-arrival")
        }.joinToString(" ")
        val isWait = entry.verb.equals("wait", ignoreCase = true)
        val meta = buildList {
            if (!isWait && entry.frame.isNotBlank()) add("${macroTemplateHtml("frame")} ${macroNumberHtml(entry.frame)}")
            if (entry.at.isNotBlank()) add(macroInlineTokenHtml(entry.at))
        }.joinToString(" ${macroNotationHtml("·")} ")
        val metaHtml = if (meta.isBlank()) "" else "<span class=\"macro-recorder-transcript-meta\">$meta</span>"
        """
        <div class="$rowClasses">
            <label>
                <input type="checkbox" data-transcript-index="${entry.index}"$checked$disabled>
                <span class="macro-recorder-transcript-index">${macroNotationHtml("#")}${macroNumberHtml(entry.index.toString())}</span>
                <span class="macro-recorder-transcript-summary">${macroTranscriptSummaryHtml(entry)}</span>
                $metaHtml
            </label>
            <details class="macro-recorder-detail macro-recorder-transcript-detail" data-transcript-detail-index="${entry.index}"$open>
                <summary title="Show transcript YAML"></summary>
                <pre>${htmlEscape(entry.yaml)}</pre>
            </details>
        </div>
        """.trimIndent()
    }
    macroTranscriptRenderedIndices = response.entries.map { it.index }.toSet()
    if (wasAtBottom) {
        macroTranscriptFollowTail = true
        window.setTimeout({
            list.scrollTop = list.scrollHeight.toDouble()
        }, 0)
    }
    macroPanelRestoreTranscriptYamlScrollState()

    list.querySelectorAll("input[data-transcript-index]").asList().forEach { node ->
        val input = node as? HTMLElement ?: return@forEach
        input.addEventListener("click", { event ->
            val index = input.getAttribute("data-transcript-index")?.toIntOrNull() ?: return@addEventListener
            val checked = input.asDynamic().checked as? Boolean ?: false
            val anchor = macroTranscriptSelectionAnchor
            if (checked) {
                if (event.asDynamic().shiftKey as? Boolean == true && anchor != null) {
                    val visibleIndices = macroPanelVisibleTranscriptCheckboxIndices()
                    val anchorPosition = visibleIndices.indexOf(anchor)
                    val indexPosition = visibleIndices.indexOf(index)
                    if (anchorPosition >= 0 && indexPosition >= 0) {
                        val first = minOf(anchorPosition, indexPosition)
                        val last = maxOf(anchorPosition, indexPosition)
                        visibleIndices.subList(first, last + 1).forEach { macroTranscriptSelected.add(it) }
                        macroPanelApplyVisibleTranscriptSelection()
                    } else {
                        macroTranscriptSelected.add(index)
                    }
                } else {
                    macroTranscriptSelected.add(index)
                }
            } else {
                macroTranscriptSelected.remove(index)
            }
            macroTranscriptSelectionAnchor = index
            macroPanelUpdateSelectionStatus()
        })
    }
    list.querySelectorAll("details[data-transcript-detail-index]").asList().forEach { node ->
        val details = node as? HTMLElement ?: return@forEach
        details.addEventListener("toggle", {
            val index = details.getAttribute("data-transcript-detail-index")?.toIntOrNull() ?: return@addEventListener
            val open = details.asDynamic().open as? Boolean ?: false
            if (open) macroTranscriptOpenDetails.add(index) else macroTranscriptOpenDetails.remove(index)
        })
    }
    macroPanelUpdateSelectionStatus()
}

private fun macroPanelTranscriptIsAtBottom(list: HTMLElement): Boolean {
    val scrollTop = (list.asDynamic().scrollTop as? Number)?.toDouble() ?: 0.0
    val scrollHeight = (list.asDynamic().scrollHeight as? Number)?.toDouble() ?: 0.0
    val clientHeight = (list.asDynamic().clientHeight as? Number)?.toDouble() ?: 0.0
    return scrollHeight - (scrollTop + clientHeight) <= 8.0
}

private fun macroPanelCaptureTranscriptDetailState() {
    val list = macroPanelQuery("[data-role='transcript-list']") ?: return
    list.querySelectorAll("details[data-transcript-detail-index]").asList().forEach { node ->
        val details = node as? HTMLElement ?: return@forEach
        val index = details.getAttribute("data-transcript-detail-index")?.toIntOrNull() ?: return@forEach
        val open = details.asDynamic().open as? Boolean ?: false
        if (open) macroTranscriptOpenDetails.add(index) else macroTranscriptOpenDetails.remove(index)
        val pre = details.querySelector("pre") as? HTMLElement
        val scrollTop = (pre?.asDynamic()?.scrollTop as? Number)?.toDouble() ?: 0.0
        if (scrollTop > 0.0) {
            macroTranscriptYamlScrollTop[index] = scrollTop
        } else {
            macroTranscriptYamlScrollTop.remove(index)
        }
    }
}

private fun macroPanelRestoreTranscriptYamlScrollState() {
    val list = macroPanelQuery("[data-role='transcript-list']") ?: return
    list.querySelectorAll("details[data-transcript-detail-index]").asList().forEach { node ->
        val details = node as? HTMLElement ?: return@forEach
        val index = details.getAttribute("data-transcript-detail-index")?.toIntOrNull() ?: return@forEach
        val scrollTop = macroTranscriptYamlScrollTop[index] ?: return@forEach
        val pre = details.querySelector("pre") as? HTMLElement ?: return@forEach
        val maxScroll = ((pre.asDynamic().scrollHeight as? Number)?.toDouble() ?: 0.0) -
            ((pre.asDynamic().clientHeight as? Number)?.toDouble() ?: 0.0)
        pre.asDynamic().scrollTop = scrollTop.coerceIn(0.0, maxScroll.coerceAtLeast(0.0))
    }
}

private fun macroPanelVisibleTranscriptCheckboxIndices(): List<Int> {
    val list = macroPanelQuery("[data-role='transcript-list']") ?: return emptyList()
    return list.querySelectorAll("input[data-transcript-index]").asList().mapNotNull { node ->
        val input = node as? HTMLElement ?: return@mapNotNull null
        val disabled = input.asDynamic().disabled as? Boolean ?: false
        if (disabled) null else input.getAttribute("data-transcript-index")?.toIntOrNull()
    }
}

private fun macroPanelApplyVisibleTranscriptSelection() {
    val list = macroPanelQuery("[data-role='transcript-list']") ?: return
    list.querySelectorAll("input[data-transcript-index]").asList().forEach { node ->
        val input = node as? HTMLElement ?: return@forEach
        val index = input.getAttribute("data-transcript-index")?.toIntOrNull() ?: return@forEach
        input.asDynamic().checked = macroTranscriptSelected.contains(index)
    }
}

private fun macroPanelUpdateSelectionStatus() {
    val status = macroPanelQuery("[data-role='transcript-selection']")
    val count = macroTranscriptSelected.size
    status?.textContent = if (count == 0) "nothing selected" else "$count selected"
    macroPanelSyncControls()
}

private fun macroPanelLoadTranscript(offset: Int = macroTranscriptOffset, followTail: Boolean = macroTranscriptFollowTail) {
    macroTranscriptFollowTail = followTail
    macroPanelRequest(
        "transcript-page",
        offset = if (followTail) -1 else offset.coerceAtLeast(0),
        limit = MACRO_TRANSCRIPT_PAGE_SIZE,
        mode = if (macroTranscriptHideWaits) "hide-wait" else "",
        announce = false
    ) { response ->
        macroTranscriptOffset = response.offset
        macroTranscriptLastTotal = response.total
        renderMacroTranscriptPage(response)
    }
}

private fun macroPanelSelectedTranscriptIndices(): List<Int> =
    macroTranscriptSelected.toList().sorted()

private fun macroPanelLaunchAiUndo(prompt: String) {
    val uid = getSelectedIds().firstOrNull()
    if (uid == null) {
        notify("Select a box to host the AI4 undo conversation", "error")
        return
    }
    val w = window.asDynamic()
    val opener = w.openAiChatForBox
    if (opener != null) {
        opener(uid, selectedProperty)
    }
    val payload = js("{}")
    payload.uid = uid
    payload.prompt = "Macro transcript undo request"
    payload.extraPrompt = prompt
    payload.startLine = 0
    payload.property = selectedProperty
    payload.kind = "general"
    send("ai-assist", payload)
}

private fun macroPanelQuery(selector: String): HTMLElement? =
    macroPanelElement?.querySelector(selector) as? HTMLElement

private fun macroPanelValue(selector: String): String =
    macroPanelElement?.querySelector(selector).asDynamic()?.value as? String ?: ""

private fun macroPanelSetValue(selector: String, value: String) {
    macroPanelElement?.querySelector(selector).asDynamic().value = value
}

private fun macroPanelOpenEditor() {
    (macroPanelElement?.querySelector("[data-role='macro-editor']") as? HTMLElement)?.asDynamic().open = true
}

private fun macroPanelSetContent(content: String, dirty: Boolean = false) {
    macroPanelSetValue("[data-role='content']", content)
    renderMacroPreview(content)
    macroPanelContentDirty = dirty
}

private fun macroPanelScope(): String =
    macroPanelValue("[data-role='scope']").ifBlank { "document" }

private fun macroPanelProjectName(): String =
    macroPanelLastState?.projectName.orEmpty()

private fun macroPanelName(): String =
    macroPanelValue("[data-role='name']").trim()

private fun macroPanelHasProject(): Boolean =
    macroPanelLastState?.projectName.orEmpty().isNotBlank()

/** Saved macros available in the scope currently selected in the panel. */
private fun macroPanelScopeMacros(): List<String> {
    val state = macroPanelLastState ?: return emptyList()
    return when (macroPanelScope()) {
        "workspace" -> state.workspaceMacros
        "project" -> state.projectMacros
        else -> state.documentMacros.ifEmpty { state.macros }
    }
}

private fun macroPanelSetControlEnabled(action: String, enabled: Boolean, hint: String = "") {
    val button = macroPanelElement?.querySelector("[data-action='$action']") as? HTMLElement ?: return
    button.asDynamic().disabled = !enabled
    button.classList.toggle("is-disabled", !enabled)
    if (hint.isBlank()) button.removeAttribute("title") else button.setAttribute("title", hint)
}

/**
 * Single source of truth for the panel's modal button state. Recompute whenever anything that a
 * button's precondition depends on changes: recorder state, scope, the name field, editor content,
 * the loaded macro lists, the transcript page, or the transcript selection.
 */
private fun macroPanelSyncControls() {
    val recording = macroPanelLastState?.recording == true
    val name = macroPanelName()
    val scope = macroPanelScope()
    val nameSaved = name.isNotBlank() && macroPanelScopeMacros().contains(name)
    val hasContent = macroPanelValue("[data-role='content']").isNotBlank()
    val hasSelection = macroTranscriptSelected.isNotEmpty()

    macroPanelSetControlEnabled(
        "start", !recording,
        if (recording) "Already recording — Save to finish the macro." else ""
    )
    macroPanelSetControlEnabled(
        "save", name.isNotBlank() && (recording || hasContent),
        when {
            name.isBlank() -> "Name the macro before saving."
            !recording && !hasContent -> "Record or edit a macro before saving."
            else -> ""
        }
    )
    macroPanelSetControlEnabled(
        "run", nameSaved,
        if (nameSaved) "" else "Load or save a macro before running it."
    )
    macroPanelSetControlEnabled(
        "promote", nameSaved && scope != "workspace",
        when {
            !nameSaved -> "Load or save a macro before promoting it."
            scope == "workspace" -> "Workspace macros are already at the broadest scope."
            else -> "Copy this macro to the ${if (macroPanelHasProject()) "project" else "workspace"} so other documents can use it."
        }
    )

    macroPanelSetControlEnabled(
        "save-selected", hasSelection && name.isNotBlank(),
        when {
            !hasSelection -> "Select transcript entries to save."
            name.isBlank() -> "Name the macro before saving."
            else -> ""
        }
    )
    macroPanelSetControlEnabled(
        "undo-selected", hasSelection,
        if (hasSelection) "" else "Select transcript entries to undo."
    )
    macroPanelSetControlEnabled(
        "ai-undo-selected", hasSelection,
        if (hasSelection) "" else "Select transcript entries for AI4 undo."
    )

    val shown = macroTranscriptRenderedIndices.size
    macroPanelSetControlEnabled("transcript-older", macroTranscriptOffset > 0)
    macroPanelSetControlEnabled("transcript-newer", macroTranscriptOffset + shown < macroTranscriptLastTotal)
}

private fun macroRevealLabel(): String {
    val platform = (window.asDynamic().navigator.userAgentData?.platform as? String)
        ?: (window.navigator.asDynamic().platform as? String)
        ?: ""
    val lower = platform.lowercase()
    return when {
        lower.contains("mac") -> "Reveal in Finder"
        lower.contains("win") -> "Reveal in Explorer"
        lower.contains("linux") -> "Reveal in Files"
        else -> "Reveal"
    }
}

private fun macroFileBaseName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("\\.(ya?ml)$", RegexOption.IGNORE_CASE), "")

private fun macroPanelImportYamlFile(file: dynamic) {
    val fileName = (file.name as? String).orEmpty()
    if (!fileName.lowercase().endsWith(".yaml") && !fileName.lowercase().endsWith(".yml")) {
        macroPanelSetFeedback("Drop .yaml macro files here", "error")
        return
    }
    val name = macroFileBaseName(fileName).ifBlank { "macro" }
    val reader = js("new FileReader()")
    reader.onload = { _: dynamic ->
        val content = (reader.result as? String).orEmpty()
        macroPanelSetValue("[data-role='scope']", "document")
        macroPanelSetValue("[data-role='name']", name)
        val rewritten = macroContentWithName(content, name)
        macroPanelRequest("write", name = name, content = rewritten, announce = false) { response ->
            if (response.success) {
                macroPanelSetContent(response.content.ifBlank { rewritten }, dirty = false)
                macroPanelOpenEditor()
                macroPanelSetFeedback("Imported ${response.name.ifBlank { name }} into document macros.")
            } else {
                macroPanelSetFeedback(response.message.ifBlank { "Could not import $fileName" }, "error")
            }
            macroPanelRefresh()
        }
    }
    reader.onerror = { _: dynamic ->
        macroPanelSetFeedback("Could not read $fileName", "error")
    }
    reader.readAsText(file)
}

private fun macroPanelRequest(
    command: String,
    name: String = macroPanelName(),
    content: String = "",
    offset: Int = 0,
    limit: Int = MACRO_TRANSCRIPT_PAGE_SIZE,
    indices: List<Int> = emptyList(),
    mode: String = "",
    announce: Boolean = true,
    after: ((MacroTranscriptCommandResponse) -> Unit)? = null
) {
    val scope = macroPanelScope()
    sendMacroTranscriptCommand(
        MacroTranscriptCommandRequest(
            command = command,
            name = name,
            label = name,
            selection = getSelectedIds(),
            scope = scope,
            projectName = if (scope == "project") macroPanelProjectName() else "",
            content = content,
            offset = offset,
            limit = limit,
            indices = indices,
            mode = mode
        ),
        announce = announce,
        after = after
    )
}

private fun macroPaletteList(): Promise<dynamic> =
    Promise { resolve, reject ->
        sendMacroTranscriptCommand(
            MacroTranscriptCommandRequest(command = "state", scope = "document"),
            announce = false
        ) { response ->
            if (!response.success) {
                reject(Throwable(response.message))
                return@sendMacroTranscriptCommand
            }
            val items = js("[]")
            fun add(scope: String, names: List<String>) {
                names.forEach { name ->
                    val item = js("{}")
                    item.name = name
                    item.scope = scope
                    item.projectName = if (scope == "project") response.projectName else ""
                    item.operationCount = response.operationCount
                    items.push(item)
                }
            }
            add("document", response.documentMacros.ifEmpty { response.macros })
            add("project", response.projectMacros)
            add("workspace", response.workspaceMacros)
            resolve(items)
        }
    }

private fun macroPaletteRun(name: String, scope: String, projectName: String = ""): Promise<dynamic> =
    Promise { resolve, reject ->
        val normalizedScope = scope.ifBlank { "document" }
        sendMacroTranscriptCommand(
            MacroTranscriptCommandRequest(
                command = "run",
                name = name,
                label = name,
                selection = getSelectedIds(),
                scope = normalizedScope,
                projectName = if (normalizedScope == "project") projectName else ""
            ),
            announce = true
        ) { response ->
            if (response.success) {
                resolve(response.asDynamic())
            } else {
                reject(Throwable(response.message))
            }
        }
    }

private fun ensureMacroPanel(): HTMLElement {
    macroPanelElement?.let { return it }
    val panel = document.createElement("div") as HTMLElement
    panel.id = "macro-recorder-panel"
    panel.className = "macro-recorder-panel"
    panel.innerHTML = """
        <div class="macro-recorder-head">
            <div>
                <div class="macro-recorder-title">Macro Recorder</div>
                <div class="macro-recorder-status" data-role="status">idle</div>
            </div>
            <button type="button" class="macro-recorder-icon" data-action="close" title="Close">×</button>
        </div>
        <div class="macro-recorder-controls">
            <input data-role="name" autocomplete="off" placeholder="macro name">
            <select data-role="scope" title="Macro scope">
                <option value="document">document</option>
                <option value="project">project</option>
                <option value="workspace">workspace</option>
            </select>
            <button type="button" data-action="start">Start</button>
            <button type="button" data-action="save">Save</button>
            <button type="button" data-action="run">Run</button>
            <button type="button" data-action="promote" title="Copy this macro to the project (or workspace) so other documents can use it">Promote</button>
            <button type="button" data-action="reveal"></button>
        </div>
        <div class="macro-recorder-meta" data-role="meta"></div>
        <div class="macro-recorder-feedback" data-role="feedback"></div>
        <div class="macro-recorder-transcript-head">
            <div>
                <div class="macro-recorder-list-title">transcript</div>
                <div class="macro-recorder-transcript-meta-line">
                    <span class="macro-recorder-meta" data-role="transcript-page">entries 0-0 of 0</span>
                    <span class="macro-recorder-notation">·</span>
                    <span class="macro-recorder-meta" data-role="transcript-selection">nothing selected</span>
                </div>
            </div>
            <div class="macro-recorder-transcript-actions">
                <label class="macro-recorder-transcript-toggle">
                    <input type="checkbox" data-role="hide-waits">
                    <span>hide wait</span>
                </label>
                <button type="button" data-action="transcript-newer">Newer</button>
                <button type="button" data-action="transcript-older">Older</button>
                <button type="button" data-action="save-selected">Save selected</button>
                <button type="button" data-action="undo-selected">Undo selected</button>
                <button type="button" data-action="ai-undo-selected">AI Undo</button>
            </div>
        </div>
        <div class="macro-recorder-transcript" data-role="transcript-list"></div>
        <details class="macro-recorder-editor" data-role="macro-editor">
            <summary>macro yaml</summary>
            <div class="macro-recorder-editor-body">
                <textarea data-role="content" spellcheck="false"></textarea>
                <div class="macro-recorder-preview" data-role="preview"></div>
            </div>
        </details>
        <div class="macro-recorder-browser">
            <div>
                <div class="macro-recorder-list-title">document</div>
                <div class="macro-recorder-list" data-role="document-list"></div>
            </div>
            <div>
                <div class="macro-recorder-list-title" data-role="project-title">project</div>
                <div class="macro-recorder-list" data-role="project-list"></div>
            </div>
            <div>
                <div class="macro-recorder-list-title">workspace</div>
                <div class="macro-recorder-list" data-role="workspace-list"></div>
            </div>
        </div>
    """.trimIndent()
    document.body!!.appendChild(panel)
    macroPanelElement = panel
    panel.querySelector("[data-action='reveal']")?.textContent = macroRevealLabel()

    panel.querySelector("[data-action='close']")?.addEventListener("click", {
        panel.classList.remove("is-visible")
        macroPanelStopAutoRefresh()
    })
    panel.addEventListener("dragover", { event ->
        event.preventDefault()
        panel.classList.add("is-dragging-file")
    })
    panel.addEventListener("dragleave", {
        panel.classList.remove("is-dragging-file")
    })
    panel.addEventListener("drop", { event ->
        event.preventDefault()
        panel.classList.remove("is-dragging-file")
        val files = event.asDynamic().dataTransfer?.files ?: return@addEventListener
        val length = (files.length as? Number)?.toInt() ?: 0
        if (length == 0) return@addEventListener
        (0 until length).forEach { index ->
            macroPanelImportYamlFile(files[index])
        }
    })
    panel.querySelector("[data-role='transcript-list']")?.addEventListener("scroll", { event ->
        val list = event.target as? HTMLElement ?: return@addEventListener
        macroTranscriptFollowTail = macroPanelTranscriptIsAtBottom(list)
    })
    panel.querySelector("[data-action='transcript-newer']")?.addEventListener("click", {
        val nextOffset = macroTranscriptOffset + MACRO_TRANSCRIPT_PAGE_SIZE
        if (nextOffset + MACRO_TRANSCRIPT_PAGE_SIZE >= macroTranscriptLastTotal) {
            macroPanelLoadTranscript(followTail = true)
        } else {
            macroPanelLoadTranscript(nextOffset, followTail = false)
        }
    })
    panel.querySelector("[data-action='transcript-older']")?.addEventListener("click", {
        macroPanelLoadTranscript((macroTranscriptOffset - MACRO_TRANSCRIPT_PAGE_SIZE).coerceAtLeast(0), followTail = false)
    })
    panel.querySelector("[data-role='hide-waits']")?.addEventListener("change", {
        val input = it.target as? HTMLElement ?: return@addEventListener
        macroTranscriptHideWaits = input.asDynamic().checked as? Boolean ?: false
        macroTranscriptFollowTail = true
        macroPanelLoadTranscript(followTail = true)
    })
    panel.querySelector("[data-action='save-selected']")?.addEventListener("click", {
        val indices = macroPanelSelectedTranscriptIndices()
        if (indices.isEmpty()) {
            notify("Select transcript entries to save", "error")
            return@addEventListener
        }
        val name = macroPanelName()
        if (name.isBlank()) {
            notify("Name the macro before saving", "error")
            return@addEventListener
        }
        macroPanelRequest("save-transcript-selection", name = name, indices = indices) { response ->
            if (response.success) {
                macroPanelSetContent(response.content, dirty = false)
                macroPanelOpenEditor()
                macroPanelSetFeedback(response.message.ifBlank { "saved ${response.name.ifBlank { name }}" })
            } else {
                macroPanelSetFeedback(response.message.ifBlank { "Could not save selected transcript entries" }, "error")
            }
            macroPanelRefresh()
        }
    })
    panel.querySelector("[data-action='undo-selected']")?.addEventListener("click", {
        val indices = macroPanelSelectedTranscriptIndices()
        if (indices.isEmpty()) {
            notify("Select transcript entries to undo", "error")
            return@addEventListener
        }
        macroPanelRequest("undo-transcript-selection", indices = indices) { response ->
            macroPanelShowRunFeedback(response)
            macroPanelRefresh()
        }
    })
    panel.querySelector("[data-action='ai-undo-selected']")?.addEventListener("click", {
        val indices = macroPanelSelectedTranscriptIndices()
        if (indices.isEmpty()) {
            notify("Select transcript entries for AI4 undo", "error")
            return@addEventListener
        }
        macroPanelRequest("ai-undo-prompt", indices = indices) { response ->
            if (response.success && response.prompt.isNotBlank()) {
                macroPanelSetFeedback("${response.message} Launching AI4 general conversation.")
                macroPanelLaunchAiUndo(response.prompt)
            } else {
                macroPanelShowRunFeedback(response)
            }
        }
    })
    panel.querySelector("[data-action='start']")?.addEventListener("click", {
        macroTranscriptFollowTail = true
        macroPanelRequest("start") {
            macroPanelRefresh()
        }
    })
    panel.querySelector("[data-action='save']")?.addEventListener("click", {
        val name = macroPanelName()
        if (name.isBlank()) {
            notify("Name the macro before saving", "error")
            return@addEventListener
        }
        if (macroPanelLastState?.recording == true) {
            macroPanelRequest("end-and-save", name = name) { endResponse ->
                if (endResponse.success) {
                    val savedName = endResponse.name.ifBlank { name }
                    macroPanelRequest("read", name = savedName, announce = false) { readResponse ->
                        if (readResponse.success) {
                            macroPanelSetContent(readResponse.content, dirty = false)
                            macroPanelOpenEditor()
                            macroPanelSetFeedback("saved $savedName")
                        } else {
                            macroPanelSetFeedback(readResponse.message.ifBlank { "saved $savedName, but could not reload it" }, "error")
                        }
                        macroPanelRefresh()
                    }
                } else {
                    macroPanelSetFeedback(endResponse.message.ifBlank { "Could not save macro" }, "error")
                    macroPanelRefresh()
                }
            }
        } else {
            val content = macroContentWithName(macroPanelValue("[data-role='content']"), name)
            macroPanelRequest("write", name = name, content = content) { response ->
                if (response.success) {
                    macroPanelSetContent(response.content.ifBlank { content }, dirty = false)
                }
                macroPanelRefresh()
            }
        }
    })
    panel.querySelector("[data-action='run']")?.addEventListener("click", {
        val name = macroPanelName()
        if (name.isBlank()) {
            notify("Choose a macro to run", "error")
            return@addEventListener
        }
        macroPanelRequest("run", name = name) { response ->
            macroPanelShowRunFeedback(response)
            macroPanelRefresh()
        }
    })
    panel.querySelector("[data-action='promote']")?.addEventListener("click", {
        val name = macroPanelName()
        if (name.isBlank()) {
            notify("Choose a macro to promote", "error")
            return@addEventListener
        }
        macroPanelRequest("promote", name = name, announce = false) { response ->
            if (response.success) {
                macroPanelSetValue("[data-role='scope']", response.scope)
            }
            macroPanelSetFeedback(response.message, if (response.success) "ok" else "error")
            macroPanelRefresh()
        }
    })
    panel.querySelector("[data-action='reveal']")?.addEventListener("click", {
        macroPanelRequest("reveal", announce = false) { response ->
            macroPanelSetFeedback(response.message, if (response.success) "ok" else "error")
        }
    })
    panel.querySelector("[data-role='scope']")?.addEventListener("change", {
        macroPanelRefresh()
    })
    panel.querySelector("[data-role='name']")?.addEventListener("input", {
        macroPanelSyncControls()
    })
    panel.querySelector("[data-role='content']")?.addEventListener("input", {
        macroPanelContentDirty = true
        renderMacroPreview(macroPanelValue("[data-role='content']"))
        macroPanelSyncControls()
    })
    return panel
}

private fun renderMacroList(selector: String, names: List<String>, scope: String) {
    val list = macroPanelQuery(selector) ?: return
    if (names.isEmpty()) {
        list.innerHTML = "<span class=\"macro-recorder-empty\">none</span>"
        return
    }
    list.innerHTML = names.joinToString("") { name ->
        "<button type=\"button\" data-macro-scope=\"${htmlEscape(scope)}\" data-macro-name=\"${htmlEscape(name)}\">${htmlEscape(name)}</button>"
    }
    list.querySelectorAll("button").asList().forEach { item ->
        val button = item as? HTMLElement ?: return@forEach
        button.addEventListener("click", {
            val name = button.getAttribute("data-macro-name").orEmpty()
            val selectedScope = button.getAttribute("data-macro-scope").orEmpty().ifBlank { "document" }
            macroPanelSetValue("[data-role='name']", name)
            macroPanelSetValue("[data-role='scope']", selectedScope)
            macroPanelRequest("read", name = name, announce = false) { response ->
                if (response.success) {
                    macroPanelSetContent(response.content, dirty = false)
                    macroPanelOpenEditor()
                    macroPanelLastState = response.copy(
                        documentMacros = macroPanelLastState?.documentMacros.orEmpty(),
                        projectMacros = macroPanelLastState?.projectMacros.orEmpty(),
                        workspaceMacros = macroPanelLastState?.workspaceMacros.orEmpty(),
                        projectName = macroPanelLastState?.projectName.orEmpty()
                    )
                    macroPanelQuery("[data-role='status']")?.textContent = "loaded ${response.name}"
                    macroPanelSyncControls()
                } else {
                    notify(response.message, "error")
                }
            }
        })
    }
}

private fun macroPanelShowRunFeedback(response: MacroTranscriptCommandResponse) {
    val summary = buildString {
        if (response.direction.isNotBlank()) append(response.direction).append(": ")
        append(response.applied).append(" applied, ")
        append(response.skipped).append(" skipped")
        if (response.errors.isNotEmpty()) append(", ").append(response.errors.size).append(" error(s)")
    }
    val errors = if (response.errors.isEmpty()) {
        ""
    } else {
        "<details open><summary>errors</summary><pre>${htmlEscape(response.errors.joinToString("\n"))}</pre></details>"
    }
    val kind = if (response.success) "ok" else "error"
    val feedback = macroPanelQuery("[data-role='feedback']") ?: return
    feedback.classList.remove("macro-recorder-feedback-pulse")
    feedback.classList.toggle("has-errors", response.errors.isNotEmpty())
    val text = response.message.ifBlank { summary }
    feedback.innerHTML = "<div class=\"macro-recorder-feedback-$kind\">${htmlEscape(text)}</div>$errors"
    feedback.asDynamic().offsetWidth
    feedback.classList.add("macro-recorder-feedback-pulse")
}

private fun updateMacroPanel(response: MacroTranscriptCommandResponse) {
    val panel = ensureMacroPanel()
    macroPanelLastState = response
    val status = macroPanelQuery("[data-role='status']")
    status?.textContent = response.message
    status?.asDynamic()?.classList?.toggle("is-recording", response.recording)
    val projectOption = panel.querySelector("[data-role='scope'] option[value='project']") as? HTMLElement
    projectOption?.textContent = if (response.projectName.isBlank()) "project" else "project: ${response.projectName}"
    projectOption?.asDynamic()?.disabled = response.projectName.isBlank()
    if (response.projectName.isBlank() && macroPanelScope() == "project") {
        macroPanelSetValue("[data-role='scope']", "document")
    }
    val selectionText = if (response.selection.isEmpty()) {
        "source selection: none"
    } else {
        "source selection: " + response.selection.joinToString(", ")
    }
    val scopeText = when (macroPanelScope()) {
        "workspace" -> "workspace macros"
        "project" -> if (response.projectName.isBlank()) "project macros" else "project ${response.projectName} macros"
        else -> "document macros"
    }
    macroPanelQuery("[data-role='meta']")?.textContent = "$scopeText · $selectionText · ${response.operationCount} operation(s)"
    val textArea = panel.querySelector("[data-role='content']")
    val activeElement = document.asDynamic().activeElement
    if (response.content.isNotBlank() || response.draft.isNotBlank()) {
        val nextContent = response.content.ifBlank { response.draft }
        if (activeElement != textArea && !macroPanelContentDirty) {
            macroPanelSetContent(nextContent, dirty = false)
        } else {
            renderMacroPreview(macroPanelValue("[data-role='content']"))
        }
    } else {
        renderMacroPreview(macroPanelValue("[data-role='content']"))
    }
    renderMacroList("[data-role='document-list']", response.documentMacros.ifEmpty { response.macros }, "document")
    renderMacroList("[data-role='project-list']", response.projectMacros, "project")
    renderMacroList("[data-role='workspace-list']", response.workspaceMacros, "workspace")
    macroPanelQuery("[data-role='project-title']")?.textContent =
        if (response.projectName.isBlank()) "project" else "project · ${response.projectName}"
    macroPanelSyncControls()
}

private fun macroPanelStartAutoRefresh() {
    if (macroPanelRefreshTimer != null) return
    macroPanelRefreshTimer = window.setInterval({
        val visible = macroPanelElement?.classList?.contains("is-visible") == true
        if (visible) {
            macroPanelRefresh()
        } else {
            macroPanelStopAutoRefresh()
        }
    }, 1250)
}

private fun macroPanelStopAutoRefresh() {
    val timer = macroPanelRefreshTimer ?: return
    window.clearInterval(timer)
    macroPanelRefreshTimer = null
}

internal fun showDocumentHistoryLocally(filename: String = "") {
    val resolvedFilename = filename.ifBlank {
        (window.asDynamic().currentDocumentFilename as? String)?.ifBlank { "default" } ?: "default"
    }
    val switchToHistory = window.asDynamic().switchToHistoryForFile
    if (jsTypeOf(switchToHistory) == "function") {
        switchToHistory(resolvedFilename)
    }
}

/**
 * History is a canvas-owned panel. In the split visionOS shell the command palette normally lives
 * in the floating editor, so send an ephemeral intent to the paired canvas instead of opening an
 * invisible/poorly placed iframe in the editor window. Desktop and unpaired clients stay local.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun showDocumentHistory() {
    val filename = (window.asDynamic().currentDocumentFilename as? String)?.ifBlank { "default" } ?: "default"
    if (fieldDisplayMode == "editor-only" && sharedUiContext != null) {
        send(Message("shared-ui-intent").payload(SharedUiIntent("show-document-history", filename)))
    } else {
        showDocumentHistoryLocally(filename)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun macroTranscriptPanel() {
    val panel = ensureMacroPanel()
    panel.classList.add("is-visible")
    macroTranscriptFollowTail = true
    macroPanelStartAutoRefresh()
    macroPanelRefresh()
}

fun macroPanelRefresh() {
    macroPanelRequest("state", announce = false) { response ->
        updateMacroPanel(response)
        macroPanelLoadTranscript(macroTranscriptOffset)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun macroTranscriptMarkStart() {
    val label = browserPrompt("Macro start label", "")?.trim().orEmpty()
    sendMacroTranscriptCommand(MacroTranscriptCommandRequest(command = "start", label = label, selection = getSelectedIds()))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun macroTranscriptMarkEndAndSave() {
    val name = browserPrompt("Save macro as", "macro")?.trim()?.takeIf { it.isNotEmpty() } ?: return
    sendMacroTranscriptCommand(MacroTranscriptCommandRequest(command = "end-and-save", name = name))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun macroTranscriptRunOnSelection() {
    val name = browserPrompt("Run macro", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return
    sendMacroTranscriptCommand(MacroTranscriptCommandRequest(command = "run", name = name, selection = getSelectedIds()))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun macroTranscriptUndoOnSelection() {
    val name = browserPrompt("Undo macro", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return
    sendMacroTranscriptCommand(MacroTranscriptCommandRequest(command = "undo", name = name, selection = getSelectedIds()))
}

/**
 * Load a dropped operation-transcript / macro YAML file into the current document's
 * macro scope under a name derived from the file, then run it against the current
 * selection. Mirrors the MCP write-then-run flow (tools/field_mcp.py tool_run_transcript).
 */
fun runDroppedTranscript(fileName: String, content: String) {
    if (content.isBlank()) {
        notify("Dropped transcript '$fileName' is empty.", "error")
        return
    }
    val macroName = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
        .ifBlank { "dropped-transcript" }
    // Write the content verbatim: the macro is keyed by the `name` parameter
    // (sanitizeMacroName), not by any `name:` line in the YAML. Rewriting the
    // content's first `name:` would corrupt transcripts whose first `name:` is a
    // nested box name rather than a top-level macro name.
    notify("Loading transcript '$macroName'...", "macro")
    sendMacroTranscriptCommand(
        MacroTranscriptCommandRequest(command = "write", name = macroName, content = content, scope = "document"),
        announce = false
    ) { writeResponse ->
        if (!writeResponse.success) {
            notify("Could not load transcript '$macroName': ${writeResponse.message}", "error")
            return@sendMacroTranscriptCommand
        }
        sendMacroTranscriptCommand(
            MacroTranscriptCommandRequest(
                command = "run",
                name = macroName,
                selection = getSelectedIds(),
                scope = "document"
            )
        )
    }
}

fun recordSelectedBoxMp4() {
    val box = requireSingleSelectedBox() ?: return
    val uid = box.uid as? String ?: return
    val (path, durationSec) = promptMp4Target("box") ?: return
    sendBoxVideo(
        AgentBoxVideo(
            uid = uid,
            path = path,
            durationSec = durationSec,
            fps = 30
        )
    )
}

fun recordSelectedBoxEditorMp4() {
    val box = requireSingleSelectedBox() ?: return
    val uid = box.uid as? String ?: return
    val (path, durationSec) = promptMp4Target("editor") ?: return
    sendWindowVideo(
        AgentWindowVideo(
            path = path,
            uid = uid,
            showEditor = true,
            editorOnly = true,
            openOutputs = false,
            durationSec = durationSec,
            fps = 30
        )
    )
}

fun recordWindowMp4() {
    val (path, durationSec) = promptMp4Target("window") ?: return
    sendWindowVideo(
        AgentWindowVideo(
            path = path,
            uid = null,
            showEditor = false,
            editorOnly = false,
            openOutputs = false,
            durationSec = durationSec,
            fps = 30
        )
    )
}

private fun downloadEdnFile(filename: String, edn: String) {
    val blob = js("new Blob([edn], { type: 'application/edn' })")
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a")
    link.setAttribute("href", url)
    link.setAttribute("download", filename)
    document.body!!.appendChild(link)
    link.asDynamic().click()
    window.setTimeout({
        document.body!!.removeChild(link)
        URL.revokeObjectURL(url)
    }, 0)
}


fun registerMouseBug() {
    document.addEventListener("mousemove", { event ->
        val mouseEvent = event as MouseEvent
        val x = mouseEvent.pageX - (mainCanvas.unsafeCast<dynamic>().data_x as Double)
        val y = mouseEvent.pageY - (mainCanvas.unsafeCast<dynamic>().data_y as Double)
        lastMouse = x to y
    })
}

private fun allBoxesOnCanvas(): List<HTMLElement> =
    document.body!!.getElementsByClassName("box").asList().mapNotNull { it as? HTMLElement }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun normalizeNestedPanForSelection() {
    val allBoxes = allBoxesOnCanvas()
    val boxByUid = allBoxes.mapNotNull { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        if (uid == null) null else uid to box
    }.toMap()

    val parentByUid = linkedMapOf<String, HTMLElement>()
    selected.forEach { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        val nested = box.unsafeCast<dynamic>().box_nested as? Array<String>
        if (uid != null && nested != null && nested.isNotEmpty()) {
            parentByUid[uid] = box
        }

        val nestedIn = box.unsafeCast<dynamic>().box_nestedIn as? String
        if (nestedIn != null) {
            boxByUid[nestedIn]?.let { parentByUid[nestedIn] = it }
        }
    }

    if (parentByUid.isEmpty()) {
        notify("Select a nest parent or a box inside a nest", "command")
        return
    }

    val updates = mutableListOf<Set_rect>()
    var adjustedChildren = 0
    var resetNests = 0

    parentByUid.values.forEach { parent ->
        val nestedCanvas = parent.querySelector(".nested-canvas") as? HTMLElement ?: return@forEach
        val nestedContent = nestedCanvas.querySelector(".nested-content") as? HTMLElement

        val panX = (nestedCanvas.asDynamic().data_x as? Number)?.toDouble() ?: 0.0
        val panY = (nestedCanvas.asDynamic().data_y as? Number)?.toDouble() ?: 0.0

        val childIds = (parent.unsafeCast<dynamic>().box_nested as? Array<String>)?.toList() ?: emptyList()
        if (childIds.isEmpty()) {
            nestedContent?.style?.transform = "translate(0px, 0px)"
            nestedCanvas.asDynamic().data_x = 0.0
            nestedCanvas.asDynamic().data_y = 0.0
            return@forEach
        }

        if (abs(panX) > 1e-6 || abs(panY) > 1e-6) {
            childIds.forEach { childId ->
                val child = boxByUid[childId] ?: return@forEach
                val d = child.unsafeCast<dynamic>()

                val x = (d.data_x as? Number)?.toDouble() ?: 0.0
                val y = (d.data_y as? Number)?.toDouble() ?: 0.0
                val w = (d.data_w as? Number)?.toDouble() ?: child.getBoundingClientRect().width
                val h = (d.data_h as? Number)?.toDouble() ?: child.getBoundingClientRect().height
                val nx = x + panX
                val ny = y + panY

                d.data_x = nx
                d.data_y = ny
                child.style.transform = "translate(${nx}px, ${ny}px)"

                updates += Set_rect(childId, "frame", Rect(nx, ny, w, h))
                adjustedChildren++
            }
        }

        nestedContent?.style?.transform = "translate(0px, 0px)"
        nestedCanvas.asDynamic().data_x = 0.0
        nestedCanvas.asDynamic().data_y = 0.0
        resetNests++
    }

    if (updates.isNotEmpty()) {
        send(Message("set-rects").payload(Set_rects(updates)))
    }

    canvasGestures.rebuildConnectionGroup()
    notify("Reset pan on $resetNests nest(s), adjusted $adjustedChildren box frame(s)", "command")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun newBox() {
    val w = 100.0
    val h = 100.0
    mainCanvas.append {
        createBox("Untitled", frame = Rect(lastMouse.first - w / 2, lastMouse.second - h / 2, w, h))
    }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun deleteSelectedBoxes() {
    send(
        Message("perform-action").payload(
            Perform_action(
                selected.mapNotNull { it.unsafeCast<dynamic>().uid },
                "-delete-box"
            )
        )
    )
    selected.forEach {
        val uid = it.unsafeCast<dynamic>().uid as? String
        if (uid != null) {
            val emb = js("window.embeddedEditors")
            if (emb != null) {
                (emb.disposeForBox as? Function<*>)?.asDynamic()?.call(emb, uid)
            }
        }
        it.parentElement?.removeChild(it)
    }
    canvasGestures.rebuildConnectionGroup()
    updateEmptyDocumentWelcome()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun selectAllBoxes() {
    val boxes = allBoxesOnCanvas()
    if (boxes.isEmpty()) return

    // Normalize selection state then select everything
    selected.toList().forEach { deselectBox(it) }
    boxes.forEach { selectBox(it) }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun selectAllNestedBoxes() {
    val nestedBoxes = allBoxesOnCanvas().filter { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    if (nestedBoxes.isEmpty()) {
        notify("No nested boxes in this document", "command")
        return
    }
    selected.toList().forEach { deselectBox(it) }
    nestedBoxes.forEach { selectBox(it) }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun connectFromNestParentToSelection() {
    val selectedNested = selected.filter { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    if (selectedNested.isEmpty()) {
        notify("Select one or more nested boxes", "command")
        return
    }

    val boxByUid = allBoxesOnCanvas().mapNotNull { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        if (uid == null) null else uid to box
    }.toMap()

    val groupedByParentUid = linkedMapOf<String, MutableList<String>>()
    selectedNested.forEach { child ->
        val childUid = child.unsafeCast<dynamic>().uid as? String ?: return@forEach
        val parentUid = child.unsafeCast<dynamic>().box_nestedIn as? String ?: return@forEach
        if (!boxByUid.containsKey(parentUid)) return@forEach
        groupedByParentUid.getOrPut(parentUid) { mutableListOf() }.add(childUid)
    }

    if (groupedByParentUid.isEmpty()) {
        notify("No valid nest parents found for selection", "error")
        return
    }

    var linkCount = 0
    groupedByParentUid.forEach { (parentUid, childUids) ->
        val uniqueChildren = childUids.distinct().filter { it != parentUid }
        if (uniqueChildren.isEmpty()) return@forEach
        send(
            Message("perform-action").payload(
                Perform_action(
                    listOf(parentUid) + uniqueChildren,
                    "-connect"
                )
            )
        )
        linkCount += uniqueChildren.size
    }

    if (linkCount > 0) {
        notify("Connected nest parent(s) to $linkCount selected nested box${if (linkCount == 1) "" else "es"}", "command")
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun copySelectedBoxesToClipboard() {
    val ids = getSelectedIds()
    if (ids.isEmpty()) return

    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == undefined) {
        notify("Clipboard not available", "error")
        return
    }

    sendWithReply(Message("copy-boxes").payload(CopyBoxes(ids)), "copy-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<CopyBoxesResult>(message.payload!!)

        // Write both text and file formats when the browser allows it.
        try {
            // ClipboardItem.supports is optional and not universally implemented; guard all calls.
            val supportsEdn = try {
                js("typeof ClipboardItem !== 'undefined' && ClipboardItem.supports && ClipboardItem.supports('application/edn')") as Boolean
            } catch (_: dynamic) { false }
            val supportsOctetStream = try {
                js("typeof ClipboardItem !== 'undefined' && ClipboardItem.supports && ClipboardItem.supports('application/octet-stream')") as Boolean
            } catch (_: dynamic) { false }

            val clipboardData = js("({})")
            clipboardData["text/plain"] = js("new Blob([result.edn], { type: 'text/plain' })")

            var fileType: String? = null
            if (supportsEdn) {
                fileType = "application/edn"
            } else if (supportsOctetStream) {
                fileType = "application/octet-stream"
            }

            if (fileType != null) {
                val blob = js("new Blob([result.edn], { type: fileType })")
                val file = js("new File([blob], 'metadata.edn', { type: fileType })")
                clipboardData[fileType] = file
            }

            if (fileType != null) {
                val clipboardItem = js("new ClipboardItem(clipboardData)")
                clipboard.write(js("Array").of(clipboardItem)).then({
                    console.info("[clipboard] copied as text and $fileType")
                }, { error: dynamic ->
                    console.info("[clipboard] write failed, using text-only fallback", error)
                    clipboard.writeText(result.edn)
                })
            } else {
                // Browsers sanitize custom file types; fall back to text-only.
                clipboard.writeText(result.edn)
            }
        } catch (e: Exception) {
            console.info("[clipboard] modern clipboard API failed, using text-only fallback", e)
            clipboard.writeText(result.edn)
        }

        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun exportSelectedBoxesToDownload() {
    val ids = getSelectedIds()
    if (ids.isEmpty()) return

    sendWithReply(Message("copy-boxes").payload(CopyBoxes(ids)), "copy-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<CopyBoxesResult>(message.payload!!)
        val base = if (result.filename.isNotBlank()) result.filename else "selection"
        downloadEdnFile("${base}_selection.edn", result.edn)
        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun exportDocumentToDownload() {
    sendWithReply(Message("export-document"), "export-document") { message, _ ->
        val result = Json.decodeFromJsonElement<ExportDocumentResult>(message.payload!!)
        val name = if (result.filename.isNotBlank()) result.filename else "document"
        downloadEdnFile("$name.edn", result.edn)
        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun pasteBoxesFromClipboard() {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == undefined) {
        notify("Clipboard not available", "error")
        return
    }

    // Try to read clipboard items (supports both text and files)
    clipboard.read().then({ clipboardItems: dynamic ->
        var processed = false

        // Iterate through clipboard items
        val items = clipboardItems
        if (items.length > 0) {
            val item = items[0]

            // Check if there's an EDN file in the clipboard
            if (item.types.includes("application/edn")) {
                item.getType("application/edn").then({ blob: dynamic ->
                    blob.text().then({ text: String ->
                        if (text.isNotBlank()) {
                            processPasteText(text)
                        }
                    }, { err: dynamic ->
                        console.info("[clipboard] failed to read EDN blob", err)
                    })
                }, { err: dynamic ->
                    console.info("[clipboard] failed to get EDN type", err)
                })
                processed = true
            }
            // Fallback to plain text
            else if (item.types.includes("text/plain")) {
                item.getType("text/plain").then({ blob: dynamic ->
                    blob.text().then({ text: String ->
                        if (text.isNotBlank()) {
                            processPasteText(text)
                        }
                    }, { err: dynamic ->
                        console.info("[clipboard] failed to read text blob", err)
                    })
                }, { err: dynamic ->
                    console.info("[clipboard] failed to get text type", err)
                })
                processed = true
            }
        }

        // If no items were processed, try the old text-only API as fallback
        if (!processed) {
            clipboard.readText().then({ text: String ->
                if (text.isNotBlank()) {
                    processPasteText(text)
                }
            }, { err: dynamic ->
                console.info("[clipboard] read failed", err)
            })
        }
    }, { err: dynamic ->
        console.info("[clipboard] failed to read items, falling back to text", err)
        // Fallback to text-only API
        clipboard.readText().then({ text: String ->
            if (text.isNotBlank()) {
                processPasteText(text)
            }
        }, { err: dynamic ->
            console.info("[clipboard] read failed", err)
        })
    })
}

private fun processPasteText(text: String) {
    sendWithReply(Message("paste-boxes").payload(PasteBoxes(text)), "paste-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<PasteBoxesResult>(message.payload!!)
        later({
            val boxes = result.newUids.mapNotNull { uid ->
                document.body!!.getElementsByClassName("box").asList()
                    .firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement
            }
            if (boxes.isNotEmpty()) {
                selected.copy().forEach { deselectBox(it) }
                boxes.forEach { selectBox(it) }
                canvasGestures.rebuildConnectionGroup()
            }
        }, 50)
        Handled.yes_remove
    }
}


@OptIn(ExperimentalJsExport::class)
@JsExport
fun moveSelectedBoxesForward() {
    selected.forEach {
        it.parentNode?.appendChild(it)
    }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun showAllLayers() {
    val layers = getAllLayers().filter { it.isNotBlank() }.toSet().ifEmpty { setOf("default") }
    setVisibleLayers(layers, animated = true, duration = 450.0)

    val hostBox = selected.firstOrNull() ?: allBoxesOnCanvas().firstOrNull()
    val hostUid = hostBox?.unsafeCast<dynamic>()?.uid as? String
    if (hostBox != null && hostUid != null) {
        handleOutputAreaRequest(
            hostBox,
            Set_output(
                uid = hostUid,
                property = "outputarea",
                channel = "layers",
                value = ""
            )
        )
    }

    notify("Showing all ${layers.size} layer${if (layers.size == 1) "" else "s"}", "command")
}

private fun layerNamesInNavigationOrder(): List<String> =
    getAllLayers().filter { it.isNotBlank() }.distinct().sorted().ifEmpty { listOf("default") }

private fun currentLayerForNavigation(layers: List<String>): String {
    val active = activeLayer?.takeIf { layers.contains(it) }
    if (active != null) return active

    val soloVisible = visibleLayers.singleOrNull()?.takeIf { layers.contains(it) }
    if (soloVisible != null) return soloVisible

    return layers.first()
}

private fun soloLayerTarget(offset: Int): String {
    val layers = layerNamesInNavigationOrder()
    val current = currentLayerForNavigation(layers)
    val currentIndex = layers.indexOf(current).takeIf { it >= 0 } ?: 0
    val nextIndex = (currentIndex + offset + layers.size) % layers.size
    return layers[nextIndex]
}

private fun soloLayer(offset: Int) {
    val target = soloLayerTarget(offset)
    setVisibleLayers(setOf(target), animated = true, duration = 450.0)
    setActiveLayer(target, animated = true, duration = 450.0)
    notify("Solo layer \"$target\"", "command")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun soloNextLayer() {
    soloLayer(1)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun soloPreviousLayer() {
    soloLayer(-1)
}

private fun jsCommandStringLiteral(value: String): String = JSON.stringify(value)

@OptIn(ExperimentalJsExport::class)
@JsExport
fun refreshLayerShortcutActions() {
    val next = soloLayerTarget(1)
    val previous = soloLayerTarget(-1)
    val nextLabel = jsCommandStringLiteral("Solo Next Layer: $next")
    val previousLabel = jsCommandStringLiteral("Solo Previous Layer: $previous")
    val nextDetail = jsCommandStringLiteral("Hide every other layer and make \"$next\" active. Shortcut: Alt+]")
    val previousDetail = jsCommandStringLiteral("Hide every other layer and make \"$previous\" active. Shortcut: Alt+[")

    allActions.layerNavigation = eval(
        """
        [{
            id:'solo-next-layer',
            label:$nextLabel,
            detail:$nextDetail,
            keybindings:(typeof monaco !== 'undefined') ? [(() => {
                const key = monaco.KeyCode.BracketRight || monaco.KeyCode.US_CLOSE_SQUARE_BRACKET || monaco.KeyCode.CloseBracket || 0;
                return key ? (monaco.KeyMod.Alt | key) : 0;
            })()].filter(Boolean) : [],
            canvasKeybinding: (event) => event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey && (event.key === ']' || event.code === 'BracketRight'),
            run: () => {
                window.soloNextLayer()
                if (typeof updateActions === 'function') updateActions()
            }
        },{
            id:'solo-previous-layer',
            label:$previousLabel,
            detail:$previousDetail,
            keybindings:(typeof monaco !== 'undefined') ? [(() => {
                const key = monaco.KeyCode.BracketLeft || monaco.KeyCode.US_OPEN_SQUARE_BRACKET || monaco.KeyCode.OpenBracket || 0;
                return key ? (monaco.KeyMod.Alt | key) : 0;
            })()].filter(Boolean) : [],
            canvasKeybinding: (event) => event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey && (event.key === '[' || event.code === 'BracketLeft'),
            run: () => {
                window.soloPreviousLayer()
                if (typeof updateActions === 'function') updateActions()
            }
        }]
    """.trimIndent()
    )
}

@OptIn(ExperimentalJsExport::class)
@JsExport()
fun insertFromWorkspace() {
    // Capture mouse position when the menu is opened
    val mouseX = lastMouse.first
    val mouseY = lastMouse.second

    sendWithReply(Message("request-templates").payload(Directory(command = "list")), "templates") { message, payload ->

        println(" request templates got $message $payload")

        val directory = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val options = eval("[]")

        // Parse template metadata if available
        val metadataList = try {
            directory.metadata?.let {
                Json.decodeFromJsonElement<List<TemplateMetadata>>(it)
            } ?: emptyList()
        } catch (e: Exception) {
            console.info("[templates] failed to parse template metadata: ${e.message}", e)
            emptyList()
        }

        val metadataMap = metadataList.associateBy { it.name }

        // Helper function to escape strings for JavaScript
        fun escapeJs(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u2028", "\\u2028")  // Unicode line separator
                .replace("\u2029", "\\u2029")  // Unicode paragraph separator
        }

        directory.value.forEach { templateName ->
            val metadata = metadataMap[templateName]

            // Format the description and detail
            val description = when (metadata?.source) {
                "workspace" -> "Workspace file"
                "workspace-template" -> "Workspace template"
                "bundled-template" -> "Bundled template"
                "remote-template" -> "Remote template"
                else -> "Template"
            }

            val detail = if (metadata != null && metadata.boxDocumentation.isNotEmpty()) {
                buildString {
                    // Add last modified date if available
                    if (metadata.lastModified > 0) {
                        // lastModified is already in milliseconds
                        val date = Date(metadata.lastModified)
                        append("Modified: ${date.toLocaleDateString()}\n\n")
                    }

                    // Add box documentation
                    append(metadata.boxDocumentation)
                }
            } else {
                "No documentation available"
            }

            val escapedTemplateName = escapeJs(templateName)
            val escapedDescription = escapeJs(description)
            val escapedDetail = escapeJs(detail)

            val e = """
            ({
                id:'insert-$escapedTemplateName', 
                label:'$escapedTemplateName', 
                description: '$escapedDescription',
                detail: '$escapedDetail',
                canvasKeybinding: (event) => false, 
                run: () => {
                    import("./afield.mjs").then( (afield) => {
                        afield.send("insert-template", {
                            command:"insert",
                            value:["$escapedTemplateName"],
                            mouseX: $mouseX,
                            mouseY: $mouseY
                        })
                    })
                }
            })
        """.trimIndent()
            options.push(eval(e))
        }

        if (directory.value.size > 0) {
            showPickMenu_custom(options)
        }

        canvasGestures.rebuildConnectionGroup()
        Handled.yes_remove
    }
}

fun openDocsSearch() {
    // Switch to documentation browser and load the search page
    switchToDocsIframe("/docs-search.html")
}

@OptIn(ExperimentalJsExport::class)
@JsExport()
fun saveAsTemplate() {
    val selectedIds = getSelectedIds()
    if (selectedIds.isEmpty()) {
        notify("No boxes selected", "error")
        return
    }

    // Call the helper function with the selected IDs; guard when helper is missing
    val helper = js("window._saveAsTemplateHelper")
    val hasHelper = js("typeof window._saveAsTemplateHelper === 'function'") as Boolean
    if (!hasHelper) {
        notify("Template helper is not available yet. Try again after Monaco loads.", "error")
        return
    }
    helper(selectedIds.toTypedArray())
}


@OptIn(ExperimentalJsExport::class)
@JsExport
fun editHashtag() {
    var selectedBoxes = selected.map { it.id }
    sendWithReply(
        Message("request-hashtag").payload(RequestHashtags(boxes = selectedBoxes, getOrSet = true)),
        "hashes"
    ) { message, payload ->

        println(" request templates got $message $payload")

        val directory = Json.decodeFromJsonElement<RequestHashtags>(message.payload!!)

        val options = eval("[]")


        Handled.yes_remove
    }
}

external fun updateActions(): Unit

@OptIn(ExperimentalJsExport::class)
@JsExport
@JsName("getSelectedIds")
fun getSelectedIds() = selected.map { it.unsafeCast<dynamic>().uid as String }.toList()

fun parseKeybindingSpec(spec: String): String? {
    val tokens = spec.lowercase().replace(",", "+").split('+', ' ', '\t').filter { it.isNotBlank() }
    val mods = mutableListOf<String>()
    var mainKey: String? = null
    for (raw in tokens) {
        val t = raw.trim()
        when (t) {
            "alt", "option" -> mods.add("monaco.KeyMod.Alt")
            "shift" -> mods.add("monaco.KeyMod.Shift")
            "ctrl", "control", "cmd", "command", "meta", "ctrlcmd" -> mods.add("monaco.KeyMod.CtrlCmd")
            else -> {
                val key = when {
                    t.length == 1 && t[0] in 'a'..'z' -> "monaco.KeyCode.Key" + t.uppercase()
                    t.length == 1 && t[0] in '0'..'9' -> "monaco.KeyCode.Digit" + t
                    t.startsWith("f") && t.drop(1).toIntOrNull() != null -> "monaco.KeyCode." + t.uppercase()
                    t == "enter" || t == "return" -> "monaco.KeyCode.Enter"
                    t == "escape" || t == "esc" -> "monaco.KeyCode.Escape"
                    t == "backspace" -> "monaco.KeyCode.Backspace"
                    t == "delete" || t == "del" -> "monaco.KeyCode.Delete"
                    t == "space" || t == "spacebar" -> "monaco.KeyCode.Space"
                    t == "tab" -> "monaco.KeyCode.Tab"
                    t == "left" || t == "arrowleft" -> "monaco.KeyCode.LeftArrow"
                    t == "right" || t == "arrowright" -> "monaco.KeyCode.RightArrow"
                    t == "up" || t == "arrowup" -> "monaco.KeyCode.UpArrow"
                    t == "down" || t == "arrowdown" -> "monaco.KeyCode.DownArrow"
                    else -> null
                }
                if (key != null) mainKey = key
            }
        }
    }
    if (mainKey == null && mods.isEmpty()) return null
    return (mods + listOfNotNull(mainKey)).joinToString(" | ")
}


fun refreshCommandsFromSelection(boxes: List<HTMLElement>, selection: List<List<Action_Desc>>) {
    val all = eval("[]")
    val selId = selected.map { it.unsafeCast<dynamic>().uid as String }.map { "'$it'" }.joinToString(",")
    val allBoxes = allBoxesOnCanvas()
    val hasNestedBoxes = allBoxes.any { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    val hasSelectedNestedBoxes = boxes.any { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }

    fun escapeJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
    fun jsStringLiteral(value: String): String = JSON.stringify(value)

    selection.flatMap { it }.associateBy { it.id }.toMap().entries.forEach {
        val fullDesc = it.value.description
        val pipeIndex = fullDesc.indexOf('|')
        val rawLabel = if (pipeIndex >= 0) fullDesc.substring(0, pipeIndex).trim() else fullDesc
        val label = escapeJsString(rawLabel)
        val kbSpec = if (pipeIndex >= 0) fullDesc.substring(pipeIndex + 1) else ""
        val detail = it.value.detail.ifBlank { "" }
        val detailEscaped = escapeJsString(detail)
        val docHtml = jsStringLiteral(it.value.docHtml)
        val docJs = jsStringLiteral(it.value.docJs)

        val usesContextualShaderUpdateShortcut =
            it.key == "moduleHere_update" || it.key == "computeModuleHere_update"
        val kbExpr = if (usesContextualShaderUpdateShortcut) null else parseKeybindingSpec(kbSpec.trim())
        val kbLine = if (kbExpr != null) "keybindings: [$kbExpr]," else "keybindings: [],"

        val e = """
            ({
                id:'${it.key}', label:'$label', 
                detail:'$detailEscaped',
                docHtml:$docHtml,
                docJs:$docJs,
                $kbLine
                canvasKeybinding: (event) => false, 
                run: () => { 
                    clearErrors(globalEditor)                
                    
                    import("./afield.mjs").then( (afield) => {
                        afield.send("perform-action", {
                                uid_action: '${it.key}',
                                uid_currentSelection: getSelectedIds()
                        })
                        
                    })}
            })
        """.trimIndent()
        val a = eval(e)
        all.push(a)
    }

    if (boxes.size >= 1) {
        run {
            val e = """
            ({
                id:'fmt-property', label:'Format', 
                detail:'Format the current property with the backend formatter',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-format-property',
                                uid_currentSelection: [${selId}],
                                uid_property: window.currentSelectedProperty || window.selectedProperty || 'code'
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }

        run {
            val e = """
            ({
                id:'fmt-kotlin', label:'Format Kotlin', 
                detail:'Fix the formatting of your code',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-format-kotlin',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }

        run {
            val e = """
            ({
                id:'-copy', label:'Duplicate box(es)', 
                detail:'Duplicate the currently selected boxes',
                canvasKeybinding: (event) => (event.key=='D'||event.key=='d') && event.shiftKey && (event.ctrlKey || event.metaKey) && !event.altKey, 
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_D],
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-copy',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }

        run {
            val e = """
            ({
                id:'export-selection-edn', label:'Export selection (.edn)', 
                detail:'Downloads the selected boxes as a self contained .edn file',
                canvasKeybinding: (event) => false,
                run: () => { 
                    exportSelectedBoxesToDownload()
                }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    if (boxes.size >= 2) {
        val sourceName = (boxes.first().unsafeCast<dynamic>().name as? String)?.trim().orEmpty()
        val targetNames = boxes.drop(1).map { (it.unsafeCast<dynamic>().name as? String)?.trim().orEmpty() }
        val safeSource = sourceName.ifBlank { "box 1" }
        val safeTargets = targetNames.map { it.ifBlank { "..." } }

        val targetLabel = when {
            safeTargets.isEmpty() -> ""
            safeTargets.size <= 3 -> safeTargets.joinToString(", ")
            else -> safeTargets.take(2).joinToString(", ") + ", +${safeTargets.size - 2} more"
        }

        val connectLabel = if (targetLabel.isNotEmpty()) "Connect '$safeSource' to '$targetLabel'" else "Connect '$safeSource'"
        val disconnectLabel =
            if (targetLabel.isNotEmpty()) "Disconnect '$safeSource' from '$targetLabel'" else "Disconnect '$safeSource'"
        val nestLabel = if (targetLabel.isNotEmpty()) "Nest '$targetLabel' into '$safeSource'" else "Nest into '$safeSource'"

        run {
            val e = """
            ({
                id:'connect', label:${jsStringLiteral(connectLabel)}, 
                detail:${jsStringLiteral("Shared selected variables from one box to another")},
                canvasKeybinding: (event) => event.key=='g' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-connect',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'nest-into', label:${jsStringLiteral(nestLabel)}, 
                detail:${jsStringLiteral("Hide one box 'inside' the nest of another box")},
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-nest-into',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'disconnect', label:${jsStringLiteral(disconnectLabel)}, 
                detail:${jsStringLiteral("Stop sharing variables between these two boxes")},
                canvasKeybinding: (event) => event.key=='u' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-disconnect',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }

    if (activeLayer != null) {
        val layerLabel = activeLayer!!
        val countLabel = if (boxes.size == 1) "1 box" else "${boxes.size} boxes"
        val moveLabel = "Move $countLabel to active layer \"$layerLabel\""
        run {
            val e = """
                ({
                    id:'move-to-active-layer', label:${jsStringLiteral(moveLabel)},
                    detail:${jsStringLiteral("Assign selected box"+(if (boxes.size>1) "es" else "")+" to the current active layer")},
                    canvasKeybinding: (event) => false,
                    run: () => {
                        const layer = ${jsStringLiteral(layerLabel)};
                        if (!layer) return;
                        import("./afield.mjs").then((afield) => {
                            const ids = (typeof getSelectedIds === 'function') ? getSelectedIds() : [];
                            ids.forEach((id) => {
                                afield.send("set-string", { uid: id, property: "layers", value: layer });
                                const el = document.querySelector('.box[uid="' + id + '"]');
                                if (Entities.el) { Entities.el.box_layers = layer; }
                            });
                            if (typeof updateAllBoxVisibility === 'function') {
                                updateAllBoxVisibility(true, 300);
                            }
                        });
                    }
                })
            """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    run {
        val e = """
            ({
                id:'-update-check', label:'Check for updates & download', 
                detail:'Check the shared server for updates to Field and download them',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-update-check',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
        val a = eval(e)
        all.push(a)
    }
    if (boxes.isNotEmpty()) {
        run {
            val e = """
            ({
                id:'normalize-nested-pan', label:'Normalize Nested contents', 
                detail:'Reset nest offset so that the boxes appear in the current position when the document is opened',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    normalizeNestedPanForSelection()
                }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'un-nest', label:'Un-nest selection', 
                detail:'Bring a nested box out of it\'s nest',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-un-nest',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            console.log(e)
            val a = eval(e)
            all.push(a)
        }
    }
    run {
        val e = """
        ({
            id:'select-all-boxes', label:'Select All Boxes',
            detail:'Select every box on the canvas',
            canvasKeybinding: (event) => false,
            run: () => { selectAllBoxes() }
        })
    """.trimIndent()
        val a = eval(e)
        all.push(a)
    }
    if (hasNestedBoxes) {
        run {
            val e = """
            ({
                id:'select-all-nested', label:'Select all nested',
                detail:'Select all boxes that are currently nested inside another box',
                canvasKeybinding: (event) => false,
                run: () => { selectAllNestedBoxes() }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    if (hasSelectedNestedBoxes) {
        run {
            val e = """
            ({
                id:'connect-nest-parent-to-selection', label:'Connect from nest parent to selection',
                detail:'For each selected nested box, connect its nest parent to it',
                canvasKeybinding: (event) => false,
                run: () => { connectFromNestParentToSelection() }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    allActions.fromSelection = all
    updateActions()
}

fun registerBuiltinCommands() {
    // Expose openEditorInOutput to window so inline JS commands can call it
    val w = js("window")
    w.openEditorInOutput = ::openEditorInOutput
    w.recordSelectedBoxMp4 = ::recordSelectedBoxMp4
    w.recordSelectedBoxEditorMp4 = ::recordSelectedBoxEditorMp4
    w.recordWindowMp4 = ::recordWindowMp4
    w.soloNextLayer = ::soloNextLayer
    w.soloPreviousLayer = ::soloPreviousLayer
    w.refreshLayerShortcutActions = ::refreshLayerShortcutActions
    w.openUserThemeProxyBox = ::openUserThemeProxyBox
    w.macroTranscriptMarkStart = ::macroTranscriptMarkStart
    w.macroTranscriptMarkEndAndSave = ::macroTranscriptMarkEndAndSave
    w.macroTranscriptRunOnSelection = ::macroTranscriptRunOnSelection
    w.macroTranscriptUndoOnSelection = ::macroTranscriptUndoOnSelection
    w.macroTranscriptPanel = ::macroTranscriptPanel
    w.showDocumentHistory = ::showDocumentHistory
    w.macroPaletteList = ::macroPaletteList
    w.macroPaletteRun = ::macroPaletteRun

    allActions.builtin = eval(
        """
        [{
            id:'new-box',
            label:"New Box",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyCode.KEY_N] : [],
            detail:"Make a new box under the mouse",
            canvasKeybinding: (event) => {
                let a = event.key=='n' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey
                return a
            },
            run: () => {
                newBox()
            }
        },{
            id:'set-main-language',
            label:"Set Main Language",
            detail:"Choose the language used by child boxes of the selected language root",
            canvasKeybinding: (event) => false,
            run: () => {
                if (typeof window.openFieldCommandPalette === 'function') {
                    window.openFieldCommandPalette({ includeEditor: false, editor: window.globalEditor, query: 'Set Main Language:' })
                }
            }
        },{
            id:'macro-transcript-panel',
            label:"Macro: Recorder Panel",
            detail:"Open the macro recorder and browser",
            canvasKeybinding: (event) => false,
            run: () => {
                macroTranscriptPanel()
            }
        },{
            id:'macro-transcript-start',
            label:"Macro: Mark Start",
            detail:"Insert a start marker into the operation transcript",
            canvasKeybinding: (event) => false,
            run: () => {
                macroTranscriptMarkStart()
            }
        },{
            id:'macro-transcript-save',
            label:"Macro: Mark End & Save",
            detail:"Save transcript operations since the last start marker as a macro",
            canvasKeybinding: (event) => false,
            run: () => {
                macroTranscriptMarkEndAndSave()
            }
        },{
            id:'macro-transcript-run',
            label:"Macro: Run on Selection",
            detail:"Run a saved macro against the selected boxes",
            canvasKeybinding: (event) => false,
            run: () => {
                macroTranscriptRunOnSelection()
            }
        },{
            id:'macro-transcript-undo',
            label:"Macro: Undo on Selection",
            detail:"Run a saved macro backwards against the selected boxes",
            canvasKeybinding: (event) => false,
            run: () => {
                macroTranscriptUndoOnSelection()
            }
        },{
            id:'delete-box',
            label:"Delete Box(es)",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Backspace] : [],
            detail:"Delete the selected boxes",
            canvasKeybinding: (event) => (event.keyCode==8 || event.keyCode==46) && (event.ctrlKey || event.metaKey),
            run: () => {
                deleteSelectedBoxes()
            }
        },{
            id:'copy-selection',
            label:"Copy Selection",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_C] : [],
            detail:"Copy selected boxes (and all their code & properties)",
            canvasKeybinding: (event) => {
                const copyChord = (event.key=='c') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey)
                if (!copyChord) return false
                const sel = (window.getSelection && window.getSelection()) || null
                const hasDomSelection = !!(sel && sel.rangeCount > 0 && !sel.isCollapsed && (sel.toString() || "").length > 0)
                return !hasDomSelection
            },
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                if (window.embeddedEditors && window.embeddedEditors.getFocused && window.embeddedEditors.getFocused()) return
                const sel = (window.getSelection && window.getSelection()) || null
                const hasDomSelection = !!(sel && sel.rangeCount > 0 && !sel.isCollapsed && (sel.toString() || "").length > 0)
                if (hasDomSelection) return
                copySelectedBoxesToClipboard()
            }
        },{
            id:'paste-selection',
            label:"Paste Selection",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_V] : [],
            detail:"Paste as new boxes",
            canvasKeybinding: (event) => (event.key=='v') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey),
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                if (window.embeddedEditors && window.embeddedEditors.getFocused && window.embeddedEditors.getFocused()) return
                pasteBoxesFromClipboard()
            }
        },{
            id:'export-document-edn',
            label:"Export document (.edn)",
            detail:"Download a self-contained .edn file of the whole document",
            canvasKeybinding: (event) => false,
            run: () => {
                exportDocumentToDownload()
            }
        },{
            id:'move-forward',
            label:"Move selected boxes forward",
            detail:"Bring to front",
            run: () => {
                    moveSelectedBoxesForward()
            }
        },{
            id:'show-all-layers',
            label:"Show All Layers",
            detail:"Restore visibility for every layer and reopen the layers palette",
            canvasKeybinding: (event) => false,
            run: () => {
                showAllLayers()
            }
        },{
            id:'insert-from-workspace',
            label:"Insert template",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_T] : [],
            detail:"Search for boxes that you might want to add to this document",
            canvasKeybinding: (event) => (event.key=='t') && (event.ctrlKey || event.metaKey),
            run: () => {
                insertFromWorkspace()
            }
        },{
            id:'open-user-theme',
            label:"Open User Theme",
            detail:"Open ~/.afield/theme.css as an absolute file proxy",
            canvasKeybinding: (event) => false,
            run: () => {
                window.openUserThemeProxyBox()
            }
        },{
            id:'search-docs-and-code',
            label:"Search Documentation & Code",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_D] : [],
            detail:"Search Field4 documentation, code examples, and API references using AI",
            canvasKeybinding: (event) => (event.key=='D') && (event.ctrlKey || event.metaKey) && event.shiftKey,
            run: () => {
                window.switchToDocsIframe("/docs-search.html");
            }
        },{
            id:'edit-hashtag',
            label:"Edit hashtag",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_H] : [],
            detail:"Edit the metadata associated with this document",
            canvasKeybinding: (event) => (event.key=='h') && (event.ctrlKey || event.metaKey),
            run: () => {
                editHashtag()
            }
        },{
            id:'save-as-template',
            label:"Save selection as template",
            detail:"Make the selected boxes a new template in your workspace for sharing later",
            canvasKeybinding: (event) => false,
            run: () => {
                saveAsTemplate()
            }
        },{
            id:'provisional-accept-all',
            label:"Accept All Provisional (Everywhere)",
            detail:"If AI has proposed changes to your code (shown in orange) accept them all",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-accept-all", {})})
            }
        },{
            id:'provisional-reject-all',
            label:"Reject All Provisional (Everywhere)",
            detail:"If AI has proposed changes to your code (shown in orange) reject them all",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-reject-all", {})})
            }
        },{
            id:'switch-to-docs-iframe',
            label:"Switch to Documentation Iframe",
            detail:"Switch to the documentation mode",
            canvasKeybinding: (event) => false,
            run: () => {
                window.switchToDefaultDocs();
            }
        },{
            id:'show-document-history',
            label:"Show Document History",
            detail:"Switch to the history view mode",
            canvasKeybinding: (event) => false,
            run: () => {
                showDocumentHistory()
            }
        },{
            id:'switch-to-monaco-editor',
            label:"Switch to Monaco Editor",
            detail:"Switch back to editing code",
            canvasKeybinding: (event) => false,
            run: () => {
                window.switchToMonaco();
            }
        },{
            id:'toggle-notes-markdown-preview',
            label:"Toggle Notes Markdown Preview",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_M] : [],
            detail:"Switch the notes property between rendered Markdown and editable Markdown source",
            canvasKeybinding: (event) => (event.key=='M' || event.key=='m') && event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey),
            run: () => {
                if (typeof window.toggleNotesMarkdownPreview === 'function') {
                    window.toggleNotesMarkdownPreview(window.globalEditor || null);
                }
            }
        },{
            id:'add-editor-to-output',
            label:"Add Editor to Output Area",
            detail:"Open an embedded text editor for a property (e.g. fragment, vertex) in the selected box's output area",
            canvasKeybinding: (event) => false,
            run: () => {
                if (!window.selectedList || window.selectedList.length !== 1) {
                    if (typeof window.notify === 'function') window.notify('Select exactly one box', 'command');
                    return;
                }
                const box = window.selectedList[0];
                const uid = box.uid;
                if (!uid) return;
                const props = [
                    {label:'fragment',  description:'Fragment Shader (GLSL)'},
                    {label:'stackSL',   description:'StackSL Shader Function'},
                    {label:'vertex_fragment', description:'Shared Vertex+Fragment Shader (Slang)'},
                    {label:'fragment_s', description:'Fragment Shader (Slang)'},
                    {label:'vertex',    description:'Vertex Shader (GLSL)'},
                    {label:'vertex_s',  description:'Vertex Shader (Slang)'},
                    {label:'compute',   description:'Compute Shader (GLSL)'},
                    {label:'compute_s', description:'Compute Shader (Slang)'},
                    {label:'task',      description:'Task Shader (GLSL)'},
                    {label:'task_s',    description:'Task Shader (Slang)'},
                    {label:'mesh',      description:'Mesh Shader (GLSL)'},
                    {label:'mesh_s',    description:'Mesh Shader (Slang)'},
                    {label:'raygen',    description:'Ray Generation Shader (GLSL)'},
                    {label:'raygen_s',  description:'Ray Generation Shader (Slang)'},
                    {label:'miss',      description:'Miss Shader (GLSL)'},
                    {label:'miss_s',    description:'Miss Shader (Slang)'},
                    {label:'closesthit', description:'Closest Hit Shader (GLSL)'},
                    {label:'closesthit_s', description:'Closest Hit Shader (Slang)'},
                    {label:'anyhit',    description:'Any Hit Shader (GLSL)'},
                    {label:'anyhit_s',  description:'Any Hit Shader (Slang)'},
                    {label:'intersection', description:'Intersection Shader (GLSL)'},
                    {label:'intersection_s', description:'Intersection Shader (Slang)'},
                    {label:'callable',  description:'Callable Shader (GLSL)'},
                    {label:'callable_s', description:'Callable Shader (Slang)'},
                    {label:'preamble',  description:'Kotlin (preamble)'},
                    {label:'introduction', description:'Kotlin (introduction)'},
                    {label:'attic',     description:'Kotlin (attic)'},
                    {label:'metadata',  description:'Metadata (YAML)'},
                    {label:'code',      description:'Kotlin (main)'},
                ];
                if (typeof window.showPickMenu_custom === 'function') {
                    window.showPickMenu_custom(props.map(p => ({
                        label: '✎ ' + p.label,
                        detail: p.description,
                        run: () => { window.openEditorInOutput(uid, p.label); }
                    })));
                } else {
                    window.openEditorInOutput(uid, 'fragment');
                }
            }
        },{
            id:'record-selected-box-mp4',
            label:"Record Selected Box MP4",
            detail:"Record a short MP4 clipped to the selected box and its outputs",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordSelectedBoxMp4();
            }
        },{
            id:'record-selected-editor-mp4',
            label:"Record Selected Editor MP4",
            detail:"Record a short MP4 clipped to the selected box's Monaco editor",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordSelectedBoxEditorMp4();
            }
        },{
            id:'record-window-mp4',
            label:"Record Window MP4",
            detail:"Record a short MP4 of the current Field window",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordWindowMp4();
            }
        }]
    """.trimIndent()
    )
    updateActions()

    onSelectedChange["refreshCommands"] = {
        refreshCommandsFromSelection(
            selected.toList(),
            selected.mapNotNull { it.unsafeCast<dynamic>().injectedActions as List<Action_Desc>? ?: emptyList() }
                .toList())
        true
    }
}

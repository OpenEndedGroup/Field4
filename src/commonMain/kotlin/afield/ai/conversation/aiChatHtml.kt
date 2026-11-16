package afield.ai.conversation

import afield.app.protocol.send

/*
 * The AI4 chat panel's markup, as HTML. Shared so a page served by the JVM shows a transcript exactly as the panel
 * does (`/ai-tool-use-improvement.html` through `ai-chat-view.html`); the panel reaches it through the @JsExport
 * wrappers in jsMain (OutputAreas2Utils.kt), and both style it with field.css.
 */

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/**
 * A code span, or math: `$$…$$` and `\[…\]` display, `\(…\)` inline, and `$…$` inline only when the dollars
 * hug their content and the closing one is not followed by a digit (Pandoc's rule), so "$5 and $10" stays
 * prose. The first match wins, so a `$` inside a code span is code and a backtick inside math is TeX.
 */
private val literalSpan = Regex(
    "`([^`]+)`" +
        "|\\$\\$(.+?)\\$\\$" +
        "|\\\\\\[(.+?)\\\\\\]" +
        "|\\\\\\((.+?)\\\\\\)" +
        "|(?<![\\\\$])\\$([^\\s$](?:[^$]*[^\\s\\\\$])?)\\$(?![$0-9])"
)

/**
 * Code spans, math, emphasis, images and links. A code span is literal: the `*` in `` `sin(r * 3.6)` `` is
 * multiplication, so emphasis and links are only looked for between code spans, never inside or across one.
 * Math is literal the same way. It leaves here as `<span class="ai-math">` holding its source, delimiters and
 * all, with the TeX in `data-tex`; the browser typesets it (aiChatMath.js), and anywhere that does not still
 * reads the source.
 */
private fun inlineFormat(text: String): String {
    val out = StringBuilder(text.length + 16)
    var index = 0
    for (span in literalSpan.findAll(text)) {
        out.append(formatProse(text.substring(index, span.range.first)))
        val groups = span.groupValues
        if (groups[1].isNotEmpty()) {
            out.append("<code>").append(groups[1]).append("</code>")
        } else {
            val display = groups[2].isNotEmpty() || groups[3].isNotEmpty()
            val tex = (2..5).map { groups[it] }.first { it.isNotEmpty() }.trim()
            val kind = if (display) "ai-math ai-math-display" else "ai-math"
            out.append("<span class=\"").append(kind).append("\" data-tex=\"").append(tex).append("\">")
                .append(span.value).append("</span>")
        }
        index = span.range.last + 1
    }
    out.append(formatProse(text.substring(index)))
    return out.toString()
}

private fun formatProse(text: String): String {
    var out = text
    out = out.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
    out = out.replace(Regex("\\*([^*\\s][^*]*)\\*"), "<em>$1</em>")
    out = replaceImages(out)
    out = replaceLinks(out)
    return out
}

private fun replaceImages(text: String): String {
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val open = text.indexOf("![", index)
        if (open == -1) {
            sb.append(text.substring(index))
            break
        }
        val close = text.indexOf(']', open + 2)
        if (close == -1 || close + 1 >= text.length || text[close + 1] != '(') {
            sb.append(text.substring(index, open + 2))
            index = open + 2
            continue
        }
        val end = text.indexOf(')', close + 2)
        if (end == -1) {
            sb.append(text.substring(index, open + 2))
            index = open + 2
            continue
        }
        val alt = text.substring(open + 2, close)
        val src = text.substring(close + 2, end)
        if (src.isEmpty()) {
            sb.append(text.substring(index, open + 2))
            index = open + 2
            continue
        }
        sb.append(text.substring(index, open))
        sb.append("<img class=\"ai-chat-inline-image\" src=\"")
        sb.append(src)
        sb.append("\" alt=\"")
        sb.append(alt)
        // `loading="lazy"` earns nothing on a `data:` URL -- the bytes are already inline, so there is
        // no fetch to defer -- and it actively hurts: a screenshot below the fold stays unloaded, so it
        // contributes no height and never fires the `load` that `renderChatMessages` settles the scroll
        // on. Deferring stays worthwhile for a real URL.
        if (!src.startsWith("data:")) sb.append("\" loading=\"lazy")
        sb.append("\" />")
        index = end + 1
    }
    return sb.toString()
}

private fun replaceLinks(text: String): String {
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val open = text.indexOf('[', index)
        if (open == -1) {
            sb.append(text.substring(index))
            break
        }
        val close = text.indexOf(']', open + 1)
        if (close == -1 || close + 1 >= text.length || text[close + 1] != '(') {
            sb.append(text.substring(index, open + 1))
            index = open + 1
            continue
        }
        val end = text.indexOf(')', close + 2)
        if (end == -1) {
            sb.append(text.substring(index, open + 1))
            index = open + 1
            continue
        }
        val label = text.substring(open + 1, close)
        val url = text.substring(close + 2, end)
        if (label.isEmpty() || url.isEmpty()) {
            sb.append(text.substring(index, open + 1))
            index = open + 1
            continue
        }
        sb.append(text.substring(index, open))
        sb.append("<a href=\"")
        sb.append(url)
        if (url.startsWith("field:", ignoreCase = true)) sb.append("\">")
        else sb.append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
        sb.append(label)
        sb.append("</a>")
        index = end + 1
    }
    return sb.toString()
}

private val bulletMarker = Regex("^[-*]\\s+")
private val numberMarker = Regex("^\\d+[.)]\\s+")

/**
 * Paragraphs, headings, tables and lists, for the chat panel and for box notes. A list is `-` or `*`
 * items (`<ul>`) or `1.` / `1)` items (`<ol>`); an indented line after an item continues that item,
 * so a list item may wrap over several source lines as Markdown allows.
 */
private fun renderTextBlock(text: String): String {
    val lines = text.split("\n")
    val html = StringBuilder()
    // "ul", "ol", or null when not in a list
    var listTag: String? = null
    val item = mutableListOf<String>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        html.append("<p>")
        html.append(inlineFormat(paragraph.joinToString(" ")))
        html.append("</p>")
        paragraph.clear()
    }

    fun flushItem() {
        if (item.isEmpty()) return
        html.append("<li>")
        html.append(inlineFormat(item.joinToString(" ")))
        html.append("</li>")
        item.clear()
    }

    fun closeList() {
        flushItem()
        if (listTag != null) {
            html.append("</").append(listTag).append(">")
            listTag = null
        }
    }

    var lineIndex = 0
    while (lineIndex < lines.size) {
        val raw = lines[lineIndex]
        val line = raw.trim()
        if (line.isEmpty()) {
            flushParagraph()
            closeList()
            lineIndex++
            continue
        }

        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (headingMatch != null) {
            flushParagraph()
            closeList()
            val level = headingMatch.groupValues[1].length
            html.append("<h")
            html.append(level)
            html.append(">")
            html.append(inlineFormat(headingMatch.groupValues[2]))
            html.append("</h")
            html.append(level)
            html.append(">")
            lineIndex++
            continue
        }

        if (lineIndex + 1 < lines.size && looksLikeMarkdownTableHeader(line, lines[lineIndex + 1].trim())) {
            flushParagraph()
            closeList()
            val tableLines = mutableListOf(line, lines[lineIndex + 1].trim())
            lineIndex += 2
            while (lineIndex < lines.size) {
                val tableLine = lines[lineIndex].trim()
                if (tableLine.isEmpty() || !looksLikeMarkdownTableRow(tableLine)) break
                tableLines.add(tableLine)
                lineIndex++
            }
            html.append(renderMarkdownTable(tableLines))
            continue
        }

        val marker = bulletMarker.find(line) ?: numberMarker.find(line)
        if (marker != null) {
            flushParagraph()
            val tag = if (bulletMarker.containsMatchIn(line)) "ul" else "ol"
            if (listTag != tag) {
                closeList()
                html.append("<").append(tag).append(">")
                listTag = tag
            }
            flushItem()
            item.add(line.substring(marker.range.last + 1))
            lineIndex++
            continue
        }

        // an indented line straight after an item is more of that item
        if (listTag != null && item.isNotEmpty() && raw.firstOrNull()?.isWhitespace() == true) {
            item.add(line)
            lineIndex++
            continue
        }

        closeList()
        paragraph.add(line)
        lineIndex++
    }

    flushParagraph()
    closeList()
    return html.toString()
}

private fun looksLikeMarkdownTableRow(line: String): Boolean {
    return line.contains("|") && splitMarkdownTableRow(line).size >= 2
}

private fun looksLikeMarkdownTableHeader(header: String, divider: String): Boolean {
    val headerCells = splitMarkdownTableRow(header)
    val dividerCells = splitMarkdownTableRow(divider)
    return headerCells.size >= 2 &&
        dividerCells.size >= headerCells.size &&
        dividerCells.all { isMarkdownTableDividerCell(it) }
}

private fun splitMarkdownTableRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith("|")) row = row.substring(1)
    if (row.endsWith("|")) row = row.substring(0, row.length - 1)
    return row.split("|").map { it.trim() }
}

private fun isMarkdownTableDividerCell(cell: String): Boolean {
    return Regex("^:?-{3,}:?$").matches(cell.trim())
}

private fun renderMarkdownTable(lines: List<String>): String {
    val headerCells = splitMarkdownTableRow(lines[0])
    val alignments = splitMarkdownTableRow(lines[1]).map {
        val cell = it.trim()
        when {
            cell.startsWith(":") && cell.endsWith(":") -> "center"
            cell.endsWith(":") -> "right"
            else -> ""
        }
    }
    val columnCount = headerCells.size
    val html = StringBuilder()

    fun appendCell(tag: String, content: String, column: Int) {
        val align = alignments.getOrNull(column).orEmpty()
        html.append("<")
        html.append(tag)
        if (align.isNotEmpty()) {
            html.append(" style=\"text-align:")
            html.append(align)
            html.append("\"")
        }
        html.append(">")
        html.append(inlineFormat(content))
        html.append("</")
        html.append(tag)
        html.append(">")
    }

    html.append("<table><thead><tr>")
    headerCells.forEachIndexed { column, cell -> appendCell("th", cell, column) }
    html.append("</tr></thead><tbody>")

    lines.drop(2).forEach { row ->
        val cells = splitMarkdownTableRow(row)
        html.append("<tr>")
        for (column in 0 until columnCount) {
            appendCell("td", cells.getOrNull(column).orEmpty(), column)
        }
        html.append("</tr>")
    }

    html.append("</tbody></table>")
    return html.toString()
}

private fun renderMarkdown(markdown: String?): String {
    if (markdown == null) return ""
    val normalized = markdown.replace("\r\n", "\n")
    val blocks = normalized.split("```")
    val html = StringBuilder()
    blocks.forEachIndexed { index, block ->
        if (index % 2 == 1) {
            var trimmed = block
            if (trimmed.startsWith("\n")) trimmed = trimmed.substring(1)
            val firstNewline = trimmed.indexOf("\n")
            var language = ""
            var code = trimmed
            if (firstNewline != -1) {
                val possibleLang = trimmed.substring(0, firstNewline).trim()
                val rest = trimmed.substring(firstNewline + 1)
                if (possibleLang.isNotEmpty() && !possibleLang.contains(" ") && rest.isNotEmpty()) {
                    language = possibleLang
                    code = rest
                }
            }
            html.append(aiChatCodeBlockHtml(code, language, closed = index < blocks.lastIndex))
        } else {
            val escaped = escapeHtml(block)
            html.append(renderTextBlock(escaped))
        }
    }
    return html.toString()
}

/** One fenced block as the panel shows it: a copy button over `<pre><code class="language-…">`, highlighted client-side. */
fun aiChatCodeBlockHtml(code: String, language: String = "", closed: Boolean = true): String {
    val safeLang = escapeHtml(language)
    val langClass = if (safeLang.isNotEmpty()) " class=\"language-$safeLang\"" else ""
    val langAttr = if (safeLang.isNotEmpty()) " data-lang=\"$safeLang\"" else ""
    val closedAttr = if (closed) " data-fence-closed=\"true\"" else ""
    return "<div class=\"ai-chat-code-block\"$langAttr$closedAttr>" +
        "<div class=\"ai-chat-copy-wrap\"><button class=\"ai-chat-copy\" type=\"button\" aria-label=\"Copy\" title=\"Copy\"></button></div>" +
        "<pre><code$langClass>${escapeHtml(code.removeSuffix("\n"))}</code></pre></div>"
}

// The transcript parser is shared with the chat archive: parseAiChatBlocks (commonMain, aiChatBlocks.kt).

fun aiChatMarkdownHtml(markdown: String?): String {
    return "<div class=\"ai-chat-markdown\">" + renderMarkdown(markdown) + "</div>"
}

/**
 * A whole transcript as the panel shows it. [toolFamilies] (`ai4-chat-tool-families.json`) lets a round's summary
 * count by family; without it a summary gives calls, time and failures only.
 */
fun aiChatHistoryHtml(history: String?, toolFamilies: AiChatToolFamilies?): String {
    val units = aiChatHistoryUnits(history, toolFamilies)
    if (units.isEmpty()) return ""
    return "<div class=\"ai-chat-thread\">${units.joinToString("")}</div>"
}

/**
 * The transcript as [aiChatHistoryHtml] renders it, one string per top-level unit (a turn, a round, a run of
 * progress, a question, a build wait) and without the `ai-chat-thread` wrapper. A unit's position and HTML
 * change only when its own content does, so the panel (outputareas2.js, renderChatMessages) rebuilds just the
 * units that differ from the last render instead of the whole thread. [markdown] renders a block's Markdown;
 * the panel passes a memoized one so unchanged turns are not re-parsed either.
 */
fun aiChatHistoryUnits(
    history: String?,
    toolFamilies: AiChatToolFamilies?,
    markdown: (String?) -> String = ::aiChatMarkdownHtml,
): List<String> = aiChatHistoryUnits(parseAiChatBlocks(history), toolFamilies, markdown)

/**
 * The units of the last render, by position, for [aiChatHistoryUnits] to reuse: a unit whose blocks (compared by
 * reference first, so the blocks a resumed [parseAiChatHistory] carries over cost nothing) and numbering are
 * unchanged keeps its HTML.
 */
class AiChatUnitCache {
    internal var keys: List<Any> = emptyList()
    internal var htmls: List<String> = emptyList()
}

private data class UnitKey(val blocks: List<AiChatBlock>, val ordinal: Int, val open: Boolean, val toolFamilies: AiChatToolFamilies?)

/** [aiChatHistoryUnits] over blocks already parsed, e.g. by a resumed [parseAiChatHistory]. */
fun aiChatHistoryUnits(
    turns: List<AiChatBlock>,
    toolFamilies: AiChatToolFamilies?,
    markdown: (String?) -> String = ::aiChatMarkdownHtml,
    cache: AiChatUnitCache? = null,
): List<String> {
    if (turns.isEmpty()) {
        cache?.keys = emptyList(); cache?.htmls = emptyList()
        return emptyList()
    }
    val units = mutableListOf<String>()
    val keys = mutableListOf<Any>()
    fun unit(blocks: List<AiChatBlock>, ordinal: Int = -1, open: Boolean = false, render: () -> String) {
        val key = UnitKey(blocks.toList(), ordinal, open, toolFamilies)
        val at = units.size
        val html = if (cache != null && cache.keys.getOrNull(at) == key) cache.htmls[at] else render()
        units += html
        keys += key
    }
    // A question is open while nobody has written after it: everything before the last "you" turn is answered.
    val lastYou = turns.indexOfLast { it.role == "you" }
    // The server's session tree numbers user turns the same way (AiSessionTree.userTurns); the text goes with
    // the ordinal so a rewind from a stale panel is refused rather than applied to the wrong turn.
    var userOrdinal = 0
    // Rounds are numbered in transcript order, so a round keeps its number as the chat grows and the panel can
    // carry its open/closed state across re-renders (outputareas2.js, renderChatMessages).
    var roundOrdinal = 0
    var i = 0
    while (i < turns.size) {
        val turn = turns[i]
        if (turn.isProgress) {
            // A build wait stands on its own: it is what the conversation is doing now, not one more tool call.
            if (turn.isBuildWait) {
                unit(listOf(turn)) { renderProgressTurn(turn, markdown) }
                i++
                continue
            }
            // So does a question; only the last one in the chat is still waiting for its answer.
            if (turn.isQuestion) {
                val open = lastYou < i
                unit(listOf(turn), open = open) { renderQuestion(turn, open, markdown) }
                i++
                continue
            }
            val end = (i until turns.size).firstOrNull { !turns[it].isProgress || turns[it].isBuildWait || turns[it].isQuestion } ?: turns.size
            val run = turns.subList(i, end)
            if (AiChatRound.folds(run)) {
                val ordinal = roundOrdinal++
                unit(run, ordinal) { renderRound(run, ordinal, toolFamilies, markdown) }
            } else {
                unit(run) { renderProgressRun(run, markdown) }
            }
            i = end
            continue
        }
        i++
        if (turn.role == "queued") {
            // Sent while the turn runs (AiTurnInbox on the server): not part of the conversation until the
            // agent reads it, so it has no ordinal and no rewind/edit.
            unit(listOf(turn)) {
                "<div class=\"ai-chat-turn you queued\">" +
                    "<div class=\"ai-chat-queued-label\">queued: the agent reads this after its current step</div>" +
                    markdown(turn.content) + "</div>"
            }
        } else {
            val ordinal = if (turn.role == "you") userOrdinal++ else -1
            unit(listOf(turn), ordinal) { renderTurn(turn, ordinal, markdown) }
        }
    }
    cache?.keys = keys
    cache?.htmls = units
    return units
}

/** An ordinary turn (yours, the model's, a summary, or one Field wrote); [userOrdinal] numbers yours, else -1. */
private fun renderTurn(turn: AiChatBlock, userOrdinal: Int, markdown: (String?) -> String): String {
    val badge = if (turn.isFieldMessage) {
        "<div class=\"ai-chat-model\" title=\"Written by Field, not by you: the outcome of the builds the last turn waited for.\">Field</div>"
    } else if (turn.role == "ai" || turn.role == "summary") {
        // A lens block (it cites the chat it summarizes) is labelled as one; its record names the model.
        val lens = turn.model == null && turn.sources.isNotEmpty()
        val label = turn.model?.label()?.let { if (turn.modelFromRun) "$it (from the run's record)" else it }
            ?: if (lens) "Lens" else "Model not recorded"
        val title = turn.model?.let { m ->
            if (turn.modelFromRun) "This entry predates per-response model records; the run it belongs to is recorded as ${m.requestedModel} on ${m.provider}."
            else m.description()
        }
            ?: if (lens) "Written as a lens over a longer chat; it stands for ${turn.sources.joinToString(", ")}."
            else "This older chat entry did not record its provider or model."
        "<div class=\"ai-chat-model\" title=\"${escapeHtml(title)}\">${escapeHtml(label)}</div>"
    } else ""
    val turnAttrs = if (turn.role == "you") {
        " data-ai-turn=\"$userOrdinal\" data-ai-text=\"${escapeHtml(turn.content)}\""
    } else ""
    val actions = if (turn.role == "you") {
        "<span class=\"ai-chat-turn-actions\">" +
            "<button type=\"button\" class=\"ai-chat-turn-action\" data-ai-action=\"edit\" title=\"Edit this message and send it again (the rest is kept as a branch)\">edit</button>" +
            "<button type=\"button\" class=\"ai-chat-turn-action\" data-ai-action=\"rewind\" title=\"Rewind to before this message (the rest is kept as a branch)\">rewind</button>" +
            "</span>"
    } else ""
    // A lens block's citations, for the viewer that draws a line from it to the chat it summarizes.
    val sourceAttr = if (turn.sources.isNotEmpty()) " data-ai-sources=\"${escapeHtml(turn.sources.joinToString(","))}\"" else ""
    val roleClass = if (turn.isFieldMessage) "you field" else turn.role
    // The badge says Field wrote it; the model reads the prefix, the panel need not show it twice.
    val shown = if (turn.isFieldMessage) turn.content.removePrefix(AiChatBlock.FIELD_MESSAGE_PREFIX).replaceFirstChar { it.uppercase() }
        else turn.content
    return "<div class=\"ai-chat-turn $roleClass\"$turnAttrs$sourceAttr${addressAttr(turn)}>" + badge + (if (turn.role == "you") geometryReferenceTurnHtml(shown, markdown) else markdown(shown)) + actions + "</div>"
}

/**
 * A user turn whose top lines are GeometryReference headers (a three.js viewer's Ask AI): each header, and the
 * hint line under it, shows as the same chip the composer showed, with an "open at line" button when the part
 * carries its source line (outputareas2.js opens it). The rest of the turn is ordinary markdown.
 */
internal fun geometryReferenceTurnHtml(content: String, markdown: (String?) -> String = { aiChatMarkdownHtml(it.orEmpty()) }): String {
    val lines = content.lines()
    val chips = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val ref = afield.app.protocol.GeometryReferenceAsk.parseHeader(lines[i]) ?: break
        chips.append(geometryReferenceChipHtml(ref))
        i++
        if (lines.getOrNull(i)?.trim() == afield.app.protocol.GeometryReferenceAsk.HEADER_HINT) i++
    }
    if (i == 0) return markdown(content)
    val rest = lines.drop(i).joinToString("\n").trim()
    return "<div class=\"ai-chat-geometry-refs\">$chips</div>" + (if (rest.isEmpty()) "" else markdown(rest))
}

/** One quoted pick, as outputareas2.js's `geometryReferenceChip` draws it. */
internal fun geometryReferenceChipHtml(ref: afield.app.protocol.GeometryReferenceAsk): String {
    val what = listOfNotNull(ref.label ?: ref.partId, ref.kind, ref.element).filter { it.isNotBlank() }.joinToString(" · ")
    val note = if (ref.note.isBlank()) "" else "<em> “${escapeHtml(ref.note)}”</em>"
    val open = ref.sourceLine?.takeIf { it.line > 0 }?.let { at ->
        "<button type=\"button\" class=\"ai-chat-geometry-open\" data-uid=\"${escapeHtml(at.box)}\" data-property=\"${escapeHtml(at.property)}\" " +
            "data-line=\"${at.line}\" title=\"Open the code at the line that drew this part\">line ${at.line}${at.variable?.let { " · " + escapeHtml(it) } ?: ""}</button>"
    } ?: ""
    return "<span class=\"ai-chat-geometry-chip\" title=\"${escapeHtml(ref.view + " · " + ref.revision)}\">${escapeHtml(what.ifEmpty { "geometry" })}$note$open</span>"
}

/**
 * Progress blocks as they render on their own: a run of title-only blocks shares one status row, anything with
 * a body is its own collapsible block.
 */
private fun renderProgressRun(run: List<AiChatBlock>, markdown: (String?) -> String): String {
    val html = StringBuilder()
    var statusRowOpen = false
    for (turn in run) {
        if (turn.isStatusOnly) {
            if (!statusRowOpen) {
                html.append("<div class=\"ai-chat-status-row\" aria-label=\"AI activity\">")
                statusRowOpen = true
            }
            html.append("<span class=\"ai-chat-status\"${addressAttr(turn)}>${shimmerIfLive(turn, escapeHtml(turn.title.ifBlank { "AI activity" }))}</span>")
            continue
        }
        if (statusRowOpen) {
            html.append("</div>")
            statusRowOpen = false
        }
        html.append(renderProgressTurn(turn, markdown))
    }
    if (statusRowOpen) html.append("</div>")
    return html.toString()
}

/**
 * A run of tool activity between two pieces of text, folded to one line: how many calls, how long they took,
 * how many failed, and the families worth naming (notes, recipe calls, edits, build steps, looks). The run's
 * own rendering is inside. While the turn runs, the live item's title follows the summary, shimmering, so
 * the round can stay closed and still show what is happening. [AiChatRound] (commonMain) does the counting,
 * so the chat archive describes a round in the same words.
 */
private fun renderRound(run: List<AiChatBlock>, ordinal: Int, toolFamilies: AiChatToolFamilies?, markdown: (String?) -> String): String {
    val round = AiChatRound(run, toolFamilies)
    val parts = mutableListOf(round.callsText())
    if (round.ms > 0) parts += formatAiChatDuration(round.ms)
    if (round.failed > 0) parts += "<span class=\"ai-chat-round-failed\">${round.failedText()}</span>"
    for ((family, n) in round.families) {
        parts += "<span class=\"ai-chat-round-family\" data-family=\"${escapeHtml(family.name)}\" title=\"${escapeHtml(family.label)}\">" +
            "${escapeHtml(round.familyText(family, n))}</span>"
    }
    val liveHtml = round.live?.let { " <span class=\"ai-chat-round-live\">— ${shimmerIfLive(it, escapeHtml(it.title.ifBlank { "AI activity" }))}</span>" }.orEmpty()
    val failedAttr = if (round.failed > 0) " data-failed=\"${round.failed}\"" else ""
    val span = run.first().address?.let { " data-ai-from=\"${escapeHtml(it)}\" data-ai-to=\"${escapeHtml(run.last().address ?: it)}\"" }.orEmpty()
    return "<details class=\"ai-chat-round\" data-ai-round=\"$ordinal\"$failedAttr$span>" +
        "<summary><span class=\"ai-chat-round-line\">${parts.joinToString("<span class=\"ai-chat-round-sep\"> · </span>")}</span>$liveHtml</summary>" +
        "<div class=\"ai-chat-round-body\">${renderProgressRun(run, markdown)}</div>" +
        "</details>"
}

private val PROGRESS_FILE = Regex("""(?:"file"\s*:\s*"([^"]+)"|\bfile=([^\s,}]+))""")

private fun renderProgressTurn(turn: AiChatBlock, markdown: (String?) -> String): String {
    val lines = turn.content.lines()
    val title = lines.firstOrNull()?.trim()?.ifBlank { null } ?: "AI activity"
    val body = lines.drop(1).joinToString("\n").trim().ifBlank { turn.content }
    val isEdit = title.contains("EditFile", ignoreCase = true) || title.contains("edit file", ignoreCase = true)
    val file = PROGRESS_FILE.find(turn.content)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        .orEmpty()
    val open = if (turn.role == "progress-open" || isEdit) " open" else ""
    val kind = if (isEdit) " data-kind=\"edit\"" else ""
    val summaryTitle = if (isEdit) "Edit" else title
    val fileHtml = if (file.isNotBlank()) "<span class=\"ai-chat-progress-file\">${escapeHtml(file)}</span>" else ""
    return "<details class=\"ai-chat-progress\"$kind$open${addressAttr(turn)}>" +
        "<summary><span>${shimmerIfLive(turn, escapeHtml(summaryTitle))}</span>$fileHtml</summary>" +
        markdown(body) +
        "</details>"
}

/**
 * A question the agent asked: the question, and a button per suggested answer while it is [open] (no message
 * from the person after it). A button sends its answer as the person's message (outputareas2.js); typing in
 * the chat box answers too.
 */
private fun renderQuestion(turn: AiChatBlock, open: Boolean, markdown: (String?) -> String): String {
    val choices = turn.questionChoices
    val buttons = if (choices.isEmpty()) "" else "<div class=\"ai-chat-question-choices\">" + choices.joinToString("") {
        "<button type=\"button\" class=\"ai-chat-question-choice\" data-ai-answer=\"${escapeHtml(it)}\"${if (open) "" else " disabled"}>${escapeHtml(it)}</button>"
    } + "</div>"
    val hint = if (open) "<div class=\"ai-chat-question-hint\">${if (choices.isEmpty()) "Type your answer below." else "Pick one, or type your own answer below."}</div>" else ""
    return "<div class=\"ai-chat-question${if (open) " open" else ""}\"${addressAttr(turn)}>" +
        "<div class=\"ai-chat-model\">Question</div>" + markdown(turn.questionText) + buttons + hint + "</div>"
}

/** Where a block sits in the chat it came from (`<<at:…>>`), for the lens viewer's lines; nothing for an ordinary chat. */
private fun addressAttr(block: AiChatBlock): String =
    block.address?.let { " data-ai-address=\"${escapeHtml(it)}\"" }.orEmpty()

// `<<progress-open>>` is the newest activity of a turn still running with nothing streamed after it
// (AiLiveConversation.render); the final render and an interrupted-turn recovery both demote it.
private fun shimmerIfLive(turn: AiChatBlock, html: String): String =
    if (turn.role == "progress-open") "<span class=\"ai-chat-shimmer\">$html</span>" else html

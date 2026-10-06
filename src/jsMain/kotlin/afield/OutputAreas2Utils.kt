package afield

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun inlineFormat(text: String): String {
    var out = text
    out = out.replace(Regex("`([^`]+)`"), "<code>$1</code>")
    out = out.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
    out = out.replace(Regex("\\*([^*]+)\\*"), "<em>$1</em>")
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

private fun renderTextBlock(text: String): String {
    val lines = text.split("\n")
    val html = StringBuilder()
    var inList = false
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        html.append("<p>")
        html.append(inlineFormat(paragraph.joinToString(" ")))
        html.append("</p>")
        paragraph.clear()
    }

    fun closeList() {
        if (inList) {
            html.append("</ul>")
            inList = false
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

        if (Regex("^[-*]\\s+").containsMatchIn(line)) {
            flushParagraph()
            if (!inList) {
                html.append("<ul>")
                inList = true
            }
            html.append("<li>")
            html.append(inlineFormat(line.replaceFirst(Regex("^[-*]\\s+"), "")))
            html.append("</li>")
            lineIndex++
            continue
        }

        paragraph.add(line)
        lineIndex++
    }

    flushParagraph()
    if (inList) html.append("</ul>")
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
            val safeLang = escapeHtml(language)
            val codeText = escapeHtml(code.removeSuffix("\n"))
            val langClass = if (safeLang.isNotEmpty()) " class=\"language-$safeLang\"" else ""
            val langAttr = if (safeLang.isNotEmpty()) " data-lang=\"$safeLang\"" else ""
            html.append("<div class=\"ai-chat-code-block\"")
            html.append(langAttr)
            if (index < blocks.lastIndex) html.append(" data-fence-closed=\"true\"")
            html.append(">")
            html.append("<div class=\"ai-chat-copy-wrap\"><button class=\"ai-chat-copy\" type=\"button\" aria-label=\"Copy\" title=\"Copy\"></button></div>")
            html.append("<pre><code")
            html.append(langClass)
            html.append(">")
            html.append(codeText)
            html.append("</code></pre>")
            html.append("</div>")
        } else {
            val escaped = escapeHtml(block)
            html.append(renderTextBlock(escaped))
        }
    }
    return html.toString()
}

// The transcript parser is shared with the chat archive: parseAiChatBlocks (commonMain, aiChatBlocks.kt).

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatMarkdownToHtml(markdown: String?): String {
    return "<div class=\"ai-chat-markdown\">" + renderMarkdown(markdown) + "</div>"
}

private var toolFamilies: AiChatToolFamilies? = null

/**
 * Install the tool-family table (`ai4-chat-tool-families.json`) that round summaries count by family. Until it
 * is installed a round's summary gives calls, time and failures only.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatSetToolFamilies(json: String) {
    toolFamilies = AiChatToolFamilies.parse(json)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatHistoryToHtml(history: String?): String {
    val turns = parseAiChatBlocks(history)
    if (turns.isEmpty()) return ""
    val html = StringBuilder()
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
            val end = (i until turns.size).firstOrNull { !turns[it].isProgress } ?: turns.size
            val run = turns.subList(i, end)
            html.append(if (AiChatRound.folds(run)) renderRound(run, roundOrdinal++) else renderProgressRun(run))
            i = end
            continue
        }
        i++
        if (turn.role == "queued") {
            // Sent while the turn runs (AiTurnInbox on the server): not part of the conversation until the
            // agent reads it, so it has no ordinal and no rewind/edit.
            html.append("<div class=\"ai-chat-turn you queued\">" +
                "<div class=\"ai-chat-queued-label\">queued: the agent reads this after its current step</div>" +
                aiChatMarkdownToHtml(turn.content) + "</div>")
        } else {
            val badge = if (turn.role == "ai") {
                val label = turn.model?.label() ?: "Model not recorded"
                val title = turn.model?.description() ?: "This older chat entry did not record its provider or model."
                "<div class=\"ai-chat-model\" title=\"${escapeHtml(title)}\">${escapeHtml(label)}</div>"
            } else ""
            val turnAttrs = if (turn.role == "you") {
                " data-ai-turn=\"${userOrdinal++}\" data-ai-text=\"${escapeHtml(turn.content)}\""
            } else ""
            val actions = if (turn.role == "you") {
                "<span class=\"ai-chat-turn-actions\">" +
                    "<button type=\"button\" class=\"ai-chat-turn-action\" data-ai-action=\"edit\" title=\"Edit this message and send it again (the rest is kept as a branch)\">edit</button>" +
                    "<button type=\"button\" class=\"ai-chat-turn-action\" data-ai-action=\"rewind\" title=\"Rewind to before this message (the rest is kept as a branch)\">rewind</button>" +
                    "</span>"
            } else ""
            // A lens block's citations, for the viewer that draws a line from it to the chat it summarizes.
            val sourceAttr = if (turn.sources.isNotEmpty()) " data-ai-sources=\"${escapeHtml(turn.sources.joinToString(","))}\"" else ""
            html.append("<div class=\"ai-chat-turn ${turn.role}\"$turnAttrs$sourceAttr>" + badge + aiChatMarkdownToHtml(turn.content) + actions + "</div>")
        }
    }
    return "<div class=\"ai-chat-thread\">$html</div>"
}

/**
 * Progress blocks as they render on their own: a run of title-only blocks shares one status row, anything with
 * a body is its own collapsible block.
 */
private fun renderProgressRun(run: List<AiChatBlock>): String {
    val html = StringBuilder()
    var statusRowOpen = false
    for (turn in run) {
        if (turn.isStatusOnly) {
            if (!statusRowOpen) {
                html.append("<div class=\"ai-chat-status-row\" aria-label=\"AI activity\">")
                statusRowOpen = true
            }
            html.append("<span class=\"ai-chat-status\">${shimmerIfLive(turn, escapeHtml(turn.title.ifBlank { "AI activity" }))}</span>")
            continue
        }
        if (statusRowOpen) {
            html.append("</div>")
            statusRowOpen = false
        }
        html.append(renderProgressTurn(turn))
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
private fun renderRound(run: List<AiChatBlock>, ordinal: Int): String {
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
    return "<details class=\"ai-chat-round\" data-ai-round=\"$ordinal\"$failedAttr>" +
        "<summary><span class=\"ai-chat-round-line\">${parts.joinToString("<span class=\"ai-chat-round-sep\"> · </span>")}</span>$liveHtml</summary>" +
        "<div class=\"ai-chat-round-body\">${renderProgressRun(run)}</div>" +
        "</details>"
}

private fun renderProgressTurn(turn: AiChatBlock): String {
    val lines = turn.content.lines()
    val title = lines.firstOrNull()?.trim()?.ifBlank { null } ?: "AI activity"
    val body = lines.drop(1).joinToString("\n").trim().ifBlank { turn.content }
    val isEdit = title.contains("EditFile", ignoreCase = true) || title.contains("edit file", ignoreCase = true)
    val file = Regex("""(?:"file"\s*:\s*"([^"]+)"|\bfile=([^\s,}]+))""")
        .find(turn.content)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        .orEmpty()
    val open = if (turn.role == "progress-open" || isEdit) " open" else ""
    val kind = if (isEdit) " data-kind=\"edit\"" else ""
    val summaryTitle = if (isEdit) "Edit" else title
    val fileHtml = if (file.isNotBlank()) "<span class=\"ai-chat-progress-file\">${escapeHtml(file)}</span>" else ""
    return "<details class=\"ai-chat-progress\"$kind$open>" +
        "<summary><span>${shimmerIfLive(turn, escapeHtml(summaryTitle))}</span>$fileHtml</summary>" +
        aiChatMarkdownToHtml(body) +
        "</details>"
}

// `<<progress-open>>` is the newest activity of a turn still running with nothing streamed after it
// (AiLiveConversation.render); the final render and an interrupted-turn recovery both demote it.
private fun shimmerIfLive(turn: AiChatBlock, html: String): String =
    if (turn.role == "progress-open") "<span class=\"ai-chat-shimmer\">$html</span>" else html

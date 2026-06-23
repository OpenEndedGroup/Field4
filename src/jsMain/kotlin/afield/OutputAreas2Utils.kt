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
        sb.append("\" loading=\"lazy\" />")
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
        sb.append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
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

        val headingMatch = Regex("^(#{1,3})\\s+(.*)$").find(line)
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

private data class ChatTurn(val role: String, val content: String)

private fun parseChatHistory(history: String?): List<ChatTurn> {
    val text = (history ?: "").replace("\r\n", "\n")
    val tokens = mutableListOf<String>()
    var idx = 0
    while (idx < text.length) {
        val markers = listOf(
            "<<you>>",
            "<<ai>>",
            "<<progress>>",
            "<<progress-open>>"
        )
        val hits = markers.mapNotNull { marker ->
            val at = text.indexOf(marker, idx)
            if (at == -1) null else at to marker
        }
        val hit = hits.minByOrNull { it.first }
        val next = hit?.first ?: -1
        val marker = hit?.second
        if (next == -1) {
            tokens.add(text.substring(idx))
            break
        }
        if (next > idx) tokens.add(text.substring(idx, next))
        tokens.add(marker!!)
        idx = next + marker.length
    }

    val turns = mutableListOf<ChatTurn>()
    var currentRole: String? = null
    val buffer = StringBuilder()

    fun flush() {
        val role = currentRole ?: return
        val content = buffer.toString().trim()
        if (content.isNotEmpty()) {
            turns.add(ChatTurn(role, content))
        }
        buffer.clear()
    }

    for (token in tokens) {
        when (token) {
            "<<you>>" -> {
                flush()
                currentRole = "you"
            }
            "<<ai>>" -> {
                flush()
                currentRole = "ai"
            }
            "<<progress>>" -> {
                flush()
                currentRole = "progress"
            }
            "<<progress-open>>" -> {
                flush()
                currentRole = "progress-open"
            }
            else -> buffer.append(token)
        }
    }

    flush()
    if (turns.isEmpty() && text.trim().isNotEmpty()) {
        turns.add(ChatTurn("ai", text.trim()))
    }
    return turns
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatMarkdownToHtml(markdown: String?): String {
    return "<div class=\"ai-chat-markdown\">" + renderMarkdown(markdown) + "</div>"
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatHistoryToHtml(history: String?): String {
    val turns = parseChatHistory(history)
    if (turns.isEmpty()) return ""
    val html = turns.joinToString("") { turn ->
        if (turn.role.startsWith("progress")) {
            renderProgressTurn(turn)
        } else {
            "<div class=\"ai-chat-turn ${turn.role}\">" + aiChatMarkdownToHtml(turn.content) + "</div>"
        }
    }
    return "<div class=\"ai-chat-thread\">$html</div>"
}

private fun renderProgressTurn(turn: ChatTurn): String {
    val lines = turn.content.lines()
    val title = lines.firstOrNull()?.trim()?.ifBlank { null } ?: "AI activity"
    val body = lines.drop(1).joinToString("\n").trim().ifBlank { turn.content }
    val open = if (turn.role == "progress-open") " open" else ""
    return "<details class=\"ai-chat-progress\"$open>" +
        "<summary>${escapeHtml(title)}</summary>" +
        aiChatMarkdownToHtml(body) +
        "</details>"
}

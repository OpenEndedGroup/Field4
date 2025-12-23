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
    out = replaceLinks(out)
    return out
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

    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) {
            flushParagraph()
            if (inList) {
                html.append("</ul>")
                inList = false
            }
            continue
        }

        val headingMatch = Regex("^(#{1,3})\\s+(.*)$").find(line)
        if (headingMatch != null) {
            flushParagraph()
            if (inList) {
                html.append("</ul>")
                inList = false
            }
            val level = headingMatch.groupValues[1].length
            html.append("<h")
            html.append(level)
            html.append(">")
            html.append(inlineFormat(headingMatch.groupValues[2]))
            html.append("</h")
            html.append(level)
            html.append(">")
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
            continue
        }

        paragraph.add(line)
    }

    flushParagraph()
    if (inList) html.append("</ul>")
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
        val nextYou = text.indexOf("<<you>>", idx)
        val nextAi = text.indexOf("<<ai>>", idx)
        val next = when {
            nextYou == -1 -> nextAi
            nextAi == -1 -> nextYou
            else -> minOf(nextYou, nextAi)
        }
        if (next == -1) {
            tokens.add(text.substring(idx))
            break
        }
        if (next > idx) tokens.add(text.substring(idx, next))
        if (next == nextYou) {
            tokens.add("<<you>>")
            idx = next + "<<you>>".length
        } else {
            tokens.add("<<ai>>")
            idx = next + "<<ai>>".length
        }
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
        "<div class=\"ai-chat-turn ${turn.role}\">" + aiChatMarkdownToHtml(turn.content) + "</div>"
    }
    return "<div class=\"ai-chat-thread\">$html</div>"
}

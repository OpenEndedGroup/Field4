package afield.ai.conversation

/** Offsets refer to UTF-16 in the LF-normalized Markdown, just like a textarea selection. */
internal data class MarkdownSourcePart(val text: String, val start: Int)

internal class MarkdownSource(raw: String, start: Int) {
    private val escapedOffsets = raw.flatMapIndexed { index, char ->
        List(when (char) { '&' -> 5; '<', '>' -> 4; '"' -> 6; '\'' -> 5; else -> 1 }) { start + index }
    }

    fun positions(parts: List<MarkdownSourcePart>): List<Int> = buildList {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                val previous = parts[index - 1]
                add(escapedOffsets.getOrElse(previous.start + previous.text.length) { escapedOffsets.lastOrNull() ?: 0 })
            }
            addAll(escapedOffsets.subList(part.start, part.start + part.text.length))
        }
    }
}

/** Preserve provenance through the reader's inline replacements, including discarded link destinations.
 * Only the editable reader requests these spans; ordinary chat/notes keep their existing HTML. */
private data class MappedHtml(val text: String, val offsets: List<Int>) {
    fun slice(start: Int, end: Int = text.length) = MappedHtml(text.substring(start, end), offsets.subList(start, end))
    operator fun plus(other: MappedHtml) = MappedHtml(text + other.text, offsets + other.offsets)
    fun replace(pattern: Regex, replacement: (MappedHtml, MatchResult) -> MappedHtml): MappedHtml {
        val chunks = mutableListOf<MappedHtml>()
        var start = 0
        for (match in pattern.findAll(text)) {
            chunks.add(slice(start, match.range.first))
            chunks.add(replacement(this, match))
            start = match.range.last + 1
        }
        chunks.add(slice(start))
        return MappedHtml(chunks.joinToString("") { it.text }, chunks.flatMap { it.offsets })
    }
    fun group(match: MatchResult, group: Int): MappedHtml {
        val value = match.groupValues[group]
        val start = text.indexOf(value, match.range.first + 1)
        return slice(start, start + value.length)
    }
}
private fun markup(text: String) = MappedHtml(text, List(text.length) { -1 })

internal fun mappedMarkdownInline(text: String, positions: List<Int>): String {
    val source = MappedHtml(text, positions)
    fun emphasis(value: MappedHtml): MappedHtml = value.replace(Regex("\\*\\*([^*]+)\\*\\*")) { input, match ->
        markup("<strong>") + input.group(match, 1) + markup("</strong>")
    }.replace(Regex("\\*([^*\\s][^*]*)\\*")) { input, match ->
        markup("<em>") + input.group(match, 1) + markup("</em>")
    }
    fun prose(value: MappedHtml): MappedHtml {
        var result = markup("")
        var cursor = 0
        for (match in markdownLinkSpan.findAll(value.text)) {
            result += emphasis(value.slice(cursor, match.range.first))
            val url = match.groupValues[2]
            if (match.value.startsWith("!")) {
                val alt = match.groupValues[1]
                val lazy = if (url.startsWith("data:")) "" else " loading=\"lazy\""
                val start = value.offsets[match.range.first]
                result += markup("<img class=\"ai-chat-inline-image\" data-markdown-start=\"$start\" src=\"$url\" alt=\"$alt\"$lazy />")
            } else if (match.groupValues[1].isEmpty()) {
                result += value.slice(match.range.first, match.range.last + 1)
            } else {
                val target = if (url.startsWith("field:", ignoreCase = true)) "" else " target=\"_blank\" rel=\"noopener noreferrer\""
                result += markup("<a href=\"$url\"$target>") + emphasis(value.group(match, 1)) + markup("</a>")
            }
            cursor = match.range.last + 1
        }
        return result + emphasis(value.slice(cursor, value.text.length))
    }
    val chunks = mutableListOf<MappedHtml>()
    var start = 0
    // The same literal rule as the shared reader: formatting never crosses code or math.
    for (match in literalSpan.findAll(text)) {
        chunks.add(prose(source.slice(start, match.range.first)))
        if (match.groupValues[1].isNotEmpty()) {
            chunks.add(markup("<code>") + source.group(match, 1) + markup("</code>"))
        } else {
            val display = match.groupValues[2].isNotEmpty() || match.groupValues[3].isNotEmpty()
            val tex = (2..5).map { match.groupValues[it] }.first { it.isNotEmpty() }.trim()
            val kind = if (display) "ai-math ai-math-display" else "ai-math"
            val offset = positions[match.range.first]
            chunks.add(markup("<span class=\"$kind\" data-markdown-start=\"$offset\" data-tex=\"$tex\">") + source.slice(match.range.first, match.range.last + 1) + markup("</span>"))
        }
        start = match.range.last + 1
    }
    chunks.add(prose(source.slice(start)))
    val html = MappedHtml(chunks.joinToString("") { it.text }, chunks.flatMap { it.offsets })
    // Generated tags/attributes have offset -1. Wrap only source characters: parsing HTML with
    // a tag regex would mistake a '>' inside an attribute (e.g. a bold image alt) for its end.
    val entityPattern = Regex("&(?:amp|lt|gt|quot|#39);")
    return buildString {
        var cursor = 0
        while (cursor < html.text.length) {
            val start = cursor
            val literal = html.offsets[cursor] >= 0
            while (cursor < html.text.length && (html.offsets[cursor] >= 0) == literal) cursor++
            val value = html.text.substring(start, cursor)
            if (!literal) append(value) else {
                val offsets = mutableListOf<Int>()
                var index = start
                while (index < cursor) {
                    offsets.add(html.offsets[index])
                    val entity = if (html.text[index] == '&') entityPattern.find(html.text, index) else null
                    index = if (entity?.range?.first == index) entity.range.last + 1 else index + 1
                }
                // A run stays intact so shaping and ligatures survive.
                append("<span data-markdown-offsets=\"${offsets.joinToString(",")}\">$value</span>")
            }
        }
    }
}

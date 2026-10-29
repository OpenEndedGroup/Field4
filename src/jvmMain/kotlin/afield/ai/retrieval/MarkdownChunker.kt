package afield.ai.retrieval

/**
 * Markdown cut into the pieces semantic search indexes and an embedder can read whole.
 *
 * Four things go wrong when a page is cut on its `#` lines and indexed raw, and each has an answer here.
 * A `#` line inside a code fence is a comment rather than a heading, so headings count only outside
 * fences. The generated reference pages carry their `<span class=…>` markup into the indexed text, so
 * markup outside code is reduced to the text it wraps. A section longer than the window the embedder
 * reads (around 512 tokens, which `DEFAULT_MAX_CHARS` approximates) loses its end, so a long section is
 * split on paragraph boundaries. And a section alone says nothing about where it sits ("Kotlin side", of
 * what?), so every chunk carries a breadcrumb of page title, parent headings and its own.
 */

/** Cuts a Markdown page into indexable sections, each with its breadcrumb and source line span. */
internal object MarkdownChunker {
    data class Chunk(
        /** The section's own heading. */
        val title: String,
        /** Page title › parent headings › this heading, for context in search text. */
        val breadcrumb: String,
        val level: Int,
        /** 1-based line of the chunk's first line, and the line after its last. */
        val line: Int,
        val endLine: Int,
        val body: String,
        /** 0 for a whole section, 1.. for the parts of a split one. */
        val part: Int = 0
    )

    const val DEFAULT_MAX_CHARS = 1_800

    /** Front matter naming another page (`---\ndocs: page/\n---`): a pointer, not content of its own. */
    fun isRedirectStub(lines: List<String>): Boolean {
        if (lines.firstOrNull()?.trim() != "---") return false
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return end > 0 && lines.subList(1, end + 1).any { it.trimStart().startsWith("docs:") }
    }

    private val heading = Regex("""^(#{1,4})\s+(.+?)\s*#*\s*$""")
    private val fence = Regex("""^\s*(```|~~~)""")
    private val anchorTag = Regex("""<a\s+id="[^"]*"\s*>\s*</a>""")
    private val tag = Regex("""</?[A-Za-z][^<>]*>""")
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val headingAttrs = Regex("""\s*\{\s*#[^}]*}\s*$""")
    private val entities = mapOf("&quot;" to "\"", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&#39;" to "'", "&nbsp;" to " ")

    /** A line of prose as text: tags, anchors and link targets removed, entities decoded. */
    fun cleanProse(line: String): String {
        var s = anchorTag.replace(line, "")
        s = link.replace(s) { it.groupValues[1] }
        s = tag.replace(s, "")
        entities.forEach { (k, v) -> s = s.replace(k, v) }
        return s
    }

    private fun cleanTitle(raw: String) = cleanProse(headingAttrs.replace(raw, "")).trim().trim('`')

    fun chunks(lines: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): List<Chunk> {
        if (lines.isEmpty()) return emptyList()
        data class Section(val title: String, val level: Int, val start: Int, val trail: List<String>)
        val sections = mutableListOf<Section>()
        val stack = ArrayList<Pair<Int, String>>()
        var inFence = false
        lines.forEachIndexed { i, line ->
            if (fence.containsMatchIn(line)) { inFence = !inFence; return@forEachIndexed }
            if (inFence) return@forEachIndexed
            val m = heading.matchEntire(line) ?: return@forEachIndexed
            val level = m.groupValues[1].length
            val title = cleanTitle(m.groupValues[2])
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.lastIndex)
            sections += Section(title, level, i, stack.map { it.second })
            stack += level to title
        }
        if (sections.isEmpty() || sections.first().start > 0) sections.add(0, Section("", 1, 0, emptyList()))
        val out = mutableListOf<Chunk>()
        sections.forEachIndexed { k, s ->
            val end = sections.getOrNull(k + 1)?.start ?: lines.size
            val breadcrumb = (s.trail + s.title).filter { it.isNotBlank() }.joinToString(" › ")
            // Paragraphs of cleaned text with their first line, fenced blocks kept whole as code.
            val paragraphs = mutableListOf<Pair<Int, String>>()
            val current = StringBuilder()
            var currentStart = s.start
            var fenced = false
            var skipBlock = false
            fun flush(next: Int) {
                if (current.isNotBlank()) paragraphs += currentStart to current.toString().trimEnd()
                current.setLength(0); currentStart = next
            }
            for (i in s.start until end) {
                val line = lines[i]
                if (fence.containsMatchIn(line)) {
                    if (!fenced) {
                        flush(i)
                        // The machine-readable EDN header of a materialized document is not for reading.
                        skipBlock = "field-document" in line
                    }
                    fenced = !fenced
                    if (!skipBlock) current.append(line).append('\n')
                    if (!fenced) { if (!skipBlock) flush(i + 1); skipBlock = false }
                    continue
                }
                if (fenced) { if (!skipBlock) current.append(line).append('\n'); continue }
                if (line.isBlank()) { flush(i + 1); continue }
                if (current.isEmpty()) currentStart = i
                current.append(cleanProse(line)).append('\n')
            }
            flush(end)
            val whole = paragraphs.joinToString("\n\n") { it.second }
            // A heading with nothing under it but the next heading is no search result of its own.
            if (paragraphs.size <= 1 && whole.lines().size <= 1) return@forEachIndexed
            if (whole.length <= maxChars) {
                out += Chunk(s.title, breadcrumb, s.level, s.start + 1, end, whole)
                return@forEachIndexed
            }
            // Split on paragraph boundaries; a single paragraph longer than the limit is cut.
            var part = 1
            val buf = StringBuilder()
            var partStart = paragraphs.first().first
            fun emit(nextStart: Int) {
                if (buf.isBlank()) return
                out += Chunk(s.title, breadcrumb, s.level, partStart + 1, nextStart, buf.toString().trimEnd(), part++)
                buf.setLength(0); partStart = nextStart
            }
            paragraphs.forEachIndexed { p, (start, text) ->
                if (buf.isNotEmpty() && buf.length + text.length + 2 > maxChars) emit(start)
                if (buf.isEmpty()) partStart = start
                if (text.length > maxChars) {
                    text.chunked(maxChars).forEach { piece -> buf.append(piece); emit(paragraphs.getOrNull(p + 1)?.first ?: end) }
                } else buf.append(text).append("\n\n")
            }
            emit(end)
        }
        return out
    }
}

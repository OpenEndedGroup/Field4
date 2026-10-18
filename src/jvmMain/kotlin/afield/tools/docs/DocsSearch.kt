package afield.tools.docs

import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ln

/**
 * Lexical search over the documentation sources (the Markdown pages under `public/docs/`, outside
 * `assets/`), for Open Anything. The semantic index finds pages by meaning; this finds them by the text a reader
 * remembers — a page title, a heading, or an identifier from an example such as `once.renderer`,
 * which an embedding search ranks poorly.
 *
 * Each page is split into units — its title, each heading, each fenced code block, and each prose
 * paragraph — and a query matches a unit when every whitespace-separated term occurs in it, case
 * insensitively. Hits link into the built site (`/field-docs/<page>/#<heading anchor>`), so the
 * index works from the Markdown alone and does not need `public/field4/` to have been built.
 *
 * The index is rebuilt when any page's size or modification time changes.
 */

/** The docs lexical index and its query entry points; see the file overview. */
object DocsSearch {

    @Serializable
    data class Hit(
        /** `page`, `section`, `code` or `text`. */
        val kind: String,
        /** Page path relative to the docs root, e.g. `running_code.md`. */
        val page: String,
        val pageTitle: String,
        /** The heading the hit sits under (the heading itself for `section`). */
        val heading: String = "",
        val url: String,
        /** 1-based line in the Markdown source. */
        val line: Int,
        val snippet: String,
        /** Fence language for `code` hits. */
        val language: String = "",
        /** The whole matched unit, capped, for the palette's preview. */
        val excerpt: String = "",
        val score: Double,
    )

    @Serializable
    data class Response(val query: String, val hits: List<Hit>, val totalMatches: Int, val truncated: Boolean)

    internal enum class Kind(val id: String, val base: Double) {
        PAGE("page", 100.0), SECTION("section", 70.0), CODE("code", 50.0), TEXT("text", 30.0)
    }

    internal data class Chunk(
        val kind: Kind,
        val text: String,
        val lower: String,
        val line: Int,
        val heading: String,
        val anchor: String,
        val language: String = "",
    )

    internal data class Page(val path: String, val title: String, val url: String, val units: List<Chunk>)

    private data class Index(val signature: Long, val pages: List<Page>)

    private val cached = AtomicReference<Index?>(null)

    /** `-Dafield.docs.dir`, else `public/docs` found by walking up from the working directory. */
    fun docsRoot(): File? {
        System.getProperty("afield.docs.dir")?.takeIf { it.isNotBlank() }?.let {
            return File(it).takeIf { d -> d.isDirectory }
        }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "public/docs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    fun search(rawQuery: String, limit: Int = 40, maxPerPage: Int = 3, root: File? = docsRoot()): Response {
        val query = rawQuery.trim()
        if (query.isEmpty() || root == null) return Response(query, emptyList(), 0, false)
        return searchPages(pages(root), query, limit.coerceIn(1, 200), maxPerPage.coerceIn(1, 20))
    }

    internal fun searchPages(pages: List<Page>, query: String, limit: Int, maxPerPage: Int): Response {
        val phrase = query.lowercase()
        val terms = phrase.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hits = mutableListOf<Hit>()
        for (page in pages) {
            val pageHits = page.units.mapNotNull { unit -> scoreUnit(unit, phrase, terms)?.let { unit to it } }
            if (pageHits.isEmpty()) continue
            // Several hits per page are useful (a heading and its example), a page's every
            // paragraph is not: keep the best few prose/code hits but every title/heading hit.
            val structural = pageHits.filter { it.first.kind == Kind.PAGE || it.first.kind == Kind.SECTION }
            val body = pageHits.filter { it.first.kind == Kind.CODE || it.first.kind == Kind.TEXT }
                .sortedByDescending { it.second }.take(maxPerPage)
            // Many matching units make the page itself more relevant.
            val density = ln(pageHits.size.toDouble() + 1.0)
            for ((unit, score) in structural + body) hits += toHit(page, unit, score + density, terms, phrase)
        }
        val sorted = hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.page }.thenBy { it.line })
        return Response(query, sorted.take(limit), hits.size, hits.size > limit)
    }

    private fun scoreUnit(unit: Chunk, phrase: String, terms: List<String>): Double? {
        if (terms.isEmpty() || !terms.all { unit.lower.contains(it) }) return null
        var score = unit.kind.base
        val trimmed = unit.lower.trim()
        if (trimmed == phrase) score += 60.0
        else if (terms.size > 1 && unit.lower.contains(phrase)) score += 30.0
        if (trimmed.startsWith(phrase)) score += 10.0
        for (term in terms) {
            val at = unit.lower.indexOf(term)
            if (at == 0 || !unit.lower[at - 1].isLetterOrDigit()) score += 4.0
            val end = at + term.length
            if (end >= unit.lower.length || !unit.lower[end].isLetterOrDigit()) score += 2.0
        }
        // A short unit that matches is about the query; a long one merely mentions it.
        if (unit.kind == Kind.TEXT || unit.kind == Kind.CODE) score -= (unit.text.length / 400.0).coerceAtMost(10.0)
        return score
    }

    private fun toHit(page: Page, unit: Chunk, score: Double, terms: List<String>, phrase: String): Hit {
        val lines = unit.text.lines()
        val needle = if (unit.lower.contains(phrase)) phrase else terms.first()
        val offset = lines.indexOfFirst { it.lowercase().contains(needle) }.coerceAtLeast(0)
        val line = when (unit.kind) {
            // A code unit's text starts on the line after its opening fence.
            Kind.CODE -> unit.line + 1 + offset
            Kind.TEXT -> unit.line + offset
            else -> unit.line
        }
        val snippet = when (unit.kind) {
            Kind.PAGE, Kind.SECTION -> unit.text
            else -> clip(lines.getOrElse(offset) { "" }.trim(), needle)
        }
        val excerpt = when (unit.kind) {
            Kind.CODE -> lines.drop((offset - 6).coerceAtLeast(0)).take(16).joinToString("\n")
            Kind.TEXT -> unit.text.take(1200)
            else -> ""
        }
        return Hit(
            kind = unit.kind.id,
            page = page.path,
            pageTitle = page.title,
            heading = unit.heading,
            url = if (unit.anchor.isEmpty()) page.url else "${page.url}#${unit.anchor}",
            line = line,
            snippet = snippet,
            language = unit.language,
            excerpt = excerpt,
            score = score,
        )
    }

    private fun clip(line: String, needle: String, width: Int = 140): String {
        if (line.length <= width) return line
        val at = line.lowercase().indexOf(needle).coerceAtLeast(0)
        val start = (at - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(line.length)
        return (if (start > 0) "…" else "") + line.substring(start, end) + (if (end < line.length) "…" else "")
    }

    private fun markdownFiles(root: File): List<File> = root.walkTopDown()
        .onEnter { it == root || (it.name != "assets" && !it.name.startsWith(".")) }
        .filter { it.isFile && it.extension == "md" }
        .sortedBy { it.path }
        .toList()

    private fun pages(root: File): List<Page> {
        val files = markdownFiles(root)
        var signature = root.absolutePath.hashCode().toLong()
        for (file in files) signature = signature * 31 + file.path.hashCode() * 17 + file.lastModified() * 7 + file.length()
        cached.get()?.takeIf { it.signature == signature }?.let { return it.pages }
        val pages = files.mapNotNull { file ->
            runCatching { parsePage(file.relativeTo(root).invariantSeparatorsPath, file.readText()) }.getOrNull()
        }
        cached.set(Index(signature, pages))
        return pages
    }

    internal fun pageUrl(path: String): String {
        val stem = path.removeSuffix(".md")
        val dir = when {
            stem == "index" -> ""
            stem.endsWith("/index") -> stem.removeSuffix("index")
            else -> "$stem/"
        }
        return "/field-docs/$dir"
    }

    private val fenceOpen = Regex("^(\\s*)(`{3,}|~{3,})(.*)$")
    private val headingLine = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val explicitId = Regex("\\{[^}]*#([\\w-]+)[^}]*\\}\\s*$")

    internal fun parsePage(path: String, source: String): Page {
        val lines = source.lines()
        val units = mutableListOf<Chunk>()
        val usedAnchors = mutableMapOf<String, Int>()
        var title = ""
        var i = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                lines.subList(1, end).firstOrNull { it.startsWith("title:") }
                    ?.let { title = it.removePrefix("title:").trim().trim('"', '\'') }
                i = end + 1
            }
        }
        var heading = ""
        var anchor = ""
        val paragraph = StringBuilder()
        var paragraphLine = 0

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) units += Chunk(Kind.TEXT, text, text.lowercase(), paragraphLine, heading, anchor)
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val raw = lines[i]
            val fence = fenceOpen.matchEntire(raw)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[2]
                val language = fence.groupValues[3].trim().trimStart('{', '.').split(Regex("[\\s{}]")).firstOrNull().orEmpty()
                val start = i
                val body = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val close = lines[i].trim()
                    if (close.startsWith(marker[0].toString().repeat(marker.length)) && close.all { it == marker[0] }) break
                    body += lines[i]
                    i++
                }
                val indent = fence.groupValues[1].length
                val text = body.joinToString("\n") { if (it.length >= indent && it.take(indent).isBlank()) it.drop(indent) else it }
                if (text.isNotBlank()) units += Chunk(Kind.CODE, text, text.lowercase(), start + 1, heading, anchor, language)
                i++
                continue
            }
            val h = headingLine.matchEntire(raw)
            if (h != null) {
                flushParagraph()
                val level = h.groupValues[1].length
                var text = h.groupValues[2]
                val id = explicitId.find(text)?.groupValues?.get(1)
                text = text.replace(Regex("\\{[^}]*\\}\\s*$"), "").trim()
                val plain = plainHeading(text)
                if (level == 1 && title.isEmpty()) {
                    title = plain
                    units += Chunk(Kind.PAGE, plain, plain.lowercase(), i + 1, "", "")
                } else {
                    val base = id ?: slugify(plain)
                    val seen = usedAnchors[base]
                    anchor = if (seen == null) base else "${base}_$seen"
                    usedAnchors[base] = (seen ?: 0) + 1
                    heading = plain
                    units += Chunk(Kind.SECTION, plain, plain.lowercase(), i + 1, plain, anchor)
                }
                i++
                continue
            }
            if (raw.isBlank()) flushParagraph()
            else if (!raw.trimStart().startsWith("<!--")) {
                if (paragraph.isEmpty()) paragraphLine = i + 1
                paragraph.append(raw.trim()).append('\n')
            }
            i++
        }
        flushParagraph()
        if (title.isEmpty()) title = path.substringAfterLast('/').removeSuffix(".md").replace('_', ' ')
        if (units.none { it.kind == Kind.PAGE }) units.add(0, Chunk(Kind.PAGE, title, title.lowercase(), 1, "", ""))
        return Page(path, title, pageUrl(path), units)
    }

    /** Heading text as rendered: code spans, emphasis and link syntax removed. */
    internal fun plainHeading(text: String): String = text
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace("`", "")
        .replace(Regex("\\*\\*|__|\\*"), "")
        .trim()

    /** Python-Markdown's default `toc` slug, which Zensical uses for heading anchors. */
    internal fun slugify(text: String): String {
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "")
        return ascii.replace(Regex("<[^>]*>"), "")
            .replace(Regex("[^\\w\\s-]"), "")
            .trim()
            .lowercase()
            .replace(Regex("[-\\s]+"), "-")
    }
}

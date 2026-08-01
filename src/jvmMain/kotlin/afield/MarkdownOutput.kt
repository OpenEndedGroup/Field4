package afield

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown for output areas — the compact way to put a *figure* in a box's output pane.
 *
 * The alternative, and what this replaces, is building the HTML by hand: a card `<div>` with a
 * hand-picked font stack and hex colours, captions as `<div style='color:#c586c0'>`, key/value
 * tables assembled with `joinToString` into `<tr><td style=…>`, a `<pre>` guarded by a locally
 * defined `fun esc(s: String)`. In the docs that grew to be longer than the code it illustrated.
 * Markdown says all of it — headings, tables, fenced code (which escapes for free) — and picks up
 * Field's own `--field-*` design tokens instead of re-inventing them badly, one figure at a time.
 *
 * ```kotlin
 * markdown("keys", """
 *     ### 1 — capture()
 *     ${captured.h}
 *
 *     ### 2 — the live structures, read back
 *
 *     | binding          | value |
 *     |------------------|-------|
 *     | glow.radius      | ${live(glow, "radius")} |
 *     | glow.intensity   | ${live(glow, "intensity")} |
 * """, width = 820)
 * ```
 *
 * GitHub-flavoured markdown, so tables and strikethrough work. Beyond it, two additions:
 *
 * **Raw HTML passes through, inline and block.** This is the point — `${'$'}{key.h}`,
 * `${'$'}{a.diffHtml(b)}`, `${'$'}{fn.graphSvg()}` and friends already return HTML, and they land
 * verbatim, including inside a table cell. Only text you wrote as markdown gets escaped.
 *
 * **`:::row` … `:::end` puts columns side by side**, split on a `---` line — the before/after shape
 * markdown has no spelling for:
 *
 * ```
 * :::row
 * #### before
 * ${'$'}{base.graphSvg()}
 * ---
 * #### after
 * ${'$'}{changed.graphSvg()}
 * :::end
 * ```
 *
 * Close it with `:::end`, not a bare `:::`. Both work here, but the docs pipeline eats the bare
 * form before your code runs — see [BLOCK_CLOSE], which is worth reading before you debug a figure
 * whose columns appeared and then swallowed the rest of the card.
 *
 * **`{role:text}` marks a span** without reaching for a hex literal. The roles are
 * [ROLES]: `{accent:…}` `{muted:…}` `{dim:…}` `{ok:…}` `{warn:…}` `{bad:…}`. They nest, and a
 * literal brace is `\{`.
 *
 * Neither addition applies inside fenced or inline code, so a `:::row` or a `{warn:…}` shown *as*
 * code stays as written.
 *
 * Three things worth knowing.
 *
 * The text is dedented for you, and more forgivingly than [String.trimIndent] — see `dedent`. You
 * do not need to call `trimIndent()`, and interpolating a multi-line value will not wreck the
 * figure the way it wrecks `trimIndent`.
 *
 * **Interpolate a multi-line value into a code fence at column zero**, hard against the left
 * margin, not at the indentation of the surrounding block:
 *
 * ```kotlin
 * markdown("persist", """
 *     #### version 0 on disk, as written
 *     ~~~json
 * ${'$'}{Files.readString(path)}
 *     ~~~
 * """)
 * ```
 *
 * Inside a fence whitespace is the content, and splicing at the margin indents only the value's
 * *first* line — which nothing downstream can tell apart from content written that way. At column
 * zero the value arrives byte for byte. Anywhere outside a fence, indentation does not matter and
 * you can interpolate wherever it reads best.
 *
 * Finally, `width` is not decoration: a docs `capture="box"` screenshot clips to the output card,
 * so an unpinned card sized by its widest unbreakable line loses everything past the clip.
 */
object FieldMarkdown {

    /** Inline span roles: `{accent:text}` renders as `<span class="field-md-accent">text</span>`. */
    val ROLES = listOf("accent", "muted", "dim", "ok", "warn", "bad")

    /** Renders [text] to an HTML fragment — no card wrapper, so no styling. Usually you want [card]. */
    fun render(text: String): String = buildString {
        parseBlocks(dedent(text)).forEach { block ->
            when (block) {
                is Block.Prose -> append(renderMarkdown(block.text))
                is Block.Row -> {
                    val columns = block.columns.filter { it.isNotBlank() }
                    if (columns.isEmpty()) return@forEach
                    append("""<div class="field-md-row">""")
                    columns.forEach { append("""<div class="field-md-col">""").append(renderMarkdown(it)).append("</div>") }
                    append("</div>")
                }
            }
        }
    }

    /**
     * Renders [text] wrapped in the `field-md` card that carries the stylesheet (see `field.css`).
     *
     * [width], in CSS pixels, pins the card. Leave it null and the card sizes to its content, which
     * is right in a live box and wrong for a `capture="box"` docs screenshot — see [FieldMarkdown].
     */
    fun card(text: String, width: Int? = null): String {
        val style = width?.let { """ style="width:${it}px;max-width:${it}px"""" } ?: ""
        return """<div class="field-md"$style>${render(text)}</div>"""
    }

    // --------------------------------------------------------------- indenting

    /**
     * Strips the author's indentation, anchored on the **first non-blank line** and removed only
     * from lines that actually carry it — where [String.trimIndent] instead takes the minimum across
     * every non-blank line and re-indents the rest.
     *
     * The difference is the whole reason this exists, and it is about interpolation. A figure builds
     * its table rows with `joinToString("\n")` and splices them into an indented `"""` block; every
     * row after the first arrives at column 0. That drags `trimIndent`'s minimum to zero, so nothing
     * is stripped, markdown reads the author's four spaces as an indented code block, and the entire
     * figure renders as one grey slab. Anchoring on the first line makes the interpolated block's own
     * indentation irrelevant.
     *
     * Two refinements sit on top, both there to keep interpolated content intact: fenced bodies are
     * judged whole rather than line by line ([marginIsUniform]), and a still-indented line that
     * opens a tag is flattened ([unindentHtmlOpener]).
     */
    private fun dedent(text: String): String {
        val lines = text.lines()
        val first = lines.firstOrNull { it.isNotBlank() } ?: return ""
        val margin = first.takeWhile { it == ' ' }

        fun unmargin(line: String) = if (line.startsWith(margin)) line.drop(margin.length) else line

        // Fenced regions are found first, on margin-stripped lines so a fence sitting at the margin
        // is still recognised as one, and each is judged as a whole — see [marginIsUniform].
        val fenced = BooleanArray(lines.size)
        val unmarginFence = BooleanArray(lines.size)
        var open: Fence? = null
        var start = -1

        fun closeRegion(end: Int) {
            val content = (start + 1) until end
            val uniform = marginIsUniform(lines, content, margin)
            content.forEach { fenced[it] = true; unmarginFence[it] = uniform }
        }

        lines.forEachIndexed { index, raw ->
            val marker = fenceAt(unmargin(raw))
            val current = open
            when {
                current == null -> if (marker != null) { open = marker.fence; start = index }
                marker != null && marker.closes(current) -> { closeRegion(index); open = null }
            }
        }
        if (open != null) closeRegion(lines.size) // unterminated fence: still treat the rest as content

        return lines
            .mapIndexed { index, raw ->
                if (fenced[index]) (if (unmarginFence[index]) unmargin(raw) else raw)
                else unindentHtmlOpener(unmargin(raw))
            }
            .joinToString("\n")
            .trim('\n')
    }

    /**
     * Whether every non-blank line of a fence's body carries the author's [margin] — which decides
     * whether the body is dedented as a whole or left exactly as written.
     *
     * All-or-nothing, because inside a fence whitespace *is* the content and a per-line rule
     * corrupts it. Hand-written fence bodies all sit at the margin and dedent cleanly. An
     * interpolated one — a JSON file read off disk, `updateCode()`, a diff — has its own
     * indentation and lines at column zero, so nothing is stripped and it survives byte for byte.
     *
     * The corollary is a rule for authors, and the KDoc on [FieldMarkdown] states it: interpolate a
     * multi-line value into a fence at **column zero**, not at the margin. Splicing it at the margin
     * indents only its first line, which no dedent rule can distinguish from content that was
     * written that way.
     */
    private fun marginIsUniform(lines: List<String>, range: IntRange, margin: String): Boolean =
        range.all { lines[it].isBlank() || lines[it].startsWith(margin) }

    /**
     * Flattens a still-indented line that opens an HTML tag, which markdown would otherwise read as
     * an indented code block and *show* rather than render.
     *
     * This is the other half of surviving interpolation, and it exists because HTML-producing APIs
     * leak indentation. `keyframeKeyHtml` is the worked example: it wraps its output in a `"""`
     * literal and calls `trimIndent()`, but its own interpolated `${'$'}rows` puts lines at column
     * zero, so the minimum indent is zero, nothing is stripped, and the returned HTML carries a
     * four-space margin. Invisible to every existing caller — leading whitespace means nothing in
     * HTML — and fatal here, where four spaces means "code block". Rather than chase that bug
     * through every generator, absorb it.
     *
     * Only the *opening* line needs this. Once an HTML block starts, CommonMark takes everything up
     * to the next blank line verbatim, whatever its indentation. Fenced code is skipped by the
     * caller, so HTML shown deliberately as an example stays shown.
     */
    private fun unindentHtmlOpener(line: String): String {
        val indent = line.takeWhile { it == ' ' }.length
        return if (indent >= 4 && line.getOrNull(indent) == '<') line.drop(indent) else line
    }

    // ---------------------------------------------------------------- blocks

    private sealed interface Block {
        data class Prose(val text: String) : Block
        data class Row(val columns: List<String>) : Block
    }

    private const val ROW_OPEN = ":::row"

    /**
     * What closes a `:::row`.
     *
     * `:::end` is the spelling to use, and the reason is the docs pipeline: `public/docs_hooks.py`
     * treats a line whose stripped form is exactly `:::` as one of its own fence-section markers
     * (alongside `:::static` and `:::fold`) and **deletes it from the code it runs**. A figure that
     * closed its row with a bare `:::` therefore lost the terminator before the box ever executed —
     * the row stayed open and swallowed everything after it, silently, and only in a docs build.
     * `:::row` survives that filter because it is not a bare `:::`, which is what made the failure
     * so confusing: the columns appeared, the closing did not.
     *
     * A bare `:::` is still accepted, so the dialect behaves the way anyone would guess in a live
     * box, a test, or anywhere else the docs pipeline is not involved.
     */
    private val BLOCK_CLOSE = setOf(":::end", ":::")

    /**
     * Splits [src] into prose runs and `:::row` blocks. Code fences are tracked so a `:::row` or a
     * `---` shown as example code is left alone. An unterminated row is closed at the end rather
     * than dropped — a missing `:::` should cost you a layout, not the whole figure.
     */
    private fun parseBlocks(src: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val prose = StringBuilder()
        var columns: MutableList<StringBuilder>? = null
        var fence: Fence? = null

        fun sink(): StringBuilder = columns?.last() ?: prose

        fun flushProse() {
            if (prose.isNotBlank()) blocks += Block.Prose(prose.toString())
            prose.setLength(0)
        }

        for (line in src.lines()) {
            val marker = fenceAt(line)
            val open = fence
            if (open == null) {
                if (marker != null) {
                    fence = marker.fence
                    sink().appendLine(line)
                    continue
                }
            } else {
                if (marker != null && marker.closes(open)) fence = null
                sink().appendLine(line)
                continue
            }

            val trimmed = line.trim()
            when {
                columns == null && trimmed == ROW_OPEN -> {
                    flushProse()
                    columns = mutableListOf(StringBuilder())
                }

                columns != null && trimmed in BLOCK_CLOSE -> {
                    blocks += Block.Row(columns.map { it.toString() })
                    columns = null
                }

                columns != null && isColumnBreak(trimmed) -> columns.add(StringBuilder())

                else -> sink().appendLine(line)
            }
        }

        columns?.let { blocks += Block.Row(it.map { column -> column.toString() }) }
        flushProse()
        return blocks
    }

    /** Inside a `:::row`, a rule line starts the next column. */
    private fun isColumnBreak(trimmed: String): Boolean = trimmed.length >= 3 && trimmed.all { it == '-' }

    // ----------------------------------------------------------------- roles

    /**
     * Rewrites `{role:text}` spans outside fenced code.
     *
     * Fences are found line by line, because that is how they are delimited, and each run of
     * non-fenced lines is then rewritten *as one string* so a role may wrap across lines. That
     * matters: a muted annotation under a chart is usually a paragraph, and requiring it to sit on
     * one physical line would mean a `{muted:` opened at the top and closed three lines down failed
     * silently, leaving the braces on screen. Indented (four-space) code blocks are not detected, so
     * show example code in a fence rather than by indenting it.
     */
    private fun applyRoles(md: String): String {
        if (!md.contains('{')) return md
        val out = StringBuilder(md.length + 64)
        val prose = StringBuilder()
        var fence: Fence? = null

        fun flushProse() {
            if (prose.isNotEmpty()) {
                out.append(applyRolesIn(prose.toString()))
                prose.setLength(0)
            }
        }

        md.lines().forEachIndexed { index, line ->
            val marker = fenceAt(line)
            val open = fence
            val isCode = when {
                open == null && marker != null -> { fence = marker.fence; true }
                open == null -> false
                else -> { if (marker != null && marker.closes(open)) fence = null; true }
            }
            val piece = if (index == 0) line else "\n$line"
            if (isCode) {
                flushProse()
                out.append(piece)
            } else {
                prose.append(piece)
            }
        }
        flushProse()
        return out.toString()
    }

    private fun applyRolesIn(line: String): String {
        if (!line.contains('{')) return line
        val out = StringBuilder(line.length + 32)
        var i = 0
        while (i < line.length) {
            when {
                // An inline code span is copied verbatim, braces and all. It cannot cross a line
                // break, so the search for its closing run stops at the end of this one.
                line[i] == '`' -> {
                    val ticks = line.drop(i).takeWhile { it == '`' }.length
                    val lineEnd = line.indexOf('\n', i).let { if (it < 0) line.length else it }
                    val close = line.indexOf("`".repeat(ticks), i + ticks).takeIf { it in 0 until lineEnd } ?: -1
                    val end = if (close < 0) minOf(i + ticks, lineEnd) else close + ticks
                    out.append(line, i, end)
                    i = end
                }

                line[i] == '\\' && line.getOrNull(i + 1) == '{' -> {
                    out.append('{')
                    i += 2
                }

                line[i] == '{' -> {
                    val role = ROLES.firstOrNull { line.startsWith("$it:", i + 1) }
                    val end = if (role == null) -1 else matchingBrace(line, i)
                    if (role == null || end < 0) {
                        out.append(line[i])
                        i++
                    } else {
                        val body = line.substring(i + role.length + 2, end)
                        out.append("""<span class="field-md-$role">""")
                            .append(applyRolesIn(body))
                            .append("</span>")
                        i = end + 1
                    }
                }

                else -> {
                    out.append(line[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Index of the `}` closing the `{` at [open], honouring nesting, or -1 when unbalanced. */
    private fun matchingBrace(line: String, open: Int): Int {
        var depth = 0
        for (i in open until line.length) {
            when (line[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    // ---------------------------------------------------------------- fences

    private data class Fence(val char: Char, val length: Int)

    private data class FenceMarker(val fence: Fence, val info: String) {
        /** A fence closes an open one only when it matches and carries no info string. */
        fun closes(open: Fence): Boolean =
            info.isEmpty() && fence.char == open.char && fence.length >= open.length
    }

    private fun fenceAt(line: String): FenceMarker? {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > 3) return null
        val rest = line.substring(indent)
        val char = rest.firstOrNull() ?: return null
        if (char != '`' && char != '~') return null
        val length = rest.takeWhile { it == char }.length
        if (length < 3) return null
        val info = rest.substring(length).trim()
        // A backtick in the info string means this is an inline code span, not a fence.
        if (char == '`' && info.contains('`')) return null
        return FenceMarker(Fence(char, length), info)
    }

    // --------------------------------------------------------------- parsing

    /**
     * Parser and flavour are built per call rather than shared. Boxes run concurrently and
     * `GFMFlavourDescriptor` initialises its parser manager lazily; the allocation is noise next to
     * the parse, and a shared instance would be a race.
     */
    private fun renderMarkdown(md: String): String {
        val src = applyRoles(md)
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(src)
        return HtmlGenerator(src, tree, flavour).generateHtml()
            .removePrefix("<body>")
            .removeSuffix("</body>")
    }
}

/**
 * Shows [text], rendered as markdown, in this box's output area on [channel].
 *
 * The compact way to put a figure in an output pane — see [FieldMarkdown] for the dialect, which is
 * GitHub-flavoured markdown plus raw-HTML passthrough, `:::row` columns and `{role:text}` spans.
 * The text is trimmed for you, so a `"""` block indented inside a `with(…) { }` renders as intended.
 *
 * ```kotlin
 * markdown("keys", """
 *     ### version history after three saves
 *
 *     | # | file | note |
 *     |---|------|------|
 *     ${versions.joinToString("\n") { "| ${'$'}{it.index} | ${'$'}{it.file} | ${'$'}{it.note ?: "—"} |" }}
 *
 *     dropped in silence: {bad:${'$'}{dropped.joinToString(", ")}}
 * """, width = 820)
 * ```
 *
 * @param channel output disclosure that receives the card, and its title
 * @param text markdown; `${'$'}`-interpolated HTML from other Field APIs passes through verbatim
 * @param width pins the card to this many CSS pixels — set it for a `capture="box"` docs screenshot,
 *   which clips to the output card and would otherwise cut off anything past the clip
 * @param open reveal the disclosure, so the figure is visible without a click
 */
fun Box.markdown(channel: String, text: String, width: Int? = null, open: Boolean = true) {
    display(channel, FieldMarkdown.card(text, width))
    if (open) displayOpen(channel)
}

/** Shows [text] as markdown on the default `output` channel. See [Box.markdown]. */
fun Box.markdown(text: String, width: Int? = null, open: Boolean = true) {
    markdown("output", text, width, open)
}

private const val MARKDOWN_PUNCTUATION = "\\`*_{}[]()#+-.!|<>&~"

/**
 * [value] as literal text inside markdown — the safe way to interpolate a *computed* value into a
 * [Box.markdown] figure, and effectively required inside a table cell.
 *
 * Runtime values are not markdown. A `|` splits the table row it lands in, a pair of `*` or `_`
 * turns into emphasis, something shaped like `<b>` is read as raw HTML and disappears rather than
 * being shown, and a newline ends the row early. Field values hit all of these in practice — a
 * StackSL program reads `saw 110 *`, a path or a `toString()` can hold anything. This
 * backslash-escapes markdown punctuation and folds newlines to spaces, so what you computed is what
 * the figure shows. `null` renders as `null`, matching plain `${'$'}` interpolation.
 *
 * Two places not to use it. HTML from another Field API — `key.h`, `diffHtml()`, `graphSvg()` — is
 * *meant* to pass through, so interpolate that directly. And a fenced code block is already
 * literal, so a multi-line dump belongs in a fence rather than here.
 */
fun mdEscape(value: Any?): String {
    val text = value.toString().replace("\r\n", "\n")
    return buildString(text.length + 16) {
        text.forEach { c ->
            when {
                c == '\n' || c == '\r' -> append(' ')
                c in MARKDOWN_PUNCTUATION -> append('\\').append(c)
                else -> append(c)
            }
        }
    }
}

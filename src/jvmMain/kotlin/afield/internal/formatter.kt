package afield

import org.jetbrains.ktfmt.format.FileType
import org.jetbrains.ktfmt.format.Formatter
import org.jetbrains.ktfmt.format.KotlinCode
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

fun formatKotlinCode(code: String, blockifyComments: Boolean = true): String {
    // Box code is a script: top-level statements, not only declarations.
    // ktfmt attaches comments that follow the last statement of a script to that statement's last
    // token, so a closing `}` swallows them (`}// note`). A sentinel statement after them gives them
    // something to precede; it is removed again below.
    val endsInComment = code.lines().lastOrNull { it.isNotBlank() }?.trim()?.startsWith("//") == true
    val input = if (endsInComment) code.trimEnd() + "\n" + END_SENTINEL + "\n" else code
    var formatted = restoreFragmentBreaks(
        Formatter.format(Formatter.KOTLINLANG_FORMAT.copy(maxWidth = MAX_WIDTH), KotlinCode(input, FileType.SCRIPT))
    )
    if (endsInComment) formatted = formatted.trimEnd().removeSuffix(END_SENTINEL).trimEnd() + "\n"
    formatted = restoreStatementSpacing(code, formatted)
    formatted = joinAssignmentBreaks(formatted)
    return if (blockifyComments) blockifyLineCommentRuns(formatted) else formatted
}

/**
 * Undo ktfmt's break after `=` when the right-hand side is one multi-line call or block:
 *
 * ```
 * val cloth =                 val cloth = sim.addCloth(
 *     sim.addCloth(               width = 22,
 *         width = 22,     ->  )
 *     )
 * ```
 *
 * The kotlinlang style breaks there whenever a dotted call's arguments go vertical, which pushes
 * the name a reader is scanning for onto a line of its own and every argument one level deeper.
 * The join only applies when the opening line ends the bracket it opens on its own closing line at
 * the same indentation, and never across a raw string, whose indentation is its content. ktfmt
 * re-breaks it on the next format and this re-joins it, so the pair is still a fixed point.
 */
fun joinAssignmentBreaks(code: String): String {
    val lines = code.split("\n").toMutableList()
    var i = 0
    while (i + 1 < lines.size) {
        val head = lines[i]
        val next = lines[i + 1]
        val indent = head.length - head.trimStart().length
        val opener = next.trimEnd().lastOrNull()
        val closer = when (opener) { '(' -> ")"; '{' -> "}"; '[' -> "]"; else -> null }
        if (!head.trimEnd().endsWith(" =") || head.trimStart().startsWith("//") || closer == null ||
            next.length - next.trimStart().length != indent + 4
        ) { i++; continue }
        val end = (i + 2 until lines.size).firstOrNull { j ->
            val l = lines[j]
            l.isNotBlank() && l.length - l.trimStart().length <= indent + 4
        }
        val body = if (end == null) emptyList() else lines.subList(i + 2, end)
        val closes = end != null && lines[end].length - lines[end].trimStart().length == indent + 4 &&
            lines[end].trimStart().startsWith(closer)
        if (!closes || body.any { "\"\"\"" in it } || "\"\"\"" in next) { i++; continue }
        val joined = head.trimEnd() + " " + next.trim()
        val dedented = body.map { if (it.isBlank()) it else it.removePrefix("    ") }
        val closing = lines[end!!].removePrefix("    ")
        val replacement = listOf(joined) + dedented + closing
        for (k in i..end) lines.removeAt(i)
        lines.addAll(i, replacement)
        i += 1
    }
    return lines.joinToString("\n")
}

// Box code is read in an editor beside a canvas and in a docs column, and it was mostly written
// to about 100. At 200, ktfmt joined deliberately wrapped string concatenations into one line.
private const val MAX_WIDTH = 120

private val BLANK_LINE = Regex("""\n[ \t]*\n""")

/**
 * Take out the blank lines ktfmt adds between top-level statements of a script.
 *
 * In script mode ktfmt separates every pair of top-level statements with a blank line, so a box's
 * consecutive `buf.add(...)` lines come back double-spaced. Blank lines the author wrote are kept:
 * for each adjacent pair of statements, a gap that had no blank line in [input] has none in
 * [formatted]. ktfmt never reorders, adds or drops statements, so the two lists pair up by
 * position; if they do not (a parse problem on either side), [formatted] is returned unchanged.
 */
fun restoreStatementSpacing(input: String, formatted: String): String {
    fun statements(code: String) = try {
        FieldKtParser.parse(code).script?.blockExpression?.statements
    } catch (t: Throwable) {
        null
    }
    val before = statements(input) ?: return formatted
    val after = statements(formatted) ?: return formatted
    if (before.size != after.size) return formatted
    val out = StringBuilder(formatted)
    for (k in after.indices.reversed()) {
        if (k == 0) continue
        val gapBefore = input.substring(before[k - 1].textRange.endOffset, before[k].textRange.startOffset)
        if (BLANK_LINE.containsMatchIn(gapBefore)) continue
        val start = after[k - 1].textRange.endOffset
        val end = after[k].textRange.startOffset
        out.replace(start, end, BLANK_LINE.replace(formatted.substring(start, end), "\n"))
    }
    return out.toString()
}

private const val END_SENTINEL = "__afieldFormatterEnd__"

// ktfmt puts a space after every `//`, which turns a `//%-` fragment break into `// %-`. The
// splitter accepts both (isFragmentBreak), but the documented spelling is the one boxes should
// carry, and older Field read the spaced form as a plain comment.
private val SPACED_FRAGMENT_BREAK = Regex("""(?m)^(\s*)//\s+%-""")

fun restoreFragmentBreaks(code: String): String = code.replace(SPACED_FRAGMENT_BREAK, "$1//%-")

// Two // lines read as a pair of remarks as often as they read as one paragraph, so the run has
// to be longer than that to be worth a pair of delimiters.
private const val MINIMUM_RUN = 3

private class LineCommentLine(val end: Int, val lineStart: Int, val indent: String, val body: String)

// Collapse a run of three or more consecutive own-line // comments into one block comment.
// Runs at the same indentation with no blank line between them are one comment in intent, so
// give them one pair of delimiters.
//
// Two rules earn their keep here:
//
//  - The delimiters get their own lines and the body sits at the block's own indentation. ktfmt
//    re-indents a block comment's continuation lines to exactly that column, so this shape is a
//    fixed point, and whatever indentation the body carries inside itself survives. Aligning the
//    body past the opening delimiter would not survive: ktfmt pulls it back on the next pass.
//
//  - A run whose text contains either block-comment delimiter is left alone. Kotlin block
//    comments nest, so an opening delimiter inside the body would need a matching closer and a
//    closing one would end the block early -- either way the rest of the file is swallowed.
//
// Run this on ktfmt's output, not its input: the indentation is already canonical, and ktfmt
// never gets a second look at what we emit.
fun blockifyLineCommentRuns(code: String): String {
    // If ktfmt handed us something we can't re-parse, the transform simply doesn't apply.
    val file = try {
        FieldKtParser.parse(code)
    } catch (t: Throwable) {
        return code
    }

    val ownLine = file.collectDescendantsOfType<PsiComment>()
        .filter { it.tokenType == KtTokens.EOL_COMMENT }
        .sortedBy { it.startOffset }
        .mapNotNull { comment ->
            val start = comment.startOffset
            val lineStart = code.lastIndexOf('\n', start - 1) + 1
            val indent = code.substring(lineStart, start)
            // Anything other than leading whitespace makes this a trailing comment on a line of
            // code, which stays as it is.
            if (indent.isNotBlank()) return@mapNotNull null
            LineCommentLine(comment.endOffset, lineStart, indent, comment.text.removePrefix("//"))
        }

    val runs = mutableListOf<MutableList<LineCommentLine>>()
    ownLine.forEach { line ->
        val open = runs.lastOrNull()
        val previous = open?.lastOrNull()
        // Exactly one newline between them: same indentation, no blank line, nothing else.
        val adjacent = previous != null &&
            previous.indent == line.indent &&
            code.substring(previous.end, line.lineStart) == "\n"
        if (open != null && adjacent) open.add(line) else runs.add(mutableListOf(line))
    }

    // Back to front, so the offsets of the runs we haven't rewritten yet stay valid.
    val out = StringBuilder(code)
    runs.asReversed()
        .filter { it.size >= MINIMUM_RUN }
        .filter { run -> run.none { it.body.contains("/*") || it.body.contains("*/") } }
        .forEach { run ->
            val indent = run.first().indent
            val block = buildString {
                append(indent).append("/*\n")
                run.forEach { line ->
                    val text = line.body.removePrefix(" ").trimEnd()
                    // An empty line stays empty rather than becoming an indent's worth of spaces.
                    if (text.isEmpty()) append('\n') else append(indent).append(text).append('\n')
                }
                append(indent).append("*/")
            }
            out.replace(run.first().lineStart, run.last().end, block)
        }
    return out.toString()
}

private val glslProperties = setOf(
    "fragment",
    "vertex",
    "compute",
    "task",
    "mesh",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable"
)

fun formatCodeProperty(property: String, code: String): String? = when (property) {
    "code" -> formatKotlinCode(code)
    in glslProperties -> formatGlslCode(code)
    else -> null
}

fun formatGlslCode(code: String): String {
    val normalized = splitGlslStatements(code)
    val out = StringBuilder()
    var indent = 0
    var previousBlank = false
    var previousLine = ""

    normalized.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) {
            if (!previousBlank && out.isNotEmpty()) out.appendLine()
            previousBlank = true
            return@forEach
        }
        previousBlank = false
        if (line.startsWith("}")) indent = (indent - 1).coerceAtLeast(0)
        val effectiveIndent = if (line.startsWith("case ") || line.startsWith("default:")) {
            (indent - 1).coerceAtLeast(0)
        } else {
            indent
        }
        if (effectiveIndent == 0 && previousLine == "};" && looksLikeGlslFunctionDeclaration(line)) {
            out.appendLine()
        }
        repeat(effectiveIndent) { out.append("    ") }
        val cleaned = cleanGlslSpacing(line)
        out.appendLine(cleaned)
        previousLine = cleaned
        if (line.endsWith("{")) indent += 1
    }

    return out.toString().trimEnd() + "\n"
}

private fun splitGlslStatements(code: String): String {
    val out = StringBuilder()
    var i = 0
    var parenDepth = 0
    var atLineStart = true
    var inLineComment = false
    var inBlockComment = false
    var inString: Char? = null
    var escaped = false

    fun appendNewlineIfNeeded() {
        while (out.endsWith(' ') || out.endsWith('\t')) out.setLength(out.length - 1)
        if (!out.endsWith('\n')) out.append('\n')
        atLineStart = true
    }

    while (i < code.length) {
        val c = code[i]
        val next = code.getOrNull(i + 1)

        if (inLineComment) {
            out.append(c)
            if (c == '\n') {
                inLineComment = false
                atLineStart = true
            }
            i++
            continue
        }

        if (inBlockComment) {
            out.append(c)
            if (c == '*' && next == '/') {
                out.append(next)
                i += 2
                inBlockComment = false
            } else {
                if (c == '\n') atLineStart = true
                i++
            }
            continue
        }

        if (inString != null) {
            out.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == inString) {
                inString = null
            }
            i++
            continue
        }

        if (atLineStart && c == '#') {
            val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
            out.append(code.substring(i, end).trimEnd())
            appendNewlineIfNeeded()
            i = if (end < code.length) end + 1 else end
            continue
        }

        when {
            c == '/' && next == '/' -> {
                out.append(c).append(next)
                inLineComment = true
                i += 2
            }
            c == '/' && next == '*' -> {
                out.append(c).append(next)
                inBlockComment = true
                i += 2
            }
            c == '"' || c == '\'' -> {
                out.append(c)
                inString = c
                i++
            }
            c == '(' -> {
                parenDepth += 1
                out.append(c)
                atLineStart = false
                i++
            }
            c == ')' -> {
                parenDepth = (parenDepth - 1).coerceAtLeast(0)
                out.append(c)
                atLineStart = false
                i++
            }
            c == '{' -> {
                if (out.isNotEmpty() && !out.endsWith(' ') && !out.endsWith('\n')) out.append(' ')
                out.append(c)
                appendNewlineIfNeeded()
                i++
            }
            c == '}' -> {
                appendNewlineIfNeeded()
                if (next == ';') {
                    out.append("};")
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
                appendNewlineIfNeeded()
            }
            c == ';' && parenDepth == 0 -> {
                out.append(c)
                appendNewlineIfNeeded()
                i++
            }
            c == '\n' -> {
                appendNewlineIfNeeded()
                i++
            }
            c.isWhitespace() -> {
                if (!out.endsWith(' ') && !out.endsWith('\n')) out.append(' ')
                i++
            }
            else -> {
                out.append(c)
                atLineStart = false
                i++
            }
        }
    }

    return out.toString()
}

private fun cleanGlslSpacing(line: String): String {
    return line
        .replace(Regex("""\s+([,;)\]])"""), "$1")
        .replace(Regex("""([(\[])\s+"""), "$1")
        .replace(Regex("""\s+\{"""), " {")
        .replace(Regex("""\s{2,}"""), " ")
}

private fun looksLikeGlslFunctionDeclaration(line: String): Boolean {
    if (!line.endsWith("{")) return false
    if (!line.contains('(') || !line.contains(')')) return false
    val head = line.substringBefore('(').trim()
    if (head.isEmpty()) return false
    val name = head.substringAfterLast(' ')
    if (name in setOf("if", "for", "while", "switch")) return false
    return head.any { it.isWhitespace() }
}

private fun StringBuilder.endsWith(c: Char): Boolean = isNotEmpty() && this[length - 1] == c

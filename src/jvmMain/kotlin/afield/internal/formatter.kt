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
    val formatted = Formatter.format(Formatter.KOTLINLANG_FORMAT.copy(maxWidth = 200), KotlinCode(code, FileType.SCRIPT))
    return if (blockifyComments) blockifyLineCommentRuns(formatted) else formatted
}

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

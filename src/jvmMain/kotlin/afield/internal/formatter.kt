package afield

import com.facebook.ktfmt.format.Formatter

fun formatKotlinCode(code: String) = Formatter.format(Formatter.KOTLINLANG_FORMAT.copy(maxWidth = 200), code)

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

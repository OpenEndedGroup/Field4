package afield.macros

import afield.Box

fun Box.macro_sourceBlock(args: List<String>): Insert {
    val raw = args.joinToString("")
    val body = extractSourceBlockBody(raw)
    val sourceLiteral = kotlinStringLiteral(body)
    val boxLiteral = kotlinStringLiteral(id)
    return Insert(
        here = "afield.stdlib.SourceBackedBlock(boxId = $boxLiteral, source = $sourceLiteral) {\n$body\n}"
    )
}

private fun extractSourceBlockBody(rawArgs: String): String {
    val trimmed = rawArgs.trim()
    require(trimmed.isNotEmpty()) { "sourceBlock expects a trailing lambda, e.g. sourceBlock { value }" }

    val unwrapped = stripOuterParens(trimmed).trim()
    val open = unwrapped.indexOf('{')
    val close = unwrapped.lastIndexOf('}')
    require(open >= 0 && close > open) { "sourceBlock expects a lambda body, e.g. sourceBlock { value }" }

    return unwrapped.substring(open + 1, close).trim()
}

private fun stripOuterParens(text: String): String {
    if (!text.startsWith("(") || !text.endsWith(")")) return text

    var depth = 0
    var inString = false
    var escaped = false
    for (i in text.indices) {
        val c = text[i]
        if (inString) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') inString = false
            continue
        }

        when (c) {
            '"' -> inString = true
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0 && i != text.lastIndex) return text
            }
        }
    }

    return if (depth == 0) text.substring(1, text.lastIndex) else text
}

private fun kotlinStringLiteral(text: String): String = buildString(text.length + 16) {
    append('"')
    text.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
    }
    append('"')
}

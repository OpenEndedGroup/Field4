package afield.ai.core.lang

data class LanguageSpec(
    val name: String,
    val wrap: (String) -> String,
    val unwrap: (String) -> String
)

class MalformedAI(message: String) : RuntimeException(message)

private fun normalizeGlslPreamble(code: String): String {
    val lines = code.trimEnd().split("\n")
    val extensions = mutableListOf<String>()
    val body = mutableListOf<String>()

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#version") -> continue // normalize to required Vulkan GLSL version
            trimmed.startsWith("#extension") -> {
                extensions += trimmed
                continue
            }
            else -> body += line
        }
    }

    return buildString {
        append("#version 450")
        if (extensions.isNotEmpty()) {
            append("\n")
            append(extensions.joinToString("\n"))
        }
        if (body.isNotEmpty()) {
            append("\n\n")
            append(body.joinToString("\n"))
        }
    }.trimEnd()
}

/**
 * Wraps top-level Kotlin script into a fun SCRIPT() block with preamble/postamble markers.
 * Keeps leading import/@file lines at top.
 */
fun wrapScript(script: String): String {
    val lines = script.split("\n")
    val importLines = lines.takeWhile { it.trim().startsWith("import ") || it.trim().startsWith("@file") }
    val bodyLines = lines.drop(importLines.size)
    return buildString {
        if (importLines.isNotEmpty()) {
            append(importLines.joinToString("\n"))
            append("\n\n")
        }
        append("/*preamble*/ fun SCRIPT() {\n")
        append(bodyLines.joinToString("\n"))
        if (bodyLines.isNotEmpty()) append("\n")
        append("/*postamble*/ }")
    }
}

/**
 * Lenient unwrap for Kotlin script output.
 */
fun unwrapScriptLenient(wrapped: String): String {
    val lines = wrapped.split("\n")

    // Case A: markers exist
    val preambleIdx = lines.indexOfFirst { it.contains("/*preamble*/") }
    val postambleIdx = lines.indexOfFirst { it.contains("/*postamble*/") }
    if (preambleIdx != -1 && postambleIdx != -1 && postambleIdx > preambleIdx) {
        val importLines = lines.subList(0, preambleIdx).dropLastWhile { it.isBlank() }
        val bodyLines = lines.subList(preambleIdx + 1, postambleIdx)
        return buildString {
            if (importLines.isNotEmpty()) {
                append(importLines.joinToString("\n"))
                if (bodyLines.isNotEmpty()) append("\n\n")
            }
            append(bodyLines.joinToString("\n"))
        }.trimEnd()
    }

    // Case B: salvage SCRIPT() without markers
    val scriptStart = lines.indexOfFirst { it.contains("fun SCRIPT()") && it.contains("{") }
    if (scriptStart != -1) {
        var depth = 0
        var started = false
        val body = mutableListOf<String>()
        for (i in scriptStart until lines.size) {
            val line = lines[i]
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            if (!started) {
                depth += opens - closes
                started = true
                continue
            } else {
                depth += opens - closes
                if (depth <= 0) break
                body += line
            }
        }
        val imports = lines.take(scriptStart).dropLastWhile { it.isBlank() }
        return buildString {
            if (imports.isNotEmpty()) {
                append(imports.joinToString("\n"))
                if (body.isNotEmpty()) append("\n\n")
            }
            append(body.joinToString("\n"))
        }.trimEnd()
    }

    // Case C: already a top-level script
    return wrapped.trimEnd()
}

val KOTLIN_SCRIPT: LanguageSpec = LanguageSpec(
    name = "Kotlin",
    wrap = ::wrapScript,
    unwrap = ::unwrapScriptLenient
)

val GLSL_VERTEX: LanguageSpec = LanguageSpec(
    name = "GLSL (vertex)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_FRAGMENT: LanguageSpec = LanguageSpec(
    name = "GLSL (fragment)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_MESH: LanguageSpec = LanguageSpec(
    name = "GLSL (mesh)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_TASK: LanguageSpec = LanguageSpec(
    name = "GLSL (task)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

val GLSL_COMPUTE: LanguageSpec = LanguageSpec(
    name = "GLSL (compute)",
    wrap = ::normalizeGlslPreamble,
    unwrap = ::normalizeGlslPreamble
)

private val GLSL_PROPERTY_SPECS: Map<String, LanguageSpec> = mapOf(
    "vertex" to GLSL_VERTEX,
    "fragment" to GLSL_FRAGMENT,
    "mesh" to GLSL_MESH,
    "task" to GLSL_TASK,
    "compute" to GLSL_COMPUTE
)

fun glslSpecForProperty(property: String): LanguageSpec? =
    GLSL_PROPERTY_SPECS[property.lowercase()]

package afield.ai.core

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

private fun normalizeSlangSource(code: String): String = code.trimEnd()

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

private fun normalizeRayTracingGlslPreamble(code: String): String {
    val lines = code.trimEnd().split("\n")
    val extensions = linkedSetOf("#extension GL_EXT_ray_tracing : require")
    val body = mutableListOf<String>()

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#version") -> continue
            trimmed.startsWith("#extension") -> {
                extensions += trimmed
                continue
            }
            else -> body += line
        }
    }

    return buildString {
        append("#version 460")
        append("\n")
        append(extensions.joinToString("\n"))
        if (body.isNotEmpty()) {
            append("\n\n")
            append(body.joinToString("\n"))
        }
    }
}

val GLSL_RAY_TRACING: LanguageSpec = LanguageSpec(
    name = "GLSL (ray generation)",
    wrap = ::normalizeRayTracingGlslPreamble,
    unwrap = ::normalizeRayTracingGlslPreamble
)

val GLSL_MISS: LanguageSpec = GLSL_RAY_TRACING.copy(name = "GLSL (miss)")
val GLSL_CLOSEST_HIT: LanguageSpec = GLSL_RAY_TRACING.copy(name = "GLSL (closest hit)")
val GLSL_ANY_HIT: LanguageSpec = GLSL_RAY_TRACING.copy(name = "GLSL (any hit)")
val GLSL_INTERSECTION: LanguageSpec = GLSL_RAY_TRACING.copy(name = "GLSL (intersection)")
val GLSL_CALLABLE: LanguageSpec = GLSL_RAY_TRACING.copy(name = "GLSL (callable)")

val SLANG_VERTEX: LanguageSpec = LanguageSpec(
    name = "Slang (vertex)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_FRAGMENT: LanguageSpec = LanguageSpec(
    name = "Slang (fragment)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_MESH: LanguageSpec = LanguageSpec(
    name = "Slang (mesh)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_TASK: LanguageSpec = LanguageSpec(
    name = "Slang (task)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_COMPUTE: LanguageSpec = LanguageSpec(
    name = "Slang (compute)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_VERTEX_FRAGMENT: LanguageSpec = LanguageSpec(
    name = "Slang (vertex+fragment)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_RAY_TRACING: LanguageSpec = LanguageSpec(
    name = "Slang (ray generation)",
    wrap = ::normalizeSlangSource,
    unwrap = ::normalizeSlangSource
)

val SLANG_MISS: LanguageSpec = SLANG_RAY_TRACING.copy(name = "Slang (miss)")
val SLANG_CLOSEST_HIT: LanguageSpec = SLANG_RAY_TRACING.copy(name = "Slang (closest hit)")
val SLANG_ANY_HIT: LanguageSpec = SLANG_RAY_TRACING.copy(name = "Slang (any hit)")
val SLANG_INTERSECTION: LanguageSpec = SLANG_RAY_TRACING.copy(name = "Slang (intersection)")
val SLANG_CALLABLE: LanguageSpec = SLANG_RAY_TRACING.copy(name = "Slang (callable)")

private val GLSL_PROPERTY_SPECS: Map<String, LanguageSpec> = mapOf(
    "vertex" to GLSL_VERTEX,
    "fragment" to GLSL_FRAGMENT,
    "mesh" to GLSL_MESH,
    "task" to GLSL_TASK,
    "compute" to GLSL_COMPUTE,
    "raygen" to GLSL_RAY_TRACING,
    "miss" to GLSL_MISS,
    "closesthit" to GLSL_CLOSEST_HIT,
    "anyhit" to GLSL_ANY_HIT,
    "intersection" to GLSL_INTERSECTION,
    "callable" to GLSL_CALLABLE
)

private val SLANG_PROPERTY_SPECS: Map<String, LanguageSpec> = mapOf(
    "vertex_s" to SLANG_VERTEX,
    "fragment_s" to SLANG_FRAGMENT,
    "vertex_fragment" to SLANG_VERTEX_FRAGMENT,
    "mesh_s" to SLANG_MESH,
    "task_s" to SLANG_TASK,
    "compute_s" to SLANG_COMPUTE,
    "raygen_s" to SLANG_RAY_TRACING,
    "miss_s" to SLANG_MISS,
    "closesthit_s" to SLANG_CLOSEST_HIT,
    "anyhit_s" to SLANG_ANY_HIT,
    "intersection_s" to SLANG_INTERSECTION,
    "callable_s" to SLANG_CALLABLE
)

private val SHADER_PROPERTY_SPECS: Map<String, LanguageSpec> =
    GLSL_PROPERTY_SPECS + SLANG_PROPERTY_SPECS

fun glslSpecForProperty(property: String): LanguageSpec? =
    GLSL_PROPERTY_SPECS[property.lowercase()]

fun shaderSpecForProperty(property: String): LanguageSpec? =
    SHADER_PROPERTY_SPECS[property.lowercase()]

/**
 * Language spec for the Nano micro-computer assembly language.
 * Nano is an 8-bit VM with 4 registers (r0–r3) and 32 bytes of RAM,
 * designed for emergence and artificial-life simulations.
 */
val NANO_ASM: LanguageSpec = LanguageSpec(
    name = "Nano",
    wrap = { it.trimEnd() },
    unwrap = { it.trimEnd() }
)

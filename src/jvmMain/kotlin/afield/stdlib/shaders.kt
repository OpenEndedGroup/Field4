package afield.stdlib

import afield.*
import afield.four.Renderer
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.FIELD_PROBE_BUFFER_NAME
import afield.four.util.FIELD_PROBE_INCLUDE_NAME
import afield.four.util.ShaderFieldProbe
import afield.four.util.IncludeResolver
import afield.four.util.getShaderTemplateResolverForBox
import afield.four.util.runTemplate
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.time.LocalDateTime

// ------------------------------
// Box properties
// ------------------------------
var Box.fragment by Inheritance<String?>()
var Box.vertex by Inheritance<String?>()
var Box.vertex_fragment by Inheritance<String?>()
var Box.task by Inheritance<String?>()
var Box.mesh by Inheritance<String?>()
var Box.fragment_s by Inheritance<String?>()
var Box.vertex_s by Inheritance<String?>()
var Box.task_s by Inheritance<String?>()
var Box.mesh_s by Inheritance<String?>()
var Box._fragment by Delegation<String?>()
var Box._vertex by Delegation<String?>()
var Box._task by Delegation<String?>()
var Box._mesh by Delegation<String?>()

var Box.compute by Inheritance<String?>()
var Box.compute_s by Inheritance<String?>()
var Box._compute by Delegation<String?>()

var Box._shader by Delegation<ModulePointer?>()
var Box._computeShader by Delegation<ComputeModulePointer?>()

var Box.meshShader by Inheritance<MeshModulePointer?>()
var Box._meshShader by Delegation<MeshModulePointer?>()
var Box._fieldProbe by Delegation<ShaderFieldProbe?>()

val Box.shaderTemplate by Delegation<MutableMap<String, Any>>(
    defaultValue = { HashMap<String, Any>() })

fun Box.fieldProbeHere(
    samples: Int = afield.four.util.FIELD_PROBE_DEFAULT_SAMPLES,
    binding: Int = afield.four.util.FIELD_PROBE_DEFAULT_BINDING,
    descriptorSet: Int = afield.four.util.FIELD_PROBE_DEFAULT_SET,
    enabled: Boolean = true,
    ordinalRange: UIntRange? = null
): ShaderFieldProbe {
    val probe = ensureFieldProbe()
    probe.resetLiveCodingRegistrations()
    probe.configure(
        samples = samples,
        binding = binding,
        descriptorSet = descriptorSet,
        enabled = enabled
    )
    if (ordinalRange != null) probe.ordinalRange(ordinalRange)
    else probe.ordinalRange()
    return probe
}

// ------------------------------
// Public macro placeholders (expanded by MacroRegistry)
// ------------------------------
fun Box.moduleHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.meshModuleHere(p: Parameters): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.moduleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.shadertoyHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}

fun Box.shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}


// ------------------------------
// Utilities
// ------------------------------
private val glslSourceLineRegex = Regex("""\b(?:ERROR|WARNING):\s*\d+:(\d+):""", RegexOption.IGNORE_CASE)
private val fileLineRegex = Regex(""":[ \t]*(\d+):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val slangSourceLineRegex = Regex("""\((\d+)\):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val anyColonLineRegex = Regex(""":(\d+):""")
private val explicitLineRegex = Regex("""\bline\s+(\d+)\b""", RegexOption.IGNORE_CASE)

private val slangShaderPropertyByBase = mapOf(
    "vertex" to "vertex_s",
    "fragment" to "fragment_s",
    "compute" to "compute_s",
    "task" to "task_s",
    "mesh" to "mesh_s"
)
private const val sharedVertexFragmentProperty = "vertex_fragment"

private data class ShaderIncludeRef(val boxName: String, val property: String) {
    val key = "${boxName.trim()}.${property.trim()}"
}

fun numberFromErrorMessage(m: String): Int? {
    glslSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    fileLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    slangSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    explicitLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return anyColonLineRegex.findAll(m).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.lastOrNull()
}

private fun slangPropertyFor(baseProperty: String): String? = slangShaderPropertyByBase[baseProperty.lowercase()]

private fun shaderPropertyVariants(baseProperty: String): List<String> {
    val normalized = baseProperty.lowercase()
    val shared = if (normalized == "vertex" || normalized == "fragment") listOf(sharedVertexFragmentProperty) else emptyList()
    return shared + listOfNotNull(normalized, slangPropertyFor(normalized))
}

private fun isSlangShaderProperty(property: String): Boolean =
    property.lowercase() == sharedVertexFragmentProperty ||
        property.lowercase().endsWith("_s") && baseShaderProperty(property) in slangShaderPropertyByBase

private fun baseShaderProperty(property: String): String = when (property.lowercase()) {
    "vertex", "vertex_s" -> "vertex"
    "fragment", "fragment_s" -> "fragment"
    "compute", "compute_s" -> "compute"
    "task", "task_s" -> "task"
    "mesh", "mesh_s" -> "mesh"
    else -> property.lowercase()
}

private fun defaultShaderProperty(kind: ShaderKind): String = when (kind) {
    ShaderKind.VERTEX_SHADER -> "vertex"
    ShaderKind.FRAGMENT_SHADER -> "fragment"
    ShaderKind.COMPUTE_SHADER -> "compute"
    ShaderKind.TASK_SHADER -> "task"
    ShaderKind.MESH_SHADER -> "mesh"
    else -> "fragment"
}

private fun shaderKindForProperty(property: String, fallback: ShaderKind): ShaderKind = when (property.lowercase()) {
    "vertex", "vertex_s" -> ShaderKind.VERTEX_SHADER
    "fragment", "fragment_s" -> ShaderKind.FRAGMENT_SHADER
    "compute", "compute_s" -> ShaderKind.COMPUTE_SHADER
    "task", "task_s" -> ShaderKind.TASK_SHADER
    "mesh", "mesh_s" -> ShaderKind.MESH_SHADER
    else -> fallback
}

private fun parseShaderIncludeRef(path: String, defaultProperty: String): ShaderIncludeRef {
    val trimmed = path.trim()
    require(trimmed.isNotEmpty()) { "Empty shader include path." }
    val dot = trimmed.lastIndexOf('.')
    if (dot >= 0) {
        require(dot > 0 && dot < trimmed.lastIndex) {
            "Invalid shader include path '$trimmed'. Use '<box>' or '<box>.<property>'."
        }
        val boxName = trimmed.substring(0, dot).trim()
        val property = trimmed.substring(dot + 1).trim()
        require(boxName.isNotEmpty() && property.isNotEmpty()) {
            "Invalid shader include path '$trimmed'."
        }
        return ShaderIncludeRef(boxName, property)
    }
    return ShaderIncludeRef(trimmed, defaultProperty)
}

private fun findBoxByNameForInclude(inside: Box, requestedName: String): Box? {
    val name = requestedName.trim()
    if (name.isEmpty()) return null

    var found: Box? = null
    inside.visitParents {
        if (found == null && it.name == name && !it.getPropertyRaw("_tombstone").isTrue()) found = it
    }
    if (found != null) return found

    val fringe = ArrayDeque<Box>()
    val seen = mutableSetOf<Box>()
    fringe.add(inside.root)
    while (fringe.isNotEmpty()) {
        val next = fringe.removeFirst()
        if (!seen.add(next)) continue
        if (next.name == name && !next.getPropertyRaw("_tombstone").isTrue()) return next
        fringe.addAll(next.children)
    }
    return null
}

private fun getIncludePropertyValue(box: Box, property: String): String? {
    val normalized = property.trim()
    return when (normalized.lowercase()) {
        "vertex" -> box.vertex
        "vertex_fragment" -> box.vertex_fragment
        "vertex_s" -> box.vertex_s
        "fragment" -> box.fragment
        "fragment_s" -> box.fragment_s
        "compute" -> box.compute
        "compute_s" -> box.compute_s
        "task" -> box.task
        "task_s" -> box.task_s
        "mesh" -> box.mesh
        "mesh_s" -> box.mesh_s
        else -> box.getPropertyRaw(normalized) as? String
    }
}

private fun Box.ensureFieldProbe(): ShaderFieldProbe {
    val existing = _fieldProbe
    if (existing != null) return existing
    return ShaderFieldProbe(this).also { _fieldProbe = it }
}

private fun builtInShaderInclude(inside: Box, includePath: String): String? {
    return when (includePath.trim()) {
        FIELD_PROBE_INCLUDE_NAME -> inside.ensureFieldProbe().includeSource()
        else -> null
    }
}

private fun attachFieldProbe(box: Box, parameters: Parameters) {
    val probe = box._fieldProbe ?: return
    parameters.fieldProbe = probe

    val previousResolver = parameters.ssboResolver
    parameters.ssboResolver = { requested ->
        if (requested.name == FIELD_PROBE_BUFFER_NAME) {
            probe.bufferParameters(parameters.renderer)
        } else {
            previousResolver(requested)
        }
    }
}

private fun preprocess(
    inside: Box,
    source: String,
    kind: ShaderKind,
    defaultProperty: String = defaultShaderProperty(kind),
    includeStack: List<String> = emptyList()
): String {
    val includeResolver = IncludeResolver { includePath, line, _ ->
        builtInShaderInclude(inside, includePath)?.let { builtIn ->
            return@IncludeResolver buildString {
                append(builtIn)
                if (!builtIn.endsWith('\n')) append('\n')
                append("#line ${line + 2}\n")
            }
        }

        val includeRef = parseShaderIncludeRef(includePath, defaultProperty)
        val includeKey = includeRef.key
        if (includeStack.contains(includeKey)) {
            val chain = (includeStack + includeKey).joinToString(" -> ")
            throw IllegalArgumentException("Include cycle at line $line while resolving '$includePath': $chain")
        }

        val targetBox = findBoxByNameForInclude(inside, includeRef.boxName)
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: no box named '${includeRef.boxName}'."
            )

        val includeBody = getIncludePropertyValue(targetBox, includeRef.property)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: '${includeRef.property}' on box '${targetBox.name}' is empty."
            )

        val nestedKind = shaderKindForProperty(includeRef.property, kind)
        val expandedInclude = preprocess(
            inside,
            includeBody,
            nestedKind,
            defaultProperty = includeRef.property,
            includeStack = includeStack + includeKey
        )
        buildString {
            append(expandedInclude)
            if (!expandedInclude.endsWith('\n')) append('\n')
            append("#line ${line + 2}\n")
        }
    }

    return runTemplate(source, getShaderTemplateResolverForBox(inside), includeResolver)
}

fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
    return preprocess(inside, code, kind, defaultShaderProperty(kind), emptyList())
}

/**
 * Unified compile/introspect helper that:
 *  - runs the GLSL/spirv compilation for a stage
 *  - reports errors via box.error with a friendly stage label
 *  - tells the caller whether errors occurred
 */
private data class CompileResult(val introspection: Introspection?, val hadError: Boolean)

private data class PreparedShaderStage(
    val kind: ShaderKind,
    val baseProperty: String,
    val sourceProperty: String,
    val source: String,
    val preprocessedSource: String,
    val glslSource: String,
    val usedSlang: Boolean,
    val spirv: ShaderSPIRVUtils.SPIRV? = null,
    val reflectionJson: String? = null
) {
    val logLabel: String
        get() = if (usedSlang) "$baseProperty shader (slang)" else "$baseProperty shader"
}

private fun shaderPropertyValue(box: Box, property: String): String? = when (property.lowercase()) {
    "vertex" -> box.vertex
    "vertex_fragment" -> box.vertex_fragment
    "vertex_s" -> box.vertex_s
    "fragment" -> box.fragment
    "fragment_s" -> box.fragment_s
    "compute" -> box.compute
    "compute_s" -> box.compute_s
    "task" -> box.task
    "task_s" -> box.task_s
    "mesh" -> box.mesh
    "mesh_s" -> box.mesh_s
    else -> box.getPropertyRaw(property) as? String
}

private fun hasShaderSource(box: Box, baseProperty: String): Boolean =
    shaderPropertyVariants(baseProperty).any { property ->
        !shaderPropertyValue(box, property).isNullOrBlank()
    }

private fun sourcePropertyForStage(box: Box, baseProperty: String): String {
    val normalized = baseProperty.lowercase()
    if ((normalized == "vertex" || normalized == "fragment") && !shaderPropertyValue(box, sharedVertexFragmentProperty).isNullOrBlank()) {
        return sharedVertexFragmentProperty
    }
    val slangProperty = slangPropertyFor(normalized)
    val slangSource = slangProperty?.let { shaderPropertyValue(box, it) }
    return if (!slangSource.isNullOrBlank()) slangProperty else normalized
}

private fun assignPreparedShaderStage(box: Box, stage: PreparedShaderStage) {
    when (stage.baseProperty) {
        "vertex" -> box._vertex = stage.glslSource
        "fragment" -> box._fragment = stage.glslSource
        "compute" -> box._compute = stage.glslSource
        "task" -> box._task = stage.glslSource
        "mesh" -> box._mesh = stage.glslSource
    }
}

private fun clearShaderErrorsForBaseProperties(box: Box, vararg baseProperties: String) {
    val properties = baseProperties
        .flatMap(::shaderPropertyVariants)
        .distinct()
        .toTypedArray()
    clearShaderErrors(box, *properties)
}

private fun reportShaderStageError(
    box: Box,
    propertyForUI: String,
    logLabel: String,
    message: String,
    throwable: Throwable? = null
) {
    val trimmed = message.trim().ifBlank { throwable?.message ?: logLabel }
    lprintln("error,graphics", "error during $logLabel $trimmed")
    throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    box.error(trimmed, propertyForUI, numberFromErrorMessage(trimmed) ?: 0)
}

private fun prepareShaderStage(box: Box, kind: ShaderKind, baseProperty: String): PreparedShaderStage? {
    val sourceProperty = sourcePropertyForStage(box, baseProperty)
    val source = shaderPropertyValue(box, sourceProperty)?.takeIf { it.isNotBlank() } ?: run {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader setup",
            "No shader source found for property '$sourceProperty'."
        )
        return null
    }

    val preprocessedSource = try {
        preprocess(box, source, kind, defaultProperty = sourceProperty)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader preprocessing",
            t.message ?: "while preprocessing $baseProperty shader",
            t
        )
        return null
    }

    val usedSlang = isSlangShaderProperty(sourceProperty)
    var spirv: ShaderSPIRVUtils.SPIRV? = null
    var reflectionJson: String? = null
    val glslSource = if (sourceProperty == sharedVertexFragmentProperty) {
        try {
            val compiled = SlangCompiler.compileSharedStage(preprocessedSource, kind, "$sourceProperty.slang")
            spirv = compiled.spirv
            reflectionJson = compiled.reflectionJson
            compiled.glslSource
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else if (usedSlang) {
        try {
            SlangCompiler.compileToGlsl(preprocessedSource, kind, "$sourceProperty.slang")
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else {
        preprocessedSource
    }

    return PreparedShaderStage(
        kind = kind,
        baseProperty = baseProperty,
        sourceProperty = sourceProperty,
        source = source,
        preprocessedSource = preprocessedSource,
        glslSource = glslSource,
        usedSlang = usedSlang,
        spirv = spirv,
        reflectionJson = reflectionJson
    )
}

private fun compileStage(
    box: Box,
    stage: PreparedShaderStage
): CompileResult {
    var hadError = false
    val handler = { message: String, throwable: Throwable ->
        val throwableMsg = throwable.message?.let { " $it" } ?: ""
        lprintln("error,graphics", "error during ${stage.logLabel} compilation $message$throwableMsg")
        lprintln("error,graphics", throwable.stackTraceToString())
        box.error(message, stage.sourceProperty, numberFromErrorMessage(message) ?: 0)
        hadError = true
    }
    val i = if (stage.spirv != null && stage.reflectionJson != null) {
        Introspection.fromSlangReflection(
            source = stage.glslSource,
            kind = stage.kind,
            spirv = stage.spirv,
            reflectionJson = stage.reflectionJson,
            errorHandler = handler
        )
    } else {
        Introspection(stage.glslSource, stage.kind, errorHandler = handler)
    }
    return CompileResult(if (hadError) null else i, hadError)
}

/**
 * Applies an Insert’s preamble(s) into the target box.
 */
private val preamblePrefixRegex = Regex("^\\s*/\\*(.*?)\\*/\\s*")
private const val preambleBlockStartMarker = "/*start*/"
private const val preambleBlockEndMarker = "/*end*/"

private data class PreambleSegment(val lines: List<String>)

private fun preambleLineKey(line: String): String {
    val normalized = line.trimEnd()
    if (normalized.isBlank()) return ""
    val prefix = preamblePrefixRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
    return if (prefix.isNullOrEmpty()) normalized else prefix
}

private fun isImportLine(line: String): Boolean {
    val withoutPrefix = line.replaceFirst(preamblePrefixRegex, "")
    return withoutPrefix.trimStart().startsWith("import ")
}

private fun stripPreambleBlockMarkers(line: String): String =
    line.replace(preambleBlockStartMarker, "").replace(preambleBlockEndMarker, "").trim()

private fun splitPreambleSegments(lines: List<String>): List<PreambleSegment> {
    val segments = mutableListOf<PreambleSegment>()
    var currentBlock: MutableList<String>? = null

    fun flushCurrentBlock() {
        val block = currentBlock ?: return
        if (block.isNotEmpty()) segments += PreambleSegment(block.toList())
        currentBlock = null
    }

    lines.forEach { line ->
        val normalized = line.trimEnd()
        if (normalized.isBlank()) return@forEach

        if (currentBlock != null) {
            currentBlock?.add(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
            return@forEach
        }

        if (normalized.contains(preambleBlockStartMarker)) {
            currentBlock = mutableListOf(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
        } else {
            segments += PreambleSegment(listOf(normalized))
        }
    }

    flushCurrentBlock()

    return segments
}

private fun preambleSegmentKey(segment: PreambleSegment): String {
    val prefixedLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { preamblePrefixRegex.containsMatchIn(it) }

    if (prefixedLine != null) return preambleLineKey(prefixedLine)

    val normalized = segment.lines.joinToString(separator = "\n") { it.trimEnd() }.trim()
    return normalized
}

private fun isImportSegment(segment: PreambleSegment): Boolean {
    val firstMeaningfulLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { it.isNotBlank() }
        ?: return false

    return isImportLine(firstMeaningfulLine)
}

private fun prefixed(prefix: String, code: String): String = "/*$prefix*/ $code"

private fun mergePreambleSegments(existing: List<String>, incoming: List<String>): List<PreambleSegment> {
    val merged = LinkedHashMap<String, PreambleSegment>()

    splitPreambleSegments(existing).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty() && !merged.containsKey(key)) {
            merged[key] = segment
        }
    }

    splitPreambleSegments(incoming).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty()) {
            // New insertions should win so reloads replace generated bindings in-place.
            merged[key] = segment
        }
    }

    return merged.values.toList()
}

fun applyInsertToPreamble(box: Box, e: Insert) {
    val b = e.target ?: box
    val incomingLines = buildList {
        if (e.preamble.isNotBlank()) addAll(e.preamble.trim().lines())
        if (e.preambleAfterImports.isNotBlank()) addAll(e.preambleAfterImports.trim().lines())
    }.map { it.trimEnd() }.filter { it.isNotBlank() }

    if (incomingLines.isEmpty()) return

    val existingLines = b.preamble.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    val merged = mergePreambleSegments(existingLines, incomingLines)
    val imports = merged.filter(::isImportSegment)
    val nonImports = merged.filterNot(::isImportSegment)
    b.preamble = (imports + nonImports).flatMap { it.lines }.joinToString(separator = "\n")

}

private fun clearShaderErrors(box: Box, vararg properties: String) {
    properties.forEach { property ->
        box.error(null, property, -1)
    }
}

private fun runShaderReloadAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    reloadedMessage: String,
    reload: (Box) -> Boolean
) {
    notify(reloadingMessage, "shader")
    var ok = 0
    var failed = 0
    boxes.forEach { selected ->
        val success = try {
            reload(selected)
        } catch (t: Throwable) {
            lprintln(
                "error,graphics",
                "unexpected error reloading shader in '${selected.name}' (${selected.id}): ${t.message}"
            )
            selected.error(t.message ?: "shader reload failed", "code", -1)
            false
        }
        if (success) ok++ else failed++
    }
    val summary = if (failed == 0) "$reloadedMessage ($ok)" else "$reloadedMessage ($ok ok, $failed failed)"
    notify(summary, "shader")
}

// ------------------------------
// Graphics (vertex/fragment) reload path
// ------------------------------
fun reloadShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    val vertexStage = prepareShaderStage(box, ShaderKind.VERTEX_SHADER, "vertex") ?: return false
    val fragmentStage = prepareShaderStage(box, ShaderKind.FRAGMENT_SHADER, "fragment") ?: return false

    assignPreparedShaderStage(box, vertexStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (vi, vErr) = compileStage(box, vertexStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (vErr || fErr) {
        lprintln("error,graphics", "shader compilation failed; skipping module reload.")
        return false
    }

    // keep consistent with the Module path: build Insert and apply to preamble
    val insert = insertForShader(vi!!, fi!!, "_moduleHere", listOf("()"))
    applyInsertToPreamble(box, insert)

    val p = box._shader!!.current.parameters.copy().apply {
        vertex = box._vertex!!
        fragment = box._fragment!!
        vertexIntrospection = vi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)

    // todo: patch error handler if you need finer-grained feedback
    box._shader!!.reload(p, forceReresolve = true)
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    return true
}

// ------------------------------
// Compute helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun kotlinBindingTypeName(member: Introspected.Member): String =
    if (member.physicalPointer) "afield.four.buffers.HasBuffer"
    else member.type.fold({ it.kotlinTypeName }, { "Any" })

private fun buildComputeInsert(ci: Introspection): Insert {
    var postImport = ""

    // Push constants
    ci.pushConstants.forEach { pc ->
        pc.members.forEach { member ->
            postImport += prefixed(
                "compute-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "compute-pc-binding:${member.name}",
                "var ComputeMesh.${member.name} by ComputeMesh.BoundFromStructure<$ty>()"
            ) + "\n"
            postImport += prefixed(
                "compute-pc-binding-group:${member.name}",
                "var ComputeMeshGroup.${member.name} by ComputeMeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
        }
    }

    // UBOs
    ci.ubos.forEach { ubo ->
        postImport += prefixed(
            "compute-ubo:${ubo.name}",
            "val ModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "compute-ubo-mesh:${ubo.name}",
            "var Mesh.${ubo.name} by Mesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "compute-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    // SSBOs
    ci.ssbo.forEach { ssbo ->
        postImport += prefixed(
            "compute-ssbo:${ssbo.name}",
            "val ComputeModulePointer.${ssbo.name} by ComputeMesh.FromUniform()"
        ) + "\n"
        // Arrays / headers would need special handling if you decide to expose members
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadComputeShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "compute")
    val computeStage = prepareShaderStage(box, ShaderKind.COMPUTE_SHADER, "compute") ?: return false
    assignPreparedShaderStage(box, computeStage)

    val (ci, cErr) = compileStage(box, computeStage)
    if (cErr) {
        lprintln("error,graphics", "compute shader compilation failed; skipping compute module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildComputeInsert(ci!!)
    applyInsertToPreamble(box, insert)

    val p = box._computeShader!!.current.parameters.copy().apply {
        compute = box._compute!!
        computeIntrospection = ci
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._computeShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "compute")
    return true
}

// ------------------------------
// Mesh helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildMeshInsert(ti: Introspection, mi: Introspection, fi: Introspection): Insert {
    var postImport = ""

    // Push constants across task+mesh+fragment
    (ti.pushConstants + mi.pushConstants + fi.pushConstants).forEach { pc ->
        pc.members.forEach { member ->
            postImport += prefixed(
                "mesh-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "mesh-pc-binding:${member.name}",
                "var MeshMesh.${member.name} by MeshMesh.BoundFromStructure<$ty>()"
            ) + "\n"
            postImport += prefixed(
                "mesh-pc-binding-group:${member.name}",
                "var MeshMeshGroup.${member.name} by MeshMeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
        }
    }

    // UBOs across task+mesh+fragment
    (ti.ubos + mi.ubos + fi.ubos).forEach { ubo ->
        postImport += prefixed(
            "mesh-ubo:${ubo.name}",
            "val MeshModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "mesh-ubo-mesh:${ubo.name}",
            "var MeshMesh.${ubo.name} by MeshMesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "mesh-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    // SSBOs across task+mesh+fragment
    (ti.ssbo + mi.ssbo + fi.ssbo).forEach { ssbo ->
        postImport += prefixed(
            "mesh-ssbo:${ssbo.name}",
            "val MeshModulePointer.${ssbo.name} by MeshMesh.FromUniform()"
        ) + "\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadMeshShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(box, ShaderKind.TASK_SHADER, "task") ?: return false
    val meshStage = prepareShaderStage(box, ShaderKind.MESH_SHADER, "mesh") ?: return false
    val fragmentStage = prepareShaderStage(box, ShaderKind.FRAGMENT_SHADER, "fragment") ?: return false

    assignPreparedShaderStage(box, taskStage)
    assignPreparedShaderStage(box, meshStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (ti, tErr) = compileStage(box, taskStage)
    val (mi, mErr) = compileStage(box, meshStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (tErr || mErr || fErr) {
        lprintln("error,graphics", "mesh pipeline compilation failed; skipping mesh module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildMeshInsert(ti!!, mi!!, fi!!)
    applyInsertToPreamble(box, insert)

    val p = box._meshShader!!.current.parameters.copy().apply {
        mesh = box._mesh!!
        task = box._task!!
        fragment = box._fragment!!
        taskIntrospection = ti
        meshIntrospection = mi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._meshShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    return true
}

// ------------------------------
// Graphics macro
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_moduleHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(args)
}


val defaultVertexShader = """
    #version 450
    layout (location = 0) in vec3 pos;

    layout(push_constant) uniform matrices {
        mat4 p;
        mat4 m;
    } pc;

    void main()
    {
        gl_Position = pc.p * pc.m * vec4(pos, 1);
    }
""".trimIndent()

val defaultFragmentShader = """
    #version 450
    layout(location = 0) out vec4 uFragColor;
    void main()
    {
        uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
    }
""".trimIndent()

private const val SHADERTOY_VERTEX_TEMPLATE_KEY = "shadertoy.vertexPreamble"
private const val SHADERTOY_FRAGMENT_TEMPLATE_KEY = "shadertoy.fragmentPreamble"

private val shadertoyVertexPreamble = """
    #version 450
""".trimIndent()

private val shadertoyFragmentPreamble = """
    #version 450

    layout(location = 0) out vec4 uFragColor;

    layout(set = 0, binding = 0) uniform ShadertoyUniforms {
        vec3 iResolution;
        float iTime;
        float iTimeDelta;
        float iFrameRate;
        int iFrame;
        vec4 iMouse;
        vec4 iDate;
        vec3 iChannelResolution0;
        float iChannelTime0;
        vec3 iChannelResolution1;
        float iChannelTime1;
        vec3 iChannelResolution2;
        float iChannelTime2;
        vec3 iChannelResolution3;
        float iChannelTime3;
    } st;

    layout(set = 0, binding = 1) uniform sampler2D iChannel0;
    layout(set = 0, binding = 2) uniform sampler2D iChannel1;
    layout(set = 0, binding = 3) uniform sampler2D iChannel2;
    layout(set = 0, binding = 4) uniform sampler2D iChannel3;

    #define iResolution st.iResolution
    #define iTime st.iTime
    #define iTimeDelta st.iTimeDelta
    #define iFrameRate st.iFrameRate
    #define iFrame st.iFrame
    #define iMouse st.iMouse
    #define iDate st.iDate
    #define iChannelTime float[4](st.iChannelTime0, st.iChannelTime1, st.iChannelTime2, st.iChannelTime3)
    #define iChannelResolution vec3[4](st.iChannelResolution0, st.iChannelResolution1, st.iChannelResolution2, st.iChannelResolution3)
""".trimIndent()

private fun Box.ensureShadertoyTemplates() {
    shaderTemplate.putIfAbsent(SHADERTOY_VERTEX_TEMPLATE_KEY, shadertoyVertexPreamble)
    shaderTemplate.putIfAbsent(SHADERTOY_FRAGMENT_TEMPLATE_KEY, shadertoyFragmentPreamble)
}

val shadertoyVertexShader = """
    {{ shadertoy.vertexPreamble }}

    void main() {
        vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    }
""".trimIndent()

val shadertoyFragmentShader = """
    {{ shadertoy.fragmentPreamble }}

    void mainImage(out vec4 fragColor, in vec2 fragCoord);

    void main() {
        vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
        mainImage(color, gl_FragCoord.xy);
        uFragColor = color;
    }

    void mainImage(out vec4 fragColor, in vec2 fragCoord) {
        vec2 uv = fragCoord / max(iResolution.xy, vec2(1.0));
        fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime), 1.0);
    }
""".trimIndent()

private data class ShadertoyTimeState(
    val startedAtNs: Long = System.nanoTime(),
    var lastFrameSampleNs: Long = startedAtNs,
    var lastDeltaSeconds: Float = 1f / 60f,
    var clickOrigin: Vector2Like? = null
)

private data class Vector2Like(val x: Float, val y: Float)

private object ShadertoyTextures {
    private val cache = java.util.WeakHashMap<Renderer, Map<String, LoadedTexture>>()
    private val colors = listOf(
        "iChannel0" to byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()),
        "iChannel1" to byteArrayOf(255.toByte(), 0, 0, 255.toByte()),
        "iChannel2" to byteArrayOf(0, 255.toByte(), 0, 255.toByte()),
        "iChannel3" to byteArrayOf(0, 0, 255.toByte(), 255.toByte())
    )

    fun forRenderer(renderer: Renderer): Map<String, LoadedTexture> = cache.getOrPut(renderer) {
        colors.associate { (name, rgba) ->
            val data = MemoryUtil.memAlloc(4)
            data.put(rgba)
            data.flip()
            name to LoadedTexture.fromRawRGBA(name, 1, 1, data, renderer, VK_FORMAT_R8G8B8A8_SRGB)
        }
    }
}

private fun bindShadertoyUniforms(module: ModulePointer, renderer: Renderer) {
    val uniforms = module.current.liveUniformStructures["ShadertoyUniforms"] ?: return
    val timeState = ShadertoyTimeState()
    val window = renderer.window

    fun cursorNow(): Vector2Like {
        val c = window.cursor
        return Vector2Like(c.x, c.y)
    }

    uniforms.bind("iResolution") {
        Vector3f(window.width.toFloat(), window.height.toFloat(), 1f)
    }
    uniforms.bind("iTime") {
        ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
    }
    uniforms.bind("iTimeDelta") {
        val now = System.nanoTime()
        val delta = ((now - timeState.lastFrameSampleNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
        timeState.lastFrameSampleNs = now
        timeState.lastDeltaSeconds = if (delta > 0f) delta else timeState.lastDeltaSeconds
        timeState.lastDeltaSeconds
    }
    uniforms.bind("iFrameRate") {
        (1f / timeState.lastDeltaSeconds.coerceAtLeast(1f / 1000f))
    }
    uniforms.bind("iFrame") {
        ((System.nanoTime() - timeState.startedAtNs) / 16_666_667L).toInt()
    }
    uniforms.bind("iMouse") {
        val leftDown = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        val cursor = cursorNow()
        if (leftDown && timeState.clickOrigin == null) {
            timeState.clickOrigin = cursor
        } else if (!leftDown) {
            timeState.clickOrigin = null
        }
        val origin = timeState.clickOrigin ?: cursor
        val shaderY = window.height.toFloat() - cursor.y
        val originShaderY = window.height.toFloat() - origin.y
        Vector4f(
            cursor.x,
            shaderY,
            if (leftDown) origin.x else -origin.x,
            if (leftDown) originShaderY else -originShaderY
        )
    }
    uniforms.bind("iDate") {
        val now = LocalDateTime.now()
        val seconds =
            now.hour * 3600f + now.minute * 60f + now.second.toFloat() + now.nano / 1_000_000_000f
        Vector4f(now.year.toFloat(), now.monthValue.toFloat(), now.dayOfMonth.toFloat(), seconds)
    }
    for (index in 0..3) {
        uniforms.bind("iChannelResolution$index") { Vector3f(1f, 1f, 1f) }
        uniforms.bind("iChannelTime$index") {
            ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
        }
    }
}

private fun installShadertoyMesh(module: ModulePointer, renderer: Renderer, name: String = "shadertoy"): Mesh {
    val existing = module.current.meshes[name]
    if (existing != null) return existing

    val mesh = Mesh(renderer, module)
    mesh.resize(3, 0, module)
    module.current.meshes[name] = mesh
    return mesh
}

fun Box.macro_moduleHereWithDefaultShaders(
    args: List<String>,
    vertex: String = defaultVertexShader,
    fragment: String = defaultFragmentShader,
    resolvesToCall: String = "_moduleHere"
): Insert? {
    this.actions += "moduleHere_reload" to Action("Reload Shader|alt r", "Reloads the graphics shader in this box") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadShader(selected)
                }
            }
        }
    }

    if (!hasShaderSource(this, "fragment") || !hasShaderSource(this, "vertex")) {
        this.setPropertyRaw(
            "fragment",
            shaderPropertyValue(this, "fragment")?.takeIf { it.isNotBlank() } ?: fragment
        )
        this.setPropertyRaw(
            "vertex",
            shaderPropertyValue(this, "vertex")?.takeIf { it.isNotBlank() } ?: vertex
        )
    }

    // Prepare code & introspect
    clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
    val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
    val fragmentStage = prepareShaderStage(this, ShaderKind.FRAGMENT_SHADER, "fragment")
    val vi = vertexStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (vi == null || fi == null) {
        var here = args[0]
        if (args.size == 2) here += args[1]
        return Insert(here = "$resolvesToCall$here")
    } else {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        return insertForShader(vi, fi, resolvesToCall, args)
    }
}

fun Box.macro_shadertoyHere(args: List<String>): Insert? {
    ensureShadertoyTemplates()
    return macro_moduleHereWithDefaultShaders(
        args,
        vertex = shadertoyVertexShader,
        fragment = shadertoyFragmentShader,
        resolvesToCall = "_shadertoyHere"
    )
}


// ------------------------------
// Graphics helpers for Insert
// ------------------------------
fun insertForShader(
    vi: Introspection,
    fi: Introspection,
    resolvesToCall: String = "_moduleHere",
    args: List<String>
): Insert {
    var postImport = ""
    vi.inputs.forEach {
        postImport += prefixed(
            "graphics-input:${it.name}",
            "val Mesh.${it.name} by FromInput()"
        ) + "\n"
    }

    postImport += writeSuperMap(vi.inputs) + "\n"

    (vi.pushConstants + fi.pushConstants).forEach {
        it.members.forEach { member ->
            postImport += prefixed(
                "graphics-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "graphics-pc-binding:${member.name}",
                "var Mesh.${member.name} by Mesh.BoundFromStructure<$ty>()"
            ) + "\n"

            postImport += prefixed(
                "graphics-pc-binding-group:${member.name}",
                "var MeshGroup.${member.name} by MeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
        }
    }

    (fi.ubos + vi.ubos).forEach {
        postImport += prefixed(
            "graphics-ubo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
        it.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "graphics-ubo-member:${it.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    (fi.ssbo + vi.ssbo).forEach {
        postImport += prefixed(
            "graphics-ssbo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
    }

    var here = "$resolvesToCall${args[0]}"
    if (args.size == 2) here += args[1]
    if (args.size == 3) here += args[2]

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import java.nio.*
        """.trimIndent(),
        preambleAfterImports = postImport,
        here = here
    )
}

// ------------------------------
// Expanded graphics module creation
// ------------------------------
fun Box._moduleHere(p: Parameters.() -> Unit): ModulePointer {
    return _moduleHere(parameters(renderer = fromContext(), p))
}

fun Box._moduleHere(p: Parameters = parameters {}): ModulePointer {
    if (!shaderPropertyValue(this, sharedVertexFragmentProperty).isNullOrBlank()) {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
            ?: throw IllegalStateException("Unable to prepare shared vertex shader")
        val fragmentStage = prepareShaderStage(this, ShaderKind.FRAGMENT_SHADER, "fragment")
            ?: throw IllegalStateException("Unable to prepare shared fragment shader")

        assignPreparedShaderStage(this, vertexStage)
        assignPreparedShaderStage(this, fragmentStage)

        val vertexCompile = compileStage(this, vertexStage)
        val fragmentCompile = compileStage(this, fragmentStage)
        val vi = vertexCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared vertex shader")
        val fi = fragmentCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared fragment shader")

        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
        p.vertexIntrospection = vi
        p.fragmentIntrospection = fi
    } else {
        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
    }

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    val previousResolver = p.textureResolver
    p.textureResolver = textureResolverFor(previousResolver)

    globalAsyncDebugHandler = { message ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
    }

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    attachFieldProbe(this, p)
    this._shader = Module(p).pointer()
    return this._shader!!
}

fun Box._shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    return _shadertoyHere(parameters(renderer = fromContext(), p))
}

fun Box._shadertoyHere(p: Parameters = parameters {}): ModulePointer {
    val renderer: Renderer = p.renderer
    val dummyTextures = ShadertoyTextures.forRenderer(renderer)
    val previousResolver = p.textureResolver

    p.depthTest = false
    p.textureResolver = { textureSpec ->
        dummyTextures[textureSpec.name]?.let { tex -> fromViews(listOf(tex), textureSpec.name) } ?: previousResolver(
            textureSpec
        )
    }

    val module = _moduleHere(p)
    bindShadertoyUniforms(module, renderer)
    installShadertoyMesh(module, renderer)
    return module
}

var Box.customTextureResolver by Inheritance<((Box, String) -> HasImageViewArray?)?>()

fun fromViews(
    views: List<HasImageView>,
    name: String = "",
    sampler: TextureSampler? = null
): Pair<HasImageViewArray, TextureSampler> {
    require(views.isNotEmpty()) { "No image views found for texture $name" }
    val provider = object : HasImageViewArray {
        override val imageViewArray = views
    }
    val sampler0 = sampler ?: TextureSampler(
        renderer = fromContext(),
        views.first().imageView.imageViewData.mipLevels,
        true
    )
    return provider to sampler0
}

private fun Box.textureResolverFor(previousResolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?): (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler> =
    { textureSpec ->

        Box.context.set(this) // todo, should be a 'with'

        val cached: Pair<HasImageViewArray, TextureSampler>? = previousResolver.invoke(textureSpec)
        if (cached != null) cached else {
            lprintln("debug,graphics", "searching for texture ${textureSpec.name}")
            val name = textureSpec.name

            val c = customTextureResolver
            var found : Pair<HasImageViewArray, TextureSampler>? = null

            if (c != null) {
                val r = c(this, name)
                if (r != null) {
                    val views = r.imageViewArray
                    require(views.isNotEmpty()) { "HasImageViewArray $name has no image views" }
                    val sampler = TextureSampler(
                        renderer = fromContext(),
                        views.first().imageView.imageViewData.mipLevels,
                        true
                    )
                     found = r to sampler
                }
            }

            val r = found ?:
                findWithNameOrNull<HasImageViewArray>(this, name)?.let { provider ->
                    val views = provider.imageViewArray
                    require(views.isNotEmpty()) { "HasImageViewArray $name has no image views" }
                    val sampler = TextureSampler(
                        renderer = fromContext(),
                        views.first().imageView.imageViewData.mipLevels,
                        true
                    )
                    provider to sampler
                }
                    ?: findWithNameOrNull<HasImageView>(this, name)?.let { hv -> fromViews(listOf(hv), name) }
                    ?: findWithNameOrNull<ImageView>(this, name)?.let { view -> fromViews(listOf(view), name) }
                    ?: findWithNameOrNull<Array<HasImageView>>(this, name)?.let { arr -> fromViews(arr.toList(), name) }
                    ?: findWithNameOrNull<Array<ImageView>>(
                        this,
                        name
                    )?.let { arr -> fromViews(arr.map { it as HasImageView }, name) }
                    ?: findWithNameOrNull<LoadedTexture>(this, name)?.let { tex -> fromViews(listOf(tex), name) }
                    ?: findWithNameOrNull<RenderToTexture>(this, name)?.let { rtt -> fromViews(listOf(rtt), name) }
                    ?: throw Exception(" can't find anything to build a texture out of from this box to ${textureSpec.name}")
            lprintln("debug,graphics", "found $r")
            r
        }
    }

// ------------------------------
// Compute macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_computeModuleHere(args: List<String>): Insert {

    this.actions += "computeModuleHere_reload" to Action(
        "Reload Compute Shader|alt r",
        "reloads the compute shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadComputeShader(selected)
                }
            }
        }
    }

    if (!hasShaderSource(this, "compute")) {
        this.setPropertyRaw(
            "compute", """
#version 450

layout(local_size_x = 64) in;

layout(binding = 0) buffer InputBuffer {
    uint inData[];
};

layout(binding = 1) buffer OutputBuffer {
    uint outData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    outData[idx] = inData[idx] + 1;
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "compute")
    val computeStage = prepareShaderStage(this, ShaderKind.COMPUTE_SHADER, "compute")
    val ci = computeStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ci == null) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "compute")
        val insert = buildComputeInsert(ci)
        return insert.copy(here = run {
            var here = "_computeModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

fun Box._computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    return _computeModuleHere(parameters(renderer = fromContext(), p))
}

fun Box._computeModuleHere(p: Parameters): ComputeModulePointer {
    p.compute = this._compute!!

    p.textureResolver = textureResolverFor(p.textureResolver)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = ComputeModule(p).pointer()
    this._computeShader = pp
    return pp
}

// ------------------------------
// Mesh macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_meshModuleHere(args: List<String>): Insert {

    this.actions += "meshModuleHere_reload" to Action(
        "Reload Mesh Shader|alt r",
        "reloads the mesh shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadMeshShader(selected)
                }
            }
        }
    }

    if (!hasShaderSource(this, "task")) {
        this.setPropertyRaw(
            "task", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    perTask.taskGlobalID = globalID;

    EmitMeshTasksEXT(3,1,1);
}        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "mesh")) {
        this.setPropertyRaw(
            "mesh", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout (triangles) out; 
layout (max_vertices=3, max_primitives=1) out;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    SetMeshOutputsEXT(3, 1);
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    uvec3 parentTaskGlobalID = perTask.taskGlobalID;

    float y = globalID.x/9.0;
    float x = parentTaskGlobalID.x/9.0;
    gl_MeshVerticesEXT[0].gl_Position = vec4(-x,y,0,1);
    gl_MeshVerticesEXT[1].gl_Position = vec4(-x+0.25,y,0,1);
    gl_MeshVerticesEXT[2].gl_Position = vec4(-x+0,0.5+y,0,1);
    gl_PrimitiveTriangleIndicesEXT[0] = uvec3(0,1,2);
}
        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "fragment")) {
        this.setPropertyRaw(
            "fragment", """
 #version 450
layout(location = 0) out vec4 uFragColor;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(this, ShaderKind.TASK_SHADER, "task")
    val meshStage = prepareShaderStage(this, ShaderKind.MESH_SHADER, "mesh")
    val fragmentStage = prepareShaderStage(this, ShaderKind.FRAGMENT_SHADER, "fragment")

    val ti = taskStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val mi = meshStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ti == null || mi == null || fi == null) {
        return Insert(here = "_meshModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
        val insert = buildMeshInsert(ti, mi, fi)
        return insert.copy(here = run {
            var here = "_meshModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

fun Box._meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    return _meshModuleHere(parameters(renderer = fromContext(), p))
}

fun Box._meshModuleHere(p: Parameters = parameters {}): MeshModulePointer {

    p.mesh = this._mesh!!
    p.task = this._task!!
    p.fragment = this._fragment!!

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "mesh module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = MeshModule(p).pointer()
    this._meshShader = pp
    return pp
}

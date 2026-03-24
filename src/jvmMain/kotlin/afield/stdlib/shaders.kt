package afield.stdlib

import afield.Box
import afield.Runloop
import afield.findWithNameOrNull
import afield.notify
import afield.visitParents
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.IncludeResolver
import afield.four.util.getShaderTemplateResolverForBox
import afield.four.util.runTemplate
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.preamble
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ------------------------------
// Box properties
// ------------------------------
var Box.fragment by Inheritance<String?>()
var Box.vertex by Inheritance<String?>()
var Box.task by Inheritance<String?>()
var Box.mesh by Inheritance<String?>()
var Box._fragment by Delegation<String?>()
var Box._vertex by Delegation<String?>()
var Box._task by Delegation<String?>()
var Box._mesh by Delegation<String?>()

var Box.compute by Inheritance<String?>()
var Box._compute by Delegation<String?>()

var Box._shader by Delegation<ModulePointer?>()
var Box._computeShader by Delegation<ComputeModulePointer?>()

var Box.meshShader by Inheritance<MeshModulePointer?>()
var Box._meshShader by Delegation<MeshModulePointer?>()

val Box.shaderTemplate by Delegation<MutableMap<String, Any>>(
    defaultValue = {HashMap<String, Any>()})

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


// ------------------------------
// Utilities
// ------------------------------
private val glslSourceLineRegex = Regex("""\b(?:ERROR|WARNING):\s*\d+:(\d+):""", RegexOption.IGNORE_CASE)
private val fileLineRegex = Regex(""":[ \t]*(\d+):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val anyColonLineRegex = Regex(""":(\d+):""")

private data class ShaderIncludeRef(val boxName: String, val property: String) {
    val key = "${boxName.trim()}.${property.trim()}"
}

fun numberFromErrorMessage(m: String): Int? {
    glslSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    fileLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return anyColonLineRegex.findAll(m).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.lastOrNull()
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
    "vertex" -> ShaderKind.VERTEX_SHADER
    "fragment" -> ShaderKind.FRAGMENT_SHADER
    "compute" -> ShaderKind.COMPUTE_SHADER
    "task" -> ShaderKind.TASK_SHADER
    "mesh" -> ShaderKind.MESH_SHADER
    else -> fallback
}

private fun parseShaderIncludeRef(path: String, fallbackKind: ShaderKind): ShaderIncludeRef {
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
    return ShaderIncludeRef(trimmed, defaultShaderProperty(fallbackKind))
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
        "fragment" -> box.fragment
        "compute" -> box.compute
        "task" -> box.task
        "mesh" -> box.mesh
        else -> box.getPropertyRaw(normalized) as? String
    }
}

private fun preprocess(
    inside: Box,
    source: String,
    kind: ShaderKind,
    includeStack: List<String> = emptyList()
): String {
    val includeResolver = IncludeResolver { includePath, line, _ ->
        val includeRef = parseShaderIncludeRef(includePath, kind)
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
        val expandedInclude = preprocess(inside, includeBody, nestedKind, includeStack + includeKey)
        buildString {
            append(expandedInclude)
            if (!expandedInclude.endsWith('\n')) append('\n')
            append("#line ${line + 2}\n")
        }
    }

    return runTemplate(source, getShaderTemplateResolverForBox(inside), includeResolver)
}

fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
    return preprocess(inside, code, kind, emptyList())
}

/**
 * Unified compile/introspect helper that:
 *  - runs the GLSL/spirv compilation for a stage
 *  - reports errors via box.error with a friendly stage label
 *  - tells the caller whether errors occurred
 */
private data class CompileResult(val introspection: Introspection?, val hadError: Boolean)

private fun compileStage(
    box: Box,
    source: String,
    kind: ShaderKind,
    stageLabelForUI: String // e.g. "vertex", "fragment", "compute", "task", "mesh"
): CompileResult {
    var hadError = false
    val i = Introspection(
        source,
        kind,
        errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during $stageLabelForUI shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            box.error(message, stageLabelForUI, numberFromErrorMessage(message) ?: 0)
            hadError = true
        }
    )
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
            lprintln("error,graphics", "unexpected error reloading shader in '${selected.name}' (${selected.id}): ${t.message}")
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
    box._fragment = preprocess(box, box.fragment!!, ShaderKind.FRAGMENT_SHADER)
    box._vertex = preprocess(box, box.vertex!!, ShaderKind.VERTEX_SHADER)

    val (vi, vErr) = compileStage(box, box._vertex!!, ShaderKind.VERTEX_SHADER, "vertex")
    val (fi, fErr) = compileStage(box, box._fragment!!, ShaderKind.FRAGMENT_SHADER, "fragment")

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
    }

    // todo: patch error handler if you need finer-grained feedback
    box._shader!!.reload(p, forceReresolve = true)
    clearShaderErrors(box, "vertex", "fragment")
    return true
}

// ------------------------------
// Compute helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildComputeInsert(ci: Introspection): Insert {
    var postImport = ""

    // Push constants
    ci.pushConstants.forEach { pc ->
        pc.members.forEach { member ->
            postImport += prefixed(
                "compute-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = member.type.fold({ it.kotlinTypeName }) {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            }
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
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
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
    box._compute = preprocess(box, box.compute!!, ShaderKind.COMPUTE_SHADER)

    val (ci, cErr) = compileStage(box, box._compute!!, ShaderKind.COMPUTE_SHADER, "compute")
    if (cErr) {
        lprintln("error,graphics", "compute shader compilation failed; skipping compute module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildComputeInsert(ci!!)
    applyInsertToPreamble(box, insert)

    val p = box._computeShader!!.current.parameters.copy().apply {
        compute = box._compute!!
    }
    // todo: patch error handler if you need finer-grained feedback
    box._computeShader!!.reload(p)
    clearShaderErrors(box, "compute")
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
            val ty = member.type.fold({ it.kotlinTypeName }) {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            }
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
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
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
    box._task = preprocess(box, box.task!!, ShaderKind.TASK_SHADER)
    box._mesh = preprocess(box, box.mesh!!, ShaderKind.MESH_SHADER)
    box._fragment = preprocess(box, box.fragment!!, ShaderKind.FRAGMENT_SHADER)

    val (ti, tErr) = compileStage(box, box._task!!, ShaderKind.TASK_SHADER, "task")
    val (mi, mErr) = compileStage(box, box._mesh!!, ShaderKind.MESH_SHADER, "mesh")
    val (fi, fErr) = compileStage(box, box._fragment!!, ShaderKind.FRAGMENT_SHADER, "fragment")

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
    }
    // todo: patch error handler if you need finer-grained feedback
    box._meshShader!!.reload(p)
    clearShaderErrors(box, "task", "mesh", "fragment")
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

    if ((this.fragment?.trim()?.length ?: 0) == 0 || (this.vertex?.trim()?.length ?: 0) == 0) {
        this.setPropertyRaw(
            "fragment", fragment
        )
        this.setPropertyRaw(
            "vertex", vertex
        )
    }

    // Prepare code & introspect
    this._fragment = preprocess(this, this.fragment!!, ShaderKind.FRAGMENT_SHADER)
    this._vertex = preprocess(this, this.vertex!!, ShaderKind.VERTEX_SHADER)

    var hasError = false
    val vi = Introspection(
        this._vertex!!, ShaderKind.VERTEX_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during vertex shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "vertex", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        this._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during fragment shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        var here = args[0]
        if (args.size == 2) here += args[1]
        return Insert(here = "$resolvesToCall$here")
    } else {
        clearShaderErrors(this, "vertex", "fragment")
        return insertForShader(vi, fi, resolvesToCall, args)
    }
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
            val ty = member.type.fold({ it.kotlinTypeName }, {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            })
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
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
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
    p.vertex = this._vertex!!
    p.fragment = this._fragment!!

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

    this._shader = Module(p).pointer()
    return this._shader!!
}

fun fromViews(views: List<HasImageView>, name : String ="",sampler : TextureSampler? = null): Pair<HasImageViewArray, TextureSampler> {
    require(views.isNotEmpty()) { "No image views found for texture $name" }
    val provider = object : HasImageViewArray {
        override val imageViewArray = views
    }
    val sampler0 = sampler?: TextureSampler(
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



            val r =
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
                    ?: findWithNameOrNull<Array<ImageView>>(this, name)?.let { arr -> fromViews(arr.map { it as HasImageView }, name) }
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

    this.actions += "computeModuleHere_reload" to Action("Reload Compute Shader|alt r", "reloads the compute shader in this box") {
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

    if (this.compute == null) {
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

    this._compute = preprocess(this, this.compute!!, ShaderKind.COMPUTE_SHADER)

    var hasError = false
    val ci = Introspection(
        this._compute!!, ShaderKind.COMPUTE_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during compute shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "compute", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        clearShaderErrors(this, "compute")
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

    val pp = ComputeModule(p).pointer()
    this._computeShader = pp
    return pp
}

// ------------------------------
// Mesh macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_meshModuleHere(args: List<String>): Insert {

    this.actions += "meshModuleHere_reload" to Action("Reload Mesh Shader|alt r", "reloads the mesh shader in this box") {
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

    if ((this.task?.trim()?.length ?: 0) == 0) {
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

    if ((this.mesh?.trim()?.length ?: 0) == 0) {
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

    if ((this.fragment?.trim()?.length ?: 0) == 0) {
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

    this._task = preprocess(this, this.task!!, ShaderKind.TASK_SHADER)
    this._mesh = preprocess(this, this.mesh!!, ShaderKind.MESH_SHADER)
    this._fragment = preprocess(this, this.fragment!!, ShaderKind.FRAGMENT_SHADER)

    var hasError = false
    val ti = Introspection(
        this._task!!, ShaderKind.TASK_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during task shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "task", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val mi = Introspection(
        this._mesh!!, ShaderKind.MESH_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during mesh shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "mesh", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        this._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            val throwableMsg = throwable?.message?.let { " $it" } ?: ""
            lprintln("error,graphics", "error during fragment shader compilation $message$throwableMsg")
            throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        return Insert(here = "_meshModuleHere${args[0]}")
    } else {
        clearShaderErrors(this, "task", "mesh", "fragment")
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

    val pp = MeshModule(p).pointer()
    this._meshShader = pp
    return pp
}

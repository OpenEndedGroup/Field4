package afield.stdlib

import afield.Box
import afield.Runloop
import afield.findWithNameOrNull
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.*
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
fun numberFromErrorMessage(m: String) = Regex(":(\\d+):").find(m)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Slot your real preprocessor here later; call sites are unified now.
 */
fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
    return code
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
            println("error during $stageLabelForUI shader compilation $message ${throwable.message}")
            box.error(message, stageLabelForUI, numberFromErrorMessage(message) ?: 0)
            hadError = true
        }
    )
    return CompileResult(if (hadError) null else i, hadError)
}

/**
 * Applies an Insert’s preamble(s) into the target box.
 */
fun applyInsertToPreamble(box: Box, e: Insert) {
    val b = e.target ?: box
    if (e.preamble.isNotEmpty()) {
        b.preamble = "\n" + e.preamble.trim() + "\n" + b.preamble
    }
    if (e.preambleAfterImports.isNotEmpty()) {
        b.preamble = b.preamble + "\n" + e.preambleAfterImports.trim() + "\n"
    }
}

// ------------------------------
// Graphics (vertex/fragment) reload path
// ------------------------------
fun reloadShader(box: Box) {
    box._fragment = preprocess(box, box.fragment!!, ShaderKind.FRAGMENT_SHADER)
    box._vertex = preprocess(box, box.vertex!!, ShaderKind.VERTEX_SHADER)

    val (vi, vErr) = compileStage(box, box._vertex!!, ShaderKind.VERTEX_SHADER, "vertex")
    val (fi, fErr) = compileStage(box, box._fragment!!, ShaderKind.FRAGMENT_SHADER, "fragment")

    if (vErr || fErr) {
        lprintln("ERROR: shader compilation failed; skipping module reload.")
        return
    }

    // keep consistent with the Module path: build Insert and apply to preamble
    val insert = insertForShader(vi!!, fi!!, "_moduleHere", listOf("()"))
    applyInsertToPreamble(box, insert)

    box.error(null, "fragment", -1)

    val p = box._shader!!.current.parameters.copy().apply {
        vertex = box._vertex!!
        fragment = box._fragment!!
    }

    // todo: patch error handler if you need finer-grained feedback
    box._shader!!.reload(p, forceReresolve = true)
}

// ------------------------------
// Compute helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildComputeInsert(ci: Introspection): Insert {
    var postImport = ""

    // Push constants
    ci.pushConstants.forEach { pc ->
        pc.members.forEach { member ->
            postImport += "var Structure.${member.name} by Structure.FromStructure<Any>()\n"
            val ty = member.type.fold({ it.kotlinTypeName }) {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            }
            postImport += "var ComputeMesh.${member.name} by ComputeMesh.BoundFromStructure<$ty>()\n"
        }
    }

    // UBOs
    ci.ubos.forEach { ubo ->
        postImport += "val ModulePointer.${ubo.name} by FromUniform()\n"
        postImport += "var Mesh.${ubo.name} by Mesh.FromStructure<Structure>()\n"
        ubo.members.forEach { m ->
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
            postImport += "var Structure.${m.name} by Structure.FromStructure<$ty>()\n"
        }
    }

    // SSBOs
    ci.ssbo.forEach { ssbo ->
        postImport += "val ComputeModulePointer.${ssbo.name} by ComputeMesh.FromUniform()\n"
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

fun reloadComputeShader(box: Box) {
    box._compute = preprocess(box, box.compute!!, ShaderKind.COMPUTE_SHADER)

    val (ci, cErr) = compileStage(box, box._compute!!, ShaderKind.COMPUTE_SHADER, "compute")
    if (cErr) {
        lprintln("ERROR: compute shader compilation failed; skipping compute module reload.")
        return
    }

    // Rebuild/import bindings like graphics path
    val insert = buildComputeInsert(ci!!)
    applyInsertToPreamble(box, insert)

    box.error(null, "compute", -1)

    val p = box._computeShader!!.current.parameters.copy().apply {
        compute = box._compute!!
    }
    // todo: patch error handler if you need finer-grained feedback
    box._computeShader!!.reload(p)
}

// ------------------------------
// Mesh helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildMeshInsert(ti: Introspection, mi: Introspection, fi: Introspection): Insert {
    var postImport = ""

    // Push constants across task+mesh+fragment
    (ti.pushConstants + mi.pushConstants + fi.pushConstants).forEach { pc ->
        pc.members.forEach { member ->
            postImport += "var Structure.${member.name} by Structure.FromStructure<Any>()\n"
            val ty = member.type.fold({ it.kotlinTypeName }) {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            }
            postImport += "var MeshMesh.${member.name} by MeshMesh.BoundFromStructure<$ty>()\n"
        }
    }

    // UBOs across task+mesh+fragment
    (ti.ubos + mi.ubos + fi.ubos).forEach { ubo ->
        postImport += "val MeshModulePointer.${ubo.name} by FromUniform()\n"
        postImport += "var MeshMesh.${ubo.name} by MeshMesh.FromStructure<Structure>()\n"
        ubo.members.forEach { m ->
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
            postImport += "var Structure.${m.name} by Structure.FromStructure<$ty>()\n"
        }
    }

    // SSBOs across task+mesh+fragment
    (ti.ssbo + mi.ssbo + fi.ssbo).forEach { ssbo ->
        postImport += "val MeshModulePointer.${ssbo.name} by MeshMesh.FromUniform()\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadMeshShader(box: Box) {
    box._task = preprocess(box, box.task!!, ShaderKind.TASK_SHADER)
    box._mesh = preprocess(box, box.mesh!!, ShaderKind.MESH_SHADER)
    box._fragment = preprocess(box, box.fragment!!, ShaderKind.FRAGMENT_SHADER)

    val (ti, tErr) = compileStage(box, box._task!!, ShaderKind.TASK_SHADER, "task")
    val (mi, mErr) = compileStage(box, box._mesh!!, ShaderKind.MESH_SHADER, "mesh")
    val (fi, fErr) = compileStage(box, box._fragment!!, ShaderKind.FRAGMENT_SHADER, "fragment")

    if (tErr || mErr || fErr) {
        lprintln("ERROR: mesh pipeline compilation failed; skipping mesh module reload.")
        return
    }

    // Rebuild/import bindings like graphics path
    val insert = buildMeshInsert(ti!!, mi!!, fi!!)
    applyInsertToPreamble(box, insert)

    box.error(null, "mesh", -1)

    val p = box._meshShader!!.current.parameters.copy().apply {
        mesh = box._mesh!!
        task = box._task!!
        fragment = box._fragment!!
    }
    // todo: patch error handler if you need finer-grained feedback
    box._meshShader!!.reload(p)
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
    this.actions += "moduleHere_reload" to Action("Reload Shader|alt r") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                val here = this@macro_moduleHereWithDefaultShaders
                it.forEach { reloadShader(it) }
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
            println("error during vertex shader compilation $message ${throwable.message}")
            this.error(message, "vertex", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        this._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            println("error during fragment shader compilation $message ${throwable.message}")
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        var here = args[0]
        if (args.size == 2) here += args[1]
        return Insert(here = "$resolvesToCall$here")
    } else {
        this.error(null, "fragment", -1)
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
        postImport += "val Mesh.${it.name} by FromInput()\n"
    }

    postImport += writeSuperMap(vi.inputs) + "\n"

    (vi.pushConstants + fi.pushConstants).forEach {
        it.members.forEach { member ->
            postImport += "var Structure.${member.name} by Structure.FromStructure<Any>()\n"
            val ty = member.type.fold({ it.kotlinTypeName }, {
                if (member.physicalPointer) "afield.four.buffers.HasBuffer" else "Any"
            })
            postImport += "var Mesh.${member.name} by Mesh.BoundFromStructure<$ty>()\n"
        }
    }

    (fi.ubos + vi.ubos).forEach {
        postImport += "val ModulePointer.${it.name} by FromUniform()\n"
        it.members.forEach { m ->
            val ty = m.type.fold({ it.kotlinTypeName }, { "Any" })
            postImport += "var Structure.${m.name} by Structure.FromStructure<$ty>()\n"
        }
    }

    (fi.ssbo + vi.ssbo).forEach {
        postImport += "val ModulePointer.${it.name} by FromUniform()\n"
    }

    var here = "$resolvesToCall${args[0]}"
    if (args.size == 2) here += args[1]

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
    p.textureResolver = {
        val r: Pair<Array<ImageView>, TextureSampler>? = previousResolver.invoke(it)
        if (r != null) r else {
            println(" searching for texture ${it.name}")
            val name = it.name
            val r =
                findWithNameOrNull<ImageView>(this@_moduleHere, name)?.let {
                    arrayOf(it) to TextureSampler(renderer = fromContext(), it.imageViewData.mipLevels, true)
                } ?: findWithNameOrNull<Array<ImageView>>(this@_moduleHere, name)?.let {
                    it to TextureSampler(renderer = fromContext(), it[0].imageViewData.mipLevels, true)
                } ?: findWithNameOrNull<LoadedTexture>(this@_moduleHere, name)?.let {
                    arrayOf(it.imageView) to TextureSampler(
                        renderer = fromContext(),
                        it.imageView.imageViewData.mipLevels,
                        true
                    )
                } ?: findWithNameOrNull<RenderToTexture>(this@_moduleHere, name)?.let {
                    arrayOf(it.imageView) to TextureSampler(
                        renderer = fromContext(),
                        it.imageView.imageViewData.mipLevels,
                        true
                    )
                } ?: findWithNameOrNull<HasImageView>(this@_moduleHere, name)?.let {
                    arrayOf(it.imageView) to TextureSampler(
                        renderer = fromContext(),
                        it.imageView.imageViewData.mipLevels,
                        true
                    )
                } ?: findWithNameOrNull<HasImageViewArray>(this@_moduleHere, name)?.let {
                    it.imageViewArray.toTypedArray() to TextureSampler(
                        renderer = fromContext(),
                        it.imageViewArray[0].imageViewData.mipLevels,
                        true
                    )
                } ?: throw Exception(" can't find anything to build a texture out of from this box to ${it.name}")
            println(" found $r")
            r
        }
    }

    globalAsyncDebugHandler = { message ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
    }

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.printStackTrace()
    }

    this._shader = Module(p).pointer()
    return this._shader!!
}

// ------------------------------
// Compute macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_computeModuleHere(args: List<String>): Insert {

    this.actions += "computeModuleHere_reload" to Action("Reload Compute Shader|alt r") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                it.forEach { reloadComputeShader(it) }
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
            println("error during compute shader compilation $message ${throwable.message}")
            this.error(message, "compute", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        this.error(null, "fragment", -1) // preserves previous behavior
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

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.printStackTrace()
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

    this.actions += "meshModuleHere_reload" to Action("Reload Mesh Shader|alt r") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                it.forEach { reloadMeshShader(it) }
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
            println("error during task shader compilation $message ${throwable.message}")
            this.error(message, "task", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val mi = Introspection(
        this._mesh!!, ShaderKind.MESH_SHADER, errorHandler = { message, throwable ->
            println("error during mesh shader compilation $message ${throwable.message}")
            this.error(message, "mesh", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        this._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            println("error during fragment shader compilation $message ${throwable.message}")
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        return Insert(here = "_meshModuleHere${args[0]}")
    } else {
        this.error(null, "fragment", -1)
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
        throwable?.printStackTrace()
    }

    if (p.name == "unnamed module") p.name = "mesh module in box '${name}' (${id})"

    val pp = MeshModule(p).pointer()
    this._meshShader = pp
    return pp
}

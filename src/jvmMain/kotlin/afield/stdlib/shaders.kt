package afield.stdlib

import afield.Box
import afield.*
import afield.findWithNameOrNull
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.ImageView
import afield.four.texture.LoadedTexture
import afield.four.texture.TextureSampler
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

var Box.fragment by Inheritance<String?>()
var Box.vertex by Inheritance<String?>()
var Box._fragment by Delegation<String?>()
var Box._vertex by Delegation<String?>()

var Box.compute by Inheritance<String?>()
var Box._compute by Delegation<String?>()

var Box._shader by Delegation<ModulePointer?>()
var Box._computeShader by Delegation<ComputeModulePointer?>()

fun Box.moduleHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.moduleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun numberFromErrorMessage(m: String) = Regex(":(\\d+):").find(m)?.groupValues?.get(1)?.toIntOrNull()

fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
//    try {
//        val v = "\"\"\"${code}\"\"\""
//        lblock("preprocessing shader $kind") {
//            lprintln("started with $v")
//            val q = inside.ki.execAndWait(v, implicits = listOf(inside))
//            val o = (q as ResultValue.Value).value as String
//            lprintln("got $o")
//            return o
//        }
//
//    } catch (e: Exception) {
//        lprintln("ERROR: preprocessor failed for '$code' in $kind")
//        inside.error("Pre-processing $kind failed, message is `${e.message}`", "code", 0)
//        e.printStackTrace()
//        return code
//    }
    return code
}

fun reloadShader(box: Box) {
    box._fragment = preprocess(box, box.fragment!!, ShaderKind.FRAGMENT_SHADER)
    box._vertex = preprocess(box, box.vertex!!, ShaderKind.VERTEX_SHADER)

    var hasError = false
    val vi = Introspection(
        box._vertex!!, ShaderKind.VERTEX_SHADER, errorHandler = { message, throwable ->
            println("error during vertex shader compilation ${message} ${throwable.message}")
            // todo: parse errors
            box.error(message, "vertex", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        box._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            println("error during fragment shader compilation ${message} ${throwable.message}")
            box.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        // it has been reported above
        lprintln("ERROR, we have errors in our shader that stop them compiling, will not bother reloading ")
    } else {
        box.error(null, "fragment", -1)

        val insert = insertForShader(vi, fi, listOf("()"))

        applyInsertToPreamble(box, insert)

        // todo, apply insert, revaluate preamble. We actually don't need to do this immediately though.

        val p = box._shader!!.current.parameters.copy()

        p.vertex = box._vertex!!
        p.fragment = box._fragment!!

        // todo, patch error handler
        box._shader!!.reload(p, forceReresolve = true)
    }
}

fun applyInsertToPreamble(box: Box, e: Insert) {
    val b = e.target ?: box
    if (e.preamble.length > 0) {
        b.preamble = "\n" + e.preamble.trim() + "\n" + b.preamble
    }
    if (e.preambleAfterImports.length > 0) {
        b.preamble =
            b.preamble + "\n" + e.preambleAfterImports.trim() + "\n"
    }
}

fun reloadComputeShader(box: Box) {
    box._compute = preprocess(box, box.compute!!, ShaderKind.COMPUTE_SHADER)

    var hasError = false
    val ci = Introspection(
        box._compute!!, ShaderKind.COMPUTE_SHADER, errorHandler = { message, throwable ->
            println("error during compute shader compilation ${message} ${throwable.message}")
            box.error(message, "compute", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        // it has been reported above
        lprintln("ERROR, we have errors in our compute shader that stop them compiling, will not bother reloading ")
    } else {
        box.error(null, "compute", -1)

        var postImport = ""
        (ci.pushConstants).forEach {
            it.members.forEach {
                postImport += "var Structure.${it.name} by Structure.FromStructure<Any>()\n" // nominate a type?
                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
                postImport += "var ComputeMesh.${it.name} by ComputeMesh.FromStructure<$ty>()\n" // nominate a type?
            }
        }

        (ci.ubos).forEach {
            postImport += "val ComputeModulePointer.${it.name} by ComputeMesh.FromUniform()\n"
            postImport += "var ComputeMesh.${it.name} by ComputeMesh.FromStructure<Structure>()\n"
            it.members.forEach {
                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
                postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
            }
        }

        (ci.ssbo).forEach {
            postImport += "val ComputeModulePointer.${it.name} by ComputeMesh.FromUniform()\n"
            // need to handle the array differently. Note that there can be a header right now...
//            it.members.forEach {
//                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
//                postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
//            }
        }


        val insert = Insert(
            preambleAfterImports = postImport,
        )

        // todo, apply insert, revaluate preamble. We actually don't need to do this immediately though.

        val p = box._computeShader!!.current.parameters.copy()

        p.compute = box._compute!!

        // todo, patch error handler
        box._computeShader!!.reload(p)
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_moduleHere(args: List<String>): Insert? {

    this.actions += "moduleHere_reload" to Action("Reload Shader|alt r") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                val here = this@macro_moduleHere
                it.forEach {
                    reloadShader(it)
                }
            }
        }
    }

    // could run texture resolver through properties? perhaps properties plus a prefix?
    // todo: error reporting. We're ready for this, right?
    // todo: remote command — "reload". We're almost ready for that as well, right?
    // send red square _and_ an error message _and_ a property name to the editor
    if (this.fragment == null || this.vertex == null) {
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
        this.setPropertyRaw(
            "vertex", """
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
        )
    }

    // trouble -- can't expand variables as templates here, because we haven't actually run the code!
    // solution -- //%- breaks
    this._fragment = preprocess(this, this.fragment!!, ShaderKind.FRAGMENT_SHADER)
    this._vertex = preprocess(this, this.vertex!!, ShaderKind.VERTEX_SHADER)

    var hasError = false
    val vi = Introspection(
        this._vertex!!, ShaderKind.VERTEX_SHADER, errorHandler = { message, throwable ->
            println("error during vertex shader compilation ${message} ${throwable.message}")
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })
    val fi = Introspection(
        this._fragment!!, ShaderKind.FRAGMENT_SHADER, errorHandler = { message, throwable ->
            println("error during fragment shader compilation ${message} ${throwable.message}")
            this.error(message, "fragment", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {

        var here = args[0]
        if (args.size == 2) {
            here += args[1]
        }
        return Insert(here = "_moduleHere$here")
    } else {
        this.error(null, "fragment", -1)
        return insertForShader(vi, fi, args)
    }
}

private fun insertForShader(
    vi: Introspection,
    fi: Introspection,
    args: List<String>
): Insert {
    var postImport = ""
    vi.inputs.forEach {
        postImport += "val Mesh.${it.name} by FromInput()\n"
    }

    postImport += writeSuperMap(vi.inputs) + "\n"

    (vi.pushConstants + fi.pushConstants).forEach {
        it.members.forEach { member ->
            postImport += "var Structure.${member.name} by Structure.FromStructure<Any>()\n" // nominate a type?
            val ty = member.type.fold({ it.kotlinTypeName }, {
                if (member.physicalPointer) {
                    "afield.four.buffers.HasBuffer"
                } else {
                    "Any"
                }
            })
            postImport += "var Mesh.${member.name} by Mesh.BoundFromStructure<$ty>()\n" // nominate a type?
        }
    }

    (fi.ubos + vi.ubos).forEach {
        postImport += "val ModulePointer.${it.name} by FromUniform()\n"
//            postImport += "var Mesh.${it.name} by Mesh.FromStructure<Structure>()\n"
        it.members.forEach {
            val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
            postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
        }
    }

    (fi.ssbo + vi.ssbo).forEach {
        postImport += "val ModulePointer.${it.name} by FromUniform()\n"
        // need to handle the array differently. Note that there can be a header right now...
//            it.members.forEach {
//                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
//                postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
//            }
    }

    var here = "_moduleHere${args[0]}"
    if (args.size == 2) {
        here += args[1]
    }
    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import java.nio.*
        """.trimIndent(), preambleAfterImports = postImport, here = here
    )
}

fun Box._moduleHere(p: Parameters.() -> Unit): ModulePointer {
    return _moduleHere(parameters(renderer = fromContext(), p))
}

fun Box._moduleHere(p: Parameters = parameters {}): ModulePointer {

    p.vertex = this._vertex!!
    p.fragment = this._fragment!!

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    // todo, how to specify sampler
    p.textureResolver = {
        println(" searching for texture ${it.name}")
        val name = it.name
        var r = findWithNameOrNull<ImageView>(this@_moduleHere, name)?.let {
            arrayOf(it) to TextureSampler(renderer = fromContext(), 4, true)
        } ?: findWithNameOrNull<Array<ImageView>>(this@_moduleHere, name)?.let {
            it to TextureSampler(renderer = fromContext(), 4, true)
        } ?: findWithNameOrNull<LoadedTexture>(this@_moduleHere, name)?.let {
            arrayOf(it.imageView) to TextureSampler(renderer = fromContext(), 4, true)
        } ?: findWithNameOrNull<RenderToTexture>(this@_moduleHere, name)?.let {
            arrayOf(it.imageView) to TextureSampler(renderer = fromContext(), 4, true)
        } ?: findWithNameOrNull<HasImageView>(this@_moduleHere, name)?.let {
            arrayOf(it.imageView) to TextureSampler(renderer = fromContext(), 1, true)
        } ?: findWithNameOrNull<HasImageViewArray>(this@_moduleHere, name)?.let {
            it.imageViewArray to TextureSampler(renderer = fromContext(), 1, true)
        } ?: throw Exception(" can't find anything to build a texture out of from this box to ${it.name}")
        println(" found ${r}")
        r
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


@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_computeModuleHere(args: List<String>): Insert {

    // TODO: tie this to current edited box (so we don't have two reloads, one for compute and one for graphics
    this.actions += "computeModuleHere_reload" to Action("Reload Compute Shader|alt r") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                it.forEach {
                    reloadComputeShader(it)
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

    // wonder if we need a compute version of a Mesh to handle pushconstants, uniforms and launch parameters??
    this._compute = preprocess(this, this.compute!!, ShaderKind.COMPUTE_SHADER)

    var hasError = false
    val ci = Introspection(
        this._compute!!, ShaderKind.COMPUTE_SHADER, errorHandler = { message, throwable ->
            println("error during compute shader compilation ${message} ${throwable.message}")
            this.error(message, "compute", numberFromErrorMessage(message) ?: 0)
            hasError = true
        })

    if (hasError) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        this.error(null, "fragment", -1)

        var postImport = ""

        (ci.pushConstants).forEach {
            it.members.forEach { member ->
//                postImport += "var Structure.${it.name} by Structure.FromStructure<Any>()\n" // nominate a type?
//                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
//                postImport += "var ComputeMesh.${it.name} by ComputeMesh.FromStructure<$ty>()\n" // nominate a type?

                postImport += "var Structure.${member.name} by Structure.FromStructure<Any>()\n" // nominate a type?
                val ty = member.type.fold({ it.kotlinTypeName }, {
                    if (member.physicalPointer) {
                        "afield.four.buffers.HasBuffer"
                    } else {
                        "Any"
                    }
                })
                postImport += "var ComputeMesh.${member.name} by ComputeMesh.BoundFromStructure<$ty>()\n" // nominate a type?
            }
        }

        (ci.ubos).forEach {
            postImport += "val ModulePointer.${it.name} by FromUniform()\n"
            postImport += "var Mesh.${it.name} by Mesh.FromStructure<Structure>()\n"
            it.members.forEach {
                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
                postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
            }
        }

        (ci.ssbo).forEach {
            postImport += "val ComputeModulePointer.${it.name} by ComputeMesh.FromUniform()\n"
            // need to handle the array differently. Note that there can be a header right now...
//            it.members.forEach {
//                val ty = it.type.fold({ it.kotlinTypeName }, { "Any" })
//                postImport += "var Structure.${it.name} by Structure.FromStructure<$ty>()\n" // nominate a type?
//            }
        }

        var here = "_computeModuleHere${args[0]}"
        if (args.size == 2) {
            here += args[1]
        }


        return Insert(
            preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
        """.trimIndent(), preambleAfterImports = postImport, here = here
        )
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

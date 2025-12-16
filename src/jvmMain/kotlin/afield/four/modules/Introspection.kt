package afield.four.modules

import afield.four.util.Options
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.json.JSONObject
import org.lwjgl.vulkan.VK10
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardOpenOption

fun runCommand(command: List<String>, dir: String = "."): String {
    val processBuilder = ProcessBuilder(command)
    processBuilder.redirectErrorStream(true) // Redirects error stream to stdout

    val process = processBuilder.start()
    val output = StringBuilder()

    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
    }

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        println("Command exited with error code $exitCode")
    }

    return output.toString()
}

enum class ImageType(val vulkan_format: Int) {
    rgba16f(VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
}


enum class PrimitiveType(
    val components: Int,
    val componentsSizeInBytes: Int,
    val bufferFormat: Int,
    val kotlinTypeName: String,
    val kotlinDefault: String,
    val writeXToBuffer: String
) {
    float(1, 4, VK10.VK_FORMAT_R32_SFLOAT, "Float", "0f", "buffer.putFloat(x)"), vec2(
        2,
        4,
        VK10.VK_FORMAT_R32G32_SFLOAT,
        "org.joml.Vector2f",
        "vec(0,0)",
        "x.get(buffer); buffer.position(buffer.position()+8)"
    ),
    vec3(
        3,
        4,
        VK10.VK_FORMAT_R32G32B32_SFLOAT,
        "org.joml.Vector3f",
        "vec(0,0,0)",
        "x.get(buffer); buffer.position(buffer.position()+12)"
    ),
    vec4(
        4,
        4,
        VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
        "org.joml.Vector4f",
        "vec(0,0,0,1)",
        "x.get(buffer); buffer.position(buffer.position()+16)"
    ),
    int(
        1, 4, VK10.VK_FORMAT_R32_SINT, "Int", "0", "buffer.putInt(x); buffer.position(buffer.position()+4)"
    ),
    uint(
        1, 4, VK10.VK_FORMAT_R32_UINT, "UInt", "0", "buffer.putInt(x)"
    ),
    mat4(
        16,
        4,
        -1,
        "org.joml.Matrix4f",
        "mat(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)",
        "x.get(buffer); buffer.position(buffer.position()+16*4)"
    ),
    mat3(
        9, 4, -1, "org.joml.Matrix3f", "mat(0,0,0,0,0,0,0,0,0)", "x.get(buffer); buffer.position(buffer.position()+9*4)"
    ),
    mat2(4, 4, -1, "org.joml.Matrix2f", "mat(0,0,0,0)", "x.get(buffer); buffer.position(buffer.position()+2*4)"),

    float16_t(
        1, 2, VK10.VK_FORMAT_R16_SFLOAT,
        "Float", "0f",
        "buffer.putf16(x)"
    ),
    f16vec2(
        2, 2, VK10.VK_FORMAT_R16G16_SFLOAT,
        "org.joml.Vector2f", "vec(0,0)",
        "buffer.putf16(x.x); buffer.putf16(x.y)"
    ),
    f16vec3(
        3, 2, VK10.VK_FORMAT_R16G16B16_SFLOAT,
        "org.joml.Vector3f", "vec(0,0,0)",
        "buffer.putf16(x.x); buffer.putf16(x.y); buffer.putf16(x.z)"
    ),
    f16vec4(
        4, 2, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
        "org.joml.Vector4f", "vec(0,0,0,1)",
        "buffer.putf16(x.x); buffer.putf16(x.y); buffer.putf16(x.z); buffer.putf16(x.w)"
    );


    val sizeInBytes: Long
        get() = (componentsSizeInBytes * components).toLong()
}

enum class TextureType(val dimensions: Int) {
    texture2D(2),
    texture2DArray(3)
}

open class Introspected {

    open class Bindable(val name: String, val set: Int, val binding: Int)
    data class Input(
        val name: String, val type: Either<PrimitiveType, List<Member>>, val location: Int
    ) {
        val sizeInBytes: Long
            get() = type.fold({ it.sizeInBytes }, { it.sumOf { it.memberSize() }.toLong() })
    }

    data class Member(
        val name: String,
        val offset: Int,
        val type: Either<PrimitiveType, List<Member>>,
        val arrayStride: Int = 0, // 0 means not an array
        var physicalPointer: Boolean = false
    ) {
        fun memberSize(): Long =
            type.fold({ it.componentsSizeInBytes * it.components.toLong() }, { it.sumOf { it.memberSize() } })
    }

    class UBO(name: String, val size: Int, set: Int, binding: Int, val members: List<Member>) :
        Bindable(name, set, binding) {
        override fun toString(): String {
            return "UBO(name=$name, set=$set, binding=$binding, size=$size, members=$members)"
        }

        fun memberSize(): Long = members.sumOf { it.memberSize() }
    }

    class Texture(
        name: String,
        val type: String,
        set: Int,
        binding: Int,
        val arrayCount: Int = 0 /*0 means not an array*/
    ) : Bindable(name, set, binding)

    class Image(name: String, val type: String, set: Int, binding: Int) : Bindable(name, set, binding)

    data class Sampler(val name: String, val type: String, val set: Int, val location: Int)
    data class PushConstant(val name: String, val size: Int, val members: List<Member>)

    val inputs = mutableListOf<Input>()
    val outputs = mutableListOf<Input>()
    val ubos = mutableListOf<UBO>()
    val ssbo = mutableListOf<UBO>()
    val textures = mutableListOf<Texture>()
    val images = mutableListOf<Image>()
    val samplers = mutableListOf<Sampler>()
    val pushConstants = mutableListOf<PushConstant>()

    lateinit var code: ShaderSPIRVUtils.SPIRV
    override fun toString(): String {
        return "Introspected(\n\tinputs=$inputs, \n\toutputs=$outputs, \n\tubos=$ubos, \n\tssbo=$ssbo, \n\ttextures=$textures, \n\timages=$images, \n" + "\tsamplers=$samplers, \n" + "\tpushConstants=$pushConstants)"
    }
}

class Introspection(
    val source: String,
    val kind: ShaderKind,
    val errorHandler: (String, Throwable) -> Unit = { desc, throwable -> throw throwable }
) : Introspected() {

    var valid = false

    init {
        try {
            val spirv = ShaderSPIRVUtils.compileShader(kind.toString() + ".glsl", source, kind)
            val data = spirv.bytecode()!!

            code = spirv

            val spv = File.createTempFile("afield", "spv")
            spv.deleteOnExit()

            Files.newByteChannel(spv.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                while (data.hasRemaining()) {
                    channel.write(data)
                }
            }

            if (!File(Options.spirv_cross_path).exists()) {
                println("ERROR: -- cannot find pre-built spirv-cross at ${Options.spirv_cross_path} -- ")
            }
            val check = runCommand(
                listOf(
                    Options.spirv_cross_path, spv.path, "--vulkan-semantics"
                )
            )
            println(check)
            val json = runCommand(listOf(Options.spirv_cross_path, spv.path, "--reflect"))
            val j = JSONObject(json)

            println(j)

            val types = mutableMapOf<String, MutableList<Member>>()
            if (j.has("types")) {

                val ti = j.getJSONObject("types")
                for (e in ti.keySet()) {
                    val to = ti.getJSONObject(e)
                    val name = to.getString("name")
                    types.put(e, mutableListOf())
                }

                for (e in ti.keySet()) {
                    val to = ti.getJSONObject(e)
                    val name = to.getString("name")
                    types.get(e)!!.addAll(to.getJSONArray("members").mapNotNull { m ->
                        m as JSONObject
                        println(m)
                        if (!m.has("offset")) return@mapNotNull null // must be a builtin or something?
                        val offset = m.getInt("offset")
                        val n2 = m.getString("name")
                        try {
                            val type = PrimitiveType.valueOf(m.getString("type").lowercase())
                            Member(
                                n2,
                                offset,
                                type.left(),
                                arrayStride = if (m.has("array_stride")) m.getInt("array_stride") else 0
                            )
                        } catch (e: java.lang.IllegalArgumentException) {
                            val physical_pointer =
                                if (m.has("physical_pointer")) m.getBoolean("physical_pointer") else false
                            Member(
                                n2, offset, types.get(m.getString("type"))!!.right(), 0, physical_pointer
                            )
                        }
                    })
                }
            }

            if (j.has("inputs")) {

                val ai = j.getJSONArray("inputs")
                for (n in 0 until ai.length()) {
                    val ao = ai.getJSONObject(n)
                    print(ao)
                    try {
                        inputs.add(
                            Input(
                                ao.getString("name"),
                                PrimitiveType.valueOf(ao.getString("type")).left(),
                                ao.getInt("location")
                            )
                        )
                    } catch (e: java.lang.IllegalArgumentException) {
                        inputs.add(
                            Input(
                                ao.getString("name"), types.get(ao.getString("type"))!!.right(), ao.getInt("location")
                            )
                        )
                    }
                }
            }


            if (j.has("ubos")) {
                println(" types are ${types}")
                val ui = j.getJSONArray("ubos")
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val ty = types[uo.getString("type")]!!
                    val blockSize = uo.getInt("block_size")
                    val binding = uo.getInt("binding")
                    val set = uo.getInt("set")
                    print("UO ${uo}")
                    ubos.add(UBO(name, blockSize, set, binding, ty))
                }
            }
            if (j.has("push_constants")) {
                println(" types for pc are ${types}")
                val ui = j.getJSONArray("push_constants")
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val ty = types[uo.getString("type")]!!

                    val size = if (ty.isEmpty()) 0 else {
                        ty.maxOf { m ->
                            m.offset + m.type.fold(
                                { it.sizeInBytes.toInt() },
                                { it.sumOf { sub -> sub.memberSize() }.toInt() }
                            )
                        }
                    }

                    fun Int.nearest16() = (this + 15) and -16

                    pushConstants.add(PushConstant(name, size.toInt().nearest16(), ty))
                }
            }


            if (j.has("ssbos")) {
                val ui = j.getJSONArray("ssbos")
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val ty = types[uo.getString("type")]!!
                    val blockSize =
                        uo.getInt("block_size") // this block size contains 0 of the flexible array element at the end, just the header
                    val location = uo.getInt("binding")
                    val set = uo.getInt("set")
                    ssbo.add(UBO(name, blockSize, set, location, ty))
                }
            }


            if (j.has("textures")) {
                val ui = j.getJSONArray("textures")
                for (i in 0 until ui.length()) {


                    val uo = ui.getJSONObject(i)
                    // only do 1d arrays of textures
                    if (uo.has("array_size_is_literal") && uo.getJSONArray("array_size_is_literal")
                            .getBoolean(0) && uo.getJSONArray("array_size_is_literal").length() == 1
                    ) {
                        val name = uo.getString("name")
                        val type = uo.getString("type")
                        val binding = uo.getInt("binding")
                        val set = uo.getInt("set")
                        textures.add(Texture(name, type, set, binding, uo.getJSONArray("array").getInt(0)))
                    } else {
                        val name = uo.getString("name")
                        val type = uo.getString("type")
                        val binding = uo.getInt("binding")
                        val set = uo.getInt("set")
                        textures.add(Texture(name, type, set, binding))
                    }
                }
            }


            if (j.has("images")) {
                val ui = j.getJSONArray("images")
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val type = uo.getString("format")
                    val location = uo.getInt("binding")
                    val set = uo.getInt("set")
                    images.add(Image(name, type, set, location))
                }
            }

            if (j.has("separate_samplers")) {
                val ui = j.getJSONArray("separate_samplers")
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val type = uo.getString("type")
                    val location = uo.getInt("binding")
                    val set = uo.getInt("set")
                    samplers.add(Sampler(name, type, set, location))
                }
            }

            if (j.has("outputs")) {
                val ui = j.getJSONArray("outputs")
                println(ui)
                for (i in 0 until ui.length()) {
                    val uo = ui.getJSONObject(i)
                    val name = uo.getString("name")
                    val type = uo.getString("type")
                    val location = uo.getInt("location")

                    try {
                        outputs.add(
                            Input(
                                name, PrimitiveType.valueOf(type).left(), location
                            )
                        )
                    } catch (e: java.lang.IllegalArgumentException) {
                        outputs.add(
                            Input(
                                name, types.get(type)!!.right(), location
                            )
                        )
                    }
                }
            }
            // Enforce contiguous descriptor sets starting at 0 so pipeline layouts can be built deterministically.
            run {
                val sets = mutableSetOf<Int>()
                sets += ubos.map { it.set }
                sets += ssbo.map { it.set }
                sets += textures.map { it.set }
                sets += images.map { it.set }
                sets += samplers.map { it.set }
                if (sets.isNotEmpty()) {
                    val minSet = sets.minOrNull()!!
                    val maxSet = sets.maxOrNull()!!
                    require(minSet == 0 && sets.size == maxSet + 1) {
                        "Descriptor sets must be contiguous starting at set=0 (found sets=${sets.sorted()})"
                    }
                }
            }

            valid = true
        } catch (e: CompilerError) {
            errorHandler(e.message, e)
        } catch (e: Exception) {
            errorHandler("while introspecting shader $kind", e)
        }

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val shader = """
                #version 450
                #extension GL_ARB_separate_shader_objects : enable

                layout(location = 0) in vec2 inPosition;
                layout(location = 1) in vec3 inColor;

                layout(location = 0) out vec3 fragColor;

                void main() {
                    gl_Position = vec4(inPosition, 0.0, 1.0);
                    fragColor = inColor;
                }
            """.trimIndent()

            Introspection(shader, ShaderKind.VERTEX_SHADER)
        }
    }
}

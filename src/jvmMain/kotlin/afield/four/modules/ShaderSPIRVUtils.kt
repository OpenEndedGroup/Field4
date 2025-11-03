package afield.four.modules

import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.NativeResource
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.vulkan.VK10
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths

enum class ShaderKind(internal val kind: Int) {
    VERTEX_SHADER(Shaderc.shaderc_glsl_vertex_shader), GEOMETRY_SHADER(Shaderc.shaderc_glsl_geometry_shader), FRAGMENT_SHADER(
        Shaderc.shaderc_glsl_fragment_shader
    ),
    COMPUTE_SHADER(Shaderc.shaderc_glsl_compute_shader);

    fun toVulkan(): Int {
        return when(kind) {
            Shaderc.shaderc_glsl_vertex_shader -> VK10.VK_SHADER_STAGE_VERTEX_BIT
            Shaderc.shaderc_glsl_fragment_shader -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT
            Shaderc.shaderc_glsl_geometry_shader -> VK10.VK_SHADER_STAGE_GEOMETRY_BIT
            Shaderc.shaderc_glsl_compute_shader -> VK10.VK_SHADER_STAGE_COMPUTE_BIT
            else -> throw RuntimeException(" unknown shader type $kind")
        }
    }
}

class CompilerError(override val message : String) : Exception(message) {

}

object ShaderSPIRVUtils {
    fun compileShaderFile(shaderFile: String?, shaderKind: ShaderKind): SPIRV? {
        return compileShaderAbsoluteFile(
            ClassLoader.getSystemClassLoader().getResource(shaderFile).toExternalForm(), shaderKind
        )
    }

    fun compileShaderAbsoluteFile(shaderFile: String, shaderKind: ShaderKind): SPIRV? {
        try {
            val source = String(Files.readAllBytes(Paths.get(URI(shaderFile))))
            return compileShader(shaderFile, source, shaderKind)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        return null
    }

    fun compileShader(filename: String, source: String?, shaderKind: ShaderKind): SPIRV {
        val compiler = Shaderc.shaderc_compiler_initialize()

        if (compiler == MemoryUtil.NULL) {
            throw RuntimeException("Failed to create shader compiler")
        }

        val opts = Shaderc.shaderc_compile_options_initialize()
//        Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_webgpu, Shaderc.shaderc_env_version_webgpu)
        Shaderc.shaderc_compile_options_set_target_env(
            opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3
        )
        Shaderc.shaderc_compile_options_set_target_spirv(
            opts, Shaderc.shaderc_spirv_version_1_5
        )



        val result = Shaderc.shaderc_compile_into_spv(compiler, source ?: File(filename).readText(), shaderKind.kind, filename, "main", opts)

        if (result == MemoryUtil.NULL) {
            throw CompilerError("Failed to compile shader $filename into SPIR-V")
        }

        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            println("""WARNING: Failed to compile shader ${filename} into SPIR-V: error is""")
            println(Shaderc.shaderc_result_get_error_message(result))
            throw CompilerError(Shaderc.shaderc_result_get_error_message(result)?:"")
        }

        Shaderc.shaderc_compiler_release(compiler)

        val res = SPIRV(result, Shaderc.shaderc_result_get_bytes(result))
        println(" compiled, got results ${res} ${res.bytecode()}")
        return res
    }

    class SPIRV(private val handle: Long, private var bytecode: ByteBuffer?) : NativeResource {
        fun bytecode(): ByteBuffer? {
            bytecode?.position(0)
            return bytecode
        }

        override fun free() {
            Shaderc.shaderc_result_release(handle)
            bytecode = null // Help the GC
        }
    }
}
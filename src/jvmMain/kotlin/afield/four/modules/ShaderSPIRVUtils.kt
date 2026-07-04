package afield.four.modules

import afield.four.util.Options
import afield.logger.lprintln
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.NativeResource
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.shaderc.ShadercIncludeResolve
import org.lwjgl.util.shaderc.ShadercIncludeResult
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease
import org.lwjgl.vulkan.KHRRayTracingPipeline
import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT
import org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
import org.lwjgl.vulkan.VK10
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

enum class ShaderKind(internal val kind: Int) {
    VERTEX_SHADER(Shaderc.shaderc_glsl_vertex_shader), GEOMETRY_SHADER(Shaderc.shaderc_glsl_geometry_shader), FRAGMENT_SHADER(
        Shaderc.shaderc_glsl_fragment_shader
    ),
    COMPUTE_SHADER(Shaderc.shaderc_glsl_compute_shader),

    MESH_SHADER(Shaderc.shaderc_glsl_mesh_shader),
    TASK_SHADER(Shaderc.shaderc_glsl_task_shader),

    RAYGEN_SHADER(Shaderc.shaderc_glsl_raygen_shader),
    ANY_HIT_SHADER(Shaderc.shaderc_glsl_anyhit_shader),
    CLOSEST_HIT_SHADER(Shaderc.shaderc_glsl_closesthit_shader),
    MISS_SHADER(Shaderc.shaderc_glsl_miss_shader),
    INTERSECTION_SHADER(Shaderc.shaderc_glsl_intersection_shader),
    CALLABLE_SHADER(Shaderc.shaderc_glsl_callable_shader);

    fun toVulkan(): Int {
        return when(kind) {
            Shaderc.shaderc_glsl_vertex_shader -> VK10.VK_SHADER_STAGE_VERTEX_BIT
            Shaderc.shaderc_glsl_fragment_shader -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT
            Shaderc.shaderc_glsl_geometry_shader -> VK10.VK_SHADER_STAGE_GEOMETRY_BIT
            Shaderc.shaderc_glsl_compute_shader -> VK10.VK_SHADER_STAGE_COMPUTE_BIT
            Shaderc.shaderc_glsl_mesh_shader -> VK_SHADER_STAGE_MESH_BIT_EXT
            Shaderc.shaderc_glsl_task_shader ->VK_SHADER_STAGE_TASK_BIT_EXT
            Shaderc.shaderc_glsl_raygen_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
            Shaderc.shaderc_glsl_anyhit_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            Shaderc.shaderc_glsl_closesthit_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
            Shaderc.shaderc_glsl_miss_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR
            Shaderc.shaderc_glsl_intersection_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_INTERSECTION_BIT_KHR
            Shaderc.shaderc_glsl_callable_shader -> KHRRayTracingPipeline.VK_SHADER_STAGE_CALLABLE_BIT_KHR
            else -> throw RuntimeException(" unknown shader type $kind")
        }
    }
}

class CompilerError(override val message : String) : Exception(message) {

}

enum class ShaderIncludeType(internal val shadercType: Int) {
    RELATIVE(Shaderc.shaderc_include_type_relative),
    STANDARD(Shaderc.shaderc_include_type_standard);

    companion object {
        fun fromShaderc(type: Int): ShaderIncludeType = when (type) {
            Shaderc.shaderc_include_type_relative -> RELATIVE
            Shaderc.shaderc_include_type_standard -> STANDARD
            else -> throw IllegalArgumentException("Unknown shader include type $type")
        }
    }
}

data class ShaderIncludeRequest(
    val requestedPath: String,
    val includeType: ShaderIncludeType,
    val requestingSource: String?,
    val includeDepth: Long
)

data class ShaderIncludeResolution(
    val resolvedPath: String,
    val source: String
)

fun interface ShaderIncludeResolver {
    fun resolve(request: ShaderIncludeRequest): ShaderIncludeResolution?
}

data class ShaderCompileSource(
    val sourceName: String,
    val sourceText: String,
    val includeResolver: ShaderIncludeResolver? = null
)

object ShaderSPIRVUtils {
    fun wrapBytecode(bytes: ByteArray): SPIRV {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        return SPIRV(0L, buffer)
    }

    fun compileShaderFile(shaderFile: String?, shaderKind: ShaderKind): SPIRV? {
        return compileShaderAbsoluteFile(
            ClassLoader.getSystemClassLoader().getResource(shaderFile).toExternalForm(), shaderKind
        )
    }

    fun compileShaderAbsoluteFile(shaderFile: String, shaderKind: ShaderKind): SPIRV? {
        try {
            val source = String(Files.readAllBytes(Paths.get(URI(shaderFile))))
            return compileShader(ShaderCompileSource(shaderFile, source), shaderKind)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        return null
    }

    fun compileShader(filename: String, source: String?, shaderKind: ShaderKind): SPIRV {
        return compileShader(
            ShaderCompileSource(
                sourceName = filename,
                sourceText = source ?: File(filename).readText()
            ),
            shaderKind
        )
    }

    fun compileShader(source: ShaderCompileSource, shaderKind: ShaderKind): SPIRV {
        val compiler = Shaderc.shaderc_compiler_initialize()

        if (compiler == MemoryUtil.NULL) {
            throw RuntimeException("Failed to create shader compiler")
        }

        val opts = Shaderc.shaderc_compile_options_initialize()
//        Shaderc.shaderc_compile_options_set_target_env(opts, Shaderc.shaderc_target_env_webgpu, Shaderc.shaderc_env_version_webgpu)

        if (Options.validate || Options.shaderDebugPrintf) {
            Shaderc.shaderc_compile_options_set_generate_debug_info(opts)
        }

        Shaderc.shaderc_compile_options_set_target_env(
            opts, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3
        )
        Shaderc.shaderc_compile_options_set_target_spirv(
            opts, Shaderc.shaderc_spirv_version_1_5
        )

        val includeBridge = source.includeResolver?.let(::NativeShadercIncludeBridge)
        if (includeBridge != null) {
            Shaderc.shaderc_compile_options_set_include_callbacks(
                opts,
                includeBridge.resolveCallback,
                includeBridge.releaseCallback,
                MemoryUtil.NULL
            )
        }

        try {
            if (Options.logShadercSource) {
                logShadercSource(source, shaderKind)
            }

            val result = Shaderc.shaderc_compile_into_spv(
                compiler,
                source.sourceText,
                shaderKind.kind,
                source.sourceName,
                "main",
                opts
            )

            if (result == MemoryUtil.NULL) {
                throw CompilerError("Failed to compile shader ${source.sourceName} into SPIR-V")
            }

            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                lprintln("error,graphics", "Failed to compile shader ${source.sourceName} into SPIR-V: error is")
                lprintln("error,graphics", Shaderc.shaderc_result_get_error_message(result))
                throw CompilerError(Shaderc.shaderc_result_get_error_message(result)?:"")
            }

            val res = SPIRV(result, Shaderc.shaderc_result_get_bytes(result))
            lprintln("info,graphics", "compiled, got results $res ${res.bytecode()}")
            return res
        } finally {
            includeBridge?.free()
            Shaderc.shaderc_compile_options_release(opts)
            Shaderc.shaderc_compiler_release(compiler)
        }
    }

    private fun logShadercSource(source: ShaderCompileSource, shaderKind: ShaderKind) {
        lprintln("debug,graphics", buildString {
            appendLine(
                "GLSL source sent to shaderc: ${source.sourceName} " +
                    "kind=${shaderKind.name} chars=${source.sourceText.length}"
            )
            appendLine("----- BEGIN GLSL ${source.sourceName} -----")
            append(lineNumberedShaderSource(source.sourceText))
            if (!endsWith('\n')) appendLine()
            appendLine("----- END GLSL ${source.sourceName} -----")
        })
    }

    private fun lineNumberedShaderSource(sourceText: String): String {
        if (sourceText.isEmpty()) return "1 | "
        val lines = sourceText.split('\n')
        val renderedLineCount = if (sourceText.endsWith('\n')) lines.size - 1 else lines.size
        val width = renderedLineCount.coerceAtLeast(1).toString().length
        return buildString {
            lines.forEachIndexed { index, line ->
                if (index == lines.lastIndex && sourceText.endsWith('\n')) return@forEachIndexed
                append((index + 1).toString().padStart(width))
                append(" | ")
                append(line)
                if (index != lines.lastIndex || sourceText.endsWith('\n')) appendLine()
            }
        }
    }

    private data class NativeIncludeAllocation(
        val result: ShadercIncludeResult,
        val sourceName: ByteBuffer?,
        val content: ByteBuffer?
    ) {
        fun free() {
            sourceName?.let(MemoryUtil::memFree)
            content?.let(MemoryUtil::memFree)
            result.free()
        }
    }

    private class NativeShadercIncludeBridge(
        private val resolver: ShaderIncludeResolver
    ) : NativeResource {
        private val allocations = ConcurrentHashMap<Long, NativeIncludeAllocation>()

        val resolveCallback = ShadercIncludeResolve.create { _, requestedSource, type, requestingSource, includeDepth ->
            val request = ShaderIncludeRequest(
                requestedPath = MemoryUtil.memUTF8(requestedSource),
                includeType = ShaderIncludeType.fromShaderc(type),
                requestingSource = if (requestingSource == MemoryUtil.NULL) null else MemoryUtil.memUTF8(requestingSource),
                includeDepth = includeDepth
            )

            val resolution: ShaderIncludeResolution? = try {
                resolver.resolve(request)
            } catch (t: Throwable) {
                return@create failedInclude(request, t.message ?: "Unable to resolve include '${request.requestedPath}'.")
            }

            if (resolution == null) {
                failedInclude(request, unresolvedIncludeMessage(request))
            } else {
                createIncludeResult(resolution.resolvedPath, resolution.source)
            }
        }

        val releaseCallback = ShadercIncludeResultRelease.create { _, includeResult ->
            allocations.remove(includeResult)?.free()
        }

        override fun free() {
            allocations.values.forEach { it.free() }
            allocations.clear()
            resolveCallback.free()
            releaseCallback.free()
        }

        private fun failedInclude(request: ShaderIncludeRequest, message: String): Long {
            return createIncludeResult("", message)
        }

        private fun unresolvedIncludeMessage(request: ShaderIncludeRequest): String {
            val source = request.requestingSource?.takeIf { it.isNotBlank() } ?: "<root>"
            return "Unable to resolve include '${request.requestedPath}' from '$source'."
        }

        private fun createIncludeResult(sourceName: String, content: String): Long {
            val result = ShadercIncludeResult.calloc()
            val sourceNameBuffer = MemoryUtil.memUTF8(sourceName, true)
            val contentBuffer = MemoryUtil.memUTF8(content, true)
            result.source_name(sourceNameBuffer)
            result.content(contentBuffer)
            ShadercIncludeResult.nsource_name_length(result.address(), (sourceNameBuffer.remaining() - 1).coerceAtLeast(0).toLong())
            ShadercIncludeResult.ncontent_length(result.address(), (contentBuffer.remaining() - 1).coerceAtLeast(0).toLong())
            val address = result.address()
            allocations[address] = NativeIncludeAllocation(result, sourceNameBuffer, contentBuffer)
            return address
        }
    }

    class SPIRV(private val handle: Long, private var bytecode: ByteBuffer?) : NativeResource {
        fun bytecode(): ByteBuffer? {
            bytecode?.position(0)
            return bytecode
        }

        override fun free() {
            if (handle != 0L) {
                Shaderc.shaderc_result_release(handle)
            }
            bytecode = null // Help the GC
        }
    }
}

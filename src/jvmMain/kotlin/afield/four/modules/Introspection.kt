package afield.four.modules

import afield.logger.lprintln
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.json.JSONObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.vulkan.VK10
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun spvcReflectJson(spirvBytecode: ByteBuffer): String {
    val words = spirvBytecode.slice().order(ByteOrder.nativeOrder()).asIntBuffer()

    MemoryStack.stackPush().use { stack ->
        val pContext = stack.mallocPointer(1)
        val createResult = Spvc.spvc_context_create(pContext)
        require(createResult == Spvc.SPVC_SUCCESS) {
            "spvc_context_create failed (result=$createResult)"
        }

        val context = pContext[0]
        try {
            val pParsedIr = stack.mallocPointer(1)
            run {
                val parseResult = Spvc.spvc_context_parse_spirv(context, words, words.remaining().toLong(), pParsedIr)
                require(parseResult == Spvc.SPVC_SUCCESS) {
                    "spvc_context_parse_spirv failed (result=$parseResult, error=${Spvc.spvc_context_get_last_error_string(context)})"
                }
            }

            val parsedIr = pParsedIr[0]
            val pCompiler = stack.mallocPointer(1)
            run {
                val compilerResult = Spvc.spvc_context_create_compiler(
                    context,
                    Spvc.SPVC_BACKEND_JSON,
                    parsedIr,
                    Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                    pCompiler
                )
                require(compilerResult == Spvc.SPVC_SUCCESS) {
                    "spvc_context_create_compiler failed (result=$compilerResult, error=${Spvc.spvc_context_get_last_error_string(context)})"
                }
            }

            val compiler = pCompiler[0]
            val pSource = stack.mallocPointer(1)
            run {
                val compileResult = Spvc.spvc_compiler_compile(compiler, pSource)
                require(compileResult == Spvc.SPVC_SUCCESS) {
                    "spvc_compiler_compile failed (result=$compileResult, error=${Spvc.spvc_context_get_last_error_string(context)})"
                }
            }

            val json = MemoryUtil.memUTF8(pSource[0])
            Spvc.spvc_context_release_allocations(context)
            return json
        } finally {
            Spvc.spvc_context_destroy(context)
        }
    }
}

private val slangStorageBlockPrefixes = listOf(
    "StructuredBuffer_",
    "RWStructuredBuffer_",
    "ByteAddressBuffer_",
    "RWByteAddressBuffer_",
    "AppendStructuredBuffer_",
    "ConsumeStructuredBuffer_",
    "RasterizerOrderedStructuredBuffer_",
    "RasterizerOrderedByteAddressBuffer_"
)

private val interfaceBlockDeclarationRegex = Regex(
    """layout\s*\([^)]*\)\s*(?:(?:readonly|writeonly|coherent|volatile|restrict)\s+)*buffer\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{.*?\}\s*([A-Za-z_][A-Za-z0-9_]*)\s*;""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
)

private fun extractSlangStorageBlockAliases(source: String): Map<String, String> {
    val aliases = mutableMapOf<String, String>()
    interfaceBlockDeclarationRegex.findAll(source).forEach { match ->
        val blockType = match.groupValues[1]
        val instanceName = match.groupValues[2]
        if (blockType == instanceName) return@forEach
        if (slangStorageBlockPrefixes.any { blockType.startsWith(it) }) {
            aliases[blockType] = instanceName
        }
    }
    return aliases
}

private val slangNumericSuffixRegex = Regex("""^(.*)_\d+$""")

private fun demangleSimpleSlangName(name: String): String {
    val match = slangNumericSuffixRegex.matchEntire(name) ?: return name
    return match.groupValues[1]
}

private fun Introspected.Member.withOffsetAndDemangledName(baseOffset: Int = 0): Introspected.Member {
    val adjustedType = type.map { members ->
        members.map { it.withOffsetAndDemangledName() }
    }
    return copy(
        name = demangleSimpleSlangName(name),
        offset = baseOffset + offset,
        type = adjustedType
    )
}

private fun normalizePushConstantMembers(blockName: String, members: List<Introspected.Member>): List<Introspected.Member> {
    val isSlangEntryPointWrapper = blockName == "entryPointParams" || blockName.startsWith("entryPointParams_")
    if (!isSlangEntryPointWrapper || members.size != 1) return members

    val wrapper = members.first()
    val nestedMembers = wrapper.type.fold({ null }, { it }) ?: return members
    return nestedMembers.map { it.withOffsetAndDemangledName(wrapper.offset) }
}

private enum class GlslDeclaredStructureKind {
    STRUCT,
    BUFFER_REFERENCE
}

private data class GlslDeclaredStructure(
    val name: String,
    val kind: GlslDeclaredStructureKind,
    val memberNames: List<String>
)

private val glslBlockCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val glslLineCommentRegex = Regex("""//.*?$""", setOf(RegexOption.MULTILINE))
private val glslStructDeclarationRegex = Regex(
    """\bstruct\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*?)\}\s*;""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
)
private val glslBufferReferenceDeclarationRegex = Regex(
    """layout\s*\([^)]*\bbuffer_reference\b[^)]*\)\s*(?:(?:readonly|writeonly|coherent|volatile|restrict)\s+)*buffer\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*?)\}\s*;""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
)
private val glslFieldRegex = Regex(
    """(?:layout\s*\([^)]*\)\s*)?(?:readonly|writeonly|coherent|volatile|restrict|const|in|out|inout|highp|mediump|lowp\s+)*[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^;>]+>)?(?:\s*\*)?\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^]]*])?\s*$"""
)

private fun stripGlslComments(source: String): String =
    source.replace(glslBlockCommentRegex, "").replace(glslLineCommentRegex, "")

private fun parseGlslDeclaredStructures(source: String): List<GlslDeclaredStructure> {
    val clean = stripGlslComments(source)

    fun fields(body: String): List<String> =
        body.split(";")
            .mapNotNull { raw ->
                val line = raw.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                glslFieldRegex.matchEntire(line)?.groupValues?.getOrNull(1)
            }

    val out = mutableListOf<GlslDeclaredStructure>()
    glslStructDeclarationRegex.findAll(clean).forEach { match ->
        out += GlslDeclaredStructure(
            match.groupValues[1],
            GlslDeclaredStructureKind.STRUCT,
            fields(match.groupValues[2])
        )
    }
    glslBufferReferenceDeclarationRegex.findAll(clean).forEach { match ->
        out += GlslDeclaredStructure(
            match.groupValues[1],
            GlslDeclaredStructureKind.BUFFER_REFERENCE,
            fields(match.groupValues[2])
        )
    }
    return out.filter { it.memberNames.isNotEmpty() }
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
        "afield.stdlib.linalg.Vector2f",
        "vec(0,0)",
        "x.get(buffer); buffer.position(buffer.position()+8)"
    ),
    vec3(
        3,
        4,
        VK10.VK_FORMAT_R32G32B32_SFLOAT,
        "afield.stdlib.linalg.Vector3f",
        "vec(0,0,0)",
        "x.get(buffer); buffer.position(buffer.position()+12)"
    ),
    vec4(
        4,
        4,
        VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
        "afield.stdlib.linalg.Vector4f",
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
        "afield.stdlib.linalg.Matrix4f",
        "mat(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)",
        "x.get(buffer); buffer.position(buffer.position()+16*4)"
    ),
    mat3(
        9, 4, -1, "afield.stdlib.linalg.Matrix3f", "mat(0,0,0,0,0,0,0,0,0)", "x.get(buffer); buffer.position(buffer.position()+9*4)"
    ),
    mat2(4, 4, -1, "afield.stdlib.linalg.Matrix2f", "mat(0,0,0,0)", "x.get(buffer); buffer.position(buffer.position()+2*4)"),

    float16_t(
        1, 2, VK10.VK_FORMAT_R16_SFLOAT,
        "Float", "0f",
        "buffer.putf16(x)"
    ),
    f16vec2(
        2, 2, VK10.VK_FORMAT_R16G16_SFLOAT,
        "afield.stdlib.linalg.Vector2f", "vec(0,0)",
        "buffer.putf16(x.x); buffer.putf16(x.y)"
    ),
    f16vec3(
        3, 2, VK10.VK_FORMAT_R16G16B16_SFLOAT,
        "afield.stdlib.linalg.Vector3f", "vec(0,0,0)",
        "buffer.putf16(x.x); buffer.putf16(x.y); buffer.putf16(x.z)"
    ),
    f16vec4(
        4, 2, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
        "afield.stdlib.linalg.Vector4f", "vec(0,0,0,1)",
        "buffer.putf16(x.x); buffer.putf16(x.y); buffer.putf16(x.z); buffer.putf16(x.w)"
    ),
    deviceptr(
        1, 8, -1,
        "Long", "0L",
        "buffer.putLong(x)"
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
            get() = type.fold({ it.sizeInBytes }, { it.sumOf { it.memberSize() } })
    }

    data class Member(
        val name: String,
        val offset: Int,
        val type: Either<PrimitiveType, List<Member>>,
        val arrayStride: Int = 0, // 0 means not an array
        var physicalPointer: Boolean = false
    ) {
        fun memberSize(): Long =
            if (physicalPointer) PrimitiveType.deviceptr.sizeInBytes
            else type.fold({ it.componentsSizeInBytes * it.components.toLong() }, { it.sumOf { it.memberSize() } })
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
    class AccelerationStructure(name: String, val type: String, set: Int, binding: Int) : Bindable(name, set, binding)

    data class Sampler(val name: String, val type: String, val set: Int, val location: Int)
    data class PushConstant(val name: String, val size: Int, val members: List<Member>)
    data class StructureType(val name: String, val members: List<Member>, val size: Int, val bufferReference: Boolean = false)

    val inputs = mutableListOf<Input>()
    val outputs = mutableListOf<Input>()
    val ubos = mutableListOf<UBO>()
    val ssbo = mutableListOf<UBO>()
    val textures = mutableListOf<Texture>()
    val images = mutableListOf<Image>()
    val accelerationStructures = mutableListOf<AccelerationStructure>()
    val samplers = mutableListOf<Sampler>()
    val pushConstants = mutableListOf<PushConstant>()
    val structureTypes = mutableListOf<StructureType>()

    lateinit var code: ShaderSPIRVUtils.SPIRV
    override fun toString(): String {
        return "Introspected(\n\tinputs=$inputs, \n\toutputs=$outputs, \n\tubos=$ubos, \n\tssbo=$ssbo, \n\ttextures=$textures, \n\timages=$images, \n\taccelerationStructures=$accelerationStructures, \n" + "\tsamplers=$samplers, \n" + "\tpushConstants=$pushConstants)"
    }
}

private fun primitiveTypeFromName(typeName: String): PrimitiveType? = try {
    PrimitiveType.valueOf(typeName.lowercase())
} catch (_: IllegalArgumentException) {
    when (typeName.lowercase()) {
        "uvec2", "ivec2" -> PrimitiveType.vec2
        "uvec3", "ivec3" -> PrimitiveType.vec3
        "uvec4", "ivec4" -> PrimitiveType.vec4
        else -> null
    }
}

private fun slangStageFor(kind: ShaderKind): String = when (kind) {
    ShaderKind.VERTEX_SHADER -> "vertex"
    ShaderKind.FRAGMENT_SHADER -> "fragment"
    ShaderKind.COMPUTE_SHADER -> "compute"
    ShaderKind.MESH_SHADER -> "mesh"
    ShaderKind.TASK_SHADER -> "task"
    ShaderKind.RAYGEN_SHADER -> "raygeneration"
    ShaderKind.ANY_HIT_SHADER -> "anyhit"
    ShaderKind.CLOSEST_HIT_SHADER -> "closesthit"
    ShaderKind.MISS_SHADER -> "miss"
    ShaderKind.INTERSECTION_SHADER -> "intersection"
    ShaderKind.CALLABLE_SHADER -> "callable"
    else -> kind.name.lowercase()
}

private fun slangBindingSet(binding: JSONObject?): Int {
    if (binding == null) return 0
    if (binding.has("space")) return binding.optInt("space", 0)
    return binding.optInt("set", 0)
}

private fun slangBindingIndex(binding: JSONObject?): Int {
    if (binding == null) return 0
    if (binding.has("index")) return binding.optInt("index", 0)
    return binding.optInt("binding", 0)
}

private fun slangFieldOffset(field: JSONObject): Int =
    field.optJSONObject("binding")?.optInt("offset", 0) ?: 0

private fun slangFieldArrayStride(field: JSONObject): Int {
    val type = field.optJSONObject("type") ?: return 0
    if (type.optString("kind") != "array") return 0
    return field.optJSONObject("binding")?.optInt("elementStride", 0) ?: 0
}

private fun slangTypeSummary(type: JSONObject): String = when (type.getString("kind")) {
    "scalar" -> when (val scalarType = type.getString("scalarType")) {
        "float32" -> "float"
        "float16" -> "half"
        "int32" -> "int"
        "uint32" -> "uint"
        "int64" -> "int64_t"
        "uint64" -> "uint64_t"
        else -> scalarType
    }
    "vector" -> {
        val count = type.getInt("elementCount")
        val elementType = type.getJSONObject("elementType")
        "${slangTypeSummary(elementType)}$count"
    }

    "matrix" -> {
        val rows = type.getInt("rowCount")
        val columns = type.getInt("columnCount")
        val elementType = type.getJSONObject("elementType")
        "${slangTypeSummary(elementType)}${rows}x$columns"
    }

    "pointer" -> {
        val valueType = type.opt("valueType")
        when (valueType) {
            is JSONObject -> "${slangTypeSummary(valueType)}*"
            is String -> "$valueType*"
            else -> "pointer"
        }
    }

    "array" -> {
        val elementType = type.getJSONObject("elementType")
        "${slangTypeSummary(elementType)}[${type.optInt("elementCount", -1)}]"
    }

    "struct" -> type.optString("name", "struct")
    else -> type.getString("kind")
}

private fun unsupportedPushConstantType(path: String, reason: String, typeDescription: String): Nothing {
    throw IllegalArgumentException(
        "Unsupported push constant member '$path': $reason (type=$typeDescription)"
    )
}

private fun validateSlangPushConstantType(type: JSONObject, path: String) {
    when (type.getString("kind")) {
        "scalar" -> {
            when (type.getString("scalarType").lowercase()) {
                "float32", "int32", "uint32" -> return
                "float16" -> unsupportedPushConstantType(path, "16-bit scalar push constants are not supported", slangTypeSummary(type))
                else -> unsupportedPushConstantType(
                    path,
                    "scalar type '${type.getString("scalarType")}' is not supported",
                    slangTypeSummary(type)
                )
            }
        }

        "vector" -> {
            val count = type.getInt("elementCount")
            val elementType = type.getJSONObject("elementType")
            val scalarType = elementType.optString("scalarType").lowercase()
            if (elementType.getString("kind") != "scalar") {
                unsupportedPushConstantType(path, "non-scalar vector elements are not supported", slangTypeSummary(type))
            }
            when (scalarType) {
                "float32" -> if (count in 2..4) return
                "float16" -> unsupportedPushConstantType(path, "16-bit vector push constants are not supported", slangTypeSummary(type))
                "int32", "uint32" -> unsupportedPushConstantType(path, "integer vector push constants are not supported", slangTypeSummary(type))
                else -> unsupportedPushConstantType(path, "vector element type '$scalarType' is not supported", slangTypeSummary(type))
            }
            unsupportedPushConstantType(path, "only 2/3/4-wide float vectors are supported", slangTypeSummary(type))
        }

        "matrix" -> {
            val rows = type.getInt("rowCount")
            val columns = type.getInt("columnCount")
            val elementType = type.getJSONObject("elementType")
            val scalarType = elementType.optString("scalarType").lowercase()
            if (elementType.getString("kind") != "scalar" || scalarType != "float32") {
                unsupportedPushConstantType(path, "only float matrices are supported", slangTypeSummary(type))
            }
            when {
                rows == 3 && columns == 3 ->
                    unsupportedPushConstantType(path, "mat3 is not supported", slangTypeSummary(type))

                rows == 2 && columns == 2 -> return
                rows == 4 && columns == 4 -> return
                else -> unsupportedPushConstantType(path, "only float2x2 and float4x4 matrices are supported", slangTypeSummary(type))
            }
        }

        "pointer" -> return
        "array" -> unsupportedPushConstantType(path, "arrays are not supported", slangTypeSummary(type))
        "struct" -> unsupportedPushConstantType(path, "nested structs are not supported", slangTypeSummary(type))
        else -> unsupportedPushConstantType(path, "type kind '${type.getString("kind")}' is not supported", slangTypeSummary(type))
    }
}

private fun slangTypeToPrimitive(type: JSONObject): PrimitiveType? = when (type.getString("kind")) {
    "scalar" -> when (type.getString("scalarType").lowercase()) {
        "float32" -> PrimitiveType.float
        "float16" -> PrimitiveType.float16_t
        "int32" -> PrimitiveType.int
        "uint32" -> PrimitiveType.uint
        else -> null
    }

    "vector" -> {
        val count = type.getInt("elementCount")
        val elementKind = type.getJSONObject("elementType").getString("kind")
        val scalarType = type.getJSONObject("elementType").optString("scalarType").lowercase()
        when {
            elementKind != "scalar" -> null
            scalarType == "float16" && count == 2 -> PrimitiveType.f16vec2
            scalarType == "float16" && count == 3 -> PrimitiveType.f16vec3
            scalarType == "float16" && count == 4 -> PrimitiveType.f16vec4
            count == 2 -> PrimitiveType.vec2
            count == 3 -> PrimitiveType.vec3
            count == 4 -> PrimitiveType.vec4
            else -> null
        }
    }

    "matrix" -> {
        val rows = type.getInt("rowCount")
        val columns = type.getInt("columnCount")
        when {
            rows == 2 && columns == 2 -> PrimitiveType.mat2
            rows == 3 && columns == 3 -> PrimitiveType.mat3
            rows == 4 && columns == 4 -> PrimitiveType.mat4
            else -> null
        }
    }

    "pointer" -> PrimitiveType.deviceptr
    else -> null
}

private fun slangTypeToEither(type: JSONObject): Either<PrimitiveType, List<Introspected.Member>> {
    slangTypeToPrimitive(type)?.let { return it.left() }
    if (type.getString("kind") == "struct") {
        return slangMembersFromFields(type.optJSONArray("fields")).right()
    }
    throw IllegalArgumentException("Unsupported Slang reflection type ${type}")
}

private fun slangMembersFromFields(fields: org.json.JSONArray?): List<Introspected.Member> {
    if (fields == null) return emptyList()
    val members = mutableListOf<Introspected.Member>()
    for (i in 0 until fields.length()) {
        val field = fields.getJSONObject(i)
        val type = field.getJSONObject("type")
        val physicalPointer = type.getString("kind") == "pointer"
        members += Introspected.Member(
            name = field.getString("name"),
            offset = slangFieldOffset(field),
            type = slangTypeToEither(type),
            arrayStride = slangFieldArrayStride(field),
            physicalPointer = physicalPointer
        )
    }
    return members
}

private fun slangPushConstantMembersFromFields(blockName: String, fields: org.json.JSONArray?): List<Introspected.Member> {
    if (fields == null) return emptyList()
    for (i in 0 until fields.length()) {
        val field = fields.getJSONObject(i)
        val type = field.getJSONObject("type")
        validateSlangPushConstantType(type, "$blockName.${field.getString("name")}")
    }
    return slangMembersFromFields(fields)
}

/**
 * Byte size of a member's type: a primitive's size, or the maximum offset+size across nested
 * members. Shared with `Structure.kt` and `shaders.kt`, which previously each hand-rolled this walk.
 */
fun Either<PrimitiveType, List<Introspected.Member>>.layoutSizeInBytes(): Int =
    fold({ it.sizeInBytes.toInt() }, { members ->
        if (members.isEmpty()) 0 else members.maxOf { member ->
            member.offset + member.type.layoutSizeInBytes()
        }
    })

/** Byte size of a member: a device pointer's size if physical-pointer, else its type's layout size. */
fun Introspected.Member.layoutSizeInBytes(): Int =
    if (physicalPointer) PrimitiveType.deviceptr.sizeInBytes.toInt() else type.layoutSizeInBytes()

fun List<Introspected.Member>.membersLayoutSizeInBytes(): Int =
    if (isEmpty()) 0 else maxOf { it.offset + it.layoutSizeInBytes() }

private fun Int.nearest16() = (this + 15) and -16

private fun slangPushConstantSize(parameter: JSONObject, members: List<Introspected.Member>): Int {
    val binding = parameter.optJSONObject("binding")
    val rawSize = binding?.optInt("size", 0) ?: 0
    val computedSize = if (members.isEmpty()) 0 else {
        members.maxOf { member -> member.offset + member.type.layoutSizeInBytes() }
    }
    return maxOf(rawSize, computedSize).nearest16()
}

private fun validateSupportedPushConstantMembers(blockName: String, members: List<Introspected.Member>) {
    fun validate(member: Introspected.Member, path: String) {
        if (member.arrayStride > 0) {
            unsupportedPushConstantType(path, "arrays are not supported", "array(stride=${member.arrayStride})")
        }
        if (member.physicalPointer) return
        member.type.fold(
            { primitive ->
                when (primitive) {
                    PrimitiveType.float,
                    PrimitiveType.vec2,
                    PrimitiveType.vec3,
                    PrimitiveType.vec4,
                    PrimitiveType.int,
                    PrimitiveType.uint,
                    PrimitiveType.mat2,
                    PrimitiveType.mat4,
                    PrimitiveType.deviceptr -> Unit

                    PrimitiveType.mat3 ->
                        unsupportedPushConstantType(path, "mat3 is not supported", primitive.name)

                    PrimitiveType.float16_t,
                    PrimitiveType.f16vec2,
                    PrimitiveType.f16vec3,
                    PrimitiveType.f16vec4 ->
                        unsupportedPushConstantType(path, "16-bit push constant types are not supported", primitive.name)
                }
            },
            { nested ->
                unsupportedPushConstantType(path, "nested structs are not supported", "struct(${nested.joinToString { it.name }})")
            }
        )
    }

    members.forEach { validate(it, "$blockName.${it.name}") }
}

private fun slangStructuredBufferMembers(parameter: JSONObject): List<Introspected.Member> {
    val type = parameter.getJSONObject("type")
    val elementTypeObject =
        type.optJSONObject("resultType")
            ?: type.optJSONObject("elementType")
            ?: type.optJSONObject("valueType")
    val memberType = elementTypeObject?.let(::slangTypeToEither) ?: PrimitiveType.vec4.left()
    return listOf(
        Introspected.Member(
            name = "_data",
            offset = 0,
            type = memberType,
            arrayStride = memberType.layoutSizeInBytes()
        )
    )
}

class Introspection private constructor(
    val source: String,
    val kind: ShaderKind,
    val errorHandler: (String, Throwable) -> Unit = { _, throwable -> throw throwable },
    val sourceName: String = kind.toString() + ".glsl",
    val includeResolver: ShaderIncludeResolver? = null,
    private val precompiledSpirv: ShaderSPIRVUtils.SPIRV? = null,
    private val slangReflectionJson: String? = null
) : Introspected() {

    constructor(
        source: String,
        kind: ShaderKind,
        errorHandler: (String, Throwable) -> Unit = { _, throwable -> throw throwable },
        sourceName: String = kind.toString() + ".glsl",
        includeResolver: ShaderIncludeResolver? = null
    ) : this(source, kind, errorHandler, sourceName, includeResolver, null, null)

    var valid = false

    init {
        try {
            val spirv = precompiledSpirv ?: ShaderSPIRVUtils.compileShader(
                ShaderCompileSource(
                    sourceName = sourceName,
                    sourceText = source,
                    includeResolver = includeResolver
                ),
                kind
            )
            val data = spirv.bytecode()!!
            code = spirv
            if (slangReflectionJson != null) {
                populateFromSlangReflection(JSONObject(slangReflectionJson))
            } else {
                populateFromSpvcReflection(JSONObject(spvcReflectJson(data)))
            }
            valid = true
        } catch (e: CompilerError) {
            errorHandler(e.message, e)
        } catch (e: Exception) {
            errorHandler("while introspecting shader $kind", e)
        }

    }

    private fun populateFromSpvcReflection(j: JSONObject) {
        val slangStorageBlockAliases = extractSlangStorageBlockAliases(source)
        lprintln("debug,graphics", j.toString())

        val types = mutableMapOf<String, MutableList<Member>>()
        if (j.has("types")) {
            val ti = j.getJSONObject("types")
            for (e in ti.keySet()) {
                types[e] = mutableListOf()
            }

            for (e in ti.keySet()) {
                val to = ti.getJSONObject(e)
                types[e]!!.addAll(to.getJSONArray("members").mapNotNull { m ->
                    m as JSONObject
                    lprintln("debug,graphics", m.toString())
                    if (!m.has("offset")) return@mapNotNull null
                    val offset = m.getInt("offset")
                    val n2 = m.getString("name")
                    val primitive = primitiveTypeFromName(m.getString("type"))
                    if (primitive != null) {
                        return@mapNotNull Member(
                            n2,
                            offset,
                            primitive.left(),
                            arrayStride = if (m.has("array_stride")) m.getInt("array_stride") else 0
                        )
                    }

                    val physicalPointer = if (m.has("physical_pointer")) m.getBoolean("physical_pointer") else false
                    val typeKey = m.getString("type")
                    val memberType = types[typeKey]?.right()
                        ?: throw IllegalArgumentException("Unknown member type '$typeKey' for '$n2'")
                    Member(n2, offset, memberType, 0, physicalPointer)
                })
            }
        }

        val declaredStructures = parseGlslDeclaredStructures(source)
        declaredStructures.forEach { declaration ->
            val reflectedMembers = types.values.firstOrNull { members ->
                members.map { it.name } == declaration.memberNames
            }
            if (reflectedMembers != null && structureTypes.none { it.name == declaration.name }) {
                structureTypes += StructureType(
                    name = declaration.name,
                    members = reflectedMembers,
                    size = reflectedMembers.membersLayoutSizeInBytes(),
                    bufferReference = declaration.kind == GlslDeclaredStructureKind.BUFFER_REFERENCE
                )
            }
        }

        if (j.has("inputs")) {
            val ai = j.getJSONArray("inputs")
            for (n in 0 until ai.length()) {
                val ao = ai.getJSONObject(n)
                lprintln("debug,graphics", ao.toString())
                val primitive = primitiveTypeFromName(ao.getString("type"))
                inputs.add(
                    Input(
                        ao.getString("name"),
                        primitive?.left() ?: types.getValue(ao.getString("type")).right(),
                        ao.getInt("location")
                    )
                )
            }
        }

        if (j.has("ubos")) {
            lprintln("debug,graphics", "types are $types")
            val ui = j.getJSONArray("ubos")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                ubos.add(
                    UBO(
                        uo.getString("name"),
                        uo.getInt("block_size"),
                        uo.getInt("set"),
                        uo.getInt("binding"),
                        types.getValue(uo.getString("type"))
                    )
                )
            }
        }

        if (j.has("push_constants")) {
            lprintln("debug,graphics", "types for pc are $types")
            val ui = j.getJSONArray("push_constants")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                val name = uo.getString("name")
                val ty = normalizePushConstantMembers(name, types.getValue(uo.getString("type")))
                val size = if (ty.isEmpty()) 0 else {
                    ty.maxOf { m ->
                        m.offset + m.type.fold(
                            { it.sizeInBytes.toInt() },
                            { it.sumOf { sub -> sub.memberSize() }.toInt() }
                        )
                    }
                }

                pushConstants.add(PushConstant(name, size.nearest16(), ty))
            }
        }

        if (j.has("ssbos")) {
            val ui = j.getJSONArray("ssbos")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                val reflectedName = uo.getString("name")
                val name = slangStorageBlockAliases[reflectedName] ?: reflectedName
                if (name != reflectedName) {
                    lprintln("debug,graphics", "Resolved Slang SSBO alias $reflectedName -> $name")
                }
                ssbo.add(
                    UBO(
                        name,
                        uo.getInt("block_size"),
                        uo.getInt("set"),
                        uo.getInt("binding"),
                        types.getValue(uo.getString("type"))
                    )
                )
            }
        }

        if (j.has("textures")) {
            val ui = j.getJSONArray("textures")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                if (uo.has("array_size_is_literal") && uo.getJSONArray("array_size_is_literal").getBoolean(0)
                    && uo.getJSONArray("array_size_is_literal").length() == 1
                ) {
                    textures.add(
                        Texture(
                            uo.getString("name"),
                            uo.getString("type"),
                            uo.getInt("set"),
                            uo.getInt("binding"),
                            uo.getJSONArray("array").getInt(0)
                        )
                    )
                } else {
                    textures.add(Texture(uo.getString("name"), uo.getString("type"), uo.getInt("set"), uo.getInt("binding")))
                }
            }
        }

        if (j.has("images")) {
            val ui = j.getJSONArray("images")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                images.add(Image(uo.getString("name"), uo.getString("format"), uo.getInt("set"), uo.getInt("binding")))
            }
        }

        if (j.has("acceleration_structures")) {
            val ui = j.getJSONArray("acceleration_structures")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                accelerationStructures.add(
                    AccelerationStructure(
                        uo.getString("name"),
                        uo.optString("type", "accelerationStructureEXT"),
                        uo.getInt("set"),
                        uo.getInt("binding")
                    )
                )
            }
        }

        if (j.has("separate_samplers")) {
            val ui = j.getJSONArray("separate_samplers")
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                samplers.add(Sampler(uo.getString("name"), uo.getString("type"), uo.getInt("set"), uo.getInt("binding")))
            }
        }

        if (j.has("outputs")) {
            val ui = j.getJSONArray("outputs")
            lprintln("debug,graphics", ui.toString())
            for (i in 0 until ui.length()) {
                val uo = ui.getJSONObject(i)
                val primitive = primitiveTypeFromName(uo.getString("type"))
                outputs.add(
                    Input(
                        uo.getString("name"),
                        primitive?.left() ?: types.getValue(uo.getString("type")).right(),
                        uo.getInt("location")
                    )
                )
            }
        }

        pushConstants.forEach { validateSupportedPushConstantMembers(it.name, it.members) }
        validateDescriptorSets()
    }

    private fun populateFromSlangReflection(root: JSONObject) {
        lprintln("debug,graphics", root.toString())

        val stage = slangStageFor(kind)
        val entryPoints = root.optJSONArray("entryPoints")
        val entryPoint = (0 until (entryPoints?.length() ?: 0))
            .map { entryPoints!!.getJSONObject(it) }
            .firstOrNull { it.optString("stage").lowercase() == stage }
            ?: throw IllegalArgumentException("No Slang reflection entry point for stage '$stage'")

        val parameters = root.optJSONArray("parameters")
        for (i in 0 until (parameters?.length() ?: 0)) {
            val parameter = parameters!!.getJSONObject(i)
            val binding = parameter.optJSONObject("binding")
            if (binding?.optString("kind") != "descriptorTableSlot") continue

            val name = parameter.getString("name")
            val type = parameter.getJSONObject("type")
            when (type.getString("kind")) {
                "resource" -> {
                    val baseShape = type.optString("baseShape")
                    val set = slangBindingSet(binding)
                    val slot = slangBindingIndex(binding)
                    when {
                        baseShape.equals("structuredBuffer", ignoreCase = true) ||
                            baseShape.equals("rwStructuredBuffer", ignoreCase = true) ||
                            baseShape.equals("byteAddressBuffer", ignoreCase = true) ||
                            baseShape.equals("rwByteAddressBuffer", ignoreCase = true) -> {
                            ssbo.add(UBO(name, 0, set, slot, slangStructuredBufferMembers(parameter)))
                        }

                        baseShape.startsWith("texture", ignoreCase = true) && type.optString("access").equals("readWrite", ignoreCase = true) -> {
                            images.add(Image(name, baseShape, set, slot))
                        }

                        baseShape.startsWith("texture", ignoreCase = true) -> {
                            textures.add(Texture(name, baseShape, set, slot))
                        }

                        baseShape.contains("accelerationStructure", ignoreCase = true) -> {
                            accelerationStructures.add(AccelerationStructure(name, baseShape, set, slot))
                        }
                    }
                }

                "samplerState" -> {
                    samplers.add(Sampler(name, "samplerState", slangBindingSet(binding), slangBindingIndex(binding)))
                }

                "constantBuffer" -> {
                    val blockBinding = type.optJSONObject("containerVarLayout")?.optJSONObject("binding") ?: binding
                    val elementLayout = type.optJSONObject("elementVarLayout")
                    val blockType = elementLayout?.optJSONObject("type") ?: type.optJSONObject("elementType")
                    val members = slangMembersFromFields(blockType?.optJSONArray("fields"))
                    val size = elementLayout?.optJSONObject("binding")?.optInt("size", 0)
                        ?: blockBinding.optInt("size", 0)
                    ubos.add(UBO(name, size, slangBindingSet(blockBinding), slangBindingIndex(blockBinding), members))
                }
            }
        }

        val entryParameters = entryPoint.optJSONArray("parameters")
        for (i in 0 until (entryParameters?.length() ?: 0)) {
            val parameter = entryParameters!!.getJSONObject(i)
            val binding = parameter.optJSONObject("binding")
            val bindingKind = binding?.optString("kind").orEmpty()
            when (bindingKind) {
                "varyingInput" -> collectSlangStageInterface(inputs, parameter)
                "uniform" -> {
                    val blockName = parameter.getString("name")
                    val members = slangPushConstantMembersFromFields(blockName, parameter.getJSONObject("type").optJSONArray("fields"))
                    pushConstants.add(PushConstant(parameter.getString("name"), slangPushConstantSize(parameter, members), members))
                }
            }
        }

        entryPoint.optJSONObject("result")?.let { result ->
            if (result.optJSONObject("binding")?.optString("kind") == "varyingOutput" ||
                result.optJSONObject("binding") == null && result.getJSONObject("type").optString("kind") == "struct"
            ) {
                collectSlangStageInterface(outputs, result, fallbackName = entryPoint.getString("name"))
            }
        }

        pushConstants.forEach { validateSupportedPushConstantMembers(it.name, it.members) }
        validateDescriptorSets()
    }

    private fun collectSlangStageInterface(
        target: MutableList<Input>,
        value: JSONObject,
        fallbackName: String? = null
    ) {
        val type = value.getJSONObject("type")
        if (type.optString("kind") == "struct") {
            val fields = type.optJSONArray("fields") ?: return
            for (i in 0 until fields.length()) {
                val field = fields.getJSONObject(i)
                val binding = field.optJSONObject("binding")
                if (binding == null || binding.optString("kind").startsWith("varying").not()) continue
                target.add(
                    Input(
                        field.getString("name"),
                        slangTypeToEither(field.getJSONObject("type")),
                        slangBindingIndex(binding)
                    )
                )
            }
            return
        }

        val binding = value.optJSONObject("binding") ?: return
        if (!binding.optString("kind").startsWith("varying")) return
        target.add(
            Input(
                fallbackName ?: value.optString("name", "result"),
                slangTypeToEither(type),
                slangBindingIndex(binding)
            )
        )
    }

    private fun validateDescriptorSets() {
        val sets = mutableSetOf<Int>()
        sets += ubos.map { it.set }
        sets += ssbo.map { it.set }
        sets += textures.map { it.set }
        sets += images.map { it.set }
        sets += accelerationStructures.map { it.set }
        sets += samplers.map { it.set }
        if (sets.isNotEmpty()) {
            val minSet = sets.minOrNull()!!
            val maxSet = sets.maxOrNull()!!
            require(minSet == 0 && sets.size == maxSet + 1) {
                "Descriptor sets must be contiguous starting at set=0 (found sets=${sets.sorted()})"
            }
        }
    }

    companion object {
        fun fromSlangReflection(
            source: String,
            kind: ShaderKind,
            spirv: ShaderSPIRVUtils.SPIRV,
            reflectionJson: String,
            sourceName: String = kind.toString() + ".glsl",
            includeResolver: ShaderIncludeResolver? = null,
            errorHandler: (String, Throwable) -> Unit = { _, throwable -> throw throwable }
        ): Introspection = Introspection(source, kind, errorHandler, sourceName, includeResolver, spirv, reflectionJson)

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

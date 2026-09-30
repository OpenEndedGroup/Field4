package afield.four.buffers

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Marks a Kotlin class as a CPU/GPU ABI declaration.
 *
 * [GpuSchema] deliberately describes memory, not a particular Vulkan binding strategy. The same
 * schema can be packed into push constants, a UBO/SSBO, or a device-addressable buffer and can emit
 * ordinary GLSL structs plus `GL_EXT_buffer_reference` views over that memory.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GpuStruct(val glslName: String = "")

/** A constructor property that exists on the Kotlin value but is not part of its GPU representation. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GpuIgnore

/** The scalar/vector leaves understood by the first GPU-schema ABI. */
enum class GpuPrimitiveKind(
    val glslType: String,
    val alignmentBytes: Int,
    val sizeBytes: Int
) {
    FLOAT("float", 4, 4),
    INT("int", 4, 4),
    UINT("uint", 4, 4),
    INT64("int64_t", 8, 8),
    UINT64("uint64_t", 8, 8),
    VEC2("vec2", 8, 8),
    // std430 vec3 has base alignment 16 but occupies 12 bytes; a following scalar may sit at +12.
    VEC3("vec3", 16, 12),
    VEC4("vec4", 16, 16)
}

sealed class GpuFieldType {
    abstract val alignmentBytes: Int
    abstract val sizeBytes: Int
    abstract val glslType: String
    internal abstract val signature: String

    data class Primitive(val kind: GpuPrimitiveKind) : GpuFieldType() {
        override val alignmentBytes: Int get() = kind.alignmentBytes
        override val sizeBytes: Int get() = kind.sizeBytes
        override val glslType: String get() = kind.glslType
        override val signature: String get() = kind.name
    }

    data class Struct(val schema: GpuSchema<*>) : GpuFieldType() {
        override val alignmentBytes: Int get() = schema.alignmentBytes
        override val sizeBytes: Int get() = schema.sizeBytes
        override val glslType: String get() = schema.glslName
        override val signature: String get() = "STRUCT(${schema.abiSignature})"
    }
}

data class GpuField(
    val name: String,
    val offsetBytes: Int,
    val type: GpuFieldType,
    internal val property: KProperty1<Any, *>
) {
    val sizeBytes: Int get() = type.sizeBytes
    val alignmentBytes: Int get() = type.alignmentBytes
}

/**
 * One reflected, deterministic std430-compatible memory schema.
 *
 * Constructor-property order is the ABI order. The schema owns all offset/stride calculations used
 * by both CPU packing and generated GLSL, so the two sides cannot silently acquire independent
 * layout implementations.
 */
class GpuSchema<T : Any> internal constructor(
    val kotlinClass: KClass<T>,
    val glslName: String,
    val fields: List<GpuField>,
    val alignmentBytes: Int,
    val sizeBytes: Int
) {
    /** Struct arrays use the struct's rounded size as their std430 stride. */
    val strideBytes: Int = sizeBytes

    /** `buffer_reference_align` is a promise made by the shader; 16 is a conservative BDA baseline. */
    val referenceAlignmentBytes: Int = maxOf(16, alignmentBytes)

    val abiSignature: String by lazy {
        "$glslName[$alignmentBytes,$sizeBytes]:" +
            fields.joinToString(";") { "${it.name}@${it.offsetBytes}:${it.type.signature}" }
    }

    val abiHash: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(abiSignature.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun field(name: String): GpuField =
        fields.firstOrNull { it.name == name } ?: error("$glslName has no GPU field '$name'")

    fun offsetOf(name: String): Int = field(name).offsetBytes

    fun offsetOf(property: KProperty1<T, *>): Int = offsetOf(property.name)

    fun addressOf(baseAddress: Long, index: Int = 0): Long =
        baseAddress + index.toLong() * strideBytes.toLong()

    fun addressOf(baseAddress: Long, index: Int, field: String): Long =
        addressOf(baseAddress, index) + offsetOf(field)

    /** Pack one value without changing [target]'s position or byte order. */
    fun write(value: T, target: ByteBuffer, baseOffset: Int = 0) {
        writeAny(value, target, baseOffset)
    }

    /** Pack values consecutively at [strideBytes] spacing. Returns the number written. */
    fun writeArray(values: Iterable<T>, target: ByteBuffer, baseOffset: Int = 0): Int {
        var i = 0
        for (value in values) {
            write(value, target, baseOffset + i * strideBytes)
            i++
        }
        return i
    }

    fun arrayView(target: ByteBuffer, count: Int, baseOffset: Int = 0): GpuArrayView<T> =
        GpuArrayView(this, target, count, baseOffset)

    /** Adapter for `Structure.bindAll { ... }`, including whole push-constant blocks. */
    fun writer(value: () -> T): WritesToBuffer = object : WritesToBuffer {
        override fun writeTo(b: ByteBuffer, baseOffset: Int) {
            write(value(), b, baseOffset)
        }
    }

    /** All regular struct declarations needed by this schema, nested dependencies first. */
    fun glslStructDeclarations(): String =
        declarationOrder().joinToString("\n\n") { it.renderStruct() }

    fun glslReferenceDeclaration(): String = buildString {
        append("layout(buffer_reference, std430, buffer_reference_align = ")
        append(referenceAlignmentBytes)
        append(") buffer ")
        append(glslName)
        append("Ref {\n    ")
        append(glslName)
        append(" value;\n};")
    }

    fun glslArrayReferenceDeclaration(): String = buildString {
        append("layout(buffer_reference, std430, buffer_reference_align = ")
        append(referenceAlignmentBytes)
        append(") buffer ")
        append(glslName)
        append("Array {\n    ")
        append(glslName)
        append(" elements[];\n};")
    }

    /**
     * GLSL intended for `{{ Particle.all }}` directly after `#version`.
     *
     * GLSL still exposes typed `buffer_reference` names because that is the pleasant source-language
     * spelling available today. The Kotlin ABI intentionally remains `address + schema`, so a later
     * SPIR-V backend can lower the same schema to `SPV_KHR_untyped_pointers` without changing user data.
     */
    fun glslDeclarations(): String = buildString {
        appendLine("#extension GL_EXT_buffer_reference : require")
        if (usesExplicitInt64()) appendLine("#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require")
        appendLine()
        appendLine(glslStructDeclarations())
        appendLine()
        appendLine(glslReferenceDeclaration())
        appendLine()
        append(glslArrayReferenceDeclaration())
    }

    /** A value suitable for `shaderTemplate["Particle"] = schema.template`. */
    val template: Map<String, Any>
        get() = linkedMapOf(
            "struct" to glslStructDeclarations(),
            "ref" to glslReferenceDeclaration(),
            "array" to glslArrayReferenceDeclaration(),
            "all" to glslDeclarations(),
            "size" to sizeBytes,
            "stride" to strideBytes,
            "alignment" to alignmentBytes,
            "abi" to abiHash
        )

    internal fun writeAny(value: Any, target: ByteBuffer, baseOffset: Int) {
        require(baseOffset >= 0 && baseOffset + sizeBytes <= target.capacity()) {
            "Writing $glslName ($sizeBytes bytes) at $baseOffset exceeds ByteBuffer capacity ${target.capacity()}"
        }
        val out = target.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (field in fields) {
            val member = field.property.get(value)
                ?: error("GPU field '$glslName.${field.name}' may not be null")
            writeField(field.type, member, out, baseOffset + field.offsetBytes)
        }
    }

    private fun declarationOrder(): List<GpuSchema<*>> {
        val result = mutableListOf<GpuSchema<*>>()
        val seen = mutableSetOf<KClass<*>>()
        fun visit(schema: GpuSchema<*>) {
            if (!seen.add(schema.kotlinClass)) return
            schema.fields.forEach { field ->
                (field.type as? GpuFieldType.Struct)?.let { visit(it.schema) }
            }
            result += schema
        }
        visit(this)
        return result
    }

    private fun renderStruct(): String = buildString {
        append("struct ")
        append(glslName)
        appendLine(" {")
        fields.forEach { field ->
            append("    ")
            append(field.type.glslType)
            append(' ')
            append(field.name)
            append("; // +")
            append(field.offsetBytes)
            appendLine()
        }
        append("};")
    }

    private fun usesExplicitInt64(): Boolean = declarationOrder().any { schema ->
        schema.fields.any { field ->
            val primitive = field.type as? GpuFieldType.Primitive
            primitive?.kind == GpuPrimitiveKind.INT64 || primitive?.kind == GpuPrimitiveKind.UINT64
        }
    }
}

/** A CPU view over an array whose stride is governed by one [GpuSchema]. */
class GpuArrayView<T : Any> internal constructor(
    val schema: GpuSchema<T>,
    private val target: ByteBuffer,
    val count: Int,
    val baseOffset: Int
) {
    init {
        require(count >= 0) { "GPU array count must be non-negative" }
        require(baseOffset >= 0 && baseOffset + count * schema.strideBytes <= target.capacity()) {
            "GPU array of $count ${schema.glslName} values needs ${count * schema.strideBytes} bytes at $baseOffset; " +
                "buffer capacity is ${target.capacity()}"
        }
    }

    operator fun set(index: Int, value: T) {
        checkIndex(index)
        schema.write(value, target, baseOffset + index * schema.strideBytes)
    }

    fun byteOffsetOf(index: Int): Int {
        checkIndex(index)
        return baseOffset + index * schema.strideBytes
    }

    fun addressOf(baseAddress: Long, index: Int): Long {
        checkIndex(index)
        return baseAddress + byteOffsetOf(index).toLong()
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until count) { "GPU array index $index is outside 0 until $count" }
    }
}

/** Bind a reflected Field [Structure] (including a push-constant block) from one schema value. */
fun <T : Any> Structure.bindGpu(schema: GpuSchema<T>, value: () -> T) {
    val writer = schema.writer(value)
    bindAll { writer }
}

inline fun <reified T : Any> gpuSchema(): GpuSchema<T> = gpuSchema(T::class)

fun <T : Any> gpuSchema(type: KClass<T>): GpuSchema<T> = GpuSchemas.schema(type)

private object GpuSchemas {
    private val cache = ConcurrentHashMap<KClass<*>, GpuSchema<*>>()
    private val building = ThreadLocal.withInitial { LinkedHashSet<KClass<*>>() }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> schema(type: KClass<T>): GpuSchema<T> {
        cache[type]?.let { return it as GpuSchema<T> }
        synchronized(cache) {
            cache[type]?.let { return it as GpuSchema<T> }
            val active = building.get()
            check(active.add(type)) {
                "Recursive inline @GpuStruct layout involving ${type.qualifiedName}; use a GPU address for graph edges instead"
            }
            try {
                return build(type).also { cache[type] = it } as GpuSchema<T>
            } finally {
                active.remove(type)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun build(type: KClass<*>): GpuSchema<*> {
        val annotation = type.findAnnotation<GpuStruct>()
            ?: error("${type.qualifiedName} is not annotated @GpuStruct")
        val name = annotation.glslName.ifBlank {
            type.simpleName ?: error("Anonymous/local GPU structs need an explicit @GpuStruct(glslName = ...)")
        }
        require(GLSL_IDENTIFIER.matches(name)) { "'$name' is not a valid GLSL struct name" }

        val ctor = type.primaryConstructor
            ?: error("@GpuStruct ${type.qualifiedName} needs a primary constructor")
        val properties = type.memberProperties.associateBy { it.name }
        var offset = 0
        val fields = mutableListOf<GpuField>()

        for (parameter in ctor.parameters) {
            val propertyName = parameter.name ?: error("Unnamed constructor parameter in ${type.qualifiedName}")
            val property = properties[propertyName]
                ?: error("No property '$propertyName' for constructor parameter on ${type.qualifiedName}")
            if (property.findAnnotation<GpuIgnore>() != null) continue
            require(GLSL_IDENTIFIER.matches(propertyName)) { "'$propertyName' is not a valid GLSL field name" }

            val fieldType = inferType(property.returnType)
            offset = align(offset, fieldType.alignmentBytes)
            fields += GpuField(
                name = propertyName,
                offsetBytes = offset,
                type = fieldType,
                property = property as KProperty1<Any, *>
            )
            offset += fieldType.sizeBytes
        }

        val alignment = fields.maxOfOrNull { it.alignmentBytes } ?: 1
        val size = align(offset, alignment)
        return GpuSchema(
            kotlinClass = type as KClass<Any>,
            glslName = name,
            fields = fields,
            alignmentBytes = alignment,
            sizeBytes = size
        )
    }

    private fun inferType(type: KType): GpuFieldType {
        val classifier = type.classifier as? KClass<*>
            ?: error("Unsupported GPU field type $type")
        val primitive = when (classifier) {
            Float::class -> GpuPrimitiveKind.FLOAT
            Int::class -> GpuPrimitiveKind.INT
            UInt::class -> GpuPrimitiveKind.UINT
            Long::class -> GpuPrimitiveKind.INT64
            ULong::class -> GpuPrimitiveKind.UINT64
            Vector2f::class -> GpuPrimitiveKind.VEC2
            Vector3f::class -> GpuPrimitiveKind.VEC3
            Vector4f::class -> GpuPrimitiveKind.VEC4
            else -> null
        }
        if (primitive != null) return GpuFieldType.Primitive(primitive)
        if (classifier.findAnnotation<GpuStruct>() != null) {
            @Suppress("UNCHECKED_CAST")
            return GpuFieldType.Struct(schema(classifier as KClass<Any>))
        }
        error(
            "Unsupported GPU field type $type. First slice supports Float/Int/UInt/Long/ULong, " +
                "Vector2f/Vector3f/Vector4f, and nested @GpuStruct values."
        )
    }
}

private fun writeField(type: GpuFieldType, value: Any, out: ByteBuffer, offset: Int) {
    when (type) {
        is GpuFieldType.Struct -> type.schema.writeAny(value, out, offset)
        is GpuFieldType.Primitive -> when (type.kind) {
            GpuPrimitiveKind.FLOAT -> out.putFloat(offset, value as Float)
            GpuPrimitiveKind.INT -> out.putInt(offset, value as Int)
            GpuPrimitiveKind.UINT -> out.putInt(offset, (value as UInt).toInt())
            GpuPrimitiveKind.INT64 -> out.putLong(offset, value as Long)
            GpuPrimitiveKind.UINT64 -> out.putLong(offset, (value as ULong).toLong())
            GpuPrimitiveKind.VEC2 -> (value as Vector2f).let {
                out.putFloat(offset, it.x)
                out.putFloat(offset + 4, it.y)
            }
            GpuPrimitiveKind.VEC3 -> (value as Vector3f).let {
                out.putFloat(offset, it.x)
                out.putFloat(offset + 4, it.y)
                out.putFloat(offset + 8, it.z)
            }
            GpuPrimitiveKind.VEC4 -> (value as Vector4f).let {
                out.putFloat(offset, it.x)
                out.putFloat(offset + 4, it.y)
                out.putFloat(offset + 8, it.z)
                out.putFloat(offset + 12, it.w)
            }
        }
    }
}

private fun align(value: Int, alignment: Int): Int {
    require(alignment > 0 && alignment and (alignment - 1) == 0) { "Alignment must be a power of two" }
    return (value + alignment - 1) and -alignment
}

private val GLSL_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

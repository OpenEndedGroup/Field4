package afield.stdlib.linalg.codegen

class KotlinEmitter(
    private val model: AlgebraModel,
    private val transformModel: TransformAlgebraModel = FieldTransformAlgebraModel.build(),
) {
    fun emitHeapVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg")
        // Half vectors store raw binary16 bits and convert on every read/write; reuse Field's
        // existing, tested IEEE-754 binary16 codec rather than re-deriving it here.
        val usesHalf = model.heapVectors.any { it.scalar == ScalarKind.HALF }
        if (usesHalf) {
            appendLine("import afield.four.util.floatToHalfBits")
            appendLine("import afield.four.util.halfBitsToFloat")
        }
        appendLine("import java.nio.ByteBuffer")
        appendLine("import java.nio.DoubleBuffer")
        appendLine("import java.nio.FloatBuffer")
        if (usesHalf) appendLine("import java.nio.ShortBuffer")
        appendLine("import kotlin.math.*")
        appendLine()
        model.heapVectors.forEachIndexed { index, type ->
            emitReadInterface(type)
            appendLine()
            emitMutableClass(type)
            if (index != model.heapVectors.lastIndex) appendLine()
        }
    }

    fun emitArenaVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg.arena")
        appendLine("import afield.stdlib.linalg.*")
        appendLine("import kotlin.math.*")
        appendLine()
        model.vectors.forEach { type ->
            emitKDoc(
                ApiDocumentation(
                    "An allocation-free handle to a ${type.dimension}D vector stored in the enclosing FrameArena.",
                    listOf("The handle is only meaningful inside its FrameScope and becomes stale when that scope is restored or the arena is reset."),
                )
            )
            appendLine("@JvmInline")
            appendLine("value class ${type.arenaName} internal constructor(internal val token: Long)")
            appendLine()
        }
        appendLine("class FrameScope internal constructor(private val arena: FrameArena) {")
        model.vectors.forEach { type ->
            emitArenaType(type)
        }
        appendLine("}")
    }

    /** Emits core quaternion/matrix equations from the typed transform model. */
    fun emitTransformSemantics(): String = buildString {
        appendHeader("afield.stdlib.linalg")
        appendLine("import kotlin.math.*")
        transformModel.operations.forEach { operation ->
            appendLine()
            emitTransformOperation(operation)
        }
    }

    /** Emits non-owning vector views over foreign, direct, or mapped memory. */
    fun emitSegmentVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg.storage")
        appendLine("import afield.stdlib.linalg.*")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout")
        appendLine("import java.nio.ByteBuffer")
        appendLine("import java.util.concurrent.atomic.AtomicInteger")
        appendLine("import kotlin.math.*")
        appendLine()
        appendLine("private const val SEGMENT_OWNER_SHIFT = 40")
        appendLine("private const val SEGMENT_OFFSET_MASK = (1L shl SEGMENT_OWNER_SHIFT) - 1")
        appendLine("private const val MAX_SEGMENT_OWNER_ID = (1 shl (64 - SEGMENT_OWNER_SHIFT)) - 1")
        appendLine("private val segmentOwnerIds = AtomicInteger()")
        appendLine("private fun nextSegmentOwnerId(): Int = segmentOwnerIds.incrementAndGet().also {")
        appendLine("    check(it in 1..MAX_SEGMENT_OWNER_ID) { \"Segment vector buffer owner id space exhausted\" }")
        appendLine("}")
        appendLine("private fun packSegmentHandle(ownerId: Int, offset: Long): Long =")
        appendLine("    (ownerId.toLong() shl SEGMENT_OWNER_SHIFT) or offset")
        model.vectors.forEach { type ->
            appendLine()
            emitSegmentType(type)
        }
    }

    /**
     * Emits vectorized bulk kernels over flat per-component `FloatArray`s, using
     * `jdk.incubator.vector`. Each kernel lowers the same [Operation] AST the heap/arena/segment
     * backends use — one SIMD lane per array element (not per vector component), so every
     * elementwise or reduction-shaped operation is embarrassingly lane-parallel with no gather and
     * no masking. Gather/neighbor-indexed operations have no representation in this model and are
     * out of scope; see `SimdVerlet.kt` for that harder, hand-written case.
     */
    fun emitBulkVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg.bulk")
        appendLine("import jdk.incubator.vector.FloatVector")
        appendLine("import jdk.incubator.vector.VectorOperators")
        appendLine("import jdk.incubator.vector.VectorSpecies")
        appendLine("import kotlin.math.*")
        appendLine()
        appendLine("private val SP: VectorSpecies<Float> = FloatVector.SPECIES_PREFERRED")
        model.vectors.forEach { type ->
            appendLine()
            model.operations(type).forEach { operation ->
                emitBulkOperation(type, operation)
                appendLine()
            }
        }
    }

    /** Emits the exhaustive public vector reference from the same model as the implementations. */
    fun emitReferenceMarkdown(): String = buildString {
        appendLine("<!-- DO NOT EDIT — generated by tools/linalg-generator -->")
        appendLine("<!-- generator-version: 1; model-version: field-f32-v1 -->")
        appendLine("# Generated vector API reference")
        appendLine()
        appendLine("This page is generated from the typed algebra model that emits Field's heap, frame-arena, and `MemorySegment` vector implementations. For intuition and worked examples, start with [Linear algebra in Field](linear_algebra.md).")
        appendLine()
        appendLine("## One algebra, three storage forms")
        appendLine()
        appendLine("| Storage | Types | Lifetime and use |")
        appendLine("| --- | --- | --- |")
        appendLine("| Heap | `Vector2f`, `Vector3f`, `Vector4f` | Ordinary mutable values; persistent state and the normal case |")
        appendLine("| Frame arena | `ArenaVec2f`, `ArenaVec3f`, `ArenaVec4f` | Temporary values inside `frameScratch` or an explicit `FrameArena` scope |")
        appendLine("| Segment view | `SegmentVec2f`, `SegmentVec3f`, `SegmentVec4f` | Non-owning views into packed, aligned, direct, foreign, or mapped memory |")
        appendLine()
        appendLine("All destination overloads write into `dest`, return it, permit input/destination aliasing, and allocate no result.")
        appendLine()
        appendLine("Further forms share the same model: the heap types also come in double precision (`Vector2d`, `Vector3d`, `Vector4d`, heap-only) and half precision (`Vector8h`, heap-only — eight IEEE-754 binary16 lanes that pack into a single `vec4` of GPU memory), and the `afield.stdlib.linalg.bulk` package provides flat-`FloatArray` SIMD kernels (`addRange`, `normalizeRange`, …) with scalar- and broadcast-vector overloads for whole-array field operations.")
        model.heapVectors.forEach { type ->
            appendLine()
            appendLine("## ${type.heapName}")
            appendLine()
            appendLine(type.documentation.summary)
            appendLine()
            appendLine("Components: ${type.components.joinToString { "`${it.sourceName}`" }}.")
            appendLine()
            when (type.scalar) {
                ScalarKind.FLOAT ->
                    appendLine("In a Field code box, ordinary heap values use `vec(${type.components.joinToString { it.sourceName }})`. In a typed context, `val value: Vec${type.dimension} = [${type.components.indices.joinToString()}]` uses the generated collection-literal constructor. The frame-arena (`${type.arenaName}`) and segment (`SegmentVec${type.dimension}f`) forms carry the same operation surface.")
                ScalarKind.DOUBLE ->
                    appendLine("The double-precision, heap-only sibling of `Vector${type.dimension}f`; the arena, segment, and bulk forms are single-precision only.")
                ScalarKind.HALF ->
                    appendLine("The half-precision (IEEE-754 binary16), heap-only vector. Its eight lanes pack into 16 bytes — one GLSL `vec4` — through `get(index, buffer)`, so a push-constant block holds twice as many as the equivalent `Vector4f` pair would. Arithmetic is evaluated in `Float`; every result is stored back as binary16, so component reads return the rounded value.")
            }
            appendLine()
            appendLine("| Operation | Meaning |")
            appendLine("| --- | --- |")
            model.operations(type).filter { !it.bulkOnly }.forEach { operation ->
                val signature = buildString {
                    append(operation.name)
                    append('(')
                    append(orderedParameters(operation).joinToString { parameter ->
                        when (parameter) {
                            is VectorParameter -> "${parameter.name}: ${type.readName}"
                            is ScalarParameter -> "${parameter.name}: ${type.scalar.kotlinType}"
                        }
                    })
                    append(')')
                }
                val explanation = (listOf(operation.documentation.summary) + operation.documentation.details)
                    .joinToString(" ")
                    .replace("|", "\\|")
                appendLine("| `$signature` | $explanation |")
            }
            appendLine("| `isFinite()` | Reports whether every component is finite. |")
        }
        appendLine()
        appendLine("## Rotations and matrices")
        appendLine()
        appendLine("Alongside the vectors, the library provides rotation and matrix types whose core equations are generated from the same transform model and which interoperate with the heap vectors.")
        appendLine()
        appendLine("| Type | Purpose | Key operations |")
        appendLine("| --- | --- | --- |")
        appendLine("| `Quaternionf` | 3D rotation | `setAngleAxis`, `rotateX/Y/Z`, `mul`, `invert`, `conjugate`, `normalize`, `slerp`, `nlerp`, `rotationTo`, `lookAlong`, `transform(Vector3f)` |")
        appendLine("| `Matrix3f` | linear and normal transforms | `rotation`, `scale`, `mul`, `invert`, `transpose`, `determinant`, `transform(Vector3f)` |")
        appendLine("| `Matrix3d` | double-precision 3x3 for numerically sensitive code | `mul`, `invert`, `transpose`, `determinant` |")
        appendLine("| `Matrix4f` | affine, camera, and projection transforms | `translate`, `scale`, `rotate`, `mul`, `invert`, `transpose`, `perspective`, `ortho`, `frustum`, `lookAt`, `transformPosition`, `transformDirection` |")
        appendLine()
        appendLine("## Storage layouts")
        appendLine()
        appendLine("| Vector | Layout choices |")
        appendLine("| --- | --- |")
        appendLine("| Vec2 | `PACKED_8`, `ALIGNED_8`, `ALIGNED_16` |")
        appendLine("| Vec3 | `PACKED_12`, `ALIGNED_16` |")
        appendLine("| Vec4 | `PACKED_16`, `ALIGNED_16` |")
        appendLine()
        appendLine("A layout name describes both array stride and required base alignment. A segment view never owns the memory and never performs Vulkan synchronization.")
    }

    private fun StringBuilder.emitSegmentType(type: VectorType) {
        val dimension = type.dimension
        val handleName = "SegmentVec${dimension}f"
        val bufferName = "SegmentVec${dimension}Buffer"
        val layoutName = "Vec${dimension}StorageLayout"
        val valueBytes = dimension * Float.SIZE_BYTES
        val layoutEntries = when (dimension) {
            2 -> listOf("PACKED_8(8, 4)", "ALIGNED_8(8, 8)", "ALIGNED_16(16, 16)")
            3 -> listOf("PACKED_12(12, 4)", "ALIGNED_16(16, 16)")
            4 -> listOf("PACKED_16(16, 4)", "ALIGNED_16(16, 16)")
            else -> error("unsupported segment vector dimension $dimension")
        }
        val defaultLayout = if (dimension == 2) "ALIGNED_8" else "ALIGNED_16"

        emitKDoc(ApiDocumentation("Storage stride and base-alignment choices for arrays of ${type.heapName} values."))
        appendLine("enum class $layoutName(val strideBytes: Int, val requiredAlignmentBytes: Int) {")
        layoutEntries.forEach { appendLine("    $it,") }
        appendLine("}")
        appendLine()
        emitKDoc(ApiDocumentation("An allocation-free handle to one ${type.heapName} inside its SegmentVec${dimension}Buffer view."))
        appendLine("@JvmInline")
        appendLine("value class $handleName internal constructor(internal val token: Long)")
        appendLine()
        appendLine("/**")
        appendLine(" * A non-owning Vec$dimension view over a MemorySegment. The segment controls lifetime")
        appendLine(" * and thread accessibility; mapped Vulkan memory additionally requires normal")
        appendLine(" * Vulkan host/device synchronization. Handles are allocation-free and view-specific.")
        appendLine(" */")
        appendLine("class $bufferName(")
        appendLine("    private val segment: MemorySegment,")
        appendLine("    val count: Int,")
        appendLine("    val layout: $layoutName = $layoutName.$defaultLayout,")
        appendLine("    val baseOffsetBytes: Long = 0,")
        appendLine(") {")
        appendLine("    private val ownerId = nextSegmentOwnerId()")
        appendLine()
        appendLine("    init {")
        appendLine("        require(count >= 0) { \"count must be non-negative\" }")
        appendLine("        require(baseOffsetBytes >= 0) { \"base offset must be non-negative\" }")
        appendLine("        require(baseOffsetBytes % layout.requiredAlignmentBytes == 0L) {")
        appendLine("            \"base offset \$baseOffsetBytes is not aligned to \${layout.requiredAlignmentBytes} bytes\"")
        appendLine("        }")
        appendLine("        if (segment.isNative) require((segment.address() + baseOffsetBytes) % layout.requiredAlignmentBytes == 0L) {")
        appendLine("            \"native address plus base offset is not aligned to \${layout.requiredAlignmentBytes} bytes\"")
        appendLine("        }")
        appendLine("        val required = if (count == 0) baseOffsetBytes else Math.addExact(")
        appendLine("            Math.addExact(baseOffsetBytes, Math.multiplyExact((count - 1).toLong(), layout.strideBytes.toLong())),")
        appendLine("            ${valueBytes}L,")
        appendLine("        )")
        appendLine("        require(required <= segment.byteSize()) {")
        appendLine("            \"Vec$dimension view needs \$required bytes but segment contains \${segment.byteSize()}\"")
        appendLine("        }")
        appendLine("        require(required <= SEGMENT_OFFSET_MASK) { \"Segment vector views are limited to \$SEGMENT_OFFSET_MASK bytes\" }")
        appendLine("    }")
        appendLine()
        appendLine("    operator fun get(index: Int): $handleName {")
        appendLine("        require(index in 0 until count) { \"index \$index outside 0..<\$count\" }")
        appendLine("        return $handleName(packSegmentHandle(ownerId, baseOffsetBytes + index.toLong() * layout.strideBytes))")
        appendLine("    }")
        appendLine()
        appendLine("    fun get(index: Int, dest: ${type.heapName}): ${type.heapName} = get(index).copyTo(dest)")
        appendLine("    fun set(index: Int, value: ${type.readName}): $bufferName { get(index).set(value); return this }")
        appendLine()
        type.components.forEachIndexed { index, component ->
            appendLine("    var $handleName.${component.sourceName}: Float")
            appendLine("        get() = read(token, $index)")
            appendLine("        set(value) = write(token, $index, value)")
        }
        appendLine()
        appendLine("    fun $handleName.set(${type.components.joinToString { "${it.sourceName}: Float" }}): $handleName {")
        type.components.forEachIndexed { index, component ->
            appendLine("        write(token, $index, ${component.sourceName})")
        }
        appendLine("        var paddingOffset = $valueBytes")
        appendLine("        while (paddingOffset < layout.strideBytes) {")
        appendLine("            segment.set(ValueLayout.JAVA_FLOAT, offset(token) + paddingOffset, 0f)")
        appendLine("            paddingOffset += Float.SIZE_BYTES")
        appendLine("        }")
        appendLine("        return this")
        appendLine("    }")
        appendLine("    fun $handleName.set(other: ${type.readName}): $handleName = set(${type.components.joinToString { "other.${it.sourceName}()" }})")
        appendLine("    fun $handleName.copyTo(dest: ${type.heapName}): ${type.heapName} = dest.set(${type.components.joinToString { it.sourceName }})")
        appendLine()
        model.operations(type).filter { !it.bulkOnly }.forEach { operation ->
            emitSegmentOperation(type, operation)
            appendLine()
        }
        emitKDoc(ApiDocumentation("Reports whether every component is finite."), "    ")
        appendLine("    fun $handleName.isFinite(): Boolean = ${type.components.joinToString(" && ") { "${it.sourceName}.isFinite()" }}")
        appendLine()
        appendLine("    private fun read(token: Long, component: Int): Float =")
        appendLine("        segment.get(ValueLayout.JAVA_FLOAT, offset(token) + component * Float.SIZE_BYTES)")
        appendLine("    private fun write(token: Long, component: Int, value: Float) =")
        appendLine("        segment.set(ValueLayout.JAVA_FLOAT, offset(token) + component * Float.SIZE_BYTES, value)")
        appendLine("    private fun offset(token: Long): Long {")
        appendLine("        check((token ushr SEGMENT_OWNER_SHIFT).toInt() == ownerId) { \"$handleName belongs to another buffer view\" }")
        appendLine("        return token and SEGMENT_OFFSET_MASK")
        appendLine("    }")
        appendLine()
        appendLine("    companion object {")
        appendLine("        fun of(")
        appendLine("            buffer: ByteBuffer,")
        appendLine("            count: Int,")
        appendLine("            layout: $layoutName = $layoutName.$defaultLayout,")
        appendLine("        ): $bufferName = $bufferName(MemorySegment.ofBuffer(buffer.slice()), count, layout)")
        appendLine("    }")
        appendLine("}")
    }

    private fun StringBuilder.appendHeader(packageName: String) {
        appendLine("// DO NOT EDIT — generated by tools/linalg-generator")
        appendLine("// generator-version: 1")
        appendLine("// model-version: field-f32-v1")
        appendLine("package $packageName")
        appendLine()
    }

    private fun StringBuilder.emitKDoc(documentation: ApiDocumentation, indent: String = "") {
        appendLine("${indent}/**")
        appendLine("$indent * ${documentation.summary}")
        documentation.details.forEach { detail ->
            appendLine("$indent *")
            appendLine("$indent * $detail")
        }
        documentation.parameters.forEach { (name, description) -> appendLine("$indent * @param $name $description") }
        documentation.returns?.let { appendLine("$indent * @return $it") }
        appendLine("$indent */")
    }

    private fun StringBuilder.emitReadInterface(type: VectorType) {
        val scalar = type.scalar.kotlinType
        emitKDoc(
            type.documentation.copy(
                summary = "A read-only view of a ${type.dimension}D vector of ${type.scalar.bits}-bit floating-point components.",
                details = type.documentation.details + "Methods with a dest parameter write into that destination and do not allocate a result.",
            )
        )
        appendLine("interface ${type.readName} {")
        type.components.forEach { component ->
            appendLine("    fun ${component.sourceName}(): $scalar")
        }
        appendLine()
        appendLine("    operator fun get(component: Int): $scalar = when (component) {")
        type.components.forEachIndexed { index, component ->
            appendLine("        $index -> ${component.sourceName}()")
        }
        appendLine("        else -> throw IndexOutOfBoundsException(\"component \$component outside 0..${type.dimension - 1}\")")
        appendLine("    }")
        appendLine()
        model.operations(type).filter { !it.bulkOnly }.forEach { operation ->
            emitHeapInterfaceOperation(type, operation)
            appendLine()
        }
        emitKDoc(ApiDocumentation("Reports whether every component is finite."), "    ")
        appendLine("    fun isFinite(): Boolean = ${type.components.joinToString(" && ") { "${it.sourceName}().isFinite()" }}")
        appendLine("    fun equals(other: ${type.readName}, delta: $scalar): Boolean =")
        appendLine("        ${type.components.joinToString(" && ") { "abs(${it.sourceName}() - other.${it.sourceName}()) <= delta" }}")
        appendLine()
        appendLine("    fun get(buffer: ${type.scalar.bufferType}): ${type.scalar.bufferType} {")
        appendLine("        val index = buffer.position()")
        type.components.forEachIndexed { index, component ->
            appendLine("        buffer.put(index + $index, ${type.scalar.toStorage("${component.sourceName}()")})")
        }
        appendLine("        return buffer")
        appendLine("    }")
        appendLine()
        appendLine("    fun get(index: Int, buffer: ByteBuffer): ByteBuffer {")
        type.components.forEachIndexed { index, component ->
            appendLine("        buffer.${type.scalar.bufferPut}(index + ${index * type.scalar.byteSize}, ${type.scalar.toStorage("${component.sourceName}()")})")
        }
        appendLine("        return buffer")
        appendLine("    }")
        appendLine("}")
    }

    private fun StringBuilder.emitHeapInterfaceOperation(type: VectorType, operation: Operation) {
        val parameters = buildList {
            orderedParameters(operation).forEach {
                when (it) {
                    is VectorParameter -> add("${it.name}: ${type.readName}")
                    is ScalarParameter -> add("${it.name}: ${type.scalar.kotlinType}")
                }
            }
            if (operation.result == OperationResult.Destination) add("dest: ${type.heapName}")
        }.joinToString(", ")
        emitKDoc(documentationFor(operation), "    ")
        when (val result = operation.result) {
            OperationResult.Destination -> {
                appendLine("    fun ${operation.name}($parameters): ${type.heapName} {")
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderHeap(expression)}")
                }
                appendLine("        return dest.set(${operation.componentExpressions.indices.joinToString { "r$it" }})")
                appendLine("    }")
            }
            is OperationResult.Scalar ->
                appendLine("    fun ${operation.name}($parameters): ${type.scalar.kotlinType} = ${renderHeap(result.expression)}")
        }
    }

    private fun StringBuilder.emitMutableClass(type: VectorType) {
        val scalar = type.scalar.kotlinType
        val zero = type.scalar.zero
        val zeroes = List(type.dimension) { zero }
        emitKDoc(type.documentation)
        appendLine("open class ${type.heapName}() : ${type.readName}, Cloneable {")
        type.components.forEach { component -> appendLine("    @JvmField var ${component.sourceName}: ${type.scalar.storageType} = ${type.scalar.storageZero}") }
        appendLine()
        appendLine("    constructor(s: $scalar) : this() { set(${List(type.dimension) { "s" }.joinToString()}) }")
        appendLine(
            "    constructor(${type.components.joinToString { "${it.sourceName}: $scalar" }}) : this() { " +
                "set(${type.components.joinToString { it.sourceName }}) }"
        )
        appendLine("    constructor(other: ${type.readName}) : this() { set(other) }")
        if (type.dimension == 3) {
            appendLine("    constructor(xy: ${type.scalar.readName(2)}, z: $scalar) : this(xy.x(), xy.y(), z)")
        }
        if (type.dimension == 4) {
            appendLine("    constructor(xyz: ${type.scalar.readName(3)}, w: $scalar) : this(xyz.x(), xyz.y(), xyz.z(), w)")
            appendLine("    constructor(xy: ${type.scalar.readName(2)}, z: $scalar, w: $scalar) : this(xy.x(), xy.y(), z, w)")
        }
        appendLine()
        type.components.forEach { component ->
            appendLine("    override fun ${component.sourceName}(): $scalar = ${type.scalar.fromStorage(component.sourceName)}")
        }
        appendLine()
        appendLine("    operator fun set(component: Int, value: $scalar) {")
        appendLine("        when (component) {")
        type.components.forEachIndexed { index, component -> appendLine("            $index -> ${component.sourceName} = ${type.scalar.toStorage("value")}") }
        appendLine("            else -> throw IndexOutOfBoundsException(\"component \$component outside 0..${type.dimension - 1}\")")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    fun set(s: $scalar): ${type.heapName} = set(${List(type.dimension) { "s" }.joinToString()})")
        appendLine(
            "    fun set(${type.components.joinToString { "${it.sourceName}: $scalar" }}): ${type.heapName} {"
        )
        type.components.forEach { appendLine("        this.${it.sourceName} = ${type.scalar.toStorage(it.sourceName)}") }
        appendLine("        return this")
        appendLine("    }")
        appendLine(
            "    fun set(other: ${type.readName}): ${type.heapName} = " +
                "set(${type.components.joinToString { "other.${it.sourceName}()" }})"
        )
        appendLine("    fun zero(): ${type.heapName} = set(${zeroes.joinToString()})")
        appendLine(
            "    fun add(${type.components.joinToString { "${it.sourceName}: $scalar" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "${type.scalar.fromStorage("this.${it.sourceName}")} + ${it.sourceName}" }})"
        )
        appendLine(
            "    fun sub(${type.components.joinToString { "${it.sourceName}: $scalar" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "${type.scalar.fromStorage("this.${it.sourceName}")} - ${it.sourceName}" }})"
        )
        appendLine(
            "    fun mul(${type.components.joinToString { "${it.sourceName}: $scalar" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "${type.scalar.fromStorage("this.${it.sourceName}")} * ${it.sourceName}" }})"
        )
        appendLine(
            "    fun div(${type.components.joinToString { "${it.sourceName}: $scalar" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "${type.scalar.fromStorage("this.${it.sourceName}")} / ${it.sourceName}" }})"
        )
        appendLine()
        model.operations(type).filter { it.result == OperationResult.Destination && !it.bulkOnly }.forEach { operation ->
            val parameters = buildList {
                orderedParameters(operation).forEach {
                    when (it) {
                        is VectorParameter -> add("${it.name}: ${type.readName}")
                        is ScalarParameter -> add("${it.name}: ${type.scalar.kotlinType}")
                    }
                }
            }.joinToString(", ")
            val arguments = buildList {
                orderedParameters(operation).forEach {
                    add(
                        when (it) {
                            is VectorParameter -> it.name
                            is ScalarParameter -> it.name
                        }
                    )
                }
                add("this")
            }.joinToString(", ")
            val modifier = if (operation.name == "div") "operator " else ""
            emitKDoc(
                documentationFor(operation, includeDestination = false).copy(
                    details = operation.documentation.details + "This overload writes the result back into this vector.",
                    returns = "this vector",
                ),
                "    ",
            )
            appendLine("    ${modifier}fun ${operation.name}($parameters): ${type.heapName} = ${operation.name}($arguments)")
        }
        appendLine()
        appendLine("    public override fun clone(): ${type.heapName} = ${type.heapName}(this)")
        appendLine("    override fun equals(other: Any?): Boolean = other is ${type.readName} && ${type.components.joinToString(" && ") { type.scalar.equalsComponent(it.sourceName, "other.${it.sourceName}()") }}")
        appendLine("    override fun hashCode(): Int {")
        appendLine("        var result = 1")
        type.components.forEach { appendLine("        result = 31 * result + ${type.scalar.hashComponent(it.sourceName)}") }
        appendLine("        return result")
        appendLine("    }")
        // Fields hold the compute value except for packed kinds, where toString must go through the
        // accessor so it prints the decoded value rather than the raw stored bits.
        val stringComponent: (Component) -> String =
            if (type.scalar.storageType == scalar) { c -> "\$${c.sourceName}" } else { c -> "\${${c.sourceName}()}" }
        appendLine("    override fun toString(): String = \"(${type.components.joinToString(", ") { stringComponent(it) }})\"")
        appendLine()
        appendLine("    companion object {")
        appendLine("        /**")
        appendLine("         * Constructs a ${type.dimension}D vector from a typed Kotlin collection literal.")
        appendLine("         *")
        appendLine("         * Example: `val value: ${type.heapName} = [${type.components.indices.joinToString()}]`.")
        appendLine("         * Numeric components are converted to ${type.scalar.kotlinType}, matching Field's `vec(...)` helper.")
        appendLine("         */")
        appendLine("        operator fun of(vararg components: Number): ${type.heapName} {")
        appendLine("            require(components.size == ${type.dimension}) { \"${type.heapName} literal requires ${type.dimension} components, got \${components.size}\" }")
        appendLine("            return ${type.heapName}(${type.components.indices.joinToString { "components[$it].${type.scalar.numberConversion}()" }})")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
    }

    private fun StringBuilder.emitArenaType(type: VectorType) {
        val kind = "FrameArena.Kind.VEC${type.dimension}"
        appendLine()
        appendLine("    fun ${type.factoryName}(): ${type.arenaName} = ${type.arenaName}(arena.allocateVector($kind))")
        appendLine(
            "    fun ${type.factoryName}(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.arenaName} = " +
                "${type.factoryName}().also { it.set(${type.components.joinToString { it.sourceName }}) }"
        )
        appendLine("    /** Creates an arena-backed ${type.dimension}D vector from Kotlin numeric values. */")
        appendLine(
            "    fun vec(${type.components.joinToString { "${it.sourceName}: Number" }}): ${type.arenaName} = " +
                "${type.factoryName}(${type.components.joinToString { "${it.sourceName}.toFloat()" }})"
        )
        appendLine()
        type.components.forEachIndexed { index, component ->
            appendLine("    var ${type.arenaName}.${component.sourceName}: Float")
            appendLine("        get() = arena.read(token, $index, $kind)")
            appendLine("        set(value) = arena.write(token, $index, value, $kind)")
        }
        appendLine()
        appendLine(
            "    fun ${type.arenaName}.set(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.arenaName} {"
        )
        type.components.forEachIndexed { index, component ->
            appendLine("        arena.write(token, $index, ${component.sourceName}, $kind)")
        }
        appendLine("        return this")
        appendLine("    }")
        appendLine(
            "    fun ${type.arenaName}.set(other: ${type.readName}): ${type.arenaName} = " +
                "set(${type.components.joinToString { "other.${it.sourceName}()" }})"
        )
        appendLine(
            "    fun ${type.heapName}.set(other: ${type.arenaName}): ${type.heapName} = " +
                "set(${type.components.joinToString { "other.${it.sourceName}" }})"
        )
        appendLine("    fun ${type.arenaName}.copyTo(dest: ${type.heapName}): ${type.heapName} = dest.set(this)")
        appendLine()
        model.operations(type).filter { !it.bulkOnly }.forEach { operation ->
            emitArenaOperation(type, operation, kind)
            appendLine()
        }
        emitKDoc(ApiDocumentation("Reports whether every component is finite."), "    ")
        appendLine("    fun ${type.arenaName}.isFinite(): Boolean = ${type.components.joinToString(" && ") { "${it.sourceName}.isFinite()" }}")
    }

    private fun StringBuilder.emitArenaOperation(type: VectorType, operation: Operation, kind: String) {
        val parameters = buildList {
            orderedParameters(operation).forEach {
                when (it) {
                    is VectorParameter -> add("${it.name}: ${type.arenaName}")
                    is ScalarParameter -> add("${it.name}: Float")
                }
            }
            if (operation.result == OperationResult.Destination) add("dest: ${type.arenaName}")
        }.joinToString(", ")
        // Each handle is validated once per operation via arena.resolve(...); component reads and
        // writes then use the unchecked readAt/writeAt. This keeps the arena's use-after-reset,
        // ownership and kind guarantees while removing per-component re-validation from the hot path.
        fun StringBuilder.emitResolves() {
            appendLine("        val o_self = arena.resolve(token, $kind)")
            operation.vectorParameters.forEach {
                appendLine("        val o_${it.name} = arena.resolve(${it.name}.token, $kind)")
            }
        }
        // Read each referenced component exactly once. The same component often appears in several
        // result lanes (a shared length term, a cross product), so hoisting reads collapses repeated
        // MemorySegment loads the JIT does not reliably common up on its own.
        fun StringBuilder.emitReadLocals(expressions: List<Expr>) {
            collectReads(expressions).forEach { (source, component) ->
                appendLine("        val ${localReadName(source, component)} = arena.readAt(${offsetVarName(source)}, ${component.ordinal})")
            }
        }
        emitKDoc(documentationFor(operation), "    ")
        when (val result = operation.result) {
            OperationResult.Destination -> {
                appendLine("    fun ${type.arenaName}.${operation.name}($parameters): ${type.arenaName} {")
                emitResolves()
                emitReadLocals(operation.componentExpressions)
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderArenaLocals(expression)}")
                }
                appendLine("        val o_dest = arena.resolve(dest.token, $kind)")
                operation.componentExpressions.indices.forEach { index ->
                    appendLine("        arena.writeAt(o_dest, $index, r$index)")
                }
                appendLine("        return dest")
                appendLine("    }")
                val receiverParameters = buildList {
                    orderedParameters(operation).forEach {
                        when (it) {
                            is VectorParameter -> add("${it.name}: ${type.arenaName}")
                            is ScalarParameter -> add("${it.name}: Float")
                        }
                    }
                }.joinToString(", ")
                val receiverArguments = buildList {
                    orderedParameters(operation).forEach {
                        add(
                            when (it) {
                                is VectorParameter -> it.name
                                is ScalarParameter -> it.name
                            }
                        )
                    }
                    add("this")
                }.joinToString(", ")
                appendLine("    fun ${type.arenaName}.${operation.name}($receiverParameters): ${type.arenaName} = ${operation.name}($receiverArguments)")
            }
            is OperationResult.Scalar -> {
                appendLine("    fun ${type.arenaName}.${operation.name}($parameters): Float {")
                emitResolves()
                emitReadLocals(listOf(result.expression))
                appendLine("        return ${renderArenaLocals(result.expression)}")
                appendLine("    }")
            }
        }
    }

    private fun StringBuilder.emitSegmentOperation(type: VectorType, operation: Operation) {
        val segmentName = "SegmentVec${type.dimension}f"
        val parameters = buildList {
            orderedParameters(operation).forEach {
                when (it) {
                    is VectorParameter -> add("${it.name}: $segmentName")
                    is ScalarParameter -> add("${it.name}: Float")
                }
            }
            if (operation.result == OperationResult.Destination) add("dest: $segmentName")
        }.joinToString(", ")
        emitKDoc(documentationFor(operation), "    ")
        when (val result = operation.result) {
            OperationResult.Destination -> {
                appendLine("    fun $segmentName.${operation.name}($parameters): $segmentName {")
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderSegment(expression)}")
                }
                appendLine("        return dest.set(${operation.componentExpressions.indices.joinToString { "r$it" }})")
                appendLine("    }")
                val receiverParameters = orderedParameters(operation).joinToString(", ") {
                    when (it) {
                        is VectorParameter -> "${it.name}: $segmentName"
                        is ScalarParameter -> "${it.name}: Float"
                    }
                }
                val receiverArguments = orderedParameters(operation).joinToString(", ") { it.name }
                val comma = if (receiverArguments.isEmpty()) "" else "$receiverArguments, "
                appendLine("    fun $segmentName.${operation.name}($receiverParameters): $segmentName = ${operation.name}(${comma}this)")
            }
            is OperationResult.Scalar ->
                appendLine("    fun $segmentName.${operation.name}($parameters): Float = ${renderSegment(result.expression)}")
        }
    }

    private fun StringBuilder.emitBulkOperation(type: VectorType, operation: Operation) {
        fun arrayParams(prefix: String) = type.components.map { "$prefix${it.sourceName.uppercase()}: FloatArray" }
        fun arrayNames(prefix: String) = type.components.map { "$prefix${it.sourceName.uppercase()}" }

        val vectorArrayNames = buildList {
            addAll(arrayNames("self"))
            orderedParameters(operation).forEach { if (it is VectorParameter) addAll(arrayNames(it.name)) }
        }
        val outputArrayNames = when (operation.result) {
            OperationResult.Destination -> arrayNames("dest")
            is OperationResult.Scalar -> listOf("resultOut")
        }

        val parameters = buildList {
            addAll(arrayParams("self"))
            orderedParameters(operation).forEach {
                when (it) {
                    is VectorParameter -> addAll(arrayParams(it.name))
                    is ScalarParameter -> add("${it.name}: Float")
                }
            }
            when (operation.result) {
                OperationResult.Destination -> addAll(arrayParams("dest"))
                is OperationResult.Scalar -> add("resultOut: FloatArray")
            }
            add("start: Int")
            add("endExclusive: Int")
        }.joinToString(", ")

        val resultExpressions = when (val result = operation.result) {
            OperationResult.Destination -> operation.componentExpressions
            is OperationResult.Scalar -> listOf(result.expression)
        }

        emitKDoc(documentationFor(operation), "")
        appendLine("fun ${operation.name}Range($parameters) {")
        appendLine("    require(start in 0..endExclusive) { \"start \$start must be in 0..\$endExclusive\" }")
        (vectorArrayNames + outputArrayNames).forEach { name ->
            appendLine("    require($name.size >= endExclusive) { \"$name has size \${$name.size}, must be >= \$endExclusive\" }")
        }
        appendLine("    val lanes = SP.length()")
        appendLine("    var i = start")
        appendLine("    while (i + lanes <= endExclusive) {")
        collectReads(resultExpressions).forEach { (source, component) ->
            appendLine("        val ${localReadName(source, component)} = FloatVector.fromArray(SP, ${bulkArrayName(source, component)}, i)")
        }
        val substitutions = linkedMapOf<Expr, String>()
        collectSharedSubexpressions(resultExpressions).forEachIndexed { index, expression ->
            appendLine("        val shared$index = ${renderBulk(expression, substitutions)}")
            substitutions[expression] = "shared$index"
        }
        when (operation.result) {
            OperationResult.Destination -> {
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderBulk(expression, substitutions)}")
                }
                outputArrayNames.forEachIndexed { index, name -> appendLine("        r$index.intoArray($name, i)") }
            }
            is OperationResult.Scalar -> {
                appendLine("        val r0 = ${renderBulk(resultExpressions[0], substitutions)}")
                appendLine("        r0.intoArray(resultOut, i)")
            }
        }
        appendLine("        i += lanes")
        appendLine("    }")
        appendLine("    while (i < endExclusive) {")
        when (operation.result) {
            OperationResult.Destination -> {
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val t$index = ${renderBulkScalarTail(expression)}")
                }
                outputArrayNames.forEachIndexed { index, name -> appendLine("        $name[i] = t$index") }
            }
            is OperationResult.Scalar -> appendLine("        resultOut[i] = ${renderBulkScalarTail(resultExpressions[0])}")
        }
        appendLine("        i++")
        appendLine("    }")
        appendLine("}")
    }

    private fun StringBuilder.emitTransformOperation(operation: TransformOperation) {
        // Every operation is single-precision unless its receiver shape says otherwise; that scalar
        // kind drives constant literals and the scalar parameter/return types.
        val scalar = operation.receiver.scalar
        val parameters = buildList {
            add("self: ${operation.receiver.readType}")
            operation.parameters.forEach { parameter ->
                add(
                    when (parameter) {
                        is ShapeParameter -> "${parameter.name}: ${parameter.shape.readType}"
                        is TransformScalarParameter -> "${parameter.name}: ${scalar.kotlinType}"
                    }
                )
            }
            if (operation.result is TransformResult.Destination) add("dest: ${operation.result.shape.mutableType}")
        }.joinToString(", ")
        val returnType = when (val result = operation.result) {
            is TransformResult.Destination -> result.shape.mutableType
            is TransformResult.Scalar -> scalar.kotlinType
        }
        appendLine("internal fun ${operation.functionName}($parameters): $returnType {")
        operation.locals.forEach { value ->
            appendLine("    val ${value.name} = ${renderTransform(value.expression, scalar)}")
        }
        when (val result = operation.result) {
            is TransformResult.Destination -> {
                result.components.forEachIndexed { index, expression ->
                    appendLine("    val r$index = ${renderTransform(expression, scalar)}")
                }
                appendLine("    return dest.set(${result.components.indices.joinToString { "r$it" }})")
            }
            is TransformResult.Scalar -> appendLine("    return ${renderTransform(result.expression, scalar)}")
        }
        appendLine("}")
    }

    private fun renderTransform(expression: ScalarExpr, scalar: ScalarKind): String = when (expression) {
        is ScalarExpr.Read -> "${expression.source}.${expression.component}()"
        is ScalarExpr.Parameter -> expression.name
        is ScalarExpr.Local -> expression.name
        is ScalarExpr.Constant -> transformConstant(expression.value, scalar)
        is ScalarExpr.Unary -> "(${expression.operator}${renderTransform(expression.value, scalar)})"
        is ScalarExpr.Binary -> "(${renderTransform(expression.left, scalar)} ${expression.operator} ${renderTransform(expression.right, scalar)})"
        is ScalarExpr.Call -> "${expression.function}(${expression.arguments.joinToString { renderTransform(it, scalar) }})"
    }

    /** Formats a transform constant in its operation's precision (float suffix vs. double point). */
    private fun transformConstant(value: Float, scalar: ScalarKind): String = when (scalar) {
        ScalarKind.DOUBLE -> when (value) {
            0f -> "0.0"; 1f -> "1.0"; 2f -> "2.0"; -1f -> "-1.0"; else -> "${value.toDouble()}"
        }
        else -> when (value) {
            0f -> "0f"; 1f -> "1f"; 2f -> "2f"; -1f -> "-1f"; else -> "${value}f"
        }
    }

    private fun renderHeap(expression: Expr): String = render(expression) { source, component ->
        when (source) {
            ValueSource.Self -> "${component.sourceName}()"
            is ValueSource.Parameter -> "${source.name}.${component.sourceName}()"
        }
    }

    private fun offsetVarName(source: ValueSource): String = when (source) {
        ValueSource.Self -> "o_self"
        is ValueSource.Parameter -> "o_${source.name}"
    }

    private fun localReadName(source: ValueSource, component: Component): String = when (source) {
        ValueSource.Self -> "self${component.ordinal}"
        is ValueSource.Parameter -> "${source.name}${component.ordinal}"
    }

    /** Flat per-component `FloatArray` parameter name for a bulk kernel, e.g. `selfX`/`otherY`. */
    private fun bulkArrayName(source: ValueSource, component: Component): String = when (source) {
        ValueSource.Self -> "self${component.sourceName.uppercase()}"
        is ValueSource.Parameter -> "${source.name}${component.sourceName.uppercase()}"
    }

    /** Distinct (source, component) reads referenced by the expressions, in first-seen order. */
    private fun collectReads(expressions: List<Expr>): List<Pair<ValueSource, Component>> {
        val seen = LinkedHashSet<Pair<ValueSource, Component>>()
        fun walk(expression: Expr) {
            when (expression) {
                is Expr.Read -> seen.add(expression.source to expression.component)
                is Expr.Binary -> { walk(expression.left); walk(expression.right) }
                is Expr.Call -> expression.arguments.forEach(::walk)
                is Expr.ScalarParameter -> {}
                is Expr.Constant -> {}
            }
        }
        expressions.forEach(::walk)
        return seen.toList()
    }

    /**
     * Non-leaf subexpressions (`Binary`/`Call`) referenced two or more times across [expressions],
     * in dependency order (a subexpression's own children always precede it), deduped by
     * structural equality since [Expr] is a plain data-class hierarchy. `normalize`'s
     * `1/sqrt(lengthSquared)` term is the current example: it's the same sub-tree multiplied into
     * every component, so without hoisting it a naive per-component render would recompute the
     * square root once per lane-width chunk per component instead of once.
     */
    private fun collectSharedSubexpressions(expressions: List<Expr>): List<Expr> {
        val counts = LinkedHashMap<Expr, Int>()
        fun walk(expression: Expr) {
            when (expression) {
                is Expr.Read, is Expr.ScalarParameter, is Expr.Constant -> {}
                is Expr.Binary -> {
                    walk(expression.left); walk(expression.right)
                    counts[expression] = (counts[expression] ?: 0) + 1
                }
                is Expr.Call -> {
                    expression.arguments.forEach(::walk)
                    counts[expression] = (counts[expression] ?: 0) + 1
                }
            }
        }
        expressions.forEach(::walk)
        return counts.filterValues { it > 1 }.keys.toList()
    }

    private fun renderArenaLocals(expression: Expr): String =
        render(expression) { source, component -> localReadName(source, component) }

    private fun renderSegment(expression: Expr): String = render(expression) { source, component ->
        val token = when (source) {
            ValueSource.Self -> "token"
            is ValueSource.Parameter -> "${source.name}.token"
        }
        "read($token, ${component.ordinal})"
    }

    private fun bulkOperator(operator: String): String = when (operator) {
        "+" -> "add"
        "-" -> "sub"
        "*" -> "mul"
        "/" -> "div"
        else -> error("bulk backend has no FloatVector method for operator '$operator'")
    }

    private fun bulkCall(function: String, arguments: List<String>): String = when (function) {
        "sqrt" -> "${arguments[0]}.lanewise(VectorOperators.SQRT)"
        "abs" -> "${arguments[0]}.abs()"
        "min" -> "${arguments[0]}.min(${arguments[1]})"
        "max" -> "${arguments[0]}.max(${arguments[1]})"
        else -> error("bulk backend has no lowering for call '$function'")
    }

    /** Vectorized main-loop rendering: reads are hoisted locals, constants/scalars broadcast across lanes. */
    private fun renderBulk(expression: Expr, substitutions: Map<Expr, String> = emptyMap()): String = render(
        expression,
        read = { source, component -> localReadName(source, component) },
        scalarParameter = { name -> "FloatVector.broadcast(SP, $name)" },
        constant = { literal -> "FloatVector.broadcast(SP, $literal)" },
        binary = { l, op, r -> "$l.${bulkOperator(op)}($r)" },
        call = ::bulkCall,
        substitute = { substitutions[it] },
    )

    /**
     * Scalar-tail rendering: the same [Expr] tree as [renderBulk], but read directly out of the
     * flat arrays at index `i` with ordinary Kotlin arithmetic — identical in shape to
     * [renderHeap], so the tail is provably the same math as the vectorized body by construction
     * rather than a second hand-written formula.
     */
    private fun renderBulkScalarTail(expression: Expr): String = render(expression) { source, component ->
        "${bulkArrayName(source, component)}[i]"
    }

    /**
     * Walks an [Expr] tree, letting each backend supply how a leaf or combination renders.
     * Defaults reproduce plain Kotlin scalar syntax, so [renderHeap]/[renderArenaLocals]/
     * [renderSegment] (which only override [read]) are unaffected by the extra parameters below;
     * [renderBulk] is the one caller that overrides [constant]/[scalarParameter]/[binary]/[call]
     * to emit `jdk.incubator.vector.FloatVector` operations instead of scalar arithmetic.
     */
    private fun render(
        expression: Expr,
        scalarParameter: (String) -> String = { it },
        constant: (String) -> String = { it },
        binary: (String, String, String) -> String = { l, op, r -> "($l $op $r)" },
        call: (String, List<String>) -> String = { fn, args -> "$fn(${args.joinToString()})" },
        substitute: (Expr) -> String? = { null },
        read: (ValueSource, Component) -> String,
    ): String {
        substitute(expression)?.let { return it }
        return when (expression) {
            is Expr.Read -> read(expression.source, expression.component)
            is Expr.ScalarParameter -> scalarParameter(expression.name)
            is Expr.Constant -> constant(expression.source)
            is Expr.Binary -> binary(
                render(expression.left, scalarParameter, constant, binary, call, substitute, read),
                expression.operator,
                render(expression.right, scalarParameter, constant, binary, call, substitute, read),
            )
            is Expr.Call -> call(
                expression.function,
                expression.arguments.map { render(it, scalarParameter, constant, binary, call, substitute, read) },
            )
        }
    }

    private fun orderedParameters(operation: Operation): List<Parameter> =
        if (operation.name == "fma") operation.scalarParameters + operation.vectorParameters
        else operation.vectorParameters + operation.scalarParameters

    private fun documentationFor(operation: Operation, includeDestination: Boolean = true): ApiDocumentation {
        val names = orderedParameters(operation).mapTo(linkedSetOf()) { it.name }
        if (includeDestination && operation.result == OperationResult.Destination) names += "dest"
        return operation.documentation.copy(parameters = operation.documentation.parameters.filterKeys { it in names })
    }
}

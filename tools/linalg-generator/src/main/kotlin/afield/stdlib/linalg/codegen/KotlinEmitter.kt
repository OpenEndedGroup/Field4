package afield.stdlib.linalg.codegen

class KotlinEmitter(private val model: AlgebraModel) {
    fun emitHeapVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg")
        appendLine("import java.nio.ByteBuffer")
        appendLine("import java.nio.FloatBuffer")
        appendLine("import kotlin.math.*")
        appendLine()
        model.vectors.forEachIndexed { index, type ->
            emitReadInterface(type)
            appendLine()
            emitMutableClass(type)
            if (index != model.vectors.lastIndex) appendLine()
        }
    }

    fun emitArenaVectors(): String = buildString {
        appendHeader("afield.stdlib.linalg.arena")
        appendLine("import afield.stdlib.linalg.*")
        appendLine("import kotlin.math.*")
        appendLine()
        model.vectors.forEach { type ->
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

    /**
     * Emits a non-owning Vec3 view over foreign or mapped memory. This is deliberately
     * generated from the same operation model as heap and frame-arena vectors so a
     * packed vertex stream and a std430-style aligned stream cannot drift mathematically.
     */
    fun emitSegmentVec3Buffer(): String = buildString {
        val type = model.vectors.single { it.dimension == 3 }
        appendHeader("afield.stdlib.linalg.storage")
        appendLine("import afield.stdlib.linalg.*")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout")
        appendLine("import java.nio.ByteBuffer")
        appendLine("import java.util.concurrent.atomic.AtomicInteger")
        appendLine("import kotlin.math.*")
        appendLine()
        appendLine("enum class Vec3StorageLayout(val strideBytes: Int, val requiredAlignmentBytes: Int) {")
        appendLine("    PACKED_12(12, 4),")
        appendLine("    ALIGNED_16(16, 16),")
        appendLine("}")
        appendLine()
        appendLine("@JvmInline")
        appendLine("value class SegmentVec3f internal constructor(internal val token: Long)")
        appendLine()
        appendLine("/**")
        appendLine(" * A non-owning vector view over a MemorySegment. The segment controls lifetime and")
        appendLine(" * thread accessibility; mapped Vulkan memory additionally requires normal Vulkan")
        appendLine(" * host/device synchronization. Handles are allocation-free and buffer-specific.")
        appendLine(" */")
        appendLine("class SegmentVec3Buffer(")
        appendLine("    private val segment: MemorySegment,")
        appendLine("    val count: Int,")
        appendLine("    val layout: Vec3StorageLayout = Vec3StorageLayout.ALIGNED_16,")
        appendLine("    val baseOffsetBytes: Long = 0,")
        appendLine(") {")
        appendLine("    private val ownerId = nextOwnerId()")
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
        appendLine("            12L,")
        appendLine("        )")
        appendLine("        require(required <= segment.byteSize()) {")
        appendLine("            \"Vec3 view needs \$required bytes but segment contains \${segment.byteSize()}\"")
        appendLine("        }")
        appendLine("        require(required <= OFFSET_MASK) { \"Segment Vec3 views are limited to \$OFFSET_MASK bytes\" }")
        appendLine("    }")
        appendLine()
        appendLine("    operator fun get(index: Int): SegmentVec3f {")
        appendLine("        require(index in 0 until count) { \"index \$index outside 0..<\$count\" }")
        appendLine("        return SegmentVec3f(pack(ownerId, baseOffsetBytes + index.toLong() * layout.strideBytes))")
        appendLine("    }")
        appendLine()
        appendLine("    fun get(index: Int, dest: Vector3f): Vector3f = get(index).copyTo(dest)")
        appendLine("    fun set(index: Int, value: Vector3fc): SegmentVec3Buffer { get(index).set(value); return this }")
        appendLine()
        appendLine("    var SegmentVec3f.x: Float")
        appendLine("        get() = read(token, 0)")
        appendLine("        set(value) = write(token, 0, value)")
        appendLine("    var SegmentVec3f.y: Float")
        appendLine("        get() = read(token, 1)")
        appendLine("        set(value) = write(token, 1, value)")
        appendLine("    var SegmentVec3f.z: Float")
        appendLine("        get() = read(token, 2)")
        appendLine("        set(value) = write(token, 2, value)")
        appendLine()
        appendLine("    fun SegmentVec3f.set(x: Float, y: Float, z: Float): SegmentVec3f {")
        appendLine("        write(token, 0, x); write(token, 1, y); write(token, 2, z)")
        appendLine("        if (layout == Vec3StorageLayout.ALIGNED_16) segment.set(ValueLayout.JAVA_FLOAT, offset(token) + 12, 0f)")
        appendLine("        return this")
        appendLine("    }")
        appendLine("    fun SegmentVec3f.set(other: Vector3fc): SegmentVec3f = set(other.x(), other.y(), other.z())")
        appendLine("    fun SegmentVec3f.copyTo(dest: Vector3f): Vector3f = dest.set(x, y, z)")
        appendLine()
        model.operations(type).forEach { operation ->
            emitSegmentOperation(type, operation)
            appendLine()
        }
        appendLine("    fun SegmentVec3f.length(): Float = sqrt(lengthSquared())")
        appendLine("    fun SegmentVec3f.distance(other: SegmentVec3f): Float = sqrt(distanceSquared(other))")
        appendLine("    fun SegmentVec3f.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()")
        appendLine()
        appendLine("    private fun read(token: Long, component: Int): Float =")
        appendLine("        segment.get(ValueLayout.JAVA_FLOAT, offset(token) + component * Float.SIZE_BYTES)")
        appendLine("    private fun write(token: Long, component: Int, value: Float) =")
        appendLine("        segment.set(ValueLayout.JAVA_FLOAT, offset(token) + component * Float.SIZE_BYTES, value)")
        appendLine("    private fun offset(token: Long): Long {")
        appendLine("        check((token ushr OWNER_SHIFT).toInt() == ownerId) { \"SegmentVec3f belongs to another buffer view\" }")
        appendLine("        return token and OFFSET_MASK")
        appendLine("    }")
        appendLine()
        appendLine("    companion object {")
        appendLine("        private const val OWNER_SHIFT = 40")
        appendLine("        private const val OFFSET_MASK = (1L shl OWNER_SHIFT) - 1")
        appendLine("        private const val MAX_OWNER_ID = (1 shl (64 - OWNER_SHIFT)) - 1")
        appendLine("        private val ownerIds = AtomicInteger()")
        appendLine("        private fun nextOwnerId(): Int = ownerIds.incrementAndGet().also {")
        appendLine("            check(it in 1..MAX_OWNER_ID) { \"SegmentVec3Buffer owner id space exhausted\" }")
        appendLine("        }")
        appendLine("        private fun pack(ownerId: Int, offset: Long): Long = (ownerId.toLong() shl OWNER_SHIFT) or offset")
        appendLine()
        appendLine("        fun of(")
        appendLine("            buffer: ByteBuffer,")
        appendLine("            count: Int,")
        appendLine("            layout: Vec3StorageLayout = Vec3StorageLayout.ALIGNED_16,")
        appendLine("        ): SegmentVec3Buffer = SegmentVec3Buffer(MemorySegment.ofBuffer(buffer.slice()), count, layout)")
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

    private fun StringBuilder.emitReadInterface(type: VectorType) {
        appendLine("interface ${type.readName} {")
        type.components.forEach { component ->
            appendLine("    fun ${component.sourceName}(): Float")
        }
        appendLine()
        appendLine("    operator fun get(component: Int): Float = when (component) {")
        type.components.forEachIndexed { index, component ->
            appendLine("        $index -> ${component.sourceName}()")
        }
        appendLine("        else -> throw IndexOutOfBoundsException(\"component \$component outside 0..${type.dimension - 1}\")")
        appendLine("    }")
        appendLine()
        model.operations(type).forEach { operation ->
            emitHeapInterfaceOperation(type, operation)
            appendLine()
        }
        appendLine("    fun length(): Float = sqrt(lengthSquared())")
        appendLine("    fun distance(other: ${type.readName}): Float = sqrt(distanceSquared(other))")
        appendLine("    fun isFinite(): Boolean = ${type.components.joinToString(" && ") { "${it.sourceName}().isFinite()" }}")
        appendLine("    fun equals(other: ${type.readName}, delta: Float): Boolean =")
        appendLine("        ${type.components.joinToString(" && ") { "abs(${it.sourceName}() - other.${it.sourceName}()) <= delta" }}")
        appendLine()
        appendLine("    fun get(buffer: FloatBuffer): FloatBuffer {")
        appendLine("        val index = buffer.position()")
        type.components.forEachIndexed { index, component ->
            appendLine("        buffer.put(index + $index, ${component.sourceName}())")
        }
        appendLine("        return buffer")
        appendLine("    }")
        appendLine()
        appendLine("    fun get(index: Int, buffer: ByteBuffer): ByteBuffer {")
        type.components.forEachIndexed { index, component ->
            appendLine("        buffer.putFloat(index + ${index * 4}, ${component.sourceName}())")
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
                    is ScalarParameter -> add("${it.name}: Float")
                }
            }
            if (operation.result == OperationResult.Destination) add("dest: ${type.heapName}")
        }.joinToString(", ")
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
                appendLine("    fun ${operation.name}($parameters): Float = ${renderHeap(result.expression)}")
        }
    }

    private fun StringBuilder.emitMutableClass(type: VectorType) {
        val zeroes = List(type.dimension) { "0.0f" }
        appendLine("class ${type.heapName}() : ${type.readName}, Cloneable {")
        type.components.forEach { component -> appendLine("    @JvmField var ${component.sourceName}: Float = 0.0f") }
        appendLine()
        appendLine("    constructor(s: Float) : this() { set(${List(type.dimension) { "s" }.joinToString()}) }")
        appendLine(
            "    constructor(${type.components.joinToString { "${it.sourceName}: Float" }}) : this() { " +
                "set(${type.components.joinToString { it.sourceName }}) }"
        )
        appendLine("    constructor(other: ${type.readName}) : this() { set(other) }")
        if (type.dimension == 3) {
            appendLine("    constructor(xy: Vector2fc, z: Float) : this(xy.x(), xy.y(), z)")
        }
        if (type.dimension == 4) {
            appendLine("    constructor(xyz: Vector3fc, w: Float) : this(xyz.x(), xyz.y(), xyz.z(), w)")
            appendLine("    constructor(xy: Vector2fc, z: Float, w: Float) : this(xy.x(), xy.y(), z, w)")
        }
        appendLine()
        type.components.forEach { component ->
            appendLine("    override fun ${component.sourceName}(): Float = ${component.sourceName}")
        }
        appendLine()
        appendLine("    operator fun set(component: Int, value: Float) {")
        appendLine("        when (component) {")
        type.components.forEachIndexed { index, component -> appendLine("            $index -> ${component.sourceName} = value") }
        appendLine("            else -> throw IndexOutOfBoundsException(\"component \$component outside 0..${type.dimension - 1}\")")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    fun set(s: Float): ${type.heapName} = set(${List(type.dimension) { "s" }.joinToString()})")
        appendLine(
            "    fun set(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.heapName} {"
        )
        type.components.forEach { appendLine("        this.${it.sourceName} = ${it.sourceName}") }
        appendLine("        return this")
        appendLine("    }")
        appendLine(
            "    fun set(other: ${type.readName}): ${type.heapName} = " +
                "set(${type.components.joinToString { "other.${it.sourceName}()" }})"
        )
        appendLine("    fun zero(): ${type.heapName} = set(${zeroes.joinToString()})")
        appendLine(
            "    fun add(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "this.${it.sourceName} + ${it.sourceName}" }})"
        )
        appendLine(
            "    fun sub(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "this.${it.sourceName} - ${it.sourceName}" }})"
        )
        appendLine(
            "    fun mul(${type.components.joinToString { "${it.sourceName}: Float" }}): ${type.heapName} = " +
                "set(${type.components.joinToString { "this.${it.sourceName} * ${it.sourceName}" }})"
        )
        appendLine()
        model.operations(type).filter { it.result == OperationResult.Destination }.forEach { operation ->
            val parameters = buildList {
                orderedParameters(operation).forEach {
                    when (it) {
                        is VectorParameter -> add("${it.name}: ${type.readName}")
                        is ScalarParameter -> add("${it.name}: Float")
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
            appendLine("    ${modifier}fun ${operation.name}($parameters): ${type.heapName} = ${operation.name}($arguments)")
        }
        appendLine()
        appendLine("    public override fun clone(): ${type.heapName} = ${type.heapName}(this)")
        appendLine("    override fun equals(other: Any?): Boolean = other is ${type.readName} && ${type.components.joinToString(" && ") { "${it.sourceName}.toBits() == other.${it.sourceName}().toBits()" }}")
        appendLine("    override fun hashCode(): Int {")
        appendLine("        var result = 1")
        type.components.forEach { appendLine("        result = 31 * result + ${it.sourceName}.toBits()") }
        appendLine("        return result")
        appendLine("    }")
        appendLine("    override fun toString(): String = \"(${type.components.joinToString(", ") { "\$${it.sourceName}" }})\"")
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
        model.operations(type).forEach { operation ->
            emitArenaOperation(type, operation, kind)
            appendLine()
        }
        appendLine("    fun ${type.arenaName}.length(): Float = sqrt(lengthSquared())")
        appendLine("    fun ${type.arenaName}.distance(other: ${type.arenaName}): Float = sqrt(distanceSquared(other))")
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
        when (val result = operation.result) {
            OperationResult.Destination -> {
                appendLine("    fun ${type.arenaName}.${operation.name}($parameters): ${type.arenaName} {")
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderArena(expression, kind)}")
                }
                appendLine("        return dest.set(${operation.componentExpressions.indices.joinToString { "r$it" }})")
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
            is OperationResult.Scalar ->
                appendLine("    fun ${type.arenaName}.${operation.name}($parameters): Float = ${renderArena(result.expression, kind)}")
        }
    }

    private fun StringBuilder.emitSegmentOperation(type: VectorType, operation: Operation) {
        val parameters = buildList {
            orderedParameters(operation).forEach {
                when (it) {
                    is VectorParameter -> add("${it.name}: SegmentVec3f")
                    is ScalarParameter -> add("${it.name}: Float")
                }
            }
            if (operation.result == OperationResult.Destination) add("dest: SegmentVec3f")
        }.joinToString(", ")
        when (val result = operation.result) {
            OperationResult.Destination -> {
                appendLine("    fun SegmentVec3f.${operation.name}($parameters): SegmentVec3f {")
                operation.componentExpressions.forEachIndexed { index, expression ->
                    appendLine("        val r$index = ${renderSegment(expression)}")
                }
                appendLine("        return dest.set(${operation.componentExpressions.indices.joinToString { "r$it" }})")
                appendLine("    }")
                val receiverParameters = orderedParameters(operation).joinToString(", ") {
                    when (it) {
                        is VectorParameter -> "${it.name}: SegmentVec3f"
                        is ScalarParameter -> "${it.name}: Float"
                    }
                }
                val receiverArguments = orderedParameters(operation).joinToString(", ") { it.name }
                val comma = if (receiverArguments.isEmpty()) "" else "$receiverArguments, "
                appendLine("    fun SegmentVec3f.${operation.name}($receiverParameters): SegmentVec3f = ${operation.name}(${comma}this)")
            }
            is OperationResult.Scalar ->
                appendLine("    fun SegmentVec3f.${operation.name}($parameters): Float = ${renderSegment(result.expression)}")
        }
    }

    private fun renderHeap(expression: Expr): String = render(expression) { source, component ->
        when (source) {
            ValueSource.Self -> "${component.sourceName}()"
            is ValueSource.Parameter -> "${source.name}.${component.sourceName}()"
        }
    }

    private fun renderArena(expression: Expr, kind: String): String = render(expression) { source, component ->
        val token = when (source) {
            ValueSource.Self -> "token"
            is ValueSource.Parameter -> "${source.name}.token"
        }
        "arena.read($token, ${component.ordinal}, $kind)"
    }

    private fun renderSegment(expression: Expr): String = render(expression) { source, component ->
        val token = when (source) {
            ValueSource.Self -> "token"
            is ValueSource.Parameter -> "${source.name}.token"
        }
        "read($token, ${component.ordinal})"
    }

    private fun render(expression: Expr, read: (ValueSource, Component) -> String): String = when (expression) {
        is Expr.Read -> read(expression.source, expression.component)
        is Expr.ScalarParameter -> expression.name
        is Expr.Constant -> expression.source
        is Expr.Binary -> "(${render(expression.left, read)} ${expression.operator} ${render(expression.right, read)})"
        is Expr.Call -> "${expression.function}(${expression.arguments.joinToString { render(it, read) }})"
    }

    private fun orderedParameters(operation: Operation): List<Parameter> =
        if (operation.name == "fma") operation.scalarParameters + operation.vectorParameters
        else operation.vectorParameters + operation.scalarParameters
}

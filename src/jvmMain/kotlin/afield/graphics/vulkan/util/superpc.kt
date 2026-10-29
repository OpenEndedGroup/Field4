@file:Suppress("MemberVisibilityCanBePrivate", "unused")
package afield.graphics.vulkan.util

/**
 * Push constants as a Kotlin class, and the GLSL that reads them back. A subclass of
 * [PushConstants] declares its members as ordinary `var` properties; reflection turns those into a
 * byte layout with explicit offsets, `glsl()` emits the push-constant block and accessors that match
 * it, and `writeTo` serialises the current values into a buffer by those same offsets.
 *
 * The reflection runs once per class and the layout is cached, so the per-frame cost is the write.
 * Members are laid out in `@PcOrder` order where that annotation is given and alphabetically where
 * it is not, which is what makes a layout stable from one run to the next.
 *
 * #### What the generated GLSL looks like
 *
 * One push-constant block, a `layout(offset = …)` on every member, a float register file loaded out
 * of that block by `loadRegs()`, and then accessors under the names the Kotlin class used:
 *
 * ```glsl
 * {{ pc.dec }}
 * void main() {
 *     loadRegs();
 *     uFragColor = baseColor();
 * }
 * ```
 *
 * A scalar member becomes a macro, so `time` in Kotlin reads as `time` in shader code. A vector
 * member becomes a function of no arguments under its own name, so `baseColor` reads as
 * `baseColor()`. To keep those macros clear of the block's own members, a member inside the block
 * carries a leading underscore: Kotlin's `time` is declared as `_time` and the bare name is the
 * macro that reads it out of the register file.
 *
 * #### Instruction payloads
 *
 * A property whose name already begins with an underscore is an instruction payload: it is declared
 * in the push-constant block under exactly that name and nothing else happens to it. It is not
 * loaded into the register file and gets no accessor, so a shader reads it from the block itself and
 * a frame pays nothing for payloads it is not reading.
 *
 * #### Member types
 *
 * `Float`, `Vector2f`, `Vector3f` and `Vector4f` become a `float`, `vec2`, `vec3` and `vec4`. The
 * half-precision types travel packed into unsigned integers, to be unpacked in the shader: `Vec2h`
 * as a `uint`, `Vec3h` and `Vec4h` as a `uvec2`, and the eight-lane `Vector8h` as a `uvec4`. Every
 * member has to be a `var`, since the layout reads and writes through the property itself, and any
 * other type fails when the layout is built rather than at draw time. `otherDeclarations` is the way
 * around all of this: raw GLSL inserted into the block that reflection never sees, which you place
 * yourself at `nextFreeOffset(…)`.
 *
 * ```kotlin
 * class MaterialPC : PushConstants<MaterialPC>() {
 *     @PcOrder(0) var time: Float = 0f
 *     @PcOrder(1) var baseColor: Vector4f = Vector4f(1f, 1f, 1f, 1f)
 *     @PcOrder(2) var uvScale: Vector2f = Vector2f(1f, 1f)
 *     @PcOrder(100) var _patch0: Vec4h = Vector4f().half
 * }
 *
 * val pc = MaterialPC()
 * pc.time = 1.0f
 * pc["uvScale"] = Vector2f(2f, 2f)
 *
 * shaderTemplate["pc"] = mapOf("dec" to Resolver { pc.glsl() })
 * mesh.push.bindAll { pc }
 * ```
 *
 * The block reaches the shader through the template engine, as above, and the instance is bound by
 * handing it to a mesh's push-constant structure, which writes it out each time that mesh's push
 * constants are assembled for a draw. The buffer it is written into has to be little-endian, and a
 * `ByteBuffer` is big-endian until it is told otherwise.
 */

import afield.graphics.vulkan.buffers.WritesToBuffer
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import afield.math.linalg.Vector8h
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

// ---------------------------
// Ordering annotation (optional)
// ---------------------------

/**
 * Fixes where a push-constant member sits in the block, lowest [value] first.
 *
 * Members carrying this annotation are laid out in its order and come before every member that does
 * not, which are laid out alphabetically after them. Numbering the whole class buys two things.
 * Packing: each member is aligned to its own size, so a `vec3` followed by a `float` fills sixteen
 * bytes exactly where the reverse order wastes twelve. And stability: under the alphabetical
 * fallback a member named early in the alphabet shifts every offset after it, which silently moves
 * where `nextFreeOffset` points and invalidates any `layout(offset = …)` written by hand into
 * `otherDeclarations`. Values need not be contiguous; leaving a gap is how payload members take the
 * high numbers.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PcOrder(val value: Int)

// ---------------------------
// Layout / schema model
// ---------------------------

internal enum class PcKind(val regFloats: Int) {
    F1(1),
    V2(2),
    V3(3),
    V4(4),

    H2(2), // Vec2h -> uint
    H3(3), // Vec3h -> uvec2
    H4(4), // Vec4h -> uvec2
    H8(8)  // Vector8h -> uvec4
}

internal data class FieldInfo(
    val name: String,
    val kind: PcKind,
    val pcOffsetBytes: Int,
    val pcSizeBytes: Int,
    val regIndex: Int?,          // null if excluded from regs (underscore)
    val property: KProperty1<Any, *>
)

internal class Schema(
    val fields: List<FieldInfo>,
    val regFloatCount: Int,
    val pcTotalBytes: Int,
    val regIndexByName: Map<String, Int>,
    val pcOffsetByName: Map<String, Int>
)

// ---------------------------
// Base class
// ---------------------------

/**
 * Base class for a push-constant block written as Kotlin properties, which generates its own matching
 * GLSL declaration.
 *
 * Subclass it, declare each member as a `var` of a supported type, and the layout is derived by
 * reflection on first use: `glsl()` emits the block and its accessors, `writeTo` serialises the
 * current values at the same offsets, and `pcOffsetOf`/`regIndexOf` answer where a member landed. The
 * two sides cannot disagree, because both read one cached schema.
 *
 * The type parameter is the subclass's own type and is not used by anything here; write
 * `class MaterialPC : PushConstants<MaterialPC>()`.
 */
abstract class PushConstants<T : Any> : WritesToBuffer {

    companion object {
        private val cache = ConcurrentHashMap<KClass<*>, Schema>()
    }

    private val schema: Schema by lazy {
        cache.computeIfAbsent(this::class) { buildSchema(it) }
    }

    /**
     * Optional raw GLSL declarations inserted into the push-constant block.
     *
     * This is NOT reflected into the schema:
     * - no automatic offset calculation
     * - no register loading
     * - no accessors
     *
     * Use this for hand-authored members you want visible in GLSL only.
     */
    open var otherDeclarations: String = ""

    /**
     * How many bytes this block occupies, rounded up to a multiple of 16.
     *
     * This is the size to allocate for a buffer that [writeTo] will fill, and the size to declare as
     * the push-constant range. The rounding means it is normally a little larger than the members
     * add up to.
     */
    fun pcSizeBytes(): Int = schema.pcTotalBytes

    /**
     * How many floats the generated register file holds: the `REG_COUNT` the emitted GLSL declares.
     *
     * One per scalar member and one per component of a vector member, counting only the members that
     * get accessors; an underscore payload contributes nothing.
     */
    fun regFloatCount(): Int = schema.regFloatCount

    /**
     * First free byte offset after all reflected fields, optionally aligned.
     *
     * Use this to place extra manual declarations in [otherDeclarations].
     * Example:
     *   val offset = nextFreeOffset(16)
     *   otherDeclarations = "layout(offset = $offset) uint _manualFlags;"
     */
    fun nextFreeOffset(alignment: Int = 4): Int {
        require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
            "Alignment must be a positive power of two, was $alignment"
        }
        val used = schema.fields.maxOfOrNull { it.pcOffsetBytes + it.pcSizeBytes } ?: 0
        if (alignment == 1) return used
        return ((used + alignment - 1) / alignment) * alignment
    }

    /**
     * Where [name] starts in the generated register file: the value of the `REG_<name>` constant
     * the emitted GLSL declares.
     *
     * Throws for an unknown member and for an underscore payload, since a payload is never loaded
     * into the register file and so has no index there. Use [pcOffsetOf] for those.
     */
    fun regIndexOf(name: String): Int =
        schema.regIndexByName[name]
            ?: error("No register-backed field named '$name'")

    /**
     * The byte offset of [name] within the push-constant block, which is the offset the generated
     * `layout(offset = …)` carries.
     *
     * Answers for every member including underscore payloads, and throws for a name the class does
     * not declare.
     */
    fun pcOffsetOf(name: String): Int =
        schema.pcOffsetByName[name]
            ?: error("No field named '$name'")

    /**
     * Dynamic getter by field name.
     */
    operator fun get(name: String): Any? {
        val f = schema.fields.firstOrNull { it.name == name }
            ?: error("No field named '$name'")
        return f.property.get(this as Any)
    }

    /**
     * Dynamic setter by field name.
     *
     * Requires underlying property to be declared as `var`.
     */
    operator fun set(name: String, value: Any?) {
        val f = schema.fields.firstOrNull { it.name == name }
            ?: error("No field named '$name'")

        if (value == null) {
            error("Null not supported for push-constant field '$name'")
        }

        validateValueOrThrow(f, value)

        @Suppress("UNCHECKED_CAST")
        val mutable = f.property as? KMutableProperty1<Any, Any?>
            ?: error("Field '$name' must be declared as 'var' to support runtime mutation.")

        mutable.set(this as Any, value)
    }

    /**
     * Writes every member into [b] at [baseOffset] plus its own offset, leaving the buffer's position
     * where it found it.
     *
     * Each member is written with absolute puts, so this is safe to call on a shared buffer. The
     * buffer has to be little-endian, and a `ByteBuffer` is big-endian until
     * `order(ByteOrder.LITTLE_ENDIAN)` is called on it — get that wrong and every value arrives
     * byte-reversed with no error anywhere.
     */
    override fun writeTo(b: ByteBuffer, baseOffset: Int) {
        val oldPos = b.position()

        for (f in schema.fields) {
            val abs = baseOffset + f.pcOffsetBytes
            val v = f.property.get(this as Any)
            writeField(b, abs, f.kind, v)
        }

        b.position(oldPos)
    }

    /**
     * Emits the GLSL side of this block: the push-constant declaration, a float register file, a
     * `loadRegs()` that fills it, and an accessor per member under the member's own Kotlin name.
     *
     * The declaration gives every member an explicit `layout(offset = …)` and a leading underscore,
     * which keeps the block's own names clear of the accessors. A scalar member's accessor is a
     * `#define`, so `time` in Kotlin reads as `time`; a vector member's is a function of no
     * arguments, so `baseColor` reads as `baseColor()`. Half-precision members get unpack helpers
     * ahead of the block. Call `loadRegs()` once at the top of `main` before reading anything.
     *
     * [emitScalarLayoutQualifier] adds the `scalar` layout qualifier to the block, which a shader
     * compiled with scalar block layout needs. [regsName] and [pcInstanceName] rename the register
     * array and the block instance, for a shader that already has something called `regs` or `pc`.
     */
    fun glsl(
        emitScalarLayoutQualifier: Boolean = false,
        regsName: String = "regs",
        pcInstanceName: String = "pc"
    ): String = GlslGen.generate(
        kClass = this::class,
        schema = schema,
        emitScalarLayoutQualifier = emitScalarLayoutQualifier,
        regsName = regsName,
        pcInstanceName = pcInstanceName,
        otherDeclarations = otherDeclarations
    )

    // ---------------------------
    // Validation + writing
    // ---------------------------

    private fun validateValueOrThrow(f: FieldInfo, value: Any) {
        val ok = when (f.kind) {
            PcKind.F1 -> value is Float
            PcKind.V2 -> value is Vector2f
            PcKind.V3 -> value is Vector3f
            PcKind.V4 -> value is Vector4f
            PcKind.H2 -> value is Vec2h
            PcKind.H3 -> value is Vec3h
            PcKind.H4 -> value is Vec4h
            PcKind.H8 -> value is Vector8h
        }

        if (!ok) {
            error(
                "Type mismatch for '${f.name}'. " +
                        "Expected ${expectedTypeName(f.kind)}, got ${value::class.simpleName}"
            )
        }
    }

    private fun expectedTypeName(kind: PcKind): String = when (kind) {
        PcKind.F1 -> "Float"
        PcKind.V2 -> "Vector2f"
        PcKind.V3 -> "Vector3f"
        PcKind.V4 -> "Vector4f"
        PcKind.H2 -> "Vec2h"
        PcKind.H3 -> "Vec3h"
        PcKind.H4 -> "Vec4h"
        PcKind.H8 -> "Vector8h"
    }

    private fun writeField(bb: ByteBuffer, absOffset: Int, kind: PcKind, v: Any?) {
        val oldPos = bb.position()
        bb.position(absOffset)

        when (kind) {
            PcKind.F1 -> bb.putFloat(v as Float)

            PcKind.V2 -> {
                val t = v as Vector2f
                bb.putFloat(t.x); bb.putFloat(t.y)
            }
            PcKind.V3 -> {
                val t = v as Vector3f
                bb.putFloat(t.x); bb.putFloat(t.y); bb.putFloat(t.z)
            }
            PcKind.V4 -> {
                val t = v as Vector4f
                bb.putFloat(t.x); bb.putFloat(t.y); bb.putFloat(t.z); bb.putFloat(t.w)
            }

            PcKind.H2 -> {
                val t = (v as Vec2h).v
                bb.putInt(packHalf2x16(t.x, t.y))
            }
            PcKind.H3 -> {
                val t = (v as Vec3h).v
                val xy = packHalf2x16(t.x, t.y)
                val z0 = packHalf1x16(t.z)
                bb.putInt(xy)
                bb.putInt(z0)
            }
            PcKind.H4 -> {
                val t = (v as Vec4h).v
                val xy = packHalf2x16(t.x, t.y)
                val zw = packHalf2x16(t.z, t.w)
                bb.putInt(xy)
                bb.putInt(zw)
            }
            // Vector8h stores fp16; its own writer emits the uvec4 bytes (two halfs per uint
            // lane) with absolute puts, so it is independent of the buffer's position.
            PcKind.H8 -> (v as Vector8h).get(absOffset, bb)
        }

        bb.position(oldPos)
    }
}

// ---------------------------
// Schema builder
// ---------------------------

private fun buildSchema(k: KClass<*>): Schema {
    // Deterministic ordering:
    // 1) PcOrder if present
    // 2) name
    val props = k.memberProperties
        .sortedWith(compareBy<KProperty1<out Any, *>>(
            { it.findAnnotation<PcOrder>()?.value ?: Int.MAX_VALUE },
            { it.name }
        ))

    var pcOffset = 0
    var regIndex = 0

    val fields = mutableListOf<FieldInfo>()
    val regMap = mutableMapOf<String, Int>()
    val pcMap = mutableMapOf<String, Int>()

    fun align(off: Int, a: Int): Int {
        if (a <= 1) return off
        val r = off % a
        return if (r == 0) off else off + (a - r)
    }

    for (pAny in props) {
        val name = pAny.name

        // Skip framework / helper properties that are not part of the PC payload.
        // Currently this is just otherDeclarations:String from the base class.
        if (name == "otherDeclarations") continue

        val kind = inferKind(pAny.returnType.classifier as? KClass<*>)

        // Enforce mutability at schema build time for clearer errors.
        val isMutable = (pAny as? KMutableProperty1<*, *>) != null
        if (!isMutable) {
            error("Push-constant field '$name' must be declared as 'var'.")
        }

        val (alignBytes, sizeBytes) = pcStorageLayout(kind)

        pcOffset = align(pcOffset, alignBytes)

        val excluded = name.startsWith("_")
        val thisRegIndex =
            if (excluded) null
            else regIndex.also { regMap[name] = it }

        pcMap[name] = pcOffset

        @Suppress("UNCHECKED_CAST")
        val p = pAny as KProperty1<Any, *>

        fields += FieldInfo(
            name = name,
            kind = kind,
            pcOffsetBytes = pcOffset,
            pcSizeBytes = sizeBytes,
            regIndex = thisRegIndex,
            property = p
        )

        pcOffset += sizeBytes
        if (!excluded) regIndex += kind.regFloats
    }

    // Round total bytes to 16 for convenience/safety.
    fun align16(off: Int): Int {
        val a = 16
        val r = off % a
        return if (r == 0) off else off + (a - r)
    }

    val total = align16(pcOffset)

    return Schema(
        fields = fields,
        regFloatCount = regIndex,
        pcTotalBytes = total,
        regIndexByName = regMap,
        pcOffsetByName = pcMap
    )
}

private fun inferKind(k: KClass<*>?): PcKind = when (k) {
    Float::class -> PcKind.F1
    Vector2f::class -> PcKind.V2
    Vector3f::class -> PcKind.V3
    Vector4f::class -> PcKind.V4

    Vec2h::class -> PcKind.H2
    Vec3h::class -> PcKind.H3
    Vec4h::class -> PcKind.H4
    Vector8h::class -> PcKind.H8

    else -> error("Unsupported push-constant field type: $k")
}

/**
 * Storage layout for the GLSL types we emit in the push constant block.
 *
 * We assume std430-ish alignment rules for:
 * - float
 * - vec2/3/4
 * - uint
 * - uvec2
 *
 * Half wrappers map to uint/uvec2 storage types in GLSL.
 */
private fun pcStorageLayout(kind: PcKind): Pair<Int, Int> = when (kind) {
    PcKind.F1 -> 4 to 4
    PcKind.V2 -> 8 to 8
    PcKind.V3 -> 16 to 12
    PcKind.V4 -> 16 to 16

    PcKind.H2 -> 4 to 4     // uint
    PcKind.H3 -> 8 to 8     // uvec2
    PcKind.H4 -> 8 to 8     // uvec2
    PcKind.H8 -> 16 to 16   // uvec4
}

// ---------------------------
// GLSL generator
// ---------------------------

/**
 * Builds the GLSL text for a push-constant block from a layout that reflection has already worked
 * out.
 *
 * Reached through [PushConstants.glsl] rather than directly, since the layout it needs is internal, so
 * there is no way to call it with a schema of your own. It is public because the generated text is
 * worth reading when a shader disagrees with its Kotlin: print `pc.glsl()` and compare the offsets.
 */
object GlslGen {

    internal fun generate(
        kClass: KClass<*>,
        schema: Schema,
        emitScalarLayoutQualifier: Boolean,
        regsName: String,
        pcInstanceName: String,
        otherDeclarations: String = ""
    ): String {
        val blockTypeName = kClass.simpleName ?: "PC"

        // Push-constant member naming to avoid macro collisions.
        fun pcMemberName(fieldName: String): String =
            if (fieldName.startsWith("_")) fieldName else "_$fieldName"

        val sb = StringBuilder()
//        sb.appendLine("#version 450")
//        sb.appendLine()

        // Half transit decode helpers
        sb.appendLine("// ---- Half transit decode helpers ----")
        sb.appendLine("vec2 half2(uint p) { return unpackHalf2x16(p); }")
        sb.appendLine(
            "vec3 half3(uvec2 p) { " +
                    "vec2 a = unpackHalf2x16(p.x); " +
                    "vec2 b = unpackHalf2x16(p.y); " +
                    "return vec3(a.x, a.y, b.x); " +
                    "}"
        )
        sb.appendLine(
            "vec4 half4(uvec2 p) { " +
                    "vec2 a = unpackHalf2x16(p.x); " +
                    "vec2 b = unpackHalf2x16(p.y); " +
                    "return vec4(a, b); " +
                    "}"
        )
        sb.appendLine(
            "void half8(uvec4 p, out vec4 lo, out vec4 hi) { " +
                    "lo = vec4(unpackHalf2x16(p.x), unpackHalf2x16(p.y)); " +
                    "hi = vec4(unpackHalf2x16(p.z), unpackHalf2x16(p.w)); " +
                    "}"
        )
        sb.appendLine()

        // Push constant block header
        val layoutHead = if (emitScalarLayoutQualifier)
            "layout(scalar, push_constant)"
        else
            "layout(push_constant)"

        sb.appendLine("$layoutHead uniform $blockTypeName {")
        for (f in schema.fields) {
            val glslType = glslStorageType(f.kind)
            val m = pcMemberName(f.name)
            sb.appendLine("    layout(offset = ${f.pcOffsetBytes}) $glslType $m;")
        }

        // Extra user-provided declarations, inserted inside the block.
        val extra = otherDeclarations.trim()
        if (extra.isNotEmpty()) {
            extra.lineSequence().forEach { line ->
                val l = line.trimEnd()
                if (l.isNotBlank()) sb.appendLine("    $l")
                else sb.appendLine()
            }
        }

        sb.appendLine("} $pcInstanceName;")
        sb.appendLine()

        // Register indices
        sb.appendLine("// ---- Register file layout ----")
        for (f in schema.fields) {
            if (f.regIndex != null) {
                sb.appendLine("const uint REG_${f.name} = ${f.regIndex}u;")
            }
        }
        sb.appendLine("const uint REG_COUNT = ${schema.regFloatCount}u;")
        sb.appendLine("float $regsName[REG_COUNT];")
        sb.appendLine()

        // Loader
        sb.appendLine("// ---- Load push constants into register file ----")
        sb.appendLine("void loadRegs() {")
        for (f in schema.fields) {
            val n = f.name
            val r = f.regIndex ?: continue
            val m = pcMemberName(n)

            when (f.kind) {
                PcKind.F1 -> {
                    sb.appendLine("    $regsName[REG_$n] = $pcInstanceName.$m;")
                }
                PcKind.V2 -> {
                    sb.appendLine("    $regsName[REG_$n + 0] = $pcInstanceName.$m.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = $pcInstanceName.$m.y;")
                }
                PcKind.V3 -> {
                    sb.appendLine("    $regsName[REG_$n + 0] = $pcInstanceName.$m.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = $pcInstanceName.$m.y;")
                    sb.appendLine("    $regsName[REG_$n + 2] = $pcInstanceName.$m.z;")
                }
                PcKind.V4 -> {
                    sb.appendLine("    $regsName[REG_$n + 0] = $pcInstanceName.$m.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = $pcInstanceName.$m.y;")
                    sb.appendLine("    $regsName[REG_$n + 2] = $pcInstanceName.$m.z;")
                    sb.appendLine("    $regsName[REG_$n + 3] = $pcInstanceName.$m.w;")
                }

                PcKind.H2 -> {
                    sb.appendLine("    vec2 _${n}_v = half2($pcInstanceName.$m);")
                    sb.appendLine("    $regsName[REG_$n + 0] = _${n}_v.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = _${n}_v.y;")
                }
                PcKind.H3 -> {
                    sb.appendLine("    vec3 _${n}_v = half3($pcInstanceName.$m);")
                    sb.appendLine("    $regsName[REG_$n + 0] = _${n}_v.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = _${n}_v.y;")
                    sb.appendLine("    $regsName[REG_$n + 2] = _${n}_v.z;")
                }
                PcKind.H4 -> {
                    sb.appendLine("    vec4 _${n}_v = half4($pcInstanceName.$m);")
                    sb.appendLine("    $regsName[REG_$n + 0] = _${n}_v.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = _${n}_v.y;")
                    sb.appendLine("    $regsName[REG_$n + 2] = _${n}_v.z;")
                    sb.appendLine("    $regsName[REG_$n + 3] = _${n}_v.w;")
                }
                PcKind.H8 -> {
                    sb.appendLine("    vec4 _${n}_lo;")
                    sb.appendLine("    vec4 _${n}_hi;")
                    sb.appendLine("    half8($pcInstanceName.$m, _${n}_lo, _${n}_hi);")
                    sb.appendLine("    $regsName[REG_$n + 0] = _${n}_lo.x;")
                    sb.appendLine("    $regsName[REG_$n + 1] = _${n}_lo.y;")
                    sb.appendLine("    $regsName[REG_$n + 2] = _${n}_lo.z;")
                    sb.appendLine("    $regsName[REG_$n + 3] = _${n}_lo.w;")
                    sb.appendLine("    $regsName[REG_$n + 4] = _${n}_hi.x;")
                    sb.appendLine("    $regsName[REG_$n + 5] = _${n}_hi.y;")
                    sb.appendLine("    $regsName[REG_$n + 6] = _${n}_hi.z;")
                    sb.appendLine("    $regsName[REG_$n + 7] = _${n}_hi.w;")
                }
            }
        }
        sb.appendLine("}")
        sb.appendLine()

        // Accessors for non-underscore fields
        sb.appendLine("// ---- Typed accessors over the register file ----")
        for (f in schema.fields) {
            if (f.regIndex == null) continue
            val n = f.name

            when (f.kind) {
                PcKind.F1 -> {
                    // macro below
                }
                PcKind.V2, PcKind.H2 -> {
                    sb.appendLine("vec2 $n() {")
                    sb.appendLine("    return vec2($regsName[REG_$n + 0], $regsName[REG_$n + 1]);")
                    sb.appendLine("}")
                }
                PcKind.V3, PcKind.H3 -> {
                    sb.appendLine("vec3 $n() {")
                    sb.appendLine("    return vec3(")
                    sb.appendLine("        $regsName[REG_$n + 0],")
                    sb.appendLine("        $regsName[REG_$n + 1],")
                    sb.appendLine("        $regsName[REG_$n + 2]")
                    sb.appendLine("    );")
                    sb.appendLine("}")
                }
                PcKind.V4, PcKind.H4 -> {
                    sb.appendLine("vec4 $n() {")
                    sb.appendLine("    return vec4(")
                    sb.appendLine("        $regsName[REG_$n + 0],")
                    sb.appendLine("        $regsName[REG_$n + 1],")
                    sb.appendLine("        $regsName[REG_$n + 2],")
                    sb.appendLine("        $regsName[REG_$n + 3]")
                    sb.appendLine("    );")
                    sb.appendLine("}")
                }
                PcKind.H8 -> {
                    sb.appendLine("vec4 ${n}Lo() {")
                    sb.appendLine("    return vec4(")
                    sb.appendLine("        $regsName[REG_$n + 0],")
                    sb.appendLine("        $regsName[REG_$n + 1],")
                    sb.appendLine("        $regsName[REG_$n + 2],")
                    sb.appendLine("        $regsName[REG_$n + 3]")
                    sb.appendLine("    );")
                    sb.appendLine("}")
                    sb.appendLine("vec4 ${n}Hi() {")
                    sb.appendLine("    return vec4(")
                    sb.appendLine("        $regsName[REG_$n + 4],")
                    sb.appendLine("        $regsName[REG_$n + 5],")
                    sb.appendLine("        $regsName[REG_$n + 6],")
                    sb.appendLine("        $regsName[REG_$n + 7]")
                    sb.appendLine("    );")
                    sb.appendLine("}")
                }
            }
            sb.appendLine()
        }

        // Scalar macros (safe due to PC member underscore prefix).
        sb.appendLine("// ---- Scalar macros ----")
        for (f in schema.fields) {
            if (f.regIndex == null) continue
            if (f.kind == PcKind.F1) {
                sb.appendLine("#define ${f.name} ($regsName[REG_${f.name}])")
            }
        }

        sb.appendLine("//#<line>")
        return sb.toString()
    }

    private fun glslStorageType(kind: PcKind): String = when (kind) {
        PcKind.F1 -> "float"
        PcKind.V2 -> "vec2"
        PcKind.V3 -> "vec3"
        PcKind.V4 -> "vec4"

        PcKind.H2 -> "uint"
        PcKind.H3 -> "uvec2"
        PcKind.H4 -> "uvec2"
        PcKind.H8 -> "uvec4"
    }
}

// ---------------------------
// Smoke test
// ---------------------------

/**
 * A worked example of a push-constant class, covering each supported member type and both payload
 * conventions.
 *
 * Five ordered members that get accessors, then two underscore payloads given high [PcOrder] numbers
 * so later members can be added in front of them. Nothing outside this file uses it; what reads it is
 * the `main` below, which prints the layout, the generated GLSL and a word dump of the written bytes.
 */
class SmokePC : PushConstants<SmokePC>() {

    @PcOrder(0) var time: Float = 0f
    @PcOrder(1) var baseColor: Vector4f = Vector4f()
    @PcOrder(2) var roughMetal: Vec2h = Vector2f().half
    @PcOrder(3) var uvScale: Vector2f = Vector2f(1f, 1f)
    @PcOrder(4) var emissive: Vector3f = Vector3f()

    // instruction payloads (excluded from regs)
    @PcOrder(100) var _patch0: Vec4h = Vector4f().half
    @PcOrder(101) var _patch1: Vec4h = Vector4f().half
}

private fun dumpU32(bb: ByteBuffer, bytes: Int) {
    val words = bytes / 4
    println("Raw u32 dump ($words words):")
    for (i in 0 until words) {
        val w = bb.getInt(i * 4).toUInt()
        val hex = w.toString(16).padStart(8, '0')
        println("  [$i] 0x$hex")
    }
}

/**
 * Prints [SmokePC]'s layout, its generated GLSL and a word dump of the bytes it writes.
 *
 * Nothing in Field calls this; it is here to be run on its own when a block's offsets need checking
 * against a shader, which it does without a device, a window or a renderer.
 */
fun main() {
    val pc = SmokePC().apply {
        time = 1.25f
        baseColor = Vector4f(1f, 0.5f, 0.25f, 1f)
        roughMetal = Vector2f(0.4f, 0.9f).half
        uvScale = Vector2f(2f, 3f)
        emissive = Vector3f(0.1f, 0.2f, 0.3f)

        // "instruction words as floats" for smoke only.
        _patch0 = Vector4f(12f, 1f, 13f, 1f).half
        _patch1 = Vector4f(0f, 0f, 0f, 0f).half

        // Example extra declaration using nextFreeOffset:
        val extraOffset = nextFreeOffset(16)
        otherDeclarations = "layout(offset = $extraOffset) uint _extraDebug;"
    }

    // Demonstrate dynamic mutation hitting real fields.
    pc["time"] = 2.0f
    pc["uvScale"] = Vector2f(4f, 5f)

    println("---- Introspection ----")
    println("pcSizeBytes = ${pc.pcSizeBytes()}")
    println("regFloatCount = ${pc.regFloatCount()}")
    listOf("time", "baseColor", "roughMetal", "uvScale", "emissive").forEach { n ->
        println("$n: pcOffset=${pc.pcOffsetOf(n)} bytes, regIndex=${pc.regIndexOf(n)}")
    }

    println()
    println("---- Generated GLSL ----")
    println(pc.glsl())

    val bytes = pc.pcSizeBytes()
    val bb = ByteBuffer.allocateDirect(bytes)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    pc.writeTo(bb, 0)

    println("---- ByteBuffer dump ----")
    dumpU32(bb, bytes)
}

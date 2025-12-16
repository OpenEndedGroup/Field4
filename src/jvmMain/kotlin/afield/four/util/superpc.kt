@file:Suppress("MemberVisibilityCanBePrivate", "unused")
package afield.four.util

/*
    PushConstant DSL + GLSL generator (explicit offsets, real typed variables)

    DESIGN SUMMARY
    --------------
    CPU-side:
      - You define mutable Push Constant classes with `var` properties.
      - Reflection builds a cached schema per class:
          * push-constant byte offsets + sizes
          * register-file float indices
      - writeTo(ByteBuffer) serializes values by explicit offsets.
      - operator get/set by field name works by reflective access to real vars.

    Shader-side (generated GLSL):
      - One push-constant block with explicit `layout(offset=...)` per member.
      - To avoid macro clashes:
          * For normal fields, the GLSL push-constant MEMBER NAME is prefixed with "_".
              Kotlin:  time
              GLSL PC: _time
          * For instruction fields (names starting with "_"), the name is left unchanged.
              Kotlin:  _patch0
              GLSL PC: _patch0
      - Fields starting with "_" are treated as instruction payloads:
          * declared in PC block
          * NOT loaded into regs[]
          * NO accessors generated
      - A float32 register file is loaded from the PC block.
      - Scalar access: macros with ORIGINAL names
          #define time (regs[REG_time])
      - Vector access: functions with ORIGINAL names (no "get" prefix)
          vec4 baseColor() { ... }

    Types supported for normal fields:
      - Float
      - org.joml.Vector2f / Vector3f / Vector4f
      - Vec2h / Vec3h / Vec4h (fp16 transit only)

    Instruction payloads:
      - You said these will always be Vec4h; this file supports that naturally.
        (Other half types also work if you choose.)

    Half transit ABI in GLSL PC:
      - Vec2h -> uint  (packed half2)
      - Vec3h -> uvec2 (x,y in .x; z in low half of .y)
      - Vec4h -> uvec2 (x,y in .x; z,w in .y)

    NOTE:
      - Ensure the ByteBuffer uses LITTLE_ENDIAN:
            bb.order(ByteOrder.LITTLE_ENDIAN)

    Example usage:
      class MaterialPC : PushConstants<MaterialPC>() {
          @PcOrder(0)  var time: Float = 0f
          @PcOrder(1)  var baseColor: Vector4f = Vector4f(1f,1f,1f,1f)
          @PcOrder(2)  var roughMetal: Vec2h = Vector2f(0.5f, 0.0f).half
          @PcOrder(3)  var uvScale: Vector2f = Vector2f(1f, 1f)

          @PcOrder(100) var _patch0: Vec4h = Vector4f().half
          @PcOrder(101) var _patch1: Vec4h = Vector4f().half
      }

      val pc = MaterialPC().apply {
          time = 1.0f
          this["uvScale"] = Vector2f(2f, 2f) // dynamic set
          otherDeclarations = "layout(offset = ${nextFreeOffset(16)}) uint _manualFlags;"
      }

      val glsl = pc.glsl()
*/

import afield.four.buffers.WritesToBuffer
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

// ---------------------------
// Half wrapper types + sugar
// ---------------------------

data class Vec2h(val v: Vector2f)
data class Vec3h(val v: Vector3f)
data class Vec4h(val v: Vector4f)

// Convenient ".half" views on JOML vectors.
val Vector2f.half: Vec2h get() = Vec2h(this)
val Vector3f.half: Vec3h get() = Vec3h(this)
val Vector4f.half: Vec4h get() = Vec4h(this)

// ---------------------------
// Ordering annotation (optional)
// ---------------------------

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
    H4(4)  // Vec4h -> uvec2
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
// Half packing utilities
// ---------------------------

object HalfUtil {

    fun pack2ToU32(x: Float, y: Float): Int {
        val hx = floatToHalfBits(x).toInt() and 0xFFFF
        val hy = floatToHalfBits(y).toInt() and 0xFFFF
        return hx or (hy shl 16)
    }

    fun pack1ToU32(x: Float): Int =
        floatToHalfBits(x).toInt() and 0xFFFF

    // Common float->half conversion
    fun floatToHalfBits(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        var exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF

        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = (mant or 0x800000) ushr (1 - exp)
            return (sign or ((mant + 0x1000) ushr 13)).toShort()
        }

        if (exp >= 31) {
            // Inf/NaN
            return (sign or 0x7C00 or (mant ushr 13)).toShort()
        }

        return (sign or (exp shl 10) or ((mant + 0x1000) ushr 13)).toShort()
    }
}

// ---------------------------
// Base class
// ---------------------------

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

    fun pcSizeBytes(): Int = schema.pcTotalBytes
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

    fun regIndexOf(name: String): Int =
        schema.regIndexByName[name]
            ?: error("No register-backed field named '$name'")

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
     * Writes this push-constant object into [bb] at [baseOffset] without
     * changing the buffer's position.
     *
     * IMPORTANT: ensure bb.order(ByteOrder.LITTLE_ENDIAN)
     */
    override fun writeTo(bb: ByteBuffer, baseOffset: Int) {
        val oldPos = bb.position()

        for (f in schema.fields) {
            val abs = baseOffset + f.pcOffsetBytes
            val v = f.property.get(this as Any)
            writeField(bb, abs, f.kind, v)
        }

        bb.position(oldPos)
    }

    /**
     * Generates GLSL glue:
     * - push constant block with explicit offsets
     * - PC member names prefixed with "_" for normal fields
     * - regs[] + loadRegs()
     * - scalar macros + vec accessors (no "get" prefix)
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
                bb.putInt(HalfUtil.pack2ToU32(t.x, t.y))
            }
            PcKind.H3 -> {
                val t = (v as Vec3h).v
                val xy = HalfUtil.pack2ToU32(t.x, t.y)
                val z0 = HalfUtil.pack1ToU32(t.z)
                bb.putInt(xy)
                bb.putInt(z0)
            }
            PcKind.H4 -> {
                val t = (v as Vec4h).v
                val xy = HalfUtil.pack2ToU32(t.x, t.y)
                val zw = HalfUtil.pack2ToU32(t.z, t.w)
                bb.putInt(xy)
                bb.putInt(zw)
            }
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
}

// ---------------------------
// GLSL generator
// ---------------------------

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
    }
}

// ---------------------------
// Smoke test
// ---------------------------

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

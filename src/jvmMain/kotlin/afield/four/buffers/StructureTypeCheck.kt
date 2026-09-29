package afield.four.buffers

import afield.four.modules.Introspected
import afield.four.modules.PrimitiveType
import afield.four.util.Vec2h
import afield.four.util.Vec3h
import afield.four.util.Vec4h
import afield.four.util.packHalf1x16
import afield.stdlib.linalg.Matrix2f
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/**
 * What a value set on a uniform member becomes on its way into the buffer: [Written] the value to write
 * (converted to the member's reflected type when the conversion loses nothing), or [Refused] why not.
 */
internal sealed class Coerced {
    class Written(val value: Any) : Coerced()
    class Refused(val why: String) : Coerced()
}

/**
 * The value [value] becomes in this member's slot, judged against the *reflected* type — the check
 * [Structure.set] and [Structure.writeToBuffer] make before writing.
 *
 * `writeToBuffer` used to write by the value's Kotlin runtime type alone, so a `Vector3f` set on a member
 * the shader now declares `float` wrote twelve bytes into a four-byte slot, an `Int` set on a `float`
 * wrote the integer's bits (1 read as 1.4e-45), and a `Float` on a `float16_t` wrote four bytes into two.
 *
 * The rule (2026-09-29): **convert what converts without loss, refuse a different shape.**
 *
 * - *Shape* must agree: a scalar goes into a scalar, a vector into a vector of the same length, a
 *   matrix into a matrix of the same size, and nothing but a buffer-writing value goes into a struct.
 *   These are refused.
 * - Among scalars (and among same-length vectors) the value is converted to the member's type when that
 *   loses nothing the member could hold: any integer into a wider or equal integer (`Int` into `int64_t`
 *   or `uint64_t`); an integer into a narrower one, or of the other signedness, when it is in range; an
 *   integer into `float` when it is exactly representable (|n| ≤ 2²⁴); a whole float into an integer;
 *   `Double` into `float`, and a float into a half — the member's declared precision, which is what
 *   rounding to it means; a half vector into a float vector. Anything else is refused, with the
 *   number it could not hold.
 * - Values whose width this cannot know — a [WritesToBuffer], a raw `ByteBuffer`, a [HasUniform] before
 *   it is evaluated, a buffer reference — array members, and device pointers are written as given.
 */
internal fun Introspected.Member.coerce(value: Any): Coerced {
    if (arrayStride > 0 || physicalPointer) return Coerced.Written(value)
    val shape = shapeOf(value) ?: return Coerced.Written(value)
    val primitive = type.fold({ it }, { null })
        ?: return Coerced.Refused("`$name` is a struct in the shader, and ${describeValue(value)} is not")
    val memberShape = shapeOfPrimitive(primitive)
    if (shape.kind != memberShape.kind || shape.components != memberShape.components)
        return Coerced.Refused("`$name` is ${primitive.name} in the shader, and ${describeValue(value)} is not")
    val converted = when (shape.kind) {
        ShapeKind.SCALAR -> coerceScalar(value, primitive)
        ShapeKind.VECTOR -> coerceVector(value, primitive)
        ShapeKind.MATRIX -> value
    }
    return if (converted != null) Coerced.Written(converted)
    else Coerced.Refused("`$name` is ${primitive.name} in the shader, and $value (${describeValue(value)}) would not survive the conversion")
}

/** Why [value] cannot go into this member's slot, or null when it can ([coerce]). */
internal fun Introspected.Member.refusalFor(value: Any): String? = (coerce(value) as? Coerced.Refused)?.why

private enum class ShapeKind { SCALAR, VECTOR, MATRIX }

private class Shape(val kind: ShapeKind, val components: Int)

private fun shapeOf(value: Any): Shape? = when (value) {
    is Float, is Double, is Int, is Long, is Short, is Byte, is UInt, is ULong, is UShort, is UByte, is Boolean -> Shape(ShapeKind.SCALAR, 1)
    is Vector2f, is Vec2h -> Shape(ShapeKind.VECTOR, 2)
    is Vector3f, is Vec3h -> Shape(ShapeKind.VECTOR, 3)
    is Vector4f, is Quaternionf, is Vec4h -> Shape(ShapeKind.VECTOR, 4)
    is Matrix2f -> Shape(ShapeKind.MATRIX, 4)
    is Matrix3f -> Shape(ShapeKind.MATRIX, 9)
    is Matrix4f -> Shape(ShapeKind.MATRIX, 16)
    else -> null
}

private fun shapeOfPrimitive(p: PrimitiveType): Shape = when (p) {
    PrimitiveType.mat2, PrimitiveType.mat3, PrimitiveType.mat4 -> Shape(ShapeKind.MATRIX, p.components)
    else -> Shape(if (p.components == 1) ShapeKind.SCALAR else ShapeKind.VECTOR, p.components)
}

private const val MAX_EXACT_FLOAT_INT = 1L shl 24

/**
 * The integer [value] holds exactly, or null: an integral type, a Boolean as 0 or 1, or a float with no
 * fractional part. An unsigned 64-bit value above `Long.MAX_VALUE` is null here (it is only exact as
 * `uint64_t` bits, which [coerceScalar] handles).
 */
private fun exactInteger(value: Any): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is UInt -> value.toLong()
    is UShort -> value.toLong()
    is UByte -> value.toLong()
    is ULong -> if (value <= Long.MAX_VALUE.toULong()) value.toLong() else null
    is Boolean -> if (value) 1L else 0L
    is Float -> if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.2e18f) value.toLong() else null
    is Double -> if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.2e18) value.toLong() else null
    else -> null
}

/** [value] as the member's scalar [p] would hold it — the type `writeToBuffer` then writes — or null. */
private fun coerceScalar(value: Any, p: PrimitiveType): Any? = when (p) {
    PrimitiveType.float -> when (value) {
        is Float -> value
        is Double -> value.toFloat()
        else -> exactInteger(value)?.takeIf { kotlin.math.abs(it) <= MAX_EXACT_FLOAT_INT }?.toFloat()
    }
    PrimitiveType.float16_t -> when (value) {
        is Float -> HalfBits(packHalf1x16(value).toShort())
        is Double -> HalfBits(packHalf1x16(value.toFloat()).toShort())
        // a half holds every integer up to 2048 exactly
        else -> exactInteger(value)?.takeIf { kotlin.math.abs(it) <= 2048 }?.let { HalfBits(packHalf1x16(it.toFloat()).toShort()) }
    }
    PrimitiveType.int -> if (value is Int) value else exactInteger(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    PrimitiveType.uint -> when (value) {
        is UInt -> value
        else -> exactInteger(value)?.takeIf { it in 0L..0xFFFF_FFFFL }?.toInt()
    }
    PrimitiveType.int64_t -> if (value is Long) value else exactInteger(value)
    // uint64_t is carried as Long bits in Kotlin (its generated delegate is a Long), so any Long is taken as is
    PrimitiveType.uint64_t, PrimitiveType.deviceptr -> when (value) {
        is Long -> value
        is ULong -> value.toLong()
        else -> exactInteger(value)?.takeIf { it >= 0 }
    }
    else -> null
}

/** [value] as the member's vector [p] would hold it, or null. Components must already agree. */
private fun coerceVector(value: Any, p: PrimitiveType): Any? = when (p) {
    PrimitiveType.vec2 -> when (value) { is Vector2f -> value; is Vec2h -> Vector2f(value.v); else -> null }
    PrimitiveType.vec3 -> when (value) { is Vector3f -> value; is Vec3h -> Vector3f(value.v); else -> null }
    PrimitiveType.vec4 -> when (value) { is Vector4f, is Quaternionf -> value; is Vec4h -> Vector4f(value.v); else -> null }
    PrimitiveType.f16vec2 -> when (value) { is Vec2h -> value; is Vector2f -> Vec2h(Vector2f(value)); else -> null }
    PrimitiveType.f16vec3 -> when (value) { is Vec3h -> value; is Vector3f -> Vec3h(Vector3f(value)); else -> null }
    PrimitiveType.f16vec4 -> when (value) { is Vec4h -> value; is Vector4f -> Vec4h(Vector4f(value)); else -> null }
    else -> null
}

/** One IEEE half, already packed: what a `Float` set on a `float16_t` member is written as (two bytes). */
internal class HalfBits(val bits: Short)

private fun describeValue(value: Any): String = "a ${value::class.simpleName ?: value.javaClass.name}"

/** The member at [path] (`"light.color"` for a nested member) among [members], or null. */
internal fun memberAtPath(members: List<Introspected.Member>, path: String): Introspected.Member? {
    var level = members
    var found: Introspected.Member? = null
    for (part in path.split('.')) {
        found = level.firstOrNull { it.name == part } ?: return null
        level = found.type.fold({ emptyList() }, { it })
    }
    return found
}

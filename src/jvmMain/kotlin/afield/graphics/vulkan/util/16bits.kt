package afield.graphics.vulkan.util

/**
 * Half-precision (binary16) conversion and packing for data headed to GPU buffers.
 *
 * [floatToHalfBits] and [halfBitsToFloat] convert single values, [packHalf1x16] and [packHalf2x16] pack them as GLSL's `packHalf2x16` does, and the `.half` views ([Vec2h], [Vec3h], [Vec4h]) mark a Field vector for 16-bit storage.
 */

import afield.math.linalg.Vector2f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

/**
 * A two-component vector marked for 16-bit storage, holding the full-precision [Vector2f] until
 * something writes it out.
 *
 * Nothing is converted or copied here: the wrapper is a type tag that tells a buffer writer to emit
 * two halves rather than two floats. A `Structure` field declared `f16vec2` and a `PushConstants`
 * member of this type both pack it into one `uint` with [packHalf2x16], which GLSL reads back with
 * `unpackHalf2x16`. Read the components through `v`.
 */
data class Vec2h(val v: Vector2f)

/**
 * Three components marked for 16-bit storage. The packed form is two `uint`s — `x` and `y` in the
 * first, `z` in the low half of the second — so it costs the same eight bytes a `uvec2` does.
 */
data class Vec3h(val v: Vector3f)

/**
 * Four components marked for 16-bit storage, packed into two `uint`s and read back in the shader as a
 * `uvec2`: eight bytes where a `vec4` takes sixteen, at enough precision for a colour.
 */
data class Vec4h(val v: Vector4f)

// The 8-lane fp16 vector is no longer a transit wrapper: use the real generated
// afield.math.linalg.Vector8h, which stores binary16 directly.

// Convenient ".half" views on Field vectors.

/**
 * Marks this vector for 16-bit storage: `Vector2f(0.4f, 0.9f).half` is the idiom for giving a
 * push-constant member or a buffer field its half-precision form.
 *
 * The wrapper holds this vector rather than a copy of it, so a later mutation of the `Vector2f` is
 * seen by whatever writes the wrapper out.
 */
val Vector2f.half: Vec2h get() = Vec2h(this)

/** Marks this vector for 16-bit storage, wrapping it rather than copying it. */
val Vector3f.half: Vec3h get() = Vec3h(this)

/** Marks this vector for 16-bit storage, wrapping it rather than copying it. */
val Vector4f.half: Vec4h get() = Vec4h(this)

/**
 * Converts one float to binary16 and returns the sixteen bits zero-extended into an `Int`.
 *
 * The upper half of the result is always zero, so two of these can be combined with a shift, which is
 * what [packHalf2x16] does. Rounding, overflow to infinity and underflow to a subnormal all follow
 * [floatToHalfBits].
 */
fun packHalf1x16(x: Float): Int =
    floatToHalfBits(x).toInt() and 0xFFFF

/**
 * Packs two floats into one `Int` as binary16 pairs, with [x] in the low sixteen bits and [y] in the
 * high sixteen: the layout GLSL's `packHalf2x16` produces and `unpackHalf2x16` reads.
 */
fun packHalf2x16(x: Float, y: Float): Int {
    val hx = packHalf1x16(x)
    val hy = packHalf1x16(y)
    return hx or (hy shl 16)
}

/** Convert a Float to IEEE 754 binary16 bit pattern (half float). */
fun floatToHalfBits(value: Float): Short {
    val f = java.lang.Float.floatToIntBits(value)
    val sign = (f ushr 16) and 0x8000
    val exp = (f ushr 23) and 0xFF
    val mant = f and 0x7FFFFF

    // NaN / Inf
    if (exp == 0xFF) {
        val halfMant = if (mant != 0) 0x200 else 0 // quiet NaN payload
        return (sign or 0x7C00 or halfMant).toShort()
    }

    // Convert exponent from float bias (127) to half bias (15)
    var halfExp = exp - 127 + 15

    // Overflow -> Inf
    if (halfExp >= 31) {
        return (sign or 0x7C00).toShort()
    }

    // Underflow -> subnormal or zero
    if (halfExp <= 0) {
        // Too small -> signed zero
        if (halfExp < -10) {
            return sign.toShort()
        }

        // Make implicit leading 1 explicit
        var m = mant or 0x800000

        // Number of positions to shift right to get a subnormal half mantissa
        val shift = 1 - halfExp

        // We need 10 mantissa bits; float mantissa is 23 bits.
        // So we shift by (shift + 13) to align into 10-bit space.
        var halfMant = m ushr (shift + 13)

        // Remainder bits for rounding
        val remainderMask = (1 shl (shift + 13)) - 1
        val remainder = m and remainderMask
        val halfway = 1 shl (shift + 12)

        // Round-to-nearest-even
        if (remainder > halfway || (remainder == halfway && (halfMant and 1) != 0)) {
            halfMant++
        }

        return (sign or halfMant).toShort()
    }

    // Normal half
    var halfMant = mant ushr 13
    val remainder = mant and 0x1FFF

    // Round-to-nearest-even
    if (remainder > 0x1000 || (remainder == 0x1000 && (halfMant and 1) != 0)) {
        halfMant++
        // Mantissa overflow -> bump exponent
        if (halfMant == 0x400) {
            halfMant = 0
            halfExp++
            if (halfExp >= 31) {
                return (sign or 0x7C00).toShort()
            }
        }
    }

    return (sign or (halfExp shl 10) or halfMant).toShort()
}

/** Convert an IEEE 754 binary16 bit pattern (half) to Float. */
fun halfBitsToFloat(bits: Short): Float {
    val h = bits.toInt() and 0xFFFF
    val sign = (h ushr 15) and 0x1
    val exp = (h ushr 10) and 0x1F
    val mant = h and 0x03FF

    val fSign = sign shl 31

    val fBits = when (exp) {
        0 -> {
            if (mant == 0) {
                // ±0
                fSign
            } else {
                // Subnormal half -> normalize
                var m = mant
                var e = -14 // half subnormal exponent base

                // Shift until leading 1 reaches bit 10 (0x400)
                while ((m and 0x400) == 0) {
                    m = m shl 1
                    e -= 1
                }

                // Remove leading 1
                m = m and 0x3FF

                val fExp = (e + 127) shl 23
                val fMant = m shl 13
                fSign or fExp or fMant
            }
        }

        31 -> {
            // Inf / NaN
            val fExp = 0xFF shl 23
            if (mant == 0) {
                // ±Inf
                fSign or fExp
            } else {
                // NaN (preserve payload-ish, set quiet NaN bit)
                val fMant = (mant shl 13) or 0x400000
                fSign or fExp or fMant
            }
        }

        else -> {
            // Normal
            val e = exp - 15 + 127
            val fExp = e shl 23
            val fMant = mant shl 13
            fSign or fExp or fMant
        }
    }

    return Float.fromBits(fBits)
}

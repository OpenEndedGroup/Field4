package afield.four.util

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.ByteBuffer

data class Vec2h(val v: Vector2f)
data class Vec3h(val v: Vector3f)
data class Vec4h(val v: Vector4f)

data class Vec8h(val lo: Vector4f, val hi: Vector4f) {
    constructor(
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        a: Float,
        b: Float,
        c: Float,
        d: Float
    ) : this(Vector4f(x, y, z, w), Vector4f(a, b, c, d))

    constructor(values: FloatArray) : this(
        requireVec8(values)[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6],
        values[7]
    )

    fun toFloatArray(): FloatArray =
        floatArrayOf(lo.x, lo.y, lo.z, lo.w, hi.x, hi.y, hi.z, hi.w)

    companion object {
        private fun requireVec8(values: FloatArray): FloatArray {
            require(values.size == 8) { "Vec8h requires exactly 8 floats, got ${values.size}" }
            return values
        }
    }
}

// Convenient ".half" views on JOML vectors.
val Vector2f.half: Vec2h get() = Vec2h(this)
val Vector3f.half: Vec3h get() = Vec3h(this)
val Vector4f.half: Vec4h get() = Vec4h(this)

fun packHalf1x16(x: Float): Int =
    floatToHalfBits(x).toInt() and 0xFFFF

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

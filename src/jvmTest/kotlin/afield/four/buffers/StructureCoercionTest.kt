package afield.four.buffers

import afield.four.modules.Introspected
import afield.four.modules.PrimitiveType
import afield.four.util.Vec3h
import afield.four.util.packHalf1x16
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import arrow.core.left
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The rule a value meets on its way into a uniform member ([coerce]): convert what converts without
 * loss, refuse a different shape. Each row is (member type, value, what the shader reads or null for a
 * refusal).
 */
class StructureCoercionTest {
    private fun member(type: PrimitiveType) = Introspected.Member("m", 0, type.left())

    private fun written(type: PrimitiveType, value: Any): Any? = when (val c = member(type).coerce(value)) {
        is Coerced.Written -> c.value
        is Coerced.Refused -> null
    }

    @Test
    fun scalarsConvertWhenNothingIsLost() {
        // integers into integers: widening always, narrowing and signedness in range
        assertEquals(5L, written(PrimitiveType.uint64_t, 5))
        assertEquals(-5L, written(PrimitiveType.int64_t, -5))
        assertEquals(7L, written(PrimitiveType.int64_t, 7u))
        assertEquals(-1L, written(PrimitiveType.uint64_t, -1L), "a Long is uint64_t's own Kotlin type, taken as bits")
        assertEquals(3, written(PrimitiveType.int, 3L))
        assertNull(written(PrimitiveType.int, 1L shl 40), "out of range for int")
        assertEquals(3, written(PrimitiveType.uint, 3))
        assertNull(written(PrimitiveType.uint, -1), "negative into uint")
        assertNull(written(PrimitiveType.uint64_t, -1), "a negative Int is not a uint64_t")
        assertEquals(1, written(PrimitiveType.int, true))

        // integers into float: exact up to 2^24
        assertEquals(1f, written(PrimitiveType.float, 1))
        assertEquals(16777216f, written(PrimitiveType.float, 16777216))
        assertNull(written(PrimitiveType.float, 16777217), "not exactly representable")

        // floats: Double to float rounds to the declared precision; a whole float into an integer
        assertEquals(0.1f, written(PrimitiveType.float, 0.1))
        assertEquals(2, written(PrimitiveType.int, 2f))
        assertEquals(2L, written(PrimitiveType.uint64_t, 2.0))
        assertNull(written(PrimitiveType.int, 2.5f), "a fraction does not survive")
        assertNull(written(PrimitiveType.int, Float.NaN))

        // halves
        val half = written(PrimitiveType.float16_t, 0.5f) as HalfBits
        assertEquals(packHalf1x16(0.5f).toShort(), half.bits)
    }

    @Test
    fun aDifferentShapeIsRefused() {
        assertNull(written(PrimitiveType.float, Vector3f(1f, 2f, 3f)), "vector into scalar")
        assertNull(written(PrimitiveType.vec3, 1f), "scalar into vector")
        assertNull(written(PrimitiveType.vec3, Vector4f()), "wrong vector length")
        assertNull(written(PrimitiveType.vec4, Matrix4f()), "matrix into vector")
        assertNull(written(PrimitiveType.mat4, Vector4f()), "vector into matrix")
        val struct = Introspected.Member("s", 0, listOf(member(PrimitiveType.float)).let { arrow.core.Either.Right(it) })
        assertTrue(struct.coerce(1f) is Coerced.Refused, "scalar into struct")
    }

    @Test
    fun vectorsConvertBetweenHalfAndFloat() {
        val v = written(PrimitiveType.vec3, Vec3h(Vector3f(1f, 2f, 3f))) as Vector3f
        assertEquals(Vector3f(1f, 2f, 3f), v)
        assertTrue(written(PrimitiveType.f16vec3, Vector3f(1f, 2f, 3f)) is Vec3h)
        assertTrue(written(PrimitiveType.vec2, Vector2f(1f, 2f)) is Vector2f)
        assertTrue(written(PrimitiveType.mat4, Matrix4f()) is Matrix4f)
    }

    @Test
    fun setAndWriteUseTheConvertedValue() {
        val members = listOf(
            Introspected.Member("count", 0, PrimitiveType.uint64_t.left()),
            Introspected.Member("gain", 8, PrimitiveType.float.left()),
            Introspected.Member("index", 12, PrimitiveType.int.left()),
            Introspected.Member("h", 16, PrimitiveType.float16_t.left()),
            Introspected.Member("after", 18, PrimitiveType.float16_t.left()),
        )
        val s = Structure().setMembers(members)
        s["count"] = 12
        s["gain"] = 2
        s["index"] = 4.0
        s["h"] = 0.5f
        s["after"] = 0.25f
        assertEquals(12, s.valueFor("count"), "the value is stored as given, and converted at write")
        s["index"] = 4.5f
        assertEquals(4.0, s.valueFor("index"), "a refused value leaves the old one")

        val bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
        s.writeToBuffer(bytes, null) { fail("no references here") }
        assertEquals(12L, bytes.getLong(0))
        assertEquals(2f, bytes.getFloat(8), "an Int into a float is the number, not its bits")
        assertEquals(4, bytes.getInt(12))
        assertEquals(packHalf1x16(0.5f).toShort(), bytes.getShort(16))
        assertEquals(packHalf1x16(0.25f).toShort(), bytes.getShort(18), "a half is two bytes, and does not overwrite the next")
    }
}

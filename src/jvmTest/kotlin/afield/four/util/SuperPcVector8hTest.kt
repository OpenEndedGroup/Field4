package afield.four.util

import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector8h
import afield.stdlib.linalg.vec8h
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A push-constant block using the generated 8-lane fp16 vector.
class GeneratedVector8hPC : PushConstants<GeneratedVector8hPC>() {
    @PcOrder(0) var data: Vector8h = Vector8h()
}

class SuperPcVector8hTest {
    @Test
    fun vector8hPacksAsH8Uvec4() {
        // 0.1 exercises rounding, 65504 is binary16 max, negatives exercise the sign path.
        val lanes = floatArrayOf(1f, 2f, 3.5f, -4f, 0.1f, 1000f, 65504f, -0.5f)

        val pc = GeneratedVector8hPC().apply {
            data = vec8h(lanes[0], lanes[1], lanes[2], lanes[3], lanes[4], lanes[5], lanes[6], lanes[7])
        }

        // A single uvec4 field: 16 bytes, eight register floats.
        assertEquals(16, pc.pcSizeBytes())
        assertEquals(8, pc.regFloatCount())

        // Serializes as four uints, two packed halfs each (the H8 -> uvec4 ABI).
        val actual = ByteBuffer.allocate(pc.pcSizeBytes()).order(ByteOrder.LITTLE_ENDIAN)
        pc.writeTo(actual, 0)
        val expected = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(packHalf2x16(lanes[0], lanes[1]))
            putInt(packHalf2x16(lanes[2], lanes[3]))
            putInt(packHalf2x16(lanes[4], lanes[5]))
            putInt(packHalf2x16(lanes[6], lanes[7]))
            position(0)
        }
        assertEquals(expected, actual, "Vector8h must pack as the H8 uvec4 ABI")

        // GLSL treats it as H8: uvec4 storage plus the half8 Lo()/Hi() accessors.
        val glsl = pc.glsl()
        assertTrue("uvec4 _data;" in glsl, "expected a uvec4 push-constant member")
        assertTrue("vec4 dataLo()" in glsl && "vec4 dataHi()" in glsl, "expected H8 Lo/Hi accessors")

        // Dynamic set accepts the generated type and rejects a mismatched one.
        pc["data"] = vec8h(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertFailsWith<IllegalStateException> { pc["data"] = Vector4f() }
    }
}

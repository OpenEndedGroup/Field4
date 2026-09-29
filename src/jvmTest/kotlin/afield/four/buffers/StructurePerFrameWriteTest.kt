package afield.four.buffers

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A per-frame uniform block is one buffer per swapchain image. A value set once has to reach every one
 * of them: the structure used to clear a single `dirty` flag after writing the current image's buffer,
 * and the others kept the old value, so consecutive frames alternated between the new and the old.
 */
class StructurePerFrameWriteTest {
    private val members = Introspection(
        "#version 450\nlayout(local_size_x = 1) in;\n" +
            "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; };\n" +
            "layout(std430, set = 0, binding = 1) buffer outbuf { float values[]; };\n" +
            "void main() { values[0] = color.x * gain; }\n",
        ShaderKind.COMPUTE_SHADER,
    ).ubos.single { it.name == "Light" }.members

    private val gainOffset = members.single { it.name == "gain" }.offset

    /** Three slots, like a three-image swapchain; each write lands in its own buffer. */
    private val slots = List(3) { ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()) }

    private fun Structure.write(slot: Int): Boolean =
        writeSlotIfStale(slot, slots.size, null, { error("no references here") }) { body -> slots[slot].body() }

    @Test
    fun aValueSetOnceReachesEverySlot() {
        val s = Structure().setMembers(members)
        s["gain"] = 0.5f
        assertTrue(s.dirty)
        assertTrue(s.write(0))
        assertFalse(s.write(0), "slot 0 is current")
        assertTrue(s.dirty, "slots 1 and 2 have not had the value yet")
        assertTrue(s.write(1), "slot 1 still holds the old value, so it is written")
        assertTrue(s.write(2))
        slots.forEach { assertEquals(0.5f, it.getFloat(gainOffset)) }
        assertFalse(s.write(1))
        assertFalse(s.dirty)

        s["gain"] = 0.75f
        assertTrue(s.write(2))
        assertTrue(s.write(0) && s.write(1), "a new value makes every slot stale again")
        slots.forEach { assertEquals(0.75f, it.getFloat(gainOffset)) }
    }

    @Test
    fun anUntouchedStructureWritesNothingAndABindingWritesEveryTime() {
        val s = Structure().setMembers(members)
        assertFalse(s.write(0), "nothing set, nothing to write")
        var g = 1f
        s.bind("gain") { g }
        assertTrue(s.write(0))
        g = 2f
        assertTrue(s.write(0), "a binding is evaluated on every write")
        assertEquals(2f, slots[0].getFloat(gainOffset))
    }

    @Test
    fun aSetDuringAWriteLeavesTheSlotStale() {
        val s = Structure().setMembers(members)
        s["gain"] = 0.5f
        s.writeSlotIfStale(0, slots.size, null, { error("no references here") }) { body ->
            slots[0].body()
            s["gain"] = 0.25f
        }
        assertTrue(s.write(0), "the value set while writing is written next time")
        assertEquals(0.25f, slots[0].getFloat(gainOffset))
    }
}

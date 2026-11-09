package afield.graphics.render

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import kotlin.test.*

class ShaderModuleCodeTest {
    @Test
    fun largeDirectCodeBorrowsMemoryWithoutConsumingTheScratchStack() {
        val code = MemoryUtil.memAlloc(2 * 1024 * 1024 + 8)
        try {
            code.position(4)
            code.limit(code.capacity() - 4)
            val pointer = MemoryStack.stackGet().pointer
            withShaderModuleCode(code) { native ->
                assertEquals(MemoryUtil.memAddress(code), MemoryUtil.memAddress(native))
                assertEquals(pointer, MemoryStack.stackGet().pointer)
                native.position(native.limit())
            }
            assertEquals(4, code.position())
            assertEquals(code.capacity() - 4, code.limit())
        } finally {
            MemoryUtil.memFree(code)
        }
    }

    @Test
    fun largeHeapCodeCopiesOnlyTheSelectedRangeAndPreservesItsCursor() {
        val code = ByteBuffer.allocate(2 * 1024 * 1024 + 8)
        code.put(4, 23.toByte())
        code.position(4)
        code.limit(code.capacity() - 4)
        val pointer = MemoryStack.stackGet().pointer
        withShaderModuleCode(code) { native ->
            assertTrue(native.isDirect)
            assertEquals(code.remaining(), native.remaining())
            assertEquals(23.toByte(), native.get(0))
            assertEquals(pointer, MemoryStack.stackGet().pointer)
        }
        assertEquals(4, code.position())
    }

    @Test
    fun unalignedDirectCodeIsCopiedAndExceptionsPreserveTheSource() {
        val code = MemoryUtil.memAlloc(17).position(1)
        try {
            assertFailsWith<IllegalStateException> {
                withShaderModuleCode(code) { native ->
                    assertEquals(0L, MemoryUtil.memAddress(native) % 4L)
                    assertNotEquals(MemoryUtil.memAddress(code), MemoryUtil.memAddress(native))
                    error("consumer failed")
                }
            }
            assertEquals(1, code.position())
        } finally {
            MemoryUtil.memFree(code)
        }
    }

    @Test
    fun rejectsEmptyOrPartialWords() {
        assertFailsWith<IllegalArgumentException> { withShaderModuleCode(ByteBuffer.allocate(0)) {} }
        assertFailsWith<IllegalArgumentException> { withShaderModuleCode(ByteBuffer.allocate(7)) {} }
    }
}

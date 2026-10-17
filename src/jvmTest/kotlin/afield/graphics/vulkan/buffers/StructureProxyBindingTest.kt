package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.device.CommandBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StructureProxyBindingTest {
    /** A real host-memory backing for the mapping interfaces; this test needs no GPU. */
    private class HostMemory : HasBuffer, BufferWriteable, BufferReadable {
        val bytes = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        override fun getBuffer() = 123L
        override fun getGPUAddress(cmd: CommandBuffer) = 123L
        override fun clean(hostReading: Boolean, cmd: CommandBuffer?) = this
        override fun mapAndWrite(builder: FloatBuffer.() -> Unit) = apply { builder(bytes.asFloatBuffer()) }
        override fun mapAndWrite_bytes(builder: ByteBuffer.() -> Unit) = apply { builder(bytes.duplicate()) }
        override fun mapAndWrite_ints(builder: IntBuffer.() -> Unit) = apply { builder(bytes.asIntBuffer()) }
        override fun mapAndRead(builder: FloatBuffer.() -> Unit) = apply { builder(bytes.asFloatBuffer()) }
        override fun mapAndRead_bytes(builder: ByteBuffer.() -> Unit) = apply { builder(bytes.duplicate()) }
        override fun mapAndRead_ints(builder: IntBuffer.() -> Unit) = apply { builder(bytes.asIntBuffer()) }
    }

    @Test
    fun legacyUnbindClearsTheBackingSharedByRootAndChildViews() {
        val root = Structure().proxyTo(HostMemory())
        val child = Structure(root = root)
        child.mapAndWrite { put(0, 42f) }
        root.mapAndRead { assertEquals(42f, get(0)) }
        assertEquals(123L, child.getBuffer())

        // All buffer access must see the same unbind, including a write through a child view.
        child.proxying = null
        assertFailsWith<IllegalArgumentException> { root.mapAndRead { get(0) } }
        assertFailsWith<IllegalArgumentException> { child.mapAndWrite { put(0, 7f) } }
        assertFailsWith<IllegalArgumentException> { child.getBuffer() }
    }
}

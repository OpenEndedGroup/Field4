package afield.four.buffers

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.stdlib.invoke
import java.nio.FloatBuffer
import java.nio.IntBuffer

class FrameBufferGroup(
    val renderer: Renderer,
    val n: Int = renderer.numImages,
    val buffers: MutableList<VulkanBuffer> = mutableListOf()
) : HasBuffer, DynamicallyWriteable {

    var lastWritten = -1

    fun get(write : Boolean = false): VulkanBuffer {
        if (write) markAsWritten()
        var b = buffers[renderer.currentImage % buffers.size]
        return b
    }

    fun read(builder: FloatBuffer.() -> Unit) {
        get(write=false).mapAndRead(builder)
    }

    // it's very likely that this should be earlier than the write buffer?
    fun read_ints(builder: IntBuffer.() -> Unit) {
        get(write=false).mapAndRead_ints(builder)
    }

    fun getLastWritten() = if (lastWritten == -1) get() else buffers[lastWritten % buffers.size]

    fun markAsWritten() {
        lastWritten = renderer.currentImage % buffers.size
    }

    fun build(builder: (Int) -> VulkanBuffer) {
        buffers.clear()
        n {
            buffers.add(builder(it))
        }
    }

    override fun getBuffer() = get().buffer

    override fun getGPUAddress(cmd: CommandBuffer) = getLastWritten().getGPUAddress(cmd)

    override fun write(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        get(write=true).write(builder)

        // todo: should we take a write barrier to shader here ?
        return this
    }


    override fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        get(write=true).append(builder)

        // todo: should we take a write barrier to shader here ?
        return this
    }
}

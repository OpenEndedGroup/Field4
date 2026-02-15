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

    @Volatile
    var lastWritten = -1

    fun get(write : Boolean = false): VulkanBuffer {
        return getAt(renderer.currentImage, write)
    }

    fun getAt(imageIndex: Int, write: Boolean = false): VulkanBuffer {
        if (write) markAsWritten(imageIndex)
        return buffers[imageIndex % buffers.size]
    }

    fun read(builder: FloatBuffer.() -> Unit) {
        get(write=false).mapAndRead(builder)
    }

    // it's very likely that this should be earlier than the write buffer?
    fun read_ints(builder: IntBuffer.() -> Unit) {
        get(write=false).mapAndRead_ints(builder)
    }

    fun getLastWritten() = if (lastWritten == -1) get() else buffers[lastWritten % buffers.size]

    fun markAsWritten(imageIndex: Int = renderer.currentImage) {
        lastWritten = imageIndex % buffers.size
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

    override val cursor : Int
        get() = get(write=false).writePosition


    override fun append(builder: DynamicBuffer.() -> Unit): DynamicallyWriteable {
        get(write=true).append(builder)

        // todo: should we take a write barrier to shader here ?
        return this
    }
}

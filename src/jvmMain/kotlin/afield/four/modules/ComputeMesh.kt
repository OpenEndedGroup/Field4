// ComputeMesh.kt (sync2 version)
package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import org.lwjgl.vulkan.VkMemoryBarrier2
import java.nio.ByteBuffer
import kotlin.reflect.KProperty

interface ComputeTask {
    fun compute(inside: ComputeModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer)
}

fun computeTask(c: (inside: ComputeModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) -> Unit): ComputeTask {
    return object : ComputeTask {
        override fun compute(
            inside: ComputeModule,
            cmd: CommandBuffer,
            pushConstantBuffer: ByteBuffer
        ) {
            c(inside, cmd, pushConstantBuffer)
        }
    }
}

fun zeroBuffer(v: VulkanBuffer, barrier: Boolean = true) = computeTask { _, cmd, _ ->
    v.zero(cmd)
    transferMemoryBarrier(cmd, v)
}

/** TRANSFER->SHADER-READ (vertex/fragment/compute). Falls back to a MemoryBarrier2 if buffer==null. */
fun transferMemoryBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)

        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(
                    VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                )
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.buffer)
                .offset(0L)
                .size(buffer.requestedSize)

            dep.pBufferMemoryBarriers(b)
        } else {
            // Generic memory dependency (no specific buffer)
            val m = VkMemoryBarrier2.calloc(1, stack)
            m
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(
                    VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                )
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)

            dep.pMemoryBarriers(m)
        }

        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

/** COMPUTE write -> COMPUTE read / write. */
fun computeMemoryBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.buffer)
                .offset(0L)
                .size(buffer.requestedSize)

            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)

            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

/** COMPUTE write -> graphics shader read / write. */
fun graphicsMemoryBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.buffer)
                .offset(0L)
                .size(buffer.requestedSize)

            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)

            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

fun computeMemoryBarrier(cmd: CommandBuffer, buffer: Long = 0L, size: Long = VK_WHOLE_SIZE) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(0L)
                .size(size)

            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)

            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

fun graphicsMemoryBarrier(cmd: CommandBuffer, buffer: Long = 0L, size: Long = VK_WHOLE_SIZE) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(0L)
                .size(size)

            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)

            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

/** COMPUTE write -> HOST read. */
fun hostReadBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->
        val dep = VkDependencyInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
        if (buffer != null) {
            val b = VkBufferMemoryBarrier2.calloc(1, stack)
            b[0]
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                .dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer.buffer)
                .offset(0L)
                .size(buffer.requestedSize)

            dep.pBufferMemoryBarriers(b)
        } else {
            val m = VkMemoryBarrier2.calloc(1, stack)
            m[0]
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                .srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                .dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
            dep.pMemoryBarriers(m)
        }
        vkCmdPipelineBarrier2(cmd.vkCommandBuffer, dep)
    }
}

// this has to agree with the vertexbufferstructure that was used for this pipeline (right now)
class ComputeMesh(var renderer: Renderer = fromContext()) : ComputeTask {

    class FromUniform {
        operator fun getValue(inside: ComputeModulePointer, property: KProperty<*>): Structure {
            return inside.current.liveUniformStructures[property.name]
                ?: throw IllegalArgumentException(" can't find '${property.name}' in this module ${inside.current.liveUniformStructures.keys}")
        }
    }

    class BoundFromStructure<T> {
        operator fun setValue(inside: ComputeMesh, property: KProperty<*>, any: () -> T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            if (inside.push.offsets.containsKey(property.name))
                inside.push.bind(property.name, any as Function0<*>)
            else throw IllegalArgumentException(" can't find ${property.name} in mesh ${inside.inside.current.parameters.name} as a push_constant")
        }

        @Suppress("UNCHECKED_CAST")
        operator fun getValue(inside: ComputeMesh, property: KProperty<*>): () -> T {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            if (inside.push.offsets.containsKey(property.name))
                return inside.push.bindings[property.name] as () -> T
            else throw IllegalArgumentException(" can't find ${property.name} in mesh ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }

    constructor(renderer: Renderer = fromContext(), pointer: ComputeModulePointer = fromContext()) : this(renderer) {
        this.inside = pointer
    }

    lateinit var inside: ComputeModulePointer

    var update = afield.four.util.MapOfSequences<String>()
    var push = Structure()

    var x = 1
    var y = 1
    var z = 1

    internal fun initializePushConstantsFromModule(inside: ComputeModulePointer) {
        for (pc in inside.current.ci.pushConstants) {
            push.setMembers(pc.members)
        }
    }

    class FromStructure<T> {
        operator fun setValue(inside: ComputeMesh, property: KProperty<*>, any: T) {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            if (inside.push.offsets.containsKey(property.name)) inside.push.set(property.name, any as Any)
            else throw IllegalArgumentException(" can't find ${property.name} in mesh ${inside.inside.current.parameters.name} as a push_constant")
        }

        @Suppress("UNCHECKED_CAST")
        operator fun getValue(inside: ComputeMesh, property: KProperty<*>): T {
            if (inside.push.offsets.isEmpty()) inside.initializePushConstantsFromModule(inside.inside)
            if (inside.push.offsets.containsKey(property.name)) return inside.push.get(property.name) as T
            else throw IllegalArgumentException(" can't find ${property.name} in mesh ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }

    override fun compute(inside: ComputeModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
        if (inside != this.inside.current) lprintln("warning,graphics", "ComputeMesh was built in a different module $inside != ${this.inside.current}")
        update.update()

        MemoryStack.stackPush().use { _ ->
            fun pushConstantsForCurrentDispatch(): Boolean {
                if (pushConstantBuffer.limit() <= 0) return true
                for (pc in inside.ci.pushConstants) {
                    push.setMembers(pc.members)
                }
                try {
                    pushConstantBuffer.clear()
                    push.writeToBuffer(pushConstantBuffer, cmd) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                } catch (e: Exception) {
                    inside.parameters.errorHandler(e.message ?: "push constant error", e)
                    lprintln("error,graphics", "push constant error, not dispatching compute mesh : ${e.message}")
                    return false
                }
                pushConstantBuffer.clear()
                vkCmdPushConstants(
                    cmd.vkCommandBuffer,
                    inside.pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    pushConstantBuffer
                )
                return true
            }

            fun dispatchForCurrentWriter(dispatchX: Int?, dispatchY: Int?, dispatchZ: Int?) {
                val xx = dispatchX ?: x
                val yy = dispatchY ?: y
                val zz = dispatchZ ?: z
                if (xx * yy * zz > 0) {
                    vkCmdDispatch(cmd.vkCommandBuffer, xx, yy, zz)
                } else {
                    lprintln("warning,graphics", "ComputeMesh has no work to do $xx $yy $zz")
                }
            }

            try {
                forEachFanOutDispatch(
                    renderer = renderer,
                    push = push,
                    onNoFanOut = {
                        if (pushConstantsForCurrentDispatch()) {
                            dispatchForCurrentWriter(null, null, null)
                        }
                    },
                    onWriter = { writer ->
                        if (pushConstantsForCurrentDispatch()) {
                            dispatchForCurrentWriter(
                                dispatchX = writer.publication.dispatchX,
                                dispatchY = writer.publication.dispatchY,
                                dispatchZ = writer.publication.dispatchZ
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                inside.parameters.errorHandler(e.message ?: "fan-out dispatch error", e)
                lprintln("error,graphics", "fan-out dispatch error, not dispatching compute mesh : ${e.message}")
            }
        }
    }
}

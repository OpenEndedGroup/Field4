package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.MissingRefException
import afield.four.buffers.Structure
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.util.MapOfSequences
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferMemoryBarrier
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

fun transferMemoryBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {
    MemoryStack.stackPush().use { stack ->

        val barrier = VkBufferMemoryBarrier.calloc(1, stack).apply {
            it.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
            it.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
            it.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            it.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.buffer(buffer?.buffer ?: 0L)
            it.offset(0L)
            it.size(buffer?.requestedSize ?: 0L)
        }

        vkCmdPipelineBarrier(
            cmd.vkCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,       // srcStageMask
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT or VK_PIPELINE_STAGE_VERTEX_SHADER_BIT or VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, // dstStageMask
            0,                                    // dependencyFlags
            null,                                 // memoryBarriers
            barrier,                              // bufferMemoryBarriers
            null                                  // imageMemoryBarriers
        )
    }
}

fun computeMemoryBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {

    MemoryStack.stackPush().use { stack ->

        val barrier = VkBufferMemoryBarrier.calloc(1, stack).apply {
            it.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
            it.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
            it.dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
            it.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.buffer(buffer?.buffer ?: 0L)
            it.offset(0L)
            it.size(buffer?.requestedSize ?: 0L)
        }

        vkCmdPipelineBarrier(
            cmd.vkCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,       // srcStageMask
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, // dstStageMask
            0,                                    // dependencyFlags
            null,                                 // memoryBarriers
            barrier,                              // bufferMemoryBarriers
            null                                  // imageMemoryBarriers
        )
    }
}

fun hostReadBarrier(cmd: CommandBuffer, buffer: VulkanBuffer? = null) {

    MemoryStack.stackPush().use { stack ->

        val barrier = VkBufferMemoryBarrier.calloc(1, stack).apply {
            it.sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
            it.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
            it.dstAccessMask(VK_ACCESS_HOST_READ_BIT)
            it.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            it.buffer(buffer?.buffer ?: 0L)
            it.offset(0L)
            it.size(buffer?.requestedSize ?: 0L)
        }

        vkCmdPipelineBarrier(
            cmd.vkCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,       // srcStageMask
            VK_PIPELINE_STAGE_HOST_BIT, // dstStageMask
            0,                                    // dependencyFlags
            null,                                 // memoryBarriers
            barrier,                              // bufferMemoryBarriers
            null                                  // imageMemoryBarriers
        )
    }
}

// this has to agree with the vertexbufferstructure that was used for this pipeline (right now)
class ComputeMesh(var renderer: Renderer = fromContext()) : ComputeTask {

    class FromUniform {
        operator fun getValue(inside: ComputeModulePointer, property: KProperty<*>): Structure {
            return inside.current.liveUniformStructures[property.name]
                ?: throw IllegalArgumentException(" can't find '${property.name}' in this module, options are ${inside.current.liveUniformStructures.keys}")
        }
    }

    class BoundFromStructure<T> {
        operator fun setValue(inside: ComputeMesh, property: KProperty<*>, any: () -> T) {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name))
                inside.push.bind(property.name, any as Function0<*>)
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
        }

        operator fun getValue(inside: ComputeMesh, property: KProperty<*>): () -> T {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name))
                return inside.push.bindings[property.name] as () -> T
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }


    constructor(renderer: Renderer = fromContext(), pointer: ComputeModulePointer = fromContext()) : this(renderer) {
        this.inside = pointer
    }

    lateinit var inside: ComputeModulePointer

    var update = MapOfSequences<String>()

    var push = Structure()

    var x = 1;
    var y = 1;
    var z = 1;

    internal fun initializePushConstantsFromModule(inside: ComputeModulePointer) {
        for (pc in inside.current.ci.pushConstants) {
            push.setMembers(pc.members)
        }

    }

    class FromStructure<T> {
        operator fun setValue(inside: ComputeMesh, property: KProperty<*>, any: T) {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }

            if (inside.push.offsets.containsKey(property.name)) inside.push.set(property.name, any as Any)
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant")
        }

        operator fun getValue(inside: ComputeMesh, property: KProperty<*>): T {
            if (inside.push.offsets.isEmpty()) {
                inside.initializePushConstantsFromModule(inside.inside)
            }
            if (inside.push.offsets.containsKey(property.name)) return inside.push.get(property.name) as T
//            else if (property.name in inside.inside.current.liveUniformStructures) {
//                return inside.inside.current.liveUniformStructures[property.name] as T
//            }
            else throw IllegalArgumentException(" can't find ${property.name} in a mesh in this module ${inside.inside.current.parameters.name} as a push_constant or a UBO structure")
        }
    }


    override fun compute(inside: ComputeModule, cmd: CommandBuffer, pushConstantBuffer: ByteBuffer) {
        if (inside != this.inside.current) println("WARNING, ComputeMesh was built in a different module ${inside}!=${this.inside.current}")
        update.update()

        MemoryStack.stackPush().use { stack ->
            if (pushConstantBuffer.limit() > 0) {
                for (pc in inside.ci.pushConstants) {
                    push.setMembers(pc.members)
                }
                pushConstantBuffer.clear()
                push.writeToBuffer(pushConstantBuffer, cmd) {
                    throw MissingRefException("risk of GPU null ref `$it`")
                }
                pushConstantBuffer.clear()

                VK10.vkCmdPushConstants(
                    cmd.vkCommandBuffer,
                    inside.pipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    pushConstantBuffer
                )
            } else {
            }

            if (x * y * z > 0) {
                vkCmdDispatch(cmd.vkCommandBuffer, x, y, z);
            } else {
                println(" ComputeMesh has no work to do $x $y $z")
            }
        }
    }


}

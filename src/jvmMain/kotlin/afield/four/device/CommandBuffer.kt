package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.util.vkCheck

class CommandBuffer(commandPool: CommandPool, primary: Boolean, oneTimeSubmit: Boolean) {
    private val commandPool: CommandPool
    private val oneTimeSubmit: Boolean
    var vkCommandBuffer: VkCommandBuffer
    private val primary: Boolean

    init {
        this.commandPool = commandPool
        this.primary = primary
        this.oneTimeSubmit = oneTimeSubmit
        val vkDevice = commandPool.device.vkDevice

        MemoryStack.stackPush().use { stack ->
            val cmdBufAllocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool.vkCommandPool)
                .level(if (primary) VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY else VK10.VK_COMMAND_BUFFER_LEVEL_SECONDARY)
                .commandBufferCount(1)
            val pb = stack.mallocPointer(1)
            vkCheck(
                VK10.vkAllocateCommandBuffers(vkDevice, cmdBufAllocateInfo, pb),
                "Failed to allocate render command buffer"
            )
            vkCommandBuffer = VkCommandBuffer(pb[0], vkDevice)
        }
    }

    @JvmOverloads
    fun beginRecording(inheritanceInfo: InheritanceInfo? = null) {
        MemoryStack.stackPush().use { stack ->
            val cmdBufInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            if (oneTimeSubmit) {
                cmdBufInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            }
            if (!primary) {
                if (inheritanceInfo == null) {
                    throw RuntimeException("Secondary buffers must declare inheritance info")
                }
                val vkInheritanceInfo = VkCommandBufferInheritanceInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                    .renderPass(inheritanceInfo.vkRenderPass)
                    .subpass(inheritanceInfo.subPass)
                    .framebuffer(inheritanceInfo.vkFrameBuffer)
                cmdBufInfo.pInheritanceInfo(vkInheritanceInfo)
                cmdBufInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
            }
            vkCheck(
                VK10.vkBeginCommandBuffer(vkCommandBuffer, cmdBufInfo),
                "Failed to begin command buffer"
            )
        }
    }

    fun cleanup() {
        VK10.vkFreeCommandBuffers(
            commandPool.device.vkDevice, commandPool.vkCommandPool,
            vkCommandBuffer
        )
    }

    fun endRecording() {
        vkCheck(VK10.vkEndCommandBuffer(vkCommandBuffer), "Failed to end command buffer")
    }

    fun reset() {
        VK10.vkResetCommandBuffer(vkCommandBuffer, VK10.VK_COMMAND_BUFFER_RESET_RELEASE_RESOURCES_BIT)
    }

    @JvmRecord
    data class InheritanceInfo(val vkRenderPass: Long, val vkFrameBuffer: Long, val subPass: Int)
}

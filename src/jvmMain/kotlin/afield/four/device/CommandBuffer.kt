package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo
import org.lwjgl.vulkan.VkCommandBufferInheritanceRenderingInfo
import afield.four.util.vkCheck

class CommandBuffer(commandPool: CommandPool, primary: Boolean, oneTimeSubmit: Boolean) {
    private val commandPool: CommandPool
    private val oneTimeSubmit: Boolean
    var vkCommandBuffer: VkCommandBuffer
    private val primary: Boolean

    data class DynamicRenderingInheritance(
        val colorFormats: IntArray = intArrayOf(),
        val depthFormat: Int = VK10.VK_FORMAT_UNDEFINED,
        val stencilFormat: Int = VK10.VK_FORMAT_UNDEFINED,
        val viewMask: Int = 0,
        val samples: Int = VK10.VK_SAMPLE_COUNT_1_BIT
    )

    data class SecondaryInheritance(
        val renderPass: Long = 0,
        val subPass: Int = 0,
        val frameBuffer: Long = 0,
        val flags: Int = 0,
        val dynamicRendering: DynamicRenderingInheritance? = null
    )

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
    fun beginRecording(inheritanceInfo: SecondaryInheritance? = null) {
        MemoryStack.stackPush().use { stack ->
            val cmdBufInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            if (oneTimeSubmit) {
                cmdBufInfo.flags(cmdBufInfo.flags() or VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            }
            if (!primary) {
                if (inheritanceInfo == null) {
                    throw RuntimeException("Secondary buffers must declare inheritance info")
                }
                val vkInheritanceInfo = VkCommandBufferInheritanceInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                    .renderPass(inheritanceInfo.renderPass)
                    .subpass(inheritanceInfo.subPass)
                    .framebuffer(inheritanceInfo.frameBuffer)
                var flags = inheritanceInfo.flags
                inheritanceInfo.dynamicRendering?.let { dyn ->
                    val colorFormats = dyn.colorFormats
                    val vkDyn = VkCommandBufferInheritanceRenderingInfo.calloc(stack)
                        .sType(VK13.VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_RENDERING_INFO)
                        .viewMask(dyn.viewMask)
                        .rasterizationSamples(dyn.samples)
                        .depthAttachmentFormat(dyn.depthFormat)
                        .stencilAttachmentFormat(dyn.stencilFormat)
                    if (colorFormats.isNotEmpty()) {
                        vkDyn.pColorAttachmentFormats(stack.ints(*colorFormats))
                    }
                    vkInheritanceInfo.pNext(vkDyn.address())
                    flags = flags or VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT
                }
                if (inheritanceInfo.renderPass != 0L) {
                    flags = flags or VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT
                }
                if (flags != 0) {
                    cmdBufInfo.flags(cmdBufInfo.flags() or flags)
                }
                cmdBufInfo.pInheritanceInfo(vkInheritanceInfo)
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
}

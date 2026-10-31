package afield.graphics.vulkan.device

/**
 * A Vulkan command buffer allocated from a [CommandPool]: primary or secondary, one-time-submit or reusable, with [CommandBuffer.beginRecording], [CommandBuffer.endRecording] and [CommandBuffer.reset].
 *
 * It tracks whether recording is inside a render pass instance, so transfer commands are not recorded where Vulkan forbids them, and can defer host-read barriers on graphics buffers until a flush.
 */

import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.util.vkCheck
import afield.app.diagnostics.logging.lblock
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
import org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT
import org.lwjgl.vulkan.VK13.*

class CommandBuffer(commandPool: CommandPool, primary: Boolean, oneTimeSubmit: Boolean) {
    private val commandPool: CommandPool
    private val oneTimeSubmit: Boolean
    /** The raw `VkCommandBuffer` handle, for the LWJGL `vkCmd…` calls Field has no wrapper for. */
    var vkCommandBuffer: VkCommandBuffer
    private val primary: Boolean

    /**
     * True while commands are being recorded inside a render pass instance or dynamic rendering.
     * Vulkan forbids buffer and image barriers and transfer commands there, so code that needs one
     * tests this and defers it; whoever ends the rendering clears the flag before flushing.
     */
    var inRenderPassInstance: Boolean = false
    private val deferredGraphicsHostReadBuffers = LinkedHashSet<VulkanBuffer>()

    /**
     * The dynamic-rendering state a secondary buffer continues: the colour attachment formats, the
     * depth and stencil formats, the multiview mask and the sample count. These have to match the
     * `vkCmdBeginRendering` the primary buffer issued.
     */
    data class DynamicRenderingInheritance(
        val colorFormats: IntArray = intArrayOf(),
        val depthFormat: Int = VK10.VK_FORMAT_UNDEFINED,
        val stencilFormat: Int = VK10.VK_FORMAT_UNDEFINED,
        val viewMask: Int = 0,
        val samples: Int = VK10.VK_SAMPLE_COUNT_1_BIT
    )

    /**
     * What a secondary buffer declares about the render pass it records into — either a render pass
     * handle with its subpass and framebuffer, or `dynamicRendering` for the dynamic-rendering path.
     * Either one makes [beginRecording] set the render-pass-continue flag and [inRenderPassInstance].
     */
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

    /**
     * Start recording, discarding anything a previous recording deferred. A secondary buffer has to
     * pass `inheritanceInfo` and this throws without it; a primary buffer ignores the argument.
     * One-time-submit is set from the flag the buffer was created with.
     */
    @JvmOverloads
    fun beginRecording(inheritanceInfo: SecondaryInheritance? = null) {
        inRenderPassInstance = false
        deferredGraphicsHostReadBuffers.clear()
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
                inRenderPassInstance = (flags and VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT) != 0
                cmdBufInfo.pInheritanceInfo(vkInheritanceInfo)
            }
            vkCheck(
                VK10.vkBeginCommandBuffer(vkCommandBuffer, cmdBufInfo),
                "Failed to begin command buffer"
            )
        }
    }

    /** Return the buffer to the [CommandPool] it was allocated from. */
    fun cleanup() {
        lblock("info,vulkan", "freeing command buffer") {
            VK10.vkFreeCommandBuffers(
                commandPool.device.vkDevice, commandPool.vkCommandPool,
                vkCommandBuffer
            )
        }
    }

    /**
     * Finish recording. Any barrier left by [deferGraphicsHostReadBarrier] is emitted first, unless
     * recording is still inside a render pass instance — where emitting one is illegal, so it is
     * discarded instead. A secondary buffer that records a deferred barrier therefore needs the
     * primary to flush after `vkCmdEndRendering`.
     */
    fun endRecording() {
        if (!inRenderPassInstance) {
            flushDeferredGraphicsHostReadBarriers()
        }
        vkCheck(VK10.vkEndCommandBuffer(vkCommandBuffer), "Failed to end command buffer")
        inRenderPassInstance = false
        deferredGraphicsHostReadBuffers.clear()
    }

    /**
     * Clear the buffer for re-recording, releasing the memory it held back to its pool rather than
     * keeping it for the next recording. Any deferred barrier is dropped.
     */
    fun reset() {
        VK10.vkResetCommandBuffer(vkCommandBuffer, VK10.VK_COMMAND_BUFFER_RESET_RELEASE_RESOURCES_BIT)
        inRenderPassInstance = false
        deferredGraphicsHostReadBuffers.clear()
    }

    /**
     * Ask for a graphics-write to host-read barrier on `buffer`, to be recorded once the render
     * pass instance ends. This is how a shader's feedback buffer is made readable from Kotlin when
     * the only place that knows about the read is inside the rendering, where a buffer barrier is
     * not allowed. Repeated requests for the same buffer collapse into one.
     */
    fun deferGraphicsHostReadBarrier(buffer: VulkanBuffer) {
        deferredGraphicsHostReadBuffers += buffer
    }

    /**
     * Record every barrier [deferGraphicsHostReadBarrier] asked for as a single
     * `vkCmdPipelineBarrier2`, covering each buffer's whole requested size, and empty the list.
     * Call it after `vkCmdEndRendering` and after clearing [inRenderPassInstance]; it does nothing
     * when nothing was deferred.
     */
    fun flushDeferredGraphicsHostReadBarriers() {
        if (deferredGraphicsHostReadBuffers.isEmpty()) return

        MemoryStack.stackPush().use { stack ->
            val barriers = VkBufferMemoryBarrier2.calloc(deferredGraphicsHostReadBuffers.size, stack)
            deferredGraphicsHostReadBuffers.forEachIndexed { index, buffer ->
                barriers[index]
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                    .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                    .dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.buffer)
                    .offset(0L)
                    .size(buffer.requestedSize)
            }

            val dep = VkDependencyInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                .pBufferMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(vkCommandBuffer, dep)
        }

        deferredGraphicsHostReadBuffers.clear()
    }
}

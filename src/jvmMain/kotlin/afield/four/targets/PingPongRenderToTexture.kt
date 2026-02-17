package afield.four.targets

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.fromContext
import afield.four.texture.HasImageView
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.image_layout_transition
import afield.stdlib.f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY
import org.lwjgl.vulkan.VK10.vkCmdClearColorImage
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkImageSubresourceRange

/**
 * RenderToTexture variant that double-buffers the first color attachment.
 * Each frame it renders into one image while exposing the other via [otherImageView]
 * so modules can sample the previous frame without managing two RTT instances.
 */
class PingPongRenderToTexture(
    renderer: Renderer = fromContext(),
    parameters: RTTParameters.() -> Unit = {}
) : RenderToTexture(renderer, parameters) {

    private val pingImages: List<Image>
    private val pingImageViews: List<HasImageView>
    private val pingAttachmentViews: List<ImageView>
    private val pingImageHandles: List<Long>
    // DEPRECATED: Use renderer.stateTracker instead
    @Deprecated("Use renderer.stateTracker for layout tracking", ReplaceWith(""))
    private val pingLayouts = intArrayOf(VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_UNDEFINED)
    private val pingMipsInitialized = booleanArrayOf(false, false)
    private var writeIndex = 0

    val otherImageView: HasImageView = object : HasImageView{
        override val imageView: ImageView
            get() = pingImageViews[otherIndex].imageView
    }

    val thisImageView: HasImageView = object : HasImageView{
        override val imageView: ImageView
            get() = pingImageViews[writeIndex].imageView
    }


    private val otherIndex: Int
        get() = 1 - writeIndex

    init {
        require(colorImageHandles.isNotEmpty()) { "PingPongRenderToTexture requires at least one color attachment" }
        require(!this.parameters.externalColorViews.containsKey(0)) {
            "PingPongRenderToTexture does not support an external color view for attachment 0"
        }

        val baseImage = requireNotNull(colorImageRefs[0]) {
            "PingPongRenderToTexture attachment 0 must be internally owned"
        }

        val wantsMips = this.parameters.mipLevels > 1 && this.parameters.generateColorMipmaps
        val needsTransfer = wantsMips && colorFormatCanBlit.getOrElse(0) { false }

        val imageData = ImageData()
            .width(width)
            .height(height)
            .arrayLayers(colorLayerCount.coerceAtLeast(1))
            .usage(
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                        (if (this.parameters.colorSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0) or
                        (if (needsTransfer) VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0)
            )
            .format(colorFormats.first())
            .mipLevels(mipLevels)

        val pingImage = Image(renderer.device, imageData)
        val pingViewData = ImageViewData()
            .format(imageData.format)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .layerCount(imageData.arrayLayers)
            .mipLevels(mipLevels)

        if (pingViewData.layerCount > 1) pingViewData.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)

        val pingView = ImageView(renderer.device, pingImage, pingViewData)

        val pingAttachmentView =
            if (mipLevels == 1) pingView
            else {
                val avd = ImageViewData()
                    .format(imageData.format)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(imageData.arrayLayers)
                    .mipLevels(1)
                if (avd.layerCount > 1) avd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                ImageView(renderer.device, pingImage, avd)
            }

        val idleLayout =
            if (this.parameters.colorSampled) VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            else VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        initializeIdleImage(pingImage, idleLayout)

        pingImages = listOf(baseImage, pingImage)
        pingImageViews = listOf(mutableImageViewArray[0], pingView)
        pingAttachmentViews = listOf(attachmentImageViews[0], pingAttachmentView)
        pingImageHandles = listOf(colorImageHandles[0], pingImage.vkImage)

        // Initialize both images in the state tracker
        renderer.stateTracker.initImageState(baseImage.vkImage, colorLayout.getOrElse(0) { VK_IMAGE_LAYOUT_UNDEFINED })
        renderer.stateTracker.initImageState(pingImage.vkImage, idleLayout)
        
        // Keep the deprecated pingLayouts for backward compatibility
        pingLayouts[0] = colorLayout.getOrElse(0) { VK_IMAGE_LAYOUT_UNDEFINED }
        pingLayouts[1] = idleLayout
        pingMipsInitialized[0] = colorMipsInitialized.getOrElse(0) { false }
        pingMipsInitialized[1] = true
    }

    private fun initializeIdleImage(image: Image, finalLayout: Int) {
        val command = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = true)
        try {
            command.beginRecording()
            MemoryStack.stackPush().use { stack ->
                val rangeBuffer = VkImageSubresourceRange.calloc(1, stack)
                val fullRange = rangeBuffer[0]
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(colorLayerCount.coerceAtLeast(1))

                val canTransferClear = (image.imageData.usage and VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0
                if (canTransferClear) {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        image,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                        0L,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        fullRange,
                        renderer.stateTracker
                    )

                    val clearColor = VkClearColorValue.calloc(stack)
                        .float32(0, parameters.clearColor.x.f)
                        .float32(1, parameters.clearColor.y.f)
                        .float32(2, parameters.clearColor.z.f)
                        .float32(3, parameters.clearColor.w.f)

                    vkCmdClearColorImage(
                        command.vkCommandBuffer,
                        image.vkImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clearColor,
                        rangeBuffer
                    )

                    val (dstStage, dstAccess) =
                        if (finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT to VK_ACCESS_2_SHADER_READ_BIT
                        else
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT

                    image_layout_transition(
                        command.vkCommandBuffer,
                        image,
                        VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                        dstStage,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        dstAccess,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        finalLayout,
                        fullRange,
                        renderer.stateTracker
                    )
                } else {
                    val (dstStage, dstAccess) =
                        if (finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT to VK_ACCESS_2_SHADER_READ_BIT
                        else
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT

                    image_layout_transition(
                        command.vkCommandBuffer,
                        image,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        dstStage,
                        0L,
                        dstAccess,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        finalLayout,
                        fullRange,
                        renderer.stateTracker
                    )
                }
            }
            command.endRecording()
            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(stack.pointers(command.vkCommandBuffer), null, null, null, fence)
            }
            fence.fenceWait()
        } finally {
            fence.cleanup()
            command.cleanup()
        }
    }

    override fun beforeRender(index: Int) {
        val write = writeIndex
        colorImageRefs[0] = pingImages[write]
        colorImageHandles[0] = pingImageHandles[write]
        mutableImageViewArray[0] = pingImageViews[write]
        attachmentImageViews[0] = pingAttachmentViews[write]
        
        // Get layout from state tracker (it's the source of truth)
        val currentLayout = renderer.stateTracker.getImageLayout(pingImageHandles[write])
        colorLayout[0] = currentLayout
        pingLayouts[write] = currentLayout
        
        colorMipsInitialized[0] = pingMipsInitialized[write]
    }

    override fun afterRender(index: Int) {
        val write = writeIndex
        
        // Sync layout back from state tracker
        val currentLayout = renderer.stateTracker.getImageLayout(pingImageHandles[write])
        pingLayouts[write] = currentLayout
        colorLayout[0] = currentLayout
        
        pingMipsInitialized[write] = colorMipsInitialized[0]
        writeIndex = otherIndex
    }
}

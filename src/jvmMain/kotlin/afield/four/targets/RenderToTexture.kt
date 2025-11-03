package afield.four.targets

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.stdlib.Vec4
import afield.stdlib.f
import afield.stdlib.vec
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR

data class RTTParameters(
    var width: Int = 1024,
    var height: Int = 1024,
    var mipLevels: Int = 1,
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var clearColor: Vec4 = vec(0, 0, 0, 1),

    // --- Color QoL ---
    var clearFirst: Boolean = true,                 // if false, LOAD previous contents
    var colorStoreOp: Int = VK_ATTACHMENT_STORE_OP_STORE,
    var colorSampled: Boolean = true,               // controls usage & final transition

    // Automatic mip generation for color (when mipLevels > 1)
    var generateColorMipmaps: Boolean = true,
    var mipBlitFilter: Int = VK_FILTER_LINEAR,      // auto-downgraded to NEAREST if linear not supported

    // --- Depth/Stencil options ---
    var useDepth: Boolean = false,
    var depthFormat: Int = VK_FORMAT_D32_SFLOAT_S8_UINT, // depth+stencil by default
    var clearDepth: Float = 1f,
    var clearStencil: Int = 0,
    var depthLoadOp: Int = VK_ATTACHMENT_LOAD_OP_CLEAR,
    var depthStoreOp: Int = VK_ATTACHMENT_STORE_OP_STORE,
    var depthSampled: Boolean = false               // set true only if you intend to sample the depth later
)

/**
 * single-buffered image (with multi-buffered commandbuffers).
 * Optional depth+stencil buffer for depth testing while rendering to texture.
 */
class RenderToTexture(
    val renderer: Renderer = fromContext(),
    parameters: RTTParameters.() -> Unit = {}
) : Target {

    var parameters = RTTParameters().apply(parameters)

    var imageView: ImageView
    val imageData: ImageData
    val image: Image

    // depth (optional)
    var depthImageView: ImageView? = null
    var depthImage: Image? = null

    // Track current layouts across frames (base color level and depth).
    private var colorLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED   // tracks mip level 0
    private var depthLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED

    // Track whether mips (levels 1..N-1) have ever been initialized (affects oldLayout for barriers)
    private var colorMipsInitialized: Boolean = false

    // Cached format capabilities & chosen filter
    private var colorFormatCanBlit: Boolean = true
    private var colorFormatLinearBlit: Boolean = true
    private var chosenMipFilter: Int = this.parameters.mipBlitFilter

    val width: Int get() = parameters.width
    val height: Int get() = parameters.height
    val mipLevels: Int get() = parameters.mipLevels

    var modules = mutableMapOf<String, ModulePointer>()

    var commandBuffers: Array<CommandBuffer>
    var fences: Array<afield.four.device.Fence>

    val numImages = renderer.swapChain.imageViews.size

    init {
        // --- Query blit/filter support for the color format and pick a safe filter ---
        MemoryStack.stackPush().use { stack ->
            val props = VkFormatProperties.calloc(stack)
            vkGetPhysicalDeviceFormatProperties(renderer.device.physicalDevice.vkPhysicalDevice, this.parameters.format, props)
            val feats = props.optimalTilingFeatures()
            val canSrc = (feats and VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0
            val canDst = (feats and VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0
            colorFormatCanBlit = canSrc && canDst
            colorFormatLinearBlit = (feats and VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0
            chosenMipFilter = when (this.parameters.mipBlitFilter) {
                VK_FILTER_LINEAR -> if (colorFormatLinearBlit) VK_FILTER_LINEAR else VK_FILTER_NEAREST
                else -> VK_FILTER_NEAREST
            }
        }

        // COLOR
        val wantsMips = this.parameters.mipLevels > 1 && this.parameters.generateColorMipmaps
        val needsTransfer = wantsMips && colorFormatCanBlit
        imageData = ImageData()
            .width(width)
            .height(height)
            .usage(
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                        (if (this.parameters.colorSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0) or
                        (if (needsTransfer) VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0)
            )
            .format(this.parameters.format)
            .mipLevels(mipLevels)

        image = Image(renderer.device, imageData)

        val imageViewData: ImageViewData =
            ImageViewData()
                .format(image.format)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevels(mipLevels)

        imageView = ImageView(renderer.device, image.vkImage, imageViewData)

        // DEPTH+STENCIL (optional)
        if (this.parameters.useDepth) {
            val depthUsage =
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or
                        (if (this.parameters.depthSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0)

            val depthImageData = ImageData()
                .width(width)
                .height(height)
                .usage(depthUsage)
                .format(this.parameters.depthFormat)
                .mipLevels(1)

            depthImage = Image(renderer.device, depthImageData)

            val depthAspect = aspectMaskForDepth(this.parameters.depthFormat)

            val depthViewData = ImageViewData()
                .format(this.parameters.depthFormat)
                .aspectMask(depthAspect)
                .mipLevels(1)

            depthImageView = ImageView(renderer.device, depthImage!!.vkImage, depthViewData)
        }

        // per-swapchain command buffers & fences
        commandBuffers = buildList(numImages) {
            for (i in 0 until numImages) add(CommandBuffer(renderer.commandPool, true, false))
        }.toTypedArray()

        fences = buildList(numImages) {
            for (i in 0 until numImages) add(afield.four.device.Fence(renderer.device, true))
        }.toTypedArray()
    }

    override fun render(command: CommandBuffer, index: Int) {
        MemoryStack.stackPush().use { stack ->
            // COLOR subresource range for mip 0 ONLY (we don't touch other mips during the attachment phase)
            val colorBaseRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(VK_REMAINING_ARRAY_LAYERS)

            // Initial COLOR transition for mip 0 based on tracked layout + ops
            if (!parameters.clearFirst) {
                val (srcStage, srcAccess) = when (colorLayout) {
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT to VK_ACCESS_SHADER_READ_BIT
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                    else ->
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT to 0
                }
                image_layout_transition(
                    command.vkCommandBuffer,
                    image.vkImage,
                    srcStage,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    srcAccess,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    colorLayout,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    colorBaseRange
                )
                colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            } else {
                // CLEAR path
                image_layout_transition(
                    command.vkCommandBuffer,
                    image.vkImage,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    0,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    colorBaseRange
                )
                colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            }

            // DEPTH (optional) subresource range + transition
            var depthRange: VkImageSubresourceRange? = null
            if (parameters.useDepth && depthImage != null) {
                val depthAspect = aspectMaskForDepth(parameters.depthFormat)
                depthRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(depthAspect)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                if (parameters.depthLoadOp == VK_ATTACHMENT_LOAD_OP_LOAD) {
                    val (srcStage, srcAccess) = when (depthLayout) {
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT to VK_ACCESS_SHADER_READ_BIT
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT to VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        else ->
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT to 0
                    }
                    image_layout_transition(
                        command.vkCommandBuffer,
                        depthImage!!.vkImage,
                        srcStage,
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                        srcAccess,
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        depthLayout,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        depthRange
                    )
                    depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                } else {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        depthImage!!.vkImage,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                        0,
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        depthRange
                    )
                    depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                }
            }

            // COLOR attachment info (KHR)
            val colorAttachmentInfo =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(imageView.vkImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .resolveMode(VK_RESOLVE_MODE_NONE)
                    .loadOp(if (parameters.clearFirst) VK_ATTACHMENT_LOAD_OP_CLEAR else VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(parameters.colorStoreOp)
                    .clearValue(
                        VkClearValue.calloc(stack).color(
                            VkClearColorValue.calloc(stack)
                                .float32(0, this.parameters.clearColor.x.f)
                                .float32(1, this.parameters.clearColor.y.f)
                                .float32(2, this.parameters.clearColor.z.f)
                                .float32(3, this.parameters.clearColor.w.f)
                        )
                    )

            // DEPTH attachment info (KHR) (optional)
            val depthAttachmentInfo =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)

            if (parameters.useDepth && depthImageView != null) {
                depthAttachmentInfo.imageView(depthImageView!!.vkImageView)
                depthAttachmentInfo.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                depthAttachmentInfo.resolveMode(VK_RESOLVE_MODE_NONE)
                depthAttachmentInfo.loadOp(parameters.depthLoadOp)
                depthAttachmentInfo.storeOp(parameters.depthStoreOp)
                depthAttachmentInfo.clearValue(
                    VkClearValue.calloc(stack).depthStencil(
                        VkClearDepthStencilValue.calloc(stack)
                            .depth(parameters.clearDepth)
                            .stencil(parameters.clearStencil)
                    )
                )
            } else {
                depthAttachmentInfo.imageView(0)
                depthAttachmentInfo.imageLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            }

            // Rendering info (KHR)
            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .layerCount(1)
                .pColorAttachments(colorAttachmentInfo)
                .pDepthAttachment(depthAttachmentInfo.get(0))

            // KHR dynamic rendering
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command.vkCommandBuffer, renderInfo)

            modules.values.forEach {
                it.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
            }

            KHRDynamicRendering.vkCmdEndRenderingKHR(command.vkCommandBuffer)

            // --- Automatic color mipmap recompute (if requested & supported) ---
            val doMips = parameters.generateColorMipmaps &&
                    parameters.mipLevels > 1 &&
                    parameters.colorSampled &&
                    colorFormatCanBlit
            if (doMips) {
                generateColorMipChain(
                    commandBuffer = command.vkCommandBuffer,
                    image = image.vkImage,
                    width = width,
                    height = height,
                    mipLevels = mipLevels,
                    filter = chosenMipFilter,
                    mipsWereInitialized = colorMipsInitialized
                )
                colorMipsInitialized = true
                colorLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL // after mipgen all mips are SRV
            } else if (parameters.colorSampled) {
                // No mip gen: transition ALL mips to SRV so samplers see a consistent layout
                val allLevelsRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(VK_REMAINING_ARRAY_LAYERS)

                image_layout_transition(
                    command.vkCommandBuffer,
                    image.vkImage,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    allLevelsRange
                )
                colorLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            } else {
                // Not sampling: keep mip 0 as color attachment
                colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            }

            // Transition DEPTH after render ONLY if we intend to sample it
            if (parameters.useDepth && parameters.depthSampled && depthImage != null && depthRange != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    depthImage!!.vkImage,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    depthRange
                )
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
            } else if (parameters.useDepth) {
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            }
        } // MemoryStack.use
    }

    @JvmName("plusAssign")
    operator fun plusAssign(m: Pair<String, ModulePointer>) {
        modules[m.first] = m.second
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m: Pair<String, Module>) {
        modules[m.first] = m.second.pointer()
    }

    override fun toString(): String {
        val ds = if (parameters.useDepth) " +Depth" + (if (hasStencil(parameters.depthFormat)) "+Stencil" else "") else ""
        return "RenderToTexture[${width}x${height}$ds, mips=${mipLevels}]"
    }

    // --- Mip generation (color) ---

    /**
     * Recomputes the full mip chain from mip 0 → mip N-1 using vkCmdBlitImage.
     * Ends with ALL mips in SHADER_READ_ONLY_OPTIMAL.
     *
     * Assumes: mip 0 is in COLOR_ATTACHMENT_OPTIMAL at call time (we just finished rendering).
     */
    private fun generateColorMipChain(
        commandBuffer: VkCommandBuffer,
        image: Long,
        width: Int,
        height: Int,
        mipLevels: Int,
        filter: Int,
        mipsWereInitialized: Boolean
    ) {
        MemoryStack.stackPush().use { stack ->
            // 1) Transition mip 0: COLOR_ATTACHMENT_OPTIMAL -> TRANSFER_SRC_OPTIMAL
            run {
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS)

                image_layout_transition(
                    commandBuffer,
                    image,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    range
                )
            }

            // 2) Transition mip 1..N-1 to TRANSFER_DST_OPTIMAL
            run {
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(1).levelCount(mipLevels - 1)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS)

                val (srcStage, srcAccess, oldLayout) =
                    if (mipsWereInitialized)
                        Triple(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    else
                        Triple(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_IMAGE_LAYOUT_UNDEFINED)

                image_layout_transition(
                    commandBuffer,
                    image,
                    srcStage,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    srcAccess,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    oldLayout,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    range
                )
            }

            // 3) Blit mip chain: level i-1 -> i
            var srcW = width
            var srcH = height
            for (i in 1 until mipLevels) {
                val dstW = if (srcW > 1) srcW / 2 else 1
                val dstH = if (srcH > 1) srcH / 2 else 1

                val blit = VkImageBlit.calloc(1, stack)
                blit.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(i - 1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                blit.srcOffsets(0).set(0, 0, 0)
                blit.srcOffsets(1).set(srcW, srcH, 1)

                blit.dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(i)
                    .baseArrayLayer(0)
                    .layerCount(1)
                blit.dstOffsets(0).set(0, 0, 0)
                blit.dstOffsets(1).set(dstW, dstH, 1)

                vkCmdBlitImage(
                    commandBuffer,
                    image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit,
                    filter
                )

                // Make mip i a source for the next iteration: DST -> SRC
                val dstToSrc = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(i).levelCount(1)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS)

                image_layout_transition(
                    commandBuffer,
                    image,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstToSrc
                )

                srcW = dstW
                srcH = dstH
            }

            // 4) Transition ALL mips to SHADER_READ_ONLY_OPTIMAL
            run {
                val allLevels = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(mipLevels)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS)

                image_layout_transition(
                    commandBuffer,
                    image,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT or VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, // valid for mip 0 and last src; others are also SRC after loop
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    allLevels
                )
            }
        }
    }
}

// --- helpers ---

private fun hasStencil(format: Int): Boolean =
    format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT

private fun aspectMaskForDepth(format: Int): Int =
    VK_IMAGE_ASPECT_DEPTH_BIT or (if (hasStencil(format)) VK_IMAGE_ASPECT_STENCIL_BIT else 0)

// --- barrier util (leak-free with MemoryStack) ---

fun image_layout_transition(
    command_buffer: VkCommandBuffer,
    image: Long,
    src_stage_mask: Int,
    dst_stage_mask: Int,
    src_access_mask: Int,
    dst_access_mask: Int,
    old_layout: Int,
    new_layout: Int,
    sub_resource_range: VkImageSubresourceRange
) {
    MemoryStack.stackPush().use { stack ->
        val barrier = VkImageMemoryBarrier.calloc(1, stack)
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        barrier.srcAccessMask(src_access_mask)
        barrier.dstAccessMask(dst_access_mask)
        barrier.oldLayout(old_layout)
        barrier.newLayout(new_layout)
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        barrier.image(image)
        barrier.subresourceRange(sub_resource_range)

        vkCmdPipelineBarrier(
            command_buffer,
            src_stage_mask,
            dst_stage_mask,
            0,
            null,
            null,
            barrier
        )
    }
}

package afield.four.targets

import afield.Rect
import afield.Runloop
import afield.four.GpuImageCapture
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.buffers.MissingRefException
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.texture.*
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.Vec4
import afield.stdlib.f
import afield.stdlib.vec
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

data class RTTParameters(
    var width: Int = 1024,
    var height: Int = 1024,
    var mipLevels: Int = 1,
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var colorAttachments: Int = 1,
    var colorFormats: List<Int> = emptyList(),
    var colorLayerCount: Int = 1,                   // single layer count for all color attachments; 0 -> infer from external views
    var forceColorArrayView: Boolean = false,       // expose color attachments as sampler2DArray even for a single layer
    var viewports: List<afield.Rect> = emptyList(), // optional viewport array override
    var multiviewViewMask: Int = 0,                 // non-zero enables VK_KHR_multiview / dynamic-rendering multiview
    var externalColorViews: Map<Int, HasImageView> = emptyMap(), // optional externally-owned attachments by slot
    var clearColor: Vec4 = vec(0, 0, 0, 1),

    // --- Color QoL ---
    var clearFirst: Boolean = true,                 // if false, LOAD previous contents
    var colorStoreOp: Int = VK_ATTACHMENT_STORE_OP_STORE,
    var colorSampled: Boolean = true,               // controls usage & final transition

    // Allow the produced color image to be used as a transfer source/destination.
    // Useful for mirroring / copying into OpenXR swapchains.
    var colorTransferSrc: Boolean = true,
    var colorTransferDst: Boolean = false,

    // Automatic mip generation for color (when mipLevels > 1)
    var generateColorMipmaps: Boolean = true,
    var mipBlitFilter: Int = VK_FILTER_LINEAR,      // auto-downgraded to NEAREST if linear not supported

    // --- Depth/Stencil options ---
    var useDepth: Boolean = true,
    var depthFormat: Int = Options.defaultDepthFormat,//Options.defaultDepthFormat, // depth+stencil by default
    var clearDepth: Float = 1f,
    var clearStencil: Int = 0,
    var depthLoadOp: Int = VK_ATTACHMENT_LOAD_OP_CLEAR,
    var depthStoreOp: Int = VK_ATTACHMENT_STORE_OP_STORE,
    var depthSampled: Boolean = false,              // set true only if you intend to sample the depth later
    var debugName: String = "",

    // Create color attachments with IOSurface-exportable backing (MoltenVK) so the RTT can be
    // handed zero-copy to a hardware video encoder (field-encode). Opt-in; macOS/MoltenVK only.
    var exportMetalIOSurface: Boolean = false,

    // Back color attachment 0 with an externally-created IOSurface (its IOSurfaceRef pointer),
    // instead of exporting one. Preferred for streaming: an IOSurfaceCreate-born surface carries a
    // real pixelFormat that CoreVideo/VideoToolbox can wrap (MoltenVK's export leaves it 0).
    // macOS/MoltenVK only; the surface's format/size must match this RTT's.
    var importMetalIOSurface: Long = 0,

    // Create color attachments with opaque-fd-exportable backing (VK_KHR_external_memory_fd) so
    // the RTT can be handed zero-copy to NVENC via CUDA external memory import (field-encode-linux).
    // Opt-in; Linux/NVIDIA only. See VulkanExternalMemory.kt.
    var exportOpaqueFd: Boolean = false,

    // Create color attachments with dma-buf-exportable backing and a driver-chosen DRM format
    // modifier, so the RTT can be handed to any dma-buf importer — including Field's own
    // external-browser receiver, which is how that receiver is testable without a browser.
    // Opt-in; Linux only. See LinuxDmaBufExport.kt.
    var exportDmaBuf: Boolean = false
)

/**
 * single-buffered offscreen target (with multi-buffered commandbuffers), supporting N color attachments.
 * Optional depth+stencil buffer for depth testing while rendering to texture.
 */
open class RenderToTexture(
    val renderer: Renderer = fromContext(),
    parameters: RTTParameters.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray, HasDefaultTextureSampler, PreparesSampledImages {

    var parameters = RTTParameters().apply(parameters)
    override var defaultSampler: TextureSampler? = null
    private val debugName = parametersDebugName()

    fun setDefaultSampler(
        anisotropyEnable: Boolean = true,
        filter: Int = VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK_FILTER_LINEAR) VK_SAMPLER_MIPMAP_MODE_LINEAR else VK_SAMPLER_MIPMAP_MODE_NEAREST,
        addressModeU: Int = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeV: Int = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW: Int = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    ): TextureSampler {
        val sampler = TextureSampler(
            renderer = renderer,
            mipLevels = mipLevels,
            anisotropyEnable = anisotropyEnable,
            filter = filter,
            mipmapMode = mipmapMode,
            addressModeU = addressModeU,
            addressModeV = addressModeV,
            addressModeW = addressModeW
        )
        defaultSampler = sampler
        return sampler
    }

    fun repeatU(
        anisotropyEnable: Boolean = true,
        filter: Int = VK_FILTER_LINEAR,
        mipmapMode: Int = if (filter == VK_FILTER_LINEAR) VK_SAMPLER_MIPMAP_MODE_LINEAR else VK_SAMPLER_MIPMAP_MODE_NEAREST
    ): TextureSampler = setDefaultSampler(
        anisotropyEnable = anisotropyEnable,
        filter = filter,
        mipmapMode = mipmapMode,
        addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT,
        addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
    )

    override val imageView: ImageView
        get() = imageViewArray.first().imageView

    val colorImageRefs: MutableList<Image?>          // null when external view has no underlying Image
    val colorImageHandles: MutableList<Long>         // always the underlying VkImage handle
    protected val mutableImageViewArray: MutableList<HasImageView>
    private val sampledMip0ImageViewArray: MutableList<HasImageView>
    protected val attachmentImageViews: MutableList<ImageView>
    override val imageViewArray: List<HasImageView>
        get() = if (usesFullSampledMipChain()) mutableImageViewArray else sampledMip0ImageViewArray
    val colorFormats: List<Int>
    val colorLayerCount: Int

    // depth (optional)
    var depthImageView: ImageView? = null
    var depthSampledImageView: ImageView? = null
    var depthImage: Image? = null

    // Track current layouts across frames (base color level and depth).
    protected var colorLayout: IntArray = intArrayOf()   // tracks mip level 0 per attachment
    private var depthLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    protected val sampledReadStageMask: Long =
        VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT or
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT or
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT

    // Track whether mips (levels 1..N-1) have ever been initialized (affects oldLayout for barriers)
    protected var colorMipsInitialized: BooleanArray = booleanArrayOf()

    // Cached format capabilities & chosen filter
    protected var colorFormatCanBlit: BooleanArray = booleanArrayOf()
    protected var colorFormatLinearBlit: BooleanArray = booleanArrayOf()
    protected var chosenMipFilter: IntArray = intArrayOf()

    val width: Int get() = parameters.width
    val height: Int get() = parameters.height
    val mipLevels: Int get() = parameters.mipLevels

    private fun usesFullSampledMipChain(): Boolean {
        if (mipLevels <= 1) return true
        if (!parameters.generateColorMipmaps) return false
        return colorImageHandles.indices.all { idx ->
            colorMipsInitialized.getOrElse(idx) { false } &&
                    colorFormatCanBlit.getOrElse(idx) { false }
        }
    }

    var modules = mutableMapOf<String, ModulePointer>()

    private data class FileSaveRequest(
        val outputPath: String,
        val attachment: Int,
        val quality: Int,
        val completion: CompletableFuture<String>
    )

    private data class RecordedFileSave(
        val request: FileSaveRequest,
        val stagingBuffer: VulkanBuffer,
        val width: Int,
        val height: Int,
        val format: Int
    )

    private val fileSaveLock = Any()
    private val pendingFileSaves = ArrayDeque<FileSaveRequest>()

    private fun parametersDebugName(): String {
        val explicit = parameters.debugName.trim()
        if (explicit.isNotEmpty()) return explicit
        return "RenderToTexture#${nextRttDebugId.getAndIncrement()}@${allocationSite()}"
    }

    private fun allocationSite(): String {
        val ignoredPrefixes = listOf(
            "java.",
            "jdk.",
            "kotlin.",
            "afield.four.targets.RenderToTexture"
        )
        val frame = Throwable().stackTrace.firstOrNull { st ->
            ignoredPrefixes.none { st.className.startsWith(it) }
        } ?: return "unknown"
        return "${frame.fileName ?: frame.className}:${frame.lineNumber}"
    }

    private fun imageDebugName(kind: String, attachment: Int? = null): String =
        buildString {
            append(debugName)
            append(" ")
            append(kind)
            if (attachment != null) append("[").append(attachment).append("]")
            append(" ")
            append(width)
            append("x")
            append(height)
        }

    /**
     * Queue a non-blocking save of a color attachment to JPEG.
     *
     * The GPU copy is recorded the next time this target renders. The returned future completes
     * after readback, JPEG encoding, and file write finish on a background thread.
     */
    @JvmOverloads
    fun saveJpgAsync(outputPath: String, attachment: Int = 0, quality: Int = 90): CompletableFuture<String> {
        val completion = CompletableFuture<String>()
        if (attachment !in colorImageHandles.indices) {
            completion.completeExceptionally(
                IllegalArgumentException("attachment $attachment is out of range for $this")
            )
            return completion
        }
        synchronized(fileSaveLock) {
            pendingFileSaves.addLast(
                FileSaveRequest(
                    outputPath = outputPath,
                    attachment = attachment,
                    quality = quality.coerceIn(1, 100),
                    completion = completion
                )
            )
        }
        return completion
    }

    @JvmOverloads
    fun saveJpegAsync(outputPath: String, attachment: Int = 0, quality: Int = 90): CompletableFuture<String> =
        saveJpgAsync(outputPath, attachment, quality)

    @JvmOverloads
    fun launchJpgSave(outputPath: String, attachment: Int = 0, quality: Int = 90): CompletableFuture<String> =
        saveJpgAsync(outputPath, attachment, quality)

    private fun dequeueFileSaves(): List<FileSaveRequest> =
        synchronized(fileSaveLock) {
            if (pendingFileSaves.isEmpty()) emptyList()
            else buildList(pendingFileSaves.size) {
                while (pendingFileSaves.isNotEmpty()) add(pendingFileSaves.removeFirst())
            }
        }

    private fun recordPendingFileSaves(command: CommandBuffer) {
        val requests = dequeueFileSaves()
        if (requests.isEmpty()) return

        val recorded = mutableListOf<RecordedFileSave>()
        MemoryStack.stackPush().use { stack ->
            requests.forEach { request ->
                try {
                    require(request.attachment in colorImageHandles.indices) {
                        "attachment ${request.attachment} is out of range for $this"
                    }
                    val canTransfer =
                        parameters.colorTransferSrc ||
                                (parameters.generateColorMipmaps &&
                                        parameters.mipLevels > 1 &&
                                        colorFormatCanBlit.getOrElse(request.attachment) { false })
                    require(canTransfer) {
                        "RenderToTexture attachment ${request.attachment} was not created with transfer-source usage; set colorTransferSrc = true"
                    }

                    val format = colorFormats[request.attachment]
                    val bytesPerPixel = GpuImageCapture.bytesPerPixel(format, "RenderToTexture")
                    val staging = VulkanBuffer(
                        renderer = renderer,
                        requestedSize = width.toLong() * height.toLong() * bytesPerPixel.toLong(),
                        usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                    )

                    val range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(colorLayerCount.coerceAtLeast(1))

                    val oldLayout = colorLayout[request.attachment]
                    val (srcStage, srcAccess) = captureSourceStageAndAccess(oldLayout)
                    transitionColorAttachment(
                        command = command,
                        attachment = request.attachment,
                        srcStage = srcStage,
                        dstStage = VK_PIPELINE_STAGE_2_COPY_BIT,
                        srcAccess = srcAccess,
                        dstAccess = VK_ACCESS_2_TRANSFER_READ_BIT,
                        oldLayout = oldLayout,
                        newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        range = range
                    )

                    val copyRegion = VkBufferImageCopy.calloc(1, stack)
                    copyRegion[0]
                        .bufferOffset(0L)
                        .bufferRowLength(0)
                        .bufferImageHeight(0)
                        .imageSubresource(
                            VkImageSubresourceLayers.calloc(stack)
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0)
                                .baseArrayLayer(0)
                                .layerCount(1)
                        )
                        .imageOffset(VkOffset3D.calloc(stack).x(0).y(0).z(0))
                        .imageExtent(VkExtent3D.calloc(stack).width(width).height(height).depth(1))

                    vkCmdCopyImageToBuffer(
                        command.vkCommandBuffer,
                        colorImageHandles[request.attachment],
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        staging.buffer,
                        copyRegion
                    )

                    transitionColorAttachment(
                        command = command,
                        attachment = request.attachment,
                        srcStage = VK_PIPELINE_STAGE_2_COPY_BIT,
                        dstStage = srcStage,
                        srcAccess = VK_ACCESS_2_TRANSFER_READ_BIT,
                        dstAccess = srcAccess,
                        oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        newLayout = oldLayout,
                        range = range
                    )
                    colorLayout[request.attachment] = oldLayout

                    recorded += RecordedFileSave(request, staging, width, height, format)
                } catch (t: Throwable) {
                    request.completion.completeExceptionally(t)
                }
            }
        }

        if (recorded.isNotEmpty()) {
            renderer.afterCurrentGraphicsFrameComplete {
                recorded.forEach { capture ->
                    Runloop.miscPool.execute { completeFileSave(capture) }
                }
            }
        }
    }

    private fun captureSourceStageAndAccess(layout: Int): Pair<Long, Long> =
        when (layout) {
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                sampledReadStageMask to VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ->
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT to VK_ACCESS_2_TRANSFER_READ_BIT
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT to VK_ACCESS_2_TRANSFER_WRITE_BIT
            else -> VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT to 0L
        }

    override fun prepareSampledImagesFor(command: CommandBuffer) {
        if (!parameters.colorSampled) return
        if (colorImageHandles.isEmpty()) return

        MemoryStack.stackPush().use { stack ->
            colorImageHandles.forEachIndexed { idx, _ ->
                if (colorLayout.getOrElse(idx) { VK_IMAGE_LAYOUT_UNDEFINED } != VK_IMAGE_LAYOUT_UNDEFINED) {
                    return@forEachIndexed
                }

                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(colorLayerCount.coerceAtLeast(1))

                transitionColorAttachment(
                    command = command,
                    attachment = idx,
                    srcStage = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    dstStage = sampledReadStageMask,
                    srcAccess = 0L,
                    dstAccess = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                    newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    range = range
                )
                colorLayout[idx] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            }
        }
    }

    private fun transitionColorAttachment(
        command: CommandBuffer,
        attachment: Int,
        srcStage: Long,
        dstStage: Long,
        srcAccess: Long,
        dstAccess: Long,
        oldLayout: Int,
        newLayout: Int,
        range: VkImageSubresourceRange
    ) {
        val imageRef = colorImageRefs.getOrElse(attachment) { null }
        if (imageRef != null) {
            image_layout_transition(
                command.vkCommandBuffer,
                imageRef,
                srcStage,
                dstStage,
                srcAccess,
                dstAccess,
                oldLayout,
                newLayout,
                range
            )
        } else {
            image_layout_transition(
                command.vkCommandBuffer,
                colorImageHandles[attachment],
                srcStage,
                dstStage,
                srcAccess,
                dstAccess,
                oldLayout,
                newLayout,
                range
            )
        }
    }

    private fun completeFileSave(capture: RecordedFileSave) {
        try {
            val rgba = readCaptureRgba(capture)
            GpuImageCapture.writeJpeg(
                capture.request.outputPath,
                rgba,
                capture.width,
                capture.height,
                capture.request.quality
            )
            capture.request.completion.complete(capture.request.outputPath)
        } catch (t: Throwable) {
            capture.request.completion.completeExceptionally(t)
        } finally {
            capture.stagingBuffer.cleanup()
        }
    }

    private fun readCaptureRgba(capture: RecordedFileSave): ByteArray {
        var rgba: ByteArray? = null
        capture.stagingBuffer.mapAndRead_bytes {
            rgba = GpuImageCapture.readRgba(this, capture.format, capture.width, capture.height, "RenderToTexture")
        }
        return requireNotNull(rgba)
    }

    var commandBuffers: Array<CommandBuffer>
    var fences: Array<afield.four.device.Fence>

    val numImages = renderer.swapChain.imageViews.size

    init {

        val resolvedColorFormats =
            if (this.parameters.colorFormats.isNotEmpty()) this.parameters.colorFormats
            else List(this.parameters.colorAttachments) { this@RenderToTexture.parameters.format }
        require(resolvedColorFormats.isNotEmpty()) { "RenderToTexture needs at least one color format" }
        require(resolvedColorFormats.size == this.parameters.colorAttachments) {
            "colorFormats size (${resolvedColorFormats.size}) must match colorAttachments (${this.parameters.colorAttachments})"
        }
        colorFormats = resolvedColorFormats
        val explicitLayerCount = this.parameters.colorLayerCount
        val externalViews = this.parameters.externalColorViews
        val inferredLayerCount =
            if (explicitLayerCount > 0) explicitLayerCount
            else if (externalViews.isNotEmpty()) {
                val counts = externalViews.values.map { it.imageView.imageViewData.layerCount }
                require(counts.distinct().size == 1) {
                    "All external color attachments must share the same layerCount; got $counts"
                }
                counts.first()
            } else 1
        colorLayerCount = inferredLayerCount
        val multiviewLayerCount = Integer.bitCount(this.parameters.multiviewViewMask)
        if (this.parameters.multiviewViewMask != 0) {
            require(renderer.device.supportsMultiview) {
                "RenderToTexture requested multiviewViewMask=0x${this.parameters.multiviewViewMask.toString(16)}, but VK_KHR_multiview is not available"
            }
            require(colorLayerCount >= multiviewLayerCount) {
                "RenderToTexture multiviewViewMask=0x${this.parameters.multiviewViewMask.toString(16)} needs at least $multiviewLayerCount layers, but colorLayerCount=$colorLayerCount"
            }
            require(this.parameters.multiviewViewMask > 0) {
                "RenderToTexture multiviewViewMask must be positive when multiview is enabled"
            }
        }

        // --- Query blit/filter support for each color format and pick a safe filter ---
        MemoryStack.stackPush().use { stack ->
            colorFormatCanBlit = BooleanArray(resolvedColorFormats.size)
            colorFormatLinearBlit = BooleanArray(resolvedColorFormats.size)
            chosenMipFilter = IntArray(resolvedColorFormats.size)
            resolvedColorFormats.forEachIndexed { idx, fmt ->
                val props = VkFormatProperties.calloc(stack)
                vkGetPhysicalDeviceFormatProperties(renderer.device.physicalDevice.vkPhysicalDevice, fmt, props)
                val feats = props.optimalTilingFeatures()
                val canSrc = (feats and VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0
                val canDst = (feats and VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0
                val canBlit = canSrc && canDst
                colorFormatCanBlit[idx] = canBlit
                colorFormatLinearBlit[idx] = (feats and VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0
                chosenMipFilter[idx] = when (this.parameters.mipBlitFilter) {
                    VK_FILTER_LINEAR -> if (colorFormatLinearBlit[idx]) VK_FILTER_LINEAR else VK_FILTER_NEAREST
                    else -> VK_FILTER_NEAREST
                }
            }
        }

        val wantsMips = this.parameters.mipLevels > 1 && this.parameters.generateColorMipmaps
        require(externalViews.keys.all { it in colorFormats.indices }) {
            "externalColorViews keys must be within [0, ${colorFormats.lastIndex}]"
        }

        val tmpViews = MutableList<HasImageView?>(colorFormats.size) { null }
        val tmpSampledMip0Views = MutableList<HasImageView?>(colorFormats.size) { null }
        val tmpAttachmentViews = MutableList<ImageView?>(colorFormats.size) { null }
        val tmpImageRefs = MutableList<Image?>(colorFormats.size) { null }
        val tmpImageHandles = MutableList<Long>(colorFormats.size) { 0L }
        val forceColorArrayView = this.parameters.forceColorArrayView

        colorFormats.forEachIndexed { idx, fmt ->
            val external = externalViews[idx]
            if (external != null) {
                require(external.imageView.imageViewData.layerCount == colorLayerCount) {
                    "External color attachment $idx has layerCount=${external.imageView.imageViewData.layerCount} but expected $colorLayerCount"
                }
                tmpImageRefs[idx] = external.imageView.underlyingImage
                tmpImageHandles[idx] = external.imageView.image

                val sampledView =
                    if (forceColorArrayView && external.imageView.imageViewData.viewType != VK_IMAGE_VIEW_TYPE_2D_ARRAY) {
                        val ivd = ImageViewData()
                            .format(fmt)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseArrayLayer(external.imageView.imageViewData.baseArrayLayer)
                            .layerCount(colorLayerCount.coerceAtLeast(1))
                            .mipLevels(external.imageView.imageViewData.mipLevels)
                            .debugName(imageDebugName("sampled-array-view", idx))
                            .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                        ImageView(renderer.device, external.imageView.image, ivd)
                    } else {
                        external.imageView
                }
                tmpViews[idx] = sampledView
                tmpSampledMip0Views[idx] =
                    if (sampledView.imageView.imageViewData.mipLevels == 1) sampledView
                    else {
                        val ivd = ImageViewData()
                            .format(fmt)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseArrayLayer(sampledView.imageView.imageViewData.baseArrayLayer)
                            .layerCount(colorLayerCount.coerceAtLeast(1))
                            .mipLevels(1)
                            .debugName(imageDebugName("sampled-mip0-view", idx))
                            .viewType(sampledView.imageView.imageViewData.viewType)
                        ImageView(renderer.device, external.imageView.image, ivd)
                    }

                val attachmentView =
                    if (mipLevels == 1 && !forceColorArrayView) external.imageView
                    else {
                        val ivd = ImageViewData()
                            .format(fmt)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseArrayLayer(external.imageView.imageViewData.baseArrayLayer)
                            .layerCount(colorLayerCount.coerceAtLeast(1))
                            .mipLevels(1)
                            .debugName(imageDebugName("attachment-view", idx))
                        if (ivd.layerCount > 1 || forceColorArrayView) ivd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                        ImageView(renderer.device, external.imageView.image, ivd)
                    }
                tmpAttachmentViews[idx] = attachmentView
            } else {
                val needsMipTransfers = wantsMips && colorFormatCanBlit[idx]
                val data = ImageData()
                    .width(width)
                    .height(height)
                    .arrayLayers(colorLayerCount.coerceAtLeast(1))
                    .usage(
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                                (if (this.parameters.colorSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0) or
                                (if (this.parameters.colorTransferSrc || needsMipTransfers) VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0) or
                                (if (this.parameters.colorTransferDst || needsMipTransfers) VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0)
                    )
                    .format(fmt)
                    .mipLevels(mipLevels)
                    .debugName(imageDebugName("color", idx))
                    .aliasSingleLayerDescriptors(forceColorArrayView)
                data.exportMetalIOSurface = this.parameters.exportMetalIOSurface
                data.exportOpaqueFd = this.parameters.exportOpaqueFd
                data.exportDmaBuf = this.parameters.exportDmaBuf
                // Import applies to color attachment 0 only (a streamed RTT is single-attachment;
                // a ring is N separate RTTs, each importing its own surface).
                if (idx == 0) data.importMetalIOSurface = this.parameters.importMetalIOSurface

                val img = Image(renderer.device, data)
                val ivd = ImageViewData()
                    .format(data.format)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(data.arrayLayers)
                    .mipLevels(mipLevels)
                    .debugName(imageDebugName("sampled-view", idx))
                if (ivd.layerCount > 1 || forceColorArrayView) ivd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)

                val view = ImageView(renderer.device, img, ivd)
                tmpViews[idx] = view
                tmpSampledMip0Views[idx] =
                    if (mipLevels == 1) view
                    else {
                        val svd = ImageViewData()
                            .format(data.format)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .layerCount(data.arrayLayers)
                            .mipLevels(1)
                            .debugName(imageDebugName("sampled-mip0-view", idx))
                        if (svd.layerCount > 1 || forceColorArrayView) svd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                        ImageView(renderer.device, img, svd)
                    }
                tmpImageRefs[idx] = img
                tmpImageHandles[idx] = img.vkImage

                val attachmentView =
                    if (mipLevels == 1) view
                    else {
                        val avd = ImageViewData()
                            .format(data.format)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .layerCount(data.arrayLayers)
                            .mipLevels(1)
                            .debugName(imageDebugName("attachment-view", idx))
                        if (avd.layerCount > 1 || forceColorArrayView) avd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                        ImageView(renderer.device, img, avd)
                    }
                tmpAttachmentViews[idx] = attachmentView
            }
        }

        mutableImageViewArray = tmpViews.map { requireNotNull(it) }.toMutableList()
        sampledMip0ImageViewArray = tmpSampledMip0Views.map { requireNotNull(it) }.toMutableList()
        attachmentImageViews = tmpAttachmentViews.map { requireNotNull(it) }.toMutableList()
        colorImageRefs = tmpImageRefs
        colorImageHandles = tmpImageHandles
        colorLayout = IntArray(colorImageHandles.size) { VK_IMAGE_LAYOUT_UNDEFINED }
        colorMipsInitialized = BooleanArray(colorImageHandles.size) { false }

        // DEPTH+STENCIL (optional)
        if (this.parameters.useDepth) {
            val depthUsage =
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or
                        (if (this.parameters.depthSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0)

            val depthImageData = ImageData()
                .width(width)
                .height(height)
                .arrayLayers(colorLayerCount.coerceAtLeast(1))
                .usage(depthUsage)
                .format(this.parameters.depthFormat)
                .mipLevels(1)
                .debugName(imageDebugName("depth"))
                .aliasSingleLayerDescriptors(this.parameters.depthSampled && colorLayerCount > 1)

            depthImage = Image(renderer.device, depthImageData)

            val depthAspect = aspectMaskForDepth(this.parameters.depthFormat)

            val depthViewData = ImageViewData()
                .format(this.parameters.depthFormat)
                .aspectMask(depthAspect)
                .layerCount(depthImageData.arrayLayers)
                .mipLevels(1)
                .debugName(imageDebugName("depth-view"))
            if (depthViewData.layerCount > 1) depthViewData.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)

            depthImageView = ImageView(renderer.device, depthImage!!, depthViewData)
            depthSampledImageView =
                if (this.parameters.depthSampled && depthAspect != VK_IMAGE_ASPECT_DEPTH_BIT) {
                    val depthOnlyViewData = ImageViewData()
                        .format(this.parameters.depthFormat)
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .layerCount(depthImageData.arrayLayers)
                        .mipLevels(1)
                        .debugName(imageDebugName("depth-sampled-view"))
                    if (depthOnlyViewData.layerCount > 1) depthOnlyViewData.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                    ImageView(
                        renderer.device,
                        depthImage!!,
                        depthOnlyViewData
                    )
                } else {
                    depthImageView
                }
        }

        // per-swapchain command buffers & fences
        commandBuffers = buildList(numImages) {
            for (i in 0 until numImages) add(CommandBuffer(renderer.commandPool, true, false))
        }.toTypedArray()

        fences = buildList(numImages) {
            for (i in 0 until numImages) add(afield.four.device.Fence(renderer.device, true))
        }.toTypedArray()
    }

    protected open fun beforeRender(index: Int) {}

    protected open fun afterRender(index: Int) {}

    /**
     * Hook for subclasses that need to record transfer/upload commands *before* dynamic rendering begins.
     *
     * Called after any module/mesh buffer preparation, while `command.inRenderPassInstance == false`.
     */
    protected open fun prepareAdditionalResources(command: CommandBuffer, index: Int) {}

    /**
     * Hook for subclasses that want to emit additional draw commands *inside* the dynamic rendering scope.
     *
     * Called after modules have rendered and viewport/scissor have been set to this RTT's configured viewports.
     */
    protected open fun renderAdditional(command: CommandBuffer, index: Int, stack: MemoryStack) {}

    data class ColorGrade(
        var exposure: Vec4 = vec(1f, 1f, 1f, 1f),
        var gamma: Vec4 = vec(1f, 1f, 1f, 1f),
        var offset: Vec4 = vec(0f, 0f, 0f, 0f),
        var sampleWeight: Float = 1f
    )

    /**
     * Simple on-screen blit of a color attachment from this RTT. Generates a fullscreen triangle
     * mesh internally (no vertex inputs) and wires a small grading push-constant block.
     *
     * [viewport] clips the destination on the screen target, [sourceRect] crops the sampled source
     * texture, and the returned [ModulePointer] identifies the generated blit module.
     *
     * If [warper] is supplied, it should be the GLSL body of `vec2 warp(vec2 x, float t)`. `x` is
     * the unwarped NDC vertex position, `t` is `time.x`, and the return value is added as an NDC
     * offset. Warped blits use a viewport-local grid sized by [numSegmentsW] and [numSegmentsH],
     * so normal viewport/scissor clipping crops displaced vertices.
     */
    open fun blitToScreen(
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer {
        require(attachment in imageViewArray.indices) {
            "attachment $attachment is out of range for RenderToTexture with ${imageViewArray.size} color attachments"
        }
        val sourceProvider = object : HasImageViewArray, PreparesSampledImages {
            override val imageViewArray: List<HasImageView>
                get() = listOf(this@RenderToTexture.imageViewArray[attachment])

            override fun prepareSampledImagesFor(command: CommandBuffer) {
                this@RenderToTexture.prepareSampledImagesFor(command)
            }
        }
        return blitImageToScreen(
            sourceProvider = sourceProvider,
            sourceView = imageViewArray[attachment].imageView,
            renderer = renderer,
            target = target,
            name = name,
            viewport = viewport,
            sourceRect = sourceRect,
            blend = blend,
            additive = additive,
            sourceLayer = sourceLayer,
            warper = warper,
            numSegmentsW = numSegmentsW,
            numSegmentsH = numSegmentsH,
            configure = configure
        )
    }

    protected fun blitImageToScreen(
        sourceProvider: HasImageViewArray,
        sourceView: ImageView,
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer {
        require(numSegmentsW >= 1) { "numSegmentsW must be at least 1" }
        require(numSegmentsH >= 1) { "numSegmentsH must be at least 1" }
        val sourceIsArray = sourceView.imageViewData.viewType == VK_IMAGE_VIEW_TYPE_2D_ARRAY
        if (sourceIsArray) {
            require(sourceLayer in 0 until sourceView.imageViewData.layerCount) {
                "sourceLayer $sourceLayer is out of range for source image with ${sourceView.imageViewData.layerCount} layers"
            }
        } else {
            require(sourceLayer == 0) {
                "sourceLayer can only be non-zero when the source attachment is a 2D array image view"
            }
        }

        val grade = ColorGrade().apply(configure)
        val sampler = afield.four.texture.TextureSampler(
            renderer,
            sourceView.imageViewData.mipLevels,
            true,
            VK_FILTER_NEAREST,
            VK_SAMPLER_MIPMAP_MODE_NEAREST
        )

        val module = Module(afield.four.modules.parameters(renderer) {
            this.name = name
            color = 1
            depth = 1
            depthFormat = Options.defaultDepthFormat
            depthTest = false
            imageFormat = renderer.windowContext(target.windowIndex).swapChain.surfaceFormat.imageFormat
            this.blend = blend || additive
            if (additive) {
                colorBlendOp = VK_BLEND_OP_ADD
                alphaBlendOp = VK_BLEND_OP_ADD
            }
            this.viewport = viewport
            vertex = if (usesSegmentedBlit(warper, numSegmentsW, numSegmentsH)) {
                segmentedBlitVertexShader(warper)
            } else {
                blitVertexShader
            }
            fragment = if (sourceIsArray) blitArrayFragmentShader else blitFragmentShader
            textureResolver = { tex ->
                if (tex.name == "sourceTex") sourceProvider to sampler else null
            }
        }).pointer()

        val mesh = afield.four.modules.Mesh(renderer, module)
        if (usesSegmentedBlit(warper, numSegmentsW, numSegmentsH)) {
            buildSegmentedBlitMesh(mesh, module, numSegmentsW, numSegmentsH)
        } else {
            // Fullscreen triangle mesh (no attributes).
            mesh.resize(3, 0, module)
        }
        mesh.push["exposure"] = grade.exposure
        mesh.push["gamma"] = grade.gamma
        mesh.push["offset"] = grade.offset
        val src = sourceRect ?: Rect(0.0, 0.0, 1.0, 1.0)
        mesh.push["srcRect"] = vec(src.x.f, src.y.f, src.w.f, src.h.f)
        mesh.push["sampleWeight"] = grade.sampleWeight
        if (sourceIsArray) mesh.push["sourceLayer"] = sourceLayer.toFloat()
        var tick = 0L
        mesh.push.bind("time") {
            vec((tick++ / 120.0).toFloat(), 0f, 0f, 0f)
        }
        module.current.meshes[name] = mesh

        target += name to module
        return module
    }

    open fun debugBlitToScreen(
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        renderer = renderer,
        target = target,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun debugBlitToScreen(
        windowIndex: Int,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer = debugBlitToScreen(
        renderer = renderer,
        target = renderer.renderTargetFor(windowIndex),
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    fun debugBlitToScreen(
        window: Window,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-debug-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer = debugBlitToScreen(
        windowIndex = renderer.windowIndex(window),
        renderer = renderer,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    /**
     * Blits a color attachment to the renderer window identified by [windowIndex].
     *
     * This resolves the window's [RenderTarget] and delegates to the main
     * [blitToScreen] overload, preserving optional cropping, blending, warping,
     * and color grading.
     */
    fun blitToScreen(
        windowIndex: Int,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        renderer = renderer,
        target = renderer.renderTargetFor(windowIndex),
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    /**
     * Blits a color attachment to the screen for [window].
     *
     * The window is resolved through [renderer], then forwarded to the
     * window-index overload.
     */
    fun blitToScreen(
        window: Window,
        renderer: Renderer = this.renderer,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false,
        sourceLayer: Int = 0,
        warper: String? = null,
        numSegmentsW: Int = 1,
        numSegmentsH: Int = 1,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer = blitToScreen(
        windowIndex = renderer.windowIndex(window),
        renderer = renderer,
        attachment = attachment,
        name = name,
        viewport = viewport,
        sourceRect = sourceRect,
        blend = blend,
        additive = additive,
        sourceLayer = sourceLayer,
        warper = warper,
        numSegmentsW = numSegmentsW,
        numSegmentsH = numSegmentsH,
        configure = configure
    )

    companion object {
        private fun usesSegmentedBlit(warper: String?, numSegmentsW: Int, numSegmentsH: Int): Boolean =
            !warper.isNullOrBlank() || numSegmentsW > 1 || numSegmentsH > 1

        private fun buildSegmentedBlitMesh(
            mesh: afield.four.modules.Mesh,
            module: ModulePointer,
            numSegmentsW: Int,
            numSegmentsH: Int
        ) {
            val verticesW = numSegmentsW + 1
            val verticesH = numSegmentsH + 1
            mesh.resize(verticesW * verticesH, numSegmentsW * numSegmentsH * 2, module)
            mesh.write("inPosition") {
                for (y in 0 until verticesH) {
                    val v = y.toFloat() / numSegmentsH.toFloat()
                    for (x in 0 until verticesW) {
                        val u = x.toFloat() / numSegmentsW.toFloat()
                        put(u).put(v)
                    }
                }
            }
            mesh.t?.mapAndWrite_ints {
                for (y in 0 until numSegmentsH) {
                    for (x in 0 until numSegmentsW) {
                        val a = y * verticesW + x
                        val b = a + 1
                        val c = a + verticesW
                        val d = c + 1
                        put(a).put(b).put(c)
                        put(b).put(d).put(c)
                    }
                }
            }
        }

        private fun segmentedBlitVertexShader(warper: String?): String {
            val warpBody = warper?.takeIf { it.isNotBlank() } ?: "return vec2(0.0);"
            return """
                #version 460
                layout(location = 0) in vec2 inPosition;
                layout(location = 0) out vec2 fragUV;
                
                layout(push_constant) uniform Push {
                    vec4 exposure;
                    vec4 gamma;
                    vec4 offset;
                    vec4 srcRect;
                    float sampleWeight;
                    float sourceLayer;
                    vec4 time;
                } push;
                
                vec2 warp(vec2 x, float t) {
                    $warpBody
                }
                
                void main() {
                    vec2 ndc = inPosition * 2.0 - 1.0;
                    gl_Position = vec4(ndc + warp(ndc, push.time.x), 0.0, 1.0);
                    fragUV = inPosition;
                }
            """.trimIndent()
        }

        private val blitVertexShader = """
            #version 460
            layout(location = 0) out vec2 fragUV;
            
            void main() {
                // Fullscreen triangle: vertices 0,1,2 -> (-1,-1), (3,-1), (-1,3)
                vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
                fragUV = pos;
            }
        """.trimIndent()

        private val blitFragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            
            layout(set = 0, binding = 0) uniform sampler2D sourceTex;
            
            layout(push_constant) uniform Push {
                vec4 exposure;
                vec4 gamma;
                vec4 offset;
                vec4 srcRect; // xy = offset, zw = size in UV space
                float sampleWeight;
                vec4 time;
            } push;
            
            void main() {
                if (push.time.x < -1e30) discard;
                vec2 uv = push.srcRect.xy + fragUV * push.srcRect.zw;
                vec4 sampled = push.sampleWeight == 0.0 ? vec4(0.0, 0.0, 0.0, 1.0) : texture(sourceTex, uv);
                if (any(isnan(sampled)) || any(isinf(sampled))) {
                    sampled = vec4(0.0, 0.0, 0.0, 1.0);
                }
                vec4 color = sampled * push.sampleWeight;
                vec3 graded = color.rgb * push.exposure.rgb + push.offset.rgb;
                color.rgb = pow(max(graded, vec3(0.0)), push.gamma.rgb);
                outColor = color;
            }
        """.trimIndent()

        private val blitArrayFragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            
            layout(set = 0, binding = 0) uniform sampler2DArray sourceTex;
            
            layout(push_constant) uniform Push {
                vec4 exposure;
                vec4 gamma;
                vec4 offset;
                vec4 srcRect; // xy = offset, zw = size in UV space
                float sampleWeight;
                float sourceLayer;
                vec4 time;
            } push;
            
            void main() {
                if (push.time.x < -1e30) discard;
                vec2 uv = push.srcRect.xy + fragUV * push.srcRect.zw;
                int layerCount = textureSize(sourceTex, 0).z;
                float layer = clamp(push.sourceLayer, 0.0, float(max(layerCount - 1, 0)));
                vec4 sampled = push.sampleWeight == 0.0 ? vec4(0.0, 0.0, 0.0, 1.0) : texture(sourceTex, vec3(uv, layer));
                if (any(isnan(sampled)) || any(isinf(sampled))) {
                    sampled = vec4(0.0, 0.0, 0.0, 1.0);
                }
                vec4 color = sampled * push.sampleWeight;
                vec3 graded = color.rgb * push.exposure.rgb + push.offset.rgb;
                color.rgb = pow(max(graded, vec3(0.0)), push.gamma.rgb);
                outColor = color;
            }
        """.trimIndent()
    }

    override fun render(command: CommandBuffer, index: Int) {
        beforeRender(index)
        MemoryStack.stackPush().use { stack ->
            // COLOR subresource range for mip 0 ONLY (we don't touch other mips during the attachment phase)
        // Initial COLOR transition for mip 0 based on tracked layout + ops
            colorImageHandles.forEachIndexed { idx, img ->
                val imgRef = colorImageRefs.getOrNull(idx)
                val colorBaseRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(colorLayerCount.coerceAtLeast(1))

                val layout = colorLayout[idx]
                val (srcStage, srcAccess) = when (layout) {
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                        // Include CA/depth test stages to chain with broad read barriers on strict validators (MoltenVK/macOS).
                        (sampledReadStageMask or
                                VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT or
                                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT) to VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ->
                        VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT to VK_ACCESS_2_TRANSFER_READ_BIT
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                        VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT to VK_ACCESS_2_TRANSFER_WRITE_BIT
                    else ->
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT to 0L
                }

                if (imgRef != null) {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        imgRef,
                        srcStage,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        srcAccess,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        layout,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        colorBaseRange
                    )
                } else {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        img,
                        srcStage,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        srcAccess,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        layout,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        colorBaseRange
                    )
                }
                colorLayout[idx] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
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

                val (srcStage, srcAccess) = when (depthLayout) {
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL ->
                        sampledReadStageMask to VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                        (VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT) to
                                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                    else ->
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT to 0L
                }
                image_layout_transition(
                    command.vkCommandBuffer,
                    depthImage!!,
                    srcStage,
                    VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT,
                    srcAccess,
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    depthLayout,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    depthRange
                )
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            }

            // COLOR attachment info (KHR)
            val colorAttachmentInfo = VkRenderingAttachmentInfoKHR.calloc(colorImageHandles.size, stack)
            colorImageHandles.indices.forEach { i ->
                colorAttachmentInfo[i]
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(attachmentImageViews[i].vkImageView)
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
            }

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
            val renderViewMask = parameters.multiviewViewMask
            val renderLayers = if (renderViewMask != 0) 1 else colorLayerCount.coerceAtLeast(1)
            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .flags(0)
                .layerCount(renderLayers)
                .viewMask(renderViewMask)
                .pColorAttachments(colorAttachmentInfo)
                .pDepthAttachment(depthAttachmentInfo.get(0))

            // KHR dynamic rendering
            // Upload dirty vertex/index buffers before beginning rendering.
            modules.values.forEach { mp ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(command)
                    mesh.push.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                }
                mp.current.liveUniformStructures.values.forEach { structure ->
                    structure.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                }
            }

            modules.values.forEach { mp ->
                mp.current.before.values.forEach { target ->
                    target.render(command, index)
                }
            }

            // Allow subclasses to enqueue additional transfers/uploads before rendering begins.
            prepareAdditionalResources(command, index)

            // Texture providers may need layout transitions before descriptors are pushed.
            // Dynamic rendering does not allow image barriers inside the rendering instance.
            modules.values.forEach { it.prepareSampledImagesFor(command) }

            command.inRenderPassInstance = true
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command.vkCommandBuffer, renderInfo)

            val targetViewports = if (parameters.viewports.isNotEmpty()) parameters.viewports
            else listOf(Rect(0.0, 0.0, width.toDouble(), height.toDouble()))

            modules.values.forEach {
                setViewportAndScissorArray(command.vkCommandBuffer, targetViewports, stack)
                it.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
            }

            // Allow subclasses to emit additional drawing inside the dynamic rendering region.
            setViewportAndScissorArray(command.vkCommandBuffer, targetViewports, stack)
            renderAdditional(command, index, stack)

            KHRDynamicRendering.vkCmdEndRenderingKHR(command.vkCommandBuffer)
            command.inRenderPassInstance = false
            command.flushDeferredGraphicsHostReadBarriers()

            // --- Automatic color mipmap recompute (if requested & supported) ---
            colorImageHandles.forEachIndexed { idx, img ->
                val doMips = parameters.generateColorMipmaps &&
                        parameters.mipLevels > 1 &&
                        parameters.colorSampled &&
                        colorFormatCanBlit.getOrElse(idx) { false }
                if (doMips) {
                    generateColorMipChain(
                        commandBuffer = command.vkCommandBuffer,
                        image = img,
                        imageRef = colorImageRefs.getOrElse(idx) { null },
                        width = width,
                        height = height,
                        mipLevels = mipLevels,
                        filter = chosenMipFilter.getOrElse(idx) { VK_FILTER_LINEAR },
                        mipsWereInitialized = colorMipsInitialized.getOrElse(idx) { false },
                        arrayLayers = colorLayerCount.coerceAtLeast(1)
                    )
                    colorMipsInitialized[idx] = true
                    colorLayout[idx] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL // after mipgen all mips are SRV
                } else if (parameters.colorSampled) {
                    // No mip generation: only mip 0 was rendered. Keep descriptors on a mip-0 view
                    // and transition exactly that subresource to the sampled layout.
                    val baseLevelRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(colorLayerCount.coerceAtLeast(1))

                    transitionColorAttachment(
                        command = command,
                        attachment = idx,
                        srcStage = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        dstStage = sampledReadStageMask,
                        srcAccess = VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        dstAccess = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                        oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        range = baseLevelRange
                    )
                    colorLayout[idx] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                } else {
                    // Not sampling: keep mip 0 as color attachment
                    colorLayout[idx] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                }
            }

            // Transition DEPTH after render ONLY if we intend to sample it
            if (parameters.useDepth && parameters.depthSampled && depthImage != null && depthRange != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    depthImage!!,
                    VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                    sampledReadStageMask,
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    depthRange
                )
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
            } else if (parameters.useDepth) {
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            }

            recordPendingFileSaves(command)
        } // MemoryStack.use
        afterRender(index)
    }

    @JvmName("plusAssign")
    operator fun plusAssign(m: Pair<String, ModulePointer>) {
        validateModule(m.second.current)
        modules[m.first] = m.second
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m: Pair<String, Module>) {
        validateModule(m.second)
        modules[m.first] = m.second.pointer()
    }

    private fun validateModule(module: Module) {
        val moduleFormats = if (module.parameters.colorFormats.isNotEmpty())
            module.parameters.colorFormats else List(module.parameters.color) { module.parameters.imageFormat }
        val moduleColorCount = moduleFormats.size
        require(moduleColorCount == colorImageHandles.size) {
            "RenderToTexture expects ${colorImageHandles.size} color attachments, but module '${module.parameters.name}' declares ${moduleColorCount}"
        }
        require(moduleFormats == colorFormats) {
            "RenderToTexture color format mismatch for module '${module.parameters.name}': module=$moduleFormats target=$colorFormats. Call target.apply() inside module parameters."
        }
        require(module.parameters.viewMask == parameters.multiviewViewMask) {
            "RenderToTexture viewMask mismatch for module '${module.parameters.name}': module=0x${module.parameters.viewMask.toString(16)} target=0x${parameters.multiviewViewMask.toString(16)}. Call target.apply() inside module parameters."
        }
        val expectedViewportCount = parameters.viewports.size.coerceAtLeast(1)
        require(module.parameters.viewportCount >= expectedViewportCount) {
            "RenderToTexture viewport count mismatch for module '${module.parameters.name}': module=${module.parameters.viewportCount} target=$expectedViewportCount. Call target.apply() inside module parameters."
        }
    }

    private fun setViewportAndScissorArray(cmd: VkCommandBuffer, rects: List<Rect>, stack: MemoryStack) {
        val viewports = VkViewport.calloc(rects.size, stack)
        rects.forEachIndexed { i, r ->
            viewports[i]
                .x(r.x.toFloat())
                .y((r.y + r.h).toFloat()) // flip Y
                .width(r.w.toFloat())
                .height((-r.h).toFloat())
                .minDepth(0f).maxDepth(1f)
        }
        VK10.vkCmdSetViewport(cmd, 0, viewports)

        val scissors = VkRect2D.calloc(rects.size, stack)
        rects.forEachIndexed { i, r ->
            scissors[i]
                .offset(VkOffset2D.calloc(stack).x(r.x.toInt()).y(r.y.toInt()))
                .extent(VkExtent2D.calloc(stack).width(r.w.toInt()).height(r.h.toInt()))
        }
        VK10.vkCmdSetScissor(cmd, 0, scissors)
    }

    override fun toString(): String {
        val ds = if (parameters.useDepth) " +Depth" + (if (hasStencil(parameters.depthFormat)) "+Stencil" else "") else ""
        return "RenderToTexture[${width}x${height}, colors=${colorImageHandles.size}$ds, mips=${mipLevels}]"
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
        imageRef: Image?,
        width: Int,
        height: Int,
        mipLevels: Int,
        filter: Int,
        mipsWereInitialized: Boolean,
        arrayLayers: Int
    ) {
        MemoryStack.stackPush().use { stack ->
            val layerCount = arrayLayers.coerceAtLeast(1)
            for (layer in 0 until layerCount) {
                // 1) Transition mip 0: COLOR_ATTACHMENT_OPTIMAL -> TRANSFER_SRC_OPTIMAL (this layer only)
                run {
                    val range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(layer).layerCount(1)

                    if (imageRef != null) {
                        image_layout_transition(
                            commandBuffer,
                            imageRef,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            range
                        )
                    } else {
                        image_layout_transition(
                            commandBuffer,
                            image,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            range
                        )
                    }
                }

                // 2) Transition mip 1..N-1 to TRANSFER_DST_OPTIMAL for this layer
                run {
                    val range = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(1).levelCount(mipLevels - 1)
                        .baseArrayLayer(layer).layerCount(1)

                    val (srcStage, srcAccess, oldLayout) =
                        if (mipsWereInitialized)
                            Triple(
                                sampledReadStageMask,
                                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                            )
                        else
                            Triple(
                                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                                0L,
                                VK_IMAGE_LAYOUT_UNDEFINED
                            )

                    if (imageRef != null) {
                        image_layout_transition(
                            commandBuffer,
                            imageRef,
                            srcStage,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            srcAccess,
                            VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            oldLayout,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            range
                        )
                    } else {
                        image_layout_transition(
                            commandBuffer,
                            image,
                            srcStage,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            srcAccess,
                            VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            oldLayout,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            range
                        )
                    }
                }

                // 3) Blit mip chain: level i-1 -> i (single layer)
                var srcW = width
                var srcH = height
                for (i in 1 until mipLevels) {
                    val dstW = if (srcW > 1) srcW / 2 else 1
                    val dstH = if (srcH > 1) srcH / 2 else 1

                    val blit = VkImageBlit.calloc(1, stack)
                    blit.srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1)
                        .baseArrayLayer(layer)
                        .layerCount(1)
                    blit.srcOffsets(0).set(0, 0, 0)
                    blit.srcOffsets(1).set(srcW, srcH, 1)

                    blit.dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i)
                        .baseArrayLayer(layer)
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
                        .baseArrayLayer(layer).layerCount(1)

                    if (imageRef != null) {
                        image_layout_transition(
                            commandBuffer,
                            imageRef,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            dstToSrc
                        )
                    } else {
                        image_layout_transition(
                            commandBuffer,
                            image,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            dstToSrc
                        )
                    }

                    srcW = dstW
                    srcH = dstH
                }

                // 4) Transition ALL mips of this layer to SHADER_READ_ONLY_OPTIMAL
                run {
                    val allLevels = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(mipLevels)
                        .baseArrayLayer(layer).layerCount(1)

                    if (imageRef != null) {
                        image_layout_transition(
                            commandBuffer,
                            imageRef,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            sampledReadStageMask,
                            VK_ACCESS_2_TRANSFER_READ_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, // valid for mip 0 and last src; others are also SRC after loop
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            allLevels
                        )
                    } else {
                        image_layout_transition(
                            commandBuffer,
                            image,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            sampledReadStageMask,
                            VK_ACCESS_2_TRANSFER_READ_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, // valid for mip 0 and last src; others are also SRC after loop
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            allLevels
                        )
                    }
                }
            }
        }
    }
}

private val nextRttDebugId = AtomicInteger(1)

// --- helpers ---

private fun hasStencil(format: Int): Boolean =
    format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT

private fun aspectMaskForDepth(format: Int): Int =
    VK_IMAGE_ASPECT_DEPTH_BIT or (if (hasStencil(format)) VK_IMAGE_ASPECT_STENCIL_BIT else 0)

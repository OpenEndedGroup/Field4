package afield.four.targets

import afield.four.device.vkLayout
import afield.Rect
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.texture.HasDefaultTextureSampler
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.TextureSampler
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
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkImageSubresourceRange

/**
 * RenderToTexture variant that ring-buffers every color attachment.
 * Each frame it renders into one image set while exposing previous completed frames
 * via [otherImageView] and [previousFrameImageViews], so modules can sample
 * feedback history without managing several RTT instances.
 */
open class PingPongRenderToTexture(
    renderer: Renderer = fromContext(),
    frameCount: Int = 2,
    parameters: RTTParameters.() -> Unit = {}
) : RenderToTexture(renderer, parameters) {

    val frameCount: Int = frameCount.also {
        require(it >= 2) { "PingPongRenderToTexture requires frameCount >= 2" }
    }

    private val pingAttachmentCount = colorImageHandles.size
    private val pingImages: List<List<Image>>
    protected val pingImageViews: List<List<HasImageView>>
    private val pingAttachmentViews: List<List<ImageView>>
    private val pingImageHandles: List<LongArray>
    private val pingLayouts = Array(pingAttachmentCount) { IntArray(this.frameCount) { VK_IMAGE_LAYOUT_UNDEFINED } }
    private val pingMipsInitialized = Array(pingAttachmentCount) { BooleanArray(this.frameCount) { false } }
    protected var writeIndex = 0
    protected var latestIndex = 0
        private set
    protected var previousIndex = this.frameCount - 1
        private set

    open val otherImageView: HasImageView = otherImageViewFor(0)

    open val thisImageView: HasImageView = thisImageViewFor(0)

    val latestImageView: HasImageView = latestImageViewFor(0)

    val previousImageView: HasImageView = previousImageViewFor(0)

    val colorAttachmentCount: Int
        get() = pingAttachmentCount

    protected fun checkedAttachment(attachment: Int): Int {
        require(attachment in 0 until pingAttachmentCount) {
            "color attachment $attachment out of range; expected 0..${pingAttachmentCount - 1}"
        }
        return attachment
    }

    protected fun pingImageView(frame: Int, attachment: Int): ImageView =
        pingImageViews[checkedAttachment(attachment)][wrapFrameIndex(frame)].imageView

    open fun otherImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(otherIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    open fun thisImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(writeIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    fun latestImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(latestIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    fun previousImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(previousIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    /**
     * Previous completed frames in render-time order: index 0 is the most
     * recent completed frame, index 1 is the frame before that, and so on.
     *
     * Bind this to `sampler2D history[N]` where `N == frameCount - 1`.
     */
    val previousFrameImageViews: HasImageViewArray = object : HasImageViewArray, HasDefaultTextureSampler {
        override val imageViewArray: List<HasImageView>
            get() = (1 until this@PingPongRenderToTexture.frameCount).map { previousFrameImageView(it) }
        override var defaultSampler: TextureSampler?
            get() = this@PingPongRenderToTexture.defaultSampler
            set(value) {
                this@PingPongRenderToTexture.defaultSampler = value
            }
    }

    fun previousFrameImageViewsFor(attachment: Int): HasImageViewArray {
        checkedAttachment(attachment)
        return object : HasImageViewArray, HasDefaultTextureSampler {
            override val imageViewArray: List<HasImageView>
                get() = (1 until this@PingPongRenderToTexture.frameCount).map {
                    previousFrameImageView(it, attachment)
                }
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    /**
     * All frame slots in write order, starting with [writeIndex]. Outside the
     * update render this is oldest-to-newest for a warmed-up ring, with
     * [thisImageView] substituted at its physical slot.
     *
     * For a frame-cadenced target, adjacent elements are therefore adjacent in
     * history and the direct `otherImageView -> thisImageView` cadence blend is
     * the final adjacent pair in the array.
     */
    val frameImageViews: HasImageViewArray = object : HasImageViewArray, HasDefaultTextureSampler {
        override val imageViewArray: List<HasImageView>
            get() = (0 until this@PingPongRenderToTexture.frameCount).map { frameImageView(it) }
        override var defaultSampler: TextureSampler?
            get() = this@PingPongRenderToTexture.defaultSampler
            set(value) {
                this@PingPongRenderToTexture.defaultSampler = value
            }
    }

    fun frameImageViewsFor(attachment: Int): HasImageViewArray {
        checkedAttachment(attachment)
        return object : HasImageViewArray, HasDefaultTextureSampler {
            override val imageViewArray: List<HasImageView>
                get() = (0 until this@PingPongRenderToTexture.frameCount).map {
                    frameImageView(it, attachment)
                }
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    /**
     * Explicit alias for [frameImageViews], named for code that wants to state
     * that it depends on the write-order rotation.
     */
    val writeFrameImageViews: HasImageViewArray = object : HasImageViewArray, HasDefaultTextureSampler {
        override val imageViewArray: List<HasImageView>
            get() = (0 until this@PingPongRenderToTexture.frameCount).map { writeFrameImageView(it) }
        override var defaultSampler: TextureSampler?
            get() = this@PingPongRenderToTexture.defaultSampler
            set(value) {
                this@PingPongRenderToTexture.defaultSampler = value
            }
    }

    fun writeFrameImageViewsFor(attachment: Int): HasImageViewArray {
        checkedAttachment(attachment)
        return object : HasImageViewArray, HasDefaultTextureSampler {
            override val imageViewArray: List<HasImageView>
                get() = (0 until this@PingPongRenderToTexture.frameCount).map {
                    writeFrameImageView(it, attachment)
                }
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    protected open val frameImageViewWriteStartIndex: Int
        get() = writeIndex

    protected open val frameImageViewThisIndex: Int
        get() = writeIndex

    /**
     * Returns a frame by this target's default binding order.
     */
    open fun frameImageView(offset: Int): HasImageView = frameImageView(offset, 0)

    open fun frameImageView(offset: Int, attachment: Int): HasImageView = writeFrameImageView(offset, attachment)

    /**
     * Returns a frame by write-order offset. Offset 0 is [writeIndex], offset 1
     * is the next physical ring slot, and so on. When the selected physical slot
     * is the slot represented by [thisImageView], it resolves through
     * [thisImageView] so subclass dynamic semantics are preserved.
     */
    fun writeFrameImageView(offset: Int): HasImageView = writeFrameImageView(offset, 0)

    fun writeFrameImageView(offset: Int, attachment: Int): HasImageView {
        require(offset in 0 until frameCount) {
            "frame offset $offset out of range; expected 0..${frameCount - 1}"
        }
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() {
                    val physicalIndex = wrapFrameIndex(frameImageViewWriteStartIndex + offset)
                    return if (physicalIndex == frameImageViewThisIndex) {
                        thisImageViewFor(attachment).imageView
                    } else {
                        pingImageView(physicalIndex, attachment)
                    }
                }
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    /**
     * Returns the Nth previous completed frame relative to the next/current
     * render. `age == 1` is the most recent completed frame.
     */
    fun previousFrameImageView(age: Int): HasImageView = previousFrameImageView(age, 0)

    fun previousFrameImageView(age: Int, attachment: Int): HasImageView {
        require(age in 1 until frameCount) {
            "previous frame age $age out of range; expected 1..${frameCount - 1}"
        }
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(previousFrameIndex(age), attachment)
            override var defaultSampler: TextureSampler?
                get() = this@PingPongRenderToTexture.defaultSampler
                set(value) {
                    this@PingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    val previousFrameCount: Int
        get() = frameCount - 1

    protected val otherIndex: Int
        get() = previousFrameIndex(1)

    protected fun previousFrameIndex(age: Int): Int {
        require(age in 1 until frameCount) {
            "previous frame age $age out of range; expected 1..${frameCount - 1}"
        }
        return wrapFrameIndex(writeIndex - age)
    }

    protected fun wrapFrameIndex(index: Int): Int {
        val wrapped = index % frameCount
        return if (wrapped < 0) wrapped + frameCount else wrapped
    }

    private data class PingFrameResources(
        val image: Image,
        val sampledView: ImageView,
        val attachmentView: ImageView
    )
    private var additionalFrameBuildIndex: Int = 0

    init {
        require(colorImageHandles.isNotEmpty()) { "PingPongRenderToTexture requires at least one color attachment" }
        require(this.parameters.externalColorViews.isEmpty()) {
            "PingPongRenderToTexture does not support external color views; every color attachment is ring-buffered"
        }

        val baseImages = List(pingAttachmentCount) { attachment ->
            requireNotNull(colorImageRefs[attachment]) {
                "PingPongRenderToTexture attachment $attachment must be internally owned"
            }
        }

        val idleLayout =
            if (this.parameters.colorSampled) VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            else VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL

        fun imageDataFor(attachment: Int): ImageData {
            val wantsMips = this.parameters.mipLevels > 1 && this.parameters.generateColorMipmaps
            val needsTransfer = wantsMips && colorFormatCanBlit.getOrElse(attachment) { false }
            return ImageData()
                .width(width)
                .height(height)
                .arrayLayers(colorLayerCount.coerceAtLeast(1))
                .usage(
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                            (if (this.parameters.colorSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0) or
                            (if (this.parameters.colorTransferSrc) VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0) or
                            (if (this.parameters.colorTransferDst || this.parameters.colorSampled) VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0) or
                            (if (needsTransfer) VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0)
                )
                .format(colorFormats[attachment])
                .mipLevels(mipLevels)
                .debugName(pingDebugName("color", attachment))
                .aliasSingleLayerDescriptors(this.parameters.forceColorArrayView)
        }

        fun createAdditionalFrame(attachment: Int): PingFrameResources {
            val frame = additionalFrameBuildIndex
            val imageData = imageDataFor(attachment)
            val image = Image(renderer.device, imageData)
            val viewData = ImageViewData()
                .format(imageData.format)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .layerCount(imageData.arrayLayers)
                .mipLevels(mipLevels)
                .debugName(pingDebugName("sampled-view", attachment, frame))
            if (viewData.layerCount > 1 || this.parameters.forceColorArrayView) {
                viewData.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
            }

            val sampledView = ImageView(renderer.device, image, viewData)
            val attachmentView =
                if (mipLevels == 1) sampledView
                else {
                    val avd = ImageViewData()
                        .format(imageData.format)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(imageData.arrayLayers)
                        .mipLevels(1)
                        .debugName(pingDebugName("attachment-view", attachment, frame))
                    if (avd.layerCount > 1 || this.parameters.forceColorArrayView) {
                        avd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                    }
                    ImageView(renderer.device, image, avd)
                }

            return PingFrameResources(image, sampledView, attachmentView)
        }

        val additionalFrames = (0 until pingAttachmentCount).map { attachment ->
            (1 until this.frameCount).map { frame ->
                additionalFrameBuildIndex = frame
                createAdditionalFrame(attachment)
            }
        }

        pingImages = (0 until pingAttachmentCount).map { attachment ->
            listOf(baseImages[attachment]) + additionalFrames[attachment].map { it.image }
        }
        pingImageViews = (0 until pingAttachmentCount).map { attachment ->
            listOf(mutableImageViewArray[attachment]) + additionalFrames[attachment].map { it.sampledView }
        }
        pingAttachmentViews = (0 until pingAttachmentCount).map { attachment ->
            listOf(attachmentImageViews[attachment]) + additionalFrames[attachment].map { it.attachmentView }
        }
        pingImageHandles = (0 until pingAttachmentCount).map { attachment ->
            LongArray(this.frameCount) { frame ->
                if (frame == 0) colorImageHandles[attachment] else additionalFrames[attachment][frame - 1].image.vkImage
            }
        }

        initializePingImagesNow(idleLayout)
    }

    private fun pingDebugName(kind: String, attachment: Int, frame: Int = additionalFrameBuildIndex): String {
        val prefix = parameters.debugName.trim().ifEmpty { "PingPongRenderToTexture" }
        return "$prefix ping[$frame] $kind[$attachment] ${width}x${height}"
    }

    private fun recordIdleImageInitialization(command: CommandBuffer, image: Image, finalLayout: Int) {
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
                    fullRange
                )

                val clearColor = VkClearColorValue.calloc(stack)
                    .float32(0, parameters.clearColor.x.f)
                    .float32(1, parameters.clearColor.y.f)
                    .float32(2, parameters.clearColor.z.f)
                    .float32(3, parameters.clearColor.w.f)

                vkCmdClearColorImage(
                    command.vkCommandBuffer,
                    image.vkImage,
                    vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
                    clearColor,
                    rangeBuffer
                )

                val (dstStage, dstAccess) =
                    if (finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        sampledReadStageMask to VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
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
                    fullRange
                )
            } else {
                val (dstStage, dstAccess) =
                    if (finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        sampledReadStageMask to VK_ACCESS_2_SHADER_SAMPLED_READ_BIT
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
                    fullRange
                )
            }
        }
    }

    private fun initializePingImagesNow(finalLayout: Int) {
        val command = CommandBuffer(renderer.commandPool, true, true)
        val fence = Fence(renderer.device, true)
        try {
            command.beginRecording()
            pingImages.forEach { attachmentImages ->
                attachmentImages.forEach { image ->
                    recordIdleImageInitialization(command, image, finalLayout)
                }
            }
            command.endRecording()

            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(stack.pointers(command.vkCommandBuffer), null, null, null, fence)
            }
            fence.fenceWait()

            for (attachment in 0 until pingAttachmentCount) {
                for (frame in 0 until frameCount) {
                    pingLayouts[attachment][frame] = finalLayout
                    pingMipsInitialized[attachment][frame] = true
                }
                colorLayout[attachment] = finalLayout
                colorMipsInitialized[attachment] = true
            }
        } finally {
            fence.cleanup()
            command.cleanup()
        }
    }

    /**
     * Blits the latest ping-pong output to a screen target.
     *
     * Registers this object as the named source pre-target and removes duplicate
     * `others` entries before delegating to [RenderToTexture.blitToScreen].
     */
    override fun blitToScreen(
        renderer: Renderer,
        target: RenderTarget,
        attachment: Int,
        name: String,
        viewport: Rect?,
        sourceRect: Rect?,
        blend: Boolean,
        additive: Boolean,
        sourceLayer: Int,
        warper: String?,
        numSegmentsW: Int,
        numSegmentsH: Int,
        configure: ColorGrade.() -> Unit
    ): ModulePointer {
        target.preTargets["$name-source"] = this
        target.others.entries.removeIf { it.value === this }
        return super.blitToScreen(
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
    }

    override fun debugBlitToScreen(
        renderer: Renderer,
        target: RenderTarget,
        attachment: Int,
        name: String,
        viewport: Rect?,
        sourceRect: Rect?,
        blend: Boolean,
        additive: Boolean,
        sourceLayer: Int,
        warper: String?,
        numSegmentsW: Int,
        numSegmentsH: Int,
        configure: ColorGrade.() -> Unit
    ): ModulePointer {
        checkedAttachment(attachment)
        val latestView = latestImageViewFor(attachment)
        val sourceProvider = object : HasImageViewArray {
            override val imageViewArray: List<HasImageView>
                get() = listOf(latestView)
        }
        return blitImageToScreen(
            sourceProvider = sourceProvider,
            sourceView = latestView.imageView,
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

    override fun beforeRender(index: Int) {
        val write = writeIndex
        for (attachment in 0 until pingAttachmentCount) {
            colorImageRefs[attachment] = pingImages[attachment][write]
            colorImageHandles[attachment] = pingImageHandles[attachment][write]
            mutableImageViewArray[attachment] = pingImageViews[attachment][write]
            attachmentImageViews[attachment] = pingAttachmentViews[attachment][write]
            colorLayout[attachment] = pingLayouts[attachment][write]
            colorMipsInitialized[attachment] = pingMipsInitialized[attachment][write]
        }
    }

    override fun afterRender(index: Int) {
        val write = writeIndex
        val previous = wrapFrameIndex(write - 1)
        for (attachment in 0 until pingAttachmentCount) {
            pingLayouts[attachment][write] = colorLayout[attachment]
            pingMipsInitialized[attachment][write] = colorMipsInitialized[attachment]
        }
        latestIndex = write
        previousIndex = previous
        writeIndex = wrapFrameIndex(write + 1)
    }
}

/**
 * A [PingPongRenderToTexture] that only advances the frame ring once every
 * [framesPerPingPong] render calls. Between advances, [thisImageView] remains
 * the latest completed texture and [otherImageView] remains the previous one,
 * so downstream modules can crossfade from `other` to `this` with [crossfade].
 */
class FrameCadencedPingPongRenderToTexture(
    renderer: Renderer = fromContext(),
    framesPerPingPong: Int = 1,
    frameCount: Int = 2,
    parameters: RTTParameters.() -> Unit = {}
) : PingPongRenderToTexture(renderer, frameCount, parameters) {

    private var insidePingPongRender = false

    var framesPerPingPong: Int = framesPerPingPong.coerceAtLeast(1)
        set(value) {
            val safeValue = value.coerceAtLeast(1)
            field = safeValue
            framesSincePingPong = framesSincePingPong.coerceAtMost(safeValue - 1)
        }

    var framesSincePingPong: Int = 0
        private set

    var pingPongCount: Long = 0
        private set

    val framesUntilPingPong: Int
        get() = if (pingPongCount == 0L) 0 else (framesPerPingPong - 1 - framesSincePingPong).coerceAtLeast(0)

    /**
     * 0 immediately after a ping-pong render, 1 on the last held frame before
     * the next one. Intended for `mix(other, this, crossfade)`.
     */
    val crossfade: Float
        get() = if (framesPerPingPong <= 1) 1f
        else framesSincePingPong.toFloat() / (framesPerPingPong - 1).toFloat()

    /**
     * Similar to [crossfade], but normalized by the whole cadence period and
     * therefore stays below 1 before the next ping-pong.
     */
    val framePhase: Float
        get() = framesSincePingPong.toFloat() / framesPerPingPong.toFloat()

    override val frameImageViewThisIndex: Int
        get() = if (insidePingPongRender) writeIndex else latestIndex

    override val thisImageView: HasImageView = thisImageViewFor(0)

    override val otherImageView: HasImageView = otherImageViewFor(0)

    override fun thisImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(if (insidePingPongRender) writeIndex else latestIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@FrameCadencedPingPongRenderToTexture.defaultSampler
                set(value) {
                    this@FrameCadencedPingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    override fun otherImageViewFor(attachment: Int): HasImageView {
        checkedAttachment(attachment)
        return object : HasImageView, HasDefaultTextureSampler {
            override val imageView: ImageView
                get() = pingImageView(if (insidePingPongRender) otherIndex else previousIndex, attachment)
            override var defaultSampler: TextureSampler?
                get() = this@FrameCadencedPingPongRenderToTexture.defaultSampler
                set(value) {
                    this@FrameCadencedPingPongRenderToTexture.defaultSampler = value
                }
        }
    }

    override fun render(command: CommandBuffer, index: Int) {
        val shouldPingPong = pingPongCount == 0L || framesSincePingPong >= framesPerPingPong - 1
        if (shouldPingPong) {
            insidePingPongRender = true
            try {
                super.render(command, index)
            } finally {
                insidePingPongRender = false
            }
            pingPongCount++
            framesSincePingPong = 0
        } else {
            framesSincePingPong++
        }
    }

    override fun toString(): String {
        return "FrameCadencedPingPongRenderToTexture[${width}x${height}, frames=$frameCount, every=$framesPerPingPong, crossfade=$crossfade]"
    }
}

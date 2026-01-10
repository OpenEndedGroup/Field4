package afield.four.targets

import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.texture.*
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.Options
import afield.stdlib.Vec4
import afield.stdlib.f
import afield.stdlib.vec
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*

data class RTTParameters(
    var width: Int = 1024,
    var height: Int = 1024,
    var mipLevels: Int = 1,
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var colorAttachments: Int = 1,
    var colorFormats: List<Int> = emptyList(),
    var colorLayerCount: Int = 1,                   // single layer count for all color attachments; 0 -> infer from external views
    var viewports: List<afield.Rect> = emptyList(), // optional viewport array override
    var externalColorViews: Map<Int, HasImageView> = emptyMap(), // optional externally-owned attachments by slot
    var clearColor: Vec4 = vec(0, 0, 0, 1),

    // --- Color QoL ---
    var clearFirst: Boolean = true,                 // if false, LOAD previous contents
    var colorStoreOp: Int = VK_ATTACHMENT_STORE_OP_STORE,
    var colorSampled: Boolean = true,               // controls usage & final transition

    // Allow the produced color image to be used as a transfer source/destination.
    // Useful for mirroring / copying into OpenXR swapchains.
    var colorTransferSrc: Boolean = false,
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
    var depthSampled: Boolean = false               // set true only if you intend to sample the depth later
)

/**
 * single-buffered offscreen target (with multi-buffered commandbuffers), supporting N color attachments.
 * Optional depth+stencil buffer for depth testing while rendering to texture.
 */
open class RenderToTexture(
    val renderer: Renderer = fromContext(),
    parameters: RTTParameters.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    var parameters = RTTParameters().apply(parameters)

    override val imageView: ImageView
        get() = imageViewArray.first().imageView

    val colorImageRefs: MutableList<Image?>          // null when external view has no underlying Image
    val colorImageHandles: MutableList<Long>         // always the underlying VkImage handle
    protected val mutableImageViewArray: MutableList<HasImageView>
    override val imageViewArray: List<HasImageView>
        get() = mutableImageViewArray
    val colorFormats: List<Int>
    val colorLayerCount: Int

    // depth (optional)
    var depthImageView: ImageView? = null
    var depthImage: Image? = null

    // Track current layouts across frames (base color level and depth).
    protected var colorLayout: IntArray = intArrayOf()   // tracks mip level 0 per attachment
    private var depthLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED

    // Track whether mips (levels 1..N-1) have ever been initialized (affects oldLayout for barriers)
    protected var colorMipsInitialized: BooleanArray = booleanArrayOf()

    // Cached format capabilities & chosen filter
    protected var colorFormatCanBlit: BooleanArray = booleanArrayOf()
    protected var colorFormatLinearBlit: BooleanArray = booleanArrayOf()
    protected var chosenMipFilter: IntArray = intArrayOf()

    val width: Int get() = parameters.width
    val height: Int get() = parameters.height
    val mipLevels: Int get() = parameters.mipLevels

    var modules = mutableMapOf<String, ModulePointer>()

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
        val tmpImageRefs = MutableList<Image?>(colorFormats.size) { null }
        val tmpImageHandles = MutableList<Long>(colorFormats.size) { 0L }

        colorFormats.forEachIndexed { idx, fmt ->
            val external = externalViews[idx]
            if (external != null) {
                require(external.imageView.imageViewData.layerCount == colorLayerCount) {
                    "External color attachment $idx has layerCount=${external.imageView.imageViewData.layerCount} but expected $colorLayerCount"
                }
                tmpViews[idx] = external
                tmpImageRefs[idx] = external.imageView.underlyingImage
                tmpImageHandles[idx] = external.imageView.image
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

                val img = Image(renderer.device, data)
                val ivd = ImageViewData()
                    .format(data.format)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(data.arrayLayers)
                    .mipLevels(mipLevels)
                if (ivd.layerCount > 1) ivd.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)

                val view = ImageView(renderer.device, img, ivd)
                tmpViews[idx] = view
                tmpImageRefs[idx] = img
                tmpImageHandles[idx] = img.vkImage
            }
        }

        mutableImageViewArray = tmpViews.map { requireNotNull(it) }.toMutableList()
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
                .usage(depthUsage)
                .format(this.parameters.depthFormat)
                .mipLevels(1)

            depthImage = Image(renderer.device, depthImageData)

            val depthAspect = aspectMaskForDepth(this.parameters.depthFormat)

            val depthViewData = ImageViewData()
                .format(this.parameters.depthFormat)
                .aspectMask(depthAspect)
                .mipLevels(1)

            depthImageView = ImageView(renderer.device, depthImage!!, depthViewData)
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

    data class ColorGrade(
        var exposure: Vec4 = vec(1f, 1f, 1f, 1f),
        var gamma: Vec4 = vec(1f, 1f, 1f, 1f),
        var offset: Vec4 = vec(0f, 0f, 0f, 0f)
    )

    /**
     * Simple on-screen blit of a color attachment from this RTT. Generates a fullscreen triangle
     * mesh internally (no vertex inputs) and wires a small grading push-constant block.
     */
    fun blitToScreen(
        renderer: Renderer = this.renderer,
        target: RenderTarget = renderer.renderList,
        attachment: Int = 0,
        name: String = "rtt-blit",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        configure: ColorGrade.() -> Unit = {}
    ): ModulePointer {
        require(attachment in imageViewArray.indices) {
            "attachment $attachment is out of range for RenderToTexture with ${imageViewArray.size} color attachments"
        }

        val grade = ColorGrade().apply(configure)
        val sampler = afield.four.texture.TextureSampler(renderer, imageViewArray[attachment].imageView.imageViewData.mipLevels, true)

        val module = Module(afield.four.modules.parameters(renderer) {
            this.name = name
            color = 1
            depth = 1
            depthFormat = Options.defaultDepthFormat
            depthTest = false
            this.viewport = viewport
            vertex = blitVertexShader
            fragment = blitFragmentShader
            textureResolver = { tex ->
                if (tex.name == "sourceTex") afield.stdlib.fromViews(listOf(imageViewArray[attachment]), tex.name, sampler) else null
            }
        }).pointer()

        // Fullscreen triangle mesh (no attributes)
        val mesh = afield.four.modules.Mesh(renderer, module)
        mesh.resize(3, 0, module)
        mesh.push["exposure"] = grade.exposure
        mesh.push["gamma"] = grade.gamma
        mesh.push["offset"] = grade.offset
        val src = sourceRect ?: Rect(0.0, 0.0, 1.0, 1.0)
        mesh.push["srcRect"] = vec(src.x.f, src.y.f, src.w.f, src.h.f)
        module.current.meshes[name] = mesh

        target += name to module
        return module
    }

    companion object {
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
            } push;
            
            void main() {
                vec2 uv = push.srcRect.xy + fragUV * push.srcRect.zw;
                vec4 color = texture(sourceTex, uv);
                color.rgb = pow(color.rgb * push.exposure.rgb + push.offset.rgb, push.gamma.rgb);
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
            if (!parameters.clearFirst) {
                val (srcStage, srcAccess) = when (layout) {
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ->
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT to VK_ACCESS_2_SHADER_READ_BIT
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
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
            } else {
                // CLEAR path
                if (imgRef != null) {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        imgRef,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0L,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        colorBaseRange
                    )
                } else {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        img,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        0L,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        colorBaseRange
                    )
                }
                colorLayout[idx] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            }
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
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT to VK_ACCESS_2_SHADER_READ_BIT
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                            VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT to VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
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
                } else {
                    image_layout_transition(
                        command.vkCommandBuffer,
                        depthImage!!,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT,
                        0,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        depthRange
                    )
                    depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                }
            }

            // COLOR attachment info (KHR)
            val colorAttachmentInfo = VkRenderingAttachmentInfoKHR.calloc(colorImageHandles.size, stack)
            colorImageHandles.indices.forEach { i ->
                colorAttachmentInfo[i]
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(imageViewArray[i].imageView.vkImageView)
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
            val renderLayers = colorLayerCount.coerceAtLeast(1)

            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .flags(if (!Options.isMac) VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT else 0)
                .layerCount(renderLayers)
                .pColorAttachments(colorAttachmentInfo)
                .pDepthAttachment(depthAttachmentInfo.get(0))

            // KHR dynamic rendering
            // Upload dirty vertex/index buffers before beginning rendering.
            modules.values.forEach { mp ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(command)
                }
            }

            command.inRenderPassInstance = true
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command.vkCommandBuffer, renderInfo)

            // Set viewport/scissor array (default to full size)
            val vpRects = if (parameters.viewports.isNotEmpty()) parameters.viewports
            else listOf(Rect(0.0, 0.0, width.toDouble(), height.toDouble()))

            val viewports = VkViewport.calloc(vpRects.size, stack)
            vpRects.forEachIndexed { i, r ->
                viewports[i]
                    .x(r.x.toFloat())
                    .y((r.y + r.h).toFloat()) // flip Y
                    .width(r.w.toFloat())
                    .height((-r.h).toFloat())
                    .minDepth(0f).maxDepth(1f)
            }
            VK10.vkCmdSetViewport(command.vkCommandBuffer, 0, viewports)

            val scissors = VkRect2D.calloc(vpRects.size, stack)
            vpRects.forEachIndexed { i, r ->
                scissors[i]
                    .offset(VkOffset2D.calloc(stack).x(r.x.toInt()).y(r.y.toInt()))
                    .extent(VkExtent2D.calloc(stack).width(r.w.toInt()).height(r.h.toInt()))
            }
            VK10.vkCmdSetScissor(command.vkCommandBuffer, 0, scissors)

            modules.values.forEach {
                it.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
            }

            KHRDynamicRendering.vkCmdEndRenderingKHR(command.vkCommandBuffer)
            command.inRenderPassInstance = false

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
                    // No mip gen: transition ALL mips to SRV so samplers see a consistent layout
                    val allLevelsRange = MemoryStack.stackGet().let { st ->
                        VkImageSubresourceRange.calloc(st)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(mipLevels)
                            .baseArrayLayer(0)
                            .layerCount(VK_REMAINING_ARRAY_LAYERS)
                    }

                    val imgRef = colorImageRefs.getOrElse(idx) { null }
                    if (imgRef != null) {
                        image_layout_transition(
                            command.vkCommandBuffer,
                            imgRef,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                            VK_ACCESS_2_SHADER_READ_BIT,
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            allLevelsRange
                        )
                    } else {
                        image_layout_transition(
                            command.vkCommandBuffer,
                            img,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                            VK_ACCESS_2_SHADER_READ_BIT,
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            allLevelsRange
                        )
                    }
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
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL,
                    depthRange
                )
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
            } else if (parameters.useDepth) {
                depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            }
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
        val moduleColorCount = if (module.parameters.colorFormats.isNotEmpty())
            module.parameters.colorFormats.size else module.parameters.color
        require(moduleColorCount == colorImageHandles.size) {
            "RenderToTexture expects ${colorImageHandles.size} color attachments, but module '${module.parameters.name}' declares ${moduleColorCount}"
        }
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
                                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                                VK_ACCESS_2_SHADER_READ_BIT,
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
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_SHADER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, // valid for mip 0 and last src; others are also SRC after loop
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            allLevels
                        )
                    } else {
                        image_layout_transition(
                            commandBuffer,
                            image,
                            VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                            VK_ACCESS_2_TRANSFER_READ_BIT or VK_ACCESS_2_TRANSFER_WRITE_BIT,
                            VK_ACCESS_2_SHADER_READ_BIT,
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

// --- helpers ---

private fun hasStencil(format: Int): Boolean =
    format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT

private fun aspectMaskForDepth(format: Int): Int =
    VK_IMAGE_ASPECT_DEPTH_BIT or (if (hasStencil(format)) VK_IMAGE_ASPECT_STENCIL_BIT else 0)

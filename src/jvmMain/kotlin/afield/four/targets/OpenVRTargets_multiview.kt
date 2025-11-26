package afield.four.targets

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.pointer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.Options
import afield.stdlib.vec
import org.joml.primitives.Rectanglef
import org.lwjgl.openvr.VR
import org.lwjgl.openvr.VRCompositor
import org.lwjgl.openvr.VRSystem
import org.lwjgl.openvr.VRVulkanTextureData
import org.lwjgl.openvr.VRTextureBounds
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.KHRDynamicRendering

/**
 * Multiview OpenVR target: renders both eyes in a single 2-layer array image using viewMask=0b11,
 * then copies each layer to per-eye images for submission to the compositor.
 */
class OpenVRRenderTargetMultiview(
    val renderer: Renderer,
    val params: OpenVREyeParams.() -> Unit = {}
) : Target {

    private var initialized = false

    private lateinit var multiviewImage: Image
    private lateinit var multiviewView: ImageView
    private lateinit var submitImages: Array<Image>
    private lateinit var submitViews: Array<ImageView>

    private var colorLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED
    private var submitLayouts: IntArray = intArrayOf()

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set

    val eyeParams = OpenVREyeParams().apply(params)
    private val modules = linkedMapOf<String, afield.four.modules.ModulePointer>()

    init {
        initOpenVR()
    }

    private fun initOpenVR() {
        if (!VR.VR_IsHmdPresent()) throw IllegalStateException("No HMD present / SteamVR not running")

        MemoryStack.stackPush().use { s ->
            val pe = s.mallocInt(1)
            VR.VR_InitInternal(pe, VR.EVRApplicationType_VRApplication_Scene)
            val err = pe[0]
            require(err == VR.EVRInitError_VRInitError_None) {
                "OpenVR initialization failed: error=$err"
            }
        }

        MemoryStack.stackPush().use { s ->
            val w = s.mallocInt(1)
            val h = s.mallocInt(1)
            VRSystem.VRSystem_GetRecommendedRenderTargetSize(w, h)
            recommendedWidth = w[0]
            recommendedHeight = h[0]
        }

        // Multiview color image (2 layers)
        val imgData = ImageData()
            .width(recommendedWidth)
            .height(recommendedHeight)
            .format(eyeParams.format)
            .arrayLayers(2)
            .mipLevels(1)
            .usage(
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
                        VK_IMAGE_USAGE_SAMPLED_BIT
            )
        multiviewImage = Image(renderer.device, imgData)
        multiviewView = ImageView(
            renderer.device,
            multiviewImage.vkImage,
            ImageViewData()
                .format(eyeParams.format)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .layerCount(2)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
        )

        // Per-eye submission images (single layer each)
        submitImages = Array(2) {
            val d = ImageData()
                .width(recommendedWidth)
                .height(recommendedHeight)
                .format(eyeParams.format)
                .mipLevels(1)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
            Image(renderer.device, d)
        }
        submitViews = submitImages.map {
            ImageView(
                renderer.device,
                it.vkImage,
                ImageViewData().format(eyeParams.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(1)
            )
        }.toTypedArray()
        submitLayouts = IntArray(2) { VK_IMAGE_LAYOUT_UNDEFINED }

        initialized = true
    }

    @JvmName("plusAssign_modulePointer")
    operator fun plusAssign(named: Pair<String, afield.four.modules.ModulePointer>) {
        modules[named.first] = named.second
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(named: Pair<String, afield.four.modules.Module>) {
        modules[named.first] = named.second.pointer()
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (!initialized) return
        val cmd = command.vkCommandBuffer
        MemoryStack.stackPush().use { stack ->
            // Transition multiview image for rendering (all layers)
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(2)
            image_layout_transition(
                cmd,
                multiviewImage.vkImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                colorLayout,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                range
            )
            colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL

            val colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(multiviewView.vkImageView)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .resolveMode(VK_RESOLVE_MODE_NONE)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .clearValue(
                    VkClearValue.calloc(stack).color(
                        VkClearColorValue.calloc(stack)
                            .float32(0, eyeParams.clearColor.x)
                            .float32(1, eyeParams.clearColor.y)
                            .float32(2, eyeParams.clearColor.z)
                            .float32(3, eyeParams.clearColor.w)
                    )
                )

            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(recommendedWidth).height(recommendedHeight))
                )
                .layerCount(2)
                .viewMask(0b11)
                .pColorAttachments(colorAttach)

            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderInfo)
        }

        modules.values.forEach {
            it.render(command, Rectanglef(0f, 0f, recommendedWidth.toFloat(), recommendedHeight.toFloat()))
        }

        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)

        // Copy each layer to per-eye submit images
        MemoryStack.stackPush().use { stack ->
            val srcToCopy = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(2)

            // Prepare src for transfer
            image_layout_transition(
                cmd,
                multiviewImage.vkImage,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                colorLayout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                srcToCopy
            )
            colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL

            for (layer in 0 until 2) {
                val dstRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1)

                image_layout_transition(
                    cmd,
                    submitImages[layer].vkImage,
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                    0,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    submitLayouts[layer],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )

                val region = VkImageCopy.calloc(1, stack)
                region[0].srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(layer)
                    .layerCount(1)
                region[0].dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
                region[0].srcOffset().set(0, 0, 0)
                region[0].dstOffset().set(0, 0, 0)
                region[0].extent().set(recommendedWidth, recommendedHeight, 1)

                vkCmdCopyImage(
                    cmd,
                    multiviewImage.vkImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    submitImages[layer].vkImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region
                )

                // Prepare submit image for compositor read
                image_layout_transition(
                    cmd,
                    submitImages[layer].vkImage,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_NONE,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    0,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstRange
                )

                submitLayouts[layer] = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
            }
        }
    }

    fun submitAfterGraphics() {
        if (!initialized) return
        submitOneEye(VR.EVREye_Eye_Left, 0)
        submitOneEye(VR.EVREye_Eye_Right, 1)
    }

    private fun submitOneEye(eye: Int, idx: Int) {
        MemoryStack.stackPush().use { stack ->
            val v = VRVulkanTextureData.malloc(stack).apply {
                m_nImage(submitImages[idx].vkImage)
                m_pDevice(renderer.device.vkDevice.address())
                m_pPhysicalDevice(renderer.device.physicalDevice.vkPhysicalDevice.address())
                m_pInstance(renderer.instance.vkInstance.address())
                m_pQueue(renderer.graphQueue.vkQueue.address())
                m_nQueueFamilyIndex(renderer.graphQueue.queueFamilyIndex)
                m_nWidth(recommendedWidth)
                m_nHeight(recommendedHeight)
                m_nFormat(eyeParams.format)
                m_nSampleCount(1)
            }

            val tex = org.lwjgl.openvr.Texture.malloc(stack).apply {
                handle(v.address())
                eType(VR.ETextureType_TextureType_Vulkan)
                eColorSpace(VR.EColorSpace_ColorSpace_Auto)
            }

            val bounds = VRTextureBounds.malloc(stack).apply {
                uMin(0f); vMin(0f); uMax(1f); vMax(1f)
            }

            val err = VRCompositor.VRCompositor_Submit(eye, tex, bounds, VR.EVRSubmitFlags_Submit_Default)
            if (err != VR.EVRCompositorError_VRCompositorError_None) {
                System.err.println("OpenVR multiview submit error for eye=$eye: $err")
            }
        }
    }

    fun cleanup() {
        if (!initialized) return
        submitViews.forEach { it.cleanup() }
        submitImages.forEach { it.cleanup() }
        multiviewView.cleanup()
        multiviewImage.cleanup()
        VR.VR_ShutdownInternal()
        initialized = false
    }
}

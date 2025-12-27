package afield.four.targets

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.pointer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.image_layout_transition
import org.joml.primitives.Rectanglef
import org.lwjgl.openxr.*
import org.lwjgl.openxr.XrExtent2Di
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*

/**
 * Multiview OpenXR target: renders both eyes into a 2-layer array image with viewMask=0b11,
 * then copies each layer into the OpenXR swapchain image (array size 2) for submission.
 */
class OpenXRRenderTargetMultiview(
    val renderer: Renderer,
    val params: OpenXREyeParams.() -> Unit = {}
) : Target {

    private val eyeParams = OpenXREyeParams().apply(params)
    private val session = OpenXRSession(renderer, eyeParams)

    private lateinit var multiviewImage: Image
    private lateinit var multiviewView: ImageView
    private val swapchain = session.createSwapchain(arraySize = 2)
    private var acquiredIndex: Int = -1
    private var colorLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set

    private val modules = linkedMapOf<String, afield.four.modules.ModulePointer>()

    private var frameActive = false

    init {
        recommendedWidth = session.recommendedWidth
        recommendedHeight = session.recommendedHeight
        multiviewImage = Image(
            renderer.device,
            ImageData()
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
        )
        multiviewView = ImageView(
            renderer.device,
            multiviewImage,
            ImageViewData()
                .format(eyeParams.format)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .layerCount(2)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
        )

        renderer.renderStart["__openxr_multiview__"] = sequence {
            while (true) {
                frameActive = session.beginFrame()
                if (frameActive) acquireSwapchainImage()
                yield(true)
            }
        }

        renderer.renderEnd["__openxr_multiview__"] = sequence {
            while (true) {
                submitAfterGraphics()
                yield(true)
            }
        }
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
        if (!frameActive) return
        val cmd = command.vkCommandBuffer
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(2)

            image_layout_transition(
                cmd,
                multiviewImage,
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

        MemoryStack.stackPush().use { stack ->
            val srcRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(2)

            image_layout_transition(
                cmd,
                multiviewImage,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                colorLayout,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                srcRange
            )
            colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL

            if (acquiredIndex >= 0) {
                val dstRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(2)

                image_layout_transition(
                    cmd,
                    swapchain.images[acquiredIndex].image(),
                    VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                    0,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    swapchain.layouts[acquiredIndex],
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )

                for (layer in 0..1) {
                    val region = VkImageCopy.calloc(1, stack)
                    region[0].srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(layer)
                        .layerCount(1)
                    region[0].dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(layer)
                        .layerCount(1)
                    region[0].srcOffset().set(0, 0, 0)
                    region[0].dstOffset().set(0, 0, 0)
                    region[0].extent().set(recommendedWidth, recommendedHeight, 1)

                    vkCmdCopyImage(
                        cmd,
                        multiviewImage.vkImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        swapchain.images[acquiredIndex].image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region
                    )
                }

                image_layout_transition(
                    cmd,
                    swapchain.images[acquiredIndex].image(),
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_NONE,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    0,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    dstRange
                )
                swapchain.layouts[acquiredIndex] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            }
        }
    }

    private fun acquireSwapchainImage() {
        MemoryStack.stackPush().use { stack ->
            val acquire = XrSwapchainImageAcquireInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)
            val wait = XrSwapchainImageWaitInfo.calloc(stack)
                .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                .timeout(XR10.XR_INFINITE_DURATION)
            val idx = stack.mallocInt(1)
            OpenXRSession.checkXr(XR10.xrAcquireSwapchainImage(swapchain.handle, acquire, idx), "xrAcquireSwapchainImage(multiview)")
            acquiredIndex = idx[0]
            OpenXRSession.checkXr(XR10.xrWaitSwapchainImage(swapchain.handle, wait), "xrWaitSwapchainImage(multiview)")
        }
    }

    private fun submitAfterGraphics() {
        if (!frameActive) return
        MemoryStack.stackPush().use { stack ->
            if (acquiredIndex >= 0) {
                val release = XrSwapchainImageReleaseInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
                OpenXRSession.checkXr(XR10.xrReleaseSwapchainImage(swapchain.handle, release), "xrReleaseSwapchainImage(multiview)")
            }

            val viewCount = minOf(2, session.viewCount())
            val views = XrCompositionLayerProjectionView.calloc(viewCount, stack)
            for (i in 0 until viewCount) {
                val subImage = XrSwapchainSubImage.calloc(stack)
                    .swapchain(swapchain.handle)
                    .imageArrayIndex(i)
                    .imageRect(
                        XrRect2Di.calloc(stack)
                            .offset(XrOffset2Di.calloc(stack).x(0).y(0))
                            .extent(XrExtent2Di.calloc(stack).width(recommendedWidth).height(recommendedHeight))
                    )
                session.writeProjectionView(i, subImage, views[i], stack)
            }

            val projection = XrCompositionLayerProjection.calloc(stack)
                .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                .space(session.appSpace)
                .views(views)

            val layers = stack.mallocPointer(1)
            layers.put(0, projection.address()).flip()
            session.endFrame(layers)
        }
        acquiredIndex = -1
        frameActive = false
    }

    fun leftMVP(zNear: Float = 0.01f, zFar: Float = 1000f) = session.leftMVP(zNear, zFar)
    fun rightMVP(zNear: Float = 0.01f, zFar: Float = 1000f) = session.rightMVP(zNear, zFar)

    fun getControllerPose(role: ControllerRole) = session.getControllerPose(role)
    fun getControllerState(role: ControllerRole) = session.getControllerState(role)

    fun cleanup() {
        XR10.xrDestroySwapchain(swapchain.handle)
        multiviewView.cleanup()
        multiviewImage.cleanup()
        session.cleanup()
    }
}

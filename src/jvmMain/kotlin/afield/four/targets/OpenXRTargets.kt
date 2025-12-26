package afield.four.targets

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.image_layout_transition
import afield.four.util.Options
import afield.stdlib.vec
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.primitives.Rectanglef
import org.lwjgl.openxr.*
import org.lwjgl.openxr.KHRVulkanEnable.*
import org.lwjgl.openxr.XR10.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*

data class OpenXREyeParams(
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var clearColor: org.joml.Vector4f = vec(0.3, 0.5, 0.4, 1),
    var useDepth: Boolean = false,
    var depthFormat: Int = Options.defaultDepthFormat
)

/**
 * Wrapper for OpenXR swapchain images that exposes them as Field4 Image/ImageView objects.
 * Unlike OpenVR where we create our own images, OpenXR manages the swapchain images.
 */
private class OpenXRSwapchainWrapper(
    val renderer: Renderer,
    val xrSwapchain: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val p: OpenXREyeParams
) {
    // OpenXR swapchain images (managed by OpenXR runtime)
    private val swapchainImages = mutableListOf<Long>()
    
    // ImageView wrappers for each swapchain image
    private val imageViews = mutableListOf<ImageView>()
    
    // Current acquired image index
    var currentImageIndex: Int = -1
        private set

    init {
        // Enumerate swapchain images
        MemoryStack.stackPush().use { stack ->
            val countBuf = stack.mallocInt(1)
            xrEnumerateSwapchainImages(xrSwapchain, countBuf, null)
            val count = countBuf[0]
            
            val images = XrSwapchainImageVulkanKHR.calloc(count, stack)
            for (i in 0 until count) {
                images[i].type(XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR)
            }
            
            xrEnumerateSwapchainImages(xrSwapchain, countBuf, XrSwapchainImageBaseHeader.create(images.address()))
            
            for (i in 0 until count) {
                val vkImage = images[i].image()
                swapchainImages.add(vkImage)
                
                // Create ImageView for this swapchain image
                // Note: We wrap the OpenXR-provided VkImage without creating our own Image object
                val imageView = createImageViewForSwapchainImage(vkImage)
                imageViews.add(imageView)
            }
        }
    }

    private fun createImageViewForSwapchainImage(vkImage: Long): ImageView {
        MemoryStack.stackPush().use { stack ->
            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(vkImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .components { it
                    .r(VK_COMPONENT_SWIZZLE_R)
                    .g(VK_COMPONENT_SWIZZLE_G)
                    .b(VK_COMPONENT_SWIZZLE_B)
                    .a(VK_COMPONENT_SWIZZLE_A)
                }
                .subresourceRange { it
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
                }
            
            val pView = stack.mallocLong(1)
            vkCreateImageView(renderer.device.vkDevice, viewInfo, null, pView)
            
            return object : ImageView(renderer.device, null, ImageViewData()) {
                init {
                    // Override the vkImageView with our created view
                    this.vkImageView = pView[0]
                }
                
                override fun cleanup() {
                    if (vkImageView != 0L) {
                        vkDestroyImageView(renderer.device.vkDevice, vkImageView, null)
                        vkImageView = 0L
                    }
                }
            }
        }
    }

    fun acquireImage(): Int {
        MemoryStack.stackPush().use { stack ->
            val acquireInfo = XrSwapchainImageAcquireInfo.calloc(stack)
                .type(XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)
            
            val indexBuf = stack.mallocInt(1)
            xrAcquireSwapchainImage(xrSwapchain, acquireInfo, indexBuf)
            currentImageIndex = indexBuf[0]
            
            val waitInfo = XrSwapchainImageWaitInfo.calloc(stack)
                .type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                .timeout(XR_INFINITE_DURATION)
            
            xrWaitSwapchainImage(xrSwapchain, waitInfo)
            
            return currentImageIndex
        }
    }

    fun releaseImage() {
        MemoryStack.stackPush().use { stack ->
            val releaseInfo = XrSwapchainImageReleaseInfo.calloc(stack)
                .type(XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
            
            xrReleaseSwapchainImage(xrSwapchain, releaseInfo)
        }
    }

    fun getCurrentImage(): Long = if (currentImageIndex >= 0) swapchainImages[currentImageIndex] else 0L
    fun getCurrentImageView(): ImageView? = if (currentImageIndex >= 0) imageViews[currentImageIndex] else null

    fun beginRendering(cmd: VkCommandBuffer) {
        if (currentImageIndex < 0) return
        
        val vkImage = swapchainImages[currentImageIndex]
        
        MemoryStack.stackPush().use { stack ->
            val baseRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            // UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL
            image_layout_transition(
                cmd,
                vkImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                baseRange
            )

            val colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(imageViews[currentImageIndex].vkImageView)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .resolveMode(VK_RESOLVE_MODE_NONE)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .clearValue(
                    VkClearValue.calloc(stack).color(
                        VkClearColorValue.calloc(stack)
                            .float32(0, p.clearColor.x)
                            .float32(1, p.clearColor.y)
                            .float32(2, p.clearColor.z)
                            .float32(3, p.clearColor.w)
                    )
                )

            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .layerCount(1)
                .pColorAttachments(colorAttach)

            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderInfo)
        }
    }

    fun endRendering(cmd: VkCommandBuffer) {
        if (currentImageIndex < 0) return
        
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)

        val vkImage = swapchainImages[currentImageIndex]
        
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            // COLOR_ATTACHMENT_OPTIMAL -> COLOR_ATTACHMENT_OPTIMAL (ready for presentation)
            // OpenXR expects images to be in COLOR_ATTACHMENT_OPTIMAL for submission
            image_layout_transition(
                cmd,
                vkImage,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                0,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                range
            )
        }
    }

    fun cleanup() {
        imageViews.forEach { it.cleanup() }
        imageViews.clear()
        swapchainImages.clear()
        
        if (xrSwapchain != 0L) {
            xrDestroySwapchain(xrSwapchain)
        }
    }
}

// Helper to transition image layouts with raw VkImage handle
private fun image_layout_transition(
    cmd: VkCommandBuffer,
    vkImage: Long,
    srcStageMask: Long,
    dstStageMask: Long,
    srcAccessMask: Long,
    dstAccessMask: Long,
    oldLayout: Int,
    newLayout: Int,
    subresourceRange: VkImageSubresourceRange
) {
    MemoryStack.stackPush().use { stack ->
        val barrier = VkImageMemoryBarrier2.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(srcStageMask)
            .srcAccessMask(srcAccessMask)
            .dstStageMask(dstStageMask)
            .dstAccessMask(dstAccessMask)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(vkImage)
            .subresourceRange(subresourceRange)

        val depInfo = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier)

        vkCmdPipelineBarrier2(cmd, depInfo)
    }
}

class OpenXRRenderTarget(
    val renderer: Renderer,
    val eyeParams: OpenXREyeParams.() -> Unit = {}
) : Target {

    private var initialized = false

    // OpenXR handles
    private var xrInstance: Long = 0L
    private var xrSystemId: Long = 0L
    private var xrSession: Long = 0L
    private var xrSpace: Long = 0L

    // Swapchain wrappers for each eye
    private lateinit var leftEye: OpenXRSwapchainWrapper
    private lateinit var rightEye: OpenXRSwapchainWrapper

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set

    val params = OpenXREyeParams().apply(eyeParams)

    private val modules = linkedMapOf<String, afield.four.modules.ModulePointer>()

    // Frame state
    private var frameState = XrFrameState.calloc().type(XR_TYPE_FRAME_STATE)
    private var sessionRunning = false

    // View configuration
    private var viewConfigType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO
    private lateinit var views: XrView.Buffer
    
    // View/projection matrices
    private val leftViewMatrix = Matrix4f()
    private val leftProjMatrix = Matrix4f()
    private val rightViewMatrix = Matrix4f()
    private val rightProjMatrix = Matrix4f()

    init {
        initOpenXR()
        renderer.renderStart["__openxr__"] = sequence {
            while (true) {
                waitFrame()
                yield(true)
            }
        }

        renderer.renderEnd["__openxr__"] = sequence {
            while (true) {
                endFrame()
                yield(true)
            }
        }
    }

    private fun initOpenXR() {
        try {
            createInstance()
            getSystem()
            createSession()
            createReferenceSpace()
            createSwapchains()
            initialized = true
        } catch (e: Exception) {
            System.err.println("Failed to initialize OpenXR: ${e.message}")
            e.printStackTrace()
            cleanup()
            throw e
        }
    }

    private fun createInstance() {
        MemoryStack.stackPush().use { stack ->
            val appInfo = XrApplicationInfo.calloc(stack)
                .applicationName(stack.UTF8("Field4"))
                .applicationVersion(XR_MAKE_VERSION(1, 0, 0))
                .engineName(stack.UTF8("Field4"))
                .engineVersion(XR_MAKE_VERSION(1, 0, 0))
                .apiVersion(XR_CURRENT_API_VERSION)

            val extensionNames = stack.pointers(
                stack.UTF8(XR_KHR_VULKAN_ENABLE_EXTENSION_NAME)
            )

            val createInfo = XrInstanceCreateInfo.calloc(stack)
                .type(XR_TYPE_INSTANCE_CREATE_INFO)
                .applicationInfo(appInfo)
                .enabledExtensionNames(extensionNames)

            val pInstance = stack.mallocPointer(1)
            val result = xrCreateInstance(createInfo, pInstance)
            if (result != XR_SUCCESS) {
                throw RuntimeException("Failed to create OpenXR instance: $result")
            }
            xrInstance = pInstance[0]
        }
    }

    private fun getSystem() {
        MemoryStack.stackPush().use { stack ->
            val getInfo = XrSystemGetInfo.calloc(stack)
                .type(XR_TYPE_SYSTEM_GET_INFO)
                .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY)

            val pSystemId = stack.mallocLong(1)
            val result = xrGetSystem(xrInstance, getInfo, pSystemId)
            if (result != XR_SUCCESS) {
                throw RuntimeException("Failed to get OpenXR system: $result")
            }
            xrSystemId = pSystemId[0]

            // Get view configuration views for recommended resolution
            val countBuf = stack.mallocInt(1)
            xrEnumerateViewConfigurationViews(xrInstance, xrSystemId, viewConfigType, countBuf, null)
            val count = countBuf[0]

            val configViews = XrViewConfigurationView.calloc(count, stack)
            for (i in 0 until count) {
                configViews[i].type(XR_TYPE_VIEW_CONFIGURATION_VIEW)
            }
            xrEnumerateViewConfigurationViews(xrInstance, xrSystemId, viewConfigType, countBuf, configViews)

            // Use the recommended resolution from the first view (typically both eyes have same resolution)
            recommendedWidth = configViews[0].recommendedImageRectWidth()
            recommendedHeight = configViews[0].recommendedImageRectHeight()

            println("[OpenXR] Recommended render target size: ${recommendedWidth}x${recommendedHeight}")
        }
    }

    private fun createSession() {
        MemoryStack.stackPush().use { stack ->
            // Get Vulkan graphics requirements
            val graphicsReq = XrGraphicsRequirementsVulkanKHR.calloc(stack)
                .type(XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR)
            
            xrGetVulkanGraphicsRequirementsKHR(xrInstance, xrSystemId, graphicsReq)

            // Create graphics binding
            val graphicsBinding = XrGraphicsBindingVulkanKHR.calloc(stack)
                .type(XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR)
                .instance(renderer.instance.vkInstance.address())
                .physicalDevice(renderer.device.physicalDevice.vkPhysicalDevice.address())
                .device(renderer.device.vkDevice.address())
                .queueFamilyIndex(renderer.graphQueue.queueFamilyIndex)
                .queueIndex(0)

            val createInfo = XrSessionCreateInfo.calloc(stack)
                .type(XR_TYPE_SESSION_CREATE_INFO)
                .next(graphicsBinding.address())
                .systemId(xrSystemId)

            val pSession = stack.mallocPointer(1)
            val result = xrCreateSession(xrInstance, createInfo, pSession)
            if (result != XR_SUCCESS) {
                throw RuntimeException("Failed to create OpenXR session: $result")
            }
            xrSession = pSession[0]
        }
    }

    private fun createReferenceSpace() {
        MemoryStack.stackPush().use { stack ->
            val createInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
                .referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL)
                .poseInReferenceSpace { it
                    .orientation { it.x(0f).y(0f).z(0f).w(1f) }
                    .position { it.x(0f).y(0f).z(0f) }
                }

            val pSpace = stack.mallocPointer(1)
            val result = xrCreateReferenceSpace(xrSession, createInfo, pSpace)
            if (result != XR_SUCCESS) {
                throw RuntimeException("Failed to create reference space: $result")
            }
            xrSpace = pSpace[0]
        }
    }

    private fun createSwapchains() {
        MemoryStack.stackPush().use { stack ->
            // Enumerate supported swapchain formats
            val countBuf = stack.mallocInt(1)
            xrEnumerateSwapchainFormats(xrSession, countBuf, null as java.nio.LongBuffer?)
            val count = countBuf[0]
            
            val formats = stack.mallocLong(count)
            xrEnumerateSwapchainFormats(xrSession, countBuf, formats)

            // Choose format (prefer SRGB)
            var chosenFormat = formats[0]
            for (i in 0 until count) {
                val fmt = formats[i].toInt()
                if (fmt == VK_FORMAT_B8G8R8A8_SRGB || fmt == VK_FORMAT_R8G8B8A8_SRGB) {
                    chosenFormat = fmt.toLong()
                    break
                }
            }
            
            params.format = chosenFormat.toInt()

            // Create swapchain for each eye
            leftEye = createSwapchainForEye(stack, recommendedWidth, recommendedHeight, params.format)
            rightEye = createSwapchainForEye(stack, recommendedWidth, recommendedHeight, params.format)

            // Allocate views for frame rendering
            views = XrView.calloc(2)
            views[0].type(XR_TYPE_VIEW)
            views[1].type(XR_TYPE_VIEW)
        }
    }

    private fun createSwapchainForEye(stack: MemoryStack, width: Int, height: Int, format: Int): OpenXRSwapchainWrapper {
        val createInfo = XrSwapchainCreateInfo.calloc(stack)
            .type(XR_TYPE_SWAPCHAIN_CREATE_INFO)
            .usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT)
            .format(format.toLong())
            .sampleCount(1)
            .width(width)
            .height(height)
            .faceCount(1)
            .arraySize(1)
            .mipCount(1)

        val pSwapchain = stack.mallocPointer(1)
        val result = xrCreateSwapchain(xrSession, createInfo, pSwapchain)
        if (result != XR_SUCCESS) {
            throw RuntimeException("Failed to create swapchain: $result")
        }

        return OpenXRSwapchainWrapper(renderer, pSwapchain[0], width, height, format, params)
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
        if (!initialized || !sessionRunning) return
        
        val cmd = command.vkCommandBuffer

        // Begin frame already called in waitFrame()
        
        // Acquire and render left eye
        leftEye.acquireImage()
        leftEye.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, leftEye.width.toFloat(), leftEye.height.toFloat())) }
        leftEye.endRendering(cmd)

        // Acquire and render right eye
        rightEye.acquireImage()
        rightEye.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, rightEye.width.toFloat(), rightEye.height.toFloat())) }
        rightEye.endRendering(cmd)
    }

    private fun waitFrame() {
        if (!initialized) return

        MemoryStack.stackPush().use { stack ->
            // Poll events
            pollEvents(stack)

            if (!sessionRunning) return

            // Wait for next frame
            val waitInfo = XrFrameWaitInfo.calloc(stack)
                .type(XR_TYPE_FRAME_WAIT_INFO)

            xrWaitFrame(xrSession, waitInfo, frameState)

            // Begin frame
            val beginInfo = XrFrameBeginInfo.calloc(stack)
                .type(XR_TYPE_FRAME_BEGIN_INFO)

            xrBeginFrame(xrSession, beginInfo)

            // Locate views
            if (frameState.shouldRender()) {
                locateViews(stack)
            }
        }
    }

    private fun pollEvents(stack: MemoryStack) {
        val eventData = XrEventDataBuffer.calloc(stack)
        
        while (true) {
            eventData.type(XR_TYPE_EVENT_DATA_BUFFER)
            val result = xrPollEvent(xrInstance, eventData)
            if (result != XR_SUCCESS) break

            when (eventData.type()) {
                XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                    val stateEvent = XrEventDataSessionStateChanged.create(eventData.address())
                    handleSessionStateChanged(stateEvent.state())
                }
            }
        }
    }

    private fun handleSessionStateChanged(state: Int) {
        when (state) {
            XR_SESSION_STATE_READY -> {
                MemoryStack.stackPush().use { stack ->
                    val beginInfo = XrSessionBeginInfo.calloc(stack)
                        .type(XR_TYPE_SESSION_BEGIN_INFO)
                        .primaryViewConfigurationType(viewConfigType)
                    
                    xrBeginSession(xrSession, beginInfo)
                    sessionRunning = true
                }
            }
            XR_SESSION_STATE_STOPPING -> {
                xrEndSession(xrSession)
                sessionRunning = false
            }
            XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> {
                sessionRunning = false
            }
        }
    }

    private fun locateViews(stack: MemoryStack) {
        val viewLocateInfo = XrViewLocateInfo.calloc(stack)
            .type(XR_TYPE_VIEW_LOCATE_INFO)
            .viewConfigurationType(viewConfigType)
            .displayTime(frameState.predictedDisplayTime())
            .space(xrSpace)

        val viewState = XrViewState.calloc(stack)
            .type(XR_TYPE_VIEW_STATE)

        val countBuf = stack.mallocInt(1)
        countBuf.put(0, 2)

        xrLocateViews(xrSession, viewLocateInfo, viewState, countBuf, views)

        // Update view and projection matrices
        if (countBuf[0] >= 2) {
            updateViewMatrix(views[0], leftViewMatrix)
            updateProjectionMatrix(views[0].fov(), leftProjMatrix)
            updateViewMatrix(views[1], rightViewMatrix)
            updateProjectionMatrix(views[1].fov(), rightProjMatrix)
        }
    }

    private fun updateViewMatrix(view: XrView, outMatrix: Matrix4f) {
        val pose = view.pose()
        val pos = pose.position()
        val orient = pose.orientation()

        val position = Vector3f(pos.x(), pos.y(), pos.z())
        val orientation = Quaternionf(orient.x(), orient.y(), orient.z(), orient.w())

        outMatrix.identity()
        outMatrix.translate(position)
        outMatrix.rotate(orientation)
        outMatrix.invert()
    }

    private fun updateProjectionMatrix(fov: XrFovf, outMatrix: Matrix4f) {
        val zNear = 0.01f
        val zFar = 1000f

        val tanLeft = kotlin.math.tan(fov.angleLeft())
        val tanRight = kotlin.math.tan(fov.angleRight())
        val tanUp = kotlin.math.tan(fov.angleUp())
        val tanDown = kotlin.math.tan(fov.angleDown())

        val tanWidth = tanRight - tanLeft
        val tanHeight = tanDown - tanUp

        outMatrix.identity()
        outMatrix.m00(2.0f / tanWidth)
        outMatrix.m11(2.0f / tanHeight)
        outMatrix.m20((tanRight + tanLeft) / tanWidth)
        outMatrix.m21((tanUp + tanDown) / tanHeight)
        outMatrix.m22(-(zFar + zNear) / (zFar - zNear))
        outMatrix.m23(-1.0f)
        outMatrix.m32(-(2.0f * zFar * zNear) / (zFar - zNear))
        outMatrix.m33(0.0f)
    }

    private fun endFrame() {
        if (!initialized || !sessionRunning) return

        MemoryStack.stackPush().use { stack ->
            // Release swapchain images
            leftEye.releaseImage()
            rightEye.releaseImage()

            if (!frameState.shouldRender()) {
                // Submit empty frame
                val endInfo = XrFrameEndInfo.calloc(stack)
                    .type(XR_TYPE_FRAME_END_INFO)
                    .displayTime(frameState.predictedDisplayTime())
                    .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layers(null)

                xrEndFrame(xrSession, endInfo)
                return
            }

            // Prepare projection layers
            val projectionViews = XrCompositionLayerProjectionView.calloc(2, stack)
            
            // Left eye
            projectionViews[0]
                .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                .pose(views[0].pose())
                .fov(views[0].fov())
                .subImage { it
                    .swapchain(leftEye.xrSwapchain)
                    .imageRect { rect ->
                        rect.offset { it.x(0).y(0) }
                        rect.extent { it.width(leftEye.width).height(leftEye.height) }
                    }
                    .imageArrayIndex(0)
                }

            // Right eye
            projectionViews[1]
                .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                .pose(views[1].pose())
                .fov(views[1].fov())
                .subImage { it
                    .swapchain(rightEye.xrSwapchain)
                    .imageRect { rect ->
                        rect.offset { it.x(0).y(0) }
                        rect.extent { it.width(rightEye.width).height(rightEye.height) }
                    }
                    .imageArrayIndex(0)
                }

            val projectionLayer = XrCompositionLayerProjection.calloc(stack)
                .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                .space(xrSpace)
                .views(projectionViews)

            val layers = stack.pointers(projectionLayer.address())

            val endInfo = XrFrameEndInfo.calloc(stack)
                .type(XR_TYPE_FRAME_END_INFO)
                .displayTime(frameState.predictedDisplayTime())
                .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                .layers(XrCompositionLayerBaseHeader.create(layers))

            xrEndFrame(xrSession, endInfo)
        }
    }

    fun leftMVP(zNear: Float = 0.01f, zFar: Float = 1000f): Pair<Matrix4f, Matrix4f> {
        return leftViewMatrix to leftProjMatrix
    }

    fun rightMVP(zNear: Float = 0.01f, zFar: Float = 1000f): Pair<Matrix4f, Matrix4f> {
        return rightViewMatrix to rightProjMatrix
    }

    fun cleanup() {
        if (initialized) {
            if (::rightEye.isInitialized) rightEye.cleanup()
            if (::leftEye.isInitialized) leftEye.cleanup()
            
            if (xrSpace != 0L) xrDestroySpace(xrSpace)
            if (xrSession != 0L) xrDestroySession(xrSession)
            if (xrInstance != 0L) xrDestroyInstance(xrInstance)
            
            if (::views.isInitialized) views.free()
            frameState.free()
            
            initialized = false
        }
    }
}

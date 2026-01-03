package afield.four.targets

import org.lwjgl.openxr.*
import org.lwjgl.openxr.KHRVulkanEnable
import org.lwjgl.openxr.KHRVulkanEnable2.*
import org.lwjgl.openxr.XR10.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

// Swapchain data class - needs to be at package level
private class SwapchainInfo(
    val swapchain: XrSwapchain,
    val width: Int,
    val height: Int,
    val format: Int,
    val images: List<Long>,
    val imageViews: MutableList<Long> = mutableListOf(),
    val framebuffers: MutableList<Long> = mutableListOf()
)

/**
 * Self-contained OpenXR + Vulkan example using LWJGL.
 * Goal: Clear the VR headset to purple as a proof-of-life.
 * Based on Khronos hello_xr example.
 */
class OpenXrVulkanPurple {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            OpenXrVulkanPurple().run()
        }
    }

    fun run() {
        Runner().execute()
    }

    private inner class Runner {
        // OpenXR handles
        lateinit var xrInstance: XrInstance
        var xrSystemId: Long = 0
        lateinit var xrSession: XrSession
        lateinit var xrSpace: XrSpace

        // Vulkan handles
        lateinit var vkInstance: VkInstance
        lateinit var vkPhysicalDevice: VkPhysicalDevice
        lateinit var vkDevice: VkDevice
        lateinit var vkQueue: VkQueue
        var vkQueueFamilyIndex: Int = 0
        var vkCommandPool: Long = VK_NULL_HANDLE
        lateinit var vkCommandBuffer: VkCommandBuffer
        var vkFence: Long = VK_NULL_HANDLE
        var vkRenderPass: Long = VK_NULL_HANDLE

        val swapchains = mutableListOf<SwapchainInfo>()
        lateinit var viewConfigs: XrViewConfigurationView.Buffer
        var viewCount = 0

        // Session state
        var sessionRunning = false
        var sessionState = XR_SESSION_STATE_UNKNOWN

        fun execute() {
            try {
                println("=== OpenXR + Vulkan Purple Clear Example ===")
                initOpenXR()
                initVulkanForOpenXR()
                createOpenXRSession()
                createRenderResources()
                createSwapchains()
                loop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                cleanup()
            }
        }

        private fun initOpenXR() {
            stackPush().use { stack ->
                // Check available extensions
                val extensionCountBuf = stack.callocInt(1)
                xrEnumerateInstanceExtensionProperties(null as CharSequence?, extensionCountBuf, null)
                val extensionCount = extensionCountBuf[0]

                val extensions = XrExtensionProperties.calloc(extensionCount, stack)
                for (i in 0 until extensionCount) {
                    extensions[i].type(XR_TYPE_EXTENSION_PROPERTIES)
                }
                xrEnumerateInstanceExtensionProperties(null as CharSequence?, extensionCountBuf, extensions)

                println("Available OpenXR extensions:")
                var hasVulkan2 = false
                for (i in 0 until extensionCount) {
                    val name = extensions[i].extensionNameString()
                    println("  $name")
                    if (name == XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME) hasVulkan2 = true
                }

                check(hasVulkan2) { "XR_KHR_vulkan_enable2 extension not available!" }

                // Create XR instance
                val appInfo = XrApplicationInfo.calloc(stack)
                    .applicationName(stack.UTF8("OpenXR Purple"))
                    .applicationVersion(1)
                    .engineName(stack.UTF8("LWJGL"))
                    .engineVersion(1)
                    .apiVersion(XR_MAKE_VERSION(1, 0, 0))

                val enabledExtensions = stack.pointers(
                    stack.UTF8(XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME)
                )

                val createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type(XR_TYPE_INSTANCE_CREATE_INFO)
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(enabledExtensions)

                val instancePtr = stack.callocPointer(1)
                val result = xrCreateInstance(createInfo, instancePtr)
                check(result == XR_SUCCESS) { "Failed to create XR instance: $result" }

                xrInstance = XrInstance(instancePtr[0], createInfo)
                println("OpenXR instance created!")

                // Get system (HMD)
                val systemInfo = XrSystemGetInfo.calloc(stack)
                    .type(XR_TYPE_SYSTEM_GET_INFO)
                    .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY)

                val systemIdBuf = stack.callocLong(1)
                val sysResult = xrGetSystem(xrInstance, systemInfo, systemIdBuf)
                check(sysResult == XR_SUCCESS) { "Failed to get XR system: $sysResult" }

                xrSystemId = systemIdBuf[0]
                println("OpenXR system acquired: $xrSystemId")

                // Get view configuration views
                val viewConfigCountBuf = stack.callocInt(1)
                xrEnumerateViewConfigurationViews(
                    xrInstance, xrSystemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    viewConfigCountBuf, null
                )
                viewCount = viewConfigCountBuf[0]

                viewConfigs = XrViewConfigurationView.calloc(viewCount)
                for (i in 0 until viewCount) {
                    viewConfigs[i].type(XR_TYPE_VIEW_CONFIGURATION_VIEW)
                }
                xrEnumerateViewConfigurationViews(
                    xrInstance, xrSystemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    viewConfigCountBuf, viewConfigs
                )

                println("View configurations: $viewCount")
                for (i in 0 until viewCount) {
                    println("  View $i: ${viewConfigs[i].recommendedImageRectWidth()}x${viewConfigs[i].recommendedImageRectHeight()}")
                }
            }
        }

        private fun initVulkanForOpenXR() {
            stackPush().use { stack ->
                // Get Vulkan graphics requirements
                val graphicsRequirements = XrGraphicsRequirementsVulkan2KHR.calloc(stack)
                    .type(XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN2_KHR)

                val reqResult = xrGetVulkanGraphicsRequirements2KHR(xrInstance, xrSystemId, graphicsRequirements)
                check(reqResult == XR_SUCCESS) { "Failed to get Vulkan graphics requirements: $reqResult" }

                println("Vulkan requirements: min=${graphicsRequirements.minApiVersionSupported()}, max=${graphicsRequirements.maxApiVersionSupported()}")

                // Create Vulkan instance via OpenXR
                val appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("OpenXR Purple"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("LWJGL"))
                    .engineVersion(1)
                    .apiVersion(VK_API_VERSION_1_0)

                val vkInstanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)

                val xrVkInstanceCreateInfo = XrVulkanInstanceCreateInfoKHR.calloc(stack)
                    .type(XR_TYPE_VULKAN_INSTANCE_CREATE_INFO_KHR)
                    .systemId(xrSystemId)
                    .pfnGetInstanceProcAddr(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"))
                    .vulkanCreateInfo(vkInstanceCreateInfo)

                val vkInstancePtr = stack.callocPointer(1)
                val vkResultBuf = stack.callocInt(1)

                val createResult = xrCreateVulkanInstanceKHR(xrInstance, xrVkInstanceCreateInfo, vkInstancePtr, vkResultBuf)
                check(createResult == XR_SUCCESS) { "Failed to create Vulkan instance via OpenXR: $createResult" }
                check(vkResultBuf[0] == VK_SUCCESS) { "Vulkan instance creation failed: ${vkResultBuf[0]}" }

                vkInstance = VkInstance(vkInstancePtr[0], vkInstanceCreateInfo)
                println("Vulkan instance created via OpenXR!")

                // Get physical device via OpenXR
                val deviceGetInfo = XrVulkanGraphicsDeviceGetInfoKHR.calloc(stack)
                    .type(XR_TYPE_VULKAN_GRAPHICS_DEVICE_GET_INFO_KHR)
                    .systemId(xrSystemId)
                    .vulkanInstance(vkInstance)

                val physicalDevicePtr = stack.callocPointer(1)
                val pdResult = xrGetVulkanGraphicsDevice2KHR(xrInstance, deviceGetInfo, physicalDevicePtr)
                check(pdResult == XR_SUCCESS) { "Failed to get Vulkan physical device: $pdResult" }

                vkPhysicalDevice = VkPhysicalDevice(physicalDevicePtr[0], vkInstance)
                println("Vulkan physical device obtained!")

                // Find graphics queue family
                val queueFamilyCountBuf = stack.callocInt(1)
                vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, queueFamilyCountBuf, null)
                val queueFamilyCount = queueFamilyCountBuf[0]

                val queueFamilyProps = VkQueueFamilyProperties.calloc(queueFamilyCount, stack)
                vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, queueFamilyCountBuf, queueFamilyProps)

                vkQueueFamilyIndex = -1
                for (i in 0 until queueFamilyCount) {
                    if ((queueFamilyProps[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0) {
                        vkQueueFamilyIndex = i
                        break
                    }
                }
                check(vkQueueFamilyIndex >= 0) { "No graphics queue family found!" }
                println("Graphics queue family index: $vkQueueFamilyIndex")

                // Create Vulkan device via OpenXR
                val queuePriority = stack.floats(1.0f)
                val queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(vkQueueFamilyIndex)
                    .pQueuePriorities(queuePriority)

                val deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCreateInfo)

                val xrVkDeviceCreateInfo = XrVulkanDeviceCreateInfoKHR.calloc(stack)
                    .type(XR_TYPE_VULKAN_DEVICE_CREATE_INFO_KHR)
                    .systemId(xrSystemId)
                    .pfnGetInstanceProcAddr(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"))
                    .vulkanPhysicalDevice(vkPhysicalDevice)
                    .vulkanCreateInfo(deviceCreateInfo)

                val vkDevicePtr = stack.callocPointer(1)
                val devResult = xrCreateVulkanDeviceKHR(xrInstance, xrVkDeviceCreateInfo, vkDevicePtr, vkResultBuf)
                check(devResult == XR_SUCCESS) { "Failed to create Vulkan device via OpenXR: $devResult" }
                check(vkResultBuf[0] == VK_SUCCESS) { "Vulkan device creation failed: ${vkResultBuf[0]}" }

                vkDevice = VkDevice(vkDevicePtr[0], vkPhysicalDevice, deviceCreateInfo)
                println("Vulkan device created via OpenXR!")

                // Get queue
                val queuePtr = stack.callocPointer(1)
                vkGetDeviceQueue(vkDevice, vkQueueFamilyIndex, 0, queuePtr)
                vkQueue = VkQueue(queuePtr[0], vkDevice)
                println("Vulkan queue obtained!")
            }
        }

        private fun createOpenXRSession() {
            stackPush().use { stack ->
                // Create graphics binding - Using XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR (NOT vulkan2!)
                val graphicsBinding = XrGraphicsBindingVulkan2KHR.calloc(stack)
                    .type(KHRVulkanEnable.XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR)  // Important: NOT vulkan2!
                    .instance(vkInstance)
                    .physicalDevice(vkPhysicalDevice)
                    .device(vkDevice)
                    .queueFamilyIndex(vkQueueFamilyIndex)
                    .queueIndex(0)

                val sessionCreateInfo = XrSessionCreateInfo.calloc(stack)
                    .type(XR_TYPE_SESSION_CREATE_INFO)
                    .next(graphicsBinding.address())
                    .systemId(xrSystemId)

                val sessionPtr = stack.callocPointer(1)
                val result = xrCreateSession(xrInstance, sessionCreateInfo, sessionPtr)
                check(result == XR_SUCCESS) { "Failed to create XR session: $result" }

                xrSession = XrSession(sessionPtr[0], xrInstance)
                println("OpenXR session created!")

                // Create reference space (local space, centered at origin)
                val spaceCreateInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                    .type(XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
                    .referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL)
                spaceCreateInfo.poseInReferenceSpace().orientation().w(1f)
                spaceCreateInfo.poseInReferenceSpace().`position$`().x(0f).y(0f).z(0f)

                val spacePtr = stack.callocPointer(1)
                val spaceResult = xrCreateReferenceSpace(xrSession, spaceCreateInfo, spacePtr)
                check(spaceResult == XR_SUCCESS) { "Failed to create reference space: $spaceResult" }

                xrSpace = XrSpace(spacePtr[0], xrSession)
                println("Reference space created!")
            }
        }

        private fun createRenderResources() {
            stackPush().use { stack ->
                // Create command pool
                val poolCreateInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(vkQueueFamilyIndex)

                val commandPoolPtr = stack.callocLong(1)
                check(vkCreateCommandPool(vkDevice, poolCreateInfo, null, commandPoolPtr) == VK_SUCCESS) {
                    "Failed to create command pool"
                }
                vkCommandPool = commandPoolPtr[0]

                // Allocate command buffer
                val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(vkCommandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1)

                val cmdBufPtr = stack.callocPointer(1)
                check(vkAllocateCommandBuffers(vkDevice, allocInfo, cmdBufPtr) == VK_SUCCESS) {
                    "Failed to allocate command buffer"
                }
                vkCommandBuffer = VkCommandBuffer(cmdBufPtr[0], vkDevice)

                // Create fence
                val fenceCreateInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT)

                val fencePtr = stack.callocLong(1)
                check(vkCreateFence(vkDevice, fenceCreateInfo, null, fencePtr) == VK_SUCCESS) {
                    "Failed to create fence"
                }
                vkFence = fencePtr[0]

                println("Vulkan command pool, buffer, and fence created!")
            }
        }

        private fun createSwapchains() {
            stackPush().use { stack ->
                // Get supported swapchain formats
                val formatCountBuf = stack.callocInt(1)
                xrEnumerateSwapchainFormats(xrSession, formatCountBuf, null)
                val formatCount = formatCountBuf[0]

                val formats = stack.callocLong(formatCount)
                xrEnumerateSwapchainFormats(xrSession, formatCountBuf, formats)

                println("Supported swapchain formats:")
                val formatList = (0 until formatCount).map { formats[it].toInt() }
                formatList.forEach { fmt ->
                    println("  $fmt (${vkFormatName(fmt)})")
                }

                // Select format - prefer SRGB
                val preferredFormats = listOf(
                    VK_FORMAT_B8G8R8A8_SRGB,
                    VK_FORMAT_R8G8B8A8_SRGB,
                    VK_FORMAT_B8G8R8A8_UNORM,
                    VK_FORMAT_R8G8B8A8_UNORM
                )
                val selectedFormat = preferredFormats.firstOrNull { it in formatList }
                    ?: formatList.first()
                println("Selected format: $selectedFormat (${vkFormatName(selectedFormat)})")

                // Create render pass for the selected format
                createRenderPass(selectedFormat)

                // Create a swapchain for each view (typically 2 for stereo)
                for (i in 0 until viewCount) {
                    val viewConfig = viewConfigs[i]
                    val swapchainCreateInfo = XrSwapchainCreateInfo.calloc(stack)
                        .type(XR_TYPE_SWAPCHAIN_CREATE_INFO)
                        .usageFlags((XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT or XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT).toLong())
                        .format(selectedFormat.toLong())
                        .sampleCount(1)
                        .width(viewConfig.recommendedImageRectWidth())
                        .height(viewConfig.recommendedImageRectHeight())
                        .faceCount(1)
                        .arraySize(1)
                        .mipCount(1)

                    val swapchainPtr = stack.callocPointer(1)
                    val result = xrCreateSwapchain(xrSession, swapchainCreateInfo, swapchainPtr)
                    check(result == XR_SUCCESS) { "Failed to create swapchain: $result" }

                    val swapchain = XrSwapchain(swapchainPtr[0], xrSession)

                    // Get swapchain images
                    val imageCountBuf = stack.callocInt(1)
                    xrEnumerateSwapchainImages(swapchain, imageCountBuf, null)
                    val imageCount = imageCountBuf[0]

                    val swapchainImages = XrSwapchainImageVulkan2KHR.calloc(imageCount, stack)
                    for (j in 0 until imageCount) {
                        swapchainImages[j].type(XR_TYPE_SWAPCHAIN_IMAGE_VULKAN2_KHR)
                    }
                    xrEnumerateSwapchainImages(swapchain, imageCountBuf, XrSwapchainImageBaseHeader.create(swapchainImages))

                    val images = (0 until imageCount).map { swapchainImages[it].image() }

                    val swapchainData = SwapchainInfo(
                        swapchain = swapchain,
                        width = viewConfig.recommendedImageRectWidth(),
                        height = viewConfig.recommendedImageRectHeight(),
                        format = selectedFormat,
                        images = images
                    )

                    // Create image views and framebuffers for each swapchain image
                    for (image in images) {
                        val imageView = createImageView(image, selectedFormat)
                        swapchainData.imageViews.add(imageView)

                        val framebuffer = createFramebuffer(imageView, swapchainData.width, swapchainData.height)
                        swapchainData.framebuffers.add(framebuffer)
                    }

                    swapchains.add(swapchainData)

                    println("Swapchain $i created: ${swapchainData.width}x${swapchainData.height}, ${images.size} images")
                }
            }
        }

        private fun createRenderPass(format: Int) {
            stackPush().use { stack ->
                val colorAttachment = VkAttachmentDescription.calloc(1, stack)
                    .format(format)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

                val colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

                val subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)

                val renderPassInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(colorAttachment)
                    .pSubpasses(subpass)

                val renderPassPtr = stack.callocLong(1)
                check(vkCreateRenderPass(vkDevice, renderPassInfo, null, renderPassPtr) == VK_SUCCESS) {
                    "Failed to create render pass"
                }
                vkRenderPass = renderPassPtr[0]
                println("Render pass created!")
            }
        }

        private fun createImageView(image: Long, format: Int): Long {
            stackPush().use { stack ->
                val viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                val imageViewPtr = stack.callocLong(1)
                check(vkCreateImageView(vkDevice, viewInfo, null, imageViewPtr) == VK_SUCCESS) {
                    "Failed to create image view"
                }
                return imageViewPtr[0]
            }
        }

        private fun createFramebuffer(imageView: Long, width: Int, height: Int): Long {
            stackPush().use { stack ->
                val attachments = stack.longs(imageView)

                val framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(vkRenderPass)
                    .pAttachments(attachments)
                    .width(width)
                    .height(height)
                    .layers(1)

                val framebufferPtr = stack.callocLong(1)
                check(vkCreateFramebuffer(vkDevice, framebufferInfo, null, framebufferPtr) == VK_SUCCESS) {
                    "Failed to create framebuffer"
                }
                return framebufferPtr[0]
            }
        }

        private fun loop() {
            println("Entering main loop...")

            while (true) {
                // Poll OpenXR events
                if (!pollEvents()) break

                // Only render if session is running
                if (sessionRunning) {
                    renderFrame()
                }
            }

            println("Exiting main loop")
        }

        private fun pollEvents(): Boolean {
            stackPush().use { stack ->
                val eventData = XrEventDataBuffer.calloc(stack)

                while (true) {
                    eventData.type(XR_TYPE_EVENT_DATA_BUFFER)
                    val result = xrPollEvent(xrInstance, eventData)

                    if (result == XR_EVENT_UNAVAILABLE) break
                    if (result != XR_SUCCESS) {
                        println("Error polling events: $result")
                        return false
                    }

                    when (eventData.type()) {
                        XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                            val stateEvent = XrEventDataSessionStateChanged.create(eventData.address())
                            sessionState = stateEvent.state()
                            println("Session state changed: ${sessionStateName(sessionState)}")

                            when (sessionState) {
                                XR_SESSION_STATE_READY -> {
                                    // Begin session
                                    val beginInfo = XrSessionBeginInfo.calloc(stack)
                                        .type(XR_TYPE_SESSION_BEGIN_INFO)
                                        .primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)

                                    val beginResult = xrBeginSession(xrSession, beginInfo)
                                    check(beginResult == XR_SUCCESS) { "Failed to begin session: $beginResult" }
                                    sessionRunning = true
                                    println("Session begun!")
                                }

                                XR_SESSION_STATE_STOPPING -> {
                                    xrEndSession(xrSession)
                                    sessionRunning = false
                                    println("Session ended")
                                }

                                XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> {
                                    return false
                                }
                            }
                        }

                        XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING -> {
                            println("Instance loss pending")
                            return false
                        }
                    }
                }
            }
            return true
        }

        private fun renderFrame() {
            stackPush().use { stack ->
                // Wait for frame
                val frameWaitInfo = XrFrameWaitInfo.calloc(stack)
                    .type(XR_TYPE_FRAME_WAIT_INFO)

                val frameState = XrFrameState.calloc(stack)
                    .type(XR_TYPE_FRAME_STATE)

                var result = xrWaitFrame(xrSession, frameWaitInfo, frameState)
                if (result != XR_SUCCESS) {
                    println("xrWaitFrame failed: $result")
                    return
                }

                // Begin frame
                val frameBeginInfo = XrFrameBeginInfo.calloc(stack)
                    .type(XR_TYPE_FRAME_BEGIN_INFO)

                result = xrBeginFrame(xrSession, frameBeginInfo)
                if (result != XR_SUCCESS) {
                    println("xrBeginFrame failed: $result")
                    return
                }

                val layers = stack.callocPointer(1)
                var layerCount = 0

                if (frameState.shouldRender()) {
                    // Locate views
                    val viewLocateInfo = XrViewLocateInfo.calloc(stack)
                        .type(XR_TYPE_VIEW_LOCATE_INFO)
                        .viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                        .displayTime(frameState.predictedDisplayTime())
                        .space(xrSpace)

                    val viewState = XrViewState.calloc(stack)
                        .type(XR_TYPE_VIEW_STATE)

                    val viewCapacityBuf = stack.ints(viewCount)
                    val viewsBuffer = XrView.calloc(viewCount, stack)
                    for (i in 0 until viewCount) {
                        viewsBuffer[i].type(XR_TYPE_VIEW)
                    }

                    result = xrLocateViews(xrSession, viewLocateInfo, viewState, viewCapacityBuf, viewsBuffer)
                    if (result == XR_SUCCESS) {
                        // Render each view
                        val projectionViews = XrCompositionLayerProjectionView.calloc(swapchains.size, stack)

                        for (i in swapchains.indices) {
                            val swapchainData = swapchains[i]
                            val view = viewsBuffer[i]

                            // Acquire swapchain image
                            val acquireInfo = XrSwapchainImageAcquireInfo.calloc(stack)
                                .type(XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)

                            val imageIndexBuf = stack.callocInt(1)
                            xrAcquireSwapchainImage(swapchainData.swapchain, acquireInfo, imageIndexBuf)
                            val imageIndex = imageIndexBuf[0]

                            // Wait for swapchain image
                            val waitInfo = XrSwapchainImageWaitInfo.calloc(stack)
                                .type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                                .timeout(XR_INFINITE_DURATION)

                            xrWaitSwapchainImage(swapchainData.swapchain, waitInfo)

                            // Clear to purple!
                            clearSwapchainToPurple(swapchainData, imageIndex)

                            // Release swapchain image
                            val releaseInfo = XrSwapchainImageReleaseInfo.calloc(stack)
                                .type(XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)

                            xrReleaseSwapchainImage(swapchainData.swapchain, releaseInfo)

                            // Set up projection view
                            projectionViews[i]
                                .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                                .pose(view.pose())
                                .fov(view.fov())
                            projectionViews[i].subImage()
                                .swapchain(swapchainData.swapchain)
                                .imageArrayIndex(0)
                            projectionViews[i].subImage().imageRect().offset().x(0).y(0)
                            projectionViews[i].subImage().imageRect().extent()
                                .width(swapchainData.width).height(swapchainData.height)
                        }

                        // Create projection layer
                        val projectionLayer = XrCompositionLayerProjection.calloc(stack)
                            .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                            .space(xrSpace)
                            .views(projectionViews)

                        layers.put(0, projectionLayer)
                        layerCount = 1
                    }
                }

                // End frame
                val frameEndInfo = XrFrameEndInfo.calloc(stack)
                    .type(XR_TYPE_FRAME_END_INFO)
                    .displayTime(frameState.predictedDisplayTime())
                    .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layerCount(layerCount)
                    .layers(if (layerCount > 0) layers else null)

                result = xrEndFrame(xrSession, frameEndInfo)
                if (result != XR_SUCCESS) {
                    println("xrEndFrame failed: $result")
                }
            }
        }

        private fun clearSwapchainToPurple(swapchainData: SwapchainInfo, imageIndex: Int) {
            stackPush().use { stack ->
                // Wait for previous frame
                vkWaitForFences(vkDevice, vkFence, true, Long.MAX_VALUE)
                vkResetFences(vkDevice, vkFence)

                // Reset and begin command buffer
                vkResetCommandBuffer(vkCommandBuffer, 0)

                val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)

                vkBeginCommandBuffer(vkCommandBuffer, beginInfo)

                // Clear color: PURPLE (1.0, 0.0, 1.0, 1.0)
                val clearValue = VkClearValue.calloc(1, stack)
                clearValue[0].color()
                    .float32(0, 1.0f)  // R
                    .float32(1, 0.0f)  // G
                    .float32(2, 1.0f)  // B
                    .float32(3, 1.0f)  // A

                val renderPassBeginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(vkRenderPass)
                    .framebuffer(swapchainData.framebuffers[imageIndex])
                    .clearValueCount(1)
                    .pClearValues(clearValue)
                renderPassBeginInfo.renderArea().offset().x(0).y(0)
                renderPassBeginInfo.renderArea().extent().width(swapchainData.width).height(swapchainData.height)

                vkCmdBeginRenderPass(vkCommandBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE)
                vkCmdEndRenderPass(vkCommandBuffer)

                vkEndCommandBuffer(vkCommandBuffer)

                // Submit command buffer
                val submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(vkCommandBuffer))

                vkQueueSubmit(vkQueue, submitInfo, vkFence)
            }
        }

        private fun cleanup() {
            println("Cleaning up...")

            if (::vkDevice.isInitialized) {
                vkDeviceWaitIdle(vkDevice)

                // Destroy framebuffers and image views
                for (swapchain in swapchains) {
                    for (fb in swapchain.framebuffers) {
                        vkDestroyFramebuffer(vkDevice, fb, null)
                    }
                    for (iv in swapchain.imageViews) {
                        vkDestroyImageView(vkDevice, iv, null)
                    }
                }

                if (vkRenderPass != VK_NULL_HANDLE) {
                    vkDestroyRenderPass(vkDevice, vkRenderPass, null)
                }

                if (vkFence != VK_NULL_HANDLE) {
                    vkDestroyFence(vkDevice, vkFence, null)
                }

                if (vkCommandPool != VK_NULL_HANDLE) {
                    vkDestroyCommandPool(vkDevice, vkCommandPool, null)
                }
            }

            // Destroy swapchains
            for (swapchain in swapchains) {
                xrDestroySwapchain(swapchain.swapchain)
            }

            if (::xrSpace.isInitialized) xrDestroySpace(xrSpace)
            if (::xrSession.isInitialized) xrDestroySession(xrSession)

            if (::vkDevice.isInitialized) vkDestroyDevice(vkDevice, null)
            if (::vkInstance.isInitialized) vkDestroyInstance(vkInstance, null)

            if (::viewConfigs.isInitialized) viewConfigs.free()
            if (::xrInstance.isInitialized) xrDestroyInstance(xrInstance)

            println("Cleanup complete")
        }

        private fun sessionStateName(state: Int): String = when (state) {
            XR_SESSION_STATE_UNKNOWN -> "UNKNOWN"
            XR_SESSION_STATE_IDLE -> "IDLE"
            XR_SESSION_STATE_READY -> "READY"
            XR_SESSION_STATE_SYNCHRONIZED -> "SYNCHRONIZED"
            XR_SESSION_STATE_VISIBLE -> "VISIBLE"
            XR_SESSION_STATE_FOCUSED -> "FOCUSED"
            XR_SESSION_STATE_STOPPING -> "STOPPING"
            XR_SESSION_STATE_LOSS_PENDING -> "LOSS_PENDING"
            XR_SESSION_STATE_EXITING -> "EXITING"
            else -> "UNKNOWN($state)"
        }

        private fun vkFormatName(format: Int): String = when (format) {
            VK_FORMAT_B8G8R8A8_SRGB -> "B8G8R8A8_SRGB"
            VK_FORMAT_R8G8B8A8_SRGB -> "R8G8B8A8_SRGB"
            VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM"
            VK_FORMAT_R8G8B8A8_UNORM -> "R8G8B8A8_UNORM"
            VK_FORMAT_D32_SFLOAT -> "D32_SFLOAT"
            VK_FORMAT_D24_UNORM_S8_UINT -> "D24_UNORM_S8_UINT"
            VK_FORMAT_D16_UNORM -> "D16_UNORM"
            else -> "FORMAT_$format"
        }
    }
}


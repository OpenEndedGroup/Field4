package afield.four.targets

import afield.Generator
import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.pointer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.texture.image_layout_transition
import afield.four.util.DownResult
import afield.four.util.Options
import afield.stdlib.vec
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.primitives.Rectanglef
import org.lwjgl.PointerBuffer
import org.lwjgl.openxr.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import java.nio.LongBuffer

data class OpenXREyeParams(
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var clearColor: org.joml.Vector4f = vec(0.3, 0.5, 0.4, 1),
    var useDepth: Boolean = false,
    var depthFormat: Int = Options.defaultDepthFormat
)

private class EyeImage(
    val renderer: Renderer, width: Int, height: Int, val p: OpenXREyeParams
) {
    val colorImage: Image
    val colorView: ImageView
    var width = width; private set
    var height = height; private set

    private var colorLayout: Int = VK_IMAGE_LAYOUT_UNDEFINED

    init {
        val usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT

        val img = ImageData().width(width).height(height).usage(usage).format(p.format).mipLevels(1)
        colorImage = Image(renderer.device, img)

        val viewInfo = ImageViewData().format(p.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(1)
        colorView = ImageView(renderer.device, colorImage, viewInfo)
    }

    fun beginRendering(cmd: VkCommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            val baseRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            image_layout_transition(
                cmd,
                colorImage,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                baseRange
            )
            colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL

            val colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(colorView.vkImageView)
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
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)

        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            image_layout_transition(
                cmd,
                colorImage,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_NONE,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                0,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range
            )
            colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        }
    }

    fun cleanup() {
        colorView.cleanup()
        colorImage.cleanup()
    }
}

enum class ControllerRole { LEFT, RIGHT }
enum class Hand { LEFT, RIGHT }
data class Parsed(val hand: Hand, val buttonId: Int)

/** Full pose for a controller in absolute (tracking) space. */
data class ControllerPose(
    val deviceIndex: Int,
    val matrix: Matrix4f,
    val position: Vector3f,
    val orientation: Quaternionf,
    val linearVelocity: Vector3f,
    val angularVelocity: Vector3f,
    val connected: Boolean,
    val trackingValid: Boolean,
    val trackingResult: Int
)

/** Buttons/axes snapshot (optional). */
data class ControllerState(
    val deviceIndex: Int,
    val packetNum: Int,
    val buttonPressedMask: Long,
    val buttonTouchedMask: Long,
    val axes: FloatArray
)

private data class FovData(
    var left: Float = 0f,
    var right: Float = 0f,
    var up: Float = 0f,
    var down: Float = 0f
)

private data class PoseData(
    val position: Vector3f = Vector3f(),
    val orientation: Quaternionf = Quaternionf()
)

internal data class Swapchain(
    val handle: XrSwapchain,
    val images: XrSwapchainImageVulkan2KHR.Buffer,
    val layouts: IntArray
)

internal class OpenXRSession(private val renderer: Renderer, private val params: OpenXREyeParams) {
    lateinit var xrInstance: XrInstance; private set
    lateinit var xrSession: XrSession; private set
    lateinit var appSpace: XrSpace; private set
    var systemId: Long = 0; private set

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set
    var swapchainFormat: Int = params.format; private set

    private var sessionRunning = false
    private var sessionState = XR10.XR_SESSION_STATE_UNKNOWN

    private val frameState = XrFrameState.calloc().type(XR10.XR_TYPE_FRAME_STATE)
    private val viewState = XrViewState.calloc().type(XR10.XR_TYPE_VIEW_STATE)
    private val views: XrView.Buffer
    private val viewData: Array<Pair<PoseData, FovData>>
    private var predictedDisplayTime: Long = 0L

    private val viewConfigViews: XrViewConfigurationView.Buffer

    // Actions / controllers
    private lateinit var actionSet: XrActionSet
    private lateinit var poseAction: XrAction
    private lateinit var selectAction: XrAction
    private lateinit var squeezeAction: XrAction
    private lateinit var menuAction: XrAction
    private lateinit var thumbstickAction: XrAction
    private val handPaths = LongArray(2)
    private lateinit var handSpaces: Array<XrSpace>

    private val controllerPoses = arrayOfNulls<ControllerPose>(2)
    private val controllerMasks = LongArray(2) { 0L }
    private val controllerAxes = Array(2) { floatArrayOf() }

    init {
        MemoryStack.stackPush().use { stack ->
            val appInfo = XrApplicationInfo.calloc(stack)
                .applicationName(stack.UTF8("afield"))
                .applicationVersion(1)
                .engineName(stack.UTF8("afield"))
                .engineVersion(1)
                .apiVersion(XR10.XR_CURRENT_API_VERSION)

            val extensions = arrayOf(KHRVulkanEnable2.XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME)
            val extBuf = stack.mallocPointer(extensions.size)
            extensions.forEachIndexed { i, s -> extBuf.put(i, stack.UTF8(s)) }

            val instanceInfo = XrInstanceCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_INSTANCE_CREATE_INFO)
                .applicationInfo(appInfo)
                .enabledExtensionNames(extBuf)

            val pInstance = stack.mallocPointer(1)
            checkXr(XR10.xrCreateInstance(instanceInfo, pInstance), "xrCreateInstance")
            xrInstance = XrInstance(pInstance[0], instanceInfo)

            val sysInfo = XrSystemGetInfo.calloc(stack)
                .type(XR10.XR_TYPE_SYSTEM_GET_INFO)
                .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY)
            val pSystem = stack.mallocLong(1)
            checkXr(XR10.xrGetSystem(xrInstance, sysInfo, pSystem), "xrGetSystem")
            systemId = pSystem[0]

            val binding = XrGraphicsBindingVulkan2KHR.calloc(stack)
                .type(KHRVulkanEnable2.XR_TYPE_GRAPHICS_BINDING_VULKAN2_KHR)
                .instance(renderer.instance.vkInstance)
                .physicalDevice(renderer.device.physicalDevice.vkPhysicalDevice)
                .device(renderer.device.vkDevice)
                .queueFamilyIndex(renderer.graphQueue.queueFamilyIndex)
                .queueIndex(0)

            val sessionInfo = XrSessionCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_SESSION_CREATE_INFO)
                .next(binding.address())
                .systemId(systemId)

            val pSession = stack.mallocPointer(1)
            checkXr(XR10.xrCreateSession(xrInstance, sessionInfo, pSession), "xrCreateSession")
            xrSession = XrSession(pSession[0], xrInstance)

            val refSpaceInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
                .referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_LOCAL)
            refSpaceInfo.poseInReferenceSpace()
                .orientation().w(1f)
            refSpaceInfo.poseInReferenceSpace().`position$`().x(0f).y(0f).z(0f)
            val pSpace = stack.mallocPointer(1)
            checkXr(XR10.xrCreateReferenceSpace(xrSession, refSpaceInfo, pSpace), "xrCreateReferenceSpace")
            appSpace = XrSpace(pSpace[0], xrSession)

            // View config (stereo expected)
            val viewCountBuf = stack.mallocInt(1)
            checkXr(
                XR10.xrEnumerateViewConfigurationViews(
                    xrInstance,
                    systemId,
                    XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    viewCountBuf,
                    null as XrViewConfigurationView.Buffer?
                ),
                "xrEnumerateViewConfigurationViews(count)"
            )
            val viewCount = viewCountBuf[0]
            viewConfigViews = XrViewConfigurationView.calloc(viewCount)
            viewConfigViews.forEach { it.type(XR10.XR_TYPE_VIEW_CONFIGURATION_VIEW) }
            checkXr(
                XR10.xrEnumerateViewConfigurationViews(
                    xrInstance,
                    systemId,
                    XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    viewCountBuf,
                    viewConfigViews
                ),
                "xrEnumerateViewConfigurationViews"
            )

            recommendedWidth = viewConfigViews[0].recommendedImageRectWidth()
            recommendedHeight = viewConfigViews[0].recommendedImageRectHeight()

            // Swapchain formats
            val fmtCount = stack.mallocInt(1)
            checkXr(XR10.xrEnumerateSwapchainFormats(xrSession, fmtCount, null as LongBuffer?), "xrEnumerateSwapchainFormats(count)")
            val formatsBuf = stack.mallocLong(fmtCount[0])
            checkXr(XR10.xrEnumerateSwapchainFormats(xrSession, fmtCount, formatsBuf), "xrEnumerateSwapchainFormats")
            val preferred = params.format.toLong()
            val formats = LongArray(fmtCount[0]) { formatsBuf[it].toLong() }
            swapchainFormat = if (formats.contains(preferred)) params.format else formats.first().toInt()

            // Allocate views
            views = XrView.calloc(viewCount).also { buf ->
                buf.forEach { it.type(XR10.XR_TYPE_VIEW) }
            }
            viewData = Array(viewCount) { Pair(PoseData(), FovData()) }

            setupActions()
        }
    }

    private fun setupActions() {
        MemoryStack.stackPush().use { stack ->
            val pLeft = stack.mallocLong(1)
            val pRight = stack.mallocLong(1)
            checkXr(XR10.xrStringToPath(xrInstance, "/user/hand/left", pLeft), "xrStringToPath left")
            checkXr(XR10.xrStringToPath(xrInstance, "/user/hand/right", pRight), "xrStringToPath right")
            handPaths[0] = pLeft[0]
            handPaths[1] = pRight[0]

            val setInfo = XrActionSetCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_ACTION_SET_CREATE_INFO)
                .actionSetName(stack.ASCII("gameplay"))
                .localizedActionSetName(stack.UTF8("Gameplay"))
                .priority(0)
            val pActionSet = stack.mallocPointer(1)
            checkXr(XR10.xrCreateActionSet(xrInstance, setInfo, pActionSet), "xrCreateActionSet")
            actionSet = XrActionSet(pActionSet[0], xrInstance)

            val subaction = stack.longs(handPaths[0], handPaths[1])

            poseAction = createAction(
                stack, "pose", XR10.XR_ACTION_TYPE_POSE_INPUT, subaction
            )
            selectAction = createAction(
                stack, "select", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT, subaction
            )
            squeezeAction = createAction(
                stack, "squeeze", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT, subaction
            )
            menuAction = createAction(
                stack, "menu", XR10.XR_ACTION_TYPE_BOOLEAN_INPUT, subaction
            )
            thumbstickAction = createAction(
                stack, "thumbstick", XR10.XR_ACTION_TYPE_VECTOR2F_INPUT, subaction
            )

            suggestBindings(
                stack,
                "/interaction_profiles/khr/simple_controller",
                arrayOf(
                    poseBinding(stack, poseAction, "/user/hand/left/input/aim/pose"),
                    poseBinding(stack, poseAction, "/user/hand/right/input/aim/pose"),
                    boolBinding(stack, selectAction, "/user/hand/left/input/select/click"),
                    boolBinding(stack, selectAction, "/user/hand/right/input/select/click"),
                    boolBinding(stack, menuAction, "/user/hand/left/input/menu/click"),
                    boolBinding(stack, menuAction, "/user/hand/right/input/menu/click")
                )
            )

            suggestBindings(
                stack,
                "/interaction_profiles/oculus/touch_controller",
                arrayOf(
                    poseBinding(stack, poseAction, "/user/hand/left/input/aim/pose"),
                    poseBinding(stack, poseAction, "/user/hand/right/input/aim/pose"),
                    boolBinding(stack, selectAction, "/user/hand/left/input/trigger/value"),
                    boolBinding(stack, selectAction, "/user/hand/right/input/trigger/value"),
                    boolBinding(stack, squeezeAction, "/user/hand/left/input/squeeze/value"),
                    boolBinding(stack, squeezeAction, "/user/hand/right/input/squeeze/value"),
                    boolBinding(stack, menuAction, "/user/hand/left/input/menu/click"),
                    vec2Binding(stack, thumbstickAction, "/user/hand/left/input/thumbstick"),
                    vec2Binding(stack, thumbstickAction, "/user/hand/right/input/thumbstick")
                )
            )

            suggestBindings(
                stack,
                "/interaction_profiles/valve/index_controller",
                arrayOf(
                    poseBinding(stack, poseAction, "/user/hand/left/input/aim/pose"),
                    poseBinding(stack, poseAction, "/user/hand/right/input/aim/pose"),
                    boolBinding(stack, selectAction, "/user/hand/left/input/trigger/click"),
                    boolBinding(stack, selectAction, "/user/hand/right/input/trigger/click"),
                    boolBinding(stack, squeezeAction, "/user/hand/left/input/squeeze/value"),
                    boolBinding(stack, squeezeAction, "/user/hand/right/input/squeeze/value"),
                    boolBinding(stack, menuAction, "/user/hand/left/input/system/click"),
                    vec2Binding(stack, thumbstickAction, "/user/hand/left/input/thumbstick"),
                    vec2Binding(stack, thumbstickAction, "/user/hand/right/input/thumbstick")
                )
            )

            // Attach action set
            val attachInfo = XrSessionActionSetsAttachInfo.calloc(stack)
                .type(XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO)
                .actionSets(stack.pointers(actionSet.address()))
            checkXr(XR10.xrAttachSessionActionSets(xrSession, attachInfo), "xrAttachSessionActionSets")

            handSpaces = Array(2) { idx ->
                val spaceInfo = XrActionSpaceCreateInfo.calloc(stack)
                    .type(XR10.XR_TYPE_ACTION_SPACE_CREATE_INFO)
                    .action(poseAction)
                    .subactionPath(handPaths[idx])
                spaceInfo.poseInActionSpace().orientation().w(1f)
                spaceInfo.poseInActionSpace().`position$`().x(0f).y(0f).z(0f)
                val pSpace = stack.mallocPointer(1)
                checkXr(XR10.xrCreateActionSpace(xrSession, spaceInfo, pSpace), "xrCreateActionSpace")
                XrSpace(pSpace[0], xrSession)
            }
        }
    }

    private fun createAction(
        stack: MemoryStack,
        name: String,
        type: Int,
        subaction: LongBuffer
    ): XrAction {
        val info = XrActionCreateInfo.calloc(stack)
            .type(XR10.XR_TYPE_ACTION_CREATE_INFO)
            .actionType(type)
            .actionName(stack.ASCII(name))
            .localizedActionName(stack.UTF8(name))
            .subactionPaths(subaction)
        val pAction = stack.mallocPointer(1)
        checkXr(XR10.xrCreateAction(actionSet, info, pAction), "xrCreateAction($name)")
        return XrAction(pAction[0], actionSet)
    }

    private fun boolBinding(stack: MemoryStack, action: XrAction, path: String): XrActionSuggestedBinding =
        XrActionSuggestedBinding.calloc(stack).apply {
            this.action(action)
            val p = stack.mallocLong(1)
            checkXr(XR10.xrStringToPath(xrInstance, path, p), "xrStringToPath($path)")
            binding(p[0])
        }

    private fun poseBinding(stack: MemoryStack, action: XrAction, path: String): XrActionSuggestedBinding =
        boolBinding(stack, action, path)

    private fun vec2Binding(stack: MemoryStack, action: XrAction, path: String): XrActionSuggestedBinding =
        boolBinding(stack, action, path)

    private fun suggestBindings(
        stack: MemoryStack,
        profile: String,
        bindings: Array<XrActionSuggestedBinding>
    ) {
        val profilePath = stack.mallocLong(1)
        if (XR10.xrStringToPath(xrInstance, profile, profilePath) != XR10.XR_SUCCESS) return
        val bindingBuf = XrActionSuggestedBinding.calloc(bindings.size, stack)
        bindings.forEachIndexed { idx, b ->
            bindingBuf[idx]
                .action(XrAction(b.action(), actionSet))
                .binding(b.binding())
        }
        val suggested = XrInteractionProfileSuggestedBinding.calloc(stack)
            .type(XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING)
            .interactionProfile(profilePath[0])
            .suggestedBindings(bindingBuf)
        XR10.xrSuggestInteractionProfileBindings(xrInstance, suggested)
    }

    fun createSwapchain(arraySize: Int = 1): Swapchain {
        MemoryStack.stackPush().use { stack ->
            val createInfo = XrSwapchainCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_SWAPCHAIN_CREATE_INFO)
                .usageFlags(
                    (XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT or
                            XR10.XR_SWAPCHAIN_USAGE_SAMPLED_BIT or
                            XR10.XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT).toLong()
                )
                .format(swapchainFormat.toLong())
                .sampleCount(1)
                .width(recommendedWidth)
                .height(recommendedHeight)
                .faceCount(1)
                .arraySize(arraySize)
                .mipCount(1)

            val pSwapchain = stack.mallocPointer(1)
            checkXr(XR10.xrCreateSwapchain(xrSession, createInfo, pSwapchain), "xrCreateSwapchain")
            val handle = XrSwapchain(pSwapchain[0], xrSession)

            val countBuf = stack.mallocInt(1)
            checkXr(XR10.xrEnumerateSwapchainImages(handle, countBuf, null as XrSwapchainImageBaseHeader.Buffer?), "xrEnumerateSwapchainImages(count)")
            val images = XrSwapchainImageVulkan2KHR.calloc(countBuf[0])
            images.forEach { it.type(KHRVulkanEnable2.XR_TYPE_SWAPCHAIN_IMAGE_VULKAN2_KHR) }
            checkXr(
                XR10.xrEnumerateSwapchainImages(
                    handle,
                    countBuf,
                    XrSwapchainImageBaseHeader.create(images.address(), images.capacity())
                ),
                "xrEnumerateSwapchainImages"
            )
            return Swapchain(handle, images, IntArray(images.capacity()) { VK_IMAGE_LAYOUT_UNDEFINED })
        }
    }

    fun viewCount(): Int = viewData.size

    fun writeProjectionView(
        index: Int,
        subImage: XrSwapchainSubImage,
        target: XrCompositionLayerProjectionView,
        stack: MemoryStack
    ) {
        if (index !in viewData.indices) return
        val (pose, fov) = viewData[index]
        target.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
            .pose(toXrPose(pose, stack))
            .fov(toXrFov(fov, stack))
            .subImage(subImage)
    }

    fun beginFrame(): Boolean {
        pollEvents()
        if (!sessionRunning) return false

        MemoryStack.stackPush().use { stack ->
            val waitInfo = XrFrameWaitInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_WAIT_INFO)
            checkXr(XR10.xrWaitFrame(xrSession, waitInfo, frameState), "xrWaitFrame")
            predictedDisplayTime = frameState.predictedDisplayTime()

            val beginInfo = XrFrameBeginInfo.calloc(stack).type(XR10.XR_TYPE_FRAME_BEGIN_INFO)
            checkXr(XR10.xrBeginFrame(xrSession, beginInfo), "xrBeginFrame")

            val locateInfo = XrViewLocateInfo.calloc(stack)
                .type(XR10.XR_TYPE_VIEW_LOCATE_INFO)
                .displayTime(predictedDisplayTime)
                .space(appSpace)
                .viewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)

            val viewCount = views.capacity()
            val viewCountOutput = stack.mallocInt(1)
            viewCountOutput.put(0, viewCount)
            checkXr(XR10.xrLocateViews(xrSession, locateInfo, viewState, viewCountOutput, views), "xrLocateViews")
            if (viewCountOutput[0] != viewCount) {
                // ignore unexpected count; keep most recent values
            }
            for (i in 0 until viewCount) {
                val pose = views[i].pose()
                val fov = views[i].fov()
                val target = viewData[i]
                target.first.position.set(pose.`position$`().x(), pose.`position$`().y(), pose.`position$`().z())
                target.first.orientation.set(
                    pose.orientation().x(),
                    pose.orientation().y(),
                    pose.orientation().z(),
                    pose.orientation().w()
                )
                target.second.left = fov.angleLeft()
                target.second.right = fov.angleRight()
                target.second.up = fov.angleUp()
                target.second.down = fov.angleDown()
            }

            syncActions()
        }
        return true
    }

    private fun pollEvents() {
        MemoryStack.stackPush().use { stack ->
            val event = XrEventDataBuffer.calloc(stack).type(XR10.XR_TYPE_EVENT_DATA_BUFFER)
            while (XR10.xrPollEvent(xrInstance, event) == XR10.XR_SUCCESS) {
                when (event.type()) {
                    XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                        val stateChanged = XrEventDataSessionStateChanged.create(event.address())
                        sessionState = stateChanged.state()
                        when (sessionState) {
                            XR10.XR_SESSION_STATE_READY -> {
                                val beginInfo = XrSessionBeginInfo.calloc(stack)
                                    .type(XR10.XR_TYPE_SESSION_BEGIN_INFO)
                                    .primaryViewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                                checkXr(XR10.xrBeginSession(xrSession, beginInfo), "xrBeginSession")
                                sessionRunning = true
                            }

                            XR10.XR_SESSION_STATE_STOPPING -> {
                                XR10.xrEndSession(xrSession)
                                sessionRunning = false
                            }

                            XR10.XR_SESSION_STATE_EXITING, XR10.XR_SESSION_STATE_LOSS_PENDING -> sessionRunning = false
                        }
                    }
                }
            }
        }
    }

    private fun syncActions() {
        MemoryStack.stackPush().use { stack ->
            val active = XrActiveActionSet.calloc(1, stack)
            active[0].actionSet(actionSet)

            val syncInfo = XrActionsSyncInfo.calloc(stack)
                .type(XR10.XR_TYPE_ACTIONS_SYNC_INFO)
                .activeActionSets(active)
            XR10.xrSyncActions(xrSession, syncInfo)

            fun readBool(action: XrAction, handPath: Long): Boolean {
                val getInfo = XrActionStateGetInfo.calloc(stack)
                    .type(XR10.XR_TYPE_ACTION_STATE_GET_INFO)
                    .action(action)
                    .subactionPath(handPath)
                val state = XrActionStateBoolean.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_BOOLEAN)
                XR10.xrGetActionStateBoolean(xrSession, getInfo, state)
                return state.currentState()
            }

            fun readVec(action: XrAction, handPath: Long): Vector3f {
                val getInfo = XrActionStateGetInfo.calloc(stack)
                    .type(XR10.XR_TYPE_ACTION_STATE_GET_INFO)
                    .action(action)
                    .subactionPath(handPath)
                val state = XrActionStateVector2f.calloc(stack).type(XR10.XR_TYPE_ACTION_STATE_VECTOR2F)
                XR10.xrGetActionStateVector2f(xrSession, getInfo, state)
                return Vector3f(state.currentState().x(), state.currentState().y(), 0f)
            }

            val maskBits = intArrayOf(0, 1, 2, 3) // select, squeeze, menu, thumbstick-click (use select)
            for (i in 0..1) {
                var mask = 0L
                val select = readBool(selectAction, handPaths[i])
                val squeeze = readBool(squeezeAction, handPaths[i])
                val menu = readBool(menuAction, handPaths[i])
                // Reuse select for thumbstick-click bit if we have no explicit click
                val thumbVec = readVec(thumbstickAction, handPaths[i])
                val thumbClick = select

                if (select) mask = mask or (1L shl maskBits[0])
                if (squeeze) mask = mask or (1L shl maskBits[1])
                if (menu) mask = mask or (1L shl maskBits[2])
                if (thumbClick) mask = mask or (1L shl maskBits[3])
                controllerMasks[i] = mask
                controllerAxes[i] = floatArrayOf(thumbVec.x, thumbVec.y)

                val loc = XrSpaceLocation.calloc(stack).type(XR10.XR_TYPE_SPACE_LOCATION)
                val vel = XrSpaceVelocity.calloc(stack).type(XR10.XR_TYPE_SPACE_VELOCITY)
                loc.next(vel.address())
                XR10.xrLocateSpace(handSpaces[i], appSpace, predictedDisplayTime, loc)
                val hasPosition = (loc.locationFlags().toLong() and XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT.toLong()) != 0L
                val hasOrientation = (loc.locationFlags().toLong() and XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT.toLong()) != 0L
                val connected = (loc.locationFlags().toLong() and XR10.XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT.toLong()) != 0L ||
                        (loc.locationFlags().toLong() and XR10.XR_SPACE_LOCATION_POSITION_TRACKED_BIT.toLong()) != 0L
                if (hasPosition || hasOrientation) {
                    val pos = Vector3f(loc.pose().`position$`().x(), loc.pose().`position$`().y(), loc.pose().`position$`().z())
                    val ori = Quaternionf(
                        loc.pose().orientation().x(),
                        loc.pose().orientation().y(),
                        loc.pose().orientation().z(),
                        loc.pose().orientation().w()
                    )
                    val lv = if ((vel.velocityFlags().toLong() and XR10.XR_SPACE_VELOCITY_LINEAR_VALID_BIT.toLong()) != 0L)
                        Vector3f(vel.linearVelocity().x(), vel.linearVelocity().y(), vel.linearVelocity().z()) else Vector3f()
                    val av = if ((vel.velocityFlags().toLong() and XR10.XR_SPACE_VELOCITY_ANGULAR_VALID_BIT.toLong()) != 0L)
                        Vector3f(vel.angularVelocity().x(), vel.angularVelocity().y(), vel.angularVelocity().z()) else Vector3f()
                    val matrix = poseToMatrix(pos, ori)
                    controllerPoses[i] = ControllerPose(
                        deviceIndex = i,
                        matrix = matrix,
                        position = pos,
                        orientation = ori,
                        linearVelocity = lv,
                        angularVelocity = av,
                        connected = connected,
                        trackingValid = hasPosition || hasOrientation,
                        trackingResult = if (hasPosition || hasOrientation) 0 else -1
                    )
                } else {
                    controllerPoses[i] = null
                }
            }
        }
    }

    private fun poseToMatrix(pos: Vector3f, ori: Quaternionf, dst: Matrix4f = Matrix4f()): Matrix4f =
        dst.identity().translate(pos).rotate(ori)

    private fun toXrPose(pose: PoseData, stack: MemoryStack): XrPosef =
        XrPosef.calloc(stack).apply {
            `position$`().x(pose.position.x).y(pose.position.y).z(pose.position.z)
            orientation().x(pose.orientation.x).y(pose.orientation.y).z(pose.orientation.z).w(pose.orientation.w)
        }

    private fun toXrFov(fov: FovData, stack: MemoryStack): XrFovf =
        XrFovf.calloc(stack)
            .angleLeft(fov.left)
            .angleRight(fov.right)
            .angleUp(fov.up)
            .angleDown(fov.down)

    fun getViewProjection(
        eye: Int,
        zNear: Float,
        zFar: Float,
        vulkanYFlip: Boolean,
        outView: Matrix4f = Matrix4f(),
        outProj: Matrix4f = Matrix4f()
    ): Pair<Matrix4f, Matrix4f> {
        if (eye !in viewData.indices) return outView.identity() to outProj.identity()
        val (pose, fov) = viewData[eye]
        val worldFromEye = poseToMatrix(pose.position, pose.orientation, outView)
        outView.set(worldFromEye).invert()

        val tanLeft = kotlin.math.tan(fov.left.toDouble()).toFloat()
        val tanRight = kotlin.math.tan(fov.right.toDouble()).toFloat()
        val tanDown = kotlin.math.tan(fov.down.toDouble()).toFloat()
        val tanUp = kotlin.math.tan(fov.up.toDouble()).toFloat()

        outProj.identity()
        outProj.m00(2f / (tanRight - tanLeft))
        outProj.m02((tanRight + tanLeft) / (tanRight - tanLeft))
        outProj.m11(2f / (tanUp - tanDown))
        outProj.m12((tanUp + tanDown) / (tanUp - tanDown))
        outProj.m22(-zFar / (zFar - zNear))
        outProj.m23(-(zFar * zNear) / (zFar - zNear))
        outProj.m32(-1f)
        outProj.m33(0f)
        if (vulkanYFlip) outProj.m11(-outProj.m11())
        return outView to outProj
    }

    fun leftMVP(zNear: Float = 0.01f, zFar: Float = 1000f) =
        getViewProjection(0, zNear, zFar, vulkanYFlip = true)

    fun rightMVP(zNear: Float = 0.01f, zFar: Float = 1000f) =
        getViewProjection(1, zNear, zFar, vulkanYFlip = true)

    fun getControllerPose(role: ControllerRole): ControllerPose? =
        when (role) {
            ControllerRole.LEFT -> controllerPoses[0]
            ControllerRole.RIGHT -> controllerPoses[1]
        }

    fun getControllerState(role: ControllerRole): ControllerState? {
        val idx = when (role) {
            ControllerRole.LEFT -> 0
            ControllerRole.RIGHT -> 1
        }
        val pose = controllerPoses[idx] ?: return null
        return ControllerState(
            deviceIndex = idx,
            packetNum = 0,
            buttonPressedMask = controllerMasks[idx],
            buttonTouchedMask = controllerMasks[idx],
            axes = controllerAxes[idx]
        )
    }

    fun endFrame(layers: PointerBuffer?) {
        MemoryStack.stackPush().use { stack ->
            val endInfo = XrFrameEndInfo.calloc(stack)
                .type(XR10.XR_TYPE_FRAME_END_INFO)
                .displayTime(predictedDisplayTime)
                .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                .layerCount(layers?.remaining() ?: 0)
            layers?.let { endInfo.layers(it) }
            XR10.xrEndFrame(xrSession, endInfo)
        }
    }

    fun cleanup() {
        handSpaces.forEach { XR10.xrDestroySpace(it) }
        XR10.xrDestroyActionSet(actionSet)
        XR10.xrDestroySpace(appSpace)
        XR10.xrDestroySession(xrSession)
        XR10.xrDestroyInstance(xrInstance)
        frameState.free()
        viewState.free()
        views.free()
        viewConfigViews.free()
    }

    companion object {
        fun checkXr(result: Int, msg: String) {
            if (result != XR10.XR_SUCCESS) {
                throw IllegalStateException("$msg (xr=$result)")
            }
        }
    }
}

class OpenXRRenderTarget(
    val renderer: Renderer,
    val eyeParams: OpenXREyeParams.() -> Unit = {}
) : Target {
    private var initialized = false

    private lateinit var left: EyeImage
    private lateinit var right: EyeImage

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set

    val params = OpenXREyeParams().apply(eyeParams)
    private val modules = linkedMapOf<String, afield.four.modules.ModulePointer>()

    private val session = OpenXRSession(renderer, params)
    private val swapchains = Array<Swapchain>(2) { session.createSwapchain() }
    private val acquiredIndices = IntArray(2) { -1 }

    val controllerButtons by lazy { ControllerButtonEvents() }
    private var frameActive = false

    init {
        recommendedWidth = session.recommendedWidth
        recommendedHeight = session.recommendedHeight
        left = EyeImage(renderer, recommendedWidth, recommendedHeight, params)
        right = EyeImage(renderer, recommendedWidth, recommendedHeight, params)
        initialized = true

        renderer.renderStart["__openxr__"] = sequence {
            while (true) {
                frameActive = session.beginFrame()
                if (frameActive) {
                    acquireSwapchainImages()
                }
                yield(true)
            }
        }

        renderer.renderEnd["__openxr__"] = sequence {
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
        if (!initialized || !frameActive) return
        val cmd = command.vkCommandBuffer

        left.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, left.width.toFloat(), left.height.toFloat())) }
        left.endRendering(cmd)
        copyEyeToSwapchain(cmd, 0, left)

        right.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, right.width.toFloat(), right.height.toFloat())) }
        right.endRendering(cmd)
        copyEyeToSwapchain(cmd, 1, right)
    }

    private fun acquireSwapchainImages() {
        MemoryStack.stackPush().use { stack ->
            val acquire = XrSwapchainImageAcquireInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)
            val waitInfo = XrSwapchainImageWaitInfo.calloc(stack)
                .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                .timeout(XR10.XR_INFINITE_DURATION)
            for (i in 0..1) {
                val idx = stack.mallocInt(1)
                OpenXRSession.checkXr(XR10.xrAcquireSwapchainImage(swapchains[i].handle, acquire, idx), "xrAcquireSwapchainImage[$i]")
                acquiredIndices[i] = idx[0]
                OpenXRSession.checkXr(XR10.xrWaitSwapchainImage(swapchains[i].handle, waitInfo), "xrWaitSwapchainImage[$i]")
            }
        }
    }

    private fun copyEyeToSwapchain(cmd: VkCommandBuffer, eyeIdx: Int, eyeImage: EyeImage) {
        val sc = swapchains[eyeIdx]
        val acquired = acquiredIndices[eyeIdx]
        if (acquired < 0) return
        MemoryStack.stackPush().use { stack ->
            val subRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1)

            image_layout_transition(
                cmd,
                sc.images[acquired].image(),
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                0,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                sc.layouts[acquired],
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                subRange
            )

            val region = VkImageCopy.calloc(1, stack)
            region[0].srcSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1)
            region[0].dstSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1)
            region[0].srcOffset().set(0, 0, 0)
            region[0].dstOffset().set(0, 0, 0)
            region[0].extent().set(eyeImage.width, eyeImage.height, 1)

            vkCmdCopyImage(
                cmd,
                eyeImage.colorImage.vkImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                sc.images[acquired].image(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                region
            )

            image_layout_transition(
                cmd,
                sc.images[acquired].image(),
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_PIPELINE_STAGE_2_NONE,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                0,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                subRange
            )
            sc.layouts[acquired] = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
        }
    }

    fun submitAfterGraphics() {
        if (!initialized || !frameActive) return
        MemoryStack.stackPush().use { stack ->
            val release = XrSwapchainImageReleaseInfo.calloc(stack).type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
            val viewCount = minOf(2, session.viewCount())
            val views = XrCompositionLayerProjectionView.calloc(viewCount, stack)
            for (i in 0 until viewCount) {
                if (acquiredIndices[i] >= 0) {
                    OpenXRSession.checkXr(
                        XR10.xrReleaseSwapchainImage(swapchains[i].handle, release),
                        "xrReleaseSwapchainImage[$i]"
                    )
                }
                val subImage = XrSwapchainSubImage.calloc(stack)
                    .swapchain(swapchains[i].handle)
                    .imageArrayIndex(0)
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
            layers.put(0, projection.address())
            layers.flip()
            session.endFrame(layers)
        }
        acquiredIndices.fill(-1)
        frameActive = false
    }

    fun cleanup() {
        if (!initialized) return
        swapchains.forEach { XR10.xrDestroySwapchain(it.handle) }
        right.cleanup()
        left.cleanup()
        session.cleanup()
        initialized = false
    }

    fun leftMVP(zNear: Float = 0.01f, zFar: Float = 1000f) = session.leftMVP(zNear, zFar)
    fun rightMVP(zNear: Float = 0.01f, zFar: Float = 1000f) = session.rightMVP(zNear, zFar)

    fun getControllerPose(role: ControllerRole) = session.getControllerPose(role)
    fun getControllerState(role: ControllerRole) = session.getControllerState(role)

    inner class ControllerButtonEvents(val name: String = "__controllerButtons__") {
        private val down = mutableMapOf<String, MutableMap<String, () -> DownResult>>()
        private val up = mutableMapOf<String, MutableMap<String, () -> Boolean>>()
        private val pendingUp = mutableMapOf<String, MutableList<() -> Unit>>()
        private val wasDown = mutableSetOf<String>()

        init {
            renderer.window.onPoll[name] = sequence {
                while (true) {
                    val keys: Set<String> = (down.keys + up.keys)
                    val nowDown = mutableSetOf<String>()
                    for (k in keys) {
                        val parsed = parseKey(k) ?: continue
                        val state = getControllerState(
                            if (parsed.hand == Hand.LEFT) ControllerRole.LEFT else ControllerRole.RIGHT
                        )
                        val pressed = state?.let { isPressed(it, parsed.buttonId) } ?: false
                        if (pressed) nowDown.add(k)
                    }

                    val newDown = nowDown - wasDown
                    val newUp = wasDown - nowDown

                    newDown.forEach { n ->
                        down[n]?.let { map ->
                            val toKeep = mutableListOf<Pair<String, () -> DownResult>>()
                            map.forEach { (k, cb) ->
                                when (val r = cb()) {
                                    DownResult.Keep -> toKeep += k to cb
                                    DownResult.Remove -> {}
                                    is DownResult.OnUp -> {
                                        pendingUp.getOrPut(n) { mutableListOf() }.add(r.onUp)
                                        toKeep += k to cb
                                    }

                                    is DownResult.WhileDown -> {
                                        val gen = r.generator
                                        gen.start()
                                        if (!gen.isCompleted) {
                                            whileDownSeq.getOrPut(n) { mutableListOf() }.add(gen)
                                        }
                                        toKeep += k to cb
                                    }
                                }
                            }
                            map.clear()
                            toKeep.forEach { (k, cb) -> map[k] = cb }
                        }
                    }

                    newUp.forEach { n ->
                        pendingUp.remove(n)?.forEach { it.invoke() }
                    }

                    whileDownSeq.entries.toList().forEach { (k, gens) ->
                        val input = nowDown.contains(k)
                        val toRemove = mutableListOf<Generator<Boolean, Boolean>>()
                        gens.forEach { g ->
                            if (!g.isCompleted) g.next(input)
                            if (g.isCompleted) toRemove += g
                        }
                        gens.removeAll(toRemove)
                        if (gens.isEmpty()) whileDownSeq.remove(k)
                    }

                    newUp.forEach { n -> up[n]?.let { map -> map.values.retainAll { it.invoke() } } }
                    wasDown.clear()
                    wasDown.addAll(nowDown)
                    yield(true)
                }
            }
        }

        fun onDownAndUp(key: String, name: String, callback: () -> DownResult) {
            if (!isWellFormed(key)) warnNoSuchKey(key)
            down.computeIfAbsent(key) { mutableMapOf() }[name] = callback
        }

        fun onDownUntilUp(key: String, name: String, onUp: () -> Unit) {
            onDownAndUp(key, name) { DownResult.OnUp(onUp) }
        }

        fun onDown(key: String, name: String, callback: () -> Boolean) {
            onDownAndUp(key, name) { if (callback()) DownResult.Keep else DownResult.Remove }
        }

        fun onUp(key: String, name: String, callback: () -> Boolean) {
            if (!isWellFormed(key)) warnNoSuchKey(key)
            up.computeIfAbsent(key) { mutableMapOf() }[name] = callback
        }

        private fun isWellFormed(key: String): Boolean =
            key.startsWith("left_") || key.startsWith("right_")

        private fun warnNoSuchKey(key: String) {
            if (!isWellFormed(key)) {
                println(" -- warning, controller key must be 'left_N' or 'right_N', got: $key")
            }
        }

        private fun parseKey(key: String): Parsed? {
            val parts = key.split('_')
            if (parts.size != 2) {
                warnNoSuchKey(key); return null
            }
            val hand = when (parts[0]) {
                "left" -> Hand.LEFT
                "right" -> Hand.RIGHT
                else -> {
                    warnNoSuchKey(key); return null
                }
            }
            val id = parts[1].toIntOrNull()
            if (id == null || id < 0 || id >= 64) {
                println(" -- warning, button id must be 0..63: $key")
                return null
            }
            return Parsed(hand, id)
        }

        private fun isPressed(state: ControllerState, buttonBitIndex: Int): Boolean {
            val mask = 1L shl buttonBitIndex
            return (state.buttonPressedMask and mask) != 0L
        }
    }

    companion object {
        private val whileDownSeq = mutableMapOf<String, MutableList<Generator<Boolean, Boolean>>>()
    }
}

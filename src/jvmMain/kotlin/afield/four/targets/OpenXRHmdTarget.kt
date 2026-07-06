package afield.four.targets

import afield.Rect
import afield.four.CPUSpan
import afield.four.Renderer
import afield.four.Target
import afield.four.device.Attachment
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.device.GpuProfiler
import afield.four.device.Instance
import afield.four.device.ProfilingOptions
import afield.four.modules.MeshModule
import afield.four.modules.MeshModulePointer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.pointer
import afield.four.buffers.MissingRefException
import afield.four.texture.HasImageView
import afield.four.texture.image_layout_transition
import afield.four.util.Options
import afield.logger.lprintln
import afield.stdlib.*
import afield.stdlib.remAssign
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.primitives.Rectanglef
import org.lwjgl.openxr.*
import org.lwjgl.openxr.KHRVulkanEnable
import org.lwjgl.openxr.KHRVulkanEnable2
import org.lwjgl.openxr.XR10.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.joml.*
import org.lwjgl.vulkan.*
import kotlin.math.tan

private data class XrEyeSwapchain(
    val swapchain: XrSwapchain,
    val width: Int,
    val height: Int,
    val format: Int,
    val images: LongArray,
    val imageViews: LongArray,
    val depthAttachments: Array<Attachment>
) {
    val colorSeen: BooleanArray = BooleanArray(images.size)
    val depthSeen: BooleanArray = BooleanArray(images.size)

    // Preallocated viewport rectangles (avoid per-frame heap allocations).
    val fullRect: Rect = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
    val rectF: Rectanglef = Rectanglef(0f, 0f, width.toFloat(), height.toFloat())
}

/**
 * OpenXR HMD render target.
 *
 * Minimal goal:
 * - Acquire per-eye swapchain images
 * - Clear them (default: purple)
 * - Optionally render `Module`s/`MeshModule`s into each eye via dynamic rendering
 * - Submit to Vulkan graphics queue and `xrEndFrame` with a projection layer
 *
 * Side-by-side mirror / postprocessing plan:
 * - Render your whole stereo view into a single offscreen texture (left eye on the left half,
 *   right eye on the right half) using [RenderToTexture].
 *   - Use [sideBySideRTTParameters] to get recommended dimensions: width = 2*eyeWidth, height = eyeHeight.
 *   - Ensure the RTT color image is created with transfer-src usage (RTTParameters.colorTransferSrc = true).
 * - Present to the HMD by copying halves into the acquired OpenXR swapchain images.
 *   - Call [copySideBySideToEyes] once to register the source; during [renderFrame] we do:
 *     left-half -> left eye, right-half -> right eye via vkCmdCopyImage.
 *   - The OpenXR swapchains are created with TRANSFER_DST usage for this path.
 * - Show the exact same side-by-side RTT in your desktop window however you like (no need to sample
 *   or blit from the OpenXR swapchain images).
 *
 * Notes:
 * - The copy path assumes the source RTT is usually in SHADER_READ_ONLY_OPTIMAL (current RenderToTexture default)
 *   and temporarily transitions it to TRANSFER_SRC_OPTIMAL for the copy, then back.
 * - Call [disableSideBySideCopy] to go back to the normal per-eye dynamic-rendering path.
 *
 * This consumes the OpenXR bootstrap created in [afield.four.device.Instance] when [Options.enableVR] is true.
 */
class OpenXRHmdTarget(
    val renderer: Renderer = fromContext()
) {

    @Volatile
    private var sideBySideSource: HasImageView? = null

    // Scratch arrays to avoid per-frame allocations.
    private var acquiredIndicesScratch: IntArray = IntArray(0)

    // -------------------------------------------------------------------------
    // Per-eye view/projection state (updated from xrLocateViews)
    // -------------------------------------------------------------------------

    /** Index of the eye currently being rendered inside [renderEyesVulkan], or -1 when idle. */
    var currentEyeIndex: Int = -1
        private set

    private data class EyeViewState(
        var px: Float = 0f,
        var py: Float = 0f,
        var pz: Float = 0f,
        var qx: Float = 0f,
        var qy: Float = 0f,
        var qz: Float = 0f,
        var qw: Float = 1f,
        var angleLeft: Float = 0f,
        var angleRight: Float = 0f,
        var angleUp: Float = 0f,
        var angleDown: Float = 0f,
    )

    private var eyeStates: Array<EyeViewState> = emptyArray()
    private var eyeViewMatrices: Array<Matrix4f> = emptyArray()
    private var eyeProjMatrices: Array<Matrix4f> = emptyArray()

    /**
     * Copies the current eye's view matrix into [out] and returns it.
     * Only valid while rendering (i.e. called from module render callbacks).
     */
    fun currentEyeViewMatrix(out: Matrix4f = Matrix4f()): Matrix4f {
        val i = currentEyeIndex
        check(i in eyeViewMatrices.indices) { "No current eye (currentEyeIndex=$i)" }
        return out.set(eyeViewMatrices[i])
    }

    /**
     * Copies the current eye's projection matrix into [out] and returns it.
     * Only valid while rendering (i.e. called from module render callbacks).
     */
    fun currentEyeProjectionMatrix(out: Matrix4f = Matrix4f()): Matrix4f {
        val i = currentEyeIndex
        check(i in eyeProjMatrices.indices) { "No current eye (currentEyeIndex=$i)" }
        return out.set(eyeProjMatrices[i])
    }

    /** Like [currentEyeViewMatrix] but for an explicit eye index. */
    fun eyeViewMatrix(eyeIndex: Int, out: Matrix4f = Matrix4f()): Matrix4f {
        check(eyeIndex in eyeViewMatrices.indices) { "Invalid eyeIndex=$eyeIndex" }
        return out.set(eyeViewMatrices[eyeIndex])
    }

    /** Like [currentEyeProjectionMatrix] but for an explicit eye index. */
    fun eyeProjectionMatrix(eyeIndex: Int, out: Matrix4f = Matrix4f()): Matrix4f {
        check(eyeIndex in eyeProjMatrices.indices) { "Invalid eyeIndex=$eyeIndex" }
        return out.set(eyeProjMatrices[eyeIndex])
    }

    /**
     * Approximate head pose from the currently tracked eye views.
     *
     * Returns false if views are not initialized yet.
     * The position is the average of all eye positions, and the orientation
     * is taken from the first eye (OpenXR typically reports identical orientation per-eye).
     */
    fun headPose(outPosition: Vec3 = Vec3(), outOrientation: Quat = Quat()): Boolean {
        if (eyeStates.isEmpty()) return false

        var px = 0f
        var py = 0f
        var pz = 0f
        for (s in eyeStates) {
            px += s.px
            py += s.py
            pz += s.pz
        }
        val inv = 1f / eyeStates.size.toFloat()
        outPosition.set(px * inv, py * inv, pz * inv)

        val s0 = eyeStates[0]
        outOrientation.set(s0.qx, s0.qy, s0.qz, s0.qw)
        return true
    }

    // -------------------------------------------------------------------------
    // Controller state (basic: grip pose + select/squeeze)
    // -------------------------------------------------------------------------

    enum class Hand { LEFT, RIGHT }

    data class ControllerState(
        var active: Boolean = false,
        var poseValid: Boolean = false,

        var position : Vec3 = Vec3(),
        var rotation : Quat = Quat(),

        // Generic select (bound per-profile; for Index we bind trigger/click)
        var selectPressed: Boolean = false,

        // Trigger
        var triggerValue: Float = 0f,
        var triggerPressed: Boolean = false,

        // Grip / squeeze
        var squeezeValue: Float = 0f,
        var squeezePressed: Boolean = false,

        // Trackpad (Vive + Index)
        var trackpadX: Float = 0f,
        var trackpadY: Float = 0f,
        var trackpadTouched: Boolean = false,
        var trackpadPressed: Boolean = false,

        // Thumbstick (Index)
        var thumbstickX: Float = 0f,
        var thumbstickY: Float = 0f,
        var thumbstickTouched: Boolean = false,
        var thumbstickPressed: Boolean = false,

        // Buttons (Index)
        var aPressed: Boolean = false,
        var bPressed: Boolean = false,

        // Menu (Vive)
        var menuPressed: Boolean = false,
    )

    private val leftController = ControllerState()
    private val rightController = ControllerState()

    private var xrActionSet: XrActionSet? = null
    private var xrPoseAction: XrAction? = null
    private var xrAimPoseAction: XrAction? = null
    private var xrSelectAction: XrAction? = null
    private var xrTriggerValueAction: XrAction? = null
    private var xrTriggerClickAction: XrAction? = null
    private var xrSqueezeValueAction: XrAction? = null
    private var xrSqueezeClickAction: XrAction? = null
    private var xrTrackpadAction: XrAction? = null
    private var xrTrackpadTouchAction: XrAction? = null
    private var xrTrackpadClickAction: XrAction? = null
    private var xrThumbstickAction: XrAction? = null
    private var xrThumbstickTouchAction: XrAction? = null
    private var xrThumbstickClickAction: XrAction? = null
    private var xrAAction: XrAction? = null
    private var xrBAction: XrAction? = null
    private var xrMenuAction: XrAction? = null
    private var xrHapticAction: XrAction? = null
    private var leftHandPath: Long = XR_NULL_PATH
    private var rightHandPath: Long = XR_NULL_PATH
    private var leftGripSpace: XrSpace? = null
    private var rightGripSpace: XrSpace? = null
    private var leftAimSpace: XrSpace? = null
    private var rightAimSpace: XrSpace? = null

    private var inputDebugPrinted = false

    fun controllerState(hand: Hand, out: ControllerState = ControllerState()): ControllerState {
        val src = if (hand == Hand.LEFT) leftController else rightController
        out.active = src.active
        out.poseValid = src.poseValid
        out.position %= src.position
        out.rotation %= src.rotation
        out.selectPressed = src.selectPressed
        out.triggerValue = src.triggerValue
        out.triggerPressed = src.triggerPressed
        out.squeezeValue = src.squeezeValue
        out.squeezePressed = src.squeezePressed
        out.trackpadX = src.trackpadX
        out.trackpadY = src.trackpadY
        out.trackpadTouched = src.trackpadTouched
        out.trackpadPressed = src.trackpadPressed
        out.thumbstickX = src.thumbstickX
        out.thumbstickY = src.thumbstickY
        out.thumbstickTouched = src.thumbstickTouched
        out.thumbstickPressed = src.thumbstickPressed
        out.aPressed = src.aPressed
        out.bPressed = src.bPressed
        out.menuPressed = src.menuPressed
        return out
    }

    fun hapticPulse(
        hand: Hand,
        amplitude: Float = 0.5f,
        durationNs: Long = 20_000_000L,
        frequencyHz: Float = 0f,
    ) {
        val session = xrSession ?: return
        val hapticAction = xrHapticAction ?: return
        val subactionPath = if (hand == Hand.LEFT) leftHandPath else rightHandPath

        MemoryStack.stackPush().use { stack ->
            val vib = XrHapticVibration.calloc(stack)
                .type(XR_TYPE_HAPTIC_VIBRATION)
                .amplitude(amplitude)
                .duration(durationNs)
                .frequency(frequencyHz)

            val hapticBase = XrHapticBaseHeader.create(vib.address())
            val info = XrHapticActionInfo.calloc(stack)
                .type(XR_TYPE_HAPTIC_ACTION_INFO)
                .action(hapticAction)
                .subactionPath(subactionPath)

            xrApplyHapticFeedback(session, info, hapticBase)
        }
    }

    fun controllerGripPoseMatrix(hand: Hand, out: Matrix4f = Matrix4f()): Matrix4f {
        val s = if (hand == Hand.LEFT) leftController else rightController
        return out.identity()
            .translate(s.position.x, s.position.y, s.position.z)
            .rotate(Quaternionf(s.rotation.x, s.rotation.y, s.rotation.z, s.rotation.w))
    }

    // public module lists (mirrors RenderTarget)
    var modules = mutableMapOf<String, ModulePointer>()
    var meshModules = mutableMapOf<String, MeshModulePointer>()
    // Clear color for the HMD swapchain (RGBA). Defaults to Options.openXRClearColor.
    var clearColor: FloatArray = Options.openXRClearColor.clone()

    fun setClearColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        clearColor = floatArrayOf(r, g, b, a)
    }

    /**
     * Convenience: Build an [RTTParameters] for a single side-by-side render target (left eye on the left half,
     * right eye on the right half) matching the OpenXR recommended eye dimensions.
     *
     * Requires this target to have initialized its swapchains (i.e. after the first [renderFrame] call).
     */
    fun sideBySideRTTParameters(parameters: RTTParameters.() -> Unit = {}): RTTParameters {
        check(swapchains.size >= 2) {
            "OpenXR swapchains not initialized yet; call renderFrame() once before requesting RTT parameters"
        }

        val left = swapchains[0]
        val right = swapchains[1]
        check(left.width == right.width && left.height == right.height) {
            "Expected stereo eyes to share the same size; got L=${left.width}x${left.height}, R=${right.width}x${right.height}"
        }

        return RTTParameters(
            width = left.width * 2,
            height = left.height,
            mipLevels = 1,
            format = left.format,
            colorAttachments = 1,
            colorFormats = listOf(left.format),
            colorLayerCount = 1,
            clearFirst = true,
            colorSampled = true,
            colorTransferSrc = true,
            useDepth = true,
        ).apply(parameters)
    }

    /**
     * Convenience: Copy a side-by-side image into the OpenXR eyes every frame.
     *
     * The source is interpreted as:
     * - left half (x in [0, eyeWidth))  -> left eye
     * - right half (x in [eyeWidth, 2*eyeWidth)) -> right eye
     *
     * This is executed during [renderFrame] (after acquiring swapchain images, before releasing them).
     */
    fun copySideBySideToEyes(source: HasImageView) {
        sideBySideSource = source
    }

    fun disableSideBySideCopy() {
        sideBySideSource = null
    }

    @JvmName("plusAssign")
    operator fun plusAssign(m: Pair<String, ModulePointer>) {
        if (m.first in modules) modules.remove(m.first)
        modules[m.first] = m.second
    }

    @JvmName("plusAssign_mp")
    operator fun plusAssign(m: ModulePointer) {
        modules[m.current.parameters.name] = m
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m: Pair<String, Module>) {
        modules[m.first] = m.second.pointer()
    }

    @JvmName("plusAssign_mesh")
    operator fun plusAssign(m: Pair<String, MeshModulePointer>) {
        meshModules[m.first] = m.second
    }

    @JvmName("plusAssign_mmp")
    operator fun plusAssign(m: MeshModulePointer) {
        meshModules[m.current.parameters.name] = m
    }

    @JvmName("plusAssign_mmodule")
    operator fun plusAssign(m: Pair<String, MeshModule>) {
        meshModules[m.first] = m.second.pointer()
    }

    // --- OpenXR handles ---
    private var xrBootstrap: Instance.Companion.OpenXRBootstrap? = null
    private var xrInstance: XrInstance? = null
    private var xrSystemId: Long = 0
    private var xrSession: XrSession? = null
    private var xrSpace: XrSpace? = null

    private var viewCount: Int = 0
    private var viewConfigs: XrViewConfigurationView.Buffer? = null

    private val swapchains = mutableListOf<XrEyeSwapchain>()

    // --- state ---
    private var sessionRunning = false
    private var sessionState = XR_SESSION_STATE_UNKNOWN

    // --- Vulkan submission resources ---
    private val cmd = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
    private val fence = Fence(renderer.device, signaled = true)

    // lazy init guard
    private var initialized = false
    private var disabledReason: String? = null

    // --- Profiling infrastructure (similar to Renderer) ---
    data class XrFrameMarkers(
        var tsFrameBegin: Int = -1,
        var tsAfterEyes: Int = -1,
        var tsFrameEnd: Int = -1,
        var pipeStatsEyes: Int = -1,
        var perfFrameIndex: Int = -1
    )

    data class XrEyeMarks(
        val eyeIndex: Int,
        var tsBegin: Int = -1,
        var tsEnd: Int = -1,
        var pipeStatsIndex: Int = -1,
        var perfIndex: Int = -1
    )

    data class XrProfilerState(
        val profiler: GpuProfiler,
        var markers: XrFrameMarkers = XrFrameMarkers(),
        var perFrameEyeMarks: MutableList<XrEyeMarks> = mutableListOf(),
        val pending: ArrayDeque<Pending> = ArrayDeque(),
        val lock: Any = Any()
    )

    data class Pending(val frameCounter: Long)
    private companion object { const val READBACK_LAG = 3 }

    private var profilerState: XrProfilerState? = null
    var xrFrameCounter = 0L
        private set

    init {
        run {
            if (!Options.enableVR) {
                disabledReason = "Options.enableVR is false"
                return@run
            }

            // Consume the OpenXR bootstrap from Instance (created before VkInstance/VkDevice).
            // Ownership of the XrInstance transfers to this target.
            val b = Instance.consumeOpenXRBootstrap()
            if (b == null) {
                disabledReason = "OpenXR bootstrap missing (Instance.consumeOpenXRBootstrap() returned null)"
                return@run
            }
            xrBootstrap = b

            xrInstance = b.xrInstance
            xrSystemId = b.systemId

            // full initialization happens on first renderFrame() so construction stays cheap
        }
    }

    /**
     * Render one OpenXR frame.
     *
     * Called by Renderer once per frame (see Renderer integration).
     */
    fun renderFrame() {
        if (disabledReason != null) return
        if (!initialized) {
            renderer.runOnQueueThread {
                initSessionAndSwapchains()
            }
            initialized = true
        }

        // Drain ready frames (no stalls) for profiling readback
        profilerState?.let { ps ->
            synchronized(ps.lock) {
                while (ps.pending.isNotEmpty()) {
                    val head = ps.pending.first()
                    val enoughLag = ps.pending.size > READBACK_LAG
                    // For XR we only check lag, not fence (fence is per-frame, not per-swapchain-image)
                    if (!enoughLag) break
                    resolveProfilingForFrame(head.frameCounter)
                    ps.pending.removeFirst()
                }
            }
        }

        renderer.runOnQueueThread {
            CPUSpan.time("xr_events") {
                if (!pollEvents()) return@runOnQueueThread
            }
            if (!sessionRunning) return@runOnQueueThread

            val xrSessionLocal = xrSession ?: return@runOnQueueThread
            val xrSpaceLocal = xrSpace ?: return@runOnQueueThread

            MemoryStack.stackPush().use { stack ->
                // Wait for frame
                val frameWaitInfo = XrFrameWaitInfo.calloc(stack).type(XR_TYPE_FRAME_WAIT_INFO)
                val frameState = XrFrameState.calloc(stack).type(XR_TYPE_FRAME_STATE)

                var res = XR_SUCCESS
                CPUSpan.time("xr_waitFrame") {
                    res = xrWaitFrame(xrSessionLocal, frameWaitInfo, frameState)
                }
                if (res != XR_SUCCESS) return@use

                // Begin profiling for this frame
                profilerState?.let { ps ->
                    synchronized(ps.lock) {
                        ps.profiler.beginFrame(0) // single-frame index
                        ps.markers = XrFrameMarkers()
                        ps.perFrameEyeMarks.clear()
                    }
                }

                // Begin frame
                val frameBeginInfo = XrFrameBeginInfo.calloc(stack).type(XR_TYPE_FRAME_BEGIN_INFO)
                CPUSpan.time("xr_beginFrame") {
                    res = xrBeginFrame(xrSessionLocal, frameBeginInfo)
                }
                if (res != XR_SUCCESS) return@use

                var layerCount = 0
                val layers = stack.callocPointer(1)

                if (frameState.shouldRender()) {
                    // Locate views
                    val viewLocateInfo = XrViewLocateInfo.calloc(stack)
                        .type(XR_TYPE_VIEW_LOCATE_INFO)
                        .viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                        .displayTime(frameState.predictedDisplayTime())
                        .space(xrSpaceLocal)

                    val viewState = XrViewState.calloc(stack).type(XR_TYPE_VIEW_STATE)
                    val viewCapacityBuf = stack.ints(viewCount)
                    val viewsBuffer = XrView.calloc(viewCount, stack)
                    for (i in 0 until viewCount) {
                        viewsBuffer[i].type(XR_TYPE_VIEW)
                    }

                    CPUSpan.time("xr_locateViews") {
                        res = xrLocateViews(xrSessionLocal, viewLocateInfo, viewState, viewCapacityBuf, viewsBuffer)
                    }
                    if (res == XR_SUCCESS) {
                        updateEyeMatricesFromLocatedViews(viewsBuffer)

                        CPUSpan.time("xr_controllers") {
                            syncAndUpdateControllers(xrSessionLocal, xrSpaceLocal, frameState.predictedDisplayTime())
                        }

                        // Acquire all swapchain images first (CPU), then render, then submit+wait, then release.
                        if (acquiredIndicesScratch.size != swapchains.size) {
                            acquiredIndicesScratch = IntArray(swapchains.size)
                        }
                        val acquiredIndices = acquiredIndicesScratch
                        java.util.Arrays.fill(acquiredIndices, -1)

                        CPUSpan.time("xr_acquire") {
                            for (i in swapchains.indices) {
                                val sc = swapchains[i]

                                val acquireInfo = XrSwapchainImageAcquireInfo.calloc(stack)
                                    .type(XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)
                                val idxBuf = stack.callocInt(1)
                                xrAcquireSwapchainImage(sc.swapchain, acquireInfo, idxBuf)
                                val imageIndex = idxBuf[0]
                                acquiredIndices[i] = imageIndex

                                val waitInfo = XrSwapchainImageWaitInfo.calloc(stack)
                                    .type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                                    .timeout(XR_INFINITE_DURATION)
                                xrWaitSwapchainImage(sc.swapchain, waitInfo)
                            }
                        }

                        // Record + submit Vulkan work that renders into the acquired swapchain images.
                        // NOTE: Depending on Options.openXRWaitForGpuBeforeRelease, we may or may not
                        // hard-wait for GPU completion before releasing.
                        CPUSpan.time("xr_render") {
                            renderEyesVulkan(acquiredIndices)
                        }

                        if (Options.openXRWaitForGpuBeforeRelease) {
                            CPUSpan.time("xr_fence_wait") {
                                fence.fenceWait()
                            }
                        }

                        // Release swapchain images.
                        CPUSpan.time("xr_release") {
                            for (i in swapchains.indices) {
                                val sc = swapchains[i]
                                val releaseInfo = XrSwapchainImageReleaseInfo.calloc(stack)
                                    .type(XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO)
                                xrReleaseSwapchainImage(sc.swapchain, releaseInfo)
                            }
                        }

                        // Build projection views
                        val projectionViews = XrCompositionLayerProjectionView.calloc(swapchains.size, stack)
                        for (i in swapchains.indices) {
                            val sc = swapchains[i]
                            val view = viewsBuffer[i]

                            projectionViews[i]
                                .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                                .pose(view.pose())
                                .fov(view.fov())

                            projectionViews[i].subImage()
                                .swapchain(sc.swapchain)
                                .imageArrayIndex(0)

                            projectionViews[i].subImage().imageRect().offset().x(0).y(0)
                            projectionViews[i].subImage().imageRect().extent()
                                .width(sc.width)
                                .height(sc.height)
                        }

                        val projectionLayer = XrCompositionLayerProjection.calloc(stack)
                            .type(XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                            .space(xrSpaceLocal)
                            .views(projectionViews)

                        layers.put(0, projectionLayer)
                        layerCount = 1
                    }
                }

                // End frame
                val endInfo = XrFrameEndInfo.calloc(stack)
                    .type(XR_TYPE_FRAME_END_INFO)
                    .displayTime(frameState.predictedDisplayTime())
                    .environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layerCount(layerCount)
                    .layers(if (layerCount > 0) layers else null)

                CPUSpan.time("xr_endFrame") {
                    xrEndFrame(xrSessionLocal, endInfo)
                }

                // Add to pending for profiling readback
                profilerState?.pending?.addLast(Pending(xrFrameCounter))
            }

            // Print profiling stats periodically
            CPUSpan.snapshotAndMaybePrint(everyNFrames = 120, frame = xrFrameCounter, header = "XR CPU")

            xrFrameCounter++
        }
    }

    private fun initSessionAndSwapchains() {
        val xrInstanceLocal = xrInstance ?: return

        MemoryStack.stackPush().use { stack ->
            // Query view configuration
            val viewCountBuf = stack.callocInt(1)
            xrEnumerateViewConfigurationViews(
                xrInstanceLocal,
                xrSystemId,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                viewCountBuf,
                null
            )
            viewCount = viewCountBuf[0]

            eyeStates = Array(viewCount) { EyeViewState() }
            eyeViewMatrices = Array(viewCount) { Matrix4f() }
            eyeProjMatrices = Array(viewCount) { Matrix4f() }

            viewConfigs = XrViewConfigurationView.calloc(viewCount)
            for (i in 0 until viewCount) {
                viewConfigs!![i].type(XR_TYPE_VIEW_CONFIGURATION_VIEW)
            }
            xrEnumerateViewConfigurationViews(
                xrInstanceLocal,
                xrSystemId,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                viewCountBuf,
                viewConfigs
            )

            // Create XR session
            val graphicsBinding = XrGraphicsBindingVulkan2KHR.calloc(stack)
                // LWJGL quirk: the type constant for the binding struct is XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR
                .type(KHRVulkanEnable.XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR)
                .instance(renderer.instance.vkInstance)
                .physicalDevice(renderer.physicalDevice.vkPhysicalDevice)
                .device(renderer.device.vkDevice)
                .queueFamilyIndex(renderer.graphQueue.queueFamilyIndex)
                .queueIndex(0)

            val sessionCreateInfo = XrSessionCreateInfo.calloc(stack)
                .type(XR_TYPE_SESSION_CREATE_INFO)
                .next(graphicsBinding.address())
                .systemId(xrSystemId)

            val sessionPtr = stack.callocPointer(1)
            val res = xrCreateSession(xrInstanceLocal, sessionCreateInfo, sessionPtr)
            check(res == XR_SUCCESS) { "xrCreateSession failed: $res" }
            val session = XrSession(sessionPtr[0], xrInstanceLocal)
            xrSession = session

            // Reference space
            val spaceCreateInfo = XrReferenceSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
                .referenceSpaceType(XR_REFERENCE_SPACE_TYPE_LOCAL)
            spaceCreateInfo.poseInReferenceSpace().orientation().w(1f)

            val spacePtr = stack.callocPointer(1)
            val spRes = xrCreateReferenceSpace(session, spaceCreateInfo, spacePtr)
            check(spRes == XR_SUCCESS) { "xrCreateReferenceSpace failed: $spRes" }
            xrSpace = XrSpace(spacePtr[0], session)

            initControllerActions(session)

            // Swapchain format selection (prefer SRGB)
            val formatCountBuf = stack.callocInt(1)
            xrEnumerateSwapchainFormats(session, formatCountBuf, null)
            val formatCount = formatCountBuf[0]
            check(formatCount > 0) { "No OpenXR swapchain formats" }

            val formats = stack.callocLong(formatCount)
            xrEnumerateSwapchainFormats(session, formatCountBuf, formats)
            val formatList = IntArray(formatCount) { formats[it].toInt() }

            val preferred = intArrayOf(
                VK_FORMAT_B8G8R8A8_SRGB,
                VK_FORMAT_R8G8B8A8_SRGB,
                VK_FORMAT_B8G8R8A8_UNORM,
                VK_FORMAT_R8G8B8A8_UNORM
            )
            val selectedFormat = preferred.firstOrNull { pf -> formatList.any { it == pf } } ?: formatList[0]

            // Create per-eye swapchains
            swapchains.clear()
            for (i in 0 until viewCount) {
                val cfg = viewConfigs!![i]
                val w = cfg.recommendedImageRectWidth()
                val h = cfg.recommendedImageRectHeight()
                val samples = cfg.recommendedSwapchainSampleCount().coerceAtLeast(1)

                val sci = XrSwapchainCreateInfo.calloc(stack)
                    .type(XR_TYPE_SWAPCHAIN_CREATE_INFO)
                    .usageFlags((XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT or XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT).toLong())
                    .format(selectedFormat.toLong())
                    .sampleCount(samples)
                    .width(w)
                    .height(h)
                    .faceCount(1)
                    .arraySize(1)
                    .mipCount(1)

                val pSwapchain = stack.callocPointer(1)
                val scRes = xrCreateSwapchain(session, sci, pSwapchain)
                check(scRes == XR_SUCCESS) { "xrCreateSwapchain failed: $scRes" }

                val swapchain = XrSwapchain(pSwapchain[0], session)

                // Enumerate images
                val imageCountBuf = stack.callocInt(1)
                xrEnumerateSwapchainImages(swapchain, imageCountBuf, null)
                val imageCount = imageCountBuf[0]

                val swapchainImages = XrSwapchainImageVulkan2KHR.calloc(imageCount, stack)
                for (j in 0 until imageCount) {
                    swapchainImages[j].type(KHRVulkanEnable2.XR_TYPE_SWAPCHAIN_IMAGE_VULKAN2_KHR)
                }
                xrEnumerateSwapchainImages(
                    swapchain,
                    imageCountBuf,
                    XrSwapchainImageBaseHeader.create(swapchainImages)
                )

                val images = LongArray(imageCount) { swapchainImages[it].image() }

                // Create VkImageViews and depth attachments for each swapchain image.
                val imageViews = LongArray(imageCount)
                val depth = Array(imageCount) {
                    Attachment(
                        renderer.device,
                        w,
                        h,
                        Options.defaultDepthFormat,
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    )
                }

                for (j in 0 until imageCount) {
                    imageViews[j] = createImageView(images[j], selectedFormat)
                }

                swapchains.add(
                    XrEyeSwapchain(
                        swapchain = swapchain,
                        width = w,
                        height = h,
                        format = selectedFormat,
                        images = images,
                        imageViews = imageViews,
                        depthAttachments = depth
                    )
                )
            }

            // Initialize profiling if enabled
            if (Options.profiling) {
                profilerState = createProfilerState()
            }
        }
    }

    private fun createProfilerState(): XrProfilerState {
        val opts = ProfilingOptions(
            enabled = true,
            timestamps = true,
            pipelineStats = Options.isWindows,
            perfCounters = Options.isWindows
        )
        // We use a single frame for OpenXR profiling since we don't have per-swapchain-image tracking
        val profiler = GpuProfiler(
            device = renderer.device.vkDevice,
            physicalDevice = renderer.device.physicalDevice.vkPhysicalDevice,
            queueFamilyIndex = renderer.graphQueue.queueFamilyIndex,
            framesInFlight = 1,
            opts = opts
        )
        return XrProfilerState(profiler)
    }

    private fun updateEyeMatricesFromLocatedViews(views: XrView.Buffer) {
        val nearF = Options.zNear.toFloat()
        val farF = Options.zFar.toFloat()

        for (i in 0 until viewCount.coerceAtMost(views.capacity())) {
            val v = views[i]

            val pose = v.pose()
            val pos = pose.`position$`()
            val ori = pose.orientation()
            val fov = v.fov()

            val s = eyeStates[i]
            s.px = pos.x(); s.py = pos.y(); s.pz = pos.z()
            s.qx = ori.x(); s.qy = ori.y(); s.qz = ori.z(); s.qw = ori.w()
            s.angleLeft = fov.angleLeft()
            s.angleRight = fov.angleRight()
            s.angleUp = fov.angleUp()
            s.angleDown = fov.angleDown()

            computeViewMatrix(s, eyeViewMatrices[i])
            computeProjectionMatrix(s, nearF, farF, eyeProjMatrices[i])
        }
    }

    private fun computeViewMatrix(state: EyeViewState, out: Matrix4f) {
        // OpenXR view pose is a rigid transform in the chosen reference space.
        // View matrix is the inverse of that pose (world->view).
        val q = Quaternionf(state.qx, state.qy, state.qz, state.qw)
        val p = Vector3f(state.px, state.py, state.pz)
        out.identity()
            .translate(p)
            .rotate(q)
            .invert()
    }

    private fun computeProjectionMatrix(state: EyeViewState, zNear: Float, zFar: Float, out: Matrix4f) {
        // Asymmetric frustum from OpenXR fov angles.
        val left = tan(state.angleLeft.toDouble()).toFloat() * zNear
        val right = tan(state.angleRight.toDouble()).toFloat() * zNear
        val bottom = tan(state.angleDown.toDouble()).toFloat() * zNear
        val top = tan(state.angleUp.toDouble()).toFloat() * zNear

        out.identity().frustum(left, right, bottom, top, zNear, zFar, true)
    }

    private fun initControllerActions(session: XrSession) {
        val xrInstanceLocal = xrInstance ?: return

        MemoryStack.stackPush().use { stack ->
            // Paths for hands + profile
            leftHandPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left")
            rightHandPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right")
            val profileSimpleController = stringToPath(xrInstanceLocal, stack, "/interaction_profiles/khr/simple_controller")
            val profileOculusTouch = stringToPath(xrInstanceLocal, stack, "/interaction_profiles/oculus/touch_controller")
            val profileValveIndex = stringToPath(xrInstanceLocal, stack, "/interaction_profiles/valve/index_controller")
            val profileHTCVive = stringToPath(xrInstanceLocal, stack, "/interaction_profiles/htc/vive_controller")
            val profileMicrosoftMotion = stringToPath(xrInstanceLocal, stack, "/interaction_profiles/microsoft/motion_controller")

            // Action set
            val actionSetInfo = XrActionSetCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_SET_CREATE_INFO)
                .actionSetName(stack.UTF8("gameplay"))
                .localizedActionSetName(stack.UTF8("Gameplay"))
                .priority(0)

            val pActionSet = stack.callocPointer(1)
            val asRes = xrCreateActionSet(xrInstanceLocal, actionSetInfo, pActionSet)
            check(asRes == XR_SUCCESS) { "xrCreateActionSet failed: $asRes" }
            val actionSet = XrActionSet(pActionSet[0], xrInstanceLocal)
            xrActionSet = actionSet

            val subactionPaths = stack.longs(leftHandPath, rightHandPath)

            // Pose action (grip pose)
            val poseInfo = XrActionCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_CREATE_INFO)
                .actionType(XR_ACTION_TYPE_POSE_INPUT)
                .actionName(stack.UTF8("grip_pose"))
                .localizedActionName(stack.UTF8("Grip Pose"))
                .countSubactionPaths(subactionPaths.remaining())
                .subactionPaths(subactionPaths)

            val pPoseAction = stack.callocPointer(1)
            val paRes = xrCreateAction(actionSet, poseInfo, pPoseAction)
            check(paRes == XR_SUCCESS) { "xrCreateAction(pose) failed: $paRes" }
            val poseAction = XrAction(pPoseAction[0], actionSet)
            xrPoseAction = poseAction

            // Pose action (aim pose) - many controllers report aim pose more reliably than grip.
            val aimInfo = XrActionCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_CREATE_INFO)
                .actionType(XR_ACTION_TYPE_POSE_INPUT)
                .actionName(stack.UTF8("aim_pose"))
                .localizedActionName(stack.UTF8("Aim Pose"))
                .countSubactionPaths(subactionPaths.remaining())
                .subactionPaths(subactionPaths)

            val pAimAction = stack.callocPointer(1)
            val aaRes = xrCreateAction(actionSet, aimInfo, pAimAction)
            check(aaRes == XR_SUCCESS) { "xrCreateAction(aim) failed: $aaRes" }
            val aimAction = XrAction(pAimAction[0], actionSet)
            xrAimPoseAction = aimAction

            // Select (boolean)
            val selectInfo = XrActionCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_CREATE_INFO)
                .actionType(XR_ACTION_TYPE_BOOLEAN_INPUT)
                .actionName(stack.UTF8("select_click"))
                .localizedActionName(stack.UTF8("Select"))
                .countSubactionPaths(subactionPaths.remaining())
                .subactionPaths(subactionPaths)

            val pSelectAction = stack.callocPointer(1)
            val saRes = xrCreateAction(actionSet, selectInfo, pSelectAction)
            check(saRes == XR_SUCCESS) { "xrCreateAction(select) failed: $saRes" }
            val selectAction = XrAction(pSelectAction[0], actionSet)
            xrSelectAction = selectAction

            fun createAction(
                type: Int,
                name: String,
                localized: String,
            ): XrAction {
                val subPaths = subactionPaths.duplicate()
                subPaths.position(0)

                val info = XrActionCreateInfo.calloc(stack)
                    .type(XR_TYPE_ACTION_CREATE_INFO)
                    .actionType(type)
                    .actionName(stack.UTF8(name))
                    .localizedActionName(stack.UTF8(localized))
                    .countSubactionPaths(subPaths.remaining())
                    .subactionPaths(subPaths)

                val p = stack.callocPointer(1)
                val r = xrCreateAction(actionSet, info, p)
                check(r == XR_SUCCESS) { "xrCreateAction($name) failed: $r" }
                return XrAction(p[0], actionSet)
            }

            // Full input set (Valve Index + HTC Vive)
            val triggerValueAction = createAction(XR_ACTION_TYPE_FLOAT_INPUT, "trigger_value", "Trigger Value")
            val triggerClickAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "trigger_click", "Trigger Click")
            val squeezeValueAction = createAction(XR_ACTION_TYPE_FLOAT_INPUT, "squeeze_value", "Squeeze Value")
            val squeezeClickAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "squeeze_click", "Squeeze Click")

            val trackpadAction = createAction(XR_ACTION_TYPE_VECTOR2F_INPUT, "trackpad", "Trackpad")
            val trackpadTouchAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "trackpad_touch", "Trackpad Touch")
            val trackpadClickAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "trackpad_click", "Trackpad Click")

            val thumbstickAction = createAction(XR_ACTION_TYPE_VECTOR2F_INPUT, "thumbstick", "Thumbstick")
            val thumbstickTouchAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "thumbstick_touch", "Thumbstick Touch")
            val thumbstickClickAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "thumbstick_click", "Thumbstick Click")

            val aAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "a_click", "A")
            val bAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "b_click", "B")
            val menuAction = createAction(XR_ACTION_TYPE_BOOLEAN_INPUT, "menu_click", "Menu")

            val hapticAction = createAction(XR_ACTION_TYPE_VIBRATION_OUTPUT, "haptic", "Haptic")

            xrTriggerValueAction = triggerValueAction
            xrTriggerClickAction = triggerClickAction
            xrSqueezeValueAction = squeezeValueAction
            xrSqueezeClickAction = squeezeClickAction
            xrTrackpadAction = trackpadAction
            xrTrackpadTouchAction = trackpadTouchAction
            xrTrackpadClickAction = trackpadClickAction
            xrThumbstickAction = thumbstickAction
            xrThumbstickTouchAction = thumbstickTouchAction
            xrThumbstickClickAction = thumbstickClickAction
            xrAAction = aAction
            xrBAction = bAction
            xrMenuAction = menuAction
            xrHapticAction = hapticAction

            // Suggested bindings.
            // NOTE: runtimes may reject the entire suggestion if any binding is invalid for the profile.
            // We therefore:
            // - suggest Index and Vive separately
            // - retry with reduced sets if needed

            val leftGripPosePath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/grip/pose")
            val rightGripPosePath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/grip/pose")
            val leftAimPosePath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/aim/pose")
            val rightAimPosePath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/aim/pose")
            val leftHapticPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/output/haptic")
            val rightHapticPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/output/haptic")

            fun suggestBindings(profileName: String, profile: Long, pairs: List<Pair<XrAction, Long>>): Int {
                val bindings = XrActionSuggestedBinding.calloc(pairs.size, stack)
                for (i in pairs.indices) {
                    bindings[i].action(pairs[i].first).binding(pairs[i].second)
                }

                val suggested = XrInteractionProfileSuggestedBinding.calloc(stack)
                    .type(XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING)
                    .interactionProfile(profile)
                    .suggestedBindings(bindings)

                val r = xrSuggestInteractionProfileBindings(xrInstanceLocal, suggested)
                if (Options.openXRDebugInput && r != XR_SUCCESS) {
                    System.err.println("OpenXR: xrSuggestInteractionProfileBindings failed for $profileName : $r")
                }
                return r
            }

            fun suggestWithFallback(profileName: String, profile: Long, attempts: List<List<Pair<XrAction, Long>>>) {
                for (i in attempts.indices) {
                    val r = suggestBindings(profileName, profile, attempts[i])
                    if (r == XR_SUCCESS) {
                        if (Options.openXRDebugInput && i > 0) {
                            System.err.println("OpenXR: $profileName bindings succeeded after fallback #$i")
                        }
                        return
                    }
                }
            }

            // --- Valve Index controller profile ---
            // Common paths for Index:
            // - trigger: /input/trigger/value and /input/trigger/click
            // - squeeze: /input/squeeze/value (analog)
            // - thumbstick: /input/thumbstick (vector2) + /click + /touch
            // - trackpad: /input/trackpad (vector2) + /click + /touch
            // - buttons: /input/a/click, /input/b/click
            val leftTriggerValuePath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/trigger/value")
            val rightTriggerValuePath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/trigger/value")
            val leftTriggerClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/trigger/click")
            val rightTriggerClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/trigger/click")
            val leftSqueezeValuePath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/squeeze/value")
            val rightSqueezeValuePath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/squeeze/value")

            val leftTrackpadPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/trackpad")
            val rightTrackpadPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/trackpad")
            val leftTrackpadClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/trackpad/click")
            val rightTrackpadClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/trackpad/click")
            val leftTrackpadTouchPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/trackpad/touch")
            val rightTrackpadTouchPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/trackpad/touch")

            val leftThumbstickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/thumbstick")
            val rightThumbstickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/thumbstick")
            val leftThumbstickClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/thumbstick/click")
            val rightThumbstickClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/thumbstick/click")
            val leftThumbstickTouchPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/thumbstick/touch")
            val rightThumbstickTouchPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/thumbstick/touch")

            val rightAPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/a/click")
            val rightBPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/b/click")

            val indexFull = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                selectAction to leftTriggerClickPath,
                selectAction to rightTriggerClickPath,
                triggerValueAction to leftTriggerValuePath,
                triggerValueAction to rightTriggerValuePath,
                triggerClickAction to leftTriggerClickPath,
                triggerClickAction to rightTriggerClickPath,
                squeezeValueAction to leftSqueezeValuePath,
                squeezeValueAction to rightSqueezeValuePath,
                trackpadAction to leftTrackpadPath,
                trackpadAction to rightTrackpadPath,
                trackpadClickAction to leftTrackpadClickPath,
                trackpadClickAction to rightTrackpadClickPath,
                trackpadTouchAction to leftTrackpadTouchPath,
                trackpadTouchAction to rightTrackpadTouchPath,
                thumbstickAction to leftThumbstickPath,
                thumbstickAction to rightThumbstickPath,
                thumbstickClickAction to leftThumbstickClickPath,
                thumbstickClickAction to rightThumbstickClickPath,
                thumbstickTouchAction to leftThumbstickTouchPath,
                thumbstickTouchAction to rightThumbstickTouchPath,
                aAction to rightAPath,
                bAction to rightBPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            val indexReduced = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                triggerValueAction to leftTriggerValuePath,
                triggerValueAction to rightTriggerValuePath,
                triggerClickAction to leftTriggerClickPath,
                triggerClickAction to rightTriggerClickPath,
                squeezeValueAction to leftSqueezeValuePath,
                squeezeValueAction to rightSqueezeValuePath,
                thumbstickAction to leftThumbstickPath,
                thumbstickAction to rightThumbstickPath,
                thumbstickClickAction to leftThumbstickClickPath,
                thumbstickClickAction to rightThumbstickClickPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            val indexMinimum = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                triggerClickAction to leftTriggerClickPath,
                triggerClickAction to rightTriggerClickPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            suggestWithFallback(
                "valve/index_controller",
                profileValveIndex,
                listOf(indexFull, indexReduced, indexMinimum)
            )

            // --- HTC Vive controller profile ---
            // Common paths for Vive:
            // - trigger: /input/trigger/value and /input/trigger/click
            // - grip button: /input/squeeze/click (boolean)
            // - trackpad: /input/trackpad (vector2) + /click + /touch
            // - menu: /input/menu/click
            val leftViveTriggerValuePath = leftTriggerValuePath
            val rightViveTriggerValuePath = rightTriggerValuePath
            val leftViveTriggerClickPath = leftTriggerClickPath
            val rightViveTriggerClickPath = rightTriggerClickPath
            val leftViveSqueezeClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/squeeze/click")
            val rightViveSqueezeClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/squeeze/click")
            val leftViveMenuClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/left/input/menu/click")
            val rightViveMenuClickPath = stringToPath(xrInstanceLocal, stack, "/user/hand/right/input/menu/click")

            val viveFull = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                selectAction to leftViveTriggerClickPath,
                selectAction to rightViveTriggerClickPath,
                triggerValueAction to leftViveTriggerValuePath,
                triggerValueAction to rightViveTriggerValuePath,
                triggerClickAction to leftViveTriggerClickPath,
                triggerClickAction to rightViveTriggerClickPath,
                squeezeClickAction to leftViveSqueezeClickPath,
                squeezeClickAction to rightViveSqueezeClickPath,
                trackpadAction to leftTrackpadPath,
                trackpadAction to rightTrackpadPath,
                trackpadClickAction to leftTrackpadClickPath,
                trackpadClickAction to rightTrackpadClickPath,
                trackpadTouchAction to leftTrackpadTouchPath,
                trackpadTouchAction to rightTrackpadTouchPath,
                menuAction to leftViveMenuClickPath,
                menuAction to rightViveMenuClickPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            val viveReduced = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                triggerValueAction to leftViveTriggerValuePath,
                triggerValueAction to rightViveTriggerValuePath,
                triggerClickAction to leftViveTriggerClickPath,
                triggerClickAction to rightViveTriggerClickPath,
                trackpadAction to leftTrackpadPath,
                trackpadAction to rightTrackpadPath,
                trackpadClickAction to leftTrackpadClickPath,
                trackpadClickAction to rightTrackpadClickPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            val viveMinimum = listOf(
                poseAction to leftGripPosePath,
                poseAction to rightGripPosePath,
                aimAction to leftAimPosePath,
                aimAction to rightAimPosePath,
                triggerClickAction to leftViveTriggerClickPath,
                triggerClickAction to rightViveTriggerClickPath,
                hapticAction to leftHapticPath,
                hapticAction to rightHapticPath,
            )

            suggestWithFallback(
                "htc/vive_controller",
                profileHTCVive,
                listOf(viveFull, viveReduced, viveMinimum)
            )

            // Attach action set to the session
            val actionSetsBuf = stack.callocPointer(1)
            actionSetsBuf.put(0, actionSet.address())
            val attachInfo = XrSessionActionSetsAttachInfo.calloc(stack)
                .type(XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO)
                .actionSets(actionSetsBuf)

            val atRes = xrAttachSessionActionSets(session, attachInfo)
            check(atRes == XR_SUCCESS) { "xrAttachSessionActionSets failed: $atRes" }

            // Create action spaces for grip pose (left/right)
            val identityPose = XrPosef.calloc(stack)
            identityPose.orientation().w(1f)

            val leftSpaceInfo = XrActionSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
                .action(poseAction)
                .subactionPath(leftHandPath)
            leftSpaceInfo.poseInActionSpace(identityPose)

            val rightSpaceInfo = XrActionSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
                .action(poseAction)
                .subactionPath(rightHandPath)
            rightSpaceInfo.poseInActionSpace(identityPose)

            val pLeftSpace = stack.callocPointer(1)
            val lsRes = xrCreateActionSpace(session, leftSpaceInfo, pLeftSpace)
            check(lsRes == XR_SUCCESS) { "xrCreateActionSpace(left) failed: $lsRes" }
            leftGripSpace = XrSpace(pLeftSpace[0], session)

            val pRightSpace = stack.callocPointer(1)
            val rsRes = xrCreateActionSpace(session, rightSpaceInfo, pRightSpace)
            check(rsRes == XR_SUCCESS) { "xrCreateActionSpace(right) failed: $rsRes" }
            rightGripSpace = XrSpace(pRightSpace[0], session)

            // Create action spaces for aim pose (left/right)
            val leftAimInfo = XrActionSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
                .action(aimAction)
                .subactionPath(leftHandPath)
            leftAimInfo.poseInActionSpace(identityPose)

            val rightAimInfo = XrActionSpaceCreateInfo.calloc(stack)
                .type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
                .action(aimAction)
                .subactionPath(rightHandPath)
            rightAimInfo.poseInActionSpace(identityPose)

            val pLeftAim = stack.callocPointer(1)
            val laRes = xrCreateActionSpace(session, leftAimInfo, pLeftAim)
            check(laRes == XR_SUCCESS) { "xrCreateActionSpace(leftAim) failed: $laRes" }
            leftAimSpace = XrSpace(pLeftAim[0], session)

            val pRightAim = stack.callocPointer(1)
            val raRes = xrCreateActionSpace(session, rightAimInfo, pRightAim)
            check(raRes == XR_SUCCESS) { "xrCreateActionSpace(rightAim) failed: $raRes" }
            rightAimSpace = XrSpace(pRightAim[0], session)
        }
    }

    private fun stringToPath(xrInstanceLocal: XrInstance, stack: MemoryStack, str: String): Long {
        val out = stack.callocLong(1)
        val res = xrStringToPath(xrInstanceLocal, str, out)
        check(res == XR_SUCCESS) { "xrStringToPath('$str') failed: $res" }
        return out[0]
    }

    private fun syncAndUpdateControllers(session: XrSession, referenceSpace: XrSpace, predictedDisplayTime: Long) {
        val actionSet = xrActionSet ?: return
        val gripAction = xrPoseAction ?: return
        val aimAction = xrAimPoseAction

        MemoryStack.stackPush().use { stack ->
            val activeSets = XrActiveActionSet.calloc(1, stack)
            activeSets[0].actionSet(actionSet).subactionPath(XR_NULL_PATH)

            val syncInfo = XrActionsSyncInfo.calloc(stack)
                .type(XR_TYPE_ACTIONS_SYNC_INFO)
                .activeActionSets(activeSets)

            xrSyncActions(session, syncInfo)

            updateControllerForHand(
                session,
                referenceSpace,
                predictedDisplayTime,
                gripAction,
                aimAction,
                leftHandPath,
                leftGripSpace,
                leftAimSpace,
                leftController
            )
            updateControllerForHand(
                session,
                referenceSpace,
                predictedDisplayTime,
                gripAction,
                aimAction,
                rightHandPath,
                rightGripSpace,
                rightAimSpace,
                rightController
            )

            if (Options.openXRDebugInput && !inputDebugPrinted) {
                if (!leftController.active && !rightController.active) {
                    printInputDiagnostics(session)
                    inputDebugPrinted = true
                }
            }
        }
    }

    private fun printInputDiagnostics(session: XrSession) {
        val xrInstanceLocal = xrInstance ?: return
        val actionSet = xrActionSet ?: return
        val gripAction = xrPoseAction
        val aimAction = xrAimPoseAction

        MemoryStack.stackPush().use { stack ->
            fun pathToString(path: Long): String {
                if (path == XR_NULL_PATH) return "<null>"
                val len = stack.callocInt(1)
                // First call to get required size
                xrPathToString(xrInstanceLocal, path, len, null)
                val buf = stack.malloc(len[0])
                xrPathToString(xrInstanceLocal, path, len, buf)
                return MemoryUtil.memUTF8(buf, len[0] - 1)
            }

            fun printProfile(userPathString: String) {
                val userPath = stringToPath(xrInstanceLocal, stack, userPathString)
                val info = XrInteractionProfileState.calloc(stack).type(XR_TYPE_INTERACTION_PROFILE_STATE)
                val r = xrGetCurrentInteractionProfile(session, userPath, info)
                if (r == XR_SUCCESS) {
                    System.err.println("OpenXR input: $userPathString profile=${pathToString(info.interactionProfile())}")
                } else {
                    System.err.println("OpenXR input: xrGetCurrentInteractionProfile failed for $userPathString : $r")
                }
            }

            fun printBoundSources(action: XrAction?, label: String) {
                if (action == null) {
                    System.err.println("OpenXR input: action $label is null")
                    return
                }

                val getInfo = XrBoundSourcesForActionEnumerateInfo.calloc(stack)
                    .type(XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO)
                    .action(action)

                val count = stack.callocInt(1)
                val r0 = xrEnumerateBoundSourcesForAction(session, getInfo, count, null)
                if (r0 != XR_SUCCESS) {
                    System.err.println("OpenXR input: xrEnumerateBoundSourcesForAction(count) failed for $label : $r0")
                    return
                }

                val paths = stack.callocLong(count[0])
                val r1 = xrEnumerateBoundSourcesForAction(session, getInfo, count, paths)
                if (r1 != XR_SUCCESS) {
                    System.err.println("OpenXR input: xrEnumerateBoundSourcesForAction(paths) failed for $label : $r1")
                    return
                }

                val sources = (0 until count[0]).joinToString { i -> pathToString(paths[i]) }
                System.err.println("OpenXR input: bound sources for $label: [$sources]")
            }

            System.err.println("OpenXR input: actionSet=${actionSet.address()}")
            printProfile("/user/hand/left")
            printProfile("/user/hand/right")
            printBoundSources(gripAction, "grip_pose")
            printBoundSources(aimAction, "aim_pose")
            printBoundSources(xrSelectAction, "select")
            printBoundSources(xrTriggerValueAction, "trigger_value")
            printBoundSources(xrTriggerClickAction, "trigger_click")
            printBoundSources(xrSqueezeValueAction, "squeeze_value")
            printBoundSources(xrSqueezeClickAction, "squeeze_click")
            printBoundSources(xrTrackpadAction, "trackpad")
            printBoundSources(xrTrackpadTouchAction, "trackpad_touch")
            printBoundSources(xrTrackpadClickAction, "trackpad_click")
            printBoundSources(xrThumbstickAction, "thumbstick")
            printBoundSources(xrThumbstickTouchAction, "thumbstick_touch")
            printBoundSources(xrThumbstickClickAction, "thumbstick_click")
            printBoundSources(xrAAction, "a")
            printBoundSources(xrBAction, "b")
            printBoundSources(xrMenuAction, "menu")
            printBoundSources(xrHapticAction, "haptic")
        }
    }

    private fun updateControllerForHand(
        session: XrSession,
        referenceSpace: XrSpace,
        predictedDisplayTime: Long,
        gripPoseAction: XrAction,
        aimPoseAction: XrAction?,
        subactionPath: Long,
        gripSpace: XrSpace?,
        aimSpace: XrSpace?,
        out: ControllerState
    ) {
        MemoryStack.stackPush().use { stack ->
            // Grip pose active?
            val gripGetInfo = XrActionStateGetInfo.calloc(stack)
                .type(XR_TYPE_ACTION_STATE_GET_INFO)
                .action(gripPoseAction)
                .subactionPath(subactionPath)
            val gripState = XrActionStatePose.calloc(stack).type(XR_TYPE_ACTION_STATE_POSE)
            xrGetActionStatePose(session, gripGetInfo, gripState)

            // Aim pose active?
            var aimActive = false
            if (aimPoseAction != null) {
                val aimGetInfo = XrActionStateGetInfo.calloc(stack)
                    .type(XR_TYPE_ACTION_STATE_GET_INFO)
                    .action(aimPoseAction)
                    .subactionPath(subactionPath)
                val aimState = XrActionStatePose.calloc(stack).type(XR_TYPE_ACTION_STATE_POSE)
                xrGetActionStatePose(session, aimGetInfo, aimState)
                aimActive = aimState.isActive()
            }

            val gripActive = gripState.isActive()

            var anyActive = gripActive || aimActive

            // Generic select (usually trigger/click)
            run {
                val action = xrSelectAction
                if (action != null) {
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    anyActive = anyActive || st.isActive()
                    out.selectPressed = st.currentState()
                } else {
                    out.selectPressed = false
                }
            }

            // Trigger
            run {
                var valueActive = false
                var value = 0f
                xrTriggerValueAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateFloat.calloc(stack).type(XR_TYPE_ACTION_STATE_FLOAT)
                    xrGetActionStateFloat(session, gi, st)
                    valueActive = st.isActive()
                    value = st.currentState()
                }

                var clickActive = false
                var pressed = false
                xrTriggerClickAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    clickActive = st.isActive()
                    pressed = st.currentState()
                }

                anyActive = anyActive || valueActive || clickActive
                out.triggerValue = if (valueActive) value else 0f
                out.triggerPressed = pressed
            }

            // Squeeze / grip
            run {
                var valueActive = false
                var value = 0f
                xrSqueezeValueAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateFloat.calloc(stack).type(XR_TYPE_ACTION_STATE_FLOAT)
                    xrGetActionStateFloat(session, gi, st)
                    valueActive = st.isActive()
                    value = st.currentState()
                }

                var clickActive = false
                var pressed = false
                xrSqueezeClickAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    clickActive = st.isActive()
                    pressed = st.currentState()
                }

                anyActive = anyActive || valueActive || clickActive
                out.squeezeValue = if (valueActive) value else 0f
                out.squeezePressed = pressed || (valueActive && value > 0.5f)
            }

            // Trackpad
            run {
                var vecActive = false
                var x = 0f
                var y = 0f
                xrTrackpadAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateVector2f.calloc(stack).type(XR_TYPE_ACTION_STATE_VECTOR2F)
                    xrGetActionStateVector2f(session, gi, st)
                    vecActive = st.isActive()
                    val v = st.currentState()
                    x = v.x(); y = v.y()
                }

                var touchActive = false
                var touched = false
                xrTrackpadTouchAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    touchActive = st.isActive()
                    touched = st.currentState()
                }

                var clickActive = false
                var pressed = false
                xrTrackpadClickAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    clickActive = st.isActive()
                    pressed = st.currentState()
                }

                anyActive = anyActive || vecActive || touchActive || clickActive
                out.trackpadX = if (vecActive) x else 0f
                out.trackpadY = if (vecActive) y else 0f
                out.trackpadTouched = touched
                out.trackpadPressed = pressed
            }

            // Thumbstick
            run {
                var vecActive = false
                var x = 0f
                var y = 0f
                xrThumbstickAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateVector2f.calloc(stack).type(XR_TYPE_ACTION_STATE_VECTOR2F)
                    xrGetActionStateVector2f(session, gi, st)
                    vecActive = st.isActive()
                    val v = st.currentState()
                    x = v.x(); y = v.y()
                }

                var touchActive = false
                var touched = false
                xrThumbstickTouchAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    touchActive = st.isActive()
                    touched = st.currentState()
                }

                var clickActive = false
                var pressed = false
                xrThumbstickClickAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    clickActive = st.isActive()
                    pressed = st.currentState()
                }

                anyActive = anyActive || vecActive || touchActive || clickActive
                out.thumbstickX = if (vecActive) x else 0f
                out.thumbstickY = if (vecActive) y else 0f
                out.thumbstickTouched = touched
                out.thumbstickPressed = pressed
            }

            // Buttons
            run {
                var aActive = false
                var aPressed = false
                xrAAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    aActive = st.isActive()
                    aPressed = st.currentState()
                }

                var bActive = false
                var bPressed = false
                xrBAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    bActive = st.isActive()
                    bPressed = st.currentState()
                }

                var menuActive = false
                var menuPressed = false
                xrMenuAction?.let { action ->
                    val gi = XrActionStateGetInfo.calloc(stack)
                        .type(XR_TYPE_ACTION_STATE_GET_INFO)
                        .action(action)
                        .subactionPath(subactionPath)
                    val st = XrActionStateBoolean.calloc(stack).type(XR_TYPE_ACTION_STATE_BOOLEAN)
                    xrGetActionStateBoolean(session, gi, st)
                    menuActive = st.isActive()
                    menuPressed = st.currentState()
                }

                anyActive = anyActive || aActive || bActive || menuActive
                out.aPressed = aPressed
                out.bPressed = bPressed
                out.menuPressed = menuPressed
            }

            out.active = anyActive

            // Choose the best pose space to locate.
            val chosenSpace = if (aimActive && aimSpace != null) aimSpace else gripSpace
            if (!out.active || chosenSpace == null) {
                out.poseValid = false
                return@use
            }

            val loc = XrSpaceLocation.calloc(stack).type(XR_TYPE_SPACE_LOCATION)
            val res = xrLocateSpace(chosenSpace, referenceSpace, predictedDisplayTime, loc)
            if (res != XR_SUCCESS) {
                out.poseValid = false
                return@use
            }

            val flags = loc.locationFlags()
            val posValid = (flags and XR_SPACE_LOCATION_POSITION_VALID_BIT.toLong()) != 0L
            val oriValid = (flags and XR_SPACE_LOCATION_ORIENTATION_VALID_BIT.toLong()) != 0L
            out.poseValid = posValid && oriValid
            if (!out.poseValid) return@use

            val p = loc.pose().`position$`()
            val q = loc.pose().orientation()
            out.position.x = p.x()
            out.position.y = p.y()
            out.position.z = p.z()

            out.rotation.x = q.x()
            out.rotation.y = q.y()
            out.rotation.z = q.z()
            out.rotation.w = q.w()
        }
    }

    private fun createImageView(image: Long, format: Int): Long {
        MemoryStack.stackPush().use { stack ->
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

            val pView = stack.callocLong(1)
            val res = vkCreateImageView(renderer.device.vkDevice, viewInfo, null, pView)
            check(res == VK_SUCCESS) { "vkCreateImageView failed: $res" }
            return pView[0]
        }
    }

    private fun renderEyesVulkan(acquiredIndices: IntArray) {
        // Ensure previous submission is complete before reusing the command buffer/fence.
        // (If we didn't do a hard wait before release, this is where we naturally apply backpressure.)
        if (!fence.isSignaled()) {
            fence.fenceWait()
        }
        fence.reset()

        cmd.reset()
        cmd.beginRecording()

        // Begin GPU profiling for this frame
        val ps = profilerState
        var eyesScope: GpuProfiler.PipeStatScope? = null
        var perfScope: GpuProfiler.PerfScope? = null
        ps?.let { p ->
            synchronized(p.lock) {
                p.markers.tsFrameBegin = p.profiler.writeTimestamp2(
                    cmd.vkCommandBuffer, 0, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                )
                eyesScope = p.profiler.beginPipelineStats(cmd.vkCommandBuffer, 0)
                eyesScope?.let { p.markers.pipeStatsEyes = it.index }
                perfScope = p.profiler.beginPerfCounters(cmd.vkCommandBuffer, 0)
            }
        }

        val sbs = sideBySideSource
        if (sbs == null) {
            // Upload dirty mesh buffers once per frame BEFORE beginning dynamic rendering.
            // This keeps VertexBuffer transfers outside the render pass instance.
            modules.forEach { (_, mp) ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(cmd)
                }
            }
        }

        MemoryStack.stackPush().use { stack ->
            if (sbs != null) {
                // Copy side-by-side source into both eyes.
                check(swapchains.size >= 2) { "Need at least two swapchains for stereo" }

                val left = swapchains[0]
                val right = swapchains[1]
                val eyeW = left.width
                val eyeH = left.height
                check(eyeW == right.width && eyeH == right.height) {
                    "Expected stereo eyes to share the same size; got L=${left.width}x${left.height}, R=${right.width}x${right.height}"
                }

                val srcView = sbs.imageView
                val srcImage = srcView.image
                val srcUnderlying = srcView.underlyingImage

                srcUnderlying?.let { img ->
                    val w = img.imageData.width
                    val h = img.imageData.height
                    require(w >= eyeW * 2 && h >= eyeH) {
                        "Side-by-side source too small: got ${w}x${h}, need at least ${eyeW * 2}x${eyeH}"
                    }
                }

                val srcRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                // Assume the source is in SHADER_READ_ONLY_OPTIMAL (RenderToTexture default).
                if (srcUnderlying != null)
                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        srcUnderlying,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        srcRange
                    )
                else
                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        srcImage,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        srcRange
                    )
                for (eye in 0..1) {
                    val sc = swapchains[eye]
                    val imageIndex = acquiredIndices[eye]
                    if (imageIndex < 0) continue

                    val dstImage = sc.images[imageIndex]
                    val dstRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    val dstOldLayout = if (sc.colorSeen[imageIndex]) VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL else VK_IMAGE_LAYOUT_UNDEFINED
                    val (dstSrcStage, dstSrcAccess) = if (sc.colorSeen[imageIndex]) {
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    } else {
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT to 0L
                    }

                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        dstImage,
                        dstSrcStage,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        dstSrcAccess,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        dstOldLayout,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        dstRange
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
                    region[0].srcOffset().set(eye * eyeW, 0, 0)
                    region[0].dstOffset().set(0, 0, 0)
                    region[0].extent().set(eyeW, eyeH, 1)

                    vkCmdCopyImage(
                        cmd.vkCommandBuffer,
                        srcImage,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dstImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region
                    )

                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        dstImage,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        dstRange
                    )

                    sc.colorSeen[imageIndex] = true
                }

                // Restore the source for sampling (e.g. window mirror draw).
                if (srcUnderlying != null) {
                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        srcUnderlying,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcRange
                    )
                } else {
                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        srcImage,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcRange
                    )
                }
            } else {
                for (eye in swapchains.indices) {
                    currentEyeIndex = eye
                    val sc = swapchains[eye]
                    val imageIndex = acquiredIndices[eye]
                    if (imageIndex < 0) continue

                    // Per-eye GPU timing markers
                    val eyeMarks = XrEyeMarks(eyeIndex = eye)
                    ps?.let { p ->
                        synchronized(p.lock) {
                            eyeMarks.tsBegin = p.profiler.writeTimestamp2(
                                cmd.vkCommandBuffer, 0, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                            )
                        }
                    }

                    val width = sc.width
                    val height = sc.height

                    // Color transition (UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL on first use; otherwise keep as COLOR_ATTACHMENT_OPTIMAL)
                    val colorRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    val colorOldLayout =
                        if (sc.colorSeen[imageIndex]) VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL else VK_IMAGE_LAYOUT_UNDEFINED
                    val (srcStage, srcAccess) = if (sc.colorSeen[imageIndex]) {
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT to VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                    } else {
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT to 0L
                    }

                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        sc.images[imageIndex],
                        srcStage,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        srcAccess,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                        colorOldLayout,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        colorRange
                    )
                    sc.colorSeen[imageIndex] = true

                    // Depth transition
                    val depthAtt = sc.depthAttachments[imageIndex]
                    val depthRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT or VK_IMAGE_ASPECT_STENCIL_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1)

                    val depthOldLayout = if (sc.depthSeen[imageIndex]) VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL else VK_IMAGE_LAYOUT_UNDEFINED
                    image_layout_transition(
                        cmd.vkCommandBuffer,
                        depthAtt.image,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT,
                        0L,
                        VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        depthOldLayout,
                        VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL,
                        depthRange
                    )
                    sc.depthSeen[imageIndex] = true

                    val colorAttachment = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                        .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                        .imageView(sc.imageViews[imageIndex])
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .resolveMode(VK_RESOLVE_MODE_NONE)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .clearValue(
                            VkClearValue.calloc(stack).color(
                                VkClearColorValue.calloc(stack)
                                    .float32(0, clearColor.getOrElse(0) { 1f })
                                    .float32(1, clearColor.getOrElse(1) { 0f })
                                    .float32(2, clearColor.getOrElse(2) { 1f })
                                    .float32(3, clearColor.getOrElse(3) { 1f })
                            )
                        )

                    val depthAttachment = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                        .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                        .imageView(depthAtt.imageView.vkImageView)
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                        .resolveMode(VK_RESOLVE_MODE_NONE)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                        .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .clearValue(
                            VkClearValue.calloc(stack).depthStencil(
                                VkClearDepthStencilValue.calloc(stack)
                                    .depth(1f)
                                    .stencil(0)
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
                        .pColorAttachments(colorAttachment)
                        .pDepthAttachment(depthAttachment[0])

                    modules.forEach { (_, mp) ->
                        mp.current.meshes.values.forEach { mesh ->
                            mesh.prepareForDraw(cmd)
                            mesh.push.primeBufferReferences(cmd) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                        mp.current.liveUniformStructures.values.forEach { structure ->
                            structure.primeBufferReferences(cmd) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                    }

                    meshModules.forEach { (_, mp) ->
                        mp.current.meshes.values.forEach { mesh ->
                            mesh.push.primeBufferReferences(cmd) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                        mp.current.liveUniformStructures.values.forEach { structure ->
                            structure.primeBufferReferences(cmd) {
                                throw MissingRefException("risk of GPU null ref `$it`")
                            }
                        }
                    }

                    modules.forEach { (_, mp) ->
                        mp.current.before.forEach { (_, target) ->
                            target.render(cmd, 0)
                        }
                    }

                    meshModules.forEach { (_, mp) ->
                        mp.current.before.forEach { (_, target) ->
                            target.render(cmd, 0)
                        }
                    }

                    // Texture providers may need layout transitions before descriptors are pushed.
                    // Dynamic rendering does not allow image barriers inside the rendering instance.
                    modules.forEach { (_, mp) -> mp.prepareSampledImagesFor(cmd) }
                    meshModules.forEach { (_, mp) -> mp.prepareSampledImagesFor(cmd) }

                    cmd.inRenderPassInstance = true
                    KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd.vkCommandBuffer, renderInfo)

                    // Render all modules into this eye
                    val fullRect = sc.fullRect
                    val rectF = sc.rectF

                    modules.forEach { (_, mp) ->
                        MemoryStack.stackPush().use { s2 ->
                            setViewportAndScissor(cmd.vkCommandBuffer, mp.current.parameters.viewport ?: fullRect, s2)
                        }
                        mp.render(cmd, rectF)
                    }

                    meshModules.forEach { (_, mp) ->
                        MemoryStack.stackPush().use { s2 ->
                            setViewportAndScissor(cmd.vkCommandBuffer, mp.current.parameters.viewport ?: fullRect, s2)
                        }
                        mp.render(cmd, rectF)
                    }

                    KHRDynamicRendering.vkCmdEndRenderingKHR(cmd.vkCommandBuffer)
                    cmd.inRenderPassInstance = false
                    cmd.flushDeferredGraphicsHostReadBarriers()

                    // End per-eye GPU timing
                    ps?.let { p ->
                        synchronized(p.lock) {
                            eyeMarks.tsEnd = p.profiler.writeTimestamp2(
                                cmd.vkCommandBuffer, 0, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                            )
                            p.perFrameEyeMarks.add(eyeMarks)
                        }
                    }
                }
            }
        }

        // End GPU profiling
        ps?.let { p ->
            synchronized(p.lock) {
                p.profiler.endPerfCounters(cmd.vkCommandBuffer, 0, perfScope)
                perfScope?.let { p.markers.perfFrameIndex = it.index }
                p.profiler.endPipelineStats(cmd.vkCommandBuffer, eyesScope)
                p.markers.tsAfterEyes = p.profiler.writeTimestamp2(
                    cmd.vkCommandBuffer, 0, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                )
                p.markers.tsFrameEnd = p.profiler.writeTimestamp2(
                    cmd.vkCommandBuffer, 0, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                )
            }
        }

        currentEyeIndex = -1

        cmd.endRecording()

        // Submit and wait for completion so we can safely xrReleaseSwapchainImage.
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(
                stack.pointers(cmd.vkCommandBuffer),
                null,
                null,
                null,
                fence
            )
        }
        // No wait here: we either wait just before release (conservative), or defer the wait until
        // the next frame when we need to reuse the command buffer/fence.
    }

    private fun pollEvents(): Boolean {
        val xrInstanceLocal = xrInstance ?: return false
        val xrSessionLocal = xrSession

        MemoryStack.stackPush().use { stack ->
            val eventData = XrEventDataBuffer.calloc(stack)

            while (true) {
                eventData.type(XR_TYPE_EVENT_DATA_BUFFER)
                val res = xrPollEvent(xrInstanceLocal, eventData)
                if (res == XR_EVENT_UNAVAILABLE) break
                if (res != XR_SUCCESS) return false

                when (eventData.type()) {
                    XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED -> {
                        val changed = XrEventDataSessionStateChanged.create(eventData.address())
                        sessionState = changed.state()

                        when (sessionState) {
                            XR_SESSION_STATE_READY -> {
                                if (xrSessionLocal != null) {
                                    val beginInfo = XrSessionBeginInfo.calloc(stack)
                                        .type(XR_TYPE_SESSION_BEGIN_INFO)
                                        .primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
                                    val br = xrBeginSession(xrSessionLocal, beginInfo)
                                    if (br == XR_SUCCESS) sessionRunning = true
                                }
                            }

                            XR_SESSION_STATE_STOPPING -> {
                                if (xrSessionLocal != null) {
                                    xrEndSession(xrSessionLocal)
                                }
                                sessionRunning = false
                            }

                            XR_SESSION_STATE_EXITING, XR_SESSION_STATE_LOSS_PENDING -> return false
                        }
                    }

                    XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING -> return false
                }
            }
        }
        return true
    }

    private fun setViewportAndScissor(cmd: VkCommandBuffer, rect: Rect, stack: MemoryStack) {
        val viewport = VkViewport.calloc(1, stack)
            .x(rect.x.toFloat())
            .y((rect.y + rect.h).toFloat())
            .height((-rect.h).toFloat())
            .width(rect.w.toFloat())
            .minDepth(0f)
            .maxDepth(1f)
        vkCmdSetViewport(cmd, 0, viewport)

        val scissor = VkRect2D.calloc(1, stack)
            .extent(
                VkExtent2D.calloc(stack)
                    .width(rect.w.toInt())
                    .height(rect.h.toInt())
            )
            .offset(VkOffset2D.calloc(stack).x(rect.x.toInt()).y(rect.y.toInt()))
        vkCmdSetScissor(cmd, 0, scissor)
    }

    private fun resolveProfilingForFrame(@Suppress("UNUSED_PARAMETER") frameCounter: Long) {
        val ps = profilerState ?: return
        synchronized(ps.lock) {
            val p = ps.profiler
            val m = ps.markers

            // Print performance counter data
            if (m.perfFrameIndex >= 0 && p.opts.perfCounters) {
                val meta = p.perfMeta
                val vals = p.fetchPerfCounters(0, m.perfFrameIndex, meta.size)
                val n = kotlin.math.min(vals.size, meta.size)
                if (n > 0) {
                    val byScope = meta.withIndex().groupBy { it.value.scope }
                    for ((scope, items) in byScope) {
                        lprintln("info,perf", "XR scope=$scope")
                        for (e in items) {
                            val i = e.index
                            lprintln("info,perf", "  ${e.value.name}: ${p.formatPerfValue(vals[i], e.value.unit)}")
                        }
                    }
                }
            }

            // Print frame timing data
            if (m.tsFrameBegin >= 0 && m.tsFrameEnd >= 0) {
                val ts = p.fetchTimestampNsRange(0, m.tsFrameBegin, m.tsFrameEnd)
                if (ts.size >= 3) {
                    val frameNs = ts[2] - ts[0]
                    val eyesNs = ts[1] - ts[0]
                    val postNs = ts[2] - ts[1]
                    lprintln(
                        "info,perf",
                        "XR GPU frame=${frameNs/1e6} ms (eyes=${eyesNs/1e6} ms, post=${postNs/1e6} ms)"
                    )
                }
            }

            // Print pipeline stats
            if (m.pipeStatsEyes >= 0 && p.opts.pipelineStats) {
                val stats = p.fetchPipelineStats(0, m.pipeStatsEyes)
                val iaVerts = stats.getOrNull(0) ?: -1
                val iaPrims = stats.getOrNull(1) ?: -1
                val vsInv   = stats.getOrNull(2) ?: -1
                val fsInv   = stats.getOrNull(7) ?: -1
                lprintln("info,perf", "XR Eyes IA verts=$iaVerts, IA prims=$iaPrims, VS invoc=$vsInv, FS invoc=$fsInv")
            }

            // Print per-eye timing
            val eyeMarks = ps.perFrameEyeMarks
            if (eyeMarks.isNotEmpty()) {
                for (em in eyeMarks) {
                    if (em.tsBegin >= 0 && em.tsEnd >= 0) {
                        val ts = p.fetchTimestampNsRange(0, em.tsBegin, em.tsEnd)
                        if (ts.size >= 2) {
                            val dtMs = (ts.last() - ts.first()) / 1e6
                            lprintln("info,perf", "XR Eye[${em.eyeIndex}]: ${"%.3f".format(dtMs)} ms")
                        }
                    }
                }
            }
        }
    }

    fun cleanup() {
        renderer.runOnQueueThread {
            if (initialized) {
                fence.fenceWait()
            }
        }

        // Profiling resources
        profilerState?.profiler?.destroy()
        profilerState = null

        // Vulkan resources
        swapchains.forEach { sc ->
            sc.depthAttachments.forEach { it.cleanup() }
            sc.imageViews.forEach { vkDestroyImageView(renderer.device.vkDevice, it, null) }
            xrDestroySwapchain(sc.swapchain)
        }
        swapchains.clear()

        // XR resources
        leftGripSpace?.let { xrDestroySpace(it) }
        rightGripSpace?.let { xrDestroySpace(it) }
        leftAimSpace?.let { xrDestroySpace(it) }
        rightAimSpace?.let { xrDestroySpace(it) }
        xrActionSet?.let { xrDestroyActionSet(it) }

        xrSpace?.let { xrDestroySpace(it) }
        xrSession?.let { xrDestroySession(it) }
        xrInstance?.let { xrDestroyInstance(it) }
        leftGripSpace = null
        rightGripSpace = null
        leftAimSpace = null
        rightAimSpace = null
        xrActionSet = null
        xrPoseAction = null
        xrAimPoseAction = null
        xrSelectAction = null
        xrTriggerValueAction = null
        xrTriggerClickAction = null
        xrSqueezeValueAction = null
        xrSqueezeClickAction = null
        xrTrackpadAction = null
        xrTrackpadTouchAction = null
        xrTrackpadClickAction = null
        xrThumbstickAction = null
        xrThumbstickTouchAction = null
        xrThumbstickClickAction = null
        xrAAction = null
        xrBAction = null
        xrMenuAction = null
        xrHapticAction = null
        xrSpace = null
        xrSession = null
        xrInstance = null

        viewConfigs?.free(); viewConfigs = null

        // Vulkan submission resources
        fence.cleanup()
        cmd.cleanup()

        disabledReason = "cleaned up"
    }
}

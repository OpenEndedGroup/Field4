package afield.four.targets

// use your barrier helper
// for Module -> ModulePointer
import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.pointer
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import afield.four.util.DownResult
import afield.four.util.Options
import afield.stdlib.vec
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.primitives.Rectanglef
import org.lwjgl.openvr.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

data class OpenVREyeParams(
    var format: Int = VK_FORMAT_B8G8R8A8_SRGB,
    var clearColor: org.joml.Vector4f = vec(0.3, 0.5, 0.4, 1),
    var useDepth: Boolean = false,
    var depthFormat: Int = Options.defaultDepthFormat
)

private class EyeImage(
    val renderer: Renderer, width: Int, height: Int, val p: OpenVREyeParams
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

        colorView = ImageView(renderer.device, colorImage.vkImage, viewInfo)
    }

    fun beginRendering(cmd: VkCommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            val baseRange = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                .levelCount(1).baseArrayLayer(0).layerCount(1)

            image_layout_transition(
                cmd,
                colorImage.vkImage,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                baseRange
            )
            colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL

            val colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(colorView.vkImageView).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .resolveMode(VK13.VK_RESOLVE_MODE_NONE).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(
                    VkClearValue.calloc(stack).color(
                        VkClearColorValue.calloc(stack).float32(0, p.clearColor.x).float32(1, p.clearColor.y)
                            .float32(2, p.clearColor.z).float32(3, p.clearColor.w)
                    )
                )

            val renderInfo =
                VkRenderingInfoKHR.calloc(stack).sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .renderArea(
                        VkRect2D.calloc(stack).offset(VkOffset2D.calloc(stack).x(0).y(0))
                            .extent(VkExtent2D.calloc(stack).width(width).height(height))
                    ).layerCount(1).pColorAttachments(colorAttach)

            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderInfo)
        }
    }

    fun endRendering(cmd: VkCommandBuffer) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd)

        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                .levelCount(1).baseArrayLayer(0).layerCount(1)

            image_layout_transition(
                cmd,
                colorImage.vkImage,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                range
            )
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
    val axes: FloatArray // up to 5 axes in OpenVR; length can be 0..5
)

class OpenVRRenderTarget(
    val renderer: Renderer, val eyeParams: OpenVREyeParams.() -> Unit = {}
) : Target {

    private var initialized = false

    private lateinit var left: EyeImage
    private lateinit var right: EyeImage

    var recommendedWidth: Int = 0; private set
    var recommendedHeight: Int = 0; private set

    val params = OpenVREyeParams().apply(eyeParams)

    private val modules = linkedMapOf<String, afield.four.modules.ModulePointer>()

    // Pose buffers kept across frames
    private val renderPoses = TrackedDevicePose.calloc(VR.k_unMaxTrackedDeviceCount)
    private val gamePoses = TrackedDevicePose.calloc(VR.k_unMaxTrackedDeviceCount)

    // Controller device indices (k_unTrackedDeviceIndexInvalid if not found)
    private var leftControllerIdx: Int = VR.k_unTrackedDeviceIndexInvalid
    private var rightControllerIdx: Int = VR.k_unTrackedDeviceIndexInvalid

    // Cached HMD absolute transform (AbsoluteFromHead)
    private val absoluteFromHead = Matrix4f()

    // Eye transforms (HeadFromEye), updated on demand
    private val headFromEyeLeft = Matrix4f()
    private val headFromEyeRight = Matrix4f()

    val controllerButtons by lazy { ControllerButtonEvents() }

    init {
        initOpenVR()
        renderer.renderStart["__openvr__"] = sequence {
            while (true) {
                waitPoses()
                yield(true)
            }
        }

        renderer.renderEnd["__openvr__"] = sequence {
            while (true) {
                submitAfterGraphics()
                yield(true)
            }
        }
    }

    private fun initOpenVR() {
        if (!VR.VR_IsHmdPresent()) throw IllegalStateException("No HMD present / SteamVR not running")

        // Initialize OpenVR; use static wrappers (no VR.create()).
        MemoryStack.stackPush().use { s ->
            val pe = s.mallocInt(1)
            VR.VR_InitInternal(pe, VR.EVRApplicationType_VRApplication_Scene)
            val err = pe[0]
            require(err == VR.EVRInitError_VRInitError_None) {
                "OpenVR initialization failed: error=$err"
            }
        }

        // Print required Vulkan extensions (enable these in your Instance/Device creation).
        val instReq = safeGetInstanceExts()
        val devReq = safeGetDeviceExts(renderer.device.physicalDevice.vkPhysicalDevice.address())
        if (instReq.isNotEmpty()) println("[OpenVR] Required Vulkan INSTANCE extensions: $instReq")
        if (devReq.isNotEmpty()) println("[OpenVR] Required Vulkan DEVICE extensions: $devReq")

        MemoryStack.stackPush().use { s ->
            val w = s.mallocInt(1)
            val h = s.mallocInt(1)
            VRSystem.VRSystem_GetRecommendedRenderTargetSize(w, h)
            recommendedWidth = w[0]
            recommendedHeight = h[0]
        }

        left = EyeImage(renderer, recommendedWidth, recommendedHeight, params)
        right = EyeImage(renderer, recommendedWidth, recommendedHeight, params)

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

        left.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, left.width.toFloat(), left.height.toFloat())) }
        left.endRendering(cmd)

        right.beginRendering(cmd)
        modules.values.forEach { it.render(command, Rectanglef(0f, 0f, right.width.toFloat(), right.height.toFloat())) }
        right.endRendering(cmd)
    }

    fun submitAfterGraphics() {
        if (!initialized) return

        submitOneEye(VR.EVREye_Eye_Left, left)
        submitOneEye(VR.EVREye_Eye_Right, right)
    }

    private fun submitOneEye(eye: Int, img: EyeImage) {
        MemoryStack.stackPush().use { stack ->
            // Fill the VRVulkanTextureData_t
            val v = VRVulkanTextureData.malloc(stack).apply {
                m_nImage(img.colorImage.vkImage)
                m_pDevice(renderer.device.vkDevice.address())
                m_pPhysicalDevice(renderer.device.physicalDevice.vkPhysicalDevice.address())
                m_pInstance(renderer.instance.vkInstance.address())
                m_pQueue(renderer.graphQueue.vkQueue.address())
                m_nQueueFamilyIndex(renderer.graphQueue.queueFamilyIndex)
                m_nWidth(img.width)
                m_nHeight(img.height)
                m_nFormat(params.format)
                m_nSampleCount(1)
            }

            val tex = Texture.malloc(stack).apply {
                handle(v.address())
                eType(VR.ETextureType_TextureType_Vulkan)
                eColorSpace(VR.EColorSpace_ColorSpace_Auto)
            }

            val err = VRCompositor.VRCompositor_Submit(
                eye, tex, null, VR.EVRSubmitFlags_Submit_Default
            )
            if (err != VR.EVRCompositorError_VRCompositorError_None) {
                System.err.println("OpenVR Submit error for eye=$eye: $err")
            }
        }
    }

    fun cleanup() {
        if (!initialized) return
        right.cleanup()
        left.cleanup()
        VR.VR_ShutdownInternal()
        initialized = false
    }

    private fun safeGetInstanceExts(): List<String> {
        // String-returning overload; returns space-separated list
        val s = VRCompositor.VRCompositor_GetVulkanInstanceExtensionsRequired(4096)
        return if (s.isNullOrEmpty()) emptyList() else s.split(' ').filter { it.isNotBlank() }
    }

    private fun safeGetDeviceExts(physicalDeviceAddr: Long): List<String> {
        val s = VRCompositor.VRCompositor_GetVulkanDeviceExtensionsRequired(physicalDeviceAddr, 4096)
        return if (s.isNullOrEmpty()) emptyList() else s.split(' ').filter { it.isNotBlank() }
    }

    private val trackedDevicePose = TrackedDevicePose.calloc(VR.k_unMaxTrackedDeviceCount)
    private val gamePose = TrackedDevicePose.calloc(VR.k_unMaxTrackedDeviceCount)

    private fun waitPoses() {
        val err = VRCompositor.VRCompositor_WaitGetPoses(trackedDevicePose, gamePose)
        if (err != VR.EVRCompositorError_VRCompositorError_None) {
            System.err.println("WaitGetPoses error: $err")
        }

        val hmdIndex = VR.k_unTrackedDeviceIndex_Hmd
        val pose = renderPoses[hmdIndex]
        if (pose.bPoseIsValid()) {
            // AbsoluteFromHead
            hmd34ToMatrix4f(pose.mDeviceToAbsoluteTracking(), absoluteFromHead)
        }

        updateControllerIndices()
    }

    /**
     * Returns the absolute-space pose for a controller role (LEFT/RIGHT),
     * or null if the device is absent or tracking is invalid.
     */
    fun getControllerPose(role: ControllerRole): ControllerPose? {
        val idx = when (role) {
            ControllerRole.LEFT -> leftControllerIdx
            ControllerRole.RIGHT -> rightControllerIdx
        }
        if (idx == VR.k_unTrackedDeviceIndexInvalid) return null

        val p = renderPoses[idx]
        val connected = p.bDeviceIsConnected()
        val valid = p.bPoseIsValid()
        val trackingResult = p.eTrackingResult()

        if (!connected || !valid) {
            return ControllerPose(
                deviceIndex = idx,
                matrix = Matrix4f(),
                position = Vector3f(),
                orientation = Quaternionf(),
                linearVelocity = Vector3f(),
                angularVelocity = Vector3f(),
                connected = connected,
                trackingValid = valid,
                trackingResult = trackingResult
            )
        }

        val m = Matrix4f()
        hmd34ToMatrix4f(p.mDeviceToAbsoluteTracking(), m)

        // extract position (last column) and orientation (from 3x3 basis)
        val pos = Vector3f(m.m30(), m.m31(), m.m32())
        val ori = Quaternionf().setFromUnnormalized(
            Matrix3f(
                m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22()
            )
        )

        val lv = vec3From(p.vVelocity())
        val av = vec3From(p.vAngularVelocity())

        return ControllerPose(
            deviceIndex = idx,
            matrix = m,
            position = pos,
            orientation = ori,
            linearVelocity = lv,
            angularVelocity = av,
            connected = connected,
            trackingValid = valid,
            trackingResult = trackingResult
        )
    }

    /**
     * Polls the controller state (buttons & axes). Returns null if device invalid.
     * You can query symbolic buttons with the mask defined by OpenVR (e.g., k_EButton_SteamVR_Trigger).
     */
    fun getControllerState(role: ControllerRole): ControllerState? {
        val idx = when (role) {
            ControllerRole.LEFT -> leftControllerIdx
            ControllerRole.RIGHT -> rightControllerIdx
        }
        if (idx == VR.k_unTrackedDeviceIndexInvalid) return null

        val state = VRControllerState.calloc()
        return try {
            val ok = VRSystem.VRSystem_GetControllerState(idx, state, VRControllerState.SIZEOF)
            if (!ok) return null

            // axes: up to 5 (x,y pairs); build a compact array
            val axesList = ArrayList<Float>(10)
            for (i in 0 until VR.k_unControllerStateAxisCount) {
                axesList += state.rAxis(i).x()
                axesList += state.rAxis(i).y()
            }

            ControllerState(
                deviceIndex = idx,
                packetNum = state.unPacketNum().toInt(),
                buttonPressedMask = state.ulButtonPressed(),
                buttonTouchedMask = state.ulButtonTouched(),
                axes = axesList.toFloatArray()
            )
        } finally {
            state.free()
        }
    }


    private fun updateControllerIndices() {
        leftControllerIdx = VRSystem.VRSystem_GetTrackedDeviceIndexForControllerRole(
            VR.ETrackedControllerRole_TrackedControllerRole_LeftHand
        )
        rightControllerIdx = VRSystem.VRSystem_GetTrackedDeviceIndexForControllerRole(
            VR.ETrackedControllerRole_TrackedControllerRole_RightHand
        )
    }

    private fun refreshEyeFromHeadTransforms() {
        // OpenVR returns EyeToHead (i.e., HeadFromEye); we convert to 4x4 directly.
        val left34 = HmdMatrix34.calloc()
        val right34 = HmdMatrix34.calloc()
        try {
            VRSystem.VRSystem_GetEyeToHeadTransform(VR.EVREye_Eye_Left, left34)
            VRSystem.VRSystem_GetEyeToHeadTransform(VR.EVREye_Eye_Right, right34)
            hmd34ToMatrix4f(left34, headFromEyeLeft)
            hmd34ToMatrix4f(right34, headFromEyeRight)
        } finally {
            left34.free()
            right34.free()
        }
    }

    fun leftMVP(zNear: Float = 0.01f, zFar: Float = 1000f) =
        getViewProjection(VR.EVREye_Eye_Left, zNear = zNear, zFar = zFar, vulkanYFlip = true)

    fun rightMVP(zNear: Float = 0.01f, zFar: Float = 1000f) =
        getViewProjection(VR.EVREye_Eye_Right, zNear = zNear, zFar = zFar, vulkanYFlip = true)

    /**
     * Returns per-eye View and Projection matrices.
     * - View = inverse(AbsoluteFromHead * HeadFromEye)
     * - Projection from VRSystem (optionally Vulkan Y-flipped).
     *
     * Call after waitGetPoses() for freshest head pose.
     */
    fun getViewProjection(
        eye: Int,
        zNear: Float,
        zFar: Float,
        vulkanYFlip: Boolean = true,
        outView: Matrix4f = Matrix4f(),
        outProj: Matrix4f = Matrix4f()
    ): Pair<Matrix4f, Matrix4f> {
        if (!initialized) return outView.identity() to outProj.identity()

        // Ensure we have current HeadFromEye transforms
        refreshEyeFromHeadTransforms()

        // AbsoluteFromEye = AbsoluteFromHead * HeadFromEye
        when (eye) {
            VR.EVREye_Eye_Left -> outView.set(absoluteFromHead).mul(headFromEyeLeft)
            VR.EVREye_Eye_Right -> outView.set(absoluteFromHead).mul(headFromEyeRight)
            else -> outView.set(absoluteFromHead).mul(headFromEyeLeft)
        }
        // View = inverse(AbsoluteFromEye)
        outView.invert()

        // Projection
        val proj44 = HmdMatrix44.calloc()
        try {
            VRSystem.VRSystem_GetProjectionMatrix(eye, zNear, zFar, proj44)
            hmd44ToMatrix4f(proj44, outProj)
            if (vulkanYFlip) {
                // Flip Y for Vulkan NDC (if your shader/pipeline doesn’t already)
                outProj.m11(-outProj.m11())
            }
        } finally {
            proj44.free()
        }

        return outView to outProj
    }

    /** Convert OpenVR HmdMatrix34 (row-major 3x4) to JOML Matrix4f. */
    private fun hmd34ToMatrix4f(src: HmdMatrix34, dst: Matrix4f = Matrix4f()): Matrix4f {
        val m = src.m() // FloatBuffer length 12, row-major: r0[0..3], r1[4..7], r2[8..11]
        return dst.set(
            m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], 0f, 0f, 0f, 1f
        )
    }

    /** Convert OpenVR HmdMatrix44 (row-major 4x4) to JOML Matrix4f. */
    private fun hmd44ToMatrix4f(src: HmdMatrix44, dst: Matrix4f = Matrix4f()): Matrix4f {
        val m = src.m() // FloatBuffer length 16, row-major
        return dst.set(
            m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]
        )
    }

    private fun vec3From(src: HmdVector3): Vector3f = Vector3f(src.v(0), src.v(1), src.v(2))

    // --- Controller buttons (OpenVR) -> KeyEvents-style handling -----------------
    inner class ControllerButtonEvents(val name: String = "__controllerButtons__") {
        // Handlers (same shape as KeyEvents)
        private val down = mutableMapOf<String, MutableMap<String, () -> DownResult>>()
        private val up   = mutableMapOf<String, MutableMap<String, () -> Boolean>>()

        // Per-press, transient onUp callbacks created by down-handlers
        private val pendingUp = mutableMapOf<String, MutableList<() -> Unit>>()

        // Track what's currently down ("left_33", "right_2", etc.)
        private val wasDown = mutableSetOf<String>()

        // Cached state structs reused each tick
        private val stateLeft  = VRControllerState.calloc()
        private val stateRight = VRControllerState.calloc()

        init {
            // Register a per-poll coroutine just like KeyEvents does
            renderer.window.onPoll[name] = sequence {
                while (true) {
                    // Pull current device indices (may be invalid if not present)
                    val lIdx = leftControllerIdx
                    val rIdx = rightControllerIdx

                    // Read controller states once
                    val hasL = lIdx != VR.k_unTrackedDeviceIndexInvalid &&
                            VRSystem.VRSystem_GetControllerState(lIdx, stateLeft, VRControllerState.SIZEOF)
                    val hasR = rIdx != VR.k_unTrackedDeviceIndexInvalid &&
                            VRSystem.VRSystem_GetControllerState(rIdx, stateRight, VRControllerState.SIZEOF)

                    // What handlers want us to watch:
                    val keys: Set<String> = (down.keys + up.keys)
                    val nowDown = mutableSetOf<String>()

                    // Evaluate each registered key against the current mask
                    for (k in keys) {
                        val parsed = parseKey(k) ?: continue // warns inside parseKey if malformed
                        val pressed = when (parsed.hand) {
                            Hand.LEFT  -> hasL && isPressed(stateLeft, parsed.buttonId)
                            Hand.RIGHT -> hasR && isPressed(stateRight, parsed.buttonId)
                        }
                        if (pressed) nowDown.add(k)
                    }

                    val newDown = nowDown - wasDown
                    val newUp   = wasDown - nowDown

                    // Fire DOWN; collect UntilUp callbacks; decide handler retention
                    newDown.forEach { n ->
                        down[n]?.let { map ->
                            val toKeep = mutableListOf<Pair<String, () -> DownResult>>()
                            map.forEach { (k, cb) ->
                                when (val r = cb()) {
                                    DownResult.Keep -> toKeep += k to cb
                                    DownResult.Remove -> { /* drop */ }
                                    is DownResult.UntilUp -> {
                                        pendingUp.getOrPut(n) { mutableListOf() }.add(r.onUp)
                                        toKeep += k to cb
                                    }
                                }
                            }
                            map.clear()
                            toKeep.forEach { (k, cb) -> map[k] = cb }
                        }
                    }

                    // Fire transient onUp from down-handlers first
                    newUp.forEach { n -> pendingUp.remove(n)?.forEach { it.invoke() } }

                    // Then fire normal UP handlers; allow self-unregister via Boolean
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
            onDownAndUp(key, name) { DownResult.UntilUp(onUp) }
        }

        fun onDown(key: String, name: String, callback: () -> Boolean) {
            onDownAndUp(key, name) { if (callback()) DownResult.Keep else DownResult.Remove }
        }

        fun onUp(key: String, name: String, callback: () -> Boolean) {
            if (!isWellFormed(key)) warnNoSuchKey(key)
            up.computeIfAbsent(key) { mutableMapOf() }[name] = callback
        }

        // --- helpers -------------------------------------------------------------


        private fun isWellFormed(key: String): Boolean =
            key.startsWith("left_") || key.startsWith("right_")

        private fun warnNoSuchKey(key: String) {
            if (!isWellFormed(key)) {
                println(" -- warning, controller key must be 'left_N' or 'right_N', got: $key")
            }
        }

        private fun parseKey(key: String): Parsed? {
            val parts = key.split('_')
            if (parts.size != 2) { warnNoSuchKey(key); return null }
            val hand = when (parts[0]) {
                "left"  -> Hand.LEFT
                "right" -> Hand.RIGHT
                else -> { warnNoSuchKey(key); return null }
            }
            val id = parts[1].toIntOrNull()
            if (id == null || id < 0 || id >= 64) {
                println(" -- warning, button id must be 0..63 (OpenVR bit index): $key")
                return null
            }
            return Parsed(hand, id)
        }

        // OpenVR uses a 64-bit mask; EVRButtonId values map to bit positions.
        private fun isPressed(state: VRControllerState, buttonBitIndex: Int): Boolean {
            val mask = 1L shl buttonBitIndex
            return (state.ulButtonPressed() and mask) != 0L
        }
    }


}

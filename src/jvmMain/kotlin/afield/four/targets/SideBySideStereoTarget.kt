package afield.four.targets

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf

import afield.Rect
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.targets.OpenXRHmdTarget.ControllerState
import afield.four.targets.OpenXRHmdTarget.Hand
import afield.four.texture.HasImageView
import afield.four.texture.image_layout_transition
import afield.four.util.Camera
import afield.four.util.DownResult
import afield.four.util.MouseEvent
import afield.four.util.MouseEvents
import afield.four.util.Options
import afield.stdlib.times
import afield.stdlib.vec
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageCopy
import org.lwjgl.vulkan.VkImageSubresourceRange

/**
 * Side-by-side stereo helper that renders two eye passes into a single RTT (left half / right half).
 *
 * - Acts as a sideBySideSource for OpenXR via [packed] image view.
 * - Renders registered modules twice (left/right) using separate RenderToTexture passes, then copies
 *   into a packed side-by-side image.
 * - Exposes per-eye view/projection matrices. If an OpenXR target is attached, delegates to it;
 *   otherwise uses an internal stereo camera.
 */
class SideBySideStereoTarget(
    val renderer: Renderer = fromContext(),
    val eyeWidth: Int = renderer.swapChain.swapChainExtent.width() / 2,
    val eyeHeight: Int = renderer.swapChain.swapChainExtent.height(),
    var openXR: OpenXRHmdTarget? = null
) : afield.four.Target, HasImageView {

    // Internal stereo camera for non-XR rendering paths.
    val stereoCamera = StereoCamera(Camera(renderer))

    // Per-eye offscreen passes.
    private val left = RenderToTexture(renderer) {
        width = eyeWidth
        height = eyeHeight
        colorAttachments = 1
        colorSampled = true
        colorTransferSrc = true
        useDepth = true
        clearColor = vec(0f, 0f, 0f, 1f)
    }

    private val right = RenderToTexture(renderer) {
        width = eyeWidth
        height = eyeHeight
        colorAttachments = 1
        colorSampled = true
        colorTransferSrc = true
        useDepth = true
        clearColor = vec(0f, 0f, 0f, 1f)
    }

    // Packed side-by-side output (left half, right half) suitable as OpenXR sideBySideSource.
    val packed = RenderToTexture(renderer) {
        width = eyeWidth * 2
        height = eyeHeight
        colorAttachments = 1
        colorSampled = true
        colorTransferSrc = true
        colorTransferDst = true
        useDepth = false
        clearColor = vec(0f, 0f, 0f, 1f)
    }

    override val imageView = packed.imageView

    // Active eye index during rendering (-1 when idle).
    var currentEyeIndex: Int = -1
        private set

    /**
     * A remote head to render from, in preference to [openXR] and to [stereoCamera].
     *
     * Set this and the eye passes draw from whatever the source last latched — see [StereoPoseSource]
     * for why a remote pose must be latched rather than read per eye.
     */
    var poseSource: StereoPoseSource? = null

    /**
     * Identity of the pose the most recent frame was rendered from, or -1 when [poseSource] is unset
     * or has never latched.
     *
     * This is the frame/pose association: whatever ships the rendered image onward can say which pose
     * it was drawn for, so a later stage can reproject it instead of assuming it is current.
     */
    var renderedPoseFrameId: Long = -1L
        private set

    /** The head's predicted display time for [renderedPoseFrameId], in *the head's* clock. */
    var renderedPoseDisplayTime: Double = 0.0
        private set

    // Simple mock controller fallback when OpenXR is absent.
    private val mockController = ControllerState(active = true, poseValid = true, position = vec(0.0, 0.0, 0.0))
    private var mouseControllerPos: Vector2f? = null
    private var mouseControllerDistance: Float = 1f

    init {
        // Render eyes before the main swapchain if desired.
        renderer.renderList.preTargets["sbs-left"] = left
        renderer.renderList.preTargets["sbs-right"] = right
        renderer.renderList.preTargets["sbs-pack"] = this

        // If an OpenXR target is provided, wire the packed RTT as its side-by-side source.
        openXR?.copySideBySideToEyes(packed)
    }

    /** Register a module factory; it will be instantiated once per eye. */
    fun registerModule(name: String, factory: () -> ModulePointer) {
        val leftModule = factory()
        val rightModule = factory()
        left += "$name-left" to leftModule
        right += "$name-right" to rightModule
    }

    @JvmName("plusAssign_moduleFactory")
    operator fun plusAssign(entry: Pair<String, () -> ModulePointer>) {
        registerModule(entry.first, entry.second)
    }

    /** Convenience: attach this packed RTT as the OpenXR side-by-side source. */
    fun attachToOpenXR(target: OpenXRHmdTarget) {
        openXR = target
        target.copySideBySideToEyes(packed)
    }

    /** Current eye view matrix (a latched [poseSource] first, then OpenXR, then [stereoCamera]). */
    fun currentEyeViewMatrix(out: Matrix4f = Matrix4f()): Matrix4f {
        poseSource?.takeIf { it.latchedFrameId >= 0 }?.let {
            return it.eyeViewMatrix(currentEyeIndex.coerceIn(0, 1), out)
        }
        val xr = openXR
        return if (xr != null && xr.currentEyeIndex >= 0) {
            xr.currentEyeViewMatrix(out)
        } else {
            if (xr != null) {
                val eye = currentEyeIndex.coerceIn(0, 1)
                xr.eyeViewMatrix(eye, out)
            }
            else {
                val eye = currentEyeIndex.coerceIn(0, 1)
                val views = stereoCamera.eyeViewMatrices()
                out.set(views[eye])
            }
        }
    }

    /** Current eye projection matrix (a latched [poseSource] first, then OpenXR, then [stereoCamera]). */
    fun currentEyeProjectionMatrix(out: Matrix4f = Matrix4f()): Matrix4f {
        poseSource?.takeIf { it.latchedFrameId >= 0 }?.let {
            return it.eyeProjectionMatrix(currentEyeIndex.coerceIn(0, 1), out)
        }
        val xr = openXR
        return if (xr != null && xr.currentEyeIndex >= 0) {
            xr.currentEyeProjectionMatrix(out)
        } else {
            if (xr != null) {
                val eye = currentEyeIndex.coerceIn(0, 1)
                xr.eyeProjectionMatrix(eye, out)
            }
            else {
                val eye = currentEyeIndex.coerceIn(0, 1)
                val projs = stereoCamera.projectionMatrices()
                out.set(projs[eye])
            }
        }
    }

    /**
     * Controller state passthrough to OpenXR when attached; otherwise returns a simple mock
     * right-hand controller positioned via [updateMockControllerFromRay] or [updateMockControllerFromCamera].
     */
    fun controllerState(hand: Hand = Hand.RIGHT, out: ControllerState = ControllerState()): ControllerState {
        // Same precedence as the eye matrices: a latched remote head first, then OpenXR, then the
        // mouse-driven mock. Taking input from the latched frame rather than live keeps it consistent
        // with the image it will act on.
        poseSource?.takeIf { it.latchedFrameId >= 0 }?.let { source ->
            if (source.controller(hand, out)) return out
        }
        val xr = openXR
        if (xr != null) return xr.controllerState(hand, out)

        val src = mockController
        out.active = src.active
        out.poseValid = src.poseValid
        out.position = src.position
        out.rotation = src.rotation
        out.selectPressed = src.selectPressed
        out.triggerValue = src.triggerValue; out.triggerPressed = src.triggerPressed
        out.squeezeValue = src.squeezeValue; out.squeezePressed = src.squeezePressed
        out.trackpadX = src.trackpadX; out.trackpadY = src.trackpadY
        out.trackpadTouched = src.trackpadTouched; out.trackpadPressed = src.trackpadPressed
        out.thumbstickX = src.thumbstickX; out.thumbstickY = src.thumbstickY
        out.thumbstickTouched = src.thumbstickTouched; out.thumbstickPressed = src.thumbstickPressed
        out.aPressed = src.aPressed; out.bPressed = src.bPressed; out.menuPressed = src.menuPressed
        return out
    }

    /** Position the mock controller 1m along the current camera forward vector. */
    fun updateMockControllerFromCamera(distanceMeters: Float = 1f) {
        val cam = stereoCamera.base
        val origin = cam.at
        val forward = cam.forward
        val dir = Vector3f(forward).normalize()
        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    /** Position the mock controller along an arbitrary ray (origin + direction * distance). */
    fun updateMockControllerFromRay(origin: Vector3f, direction: Vector3f, distanceMeters: Float = 1f) {
        val nx = direction.normalize(Vector3f())
        mockController.position = origin + nx * distanceMeters
        mockController.rotation = lookAlongZ(nx, Vector3f(0f, 1f, 0f))
        mockController.poseValid = true
    }

    /** Attach mouse handlers to drive the mock controller. Re-registering replaces previous handlers. */
    fun enableMouseController(mouse: MouseEvents = renderer.window.mouseEvents ?: MouseEvents(renderer), name: String = "sbs-mouse-controller", distanceMeters: Float = 1f) {
        mouseControllerDistance = distanceMeters

        val updateFromScreen: (MouseEvent) -> Unit = { ev ->
            updateMockControllerFromScreen(ev.pos.x, ev.pos.y, distanceMeters)
            mouseControllerPos = Vector2f(ev.pos.x, ev.pos.y)
        }

        mouse.onMove(name, callback = { ev -> updateFromScreen(ev); true })
        mouse.onDrag(name, callback = { ev -> updateFromScreen(ev); true })

        mouse.onDown("left", name) { ev ->
            updateFromScreen(ev)
            mockController.selectPressed = true
            mockController.triggerPressed = true
            mockController.triggerValue = 1f
            DownResult.Keep
        }
        mouse.onUp("left", name) { _ ->
            mockController.selectPressed = false
            mockController.triggerPressed = false
            mockController.triggerValue = 0f
            true
        }
    }

    private fun updateMockControllerFromScreen(x: Float, y: Float, distanceMeters: Float) {
        val w = renderer.window.width.toFloat().coerceAtLeast(1f)
        val h = renderer.window.height.toFloat().coerceAtLeast(1f)
        val ndcX = (x / w) * 2f - 1f
        val ndcY = 1f - (y / h) * 2f // flip Y so screen top maps to +Y

        val cam = stereoCamera.base
        val origin = cam.at

        val halfFovRad = ((cam.p.fov * kotlin.math.PI / 180.0) * 0.5).toFloat()
        val tanHalfFov = kotlin.math.tan(halfFovRad)
        val aspect = cam.p.aspect.takeIf { it > 0f } ?: (w / h)

        val scale = 2f // widen rays to match on-screen scale
        val dir = Vector3f(cam.forward)
            .fma(-ndcX * aspect * tanHalfFov * scale, cam.right)
            .fma(ndcY * tanHalfFov * scale, cam.up)
            .normalize()

        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    private fun lookAlongZ(direction: Vector3f, up: Vector3f): Quaternionf {
        val dir = Vector3f(direction).normalize()
        val upNorm = Vector3f(up).normalize()
        return Quaternionf().lookAlong(dir, upNorm)
    }

    override fun render(command: CommandBuffer, index: Int) {
        // Keep mock controller aligned with camera even if mouse hasn’t moved.
        mouseControllerPos?.let { updateMockControllerFromScreen(it.x, it.y, mouseControllerDistance) }

        // Keep stereo camera projection matched to eye render size.
        stereoCamera.base.p.resize(eyeWidth, eyeHeight)

        // Latch the remote pose once, here, so both eye passes draw from the same head position. A
        // per-eye read would let the pose move between them, which shows up as the two eyes
        // disagreeing rather than as lag.
        poseSource?.let { source ->
            source.latch()
            renderedPoseFrameId = source.latchedFrameId
            renderedPoseDisplayTime = source.latchedDisplayTime
        }

        // Render each eye into its own RTT.
        currentEyeIndex = 0
        left.render(command, index)
        currentEyeIndex = 1
        right.render(command, index)
        currentEyeIndex = -1

        // Pack into side-by-side.
        copyEyesIntoPacked(command)
    }

    private fun copyEyesIntoPacked(command: CommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            val dstRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            val dstView = packed.imageView
            val dstImage = dstView.image
            val dstUnderlying = dstView.underlyingImage

            // Transition destination to TRANSFER_DST
            if (dstUnderlying != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstUnderlying,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )
            } else {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstImage,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )
            }

            fun copyEye(srcRTT: RenderToTexture, eyeIndex: Int) {
                val srcView = srcRTT.imageViewArray[0].imageView
                val srcImage = srcView.image
                val srcUnderlying = srcView.underlyingImage

                val srcRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)

                // Source to TRANSFER_SRC
                if (srcUnderlying != null)
                    image_layout_transition(
                        command.vkCommandBuffer,
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
                        command.vkCommandBuffer,
                        srcImage,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        srcRange
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
                region[0].dstOffset().set(eyeIndex * eyeWidth, 0, 0)
                region[0].extent().set(eyeWidth, eyeHeight, 1)

                org.lwjgl.vulkan.VK10.vkCmdCopyImage(
                    command.vkCommandBuffer,
                    srcImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region
                )

                // Restore source to SHADER_READ
                if (srcUnderlying != null)
                    image_layout_transition(
                        command.vkCommandBuffer,
                        srcUnderlying,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcRange
                    )
                else
                    image_layout_transition(
                        command.vkCommandBuffer,
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

            copyEye(left, 0)
            copyEye(right, 1)

            // Restore destination to SHADER_READ
            if (dstUnderlying != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstUnderlying,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    dstRange
                )
            } else {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstImage,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    dstRange
                )
            }
        }
    }
}

/**
 * Multiview variant of [SideBySideStereoTarget].
 *
 * Registered modules are rendered once into a two-layer RTT with `viewMask = 0b11`.
 * Shaders are expected to use `gl_ViewIndex` to select per-eye data. After the
 * multiview pass, layer 0 and layer 1 are copied into [packed] as left/right halves
 * so existing OpenXR side-by-side copy paths can still consume the result.
 *
 * Note: this variant has no [StereoPoseSource] hook. Remote-head rendering is the macOS/Vision Pro
 * path, which is exactly where multiview is the awkward one — so the pose source lives on
 * [SideBySideStereoTarget] only, rather than being duplicated here untested.
 *
 * This intentionally remains a separate class because MoltenVK/macOS multiview is
 * not reliable for Field's current path.
 */
class MultiviewSideBySideStereoTarget(
    val renderer: Renderer = fromContext(),
    val eyeWidth: Int = renderer.swapChain.swapChainExtent.width() / 2,
    val eyeHeight: Int = renderer.swapChain.swapChainExtent.height(),
    var openXR: OpenXRHmdTarget? = null
) : afield.four.Target, HasImageView {

    val stereoCamera = StereoCamera(Camera(renderer))

    val layered = RenderToTexture(renderer) {
        width = eyeWidth
        height = eyeHeight
        colorAttachments = 1
        colorLayerCount = 2
        multiviewViewMask = 0b11
        viewports = listOf(
            Rect(0.0, 0.0, eyeWidth.toDouble(), eyeHeight.toDouble()),
            Rect(0.0, 0.0, eyeWidth.toDouble(), eyeHeight.toDouble())
        )
        colorSampled = true
        colorTransferSrc = true
        useDepth = true
        clearColor = vec(0f, 0f, 0f, 1f)
    }

    val packed = RenderToTexture(renderer) {
        width = eyeWidth * 2
        height = eyeHeight
        colorAttachments = 1
        colorSampled = true
        colorTransferSrc = true
        colorTransferDst = true
        useDepth = false
        clearColor = vec(0f, 0f, 0f, 1f)
    }

    override val imageView = packed.imageView

    val layeredImageView: HasImageView
        get() = layered

    var currentEyeIndex: Int = -1
        private set

    private val mockController = ControllerState(active = true, poseValid = true, position = vec(0.0, 0.0, 0.0))
    private var mouseControllerPos: Vector2f? = null
    private var mouseControllerDistance: Float = 1f

    init {
        require(!Options.isMac) {
            "MultiviewSideBySideStereoTarget is disabled on macOS; use SideBySideStereoTarget instead"
        }
        require(renderer.device.supportsMultiview) {
            "MultiviewSideBySideStereoTarget requires VK_KHR_multiview support"
        }

        renderer.renderList.preTargets["sbs-multiview-pack"] = this
        openXR?.copySideBySideToEyes(packed)
    }

    fun registerModule(name: String, factory: () -> ModulePointer) {
        layered += name to factory()
    }

    fun registerModule(name: String, module: ModulePointer) {
        layered += name to module
    }

    @JvmName("plusAssign_moduleFactory_multiview")
    operator fun plusAssign(entry: Pair<String, () -> ModulePointer>) {
        registerModule(entry.first, entry.second)
    }

    @JvmName("plusAssign_modulePointer_multiview")
    operator fun plusAssign(entry: Pair<String, ModulePointer>) {
        registerModule(entry.first, entry.second)
    }

    fun attachToOpenXR(target: OpenXRHmdTarget) {
        openXR = target
        target.copySideBySideToEyes(packed)
    }

    fun eyeViewMatrix(eye: Int, out: Matrix4f = Matrix4f()): Matrix4f {
        val safeEye = eye.coerceIn(0, 1)
        val xr = openXR
        return if (xr != null) {
            xr.eyeViewMatrix(safeEye, out)
        } else {
            val views = stereoCamera.eyeViewMatrices()
            out.set(views[safeEye])
        }
    }

    fun eyeProjectionMatrix(eye: Int, out: Matrix4f = Matrix4f()): Matrix4f {
        val safeEye = eye.coerceIn(0, 1)
        val xr = openXR
        return if (xr != null) {
            xr.eyeProjectionMatrix(safeEye, out)
        } else {
            val projs = stereoCamera.projectionMatrices()
            out.set(projs[safeEye])
        }
    }

    fun eyeViewMatrices(): List<Matrix4f> =
        listOf(eyeViewMatrix(0), eyeViewMatrix(1))

    fun eyeProjectionMatrices(): List<Matrix4f> =
        listOf(eyeProjectionMatrix(0), eyeProjectionMatrix(1))

    fun currentEyeViewMatrix(out: Matrix4f = Matrix4f()): Matrix4f =
        eyeViewMatrix(currentEyeIndex.coerceAtLeast(0), out)

    fun currentEyeProjectionMatrix(out: Matrix4f = Matrix4f()): Matrix4f =
        eyeProjectionMatrix(currentEyeIndex.coerceAtLeast(0), out)

    fun controllerState(hand: Hand = Hand.RIGHT, out: ControllerState = ControllerState()): ControllerState {
        val xr = openXR
        if (xr != null) return xr.controllerState(hand, out)

        val src = mockController
        out.active = src.active
        out.poseValid = src.poseValid
        out.position = src.position
        out.rotation = src.rotation
        out.selectPressed = src.selectPressed
        out.triggerValue = src.triggerValue; out.triggerPressed = src.triggerPressed
        out.squeezeValue = src.squeezeValue; out.squeezePressed = src.squeezePressed
        out.trackpadX = src.trackpadX; out.trackpadY = src.trackpadY
        out.trackpadTouched = src.trackpadTouched; out.trackpadPressed = src.trackpadPressed
        out.thumbstickX = src.thumbstickX; out.thumbstickY = src.thumbstickY
        out.thumbstickTouched = src.thumbstickTouched; out.thumbstickPressed = src.thumbstickPressed
        out.aPressed = src.aPressed; out.bPressed = src.bPressed; out.menuPressed = src.menuPressed
        return out
    }

    fun updateMockControllerFromCamera(distanceMeters: Float = 1f) {
        val cam = stereoCamera.base
        val origin = cam.at
        val forward = cam.forward
        val dir = Vector3f(forward).normalize()
        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    fun updateMockControllerFromRay(origin: Vector3f, direction: Vector3f, distanceMeters: Float = 1f) {
        val nx = direction.normalize(Vector3f())
        mockController.position = origin + nx * distanceMeters
        mockController.rotation = lookAlongZ(nx, Vector3f(0f, 1f, 0f))
        mockController.poseValid = true
    }

    fun enableMouseController(mouse: MouseEvents = renderer.window.mouseEvents ?: MouseEvents(renderer), name: String = "sbs-multiview-mouse-controller", distanceMeters: Float = 1f) {
        mouseControllerDistance = distanceMeters

        val updateFromScreen: (MouseEvent) -> Unit = { ev ->
            updateMockControllerFromScreen(ev.pos.x, ev.pos.y, distanceMeters)
            mouseControllerPos = Vector2f(ev.pos.x, ev.pos.y)
        }

        mouse.onMove(name, callback = { ev -> updateFromScreen(ev); true })
        mouse.onDrag(name, callback = { ev -> updateFromScreen(ev); true })

        mouse.onDown("left", name) { ev ->
            updateFromScreen(ev)
            mockController.selectPressed = true
            mockController.triggerPressed = true
            mockController.triggerValue = 1f
            DownResult.Keep
        }
        mouse.onUp("left", name) { _ ->
            mockController.selectPressed = false
            mockController.triggerPressed = false
            mockController.triggerValue = 0f
            true
        }
    }

    private fun updateMockControllerFromScreen(x: Float, y: Float, distanceMeters: Float) {
        val w = renderer.window.width.toFloat().coerceAtLeast(1f)
        val h = renderer.window.height.toFloat().coerceAtLeast(1f)
        val ndcX = (x / w) * 2f - 1f
        val ndcY = 1f - (y / h) * 2f

        val cam = stereoCamera.base
        val origin = cam.at

        val halfFovRad = ((cam.p.fov * kotlin.math.PI / 180.0) * 0.5).toFloat()
        val tanHalfFov = kotlin.math.tan(halfFovRad)
        val aspect = cam.p.aspect.takeIf { it > 0f } ?: (w / h)

        val scale = 2f
        val dir = Vector3f(cam.forward)
            .fma(-ndcX * aspect * tanHalfFov * scale, cam.right)
            .fma(ndcY * tanHalfFov * scale, cam.up)
            .normalize()

        mockController.position = origin + dir * distanceMeters
        mockController.rotation = lookAlongZ(dir, cam.up)
        mockController.poseValid = true
    }

    private fun lookAlongZ(direction: Vector3f, up: Vector3f): Quaternionf {
        val dir = Vector3f(direction).normalize()
        val upNorm = Vector3f(up).normalize()
        return Quaternionf().lookAlong(dir, upNorm)
    }

    override fun render(command: CommandBuffer, index: Int) {
        mouseControllerPos?.let { updateMockControllerFromScreen(it.x, it.y, mouseControllerDistance) }

        stereoCamera.base.p.resize(eyeWidth, eyeHeight)

        currentEyeIndex = -1
        layered.render(command, index)
        copyLayersIntoPacked(command)
    }

    private fun copyLayersIntoPacked(command: CommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            val dstRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            val dstView = packed.imageView
            val dstImage = dstView.image
            val dstUnderlying = dstView.underlyingImage

            if (dstUnderlying != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstUnderlying,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )
            } else {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstImage,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    dstRange
                )
            }

            fun copyLayer(eyeIndex: Int) {
                val srcView = layered.imageViewArray[0].imageView
                val srcImage = srcView.image
                val srcUnderlying = srcView.underlyingImage

                val srcRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(eyeIndex)
                    .layerCount(1)

                if (srcUnderlying != null)
                    image_layout_transition(
                        command.vkCommandBuffer,
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
                        command.vkCommandBuffer,
                        srcImage,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        srcRange
                    )

                val region = VkImageCopy.calloc(1, stack)
                region[0].srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(eyeIndex)
                    .layerCount(1)
                region[0].dstSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
                region[0].srcOffset().set(0, 0, 0)
                region[0].dstOffset().set(eyeIndex * eyeWidth, 0, 0)
                region[0].extent().set(eyeWidth, eyeHeight, 1)

                org.lwjgl.vulkan.VK10.vkCmdCopyImage(
                    command.vkCommandBuffer,
                    srcImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region
                )

                if (srcUnderlying != null)
                    image_layout_transition(
                        command.vkCommandBuffer,
                        srcUnderlying,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_ACCESS_2_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        srcRange
                    )
                else
                    image_layout_transition(
                        command.vkCommandBuffer,
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

            copyLayer(0)
            copyLayer(1)

            if (dstUnderlying != null) {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstUnderlying,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    dstRange
                )
            } else {
                image_layout_transition(
                    command.vkCommandBuffer,
                    dstImage,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    dstRange
                )
            }
        }
    }
}

/**
 * Minimal stereo camera: offsets along the right vector by +/-IPD/2, converges on a focus point,
 * and supports asymmetric frustum offsets through the base projection.
 */
class StereoCamera(val base: Camera = Camera()) {
    var ipd: Float = 0.064f
    /**
     * Distance multiplier for the stereo convergence point, measured from the
     * base camera toward its target along the forward vector.
     */
    var convergenceDistance: Float = 1f
    var asymmetricFrustumOffset: Double = 0.0

    data class EyePose(val eye: Camera)

    fun eyes(): Pair<EyePose, EyePose> {
        val fwd = base.forward
        val right = base.right
        val up = base.up
        val focus = Vector3f(base.at).fma(convergenceDistance * base.at.distance(base.target), fwd)

        val half = ipd * 0.5f
        val leftEyePos = Vector3f(base.at).fma(-half, right)
        val rightEyePos = Vector3f(base.at).fma(+half, right)

        val leftCam = Camera(base.renderer)
        leftCam.lookAt(leftEyePos, focus, up)

        val rightCam = Camera(base.renderer)
        rightCam.lookAt(rightEyePos, focus, up)

        return EyePose(leftCam) to EyePose(rightCam)
    }

    fun eyeViewMatrices(): List<Matrix4f> {
        val (l, r) = eyes()
        return listOf(Matrix4f(l.eye.modelView()), Matrix4f(r.eye.modelView()))
    }

    fun projectionMatrices(): List<Matrix4f> {
        val p = base.p
        val prevOx = p.ox
        val prevOy = p.oy
        try {
            p.ox = -asymmetricFrustumOffset
            val left = Matrix4f(p.evaluateUniform())
            p.ox = asymmetricFrustumOffset
            val right = Matrix4f(p.evaluateUniform())
            return listOf(left, right)
        } finally {
            p.ox = prevOx
            p.oy = prevOy
        }
    }
}

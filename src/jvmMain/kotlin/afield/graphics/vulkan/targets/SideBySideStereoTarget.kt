package afield.graphics.vulkan.targets

import afield.graphics.vulkan.device.vkLayout
import afield.math.linalg.Matrix4f
import afield.math.linalg.Quaternionf

import afield.app.protocol.Rect
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.render.ModulePointer
import afield.document.context.fromContext
import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand
import afield.graphics.texture.HasImageView
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.util.DownResult
import afield.graphics.vulkan.util.MouseEvent
import afield.graphics.vulkan.util.MouseEvents
import afield.graphics.vulkan.util.Options
import afield.data.collections.times
import afield.math.autodiff.times
import afield.math.linalg.times
import afield.math.units.times
import afield.math.linalg.vec
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import afield.math.linalg.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkImageCopy

/**
 * Stereo rendering into one packed image: [SideBySideStereoTarget] draws the scene once per eye
 * and lays the two views side by side, left eye on the left half.
 *
 * With an [OpenXRHmdTarget] attached, the per-eye view and projection matrices come from the
 * headset and the packed image can feed its swapchains; without one, an internal stereo camera
 * supplies them, so the same box code renders a stereo pair on a desktop window.
 */

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
    /** The renderer whose device owns the eye passes and the packed image. Found in the box graph by default. */
    val renderer: Renderer = fromContext(),
    /** Width of one eye's pass in pixels. Defaults to half the swapchain's, so the pair fills the window. */
    val eyeWidth: Int = renderer.swapChain.swapChainExtent.width() / 2,
    /** Height of one eye's pass in pixels. Defaults to the swapchain's full height. */
    val eyeHeight: Int = renderer.swapChain.swapChainExtent.height(),
    /**
     * The headset to take per-eye view and projection matrices from, and to hand [packed] to. Null
     * renders the same stereo pair from [stereoCamera] instead, which is the desktop-window path.
     */
    var openXR: OpenXRHmdTarget? = null
) : afield.graphics.vulkan.Target, HasImageView {

    /** The camera the eye passes use when neither [poseSource] nor [openXR] supplies a head. */
    val stereoCamera = StereoCamera(Camera(renderer).apply {
        // The target resizes this projection per eye; window auto-sizing would overwrite it.
        p.auto = {}
    })

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

    /**
     * The finished pair, `2 * eyeWidth` by [eyeHeight], left eye in the left half. This is what a
     * desktop window samples and what [OpenXRHmdTarget.copySideBySideToEyes] copies halves out of;
     * it carries both transfer usages so either path works.
     */
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

    /**
     * The packed pair, so a [SideBySideStereoTarget] is itself usable anywhere a [HasImageView] is —
     * a desktop window, a postprocess pass, an OpenXR side-by-side source.
     */
    override val imageView = packed.imageView

    /**
     * Which eye the modules are being drawn for right now: 0 left, 1 right, -1 between the two
     * passes. Module code reads this to pick per-eye data, and it is the eye
     * [currentEyeViewMatrix] and [currentEyeProjectionMatrix] answer for.
     */
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
     * Identity of the pose the most recent frame was rendered from, or -1 before either the
     * remote source or OpenXR has located a pose.
     *
     * This is the frame/pose association: whatever ships the rendered image onward can say which pose
     * it was drawn for, so a later stage can reproject it instead of assuming it is current.
     */
    var renderedPoseFrameId: Long = -1L
        private set

    /** Predicted display time for [renderedPoseFrameId], in seconds in the selected source's clock. */
    var renderedPoseDisplayTime: Double = 0.0
        private set

    // Simple mock controller fallback when OpenXR is absent.
    private val mockController = ControllerState(active = true, poseValid = true, position = vec(0.0, 0.0, 0.0))
    private var mouseControllerPos: Vector2f? = null
    private var mouseControllerDistance: Float = 1f

    init {
        // This target renders its eyes once each, then packs them, before the main swapchain.
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
        val xr = openXR?.takeIf { it.hasEyeViews }
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
        val xr = openXR?.takeIf { it.hasEyeViews }
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
        if (xr != null) {
            xr.controllerState(hand, out)
            if (out.active && out.poseValid) return out
        }

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

        val halfFovRad = (cam.p.fov * 0.5).toFloat() // Projection.fov is radians; see Options.fov
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
        if (poseSource == null) {
            renderedPoseFrameId = openXR?.locatedViewFrameId ?: -1L
            renderedPoseDisplayTime = (openXR?.locatedViewDisplayTime ?: 0L) / 1_000_000_000.0
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
        // Let each RTT track the barriers as well as its layout. In particular the packed
        // image starts UNDEFINED, and descriptor preparation must see it initialized after copy.
        packed.recordColorTransferDestination(command) { dstImage ->
            fun copyEye(source: RenderToTexture, eyeIndex: Int) {
                source.recordColorTransferSource(command) { srcImage ->
                    recordStereoEyeCopy(command, srcImage, dstImage, eyeIndex, 0, eyeWidth, eyeHeight)
                }
            }
            copyEye(left, 0)
            copyEye(right, 1)
        }
    }

}

/** Copy one source layer into its eye's half; the RTT owners bracket this with layout barriers. */
private fun recordStereoEyeCopy(
    command: CommandBuffer, source: Long, destination: Long,
    eyeIndex: Int, sourceLayer: Int, eyeWidth: Int, eyeHeight: Int
) {
    MemoryStack.stackPush().use { stack ->
        val region = VkImageCopy.calloc(1, stack)
        region[0].srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0).baseArrayLayer(sourceLayer).layerCount(1)
        region[0].dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0).baseArrayLayer(0).layerCount(1)
        region[0].srcOffset().set(0, 0, 0)
        region[0].dstOffset().set(eyeIndex * eyeWidth, 0, 0)
        region[0].extent().set(eyeWidth, eyeHeight, 1)
        vkCmdCopyImage(command.vkCommandBuffer, source, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            destination, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL), region)
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
    /**
     * The renderer whose device owns the layered pass. Its device must report `supportsMultiview`,
     * and construction fails on macOS outright.
     */
    val renderer: Renderer = fromContext(),
    /** Width of one eye's layer in pixels. Defaults to half the swapchain's. */
    val eyeWidth: Int = renderer.swapChain.swapChainExtent.width() / 2,
    /** Height of one eye's layer in pixels. Defaults to the swapchain's full height. */
    val eyeHeight: Int = renderer.swapChain.swapChainExtent.height(),
    /**
     * The headset to take per-eye matrices from, and to hand [packed] to. Null renders both eyes
     * from [stereoCamera] instead.
     */
    var openXR: OpenXRHmdTarget? = null
) : afield.graphics.vulkan.Target, HasImageView {

    /** The camera both views are derived from when [openXR] is unset. */
    val stereoCamera = StereoCamera(Camera(renderer).apply {
        // The target resizes this projection per eye; window auto-sizing would overwrite it.
        p.auto = {}
    })

    /**
     * The two-layer pass both eyes are drawn into at once, with `viewMask = 0b11`. Shaders select
     * their eye from `gl_ViewIndex` rather than from a per-pass index.
     */
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

    /**
     * [layered]'s two layers copied side by side, `2 * eyeWidth` by [eyeHeight], layer 0 in the left
     * half. The copy is what lets the existing OpenXR side-by-side path consume a multiview render
     * unchanged.
     */
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

    /**
     * The packed pair, so a [MultiviewSideBySideStereoTarget] is itself usable anywhere a
     * [HasImageView] is: the layers are already copied into it by then.
     */
    override val imageView = packed.imageView

    /** The two-layer pass itself, for code that would rather sample the layers than the packed copy. */
    val layeredImageView: HasImageView
        get() = layered

    /**
     * Always -1 here. A multiview pass draws both eyes in one go, so there is no "current eye" to
     * report; the field exists to mirror [SideBySideStereoTarget], and the current-eye matrix
     * accessors consequently answer for the left eye.
     */
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

        val halfFovRad = (cam.p.fov * 0.5).toFloat() // Projection.fov is radians; see Options.fov
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
        packed.recordColorTransferDestination(command) { dstImage ->
            layered.recordColorTransferSource(command) { srcImage ->
                for (eyeIndex in 0..1) {
                    recordStereoEyeCopy(command, srcImage, dstImage, eyeIndex, eyeIndex, eyeWidth, eyeHeight)
                }
            }
        }
    }

}

/**
 * Minimal stereo camera: offsets along the right vector by +/-IPD/2, converges on a focus point,
 * and supports asymmetric frustum offsets through the base projection.
 */
class StereoCamera(val base: Camera = Camera()) {
    /**
     * Interpupillary distance in world units, split half to each side of [base] along its right
     * vector. The default 0.064 is the usual adult average in metres.
     */
    var ipd: Float = 0.064f
    /**
     * Distance multiplier for the stereo convergence point, measured from the
     * base camera toward its target along the forward vector.
     */
    var convergenceDistance: Float = 1f

    /**
     * Shifts each eye's principal point instead of toeing its view in: the left eye gets
     * `-asymmetricFrustumOffset` and the right `+asymmetricFrustumOffset` as the base projection's
     * `ox`, so the units are half-widths of what that projection frames at its match depth. Zero
     * leaves both frusta centred, and convergence is then [convergenceDistance]'s business alone.
     */
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

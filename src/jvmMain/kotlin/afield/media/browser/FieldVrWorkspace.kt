package afield.media.browser

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand
import afield.graphics.vulkan.targets.SideBySideStereoTarget
import afield.math.linalg.Quaternionf
import afield.math.linalg.Vector3f
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live Field document in a world-locked Chromium panel. Construct after the renderer's initial
 * frame, then drive the renderer normally. The desktop preview uses the same page and input path.
 * The supplied URL should come from [FieldUiBrowserTexture.fieldDocumentUrl].
 */
class FieldVrWorkspace(
    val renderer: Renderer,
    val url: String,
    width: Int = 2560,
    height: Int = 1600,
    readyBoxUid: String? = null,
    val widthMeters: Float = 2.0f,
    val distanceMeters: Float = 1.35f,
    cpuBitmapFallback: Boolean = FieldUiBrowserTexture.cpuBitmapFallbackRequired,
    headsetInput: Boolean = defaultHeadsetInput(renderer.openXRHmdTarget != null),
    pairedInput: Boolean = defaultPairedInput(),
) : AutoCloseable {
    companion object {
        /** ALVR has no WiVRn HID devices; its launcher selects the desktop focus path. */
        internal fun defaultHeadsetInput(
            openXR: Boolean,
            mode: String? = System.getenv("FIELD_VR_INPUT"),
            osName: String = System.getProperty("os.name"),
        ): Boolean = when (mode?.trim()?.lowercase()) {
            null, "", "auto" -> openXR && osName.startsWith("Linux")
            "desktop", "paired" -> false
            "wivrn" -> {
                require(openXR && osName.startsWith("Linux")) { "WiVRn input requires Linux/OpenXR" }
                true
            }
            else -> error("FIELD_VR_INPUT must be auto, desktop, wivrn or paired (got '$mode')")
        }

        /**
         * `FIELD_VR_INPUT=paired`: keyboard and mouse sent by a paired headset app (the patched visionOS
         * ALVR client) to a port this panel opens; see [PairedInputSource]. Never chosen automatically.
         */
        internal fun defaultPairedInput(
            mode: String? = System.getenv("FIELD_VR_INPUT"),
            osName: String = System.getProperty("os.name"),
        ): Boolean = (mode?.trim()?.lowercase() == "paired").also {
            // The layout path is the same XKB keymap WiVRn input uses.
            if (it) require(osName.startsWith("Linux")) { "Paired headset input requires Linux (libxkbcommon)" }
        }
    }

    private val closed = AtomicBoolean(false)
    private val recenterRequested = AtomicBoolean(false)
    private val keyboardFocusRequested = AtomicBoolean(false)
    private var previousTime = System.nanoTime()
    private var installedGeneration = -1L
    private var desktopPointer = renderer.openXRHmdTarget == null
    private var headsetPointer = false
    private var reportedInputStatus: String? = null
    var headsetDevices: WiVRnBrowserInput? = null
        private set
    private var previousPresses = emptySet<String>()
    private var previousGrips = emptySet<String>()
    private val rayAnchors = mutableMapOf<String, Pair<Vector3f, Vector3f>>()
    @Volatile var keyboardReady = false
        private set
    @Volatile var keyboardError: String? = null
        private set

    init {
        require(widthMeters > 0f && distanceMeters > 0f) { "Panel dimensions must be positive" }
        check(renderer.openXRHmdTarget?.hasEyeViews != false) {
            "Render the initial OpenXR frame before opening the VR workspace"
        }
    }

    val browser = FieldUiBrowserTexture(renderer, url, width, height,
        readyBoxUid = readyBoxUid, cpuBitmapFallback = cpuBitmapFallback)
    val stereo: SideBySideStereoTarget
    val panel: PuppetBrowserVrPanel
    val input: PuppetBrowserVrInput
    val grab: PuppetBrowserVrGrab
    val keyboard = FieldVrKeyboard(browser,
        recenter = { recenterRequested.set(true) },
        focusKeyboard = { keyboardFocusRequested.set(true) })
    val companion: PuppetBrowserWindowPanel

    init {
        val xr = renderer.openXRHmdTarget
        // Insert before sbs-pack: upload and input belong to the frame, once for both eyes.
        renderer.renderList.preTargets["field-vr-browser"] = Target { command, _ ->
            browser.prepareSampledImagesFor(command)
            update()
        }
        val eye = xr?.let { renderer.runOnQueueThread { it.sideBySideRTTParameters() } }
        stereo = SideBySideStereoTarget(renderer, eyeWidth = eye?.width?.div(2) ?: 1200,
            eyeHeight = eye?.height ?: 1200, openXR = xr)
        panel = PuppetBrowserVrPanel(renderer, stereo, browser, widthMeters, distanceMeters,
            name = "field-vr-ide", automaticInteraction = false)
        panel.build()
        input = PuppetBrowserVrInput(panel.panel)
        grab = PuppetBrowserVrGrab(panel.panel)
        stereo.stereoCamera.base.lookAt(Vector3f(), Vector3f(0f, 0f, -1f))
        recenter()
        companion = PuppetBrowserWindowPanel(renderer, browser, name = "field-vr-companion")
        companion.input.focusFollowsWindow = true
        companion.input.onPointerActivity = {
            input.cancel()
            grab.cancel()
            headsetDevices?.releasePointer()
            headsetPointer = false
            desktopPointer = true
            rayAnchors.clear()
        }
        companion.build()
        require(!(headsetInput && pairedInput)) { "Choose WiVRn or paired headset input, not both" }
        val pointerActivity = {
            if (!headsetPointer) {
                val hit = panel.lastHit
                input.cancel()
                grab.cancel()
                companion.input.releasePointer()
                if (hit?.onPanel == true) headsetDevices?.placeCursor(hit.u, hit.v)
                headsetPointer = true
                desktopPointer = false
                rayAnchors.clear()
            }
        }
        val otherModifiers = { companion.input.uv.modifiers }
        if (headsetInput) headsetDevices = WiVRnBrowserInput(browser, browser.spec,
            pointerActivity = pointerActivity, otherModifiers = otherModifiers)
        else if (pairedInput) headsetDevices = WiVRnBrowserInput(browser, browser.spec,
            PairedInputSource.fromEnvironment(), pointerActivity, otherModifiers)
    }

    /** Keep the panel in the room; call this to bring it in front of the current head position. */
    fun recenter() {
        // A toolbar recenter ends any current attachment before setting a new room transform.
        grab.cancel()
        val eye = Vector3f()
        val orientation = Quaternionf()
        val direction = Vector3f(0f, 0f, -1f)
        if (renderer.openXRHmdTarget?.headPose(eye, orientation) == true) orientation.transform(direction)
        // An upright panel remains usable after glancing down at the keyboard or tilting the head.
        direction.y = 0f
        if (direction.lengthSquared() < 1e-6f) direction.set(0f, 0f, -1f)
        direction.normalize()
        val scale = panel.panel.model.transformDirection(Vector3f(1f, 0f, 0f)).length()
        panel.panel.faceToward(Vector3f(eye).fma(distanceMeters, direction), eye).scale(scale)
    }

    private fun update() {
        if (closed.get()) return
        if (keyboardFocusRequested.getAndSet(false)) {
            if (headsetDevices != null) browser.focus() else focusPhysicalKeyboard()
        }
        if (recenterRequested.getAndSet(false)) recenter()
        val now = System.nanoTime()
        val elapsed = (now - previousTime) / 1e9
        previousTime = now
        headsetDevices?.poll(deliver = browser.pageReady && !browser.state.loading)
        val xr = renderer.openXRHmdTarget
        val pointers = if (xr == null) emptyList() else {
            // Use raw runtime states: the desktop mock must never appear when tracking drops.
            listOf(Hand.RIGHT, Hand.LEFT).mapNotNull { hand ->
                input.controller("$hand-controller", xr.controllerState(hand), elapsed)
                    ?: input.hand("$hand-hand", xr.handState(hand))
            }
        }
        val worldPress = pointers.any {
            ((it.pressed && it.id !in previousPresses) || (it.gripPressed && it.id !in previousGrips)) &&
                panel.panel.raycast(it.origin, it.direction)?.onPanel == true
        }
        val worldMotion = pointers.any { pointer ->
            val anchor = rayAnchors[pointer.id]
            val moved = anchor != null && (anchor.first.distance(pointer.origin) > 0.02f ||
                anchor.second.dot(pointer.direction) < 0.9994f)
            if (anchor == null || moved) rayAnchors[pointer.id] = Vector3f(pointer.origin) to Vector3f(pointer.direction)
            moved && panel.panel.raycast(pointer.origin, pointer.direction)?.onPanel == true
        }
        // A moving controller may resume hover, but cannot interrupt a physical mouse drag.
        if ((worldPress || (worldMotion && companion.input.uv.buttons == 0 && headsetDevices?.uv?.buttons.orZero() == 0)) &&
            (desktopPointer || headsetPointer)) {
            companion.input.releasePointer()
            headsetDevices?.releasePointer()
            desktopPointer = false
            headsetPointer = false
        }
        previousPresses = pointers.filter { it.pressed }.map { it.id }.toSet()
        previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
        val worldPointer = !desktopPointer && !headsetPointer
        val dragging = grab.update(pointers, enabled = worldPointer)
        // Physical Shift/Ctrl also apply to controller clicks (selection, links and canvas tools).
        panel.panel.uv.modifiers = companion.input.uv.modifiers + (headsetDevices?.modifiers ?: emptySet())
        val hit = input.update(pointers, enabled = worldPointer && !dragging)
        val pageCursor = if (headsetPointer) headsetDevices?.uv?.position else if (desktopPointer) companion.input.lastPointerUv else null
        val cursor = pageCursor?.let { (u, v) ->
            val world = panel.panel.model.transformPosition(Vector3f(
                ((u - 0.5) * 2 * panel.panel.halfWidth).toFloat(),
                ((0.5 - v) * 2 * panel.panel.halfHeight).toFloat(), 0f))
            PuppetBrowserPanel.Hit(world, u, v, u in 0.0..1.0 && v in 0.0..1.0)
        } ?: hit
        val pressed = when {
            headsetPointer -> headsetDevices?.uv?.buttons.orZero() != 0
            desktopPointer -> companion.input.uv.buttons != 0
            else -> panel.panel.uv.buttons != 0
        }
        panel.showPointer(cursor, pressed)
        val generation = browser.state.navigationGeneration
        if (browser.pageReady && !browser.state.loading && installedGeneration != generation) {
            installedGeneration = generation
            keyboardReady = false
            keyboard.install().whenComplete { _, error ->
                keyboardError = error?.message
                reportedInputStatus = null
                keyboardReady = error == null
            }
        }
        val status = headsetDevices?.status ?: "Desktop companion keyboard / mouse"
        if (keyboardReady && status != reportedInputStatus) {
            reportedInputStatus = status
            keyboard.reportInputStatus(status)
        }
    }

    private fun focusPhysicalKeyboard() {
        org.lwjgl.glfw.GLFW.glfwFocusWindow(renderer.window.windowHandle)
        companion.input.takeFocus()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        renderer.renderList.preTargets.remove("field-vr-browser")
        renderer.renderList.preTargets.remove("sbs-pack", stereo)
        input.cancel()
        grab.cancel()
        headsetDevices?.close()
        keyboard.close()
        renderer.runOnQueueThread { org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(renderer.device.vkDevice) }
        companion.close()
        panel.cleanup()
        browser.close()
    }

    private fun Int?.orZero(): Int = this ?: 0
}

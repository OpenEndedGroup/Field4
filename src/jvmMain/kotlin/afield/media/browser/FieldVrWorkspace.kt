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
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val recenterRequested = AtomicBoolean(false)
    private val keyboardFocusRequested = AtomicBoolean(false)
    private var previousTime = System.nanoTime()
    private var installedGeneration = -1L
    private var desktopPointer = renderer.openXRHmdTarget == null
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
            desktopPointer = true
            rayAnchors.clear()
        }
        companion.build()
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
        panel.panel.faceToward(Vector3f(eye).fma(distanceMeters, direction), eye)
    }

    private fun update() {
        if (closed.get()) return
        if (keyboardFocusRequested.getAndSet(false)) focusPhysicalKeyboard()
        if (recenterRequested.getAndSet(false)) recenter()
        val now = System.nanoTime()
        val elapsed = (now - previousTime) / 1e9
        previousTime = now
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
        if ((worldPress || (worldMotion && companion.input.uv.buttons == 0)) && desktopPointer) {
            companion.input.releasePointer()
            desktopPointer = false
        }
        previousPresses = pointers.filter { it.pressed }.map { it.id }.toSet()
        previousGrips = pointers.filter { it.gripPressed }.map { it.id }.toSet()
        val dragging = !desktopPointer && grab.update(pointers)
        // Physical Shift/Ctrl also apply to controller clicks (selection, links and canvas tools).
        panel.panel.uv.modifiers = companion.input.uv.modifiers
        val hit = input.update(pointers, enabled = !desktopPointer && !dragging)
        val cursor = if (desktopPointer) companion.input.lastPointerUv?.let { (u, v) ->
            val world = panel.panel.model.transformPosition(Vector3f(
                ((u - 0.5) * 2 * panel.panel.halfWidth).toFloat(),
                ((0.5 - v) * 2 * panel.panel.halfHeight).toFloat(), 0f))
            PuppetBrowserPanel.Hit(world, u, v, u in 0.0..1.0 && v in 0.0..1.0)
        } else hit
        panel.showPointer(cursor, if (desktopPointer) companion.input.uv.buttons != 0 else panel.panel.uv.buttons != 0)
        val generation = browser.state.navigationGeneration
        if (browser.pageReady && !browser.state.loading && installedGeneration != generation) {
            installedGeneration = generation
            keyboardReady = false
            keyboard.install().whenComplete { _, error ->
                keyboardError = error?.message
                keyboardReady = error == null
            }
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
        keyboard.close()
        renderer.runOnQueueThread { org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(renderer.device.vkDevice) }
        companion.close()
        panel.cleanup()
        browser.close()
    }
}

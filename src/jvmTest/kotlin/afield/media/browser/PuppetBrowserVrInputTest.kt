package afield.media.browser

import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandTrackingState
import afield.math.linalg.Vector3f
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class PuppetBrowserVrInputTest {
    private val events = mutableListOf<BrowserPointerEvent>()
    private val keys = mutableListOf<BrowserKeyEvent>()
    private val text = mutableListOf<String>()
    private val browser = Proxy.newProxyInstance(PuppetBrowser::class.java.classLoader,
        arrayOf(PuppetBrowser::class.java)) { _, method, args ->
        when (method.name) {
            "getSpec" -> ExternalBrowserSurfaceSpec(1920, 1200)
            "pointer" -> { events += args[0] as BrowserPointerEvent; Unit }
            "key" -> { keys += args[0] as BrowserKeyEvent; Unit }
            "insertText" -> { text += args[0] as String; Unit }
            "focus", "flushInput" -> CompletableFuture.completedFuture(Unit)
            "onPageRequest" -> ({ Unit })
            else -> error("Unexpected browser call: ${method.name}")
        }
    } as PuppetBrowser
    private val panel = PuppetBrowserPanel(browser, 1f, 0.625f).also {
        it.faceToward(Vector3f(0f, 0f, -2f), Vector3f())
    }
    private val input = PuppetBrowserVrInput(panel)
    private fun ray(id: String, down: Boolean = false, x: Float = 0f) =
        BrowserVrPointer(id, Vector3f(), Vector3f(x, 0f, -2f).normalize(), down)

    @Test fun `either hand can take over but hover cannot steal a drag`() {
        input.update(listOf(ray("right"), ray("left")))
        input.update(listOf(ray("right"), ray("left", true)))
        assertEquals("left", input.activePointer)
        input.update(listOf(ray("right", true), ray("left", true, 3f)))
        assertEquals("left", input.activePointer)
        input.update(listOf(ray("right", true), ray("left", false, 3f)))
        assertEquals(listOf(BrowserPointerEventType.DOWN, BrowserPointerEventType.UP),
            events.filter { it.type != BrowserPointerEventType.MOVE }.map { it.type })
        assertEquals(0, panel.uv.buttons)
    }

    @Test fun `tracking loss releases a pressed button exactly once`() {
        input.update(listOf(ray("right", true)))
        input.update(emptyList())
        input.update(emptyList())
        assertEquals(1, events.count { it.type == BrowserPointerEventType.UP })
        assertEquals(0, panel.uv.buttons)
        assertNull(input.activePointer)
    }

    @Test fun `held trigger moving onto panel does not create a click`() {
        input.update(listOf(ray("right", true, 3f)))
        input.update(listOf(ray("right", true)))
        assertTrue(events.none { it.type == BrowserPointerEventType.DOWN })
        input.update(listOf(ray("right")))
        input.update(listOf(ray("right", true)))
        assertEquals(1, events.count { it.type == BrowserPointerEventType.DOWN })
    }

    @Test fun `controller aim points down negative Z and scrolling scales with time`() {
        val state = ControllerState(active = true, poseValid = true, thumbstickY = 0.5f)
        val pointer = assertNotNull(input.controller("right", state, 1.0 / 120))
        assertTrue(assertNotNull(panel.raycast(pointer.origin, pointer.direction)).onPanel)
        assertEquals(-10.0 * Math.pow((0.5 - 0.12) / 0.88, 2.0), pointer.scrollY, 1e-6)
        state.poseValid = false
        assertNull(input.controller("right", state, 0.01))
    }

    @Test fun `stick has a deadzone two analog axes and no tail after release`() {
        val state = ControllerState(active = true, poseValid = true, thumbstickX = 1f, thumbstickY = 0.1f)
        val horizontal = assertNotNull(input.controller("right", state, 1.0 / 120))
        assertEquals(10.0, horizontal.scrollX, 1e-6)
        assertEquals(0.0, horizontal.scrollY, 1e-9)
        input.update(listOf(horizontal))
        assertEquals(10.0, events.last { it.type == BrowserPointerEventType.WHEEL }.deltaX, 1e-6)
        state.thumbstickX = 0f
        val stopped = assertNotNull(input.controller("right", state, 1.0 / 120))
        assertEquals(0.0, stopped.scrollX, 1e-9)
        input.update(listOf(stopped))
        assertEquals(1, events.count { it.type == BrowserPointerEventType.WHEEL })
        state.thumbstickY = 1f
        val full = assertNotNull(input.controller("right", state, 1.0 / 120))
        state.thumbstickY = 0.5f
        assertTrue(kotlin.math.abs(assertNotNull(input.controller("right", state, 1.0 / 120)).scrollY) < kotlin.math.abs(full.scrollY) / 2)
    }

    @Test fun `controller primary face button clicks and releases the panel`() {
        val state = ControllerState(active = true, poseValid = true, aPressed = true)
        input.update(listOf(assertNotNull(input.controller("right", state, 0.01))))
        state.aPressed = false
        input.update(listOf(assertNotNull(input.controller("right", state, 0.01))))
        assertEquals(listOf(BrowserPointerEventType.DOWN, BrowserPointerEventType.UP),
            events.filter { it.type != BrowserPointerEventType.MOVE }.map { it.type })
    }

    @Test fun `pinch has hysteresis and rejects missing joints`() {
        val state = HandTrackingState().also { it.isActive = true }
        for (joint in listOf(HandJoint.WRIST, HandJoint.INDEX_PROXIMAL, HandJoint.THUMB_TIP, HandJoint.INDEX_TIP))
            state[joint].positionValid = true
        state[HandJoint.INDEX_PROXIMAL].position.set(0f, 0f, -0.1f)
        state.pinchStrength = 0.7f
        assertFalse(assertNotNull(input.hand("left", state)).pressed)
        state.pinchStrength = 0.9f
        assertTrue(assertNotNull(input.hand("left", state)).pressed)
        state.pinchStrength = 0.65f
        assertTrue(assertNotNull(input.hand("left", state)).pressed)
        state.pinchStrength = 0.4f
        assertFalse(assertNotNull(input.hand("left", state)).pressed)
        state[HandJoint.INDEX_TIP].positionValid = false
        assertNull(input.hand("left", state))
    }

    @Test fun `run and selection chords carry modifiers and release keys`() {
        val keyboard = FieldVrKeyboard(browser, recenter = {})
        keyboard.press("Enter", alt = true)
        keyboard.press("ArrowLeft", shift = true)
        assertEquals(listOf(BrowserKeyEventType.RAW_KEY_DOWN, BrowserKeyEventType.KEY_UP,
            BrowserKeyEventType.RAW_KEY_DOWN, BrowserKeyEventType.KEY_UP), keys.map { it.type })
        assertEquals(setOf(BrowserModifier.ALT), keys[0].modifiers)
        assertEquals(setOf(BrowserModifier.SHIFT), keys[2].modifiers)
        assertFailsWith<IllegalArgumentException> { keyboard.press("made-up") }
        keyboard.close()
    }

    @Test fun `grip preserves offset moves and rotates the panel then leaves it on release`() {
        val grab = PuppetBrowserVrGrab(panel)
        val initial = afield.math.linalg.Matrix4f(panel.model)
        val down = ray("left").copy(gripPressed = true)
        assertTrue(grab.update(listOf(down)))
        for (i in 0 until 16) assertEquals(initial[i], panel.model[i], 1e-6f, "Starting a grab must not snap the panel")
        val moved = down.copy(origin = Vector3f(0.5f, 0.2f, 0f),
            orientation = afield.math.linalg.Quaternionf().rotateY(0.3f))
        grab.update(listOf(moved, ray("right")))
        assertEquals("left", grab.owner)
        val expected = afield.math.linalg.Matrix4f().translate(moved.origin.x, moved.origin.y, moved.origin.z)
            .rotate(moved.orientation).mul(initial)
        for (i in 0 until 16) assertEquals(expected[i], panel.model[i], 1e-5f)
        grab.update(listOf(moved.copy(gripPressed = false)))
        assertNull(grab.owner)
        for (i in 0 until 16) assertEquals(expected[i], panel.model[i], 1e-5f, "Dropping must preserve the room transform")
    }

    private fun grip(id: String, x: Float, down: Boolean = true) = BrowserVrPointer(
        id, Vector3f(x, 0f, 0f), Vector3f(-x, 0f, -2f).normalize(), false, gripPressed = down)

    @Test fun `two grips resize without snapping changing aspect or moving the panel away`() {
        val grab = PuppetBrowserVrGrab(panel)
        val initial = afield.math.linalg.Matrix4f(panel.model)
        grab.update(listOf(grip("left", -0.2f), grip("right", 0.2f, false)))
        grab.update(listOf(grip("left", -0.2f), grip("right", 0.2f)))
        for (i in 0 until 16) assertEquals(initial[i], panel.model[i], 1e-6f, "Second grip must not snap")
        grab.update(listOf(grip("left", -0.4f), grip("right", 0.4f)))
        assertEquals(2f, panel.model.transformDirection(Vector3f(1f, 0f, 0f)).length(), 1e-5f)
        assertEquals(2f, panel.model.transformDirection(Vector3f(0f, 1f, 0f)).length(), 1e-5f)
        assertEquals(-2f, panel.model.getTranslation(Vector3f()).z, 1e-5f, "Growing the screen must not move it away")
        val hit = assertNotNull(panel.raycast(Vector3f(), Vector3f(0.5f, 0f, -2f)))
        assertTrue(hit.onPanel)
        assertEquals(0.625, hit.u, 1e-6, "Hit testing must follow the resized screen")
        grab.update(listOf(grip("left", -0.2f), grip("right", 0.6f)))
        assertEquals(0.2f, panel.model.getTranslation(Vector3f()).x, 1e-5f)
    }

    @Test fun `either hand can release a resize and the remaining hand keeps its size without jumping`() {
        for (releaseLeft in listOf(false, true)) {
            panel.faceToward(Vector3f(0f, 0f, -2f), Vector3f())
            val grab = PuppetBrowserVrGrab(panel)
            grab.update(listOf(grip("left", -0.2f), grip("right", 0.2f)))
            grab.update(listOf(grip("left", -0.4f), grip("right", 0.4f)))
            val resized = afield.math.linalg.Matrix4f(panel.model)
            grab.update(listOf(grip("left", -0.4f, !releaseLeft), grip("right", 0.4f, releaseLeft)))
            assertEquals(if (releaseLeft) "right" else "left", grab.owner)
            for (i in 0 until 16) assertEquals(resized[i], panel.model[i], 1e-5f)
            val survivor = if (releaseLeft) grip("right", 0.5f) else grip("left", -0.3f)
            grab.update(listOf(survivor))
            assertEquals(0.1f, panel.model.getTranslation(Vector3f()).x, 1e-5f)
            assertEquals(2f, panel.model.transformDirection(Vector3f(1f, 0f, 0f)).length(), 1e-5f)
            grab.update(emptyList())
            assertNull(grab.owner)
        }
    }

    @Test fun `resize clamps its size rejects coincident starts and safely loses tracking`() {
        val grab = PuppetBrowserVrGrab(panel, minimumWidthMeters = 0.5f, maximumWidthMeters = 3f)
        grab.update(listOf(grip("left", -0.2f), grip("right", 0.2f)))
        grab.update(listOf(grip("left", -10f), grip("right", 10f)))
        assertEquals(3f, panel.model.transformDirection(Vector3f(2f, 0f, 0f)).length(), 1e-5f)
        grab.update(listOf(grip("left", 0f), grip("right", 0f)))
        assertEquals(0.5f, panel.model.transformDirection(Vector3f(2f, 0f, 0f)).length(), 1e-5f)
        val final = afield.math.linalg.Matrix4f(panel.model)
        assertFalse(grab.update(emptyList()))
        for (i in 0 until 16) assertEquals(final[i], panel.model[i], 1e-5f)
        grab.update(listOf(grip("left", 0f), grip("right", 0f)))
        grab.update(listOf(grip("left", 0f), grip("right", 0f)))
        assertTrue(panel.model[0].isFinite())
        grab.cancel()
        assertNull(grab.owner)
    }

    @Test fun `tracking loss ends a grab and moving never sends browser clicks`() {
        val grab = PuppetBrowserVrGrab(panel)
        val pointer = ray("right", true).copy(gripPressed = true)
        input.update(listOf(pointer), enabled = !grab.update(listOf(pointer)))
        assertTrue(events.none { it.type == BrowserPointerEventType.DOWN })
        assertFalse(grab.update(emptyList()))
        input.update(listOf(pointer.copy(gripPressed = false)))
        assertTrue(events.none { it.type == BrowserPointerEventType.DOWN }, "Held trigger after a grab is not a new click")
    }

    @Test fun `a grip held during mouse ownership does not grab on hover takeover`() {
        val grab = PuppetBrowserVrGrab(panel)
        val held = ray("right").copy(gripPressed = true)
        assertFalse(grab.update(listOf(held), enabled = false))
        assertFalse(grab.update(listOf(held)))
        grab.update(listOf(held.copy(gripPressed = false)))
        assertTrue(grab.update(listOf(held)))
    }
}

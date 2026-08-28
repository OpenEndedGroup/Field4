package afield.four.browser

import afield.four.util.Window
import afield.four.targets.OpenXRHmdTarget.ControllerState
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.abs

/** Maps normalized panel UVs into the browser's CSS-pixel input coordinate system. */
class PuppetBrowserUvInput(val browser: PuppetBrowser) {
    val cssWidth: Double get() = browser.spec.width / browser.spec.deviceScaleFactor
    val cssHeight: Double get() = browser.spec.height / browser.spec.deviceScaleFactor

    var modifiers: Set<BrowserModifier> = emptySet()
    var buttons: Int = 0
        private set

    private var lastU = 0.5
    private var lastV = 0.5
    private var lastClickNanos = 0L
    private var lastClickU = Double.NaN
    private var lastClickV = Double.NaN
    private var clickCount = 0

    fun move(u: Double, v: Double, pointerType: BrowserPointerType = BrowserPointerType.MOUSE) {
        lastU = u
        lastV = v
        browser.pointer(pointerEvent(BrowserPointerEventType.MOVE, u, v, pointerType = pointerType))
    }

    fun down(
        button: BrowserMouseButton = BrowserMouseButton.LEFT,
        u: Double = lastU,
        v: Double = lastV,
        pointerType: BrowserPointerType = BrowserPointerType.MOUSE,
        pressure: Double = if (pointerType == BrowserPointerType.PEN) 1.0 else 0.0,
    ) {
        lastU = u
        lastV = v
        buttons = buttons or button.cdpBit
        browser.focus()
        browser.pointer(
            pointerEvent(
                BrowserPointerEventType.DOWN,
                u,
                v,
                button,
                currentClickCount(u, v),
                pointerType,
                pressure,
            )
        )
    }

    fun up(
        button: BrowserMouseButton = BrowserMouseButton.LEFT,
        u: Double = lastU,
        v: Double = lastV,
        pointerType: BrowserPointerType = BrowserPointerType.MOUSE,
    ) {
        lastU = u
        lastV = v
        buttons = buttons and button.cdpBit.inv()
        browser.pointer(
            pointerEvent(
                BrowserPointerEventType.UP,
                u,
                v,
                button,
                clickCount.coerceAtLeast(1),
                pointerType,
            )
        )
    }

    fun wheel(
        deltaX: Double,
        deltaY: Double,
        u: Double = lastU,
        v: Double = lastV,
    ) {
        lastU = u
        lastV = v
        browser.pointer(
            pointerEvent(BrowserPointerEventType.WHEEL, u, v).copy(deltaX = deltaX, deltaY = deltaY)
        )
    }

    fun releaseAll() {
        BrowserMouseButton.entries
            .filter { it != BrowserMouseButton.NONE && buttons and it.cdpBit != 0 }
            .forEach { up(it) }
    }

    private fun pointerEvent(
        type: BrowserPointerEventType,
        u: Double,
        v: Double,
        button: BrowserMouseButton = BrowserMouseButton.NONE,
        clickCount: Int = 0,
        pointerType: BrowserPointerType = BrowserPointerType.MOUSE,
        pressure: Double = 0.0,
    ) = BrowserPointerEvent(
        type = type,
        x = u * cssWidth,
        y = v * cssHeight,
        button = button,
        buttons = buttons,
        modifiers = modifiers,
        clickCount = clickCount,
        pointerType = pointerType,
        pressure = pressure,
    )

    private fun currentClickCount(u: Double, v: Double): Int {
        val now = System.nanoTime()
        val near = abs(u - lastClickU) * cssWidth <= 5 && abs(v - lastClickV) * cssHeight <= 5
        clickCount = if (near && now - lastClickNanos <= 500_000_000L) clickCount + 1 else 1
        lastClickNanos = now
        lastClickU = u
        lastClickV = v
        return clickCount
    }
}

/**
 * Sends one Field [Window]'s pointer and keyboard stream to a [PuppetBrowser]. [hitTest] converts
 * window-local logical coordinates to browser UVs; return null when the browser was not hit.
 */
class PuppetBrowserWindowInput(
    val browser: PuppetBrowser,
    val window: Window,
    private val hitTest: (x: Double, y: Double) -> Pair<Double, Double>? = { x, y ->
        val size = window.size()
        if (x < 0 || y < 0 || x >= size.w || y >= size.h) null else x / size.w to y / size.h
    },
) : AutoCloseable {
    val uv = PuppetBrowserUvInput(browser)
    var active: Boolean = false
        private set

    private var lastHit: Pair<Double, Double>? = null
    private var captured = false
    private val removers = mutableListOf<() -> Unit>()

    init {
        removers += window.addCursorListener { x, y ->
            val hit = hitTest(x, y)
            if (hit != null) lastHit = hit
            val at = hit ?: if (captured) lastHit else null
            at?.let { uv.move(it.first, it.second) }
        }
        removers += window.addMouseButtonListener { button, action, mods ->
            val browserButton = browserMouseButton(button) ?: return@addMouseButtonListener
            uv.modifiers = browserModifiers(mods)
            val at = hitTest(window.cursor.x.toDouble(), window.cursor.y.toDouble())
            if (at != null) lastHit = at
            when (action) {
                GLFW.GLFW_PRESS -> if (at != null) {
                    active = true
                    captured = true
                    uv.down(browserButton, at.first, at.second)
                }
                GLFW.GLFW_RELEASE -> if (captured) {
                    val releaseAt = at ?: lastHit ?: (0.5 to 0.5)
                    uv.up(browserButton, releaseAt.first, releaseAt.second)
                    if (uv.buttons == 0) captured = false
                }
            }
        }
        removers += window.addScrollListener { x, y ->
            val at = hitTest(window.cursor.x.toDouble(), window.cursor.y.toDouble())
            if (active && at != null) uv.wheel(-x * 40.0, -y * 40.0, at.first, at.second)
        }
        removers += window.addKeyListener { key, scancode, action, mods ->
            if (!active) return@addKeyListener
            uv.modifiers = browserModifiers(mods)
            browserKey(key, mods)?.let { descriptor ->
                browser.key(
                    BrowserKeyEvent(
                        type = if (action == GLFW.GLFW_RELEASE) BrowserKeyEventType.KEY_UP
                        else BrowserKeyEventType.RAW_KEY_DOWN,
                        key = descriptor.key,
                        code = descriptor.code,
                        modifiers = uv.modifiers,
                        windowsVirtualKeyCode = descriptor.virtualKey,
                        nativeVirtualKeyCode = scancode,
                        autoRepeat = action == GLFW.GLFW_REPEAT,
                        keypad = descriptor.keypad,
                    )
                )
            }
        }
        removers += window.addCharListener { codepoint ->
            if (active) browser.insertText(String(Character.toChars(codepoint)))
        }
        removers += window.addFocusListener { focused ->
            if (!focused && active) {
                uv.releaseAll()
                active = false
                captured = false
                browser.blur()
            }
        }
    }

    fun takeFocus() {
        active = true
        browser.focus()
    }

    fun releaseFocus() {
        if (!active) return
        uv.releaseAll()
        active = false
        captured = false
        browser.blur()
    }

    override fun close() {
        releaseFocus()
        removers.toList().forEach { it() }
        removers.clear()
    }
}

/** A world-space rectangular browser panel with pointer capture for controller or mouse rays. */
class PuppetBrowserPanel(
    val browser: PuppetBrowser,
    var halfWidth: Float,
    var halfHeight: Float,
) {
    val model = Matrix4f()
    val uv = PuppetBrowserUvInput(browser)

    data class Hit(
        val worldPoint: Vector3f,
        val u: Double,
        val v: Double,
        val onPanel: Boolean,
    )

    private var pressed = false
    private var captured = false
    private var lastHit: Hit? = null

    /** Compose this panel's world transform with one eye's view/projection matrices. */
    fun panelMvp(
        view: Matrix4f,
        projection: Matrix4f,
        destination: Matrix4f = Matrix4f(),
    ): Matrix4f = destination.set(projection).mul(view).mul(model)

    fun place(position: Vector3f, right: Vector3f, up: Vector3f): Matrix4f {
        val r = Vector3f(right).normalize()
        val n = Vector3f(r).cross(up).normalize()
        val u = Vector3f(n).cross(r).normalize()
        return model.set(
            r.x, r.y, r.z, 0f,
            u.x, u.y, u.z, 0f,
            n.x, n.y, n.z, 0f,
            position.x, position.y, position.z, 1f,
        )
    }

    fun faceToward(
        position: Vector3f,
        eye: Vector3f,
        worldUp: Vector3f = Vector3f(0f, 1f, 0f),
    ): Matrix4f {
        val n = Vector3f(eye).sub(position).normalize()
        val r = Vector3f(worldUp).cross(n).normalize()
        return place(position, r, Vector3f(n).cross(r).normalize())
    }

    fun raycast(origin: Vector3f, direction: Vector3f): Hit? {
        val inverse = Matrix4f(model).invert()
        val localOrigin = inverse.transformPosition(Vector3f(origin))
        val localDirection = inverse.transformDirection(Vector3f(direction))
        if (abs(localDirection.z) <= 1e-6f) return null
        val distance = -localOrigin.z / localDirection.z
        if (distance <= 0f) return null
        val localHit = Vector3f(localOrigin).fma(distance, localDirection)
        val worldHit = model.transformPosition(localHit, Vector3f())
        val u = localHit.x / (halfWidth * 2.0) + 0.5
        val v = 0.5 - localHit.y / (halfHeight * 2.0)
        return Hit(worldHit, u, v, abs(localHit.x) <= halfWidth && abs(localHit.y) <= halfHeight)
    }

    fun interact(
        origin: Vector3f,
        direction: Vector3f,
        triggerPressed: Boolean,
        scrollY: Double = 0.0,
        pointerType: BrowserPointerType = BrowserPointerType.MOUSE,
    ): Hit? {
        val hit = raycast(origin, direction)
        if (hit != null) lastHit = hit
        val at = when {
            hit?.onPanel == true -> hit
            captured -> hit ?: lastHit
            else -> null
        }
        at?.let { uv.move(it.u, it.v, pointerType) }
        if (triggerPressed && !pressed && hit?.onPanel == true) {
            captured = true
            uv.down(BrowserMouseButton.LEFT, hit.u, hit.v, pointerType)
        } else if (!triggerPressed && pressed && captured) {
            val releaseAt = at ?: lastHit
            if (releaseAt != null) uv.up(BrowserMouseButton.LEFT, releaseAt.u, releaseAt.v, pointerType)
            captured = false
        }
        if (scrollY != 0.0 && at != null) uv.wheel(0.0, scrollY, at.u, at.v)
        pressed = triggerPressed
        return hit
    }

    /** Feed an OpenXR controller directly into this panel. */
    fun interact(
        state: ControllerState,
        scrollY: Double = 0.0,
        localForward: Vector3f = Vector3f(0f, 0f, 1f),
    ): Hit? {
        if (!state.active || !state.poseValid) return null
        val (origin, direction) = controllerRay(state, localForward)
        return interact(origin, direction, state.selectPressed || state.triggerPressed, scrollY)
    }

    companion object {
        /** Build a world-space ray from Field's standard OpenXR controller pose. */
        fun controllerRay(
            state: ControllerState,
            localForward: Vector3f = Vector3f(0f, 0f, 1f),
        ): Pair<Vector3f, Vector3f> {
            val direction = Vector3f(localForward)
            state.rotation.transform(direction)
            return Vector3f(state.position) to direction
        }
    }
}

private data class BrowserKeyDescriptor(
    val key: String,
    val code: String,
    val virtualKey: Int,
    val keypad: Boolean = false,
)

private fun browserModifiers(mods: Int): Set<BrowserModifier> = buildSet {
    if (mods and GLFW.GLFW_MOD_ALT != 0) add(BrowserModifier.ALT)
    if (mods and GLFW.GLFW_MOD_CONTROL != 0) add(BrowserModifier.CONTROL)
    if (mods and GLFW.GLFW_MOD_SUPER != 0) add(BrowserModifier.META)
    if (mods and GLFW.GLFW_MOD_SHIFT != 0) add(BrowserModifier.SHIFT)
}

private fun browserMouseButton(button: Int): BrowserMouseButton? = when (button) {
    GLFW.GLFW_MOUSE_BUTTON_LEFT -> BrowserMouseButton.LEFT
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> BrowserMouseButton.RIGHT
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> BrowserMouseButton.MIDDLE
    GLFW.GLFW_MOUSE_BUTTON_4 -> BrowserMouseButton.BACK
    GLFW.GLFW_MOUSE_BUTTON_5 -> BrowserMouseButton.FORWARD
    else -> null
}

private fun browserKey(key: Int, mods: Int): BrowserKeyDescriptor? {
    val shift = mods and GLFW.GLFW_MOD_SHIFT != 0
    if (key in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) {
        val letter = ('A'.code + key - GLFW.GLFW_KEY_A).toChar()
        return BrowserKeyDescriptor(if (shift) "$letter" else "$letter".lowercase(), "Key$letter", letter.code)
    }
    if (key in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9) {
        val digit = ('0'.code + key - GLFW.GLFW_KEY_0).toChar()
        return BrowserKeyDescriptor("$digit", "Digit$digit", digit.code)
    }
    if (key in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) {
        val number = key - GLFW.GLFW_KEY_F1 + 1
        return BrowserKeyDescriptor("F$number", "F$number", 111 + number)
    }
    if (key in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9) {
        val digit = key - GLFW.GLFW_KEY_KP_0
        return BrowserKeyDescriptor("$digit", "Numpad$digit", 96 + digit, keypad = true)
    }
    return specialBrowserKeys[key]
}

private val specialBrowserKeys = mapOf(
    GLFW.GLFW_KEY_SPACE to BrowserKeyDescriptor(" ", "Space", 32),
    GLFW.GLFW_KEY_APOSTROPHE to BrowserKeyDescriptor("'", "Quote", 222),
    GLFW.GLFW_KEY_COMMA to BrowserKeyDescriptor(",", "Comma", 188),
    GLFW.GLFW_KEY_MINUS to BrowserKeyDescriptor("-", "Minus", 189),
    GLFW.GLFW_KEY_PERIOD to BrowserKeyDescriptor(".", "Period", 190),
    GLFW.GLFW_KEY_SLASH to BrowserKeyDescriptor("/", "Slash", 191),
    GLFW.GLFW_KEY_SEMICOLON to BrowserKeyDescriptor(";", "Semicolon", 186),
    GLFW.GLFW_KEY_EQUAL to BrowserKeyDescriptor("=", "Equal", 187),
    GLFW.GLFW_KEY_LEFT_BRACKET to BrowserKeyDescriptor("[", "BracketLeft", 219),
    GLFW.GLFW_KEY_BACKSLASH to BrowserKeyDescriptor("\\", "Backslash", 220),
    GLFW.GLFW_KEY_RIGHT_BRACKET to BrowserKeyDescriptor("]", "BracketRight", 221),
    GLFW.GLFW_KEY_GRAVE_ACCENT to BrowserKeyDescriptor("`", "Backquote", 192),
    GLFW.GLFW_KEY_ESCAPE to BrowserKeyDescriptor("Escape", "Escape", 27),
    GLFW.GLFW_KEY_ENTER to BrowserKeyDescriptor("Enter", "Enter", 13),
    GLFW.GLFW_KEY_TAB to BrowserKeyDescriptor("Tab", "Tab", 9),
    GLFW.GLFW_KEY_BACKSPACE to BrowserKeyDescriptor("Backspace", "Backspace", 8),
    GLFW.GLFW_KEY_INSERT to BrowserKeyDescriptor("Insert", "Insert", 45),
    GLFW.GLFW_KEY_DELETE to BrowserKeyDescriptor("Delete", "Delete", 46),
    GLFW.GLFW_KEY_RIGHT to BrowserKeyDescriptor("ArrowRight", "ArrowRight", 39),
    GLFW.GLFW_KEY_LEFT to BrowserKeyDescriptor("ArrowLeft", "ArrowLeft", 37),
    GLFW.GLFW_KEY_DOWN to BrowserKeyDescriptor("ArrowDown", "ArrowDown", 40),
    GLFW.GLFW_KEY_UP to BrowserKeyDescriptor("ArrowUp", "ArrowUp", 38),
    GLFW.GLFW_KEY_PAGE_UP to BrowserKeyDescriptor("PageUp", "PageUp", 33),
    GLFW.GLFW_KEY_PAGE_DOWN to BrowserKeyDescriptor("PageDown", "PageDown", 34),
    GLFW.GLFW_KEY_HOME to BrowserKeyDescriptor("Home", "Home", 36),
    GLFW.GLFW_KEY_END to BrowserKeyDescriptor("End", "End", 35),
    GLFW.GLFW_KEY_CAPS_LOCK to BrowserKeyDescriptor("CapsLock", "CapsLock", 20),
    GLFW.GLFW_KEY_SCROLL_LOCK to BrowserKeyDescriptor("ScrollLock", "ScrollLock", 145),
    GLFW.GLFW_KEY_NUM_LOCK to BrowserKeyDescriptor("NumLock", "NumLock", 144),
    GLFW.GLFW_KEY_PRINT_SCREEN to BrowserKeyDescriptor("PrintScreen", "PrintScreen", 44),
    GLFW.GLFW_KEY_PAUSE to BrowserKeyDescriptor("Pause", "Pause", 19),
    GLFW.GLFW_KEY_LEFT_SHIFT to BrowserKeyDescriptor("Shift", "ShiftLeft", 16),
    GLFW.GLFW_KEY_RIGHT_SHIFT to BrowserKeyDescriptor("Shift", "ShiftRight", 16),
    GLFW.GLFW_KEY_LEFT_CONTROL to BrowserKeyDescriptor("Control", "ControlLeft", 17),
    GLFW.GLFW_KEY_RIGHT_CONTROL to BrowserKeyDescriptor("Control", "ControlRight", 17),
    GLFW.GLFW_KEY_LEFT_ALT to BrowserKeyDescriptor("Alt", "AltLeft", 18),
    GLFW.GLFW_KEY_RIGHT_ALT to BrowserKeyDescriptor("Alt", "AltRight", 18),
    GLFW.GLFW_KEY_LEFT_SUPER to BrowserKeyDescriptor("Meta", "MetaLeft", 91),
    GLFW.GLFW_KEY_RIGHT_SUPER to BrowserKeyDescriptor("Meta", "MetaRight", 92),
    GLFW.GLFW_KEY_MENU to BrowserKeyDescriptor("ContextMenu", "ContextMenu", 93),
    GLFW.GLFW_KEY_KP_DECIMAL to BrowserKeyDescriptor(".", "NumpadDecimal", 110, keypad = true),
    GLFW.GLFW_KEY_KP_DIVIDE to BrowserKeyDescriptor("/", "NumpadDivide", 111, keypad = true),
    GLFW.GLFW_KEY_KP_MULTIPLY to BrowserKeyDescriptor("*", "NumpadMultiply", 106, keypad = true),
    GLFW.GLFW_KEY_KP_SUBTRACT to BrowserKeyDescriptor("-", "NumpadSubtract", 109, keypad = true),
    GLFW.GLFW_KEY_KP_ADD to BrowserKeyDescriptor("+", "NumpadAdd", 107, keypad = true),
    GLFW.GLFW_KEY_KP_ENTER to BrowserKeyDescriptor("Enter", "NumpadEnter", 13, keypad = true),
    GLFW.GLFW_KEY_KP_EQUAL to BrowserKeyDescriptor("=", "NumpadEqual", 187, keypad = true),
)

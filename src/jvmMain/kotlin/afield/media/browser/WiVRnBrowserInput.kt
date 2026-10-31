package afield.media.browser

/**
 * Exclusive, focus-independent Linux input from WiVRn's virtual keyboard and mouse. The mouse
 * lives in browser coordinates, so moving a VR panel never moves its cursor onto another app.
 * Call [poll] once per graphics frame; [close] releases keys, buttons and evdev grabs.
 * See `tools/setup_wivrn_input.py` for the narrowly scoped device permissions.
 */
class WiVRnBrowserInput internal constructor(
    private val browser: PuppetBrowserControl,
    viewport: ExternalBrowserSurfaceSpec,
    private val source: WiVRnEventSource,
    layout: String,
    variant: String,
    options: String,
    private val pointerActivity: () -> Unit,
    private val otherModifiers: () -> Set<BrowserModifier>,
) : AutoCloseable {
    constructor(
        browser: PuppetBrowserControl,
        viewport: ExternalBrowserSurfaceSpec,
        layout: String = System.getenv("FIELD_VR_KEYBOARD_LAYOUT") ?: "us",
        variant: String = System.getenv("FIELD_VR_KEYBOARD_VARIANT") ?: "",
        options: String = System.getenv("FIELD_VR_KEYBOARD_OPTIONS") ?: "",
        pointerActivity: () -> Unit = {},
        otherModifiers: () -> Set<BrowserModifier> = { emptySet() },
    ) : this(browser, viewport, WiVRnDeviceReader(), layout, variant, options, pointerActivity, otherModifiers) {
        require(System.getProperty("os.name").startsWith("Linux")) { "WiVRn evdev input requires Linux" }
    }

    val uv = PuppetBrowserUvInput(browser, viewport)
    private val mapDelegate = lazy { WiVRnKeyboardMap(layout, variant, options) }
    private val keymap by mapDelegate
    private val keys = linkedMapOf<Int, BrowserKeyEvent>()
    private val blockedKeys = mutableSetOf<Int>()
    private val mouseDown = mutableSetOf<Int>()
    private val blockedButtons = mutableSetOf<Int>()
    private var dx = 0.0
    private var dy = 0.0
    private var wheelX = 0.0
    private var wheelY = 0.0
    private var highWheelX: Double? = null
    private var highWheelY: Double? = null
    private var enabled = true
    private var closed = false
    private var failure: String? = null

    val modifiers: Set<BrowserModifier> get() = if (mapDelegate.isInitialized()) keymap.modifiers() else emptySet()
    val capturedDevices: Set<String> get() = source.captured.map { it.deviceName }.toSet()
    val status: String get() = when {
        closed -> "Headset input closed"
        failure != null -> "Headset input: $failure"
        source.problem != null -> source.problem!!
        source.captured.size == 2 -> "Headset keyboard + mouse captured · desktop focus independent"
        source.captured.isNotEmpty() -> "Captured ${capturedDevices.joinToString()} · waiting for the other device"
        else -> "Waiting for WiVRn keyboard / mouse · HID forwarding must be enabled"
    }

    /** Poll even during page loads: input stays captured, but is discarded until the page is ready. */
    fun poll(deliver: Boolean = true) {
        if (closed || failure != null) return
        try {
            if (enabled != deliver) {
                blockedKeys += keys.keys
                releaseKeyboard()
                releasePointer()
                enabled = deliver
            }
            val events = source.poll()
            source.takeResets().forEach { (kind, held) ->
                when (kind) {
                    WiVRnDevice.KEYBOARD -> { releaseKeyboard(); blockedKeys.clear(); blockedKeys += held }
                    WiVRnDevice.MOUSE -> { releasePointer(); blockedButtons.clear(); blockedButtons += held; mouseDown.clear() }
                }
            }
            events.forEach { (kind, event) ->
                if (kind == WiVRnDevice.KEYBOARD) keyboard(event) else mouse(event)
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            failure = error.message ?: error.javaClass.simpleName
            try { releaseKeyboard(); releasePointer() } finally { source.close() }
        }
    }

    /** Adopt the controller's last hit when handing the relative mouse the shared cursor. */
    fun placeCursor(u: Double, v: Double) { uv.move(u.coerceIn(0.0, 1.0), v.coerceIn(0.0, 1.0)) }

    private fun keyboard(event: EvdevEvent) {
        if (event.type != 1 || event.value !in 0..2) return
        val code = event.code
        if (code in blockedKeys) {
            if (event.value == 0) blockedKeys.remove(code)
            return
        }
        if (!enabled) { if (event.value != 0) blockedKeys += code; return }
        if (event.value == 0) {
            val previous = keys.remove(code) ?: return
            keymap.update(code, false)
            browser.key(previous.copy(type = BrowserKeyEventType.KEY_UP, modifiers = modifiers + otherModifiers(),
                text = null, unmodifiedText = null, autoRepeat = false))
        } else {
            val repeat = event.value == 2
            if (repeat && code !in keys) return
            if (!repeat && code in keys) return
            if (!repeat) keymap.update(code, true)
            val key = keymap.key(code, repeat, modifiers + otherModifiers())
            keys[code] = key
            browser.focus()
            browser.key(key)
        }
    }

    private fun mouse(event: EvdevEvent) {
        when (event.type) {
            2 -> when (event.code) {
                0 -> dx += event.value
                1 -> dy += event.value
                6 -> wheelX += event.value
                8 -> wheelY += event.value
                11 -> highWheelY = (highWheelY ?: 0.0) + event.value
                12 -> highWheelX = (highWheelX ?: 0.0) + event.value
            }
            0 -> if (event.code == 0) flushMouse()
            1 -> {
                val button = mouseButton(event.code) ?: return
                flushMouse()
                if (event.value == 0) mouseDown.remove(event.code) else mouseDown += event.code
                if (event.code in blockedButtons) {
                    if (event.value == 0) blockedButtons.remove(event.code)
                    return
                }
                if (!enabled) { if (event.value != 0) blockedButtons += event.code; return }
                pointerActivity()
                uv.modifiers = modifiers + otherModifiers()
                if (event.value == 1 && uv.buttons and button.cdpBit == 0) uv.down(button)
                else if (event.value == 0 && uv.buttons and button.cdpBit != 0) uv.up(button)
            }
        }
    }

    private fun flushMouse() {
        if (enabled && (dx != 0.0 || dy != 0.0 || wheelX != 0.0 || wheelY != 0.0 || highWheelX != null || highWheelY != null)) {
            pointerActivity()
            uv.modifiers = modifiers + otherModifiers()
            val (u, v) = uv.position
            if (dx != 0.0 || dy != 0.0) uv.move(
                (u * uv.cssWidth + dx).coerceIn(0.0, (uv.cssWidth - 1).coerceAtLeast(0.0)) / uv.cssWidth,
                (v * uv.cssHeight + dy).coerceIn(0.0, (uv.cssHeight - 1).coerceAtLeast(0.0)) / uv.cssHeight)
            val x = highWheelX?.div(120) ?: wheelX
            val y = highWheelY?.div(120) ?: wheelY
            if (x != 0.0 || y != 0.0) uv.wheel(x * 40, -y * 40)
        }
        dx = 0.0; dy = 0.0; wheelX = 0.0; wheelY = 0.0
        highWheelX = null; highWheelY = null
    }

    /** Another explicit pointer press may take over; held buttons then require a fresh press. */
    fun releasePointer() {
        blockedButtons += mouseDown
        uv.releaseAll()
        dx = 0.0; dy = 0.0; wheelX = 0.0; wheelY = 0.0
        highWheelX = null; highWheelY = null
    }

    private fun releaseKeyboard() {
        keys.values.toList().asReversed().forEach {
            browser.key(it.copy(type = BrowserKeyEventType.KEY_UP, modifiers = emptySet(), text = null,
                unmodifiedText = null, autoRepeat = false))
        }
        keys.clear()
        if (mapDelegate.isInitialized()) keymap.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            releaseKeyboard()
            releasePointer()
        } finally {
            try { source.close() } finally { if (mapDelegate.isInitialized()) keymap.close() }
        }
    }

    private fun mouseButton(code: Int): BrowserMouseButton? = when (code) {
        272 -> BrowserMouseButton.LEFT
        273 -> BrowserMouseButton.RIGHT
        274 -> BrowserMouseButton.MIDDLE
        275 -> BrowserMouseButton.BACK
        276 -> BrowserMouseButton.FORWARD
        else -> null
    }
}

package afield.media.browser

import org.junit.Assume
import org.junit.Before
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.*

internal class QueuedWiVRnSource : WiVRnEventSource {
    val events = mutableListOf<WiVRnDeviceEvent>()
    val resets = mutableMapOf<WiVRnDevice, Set<Int>>()
    override val captured = WiVRnDevice.entries.toMutableSet()
    override val problem: String? = null
    var closed = false
    private var time = 0L
    fun key(code: Int, value: Int) { event(WiVRnDevice.KEYBOARD, 1, code, value) }
    fun mouse(type: Int, code: Int, value: Int) { event(WiVRnDevice.MOUSE, type, code, value) }
    fun syn() { mouse(0, 0, 0) }
    private fun event(kind: WiVRnDevice, type: Int, code: Int, value: Int) { events += WiVRnDeviceEvent(kind, EvdevEvent(++time, type, code, value)) }
    override fun poll() = events.toList().also { events.clear() }
    override fun takeResets() = resets.toMap().also { resets.clear() }
    override fun close() { closed = true; captured.clear() }
}

class WiVRnBrowserInputTest {
    @Before fun linuxOnly() { Assume.assumeTrue("WiVRn native keyboard layouts require Linux/libxkbcommon", System.getProperty("os.name").startsWith("Linux")) }
    private val events = mutableListOf<BrowserPointerEvent>()
    private val keys = mutableListOf<BrowserKeyEvent>()
    private val source = QueuedWiVRnSource()
    private val browser = Proxy.newProxyInstance(PuppetBrowserControl::class.java.classLoader,
        arrayOf(PuppetBrowserControl::class.java)) { _, method, args ->
        when (method.name) {
            "pointer" -> { events += args[0] as BrowserPointerEvent; Unit }
            "key" -> { keys += args[0] as BrowserKeyEvent; Unit }
            "focus" -> CompletableFuture.completedFuture(PuppetBrowserState(focused = true))
            else -> error("Unexpected browser method ${method.name}")
        }
    } as PuppetBrowserControl
    private fun input(layout: String = "us", variant: String = "", activity: () -> Unit = {}) =
        WiVRnBrowserInput(browser, ExternalBrowserSurfaceSpec(2000, 1000, deviceScaleFactor = 2.0),
            source, layout, variant, "", activity, { emptySet() })

    @Test fun `mouse uses CSS coordinates combines axes and clamps to the page`() {
        input().use { input ->
            source.mouse(2, 0, 100); source.mouse(2, 1, -50); source.syn(); input.poll()
            assertEquals(1, events.size)
            assertEquals(600.0, events.last().x); assertEquals(200.0, events.last().y)
            source.mouse(2, 0, 100000); source.mouse(2, 1, -100000); source.syn(); input.poll()
            assertEquals(999.0, events.last().x); assertEquals(0.0, events.last().y)
        }
    }

    @Test fun `buttons support right click and dragging and wheel high resolution is not doubled`() {
        input().use { input ->
            source.mouse(1, 273, 1); source.mouse(2, 0, 10); source.syn(); source.mouse(1, 273, 0)
            source.mouse(2, 8, 1); source.mouse(2, 11, 120); source.mouse(2, 6, 2); source.syn(); input.poll()
            assertEquals(BrowserMouseButton.RIGHT, events.first().button)
            assertEquals(2, events.first().buttons)
            assertEquals(2, events.first { it.type == BrowserPointerEventType.MOVE }.buttons)
            assertEquals(0, events.first { it.type == BrowserPointerEventType.UP }.buttons)
            assertEquals(-40.0, events.last().deltaY); assertEquals(80.0, events.last().deltaX)
        }
    }

    @Test fun `keymap preserves shift text control shortcuts and repeats`() {
        input().use { input ->
            source.key(42,1); source.key(30,1); source.key(30,2); source.key(30,0); source.key(42,0)
            source.key(29,1); source.key(46,1); source.key(46,0); source.key(29,0)
            input.poll()
            val a = keys.first { it.code == "KeyA" }
            assertEquals("A", a.key); assertEquals("A", a.text); assertEquals(BrowserKeyEventType.KEY_DOWN, a.type)
            assertEquals(setOf(BrowserModifier.SHIFT), a.modifiers)
            assertTrue(keys.any { it.code == "KeyA" && it.autoRepeat })
            val c = keys.first { it.code == "KeyC" }
            assertEquals(BrowserKeyEventType.RAW_KEY_DOWN, c.type); assertNull(c.text)
            assertEquals(setOf(BrowserModifier.CONTROL), c.modifiers)
            assertTrue(input.modifiers.isEmpty())
        }
    }

    @Test fun `dead key composition and AltGr use the configured layout`() {
        input(variant = "intl").use { input ->
            source.key(40,1); source.key(40,0); source.key(18,1); source.key(18,0); input.poll()
            assertEquals("Dead", keys.first().key); assertNull(keys.first().text)
            assertEquals("é", keys.first { it.code == "KeyE" }.text)
        }
        keys.clear()
        input(layout = "de").use { input ->
            source.key(100,1); source.key(16,1); source.key(16,0); source.key(100,0); input.poll()
            val q = keys.first { it.code == "KeyQ" }
            assertEquals("@", q.text); assertFalse(BrowserModifier.ALT in q.modifiers)
        }
    }

    @Test fun `keyboard modifiers are carried on mouse events`() {
        input().use { input ->
            source.key(29,1); source.mouse(1,272,1); source.mouse(1,272,0); source.key(29,0); input.poll()
            assertEquals(setOf(BrowserModifier.CONTROL), events.first().modifiers)
        }
    }

    @Test fun `disconnect releases keys and buttons and initial held keys are ignored until up`() {
        input().use { input ->
            source.key(29,1); source.mouse(1,272,1); input.poll()
            source.resets[WiVRnDevice.KEYBOARD] = setOf(30)
            source.resets[WiVRnDevice.MOUSE] = setOf(272)
            input.poll()
            assertEquals(BrowserKeyEventType.KEY_UP, keys.last().type)
            assertEquals(0, input.uv.buttons)
            source.key(30,2); source.mouse(1,272,0); source.key(30,0); input.poll()
            assertTrue(keys.none { it.code == "KeyA" })
            source.key(30,1); source.key(30,0); input.poll()
            assertEquals(2, keys.count { it.code == "KeyA" })
        }
        assertTrue(source.closed)
    }

    @Test fun `page loading drops input and resume does not replay a held key`() {
        input().use { input ->
            source.key(30,1); input.poll(deliver = false)
            source.key(30,2); input.poll(deliver = true)
            assertTrue(keys.isEmpty())
            source.key(30,0); source.key(30,1); source.key(30,0); input.poll()
            assertEquals(2, keys.size)
        }
    }

    @Test fun `pointer takeover releases capture without synthesizing another held button press`() {
        input().use { input ->
            source.mouse(1,272,1); input.poll(); input.releasePointer()
            source.mouse(2,0,10); source.syn(); input.poll()
            assertEquals(1, events.count { it.type == BrowserPointerEventType.DOWN })
            source.mouse(1,272,0); source.mouse(1,272,1); input.poll()
            assertEquals(2, events.count { it.type == BrowserPointerEventType.DOWN })
        }
    }
}

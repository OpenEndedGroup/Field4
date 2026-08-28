package afield.four.browser

import afield.Box
import afield.SessionInformation
import afield.globalServerPort
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PuppetBrowserProtocolTest {
    @Test
    fun `pointer input preserves css coordinates buttons modifiers and wheel deltas`() {
        val json = BrowserPointerEvent(
            type = BrowserPointerEventType.WHEEL,
            x = 123.5,
            y = 456.25,
            button = BrowserMouseButton.NONE,
            buttons = BrowserMouseButton.LEFT.cdpBit or BrowserMouseButton.RIGHT.cdpBit,
            modifiers = setOf(BrowserModifier.SHIFT, BrowserModifier.CONTROL),
            deltaX = -12.0,
            deltaY = 80.0,
        ).toCdpJson()

        assertEquals("mouseWheel", json["type"]!!.jsonPrimitive.content)
        assertEquals(123.5, json["x"]!!.jsonPrimitive.double)
        assertEquals(456.25, json["y"]!!.jsonPrimitive.double)
        assertEquals(3, json["buttons"]!!.jsonPrimitive.int)
        assertEquals(10, json["modifiers"]!!.jsonPrimitive.int)
        assertEquals(-12.0, json["deltaX"]!!.jsonPrimitive.double)
        assertEquals(80.0, json["deltaY"]!!.jsonPrimitive.double)
    }

    @Test
    fun `key and touch payloads match cdp input fields`() {
        val key = BrowserKeyEvent(
            type = BrowserKeyEventType.RAW_KEY_DOWN,
            key = "Enter",
            code = "NumpadEnter",
            modifiers = setOf(BrowserModifier.ALT),
            windowsVirtualKeyCode = 13,
            nativeVirtualKeyCode = 76,
            autoRepeat = true,
            keypad = true,
        ).toCdpJson()
        assertEquals("rawKeyDown", key["type"]!!.jsonPrimitive.content)
        assertEquals(76, key["nativeVirtualKeyCode"]!!.jsonPrimitive.int)
        assertEquals(true, key["autoRepeat"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, key["isKeypad"]!!.jsonPrimitive.content.toBoolean())

        val touch = touchToCdpJson(
            BrowserTouchEventType.MOVE,
            listOf(BrowserTouchPoint(7, 10.5, 20.25, force = 0.4)),
            setOf(BrowserModifier.META),
        )
        assertEquals("touchMove", touch["type"]!!.jsonPrimitive.content)
        assertEquals(4, touch["modifiers"]!!.jsonPrimitive.int)
        val point = touch["touchPoints"]!!.jsonArray.single().jsonObject
        assertEquals(7, point["id"]!!.jsonPrimitive.int)
        assertEquals(0.4, point["force"]!!.jsonPrimitive.double)
    }

    @Test
    fun `state and javascript results tolerate optional protocol fields`() {
        val state = puppetBrowserState(buildJsonObject {
            put("url", "https://field.graphics/")
            put("title", "Field")
            put("loading", true)
            put("canGoBack", true)
            put("navigationGeneration", 9)
        })
        assertEquals("Field", state.title)
        assertEquals(true, state.loading)
        assertEquals(true, state.canGoBack)
        assertEquals(false, state.crashed)
        assertEquals(9, state.navigationGeneration)

        val result = browserJavascriptResult(buildJsonObject {
            put("type", "object")
            put("value", JsonNull)
            put("description", "Object")
        })
        assertEquals(JsonNull, result.value)
        assertEquals("Object", result.description)
        assertNull(result.objectId)
    }

    @Test
    fun `window viewport fits browser aspect and hit tests the visible rectangle`() {
        val pillarbox = PuppetBrowserWindowViewport.fit(1600.0, 720.0, 1280.0, 720.0)
        assertEquals(PuppetBrowserWindowViewport(160.0, 0.0, 1280.0, 720.0), pillarbox)
        assertNull(pillarbox.hitTest(159.0, 360.0))
        assertEquals(0.5 to 0.5, pillarbox.hitTest(800.0, 360.0))

        val letterbox = PuppetBrowserWindowViewport.fit(1280.0, 900.0, 1280.0, 720.0)
        assertEquals(PuppetBrowserWindowViewport(0.0, 90.0, 1280.0, 720.0), letterbox)
        assertNull(letterbox.hitTest(640.0, 89.0))
        assertEquals(0.5 to 0.5, letterbox.hitTest(640.0, 450.0))
    }

    @Test
    fun `field document URL preserves an ephemeral agent session`() {
        val root = Box("root")
        val panel = Box("panel")
        val session = SessionInformation(
            filename = "/tmp/agent scratch",
            documentName = "scratch demo",
            root = root,
            ephemeral = true,
        )
        root.inside = session
        panel.inside = session

        assertEquals(
            "https://localhost:$globalServerPort/index.html?file=scratch+demo&scratch=1&sessionKey=%2Ftmp%2Fagent+scratch",
            FieldUiBrowserTexture.fieldDocumentUrl(panel),
        )
    }
}

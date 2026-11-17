package afield.app.server

import afield.app.protocol.CustomMarkingResponse
import afield.document.graph.Box
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class CustomMarkingMenuTest {
    @Test fun nestedMenusSerializeAndFireOnceInTheirOwningBox() {
        val box = Box(id = "marking-nested-test")
        var called = 0
        box.markingMenu {
            submenu("N", "Tools") {
                item("W", "Count") { assertSame(box, Box.context.get()); called++ }
                submenu("E", "More") { item("S2", "Again") { called += 10 } }
            }
        }
        val response = CustomMarkingResponse(box.id, prepareCustomMarkingEntries(box))
        val decoded = Json.decodeFromString<CustomMarkingResponse>(Json.encodeToString(response))
        val tools = decoded.entries.single()
        assertEquals("", tools.callbackId)
        assertEquals(180.0, tools.angle)
        assertEquals(2.0, tools.children[1].children.single().radius)
        val callback = tools.children[0].callbackId
        assertTrue(fireCustomMarkingCallback(callback)); assertEquals(1, called)
        assertFalse(fireCustomMarkingCallback(callback)); assertEquals(1, called)
        assertTrue(fireCustomMarkingCallback(tools.children[1].children.single().callbackId))
        assertEquals(11, called)
    }

    @Test fun rerunsReplaceTreeAndRefreshingMenuRetiresOldCallbacks() {
        val box = Box(id = "marking-replace-test")
        box.markingMenu { item("N", "Old") { error("Stale callback ran") } }
        val old = prepareCustomMarkingEntries(box).single().callbackId
        box.markingMenu { item("E", "New") {} }
        box.customMarking["W" to "Legacy"] = {}
        val entries = prepareCustomMarkingEntries(box)
        assertEquals(listOf("Legacy", "New"), entries.map { it.label })
        assertFalse(fireCustomMarkingCallback(old))
        entries.forEach { assertTrue(fireCustomMarkingCallback(it.callbackId)) }
        box.markingMenu {}
        assertEquals("Legacy", prepareCustomMarkingEntries(box).single().label)
    }

    @Test fun invalidMenusFailAtInstallation() {
        val box = Box(id = "marking-invalid-test")
        assertFailsWith<IllegalArgumentException> { box.markingMenu { item("up", "Bad") {} } }
        assertFailsWith<IllegalArgumentException> { box.markingMenu { item("N", " ") {} } }
        assertFailsWith<IllegalArgumentException> { box.markingMenu { submenu("N", "Empty") {} } }
    }
}

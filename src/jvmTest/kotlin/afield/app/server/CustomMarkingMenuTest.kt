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

    @Test fun rerunsReplaceTreeAndRetireOldCallbacks() {
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

    @Test fun multipleViewsCanUseTheirOwnMenuSnapshotAndRetentionIsBounded() {
        val box = Box(id = "marking-multiple-views")
        var chosen = 0
        box.markingMenu { item("N", "Choose") { chosen++ } }
        val first = prepareCustomMarkingEntries(box).single().callbackId
        val second = prepareCustomMarkingEntries(box).single().callbackId
        assertTrue(fireCustomMarkingCallback(first))
        assertTrue(fireCustomMarkingCallback(second))
        assertFalse(fireCustomMarkingCallback(first))
        val oldest = prepareCustomMarkingEntries(box).single().callbackId
        var newest = ""
        repeat(32) { newest = prepareCustomMarkingEntries(box).single().callbackId }
        assertFalse(fireCustomMarkingCallback(oldest))
        assertTrue(fireCustomMarkingCallback(newest))
        assertEquals(3, chosen)
    }

    @Test fun identicalBoxIdsInDifferentDocumentsKeepIndependentCallbacks() {
        fun owner(document: String): Box {
            val root = Box(id = "root")
            val session = SessionInformation(document, document, java.util.concurrent.ConcurrentHashMap(), root)
            root.inside = session
            return Box(id = "same-id").also { it.inside = session }
        }
        val first = owner("document-a"); val second = owner("document-b")
        var value = 0
        first.markingMenu { item("N", "First") { assertSame(first, Box.context.get()); value += 1 } }
        second.markingMenu { item("N", "Second") { assertSame(second, Box.context.get()); value += 10 } }
        val firstId = prepareCustomMarkingEntries(first).single().callbackId
        val secondId = prepareCustomMarkingEntries(second).single().callbackId
        assertTrue(fireCustomMarkingCallback(firstId))
        assertTrue(fireCustomMarkingCallback(secondId))
        assertEquals(11, value)
    }

    @Test fun liveOutputPanelsSerializeWithoutCallbackTokens() {
        val box = Box(id = "marking-output-test")
        box.markingMenu { submenu("N", "Tools") { output("E", "Controls", "controls", 420, 180) } }
        val response = CustomMarkingResponse(box.id, prepareCustomMarkingEntries(box))
        val entry = Json.decodeFromString<CustomMarkingResponse>(Json.encodeToString(response)).entries.single().children.single()
        assertEquals("controls", entry.outputChannel)
        assertEquals(420, entry.outputWidth)
        assertEquals(180, entry.outputHeight)
        assertEquals("", entry.callbackId)
        assertFalse(fireCustomMarkingCallback(entry.callbackId))
        for (channel in listOf("", "nested", "*inline")) {
            assertFailsWith<IllegalArgumentException> { box.markingMenu { output("N", "Bad", channel) } }
        }
        assertFailsWith<IllegalArgumentException> { box.markingMenu { output("N", "Bad", "controls", 0) } }
    }

    @Test fun invalidMenusFailAtInstallation() {
        val box = Box(id = "marking-invalid-test")
        assertFailsWith<IllegalArgumentException> { box.markingMenu { item("up", "Bad") {} } }
        assertFailsWith<IllegalArgumentException> { box.markingMenu { item("N", " ") {} } }
        assertFailsWith<IllegalArgumentException> { box.markingMenu { submenu("N", "Empty") {} } }
    }
}

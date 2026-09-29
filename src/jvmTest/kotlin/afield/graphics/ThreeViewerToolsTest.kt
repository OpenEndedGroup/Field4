package afield.graphics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ThreeViewerToolsTest {
    @Test fun modulesAreExplicitAndDefaultPayloadIsUnchanged() {
        val plain = ThreeGeometryPayload(uid = "test")
        assertFalse(Json.encodeToString(plain).contains("toolModules"))
        assertTrue(Json.encodeToString(plain.copy(toolModules=listOf("/tool.js"))).contains("/tool.js"))
    }

    @Test fun builderRejectsCrossOriginAndDeduplicatesModules() {
        val builder = ThreeSceneBuilder(12)
        builder.tools("/tools/example.js"); builder.tools("/tools/example.js")
        assertEquals(listOf("/tools/example.js"), builder.toolModules)
        for (path in listOf("https://example.org/tool.js", "//example.org/tool.js", "/\\example.org/tool.js"))
            assertFailsWith<IllegalArgumentException> { builder.tools(path) }
    }
}

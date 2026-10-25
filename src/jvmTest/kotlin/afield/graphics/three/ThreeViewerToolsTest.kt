package afield.graphics.three

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

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

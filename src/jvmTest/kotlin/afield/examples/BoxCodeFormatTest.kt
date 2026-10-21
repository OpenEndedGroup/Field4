package afield.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxCodeFormatTest {
    @Test
    fun supplementaryUnicodeDoesNotMoveTheReplacementIntoOtherProperties() {
        val source = "name: '🎨 fixture'\nproperties:\n  code: \"val label=\\\"🌊\\\"\\nprintln(label)\"\n  label: '🧭 retained'\n"
        val formatted = BoxCodeFormat.formatYaml(source, "unicode.yaml")
        val parsed = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(formatted)
        assertEquals("🎨 fixture", parsed["name"])
        val properties = parsed["properties"] as Map<*, *>
        assertEquals("🧭 retained", properties["label"])
        assertEquals("val label = \"🌊\"\nprintln(label)", properties["code"])
        assertEquals(formatted, BoxCodeFormat.formatYaml(formatted, "unicode.yaml"))
    }

    @Test
    fun quotedAndPlainProgramsAreDecodedAndFormattedWithoutChangingMetadata() {
        for (value in listOf("\"val x=1\\nprintln(x)\"", "'val x=1'", "val x=1")) {
            val source = "properties:\n  code: $value\n  label: unchanged\n  count: 3\n"
            val formatted = BoxCodeFormat.formatYaml(source, "quoted.yaml")
            val properties = org.yaml.snakeyaml.Yaml().load<Map<String, Map<String, Any>>>(formatted).getValue("properties")
            assertTrue((properties.getValue("code") as String).startsWith("val x = 1"), formatted)
            assertEquals("unchanged", properties["label"])
            assertEquals(3, properties["count"])
            assertEquals(formatted, BoxCodeFormat.formatYaml(formatted, "quoted.yaml"))
        }
    }

    @Test
    fun quotedShaderAndHeaderCommentSurviveTheScalarConversion() {
        val source = "properties:\n  fragment: \"#version 450\\nvoid main(){float x=1;}\" # keep this\n  label: untouched\n"
        val formatted = BoxCodeFormat.formatYaml(source, "shader.yaml")
        val properties = org.yaml.snakeyaml.Yaml().load<Map<String, Map<String, String>>>(formatted).getValue("properties")
        assertTrue(properties.getValue("fragment").contains("void main() {\n    float x=1;\n}"), formatted)
        assertTrue("# keep this" in formatted)
        assertEquals("untouched", properties["label"])
        assertEquals(formatted, BoxCodeFormat.formatYaml(formatted, "shader.yaml"))
    }

    @Test
    fun anchoredProgramsAndFlowCollectionsKeepTheirYamlStructure() {
        val source = "shared: &program 'val x=1'\nproperties:\n  code: *program\nother: {code: 'val y=2'}\n"
        assertEquals(source, BoxCodeFormat.formatYaml(source, "aliases.yaml"))
    }

    @Test
    fun quotedAndBareYamlKeysUseTheSameFormatter() {
        for (key in listOf("code", "\"code\"", "'code'")) {
            val source = "properties:\n  $key: |-\n    val x=1\n    println(x)\n  label: unchanged\n"
            val formatted = BoxCodeFormat.formatYaml(source, "fixture.yaml")
            assertTrue("  $key: |-\n    val x = 1\n" in formatted, formatted)
            assertTrue(formatted.endsWith("  label: unchanged\n"))
            assertEquals(formatted, BoxCodeFormat.formatYaml(formatted, "fixture.yaml"))
        }
    }

    @Test
    fun mismatchedQuotesDoNotIntroduceACodeBlock() {
        val source = "  \"code': |\n    val x=1\n"
        assertEquals(source, BoxCodeFormat.formatYaml(source, "malformed.yaml"))
    }
}

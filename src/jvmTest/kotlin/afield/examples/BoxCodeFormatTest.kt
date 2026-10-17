package afield.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxCodeFormatTest {
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

package afield

import kotlin.script.experimental.api.KotlinType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two things the completion path used to take on faith about a box's source.
 *
 * The first is where an unresolved name *is*: the "did you mean" probe cuts the offending token out
 * of the source and completes in the hole, and it used to locate that token with a bare `\b…\b`
 * regex, which matches inside string literals and comments as readily as in code.
 *
 * The second is what a bare (non-dot) caret's receiver is. That was the literal `"Box"`; it is now
 * read off the engine, and these pin that the two answers agree.
 */
class CompletionSourceScanTest {

    private fun find(code: String, missing: String) = firstCodeOccurrence(code, missing)

    @Test
    fun `a name in code is found`() {
        val code = "val a = widget"
        assertEquals(code.indexOf("widget"), find(code, "widget"))
    }

    @Test
    fun `a name inside a string literal is not code`() {
        assertEquals(-1, find("""println("no such widget")""", "widget"))
    }

    @Test
    fun `a name inside a line comment is not code`() {
        assertEquals(-1, find("// widget goes here\nval a = 1", "widget"))
    }

    @Test
    fun `a name inside a block comment is not code`() {
        assertEquals(-1, find("/* widget\n   goes here */\nval a = 1", "widget"))
    }

    @Test
    fun `a nested block comment closes only once fully`() {
        assertEquals(-1, find("/* a /* b */ widget */\nval a = 1", "widget"))
    }

    @Test
    fun `a name inside a raw string is not code`() {
        assertEquals(-1, find("val s = \"\"\"a widget here\"\"\"", "widget"))
    }

    /** The reported failure mode: the literal comes first, the real reference comes later. */
    @Test
    fun `the code occurrence wins over an earlier literal`() {
        val code = "println(\"missing widget\")\nval a = widget"
        assertEquals(code.lastIndexOf("widget"), find(code, "widget"))
    }

    @Test
    fun `an escaped quote does not end a string early`() {
        assertEquals(-1, find("""val s = "he said \"widget\" loudly"""", "widget"))
    }

    @Test
    fun `a longer identifier is not a match`() {
        assertEquals(-1, find("val widgetFactory = 1", "widget"))
    }

    @Test
    fun `a member of a longer name is not a match`() {
        assertEquals(-1, find("val theWidget = 1", "Widget"))
    }

    /**
     * The implicit receiver every box's engine is built with (`makeKI` in `Boxes.kt`), shortened the
     * way the documentation lookup matches: this is the value the hardcoded `"Box"` used to stand in
     * for.
     */
    @Test
    fun `a box engine's implicit receiver shortens to Box`() {
        val receiver = KotlinType(Box::class).typeName.substringAfterLast('.')
        assertEquals("Box", receiver)
    }
}

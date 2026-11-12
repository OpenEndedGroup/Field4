package afield.runtime.kotlin

import afield.ui.output.FieldMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A raw string's chain after formatting: ktfmt leaves `"""` on a line of its own and `.md` or
 * `.trimIndent()` alone on the next. [tuckRawStringChains] joins the chain to the quotes, and moves
 * both to the end of the text where that cannot change the value. Every case formats twice to the
 * same text.
 */
class KotlinFormatterRawStringTest {

    private fun format(code: String): String {
        val once = formatKotlinCode(code)
        assertEquals(once, formatKotlinCode(once), "formatting again changes nothing")
        return once
    }

    private fun assertNoLoneChain(formatted: String) {
        assertFalse(formatted.lines().any { it.trim().startsWith(".") }, "no line starts with a dangling call:\n$formatted")
    }

    @Test
    fun aSlidesMarkdownEndsOnItsLastLine() {
        val formatted = format(
            "figureBeside(\"dial\") {\n    +\"\"\"\n        # Further reading\n        - item\n    \"\"\"\n        .md\n}\n")
        assertTrue("- item\"\"\".md" in formatted, formatted)
        assertNoLoneChain(formatted)
    }

    @Test
    fun aRawStringInsideACallTucksItsTrimIndent() {
        val formatted = format("display(\"main\", \"\"\"\n    <b>one</b>\n    <i>two</i>\n\"\"\".trimIndent())\n")
        assertTrue("<i>two</i>\"\"\".trimIndent()" in formatted, formatted)
        assertNoLoneChain(formatted)
    }

    @Test
    fun trimIndentAndTrimMarginFromTheirOwnLine() {
        val indent = format("val a = \"\"\"\n    one\n    two\n\"\"\"\n    .trimIndent()\nprintln(a)\n")
        assertTrue("two\"\"\".trimIndent()" in indent, indent)
        val margin = format("val b = \"\"\"\n    |one\n    |two\n\"\"\"\n    .trimMargin()\nprintln(b)\n")
        assertTrue("|two\"\"\".trimMargin()" in margin, margin)
        assertNoLoneChain(indent)
        assertNoLoneChain(margin)
    }

    @Test
    fun anyOtherChainJoinsTheQuotesWithoutMovingThem() {
        // lines() would gain or lose its last, empty element if the quotes moved.
        val formatted = format("val parts = \"\"\"\n    one\n    two\n\"\"\"\n    .lines()\nprintln(parts)\n")
        assertTrue(Regex("""two\n\s*""\"\.lines\(\)""").containsMatchIn(formatted), formatted)
        assertNoLoneChain(formatted)
    }

    @Test
    fun aMdThatEscapesKeepsItsQuotes() {
        // Not under a unary +, `.md` is Any?.md, which escapes the text verbatim, trailing newline and all.
        val formatted = format("val escaped = \"\"\"\n    one\n    two\n\"\"\"\n    .md\nprintln(escaped)\n")
        assertTrue(Regex("""two\n\s*""\"\.md""").containsMatchIn(formatted), formatted)
    }

    @Test
    fun aLastLineEndingInAQuoteKeepsItsLine() {
        // `say "hi""""` would end the string one quote early.
        val formatted = format("val q = \"\"\"\n    say \"hi\"\n\"\"\"\n    .trimIndent()\nprintln(q)\n")
        assertTrue(Regex("""say "hi"\n\s*""\"\.trimIndent\(\)""").containsMatchIn(formatted), formatted)
    }

    @Test
    fun movingTheQuotesKeepsWhatTheStringComputesTo() {
        val text = "\n    # Further reading\n\n    - [A](https://example.org/)\n    - item\n\n    ```kotlin\n    val x = 1\n    ```\n    :::row\n    left\n    ---\n    right\n    :::end"
        for (tail in listOf("\n", "\n    ", "\n        ")) {
            assertEquals(FieldMarkdown.render(text), FieldMarkdown.render(text + tail), "a slide's Markdown ignores ${tail.length - 1} trailing spaces")
            assertEquals(text.trimIndent(), (text + tail).trimIndent())
            assertEquals(text.replace("    -", "    |-").trimMargin(), (text.replace("    -", "    |-") + tail).trimMargin())
        }
    }
}

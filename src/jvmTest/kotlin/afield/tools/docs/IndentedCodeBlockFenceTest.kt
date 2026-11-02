package afield.tools.docs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **An indented code block in a file overview publishes as a `diffstatic` fence**, like every other
 * code block on the site.
 *
 * Most file overviews write their examples as indented code blocks, because that is what reads well
 * in the source. [StdlibReferenceGenerator.fencesToDiffstatic] only rewrote triple-backtick fences,
 * so those blocks reached the reference pages as plain Markdown code — the site's default
 * highlighter, a line-number gutter and a copy button, beside prose whose inline code is Field's.
 * `reference/patterns.md`'s `Marf.kt` section was the case that showed it.
 */
class IndentedCodeBlockFenceTest {

    private fun convert(vararg lines: String) =
        StdlibReferenceGenerator.fencesToDiffstatic(lines.joinToString("\n")).split("\n")

    @Test
    fun indentedBlockAfterProseBecomesDedentedDiffstaticFence() {
        assertEquals(
            listOf(
                "Prose.", "",
                "```diffstatic {data-lang=kotlin}", "val a = f(", "    1)", "", "a.g()", "```",
                "", "More prose.",
            ),
            convert("Prose.", "", "       val a = f(", "           1)", "", "       a.g()", "", "More prose."),
        )
    }

    @Test
    fun indentedRunAfterListItemIsContinuationNotCode() {
        val text = listOf("- item", "", "    continued item text", "", "After.")
        assertEquals(text, convert(*text.toTypedArray()))
    }

    @Test
    fun indentedLinesInsideExistingFenceAreLeftAlone() {
        assertEquals(
            listOf("```diffstatic {data-lang=kotlin}", "fun f() {", "", "    body()", "}", "```"),
            convert("```", "fun f() {", "", "    body()", "}", "```"),
        )
    }
}

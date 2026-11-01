package afield.examples

import kotlin.test.Test
import kotlin.test.assertEquals

/** What `formatBoxCode` does to a docs page: Kotlin in `diffstatic` fences only, and never a fence the runner reads markers from. */
class BoxCodeFormatMarkdownTest {

    private fun fence(open: String, body: String) = "Some prose.\n\n$open\n$body\n```\n\nMore prose.\n"

    @Test
    fun aKotlinFenceIsFormatted() {
        val page = fence("```diffstatic_runner {data-lang=kotlin}", "val x = listOf(1,2,  3)")
        assertEquals(fence("```diffstatic_runner {data-lang=kotlin}", "val x = listOf(1, 2, 3)"), BoxCodeFormat.formatMarkdown(page, "t"))
        val bare = fence("```diffstatic", "val x = listOf(1,2,  3)")
        assertEquals(fence("```diffstatic", "val x = listOf(1, 2, 3)"), BoxCodeFormat.formatMarkdown(bare, "t"))
    }

    @Test
    fun fencesTheRunnerReadsOrThatAreNotKotlinAreLeftAlone() {
        for (page in listOf(
            fence("```diffstatic", ":::static\nval x = listOf(1,2,  3)"),
            fence("```diffstatic", "val x = listOf(1,2,  3)\n//%-\nval y=2"),
            fence("```diffstatic", "// FIELD_DOCS_EXPECT_SVG: true\nval x = listOf(1,2,  3)"),
            fence("```diffstatic {data-lang=glsl}", "void main(){}"),
            fence("```diff_runner", ":::before\nval x=1\n:::after\nval x=2"),
            fence("```diffstatic", "val x = listOf(1,2,"),
            fence("```kotlin", "val x = listOf(1,2,  3)"),
        )) assertEquals(page, BoxCodeFormat.formatMarkdown(page, "t"))
    }
}

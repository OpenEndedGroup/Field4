package afield.tools.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocsSearchTest {

    private val page = DocsSearch.parsePage(
        "running_code.md",
        """
        # Running code in a box

        Intro prose about launching.

        ## Once, and only once

        Use `once` to run setup a single time.

        ```kotlin {run}
        val x = 1
        once.renderer {
            println("first run")
        }
        ```

        ## Once, and only once

        !!! note
            ```kotlin
            once.renderer { }
            ```
        """.trimIndent()
    )

    @Test
    fun parsesTitleHeadingsAndFences() {
        assertEquals("Running code in a box", page.title)
        assertEquals("/field-docs/running_code/", page.url)
        val anchors = page.units.filter { it.kind == DocsSearch.Kind.SECTION }.map { it.anchor }
        assertEquals(listOf("once-and-only-once", "once-and-only-once_1"), anchors)
        val code = page.units.filter { it.kind == DocsSearch.Kind.CODE }
        assertEquals(listOf("kotlin", "kotlin"), code.map { it.language })
        assertTrue(code[1].text.startsWith("once.renderer"), "indented fence is dedented: ${code[1].text}")
    }

    @Test
    fun findsAnIdentifierFromAnExampleAtItsLine() {
        val response = DocsSearch.searchPages(listOf(page), "once.renderer", limit = 10, maxPerPage = 3)
        val code = response.hits.filter { it.kind == "code" }.sortedBy { it.line }
        assertEquals(listOf(11, 20), code.map { it.line })
        assertEquals("once.renderer {", code[0].snippet)
        assertEquals("/field-docs/running_code/#once-and-only-once", code[0].url)
        assertEquals("/field-docs/running_code/#once-and-only-once_1", code[1].url)
    }

    @Test
    fun titleBeatsProseAndAllTermsAreRequired() {
        val hits = DocsSearch.searchPages(listOf(page), "running box", limit = 10, maxPerPage = 3).hits
        assertEquals("page", hits.first().kind)
        assertTrue(DocsSearch.searchPages(listOf(page), "running zebra", 10, 3).hits.isEmpty())
    }

    @Test
    fun indexesTheRealDocs() {
        val root = DocsSearch.docsRoot() ?: return
        if (!File(root, "running_code.md").isFile) return
        val hits = DocsSearch.search("once.renderer", root = root).hits
        assertTrue(hits.any { it.kind == "code" && it.page == "running_code.md" }, hits.take(5).toString())
    }

    @Test
    fun slugMatchesPythonMarkdown() {
        assertEquals("frames-and-the-animation-loop", DocsSearch.slugify("Frames and the animation loop"))
        assertEquals("why-does-this-value-resolve-here", DocsSearch.slugify("Why does this value resolve here?"))
        assertEquals("box_output-channels", DocsSearch.slugify("box_output — channels"))
    }
}

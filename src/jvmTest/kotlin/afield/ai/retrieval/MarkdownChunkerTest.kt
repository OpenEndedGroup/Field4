package afield.ai.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The four faults [MarkdownChunker] was written against, measured over public/docs on 2026-10-22. */
class MarkdownChunkerTest {
    @Test fun aCommentInACodeFenceIsNotAHeading() {
        val chunks = MarkdownChunker.chunks("""
            # Shaders
            Some prose about shaders.
            ```bash
            # build the bundle
            npm run build
            ```
            More prose.
        """.trimIndent().lines())
        assertEquals(listOf("Shaders"), chunks.map { it.title })
        assertTrue("# build the bundle" in chunks.single().body, "the fence stays in its section as code")
    }

    @Test fun eachChunkKnowsThePageAndHeadingsAboveIt() {
        val chunks = MarkdownChunker.chunks("""
            # Field probes
            Intro.
            ## Kotlin side
            Read values from a box.
            ### Sampling
            Every frame.
            ## GLSL side
            Write from a shader.
        """.trimIndent().lines())
        assertEquals(listOf("Field probes", "Field probes › Kotlin side", "Field probes › Kotlin side › Sampling", "Field probes › GLSL side"),
            chunks.map { it.breadcrumb })
    }

    @Test fun markupOutsideCodeIsReducedToItsText() {
        val line = """| <a id="x"></a>[<code class="highlight"><span class="k">fun</span> <span class="nf">orbit</span></code>](source/BoidSketch/#L184) | Circle a point at [`radius`](simulation.md#r) pixels. |"""
        val cleaned = MarkdownChunker.cleanProse(line)
        assertFalse("<span" in cleaned || "](" in cleaned || "<a id" in cleaned, cleaned)
        assertTrue("fun orbit" in cleaned && "`radius`" in cleaned, cleaned)
        assertEquals("Film", MarkdownChunker.chunks(listOf("### Film { #model-kt-film }", "A film.", "More.")).single().title)
    }

    @Test fun aLongSectionIsSplitOnParagraphBoundariesWithinTheLimit() {
        val paragraph = "word ".repeat(80).trim()   // ~400 chars
        val lines = listOf("# Long") + (1..12).flatMap { listOf(paragraph, "") }
        val chunks = MarkdownChunker.chunks(lines, maxChars = 1_000)
        assertTrue(chunks.size >= 4, "${chunks.size} chunks")
        assertTrue(chunks.all { it.body.length <= 1_000 }, chunks.map { it.body.length }.toString())
        assertEquals((1..chunks.size).toList(), chunks.map { it.part })
        assertTrue(chunks.all { it.breadcrumb == "Long" })
    }

    @Test fun theMachineHeaderOfAMaterializedDocumentIsDropped() {
        val chunks = MarkdownChunker.chunks("""
            # Field Document: hello_display
            This is a machine-readable narrative mirror.
            ```edn [field-document version="1" source="metadata.edn"]
            {"format" "field-document-md"}
            ```
        """.trimIndent().lines())
        assertFalse(chunks.any { "field-document-md" in it.body })
    }
}

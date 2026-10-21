package afield.examples

import afield.app.server.docsRedirect
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StartingPointDocsGuidesTest {
    @Test fun aTranscriptsDocsListBecomesRedirectGuidesTitledByThePage() {
        val root = createTempDirectory("starting-point-docs").toFile()
        try {
            val docs = root.resolve("docs").also { it.mkdirs() }
            docs.resolve("drawing_and_notation.md").writeText("# Drawing and notation\n\n## Reading a region\n\nText.")
            docs.resolve("reference").mkdirs()
            docs.resolve("reference/audio.md").writeText("# Audio reference\n")
            val yaml = root.resolve("t.yaml")
            yaml.writeText("""
                name: t
                docs:
                  - page: drawing_and_notation.md#reading-a-region
                    why: How box code reads marks inside a region.
                  - reference/audio.md
                operations: []
            """.trimIndent())
            val target = root.resolve("out").also { it.mkdirs() }
            AgentTranscriptMaterializer.writeDocsGuides(AgentTranscriptMaterializer.docsEntries(yaml), docs, target)

            val notation = target.resolve("guides/drawing_and_notation-reading-a-region.md").readText()
            val redirect = assertNotNull(docsRedirect(notation))
            assertEquals("drawing_and_notation/#reading-a-region", redirect.page)
            assertEquals("Drawing and notation · Reading a region", redirect.title)
            assertEquals("How box code reads marks inside a region.", redirect.summary)
            assertEquals("reference/audio/", docsRedirect(target.resolve("guides/reference-audio.md").readText())?.page)
        } finally { root.deleteRecursively() }
    }

    @Test fun aMissingPageOrHeadingFailsTheMaterialization() {
        val root = createTempDirectory("starting-point-docs-missing").toFile()
        try {
            val docs = root.resolve("docs").also { it.mkdirs() }
            docs.resolve("page.md").writeText("# Page\n")
            val target = root.resolve("out").also { it.mkdirs() }
            for (page in listOf("gone.md", "page.md#no-such-heading", "../outside.md", "page")) {
                assertFailsWith<IllegalArgumentException>(page) {
                    AgentTranscriptMaterializer.writeDocsGuides(listOf(AgentTranscriptMaterializer.DocsEntry(page)), docs, target)
                }
            }
        } finally { root.deleteRecursively() }
    }
}

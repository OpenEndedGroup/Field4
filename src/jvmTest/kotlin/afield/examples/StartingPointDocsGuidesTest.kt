package afield.examples

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.persistence.SavingContext
import afield.app.server.docsRedirect
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StartingPointDocsGuidesTest {
    @Test fun rootSceneStateSurvivesStartingPointSerialization() {
        val root = Box("root")
        val session = SessionInformation("/tmp/starting-point-root-scene", "scene", root = root, ephemeral = true)
        root.inside = session
        session.boxes[root.id] = root
        assertTrue(AgentTranscriptMaterializer.boxesForSave(session).isEmpty(), "an empty root must not churn existing documents")
        val drawing = """{"type":"excalidraw","elements":[]}"""
        for ((property, value) in listOf("drawing" to drawing, "panes" to "{}", "bookmarks" to "[]")) {
            root.setPropertyRaw(property, value, doResync = false)
        }
        val transfers = SavingContext().toTransfer(AgentTranscriptMaterializer.boxesForSave(session), session.filename)
        val persisted = transfers.single { it.id == "root" }.properties
        assertEquals(drawing, persisted["drawing"])
        assertEquals("{}", persisted["panes"])
        assertEquals("[]", persisted["bookmarks"])
    }

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

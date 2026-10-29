package afield.tools.docs

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.output.history

import afield.app.server.documents
import afield.document.workspace.openStartingPointIntoWorkspace
import afield.document.workspace.searchStartingPoints
import afield.document.workspace.startingPoints
import afield.runtime.lifecycle.map
import afield.ui.output.templates.filter
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CanonicalGraphicsStartingPointTest {
    @Test fun graphicsCollectionIsListedSearchableAndCopiedWithItsSidecars() {
        val temp = Files.createTempDirectory("graphics-starting-point-").toFile()
        val old = System.getProperty("afield.startingPoints.dir")
        try {
            val bundles = File(temp, "assets/agent_transcripts").apply { mkdirs() }
            val source = File(temp, "assets/canonical_graphics")
            File("public/docs/assets/canonical_graphics").copyRecursively(source)
            val originalMetadata = File(source, "metadata.edn").readText()
            val hidden = File(temp, "assets/unlisted_document").apply { mkdirs() }
            File(hidden, "metadata.edn").writeText(originalMetadata)
            System.setProperty("afield.startingPoints.dir", bundles.absolutePath)

            val card = startingPoints().single()
            assertEquals("canonical_graphics", card.filename)
            assertEquals("starting-point", card.kind)
            assertTrue(card.numBoxes > 30)
            val search = searchStartingPoints("SlugPath", maxScanMillis = 15_000)
            assertEquals(listOf("canonical_graphics"), search.documents.map { it.filename })
            assertEquals("starting-point", search.documents.single().kind)

            val workspace = File(temp, "workspace").apply { mkdirs() }
            File(source, "backups").mkdirs()
            File(source, "backups/old.edn").writeText("history")
            val opened = openStartingPointIntoWorkspace(workspace.absolutePath, card.filename)
            assertEquals("canonical_graphics", opened.filename)
            val copy = File(workspace, opened.filename)
            assertEquals(originalMetadata, File(copy, "metadata.edn").readText())
            for (file in source.listFiles().orEmpty().filter { it.isFile }) {
                assertContentEquals(file.readBytes(), File(copy, file.name).readBytes(), file.name)
            }
            assertFalse(File(copy, "backups").exists())
            File(copy, "metadata.edn").writeText("user edit")
            assertEquals(originalMetadata, File(source, "metadata.edn").readText())
            assertEquals("canonical_graphics-2",
                openStartingPointIntoWorkspace(workspace.absolutePath, card.filename).filename)
            assertFailsWith<IllegalArgumentException> {
                openStartingPointIntoWorkspace(workspace.absolutePath, "unlisted_document")
            }
        } finally {
            if (old == null) System.clearProperty("afield.startingPoints.dir")
            else System.setProperty("afield.startingPoints.dir", old)
            temp.deleteRecursively()
        }
    }

    @Test fun stackSLConversationsOpenWithTheirChatAndStudies() {
        val workspace = Files.createTempDirectory("stacksl-starting-point-").toFile()
        try {
            val names = listOf("stacksl_conversation_ink", "stacksl_conversation_tiles", "stacksl_conversation_aurora")
            assertTrue(startingPoints().map { it.filename }.containsAll(names))
            for (name in names) {
                val copy = File(workspace, openStartingPointIntoWorkspace(workspace.absolutePath, name).filename)
                val source = File("dev_docs/agents/demos/$name")
                assertContentEquals(File(source, "stacksl_source_ai4_chat_history_.md").readBytes(),
                    File(copy, "stacksl_source_ai4_chat_history_.md").readBytes(), name)
                assertTrue(File(copy, "studies/stacksl_source").listFiles().orEmpty().isNotEmpty(), name)
                assertTrue(File(copy, "conversations").isDirectory, name)
            }
        } finally {
            workspace.deleteRecursively()
        }
    }
}

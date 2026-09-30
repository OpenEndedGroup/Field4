package afield

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
}

package afield.document.persistence

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineEditWhileClosedTest {
    private val dirs = mutableListOf<File>()

    @AfterTest fun clean() { dirs.forEach { it.deleteRecursively() } }

    private fun document(sidecar: String, inline: String, metadataNewerByMs: Long): Pair<File, FileInline> {
        val dir = Files.createTempDirectory("afield-inline-closed").toFile().also { dirs += it }
        val file = File(dir, "a_code_.kt").apply { writeText(sidecar) }
        val metadata = File(dir, SavingContext.METADATA_EDN).apply { writeText("{}") }
        val t = System.currentTimeMillis() - 60_000
        file.setLastModified(t)
        metadata.setLastModified(t + metadataNewerByMs)
        return dir to FileInline("a_code_.kt", inline)
    }

    @Test fun anInlineEditNewerThanTheFileWinsAndTheFileIsBackedUp() {
        val (dir, value) = document("val x = 1", "val x = 2", metadataNewerByMs = 10_000)
        assertEquals("val x = 2", SavingContext().sidecarOrNewerInline(File(dir, value.path), value, dir.path))
        val backups = File(dir, "backups").listFiles().orEmpty()
        assertTrue(backups.any { it.name.startsWith("a_code_.kt.before-inline-") && it.readText() == "val x = 1" }, backups.toList().toString())
    }

    @Test fun aFileNewerThanMetadataWinsAsBefore() {
        val (dir, value) = document("val x = 1", "val x = 2", metadataNewerByMs = -10_000)
        assertEquals("val x = 1", SavingContext().sidecarOrNewerInline(File(dir, value.path), value, dir.path))
    }

    @Test fun filesWrittenTogetherKeepTheFile() {
        // a checkout or a save writes both within moments; that is not an inline edit
        val (dir, value) = document("val x = 1", "val x = 2", metadataNewerByMs = 500)
        assertEquals("val x = 1", SavingContext().sidecarOrNewerInline(File(dir, value.path), value, dir.path))
        assertTrue(!File(dir, "backups").exists())
    }
}

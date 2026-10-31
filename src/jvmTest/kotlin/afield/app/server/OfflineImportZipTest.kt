package afield.app.server

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class OfflineImportZipTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A docs download unzips to one folder; the import puts its contents in the new document. */
    @Test
    fun aDownloadsTopFolderIsRemovedAndEveryFolderBesideTheDocumentKept() {
        val dir = createTempDirectory("offline-import").toFile()
        try {
            val count = unpackDocumentZip(zip(
                "example/metadata.edn" to "{:version 2}",
                "example/box_ai4_chat_history_.md" to "chat",
                "example/studies/box/r1.kt" to "revision",
            ), dir)
            assertEquals(3, count)
            assertEquals("{:version 2}", dir.resolve("metadata.edn").readText())
            assertEquals("chat", dir.resolve("box_ai4_chat_history_.md").readText())
            assertEquals("revision", dir.resolve("studies/box/r1.kt").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aZipWithTheDocumentAtItsRootIsTakenAsIs() {
        val dir = createTempDirectory("offline-import").toFile()
        try {
            unpackDocumentZip(zip("metadata.edn" to "{}", "a/b.txt" to "b"), dir)
            assertTrue(dir.resolve("metadata.edn").isFile)
            assertTrue(dir.resolve("a/b.txt").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anEntryOutsideTheDocumentOrNoMetadataFailsAndWritesNothing() {
        val parent = createTempDirectory("offline-import").toFile()
        val dir = parent.resolve("doc").apply { mkdirs() }
        try {
            assertFails { unpackDocumentZip(zip("metadata.edn" to "{}", "../escape.txt" to "x"), dir) }
            assertFalse(parent.resolve("escape.txt").exists())
            assertFalse(dir.resolve("metadata.edn").exists(), "checked before anything is written")
            assertFails { unpackDocumentZip(zip("readme.md" to "x"), dir) }
        } finally {
            parent.deleteRecursively()
        }
    }
}

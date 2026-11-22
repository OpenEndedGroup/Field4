package afield.document.history

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A version name belongs to its backup file, not to a position in the version list, so deleting an
 * earlier backup does not move the name onto a neighbouring version.
 */
class HistoryVersionNamesTest {

    private fun document(backups: Int): java.io.File {
        val doc = Files.createTempDirectory("history-names").toFile()
        val dir = doc.resolve("backups").apply { mkdirs() }
        repeat(backups) { i -> dir.resolve("metadata_backup_%05d.edn".format(i)).writeText("{:version 2 :boxes []}") }
        doc.resolve("metadata.edn").writeText("{:version 2 :boxes []}")
        return doc
    }

    @Test
    fun `a name stays with its backup when an earlier backup is deleted`() {
        val doc = document(3)
        val path = doc.absolutePath
        try {
            HistoryManager.clearCache(path)
            HistoryManager.setVersionName(path, 2, "keeper")
            HistoryManager.setVersionName(path, 3, "current")
            assertEquals(mapOf(2 to "keeper", 3 to "current"), HistoryManager.getVersionNames(path))

            doc.resolve("backups/metadata_backup_00000.edn").delete()
            HistoryManager.clearCache(path)
            assertEquals(mapOf(1 to "keeper", 2 to "current"), HistoryManager.getVersionNames(path))
        } finally {
            HistoryManager.clearCache(path)
            doc.deleteRecursively()
        }
    }

    @Test
    fun `a versions file keyed by position is read against the current list`() {
        val doc = document(3)
        val path = doc.absolutePath
        try {
            // Backup 00000 is already gone, so positions 0..2 are backups 1, 2 and the current file.
            doc.resolve("backups/metadata_backup_00000.edn").delete()
            doc.resolve("versions.edn").writeText("{\"transitions\" [] \"names\" {1 \"old\"}}")
            HistoryManager.clearCache(path)
            assertEquals(mapOf(1 to "old"), HistoryManager.getVersionNames(path))
            // Writing anything back stores backup numbers, so the next deletion keeps it in place.
            HistoryManager.setVersionName(path, 0, "first")
            doc.resolve("backups/metadata_backup_00001.edn").delete()
            HistoryManager.clearCache(path)
            assertEquals(mapOf(0 to "old"), HistoryManager.getVersionNames(path))
            assertNull(HistoryManager.getVersionNames(path)[1])
        } finally {
            HistoryManager.clearCache(path)
            doc.deleteRecursively()
        }
    }
}

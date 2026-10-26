package afield.document.history

import afield.app.protocol.HistoryDiffStats
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.document.graph.Box
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** History helpers themselves enforce read-only, even when invoked without the browser handlers. */
class HistoryMetricsReadOnlyTest {
    private fun inDocument(readOnly: Boolean, seed: Boolean = false, action: (File) -> Unit) {
        val directory = Files.createTempDirectory("history-readonly-").toFile()
        File(directory, "metadata.edn").writeText("{}\n")
        if (seed) HistoryMetrics.addScreenshotRef(directory.absolutePath, 0, "fixture", "missing.png", timestamp = 1)
        val root = Box("root")
        val session = SessionInformation(directory.absolutePath, directory.name, root = root, readOnly = readOnly)
        root.inside = session
        documents[session.filename] = session
        try {
            action(directory)
        } finally {
            documents.remove(session.filename, session)
            directory.deleteRecursively()
        }
    }

    @Test
    fun addingMetricsCannotCreateFilesInAReadOnlyDocument() {
        for (readOnly in listOf(false, true)) inDocument(readOnly) { directory ->
            HistoryMetrics.addScreenshotRef(directory.absolutePath, 0, "fixture", "missing.png", timestamp = 1)
            assertEquals(!readOnly, File(directory, "history_metrics.edn").isFile)
        }
    }

    @Test
    fun editStatsCannotRewriteExistingReadOnlyMetrics() {
        for (readOnly in listOf(false, true)) inDocument(readOnly, seed = true) { directory ->
            val metrics = File(directory, "history_metrics.edn")
            val before = metrics.readBytes()
            HistoryMetrics.recordEditStats(directory.absolutePath, 0, HistoryDiffStats(boxesEdited = 7, boxesMoved = 3))
            if (readOnly) assertContentEquals(before, metrics.readBytes())
            val activity = HistoryMetrics.activityByVersion(directory.absolutePath).getValue(0)
            assertEquals(if (readOnly) 0 else 7, activity.editedBoxes)
            assertEquals(if (readOnly) 0 else 3, activity.movedBoxes)
        }
    }

    @Test
    fun screenshotHelpersCannotCreateReadOnlyDirectoriesOrImages() {
        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output))
        val bytes = output.toByteArray()
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        for (readOnly in listOf(false, true)) inDocument(readOnly) { directory ->
            val reserved = HistoryMetrics.reserveScreenshotFile(directory.absolutePath, versionIndex = 0, channel = "reserved")
            val stored = HistoryMetrics.storeScreenshotDataUrl(directory.absolutePath, "stored", dataUrl, versionIndex = 0)
            if (readOnly) {
                assertNull(reserved)
                assertNull(stored)
                assertEquals(listOf("metadata.edn"), directory.list()!!.toList())
            } else {
                assertNotNull(reserved)
                assertTrue(reserved.first.parentFile.isDirectory)
                assertNotNull(stored)
                assertContentEquals(bytes, File(directory, stored.path).readBytes())
                assertTrue(File(directory, "history_metrics.edn").isFile)
            }
        }
    }
}

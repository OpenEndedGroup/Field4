@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.FieldMarkdown
import afield.RunsAt
import afield.md
import afield.newRoot
import afield.registerValuePrinter
import java.util.Base64

/**
 * `report.h`: a [StudyReport] as a card — its revision, its message if it has one, and its pictures at
 * their own pixel size (at most [MAX_WIDTH] CSS px), so a box shows what the model was shown with
 * `display("main", report.h)` rather than an `<img>` it builds itself.
 *
 * Registered as a value printer rather than written into [StudyReport], which stays the plain result
 * type every study returns.
 */
object StudyReportView {
    /** Widest a picture is drawn, in CSS px. */
    const val MAX_WIDTH = 720

    /** The width a `data:image/png;base64,` URL's image is, read from its IHDR, or null if it is not one. */
    fun pngWidth(dataUrl: String): Int? {
        val b64 = dataUrl.substringAfter("base64,", "").takeIf { it.length >= 44 } ?: return null
        val head = runCatching { Base64.getDecoder().decode(b64.take(44)) }.getOrNull() ?: return null
        if (head.size < 24 || head[1] != 'P'.code.toByte() || head[2] != 'N'.code.toByte()) return null
        return ((head[16].toInt() and 0xff) shl 24) or ((head[17].toInt() and 0xff) shl 16) or
            ((head[18].toInt() and 0xff) shl 8) or (head[19].toInt() and 0xff)
    }

    /** The card: `revision · message`, then each picture. */
    fun html(report: StudyReport): String {
        val message = (report.facts["message"] as? String)?.let { " {muted:${it.md}}" }.orEmpty()
        val pictures = report.toolImages.joinToString("\n\n") { image ->
            val width = minOf(pngWidth(image.dataUrl) ?: MAX_WIDTH, MAX_WIDTH)
            """<img src="${image.dataUrl}" alt="${image.caption.replace("\"", "&quot;")}" style="width:${width}px"/>"""
        }
        return FieldMarkdown.card("{accent:${report.revision.md}}$message\n\n$pictures")
    }
}

/** Registers [StudyReportView] as `.h` for every [StudyReport]. */
@RunsAt(newRoot)
fun registerStudyReportView() {
    registerValuePrinter<StudyReport> { StudyReportView.html(it) }
}

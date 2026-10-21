@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.study.ui

import afield.animation.keyframes.h
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.io.osc.main
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.simulation.automata.main
import afield.ui.output.h

import afield.audio.synth.*
import afield.math.autodiff.minOf
import afield.media.video.image
import afield.study.evidence.StudyReport
import afield.ui.output.FieldMarkdown
import afield.runtime.lifecycle.RunsAt
import afield.ui.output.md
import afield.runtime.lifecycle.newRoot
import afield.ui.visualization.registerValuePrinter
import java.util.Base64

/**
 * Renders a [StudyReport] as an HTML card, so `display("main", report.h)` shows a study's revision,
 * message and pictures in a box's output.
 *
 * [StudyReportView.html] builds the card and draws each picture at its own PNG width, capped at
 * [StudyReportView.MAX_WIDTH]; [registerStudyReportView] installs it as the `.h` value printer when
 * a new root starts.
 */


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

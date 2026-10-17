@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.ui.output

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.open
import afield.media.video.image
import afield.study.ui.numbers

import afield.audio.synth.*
import afield.media.image.split
import afield.runtime.lifecycle.RunsAt
import afield.ai.tools.AgentToolExtensions
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolImage
import afield.runtime.lifecycle.plugins
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/** The AI4 tool that reads a program's line notes ([LineNotes]) — what the editor shows beside the cursor. */
@JsonClassDescription(
    "Read the line notes of the focused box's program: for each requested line, what the editor shows about it. " +
        "StackSL and RectSL draw the partial evaluation up to the line; Forth, Clojure and Python report what the line " +
        "produced on the box's LAST RUN (the stack, the value) and say when the line has changed since; Datalog answers " +
        "from the box's model (what a rule derives, a query's answers). Reading a note never runs the program. " +
        "Pictures come back as images."
)
data class ReadLineNotes(
    @JsonPropertyDescription("1-based line numbers of the focused property's current text; at most 12.")
    val lines: List<Int>,
)

/** One line's note, as [ReadLineNotes] answers it. */
data class LineNoteAnswer(val line: Int, val text: String, val caption: String?, val hasPicture: Boolean)

/** [ReadLineNotes]'s answer: a note per line asked about (null caption: nothing to say), pictures as images. */
data class ReadLineNotesResult(
    val box: String,
    val property: String,
    val notes: List<LineNoteAnswer>,
    @com.fasterxml.jackson.annotation.JsonIgnore override val toolImages: List<ToolImage> = emptyList(),
) : CarriesToolImages, SummarisesForToolLog {
    override fun toolLogSummary(): String = "${notes.count { it.caption != null }}/${notes.size} lines noted, ${toolImages.size} pictures"
}

/**
 * Offer [ReadLineNotes] to a conversation focused on a program some language gives notes for — not a
 * plain Kotlin box's `code`, which only has them inside an embedded StackSL or RectSL string and
 * would carry the schema on every turn for nothing.
 */
@RunsAt(plugins)
fun installLineNoteTools() {
    val offered: (ToolExecutionContext) -> Boolean = { ctx ->
        val focus = ctx.focus
        focus != null && LineNotes.offersFor(focus.box, focus.property) &&
            !(focus.property == "code" && focus.box.ke.languageName.equals("kotlin", ignoreCase = true))
    }
    AgentToolExtensions.register(ReadLineNotes::class.java, offered, "afield.stdlib.line-notes") { ctx, request ->
        val focus = ctx.focus ?: throw IllegalStateException("ReadLineNotes works on the box whose chat is open")
        readLineNotes(focus.box, focus.property, request.lines)
    }
}

/** [ReadLineNotes] for [box]'s [property] as it holds it now: the note for each of [lines] (at most 12). */
fun readLineNotes(box: afield.document.graph.Box, property: String, lines: List<Int>): ReadLineNotesResult {
    val source = box.getPropertyRaw(property, null) as? String ?: ""
    val text = source.split("\n")
    val images = ArrayList<ToolImage>()
    val notes = lines.distinct().take(12).map { line ->
        val note = if (line in 1..text.size) runCatching { LineNotes.note(box, property, source, line) }.getOrNull() else null
        note?.png?.let { images += ToolImage("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(it), "line $line: ${note.caption}") }
        LineNoteAnswer(line, text.getOrElse(line - 1) { "" }, note?.caption, note?.png != null)
    }
    return ReadLineNotesResult(box.name, property, notes, images)
}

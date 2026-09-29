package afield.rectsl.study

import afield.Box
import afield.RunsAt
import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext
import afield.plugins
import afield.stdlib.FileManager
import afield.stdlib.StudySourceEdit
import afield.stdlib.numbers
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// The tools an AI4 conversation gets when it is opened on a RectSL scene's `rectSL` property. Each
// works on the focused box's own study (see RectSLPrograms), so nothing here is per document. A
// document can still add tools of its own from a box: AgentToolExtensions.register with its document
// as the scope.

@JsonClassDescription("Read how to work on a RectSL study: the wall and its coordinates, idioms that hold, traps, the tools and the style a person expects. Call it once, before your first edit in a conversation. With search, return the words guide's entries matching a word or phrase instead.")
data class RectSLStudyGuide(
    @JsonPropertyDescription("A word or phrase to look up in the RectSL words guide; empty returns the working guide") val search: String = ""
)

@JsonClassDescription("Read this RectSL study: the checked scene, whether the editor holds an unchecked human edit (dirty; then liveSource is what is in the box and liveCheck says whether it compiles), revision history, the scene's shape (wall, duration, rectangles, what is visible when), the analysis's warnings, the lines that make rectangles, knobs (typed numbers and colours, one line each: index, line:column, as typed, what reads it), and personAt — the line the person's editor cursor is on, which a click on a rectangle in the window moves to the line that made it, so 'this one' usually means it. Inspect once before editing and reuse liveSourceHash. The window above the box plays the scene live; prefer ObserveRectSLStudy or TimelineRectSLStudy, which draw exact times.")
data class InspectRectSLStudy(val revision: String = "current")

@JsonClassDescription("Replace the whole RectSL program. Compiles it (materials included), samples and draws it at probe times, then activates it as a new revision and returns warnings plus an image preview against the parent. Needs expectedRevision and expectedSourceHash from Inspect; a stale guard or failed check changes nothing. Prefer PatchRectSLStudy or SetRectSLNumbers for small edits.")
data class EditRectSLStudy(
    val expectedRevision: String,
    val expectedSourceHash: String,
    @JsonPropertyDescription("Complete RectSL source. Keep it human-editable: scene words first, one object per paragraph with a # comment saying what it is, named rectangles (\"sun\" rect), : name ... ; definitions for repeated motifs.")
    val source: String,
    val description: String,
    @JsonPropertyDescription("Times in seconds for the preview frames; empty chooses four evenly across the clip.")
    val previewSeconds: List<Double> = emptyList()
)

@JsonClassDescription("Preferred for local edits: apply exact, unique, non-overlapping text replacements to the live source, then check and activate like EditRectSLStudy. Returns warnings and a before/after preview.")
data class PatchRectSLStudy(val expectedRevision: String, val expectedSourceHash: String,
    val edits: List<StudySourceEdit>, val description: String, val previewSeconds: List<Double> = emptyList())

/** One knob to set, by its index in the live source's knob list, and the value to write there. */
data class RectSLNumberEdit(
    override val knob: Int,
    @JsonPropertyDescription("The new value; for a hex colour knob, the colour as a 24-bit number (0xff8800 is 16746496)") override val value: Double
) : afield.stdlib.NumberEdit

@JsonClassDescription("Set typed numbers and hex colours (knobs, by index from Inspect) to new values, rewritten in place. Checks and activates like an edit.")
data class SetRectSLNumbers(val expectedRevision: String, val expectedSourceHash: String,
    val numbers: List<RectSLNumberEdit>, val description: String, val previewSeconds: List<Double> = emptyList())

@JsonClassDescription("See a revision: exact frames at the given times (a still scene draws once) sent as an image, with how many rectangles are visible, luma, detail, colourfulness, palette and motion between frames. compareTo adds that revision's frames and a difference map. renderSeconds says what drew them: the window's GPU, or the CPU reference when there is no window.")
data class ObserveRectSLStudy(val revision: String = "current", val seconds: List<Double> = emptyList(),
    @JsonPropertyDescription("Pixels along the wall's longer side, 48..1024; 0 chooses 512 for one frame and 320 for several") val size: Int = 0,
    val compareTo: String = "")

@JsonClassDescription("See the whole clip at once: frames evenly across the duration, and a chart of how many rectangles are visible, how much of the wall they cover and how fast they move, with the events a viewer would notice (a rectangle entering or leaving the wall, stopping, starting, turning, fading, touching another), each named with the line that made it. Use it to judge pacing and to find the moment something goes wrong.")
data class TimelineRectSLStudy(val revision: String = "current",
    @JsonPropertyDescription("How many frames, 2..16") val frames: Int = 8)

@JsonClassDescription("Find which line made what. mode identify draws the frame at seconds with every visible rectangle outlined in its line's colour and labelled L<line>, and lists the lines with example rectangles (centre, size, fill); use it to answer 'which of these is the one on line 12?' or 'what is that shape?'. mode build shows the scene after each line that makes something. Give probeX and probeY (a wall point) to also get everything covering that point at seconds, topmost first, with the line that made each — the way to answer 'what is this, and why is it here?'.")
data class TraceRectSLStudy(val revision: String = "current", val seconds: Double = 0.0, val mode: String = "identify",
    @JsonPropertyDescription("x of a wall point to probe, in wall units (the frame's convention); omit for none") val probeX: Double? = null,
    @JsonPropertyDescription("y of a wall point to probe, in wall units; omit for none") val probeY: Double? = null)

@JsonClassDescription("List the knobs (typed numbers and colours) with what reads each and, unless sensitivity is false, how strongly a 10% nudge to each changes the frames — which numbers matter and which do nothing at these times.")
data class KnobsRectSLStudy(val revision: String = "current", val seconds: List<Double> = emptyList(), val sensitivity: Boolean = true)

@JsonClassDescription("Draw one knob at 2..9 values side by side at one time, without publishing anything. Use it to choose a value before SetRectSLNumbers.")
data class SweepRectSLNumber(val revision: String = "current", val knob: Int, val values: List<Double>, val seconds: Double = 0.0)

@JsonClassDescription("Speculate: check and draw 1..8 candidate scenes beside the current one without publishing. Each variant is a complete source or exact edits to the live source, with a short label and note. They are held so the person can Adopt one in the study card, or you can with AdoptRectSLVariant. Labels are short (A, B or 'drift').")
data class TryRectSLVariants(val variants: List<RectSLStudy.Variant>, val seconds: List<Double> = emptyList())

@JsonClassDescription("Publish a held variant by label as a new revision (guards as for an edit).")
data class AdoptRectSLVariant(val expectedRevision: String, val expectedSourceHash: String, val label: String, val description: String = "")

@JsonClassDescription("Make an earlier revision current again, as a new revision; history is only appended to.")
data class RestoreRectSLRevision(val expectedRevision: String, val expectedSourceHash: String, val revision: String, val description: String = "")

@JsonClassDescription("Check what an edit preserved: pixels (mode rgb, or luma for brightness only) held to one level outside allowedRegion [x0,y0,x1,y1] in wall units, and — by id — which rectangles changed (position, size, rotation, opacity, fill, material, order), appeared or went, grouped by the line that made them. Name rectangles so their ids survive edits.")
data class CompareRectSLStudy(val beforeRevision: String, val afterRevision: String = "current", val seconds: List<Double> = emptyList(),
    val mode: String = "rgb", val allowedRegion: List<Double> = emptyList())

@JsonClassDescription("Export a checked revision into this document's files directory: kind png (one frame at seconds) or mp4 (the whole clip at the scene's fps, repeated loops times; needs ffmpeg). Runs in the background and returns at once with an estimate, timed from one real frame, and refuses one over maxMinutes with a size that fits; tell the person where the file will appear and how long it should take. Progress is in InspectRectSLStudy's exports. The filename is used literally. Refuses an unchecked editor edit; overwrite only when asked.")
data class ExportRectSLStudy(val revision: String, val expectedSourceHash: String, val filename: String, val kind: String = "png",
    @JsonPropertyDescription("Pixels along the wall's longer side; 0 means 1920 for png and 1280 for mp4") val size: Int = 0,
    @JsonPropertyDescription("The still's time in seconds") val seconds: Double = 0.0,
    val loops: Int = 1, val overwrite: Boolean = false,
    @JsonPropertyDescription("Refuse an export estimated to take longer than this many minutes; the refusal names a size that fits. Raise it only if the person agrees to wait") val maxMinutes: Double = 10.0)

/** The working guide and the words guide the [RectSLStudyGuide] tool serves. */
object RectSLStudyGuides {
    private fun resource(name: String): String =
        RectSLStudyGuides::class.java.getResourceAsStream("/afield/rectsl/study/$name")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("missing resource afield/rectsl/study/$name")

    /** How to work on a RectSL study. */
    val working: String by lazy { resource("working.md") }

    /** Every RectSL word with its signature and a line of description, as Markdown sections. */
    val words: String by lazy { resource("rectsl_words.md") }

    /** The words guide's entries (lines, with their section heading) that mention [query], at most [limit] of them. */
    fun search(query: String, limit: Int = 40): String {
        val needle = query.trim().lowercase()
        var heading = ""
        val hits = mutableListOf<String>()
        for (line in words.lines()) {
            if (line.startsWith("#")) { heading = line.trimStart('#').trim(); continue }
            if (needle.isNotEmpty() && needle in line.lowercase()) hits += (if (heading.isEmpty()) "" else "[$heading] ") + line.trim()
            if (hits.size >= limit) break
        }
        return if (hits.isEmpty()) "No entry in the words guide mentions '$query'." else hits.joinToString("\n")
    }
}

/** A conversation is about a RectSL scene when it was opened on a box's `rectSL` property. */
private val onAScene: (ToolExecutionContext) -> Boolean = { ctx ->
    ctx.focus?.let { RectSLPrograms.isScene(it.box, it.property) } == true
}

/** The scope the library's tools register under, so a document's own tool of the same name adds to them. */
private const val LIBRARY = "afield.rectsl.study"

private fun scene(ctx: ToolExecutionContext): Box =
    ctx.focus?.box ?: throw IllegalStateException("RectSL study tools work on the box whose chat is open")

private fun studyOf(ctx: ToolExecutionContext): RectSLStudy = RectSLPrograms.readyStudy(scene(ctx))

/** Register the RectSL study tools once, for every conversation opened on a scene's `rectSL` property. */
@RunsAt(plugins)
fun installRectSLStudyTools() {
    val r = AgentToolExtensions
    r.register(RectSLStudyGuide::class.java, onAScene, LIBRARY) { _, q ->
        RectSLGuideText(if (q.search.isBlank()) RectSLStudyGuides.working else RectSLStudyGuides.search(q.search))
    }
    r.register(InspectRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).inspect(q.revision) }
    r.register(EditRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description, q.previewSeconds) }
    r.register(PatchRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description, q.previewSeconds) }
    r.register(SetRectSLNumbers::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).setNumbers(q.expectedRevision, q.expectedSourceHash, q.numbers.numbers(), q.description, q.previewSeconds) }
    r.register(ObserveRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).observe(q.revision, q.seconds, q.size, q.compareTo) }
    r.register(TimelineRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).timeline(q.revision, q.frames) }
    r.register(TraceRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).trace(q.revision, q.seconds, q.mode, probeX = q.probeX ?: Double.NaN, probeY = q.probeY ?: Double.NaN) }
    r.register(KnobsRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).knobs(q.revision, q.seconds, q.sensitivity) }
    r.register(SweepRectSLNumber::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).sweep(q.revision, q.knob, q.values, q.seconds) }
    r.register(TryRectSLVariants::class.java, onAScene, LIBRARY) { ctx, q -> studyOf(ctx).tryVariants(q.variants, q.seconds) }
    r.register(AdoptRectSLVariant::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).adoptVariant(q.expectedRevision, q.expectedSourceHash, q.label, q.description) }
    r.register(RestoreRectSLRevision::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).restore(q.expectedRevision, q.expectedSourceHash, q.revision, q.description) }
    r.register(CompareRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q ->
        studyOf(ctx).compare(q.beforeRevision, q.afterRevision, q.seconds, mode = q.mode, region = q.allowedRegion.takeIf { it.isNotEmpty() }) }
    r.register(ExportRectSLStudy::class.java, onAScene, LIBRARY) { ctx, q ->
        require(q.filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,120}"))) { "Use a plain document filename, such as orbit.mp4" }
        val box = scene(ctx)
        val session = RectSLPrograms.sessionOf(box) ?: throw IllegalStateException("'${box.name}' is not in an open document")
        val file = FileManager.forSession(session).file("%document/${q.filename}")
        studyOf(ctx).startExport(q.revision.ifBlank { "current" }, q.expectedSourceHash, file, q.kind, q.size, q.seconds, q.loops, q.overwrite, q.maxMinutes)
    }
}

/** The [RectSLStudyGuide] tool's answer. */
data class RectSLGuideText(val text: String) : afield.ai.core.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

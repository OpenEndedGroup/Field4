package afield.stacksl.study

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

// The tools an AI4 conversation gets when it is opened on a StackSL program's `stackSL` property.
// Each works on the focused box's own study (see StackSLPrograms), so nothing here is per document.
// A document can still add tools of its own from a box: AgentToolExtensions.register with its
// document as the scope.

@JsonClassDescription("Read how to work on a StackSL study: the frame, idioms that hold, traps, the tools and the style a person expects. Call it once, before your first edit in a conversation. With search, return the words guide's entries matching a word or phrase instead.")
data class StackSLStudyGuide(
    @JsonPropertyDescription("A word or phrase to look up in the StackSL words guide; empty returns the working guide") val search: String = ""
)

@JsonClassDescription("Read this StackSL study: the checked program, whether the editor holds an unchecked human edit (dirty; then liveSource is what is in the box and liveCheck says whether it compiles), revision history, compiler warnings, stages (one per line), knobs (the numbers a person typed, one line each: index, line:column, as typed, the operand the compiler took it as — 'mix amount', 'multiply b'), and personAt — the line the person's editor cursor is on, so 'this line' usually means it. Inspect once before editing and reuse liveSourceHash. Historical revisions are immutable. Renders come from the GPU of the window above the program (the CPU reference, slowly, when there is none). The window also shows the program live at whatever time it has reached; prefer ObserveStackSLStudy, which renders exact times. ReloadShaders does not apply: an accepted edit reaches the window by itself.")
data class InspectStackSLStudy(val revision: String = "current")

@JsonClassDescription("Replace the whole StackSL program. Compiles it under stack.auto rules, refuses unbalanced definitions and texture sampling, renders probe frames to catch NaN and blank output, then activates it as a new revision and returns warnings plus an image preview against the parent. Needs expectedRevision and expectedSourceHash from Inspect; a stale guard or failed check changes nothing. Prefer PatchStackSLStudy or SetStackSLNumbers for small edits.")
data class EditStackSLStudy(
    val expectedRevision: String,
    val expectedSourceHash: String,
    @JsonPropertyDescription("Complete StackSL source. Keep it human-editable: one transform per line, # comments naming what each stage does, : name ... ; definitions for motifs.")
    val source: String,
    val description: String,
    @JsonPropertyDescription("Times in seconds for the preview frames; empty chooses 0, 1.75, 3.5, 7 for animated programs.")
    val previewSeconds: List<Double> = emptyList()
)

@JsonClassDescription("Preferred for local edits: apply exact, unique, non-overlapping text replacements to the live source, then check and activate like EditStackSLStudy. Returns warnings and a before/after preview.")
data class PatchStackSLStudy(val expectedRevision: String, val expectedSourceHash: String,
    val edits: List<StudySourceEdit>, val description: String, val previewSeconds: List<Double> = emptyList())

@JsonClassDescription("Set typed numbers (knobs, by index from Inspect) to new values, rewritten in place so 0.2t stays a time rate and ]wr14 stays a wave frequency. Refuses a value a word cannot hold. Checks and activates like an edit.")
data class SetStackSLNumbers(val expectedRevision: String, val expectedSourceHash: String,
    val numbers: List<StackSLNumberEdit>, val description: String, val previewSeconds: List<Double> = emptyList())

@JsonClassDescription("See a revision: exact frames at the given times (a still program renders once) sent as an image, with measurements a thumbnail hides — clipping above/below 0..1, NaN pixels, detail, feature size, colourfulness, palette, motion between frames. compareTo adds that revision's frames and a difference map. renderSeconds says what drew the frames: the window's GPU, or the CPU reference when there is no window.")
data class ObserveStackSLStudy(val revision: String = "current", val seconds: List<Double> = emptyList(),
    @JsonPropertyDescription("Pixels square, at most 256; 0 chooses 256 for a still program and 160 for an animated one") val size: Int = 0,
    val compareTo: String = "")

@JsonClassDescription("Read the program as pictures: what each stage (line, or word for a one-line program) leaves on top of the stack — for a line ending in a store (>name), the value it stored — with value ranges and stack depth. Set probeU and probeV (0..1) to also get the top four stack values at that point after every root word — the way to answer 'why is this pixel black?'.")
data class TraceStackSLStudy(val revision: String = "current", val seconds: Double = 1.75,
    @JsonPropertyDescription("uv x of a point to probe, 0..1; negative for none") val probeU: Double = -1.0,
    @JsonPropertyDescription("uv y of a point to probe, 0..1 with y up; negative for none") val probeV: Double = -1.0)

@JsonClassDescription("List the knobs (typed numbers) with their role and context and, unless sensitivity is false, how strongly a 10% nudge to each changes the frame — which numbers matter and which do nothing here.")
data class KnobsStackSLStudy(val revision: String = "current", val seconds: Double = 1.75, val sensitivity: Boolean = true)

@JsonClassDescription("Render one knob at 2..9 values side by side, without publishing anything. Use it to choose a value before SetStackSLNumbers.")
data class SweepStackSLNumber(val revision: String = "current", val knob: Int, val values: List<Double>, val seconds: Double = 1.75)

@JsonClassDescription("Speculate: check and render 1..8 candidate programs beside the current one without publishing. Each variant is a complete source or exact edits to the live source, with a short label and note. They are held so the person can Adopt one in the study card, or you can with AdoptStackSLVariant. Labels are short (A, B or 'veils').")
data class TryStackSLVariants(val variants: List<StackSLStudy.Variant>, val seconds: List<Double> = emptyList())

@JsonClassDescription("Publish a held variant by label as a new revision (guards as for an edit).")
data class AdoptStackSLVariant(val expectedRevision: String, val expectedSourceHash: String, val label: String, val description: String = "")

@JsonClassDescription("Make an earlier revision current again, as a new revision; history is only appended to.")
data class RestoreStackSLRevision(val expectedRevision: String, val expectedSourceHash: String, val revision: String, val description: String = "")

@JsonClassDescription("Check what an edit preserved. mode rgb compares displayed colour, luma brightness only, stage what each program leaves on the stack after beforeStage/afterStage (e.g. to prove a recolour left the field before the palette untouched; for a line ending in a store >name it compares the stored value). allowedRegion [u0,v0,u1,v1] is where change may happen; outside it everything must stay within one level (1e-6 for stage).")
data class CompareStackSLStudy(val beforeRevision: String, val afterRevision: String = "current", val seconds: List<Double> = emptyList(),
    val mode: String = "rgb", val allowedRegion: List<Double> = emptyList(), val beforeStage: Int = 0, val afterStage: Int = 0)

@JsonClassDescription("Export a checked revision into this document's files directory: kind png (one frame at seconds, up to 2048 px) or mp4 (durationSeconds at fps, up to 512 px, needs ffmpeg). Runs in the background and returns at once with an estimate (seconds on the window's GPU, minutes on the CPU reference); tell the person where the file will appear. Progress is in InspectStackSLStudy's exports. The filename is used literally. Refuses an unchecked editor edit; overwrite only when asked.")
data class ExportStackSLStudy(val revision: String, val expectedSourceHash: String, val filename: String, val kind: String = "png",
    @JsonPropertyDescription("Pixels square; 0 means 1024 for png and 256 for mp4") val size: Int = 0,
    @JsonPropertyDescription("The still's time, or the clip's first frame, in seconds") val seconds: Double = 1.75,
    val durationSeconds: Double = 6.0, val fps: Int = 24, val overwrite: Boolean = false,
    @JsonPropertyDescription("Refuse, with a size that fits, if the estimate exceeds this; raise it only when the person agrees to wait") val maxMinutes: Double = 10.0)

/** The working guide and the words guide the [StackSLStudyGuide] tool serves. */
object StackSLStudyGuides {
    private fun resource(name: String): String =
        StackSLStudyGuides::class.java.getResourceAsStream("/afield/stacksl/study/$name")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("missing resource afield/stacksl/study/$name")

    /** How to work on a StackSL study. */
    val working: String by lazy { resource("working.md") }

    /** Every StackSL word with its signature and a line of description, as Markdown sections. */
    val words: String by lazy { resource("stacksl_words.md") }

    /**
     * The words guide's entries (lines, with their section heading) that mention [query], at most
     * [limit] of them.
     */
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

/** A conversation is about a StackSL program when it was opened on a box's `stackSL` property. */
private val onAProgram: (ToolExecutionContext) -> Boolean = { ctx ->
    ctx.focus?.let { StackSLPrograms.isProgram(it.box, it.property) } == true
}

/**
 * The scope the library's tools register under, so a document that registers a tool of the same name
 * from a box adds to them rather than replacing them (see AgentToolExtensions, "Scopes").
 */
private val LIBRARY = "afield.stacksl.study"

private fun program(ctx: ToolExecutionContext): Box =
    ctx.focus?.box ?: throw IllegalStateException("StackSL study tools work on the box whose chat is open")

private fun studyOf(ctx: ToolExecutionContext): StackSLStudy = StackSLPrograms.readyStudy(program(ctx))

/**
 * Register the StackSL study tools once, for every conversation opened on a program's `stackSL`
 * property. Each call works on the focused box's own study.
 */
@RunsAt(plugins)
fun installStackSLStudyTools() {
    val r = AgentToolExtensions
    r.register(StackSLStudyGuide::class.java, onAProgram, LIBRARY) { _, q ->
        StackSLGuideText(if (q.search.isBlank()) StackSLStudyGuides.working else StackSLStudyGuides.search(q.search))
    }
    r.register(InspectStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q -> studyOf(ctx).inspect(q.revision) }
    r.register(EditStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description, q.previewSeconds) }
    r.register(PatchStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description, q.previewSeconds) }
    r.register(SetStackSLNumbers::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).setNumbers(q.expectedRevision, q.expectedSourceHash, q.numbers.numbers(), q.description, q.previewSeconds) }
    r.register(ObserveStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q -> studyOf(ctx).observe(q.revision, q.seconds, q.size, q.compareTo) }
    r.register(TraceStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).trace(q.revision, q.seconds, probeU = q.probeU.takeIf { it >= 0 }, probeV = q.probeV.takeIf { it >= 0 }) }
    r.register(KnobsStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q -> studyOf(ctx).knobs(q.revision, q.seconds, q.sensitivity) }
    r.register(SweepStackSLNumber::class.java, onAProgram, LIBRARY) { ctx, q -> studyOf(ctx).sweep(q.revision, q.knob, q.values, q.seconds) }
    r.register(TryStackSLVariants::class.java, onAProgram, LIBRARY) { ctx, q -> studyOf(ctx).tryVariants(q.variants, q.seconds) }
    r.register(AdoptStackSLVariant::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).adoptVariant(q.expectedRevision, q.expectedSourceHash, q.label, q.description) }
    r.register(RestoreStackSLRevision::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).restore(q.expectedRevision, q.expectedSourceHash, q.revision, q.description) }
    r.register(CompareStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q ->
        studyOf(ctx).compare(q.beforeRevision, q.afterRevision, q.seconds, mode = q.mode, region = q.allowedRegion.takeIf { it.isNotEmpty() },
            beforeStage = q.beforeStage.takeIf { it > 0 }, afterStage = q.afterStage.takeIf { it > 0 }) }
    r.register(ExportStackSLStudy::class.java, onAProgram, LIBRARY) { ctx, q ->
        require(q.filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,120}"))) { "Use a plain document filename, such as ink.png" }
        val box = program(ctx)
        val session = StackSLPrograms.sessionOf(box) ?: throw IllegalStateException("'${box.name}' is not in an open document")
        val file = FileManager.forSession(session).file("%document/${q.filename}")
        studyOf(ctx).startExport(q.revision.ifBlank { "current" }, q.expectedSourceHash, file, q.kind, q.size, q.seconds, q.durationSeconds, q.fps, q.overwrite, q.maxMinutes)
    }
}

/** The [StackSLStudyGuide] tool's answer. */
data class StackSLGuideText(val text: String) : afield.ai.core.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

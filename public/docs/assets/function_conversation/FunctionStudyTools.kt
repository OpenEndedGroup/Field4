@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.Box
import afield.RunsAt
import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext
import afield.displayClear
import afield.displayOpen
import afield.early
import afield.newRoot
import afield.notify
import afield.plugins
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

// The tools an AI4 conversation gets when it is opened on a function study's `code`. A function
// study is a box whose Kotlin returns a StudyComposition and whose `aiStudy` property is "function"
// (the "Make a Function Study" action sets it). Each tool works on the focused box's own study, so
// nothing here is per document; a document can still add tools of its own from a box, scoped to it.

@JsonClassDescription("Read how to work on a function study: the Kotlin contract, the fast edit and export loop, time and observation policies, and the limits of the evidence. Call it once, before your first edit in a conversation.")
data class FunctionStudyGuide(val unused: String = "")

@JsonClassDescription("Inspect this function study: authoritative Kotlin, stable event IDs, assumptions, selection, revision history and live source hash. Inspect once before editing; when dirty, liveSource is the editable source and source is the checked revision. Reuse the returned hash for the edit. Historical revisions are immutable. Prefer PatchFunctionStudy for local changes.")
data class InspectFunctionStudy(val revision: String = "current")

@JsonClassDescription("Compile, check and activate a COMPLETE Kotlin candidate in this box. Must return StudyComposition of pure Functions.Fn channels. Expected revision AND live source hash from Inspect are required. Failed compile/check keeps the working revision. This is trusted local Kotlin in Field, not a sandbox. Preserve unrelated source and stable event IDs. Returns compilation/check evidence and an actual image preview; this satisfies Kotlin verification for this standalone study. No extra CompileKotlin or Observe call is needed unless a different interval or additional evidence is required. Prefer PatchFunctionStudy for small edits.")
data class EditFunctionStudy(
    val expectedRevision: String,
    val expectedSourceHash: String,
    @JsonPropertyDescription("Complete Kotlin source with imports. Last expression returns afield.stdlib.StudyComposition. No filesystem/network/process effects; no mutable or stateful evaluators.")
    val source: String,
    val description: String,
    val previewStartSeconds: Double = 0.0,
    val previewEndSeconds: Double = 25.0
)

@JsonClassDescription("Preferred for local edits: atomically apply exact unique text replacements to the inspected live source. Both revision and live-source hash are required. Match snippets against the same original source; matches must not overlap. Compiles and validates before activation, updates the human viewer, and returns checks plus a before/after image preview. Do not call CompileKotlin again. Choose a preview interval around the changed signal; default is the first 25 seconds.")
data class PatchFunctionStudy(val expectedRevision: String, val expectedSourceHash: String,
    val edits: List<StudySourceEdit>, val description: String,
    val previewStartSeconds: Double = 0.0, val previewEndSeconds: Double = 25.0)

@JsonClassDescription("Export the checked function revision to CSV files in this document's files directory, using Field's file resolver. One filename per channel, exactly as requested (no automatic .csv suffix). Each row is time in seconds, signal*scale. Includes the exact endpoint; no header by default. Files are staged and verified before publication. Requires the revision source hash; refuses dirty current source. Set overwrite=true only when the user asks to replace/re-export. Returns paths, revision, rows, duration, sample rate and checksums. No generic Kotlin evaluation or further verification is needed.")
data class ExportFunctionStudy(val revision: String, val expectedSourceHash: String,
    val filenames: List<String>, val sampleRateHz: Double = 100.0, val scale: Double = 100.0,
    val header: Boolean = false, val overwrite: Boolean = false)

@JsonClassDescription("See actual function pixels plus sampled extrema, exact declared join probes, hold errors and optional before/after differences. Overview uses min/max per pixel; request <=25 seconds for resolved waveform. All output describes one immutable revision. Images are sent to the model as image inputs. For preservation, compare intervals outside the union of old/new event supports.")
data class ObserveFunctionStudy(val revision: String = "current", val startSeconds: Double = 0.0,
    val endSeconds: Double = 3600.0, val compareTo: String = "")

@JsonClassDescription("Evaluate at 1..256 explicit times in seconds. Returns actual Fn values for a named revision; arrays stay in the host for larger observations.")
data class SampleFunctionStudy(val revision: String, val timesSeconds: List<Double>)

@JsonClassDescription("Resolve conversational references to stable event IDs and a selected interval, before changing them. Selection survives chronological reordering. IDs must exist in the active revision.")
data class SelectFunctionStudy(val eventIds: List<String>, val startSeconds: Double, val endSeconds: Double)

@JsonClassDescription("Test preservation across two revisions. Declare the only allowed changed time interval and channel names. Samples the whole composition at <=20ms plus exact event joins. Returns max/RMS errors outside the allowed scope, timestamps, and changed event metadata. Empty allowedChannels means no channel may change. Does not prove absence of hidden narrow features.")
data class CompareFunctionStudy(val beforeRevision: String, val afterRevision: String,
    val changedStartSeconds: Double, val changedEndSeconds: Double, val allowedChannels: List<String>)

/**
 * Function studies, one per box: a box whose `aiStudy` property is `"function"` and whose Kotlin
 * returns a [StudyComposition]. The study keeps its revisions in `studies/<box id>` and shows its
 * signal viewer in the box's own output.
 */
object FunctionStudies {
    /** The property that marks a box as a study, and the value that says which kind. */
    const val MARK = "aiStudy"
    const val KIND = "function"

    /** The source a new function study starts from: three flat channels over an hour. */
    val STARTER = """
        |import afield.stdlib.Functions
        |import afield.stdlib.StudyComposition
        |import afield.stdlib.StudyChoice
        |
        |// Three empty channels. Describe the function you want in this box's AI4 chat.
        |with(Functions) {
        |    StudyComposition(
        |        channels = listOf("x", "y", "z").map { name ->
        |            fn(0.0..3600.0, name) { 0.0 }
        |        },
        |        choices = listOf(
        |            StudyChoice("One hour, three zero-valued channels: a starting point to replace", "starter assumption")
        |        )
        |    )
        |}
        |""".trimMargin()

    /** How to work on a function study: what [FunctionStudyGuide] returns. */
    val guide: String by lazy {
        FunctionStudies::class.java.getResourceAsStream("/afield/stdlib/function_study_working.md")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("missing resource afield/stdlib/function_study_working.md")
    }

    /** Whether a conversation focused on [box]'s [property] is about a function study. */
    fun isStudy(box: Box, property: String): Boolean = property == "code" && box.getPropertyRaw(MARK, null) == KIND

    /** [box]'s study, created on first use. */
    fun study(box: Box): FunctionStudy {
        (box.getPropertyRaw("_functionStudy", null) as? FunctionStudy)?.let { return it }
        synchronized(box) {
            (box.getPropertyRaw("_functionStudy", null) as? FunctionStudy)?.let { return it }
            val study = FunctionStudy(BoxStudies.directory(box), { box.getPropertyRaw("code", "") as? String ?: "" }, BoxStudies.replacer(box, "code"))
            study.editorLine = { BoxStudies.editorLine(box, "code") }
            study.resume()
            box.properties["_functionStudy"] = study
            return study
        }
    }

    /** [study], with the box's current source checked in as the first revision if there is none, and its viewer showing. */
    fun ready(box: Box): FunctionStudy {
        val study = study(box)
        if (study.currentId() == "empty") {
            val source = box.getPropertyRaw("code", "") as? String ?: ""
            study.edit("empty", FunctionStudy.hash(source), source, "the starting source")
            listOf("signal", "detail", "comparison").forEach { box.displayClear(it) }
            showSignal(box)
            box.displayOpen("overview")
        }
        return study
    }

    /** Show [revisionId] in the box's signal viewer, optionally with [comparisonId] dashed beneath it. */
    fun showSignal(box: Box, revisionId: String = "current", comparisonId: String = "", channel: String = "overview") {
        val studio = study(box)
        val revision = studio.revision(revisionId)
        val c = revision.composition
        val requestedPrevious = comparisonId.takeIf { it.isNotBlank() }?.let { studio.revision(it) }
        val previous = requestedPrevious?.takeIf { it.composition.duration == c.duration && it.composition.channelNames == c.channelNames }
        val notice = if (requestedPrevious != null && previous == null) " · comparison omitted: domain or channels changed" else ""
        val viewer = SignalViewer(c.channels.mapIndexed { index, f ->
            SignalChannel(c.channelNames[index], { t -> f(t) }, comparison = previous?.composition?.channels?.get(index)?.let { old -> { t: Double -> old(t) } })
        }, 0.0, c.duration)
        val document = BoxStudies.documentOf(box)?.filename ?: box.id
        val html = viewer.renderHtml(SignalViewerOptions(label = revision.id + " · " + revision.description + notice,
            comparisonLabel = previous?.id ?: "previous", viewKey = "$document:${box.id}:function-study:$channel"))
        val key = "_signalViewer:$channel"
        val oldViewer = box.getPropertyRaw(key, null) as? SignalViewer
        box.setPropertyRaw(key, viewer)
        // Keep Plotly interactions inside this output.
        BoxStudies.show(box, channel, "<div onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()'>$html</div>")
        oldViewer?.close()
    }

    /** An accepted edit's preview, with the viewer moved to the new revision (compared with its parent). */
    fun finishEdit(box: Box, report: StudyReport, parent: String, start: Double, end: Double): StudyReport {
        val preview = study(box).previewEdit(report, start, end)
        return try {
            showSignal(box, report.revision, parent.takeUnless { it == "empty" }.orEmpty())
            preview
        } catch (e: Exception) {
            preview.copy(facts = preview.facts + ("viewerError" to "Revision already activated; viewer refresh failed: ${e.message}"))
        }
    }

    /** [FunctionStudy.observe], with the local maxima of each channel when the interval is short enough to resolve them. */
    fun observe(box: Box, request: ObserveFunctionStudy): StudyReport {
        val studio = study(box)
        val original = studio.observe(request.revision.ifBlank { "current" }, request.startSeconds, request.endSeconds, request.compareTo)
        // Measurements run on exactly the same immutable revision as the returned pixels.
        val revision = studio.revision(original.revision)
        val local = request.endSeconds - request.startSeconds <= 25.0
        val peaks = if (!local) emptyList() else revision.composition.channels.mapIndexed { channel, f ->
            val dt = 0.002
            val n = ceil((request.endSeconds - request.startSeconds) / dt).toInt()
            val ts = List(n + 1) { request.startSeconds + (request.endSeconds - request.startSeconds) * it / n }
            val values = ts.map { f(it) }
            val maxima = (1 until n).filter { values[it] > values[it - 1] && values[it] >= values[it + 1] }.map { ts[it] }
            mapOf("channel" to revision.composition.channelNames[channel], "peakTimesSeconds" to maxima.take(100),
                "interPeakSeconds" to maxima.zipWithNext { a, b -> b - a }.take(99),
                "method" to "Local maxima at <=2ms; background can shift peaks; plateaus excluded by strict left comparison. This is not oscillator phase.")
        }
        // The main view always follows HEAD, including when inspecting a historical revision.
        showSignal(box, "current", if (revision.id == studio.currentId()) request.compareTo else "")
        if (revision.id != studio.currentId()) showSignal(box, revision.id, request.compareTo, "historical observation")
        return original.copy(facts = original.facts + ("localPeaks" to peaks))
    }

    /** Sampled preservation between two revisions outside the declared interval and channels; see [CompareFunctionStudy]. */
    fun compare(box: Box, request: CompareFunctionStudy): StudyReport {
        val studio = study(box)
        val before = studio.revision(request.beforeRevision)
        val after = studio.revision(request.afterRevision)
        val a = before.composition
        val b = after.composition
        require(a.duration == b.duration && a.channelNames == b.channelNames) { "Comparison needs matching domains and channels" }
        require(request.changedStartSeconds.isFinite() && request.changedEndSeconds.isFinite() &&
            request.changedStartSeconds >= 0.0 && request.changedEndSeconds >= request.changedStartSeconds && request.changedEndSeconds <= a.duration)
        require(request.allowedChannels.all { it in a.channelNames })
        val count = ceil(a.duration / 0.02).toInt()
        val joins = (a.events + b.events).flatMap { listOf(it.start, it.plateauStart, it.plateauEnd, it.end) }
        val times = (List(count + 1) { a.duration * it / count } + joins).distinct().sorted()
        val measured = a.channelNames.mapIndexed { channel, name ->
            var peak = 0.0; var at = 0.0; var sum = 0.0; var checked = 0
            for (t in times) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                if (name !in request.allowedChannels || t < request.changedStartSeconds || t > request.changedEndSeconds) {
                    val delta = abs(a.channels[channel](t) - b.channels[channel](t))
                    require(delta.isFinite())
                    if (delta > peak) { peak = delta; at = t }
                    sum += delta * delta; checked++
                }
            }
            mapOf("channel" to name, "samples" to checked, "maxAbs" to peak, "atSeconds" to at,
                "rms" to if (checked > 0) sqrt(sum / checked) else null,
                "passes1eMinus9" to if (checked > 0) (peak <= 1e-9) else null)
        }
        val ids = (a.events + b.events).map { it.id }.distinct()
        val report = StudyReport(after.id, mapOf("before" to before.id, "allowedIntervalSeconds" to listOf(request.changedStartSeconds, request.changedEndSeconds),
            "allowedChannels" to request.allowedChannels, "preservedScope" to measured,
            "changedEventIds" to ids.filter { id -> a.events.find { it.id == id } != b.events.find { it.id == id } },
            "method" to "Whole-domain <=20ms grid plus both revisions' declared joins; sampled preservation, not a proof for arbitrary lambdas"))
        BoxStudies.show(box, "preservation checks", "<div style='width:1050px;background:#000;color:#ddd;padding:24px;font:16px Iosevka,monospace'><h3>" +
            before.id + " → " + after.id + "</h3><p>Allowed change: " + request.changedStartSeconds + "–" + request.changedEndSeconds +
            " seconds; channels " + request.allowedChannels.joinToString() + "</p><pre>" + measured.joinToString("\n") +
            "</pre><p>Sampled preservation at ≤20ms plus declared joins; tolerance 1e-9.</p></div>")
        return report
    }
}

private val onAFunctionStudy: (ToolExecutionContext) -> Boolean = { ctx -> ctx.focus?.let { FunctionStudies.isStudy(it.box, it.property) } == true }
private fun studyBox(ctx: ToolExecutionContext): Box = ctx.focus?.box ?: throw IllegalStateException("Function study tools work on the box whose chat is open")

/** See AgentToolExtensions, "Scopes": a document's own tool of the same name adds to these rather than replacing them. */
private const val LIBRARY = "afield.stdlib.function-study"

/** Register the function study tools once, for every conversation opened on a function study's `code`. */
@RunsAt(plugins)
fun installFunctionStudyTools() {
    val r = AgentToolExtensions
    r.register(FunctionStudyGuide::class.java, onAFunctionStudy, LIBRARY) { _, _ -> FunctionStudyGuideText(FunctionStudies.guide) }
    r.register(InspectFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        FunctionStudies.ready(studyBox(ctx)).inspect(q.revision.ifBlank { "current" }) }
    r.register(EditFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        val box = studyBox(ctx)
        FunctionStudies.finishEdit(box, FunctionStudies.ready(box).edit(q.expectedRevision, q.expectedSourceHash, q.source, q.description),
            q.expectedRevision, q.previewStartSeconds, q.previewEndSeconds) }
    r.register(PatchFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        val box = studyBox(ctx)
        FunctionStudies.finishEdit(box, FunctionStudies.ready(box).patch(q.expectedRevision, q.expectedSourceHash, q.edits, q.description),
            q.expectedRevision, q.previewStartSeconds, q.previewEndSeconds) }
    r.register(ExportFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        require(q.filenames.all { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,120}")) }) { "Use plain document filenames, such as a, b, c or signal.csv" }
        val box = studyBox(ctx)
        val session = BoxStudies.documentOf(box) ?: throw IllegalStateException("'${box.name}' is not in an open document")
        val files = FileManager.forSession(session)
        FunctionStudies.ready(box).exportCsv(q.revision.ifBlank { "current" }, q.expectedSourceHash, q.filenames.map { files.file("%document/$it") },
            q.sampleRateHz, q.scale, q.header, q.overwrite) }
    r.register(ObserveFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        val box = studyBox(ctx); FunctionStudies.ready(box); FunctionStudies.observe(box, q) }
    r.register(SampleFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q -> FunctionStudies.ready(studyBox(ctx)).sample(q.revision, q.timesSeconds) }
    r.register(SelectFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q -> FunctionStudies.ready(studyBox(ctx)).select(q.eventIds, q.startSeconds, q.endSeconds) }
    r.register(CompareFunctionStudy::class.java, onAFunctionStudy, LIBRARY) { ctx, q ->
        val box = studyBox(ctx); FunctionStudies.ready(box); FunctionStudies.compare(box, q) }
}

/** The [FunctionStudyGuide] tool's answer. */
data class FunctionStudyGuideText(val text: String) : afield.ai.core.SummarisesForToolLog {
    override fun toolLogSummary(): String = "guide ${text.length} chars"
}

// Studies are shown as their documents open, off the main thread: checking in a first revision
// compiles the box's Kotlin.
private val opening = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "function-studies").apply { isDaemon = true } }

/** Show each function study's viewer when its document opens (or a box is marked), without waiting for a tool call. */
@RunsAt(plugins)
fun installFunctionStudyWatcher() {
    afield.runtime.TopologyWatcher("function-study") { boxes ->
        boxes.filter { it.getPropertyRaw(FunctionStudies.MARK, null) == FunctionStudies.KIND && it.getPropertyRaw("_functionStudyShown", null) != true }
            .forEach { box ->
                box.properties["_functionStudyShown"] = true
                opening.execute {
                    runCatching {
                        FunctionStudies.ready(box)
                        FunctionStudies.showSignal(box)
                        box.displayOpen("overview")
                    }.onFailure { afield.logger.lprintln("warning,study", "showing function study '${box.name}' failed: $it") }
                }
            }
    }.start()
}

/** Keep the `aiStudy` mark with the document. */
@RunsAt(early)
fun installFunctionStudyMark() {
    afield.Box.ioPersistentProperties.add(FunctionStudies.MARK)
}

/** "Make a Function Study": mark the selected boxes as function studies, seeding an empty one with [FunctionStudies.STARTER]. */
@RunsAt(newRoot)
fun installFunctionStudyAction(root: Box) {
    root.actions["make-function-study"] = Action(
        desc = "Make a Function Study",
        detail = "Mark the selected box as a function study: its AI4 chat gets tools that check, plot, compare and version the Kotlin it holds.",
    ) { selection ->
        selection.forEach { box ->
            if ((box.getPropertyRaw("code", "") as? String).isNullOrBlank()) box.setPropertyRaw("code", FunctionStudies.STARTER, doResync = true)
            box.setPropertyRaw(FunctionStudies.MARK, FunctionStudies.KIND, doResync = true)
            box.properties["_functionStudyShown"] = true
            opening.execute {
                runCatching { FunctionStudies.ready(box); FunctionStudies.showSignal(box); box.displayOpen("overview") }
                    .onFailure { afield.logger.lprintln("warning,study", "showing function study '${box.name}' failed: $it") }
            }
            notify("'${box.name}' is a function study: open its AI4 chat and describe a function", "study")
        }
    }
}

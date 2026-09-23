import afield.ai.core.*
import afield.displayClear
import afield.displayOpen
import afield.stdlib.FunctionStudy
import afield.stdlib.StudyReport
import afield.stdlib.StudySourceEdit
import afield.stdlib.FileManager
import afield.stdlib.SignalChannel
import afield.stdlib.SignalViewer
import afield.stdlib.SignalViewerOptions
import afield.stdlib.named
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

// These are deliberately document-defined tools. Edit a handler and rerun this box.
// The domain layer has no model or transport dependency.
val owner = afield.Box.context.get() ?: throw IllegalStateException("Run in a Field box")
val document = inside
val documentFiles = FileManager.forSession(document)
// The saved demo has a fixed UID; a source box made in the UI has a generated UID.
val sourceBox = document.boxes["function_source"] ?: root.children.named("function_source")
val studio = (properties["_functionStudy"] as? FunctionStudy) ?: FunctionStudy(
    File(document.filename, "revisions"),
    { sourceBox.getPropertyRaw("code", "") as String },
    { expected, replacement ->
        val publish = {
            if (document.readOnly || sourceBox.getPropertyRaw("code", "") != expected) false
            else {
                sourceBox.setPropertyRaw("code", replacement, doResync = true)
                document.markSidecarPending(sourceBox.id, "code")
                true
            }
        }
        if (afield.Runloop.isMainThread) publish()
        else runBlocking { withContext(afield.Runloop.runLoop.main) { publish() } }
    }
).also { properties["_functionStudy"] = it; it.resume() }

// One interactive human view; the observation tools still return measured image inputs to AI4.
fun showSignal(revisionId: String = "current", comparisonId: String = "", channel: String = "overview") {
    val revision = studio.revision(revisionId)
    val c = revision.composition
    val requestedPrevious = comparisonId.takeIf { it.isNotBlank() }?.let { studio.revision(it) }
    val previous = requestedPrevious?.takeIf { it.composition.duration == c.duration && it.composition.channelNames == c.channelNames }
    val notice = if (requestedPrevious != null && previous == null) " · comparison omitted: domain or channels changed" else ""
    val viewer = SignalViewer(c.channels.mapIndexed { index, f ->
        SignalChannel(c.channelNames[index], { t -> f(t) }, comparison = previous?.composition?.channels?.get(index)?.let { old -> { t: Double -> old(t) } })
    }, 0.0, c.duration)
    val html = viewer.renderHtml(SignalViewerOptions(label = revision.id + " · " + revision.description + notice,
        comparisonLabel = previous?.id ?: "previous", viewKey = document.filename + ":function-study:" + channel))
    val key = "_signalViewer:" + channel
    val oldViewer = owner.getPropertyRaw(key, null) as? SignalViewer
    owner.setPropertyRaw(key, viewer)
    // Keep Plotly interactions inside this output, including on already-running Field builds.
    owner.display(channel, "<div onpointerdown='event.stopPropagation()' onmousedown='event.stopPropagation()' onclick='event.stopPropagation()'>" + html + "</div>")
    oldViewer?.close()
}

@JsonClassDescription("Inspect this function study: authoritative Kotlin, stable event IDs, assumptions, selection, revision history and live source hash. Inspect once before editing; when dirty, liveSource is the editable source and source is the checked revision. Reuse the returned hash for the edit. Historical revisions are immutable. Prefer PatchFunctionStudy for local changes.")
data class InspectFunctionStudy(val revision: String = "current")

@JsonClassDescription("Compile, check and activate a COMPLETE Kotlin candidate in this document. Must return StudyComposition of pure Functions.Fn channels. Expected revision AND live source hash from Inspect are required. Failed compile/check keeps the working revision. This is trusted local Kotlin in Field, not a sandbox. Preserve unrelated source and stable event IDs. Returns compilation/check evidence and an actual image preview; this satisfies Kotlin verification for this standalone study. No extra CompileKotlin or Observe call is needed unless a different interval or additional evidence is required. Prefer PatchFunctionStudy for small edits.")
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

val available: (ToolExecutionContext) -> Boolean = { ctx ->
    ctx.documentGuides?.list()?.document == document.filename
}
AgentToolExtensions.register(InspectFunctionStudy::class.java, available) { _, request -> studio.inspect(request.revision.ifBlank { "current" }) }
fun finishEdit(report: StudyReport, parent: String, start: Double, end: Double): StudyReport {
    val preview = studio.previewEdit(report, start, end)
    return try {
        showSignal(report.revision, parent.takeUnless { it == "empty" }.orEmpty())
        preview
    } catch (e: Exception) {
        preview.copy(facts = preview.facts + ("viewerError" to "Revision already activated; viewer refresh failed: ${e.message}"))
    }
}
AgentToolExtensions.register(EditFunctionStudy::class.java, available) { _, request ->
    finishEdit(studio.edit(request.expectedRevision, request.expectedSourceHash, request.source, request.description),
        request.expectedRevision, request.previewStartSeconds, request.previewEndSeconds)
}
AgentToolExtensions.register(PatchFunctionStudy::class.java, available) { _, request ->
    finishEdit(studio.patch(request.expectedRevision, request.expectedSourceHash, request.edits, request.description),
        request.expectedRevision, request.previewStartSeconds, request.previewEndSeconds)
}
AgentToolExtensions.register(ExportFunctionStudy::class.java, available) { _, request ->
    require(request.filenames.all { it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,120}")) }) {
        "Use plain document filenames, such as a, b, c or signal.csv"
    }
    val outputs = request.filenames.map { documentFiles.file("%document/$it") }
    studio.exportCsv(request.revision.ifBlank { "current" }, request.expectedSourceHash, outputs,
        request.sampleRateHz, request.scale, request.header, request.overwrite)
}
AgentToolExtensions.register(ObserveFunctionStudy::class.java, available) { _, request ->
    val original = studio.observe(request.revision.ifBlank { "current" }, request.startSeconds, request.endSeconds, request.compareTo)
    // Measurements run on exactly the same immutable revision as the returned pixels.
    val revision = studio.revision(original.revision)
    val local = request.endSeconds - request.startSeconds <= 25.0
    val peaks = if (!local) emptyList() else revision.composition.channels.mapIndexed { channel, f ->
        val dt = 0.002
        val n = kotlin.math.ceil((request.endSeconds-request.startSeconds)/dt).toInt()
        val ts = List(n+1) { request.startSeconds+(request.endSeconds-request.startSeconds)*it/n }
        val values = ts.map { f(it) }
        val maxima = (1 until n).filter { values[it] > values[it-1] && values[it] >= values[it+1] }.map { ts[it] }
        mapOf("channel" to revision.composition.channelNames[channel], "peakTimesSeconds" to maxima.take(100),
            "interPeakSeconds" to maxima.zipWithNext { a,b -> b-a }.take(99),
            "method" to "Local maxima at <=2ms; background can shift peaks; plateaus excluded by strict left comparison. This is not oscillator phase.")
    }
    val report = original.copy(facts=original.facts + ("localPeaks" to peaks))
    // The main view always follows HEAD, including when inspecting a historical revision.
    showSignal("current", if (revision.id == studio.currentId()) request.compareTo else "")
    if (revision.id != studio.currentId()) showSignal(revision.id, request.compareTo, "historical observation")
    report
}
AgentToolExtensions.register(SampleFunctionStudy::class.java, available) { _, request -> studio.sample(request.revision, request.timesSeconds) }
AgentToolExtensions.register(SelectFunctionStudy::class.java, available) { _, request -> studio.select(request.eventIds, request.startSeconds, request.endSeconds) }
AgentToolExtensions.register(CompareFunctionStudy::class.java, available) { _, request ->
    val before = studio.revision(request.beforeRevision)
    val after = studio.revision(request.afterRevision)
    val a = before.composition; val b = after.composition
    require(a.duration == b.duration && a.channelNames == b.channelNames) { "Comparison needs matching domains and channels" }
    require(request.changedStartSeconds.isFinite() && request.changedEndSeconds.isFinite() &&
        request.changedStartSeconds >= 0.0 && request.changedEndSeconds >= request.changedStartSeconds && request.changedEndSeconds <= a.duration)
    require(request.allowedChannels.all { it in a.channelNames })
    val count = kotlin.math.ceil(a.duration / 0.02).toInt()
    val joins = (a.events+b.events).flatMap { listOf(it.start,it.plateauStart,it.plateauEnd,it.end) }
    val times = (List(count+1) { a.duration*it/count } + joins).distinct().sorted()
    val measured = a.channelNames.mapIndexed { channel, name ->
        var peak=0.0; var at=0.0; var sum=0.0; var checked=0
        for (t in times) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            if (name !in request.allowedChannels || t < request.changedStartSeconds || t > request.changedEndSeconds) {
                val delta=kotlin.math.abs(a.channels[channel](t)-b.channels[channel](t))
                require(delta.isFinite())
                if (delta>peak) { peak=delta; at=t }
                sum+=delta*delta; checked++
            }
        }
        mapOf("channel" to name,"samples" to checked,"maxAbs" to peak,"atSeconds" to at,
            "rms" to if(checked>0) kotlin.math.sqrt(sum/checked) else null,
            "passes1eMinus9" to if(checked>0) (peak<=1e-9) else null)
    }
    val ids=(a.events+b.events).map { it.id }.distinct()
    val report = StudyReport(after.id,mapOf("before" to before.id,"allowedIntervalSeconds" to listOf(request.changedStartSeconds,request.changedEndSeconds),
        "allowedChannels" to request.allowedChannels,"preservedScope" to measured,
        "changedEventIds" to ids.filter { id -> a.events.find { it.id==id } != b.events.find { it.id==id } },
        "method" to "Whole-domain <=20ms grid plus both revisions' declared joins; sampled preservation, not a proof for arbitrary lambdas"))
    owner.display("preservation checks", "<div style='width:1050px;background:#000;color:#ddd;padding:24px;font:16px Iosevka,monospace'><h3>" +
        before.id + " → " + after.id + "</h3><p>Allowed change: " + request.changedStartSeconds + "–" + request.changedEndSeconds +
        " seconds; channels " + request.allowedChannels.joinToString() + "</p><pre>" + measured.joinToString("\n") +
        "</pre><p>Sampled preservation at ≤20ms plus declared joins; tolerance 1e-9.</p></div>")
    report
}

if (studio.currentId() == "empty") {
    val source = sourceBox.getPropertyRaw("code", "") as String
    println(studio.edit("empty", FunctionStudy.hash(source), source, "Blank page: three constant channels").toolLogSummary())
}
// Retire the old static outputs when upgrading an existing document.
listOf("signal", "detail", "comparison").forEach { owner.displayClear(it) }
showSignal()
owner.displayOpen("overview")
println("Function study tools ready. Select the source box, open AI4 chat, and ask for a function. Current revision: " + studio.currentId())

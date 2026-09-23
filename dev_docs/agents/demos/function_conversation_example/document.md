# Field Document: function_conversation_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isDatalogRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["function_source" "function_tools"] "properties" {"children" #{#afield/box "function_source" #afield/box "function_tools"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{} "preamble" "" "fileManagerAccessHistory" []} "blockProperties" []}
```

## 2. The function — talk here (AI4)

<!-- field-box id="function_source" index="1" -->

```edn [field-box id="function_source" role="properties"]
{"id" "function_source" "index" 1 "name" "The function — talk here (AI4)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The function — talk here (AI4)" "frame" #afield/rect [70.0 80.0 560.0 150.0] "nested" #{} "preamble" "" "autorun" false "attic" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "function_source_code_.kt" "lines" 121 "characters" 9518 "sha256" "82456e1e48acd33f7832550e67880a6cfbea2f6e09409d8b1514eea08f24d201"}]}
```

```kotlin [field-property box="function_source" name="code" path="function_source_code_.kt"]
import afield.stdlib.Functions
import afield.stdlib.StudyComposition
import afield.stdlib.StudyEvent
import afield.stdlib.StudyChoice
import kotlin.math.PI

with(Functions) {
    val durationSeconds = 3600.0
    val carrierPeriodSeconds = 40.0
    val relativePhasePeriodSeconds = 100.0
    val phaseExcursionRadians = 2.0 * PI / 3.0
    val backgroundCenter = 0.45
    val backgroundAmplitude = 0.20
    val slowAmplitude = 0.04
    val slowPeriodSeconds = 230.0

    val firstStartSeconds = 185.0
    val eventSpacingSeconds = 600.0
    val e3OffsetSeconds = 40.0
    val riseSeconds = 100.0
    val e3RiseSeconds = 200.0
    val holdSeconds = 30.0
    val fallSeconds = 100.0
    val lowPulseHz = 0.06
    val highPulseHz = 0.80
    val pulseFloor = 0.25
    val mergeFraction = 0.20
    val e3YPeakLagSeconds = 6.0
    val e3YLagSpanSeconds = 180.0

    // Starts are independent of rise durations: changing a rise pins its start.
    // Stable IDs survive moves and chronological reordering.
    val events = listOf(
        StudyEvent("e1", firstStartSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e2", firstStartSeconds + eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e3", firstStartSeconds + 2.0 * eventSpacingSeconds + e3OffsetSeconds, e3RiseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e5", firstStartSeconds + 4.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e6", firstStartSeconds + 5.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds)
    )

    fun smooth01(value: Double): Double {
        val u = value.coerceIn(0.0, 1.0)
        return u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
    }

    fun background(seconds: Double, channel: Int): Double {
        val drift = phaseExcursionRadians * kotlin.math.sin(2.0 * PI * seconds / relativePhasePeriodSeconds)
        val phase = 2.0 * PI * seconds / carrierPeriodSeconds + (channel - 1) * drift
        return backgroundCenter + backgroundAmplitude * kotlin.math.sin(phase) +
            slowAmplitude * kotlin.math.sin(2.0 * PI * seconds / slowPeriodSeconds)
    }

    // age runs from the background edge toward the plateau on either side.
    // Integral of f(age) = lowPulseHz + (highPulseHz-lowPulseHz)*age/span.
    // Reversing age on the fall gives a decelerating, shrinking pulse train.
    fun shoulder(base: Double, age: Double, span: Double): Double {
        val u = (age / span).coerceIn(0.0, 1.0)
        val cycles = lowPulseHz * age +
            0.5 * (highPulseHz - lowPulseHz) * age * age / span
        val pulse = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cycles)
        val strength = smooth01(u)
        val pulseTarget = pulseFloor + (1.0 - pulseFloor) * pulse
        val pulsed = (1.0 - strength) * base + strength * pulseTarget
        // Fill the troughs during the last 20% rather than abruptly cut pulses.
        val merge = smooth01((u - (1.0 - mergeFraction)) / mergeFraction)
        return (1.0 - merge) * pulsed + merge
    }

    val channels = listOf("x", "y", "z").mapIndexed { index, name ->
        fn(0.0..durationSeconds, name) { seconds ->
            // The background always uses absolute time; events never restart it.
            val base = background(seconds, index)
            val event = events.firstOrNull { seconds >= it.start && seconds <= it.end }
            when {
                event == null -> base
                seconds < event.plateauStart -> {
                    val age = seconds - event.start
                    // Instance-specific shoulder clock only; never warp the background.
                    // 64*u^3*(1-u)^3 has peak 1 and zero first/second endpoint derivatives.
                    val lag = if (event.id == "e3" && name == "y" && age < e3YLagSpanSeconds) {
                        val u = age / e3YLagSpanSeconds
                        val v = 1.0 - u
                        e3YPeakLagSeconds * 64.0 * u * u * u * v * v * v
                    } else 0.0
                    // Clock rate is in [0.885514, 1.114487], so time never reverses.
                    val localAge = age - lag
                    // Bind local function result: avoids Wither's brace-tail callableId pitfall.
                    val result = shoulder(base, localAge, event.rise)
                    result
                }
                seconds <= event.plateauEnd -> 1.0 // exact closed constant hold
                else -> shoulder(base, event.end - seconds, event.fall)
            }
        }
    }

    StudyComposition(
        channels = channels,
        events = events,
        duration = durationSeconds,
        maxFrequencyHz = 2.0,
        choices = listOf(
            StudyChoice("Three time-dependent channels over one hour, with smooth relative phase motion on an approximately 100-second scale", "user requirement"),
            StudyChoice("Six evenly spaced pulse crescendos/decrescendos, each reaching an exact 30-second all-ones hold; background continues and returns, with events away from boundaries", "user requirement"),
            StudyChoice("Integrated instantaneous pulse frequency, smooth joins, and stable event IDs e1 through e6", "user requirement"),
            StudyChoice("Background: 40-second carrier, sinusoidal relative-phase drift with 100-second period and +/-2pi/3 excursion, plus a gentle 230-second common sway", "agent assumption"),
            StudyChoice("Starts at 185+600*n seconds; 100-second rises and falls put plateau centers at 300+600*n seconds and leave 185-second boundary margins", "agent assumption"),
            StudyChoice("Synchronized pulse targets sweep from 0.06 to 0.80 Hz; quintic crossfade grows pulse depth toward a 0.25-to-1 target, then fills troughs in the final 20% to merge into the hold. Falls reverse this gesture", "agent assumption"),
            StudyChoice("All channels stay in [0,1]; quintic envelopes give value, velocity and acceleration continuity at event boundaries and plateaus", "agent assumption"),
            StudyChoice("Rise edits pin the event start, retain the 30-second hold, and move that event's later joins only; other event starts do not ripple", "agent assumption"),
            StudyChoice("Move the third event, resolved and selected as e3, 40 seconds later: start 1425, plateau 1525..1555, end 1655 seconds. Preserve its identity and internal timing, every other event, and the continuing background. This supersedes the original even-spacing requirement and nominal timing assumption for e3 only", "user requirement"),
            StudyChoice("Double selected e3's rise from 100 to 200 seconds, pinning its start at 1425. Its hold remains 30 seconds at 1625..1655, followed by the unchanged 100-second fall ending at 1755. The integrated rise frequency ramp now spans 200 seconds with unchanged 0.06/0.80 Hz endpoints. This supersedes e3's prior rise and later join times only; preserve every other event and the absolute-time background without ripple", "user requirement"),
            StudyChoice("During e3 only, let y lag and catch up smoothly on a strictly monotonic local rise clock; preserve x/z, everything outside e3, and the shared 30-second all-ones hold", "user requirement"),
            StudyChoice("e3 y shoulder age is a minus 6*64*u^3*(1-u)^3 seconds for a in [0,180], u=a/180; identity thereafter. Peak lag 6 seconds at 1515; recovered at 1605, 20 seconds before the unchanged 1625..1655 hold. Clock rate stays positive (approximately 0.8855..1.1145), with unit endpoint slopes and zero endpoint curvature. Warp pulse phase and shoulder envelopes together, retaining the absolute-time background. This overrides pulse synchronization only for this instance and channel", "agent assumption"),
            StudyChoice("Delete the fourth interruption, resolved from the current inventory as e4, formerly 1985..2215 seconds. Reveal the continuing absolute-time background there; preserve e1/e2/e3/e5/e6 without renumbering or ripple, including e3 timing and y lag. This supersedes the original six-event requirement only by removing e4. Retain the pre-deletion revision r6-deb1b1738cb0 in history", "user requirement"),
            StudyChoice("Select background-only interval 1100..1250 seconds; slow its first half and catch up in its second with a strictly monotonic time map pinned in value and slope 1 at both ends. Preserve all other times, current event instances, metadata, and held plateaus", "user requirement"),
            StudyChoice("Within the selected interval evaluate all background channels at T(t)=t-A*64*u^3*(1-u)^3, u=(t-1100)/150, A=6 seconds; identity elsewhere. Peak delay 6 seconds at 1175; derivative 1-(A/150)*192*u^2*(1-u)^2*(1-2u) lies in approximately 0.8626..1.1374. Endpoint second derivatives also vanish. Strength zero restores the unwarped background; the strictly increasing map is invertible. This supersedes absolute-time background only inside the selected event-free passage; event clocks and e3 y lag are untouched", "agent assumption"),
            StudyChoice("Restore the unwarped absolute-time background using historical r7-ebc54198e3ae as reference, in a new checked child of r8 rather than discarding history. Remove the background time-map implementation only; preserve all current event timings, e3 y lag and e4 deletion. This supersedes the preceding 1100..1250 background-warp requirement and map assumption; retain that experiment in revision history", "user requirement")
        )
    )
}
```

## 3. Observation tools — run first

<!-- field-box id="function_tools" index="2" -->

```edn [field-box id="function_tools" role="properties"]
{"id" "function_tools" "index" 2 "name" "Observation tools — run first" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Observation tools — run first" "frame" #afield/rect [1000.0 80.0 1320.0 150.0] "nested" #{} "preamble" "" "autorun" false "attic" ""} "blockProperties" [{"name" "code" "language" "kotlin" "path" "function_tools_code_.kt" "lines" 198 "characters" 15400 "sha256" "aa43d84a0fff06ae7cd90b2ffa36e01386db2d767875efc9f388fe5b119610e1"}]}
```

```kotlin [field-property box="function_tools" name="code" path="function_tools_code_.kt"]
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
val owner = thisBox
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
```

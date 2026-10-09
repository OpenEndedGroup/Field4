@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.registerButtonCallback
import afield.Runloop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A small output card over the real runner, using Field's existing button transport. */
internal class StudyBuildPanel(private val binding: BoxStudyBuildBinding, private val channel: String) : AutoCloseable {
    private val prefix = "study-build-${UUID.randomUUID()}"
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "study-build-panel").apply { isDaemon = true } }
    @Volatile private var shown = emptyList<StudyStageState>()
    private var findings = emptyList<StudyBuildFinding>()
    @Volatile private var error: String? = null
    @Volatile private var closed = false
    private var lastHtml = ""

    init {
        val initial = binding.builds.stages()
        for (stage in initial) {
            registerButtonCallback("$prefix-${stage.id}-build") { down -> if (down && !closed) executor.execute {
                try {
                    val state = shown.first { it.id == stage.id }
                    binding.builds.start(stage.id, checkNotNull(state.fingerprint) { state.reason.orEmpty() })
                    error = null
                } catch (e: Exception) { error = e.message ?: "Could not start this build" }
                refresh()
            } }
            registerButtonCallback("$prefix-${stage.id}-cancel") { down -> if (down && !closed) executor.execute {
                try {
                    val job = checkNotNull(shown.first { it.id == stage.id }.latest)
                    binding.builds.cancel(job.id); error = null
                } catch (e: Exception) { error = e.message ?: "Could not cancel this build" }
                refresh()
            } }
        }
        executor.scheduleWithFixedDelay({ refresh() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        if (closed) return
        val html = try {
            shown = binding.builds.stages()
            val runs = shown.mapNotNull { it.latest?.id }.toSet()
            val read = { studyBuildFindings(binding.owner.inside.boxes.values + binding.owner.inside.root, runs) }
            findings = if (Runloop.isMainThread) read() else runBlocking { withContext(Runloop.runLoop.main) { read() } }
            studyBuildCardHtml(prefix, binding.name, shown, findings, error, exclusive = binding.builds.exclusive, button = ::button)
        }
        catch (e: Exception) { "<div class=\"study-build-error\" style=\"color:#ff6969;user-select:text\">${studyBuildEscape(e.message ?: "Build runner unavailable")}</div>" }
        if (html != lastHtml && !closed) { lastHtml = html; BoxStudies.show(binding.owner, channel, html) }
    }

    private fun button(stage: String, action: String, label: String, important: Boolean): String {
        val id = "$prefix-$stage-$action"
        return """<span class="field-button" data-control-id="$id"><button id="$id" type="button" class="field-button-hit ${if (important) "important" else ""}" aria-pressed="false">$label</button></span>"""
    }

    override fun close() { closed = true; executor.shutdownNow() }
}

/** Renders one stage's Field button; [StudyBuildPanel] passes the buttons it registered callbacks for. */
internal typealias StudyBuildButton = (stage: String, action: String, label: String, important: Boolean) -> String

/**
 * The whole card: one section per stage under a style block scoped to [prefix]. In an [exclusive]
 * runner, a stage that could build waits while another stage's build is running.
 */
internal fun studyBuildCardHtml(prefix: String, runner: String, stages: List<StudyStageState>,
    findings: List<StudyBuildFinding>, error: String? = null, exclusive: Boolean = false,
    button: StudyBuildButton = { _, _, _, _ -> "" }): String {
    val busy = stages.firstOrNull { it.latest?.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING) }?.label?.takeIf { exclusive }
    val cards = stages.joinToString("") { studyBuildStageHtml(it, stages, findings, busy, button) }
    return """
<style>
.$prefix{background:#000;color:#ddd;padding:16px;border-radius:12px;font:14px/1.45 Iosevka,monospace;max-width:640px;box-sizing:border-box}
.$prefix section+section{margin-top:24px}.$prefix .study-build-title{display:flex;align-items:center;gap:12px;user-select:none}
.$prefix .study-build-state{color:#999;font-size:inherit;margin-left:auto}.$prefix p{margin:8px 0 0;user-select:text;white-space:pre-wrap;overflow-wrap:anywhere}
.$prefix .study-build-findings{margin-top:12px;padding:12px;background:#141414;border-radius:8px}.$prefix .study-build-findings a{display:block;margin-top:10px;color:#62d7ff;text-decoration:none}.$prefix .study-build-findings a:hover{text-decoration:underline}
.$prefix .study-build-findings .study-build-finding-error{color:#ff6969}.$prefix .study-build-findings .study-build-finding-warning{color:#f5b748}.$prefix .study-build-findings .study-build-finding-resolved{color:#999}
.$prefix .study-build-error{color:#ff6969}.$prefix .study-build-muted{color:#999}.$prefix progress{display:block;width:100%;height:5px;margin-top:12px;accent-color:#55d4f2;border:0;border-radius:4px;overflow:hidden}
.$prefix .study-build-geometry-view a{color:#ff6969;text-decoration:none}.$prefix .study-build-geometry-view a:hover{text-decoration:underline}
.$prefix .study-build-result-notes{padding:12px;background:#171717;border-radius:8px;margin-top:12px}.$prefix .study-build-result-notes p:first-child{margin-top:0}
.$prefix .study-build-elapsed{color:#999}
.$prefix .study-build-diagnostic{margin-top:12px;background:#171717;padding:12px;border-radius:8px}.$prefix .study-build-diagnostic summary{color:#ff6969;cursor:pointer;user-select:none}.$prefix .study-build-diagnostic pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto;user-select:text}
.$prefix .study-build-diagnostic.study-build-diagnostic-earlier summary{color:#999}
.$prefix progress::-webkit-progress-bar{background:#262626}.$prefix progress::-webkit-progress-value{background:#55d4f2}
.$prefix .field-button{display:inline-flex;margin:0;padding:0;background:none;border:0}
.$prefix button.field-button-hit{font:inherit;text-transform:none;letter-spacing:normal;padding:8px 14px;border:0;border-radius:9px;outline:none;box-shadow:none;background:#262626;color:#ddd;cursor:pointer;user-select:none}
.$prefix button.important{background:#55d4f2;color:#000}.$prefix button:focus-visible{outline:none;box-shadow:none}.$prefix button:hover{filter:brightness(1.12)}
</style><div class="$prefix" data-study-builds="${studyBuildEscape(runner)}" data-afield-intrinsic-width="640">$cards${error?.let { "<p class=\"study-build-error\">${studyBuildEscape(it)}</p>" }.orEmpty()}</div>
""".trimIndent()
}

/**
 * Whether [job] ran against inputs other than [state]'s current ones, so its failure and findings
 * describe an earlier design. A stage that cannot be fingerprinted now (missing input, dependency
 * not current) counts as changed. The card and the agent's build tools both ask this.
 */
internal fun studyBuildUsedEarlierInputs(state: StudyStageState, job: StudyBuildRecord): Boolean =
    !job.ranAgainst(state.fingerprint)

/** [studyBuildUsedEarlierInputs] for the stage's latest attempt, the one the card shows. */
internal fun studyBuildLatestUsedEarlierInputs(state: StudyStageState): Boolean =
    state.latest != null && studyBuildUsedEarlierInputs(state, state.latest)

/**
 * One stage's section of the card. [stages] names blocking stages by label. [busyWith] is the label
 * of the stage an exclusive runner is building, when that is another stage.
 */
internal fun studyBuildStageHtml(state: StudyStageState, stages: List<StudyStageState>,
    findings: List<StudyBuildFinding>, busyWith: String? = null, button: StudyBuildButton = { _, _, _, _ -> "" }): String {
    val latest = state.latest
    val running = latest?.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
    val earlier = studyBuildLatestUsedEarlierInputs(state)
    val failure = latest?.problem?.takeIf { latest.status == StudyBuildStatus.FAILED && !earlier }
    val label = when {
        running && latest!!.cancelRequested -> "Cancelling"
        running && earlier -> "Building an earlier design"
        running -> "Building"
        failure != null -> "Build failed"
        state.current != null -> studyBuildCurrentLabel(state.resultNotes)
        state.blockedBy.isNotEmpty() -> "Waiting for an earlier stage"
        state.inputHelp != null -> state.inputHelp.label
        state.reason?.startsWith("Missing input") == true -> "Needs input"
        latest != null -> "Needs rebuilding"
        else -> "Ready to build"
    }
    val message = when {
        running && earlier -> "The design changed. This result will be retained for inspection."
        running -> latest!!.message
        state.blockedBy.isNotEmpty() -> "Complete ${state.blockedBy.joinToString { id -> stages.firstOrNull { it.id == id }?.label ?: id }} first."
        state.inputHelp != null -> state.inputHelp.message
        else -> failure?.message ?: state.reason.orEmpty()
    }
    val advice = failure?.suggestions?.joinToString(" ").orEmpty()
    val request = latest?.request?.takeIf { it.isNotEmpty() }?.let { asked ->
        "<p class=\"study-build-muted\">Request: ${studyBuildEscape(asked.entries.joinToString { (key, value) -> "$key = $value" })}</p>"
    }.orEmpty() + latest?.resumedFrom.orEmpty().joinToString("") { resumed ->
        val files = resumed.files.map { it.path }.let { if (it.size > 3) it.take(3) + "${it.size - 3} more" else it }
        "<p class=\"study-build-muted\">Continued from attempt ${studyBuildEscape(resumed.jobId.take(8))}: ${studyBuildEscape(files.joinToString())}</p>"
    }
    val geometry = failure?.let(::studyBuildGeometryViewHtml).orEmpty()
    val action = when {
        running && !latest!!.cancelRequested -> button(state.id, "cancel", "Cancel", false)
        !running && state.fingerprint != null && busyWith == null -> button(state.id, "build", if (state.current != null) "Rebuild" else "Build", true)
        else -> ""
    }
    val turn = if (!running && state.fingerprint != null && busyWith != null)
        "<p class=\"study-build-muted\">One build at a time: this can start when ${studyBuildEscape(busyWith)} finishes.</p>" else ""
    val progress = if (running) """<progress max="1" value="${latest!!.progress}" aria-label="${studyBuildEscape(state.label)} progress"></progress><p class="study-build-elapsed">${studyBuildElapsedText(latest)}</p>""" else ""
    val prior = if (failure != null && state.current != null) "<p>Previous checked result is still current.</p>" else ""
    val resultNotes = studyBuildResultNotesHtml(state.resultNotes,
        state.current != null && latest?.id != state.current.id)
    val reviewLinks = studyBuildFindingsHtml(state, findings)
    val hasReview = !earlier && findings.any { it.runId == latest?.id }
    val diagnostic = latest?.takeIf { it.problem != null }?.let { studyBuildDiagnosticHtml(it, hasReview, currentInputs = !earlier) }.orEmpty()
    return """<section data-stage="${state.id}"><div class="study-build-title"><span>${studyBuildEscape(state.label)}</span><span class="study-build-state">$label</span>$action</div>$progress<p class="${if (failure != null) "study-build-error" else ""}">${studyBuildEscape(message)}</p>$request$turn${if (advice.isNotEmpty()) "<p class=\"study-build-error\">${studyBuildEscape(advice)}</p>" else ""}$geometry$prior$resultNotes$reviewLinks$diagnostic</section>"""
}

/**
 * Review findings of the stage's latest attempt, coloured by kind: open errors red, open warnings
 * amber, decisions cyan. Findings of an attempt against earlier inputs are not open errors in the
 * current design, so they collapse to one muted line until a rebuild rechecks them.
 */
internal fun studyBuildFindingsHtml(state: StudyStageState, findings: List<StudyBuildFinding>): String {
    val reviews = findings.filter { it.runId == state.latest?.id }
    if (reviews.isEmpty()) return ""
    if (studyBuildLatestUsedEarlierInputs(state)) {
        val count = if (reviews.size == 1) "1 finding" else "${reviews.size} findings"
        return "<p class=\"study-build-muted\">$count from an attempt with earlier inputs; rebuild to recheck.</p>"
    }
    val open = reviews.count { it.status != "resolved" }
    return "<div class=\"study-build-findings\"><p>Findings for this attempt · $open open · ${reviews.size - open} closed</p>" +
        reviews.joinToString("") { finding ->
            val resolved = finding.status == "resolved"
            val kind = when (finding.kind) { "error" -> "Error"; "warning" -> "Needs inspection"; else -> "Decision needed" }
            val tone = when {
                resolved -> "study-build-finding-resolved"
                finding.kind == "error" -> "study-build-finding-error"
                finding.kind == "warning" -> "study-build-finding-warning"
                else -> "study-build-finding-question"
            }
            val notes = listOfNotNull("resolved".takeIf { resolved },
                if (!finding.live) "run the review source to open" else "earlier geometry".takeIf { finding.geometryStale == true })
            val labelText = studyBuildEscape((listOf("$kind · ${finding.text}") + notes).joinToString(" · "))
            if (finding.live) "<a class=\"$tone\" href=\"#box:${studyBuildEscape(finding.outputBox)}\" data-box-uid=\"${studyBuildEscape(finding.outputBox)}\" data-review-view=\"${studyBuildEscape(finding.viewId)}\" data-review-note=\"${studyBuildEscape(finding.noteId)}\">$labelText</a>"
            else "<p class=\"study-build-state $tone\">$labelText</p>"
        } + "</div>"
}

internal fun studyBuildCurrentLabel(notes: List<StudyBuildNotice>): String = when {
    notes.any { it.kind == StudyBuildNoticeKind.ERROR } -> "Current · Needs correction"
    notes.any { it.kind == StudyBuildNoticeKind.WARNING } -> "Current · Needs review"
    else -> "Current"
}

internal fun studyBuildElapsedText(job: StudyBuildRecord, now: java.time.Instant = java.time.Instant.now()): String {
    val seconds = runCatching { java.time.Duration.between(java.time.Instant.parse(job.created), now).seconds }.getOrDefault(0).coerceAtLeast(0)
    return "Elapsed ${seconds / 60}m ${seconds % 60}s · progress reports completed work, not time remaining"
}

internal fun studyBuildResultNotesHtml(notes: List<StudyBuildNotice>, previous: Boolean = false): String {
    if (notes.isEmpty()) return ""
    val heading = if (previous) "<p>Previous current result</p>" else ""
    return "<div class=\"study-build-result-notes\">$heading" + notes.joinToString("") {
        val kind = when (it.kind) { StudyBuildNoticeKind.INFO -> ""; StudyBuildNoticeKind.WARNING -> "Needs review: "; StudyBuildNoticeKind.ERROR -> "Needs correction: " }
        "<p${if (it.kind == StudyBuildNoticeKind.ERROR) " class=\"study-build-error\"" else ""}>${studyBuildEscape(kind + it.message)}</p>"
    } + "</div>"
}

/**
 * Where a failed attempt draws its own failure: `problem.details["geometryView"]`, a map whose
 * `"box"` is the uid of the box whose output shows the failing geometry in red, with an optional
 * `"caption"` describing what is drawn and an optional `"viewId"` naming the view in that output.
 */
internal data class StudyBuildGeometryView(val box: String, val caption: String?, val viewId: String?)

/** The attempt's [StudyBuildGeometryView], or null when it named none or its `"box"` is not a uid string. */
internal fun studyBuildGeometryView(problem: StudyBuildProblem): StudyBuildGeometryView? {
    val view = problem.details["geometryView"] as? Map<*, *> ?: return null
    fun text(key: String) = (view[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    return StudyBuildGeometryView(text("box") ?: return null, text("caption"), text("viewId"))
}

/**
 * A red link that opens the output of the box showing where this attempt fails, or "" when the attempt
 * named none. `data-box-reveal="output"` makes the output area's link handler reveal that box's output
 * rather than open its code.
 */
internal fun studyBuildGeometryViewHtml(problem: StudyBuildProblem): String {
    val view = studyBuildGeometryView(problem) ?: return ""
    val uid = studyBuildEscape(view.box)
    val viewAttribute = view.viewId?.let { " data-review-view=\"${studyBuildEscape(it)}\"" }.orEmpty()
    val caption = view.caption?.let { " · ${studyBuildEscape(it)}" }.orEmpty()
    return "<p class=\"study-build-geometry-view\"><a href=\"#box:$uid\" data-box-uid=\"$uid\" data-box-reveal=\"output\"$viewAttribute>Show where it fails…</a>$caption</p>"
}

/**
 * Retained diagnostics are available even when a recipe supplied no spatial finding. With
 * [currentInputs] false the attempt ran against an earlier design, so the panel is muted and says so.
 */
internal fun studyBuildDiagnosticHtml(job: StudyBuildRecord, hasReview: Boolean, currentInputs: Boolean = true): String {
    val problem = job.problem ?: return ""
    val details = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writerWithDefaultPrettyPrinter()
        .writeValueAsString(mapOf("attempt" to job.id, "stage" to job.stage, "status" to job.status,
            "updated" to job.updated, "problem" to problem))
    val location = when {
        !currentInputs -> "This attempt ran against earlier inputs, so its failure may no longer apply; rebuild to recheck. Its saved diagnostic is below."
        studyBuildGeometryView(problem) != null -> "The failing geometry is shown in red in the output of the box that “Show where it fails” opens." +
            (if (hasReview) " The finding links above point at individual problems." else "") + " Its saved diagnostic is below."
        hasReview -> "Use the finding links above to inspect the affected geometry."
        else -> "This attempt supplied no geometry location to highlight. Its saved diagnostic is below."
    }
    val (kind, summary) = if (currentInputs) "study-build-diagnostic" to "Inspect failure details"
        else "study-build-diagnostic study-build-diagnostic-earlier" to "Inspect the earlier attempt's failure"
    return """<details class="$kind" data-build-attempt="${studyBuildEscape(job.id)}"><summary>$summary</summary><p>${studyBuildEscape(location)}</p><pre>${studyBuildEscape(details)}</pre></details>"""
}

private fun studyBuildEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
            html()
        }
        catch (e: Exception) { "<div class=\"study-build-error\" style=\"color:#ff6969;user-select:text\">${escape(e.message ?: "Build runner unavailable")}</div>" }
        if (html != lastHtml && !closed) { lastHtml = html; BoxStudies.show(binding.owner, channel, html) }
    }

    private fun html(): String {
        val cards = shown.joinToString("") { state ->
            val latest = state.latest
            val running = latest?.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
            val earlier = latest != null && latest.fingerprint != state.fingerprint
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
                state.blockedBy.isNotEmpty() -> "Complete ${state.blockedBy.joinToString { id -> shown.first { it.id == id }.label }} first."
                state.inputHelp != null -> state.inputHelp.message
                else -> failure?.message ?: state.reason.orEmpty()
            }
            val advice = failure?.suggestions?.joinToString(" ").orEmpty()
            val button = when {
                running && !latest!!.cancelRequested -> button(state.id, "cancel", "Cancel", false)
                !running && state.fingerprint != null -> button(state.id, "build", if (state.current != null) "Rebuild" else "Build", true)
                else -> ""
            }
            val progress = if (running) """<progress max="1" value="${latest!!.progress}" aria-label="${escape(state.label)} progress"></progress><p class="study-build-elapsed">${studyBuildElapsedText(latest)}</p>""" else ""
            val prior = if (failure != null && state.current != null) "<p>Previous checked result is still current.</p>" else ""
            val resultNotes = studyBuildResultNotesHtml(state.resultNotes,
                state.current != null && latest?.id != state.current.id)
            val reviews = findings.filter { it.runId == latest?.id }
            val reviewLinks = if (reviews.isEmpty()) "" else {
                val open = reviews.count { it.status != "resolved" }
                val heading = if (earlier) "Findings from the earlier attempt" else "Findings for this attempt"
                "<div class=\"study-build-findings\"><p>$heading · $open open · ${reviews.size - open} closed</p>" +
                    reviews.joinToString("") { finding ->
                        val kind = when (finding.kind) { "error" -> "Error"; "warning" -> "Needs inspection"; else -> "Decision needed" }
                        val suffix = when { !finding.live -> " · run the review source to open"; finding.geometryStale == true -> " · earlier geometry"; else -> "" }
                        val labelText = escape("$kind · ${finding.text}$suffix")
                        if (finding.live) "<a href=\"#box:${escape(finding.outputBox)}\" data-box-uid=\"${escape(finding.outputBox)}\" data-review-view=\"${escape(finding.viewId)}\" data-review-note=\"${escape(finding.noteId)}\">$labelText</a>"
                        else "<p class=\"study-build-state\">$labelText</p>"
                    } + "</div>"
            }
            val diagnostic = latest?.takeIf { it.problem != null }?.let { studyBuildDiagnosticHtml(it, reviews.isNotEmpty()) }.orEmpty()
            """<section data-stage="${state.id}"><div class="study-build-title"><span>${escape(state.label)}</span><span class="study-build-state">$label</span>$button</div>$progress<p class="${if (failure != null) "study-build-error" else ""}">${escape(message)}</p>${if (advice.isNotEmpty()) "<p class=\"study-build-error\">${escape(advice)}</p>" else ""}$prior$resultNotes$reviewLinks$diagnostic</section>"""
        }
        return """
<style>
.$prefix{background:#000;color:#ddd;padding:16px;border-radius:12px;font:14px/1.45 Iosevka,monospace;max-width:640px;box-sizing:border-box}
.$prefix section+section{margin-top:24px}.$prefix .study-build-title{display:flex;align-items:center;gap:12px;user-select:none}
.$prefix .study-build-state{color:#999;font-size:inherit;margin-left:auto}.$prefix p{margin:8px 0 0;user-select:text;white-space:pre-wrap;overflow-wrap:anywhere}
.$prefix .study-build-findings{margin-top:12px;padding:12px;background:#141414;border-radius:8px}.$prefix .study-build-findings a{display:block;margin-top:10px;color:#62d7ff;text-decoration:none}.$prefix .study-build-findings a:hover{text-decoration:underline}
.$prefix .study-build-error{color:#ff6969}.$prefix progress{display:block;width:100%;height:5px;margin-top:12px;accent-color:#55d4f2;border:0;border-radius:4px;overflow:hidden}
.$prefix .study-build-result-notes{padding:12px;background:#171717;border-radius:8px;margin-top:12px}.$prefix .study-build-result-notes p:first-child{margin-top:0}
.$prefix .study-build-elapsed{color:#999}
.$prefix .study-build-diagnostic{margin-top:12px;background:#171717;padding:12px;border-radius:8px}.$prefix .study-build-diagnostic summary{color:#ff6969;cursor:pointer;user-select:none}.$prefix .study-build-diagnostic pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto;user-select:text}
.$prefix progress::-webkit-progress-bar{background:#262626}.$prefix progress::-webkit-progress-value{background:#55d4f2}
.$prefix .field-button{display:inline-flex;margin:0;padding:0;background:none;border:0}
.$prefix button.field-button-hit{font:inherit;text-transform:none;letter-spacing:normal;padding:8px 14px;border:0;border-radius:9px;outline:none;box-shadow:none;background:#262626;color:#ddd;cursor:pointer;user-select:none}
.$prefix button.important{background:#55d4f2;color:#000}.$prefix button:focus-visible{outline:none;box-shadow:none}.$prefix button:hover{filter:brightness(1.12)}
</style><div class="$prefix" data-study-builds="${escape(binding.name)}" data-afield-intrinsic-width="640">$cards${error?.let { "<p class=\"study-build-error\">${escape(it)}</p>" }.orEmpty()}</div>
""".trimIndent()
    }

    private fun button(stage: String, action: String, label: String, important: Boolean): String {
        val id = "$prefix-$stage-$action"
        return """<span class="field-button" data-control-id="$id"><button id="$id" type="button" class="field-button-hit ${if (important) "important" else ""}" aria-pressed="false">$label</button></span>"""
    }

    override fun close() { closed = true; executor.shutdownNow() }
    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
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
    fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val heading = if (previous) "<p>Previous current result</p>" else ""
    return "<div class=\"study-build-result-notes\">$heading" + notes.joinToString("") {
        val kind = when (it.kind) { StudyBuildNoticeKind.INFO -> ""; StudyBuildNoticeKind.WARNING -> "Needs review: "; StudyBuildNoticeKind.ERROR -> "Needs correction: " }
        "<p${if (it.kind == StudyBuildNoticeKind.ERROR) " class=\"study-build-error\"" else ""}>${esc(kind + it.message)}</p>"
    } + "</div>"
}

/** Retained diagnostics are available even when a recipe supplied no spatial finding. */
internal fun studyBuildDiagnosticHtml(job: StudyBuildRecord, hasReview: Boolean): String {
    val problem = job.problem ?: return ""
    fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val details = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writerWithDefaultPrettyPrinter()
        .writeValueAsString(mapOf("attempt" to job.id, "stage" to job.stage, "status" to job.status,
            "updated" to job.updated, "problem" to problem))
    val location = if (hasReview) "Use the finding links above to inspect the affected geometry."
        else "This attempt supplied no geometry location to highlight. Its saved diagnostic is below."
    return """<details class="study-build-diagnostic" data-build-attempt="${esc(job.id)}"><summary>Inspect failure details</summary><p>${esc(location)}</p><pre>${esc(details)}</pre></details>"""
}

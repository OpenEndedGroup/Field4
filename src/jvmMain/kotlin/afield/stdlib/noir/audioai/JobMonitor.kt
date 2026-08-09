@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

/**
 * The job monitor: the service's own live view of every durable job, embedded in a box output.
 *
 * It is an `<iframe>` rather than a Kotlin-rendered table on purpose — the page is served by the
 * same service that owns the jobs, so it cannot disagree with them, and it needs no polling code on
 * the Field side. Same-origin through the `/field-audio` prefix, so nothing here reaches across
 * origins.
 */

/** An embeddable, same-origin view of all durable Field Audio jobs. */
fun fieldAudioJobMonitor(
    serviceBaseUrl: String = "/field-audio",
    heightPixels: Int = 420,
): String {
    require(heightPixels >= 120) { "job monitor height must be at least 120 pixels" }
    val base = serviceBaseUrl.trimEnd('/').replace("&", "&amp;").replace("\"", "&quot;")
    return """
<iframe
    title="Field Audio jobs"
    src="$base/monitor"
    data-afield-intrinsic-width="760"
    data-afield-intrinsic-height="$heightPixels"
    style="border:none;padding:0;margin:0;display:block;width:100%;height:${heightPixels}px;background:#111417;"
></iframe>
""".trimIndent()
}

/** A small per-operation status card suitable for replacing one named Field output as events arrive. */
fun fieldAudioJobStatusHtml(
    accepted: JobAccepted,
    event: ProgressEvent? = null,
): String {
    val state = event?.state ?: accepted.state
    val progress = ((event?.progress ?: if (accepted.cacheHit) 1.0 else 0.0) * 100.0).coerceIn(0.0, 100.0)
    val message = event?.message ?: if (accepted.cacheHit) "cache hit" else "accepted"
    return fieldAudioJobStatusShell(
        jobId = accepted.jobId,
        state = state,
        progressPercent = progress,
        backend = accepted.resolvedBackend,
        message = message,
        cacheHit = accepted.cacheHit,
        error = null,
    )
}

/** Render durable inspected state, including the structured error when a job failed. */
fun fieldAudioJobStatusHtml(job: JobRecord): String = fieldAudioJobStatusShell(
    jobId = job.id,
    state = job.state,
    progressPercent = (job.progress * 100.0).coerceIn(0.0, 100.0),
    backend = job.resolvedBackend,
    message = job.error?.message,
    cacheHit = job.cacheHit,
    error = job.error,
)

private fun fieldAudioJobStatusShell(
    jobId: String,
    state: JobState,
    progressPercent: Double,
    backend: String,
    message: String?,
    cacheHit: Boolean,
    error: StructuredError?,
): String {
    val failed = state == JobState.FAILED
    val complete = state == JobState.COMPLETE || state == JobState.COMPLETED_BEFORE_CANCELLATION
    val accent = when {
        failed -> "#d87575"
        complete -> "#78c58e"
        else -> "#70b9cf"
    }
    val safeState = state.name.lowercase().replace('_', ' ').jobHtmlEscaped()
    val safeMessage = message?.jobHtmlEscaped().orEmpty()
    val errorDetail = error?.let {
        "<div style=\"margin-top:7px;color:#efaaaa\"><b>${it.code.jobHtmlEscaped()}</b> · ${it.message.jobHtmlEscaped()}</div>"
    }.orEmpty()
    return """
<section class="field-audio-job-status" data-afield-intrinsic-width="760"
 style="width:760px;max-width:100%;box-sizing:border-box;padding:11px 13px;background:#151b21;color:#d8e1e8;border:1px solid #31404a;border-radius:7px;font:12px/1.4 system-ui,sans-serif">
  <div style="display:flex;gap:10px;align-items:baseline"><b style="color:$accent">$safeState</b>
    <code style="color:#9eb0bd">${jobId.jobHtmlEscaped()}</code><span style="margin-left:auto;color:#8195a4">${backend.jobHtmlEscaped()}${if (cacheHit) " · cache" else ""}</span></div>
  <div style="height:4px;background:#26313a;border-radius:3px;margin:8px 0 5px;overflow:hidden"><div style="width:${"%.2f".format(progressPercent)}%;height:100%;background:$accent"></div></div>
  <div style="color:#99aab6">${"%.0f".format(progressPercent)}%${if (safeMessage.isNotBlank()) " · $safeMessage" else ""}</div>$errorDetail
</section>
""".trimIndent()
}

private fun String.jobHtmlEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

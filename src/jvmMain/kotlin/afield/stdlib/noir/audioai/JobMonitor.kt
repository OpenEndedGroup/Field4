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

@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import afield.stdlib.TimeViewerShell
import java.security.MessageDigest

/**
 * Analysis viewers and dashboards for beat, pitch, and note results, with zoom, pan, and a shared time cursor.
 *
 * [analysisVisualizationHtml] turns an [AnalysisResult] into a zoomable interactive display; the
 * browser decides which of the three layouts to draw from [AnalysisPreview.operation]: onset
 * strength and multi-scale tempo for a beat analysis, pYIN frequency and voicing probability for
 * a monophonic pitch analysis, or a Basic Pitch piano roll with activation curves for a
 * polyphonic one. [analysisPreviewVisualizationHtml] takes an already-fetched [AnalysisPreview]
 * and works without any service round trip.
 *
 * Dashboard variants ([analysisDashboardHtml], [analysisPreviewDashboardHtml]) stack panels with
 * a synchronized cursor, making beat/pYIN/Basic Pitch disagreements inspectable side by side.
 * All panels support wheel zoom, drag pan, hover inspection, and click-to-seek, synchronized
 * across panels that share a `syncGroup`.
 */

private val analysisVisualizationJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The analysis viewer: one function that turns an [AnalysisResult] into a zoomable beat, pitch or
 * note display for a box output.
 *
 * It draws whichever of the three analyses the result came from, deciding in the browser from
 * [AnalysisPreview.operation]. What crosses the wire is the downsampled preview and never the
 * `.npz`, which is what keeps this cheap on a long recording — and the reason it is a display and
 * not a measurement.
 */
fun analysisVisualizationHtml(
    result: AnalysisResult,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    height: Int = 360,
    points: Int = 1600,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = analysisVisualizationShell(
    label = result.data.role.removePrefix("analysis_").replace('_', ' '),
    dataUrl = "${serviceBaseUrl.trimEnd('/')}/v1/analysis-results/${result.id}/preview?points=$points",
    embeddedPreview = null,
    width = width,
    height = height,
    points = points,
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/** Render a deterministic or already-fetched preview without contacting the service. */
fun analysisPreviewVisualizationHtml(
    preview: AnalysisPreview,
    width: Int = 1100,
    height: Int = 360,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String = analysisVisualizationShell(
    label = preview.operation.removePrefix("analysis.").replace('_', ' '),
    dataUrl = null,
    embeddedPreview = analysisVisualizationJson.encodeToString(preview),
    width = width,
    height = height,
    points = (preview.series.maxOfOrNull { it.values.size } ?: 16).coerceIn(16, 4096),
    syncGroup = syncGroup,
    timeOffsetSeconds = timeOffsetSeconds,
)

/**
 * Stack several live results into one board. Zoom, pan and hover cursors are
 * synchronized, which makes beat/pYIN/Basic Pitch disagreements inspectable.
 */
fun analysisDashboardHtml(
    results: List<AnalysisResult>,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    panelHeight: Int = 270,
    points: Int = 1600,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(results.isNotEmpty()) { "analysis dashboard needs at least one result" }
    val group = syncGroup ?: analysisSyncGroup(results.map { it.id })
    return analysisDashboardShell(
        results.map {
            analysisVisualizationHtml(it, serviceBaseUrl, width, panelHeight, points, group, timeOffsetSeconds)
        },
        width,
    )
}

/** Model-free counterpart used by documentation, fixtures and cached previews. */
fun analysisPreviewDashboardHtml(
    previews: List<AnalysisPreview>,
    width: Int = 1100,
    panelHeight: Int = 270,
    syncGroup: String? = null,
    timeOffsetSeconds: Double = 0.0,
): String {
    require(previews.isNotEmpty()) { "analysis dashboard needs at least one preview" }
    val group = syncGroup ?: analysisSyncGroup(previews.mapIndexed { index, preview -> "$index:${preview.operation}" })
    return analysisDashboardShell(
        previews.map { analysisPreviewVisualizationHtml(it, width, panelHeight, group, timeOffsetSeconds) },
        width,
    )
}

private fun analysisDashboardShell(panels: List<String>, width: Int): String = """
<section class="field-analysis-dashboard" data-afield-intrinsic-width="$width">
  <style>
    .field-analysis-dashboard { width:${width}px;max-width:100%;display:grid;gap:12px }
    .field-analysis-dashboard > header { color:#aabac8;font:10px/1.4 "Iosevka Web","IBM Plex Mono",ui-monospace,monospace;
      display:flex;justify-content:space-between;padding:0 2px }
  </style>
  <header><b>analysis board</b><span>shared playhead/cursor · click seek · wheel zoom · drag pan</span></header>
  ${panels.joinToString("\n")}
</section>
""".trimIndent()

private fun analysisSyncGroup(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ids.joinToString("|").toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return "field-analysis-$digest"
}

private fun analysisVisualizationShell(
    label: String,
    dataUrl: String?,
    embeddedPreview: String?,
    width: Int,
    height: Int,
    points: Int,
    syncGroup: String?,
    timeOffsetSeconds: Double,
): String {
    require(width >= 320) { "analysis visualization width must be at least 320" }
    require(height >= 180) { "analysis visualization height must be at least 180" }
    require(points in 16..4096) { "analysis visualization points must be between 16 and 4096" }
    require(timeOffsetSeconds.isFinite()) { "analysis time offset must be finite" }
    return TimeViewerShell.render(
        config = mapOf(
            "source" to "analysis", "analysisUrl" to dataUrl, "label" to label,
            "syncGroup" to syncGroup.orEmpty(), "syncOffset" to timeOffsetSeconds,
            "plotHeight" to height, "xUnit" to "s", "points" to points,
            "readoutIdle" to "hover to inspect · click to seek linked audio · wheel to zoom · drag to pan",
            "lanes" to listOf(mapOf("name" to "loading preview…")),
        ),
        widthPx = width,
        preview = embeddedPreview,
        accent = "#61c8d9",
        extraClass = "field-analysis-view",
    )
}


@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.AudioVisualizerSource
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private fun String.corpusHtml(): String = replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun corpusNumber(value: Double): String = String.format(Locale.US, "%.3f", value)
private fun corpusTime(value: Double): String = String.format(Locale.US, "%.2f", value)

/**
 * A source-relative audition board for [CorpusPreview.neighbours].
 *
 * Every player maps its own absolute file position back to zero at the beginning of its segment.
 * The general audio visualizer's event bus can therefore share hover, zoom, seeking and playheads
 * across recordings with unrelated file timelines. The splice player remains independent: it is a
 * short query-tail to neighbour-head judgement rather than another aligned view.
 */
fun corpusMicroscopeHtml(
    preview: CorpusPreview,
    queryAudio: AssetRepresentation,
    serviceBaseUrl: String = "/field-audio",
    width: Int = 1100,
    cardWidth: Int = 510,
    syncGroup: String = "corpus-${preview.query.assetId.takeLast(12)}-${preview.query.startSeconds}",
): String {
    require(preview.operation == "analysis.find_neighbours") {
        "corpus microscope requires an analysis.find_neighbours preview"
    }
    require(width >= 640 && cardWidth >= 320) { "corpus microscope dimensions are too small" }
    require(queryAudio.mediaType in setOf("audio/wav", "audio/x-wav")) {
        "queryAudio must be a WAV representation"
    }
    val base = serviceBaseUrl.trimEnd('/')
    fun player(
        representation: AssetRepresentation,
        start: Double,
        duration: Double,
        label: String,
        height: Int,
        synchronized: Boolean,
    ) = AudioVisualizer.renderHtml(
        AudioVisualizerSource(
            audioUrl = "$base${representation.binaryUrl}",
            waveformUrl = AudioVisualizer.fieldAudioWaveformUrl(representation.id, base),
            durationSeconds = representation.durationSeconds,
            label = label,
        ),
        AudioVisualizerOptions(
            width = if (synchronized) cardWidth else cardWidth - 20,
            waveformHeight = height,
            initialStartSeconds = start,
            initialDurationSeconds = duration,
            initialPositionSeconds = start,
            syncGroup = if (synchronized) syncGroup else null,
            syncOffsetSeconds = if (synchronized) -start else 0.0,
            followPlayhead = true,
        ),
    )

    val sourcePlayer = player(
        queryAudio, preview.query.startSeconds, preview.query.durationSeconds,
        "query · ${corpusTime(preview.query.startSeconds)}–${corpusTime(preview.query.startSeconds + preview.query.durationSeconds)} s",
        125, true,
    )
    val weights = preview.weights.entries.sortedBy { it.key }.joinToString("") { (name, value) ->
        "<span><b>${name.corpusHtml()}</b> ${corpusNumber(value)}</span>"
    }
    val cards = preview.neighbours.mapIndexed { index, neighbour ->
        val segment = neighbour.segment
        val filename = neighbour.audio.metadata["original_filename"]?.jsonPrimitive?.contentOrNull
            ?: segment.assetId.removePrefix("sha256:").take(12)
        val similarity = 100.0 * (1.0 - min(2.0, max(0.0, neighbour.distance)) / 2.0)
        val groupBars = neighbour.groupDistances.entries.sortedBy { it.key }.joinToString("") { (name, distance) ->
            val closeness = 100.0 * (1.0 - min(2.0, max(0.0, distance)) / 2.0)
            """<span title="distance ${corpusNumber(distance)}"><b>${name.corpusHtml()}</b><i><em style="width:${corpusNumber(closeness)}%"></em></i></span>"""
        }
        val neighbourPlayer = player(
            neighbour.audio, segment.startSeconds, segment.durationSeconds,
            "$filename · ${corpusTime(segment.startSeconds)}–${corpusTime(segment.startSeconds + segment.durationSeconds)} s",
            92, true,
        )
        val splicePlayer = neighbour.splicePreview?.let {
            player(
                it, 0.0, it.durationSeconds ?: 4.5,
                "query → neighbour splice", 58, false,
            )
        }.orEmpty()
        """
        <article class="fcm-card">
          <header><strong>${index + 1}</strong><span>${corpusNumber(similarity)}% similarity · distance ${corpusNumber(neighbour.distance)}</span></header>
          $neighbourPlayer
          <div class="fcm-groups">$groupBars</div>
          $splicePlayer
          <footer>
            <span>${segment.scaleSeconds} s scale</span>
            <span>${if (segment.previousSegmentId != null) "← source neighbour" else "source beginning"}</span>
            <span>${if (segment.nextSegmentId != null) "source neighbour →" else "source end"}</span>
          </footer>
        </article>
        """.trimIndent()
    }.joinToString("\n")

    return """
    <section class="field-corpus-microscope" data-afield-intrinsic-width="$width">
      <style>
        .field-corpus-microscope { width:${width}px;max-width:100%;box-sizing:border-box;background:#14191f;
          color:#d9e2e8;border:1px solid #2d3943;border-radius:9px;padding:14px;font:12px/1.35 system-ui,sans-serif }
        .field-corpus-microscope > header { display:flex;align-items:baseline;gap:12px;margin-bottom:10px }
        .field-corpus-microscope > header h3 { margin:0;color:#80c9d4;font-size:17px;font-weight:600 }
        .field-corpus-microscope > header p { margin:0;color:#8295a4 }
        .field-corpus-microscope .fcm-query { padding:11px;background:#1a2128;border:1px solid #35434e;border-radius:7px }
        .field-corpus-microscope .fcm-weights { display:flex;flex-wrap:wrap;gap:6px;margin:9px 0 12px }
        .field-corpus-microscope .fcm-weights span { padding:3px 7px;border:1px solid #35434e;border-radius:10px;color:#91a3b0 }
        .field-corpus-microscope .fcm-grid { display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:10px }
        .field-corpus-microscope .fcm-card { min-width:0;background:#1b2026;border:1px solid #313c46;border-radius:7px;padding:10px }
        .field-corpus-microscope .fcm-card > header { display:flex;justify-content:space-between;color:#93a3af;margin-bottom:7px }
        .field-corpus-microscope .fcm-card > header strong { color:#efc06d;font-size:16px }
        .field-corpus-microscope .fcm-card > .field-audio-visualizer { margin-top:8px }
        .field-corpus-microscope .fcm-groups { display:grid;grid-template-columns:repeat(2,minmax(120px,1fr));gap:4px 10px;margin:9px 0 }
        .field-corpus-microscope .fcm-groups span { display:grid;grid-template-columns:70px 1fr;align-items:center;gap:5px;color:#8494a0;font-size:10px }
        .field-corpus-microscope .fcm-groups i { height:3px;background:#303943;overflow:hidden }
        .field-corpus-microscope .fcm-groups em { display:block;height:100%;background:#70c1ce }
        .field-corpus-microscope footer { display:flex;justify-content:space-between;gap:8px;color:#748590;margin-top:8px;font:10px ui-monospace,monospace }
      </style>
      <header><h3>corpus microscope</h3><p>${preview.neighbours.size} neighbours from ${preview.indexedSegmentCount} eligible indexed windows · shared relative-time cursor</p></header>
      <div class="fcm-query">$sourcePlayer</div>
      <div class="fcm-weights">$weights</div>
      <div class="fcm-grid">$cards</div>
    </section>
    """.trimIndent()
}

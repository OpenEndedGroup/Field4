package afield.stdlib.noir.audioai

import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.AudioVisualizerSource

/** Render any Field Audio WAV representation with range-aware server-side waveform summaries. */
fun AssetRepresentation.audioVisualizerHtml(
    serviceBaseUrl: String = "/field-audio",
    label: String? = role,
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
): String {
    require(mediaType in setOf("audio/wav", "audio/x-wav")) {
        "audio visualization requires a WAV representation, got $mediaType"
    }
    val base = serviceBaseUrl.trimEnd('/')
    return AudioVisualizer.renderHtml(
        AudioVisualizerSource(
            audioUrl = "$base$binaryUrl",
            waveformUrl = AudioVisualizer.fieldAudioWaveformUrl(id, base),
            durationSeconds = durationSeconds,
            label = label,
        ),
        options,
    )
}

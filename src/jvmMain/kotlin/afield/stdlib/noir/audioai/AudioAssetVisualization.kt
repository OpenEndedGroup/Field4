@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.AudioVisualizerSource

/**
 * The one-line bridge from a Field Audio [AssetRepresentation] to Field's general-purpose waveform
 * player.
 *
 * It exists so that playing a service asset needs no URL arithmetic: the extension resolves the
 * binary path and the server-side waveform endpoint against the same-origin `/field-audio` prefix
 * and hands both to `AudioVisualizer`, which then fetches only the envelope of whatever window the
 * user is looking at.
 */

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

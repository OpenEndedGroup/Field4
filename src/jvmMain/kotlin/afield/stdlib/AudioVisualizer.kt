@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import afield.Box
import afield.four.audio.WavFloatArrayWriter
import java.util.Base64

/**
 * The recorded-audio waveform player: a zoomable, seekable view of a recording, as one
 * self-contained HTML fragment for a box output.
 *
 * It shows audio that already exists, rather than a spectrum or a live meter, with the interaction
 * an editor expects (wheel to zoom, drag to pan, click to seek, a playhead that tracks playback)
 * and the browser's own transport underneath.
 *
 * What it draws is a min/max envelope of the *visible* window, not the whole file decimated. Where
 * a server can summarise a window ([AudioVisualizerSource.waveformUrl] — Field Audio's waveform
 * endpoint, for instance) the component asks for one envelope per view, so an hour-long recording
 * costs the same as a bar of it; where there is no server it decodes the clip in the browser and
 * does the same arithmetic locally. Min/max rather than an average is what keeps a single-sample
 * click visible in a window drawn at 1000 points.
 *
 * Start from `Box.displayAudio` for a URL, `Box.displayAudioSamples` for a `FloatArray` you
 * generated, or `Box.displayAudioBytes` for an encoded clip you have in memory.
 */

/**
 * Where a waveform player gets its audio, and how it gets the envelope it draws.
 *
 * Exactly one of three strategies applies, in this order: [waveformUrl] (the server summarises
 * each visible window — the only workable choice for anything long or remote),
 * [inlineWaveformWavBase64] (a PCM16 copy the browser analyses locally while playing something
 * else), or neither, in which case the component decodes [audioUrl] itself.
 */
data class AudioVisualizerSource(
    /** What the `<audio>` element plays: an ordinary URL or a `data:` URI. Always required. */
    val audioUrl: String,
    /** A GET endpoint accepting start_seconds, duration_seconds, and points. */
    val waveformUrl: String? = null,
    /** Browser-compatible PCM16 WAV used only for local waveform analysis, without replacing playback audio. */
    val inlineWaveformWavBase64: String? = null,
    /**
     * Total length in seconds, if it is known up front.
     *
     * Supplying it lets the player draw its ruler before any audio has loaded; without it the
     * component waits for the browser's `loadedmetadata`, which for a remote file is a round trip.
     */
    val durationSeconds: Double? = null,
    /** A caption above the waveform, HTML-escaped. Null draws no caption. */
    val label: String? = null,
)

/**
 * How a waveform player looks and what it lets the user do.
 *
 * The defaults are the ones the docs pages use: 960 px wide, a 150 px waveform, a playhead, full
 * interaction and the browser's own transport controls underneath.
 */
data class AudioVisualizerOptions(
    /** Maximum width in CSS pixels; the component is fluid below it. Must be positive. */
    val width: Int = 960,
    /** Height of the waveform plot area in CSS pixels. At least 48, and channels share it as lanes. */
    val waveformHeight: Int = 150,
    /** Draw the moving playhead while the audio plays. */
    val showPlayhead: Boolean = true,
    /** Keep the view scrolled to the playhead as it moves. */
    val followPlayhead: Boolean = true,
    /**
     * Allow wheel-zoom, drag-pan, click-to-seek and the keyboard shortcuts.
     *
     * False also hides the zoom buttons, which is what you want for a figure in a page rather than an
     * instrument in a box.
     */
    val interactive: Boolean = true,
    /** Show the browser's own `<audio>` transport. The element is always present; this only hides it. */
    val showNativeControls: Boolean = true,
    /** Left edge of the initial view, in seconds. Must be non-negative. */
    val initialStartSeconds: Double = 0.0,
    /** Width of the initial view in seconds; null shows the whole clip. */
    val initialDurationSeconds: Double? = null,
    /** Where to place the playhead once metadata has loaded, in seconds; null leaves it at zero. */
    val initialPositionSeconds: Double? = null,
    /** Optional DOM-event group for synchronizing this player with plots and other time views. */
    val syncGroup: String? = null,
    /** Maps player-local seconds to the shared timeline: shared = local + offset. */
    val syncOffsetSeconds: Double = 0.0,
    /** CSS colour for the first channel's trace and the focus highlights. The second channel is amber. */
    val accent: String = "#63c7dc",
)

/**
 * General-purpose waveform player for Field output areas.
 *
 * Large/remote assets should supply [AudioVisualizerSource.waveformUrl]. The browser then asks
 * only for a min/max envelope of the visible interval. Small self-contained examples may omit
 * it; the component decodes the clip locally and uses the same interaction model.
 */
object AudioVisualizer {
    /**
     * The player as one self-contained HTML fragment — markup, styles and script together.
     *
     * This is the entry point for audio that already has a URL. Validates the options in the JVM
     * (width, height, non-negative times) so a bad value is a Kotlin error rather than a silently
     * broken widget.
     */
    fun renderHtml(
        source: AudioVisualizerSource,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
    ): String {
        require(options.width > 0) { "width must be positive" }
        require(options.waveformHeight >= 48) { "waveformHeight must be at least 48" }
        require(options.initialStartSeconds >= 0.0) { "initialStartSeconds must be non-negative" }
        require(options.initialPositionSeconds == null || options.initialPositionSeconds >= 0.0) {
            "initialPositionSeconds must be non-negative"
        }
        require(options.syncOffsetSeconds.isFinite()) { "syncOffsetSeconds must be finite" }
        val duration = source.durationSeconds?.takeIf { it > 0.0 }
        val initialDuration = options.initialDurationSeconds?.takeIf { it > 0.0 }
        val audio = """<audio controls preload="metadata" src="${source.audioUrl.audioHtmlEscaped()}"""" +
            """ style="display:${if (options.showNativeControls) "block" else "none"}"></audio>"""
        return TimeViewerShell.render(
            config = mapOf(
                "source" to "audio", "start" to 0.0, "end" to (duration ?: 0.0),
                "initialStart" to options.initialStartSeconds, "initialDuration" to initialDuration,
                "syncGroup" to options.syncGroup.orEmpty(), "syncOffset" to options.syncOffsetSeconds,
                "label" to source.label.orEmpty(), "xUnit" to "clock", "interactive" to options.interactive,
                "plotHeight" to options.waveformHeight + 36, "margin" to mapOf("l" to 40, "r" to 12),
                "colors" to mapOf("primary" to options.accent),
                "lanes" to listOf(mapOf("name" to "audio", "minimum" to -1.0, "maximum" to 1.0)),
                "audio" to mapOf(
                    "waveformUrl" to source.waveformUrl, "inlineWav" to source.inlineWaveformWavBase64,
                    "showPlayhead" to options.showPlayhead, "follow" to options.followPlayhead,
                    "zoomAtPlayhead" to true, "initialPosition" to options.initialPositionSeconds,
                ),
            ),
            widthPx = options.width,
            tail = audio,
            accent = options.accent,
            extraClass = "field-audio-visualizer",
        )
    }

    /**
     * A player for samples you have in memory: mono `FloatArray` in, self-contained player out.
     *
     * The samples are encoded as a PCM16 WAV and inlined as a data URI, so the whole clip travels
     * inside the fragment — fine for a few seconds of audio, wasteful for minutes. Pass [wavBytes] to
     * play a *different* encoding of the same audio (the original file, say) while still drawing the
     * envelope from [samples].
     */
    fun renderSamplesHtml(
        samples: FloatArray,
        sampleRate: Int,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
        wavBytes: ByteArray? = null,
        label: String? = null,
    ): String {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val waveformWav = WavFloatArrayWriter.encodeMono16(samples, sampleRate)
        val playbackWav = wavBytes ?: waveformWav
        val uri = "data:audio/wav;base64,${Base64.getEncoder().encodeToString(playbackWav)}"
        return renderHtml(
            AudioVisualizerSource(
                audioUrl = uri,
                inlineWaveformWavBase64 = if (wavBytes == null) null else
                    Base64.getEncoder().encodeToString(waveformWav),
                durationSeconds = samples.size.toDouble() / sampleRate,
                label = label,
            ),
            options,
        )
    }

    /**
     * Embed a short browser-decodable recording (WAV, MP3, Ogg, and so on) in a self-contained
     * player. Prefer [renderHtml] with ordinary audio and waveform URLs for long recordings.
     */
    fun renderBytesHtml(
        bytes: ByteArray,
        mediaType: String = "audio/wav",
        durationSeconds: Double? = null,
        label: String? = null,
        options: AudioVisualizerOptions = AudioVisualizerOptions(),
    ): String {
        require(bytes.isNotEmpty()) { "audio bytes must not be empty" }
        require(mediaType.startsWith("audio/") && mediaType.none { it == '\r' || it == '\n' || it == ';' }) {
            "mediaType must be a simple audio/* media type"
        }
        val uri = "data:$mediaType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        return renderHtml(
            AudioVisualizerSource(
                audioUrl = uri,
                durationSeconds = durationSeconds,
                label = label,
            ),
            options,
        )
    }

    /**
     * The Field Audio waveform endpoint for one representation id, ready for
     * [AudioVisualizerSource.waveformUrl].
     *
     * The id is URL-encoded, so a content address with its `sha256:` colon survives.
     */
    fun fieldAudioWaveformUrl(representationId: String, serviceBaseUrl: String = "/field-audio"): String =
        "${serviceBaseUrl.trimEnd('/')}/v1/representations/${representationId.audioUrlSegment()}/waveform"
}

/** Display a URL-backed recording in this Field box. */
fun Box.displayAudio(
    source: AudioVisualizerSource,
    channel: String = "audio",
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
): String = AudioVisualizer.renderHtml(source, options).also { display(channel, it) }

/** Display generated or otherwise in-memory mono samples in this Field box. */
fun Box.displayAudioSamples(
    samples: FloatArray,
    sampleRate: Int,
    channel: String = "audio",
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
    wavBytes: ByteArray? = null,
    label: String? = null,
): String = AudioVisualizer.renderSamplesHtml(
    samples = samples,
    sampleRate = sampleRate,
    options = options,
    wavBytes = wavBytes,
    label = label,
).also { display(channel, it) }

/** Display a short browser-decodable WAV, MP3, Ogg, or similar byte buffer in this Field box. */
fun Box.displayAudioBytes(
    bytes: ByteArray,
    mediaType: String = "audio/wav",
    channel: String = "audio",
    durationSeconds: Double? = null,
    label: String? = null,
    options: AudioVisualizerOptions = AudioVisualizerOptions(),
): String = AudioVisualizer.renderBytesHtml(
    bytes = bytes,
    mediaType = mediaType,
    durationSeconds = durationSeconds,
    label = label,
    options = options,
).also { display(channel, it) }

private fun String.audioHtmlEscaped(): String = buildString(length) {
    for (character in this@audioHtmlEscaped) append(
        when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> character
        }
    )
}

private fun String.audioUrlSegment(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

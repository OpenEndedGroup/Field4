package afield.stdlib

import afield.Box
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AudioVisualizerTest {
    @Test
    fun rendersRemoteZoomablePlayerWithOptionalPlayhead() {
        val html = AudioVisualizer.renderHtml(
            AudioVisualizerSource(
                audioUrl = "/audio/example.wav",
                waveformUrl = "/waveform/example",
                durationSeconds = 12.5,
                label = "Example",
            ),
            AudioVisualizerOptions(
                showPlayhead = false,
                initialDurationSeconds = 3.0,
                initialPositionSeconds = 1.25,
                syncGroup = "take-a",
                syncOffsetSeconds = 18.0,
            ),
        )

        // Rendered by the one time-axis viewer; the options travel in its JSON config.
        assertContains(html, "class=\"field-signal-viewer fsv-audio field-audio-visualizer\"")
        assertContains(html, "\"waveformUrl\":\"/waveform/example\"")
        assertContains(html, "\"showPlayhead\":false")
        assertContains(html, "\"follow\":true")
        assertContains(html, "\"initialPosition\":1.25")
        assertContains(html, "\"initialDuration\":3.0")
        assertContains(html, "\"syncGroup\":\"take-a\"")
        assertContains(html, "\"syncOffset\":18.0")
        assertContains(html, "\"zoomAtPlayhead\":true")
        assertContains(html, "data-afield-intrinsic-width=\"960\"")
        assertContains(html, "width:960px;max-width:100%;box-sizing:border-box")
        assertContains(html, "<audio controls preload=\"metadata\" src=\"/audio/example.wav\"")
        // The runtime's contract with Field Audio's waveform endpoint and the time bus.
        for (needle in listOf("start_seconds", "duration_seconds", "'points'", "resize.observe(stage)",
            "request timed out after 15 seconds", "field-time-cursor", "field-time-seek", "field-time-window",
            "field-time-transport", "bus.dispatchEvent", "p > start + span"))
            assertContains(html, needle)
        assertFalse("observe(canvas)" in html)
        assertFalse("<canvas" in html, "the hand-written canvas renderer is gone")
    }

    @Test
    fun buildsFieldAudioWaveformRoute() {
        assertContains(
            AudioVisualizer.fieldAudioWaveformUrl("rep_a:b"),
            "/field-audio/v1/representations/rep_a%3Ab/waveform",
        )
    }

    @Test
    fun customPlaybackWavGetsIndependentBrowserCompatibleWaveformData() {
        val html = AudioVisualizer.renderSamplesHtml(
            samples = floatArrayOf(-1f, 0f, 1f),
            sampleRate = 8_000,
            wavBytes = byteArrayOf(1, 2, 3, 4),
        )

        assertContains(html, "src=\"data:audio/wav;base64,AQIDBA==\"")
        assertContains(html, "\"inlineWav\":\"UklGR")
        assertFalse("waveform: Unable to decode audio data" in html)
    }

    @Test
    fun fieldHelpersDisplayUrlSamplesAndEncodedAudioBytes() {
        val box = Box("audio-visualizer-test")
        var channel: String? = null
        var displayed: String? = null
        box.output.displayProxy = { nextChannel, value ->
            channel = nextChannel
            displayed = value as String
        }

        val urlHtml = box.displayAudio(
            AudioVisualizerSource("/recordings/example.wav", label = "URL recording"),
            channel = "recording",
        )
        assertEquals("recording", channel)
        assertEquals(urlHtml, displayed)
        assertContains(urlHtml, "/recordings/example.wav")

        val samplesHtml = box.displayAudioSamples(floatArrayOf(-1f, 0f, 1f), 8_000)
        assertContains(samplesHtml, "data:audio/wav;base64,UklGR")

        val bytesHtml = box.displayAudioBytes(
            byteArrayOf(1, 2, 3, 4),
            mediaType = "audio/mpeg",
            label = "tiny MP3 fixture",
        )
        assertContains(bytesHtml, "data:audio/mpeg;base64,AQIDBA==")
        assertContains(bytesHtml, "tiny MP3 fixture")
    }
}

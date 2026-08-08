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
            ),
        )

        assertContains(html, "data-waveform-url=\"/waveform/example\"")
        assertContains(html, "data-playhead=\"false\"")
        assertContains(html, "data-follow-playhead=\"true\"")
        assertContains(html, "data-position=\"1.250000000\"")
        assertContains(html, "wheel zoom at playhead · drag pan · click seek")
        assertContains(html, "start_seconds")
        assertContains(html, "duration_seconds")
        assertContains(html, "points")
        assertContains(html, "data-afield-intrinsic-width=\"960\"")
        assertContains(html, "width:960px;max-width:100%;box-sizing:border-box")
        assertContains(html, "observe(stage)")
        assertFalse("observe(canvas)" in html)
        assertContains(html, "class=\"fav-busy\"")
        assertContains(html, "animation:fav-spin 760ms linear infinite")
        assertContains(html, "const anchor=zoomAnchor(fallbackAnchor)")
        assertContains(html, "setLoading(true)")
        assertContains(html, "if(playhead>viewStart+viewDuration)")
        assertContains(html, "request timed out after 15 seconds")
        assertContains(html, "Promise.race([promise,deadline])")
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
        assertContains(html, "data-inline-waveform-wav=\"UklGR")
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

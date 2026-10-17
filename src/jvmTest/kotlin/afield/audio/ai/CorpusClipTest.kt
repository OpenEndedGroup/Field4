package afield.audio.ai

import afield.audio.engine.WavFloatData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A placed corpus neighbour: the box code it gets, and the window that code plays. */
class CorpusClipTest {
    private val preview = FieldAudioClient.json.decodeFromString<CorpusPreview>(
        File("public/docs/assets/audio_ai_corpus_microscope/corpus.json").readText())

    @Test fun aPlacedNeighboursCodeLaunchesItsRecordingOverItsWindow() {
        val neighbour = preview.neighbours.first()
        val code = corpusNeighbourCode(neighbour)
        val call = Regex("""corpusClip\(\s*"([^"]+)",\s*startSeconds = ([\d.]+),\s*endSeconds = ([\d.]+),\s*\)""").find(code)
        requireNotNull(call) { code }
        assertEquals(neighbour.segment.assetId, call.groupValues[1])
        assertEquals(neighbour.segment.startSeconds, call.groupValues[2].toDouble())
        assertEquals(neighbour.segment.startSeconds + neighbour.segment.durationSeconds, call.groupValues[3].toDouble())
        assertTrue("audio(clip.sampler())" in code, code)
        assertTrue(corpusNeighbourName(neighbour).endsWith("21.00–27.00 s"), corpusNeighbourName(neighbour))
    }

    @Test fun aClipHoldsExactlyItsWindow() {
        // A recording whose sample value is its own index, at 1 kHz: the window's first and last values say where it was cut.
        val wav = WavFloatData(FloatArray(40_000) { it.toFloat() }, 1000)
        val clip = CorpusClip.of("sha256:x", wav, startSeconds = 21.0, endSeconds = 27.0)
        assertEquals(6000, clip.sample.lengthSamples)
        assertEquals(21_000f, clip.sample.data.first())
        assertEquals(26_999f, clip.sample.data.last())
        assertEquals(1000f, clip.sample.sampleRate)
    }
}

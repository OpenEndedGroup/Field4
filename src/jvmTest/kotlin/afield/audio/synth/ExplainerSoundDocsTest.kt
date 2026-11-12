package afield.audio.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Sound explainer's listening clips follow the figures they play.
 *
 * [ExplainerSoundDocs] rebuilds five figures' signal graphs outside their boxes. These tests hold
 * each rebuilt graph to the box it came from: every line [ExplainerSoundDocs.FIGURE_LINES] names
 * must still be in that figure's code in the transcript, so changing a figure's sound fails here
 * until the clip's scene changes with it. They also render every scene once.
 */
class ExplainerSoundDocsTest {

    private val transcript = File("dev_docs/agents/transcripts/explainer_sound.yaml").readText()

    /** The `code` block of the box created with `name: <figure>`, its lines trimmed. */
    private fun figureLines(figure: String): Set<String> {
        val start = transcript.indexOf("\n      name: $figure\n")
        assertTrue(start >= 0, "no box named $figure in explainer_sound.yaml")
        val end = transcript.indexOf("\n  - ", start).let { if (it < 0) transcript.length else it }
        return transcript.substring(start, end).lines().map { it.trim() }.toSet()
    }

    @Test
    fun `every scene's source lines are still in its figure`() {
        assertEquals(ExplainerSoundDocs.SCENES.keys, ExplainerSoundDocs.FIGURE_LINES.keys)
        val stale = ExplainerSoundDocs.FIGURE_LINES.flatMap { (scene, source) ->
            val (figure, lines) = source
            val code = figureLines(figure)
            lines.filter { it.trim() !in code }.map { "$scene ($figure): $it" }
        }
        assertTrue(stale.isEmpty(), "lines ExplainerSoundDocs rebuilds that the figure no longer has:\n" + stale.joinToString("\n"))
    }

    @Test
    fun `a changed figure line is caught`() {
        val code = figureLines("beats")
        assertTrue("val tones = [SineOsc(Param(221f)), SineOsc(second)]" !in code)
    }

    @Test
    fun `every scene renders sound`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "explainer-sound-test").apply { mkdirs() }
        for (scene in ExplainerSoundDocs.SCENES.keys) {
            val wav = File(dir, "$scene.wav")
            ExplainerSoundDocs.renderWav(scene, wav.path)
            assertTrue(wav.length() > 44_000, "$scene rendered ${wav.length()} bytes")
        }
    }
}

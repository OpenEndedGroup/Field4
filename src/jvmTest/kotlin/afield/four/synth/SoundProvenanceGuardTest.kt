package afield.four.synth

import afield.four.synth.SoundRenderPathDocs.Voice
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `public/docs/sound.md` says, of every audio figure on it, which voice produced it. This is the
 * test that makes those sentences claims rather than decoration.
 *
 * The interesting assertion is the last one. It **decodes the committed `.wav`** and correlates it
 * against a fresh stand-in render of the same pattern, so a figure captioned "real Dirt-Samples"
 * that has quietly reverted to bursts of white noise fails the build. That check deliberately needs
 * no sample library: `patRenderStereo` is in the source tree, so a CI box with no 400MB third-party
 * download — which is every CI box — can still catch the regression. The reverse case is caught
 * too, and matters just as much: a stand-in figure whose caption has drifted onto the wrong asset.
 *
 * What this cannot do is verify that a sample-backed asset was rendered from *the Tidal library
 * specifically*. It verifies that it was not rendered from the synth. That is the failure mode the
 * fallback creates, and it is the one worth a guard.
 */
class SoundProvenanceGuardTest {

    private val page = File("public/docs/sound.md")
    private val assetDir = File("public/docs/assets/sound_render_path")

    /** One `audio="…" audio-spec="…" audio-renderer="…"` fence, as the page wrote it. */
    private class Fence(val audio: String, val spec: String, val renderer: String?, val index: Int)

    /** The audio fences on the page, in order. Mirrors `parse_runner_options` in docs_hooks.py. */
    private fun fences(): List<Fence> {
        val text = page.readText()
        val header = Regex("""```+[a-z_]*runner \{([^}\n]*)\}""")
        return header.findAll(text).mapIndexedNotNull { index, match ->
            val attrs = match.groupValues[1]
            fun attr(name: String) = Regex("""\b$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)
            val audio = attr("audio") ?: return@mapIndexedNotNull null
            Fence(audio, attr("audio-spec").orEmpty(), attr("audio-renderer"), index)
        }.toList()
    }

    @Test
    fun `the page exists and its audio fences all come from this renderer`() {
        assertTrue(page.isFile, "${page.path} is missing; the guard has nothing to check")
        val fences = fences()
        assertTrue(fences.isNotEmpty(), "${page.path} publishes no audio at all, which cannot be right")
        fences.forEach { fence ->
            assertEquals(
                "afield.four.synth.SoundRenderPathDocs",
                fence.renderer,
                "${page.path}: audio fence ${fence.audio} must be rendered by SoundRenderPathDocs, " +
                    "because that is the only renderer whose figures declare a voice",
            )
            // An unknown name throws, with the known ones in the message.
            SoundRenderPathDocs.figure(fence.spec)
        }
    }

    @Test
    fun `every figure is published exactly once, and nothing else is`() {
        val published = fences().map { it.spec }
        val declared = SoundRenderPathDocs.FIGURES.map { it.name }

        val duplicates = published.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "${page.path} publishes ${duplicates.sorted()} more than once")

        assertEquals(
            declared.sorted(),
            published.sorted(),
            "${page.path} and SoundRenderPathDocs.FIGURES disagree about what is published. " +
                "A figure with no fence is dead code; a fence with no figure cannot render.",
        )
    }

    @Test
    fun `each fence carries its figure's declared voice label verbatim`() {
        val text = page.readText()
        fences().forEach { fence ->
            val figure = SoundRenderPathDocs.figure(fence.spec)
            assertTrue(
                figure.voice.pageLabel in text,
                "${page.path} publishes '${fence.spec}', which declares ${figure.voice}, but the page " +
                    "never says so. It must contain, verbatim:\n\n    ${figure.voice.pageLabel}\n",
            )
            // …and near the fence, not in some unrelated section three screens away.
            val fenceAt = text.indexOf(fence.audio)
            val labelAt = text.indexOf(figure.voice.pageLabel, startIndex = fenceAt)
            assertTrue(
                labelAt in (fenceAt + 1)..(fenceAt + NEARBY_CHARS),
                "${page.path}: the voice label for '${fence.spec}' is not next to its fence " +
                    "(fence at $fenceAt, nearest label after it at $labelAt). A reader has to be able " +
                    "to see the caption and the player at the same time for it to mean anything.",
            )
        }
    }

    @Test
    fun `no figure carries a voice label that contradicts another figure's`() {
        // Two different voices must not share a sentence, or the check above proves nothing.
        val labels = Voice.entries.map { it.pageLabel }
        assertEquals(labels.size, labels.toSet().size, "two Voice values share a pageLabel")
        Voice.entries.forEach { a ->
            Voice.entries.filter { it != a }.forEach { b ->
                assertTrue(
                    a.pageLabel !in b.pageLabel,
                    "${a.pageLabel} is a substring of ${b.pageLabel}, so matching one matches the other",
                )
            }
        }
    }

    /**
     * The load-bearing one: what the committed asset is actually made of.
     *
     * Skipped, loudly, when the assets are not there yet — a docs build generates them, and a
     * checkout that has not run one should not fail here. It does *not* skip when they exist,
     * whether or not this machine has a sample library.
     */
    @Test
    fun `the committed audio matches the voice its figure declares`() {
        val fences = fences()
        val present = fences.filter { File(page.parentFile, it.audio).isFile }
        if (present.isEmpty()) {
            println(
                "SoundProvenanceGuardTest: no committed audio under ${assetDir.path} yet — " +
                    "run a docs build for `sound` to generate it. Skipping the waveform check."
            )
            return
        }

        val problems = mutableListOf<String>()
        present.forEach { fence ->
            val file = File(page.parentFile, fence.audio)
            val audio = readWavStereo(file)
            val figure = SoundRenderPathDocs.figure(fence.spec)
            val measured = SoundRenderPathDocs.measureProvenance(fence.spec, audio)
            println("${file.name}: declares ${figure.voice}, $measured")
            measured.disagreements().forEach { problems += "${file.name}: $it" }
        }
        assertTrue(
            problems.isEmpty(),
            "the committed audio contradicts the page:\n" + problems.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The guard has to be able to fail. A stand-in render substituted for a sample-backed figure is
     * exactly the regression this exists to catch, so construct it and confirm it is caught —
     * otherwise the check above is a check that everything is fine.
     */
    @Test
    fun `substituting the stand-in for a sample-backed figure is detected`() {
        val victim = SoundRenderPathDocs.FIGURES.first { it.voice.usesSamples }
        val forged = SoundRenderPathDocs.standInReference(victim.name)
        val measured = SoundRenderPathDocs.measureProvenance(victim.name, forged)
        val disagreements = measured.disagreements()
        assertTrue(
            disagreements.isNotEmpty(),
            "the stand-in render passed as '${victim.name}' (${victim.voice}) — $measured — so the " +
                "provenance guard cannot detect the one thing it exists for",
        )
        println("negative control: '${victim.name}' rendered from the stand-in is rejected — $disagreements")
    }

    /** And the mirror: the real render of a stand-in figure must not be rejected by accident. */
    @Test
    fun `a correctly rendered stand-in figure passes`() {
        val figure = SoundRenderPathDocs.FIGURES.first { it.voice == Voice.STAND_IN }
        val audio = SoundRenderPathDocs.render(figure.name).audio
        assertEquals(
            emptyList(),
            SoundRenderPathDocs.measureProvenance(figure.name, audio).disagreements(),
            "a freshly rendered stand-in figure is being rejected, so the guard is over-tight",
        )
    }

    // ── WAV decoding ───────────────────────────────────────────────────────────────────────

    /**
     * Reads a committed 16-bit PCM stereo WAV back into the same shape the renderer produces.
     *
     * `javax.sound.sampled` rather than a hand-rolled parser: the asset is written by
     * `WavFloatArrayWriter`, and reading it with a second implementation of the same author's
     * assumptions would verify nothing about the file a browser is going to play.
     */
    private fun readWavStereo(file: File): PatSoundDocs.Stereo {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            require(format.encoding == AudioFormat.Encoding.PCM_SIGNED) { "$file: not PCM_SIGNED" }
            require(format.sampleSizeInBits == 16) { "$file: ${format.sampleSizeInBits}-bit, expected 16" }
            require(format.channels == 2) { "$file: ${format.channels} channels, expected 2" }
            require(format.sampleRate.toInt() == PatSoundDocs.SR) { "$file: ${format.sampleRate} Hz" }

            val bytes = stream.readAllBytes()
            val frames = bytes.size / 4
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            val big = format.isBigEndian
            for (i in 0 until frames) {
                left[i] = pcm16(bytes, i * 4, big)
                right[i] = pcm16(bytes, i * 4 + 2, big)
            }
            return PatSoundDocs.Stereo(left, right)
        }
    }

    private fun pcm16(bytes: ByteArray, at: Int, bigEndian: Boolean): Float {
        val lo = bytes[if (bigEndian) at + 1 else at].toInt() and 0xFF
        val hi = bytes[if (bigEndian) at else at + 1].toInt()
        return (((hi shl 8) or lo).toShort()).toFloat() / 32768f
    }

    companion object {
        /**
         * How far after a fence the voice label may sit and still count as "beside" it.
         *
         * Generous enough for the runner to have emitted its rendered code block in between, tight
         * enough that a label in the next section does not satisfy the wrong fence. The fences on
         * the page today put the label 200-400 characters after the `audio=` attribute.
         */
        const val NEARBY_CHARS = 1200
    }
}

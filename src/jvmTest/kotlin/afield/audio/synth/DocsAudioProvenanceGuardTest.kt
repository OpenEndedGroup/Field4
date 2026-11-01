package afield.audio.synth

import afield.audio.synth.DocsAudioProvenance.Origin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every playable audio artifact the documentation publishes, held to a stated provenance.
 *
 * `SoundProvenanceGuardTest` covers the three clips on `sound.md`. This one covers **all of them**,
 * because the claim `sound.md` inherited from [DirtRender]'s KDoc — that every audio figure in the
 * docs is [patVoice] — turned out to be false, and the only way to keep a corpus-wide claim honest
 * is to measure the corpus.
 *
 * Three things are asserted, and the middle one is the one that would have caught `56b124131`:
 *
 *  1. **Nothing ships unclassified.** The fence count is pinned, so a new audio asset fails the
 *     build until somebody says which family it belongs to.
 *  2. **Every committed asset still matches what its producer renders today.** Measured by
 *     re-rendering through the real `renderWav` contract and correlating.
 *  3. **The allowlist of un-re-derivable audio is pinned exactly**, keys and reasons, so it cannot
 *     grow quietly into a place to hide problems.
 *
 * Plus a negative control, because a guard that has never failed is not known to work.
 */
class DocsAudioProvenanceGuardTest {

    private val scratch: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "docs-audio-provenance-test").apply { mkdirs() }
    }

    /**
     * The corpus as it stands, pinned by count and by family.
     *
     * These numbers are the audit's headline and the reason this file exists. Thirty-three of the
     * seventy-three never touch [patVoice] — eighteen a DoughDSP signal, nine real Dirt-Samples,
     * one mixed, three a Faust program and two the dough wasm engine. That minority refuted
     * the "every audio figure in the documentation is `patVoice`" claim this file was written to
     * check.
     *
     * The *majority* moved, though, and the direction is worth recording rather than just
     * re-typing: it was 5 of 15 stand-in until `pattern_language.md` grew a mini-notation card per
     * form of the notation, twenty-six clips of `patVoice` in one commit. The stand-in was then
     * 40 of 54, then 40 of 74, then 42 of 76; `sound.md`'s section had to stop arguing that the stand-in is
     * *not* what you hear on most of the site. On 2026-10-09 those twenty-six cards moved to the pinned
     * Dirt-Samples, and the stand-in fell to 16 of 76. A count pinned here and stated in prose there is exactly
     * the pair that drifts, which is why the assertion names the page.
     */
    @Test
    fun `the published audio corpus is exactly what we think it is`() {
        val published = DocsAudioProvenance.scan()
        assertEquals(
            EXPECTED_FENCE_COUNT, published.size,
            "the number of published audio fences changed. Every one needs a stated provenance, so " +
                "update EXPECTED_FENCE_COUNT and the family counts below deliberately:\n" +
                published.joinToString("\n") { "  ${it.page} -> ${it.assetRef} (${it.renderer})" },
        )

        val byOrigin: Map<Origin?, Int> = published.groupingBy { DocsAudioProvenance.originOf(it) }.eachCount()
        val expected: Map<Origin?, Int> = mapOf(
            Origin.STAND_IN_SYNTH to 16,
            Origin.DIRT_SAMPLES to 37,
            Origin.MIXED_SAMPLES_AND_STAND_IN to 1,
            Origin.DOUGH_DSP to 18,
            Origin.FAUST to 3,
            Origin.DOUGH_WASM to 2,
            Origin.STEAM_AUDIO_HRTF to 1,
        )
        assertEquals(
            expected,
            byOrigin,
            "the mix of audio families changed; `sound.md` states these counts in prose",
        )
    }

    @Test
    fun `every published audio fence names a producer we can resolve`() {
        val unclassified = DocsAudioProvenance.scan().filter { DocsAudioProvenance.originOf(it) == null }
        assertTrue(
            unclassified.isEmpty(),
            "audio with no stated provenance: ${unclassified.map { "${it.page} -> ${it.assetRef}" }}. " +
                "Add its renderer to DocsAudioProvenance.ORIGIN_BY_RENDERER.",
        )
    }

    /**
     * The `56b124131` guard, generalised: a published clip must still be what its code renders.
     *
     * This re-renders through the same `renderWav(name, path)` contract the docs runner invokes, so
     * it exercises the production path rather than a re-implementation of it.
     */
    @Test
    fun `every committed audio asset still matches what its producer renders`() {
        val findings = DocsAudioProvenance.scan().map { DocsAudioProvenance.audit(it, scratch) }
        findings.forEach { println("  " + it.line()) }

        val broken = findings.filter { it.skipped == null && (it.failure != null || !it.reproduces) }
        assertTrue(
            broken.isEmpty(),
            "published audio the code no longer produces — the shape of 56b124131:\n" +
                broken.joinToString("\n") { "  - ${it.line()}" },
        )
    }

    /** Real audio, not a valid-but-empty file. */
    @Test
    fun `every committed audio asset is audible`() {
        val findings = DocsAudioProvenance.scan().map { DocsAudioProvenance.audit(it, scratch) }
        findings.forEach {
            assertTrue(it.frames > 0, "${it.published.id}: no frames")
            assertTrue(it.peak > 0.02, "${it.published.id}: effectively silent (peak ${it.peak})")
            assertTrue(it.rms > 0.002, "${it.published.id}: RMS too low to be sound (${it.rms})")
            assertTrue(it.rms < it.peak, "${it.published.id}: RMS >= peak, which is impossible")
        }
    }

    /**
     * The allowlist is pinned by key *and* reason.
     *
     * A reason is the whole value of an allowlist entry — an entry that says only "skip this" is
     * indistinguishable from a bug somebody got tired of.
     */
    @Test
    fun `the un-re-derivable allowlist has not grown`() {
        assertEquals(
            setOf("assets/banner.mp4"),
            DocsAudioProvenance.UNDERIVABLE.keys,
            "the set of audio we cannot re-derive changed. Each entry is a finding, not a pass.",
        )
        DocsAudioProvenance.UNDERIVABLE.forEach { (id, reason) ->
            assertTrue(reason.length > 40, "$id: an allowlist entry needs a real reason, got '$reason'")
        }
        assertTrue(
            File(DocsAudioProvenance.DOCS_DIR, "assets/banner.mp4").isFile,
            "banner.mp4 is allowlisted but no longer present — drop it from UNDERIVABLE",
        )
    }

    /**
     * The source-level half of the headline claim.
     *
     * The measurement shows the six DoughDSP clips are not reproducible by `PatSoundDocs`; this
     * pins *why* — the producer never reaches the stand-in voice at all. Source-level rather than
     * measured, and labelled as such, because "which function was called" is a property of the
     * code and not of the waveform.
     */
    @Test
    fun `the DoughDSP producer never reaches the stand-in voice`() {
        val source = File("src/jvmMain/kotlin/afield/audio/synth/DoughDspSoundDocs.kt")
        assertTrue(source.isFile, "${source.path} is missing")
        val text = source.readText()
        listOf("patVoice", "patRenderStereo", "patDrumHz").forEach { symbol ->
            assertTrue(
                symbol !in text,
                "${source.path} now references '$symbol'. Six published clips are classified " +
                    "DOUGH_DSP on the basis that this producer is not the stand-in synth; " +
                    "re-check that classification.",
            )
        }
    }

    /**
     * The guard has to be able to fail. Point a fence at the wrong asset and it must be caught —
     * otherwise the reproducibility test above is a test that nothing has happened.
     */
    @Test
    fun `an asset swapped for a different clip is detected`() {
        val all = DocsAudioProvenance.scan()
        val victim = all.first { it.assetRef.startsWith("assets/dough_dsp_sound/") }
        val impostor = all.first { it.assetRef.startsWith("assets/pattern_language/") }

        // The committed bytes of a different clip, measured against the victim's producer.
        val forged = DocsAudioProvenance.readWav(impostor.file)
        val genuine = DocsAudioProvenance.readWav(victim.file)
        val similarity = SoundRenderPathDocs.waveformSimilarity(forged, genuine)
        assertTrue(
            similarity < DocsAudioProvenance.REPRODUCIBILITY_FLOOR,
            "swapping ${impostor.assetRef} in for ${victim.assetRef} scores $similarity, which the " +
                "reproducibility floor (${DocsAudioProvenance.REPRODUCIBILITY_FLOOR}) would accept — " +
                "the corpus guard cannot detect a substituted asset",
        )
        println(
            "negative control: ${impostor.assetRef} substituted for ${victim.assetRef} " +
                "scores ${"%.4f".format(similarity)}, rejected by the ${DocsAudioProvenance.REPRODUCIBILITY_FLOOR} floor"
        )
    }

    companion object {
        /**
         * How many `audio="…"` fences the docs publish.
         *
         * Seventy-three across fourteen pages, including twenty-nine on `pattern_language.md`
         * and nine on `subharmonic_sound.md`. Pinned so a new clip cannot ship without a stated
         * provenance.
         *
         * It was twelve until `sound_paths.md` published its one-buffer-three-paths comparison,
         * which is also where [Origin.FAUST] came from: a Faust program compiled at runtime is a
         * fourth family, and before that page existed no published clip had ever been one. Then
         * fifteen until the mini-notation cards, which added twenty-six in one commit and are the
         * reason this number is now large enough that a hand-count is no longer a check. Two more
         * are `faust.md`'s, which made a polyphonic instrument audible on a page that had described
         * one across ten subsections without ever letting the reader hear it (see
         * [FaustPolySoundDocs]). The six newest are the two chain pages, and they added no new
         * family: both drive [patVoice], one through [patRenderStereo] and one through a
         * [PatSynthBank] rendered block by block.
         *
         * Two are [PatSpatialSoundDocs]', and they brought [Origin.DOUGH_WASM] with them. They are
         * the only pair in the corpus that is deliberately **half a path**: they publish the mono
         * source `pat-spatial` hands Steam Audio. (The binaural half was thought realtime-only when
         * they were written; it is not, and has its own clip since 2026-10-09 — see below.)
         *
         * The three newest are `chain_lambda_sound.md`'s, and they are [Origin.STAND_IN_SYNTH]
         * again: [LambdaSoundDocs] drives [patRenderStereo] the way [CaSoundDocs] does, with an
         * interaction-net reduction upstream of the step mask instead of a cellular automaton. Two
         * of the three are the *same* term under two schedulers, which is the page's whole point,
         * so they are necessarily the same length and the same level — [LambdaSoundDocs]'s own
         * FIELD_TEST correlates them at 0.375 rather than leaving "they sound different" to a
         * caption.
         *
         * The two after those are `chain_fn_sound_roundtrip.md`'s, and they are [Origin.DOUGH_DSP]:
         * [FnSoundDocs] plays one `Functions.Fn` as the amplitude and then as the frequency of a
         * `SineOsc`, through `FnSignal`, unnormalised so the analysed file and the published one are
         * the same bytes — which its FIELD_TEST checks.
         *
         * The two newest are `chain_drawn_score.md`'s, and they are [Origin.STAND_IN_SYNTH]:
         * [DrawnScoreDocs] reads a score out of the document's drawing with `notation.space`, turns
         * its events into notes with the loop the page shows (once as drawn, once snapped to a
         * pentatonic scale), and plays them through [patTimed] and [patRenderStereo]. Its FIELD_TEST
         * finds every strike and every pitch in both renders and holds them against the notes.
         *
         * Two more are `chain_drawn_morph.md`'s (2026-10-02), [Origin.STAND_IN_SYNTH]:
         * [DrawnMorphDocs] plays the size of a point cloud morphing between two drawn strokes, paired
         * by `matchPoints`, as pitch through [patTimed] and [patRenderStereo].
         *
         * The four newest are `boolean_network_music_examples.md`'s, and they are
         * [Origin.DIRT_SAMPLES]: [BooleanNetworkSoundDocs] plays each fence's note stream through
         * the pinned Dirt-Samples subset with no fallback. In the same change (2026-10-01)
         * `sound_paths.md`'s dry comparison clip moved from [Origin.STAND_IN_SYNTH] to
         * [Origin.DIRT_SAMPLES], because its input became real one-shots; the total did not move
         * for that one, the split did.
         *
         * `chain_ca_sound.md`'s three moved the same way (2026-10-02): [CaSoundDocs] now plays its
         * rows as `click`, `hh` and pitched `arpy` one-shots from the pinned subset.
         *
         * `subharmonic_sound.md` added nine (2026-10-03), all [Origin.DOUGH_DSP]: pitch trackers
         * from `Subharmonic.kt` rendered by [SubharmonicSoundDocs] in the same Kotlin signal graph,
         * on a saw bassline, pinned Dirt-Samples one-shots and a committed public-domain film clip.
         *
         * `chain_petri_sparks.md` added one (2026-10-08), [Origin.DIRT_SAMPLES]: [PetriSparksDocs]
         * plays a Petri net's `emits()` through the pinned subset with no fallback.
         *
         * `orca.md` added two (2026-10-09), [Origin.STAND_IN_SYNTH]: [OrcaSoundDocs] runs ORCA's own
         * operators in [OrcaHeadless] and voices each note through a [PatSynthBank] of [patVoice]
         * voices, not samples.
         *
         * `pat_spatial.md` added one (2026-10-09), [Origin.STEAM_AUDIO_HRTF]:
         * [PatSpatialHeadTrackedDocs] renders `HeadTrackedPatternDemo`'s four movements binaurally,
         * re-rendered here only where `libphonon` loads.
         *
         * `petri_nets.md` added one (2026-10-31), [Origin.DIRT_SAMPLES]: [PetriSoundDocs] plays the
         * page's two-place loop's `emits()` through the pinned subset with `strict = true`.
         */
        const val EXPECTED_FENCE_COUNT = 78
    }
}

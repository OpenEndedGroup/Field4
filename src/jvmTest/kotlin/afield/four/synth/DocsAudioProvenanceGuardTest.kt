package afield.four.synth

import afield.four.synth.DocsAudioProvenance.Origin
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
     * These numbers are the audit's headline and the reason this file exists. Fourteen of the
     * fifty-four never touch [patVoice] — seven a DoughDSP primitive, one real Dirt-Samples, one
     * mixed, three a Faust program, two the dough wasm engine — and that minority is what refuted
     * the "every audio figure in the documentation is `patVoice`" claim this file was written to
     * check.
     *
     * The *majority* moved, though, and the direction is worth recording rather than just
     * re-typing: it was 5 of 15 stand-in until `pattern_language.md` grew a mini-notation card per
     * form of the notation, twenty-six clips of `patVoice` in one commit. So the stand-in is now
     * 40 of 54, and `sound.md`'s section on this had to stop arguing that the stand-in is *not*
     * what you hear on most of the site. A count pinned here and stated in prose there is exactly
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
            Origin.STAND_IN_SYNTH to 40,
            Origin.DIRT_SAMPLES to 1,
            Origin.MIXED_SAMPLES_AND_STAND_IN to 1,
            Origin.DOUGH_DSP to 9,
            Origin.FAUST to 3,
            Origin.DOUGH_WASM to 2,
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

        val broken = findings.filter { it.failure != null || !it.reproduces }
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
        val source = File("src/jvmMain/kotlin/afield/four/synth/DoughDspSoundDocs.kt")
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
         * Fifty-four, across nine pages: **twenty-nine** on `pattern_language.md` (three scene
         * clips plus one per mini-notation card), six on `dough_dsp_sound.md`, three each on
         * `sound.md`, `sound_paths.md`, `chain_ca_sound.md`, `chain_pattern_particles.md` and
         * `chain_lambda_sound.md`, and two each on `faust.md` and `pat_spatial.md`. Pinned so a new
         * clip cannot ship without a stated provenance.
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
         * also the only pair in the corpus that is deliberately **half a path**: they publish the
         * mono source `pat-spatial` hands Steam Audio, because the spatialised half is realtime-only
         * and needs a binary this repository does not carry, so this very audit could not re-render
         * it. Worth stating next to the count: the corpus is now bounded by what can be re-derived,
         * and one of Field's render paths falls outside that boundary by design.
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
         */
        const val EXPECTED_FENCE_COUNT = 56
    }
}

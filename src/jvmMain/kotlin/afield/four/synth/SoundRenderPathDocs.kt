package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.sound.render.path
runner: jvm-main
entrypoint: afield.four.synth.SoundRenderPathDocs
tags: [smoke, synth, pattern, tidal, audio, wav, dirt, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "sound render path figures rendered"
*/

/**
 * The figures behind `public/docs/sound.md`, and the reason that page can be trusted.
 *
 * Field has two offline voices for the same **pattern** and they sound nothing alike.
 * [patRenderStereo] plays it through [patVoice] — a sine for `bd`, filtered noise for `sd` and
 * `hh` — and [patRenderStereoDirt] plays it through the genuine Tidal Dirt-Samples library.
 *
 * This used to continue "and every audio figure Field has published so far is the first one, and
 * nothing on any page said so". Both halves went stale within days: [DocsAudioProvenance] measured
 * the corpus and most of it is [DoughDspSoundDocs], and `public/docs/sound.md` now says so at
 * length. Two is also the count of *pattern* voices and never was the count of ways to make a sound
 * — [SoundPathMap] has that, and there are six.
 *
 * So provenance is not a comment here, it is a field. Each [Figure] *declares* its [Voice], and
 * two mechanisms hold the declaration to it:
 *
 *  * [renderWav] refuses to write a [Voice.DIRT_SAMPLES] figure out of the fallback. On a machine
 *    with no library the docs build fails with an explanation instead of publishing the
 *    stand-in under a caption that says "real samples".
 *  * `SoundProvenanceGuardTest` decodes the *committed* `.wav`, correlates it against a fresh
 *    stand-in render of the same pattern, and fails if the answer disagrees with the declaration.
 *    That check needs no sample library, which is exactly why it is the useful one: a build
 *    machine with no Dirt-Samples can still catch a "real drums" figure that has silently become
 *    bursts of white noise.
 *
 * Add a figure by appending one [Figure] to [FIGURES] and putting its [Voice.pageLabel] beside the
 * fence on the page. The guard enforces both directions, so half of that will fail the build.
 */
object SoundRenderPathDocs {

    /** Which voice a figure is made of — declared by the figure, checked against the audio. */
    enum class Voice(
        /**
         * The exact sentence the page must carry next to this figure.
         *
         * Verbatim, so the claim in the prose and the claim in the code cannot drift apart
         * silently. Prose around it is free; this string has to be present.
         */
        val pageLabel: String
    ) {
        // Line comments, not KDoc: an enum entry that takes a constructor argument is not a form
        // KDocExtractor.parseDeclaration can read, so a KDoc block here slides onto whatever parses
        // next. A bare entry is fine; `STAND_IN("…")` is not. See KDocAttachmentGuard.

        // patVoice: no assets, works on a clean checkout, sounds like bursts of white noise.
        STAND_IN("**Voice: the stand-in synth (`patVoice`).** No sample library was used."),

        // patRenderStereoDirt against a real Dirt-Samples install, every token matched.
        DIRT_SAMPLES("**Voice: real Dirt-Samples one-shots, every token matched.**"),

        // Real samples where the library had them, patVoice per-event where it did not.
        MIXED("**Voice: real Dirt-Samples where the library had the token, the stand-in synth per event where it did not.**"),

        ;

        /** True when this figure claims any of its sound came out of the sample library. */
        val usesSamples: Boolean get() = this != STAND_IN
    }

    /**
     * One published audio figure: a pattern, a tempo, and the voice it claims to be made of.
     *
     * [pattern] is a factory rather than a value because the guard renders each figure several
     * times and a `PatPattern` is a query function — sharing one across renders would be fine
     * today and a subtle bug the first time one of them caches.
     */
    class Figure(
        val name: String,
        val title: String,
        val voice: Voice,
        val cycles: Int,
        val cps: Double,
        val pattern: () -> PatPattern<String>,
        /** The synth voice used, either as the figure itself or as the per-event fallback. */
        val synth: PatVoiceSpec = patVoice(),
        /**
         * The tokens this figure expects the library *not* to have, and therefore to voice through
         * the synth fallback.
         *
         * Declared rather than discovered, because the provenance guard has to work on a machine
         * with no sample library: it splits the pattern into the part that should have come from
         * samples and the part that should not, and measures the committed `.wav` against each
         * half separately. Empty for a [Voice.STAND_IN] or a fully-matched [Voice.DIRT_SAMPLES]
         * figure; non-empty is what makes a figure [Voice.MIXED].
         */
        val expectedUnmatched: Set<String> = emptySet(),
    ) {
        init {
            require((voice == Voice.MIXED) == expectedUnmatched.isNotEmpty()) {
                "figure '$name': ${Voice.MIXED} means exactly 'some tokens fall back', so expectedUnmatched " +
                    "must be non-empty for it and empty for every other voice (got $expectedUnmatched for $voice)"
            }
        }
    }

    private const val CPS = 2.0

    /** The beat every A/B figure on the page uses, so the two voices are compared on one pattern. */
    fun theBeat(): PatPattern<String> = pat("bd ~ sd ~, hh*8") with gain(0.9)

    val FIGURES: List<Figure> = listOf(

        // 1 ── the default voice, named for what it is.
        Figure(
            name = "beat-stand-in",
            title = "pat(\"bd ~ sd ~, hh*8\") through patVoice",
            voice = Voice.STAND_IN,
            cycles = 4,
            cps = CPS,
            pattern = { theBeat() },
        ),

        // 2 ── the identical pattern, the other voice. The whole point of the page in one A/B.
        Figure(
            name = "beat-dirt",
            title = "the identical pattern through the real Dirt samples",
            voice = Voice.DIRT_SAMPLES,
            cycles = 4,
            cps = CPS,
            pattern = { theBeat() },
        ),

        // 3 ── the per-event fallback, made audible. `zzzznotadrum` is in no Dirt install, so the
        // library is present and used for `bd`/`hh` while that one token gets the synth voice.
        Figure(
            name = "per-event-fallback",
            title = "one token the library has never heard of",
            voice = Voice.MIXED,
            cycles = 4,
            cps = CPS,
            pattern = { pat("bd zzzznotadrum bd hh") with gain(0.9) },
            synth = patVoice(wave = PatWave.SQUARE, baseHz = 330.0, decay = 0.05, sustain = 0.3),
            expectedUnmatched = setOf("zzzznotadrum"),
        ),
    )

    private val byName: Map<String, Figure> = FIGURES.associateBy { it.name }

    /** Looks a figure up by name, with a message that lists the alternatives. */
    fun figure(name: String): Figure =
        byName[name] ?: error("unknown sound-render-path figure '$name'; known: ${byName.keys.sorted()}")

    /** A rendered figure: the audio, and what it turned out to be made of. */
    class Rendered(
        val figure: Figure,
        val audio: PatSoundDocs.Stereo,
        val result: DirtRender.DirtRenderResult?,
    ) {
        /** True when real samples were mixed in — always false for a [Voice.STAND_IN] figure. */
        val usedSamples: Boolean get() = result?.usedSamples == true

        /** One line naming the voice, for a box to print or a build log to carry. */
        fun describe(): String = result?.describe() ?: "synth voice (patVoice; this figure asks for it)"
    }

    /**
     * Renders [name] through the voice it declares.
     *
     * A [Voice.DIRT_SAMPLES] figure is an error when no library can be found: the caller asked for
     * real samples and cannot have them, and the one thing this file exists to prevent is silently
     * substituting the other voice under a caption that says otherwise. The library is
     * [DirtRender.requirePinnedLibrary]: `./gradlew fetchDocsDirtSamples` provides it.
     */
    fun render(name: String): Rendered {
        val figure = figure(name)
        return when (figure.voice) {
            Voice.STAND_IN -> Rendered(
                figure,
                patRenderStereo(figure.pattern(), figure.cycles, figure.cps, figure.synth),
                null,
            )

            Voice.DIRT_SAMPLES, Voice.MIXED -> {
                // The pinned subset, not whichever install dirtSampleRoot finds first: the
                // provenance audit compares bytes, so every machine must render from the same ones.
                // Throws with the fetch command when it is missing; rendering through the stand-in
                // would publish a figure whose caption is a lie.
                val library = DirtRender.requirePinnedLibrary()
                val result = patRenderStereoDirt(
                    figure.pattern(), figure.cycles, figure.cps, library = library, voice = figure.synth,
                )
                check(result.usedSamples) { "figure '$name': a library was found and still nothing came from it" }
                check(result.matched.isNotEmpty()) {
                    "figure '$name': the library matched no token at all (${result.unmatched}), so this " +
                        "render is entirely the stand-in wearing a sample-library caption"
                }
                Rendered(figure, result.audio, result)
            }
        }
    }

    /**
     * The **stand-in** render of a figure's pattern, whatever voice the figure declares.
     *
     * The reference the provenance guard correlates a committed `.wav` against. Available on every
     * machine, including one with no sample library, which is what makes the guard portable.
     *
     * [tokens] restricts the render to the events whose value is in the set — the two halves of a
     * [Voice.MIXED] figure, rendered separately. Null means the whole pattern.
     */
    fun standInReference(name: String, tokens: Set<String>? = null): PatSoundDocs.Stereo {
        val figure = figure(name)
        val source = figure.pattern()
        val pattern = if (tokens == null) source else {
            PatPattern { arc -> source.events(arc).filter { it.value.trim() in tokens } }
        }
        return patRenderStereo(pattern, figure.cycles, figure.cps, figure.synth)
    }

    /** The stand-in render of just the events the library is expected to play from samples. */
    fun sampleBackedReference(name: String): PatSoundDocs.Stereo {
        val figure = figure(name)
        val unmatched = figure.expectedUnmatched
        return standInReference(name, tokensOf(figure).filterNot { it in unmatched }.toSet())
    }

    /** The stand-in render of just the events expected to fall back to the synth voice. */
    fun fallbackReference(name: String): PatSoundDocs.Stereo =
        standInReference(name, figure(name).expectedUnmatched)

    /** Every distinct atom the figure's pattern schedules over its own length. */
    fun tokensOf(figure: Figure): Set<String> =
        figure.pattern()
            .events(PatArc(PatQ.ZERO, PatQ.of(figure.cycles)))
            .map { it.value.trim() }
            .toSet()

    /**
     * Offline-renders figure [name] to a stereo 16-bit PCM WAV at [outPath].
     *
     * The `renderWav(name, path)` contract the docs runner's `audio="…"` fence calls over the
     * backdoor (`public/docs_hooks.py`, `ensure_audio`).
     */
    fun renderWav(name: String, outPath: String) {
        val rendered = render(name)
        val audio = rendered.audio
        require(audio.frames > 0) { "figure '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    // ── provenance measurement ─────────────────────────────────────────────────────────────

    /**
     * How much two renders of the same pattern are the same *waveform*: normalised zero-lag
     * cross-correlation of the two channels, worst channel wins.
     *
     * Scale-invariant, so the writer's peak normalisation and 16-bit quantisation do not move it,
     * and it reads waveform identity rather than rhythm — two renders of one pattern put their
     * onsets in exactly the same places whichever voice played them, so anything that keys off
     * timing cannot tell them apart. This can. Returns 0 against a silent reference.
     */
    fun waveformSimilarity(a: PatSoundDocs.Stereo, b: PatSoundDocs.Stereo): Double {
        val n = minOf(a.frames, b.frames)
        if (n == 0) return 0.0
        val left = PatSoundDocs.correlation(a.left.copyOf(n), b.left.copyOf(n))
        val right = PatSoundDocs.correlation(a.right.copyOf(n), b.right.copyOf(n))
        return max(abs(left), abs(right))
    }

    /**
     * What a rendered figure is actually made of, measured against three stand-in references.
     *
     * All three are computable with no sample library anywhere, which is the entire trick: a build
     * machine that *cannot* render the real samples can still tell whether a committed figure was
     * rendered from them.
     */
    class ProvenanceMeasurement(
        val figure: Figure,
        /** Against the stand-in render of the whole pattern. ~1 means "this IS the stand-in". */
        val toFullStandIn: Double,
        /** Against the stand-in render of only the events that should have come from samples. */
        val toSampleBacked: Double,
        /** Against the stand-in render of only the events that should have fallen back. */
        val toFallback: Double,
    ) {
        override fun toString(): String =
            "full=${"%.4f".format(toFullStandIn)} sampleBacked=${"%.4f".format(toSampleBacked)} " +
                "fallback=${"%.4f".format(toFallback)}"

        /**
         * The ways this audio contradicts the voice its figure declares — empty when it does not.
         *
         * A list rather than a throw so both the FIELD_TEST and `SoundProvenanceGuardTest` can
         * report every problem in one run instead of one per rebuild.
         */
        fun disagreements(): List<String> = buildList {
            when (figure.voice) {
                Voice.STAND_IN ->
                    if (toFullStandIn < STAND_IN_SIMILARITY_FLOOR) {
                        add(
                            "declares ${figure.voice} but the audio is not the stand-in render " +
                                "(similarity $toFullStandIn < $STAND_IN_SIMILARITY_FLOOR)"
                        )
                    }

                Voice.DIRT_SAMPLES, Voice.MIXED -> {
                    // The load-bearing one: the events that claim to be samples must not be the
                    // synth. This holds whatever fraction of the figure fell back, which is why it
                    // is measured against the sample-backed half rather than the whole file.
                    if (toSampleBacked > SAMPLE_BACKED_SIMILARITY_CEILING) {
                        add(
                            "declares ${figure.voice} but its sample-backed events sound exactly like " +
                                "the stand-in synth (similarity $toSampleBacked > " +
                                "$SAMPLE_BACKED_SIMILARITY_CEILING) — this figure has silently become " +
                                "bursts of white noise"
                        )
                    }
                    if (toFullStandIn >= STAND_IN_SIMILARITY_FLOOR) {
                        add(
                            "declares ${figure.voice} but is the whole-render fallback " +
                                "(similarity to the plain stand-in render $toFullStandIn)"
                        )
                    }
                    if (figure.voice == Voice.MIXED && toFallback < FALLBACK_SIMILARITY_FLOOR) {
                        add(
                            "declares ${figure.voice}, so ${figure.expectedUnmatched} should be audible " +
                                "through the synth voice, and it is not (similarity $toFallback < " +
                                "$FALLBACK_SIMILARITY_FLOOR)"
                        )
                    }
                }
            }
        }
    }

    /** Measures [audio] — a fresh render or a decoded committed `.wav` — against figure [name]. */
    fun measureProvenance(name: String, audio: PatSoundDocs.Stereo) = ProvenanceMeasurement(
        figure = figure(name),
        toFullStandIn = waveformSimilarity(audio, standInReference(name)),
        toSampleBacked = waveformSimilarity(audio, sampleBackedReference(name)),
        toFallback = waveformSimilarity(audio, fallbackReference(name)),
    )

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    /**
     * Renders every figure to a temp dir and asserts the audio is real, and really the declared
     * voice. Writes NO committed assets; pass an output dir as `args[0]` to regenerate those.
     *
     * `DIRT_SAMPLES` figures need the library, so on a machine without it this reports them as
     * skipped rather than failing — a build box has no 400MB third-party download and should not
     * pretend otherwise. The parts that run everywhere still run.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("SOUND_RENDER_PATH_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "sound-render-path").path
        )
        outDir.mkdirs()

        val root = DirtRender.pinnedSampleRoot()
        println("Dirt sample root: ${root ?: "(none — DIRT_SAMPLES figures will be skipped; ${DirtRender.PINNED_REMEDY})"}")

        var rendered = 0
        var skipped = 0
        FIGURES.forEach { figure ->
            if (figure.voice == Voice.DIRT_SAMPLES && root == null) {
                println("figure ${figure.name}: SKIPPED (declares ${figure.voice}, no library on this machine)")
                skipped++
                return@forEach
            }
            val result = render(figure.name)
            val audio = result.audio
            check(audio.frames > 0) { "${figure.name}: rendered no samples" }
            check(audio.left.size == audio.right.size) { "${figure.name}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${figure.name}: non-finite samples"
            }
            val expectedFrames = (figure.cycles / figure.cps * PatSoundDocs.SR).toInt()
            // A sample one-shot may ring past the last cycle in principle; the renderer truncates
            // to the scheduled length, so the frame count is exact either way.
            check(abs(audio.frames - expectedFrames) <= PatSoundDocs.SR / 10) {
                "${figure.name}: expected ~$expectedFrames frames, got ${audio.frames}"
            }
            val peak = PatSoundDocs.peak(audio.mono)
            val rms = PatSoundDocs.rms(audio.mono)
            check(peak > 0.02) { "${figure.name}: effectively silent (peak=$peak)" }
            check(peak <= 4.0) { "${figure.name}: runaway level (peak=$peak)" }
            check(rms > 0.002) { "${figure.name}: RMS too low to be music (rms=$rms)" }
            check(rms < peak) { "${figure.name}: RMS >= peak, which is impossible" }

            // The claim the page makes, measured rather than asserted by comment.
            val measured = measureProvenance(figure.name, audio)
            println(
                "figure ${figure.name}: ${"%.2f".format(audio.frames.toDouble() / PatSoundDocs.SR)}s, " +
                    "peak=${"%.3f".format(peak)}, rms=${"%.4f".format(rms)}, " +
                    "usedSamples=${result.usedSamples}, declares ${figure.voice}, $measured"
            )
            println("  ${result.describe()}")
            if (figure.voice == Voice.MIXED) {
                val dirt = requireNotNull(result.result) { "${figure.name}: a MIXED figure must be a Dirt render" }
                check(dirt.unmatched == figure.expectedUnmatched) {
                    "${figure.name}: declares expectedUnmatched=${figure.expectedUnmatched}, the library " +
                        "actually failed on ${dirt.unmatched}"
                }
            }
            val disagreements = measured.disagreements()
            check(disagreements.isEmpty()) { "${figure.name}: ${disagreements.joinToString("; ")}" }

            val file = File(outDir, "sound-${figure.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
            val bytes = file.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${figure.name}: missing RIFF" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${figure.name}: missing WAVE" }
            check(bytes.size == 44 + audio.frames * 4) { "${figure.name}: WAV data size mismatch" }
            println("  wrote ${file.absolutePath} (${bytes.size} bytes)")
            rendered++
        }

        println("sound render path figures rendered to ${outDir.absolutePath} ($rendered rendered, $skipped skipped)")
    }

    // ── the ratchets ───────────────────────────────────────────────────────────────────────
    //
    // Every one of these is a floor near a measured number, not a guess. The measurements are in
    // the KDoc so a later change can see how much room it just used up. Re-measure with
    // `run_field_tests.py --id synth.sound.render.path`, which prints all three per figure.

    /**
     * A figure that claims to be the stand-in must essentially *be* the stand-in.
     *
     * Not 1.0, because the committed asset is peak-normalised 16-bit PCM — a quantised, rescaled
     * copy. Measured 1.0000 (to four places) for `beat-stand-in` both as a fresh render and
     * decoded back off disk; the floor sits well below that and far above anything a different
     * voice reaches.
     */
    const val STAND_IN_SIMILARITY_FLOOR = 0.99

    /**
     * The events a figure says came from samples must not sound like the synth.
     *
     * Never anywhere near zero, and the reason is worth knowing before anyone tightens this: a
     * bass-drum sample **is** largely a decaying low sine, and the stand-in's `bd` is a 62 Hz sine,
     * so the two genuinely correlate. Measured 0.0565 for `beat-dirt`, whose sixteenth-note hats
     * dilute the kick, and 0.1588 for `per-event-fallback`, which is a quarter kicks. So this
     * ceiling is set from the *worse* case with roughly a factor of two of headroom, and it is
     * still ~6x below the ~1.0 that a figure reverting to the stand-in produces — which is
     * the only failure it needs to catch.
     */
    const val SAMPLE_BACKED_SIMILARITY_CEILING = 0.35

    /**
     * A [Voice.MIXED] figure's fallback events must actually be audible in it.
     *
     * The per-event fallback measured rather than asserted: the synth-voiced events are mixed in
     * verbatim, so the correlation is roughly the square root of their share of the energy.
     * Measured 0.8074 for `per-event-fallback`; 0.5 leaves room to rebalance the mix without
     * leaving room for the fallback to vanish.
     */
    const val FALLBACK_SIMILARITY_FLOOR = 0.5
}

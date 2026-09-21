@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs

/*
FIELD_TEST
id: synth.faust.poly.sound
runner: jvm-main
entrypoint: afield.four.synth.FaustPolySoundDocs
tags: [smoke, synth, faust, polyphony, pattern, audio, wav, docs, wasm, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "faust poly sound docs rendered"
*/

/**
 * The two published clips that make polyphony audible: one chord progression, eight voices and one.
 *
 * `faust.md` describes a polyphonic instrument in prose, onset tables in samples and peak counts in
 * Hz; these two clips are the part of it a reader can listen to. The path renders offline with no
 * device, so [DocsAudioProvenance] re-renders both on every build and holds them against the
 * numbers this file asserts.
 *
 * ## Why the two clips differ only in `voices`
 *
 * Polyphony cannot be demonstrated by a single clip. A recording of a chord and a recording of an
 * arpeggio sound different for reasons that have nothing to do with voices, so a reader has no way
 * to tell a voice bank from a well-chosen pattern. What cannot be explained away is **the same
 * notation, the same program and the same gain, rendered at `voices = 8` and at `voices = 1`**: the
 * notes are identical, so everything you can hear between them is voice allocation. At one voice
 * every note after the first steals the only voice there is, and that steal is audible:
 * [FaustVoiceBank.stealReleaseFrames] measures the click it costs.
 *
 * This is the same demonstration the `faust_polyphony` starting point makes in its *three notes at
 * once* box, which is where the shape of it came from. That box displays its two renders as players
 * in its own output; these two fences are how the same comparison reaches the page.
 *
 * ## Why the voice is a single sawtooth
 *
 * The program here is **not** the detuned pair the rest of `faust.md` uses. The starting point's
 * chord box records why: two sawtooths 0.5% apart put a *beating pair* into the band the count is
 * taken in, so four voices reported four peaks and one voice reported two — which muddies the only
 * thing the figure is for. A clip whose stated measurement is a peak count has to be a signal with
 * one partial per note.
 *
 * ## Why a peak in this band can only be a note
 *
 * The smoke test counts spectral peaks between [BAND_LO_HZ] and [BAND_HI_HZ] during the first chord,
 * `[c4,ef4,g4]`. Its fundamentals are 130.81, 155.56 and 196.00 Hz and the lowest second harmonic in
 * the chord is 261.6 Hz, so nothing but a fundamental can appear in that band: counting peaks there
 * counts notes sounding. Eight voices must show three and one voice must show one, and the one-voice
 * render must report steals. Those are the assertions, and `faust.md` is required to carry both
 * captions verbatim, so its prose cannot drift off the audio it publishes.
 */

/**
 * Renders the two `faust.md` polyphony clips, counts the fundamentals in each and writes the WAVs;
 * `main` is the `synth.faust.poly.sound` entrypoint.
 */
object FaustPolySoundDocs {

    /** Sample rate both clips are rendered and written at, in Hz. */
    const val SR = 48_000

    /** Cycles per second, matching the example on [PAGE] exactly — see [progression]. */
    const val CPS = 0.5

    /** How many cycles. Four chords fit in *one*, so this plays the progression twice, in 4s. */
    const val CYCLES = 2

    /** Per-note gain. Three notes at once at full scale would clip, and that would not be the mixer. */
    const val NOTE_GAIN = 0.18

    /** Bottom of the fundamental-only band the peak count is taken in. */
    const val BAND_LO_HZ = 100.0

    /** Top of it: below the first chord's lowest second harmonic, 261.6 Hz. */
    const val BAND_HI_HZ = 230.0

    /**
     * Where the peak count is taken, in seconds, and over how many samples.
     *
     * Both are decided by the first chord rather than chosen: at [CPS] it starts at 0s and its gate
     * falls at 0.45s, and the envelope is `adsr(0.004, 0.09, …)`, so the sustain runs from about
     * 0.1s. The window has to sit inside that — a window that reaches the second chord would count
     * *its* fundamentals too and the number would mean nothing. 8192 samples is 0.17s at [SR],
     * which resolves 5.9 Hz and so separates 130.81, 155.56 and 196.00 Hz comfortably.
     */
    const val MEASURE_AT_SECONDS = 0.15

    /**
     * How many samples the peak count is taken over: 0.17s at [SR], which resolves 5.9 Hz.
     *
     * [MEASURE_AT_SECONDS] is where the window starts and says why it has to end before the second
     * chord starts.
     */
    const val MEASURE_WINDOW = 8192

    /**
     * The instrument. One sawtooth per voice through a resonant lowpass, `freq`/`gate`/`gain` as
     * Faust's own polyphonic convention names them.
     *
     * The `#faust` marker is absent because this string is compiled directly rather than being typed
     * into a box — see [FaustBox] for what that marker is for.
     */
    val SOURCE: String = """
        import("stdfaust.lib");
        cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
        freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
        gain   = hslider("gain", 0.22, 0, 1, 0.001);
        gate   = button("gate");
        env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
        process = os.sawtooth(freq) : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
    """.trimIndent()

    /**
     * The progression, **character for character the one the `faustPoly` example on [PAGE] plays**.
     *
     * Deliberately not a nicer pattern of its own. A figure whose notation differs from the code
     * block above it is a figure the reader has to take on trust; this way the clip is what that
     * example sounds like, and the only thing the second clip changes is `voices`.
     *
     * Four triads in one cycle, in Tidal's octave numbering where `c5` is middle C.
     */
    fun progression(): PatPattern<String> =
        pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]")

    /** The cutoff pattern the example automates, likewise verbatim. */
    fun cutoffPattern(): PatPattern<Double> = pat("900 2600 1400 5200").nums()

    /** One published clip: the same notation at a different voice count. */
    class Figure(
        val name: String,
        val voices: Int,
        /** The caption sentence [PAGE] must carry verbatim beside the fence. */
        val pageLabel: String,
        /** Peaks the band must hold during the first chord — the figure's whole claim. */
        val expectedFundamentals: Int,
        /** True when every note after the first has to steal the only voice there is. */
        val expectSteals: Boolean,
    )

    /** The page these are published on. Its prose is checked against [FIGURES], not trusted. */
    const val PAGE = "public/docs/faust.md"

    /** The two figures, in the order the page presents them. */
    val FIGURES: List<Figure> = listOf(
        Figure(
            name = "poly-chord-eight-voices",
            voices = 8,
            pageLabel = "**Eight voices.** Four triads, one per cycle. Three notes of each chord " +
                "sound together, which is three fundamentals in a band that can hold nothing else.",
            expectedFundamentals = 3,
            expectSteals = false,
        ),
        Figure(
            name = "poly-chord-one-voice",
            voices = 1,
            pageLabel = "**The same notation, one voice.** Identical pattern, program and gain. The " +
                "three notes of a chord arrive on the same frame, so two of them steal the only " +
                "voice there is — and what you hear is the last of the three, with a click where " +
                "the voice turned over.",
            expectedFundamentals = 1,
            expectSteals = true,
        ),
    )

    private val byName = FIGURES.associateBy { it.name }

    /** Looks a figure up by name, with a message that lists the alternatives. */
    fun figure(name: String): Figure =
        byName[name] ?: error("unknown FaustPolySoundDocs figure '$name'; known: ${byName.keys.sorted()}")

    /**
     * [SOURCE] compiled once, and shared by both figures.
     *
     * Both clips are the same program — that is half the claim — so compiling it twice would be two
     * Truffle warm-ups (13–22s each, cold) for one result.
     */
    private val program: FaustProgram by lazy { FaustEngine.compile("faustPolySoundDocs", SOURCE) }

    /**
     * Renders figure [name] offline — no audio device, no window, no GPU.
     *
     * The same [FaustVoiceBank.render] the audio thread calls, in blocks of the same size, so what
     * is published is what would be heard. The cutoff automation goes through the same event
     * timeline as the notes, which is the second half of "a pattern plays it".
     */
    fun render(name: String): FaustPatternRender {
        val f = figure(name)
        val cutoff = program.ui.writable.firstOrNull { it.label.equals("cutoff", ignoreCase = true) }
            ?: error("the docs instrument no longer declares a `cutoff` control: ${program.ui.writable.map { it.label }}")
        return faustPatternRender(
            program = program,
            pattern = progression(),
            cycles = CYCLES,
            cps = CPS,
            voices = f.voices,
            sampleRate = SR,
            gain = NOTE_GAIN,
            automation = faustParamAutomation(cutoffPattern(), cutoff, CYCLES, CPS, SR),
        )
    }

    /**
     * Offline-renders figure [name] to a mono 16-bit WAV at [outPath].
     *
     * The `renderWav(name, path)` contract the docs runner's `audio="…"` fence drives over the
     * backdoor, identical to [DoughDspSoundDocs.renderWav] and [SoundPathMap.renderWav], so
     * [DocsAudioProvenance] can re-derive these clips exactly like the others. Mono because the
     * program's two outputs are the same signal (`<: _,_`), so a stereo file would be twice the
     * bytes and none of the information.
     */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name)
        val samples = audio.mono()
        require(samples.isNotEmpty()) { "figure '$name' rendered no samples" }
        require(samples.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.9f)
    }

    /** Peaks in the fundamental-only band during the first chord of [audio]. */
    fun fundamentalsInFirstChord(audio: FaustPatternRender): List<FaustPeak> {
        val at = (MEASURE_AT_SECONDS * audio.sampleRate).toInt()
        return faustSpectrumPeaks(
            audio.mono(), at, MEASURE_WINDOW, audio.sampleRate, BAND_LO_HZ, BAND_HI_HZ, 0.15
        )
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    /**
     * Renders both figures, audits them, and writes them.
     *
     * Writes NO committed assets; pass an output dir as `args[0]` (or `FAUST_POLY_SOUND_DIR`) to
     * regenerate the published ones.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("FAUST_POLY_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "faust-poly-sound").path
        )
        outDir.mkdirs()

        try {
            audit(outDir)
        } finally {
            // The Faust engine holds a Truffle context alive; a jvm-main smoke must exit naturally.
            FaustEngine.shutdown()
        }

        println("faust poly sound docs rendered: ${FIGURES.size} figure(s)")
    }

    private fun audit(outDir: File) {
        val notated = faustPatternNotes(progression(), CYCLES, CPS, SR, gain = NOTE_GAIN).notes
        println("the notation — ${notated.size} notes over $CYCLES cycles at $CPS cps")
        println("  first chord: ${notated.take(3).map { "%.2fHz".format(it.hz) }}")
        println()

        FIGURES.forEach { f ->
            val audio = render(f.name)
            check(audio.notes.size == notated.size) {
                "figure '${f.name}': ${audio.notes.size} notes against the notation's ${notated.size}. " +
                    "The whole comparison is that both figures play the same notes."
            }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "figure '${f.name}': non-finite samples"
            }
            val mono = audio.mono()
            var peak = 0f
            for (s in mono) peak = if (abs(s) > peak) abs(s) else peak
            check(peak > 0.02f) { "figure '${f.name}': effectively silent (peak=$peak)" }
            check(peak <= 4.0f) { "figure '${f.name}': runaway level (peak=$peak)" }

            val fundamentals = fundamentalsInFirstChord(audio)
            println(
                "  %-24s voices=%-2d notes=%2d concurrency=%d steals=%2d peak=%.3f".format(
                    f.name, f.voices, audio.notes.size, audio.peakConcurrency, audio.steals, peak
                )
            )
            println("      fundamentals in ${BAND_LO_HZ.toInt()}-${BAND_HI_HZ.toInt()}Hz: $fundamentals")

            // The figure's claim, asserted rather than described. A peak in this band can only be a
            // fundamental, so this counts notes sounding — which is the one measurement that tells
            // polyphony from a voice stealing itself.
            check(fundamentals.size == f.expectedFundamentals) {
                "figure '${f.name}': ${fundamentals.size} fundamental(s) in " +
                    "${BAND_LO_HZ.toInt()}-${BAND_HI_HZ.toInt()}Hz, expected ${f.expectedFundamentals}. " +
                    "That number is this figure's caption on $PAGE: $fundamentals"
            }
            check((audio.steals > 0L) == f.expectSteals) {
                "figure '${f.name}': ${audio.steals} steal(s), expected " +
                    (if (f.expectSteals) "some" else "none") +
                    ". At ${f.voices} voice(s) that is the difference the figure is about."
            }

            val file = File(outDir, "${f.name}.wav")
            WavFloatArrayWriter.writeMono16(file, mono, SR, normalizePeak = 0.9f)
            val bytes = file.readBytes()
            check(bytes.size == 44 + mono.size * 2) {
                "figure '${f.name}': WAV data size mismatch: ${bytes.size} vs ${44 + mono.size * 2}"
            }
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "figure '${f.name}': missing RIFF header" }
            println("      wrote ${file.absolutePath} (${bytes.size} bytes, %.2fs)".format(mono.size.toDouble() / SR))
        }
        println()

        // And the page has to carry what the figures claim, so the prose cannot drift off the audio.
        val page = File(PAGE)
        if (page.isFile) {
            val text = page.readText()
            val missing = FIGURES.filterNot { text.contains(it.pageLabel) }
            check(missing.isEmpty()) {
                "$PAGE does not carry the caption(s) ${missing.map { it.name }} verbatim, so the " +
                    "prose and the published clip can drift apart"
            }
            FIGURES.forEach { f ->
                check(text.contains("audio-spec=\"${f.name}\"")) {
                    "$PAGE has no audio fence for figure '${f.name}'"
                }
            }
            println("  $PAGE carries both fences and both captions verbatim")
        }
        println()
    }
}

@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/*
FIELD_TEST
id: synth.pat.seam
runner: jvm-main
entrypoint: afield.four.synth.PatSynthDocs
tags: [smoke, synth, pattern, tidal, audio, wav, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "pat seam docs rendered"
    - "block sizes agree"
*/

/**
 * The scenes and the checks for [patSynth], the pattern scheduler that sits on the audio seam.
 *
 * Every `.wav` here comes out of [patSynthRenderStereo], which drives a real [PatSynthBank] through a
 * [SynthContext] block by block exactly as [FieldAudioEngine] would. So these files are a record of
 * the realtime path with no audio device attached, rather than a second implementation of it.
 *
 * # Three properties of the scheduler
 *
 * **Block size does not change the output.** The same bar rendered at 64, 128, 512 and 1024 frames
 * per block comes back bit-identical. A scheduler that started each note at the top of the block it
 * fell in would pass every level and onset check here, and fail this one.
 *
 * **The seam voice is the offline voice.** [patRenderStereo] and [patSynthRenderStereo] render the
 * same bar to the same onset counts and the same levels, but not to the same bytes: the offline
 * renderer works in seconds and truncates, this one works in frames and rounds, so a note can
 * differ by a frame at each end and a dense passage by a few merged onsets.
 *
 * **The band analysis separates a kick from a hat.** [BandSplit] over a kick-only render and a
 * hat-only render has to order its low and high bands opposite ways round. That is the smallest claim
 * that says the filters are filtering, and it is the property anything driving a display off
 * [BandSplit] rests on.
 */

/**
 * The three scenes, the three scheduler checks, and the renderer that writes the `.wav`s out.
 *
 * [SCENES] is the gallery in order; [BLOCK_SIZES] is the sweep the invariance check runs.
 */
object PatSynthDocs {

    const val SR: Int = PatSoundDocs.SR

    /** Block sizes the invariance check sweeps. 64 is smaller than any engine buffer, 1024 larger. */
    val BLOCK_SIZES: List<Int> = listOf(64, 128, 512, 1024)

    /** One scene: a pattern, a tempo, a voice, and what it is for. */
    class Scene(
        val name: String,
        val title: String,
        val blurb: String,
        val cycles: Int,
        val cps: Double,
        val voice: PatVoiceSpec,
        val pattern: () -> PatPattern<String>,
        /**
         * Onsets [PatSoundDocs.onsetPeaks] finds over the whole render, as measured rather than as
         * derived from the pattern: two events inside one 12 ms analysis window merge into one peak,
         * so this is below the event count for every scene here. Checked as a floor four under.
         */
        val expectedOnsets: Int,
        val onsetThreshold: Double = 0.05,
    ) {
        val seconds: Double get() = cycles / cps
        fun render(blockFrames: Int = 128): PatSoundDocs.Stereo =
            patSynthRenderStereo(
                pattern(), cycles = cycles, cps = cps, voice = voice,
                sampleRate = SR, blockFrames = blockFrames,
            )
    }

    val SCENES: List<Scene> = listOf(

        // 1 ── the beat sound.md and sound_paths.md both start from, through the seam this time.
        Scene(
            name = "seam-beat",
            title = "pat(\"bd ~ sd ~, hh*8\") through the seam scheduler",
            blurb = "The beat the rest of the sound documentation starts from, scheduled block by " +
                "block through a PatSynthBank rather than rendered in one pass. Four cycles at two " +
                "cycles per second: a kick and a snare a cycle, eight hats over them.",
            cycles = 4,
            cps = 2.0,
            // A short decay, as PatSoundDocs' scenes use: at eight hats in half a second a tail of
            // 0.24 s runs every strike into the next, and what comes out is one continuous smear
            // with a peak of 1.7 and two detectable onsets in four cycles.
            voice = patVoice(decay = 0.008, sustain = 0.05),
            pattern = { pat("bd ~ sd ~, hh*8") },
            expectedOnsets = 32,
        ),

        // 2 ── a pattern whose events do not land on block boundaries at any sane buffer size.
        Scene(
            name = "seam-triplets",
            title = "sixteen against twelve, so nothing lands on a block boundary",
            blurb = "A four-on-the-floor kick under sixteen hats and twelve triangle notes in the " +
                "same cycle. At 48 kHz and 1.5 cycles per second a twelfth of a cycle is 2666.67 " +
                "frames, so most events start part way through a block whatever the buffer size is.",
            cycles = 4,
            cps = 1.5,
            voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.01, sustain = 0.08),
            pattern = { stack(pat("bd*4"), pat("hh*16"), pat("60 63 67 70").fast(3)) },
            expectedOnsets = 58,
        ),

        // 3 ── the input the band analysis and the GPU half read.
        Scene(
            name = "seam-bands",
            title = "a kick, a mid stab and a hat line, for something to analyse",
            blurb = "Three parts that sit in three different parts of the spectrum, so a band " +
                "split has something to separate: a kick on every beat, a mid-register square " +
                "stab on the offbeats, and sixteen hats a cycle.",
            cycles = 4,
            cps = 2.0,
            voice = patVoice(wave = PatWave.SQUARE, decay = 0.012, sustain = 0.08),
            pattern = { stack(pat("bd*4"), pat("~ 52 ~ 52"), pat("hh*16")) },
            expectedOnsets = 53,
        ),
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name]
            ?: kotlin.error("unknown PatSynthDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = scene.render()
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    /**
     * The per-band reading for a whole render, as the docs page plots it.
     *
     * One [BandSplit.measure] per hop of [hopFrames] over a window of [windowFrames], which is the
     * same call a frame of box code would make against a live [PatSynthLaunch.tap] snapshot.
     */
    fun bandSeries(
        audio: PatSoundDocs.Stereo,
        split: BandSplit,
        windowFrames: Int = 2048,
        hopFrames: Int = 512,
    ): List<DoubleArray> {
        val mono = audio.mono
        val out = mutableListOf<DoubleArray>()
        var at = 0
        while (at + windowFrames <= mono.size) {
            out += split.measure(mono.copyOfRange(at, at + windowFrames))
            at += hopFrames
        }
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_SEAM_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-seam-docs").path,
        )
        outDir.mkdirs()

        SCENES.forEach { scene ->
            val audio = scene.render()
            check(audio.frames > 0) { "${scene.name}: rendered no samples" }
            check(audio.left.size == audio.right.size) { "${scene.name}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${scene.name}: non-finite samples"
            }
            val expectedFrames = Math.round(scene.seconds * SR).toInt()
            check(abs(audio.frames - expectedFrames) <= 1) {
                "${scene.name}: expected $expectedFrames frames, got ${audio.frames}"
            }
            val peak = PatSoundDocs.peak(audio.mono)
            val rms = PatSoundDocs.rms(audio.mono)
            check(peak > 0.02) { "${scene.name}: effectively silent (peak=$peak)" }
            check(peak <= 4.0) { "${scene.name}: runaway level (peak=$peak)" }
            check(rms > 0.002) { "${scene.name}: RMS too low to be music (rms=$rms)" }
            val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = scene.onsetThreshold)
            // A floor rather than an equality: two events a few milliseconds apart merge into one
            // peak in a 12 ms analysis window, and that is the detector's behaviour rather than the
            // scheduler's. The scheduler's timing is what the block sweep below tests.
            check(onsets.size >= scene.expectedOnsets - 4) {
                "${scene.name}: expected about ${scene.expectedOnsets} strikes, found ${onsets.size}"
            }
            println(
                "scene ${scene.name}: ${"%.2f".format(audio.frames.toDouble() / SR)}s, " +
                    "peak=${"%.3f".format(peak)}, rms=${"%.4f".format(rms)}, onsets=${onsets.size}",
            )

            val file = File(outDir, "pat-seam-${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, SR, normalizePeak = 0.9f)
            val bytes = file.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${scene.name}: missing RIFF" }
            check(bytes.size == 44 + audio.frames * 4) {
                "${scene.name}: WAV data size mismatch: ${bytes.size} vs ${44 + audio.frames * 4}"
            }
            println("  wrote ${file.absolutePath} (${bytes.size} bytes)")
        }

        // ── the scheduler's own property ───────────────────────────────────────────────────
        println()
        println("the same bar at four engine buffer sizes:")
        SCENES.forEach { scene ->
            val reference = scene.render(BLOCK_SIZES.first())
            BLOCK_SIZES.drop(1).forEach { block ->
                val other = scene.render(block)
                check(other.frames == reference.frames) {
                    "${scene.name}: $block frames per block gave ${other.frames} frames, " +
                        "${BLOCK_SIZES.first()} gave ${reference.frames}"
                }
                var worst = 0.0
                var worstAt = -1
                for (i in 0 until reference.frames) {
                    val dl = abs(reference.left[i] - other.left[i]).toDouble()
                    val dr = abs(reference.right[i] - other.right[i]).toDouble()
                    val d = max(dl, dr)
                    if (d > worst) { worst = d; worstAt = i }
                }
                check(worst == 0.0) {
                    "${scene.name}: ${BLOCK_SIZES.first()} and $block frames per block differ by " +
                        "$worst at frame $worstAt, so a note is being rounded to a block boundary"
                }
                // The same property stated in a way that survives a future change to the summing:
                // bit-identity would go if voices were ever mixed in a different order, and the
                // frame each strike lands on would not.
                val refStarts = PatSoundDocs.onsetPeaks(reference.mono, threshold = scene.onsetThreshold).map { it.start }
                val otherStarts = PatSoundDocs.onsetPeaks(other.mono, threshold = scene.onsetThreshold).map { it.start }
                check(refStarts == otherStarts) {
                    "${scene.name}: $block frames per block put the onsets at different frames: " +
                        "${refStarts.take(8)} against ${otherStarts.take(8)}"
                }
            }
            println("  ${scene.name}: ${BLOCK_SIZES.joinToString(", ")} block sizes agree bit for bit")
        }

        // ── the seam voice against the offline voice ───────────────────────────────────────
        println()
        println("the seam scheduler against patRenderStereo, on the same bar:")
        SCENES.forEach { scene ->
            val seam = scene.render()
            val offline = patRenderStereo(scene.pattern(), scene.cycles, scene.cps, scene.voice, SR)
            val seamOnsets = PatSoundDocs.onsetPeaks(seam.mono, threshold = scene.onsetThreshold).size
            val offlineOnsets = PatSoundDocs.onsetPeaks(offline.mono, threshold = scene.onsetThreshold).size
            check(abs(seamOnsets - offlineOnsets) <= 4) {
                "${scene.name}: the seam found $seamOnsets onsets and the offline renderer $offlineOnsets"
            }
            val seamRms = PatSoundDocs.rms(seam.mono)
            val offlineRms = PatSoundDocs.rms(offline.mono)
            val ratio = seamRms / offlineRms
            check(ratio in 0.85..1.15) {
                "${scene.name}: RMS ratio $ratio between the two paths is too far from one"
            }
            println(
                "  ${scene.name}: onsets $seamOnsets against $offlineOnsets, " +
                    "RMS ratio ${"%.3f".format(ratio)}",
            )
        }

        // ── the analysis the GPU half reads ───────────────────────────────────────────────
        println()
        println("four bands over the same window, on a kick line and a hat line:")
        val split = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = SR)
        val kick = patSynthRenderStereo(pat("bd*4"), 2, 2.0, patVoice(decay = 0.09, sustain = 0.3), sampleRate = SR)
        val hats = patSynthRenderStereo(pat("hh*16"), 2, 2.0, patVoice(decay = 0.02, sustain = 0.1), sampleRate = SR)
        val kickBands = split.measure(kick.mono.copyOfRange(0, 4096))
        val hatBands = split.measure(hats.mono.copyOfRange(0, 4096))
        println("  kick: ${kickBands.joinToString(", ") { "%.4f".format(it) }}")
        println("  hats: ${hatBands.joinToString(", ") { "%.4f".format(it) }}")
        check(kickBands.first() > kickBands.last()) {
            "a kick should be louder in the lowest band than the highest, got ${kickBands.toList()}"
        }
        check(hatBands.last() > hatBands.first()) {
            "hats should be louder in the highest band than the lowest, got ${hatBands.toList()}"
        }
        println("  the low and high bands order opposite ways round, as they have to")

        // ── patSynth itself: the feeder thread, the launch, the tap ───────────────────────
        // Everything above renders through a bank directly. This is the live assembly a box gets,
        // pulled by hand instead of by the engine, so the feeder thread, the launch lifecycle and
        // the tap are exercised on a machine with no output device.
        println()
        println("patSynth, pulled by hand for two seconds:")
        val bands = logBands(4, lowHz = 70.0, highHz = 8000.0, sampleRate = SR)
        val launch = patSynth(
            pat("bd*4"),
            cps = 2.0,
            voice = patVoice(decay = 0.09, sustain = 0.3),
            sampleRate = SR,
        )
        try {
            val frameSamples = SR / 60
            val frames = 120
            repeat(frames) { n ->
                launch.advanceOffline(frameSamples)
                // The feeder schedules ahead of bank.frame on its own thread and sleeps 20 ms
                // between passes, so pulling in a tight loop has to leave it room to keep up.
                if (n % 20 == 19) Thread.sleep(25)
            }
            val expected = frames.toLong() * frameSamples
            check(launch.bank.frame == expected) {
                "pulled $expected frames and the bank's clock says ${launch.bank.frame}"
            }
            check(launch.feeder.cyclesFed >= 3) {
                "two seconds at 2 cps needs four cycles queried; the feeder managed " +
                    "${launch.feeder.cyclesFed}"
            }
            check(launch.bank.stolen == 0L) { "${launch.bank.stolen} note(s) stole a busy voice" }
            val snapshot = launch.tap.snapshot()
            check(snapshot.frameCount > 0) { "the tap is empty, so nothing pulled through it" }
            check(PatSoundDocs.peak(snapshot.left) > 0.02) {
                "the tap's window is silent (peak ${PatSoundDocs.peak(snapshot.left)})"
            }
            val live = bands.measure(snapshot)
            check(live.first() > live.last()) {
                "a kick line should be louder in the lowest band than the highest, got ${live.toList()}"
            }
            println(
                "  ${launch.feeder.cyclesFed} cycles fed, bank at ${launch.bank.frame} frames, " +
                    "tap ${snapshot.frameCount} frames, bands " +
                    live.joinToString(", ") { "%.4f".format(it) },
            )
        } finally {
            launch.close()
        }
        check(!launch.feeder.isRunning) { "close() left the feeder thread running" }
        println("  closed, and the feeder stopped")

        println()
        println("pat seam docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}

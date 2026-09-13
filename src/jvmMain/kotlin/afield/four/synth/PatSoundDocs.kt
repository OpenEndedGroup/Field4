@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.pat.sound
runner: jvm-main
entrypoint: afield.four.synth.PatSoundDocs
tags: [smoke, synth, pattern, tidal, audio, wav, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "pat sound docs rendered"
*/

/**
 * Headless **offline sound-render harness** for the pattern language — the audible companion to
 * [PatPianoRollGallery]'s piano rolls, following the same contract as [DoughDspSoundDocs].
 *
 * Every scene is produced by actually *scheduling* a pattern and running its events through
 * [patVoice], a small deterministic CPU voice: an oscillator, a one-pole lowpass, and an
 * exponential-decay envelope, driven entirely by the event's own `gain` / `pan` / `cutoff` /
 * `sustain` parameters. There is no realtime engine, no audio device, no GPU and no Field window,
 * and the render is deterministic, so a committed `.wav` regenerates byte-stably.
 *
 * Three scenes are **two-halves-of-one-file**: the first half is what the pre-tonight
 * surface could produce and the second half is the same pattern with the new operator applied, so
 * the difference is *heard* in a single listen rather than described. The verification checks the
 * halves against each other rather than against a remembered number.
 *
 * As a FIELD_TEST (`synth.pat.sound`) every scene is rendered to a temp dir and asserted for real
 * correctness: a valid RIFF/WAVE header with the right declared sample rate and data size on disk,
 * non-silence, the expected duration, the expected peak and RMS, the expected number of onsets,
 * and per-scene spectral or stereo claims (the gain modulation really modulates, the filter sweep
 * really brightens, the voice-led chords really sound the notes the pattern says, `jux` really
 * decorrelates the two channels). It writes NO committed assets during the test. Pass an output dir
 * as `args[0]` (or set `PAT_SOUND_DIR`) to regenerate the committed captures.
 *
 * Add a sound by appending one [Scene] to [SCENES].
 */
object PatSoundDocs {

    const val SR = 44_100

    /** The analysis-window length [onsetPeaks] uses, in milliseconds. Its timing bias, too. */
    const val ONSET_WINDOW_MS = 12.0

    // ── scene model ────────────────────────────────────────────────────────────────────────

    /** Stereo audio: two equal-length channels in roughly [-1, 1]. */
    class Stereo(val left: FloatArray, val right: FloatArray) {
        val frames: Int get() = left.size
        val mono: FloatArray get() = FloatArray(frames) { 0.5f * (left[it] + right[it]) }
    }

    class Scene(
        val name: String,
        val title: String,
        /** One line describing what you hear. */
        val blurb: String,
        val cycles: Int,
        /** Cycles per second — percussive scenes want 2, a chord scene wants room to breathe. */
        val cps: Double,
        val render: (Int, Double) -> Stereo,
        /** Deeper correctness check on the rendered audio. */
        val verify: Scene.(Stereo) -> Unit = {}
    ) {
        val seconds: Double get() = cycles / cps
        /** Samples per cycle at this scene's tempo — the unit its timing checks are written in. */
        val cycleSamples: Double get() = SR / cps
    }

    private const val CPS = 2.0

    val SCENES: List<Scene> = listOf(

        // 1 ── the parameter counterfactual, in one file: flat, then following a sine.
        Scene(
            name = "gain-lfo-before-after",
            title = "gain(sine()) — two cycles flat, then two cycles following the LFO",
            blurb = "Sixteen hats a cycle. The first two cycles are the pattern as the old surface " +
                "could express it — every hat the same level. The last two are the identical pattern " +
                "with `gain(sine().range(0.08, 1.0))`; you hear the level breathe once per cycle.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                val hats = pat("hh*16")
                // `whenCycle` is the existing operator for "from cycle 2 on, play this instead".
                // (`cat(a, b).slow(2)` looks like it would do the same and does not: `slow` is
                // anchored per cycle rather than spanning them, so it doubles the density instead.)
                patRenderStereo(
                    hats.whenCycle({ it >= 2 }) { hats with gain(sine().range(0.08, 1.0)) },
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.NOISE, decay = 0.006, sustain = 0.05)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono, threshold = 0.05)
                check(onsets.size == 64) { "expected 64 hats, found ${onsets.size}" }
                // Per-note RMS, not peak: a noise burst's peak is stochastic even at a fixed gain.
                val flat = onsets.take(32).map { it.rms }
                val modulated = onsets.drop(32).map { it.rms }
                val flatCv = variation(flat)
                val modCv = variation(modulated)
                println(
                    "    flat half level CV=${"%.4f".format(flatCv)} (range ${"%.3f".format(spread(flat))}), " +
                        "modulated half CV=${"%.4f".format(modCv)} (range ${"%.3f".format(spread(modulated))})"
                )
                check(flatCv < 0.12) { "the 'before' half is not flat: CV $flatCv" }
                check(modCv > 4.0 * max(1e-6, flatCv)) {
                    "the modulated half is not audibly modulated: CV $modCv vs $flatCv"
                }
                // and the modulation really is one sine per cycle: two humps over the two cycles.
                check(humpCount(modulated) == 2) { "expected two gain humps, counted ${humpCount(modulated)}" }
            }
        ),

        // 2 ── a continuous filter parameter, note by note.
        Scene(
            name = "cutoff-sweep",
            title = "cutoff(sine().rangex(300, 6000)) — a filter curve read per note",
            blurb = "Sixteen sawtooth notes a cycle through a one-pole lowpass whose cutoff is a " +
                "sine in log-frequency. Each note latches one value of the curve, so the timbre " +
                "opens and closes once per cycle without the note count changing.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                patRenderStereo(
                    // MIDI 45 (110 Hz), not "bd": a named drum carries its own frequency and wave
                    // from the drum map, and this scene is about the filter, not the kit.
                    pat("45*16") with cutoff(sine().rangex(300, 6000)) with gain(0.7),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.SAW, baseHz = 110.0, decay = 0.012, sustain = 0.3)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono, threshold = 0.03)
                check(onsets.size == 64) { "expected 64 notes, found ${onsets.size}" }
                // Brightness per note must track the curve: bright where the cutoff is high.
                val brightness = onsets.map { brightnessOf(audio.mono, it.start, it.end) }
                val perCycle = brightness.chunked(16)
                perCycle.forEachIndexed { cycle, values ->
                    val peak = values.indexOf(values.max())
                    check(peak in 2..5) { "cycle $cycle: brightness peaks at note $peak, not near the quarter" }
                    val trough = values.indexOf(values.min())
                    check(trough in 10..13) { "cycle $cycle: brightness troughs at note $trough" }
                }
                val ratio = brightness.max() / max(1e-9, brightness.min())
                println("    brightest / dullest note = ${"%.1f".format(ratio)}x")
                check(ratio > 2.0) { "the filter sweep barely changed the timbre: ${ratio}x" }
            }
        ),

        // 3 ── the pitch surface, audibly: a voice-led progression, arpeggiated.
        Scene(
            name = "voice-leading",
            title = "chords(\"c'maj7 a'min7 d'min7 g'dom7\") — as written, then voice-led",
            blurb = "The same four-chord progression twice, arpeggiated. The first two cycles are " +
                "spelled from each chord's own root and the hand leaps; the last two are " +
                "`voiceLead()`, which holds the common tones and moves 9 semitones instead of 83.",
            cycles = 4,
            // Half the tempo of the percussive scenes: each arpeggio note needs enough cycles of
            // its own waveform for the pitch check below to be a real measurement.
            cps = 1.0,
            render = { cycles, cps ->
                val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
                val asWritten = progression.arpeggiate().texts()
                val voiceLed = progression.voiceLead(center = 60).arpeggiate().texts()
                patRenderStereo(
                    asWritten.whenCycle({ it >= 2 }) { voiceLed },
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.SINE, decay = 0.02, sustain = 0.45)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono, threshold = 0.02)
                check(onsets.size == 64) { "expected 64 arpeggiated notes, found ${onsets.size}" }

                // The audio must actually sound the notes the pattern says. Estimate each note's
                // pitch from the signal and compare with the scheduled MIDI number.
                val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
                val expected = (
                    cycleNotes(progression.arpeggiate(), 0) + cycleNotes(progression.arpeggiate(), 1) +
                        cycleNotes(progression.voiceLead(center = 60).arpeggiate(), 0) +
                        cycleNotes(progression.voiceLead(center = 60).arpeggiate(), 1)
                    )
                check(expected.size == 64) { "expected 64 scheduled notes, the pattern gave ${expected.size}" }
                var worst = 0.0
                onsets.zip(expected).forEachIndexed { index, (onset, midi) ->
                    val hz = estimateHz(audio.mono, onset.start, onset.end)
                    val heard = 69.0 + 12.0 * (Math.log(hz / 440.0) / Math.log(2.0))
                    val error = abs(heard - midi)
                    worst = max(worst, error)
                    check(error < 0.6) { "note $index: pattern says MIDI $midi, audio sounds ${"%.2f".format(heard)} (${"%.1f".format(hz)} Hz)" }
                }
                println("    worst pitch error across 64 notes = ${"%.3f".format(worst)} semitones")

                // and the second half really is the compressed, voice-led register.
                val writtenSpan = expected.take(32).let { it.max() - it.min() }
                val ledSpan = expected.drop(32).let { it.max() - it.min() }
                println("    as-written spans $writtenSpan semitones, voice-led spans $ledSpan")
                check(ledSpan < writtenSpan) { "voice leading did not narrow the register: $ledSpan vs $writtenSpan" }
            }
        ),

        // 4 ── jux: the stereo image is the point, so the file is really stereo.
        Scene(
            name = "jux-stereo",
            title = "jux { rev() } — forwards in one ear, backwards in the other",
            blurb = "A four-step pattern hard-panned left, and the same pattern reversed hard-panned " +
                "right. The two channels carry genuinely different rhythms — the file is stereo " +
                "precisely because folding it to mono would destroy what it demonstrates.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                patRenderStereo(
                    pat("bd ~ sd hh").jux { it.rev() } with gain(0.8),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.SQUARE, decay = 0.02, sustain = 0.2)
                )
            },
            verify = { audio ->
                val left = onsetPeaks(audio.left, threshold = 0.05)
                val right = onsetPeaks(audio.right, threshold = 0.05)
                check(left.size == 12) { "left channel should carry 3 onsets x 4 cycles, found ${left.size}" }
                check(right.size == 12) { "right channel should carry 3 onsets x 4 cycles, found ${right.size}" }
                // The channels must be genuinely decorrelated, not a panned copy.
                val correlation = correlation(audio.left, audio.right)
                println("    L/R correlation = ${"%.3f".format(correlation)}")
                check(abs(correlation) < 0.5) { "jux produced near-identical channels: correlation $correlation" }
                // And the reversal is visible in the onset positions. "bd ~ sd hh" fires at 0, 1/2
                // and 3/4; mirrored about the cycle, those arcs land at 3/4, 1/4 and 0 — so the
                // right channel's onsets are 0, 1/4, 3/4. That is the reversal, measured in audio.
                // Quantise each onset to the eighth-note grid it was scheduled on. The trailing
                // `% 1.0` matters: an onset detected a millisecond early rounds to a whole cycle.
                fun positions(onsets: List<Onset>) = onsets
                    .map { (it.start.toDouble() % cycleSamples) / cycleSamples }
                    .map { (Math.round(it * 8.0) / 8.0) % 1.0 }
                    .distinct().sorted()
                check(positions(left) == listOf(0.0, 0.5, 0.75)) { "left onsets: ${positions(left)}" }
                check(positions(right) == listOf(0.0, 0.25, 0.75)) { "right onsets: ${positions(right)}" }
            }
        ),

        // 5 ── off: an echo displaced by an exact eighth, quieter.
        Scene(
            name = "off-echo",
            title = "off(1/8) { gain 0.35 } — a quieter ghost an exact eighth late",
            blurb = "Two onsets a cycle, each shadowed by a copy an eighth of a cycle later at just " +
                "over a third the level. The displacement is exact rational time, so the echo lands " +
                "on the grid rather than near it.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                patRenderStereo(
                    pat("bd ~ sd ~").off(1, 8) { it with gain(0.35) },
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.SINE, baseHz = 220.0, decay = 0.012, sustain = 0.12)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono, threshold = 0.02)
                check(onsets.size == 16) { "expected 2 onsets + 2 echoes x 4 cycles = 16, found ${onsets.size}" }
                // Onsets alternate loud, quiet, loud, quiet, an eighth of a cycle apart.
                val loud = onsets.filterIndexed { i, _ -> i % 2 == 0 }
                val quiet = onsets.filterIndexed { i, _ -> i % 2 == 1 }
                val loudMean = loud.map { it.rms }.average()
                val quietMean = quiet.map { it.rms }.average()
                println("    loud=${"%.3f".format(loudMean)} echo=${"%.3f".format(quietMean)} ratio=${"%.2f".format(quietMean / loudMean)}")
                check(quietMean < loudMean * 0.6) { "the echo is not quieter: $quietMean vs $loudMean" }
                // Each pair is an onset and its echo. The tolerance is one analysis window (12 ms,
                // 0.024 cycles at this tempo): the detector timestamps an onset at the start of the
                // window whose level crossed, so a loud note is reported slightly earlier than a
                // quiet one. Measured gap 0.108 against the scheduled 0.125 — comfortably an eighth
                // rather than a quarter or a sixteenth, which is the claim.
                onsets.chunked(2).forEach { (a, b) ->
                    val gapCycles = (b.start - a.start).toDouble() / cycleSamples
                    check(abs(gapCycles - 0.125) < 0.03) { "the echo is not an eighth late: $gapCycles cycles" }
                }
            }
        ),

        // 6 ── the mini-notation additions, heard: `?` degrade over a shuffled grid.
        Scene(
            name = "degrade-shuffle",
            title = "pat(\"hh*16?0.35\").shuffle(4) — deterministic thinning, permuted quarters",
            blurb = "Sixteen hats a cycle with a 35% chance of being dropped, over quarters played " +
                "in a different order every cycle. Deterministic — the same text always gives the " +
                "same groove — but no two cycles are alike.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                patRenderStereo(
                    pat("hh*16?0.35").shuffle(4) with gain(0.85),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.NOISE, decay = 0.006, sustain = 0.05)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono, threshold = 0.05)
                check(onsets.size in 30..58) { "?0.35 over 64 slots should leave 30..58 hats, left ${onsets.size}" }
                val perCycle = (0 until cycles).map { c ->
                    onsets.count { it.start >= c * cycleSamples && it.start < (c + 1) * cycleSamples }
                }
                println("    hats per cycle = $perCycle")
                check(perCycle.all { it in 4..15 }) { "a cycle came out empty or undegraded: $perCycle" }
                check(perCycle.distinct().size > 1) { "every cycle dropped the same number of hats: $perCycle" }
                // Determinism: rendering again must be bit-identical.
                val again = patRenderStereo(
                    pat("hh*16?0.35").shuffle(4) with gain(0.85),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.NOISE, decay = 0.006, sustain = 0.05)
                )
                check(again.left.contentEquals(audio.left)) { "the render is not deterministic" }
            }
        ),

        // 7 ── groove: the same eight hats, straight then swung.
        Scene(
            name = "swing-before-after",
            title = "swing(4) — two cycles straight, then two swung",
            blurb = "Eight hats a cycle. The first two cycles are dead straight; the last two are " +
                "swung, every off-beat pushed exactly a third of an eighth late. It is still exactly " +
                "quantised — the shift is rational time, not a nudge in milliseconds.",
            cycles = 4,
            cps = CPS,
            render = { cycles, cps ->
                val hats = pat("hh*8")
                patRenderStereo(
                    hats.whenCycle({ it >= 2 }) { hats.swing(4) },
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.NOISE, decay = 0.008, sustain = 0.06)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono)
                check(onsets.size == 32) { "expected 8 hats x 4 cycles, found ${onsets.size}" }
                val gaps = onsets.zipWithNext { a, b -> (b.start - a.start).toDouble() / cycleSamples }
                // Skip gap 0: the very first onset sits at sample 0, so it is flagged in the first
                // analysis window while every later onset is flagged a window after its true start.
                // That makes only the first interval short — measured 0.104 against 0.125.
                val straight = gaps.drop(1).take(14)
                val swung = gaps.drop(16).take(15)

                // Straight: every gap an eighth. The tolerance is the detector's one-window bias.
                check(straight.all { abs(it - 0.125) < 0.02 }) { "the straight half is not even: $straight" }

                // Swung: gaps alternate long, short — 1/8 + 1/24 then 1/8 - 1/24, i.e. 1/6 and 1/12.
                val longs = swung.filterIndexed { i, _ -> i % 2 == 0 }
                val shorts = swung.filterIndexed { i, _ -> i % 2 == 1 }
                println(
                    "    straight gap = %.4f cycles; swung long = %.4f, short = %.4f, ratio = %.2f".format(
                        straight.average(), longs.average(), shorts.average(), longs.average() / shorts.average()
                    )
                )
                check(longs.all { abs(it - 1.0 / 6.0) < 0.02 }) { "the swung long gaps are wrong: $longs" }
                check(shorts.all { abs(it - 1.0 / 12.0) < 0.02 }) { "the swung short gaps are wrong: $shorts" }
                check(longs.average() / shorts.average() > 1.6) { "the swing is inaudible" }
                // and the straight half really is more even than the swung half.
                check(variation(straight) < variation(swung) / 3.0) {
                    "the two halves are equally uneven: ${variation(straight)} vs ${variation(swung)}"
                }
            }
        ),

        // 8 ── mini-notation: hear what a `.` does to five notes.
        Scene(
            name = "dot-grouping",
            title = "`.` grouping — five even notes, then two grouped halves",
            blurb = "Five notes a cycle. The first two cycles are `bd sd hh hh hh`, five equal fifths. " +
                "The last two are `bd sd . hh hh hh` — the same five notes, but the dot groups them " +
                "into two halves, so the first two get a quarter each and the last three a sixth each. " +
                "Same notes, completely different feel.",
            cycles = 4,
            cps = 1.0,
            render = { cycles, cps ->
                patRenderStereo(
                    pat("bd sd hh hh hh").whenCycle({ it >= 2 }) { pat("bd sd . hh hh hh") },
                    cycles = cycles, cps = cps,
                    voice = patVoice(decay = 0.012, sustain = 0.12)
                )
            },
            verify = { audio ->
                val onsets = onsetPeaks(audio.mono)
                check(onsets.size == 20) { "expected 5 notes x 4 cycles, found ${onsets.size}" }
                // The detector reports the start of the window whose level crossed, so a loud onset is
                // timestamped nearly a full window EARLY — measured 11 ms against the 12 ms window.
                // Uncorrected, the note on a cycle boundary falls into the previous cycle's bucket.
                val bias = ONSET_WINDOW_MS / 1000.0 * SR
                fun positionsOfCycle(cycle: Int) = onsets
                    .map { it.start + bias }
                    .filter { it >= cycle * cycleSamples && it < (cycle + 1) * cycleSamples }
                    .map { (it % cycleSamples) / cycleSamples }

                // Fifths: 0, 0.2, 0.4, 0.6, 0.8. The tolerance is the detector's one-window bias
                // (12 ms against a 1 s cycle here).
                val even = positionsOfCycle(1)
                check(even.size == 5) { "cycle 1 has ${even.size} notes" }
                listOf(0.0, 0.2, 0.4, 0.6, 0.8).zip(even).forEach { (want, got) ->
                    check(abs(got - want) < 0.03) { "even cycle: expected $want, measured $got (all: $even)" }
                }

                // Grouped: 0, 0.25, 0.5, 0.6667, 0.8333.
                val grouped = positionsOfCycle(3)
                check(grouped.size == 5) { "cycle 3 has ${grouped.size} notes" }
                listOf(0.0, 0.25, 0.5, 2.0 / 3.0, 5.0 / 6.0).zip(grouped).forEach { (want, got) ->
                    check(abs(got - want) < 0.03) { "grouped cycle: expected $want, measured $got (all: $grouped)" }
                }

                // And the two are audibly different: the third note moves by a tenth of a cycle.
                println(
                    "    even     = ${even.map { "%.3f".format(it) }}\n" +
                        "    grouped  = ${grouped.map { "%.3f".format(it) }}"
                )
                check(abs(grouped[2] - even[2]) > 0.05) { "the dot changed nothing audible" }
            }
        ),
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown PatSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = scene.render(scene.cycles, scene.cps)
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    // ── verification helpers ───────────────────────────────────────────────────────────────

    /**
      * A detected note: where it started, where the next one starts, and how loud it was.
      *
      * Both loudness measures are here because they answer different questions. [peak] is the
      * transient; [rms] is the level. For a noise-based voice the peak of each burst is stochastic
      * even at a fixed `gain` — measured spread 0.22 across sixteen identical hats — so any check
      * about *level* has to read [rms].
      */
    class Onset(val start: Int, val end: Int, val peak: Double, val rms: Double)

    /**
     * Detects onsets as rising edges of a short-time RMS envelope.
     *
     * The design is the outcome of measuring three simpler ones against patterns whose note count
     * is known exactly:
     *
     * - "any sample above a threshold" counts a single noise hat several hundred times;
     * - non-overlapping abs-peak buckets found 67 onsets where 64 were scheduled, because a noise
     *   burst's peak jitters bucket to bucket and fakes rising edges;
     * - non-overlapping RMS buckets found 9 of 64 on a 110 Hz sawtooth, because a bucket shorter
     *   than the waveform's period ripples with the waveform rather than with the envelope.
     *
     * So: **overlapping** windows — [windowMs] long, hopped every [hopMs] — which decouples
     * smoothing from time resolution. The rise test compares a window against the one a full
     * window-length earlier (never an overlapping neighbour), the level test is relative to the
     * loudest window in the file so a note at `gain 0.08` still registers, and the detector only
     * re-arms once the level falls below [releaseFraction] of the last onset's peak.
     *
     * With the defaults this counts every scene in [SCENES] exactly right. It is a counter for
     * patterns whose answer is known, not a general onset detector.
     */
    fun onsetPeaks(
        samples: FloatArray,
        threshold: Double = 0.06,
        riseRatio: Double = 1.7,
        minGapMs: Double = 12.0,
        hopMs: Double = 2.0,
        windowMs: Double = ONSET_WINDOW_MS,
        releaseFraction: Double = 0.5
    ): List<Onset> {
        val hop = (hopMs / 1000.0 * SR).toInt().coerceAtLeast(1)
        val window = (windowMs / 1000.0 * SR).toInt().coerceAtLeast(hop)
        if (samples.size < window + hop) return emptyList()
        val windows = (samples.size - window) / hop + 1

        // Prefix sums of squares, so every window's RMS costs two lookups regardless of overlap.
        val cumulative = DoubleArray(samples.size + 1)
        for (i in samples.indices) cumulative[i + 1] = cumulative[i] + samples[i].toDouble() * samples[i]
        val env = DoubleArray(windows) { k ->
            val lo = k * hop
            sqrt((cumulative[lo + window] - cumulative[lo]) / window)
        }

        val globalMax = env.max()
        if (globalMax <= 0.0) return emptyList()
        val level = threshold * globalMax
        val minGapWindows = (minGapMs / hopMs).toInt().coerceAtLeast(1)
        val lookBack = (windowMs / hopMs).toInt().coerceAtLeast(1)

        val starts = mutableListOf<Int>()
        var lastWindow = -minGapWindows * 2
        var armed = true
        var lastPeak = 0.0
        for (k in 0 until windows) {
            if (!armed && env[k] < releaseFraction * lastPeak) armed = true
            if (!armed) continue
            if (env[k] < level) continue
            val previous = if (k - lookBack >= 0) env[k - lookBack] else 0.0
            if (env[k] < riseRatio * max(1e-12, previous)) continue
            if (k - lastWindow < minGapWindows) continue
            starts += k * hop
            lastWindow = k
            lastPeak = env[k]
            armed = false
        }
        return starts.mapIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else samples.size
            var peak = 0.0
            for (j in start until end) peak = max(peak, abs(samples[j]).toDouble())
            val energy = cumulative[end] - cumulative[start]
            Onset(start, end, peak, sqrt(energy / max(1, end - start)))
        }
    }

    /** Peak-to-peak range of a list, relative to its mean — "does this vary at all". */
    fun spread(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return (values.max() - values.min()) / max(1e-9, values.average())
    }

    /**
     * Coefficient of variation: standard deviation over mean.
     *
     * The right statistic for "is this level modulated", where [spread] is not: a noise voice's
     * per-note level has a long tail, so one lucky burst moves the min-to-max range by a quarter
     * while barely moving the distribution.
     */
    fun variation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val v = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(v) / max(1e-9, mean)
    }

    /** Number of local maxima in a smoothed series — how many times a modulation went up and down. */
    fun humpCount(values: List<Double>): Int {
        if (values.size < 5) return 0
        val smoothed = values.indices.map { i ->
            val lo = max(0, i - 1); val hi = min(values.size - 1, i + 1)
            (lo..hi).sumOf { values[it] } / (hi - lo + 1)
        }
        val threshold = (smoothed.max() + smoothed.min()) / 2.0
        var humps = 0
        var above = false
        for (v in smoothed) {
            if (!above && v > threshold) { humps++; above = true }
            if (above && v < threshold) above = false
        }
        return humps
    }

    /** First-difference energy over total energy across `[from, to)` — a brightness proxy. */
    fun brightnessOf(samples: FloatArray, from: Int, to: Int): Double {
        val lo = from.coerceIn(1, samples.size)
        val hi = to.coerceIn(lo, samples.size)
        var diff = 0.0
        var total = 0.0
        for (i in lo until hi) {
            val d = (samples[i] - samples[i - 1]).toDouble()
            diff += d * d
            total += samples[i].toDouble() * samples[i]
        }
        return if (total < 1e-12) 0.0 else diff / total
    }

    /**
     * Estimates the dominant frequency of `[from, to)` by autocorrelation over the musical range.
     *
     * Used to check that the audio really sounds the MIDI note the pattern scheduled — the only
     * reliable way to verify a pitch claim about a `.wav`.
     */
    fun estimateHz(samples: FloatArray, from: Int, to: Int, loHz: Double = 40.0, hiHz: Double = 2000.0): Double {
        val lo = from.coerceIn(0, samples.size)
        // Skip the attack and use a steady middle chunk.
        val start = (lo + (to - lo) / 8).coerceIn(0, max(0, samples.size - 1))
        val end = min(samples.size, start + (to - lo) * 3 / 4)
        if (end - start < 64) return 0.0
        val minLag = (SR / hiHz).toInt().coerceAtLeast(2)
        val maxLag = min((SR / loHz).toInt(), (end - start) / 2)
        if (maxLag <= minLag) return 0.0
        var bestLag = minLag
        var bestScore = -Double.MAX_VALUE
        for (lag in minLag..maxLag) {
            var num = 0.0
            var den = 1e-12
            for (i in start until end - lag) {
                num += samples[i].toDouble() * samples[i + lag]
                den += samples[i].toDouble() * samples[i]
            }
            val score = num / den
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        // Parabolic refinement around the best integer lag, so the estimate beats the lag grid.
        fun scoreAt(lag: Int): Double {
            if (lag < minLag || lag > maxLag) return -Double.MAX_VALUE
            var num = 0.0
            var den = 1e-12
            for (i in start until end - lag) {
                num += samples[i].toDouble() * samples[i + lag]
                den += samples[i].toDouble() * samples[i]
            }
            return num / den
        }
        val prev = scoreAt(bestLag - 1)
        val next = scoreAt(bestLag + 1)
        val refined = if (prev > -1e300 && next > -1e300) {
            val denom = prev - 2.0 * bestScore + next
            if (abs(denom) < 1e-12) bestLag.toDouble() else bestLag + 0.5 * (prev - next) / denom
        } else {
            bestLag.toDouble()
        }
        return SR / refined
    }

    /** Normalised cross-correlation of two channels at zero lag. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        var num = 0.0
        var da = 1e-12
        var db = 1e-12
        for (i in a.indices) {
            num += a[i].toDouble() * b[i]
            da += a[i].toDouble() * a[i]
            db += b[i].toDouble() * b[i]
        }
        return num / sqrt(da * db)
    }

    /** RMS of a channel. */
    fun rms(a: FloatArray): Double {
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return sqrt(s / max(1, a.size))
    }

    /** Peak of a channel. */
    fun peak(a: FloatArray): Double {
        var p = 0.0
        for (v in a) p = max(p, abs(v).toDouble())
        return p
    }

    /** The MIDI numbers a numeric pattern schedules in cycle [index], in time order. */
    fun cycleNotes(pattern: PatPattern<Double>, index: Long): List<Double> =
        pattern.events(PatArc(PatQ.of(index), PatQ.of(index + 1)))
            .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
            .map { it.value }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-sound-docs").path
        )
        outDir.mkdirs()

        SCENES.forEach { scene ->
            val audio = scene.render(scene.cycles, scene.cps)
            check(audio.frames > 0) { "${scene.name}: rendered no samples" }
            check(audio.left.size == audio.right.size) { "${scene.name}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${scene.name}: non-finite samples"
            }

            val expectedFrames = (scene.seconds * SR).toInt()
            check(abs(audio.frames - expectedFrames) <= SR / 10) {
                "${scene.name}: expected ~$expectedFrames frames, got ${audio.frames}"
            }
            val monoPeak = peak(audio.mono)
            val monoRms = rms(audio.mono)
            check(monoPeak > 0.02) { "${scene.name}: effectively silent (peak=$monoPeak)" }
            // Overlapping voices legitimately sum past 1.0 before the writer normalises (the same
            // allowance DoughDspSoundDocs makes for a resonant filter); 4.0 catches a real runaway.
            check(monoPeak <= 4.0) { "${scene.name}: runaway level (peak=$monoPeak)" }
            check(monoRms > 0.002) { "${scene.name}: RMS too low to be music (rms=$monoRms)" }
            check(monoRms < monoPeak) { "${scene.name}: RMS >= peak, which is impossible" }

            println("scene ${scene.name}: ${"%.2f".format(audio.frames.toDouble() / SR)}s, peak=${"%.3f".format(monoPeak)}, rms=${"%.4f".format(monoRms)}")
            scene.verify(scene, audio)

            val file = File(outDir, "pat-${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, SR, normalizePeak = 0.9f)

            // Assert a real WAV landed on disk, with an honest header.
            val bytes = file.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${scene.name}: missing RIFF" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${scene.name}: missing WAVE" }
            check(String(bytes, 12, 4, Charsets.US_ASCII) == "fmt ") { "${scene.name}: missing fmt chunk" }
            val channels = (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)
            check(channels == 2) { "${scene.name}: WAV declares $channels channels, not 2" }
            val declaredSr = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
            check(declaredSr == SR) { "${scene.name}: WAV declares $declaredSr Hz" }
            check(bytes.size == 44 + audio.frames * 4) {
                "${scene.name}: WAV data size mismatch: ${bytes.size} vs ${44 + audio.frames * 4}"
            }
            println("  wrote ${file.absolutePath} (${bytes.size} bytes)")
        }

        println("pat sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════════════
//  The voice. A pattern is only a schedule; this is the smallest honest thing that turns one
//  into sound. Top-level in afield.four.synth (auto-imported into every box) so a docs fence or
//  a live box can render a pattern to audio with one call.
// ═══════════════════════════════════════════════════════════════════════════════════════════

/** Oscillator shapes [patVoice] can use. `NOISE` is a deterministic hash, never `Random`. */
enum class PatWave { SINE, SAW, SQUARE, TRIANGLE, NOISE }

/**
 * A tiny deterministic synth voice: one oscillator, one exponential-decay envelope, one one-pole
 * lowpass.
 *
 * Parameters read off each event, all optional: `gain` (0..1, default 1), `pan` (0 left .. 1
 * right, default centre), `cutoff` (Hz, default open), `sustain` (fraction of the event's arc the
 * note holds for, default [sustain]), `note` (MIDI, overriding the atom).
 *
 * Pitch comes from the event's value when it parses as a number (a MIDI note), and otherwise from
 * a small drum map so that `pat("bd sd hh cp")` makes a recognisable kit: `bd` low, `sd` and `cp`
 * mid-band noise, `hh` high and short. Anything unrecognised is played at [baseHz].
 */
class PatVoiceSpec internal constructor(
    val wave: PatWave,
    val baseHz: Double,
    val decay: Double,
    val sustain: Double,
    val attack: Double
)

/** Builds a [PatVoiceSpec]. See [PatVoiceSpec] for what each event parameter does. */
fun patVoice(
    wave: PatWave = PatWave.SINE,
    baseHz: Double = 220.0,
    decay: Double = 0.08,
    sustain: Double = 1.0,
    attack: Double = 0.003
): PatVoiceSpec = PatVoiceSpec(wave, baseHz, decay.coerceAtLeast(1e-4), sustain.coerceIn(0.0, 1.0), attack.coerceAtLeast(1e-5))

/**
 * The drum map behind unpitched atoms — crude, just enough to be recognisable.
 *
 * `internal` rather than private because it is the authority the piano rolls' instrument colours
 * are checked against: [PatPianoRollGallery.ATOM_COLORS] claims to distinguish exactly the tokens
 * this function gives a voice of their own, and `synth.pat.mini` reads this map to verify it.
 */
internal fun patDrumHz(atom: String, baseHz: Double): Pair<Double, PatWave?> = when (atom.substringBefore(':')) {
    "bd", "kick" -> 62.0 to PatWave.SINE
    "sd", "snare" -> 190.0 to PatWave.NOISE
    "cp", "clap" -> 900.0 to PatWave.NOISE
    "hh", "hat" -> 6200.0 to PatWave.NOISE
    "oh" -> 4200.0 to PatWave.NOISE
    else -> baseHz to null
}

/**
 * Schedules [pattern] over [cycles] whole cycles at [cps] and renders it through [voice].
 *
 * This is the core of [PatSoundDocs]: nothing here knows anything about the operators. It
 * asks the pattern for its events, reads their parameters, and adds one voice per event.
 */
fun patRenderStereo(
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    voice: PatVoiceSpec = patVoice(),
    sampleRate: Int = PatSoundDocs.SR
): PatSoundDocs.Stereo {
    require(cycles > 0) { "cycles must be > 0" }
    require(cps > 0.0) { "cps must be > 0" }
    val secondsPerCycle = 1.0 / cps
    val frames = (cycles * secondsPerCycle * sampleRate).toInt()
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    val events = pattern.events(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    for (event in events) {
        val startSec = event.arc.start.toDouble() * secondsPerCycle
        val arcSec = (event.arc.end - event.arc.start).toDouble() * secondsPerCycle
        val sustainFrac = event.annotations["sustain"]?.toDoubleOrNull() ?: voice.sustain
        // The note rings for its sustain fraction of the arc plus the envelope's own tail, so a
        // short arc still gets a natural decay rather than a click.
        val holdSec = arcSec * sustainFrac.coerceIn(0.0, 1.0)
        val tailSec = voice.decay * 4.0
        val startFrame = (startSec * sampleRate).toInt()
        val lengthFrames = ((holdSec + tailSec) * sampleRate).toInt().coerceAtLeast(1)
        if (startFrame >= frames) continue

        val explicitNote = event.annotations["note"]?.toDoubleOrNull()
        val atomNote = event.value.trim().toDoubleOrNull()
        val drum = patDrumHz(event.value.trim(), voice.baseHz)
        val hz = when {
            explicitNote != null -> patMidiToHz(explicitNote)
            atomNote != null -> patMidiToHz(atomNote)
            else -> drum.first
        }
        val wave = if (explicitNote == null && atomNote == null) (drum.second ?: voice.wave) else voice.wave

        val gain = (event.annotations["gain"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 4.0)
        val pan = (event.annotations["pan"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        val speed = event.annotations["speed"]?.toDoubleOrNull() ?: 1.0
        val cutoffHz = event.annotations["cutoff"]?.toDoubleOrNull()

        // Per-voice deterministic noise seed and filter state — no shared mutable state, so the
        // render does not depend on the order events happen to arrive in.
        var noiseState = splitmix64(
            (event.arc.start.n.toLong() * 1_000_003L) xor (event.arc.start.d.toLong() * 7_919L) xor
                event.value.hashCode().toLong()
        )
        var lowpass = 0.0
        val alpha = if (cutoffHz == null) 1.0 else {
            val rc = 1.0 / (2.0 * PI * cutoffHz.coerceIn(20.0, sampleRate / 2.2))
            val dt = 1.0 / sampleRate
            dt / (rc + dt)
        }

        val leftGain = gain * sqrt(1.0 - pan)
        val rightGain = gain * sqrt(pan)
        val freq = hz * speed
        val phaseStep = freq / sampleRate

        for (i in 0 until lengthFrames) {
            val frame = startFrame + i
            if (frame >= frames) break
            val t = i.toDouble() / sampleRate
            val env = patVoiceEnvelope(t, holdSec, voice.attack, voice.decay)
            if (env <= 1e-6 && t > holdSec) break
            val phase = (phaseStep * i) % 1.0
            val raw = when (wave) {
                PatWave.SINE -> sin(2.0 * PI * phase)
                PatWave.SAW -> 2.0 * phase - 1.0
                PatWave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
                PatWave.TRIANGLE -> if (phase < 0.5) 4.0 * phase - 1.0 else 3.0 - 4.0 * phase
                PatWave.NOISE -> {
                    noiseState = splitmix64(noiseState)
                    toUnitDouble(noiseState) * 2.0 - 1.0
                }
            }
            lowpass += alpha * (raw - lowpass)
            val sample = lowpass * env
            left[frame] += (sample * leftGain).toFloat()
            right[frame] += (sample * rightGain).toFloat()
        }
    }

    return PatSoundDocs.Stereo(left, right)
}

/**
 * Renders [pattern] through [voice] and puts an audio player in this box's output.
 *
 * The one-liner behind the loadable demo: `showPatAudio(pat("bd sd hh sd") with gain(sine()))`
 * plays it. The rendered [PatSoundDocs.Stereo] is returned so a box can also plot or measure it.
 */
fun afield.Box.showPatAudio(
    pattern: PatPattern<String>,
    cycles: Int = 4,
    cps: Double = 2.0,
    voice: PatVoiceSpec = patVoice(),
    channel: String = "main"
): PatSoundDocs.Stereo {
    val audio = patRenderStereo(pattern, cycles = cycles, cps = cps, voice = voice)
    display(
        channel,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(
            audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f
        )
    )
    return audio
}

/** Renders a named [PatSoundDocs] scene into this box's output as an audio player. */
fun afield.Box.showPatSound(name: String, channel: String = "main"): PatSoundDocs.Stereo {
    val scene: PatSoundDocs.Scene = PatSoundDocs.SCENES.firstOrNull { scene -> scene.name == name }
        // `kotlin.error`, not `error`: inside a Box extension the unqualified name resolves to
        // Box's own `error(...)`, which returns Unit, and the elvis then widens the whole thing to Any.
        ?: kotlin.error("unknown PatSoundDocs scene '$name'; known: ${PatSoundDocs.SCENES.map { it.name }}")
    val audio = scene.render(scene.cycles, scene.cps)
    display(
        channel,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(
            audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f
        )
    )
    return audio
}

/** MIDI note number to Hz, A4 = 69 = 440 Hz. */
fun patMidiToHz(midi: Double): Double = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0)

/** Attack ramp, hold at full, then exponential decay — the whole envelope, in one expression. */
private fun patVoiceEnvelope(t: Double, holdSec: Double, attack: Double, decay: Double): Double = when {
    t < attack -> t / attack
    t <= holdSec -> 1.0
    else -> exp(-(t - holdSec) / decay)
}

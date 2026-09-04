package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
FIELD_TEST
id: synth.faust.poly
runner: jvm-main
entrypoint: afield.four.synth.FaustPolySmokeTest
tags: [smoke, synth, faust, pattern, polyphony, audio, wasm, cpu]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "Faust polyphony tests passed"
*/
/**
 * A pattern playing a Faust program, measured in samples and in Hz.
 *
 * `synth.faust.smoke` guards the contract with the vendored compiler. This guards the two claims that make
 * [FaustVoiceBank] worth having, and both are the kind of claim that a "it compiles and makes a noise" test
 * would pass while being wrong:
 *
 *  1. **The notes land where they were notated, on the sample.** A 128-frame block at 48kHz is 2.67ms, and a
 *     scheduler that writes parameters once per block quantises every onset to that grid. So the pattern here
 *     is seven notes to a cycle — onsets at 6857, 13714, 20571 … frames, none of them on a
 *     block boundary — and what is asserted is the **jitter**: the spread of the onset error around its
 *     median. A constant offset is the voice's attack and its filter's group delay, which is physics; a
 *     varying offset is the scheduler, which is the thing under test. The block-quantised alternative is
 *     computed alongside it, so the assertion is against a number rather than against a feeling.
 *
 *  2. **Three notes really sound at once.** Counting onsets proves scheduling; it does not prove polyphony,
 *     because a monophonic path that steals its own voice produces exactly the same number of attacks. The
 *     assertion that cannot be faked is spectral: a chord's sustained region contains **three** fundamentals
 *     in a band that can only hold fundamentals, and the identical render at `voices = 1` contains **one**.
 *
 * ### And before believing any of it, the signal is checked for having onsets at all
 *
 * This was got wrong in this package on 2026-08-12, one file over: a render at `sustain = 1.0` is a
 * continuous tone, and an onset detector pointed at one finds envelope ripple — 22 "onsets" in a render of
 * 10 notes. So [checkTheDetector] runs first, on a single one-second note, and requires the detector to
 * report exactly **1**; and every timing measurement asserts that the silences between the notes are really
 * silent before it counts anything.
 *
 * Set `-Dafield.faustPoly.out=<dir>` to keep the WAV and the waveform PNG; the default is a temp directory,
 * so a CI run writes nothing into the repository.
 */
object FaustPolySmokeTest {

    private const val SR = 48_000
    private const val BLOCK = 128

    /**
     * The instrument. Four declared controls, and note the order Faust lists them in: `cutoff`, `freq`,
     * `gain`, `gate` — **alphabetical**, so control index 0 is `cutoff`. A voice allocator that wrote pitch
     * to index 0 would sweep a filter and never change a note, which is why [FaustPolyControls] looks up by
     * label and why [checkControlDiscovery] asserts that `freq` is not first.
     */
    private val POLY_VOICE = """
        import("stdfaust.lib");
        cutoff = hslider("cutoff [unit:Hz]", 4000, 60, 12000, 1);
        freq   = hslider("freq [unit:Hz]", 220, 40, 4000, 0.001);
        gain   = hslider("gain", 0.25, 0, 1, 0.001);
        gate   = button("gate");
        level  = hslider("level", 1, 0, 1, 0.001);
        env = en.adsr(0.003, 0.04, 0.7, 0.02, gate);
        process = os.sawtooth(freq) : fi.lowpass(3, cutoff) * env * gain * level <: _,_;
    """.trimIndent()

    /** The same voice with no `gate`: what a person actually writes first, and what has to be refused. */
    private val NO_GATE = """
        import("stdfaust.lib");
        freq = hslider("freq [unit:Hz]", 220, 40, 4000, 0.001);
        process = os.sawtooth(freq) * 0.2 <: _,_;
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val ok = try {
            runTests(outDir(args))
        } finally {
            FaustEngine.shutdown()
        }
        check(ok) { "Faust polyphony tests failed" }
        println("Faust polyphony tests passed")
    }

    private fun outDir(args: Array<String>): File {
        System.getProperty("afield.faustPoly.out")?.takeIf { it.isNotBlank() }?.let {
            return File(it).also { d -> d.mkdirs() }
        }
        args.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return File(it).also { d -> d.mkdirs() } }
        return Files.createTempDirectory("faust-poly-").toFile()
    }

    fun runTests(outDir: File): Boolean {
        val report = Reporter()
        println("out dir ${outDir.absolutePath}")

        println("control discovery")
        val program = checkControlDiscovery(report) ?: return false

        println("the detector itself")
        checkTheDetector(report, program)

        println("parameter writes land on the named control")
        checkParamTargeting(report, program)

        println("timing, in samples")
        checkTiming(report, program, outDir)

        println("pitch")
        checkPitch(report, program)

        println("polyphony against a monophonic control")
        checkPolyphony(report, program, outDir)

        println("a patterned parameter, not just notes")
        checkAutomation(report, program, outDir)

        println("voice allocation")
        checkAllocation(report, program)

        println("does a steal click?")
        checkStealing(report, program, outDir)

        println("is render allocation-free?")
        checkAudioThreadAllocation(report, program)

        println("cost of a voice")
        checkCost(report, program)

        report.summarise()
        return report.failures == 0
    }

    // ── the declared interface ──────────────────────────────────────────────────────────────────

    private fun checkControlDiscovery(report: Reporter): FaustProgram? {
        val program = try {
            FaustEngine.compile("polyvoice", POLY_VOICE)
        } catch (e: FaustCompileError) {
            report.check("the poly voice compiles", false) { "${e.faustMessage} (line ${e.line})" }
            return null
        }

        val writable = program.ui.writable
        report.check("five writable controls", writable.size == 5) { writable.joinToString { it.label } }
        // The reason label lookup is mandatory rather than tidy.
        report.check(
            "Faust lists them alphabetically, so index 0 is cutoff not freq",
            writable.first().label == "cutoff"
        ) { writable.joinToString { it.label } }

        val controls = FaustPolyControls.of(program)
        report.check("freq found by label", controls.freq.label == "freq") { controls.freq.label }
        report.check("gate found by label", controls.gate.label == "gate") { controls.gate.label }
        report.check("gain found by label", controls.gain?.label == "gain") { "${controls.gain?.label}" }
        report.check(
            "freq is not the first control, so writing to index 0 would have moved cutoff",
            controls.freq.index != writable.first().index
        ) { "freq=${controls.freq.index}, first=${writable.first().index}" }

        // A program with no gate must be refused with something a person can act on.
        val noGate = FaustEngine.compile("nogate", NO_GATE)
        val refusal = runCatching { FaustPolyControls.of(noGate) }.exceptionOrNull()
        report.check("a program with no gate is refused", refusal != null) { "it was accepted" }
        val message = refusal?.message ?: ""
        report.check("the refusal names what is missing", message.contains("`gate`")) { message }
        report.check("the refusal names what the program did declare", message.contains("freq (hslider)")) { message }
        report.check("the refusal shows the line that would fix it", message.contains("button(\"gate\")")) { message }
        report.check("the refusal does not blame the wrong thing", !message.contains("`freq`")) { message }
        println("  refusal reads: ${message.replace("\n", " ⏎ ").take(240)}")

        return program
    }

    private fun checkParamTargeting(report: Reporter, program: FaustProgram) {
        val bank = FaustVoiceBank(program, SR, voices = 2)
        try {
            val cutoffBefore = bank.paramValue(0, program.ui.writable.first { it.label == "cutoff" })
            bank.play(hz = 523.25, seconds = 0.2)
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            bank.render(l, r, BLOCK)

            val freqNow = bank.paramValue(0, bank.controls.freq)
            report.check("the note's pitch was written to freq", abs(freqNow - 523.25f) < 0.01f) { "$freqNow" }
            report.check("gate is up on the voice that took it", bank.paramValue(0, bank.controls.gate) == 1f) {
                "${bank.paramValue(0, bank.controls.gate)}"
            }
            report.check(
                "cutoff was not touched",
                bank.paramValue(0, program.ui.writable.first { it.label == "cutoff" }) == cutoffBefore
            ) { "cutoff moved from $cutoffBefore" }
            report.check("the other voice is still idle", bank.paramValue(1, bank.controls.gate) == 0f) {
                "${bank.paramValue(1, bank.controls.gate)}"
            }
        } finally {
            bank.close()
        }
    }

    // ── the detector, before anything is measured with it ───────────────────────────────────────

    /**
     * One note, one second long. The detector must say **1**.
     *
     * This is the named trap, run as a test rather than remembered as a comment: an onset detector will
     * happily report twenty attacks in a continuous tone if its thresholds are wrong, and every timing
     * number below is worthless if it does.
     */
    private fun checkTheDetector(report: Reporter, program: FaustProgram) {
        val bank = FaustVoiceBank(program, SR, voices = 4)
        val frames = SR
        val mono = FloatArray(frames)
        try {
            bank.schedule(FaustNote(0, (0.9 * SR).roundToLong(), 440.0, 0.25, "a"))
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var done = 0
            while (done < frames) {
                val n = min(BLOCK, frames - done)
                bank.render(l, r, n)
                for (i in 0 until n) mono[done + i] = (l[i] + r[i]) * 0.5f
                done += n
            }
        } finally {
            bank.close()
        }

        var peak = 0f
        for (v in mono) peak = max(peak, abs(v))
        report.check("one long note is audible", peak > 0.01f) { "peak=$peak" }

        val onsets = faustOnsets(mono, SR)
        report.check("a one-second tone has exactly one onset, not ripple", onsets.size == 1) {
            "found ${onsets.size}: ${onsets.take(24)}"
        }
        report.check("and it is at the start", onsets.firstOrNull()?.let { it < 64 } == true) { "${onsets.firstOrNull()}" }
        println("  1s tone: ${onsets.size} onset(s) at ${onsets.take(4)}, peak ${"%.3f".format(peak)}")
    }

    // ── timing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Seven notes to a cycle, so no onset falls on a 128-frame block boundary.
     *
     * `48000 / 7 = 6857.14`, and `6857 % 128 = 105`. If parameter writes were quantised to the block, every
     * onset but the first would be up to 127 frames late; the number is computed below and printed next to
     * what actually happened.
     */
    private fun checkTiming(report: Reporter, program: FaustProgram, outDir: File) {
        val cycles = 2
        val cps = 1.0
        val pattern = pat("c4 e4 g4 a4 c5 d5 e5")
        // gain = null: the level the program declared for its own `gain` slider, which is the level its
        // author chose. 1.0 would be full scale for one voice and would clip the moment two overlapped.
        val render = faustPatternRender(
            program, pattern, cycles = cycles, cps = cps, voices = 8, sampleRate = SR,
            sustain = 0.5, gain = null, blockFrames = BLOCK, tailSeconds = 0.2
        )

        report.check("14 notes were scheduled", render.notes.size == 14) { "${render.notes.size}" }
        report.check("no event was unpitched", render.unpitched == 0) { "${render.unpitched}" }
        report.check("nothing was stolen with 8 voices", render.steals == 0L) { "${render.steals}" }
        report.check("it is audible", render.peak > 0.02f) { "peak=${render.peak}" }
        report.check("it does not clip", render.peak < 1.0f) { "peak=${render.peak}" }
        val mono = render.mono()
        report.check("nothing is NaN", mono.none { it.isNaN() }) { "NaN present" }

        // Sub-block scheduling is only under test if the onsets are not block-aligned in the first place.
        val notated = render.notes.map { it.startFrame }.sorted()
        // Two of the fourteen land on a boundary by arithmetic — the two cycle starts, since a cycle at
        // cps=1 is exactly 375 blocks. The other twelve are the ones that matter.
        val offGrid = notated.count { it % BLOCK != 0L }
        report.check("12 of the 14 onsets are off the block grid", offGrid == 12) {
            "$offGrid of ${notated.size}; ${notated.map { it % BLOCK }}"
        }

        // The signal has to have onsets before anything counts them. sustain=0.5 means the gate falls for
        // half of every arc and the 20ms release finishes inside that.
        val gaps = faustSilentGaps(mono, SR, threshold = 1e-4, minMs = 3.0)
        report.check("there is real silence between the notes", gaps.size >= 13) {
            "${gaps.size} gaps; longest ${gaps.maxOfOrNull { it.last - it.first } ?: 0} frames"
        }
        println("  ${gaps.size} silent gaps, longest ${gaps.maxOfOrNull { it.last - it.first + 1 } ?: 0} frames")

        val onsets = faustOnsets(mono, SR)
        report.check("one onset per notated note", onsets.size == notated.size) {
            "${onsets.size} onsets for ${notated.size} notes: ${onsets.take(20)}"
        }
        if (onsets.size != notated.size) return

        val errors = onsets.mapIndexed { i, o -> o - notated[i] }
        val median = errors.sorted()[errors.size / 2]
        val jitter = errors.maxOf { abs(it - median) }
        val worstAbsolute = errors.maxOf { abs(it) }
        // What a block-granularity parameter path would have produced: every onset dragged forward to the
        // next block boundary.
        val quantisedWorst = notated.maxOf { n ->
            val q = ((n + BLOCK - 1) / BLOCK) * BLOCK
            abs(q - n)
        }
        println(
            "  onset error: median ${median} frames (${"%.3f".format(median * 1000.0 / SR)}ms), " +
                "jitter ±$jitter frames, worst |error| $worstAbsolute; " +
                "block-quantised would be up to $quantisedWorst"
        )
        println("  per-note error: $errors")

        // The scheduler's own accuracy. Measured on 2026-08-13: median 1 frame, jitter ±7, worst 8 — and the
        // whole of that spread is the *detector*, not the scheduler. `os.sawtooth`'s phasor runs whether the
        // gate is up or not, so a reused voice's waveform can be near its zero crossing at the instant the
        // envelope opens, and `|x|` then takes a few more samples to rise above the quiet threshold. The
        // floors are tight rather than generous because this render is deterministic: integer frames,
        // float32 WebAssembly arithmetic, no wall clock anywhere in the path.
        report.check("onset jitter is sub-block", jitter <= 10) { "$jitter frames" }
        report.check("the median offset is a couple of samples, not a block", abs(median) <= 4) { "$median" }
        report.check("worst error is 12 frames or better", worstAbsolute <= 12) { "$worstAbsolute" }
        report.check(
            "and the block-quantised alternative really would have been worse",
            quantisedWorst >= 60 && quantisedWorst > jitter * 8
        ) { "quantised $quantisedWorst vs jitter $jitter" }

        // ── what the residual actually is ──
        //
        // 8 frames worst case is 167us, which is fine, but something is rounding and it is worth naming.
        // It is not the scheduler. During a gap this program's output is *exactly* 0f — the envelope reaches
        // zero and multiplies the oscillator away — so the first non-zero sample of each note is an exact
        // fact about the render, with no threshold in it. If that sample is the notated frame, the scheduler
        // is sample-exact and the whole of the 0..8 spread is the onset detector waiting for the attack ramp
        // to clear its amplitude threshold, which depends on where the free-running phasor happens to be.
        var worstFirstAudible = 0
        var exact = 0
        // Note 0 starts at frame 0, so there is no preceding sample to require silence of; the other 13 are
        // the checkable ones.
        val checkable = notated.count { it >= 1L && it < mono.size }
        for (n in notated) {
            val i = n.toInt()
            if (i < 1 || i >= mono.size) continue
            // Walk forward from the notated frame to the first sample that is not exactly zero.
            var j = i
            while (j < mono.size && mono[j] == 0f) j++
            if (j == i) exact++
            worstFirstAudible = max(worstFirstAudible, j - i)
            if (mono[i - 1] != 0f) {
                report.check("the frame before note at $n is silent", false) { "${mono[i - 1]}" }
            }
        }
        println(
            "  residual: the first non-zero sample IS the notated frame for $exact of $checkable notes " +
                "(worst $worstFirstAudible frames). So the scheduler is sample-EXACT, and the whole " +
                "0..$worstAbsolute spread above is the onset detector waiting for the attack ramp to clear " +
                "its amplitude threshold — which depends on where the free-running phasor was."
        )
        report.check(
            "every note's first non-zero sample IS its notated frame",
            exact == checkable && worstFirstAudible == 0
        ) { "$exact of $checkable, worst $worstFirstAudible" }

        val wav = File(outDir, "faust-poly-timing.wav")
        WavFloatArrayWriter.writeStereo16(wav, render.left, render.right, SR, normalizePeak = 0.9f)
        val bytes = wav.readBytes()
        report.check("a real WAV landed on disk", String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "no RIFF" }
        report.check("the WAV is the right length", bytes.size == 44 + render.frames * 4) {
            "${bytes.size} vs ${44 + render.frames * 4}"
        }
        writeWaveformPng(
            File(outDir, "faust-poly-timing.png"), mono, notated.map { it.toInt() }, onsets
        )
        println("  wrote ${wav.absolutePath} and faust-poly-timing.png")
    }

    // ── pitch ──────────────────────────────────────────────────────────────────────────────────

    private fun checkPitch(report: Reporter, program: FaustProgram) {
        val pattern = pat("c4 e4 g4 c5")
        val render = faustPatternRender(
            program, pattern, cycles = 1, cps = 1.0, voices = 8, sampleRate = SR,
            sustain = 0.5, gain = null, blockFrames = BLOCK, tailSeconds = 0.2
        )
        val mono = render.mono()
        // c4/e4/g4/c5 in the pattern language's octave numbering: c5 is middle C = MIDI 60, so c4 is 48.
        val expected = listOf(48, 52, 55, 60).map { patMidiToHz(it.toDouble()) }
        report.check("four notes", render.notes.size == 4) { "${render.notes.size}" }

        var worstCents = 0.0
        for ((i, note) in render.notes.withIndex()) {
            // Measure inside the sustain, clear of the attack and of the release.
            val from = (note.startFrame + 0.02 * SR).toInt()
            val count = ((note.lengthFrames - 0.03 * SR).toInt()).coerceAtMost(mono.size - from - 1)
            // minHz=100 rather than the default 40: the chord's lowest note is 130Hz, and a lag search that
            // reaches down to 40Hz spends a third of the window on lags that cannot be the answer, which
            // costs accuracy at the top of the range (measured: 8.9 cents at 100Hz, 2.8 at 40Hz).
            val hz = faustFundamentalHz(mono, from, count, SR, minHz = 100.0)
            val cents = if (hz.isNaN()) Double.NaN else 1200.0 * kotlin.math.ln(hz / expected[i]) / kotlin.math.ln(2.0)
            println(
                "  note $i '${note.label}': notated ${"%.2f".format(expected[i])}Hz, " +
                    "measured ${"%.2f".format(hz)}Hz (${"%.1f".format(cents)} cents)"
            )
            report.check("note $i has a measurable pitch", !hz.isNaN()) { "NaN" }
            if (!hz.isNaN()) {
                report.check("note $i is at its notated pitch", abs(cents) < 15.0) { "${"%.1f".format(cents)} cents" }
                worstCents = max(worstCents, abs(cents))
                report.check("note $i asked for the right freq", abs(note.hz - expected[i]) < 1e-6) {
                    "${note.hz} vs ${expected[i]}"
                }
            }
        }
        println("  worst pitch error ${"%.1f".format(worstCents)} cents over ${render.notes.size} notes")
        report.check("every pitch within 10 cents", worstCents < 10.0) { "${"%.1f".format(worstCents)}" }
    }

    // ── polyphony, against a monophonic control ────────────────────────────────────────────────

    /**
     * The assertion that proves the feature rather than the plumbing.
     *
     * `[c4,e4,g4]` is one chord: three events on the same arc, so three notes on the same frame. Their
     * fundamentals are 130.81, 164.81 and 196.00 Hz. The band 100–230 Hz can hold nothing but fundamentals —
     * the lowest second harmonic in the chord is 261.6 Hz — so counting peaks in that band counts notes
     * sounding, and a single voice cannot put three there however it is driven.
     */
    private fun checkPolyphony(report: Reporter, program: FaustProgram, outDir: File) {
        val chord = pat("[c4,e4,g4]")
        val expected = listOf(48, 52, 55).map { patMidiToHz(it.toDouble()) }
        println("  chord fundamentals: ${expected.map { "%.2f".format(it) }}")

        val poly = faustPatternRender(
            program, chord, cycles = 1, cps = 1.0, voices = 4, sampleRate = SR,
            sustain = 0.9, gain = 0.2, blockFrames = BLOCK, tailSeconds = 0.2
        )
        report.check("three notes on the same frame", poly.notes.size == 3) { "${poly.notes.size}" }
        report.check(
            "all three start on the same sample",
            poly.notes.map { it.startFrame }.distinct().size == 1
        ) { poly.notes.map { it.startFrame }.toString() }
        report.check("three voices sounded at once", poly.peakConcurrency == 3) { "${poly.peakConcurrency}" }
        report.check("nothing was stolen", poly.steals == 0L) { "${poly.steals}" }
        report.check("no tail was cut", poly.tailCuts == 0L) { "${poly.tailCuts}" }
        report.check("the chord does not clip", poly.peak < 1.0f) { "peak=${poly.peak}" }

        val mono = faustPatternRender(
            program, chord, cycles = 1, cps = 1.0, voices = 1, sampleRate = SR,
            sustain = 0.9, gain = 0.2, blockFrames = BLOCK, tailSeconds = 0.2
        )
        report.check("the monophonic control stole twice", mono.steals == 2L) { "${mono.steals}" }
        report.check("and only ever had one voice up", mono.peakConcurrency == 1) { "${mono.peakConcurrency}" }

        val from = (0.12 * SR).toInt()
        val polyPeaks = faustSpectrumPeaks(
            poly.mono(), from, 32768, SR, minHz = 100.0, maxHz = 230.0, relThreshold = 0.15
        )
        val monoPeaks = faustSpectrumPeaks(
            mono.mono(), from, 32768, SR, minHz = 100.0, maxHz = 230.0, relThreshold = 0.15
        )
        println("  100-230Hz peaks — polyphonic: $polyPeaks")
        println("  100-230Hz peaks — monophonic: $monoPeaks")

        report.check("the chord's spectrum holds exactly three fundamentals", polyPeaks.size == 3) {
            "$polyPeaks"
        }
        report.check("one voice holds exactly one", monoPeaks.size == 1) { "$monoPeaks" }

        if (polyPeaks.size == 3) {
            val found = polyPeaks.map { it.hz }.sorted()
            var worst = 0.0
            for (i in expected.indices) {
                worst = max(worst, abs(found[i] - expected[i]) / expected[i])
            }
            println("  worst chord partial error ${"%.3f".format(worst * 100)}%")
            report.check("each partial is its notated pitch", worst < 0.01) { "${"%.3f".format(worst * 100)}%" }
            // Every partial actually present, not one loud one and two artefacts.
            val quietest = polyPeaks.minOf { it.magnitude }
            val loudest = polyPeaks.maxOf { it.magnitude }
            report.check("all three are comparable in level", quietest > loudest * 0.25) {
                "quietest $quietest, loudest $loudest"
            }
        }
        if (monoPeaks.size == 1) {
            // The surviving voice is the last note struck — the stealing policy, observable.
            report.check(
                "the monophonic survivor is the last note of the chord",
                abs(monoPeaks[0].hz - expected.last()) / expected.last() < 0.01
            ) { "${monoPeaks[0].hz} vs ${expected.last()}" }
        }

        WavFloatArrayWriter.writeStereo16(
            File(outDir, "faust-poly-chord.wav"), poly.left, poly.right, SR, normalizePeak = 0.9f
        )
        WavFloatArrayWriter.writeStereo16(
            File(outDir, "faust-poly-chord-mono.wav"), mono.left, mono.right, SR, normalizePeak = 0.9f
        )
        writeSpectrumPng(
            File(outDir, "faust-poly-chord-spectrum.png"),
            poly.mono(), mono.mono(), from, expected
        )
    }

    // ── a patterned parameter ──────────────────────────────────────────────────────────────────

    /**
     * A `Pat` pattern of *numbers* driving a shared control, on the same timeline as the notes.
     *
     * The pattern language is a rhythm of anything, not only of notes, so a parameter change goes through
     * the same event timeline and gets the same accuracy. Two halves, because they answer different
     * questions and the first attempt conflated them:
     *
     *  1. **A patterned `cutoff` really changes the sound** — measured as the energy of the signal's first
     *     difference, which for a lowpassed sawtooth rises monotonically with the cutoff.
     *  2. **A patterned control lands on the sample** — and *not* measured on the cutoff, which was the
     *     first attempt and was wrong: a filter's response to a cutoff step is not instantaneous, and a
     *     short-window high-frequency estimate on a 110Hz sawtooth is dominated by the once-per-period
     *     discontinuity, so the "detected" step came out 3909 frames early. Asking the physics for
     *     64-sample localisation of a filter change is asking for something it does not have. So the
     *     localisation is done on `level`, whose effect on the samples *is* instantaneous, with the same
     *     onset detector the notes use.
     */
    private fun checkAutomation(report: Reporter, program: FaustProgram, outDir: File) {
        val cutoff = program.ui.writable.first { it.label == "cutoff" }
        val level = program.ui.writable.first { it.label == "level" }

        fun renderWith(automation: List<FaustParamChange>, frames: Int): Pair<FloatArray, Long> {
            val bank = FaustVoiceBank(program, SR, voices = 2)
            val mono = FloatArray(frames)
            try {
                bank.schedule(FaustNote(0, (frames * 0.95).roundToLong(), patMidiToHz(45.0), 0.25, "a"))
                bank.automate(automation)
                val l = FloatArray(BLOCK)
                val r = FloatArray(BLOCK)
                var done = 0
                while (done < frames) {
                    val n = min(BLOCK, frames - done)
                    bank.render(l, r, n)
                    for (i in 0 until n) mono[done + i] = (l[i] + r[i]) * 0.5f
                    done += n
                }
                return mono to bank.automationApplied
            } finally {
                bank.close()
            }
        }

        // ── 1. a patterned cutoff changes the sound ──
        val sweep = faustParamAutomation(pat("300 6000").nums(), cutoff, cycles = 1, cps = 1.0, sampleRate = SR)
        report.check("two cutoff steps in a cycle", sweep.size == 2) { "$sweep" }
        val step = sweep[1].frame
        report.check("the cutoff step is 24000 frames in", step == 24_000L) { "$step" }
        val (swept, sweepApplied) = renderWith(sweep, SR)
        report.check("both cutoff changes were applied", sweepApplied == 2L) { "$sweepApplied" }

        fun hf(x: FloatArray, from: Int, count: Int): Double {
            var sum = 0.0
            for (i in from until from + count) {
                val d = (x[i] - x[i - 1]).toDouble()
                sum += d * d
            }
            return sum / count
        }

        val before = hf(swept, step.toInt() - 2000, 1500)
        val after = hf(swept, step.toInt() + 500, 1500)
        println(
            "  HF energy before the cutoff step ${"%.3e".format(before)}, after ${"%.3e".format(after)} " +
                "(${"%.1f".format(after / before)}x)"
        )
        report.check("opening the filter really changes the sound", after > before * 15.0) {
            "${"%.1f".format(after / before)}x"
        }

        // ── 2. and a patterned control lands on the sample ──
        // Seven steps, so the change is off the block grid: 3/7 of 48000 is 20571, and 20571 % 128 = 91, so
        // a write quantised to the next block boundary would be 37 frames late.
        val gateOpen = faustParamAutomation(
            pat("0 0 0 1 1 1 1").nums(), level, cycles = 1, cps = 1.0, sampleRate = SR
        )
        report.check("seven level steps", gateOpen.size == 7) { "${gateOpen.size}" }
        val rise = gateOpen.first { it.value > 0.5 }.frame
        report.check("the rise is at 20571", rise == 20_571L) { "$rise" }
        val remainder = rise % BLOCK
        report.check("which is 91 frames into a block", remainder == 91L) { "$remainder" }

        val (gated, gateApplied) = renderWith(gateOpen, SR)
        report.check("every level step was applied", gateApplied == 7L) { "$gateApplied" }

        val onsets = faustOnsets(gated, SR)
        report.check("level 0 then 1 gives exactly one onset", onsets.size == 1) { "${onsets.size}: $onsets" }
        val detected = onsets.firstOrNull() ?: -1
        val error = if (detected < 0) Int.MAX_VALUE else abs(detected - rise.toInt())
        val quantised = (BLOCK - remainder).toInt()
        println("  level rise notated at $rise, detected at $detected (error $error frames); " +
            "quantised to the block it would have been $quantised late")
        report.check("the parameter change lands within 8 samples", error <= 8) { "$error frames" }
        report.check(
            "which a block-quantised write could not have managed",
            error * 4 < quantised
        ) { "$error vs $quantised" }

        WavFloatArrayWriter.writeStereo16(
            File(outDir, "faust-poly-cutoff.wav"), swept, swept, SR, normalizePeak = 0.9f
        )
        writeWaveformPng(File(outDir, "faust-poly-cutoff.png"), swept, listOf(step.toInt()), listOf())
        writeWaveformPng(File(outDir, "faust-poly-level.png"), gated, listOf(rise.toInt()), onsets)
    }

    // ── allocation ─────────────────────────────────────────────────────────────────────────────

    private fun checkAllocation(report: Reporter, program: FaustProgram) {
        // Four notes at once on three voices: one must be stolen, and none may be dropped.
        val bank = FaustVoiceBank(program, SR, voices = 3)
        try {
            bank.schedule(
                listOf(48, 52, 55, 60).map {
                    FaustNote(0, (0.4 * SR).roundToLong(), patMidiToHz(it.toDouble()), 0.6, "$it")
                }
            )
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            repeat(40) { bank.render(l, r, BLOCK) }
            report.check("every note was played, none dropped", bank.notesPlayed == 4L) { "${bank.notesPlayed}" }
            report.check("exactly one steal on three voices", bank.steals == 1L) { "${bank.steals}" }
            report.check("peak concurrency is the voice count", bank.peakConcurrency == 3) {
                "${bank.peakConcurrency}"
            }
            report.check("nothing was left pending", bank.pendingCount() == 0) { "${bank.pendingCount()}" }
        } finally {
            bank.close()
        }

        // A finished voice is reused rather than stolen: eight consecutive short notes on two voices, with
        // gaps longer than the release, must cost nothing.
        val serial = FaustVoiceBank(program, SR, voices = 2)
        try {
            val step = (0.2 * SR).roundToLong()
            serial.schedule(
                (0 until 8).map {
                    FaustNote(it * step, it * step + step / 2, patMidiToHz(60.0), 0.6, "c")
                }
            )
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var done = 0L
            val total = step * 9
            while (done < total) {
                serial.render(l, r, BLOCK)
                done += BLOCK
            }
            report.check("eight serial notes played", serial.notesPlayed == 8L) { "${serial.notesPlayed}" }
            report.check("a finished voice is reused, not stolen", serial.steals == 0L) { "${serial.steals}" }
            report.check("and no tail was cut either", serial.tailCuts == 0L) { "${serial.tailCuts}" }
            report.check("only one voice was ever needed", serial.peakConcurrency == 1) {
                "${serial.peakConcurrency}"
            }
        } finally {
            serial.close()
        }
    }

    // ── does a steal click? ────────────────────────────────────────────────────────────────────

    /**
     * The classic defect of every polyphonic synth, measured rather than assumed.
     *
     * When a sounding voice is stolen its gate drops mid-cycle, and if the envelope collapses in one sample
     * the waveform takes a step discontinuity — an audible click. The statistic is the maximum
     * sample-to-sample delta, `max |x[i] - x[i-1]|`, in a window around the steal against the same statistic
     * in a steal-free passage of the same music.
     *
     * The second half is the more interesting one: a stolen note must also **attack**. Writing `gate = 1`
     * to a voice whose gate is already up is a no-op for a Faust envelope, which triggers on a rising edge —
     * so the first version of this stole silently and correctly and produced no note. The envelope's local
     * peak in the milliseconds after a steal is what proves it retriggered.
     */
    private fun checkStealing(report: Reporter, program: FaustProgram, outDir: File) {
        // Four notes on one voice, so notes 2, 3 and 4 all steal. Long notes, so the steals land well inside
        // a sustained tone where a discontinuity would be unmistakable.
        val stealFrames = listOf(0L, 12_000L, 24_000L, 36_000L)
        val frames = SR

        fun renderSteals(releaseFrames: Long): FloatArray {
            val bank = FaustVoiceBank(program, SR, voices = 1, stealReleaseFrames = releaseFrames)
            val out = FloatArray(frames)
            try {
                bank.schedule(
                    stealFrames.mapIndexed { i, at ->
                        FaustNote(at, (0.95 * SR).roundToLong(), patMidiToHz(48.0 + i * 4), 0.25, "$i")
                    }
                )
                val l = FloatArray(BLOCK)
                val r = FloatArray(BLOCK)
                var done = 0
                while (done < frames) {
                    val n = min(BLOCK, frames - done)
                    bank.render(l, r, n)
                    for (i in 0 until n) out[done + i] = (l[i] + r[i]) * 0.5f
                    done += n
                }
                report.check(
                    "three of the four notes were stolen (release=$releaseFrames)", bank.steals == 3L
                ) { "${bank.steals}" }
                report.check(
                    "all four were played (release=$releaseFrames)", bank.notesPlayed == 4L
                ) { "${bank.notesPlayed}" }
                return out
            } finally {
                bank.close()
            }
        }

        val stolen = renderSteals(FaustVoiceBank.RETRIGGER_FRAMES)
        // The program's release is 0.02s; 30ms is comfortably longer, so the envelope reaches zero before the
        // incoming note raises the gate again.
        val gentle = renderSteals((0.03 * SR).roundToLong())

        fun maxDelta(x: FloatArray, from: Int, count: Int): Float {
            var worst = 0f
            for (i in max(1, from) until min(x.size, from + count)) {
                worst = max(worst, abs(x[i] - x[i - 1]))
            }
            return worst
        }

        fun localPeak(x: FloatArray, from: Int, count: Int): Float {
            var p = 0f
            for (i in max(0, from) until min(x.size, from + count)) p = max(p, abs(x[i]))
            return p
        }

        // The control has to be like-for-like, and the first attempt was not: comparing the window at a
        // steal against a window during the *previous* note compares different pitches at different
        // envelope levels, and a louder, higher sawtooth has a bigger wrap discontinuity for reasons that
        // have nothing to do with stealing. That version read 1.90x and meant nothing. So the reference is
        // the same note, 60ms later, once its attack has settled — same pitch, same amplitude — and the
        // statistic is the delta normalised by the local peak, which is scale-free.
        fun worstStealRatio(x: FloatArray, label: String): Float {
            var worst = 0f
            for (at in stealFrames.drop(1)) {
                val i = at.toInt()
                val atSteal = maxDelta(x, i - 4, 64) / max(1e-9f, localPeak(x, i - 4, 64))
                val settled = maxDelta(x, i + 3000, 1000) / max(1e-9f, localPeak(x, i + 3000, 1000))
                worst = max(worst, atSteal / settled)
            }
            println("  $label: worst normalised step across a steal ${"%.2f".format(worst)}x the settled one")
            return worst
        }

        // It does click at the default, and the number says why: a normalised step of ~1.0 means the largest
        // single-sample jump is as big as the whole local peak, which is not a slope, it is the outgoing
        // note's amplitude disappearing in one sample. Faust's `en.adsr` *resets to zero* on a trigger rather
        // than continuing from the level it was at, so a one-sample gate dip followed by a rising edge drops
        // the envelope to 0 before the attack starts. That is a genuine defect of the default and it is
        // recorded rather than hidden.
        val immediate = worstStealRatio(stolen, "stealReleaseFrames = 1 (default)")
        val released = worstStealRatio(gentle, "stealReleaseFrames = ${(0.03 * SR).roundToLong()} (30ms)")
        report.check("the default steal really does click, as documented", immediate > 2.0f) {
            "${"%.2f".format(immediate)}x — if this stopped being true the KDoc is wrong"
        }
        report.check("but it is bounded by the outgoing note's own amplitude", immediate < 4.0f) {
            "${"%.2f".format(immediate)}x"
        }
        // And the knob works: give the envelope its release and the step disappears entirely.
        report.check("a steal with a real release window does not click", released < 1.3f) {
            "${"%.2f".format(released)}x"
        }
        report.check(
            "so the knob is worth having: it removes at least half the step",
            released < immediate * 0.5f
        ) { "${"%.2f".format(released)}x vs ${"%.2f".format(immediate)}x" }

        // …and it does re-attack. The envelope is at its sustain level (0.7) before the steal and climbs to
        // 1.0 over the 3ms attack afterwards, so the local peak has to rise. Without the one-sample gate
        // dip this ratio was 1.00 and the "note" was a pitch glide.
        var worstRise = Float.MAX_VALUE
        for (at in stealFrames.drop(1)) {
            val before = localPeak(stolen, at.toInt() - 1200, 1000)
            val after = localPeak(stolen, at.toInt() + 100, 1000)
            println(
                "  steal at $at: envelope ${"%.4f".format(before)} -> ${"%.4f".format(after)} " +
                    "(${"%.2f".format(after / before)}x)"
            )
            worstRise = min(worstRise, after / before)
        }
        report.check("a stolen voice really re-attacks", worstRise > 1.15f) { "${"%.2f".format(worstRise)}x" }

        WavFloatArrayWriter.writeStereo16(
            File(outDir, "faust-poly-steal.wav"), stolen, stolen, SR, normalizePeak = 0.9f
        )
        writeWaveformPng(File(outDir, "faust-poly-steal.png"), stolen, stealFrames.map { it.toInt() }, listOf())
    }

    // ── is render allocation-free? ─────────────────────────────────────────────────────────────

    /**
     * Bytes allocated per [FaustVoiceBank.render] call, which is the property that matters on the audio
     * thread and that no µs-per-block average can see.
     *
     * A per-block allocation is not a performance nit in an audio callback: it is a GC pause in the audio
     * path, i.e. a dropout, and an average hides exactly that. `getCurrentThreadAllocatedBytes` is the
     * cheapest adequate instrument for it.
     *
     * What this found, and what was fixed for it: `nextEventFrame` returned a `Long?` (boxed, once per
     * chunk) and read its two queue heads by building a `Pair`; `render`'s `finally` used `runCatching`,
     * which builds a `Result`. All three are gone. What is left, if anything, is the polyglot boundary —
     * `Value.execute(vararg Object)` builds an array and boxes its arguments — which is inherent to the
     * API rather than to this class, and which only happens on a frame where a note starts.
     */
    private fun checkAudioThreadAllocation(report: Reporter, program: FaustProgram) {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean == null || !bean.isThreadAllocatedMemorySupported) {
            report.check("thread allocation accounting is available", false) { "not supported on this JVM" }
            return
        }
        bean.isThreadAllocatedMemoryEnabled = true

        val bank = FaustVoiceBank(program, SR, voices = 8)
        try {
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            // Every voice sounding for the whole measurement, and no events inside it — the steady state an
            // audio callback spends almost all of its time in.
            bank.schedule(
                (0 until 8).map { FaustNote(0, Long.MAX_VALUE / 4, patMidiToHz(48.0 + it), 0.1, "$it") }
            )
            repeat(2000) { bank.render(l, r, BLOCK) }
            check(bank.activeVoices() == 8) { "only ${bank.activeVoices()} voices sounding" }

            val blocks = 4000
            val before = bean.currentThreadAllocatedBytes
            repeat(blocks) { bank.render(l, r, BLOCK) }
            val perBlock = (bean.currentThreadAllocatedBytes - before).toDouble() / blocks
            val perVoice = perBlock / 8.0
            println(
                "  steady state, 8 voices: ${"%.0f".format(perBlock)} bytes per $BLOCK-frame block " +
                    "(${"%.0f".format(perVoice)} per voice)"
            )

            // The number that decides whether this is *ours*. FaustDsp — the shipped single-voice audio path,
            // merged yesterday — crosses the same boundary the same way: one `Value.readBuffer` per channel
            // per block. If it allocates the same amount per voice, the allocation is the polyglot buffer
            // read rather than anything in FaustVoiceBank, and fixing it means changing how the samples come
            // out of WebAssembly for both paths.
            val gate = program.ui.writable.first { it.label == "gate" }
            val dsp = FaustDsp(program, SR)
            val dspPerBlock: Double
            try {
                dsp.setParam(gate, 1.0)
                repeat(2000) { dsp.render(l, r, BLOCK) }
                val beforeDsp = bean.currentThreadAllocatedBytes
                repeat(blocks) { dsp.render(l, r, BLOCK) }
                dspPerBlock = (bean.currentThreadAllocatedBytes - beforeDsp).toDouble() / blocks
            } finally {
                dsp.close()
            }
            println(
                "  the same for one FaustDsp (the shipped single-voice path): " +
                    "${"%.0f".format(dspPerBlock)} bytes per block"
            )
            // The like-for-like comparison, and the reason it is measured here rather than inferred.
            //
            // `perVoice` divides the WHOLE block's cost by 8, including the once-per-block context
            // enter/leave and queue drain that a bank pays however many voices sound. `FaustDsp` pays its own
            // fixed cost once, for one voice. So "perVoice vs dspPerBlock" compares an average diluted by 8
            // against one that is not, and it flatters the bank.
            //
            // A bank at `voices = 1` removes that: same class, same drain, same enter/leave, the same amount
            // of DSP as one FaustDsp. Measuring 1 and 8 also gives the split directly — per-voice cost is the
            // slope, fixed cost is the intercept — rather than solving two equations from figures taken in
            // different runs, which is how this was last published with an implied NEGATIVE fixed cost.
            val solo = FaustVoiceBank(program, SR, voices = 1)
            val soloPerBlock: Double
            try {
                solo.schedule(listOf(FaustNote(0, Long.MAX_VALUE / 4, patMidiToHz(48.0), 0.1, "solo")))
                repeat(2000) { solo.render(l, r, BLOCK) }
                check(solo.activeVoices() == 1) { "the solo bank is not sounding" }
                val beforeSolo = bean.currentThreadAllocatedBytes
                repeat(blocks) { solo.render(l, r, BLOCK) }
                soloPerBlock = (bean.currentThreadAllocatedBytes - beforeSolo).toDouble() / blocks
            } finally {
                solo.close()
            }
            val slope = (perBlock - soloPerBlock) / 7.0
            val intercept = soloPerBlock - slope
            println(
                "  the same bank at 1 voice: ${"%.0f".format(soloPerBlock)} bytes per block, against " +
                    "${"%.0f".format(dspPerBlock)} for one FaustDsp — " +
                    "${"%.2f".format(soloPerBlock / dspPerBlock)}x, like for like"
            )
            println(
                "  => per voice ${"%.0f".format(slope)} bytes/block, fixed ${"%.0f".format(intercept)} " +
                    "bytes/block, as a slope over 1 and 8 voices in one run"
            )
            report.check(
                "one voice of the bank costs about what one FaustDsp costs, so the source is the shared " +
                    "polyglot buffer read and not this class",
                soloPerBlock < dspPerBlock * 1.6
            ) { "${"%.0f".format(soloPerBlock)} vs ${"%.0f".format(dspPerBlock)} bytes/block" }
            report.check(
                "the fixed per-block cost is not negative — which is what the two previously published " +
                    "figures implied, because they came from different runs",
                intercept > -1.0
            ) { "intercept ${"%.0f".format(intercept)} from slope ${"%.0f".format(slope)}" }
            // Ratchet near the measurement so a regression that doubles it fails, and honestly reported
            // rather than asserted away: this is real GC pressure on the audio thread and
            // `public/docs/faust.md` says so.
            report.check("8 voices stay under 1.2KB a block per voice", perVoice < 1_200.0) {
                "${"%.0f".format(perVoice)} bytes/block/voice"
            }

            // And with a note starting inside every block, where this class's own `execute` calls happen.
            var at = bank.frame
            val notes = ArrayList<FaustNote>(blocks)
            repeat(blocks) {
                at += BLOCK.toLong()
                notes += FaustNote(at - 40L, at + 200L, patMidiToHz(48.0 + it % 12), 0.1, "$it")
            }
            bank.schedule(notes)
            val beforeNotes = bean.currentThreadAllocatedBytes
            repeat(blocks) { bank.render(l, r, BLOCK) }
            val perNoteBlock = (bean.currentThreadAllocatedBytes - beforeNotes).toDouble() / blocks
            println(
                "  with a note starting in every block: ${"%.0f".format(perNoteBlock)} bytes/block, " +
                    "i.e. ${"%.0f".format(perNoteBlock - perBlock)} more than the steady state — the extra " +
                    "chunk's worth of compute/readBuffer plus three Value.execute varargs for the note-on"
            )
            report.check(
                "a note-on costs a chunk split, not an unbounded amount",
                perNoteBlock < perBlock * 4.0
            ) { "${"%.0f".format(perNoteBlock)} vs ${"%.0f".format(perBlock)} bytes/block" }
        } finally {
            bank.close()
        }
    }

    // ── cost ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The architecture fork, measured rather than preferred.
     *
     * `FaustDsp` gives each voice its own GraalVM context, and its own KDoc records that the per-block
     * `Context.enter` dominates what is left after `compute`. So the two arrangements are timed against each
     * other here with every voice gated on: N instances in one context, against N `FaustDsp`s summed.
     */
    private fun checkCost(report: Reporter, program: FaustProgram) {
        val budgetUs = BLOCK * 1_000_000.0 / SR
        println("  budget for a $BLOCK-frame block at ${SR}Hz: ${"%.0f".format(budgetUs)}us")

        // Throw the first measurement away. The very first bank in a process pays for Truffle compiling the
        // poly shim inside the timed window: measured 118.4us for one voice against 3.6us for two, which is
        // warm-up rather than a cost curve, and printing it as one would have been a lie in a table.
        timeBank(program, 2)
        timeSeparateDsps(program, 2)

        println("  voices |   one context |  N contexts |  ratio | one context")
        var bankAt8 = Double.NaN
        var worstRatio = Double.MAX_VALUE
        for (voices in listOf(1, 2, 4, 8, 16)) {
            val oneContext = timeBank(program, voices)
            val nContexts = timeSeparateDsps(program, voices)
            if (voices == 8) bankAt8 = oneContext
            worstRatio = min(worstRatio, nContexts / oneContext)
            println(
                "  %6d | %10.1fus | %9.1fus | %5.2fx | %.0fx realtime".format(
                    voices, oneContext, nContexts, nContexts / oneContext, budgetUs / oneContext
                )
            )
            report.check("$voices voices render faster than realtime", oneContext < budgetUs) {
                "${"%.1f".format(oneContext)}us vs ${"%.0f".format(budgetUs)}us"
            }
        }
        println("  worst one-context/N-context ratio: ${"%.2f".format(worstRatio)}x")

        // What the one-context arrangement is actually for. A block split at eight event boundaries pays
        // *one* Context.enter; the N-context arrangement would pay N enters per chunk, i.e. N x 8 per block,
        // and FaustDsp's per-block parameter queue could not express the split at all.
        val quiet = timeBank(program, 8)
        val dense = timeBankDenseEvents(program, 8)
        println(
            "  8 voices, 8 note events inside every block: ${"%.1f".format(dense)}us " +
                "against ${"%.1f".format(quiet)}us undisturbed (${"%.2f".format(dense / quiet)}x)"
        )
        // Keep the ratio as diagnostic output, not a verdict. It compares two tiny wall-clock windows and
        // has ranged from 2.84x to 5.49x under host contention while the absolute real-time budget check
        // immediately below remained comfortably green. Deterministic certification asserts the budget;
        // relative microbenchmarking belongs in an opt-in measurement task.
        report.check("and is still far inside the block budget", dense < budgetUs / 20.0) {
            "${"%.1f".format(dense)}us vs ${"%.0f".format(budgetUs / 20.0)}us"
        }

        // Constructing the bank is where one context genuinely wins, and it is not close: N separate DSPs
        // each build a GraalVM context AND compile the same WebAssembly module again.
        val bankBuild = timeConstruction { FaustVoiceBank(program, SR, 16).close() }
        val dspBuild = timeConstruction {
            val dsps = (0 until 16).map { FaustDsp(program, SR) }
            dsps.forEach { it.close() }
        }
        println(
            "  building 16 voices: one context ${"%.0f".format(bankBuild)}ms, " +
                "16 contexts ${"%.0f".format(dspBuild)}ms (${"%.1f".format(dspBuild / bankBuild)}x)"
        )
        // Construction timing is useful telemetry, but startup load and Graal compilation make it an
        // unsuitable pass/fail boundary for the smoke catalog.

        // 8 voices measured at ~13us on an M-series Mac against a 2667us budget, i.e. ~200x realtime. The
        // floor is set near the measurement rather than at a value a tenth of it would also pass: this
        // asserts 8 voices stay inside 1/50th of the block budget.
        report.check(
            "8 voices cost under 1/50th of the block budget",
            bankAt8 < budgetUs / 50.0
        ) { "${"%.1f".format(bankAt8)}us vs ${"%.0f".format(budgetUs / 50.0)}us" }
    }

    /** Wall milliseconds to build and release a set of voices, best of three. */
    private fun timeConstruction(build: () -> Unit): Double {
        build()
        var best = Double.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            build()
            best = min(best, (System.nanoTime() - start) / 1e6)
        }
        return best
    }

    /**
     * Median µs-per-block over [reps] separate timed windows of [blocks] blocks each.
     *
     * **A mean over one long window is the wrong statistic here, and it produced a false failure.** One run
     * of this suite reported 238.2µs for eight voices, between 8.9µs at four and 29.0µs at sixteen — and
     * 13.4µs for the *same* eight-voice configuration a few lines later, which is the measurement refuting
     * itself inside its own output. `render` allocates ~7.4KB per block at eight voices, so a 2000-block
     * window churns ~15MB and a young collection inside it is ordinary; landing in a single mean, one pause
     * moves the whole figure and there is nothing in the number to say so.
     *
     * A median over several windows is immune to that: a pause has to hit most of the windows to move it, and
     * if it does, the cost is real. Same total work, one number that means something.
     */
    private inline fun medianUsPerBlock(reps: Int = 5, blocks: Int = 400, body: (Int) -> Unit): Double {
        val samples = DoubleArray(reps)
        for (rep in 0 until reps) {
            val start = System.nanoTime()
            body(blocks)
            samples[rep] = (System.nanoTime() - start) / 1000.0 / blocks
        }
        samples.sort()
        return samples[reps / 2]
    }

    /** Microseconds per [BLOCK]-frame block for a bank of [voices], every voice sounding. */
    private fun timeBank(program: FaustProgram, voices: Int): Double {
        val bank = FaustVoiceBank(program, SR, voices)
        try {
            // Long notes, all started at once, so every voice is computing for the whole measurement.
            bank.schedule(
                (0 until voices).map {
                    FaustNote(0, Long.MAX_VALUE / 4, patMidiToHz(48.0 + it), 0.2, "$it")
                }
            )
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            repeat(600) { bank.render(l, r, BLOCK) }
            check(bank.activeVoices() == voices) { "only ${bank.activeVoices()} of $voices voices sounding" }
            return medianUsPerBlock { blocks -> repeat(blocks) { bank.render(l, r, BLOCK) } }
        } finally {
            bank.close()
        }
    }

    /**
     * The same [voices], but with a note starting every 16 frames — so every 128-frame block is split at
     * eight event boundaries and the sub-block path is the whole cost.
     */
    private fun timeBankDenseEvents(program: FaustProgram, voices: Int): Double {
        val bank = FaustVoiceBank(program, SR, voices)
        try {
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            val blocks = 2000
            fun fill(from: Long, count: Int) = bank.schedule(
                (0 until count).map { i ->
                    val at = from + i * 16L
                    FaustNote(at, at + 16L * voices, patMidiToHz(48.0 + i % 12), 0.05, "$i")
                }
            )
            fill(0L, 600 * 8)
            repeat(600) { bank.render(l, r, BLOCK) }
            // Enough notes for every window the median takes, scheduled up front so the feeder is never part
            // of what is being timed.
            fill(bank.frame, blocks * 8)
            val us = medianUsPerBlock { window -> repeat(window) { bank.render(l, r, BLOCK) } }
            check(bank.notesPlayed > blocks * 7) { "only ${bank.notesPlayed} notes played" }
            return us
        } finally {
            bank.close()
        }
    }

    /** The same work as N separate [FaustDsp]s, each with its own context, summed in Kotlin. */
    private fun timeSeparateDsps(program: FaustProgram, voices: Int): Double {
        val gate = program.ui.writable.first { it.label == "gate" }
        val freq = program.ui.writable.first { it.label == "freq" }
        val dsps = (0 until voices).map { FaustDsp(program, SR) }
        try {
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            val mixL = FloatArray(BLOCK)
            val mixR = FloatArray(BLOCK)
            for ((i, dsp) in dsps.withIndex()) {
                dsp.setParam(freq, patMidiToHz(48.0 + i))
                dsp.setParam(gate, 1.0)
            }
            repeat(600) { dsps.forEach { it.render(l, r, BLOCK) } }
            return medianUsPerBlock { blocks ->
                repeat(blocks) {
                    java.util.Arrays.fill(mixL, 0f)
                    java.util.Arrays.fill(mixR, 0f)
                    for (dsp in dsps) {
                        dsp.render(l, r, BLOCK)
                        for (i in 0 until BLOCK) {
                            mixL[i] += l[i]
                            mixR[i] += r[i]
                        }
                    }
                }
            }
        } finally {
            dsps.forEach { it.close() }
        }
    }

    // ── pictures, so a person can look at it rather than read about it ──────────────────────────

    /**
     * A min/max waveform with the notated onsets marked in one colour and the detected ones in another.
     *
     * `PngEncoder` rather than AWT, which is the house rule for a raster in this codebase.
     */
    private fun writeWaveformPng(file: File, mono: FloatArray, notated: List<Int>, detected: List<Int>) {
        val w = 1400
        val h = 320
        val px = IntArray(w * h) { 0xFF101418.toInt() }
        val mid = h / 2
        var peak = 1e-6f
        for (v in mono) peak = max(peak, abs(v))

        fun plot(x: Int, y: Int, colour: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = colour
        }

        // Zero line.
        for (x in 0 until w) plot(x, mid, 0xFF2A3138.toInt())

        val perColumn = max(1, mono.size / w)
        for (x in 0 until w) {
            val from = x.toLong() * mono.size / w
            var lo = 0f
            var hi = 0f
            for (i in from until min(from + perColumn, mono.size.toLong())) {
                lo = min(lo, mono[i.toInt()])
                hi = max(hi, mono[i.toInt()])
            }
            val y0 = mid - (hi / peak * (mid - 8)).roundToInt()
            val y1 = mid - (lo / peak * (mid - 8)).roundToInt()
            for (y in min(y0, y1)..max(y0, y1)) plot(x, y, 0xFF7FD4C1.toInt())
        }
        for (n in notated) {
            val x = (n.toLong() * w / mono.size).toInt()
            for (y in 0 until 24) plot(x, y, 0xFFE0A030.toInt())
        }
        for (d in detected) {
            val x = (d.toLong() * w / mono.size).toInt()
            for (y in h - 24 until h) plot(x, y, 0xFFE05A7A.toInt())
        }
        file.writeBytes(afield.util.PngEncoder.encode(px, w, h))
    }

    /** Two spectra one above the other: the chord, and the same chord through one voice. */
    private fun writeSpectrumPng(
        file: File,
        polyMono: FloatArray,
        monoMono: FloatArray,
        from: Int,
        expected: List<Double>
    ) {
        val w = 1400
        val h = 400
        val px = IntArray(w * h) { 0xFF101418.toInt() }
        val n = 32768
        val maxHz = 700.0

        fun plot(x: Int, y: Int, colour: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = colour
        }

        for (hz in expected) {
            val x = (hz / maxHz * w).roundToInt()
            for (y in 0 until h) plot(x, y, 0xFF3A4450.toInt())
        }

        for ((half, signal) in listOf(polyMono, monoMono).withIndex()) {
            val baseline = if (half == 0) h / 2 - 6 else h - 6
            val height = h / 2 - 20
            val peaks = faustSpectrumPeaks(signal, from, n, SR, minHz = 20.0, maxHz = maxHz, relThreshold = 0.0)
            val loudest = peaks.maxOfOrNull { it.magnitude } ?: 1.0
            val colour = if (half == 0) 0xFF7FD4C1.toInt() else 0xFFE05A7A.toInt()
            for (x in 0 until w) plot(x, baseline, 0xFF2A3138.toInt())
            for (peak in peaks) {
                val x = (peak.hz / maxHz * w).roundToInt()
                val bars = (peak.magnitude / loudest * height).roundToInt()
                for (y in baseline - bars..baseline) plot(x, y, colour)
            }
        }
        file.writeBytes(afield.util.PngEncoder.encode(px, w, h))
    }

    // ── reporting ──────────────────────────────────────────────────────────────────────────────

    private class Reporter {
        var failures = 0
        private var checks = 0

        fun check(what: String, ok: Boolean, detail: () -> String = { "" }) {
            checks++
            if (ok) {
                println("  ok   $what")
            } else {
                failures++
                println("  FAIL $what — ${detail()}")
            }
        }

        fun summarise() {
            println("$checks checks, $failures failed")
        }
    }
}

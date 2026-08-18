package afield.examples.sonicpi

import afield.four.audio.WavFloatArrayLoader
import afield.four.synth.PatQ
import afield.four.synth.SonicPi
import afield.four.synth.SonicPiFlash
import afield.four.synth.SonicPiInstall
import afield.four.synth.SonicPiProgramReader
import afield.four.synth.patDetectOnsetSamples
import afield.four.synth.patEnergyEnvelope
import afield.four.synth.patMatchOnsets
import afield.four.synth.PAT_ONSET_HOP
import afield.four.synth.gain
import afield.four.synth.pat
import afield.four.synth.sonicPiSource
import afield.four.synth.with
import afield.util.PngEncoder
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
FIELD_TEST
id: sonicpi.pattern.timing
runner: jvm-main
entrypoint: afield.examples.sonicpi.SonicPiPatternProbe
tags: [smoke, sonicpi, pattern, audio, external, probe]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "pattern timing probe finished"
*/
/**
 * Does a Field pattern lowered to Sonic Pi actually *play* where the pattern says it does?
 *
 * `synth.sonic-pi.patterns` proves the emitted program's lattice agrees with the pattern's exact
 * rational schedule, and proves it offline and exactly. What it cannot prove is that Sonic Pi then
 * does what the program says — that a `sleep Rational(1, 3)` becomes a third of a bar of *sound*.
 * Two implementations of a shared idea of time, and the only honest way to answer is to put a
 * pattern in one end and measure what comes out.
 *
 * So, twice, on the real installed engine:
 *
 * **Silent** — the same program with every `amp: 0`. Sonic Pi still creates the node and still
 * reports the line to its client (`/flash`), so [SonicPi.onFlash] arrival times are an onset train
 * with no sound at all. Measured against the pattern's schedule. This runs first because it is the
 * middle of the night, and because if it fails there is no point recording anything.
 *
 * **Audible** — master volume at [PROBE_VOLUME], nine seconds of a pattern, recorded through Sonic
 * Pi's own recorder (so the measurement does not depend on which soundcard the machine has
 * selected), and the WAV analysed in-process: an energy envelope, spectral-flux peak picking, and
 * the detected onsets matched against the schedule. A flat recording is a *failure* here, not a
 * pass — [checkNotSilent] is the assertion that stops this probe quietly measuring nothing.
 *
 * The pattern is chosen so that a detector cannot pass by accident. `pat("bd ~ [bd bd bd] bd")` at
 * a quarter cycle per second puts onsets at 0, 2, 2⅓, 2⅔ and 3 seconds: a triplet inside a binary
 * bar. Four evenly spaced thumps do not match it, and a lowering that rounded `Rational(1, 3)` to
 * `0.333` would drift visibly across the nine seconds.
 *
 * Degrades to a loud skip without an install, the way [SonicPiLinkAudioProbe] does — the answer
 * "there is no Sonic Pi here" is not a regression on an ordinary machine. A machine whose job is
 * to exercise Sonic Pi sets `FIELD_SONIC_PI_REQUIRED=1`, which turns either unavailable-engine skip
 * into a failure. Unlike that probe, this one *asserts*: the bounds below are numbers that were
 * measured on this machine, not guesses, and they are commented with what was seen.
 */
object SonicPiPatternProbe {

    /** A strict Sonic Pi machine must fail rather than turn an unmeasured probe into a green skip. */
    internal fun refuseRequiredSkip(required: Boolean, reason: String) {
        check(!required) { "FIELD_SONIC_PI_REQUIRED=1, but $reason" }
    }

    /**
     * Master volume for the audible half. Low enough not to wake anyone, high enough to measure.
     *
     * Not zero: Sonic Pi's recorder taps the mixer *after* the master fader, so a volume of 0 gives
     * a silent WAV and a probe that proves nothing while looking like it passed.
     */
    private const val PROBE_VOLUME = 0.15

    private const val CPS = 0.25
    private const val RECORD_SECONDS = 9.0
    private const val SILENT_SECONDS = 9.0

    /** Onsets within one cycle, in cycles: 0, ½, ½+1/12, ⅔, ¾ — a triplet inside a binary bar. */
    private const val PATTERN = "bd ~ [bd bd bd] bd"

    // ── the bounds, measured ───────────────────────────────────────────────────────────────────
    //
    // Filled in from an actual run on this machine; see the worklog for the raw numbers. Each is
    // set a little above what was observed, so that normal variation passes and a real regression
    // — a rounded rational, a dropped pad at the end of the loop — does not.

    /**
     * Worst |arrival - scheduled| over the flash train, after the constant offset is removed.
     *
     * Observed on this machine: mean 2.3–2.8 ms, worst 5.7 ms and 11.2 ms across two runs. This is
     * UDP arrival at a JVM callback, so it is measuring Field's own receive jitter as much as Sonic
     * Pi's scheduler — 25 ms is a generous ceiling over what was seen, and still an order of
     * magnitude tighter than a sixteenth note at any usable tempo.
     */
    private const val SILENT_TOLERANCE_S = 0.025

    /**
     * Worst |detected - scheduled| in the recorded audio, after the constant offset is removed.
     *
     * Observed on this machine: **0 samples** — every one of 11 onsets across 8 seconds, triplets
     * included, landed on exactly the sample the pattern's rational schedule names. That is not
     * luck: SuperCollider schedules bundles sample-accurately, and the lowering emits
     * `Rational(1, 3)` rather than `0.333`, so there is nothing left to drift. 2 ms is the bound,
     * which is ~96 samples of slack against an observed zero — set there rather than at zero
     * because the *detector* is the fragile part, not the scheduler.
     */
    private const val AUDIO_TOLERANCE_S = 0.002

    /** Fraction of scheduled onsets that must be found in the recording. Observed: 1.000. */
    private const val AUDIO_RECALL_FLOOR = 0.90

    /** A recording quieter than this is not evidence of anything. Observed RMS: 0.0057. */
    private const val MIN_RMS = 0.002

    @JvmStatic
    fun main(args: Array<String>) {
        val required = System.getenv("FIELD_SONIC_PI_REQUIRED") == "1"
        val install = runCatching { SonicPiInstall.discover() }.getOrNull()
        if (install == null) {
            println("SKIPPED: no Sonic Pi install found, so nothing here was exercised.")
            println("  Set -Dsonicpi.home or SONIC_PI_HOME if one is installed somewhere unusual.")
            refuseRequiredSkip(required, "no Sonic Pi install was found")
            println("pattern timing probe finished")
            return
        }
        println("Sonic Pi install: ${install.home}")

        val outDir = Files.createTempDirectory("sonicpi-pattern-probe-").toFile()
        val failures = mutableListOf<String>()
        var sonicPi: SonicPi? = null

        try {
            sonicPi = SonicPi.shared()
            if (!sonicPi.awaitReady(120_000L)) {
                println("SKIPPED: Sonic Pi booted but never announced /spider/ready.")
                refuseRequiredSkip(required, "Sonic Pi never became ready")
                println("pattern timing probe finished")
                return
            }
            println("Sonic Pi ${sonicPi.version()?.version ?: "?"} ready, ports ${sonicPi.ports}")
            println()

            runSilentHalf(sonicPi, failures)
            println()
            runAudibleHalf(sonicPi, outDir, failures)
        } catch (t: Throwable) {
            failures += "probe threw ${t::class.simpleName}: ${t.message}"
            t.printStackTrace()
        } finally {
            runCatching { sonicPi?.stopAll() }
            Thread.sleep(500)
            runCatching { SonicPi.destroyShared() }
        }

        println()
        println("artifacts in $outDir")
        if (failures.isEmpty()) {
            println("pattern timing probe finished — all measurements within their asserted bounds")
        } else {
            println("pattern timing probe finished with FAILURES:")
            failures.forEach { println("  - $it") }
            throw IllegalStateException("SonicPi pattern timing probe failed: ${failures.size} failure(s)")
        }
    }

    // ── silent: the engine's own report of what it triggered ───────────────────────────────────

    private fun runSilentHalf(sonicPi: SonicPi, failures: MutableList<String>) {
        println("── silent half: amp 0, measured from Sonic Pi's own line-activity reports ──")

        // amp: 0 everywhere. Sonic Pi still creates the node and still reports the line.
        val source = (pat(PATTERN) with gain(0.0)).sonicPiSource(cps = CPS, cycles = 1, name = "probe_silent")
        val sampleLines = sampleLineNumbers(source)
        println("  program: ${source.lines().size} lines, ${sampleLines.size} sample line(s) at $sampleLines")

        val observed = mutableListOf<Double>()
        val allLines = mutableMapOf<String, MutableMap<Int, Int>>()
        val start = System.nanoTime()
        val previous = sonicPi.onFlash
        sonicPi.onFlash = { flash: SonicPiFlash ->
            synchronized(observed) {
                allLines.getOrPut(flash.workspace) { mutableMapOf() }
                    .merge(flash.line, 1) { a, b -> a + b }
                if (flash.workspace == "probe_silent" && flash.line in sampleLines) {
                    observed += (System.nanoTime() - start) / 1e9
                }
            }
        }
        try {
            sonicPi.run(source, workspace = "probe_silent")
            Thread.sleep((SILENT_SECONDS * 1000).toLong())
        } finally {
            sonicPi.onFlash = previous
            sonicPi.stopAll()
        }
        Thread.sleep(1_500) // Scheduled activity drains for about this long after a stop.

        val times = synchronized(observed) { observed.toList() }.sorted()
        // Every line Sonic Pi reported, so an empty train is diagnosable rather than a mystery:
        // "no flashes at all" and "flashes, but on the loop boundary lines" are different bugs.
        println("  flashes by workspace/line: $allLines")
        report("flash", times, SILENT_TOLERANCE_S, minRecall = 0.90, failures)
    }

    // ── audible: a real recording, looked at ───────────────────────────────────────────────────

    private fun runAudibleHalf(sonicPi: SonicPi, outDir: File, failures: MutableList<String>) {
        println("── audible half: volume $PROBE_VOLUME, recorded and measured ──")

        val source = pat(PATTERN).sonicPiSource(cps = CPS, cycles = 1, name = "probe_audio")
        val wav = File(outDir, "sonicpi_pattern.wav")

        sonicPi.volume(PROBE_VOLUME, silent = true)
        Thread.sleep(300)
        sonicPi.startRecording()
        Thread.sleep(300)
        sonicPi.run(source, workspace = "probe_audio", silent = true)
        Thread.sleep((RECORD_SECONDS * 1000).toLong())
        sonicPi.stopAll()
        Thread.sleep(1_200)
        sonicPi.stopRecording()
        Thread.sleep(400)
        sonicPi.saveRecording(wav.absolutePath)

        if (!awaitStableFile(wav, timeoutMs = 30_000)) {
            failures += "Sonic Pi never wrote a recording to ${wav.absolutePath}"
            return
        }
        println("  recorded ${wav.length() / 1024} KB to ${wav.name}")

        val audio = runCatching { WavFloatArrayLoader.loadWavToFloatArray(wav.absolutePath) }
            .getOrElse {
                failures += "could not read the recording back: $it"
                return
            }
        val durationS = audio.samples.size.toDouble() / audio.sampleRate
        println("  ${audio.samples.size} frames at ${audio.sampleRate} Hz = ${"%.2f".format(durationS)} s")

        if (!checkNotSilent(audio.samples, failures)) return

        // The detector is told the smallest gap the pattern can produce and refuses to report
        // anything closer. That is not tuning the answer: a kick's decay has a secondary rise and
        // a naive peak-picker fires three times per drum, which makes "nearest scheduled onset"
        // trivially close and turns a timing measurement into a tautology. It measured 0.0 ms
        // before this line existed, which is exactly how a probe reports success for nothing.
        val expected = scheduledOnsets(spanSeconds = RECORD_SECONDS + 2.0)
        val minGap = expected.zipWithNext().minOfOrNull { (a, b) -> b - a } ?: 0.2
        val envelope = patEnergyEnvelope(audio.samples)
        val detectedSamples =
            patDetectOnsetSamples(audio.samples, audio.sampleRate, minGap, envelope)
        val detected = detectedSamples.map { it.toDouble() / audio.sampleRate }
        println("  ${detected.size} onset(s), refined to the sample " +
            "(smallest scheduled gap ${"%.0f".format(minGap * 1000)} ms)")
        println("  onset samples: $detectedSamples")
        // Intervals in samples, against the intervals the pattern asked for. The most direct
        // statement this probe can make, with no seconds and no floating point in the way.
        val wantSamples = expected.map { (it * audio.sampleRate).roundToInt() }
        println("  observed intervals (samples): ${detectedSamples.zipWithNext().map { it.second - it.first }}")
        println("  pattern  intervals (samples): ${wantSamples.zipWithNext().map { it.second - it.first }}")

        val matched = report("audio", detected, AUDIO_TOLERANCE_S, AUDIO_RECALL_FLOOR, failures)

        val png = File(outDir, "sonicpi_pattern_onsets.png")
        png.writeBytes(plotOnsets(envelope, audio.sampleRate, detected, matched.expected))
        println("  plot written to ${png.absolutePath}")
    }

    /**
     * Is there anything on this recording at all?
     *
     * The one assertion this probe cannot do without. Every measurement below it degrades
     * gracefully to "found nothing, therefore nothing disagreed" if the WAV is flat, and a probe
     * that reports success for silence is worse than no probe.
     */
    private fun checkNotSilent(samples: FloatArray, failures: MutableList<String>): Boolean {
        var sum = 0.0
        var peak = 0.0f
        for (sample in samples) {
            sum += sample.toDouble() * sample
            if (abs(sample) > peak) peak = abs(sample)
        }
        val rms = sqrt(sum / max(1, samples.size))
        println("  overall RMS ${"%.6f".format(rms)}, peak ${"%.4f".format(peak)}")
        if (rms < MIN_RMS) {
            failures += "the recording is essentially silent (RMS ${"%.6f".format(rms)} < $MIN_RMS) — " +
                "nothing below it measured anything"
            return false
        }
        return true
    }

    // ── measurement ────────────────────────────────────────────────────────────────────────────

    private class MatchReport(val expected: List<Double>, val worstError: Double, val recall: Double)

    /**
     * Compare an onset train against the pattern's schedule and assert the bound.
     *
     * The comparison itself is [patMatchOnsets], shared with the offline half of this measurement in
     * `synth.sonic-pi.patterns`. That sharing is the point: Field's own renderer and Sonic Pi's are
     * two implementations of the same schedule, and comparing them is only meaningful if the same
     * instrument measures both. See `PatOnsetDetection.kt` for what it does and does not assert.
     */
    private fun report(
        label: String,
        observedRaw: List<Double>,
        tolerance: Double,
        minRecall: Double,
        failures: MutableList<String>
    ): MatchReport {
        val expected = scheduledOnsets(spanSeconds = RECORD_SECONDS + 2.0)
        if (observedRaw.isEmpty()) {
            failures += "$label: no onsets observed at all"
            return MatchReport(expected, Double.NaN, 0.0)
        }
        val match = patMatchOnsets(observedRaw, expected, tolerance)
        println("  " + match.describe(label))

        if (match.recall < minRecall) {
            failures += "$label recall ${"%.3f".format(match.recall)} is below the floor $minRecall " +
                "(${match.matched} of ${match.scheduled} scheduled onsets found within ${tolerance * 1000} ms)"
        }
        if (match.worstError.isNaN() || match.worstError > tolerance) {
            failures += "$label worst error ${"%.3f".format(match.worstError * 1000)} ms exceeds the " +
                "${tolerance * 1000} ms bound"
        }
        // Spurious onsets matter too: a detector that fires on everything trivially has recall 1,
        // and "nearest scheduled onset" becomes meaningless when the observed train is dense.
        if (match.observed > match.scheduled + max(2, match.scheduled / 4)) {
            failures += "$label saw ${match.observed} onsets for ${match.scheduled} scheduled — " +
                "the detector is firing on something that is not the pattern"
        }
        return MatchReport(expected, match.worstError, match.recall)
    }

    /**
     * The pattern's own schedule, in seconds relative to its first onset.
     *
     * Taken from the *emitted program*, not from the pattern object, so that this probe measures
     * the same lowering the engine was given. `SonicPiProgramReader` is the offline oracle's parser
     * and its agreement with the pattern is what `synth.sonic-pi.patterns` asserts, exactly.
     */
    private fun scheduledOnsets(spanSeconds: Double): List<Double> {
        val source = pat(PATTERN).sonicPiSource(cps = CPS, cycles = 1, name = "probe_schedule")
        val reading = SonicPiProgramReader.read(source)
        val inCycle = reading.events.map { it.at }
        val period = reading.loopPeriods.values.firstOrNull() ?: PatQ.ONE
        val secondsPerCycle = 1.0 / CPS
        val out = mutableListOf<Double>()
        var repeat = 0
        while (true) {
            val base = period.toDouble() * repeat
            var any = false
            for (at in inCycle) {
                val t = (base + at.toDouble()) * secondsPerCycle
                if (t > spanSeconds) continue
                out += t
                any = true
            }
            if (!any && base * secondsPerCycle > spanSeconds) break
            repeat++
            if (repeat > 100) break
        }
        return out.sorted()
    }

    private fun sampleLineNumbers(source: String): Set<Int> =
        source.lines().mapIndexedNotNull { index, line ->
            if (line.trim().startsWith("sample :")) index + 1 else null
        }.toSet()

    // ── the picture ────────────────────────────────────────────────────────────────────────────

    private const val PLOT_W = 1400
    private const val PLOT_H = 420

    /**
     * The envelope, the detected onsets, and where the pattern said they would be.
     *
     * Committed as evidence because "the timing was measured" is a claim, and a plot is the form of
     * it a person can disagree with in five seconds. Blue is what the pattern asked for, green is
     * what the recording contains; where they are not on top of each other, the probe is wrong or
     * the lowering is.
     */
    private fun plotOnsets(
        envelope: DoubleArray,
        sampleRate: Int,
        detected: List<Double>,
        expected: List<Double>
    ): ByteArray {
        val pixels = IntArray(PLOT_W * PLOT_H) { 0xFF101418.toInt() }
        fun px(x: Int, y: Int, colour: Int) {
            if (x in 0 until PLOT_W && y in 0 until PLOT_H) pixels[y * PLOT_W + x] = colour
        }
        fun vline(x: Int, from: Int, to: Int, colour: Int, dashed: Boolean = false) {
            for (y in from..to) if (!dashed || (y / 6) % 2 == 0) px(x, y, colour)
        }

        val totalS = envelope.size.toDouble() * PAT_ONSET_HOP / sampleRate
        if (totalS <= 0.0) return PngEncoder.encode(pixels, PLOT_W, PLOT_H)
        fun xOf(t: Double) = ((t / totalS) * (PLOT_W - 1)).roundToInt()

        val peak = envelope.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val base = PLOT_H - 40
        val envTop = 70

        // Two bands above the waveform rather than lines through it: drawn into the envelope, the
        // expected train was painted over by the bars and the first version of this plot showed
        // only the answer, never the question.
        val anchor = detected.firstOrNull() ?: 0.0
        for (t in expected) {
            val x = xOf(t + anchor)
            for (dx in -1..1) {
                vline(x + dx, 8, 30, 0xFF4C8FE0.toInt())                       // what the pattern says
                vline(x + dx, 34, base, 0xFF1E3346.toInt(), dashed = true)     // and a faint plumb line
            }
        }
        // Envelope.
        for (f in envelope.indices) {
            val x = xOf(f.toDouble() * PAT_ONSET_HOP / sampleRate)
            val h = ((envelope[f] / peak) * (base - envTop)).roundToInt().coerceIn(0, base - envTop)
            for (y in (base - h)..base) px(x, y, 0xFF7A8A99.toInt())
        }
        // What the recording contains.
        for (t in detected) {
            val x = xOf(t)
            for (dx in -1..1) {
                vline(x + dx, 38, 60, 0xFF35D07F.toInt())
                vline(x + dx, envTop, base, 0xFF35D07F.toInt())
            }
        }
        // Baseline and a one-second grid.
        for (x in 0 until PLOT_W) px(x, base, 0xFF445159.toInt())
        var second = 0
        while (second <= totalS) {
            vline(xOf(second.toDouble()), base, base + 10, 0xFF56646E.toInt())
            second++
        }
        return PngEncoder.encode(pixels, PLOT_W, PLOT_H)
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────────

    /** Sonic Pi writes the recording asynchronously; wait until it exists and has stopped growing. */
    private fun awaitStableFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var lastSize = -1L
        var stableFor = 0
        while (System.nanoTime() < deadline) {
            val size = if (file.isFile) file.length() else -1L
            if (size > 0 && size == lastSize) {
                stableFor++
                if (stableFor >= 3) return true
            } else {
                stableFor = 0
            }
            lastSize = size
            Thread.sleep(300)
        }
        return file.isFile && file.length() > 0
    }
}

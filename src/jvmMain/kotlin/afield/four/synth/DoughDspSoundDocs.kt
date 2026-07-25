package afield.four.synth

import afield.Box
import afield.showSignals
import afield.four.audio.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.dough-dsp.sound
runner: jvm-main
entrypoint: afield.four.synth.DoughDspSoundDocs
tags: [smoke, synth, dsp, dough, audio, wav, capture, cpu]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "dough dsp sound docs rendered"
*/

/**
 * Headless **offline sound-render harness** for the dough DSP ports in [DoughDsp], and the honest
 * source behind the `audio="…"` docs-capture mode (see `public/docs_hooks.py`).
 *
 * Every scene in [SCENES] is produced by actually *running* a primitive on the CPU — a pluck into
 * [PlateReverb], a sawtooth through a sweeping [Phaser] / [ChamberlinLowpass], an oscillator gated
 * by [CurvedADSR], a sine driven through [SoftDistort], and raw [ZawOsc] / [PulzeOsc] tones — never
 * a synthesized or hand-authored waveform. There is no realtime engine, JACK device, GPU or Field
 * window: signals are block-rendered into a `FloatArray` exactly like [DoughDspPlotGallery], then
 * written to a real 16-bit PCM WAV via [WavFloatArrayWriter]. The render is deterministic, so the
 * committed docs `.wav` regenerates byte-stably at docs-build.
 *
 * The public building blocks are the top-level `dough*` helpers below ([doughRenderMono],
 * [doughRenderStereoMono], [doughRenderGated], [doughPluckEnv], and the plot-shaping
 * [doughAbsEnvelope] / [doughShortTimeRms] / [doughShortTimeBrightness] / [doughWaveWindow]). They
 * are the SAME functions the docs page's runner blocks call, so the code a reader sees construct a
 * primitive and plot it is exactly the code that runs — and it builds the identical graph the
 * `audio=` WAV is rendered from. `afield.four.synth` is auto-imported into every box, so a docs
 * fence can call them unqualified.
 *
 * As a FIELD_TEST (`synth.dough-dsp.sound`) it renders every scene to a temp dir and asserts real
 * correctness (a valid WAV header on disk, plus per-scene sample-level checks: the reverb tail
 * decays, the ADSR rhythm has silent gaps, the filter sweep gets brighter, the drive sweep adds
 * harmonics, the raw oscillator is periodic). It writes NO committed assets during the test.
 *
 * Add a sound by appending one [Scene] to [SCENES].
 */
object DoughDspSoundDocs {

    internal const val SR = 48_000

    // ── scene model ───────────────────────────────────────────────────────────────

    /** Named series for the plot, sharing an x (sample index by default, or [x]). */
    class PlotData(
        val series: List<Pair<String, List<Number>>>,
        val x: List<Number>? = null,
        val xTitle: String? = null,
        val yTitle: String? = null,
    )

    class Scene(
        val name: String,
        val title: String,
        /** One-line description of what you hear. */
        val blurb: String,
        /** Real offline render → mono audio samples in roughly [-1, 1]. */
        val render: () -> FloatArray,
        /** Plot of the same primitive's behaviour. */
        val plot: (FloatArray) -> PlotData,
        /** Deeper correctness check on the rendered audio. */
        val verify: (FloatArray) -> Unit = {},
    )

    val SCENES: List<Scene> = listOf(
        // 1 ── PlateReverb: a short pluck, then a long decaying stereo tail folded to mono.
        Scene(
            name = "plate-reverb",
            title = "PlateReverb — a pluck and its decaying tail",
            blurb = "A 120 ms 330 Hz pluck fed into the Dattorro plate (decay 0.85, mix 0.5); the tail rings on long after the dry note stops.",
            render = {
                val pluck = SineOsc(Param(330f)) * doughPluckEnv(tau = 0.045f, holdSec = 0.12f)
                val verb = PlateReverb(source = pluck, decay = Param(0.85f), mix = Param(0.5f))
                doughRenderStereoMono(verb, seconds = 3f)
            },
            plot = { audio -> PlotData(listOf("|amplitude| envelope" to doughAbsEnvelope(audio, 400)), xTitle = "time", yTitle = "|amp|") },
            verify = { a ->
                val early = meanAbs(a, a.size / 40, a.size / 8)
                val late = meanAbs(a, a.size * 5 / 6, a.size)
                check(early > 1e-3) { "plate-reverb never made sound: early=$early" }
                check(late < early * 0.6) { "plate-reverb tail did not decay: early=$early late=$late" }
                // the reverb rings well past the 120 ms dry pluck: energy at ~1 s is still meaningful.
                val atOneSec = meanAbs(a, SR - SR / 20, SR + SR / 20)
                check(atOneSec > early * 0.02) { "plate-reverb has no tail at 1 s: atOneSec=$atOneSec early=$early" }
            },
        ),
        // 2 ── Phaser: a sawtooth swept by the LFO — the notches move across its harmonics.
        Scene(
            name = "phaser",
            title = "Phaser — notches sweeping across a sawtooth",
            blurb = "A 220 Hz sawtooth through the LFO-swept cascaded notches (rate 0.5 Hz, depth 0.7); you hear the classic hollow whoosh.",
            render = {
                val saw = ZawOsc(Param(220f)) * 0.4f
                val out = saw.through(Phaser(rate = Param(0.5f), depth = Param(0.7f), center = Param(700f), sweep = Param(1400f), mix = Param(1f)))
                doughRenderMono(out, seconds = 3f)
            },
            plot = { audio -> PlotData(listOf("short-time RMS" to doughShortTimeRms(audio, windowMs = 25.0, points = 300)), xTitle = "time", yTitle = "rms") },
            verify = { a ->
                // Raw (pre-normalization) samples; the writer normalizes to 0.9 peak.
                check(peak(a) > 0.02f) { "phaser too quiet: ${peak(a)}" }
                check(a.all { it.isFinite() }) { "phaser produced non-finite samples" }
                // the sweep modulates the sound: the short-time RMS is not constant.
                val rmsWindows = doughShortTimeRms(a, 25.0, 60)
                check(stddev(rmsWindows) > 1e-4) { "phaser shows no LFO modulation (flat RMS)" }
            },
        ),
        // 3 ── ChamberlinLowpass: a resonant cutoff sweep opening up over a raw saw.
        Scene(
            name = "chamberlin-lowpass",
            title = "ChamberlinLowpass — a resonant cutoff sweep",
            blurb = "A 110 Hz sawtooth through the superdough 2-pole lowpass (resonance 0.6) with the cutoff sweeping 150 Hz → 6 kHz; the filter audibly opens.",
            render = {
                val saw = ZawOsc(Param(110f)) * 0.5f
                val cutoff = Param(150f).also { it.supplier = { 150f + 5850f * (time / 3f).coerceIn(0f, 1f) } }
                val out = saw.through(ChamberlinLowpass(cutoff, Param(0.6f)))
                doughRenderMono(out, seconds = 3f)
            },
            plot = { audio -> PlotData(listOf("brightness (HF energy)" to doughShortTimeBrightness(audio, windowMs = 40.0, points = 200)), xTitle = "time", yTitle = "hf ratio") },
            verify = { a ->
                // Resonance legitimately overshoots 1.0 pre-normalization; the writer normalizes.
                check(peak(a) > 0.02f && a.all { it.isFinite() }) { "chamberlin too quiet / non-finite: ${peak(a)}" }
                val earlyHf = bandEnergyRatio(a, 0, SR / 2)
                val lateHf = bandEnergyRatio(a, a.size - SR / 2, a.size)
                check(lateHf > earlyHf * 1.5) { "chamberlin sweep did not brighten: early=$earlyHf late=$lateHf" }
            },
        ),
        // 4 ── CurvedADSR: an oscillator gated into a three-note rhythm.
        Scene(
            name = "curved-adsr",
            title = "CurvedADSR — a gated three-note rhythm",
            blurb = "A 220 Hz sine multiplied by the click-free curved envelope, gated on/off three times; each note swells and fades with silence between.",
            render = {
                val gate = Gate()
                val voice = SineOsc(Param(220f)) * CurvedADSR(gate, Param(0.02f), Param(0.08f), Param(0.6f), Param(0.15f))
                // schedule: (open?, seconds) — three notes with gaps.
                val rhythm = listOf(true to 0.25f, false to 0.20f, true to 0.25f, false to 0.20f, true to 0.25f, false to 0.35f)
                doughRenderGated(voice, gate, rhythm)
            },
            plot = { audio -> PlotData(listOf("|amplitude| envelope" to doughAbsEnvelope(audio, 400)), xTitle = "time", yTitle = "|amp|") },
            verify = { a ->
                val env = doughAbsEnvelope(a, 300)
                // three humps: count zero-ish → loud transitions.
                val pk = env.max()
                val thr = pk * 0.25
                var notes = 0
                var above = false
                for (v in env) {
                    if (!above && v > thr) { notes++; above = true }
                    if (above && v < thr * 0.4) above = false
                }
                check(notes == 3) { "curved-adsr expected 3 notes, envelope shows $notes" }
                // silent gaps: the minimum in the middle is near zero.
                val mid = env.subList(env.size / 6, env.size * 5 / 6)
                check(mid.min() < pk * 0.1) { "curved-adsr has no silent gaps between notes" }
            },
        ),
        // 5 ── SoftDistort: a sine driven from clean to crunchy by a rising drive.
        Scene(
            name = "soft-distort",
            title = "SoftDistort — a sine driven from clean to crunchy",
            blurb = "A 220 Hz sine through dough's expm1 soft clipper with the drive sweeping 0.2 → 7; it starts clean and grows buzzy harmonics.",
            render = {
                val drive = Param(0.2f).also { it.supplier = { 0.2f + 6.8f * (time / 3f).coerceIn(0f, 1f) } }
                val out = SineOsc(Param(220f)).through(SoftDistort(drive, Param(0.8f)))
                doughRenderMono(out, seconds = 3f)
            },
            plot = { audio ->
                PlotData(
                    listOf(
                        "clean (t≈0.15 s)" to doughWaveWindow(audio, atSeconds = 0.15f, cycles = 2, freqHz = 220f),
                        "driven (t≈2.8 s)" to doughWaveWindow(audio, atSeconds = 2.8f, cycles = 2, freqHz = 220f),
                    ),
                    xTitle = "sample", yTitle = "amp",
                )
            },
            verify = { a ->
                check(peak(a) > 0.02f) { "soft-distort too quiet: ${peak(a)}" }
                check(a.all { it.isFinite() && abs(it) <= 2.6f }) { "soft-distort left the expected bound: ${peak(a)}" }
                val earlyHf = bandEnergyRatio(a, 0, SR / 3)
                val lateHf = bandEnergyRatio(a, a.size - SR / 3, a.size)
                check(lateHf > earlyHf * 1.3) { "soft-distort added no harmonics: early=$earlyHf late=$lateHf" }
            },
        ),
        // 6 ── raw ZawOsc / PulzeOsc: a saw tone then a pulse tone (deliberately aliasing).
        Scene(
            name = "zaw-pulze",
            title = "ZawOsc / PulzeOsc — raw, aliasing tones",
            blurb = "One second of a 200 Hz naive sawtooth, then one second of a 200 Hz narrow pulse (duty 0.3); the raw ramps buzz with aliasing.",
            render = {
                val saw = ZawOsc(Param(200f)) * 0.7f
                val pulse = PulzeOsc(Param(200f), Param(0.3f)) * 0.7f
                doughRenderMono(saw, seconds = 1f) + doughRenderMono(pulse, seconds = 1f)
            },
            plot = { audio ->
                PlotData(
                    listOf(
                        "zaw (saw)" to doughWaveWindow(audio, atSeconds = 0.005f, cycles = 2, freqHz = 200f),
                        "pulze (duty 0.3)" to doughWaveWindow(audio, atSeconds = 1.005f, cycles = 2, freqHz = 200f),
                    ),
                    xTitle = "sample", yTitle = "amp",
                )
            },
            verify = { a ->
                val zaw = a.copyOfRange(SR / 4, SR * 3 / 4)
                check(zaw.min() < -0.5f && zaw.max() > 0.5f) { "zaw does not span the range: ${zaw.min()}..${zaw.max()}" }
                // periodic at 200 Hz (period 240 samples): normalized autocorrelation peaks there.
                val ac = normalizedAutocorr(zaw, SR / 200)
                check(ac > 0.6) { "zaw not periodic at 200 Hz: autocorr=$ac" }
            },
        ),
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    // ── public entry points used by the docs ───────────────────────────────────────

    /** Offline-render scene [name] and write a mono 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown DoughDspSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val samples = scene.render()
        require(samples.isNotEmpty()) { "scene '$name' rendered no samples" }
        require(samples.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.9f)
    }

    /** Plot scene [name]'s behaviour into [box]'s output as a dark-mode plotly line plot. */
    fun plotInto(box: Box, name: String) {
        val scene = byName[name] ?: error("unknown DoughDspSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val data = scene.plot(scene.render())
        box.showSignals(
            *data.series.toTypedArray(),
            x = data.x,
            channel = "main",
            title = scene.title,
            xTitle = data.xTitle,
            yTitle = data.yTitle,
        )
    }

    // ── verification-only helpers (used by scene `verify` + main) ──────────────────

    private fun bandEnergyRatio(samples: FloatArray, from: Int, to: Int): Double {
        val lo = from.coerceIn(1, samples.size); val hi = to.coerceIn(lo, samples.size)
        var diff = 0.0; var total = 0.0
        for (i in lo until hi) {
            val d = (samples[i] - samples[i - 1]).toDouble()
            diff += d * d
            total += samples[i].toDouble() * samples[i]
        }
        return if (total < 1e-12) 0.0 else diff / total
    }

    private fun normalizedAutocorr(samples: FloatArray, lag: Int): Double {
        if (lag <= 0 || lag >= samples.size) return 0.0
        var num = 0.0; var den = 0.0
        for (i in lag until samples.size) {
            num += samples[i].toDouble() * samples[i - lag]
            den += samples[i].toDouble() * samples[i]
        }
        return if (den < 1e-12) 0.0 else num / den
    }

    private fun meanAbs(a: FloatArray, from: Int, to: Int): Double {
        val lo = from.coerceIn(0, a.size); val hi = to.coerceIn(lo, a.size)
        if (hi <= lo) return 0.0
        var s = 0.0; for (i in lo until hi) s += abs(a[i]); return s / (hi - lo)
    }

    private fun peak(a: FloatArray): Float { var p = 0f; for (v in a) p = max(p, abs(v)); return p }

    private fun stddev(a: List<Double>): Double {
        if (a.isEmpty()) return 0.0
        val mean = a.average()
        var s = 0.0; for (v in a) s += (v - mean) * (v - mean)
        return sqrt(s / a.size)
    }

    // ── FIELD_TEST entry point ────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("DOUGH_DSP_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "dough-dsp-sound-docs").path
        )
        outDir.mkdirs()

        SCENES.forEach { scene ->
            val samples = scene.render()
            check(samples.isNotEmpty()) { "${scene.name}: rendered no samples" }
            check(samples.all { it.isFinite() }) { "${scene.name}: non-finite samples" }
            check(peak(samples) > 1e-3f) { "${scene.name}: rendered silence (peak=${peak(samples)})" }
            scene.verify(samples)

            val file = File(outDir, "dough-${scene.name}.wav")
            WavFloatArrayWriter.writeMono16(file, samples, SR, normalizePeak = 0.9f)

            // Assert a valid WAV header + a plausible data chunk landed on disk.
            val bytes = file.readBytes()
            check(bytes.size > 44) { "${scene.name}: WAV too small (${bytes.size} bytes)" }
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${scene.name}: missing RIFF header" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${scene.name}: missing WAVE header" }
            check(String(bytes, 12, 4, Charsets.US_ASCII) == "fmt ") { "${scene.name}: missing fmt chunk" }
            val expectedData = samples.size * 2
            check(bytes.size == 44 + expectedData) { "${scene.name}: WAV data size mismatch: ${bytes.size} vs ${44 + expectedData}" }

            // Read the header's declared sample rate back to be sure it is honest.
            val sr = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
            check(sr == SR) { "${scene.name}: WAV sample rate wrong: $sr" }

            val seconds = samples.size.toDouble() / SR
            println("wrote ${file.absolutePath} (${"%.2f".format(seconds)}s, ${samples.size} samples, peak=${"%.3f".format(peak(samples))})")
        }

        println("dough dsp sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
//  Public dough* building blocks — the SAME functions the docs page's runner blocks call.
//  Defined top-level in afield.four.synth (auto-imported into every box) so a fence can use
//  them unqualified. They offline-render a real DoughDsp graph and shape it for a plot; no
//  engine, device, GPU or window.
// ═══════════════════════════════════════════════════════════════════════════════════

private const val DOUGH_BLOCK = 128

/** A finite pluck envelope: `exp(-t / tau)` (tau seconds) hard-gated to zero after [holdSec]. */
fun doughPluckEnv(tau: Float, holdSec: Float): Signal = object : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val t0 = ctx.time; val dt = ctx.inverseSampleRate
        for (i in 0 until numFrames) {
            val t = t0 + i * dt
            output[i] = if (t > holdSec) 0f else exp(-(t / tau))
        }
    }
}

/** Offline-render a mono [signal] for [seconds] into a `FloatArray` (no engine). */
fun doughRenderMono(signal: Signal, seconds: Float, sampleRate: Int = DoughDspSoundDocs.SR): FloatArray {
    val ctx = SynthContext(sampleRate.toFloat(), DOUGH_BLOCK)
    val total = (seconds * sampleRate).toInt()
    val out = FloatArray(total)
    val buf = FloatArray(DOUGH_BLOCK)
    var written = 0
    while (written < total) {
        signal.render(buf, DOUGH_BLOCK, ctx)
        ctx.advanceCycle()
        val n = minOf(DOUGH_BLOCK, total - written)
        System.arraycopy(buf, 0, out, written, n)
        written += n
    }
    return out
}

/** Offline-render a stereo [node] for [seconds] and fold L+R to a mono `FloatArray`. */
fun doughRenderStereoMono(node: StereoRenderable, seconds: Float, sampleRate: Int = DoughDspSoundDocs.SR): FloatArray {
    val ctx = SynthContext(sampleRate.toFloat(), DOUGH_BLOCK)
    val total = (seconds * sampleRate).toInt()
    val out = FloatArray(total)
    val bufL = FloatArray(DOUGH_BLOCK); val bufR = FloatArray(DOUGH_BLOCK)
    var written = 0
    while (written < total) {
        node.render(bufL, bufR, DOUGH_BLOCK, ctx)
        ctx.advanceCycle()
        val n = minOf(DOUGH_BLOCK, total - written)
        for (i in 0 until n) out[written + i] = 0.5f * (bufL[i] + bufR[i])
        written += n
    }
    return out
}

/** Offline-render [voice] while driving [gate] through a `(open?, seconds)` schedule. */
fun doughRenderGated(voice: Signal, gate: Gate, schedule: List<Pair<Boolean, Float>>, sampleRate: Int = DoughDspSoundDocs.SR): FloatArray {
    val ctx = SynthContext(sampleRate.toFloat(), DOUGH_BLOCK)
    val buf = FloatArray(DOUGH_BLOCK)
    val out = ArrayList<Float>()
    for ((open, seconds) in schedule) {
        if (open) gate.open() else gate.close()
        val frames = (seconds * sampleRate).toInt()
        var done = 0
        while (done < frames) {
            voice.render(buf, DOUGH_BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(DOUGH_BLOCK, frames - done)
            for (i in 0 until n) out.add(buf[i])
            done += n
        }
    }
    return FloatArray(out.size) { out[it] }
}

/** Bucketed abs-peak envelope of [samples] as a [buckets]-long list (for `showSignals`). */
fun doughAbsEnvelope(samples: FloatArray, buckets: Int): List<Double> {
    val n = buckets.coerceIn(1, max(1, samples.size))
    return List(n) { b ->
        val start = (b.toLong() * samples.size / n).toInt()
        val end = ((b + 1).toLong() * samples.size / n).toInt().coerceAtLeast(start + 1).coerceAtMost(samples.size)
        var pk = 0f
        for (i in start until end) pk = max(pk, abs(samples[i]))
        pk.toDouble()
    }
}

/** Short-time RMS of [samples] over [points] windows of [windowMs] ms. */
fun doughShortTimeRms(samples: FloatArray, windowMs: Double, points: Int, sampleRate: Int = DoughDspSoundDocs.SR): List<Double> {
    val win = (windowMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)
    return List(points) { p ->
        val start = (p.toLong() * (samples.size - win) / max(1, points - 1)).toInt().coerceIn(0, max(0, samples.size - 1))
        val end = (start + win).coerceAtMost(samples.size)
        var s = 0.0
        for (i in start until end) s += samples[i].toDouble() * samples[i]
        sqrt(s / max(1, end - start))
    }
}

/** Per-window ratio of first-difference (high-frequency) energy to total energy — a brightness proxy. */
fun doughShortTimeBrightness(samples: FloatArray, windowMs: Double, points: Int, sampleRate: Int = DoughDspSoundDocs.SR): List<Double> {
    val win = (windowMs / 1000.0 * sampleRate).toInt().coerceAtLeast(2)
    return List(points) { p ->
        val start = (p.toLong() * (samples.size - win) / max(1, points - 1)).toInt().coerceIn(1, max(1, samples.size - 1))
        val end = (start + win).coerceAtMost(samples.size)
        var diff = 0.0; var total = 0.0
        for (i in start until end) {
            val d = (samples[i] - samples[i - 1]).toDouble()
            diff += d * d
            total += samples[i].toDouble() * samples[i]
        }
        if (total < 1e-12) 0.0 else diff / total
    }
}

/** [cycles] periods of [samples] at [freqHz], starting [atSeconds] in — for overlaying waveforms. */
fun doughWaveWindow(samples: FloatArray, atSeconds: Float, cycles: Int, freqHz: Float, sampleRate: Int = DoughDspSoundDocs.SR): List<Double> {
    val start = (atSeconds * sampleRate).toInt().coerceIn(0, max(0, samples.size - 1))
    val length = (cycles * (sampleRate / freqHz).toInt()).coerceAtMost(samples.size - start)
    return List(length) { samples[start + it].toDouble() }
}

/**
 * Plot a [DoughDspSoundDocs] scene into this box. Kept for convenience; the docs page now inlines
 * the real construction instead. Defined as a top-level `Box` extension in `afield.four.synth`
 * (auto-imported) so it resolves against a box's implicit receiver like `showSignals(...)`.
 */
fun Box.plotDoughSound(name: String) = DoughDspSoundDocs.plotInto(this, name)

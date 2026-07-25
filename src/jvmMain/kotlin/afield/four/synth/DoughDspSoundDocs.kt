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
 * The docs page uses this in two ways:
 *  - `DoughDspSoundDocs.renderWav("plate-reverb", "<abs path>.wav")` — the box the `audio=` runner
 *    generates; it writes the WAV the `<audio>` player points at.
 *  - `DoughDspSoundDocs.plot(this, "plate-reverb")` — a `showSignals` plotly plot of the SAME data,
 *    captured as a box screenshot (dark-mode, real font).
 *
 * As a FIELD_TEST (`synth.dough-dsp.sound`) it renders every scene to a temp dir and asserts real
 * correctness (a valid WAV header on disk, plus per-scene sample-level checks: the reverb tail
 * decays, the ADSR rhythm has silent gaps, the filter sweep gets brighter, the drive sweep adds
 * harmonics, the raw oscillator is periodic). It writes NO committed assets during the test.
 *
 * Add a sound by appending one [Scene] to [SCENES].
 */
object DoughDspSoundDocs {

    private const val SR = 48_000
    private const val SRF = 48_000f
    private const val BLOCK = 128

    // ── scene model ───────────────────────────────────────────────────────────────

    /** Named series for the plot, sharing an x (sample index by default, or [x]). */
    class PlotData(
        val series: List<Pair<String, DoubleArray>>,
        val x: DoubleArray? = null,
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
                val pluck = SineOsc(Param(330f)) * Decay(0.045f).gatedTo(0.12f)
                val verb = PlateReverb(source = pluck, decay = Param(0.85f), mix = Param(0.5f))
                val (l, r) = renderStereo(verb, seconds = 3.0f)
                FloatArray(l.size) { 0.5f * (l[it] + r[it]) }
            },
            plot = { audio -> PlotData(listOf("|amplitude| envelope" to absEnvelope(audio, 400)), xTitle = "time", yTitle = "|amp|") },
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
                val ph = saw.through(Phaser(rate = Param(0.5f), depth = Param(0.7f), center = Param(700f), sweep = Param(1400f), mix = Param(1f)))
                renderMono(ph, seconds = 3.0f)
            },
            plot = { audio -> PlotData(listOf("short-time RMS" to shortTimeRms(audio, windowMs = 25.0, points = 300)), xTitle = "time", yTitle = "rms") },
            verify = { a ->
                // Raw (pre-normalization) samples; the writer normalizes to 0.9 peak.
                check(peak(a) > 0.02f) { "phaser too quiet: ${peak(a)}" }
                check(a.all { it.isFinite() }) { "phaser produced non-finite samples" }
                // the sweep modulates the sound: the short-time RMS is not constant.
                val rmsWindows = shortTimeRms(a, 25.0, 60)
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
                val lp = saw.through(ChamberlinLowpass(cutoff, Param(0.6f)))
                renderMono(lp, seconds = 3.0f)
            },
            plot = { audio -> PlotData(listOf("brightness (HF energy)" to shortTimeBrightness(audio, windowMs = 40.0, points = 200)), xTitle = "time", yTitle = "hf ratio") },
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
                val env = CurvedADSR(gate, Param(0.02f), Param(0.08f), Param(0.6f), Param(0.15f))
                val voice = SineOsc(Param(220f)) * env
                // schedule: (open?, seconds) — three notes with gaps.
                val schedule = listOf(true to 0.25f, false to 0.20f, true to 0.25f, false to 0.20f, true to 0.25f, false to 0.35f)
                renderGated(voice, gate, schedule)
            },
            plot = { audio -> PlotData(listOf("|amplitude| envelope" to absEnvelope(audio, 400)), xTitle = "time", yTitle = "|amp|") },
            verify = { a ->
                val env = absEnvelope(a, 300)
                // three humps: count zero-ish → loud transitions.
                val thr = env.max() * 0.25
                var notes = 0
                var above = false
                for (v in env) {
                    if (!above && v > thr) { notes++; above = true }
                    if (above && v < thr * 0.4) above = false
                }
                check(notes == 3) { "curved-adsr expected 3 notes, envelope shows $notes" }
                // silent gaps: the minimum in the middle is near zero.
                val mid = env.copyOfRange(env.size / 6, env.size * 5 / 6)
                check(mid.min() < env.max() * 0.1) { "curved-adsr has no silent gaps between notes" }
            },
        ),
        // 5 ── SoftDistort: a sine driven from clean to crunchy by a rising drive.
        Scene(
            name = "soft-distort",
            title = "SoftDistort — a sine driven from clean to crunchy",
            blurb = "A 220 Hz sine through dough's expm1 soft clipper with the drive sweeping 0.2 → 7; it starts clean and grows buzzy harmonics.",
            render = {
                val drive = Param(0.2f).also { it.supplier = { 0.2f + 6.8f * (time / 3f).coerceIn(0f, 1f) } }
                val voice = SineOsc(Param(220f)).through(SoftDistort(drive, Param(0.8f)))
                renderMono(voice, seconds = 3.0f)
            },
            plot = { audio ->
                val period = (SRF / 220f).toInt()
                val clean = windowAt(audio, (0.15f * SRF).toInt(), period * 2)
                val driven = windowAt(audio, (2.8f * SRF).toInt(), period * 2)
                PlotData(listOf("clean (t≈0.15 s)" to clean, "driven (t≈2.8 s)" to driven), xTitle = "sample", yTitle = "amp")
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
                val saw = renderMono(ZawOsc(Param(200f)) * 0.7f, seconds = 1.0f)
                val pulse = renderMono(PulzeOsc(Param(200f), Param(0.3f)) * 0.7f, seconds = 1.0f)
                saw + pulse
            },
            plot = { audio ->
                val period = SR / 200 // 240 samples, integer
                val zaw = windowAt(audio, period, period * 2)
                val pulze = windowAt(audio, SR + period, period * 2)
                PlotData(listOf("zaw (saw)" to zaw, "pulze (duty 0.3)" to pulze), xTitle = "sample", yTitle = "amp")
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
        val series = data.series.map { (label, ys) -> label to ys.asNumbers() }.toTypedArray()
        box.showSignals(
            *series,
            x = data.x?.asNumbers(),
            channel = "main",
            title = scene.title,
            xTitle = data.xTitle,
            yTitle = data.yTitle,
        )
    }

    // ── offline renderers (real primitive output, no engine) ───────────────────────

    private fun renderMonoSamples(signal: Signal, total: Int): FloatArray {
        val ctx = SynthContext(SRF, BLOCK)
        val out = FloatArray(total)
        val buf = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            signal.render(buf, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(buf, 0, out, written, n)
            written += n
        }
        return out
    }

    private fun renderMono(signal: Signal, seconds: Float): FloatArray =
        renderMonoSamples(signal, (seconds * SRF).toInt())

    private fun renderStereo(node: StereoRenderable, seconds: Float): Pair<FloatArray, FloatArray> {
        val ctx = SynthContext(SRF, BLOCK)
        val total = (seconds * SRF).toInt()
        val outL = FloatArray(total); val outR = FloatArray(total)
        val bufL = FloatArray(BLOCK); val bufR = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            node.render(bufL, bufR, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(bufL, 0, outL, written, n)
            System.arraycopy(bufR, 0, outR, written, n)
            written += n
        }
        return outL to outR
    }

    /** Render [voice] while driving [gate] through a `(open?, seconds)` schedule. */
    private fun renderGated(voice: Signal, gate: Gate, schedule: List<Pair<Boolean, Float>>): FloatArray {
        val ctx = SynthContext(SRF, BLOCK)
        val buf = FloatArray(BLOCK)
        val out = ArrayList<Float>()
        for ((open, seconds) in schedule) {
            if (open) gate.open() else gate.close()
            val frames = (seconds * SRF).toInt()
            var done = 0
            while (done < frames) {
                voice.render(buf, BLOCK, ctx)
                ctx.advanceCycle()
                val n = minOf(BLOCK, frames - done)
                for (i in 0 until n) out.add(buf[i])
                done += n
            }
        }
        return FloatArray(out.size) { out[it] }
    }

    // ── test signals ────────────────────────────────────────────────────────────────

    /** Exponential decay envelope, `exp(-t / tau)`, as a control signal. */
    private class Decay(private val tau: Float) : Signal() {
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            val t0 = ctx.time; val dt = ctx.inverseSampleRate
            for (i in 0 until numFrames) output[i] = exp(-((t0 + i * dt) / tau))
        }
    }

    /** Zero this control signal after [seconds] — turns a [Decay] into a finite pluck envelope. */
    private class Gated(private val inner: Signal, private val seconds: Float) : Signal() {
        override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
            inner.render(output, numFrames, ctx)
            val t0 = ctx.time; val dt = ctx.inverseSampleRate
            for (i in 0 until numFrames) if (t0 + i * dt > seconds) output[i] = 0f
        }
    }

    private fun Signal.gatedTo(seconds: Float): Signal = Gated(this, seconds)

    // ── plot / verification helpers ──────────────────────────────────────────────────

    /** Upcast to `List<Number>` for [showSignals] without an unchecked cast. */
    private fun DoubleArray.asNumbers(): List<Number> = this.toList()

    /** Bucketed abs-peak envelope of [samples] as a [buckets]-long array. */
    private fun absEnvelope(samples: FloatArray, buckets: Int): DoubleArray {
        val n = buckets.coerceIn(1, samples.size)
        val ys = DoubleArray(n)
        for (b in 0 until n) {
            val start = (b.toLong() * samples.size / n).toInt()
            val end = ((b + 1).toLong() * samples.size / n).toInt().coerceAtLeast(start + 1).coerceAtMost(samples.size)
            var peak = 0f
            for (i in start until end) peak = max(peak, abs(samples[i]))
            ys[b] = peak.toDouble()
        }
        return ys
    }

    private fun shortTimeRms(samples: FloatArray, windowMs: Double, points: Int): DoubleArray {
        val win = (windowMs / 1000.0 * SR).toInt().coerceAtLeast(1)
        val out = DoubleArray(points)
        for (p in 0 until points) {
            val start = (p.toLong() * (samples.size - win) / max(1, points - 1)).toInt().coerceIn(0, samples.size - 1)
            val end = (start + win).coerceAtMost(samples.size)
            var s = 0.0
            for (i in start until end) s += samples[i].toDouble() * samples[i]
            out[p] = sqrt(s / max(1, end - start))
        }
        return out
    }

    /** Per-window ratio of first-difference (high-frequency) energy to total energy. */
    private fun shortTimeBrightness(samples: FloatArray, windowMs: Double, points: Int): DoubleArray {
        val win = (windowMs / 1000.0 * SR).toInt().coerceAtLeast(2)
        val out = DoubleArray(points)
        for (p in 0 until points) {
            val start = (p.toLong() * (samples.size - win) / max(1, points - 1)).toInt().coerceIn(1, samples.size - 1)
            val end = (start + win).coerceAtMost(samples.size)
            out[p] = bandEnergyRatio(samples, start, end)
        }
        return out
    }

    /** First-difference energy over total energy in `[from, to)` — a cheap brightness proxy. */
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

    private fun windowAt(samples: FloatArray, start: Int, length: Int): DoubleArray {
        val s = start.coerceIn(0, max(0, samples.size - 1))
        val n = length.coerceAtMost(samples.size - s)
        return DoubleArray(n) { samples[s + it].toDouble() }
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

    private fun stddev(a: DoubleArray): Double {
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

/**
 * Plot a [DoughDspSoundDocs] scene into this box (used by the docs page). Defined as a top-level
 * `Box` extension in `afield.four.synth` — which every box auto-imports — so a docs fence can call
 * `plotDoughSound("plate-reverb")` and it resolves against the box's implicit receiver, exactly like
 * the bare `showSignals(...)` call does.
 */
fun Box.plotDoughSound(name: String) = DoughDspSoundDocs.plotInto(this, name)

@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayLoader
import afield.audio.engine.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
FIELD_TEST
id: synth.subharmonic.sound
runner: jvm-main
entrypoint: afield.audio.synth.SubharmonicSoundDocs
tags: [smoke, synth, dsp, audio, wav, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "subharmonic sound docs rendered"
*/

/**
 * Offline renders for the subharmonic page (`public/docs/subharmonic_sound.md`): every clip on it
 * is one of the trackers in `Subharmonic.kt` run on a real input — a Kotlin bassline, Dirt-Samples
 * one-shots from the pinned subset, or fourteen seconds of *Night of the Living Dead* (1968, public
 * domain) committed beside the page.
 *
 * The page's runner blocks build the same graphs and plot them with [subRender]; each scene here
 * renders that graph again to a 16-bit WAV for the page's `audio=` fences. As a FIELD_TEST it renders
 * every scene and checks the claim the page makes about it: that the quadrature PLL lands within a
 * few hertz of each bass note, that the zero-crossing tracker jumps around on unfiltered speech and
 * stops when the speech is lowpassed, and so on.
 */
object SubharmonicSoundDocs {

    internal const val SR = 48_000

    /** One clip: the audio it renders and the check that the page's sentence about it is true. */
    class Scene(val name: String, val render: () -> FloatArray, val verify: () -> Unit = {})

    // ── scenes ─────────────────────────────────────────────────────────────────────
    //
    // Each render block is the graph the page's runner block builds, character for character
    // where it can be; the page plots it with subRender, this renders it to the WAV.

    val SCENES: List<Scene> = listOf(
        Scene("phase-harmonics", render = { doughRenderMono(phaseBankGraph().first, seconds = 8f) }),
        Scene("phase-lattice", render = { doughRenderMono(phaseBankGraph(lattice = true).first, seconds = 8f) }),
        Scene("bass-octave", render = {
            val bass = subBassline()
            val tracker = SubharmonicQuadPLL()
            val sub = bass.lowpass(150f).through(Subharmonic(tracker))
            val out = bass + sub * subBetween(3f, 6f) * 1.5f
            doughRenderMono(out, seconds = 6f)
        }, verify = {
            val tracker = SubharmonicQuadPLL()
            val run = subRender(subBassline().lowpass(150f).through(Subharmonic(tracker)), 6f, tracker, everySeconds = 0.002)
            val e = noteError(run, 0)
            report("bass-octave quad", e)
            check(e.median < 0.5) { "quad PLL is not on the bassline: median error ${e.median} Hz" }
            check(e.settleMs < 80) { "quad PLL takes ${e.settleMs} ms to reach a new note" }
        }),

        Scene("bass-trackers", render = {
            val (out, _) = trackersGraph()
            doughRenderMono(out, seconds = 12f)
        }, verify = {
            val (out, trackers) = trackersGraph()
            val run = subRender(out, 12f, *trackers.toTypedArray(), everySeconds = 0.002)
            val (zero, pll, quiet, quad) = trackers.indices.map { noteError(run, it) }
            report("zero-cross", zero); report("pll 30 Hz", pll); report("pll 30 Hz at -12 dB", quiet); report("quad", quad)
            check(zero.median < 0.1) { "zero-crossing tracker is off a clean bassline: ${zero.median} Hz" }
            check(pll.median < 2.0) { "plain PLL at 30 Hz does not lock: ${pll.median} Hz" }
            check(quiet.median > 5.0) { "the page says the plain PLL loses the line at -12 dB; it did not (${quiet.median} Hz)" }
            check(quad.median < 0.5 && quad.p90 < pll.p90) { "quad PLL not steadier than the plain one: ${quad.p90} vs ${pll.p90}" }
        }),

        Scene("divisions", render = {
            val (out, _) = divisionsGraph()
            doughRenderMono(out, seconds = 18f)
        }, verify = {
            val (out, single) = divisionsGraph()
            val run = subRender(out, 18f, single, everySeconds = 0.005)
            for ((from, div) in listOf(0.0 to 2.0, 3.0 to 3.0, 6.0 to 1.5, 9.0 to 2.03)) {
                val ratios = run.times.indices.filter { run.times[it] in (from + 0.5)..(from + 2.9) }
                    .map { run.hz[0][it] / run.subHz[0][it] }.sorted()
                val r = ratios[ratios.size / 2]
                println("  divisions: from ${from}s tracked/generated = ${"%.3f".format(r)}")
                check(abs(r - div) < 0.01) { "division at ${from}s is $r, page says $div" }
            }
            val wob = run.times.indices.filter { run.times[it] in 12.5..14.9 }.map { run.hz[0][it] / run.subHz[0][it] }
            check(wob.max() - wob.min() > 0.1) { "the wobble does not move the division: ${wob.min()}..${wob.max()}" }
        }),

        Scene("kick", render = {
            val (out, _) = kickGraph()
            doughRenderMono(out, seconds = 12f)
        }, verify = {
            val (out, tracker) = kickGraph()
            val run = subRender(out, 12f, tracker, everySeconds = 0.002)
            // the body of each kick, 100–400 ms after the hit, sits at ~49 Hz
            val body = run.times.indices.filter { (run.times[it] % 0.5) in 0.1..0.4 }.map { run.hz[0][it] }.sorted()
            val med = body[body.size / 2]
            println("  kick: tracked body ${"%.1f".format(med)} Hz")
            check(med in 45.0..53.0) { "kick body tracked at $med Hz, the page says about 49" }
            val raw = subOnly { s, _ -> s }; val sat = subOnly { s, _ -> s.through(Saturator(Param(20f))).lowpass(400f) }
            val rawAbove = energyAbove(raw, 100f); val satAbove = energyAbove(sat, 100f)
            println("  kick: sub peak ${"%.3f".format(raw.maxOf { abs(it) })}")
            println("  kick: share of sub energy above 100 Hz raw=${"%.3f".format(rawAbove)} saturated=${"%.3f".format(satAbove)}")
            check(satAbove > 10 * rawAbove) { "saturation did not move the sub's energy into audible harmonics" }
        }),

        Scene("arpy", render = {
            val (out, _) = arpyGraph()
            doughRenderMono(out, seconds = 8f)
        }, verify = {
            val (out, tracker) = arpyGraph()
            val run = subRender(out, 8f, tracker, everySeconds = 0.002)
            val notes = listOf(0, 2, 4, 3, 0, 1, 4, 2)
            val errs = run.times.indices.filter { (run.times[it] % 0.5) in 0.1..0.25 }.map {
                val expected = ARPY_HZ[notes[(run.times[it] / 0.5).toInt() % 8]] * 0.5
                abs(run.hz[0][it] / expected - 1)
            }.sorted()
            println("  arpy: median relative error ${"%.4f".format(errs[errs.size / 2])}")
            check(errs[errs.size / 2] < 0.02) { "arpy line not tracked: median relative error ${errs[errs.size / 2]}" }
        }),

        Scene("film-naive", render = {
            val voice = SamplesSignal(subFilmClip())
            val tracker = SubharmonicZeroCross(minInputHz = 60.0)
            val sub = voice.through(Subharmonic(tracker))
            doughRenderMono(voice + sub * 1.5f, seconds = 7.5f)
        }, verify = {
            val tracker = SubharmonicZeroCross(minInputHz = 60.0)
            val agree = filmAgreement(tracker, SamplesSignal(subFilmClip()).through(Subharmonic(tracker)))
            println("  film-naive: ${"%.0f".format(agree * 100)}% of voiced frames within 6% of YIN")
            check(agree < 0.15) { "the page says the raw zero-crossing tracker mostly misses the voice; it agreed $agree" }
        }),

        Scene("film-shaped", render = {
            val (out, _) = filmShapedGraph()
            doughRenderMono(out, seconds = 13.5f)
        }, verify = {
            val (out, tracker) = filmShapedGraph()
            val agree = filmAgreement(tracker, out)
            println("  film-shaped: ${"%.0f".format(agree * 100)}% of voiced frames within 6% of YIN")
            check(agree > 0.6) { "shaped quad PLL agrees with YIN on only $agree of voiced frames" }
        }),
    )

    // ── the graphs, as the page writes them ───────────────────────────────────────

    /** Recorded plucks: four seconds dry, then a tracked harmonic or rational bank. */
    fun phaseBankGraph(lattice: Boolean = false): Pair<Signal, HarmonicPhaseTracker> {
        val notes = listOf(0, 2, 4, 3, 0, 1, 4, 2)
        val hits = (0 until 16).map { k -> k * 0.5 to subDirt("arpy:${notes[k % 8]}", rate = 0.5) }
        val plucks = SamplesSignal(subSequence(8.0, hits))
        val tracker = HarmonicPhaseTracker(minHz = 100.0, maxHz = 250.0, initialHz = 150.0)
        val analysis = TrackedPhaseAnalysis(plucks, tracker)
        val voices = if (lattice) TrackedPhaseBank.rationalLattice()
            else TrackedPhaseBank.harmonics(8) { n -> 0.5 / n }
        val colored = analysis.synthesize(TrackedPhaseBank(voices = voices))
        return plucks * subBetween(0f, 4f) + colored * subBetween(4f, 8f) to tracker
    }

    /** Four trackers on one bassline, each heard for three seconds. */
    fun trackersGraph(): Pair<Signal, List<SubharmonicTracker>> {
        val bass = subBassline()
        val clean = bass.lowpass(150f)
        val zero = SubharmonicZeroCross()
        val pll = SubharmonicPLL(bandwidthHz = 30.0)
        val quiet = SubharmonicPLL(bandwidthHz = 30.0)
        val quad = SubharmonicQuadPLL()
        val subs = clean.through(Subharmonic(zero)) * subBetween(0f, 3f) +
            clean.through(Subharmonic(pll)) * subBetween(3f, 6f) +
            (clean * 0.25f).through(Subharmonic(quiet)) * subBetween(6f, 9f) * 4f +
            clean.through(Subharmonic(quad)) * subBetween(9f, 12f)
        return bass * 0.3f + subs * 1.5f to listOf(zero, pll, quiet, quad)
    }

    /** One tracker, six ways of turning its pitch into a tone. */
    fun divisionsGraph(): Pair<Signal, SubharmonicTracker> {
        val bass = subBassline(octave = 2f)
        val clean = bass.lowpass(300f)
        val steps = Param(2f).apply {
            supplier = { when { time < 3f -> 2f; time < 6f -> 3f; time < 9f -> 1.5f; time < 12f -> 2.03f; else -> 2f } }
        }
        val wobble = SineOsc(Param(3f)) * 0.07f * subBetween(12f, 15f)
        val single = SubharmonicQuadPLL()
        val stack = SubharmonicQuadPLL().apply { voices = listOf(SubVoice(2.0, 0.7), SubVoice(3.0, 0.2), SubVoice(4.0, 0.1)) }
        val sub = clean.through(Subharmonic(single, division = steps + wobble)) * subBetween(0f, 15f) +
            clean.through(Subharmonic(stack)) * subBetween(15f, 18f)
        return bass * 0.25f + sub * 1.5f to single
    }

    /** Twenty-four `bd:3` kicks: dry, with the raw sub, with the saturated sub. */
    fun kickGraph(): Pair<Signal, SubharmonicTracker> {
        val kick = subDirt("bd:3")
        val beat = SamplesSignal(subSequence(12.0, (0 until 24).map { it * 0.5 to kick }))
        val tracker = SubharmonicQuadPLL()
        val sub = beat.lowpass(200f).through(Subharmonic(tracker))
        val audible = sub.through(Saturator(Param(20f))).lowpass(400f)
        return beat + sub * subBetween(4f, 8f) * 2f + audible * subBetween(8f, 12f) * 0.5f to tracker
    }

    /** The kick's sub alone, through [post], for the saturation check. */
    private fun subOnly(post: (Signal, SubharmonicTracker) -> Signal): FloatArray {
        val beat = SamplesSignal(subSequence(4.0, (0 until 8).map { it * 0.5 to subDirt("bd:3") }))
        val tracker = SubharmonicQuadPLL()
        val sub = beat.lowpass(200f).through(Subharmonic(tracker))
        return doughRenderMono(post(sub, tracker), seconds = 4f)
    }

    /** Fundamentals of `arpy:0`..`arpy:4` at their recorded speed, measured from the pinned files. */
    val ARPY_HZ = listOf(261.6, 293.7, 349.2, 392.0, 440.0)

    /** An arpy line an octave down, then highpassed with the sub underneath. */
    fun arpyGraph(): Pair<Signal, SubharmonicTracker> {
        val notes = listOf(0, 2, 4, 3, 0, 1, 4, 2)
        val hits = (0 until 16).map { k -> k * 0.5 to subDirt("arpy:${notes[k % 8]}", rate = 0.5) }
        val arpy = SamplesSignal(subSequence(8.0, hits))
        val tracker = SubharmonicQuadPLL()
        val sub = arpy.lowpass(300f).lowpass(300f)
            .through(Subharmonic(tracker))
        val thin = arpy.highpass(400f)
        return arpy * subBetween(0f, 4f) + (thin + sub * 1.5f) * subBetween(4f, 8f) to tracker
    }

    /** The film voice band-limited and squared up for the tracker, the sub scaled by the voice's level. */
    fun filmShapedGraph(): Pair<Signal, SubharmonicTracker> {
        val voice = SamplesSignal(subFilmClip())
        val band = voice.highpass(70f)
            .lowpass(280f)
            .lowpass(280f)
        val squared = band.through(Saturator(Param(20f))).lowpass(280f)
        val tracker = SubharmonicQuadPLL(bandwidthHz = 20.0, gateLevel = 0.05)
        val level = EnvelopeFollower(band, attackTime = Param(0.01f), releaseTime = Param(0.08f))
        val sub = squared.through(Subharmonic(tracker)).lowpass(160f) * level
        return voice + sub * 3f to tracker
    }

    // ── checks ─────────────────────────────────────────────────────────────────────

    class NoteError(val median: Double, val settleMs: Double, val p90: Double)

    private fun report(label: String, e: NoteError) =
        println("  %-22s median |error| %6.2f Hz, p90 %6.2f Hz, settles in %4.0f ms".format(label, e.median, e.p90, e.settleMs))

    /**
     * How far tracker [index] in [run] sits from the bassline note over the second half of every
     * note, and how long after each change it takes to come within 2 Hz (median over notes).
     */
    fun noteError(run: SubRender, index: Int): NoteError {
        val errs = ArrayList<Double>()
        val settles = ArrayList<Double>()
        var noteStart = 0.0; var settled = false
        for (k in run.times.indices) {
            val t = run.times[k]
            if (t >= noteStart + SUB_BASS_STEP) { noteStart += SUB_BASS_STEP; settled = false }
            val e = abs(run.hz[index][k] - subBassNoteAt(t))
            if (!settled && e < 2.0) { settled = true; settles.add((t - noteStart) * 1000) }
            if (t - noteStart > SUB_BASS_STEP / 2) errs.add(e)
        }
        errs.sort(); settles.sort()
        return NoteError(
            median = errs[errs.size / 2],
            settleMs = if (settles.isEmpty()) Double.POSITIVE_INFINITY else settles[settles.size / 2],
            p90 = errs[errs.size * 9 / 10],
        )
    }

    /** The fraction of the clip's YIN-voiced frames where [tracker], run inside [graph], is within 6%. */
    private fun filmAgreement(tracker: SubharmonicTracker, graph: Signal): Double {
        val film = subFilmClip()
        val voiced = patF0Frames(film, SR, minHz = 60.0, maxHz = 400.0).filter { it.voiced }
        val run = subRender(graph, film.size / SR.toFloat(), tracker, everySeconds = 0.0)
        var within = 0
        for (f in voiced) {
            val k = (f.centerSample / 128).coerceIn(0, run.times.size - 1)
            if (abs(run.hz[0][k] / f.hz - 1) < 0.06) within++
        }
        return within.toDouble() / voiced.size
    }

    /** Share of [a]'s energy above [hz], through a 24 dB/octave highpass. */
    private fun energyAbove(a: FloatArray, hz: Float): Double {
        val high = doughRenderMono(
            SamplesSignal(a).through(Biquad(BiquadType.HPF, Param(hz))).through(Biquad(BiquadType.HPF, Param(hz))),
            seconds = a.size / SR.toFloat(),
        )
        var hi = 0.0; var all = 0.0
        for (i in a.indices) { hi += high[i].toDouble() * high[i]; all += a[i].toDouble() * a[i] }
        return if (all < 1e-12) 0.0 else hi / all
    }

    private val byName get() = SCENES.associateBy { it.name }

    /** Offline-render scene [name]: the samples its WAV holds, before the writer normalizes them. */
    fun render(name: String): FloatArray =
        (byName[name] ?: error("unknown SubharmonicSoundDocs scene '$name'; known: ${byName.keys.sorted()}")).render()

    /** Offline-render scene [name] and write a mono 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown SubharmonicSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val samples = scene.render()
        require(samples.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.9f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "subharmonic-sound-docs").path)
        outDir.mkdirs()
        for (scene in SCENES) {
            val samples = scene.render()
            check(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${scene.name}: empty or non-finite" }
            check(samples.any { abs(it) > 1e-3f }) { "${scene.name}: silence" }
            scene.verify()
            val file = File(outDir, "subharmonic-${scene.name}.wav")
            WavFloatArrayWriter.writeMono16(file, samples, SR, normalizePeak = 0.9f)
            println("wrote ${file.absolutePath} (${"%.2f".format(samples.size.toDouble() / SR)} s)")
        }
        println("subharmonic sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
//  Public sub* helpers — the functions the page's runner blocks call. Top-level in
//  afield.audio.synth, so auto-imported into every box.
// ═══════════════════════════════════════════════════════════════════════════════════

/** An offline render plus what the watched trackers believed along the way. */
class SubRender(
    val audio: FloatArray,
    /** Seconds, one entry per reading. */
    val times: List<Double>,
    /** For each watched tracker, its [SubharmonicTracker.trackedHz] at each of [times]. */
    val hz: List<List<Double>>,
    /** For each watched tracker, the frequency it was generating: tracked Hz over its division. */
    val subHz: List<List<Double>>,
)

/**
 * Offline-render [signal] for [seconds], reading [SubharmonicTracker.trackedHz] from each of
 * [watch] every [everySeconds] (to the nearest 128-sample block; 0 reads after every block).
 */
fun subRender(
    signal: Signal,
    seconds: Float,
    vararg watch: SubharmonicTracker,
    everySeconds: Double = 0.01,
    sampleRate: Int = SubharmonicSoundDocs.SR,
): SubRender {
    val block = 128
    val ctx = SynthContext(sampleRate.toFloat(), block)
    val total = (seconds * sampleRate).toInt()
    val out = FloatArray(total)
    val buf = FloatArray(block)
    val times = ArrayList<Double>()
    val hz = List(watch.size) { ArrayList<Double>() }
    val subHz = List(watch.size) { ArrayList<Double>() }
    var written = 0
    var nextReading = 0.0
    while (written < total) {
        signal.render(buf, block, ctx)
        ctx.advanceCycle()
        val n = min(block, total - written)
        System.arraycopy(buf, 0, out, written, n)
        written += n
        val t = written.toDouble() / sampleRate
        if (t >= nextReading) {
            times.add(t)
            watch.forEachIndexed { i, tr -> hz[i].add(tr.trackedHz); subHz[i].add(tr.trackedHz / tr.division) }
            nextReading += everySeconds
        }
    }
    return SubRender(out, times, hz, subHz)
}

/** The bass notes the page's bassline plays: E2 E2 A2 G2 D2 D2 G2 B2. */
val SUB_BASS_NOTES = listOf(82.41f, 82.41f, 110.0f, 98.0f, 73.42f, 73.42f, 98.0f, 123.47f)

/** Seconds per bassline note. */
const val SUB_BASS_STEP = 0.375f

/** The note [subBassline] is playing at [t] seconds, times [octave]. */
fun subBassNoteAt(t: Double, octave: Float = 1f): Double =
    SUB_BASS_NOTES[(t / SUB_BASS_STEP).toInt() % SUB_BASS_NOTES.size].toDouble() * octave

/** The page's bassline: a sawtooth stepping through [SUB_BASS_NOTES], plucked once per note. */
fun subBassline(octave: Float = 1f): Signal {
    val pitch = Param(82.41f).apply { supplier = { SUB_BASS_NOTES[(time / SUB_BASS_STEP).toInt() % SUB_BASS_NOTES.size] * octave } }
    val pluck = Param(1f).apply { supplier = { exp(-(time % SUB_BASS_STEP) / 0.25f) } }
    return SawOsc(pitch) * pluck * 0.5f
}

/** 1 from [from] to [to] seconds and 0 elsewhere: multiply by it to hear something only then. */
fun subBetween(from: Float, to: Float): Signal = Param(0f).apply { supplier = { if (time >= from && time < to) 1f else 0f } }

/**
 * The Dirt-Samples one-shot [token] (`"arpy:2"`) from the pinned subset, folded to mono and
 * resampled to [sampleRate] played back at [rate] (0.5 is an octave down and twice as long).
 */
fun subDirt(token: String, rate: Double = 1.0, sampleRate: Int = SubharmonicSoundDocs.SR): FloatArray {
    val s = DirtRender.requirePinnedLibrary().sample(token) ?: error("no Dirt sample '$token' in the pinned subset")
    val step = s.sampleRate / sampleRate * rate
    val n = (s.lengthSamples / step).toInt()
    return FloatArray(n) { i ->
        val p = i * step
        0.5f * (s.readLeft(p) + s.readRight(p))
    }
}

/** [seconds] of silence with each `time to samples` hit mixed in at its time. */
fun subSequence(seconds: Double, hits: List<Pair<Double, FloatArray>>, sampleRate: Int = SubharmonicSoundDocs.SR): FloatArray {
    val out = FloatArray((seconds * sampleRate).toInt())
    for ((t, samples) in hits) {
        val at = (t * sampleRate).toInt()
        for (i in samples.indices) if (at + i in out.indices) out[at + i] += samples[i]
    }
    return out
}

/** The parts one after another. */
fun subConcat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
    return out
}

/**
 * *Night of the Living Dead* (Romero, 1968), 6:05 to 6:19 — "They're coming to get you, Barbara" —
 * from the public-domain print on the Internet Archive, mono at 48 kHz and peak-normalized to 0.9.
 */
fun subFilmClip(): FloatArray {
    val rel = "public/docs/assets/subharmonic_sound/notld-barbara-source.wav"
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, rel).isFile) dir = dir.parentFile
    val file = File(dir ?: error("cannot find $rel above ${File("").absolutePath}"), rel)
    val wav = WavFloatArrayLoader.loadWavToFloatArray(file.path)
    require(wav.sampleRate == SubharmonicSoundDocs.SR) { "$rel is ${wav.sampleRate} Hz, expected ${SubharmonicSoundDocs.SR}" }
    var peak = 0f
    for (v in wav.samples) peak = max(peak, abs(v))
    return FloatArray(wav.samples.size) { wav.samples[it] * 0.9f / peak }
}

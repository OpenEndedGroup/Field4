@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.four.audio.WavFloatArrayLoader
import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.Functions
import afield.stdlib.certifiedRange
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.fn.sound.roundtrip
runner: jvm-main
entrypoint: afield.four.synth.FnSoundDocs
tags: [smoke, synth, functions, audio, wav, analysis, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "fn sound roundtrip measured"
*/

/**
 * One `Functions.Fn` taken through sound and back, and the distance between what went in and what
 * came out — the harness behind `public/docs/chain_fn_sound_roundtrip.md`.
 *
 * The route is four parts of Field that do not know about each other. [contour] is built from
 * formula ops only, so `afield.stdlib.FnInterval` can certify its range on any interval. It enters a
 * Dough graph either per sample, through [FnSignal], or per block, through `Param { … }`. The graph is
 * rendered offline by [doughRenderMono], written to a 16-bit WAV **without normalisation** — so the
 * amplitude in the file is the amplitude the function asked for — and read back from that file. The
 * analysis is the in-process pair `PatOnsetDetection` and `PatPitchDetection` already provide: an RMS
 * envelope ([patEnergyEnvelope]) for loudness, and YIN ([patF0Frames]) for pitch.
 *
 * # Two encodings of one function
 *
 * * [Encoding.LOUDNESS]: a [CARRIER_HZ] sine whose amplitude is `LOUDNESS_GAIN · f(t)`. Recovered as
 *   `√2 · RMS / LOUDNESS_GAIN`. The carrier is chosen so one period is exactly 128 samples at
 *   [SR] and the RMS window, [RMS_WINDOW], holds two whole periods: then a steady sine's RMS is
 *   exactly `A/√2` and the window adds no ripple of its own.
 * * [Encoding.PITCH]: a sine at `PITCH_BASE_HZ · 2^f(t)` and constant level, so `f` spans a fraction of
 *   an octave. Recovered as `log2(hz / PITCH_BASE_HZ)` from voiced YIN frames only.
 *
 * # What is measured, per encoding and route
 *
 * Every analysis frame is one sample of the population: its recovered value against `f` at the frame
 * centre ([RoundTrip.maxAbsError], [RoundTrip.rmsError]); the delay that best aligns them
 * ([RoundTrip.bestLagSamples]); and whether the recovered value lies inside the **certified** range of
 * `f` over the samples the frame actually read ([RoundTrip.insideCertified]) — the only interval a
 * windowed measurement could honestly be expected to land in. [Blip] is the narrow feature: a bump
 * with a 3 ms sigma, shorter than both analysis windows, and how much of its height comes back.
 *
 * No engine, no device, no Field window. The renders are deterministic, so the committed `.wav`s
 * regenerate byte-stably; as a FIELD_TEST everything is written to a temp dir.
 */
object FnSoundDocs {

    /** Sample rate of every render and of both published clips. */
    const val SR: Int = 48_000

    /** Frames per [doughRenderMono] block — the granularity of a `Param { … }` supplier. */
    const val BLOCK: Int = 128

    const val SECONDS: Double = 3.0

    /** 128 samples per period at [SR], so [RMS_WINDOW] holds exactly two. */
    const val CARRIER_HZ: Double = 375.0
    const val LOUDNESS_GAIN: Double = 0.8
    const val PITCH_BASE_HZ: Double = 220.0
    const val PITCH_LEVEL: Double = 0.5

    const val RMS_WINDOW: Int = 256
    const val RMS_HOP: Int = 32

    /** The register YIN is told about: [contour] stays inside 0.2..0.9, so 253..409 Hz. */
    const val PITCH_MIN_HZ: Double = 200.0
    const val PITCH_MAX_HZ: Double = 450.0

    /** Where the narrow bump sits, its sigma, and its height above what is under it. */
    const val BLIP_AT: Double = 2.6
    const val BLIP_SIGMA: Double = 0.003
    const val BLIP_HEIGHT: Double = 0.25

    val DOMAIN: Functions.Domain = Functions.Domain(0.0, SECONDS)

    /**
     * The function taken on the trip: a resting level, a broad swell, a step up to a plateau that
     * wobbles at 6 Hz, and one bump narrower than either analysis window. Formula ops only, so it
     * certifies.
     */
    fun contour(): Functions.Fn = with(Functions) {
        val d = DOMAIN
        (c(0.2, d) +
            gaussian(0.8, 0.2, 0.5, d) +
            smoothStep(1.4, 2.0, d) * (c(0.3, d) + sin(6.0, amplitude = 0.08)) +
            gaussian(BLIP_AT, BLIP_SIGMA, BLIP_HEIGHT, d)).withName("f")
    }

    enum class Encoding { LOUDNESS, PITCH }

    /** How the function enters the graph: [FnSignal] at every sample, or `Param { … }` once a block. */
    enum class Route { PER_SAMPLE, PER_BLOCK }

    /** The frequency contour the PITCH encoding plays, `PITCH_BASE_HZ · 2^f`, still an `Fn`. */
    fun pitchHz(fn: Functions.Fn): Functions.Fn = with(Functions) {
        fn.scaleY(ln(2.0)).exp().scaleY(PITCH_BASE_HZ)
    }

    /** The Dough graph for one encoding and route. */
    fun signal(fn: Functions.Fn, encoding: Encoding, route: Route): Signal = when (encoding) {
        Encoding.LOUDNESS -> {
            val gain = with(Functions) { fn.scaleY(LOUDNESS_GAIN) }
            val amplitude: Signal = when (route) {
                Route.PER_SAMPLE -> gain.toSignal()
                Route.PER_BLOCK -> Param { gain(time.toDouble()).toFloat() }
            }
            SineOsc(Param(CARRIER_HZ.toFloat())) * amplitude
        }
        Encoding.PITCH -> {
            val hz = pitchHz(fn)
            val freq = when (route) {
                Route.PER_SAMPLE -> Param(0f).modulate(hz.toSignal())
                Route.PER_BLOCK -> Param { hz(time.toDouble()).toFloat() }
            }
            SineOsc(freq) * PITCH_LEVEL
        }
    }

    /** Offline-render one encoding and route; no normalisation anywhere. */
    fun render(encoding: Encoding, route: Route, fn: Functions.Fn = contour()): FloatArray =
        doughRenderMono(signal(fn, encoding, route), SECONDS.toFloat(), SR)

    /**
     * One analysis frame: the samples it read, `[firstSample, lastSample]`, the time the analysis
     * attributes its value to, and what it recovered — `NaN` for a YIN frame that was not voiced.
     *
     * [supportCenterSeconds] is the centre of the samples the estimate actually *compared*. For an
     * RMS frame that is [centerSeconds]. For a YIN frame it is not: `PatF0Frame.centerSample` is the
     * middle of `window + tauMax` samples, but the difference function at the lag YIN picked, `tau`,
     * only reads `window + tau` of them, so for any pitch above `minHz` the estimate is really about
     * an instant `(tauMax - tau) / 2` samples earlier than the one it is attributed to.
     */
    data class Frame(
        val firstSample: Int,
        val lastSample: Int,
        val centerSeconds: Double,
        val recovered: Double,
        val supportCenterSeconds: Double = centerSeconds,
    )

    /** Analyse samples that were read back from a `.wav`. */
    fun analyse(samples: FloatArray, encoding: Encoding): List<Frame> = when (encoding) {
        Encoding.LOUDNESS -> patEnergyEnvelope(samples, RMS_HOP, RMS_WINDOW).mapIndexed { i, rms ->
            val first = i * RMS_HOP
            Frame(first, first + RMS_WINDOW - 1, (first + (RMS_WINDOW - 1) / 2.0) / SR, sqrt(2.0) * rms / LOUDNESS_GAIN)
        }
        Encoding.PITCH -> {
            val tauMax = (SR / PITCH_MIN_HZ).toInt()
            patF0Frames(samples, SR, PITCH_MIN_HZ, PITCH_MAX_HZ).map { f ->
                val voiced = f.voiced && f.hz > 0.0
                val value = if (voiced) ln(f.hz / PITCH_BASE_HZ) / ln(2.0) else Double.NaN
                val window = f.spanSamples - tauMax
                val tau = if (voiced) SR / f.hz else tauMax.toDouble()
                Frame(
                    f.startSample, f.startSample + f.spanSamples - 1, f.centerSample.toDouble() / SR, value,
                    supportCenterSeconds = (f.startSample + (window + tau - 1.0) / 2.0) / SR,
                )
            }
        }
    }

    /** How much of the narrow bump came back: its true height over the base, and the recovered one. */
    data class Blip(val truePeak: Double, val base: Double, val recoveredPeak: Double) {
        /** Recovered height above the base as a fraction of the true height; 1.0 is all of it. */
        val survival: Double get() = (recoveredPeak - base) / (truePeak - base)
    }

    /** Everything one trip measured. [wav] is the file the analysis read. */
    class RoundTrip(
        val encoding: Encoding,
        val route: Route,
        val fn: Functions.Fn,
        val wav: File,
        val frames: List<Frame>,
    ) {
        /** The frames with a recovered value: every RMS frame, and the voiced YIN frames. */
        val measured: List<Frame> = frames.filter { it.recovered.isFinite() }

        /** `recovered − f(centre)` per measured frame. */
        val errors: DoubleArray = DoubleArray(measured.size) { measured[it].recovered - fn(measured[it].centerSeconds) }

        val maxAbsError: Double = errors.maxOfOrNull { abs(it) } ?: Double.NaN
        val rmsError: Double = rms(errors)

        /**
         * The delay, in samples, that minimises the RMS error when `f` is read `lag` samples earlier
         * than each frame centre, over `-BLOCK*2 .. BLOCK*2`. Positive means the sound is late.
         */
        val bestLagSamples: Int
        val rmsErrorAtBestLag: Double

        /** [bestLagSamples], measured from each frame's [Frame.supportCenterSeconds] instead. */
        val bestLagFromSupportSamples: Int

        init {
            val (lag, r) = bestLag { it.centerSeconds }
            bestLagSamples = lag
            rmsErrorAtBestLag = r
            bestLagFromSupportSamples = bestLag { it.supportCenterSeconds }.first
        }

        private fun bestLag(time: (Frame) -> Double): Pair<Int, Double> {
            var best = 0
            var bestRms = Double.POSITIVE_INFINITY
            for (lag in -2 * BLOCK..2 * BLOCK) {
                var sum = 0.0
                for (f in measured) {
                    val e = f.recovered - fn(time(f) - lag.toDouble() / SR)
                    sum += e * e
                }
                val r = sqrt(sum / measured.size.coerceAtLeast(1))
                if (r < bestRms) { bestRms = r; best = lag }
            }
            return best to bestRms
        }

        /** Where the largest error is, in seconds. */
        val maxErrorAtSeconds: Double = errors.indices.maxByOrNull { abs(errors[it]) }?.let { measured[it].centerSeconds } ?: Double.NaN

        /**
         * Per measured frame, whether its value lies inside the certified range of `f` over the
         * samples that frame read. Bounds are `certifiedRange`'s proven ones, not sampled ones.
         */
        private val outside = DoubleArray(measured.size)
        val insideCertified: BooleanArray = BooleanArray(measured.size) { i ->
            val f = measured[i]
            // A bound is proven whether or not the search converged; convergence only says how
            // close it came to the reached value. A refusal (no bounds at all) is an error.
            val range = fn.certifiedRange(f.firstSample.toDouble() / SR, f.lastSample.toDouble() / SR, tolerance = 1e-6, maxEnclosures = 4_000)
            val lo = checkNotNull(range.min) { "no certified bound over frame $i: ${range.refusal}" }
            val hi = checkNotNull(range.max) { "no certified bound over frame $i: ${range.refusal}" }
            outside[i] = maxOf(0.0, lo.bound - f.recovered, f.recovered - hi.bound)
            outside[i] == 0.0
        }
        val insideCount: Int = insideCertified.count { it }

        /** The furthest any frame's value lies outside its certified range; `0.0` when none does. */
        val worstOutside: Double get() = outside.maxOrNull() ?: 0.0

        val blip: Blip = run {
            val near = measured.filter { abs(it.centerSeconds - BLIP_AT) <= 0.05 }
            val truth = fn.certifiedRange(BLIP_AT - 0.05, BLIP_AT + 0.05, tolerance = 1e-9)
            Blip(
                truePeak = truth.max!!.value,
                base = fn(BLIP_AT) - BLIP_HEIGHT,
                recoveredPeak = near.maxOfOrNull { it.recovered } ?: Double.NaN,
            )
        }

        /** The recovered curve between frame centres, linearly; `NaN` across an unvoiced frame. */
        fun recoveredAt(t: Double): Double {
            if (frames.isEmpty()) return Double.NaN
            var lo = 0
            var hi = frames.size - 1
            if (t <= frames[lo].centerSeconds) return frames[lo].recovered
            if (t >= frames[hi].centerSeconds) return frames[hi].recovered
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (frames[mid].centerSeconds <= t) lo = mid else hi = mid
            }
            val a = frames[lo]
            val b = frames[hi]
            val u = (t - a.centerSeconds) / (b.centerSeconds - a.centerSeconds)
            return a.recovered + (b.recovered - a.recovered) * u
        }

        /** `recoveredAt(t) − f(t)`. */
        fun errorAt(t: Double): Double = recoveredAt(t) - fn(t)

        fun describe(): String =
            "${encoding.name.lowercase().padEnd(8)} ${route.name.lowercase().padEnd(10)} " +
                "frames=${measured.size}/${frames.size} max|e|=${"%.5f".format(maxAbsError)} " +
                "rms=${"%.5f".format(rmsError)} at=${"%.4f".format(maxErrorAtSeconds)} lag=$bestLagSamples " +
                "rms@lag=${"%.5f".format(rmsErrorAtBestLag)} supportLag=$bestLagFromSupportSamples " +
                "inside=$insideCount/${measured.size} worstOutside=${"%.2e".format(worstOutside)} " +
                "blip=${"%.3f".format(blip.survival)}"
    }

    /** Render, write the `.wav` into [dir], read it back from the file, and analyse what was read. */
    fun roundTrip(
        encoding: Encoding,
        route: Route = Route.PER_SAMPLE,
        dir: File = File(System.getProperty("java.io.tmpdir"), "fn-sound-roundtrip"),
        fn: Functions.Fn = contour(),
    ): RoundTrip {
        dir.mkdirs()
        val wav = File(dir, "${encoding.name.lowercase()}-${route.name.lowercase()}.wav")
        WavFloatArrayWriter.writeMono16(wav, render(encoding, route, fn), SR, normalizePeak = null)
        val readBack = WavFloatArrayLoader.loadWavToFloatArray(wav.path)
        check(readBack.sampleRate == SR) { "read back ${readBack.sampleRate} Hz from $wav, wrote $SR" }
        return RoundTrip(encoding, route, fn, wav, analyse(readBack.samples, encoding))
    }

    private val SCENES: Map<String, Encoding> = mapOf("loudness" to Encoding.LOUDNESS, "pitch" to Encoding.PITCH)

    /** Offline-render scene `loudness` or `pitch` (the per-sample route) to a mono 16-bit WAV. */
    fun renderWav(name: String, outPath: String) {
        val encoding = SCENES[name] ?: error("unknown FnSoundDocs scene '$name'; known: ${SCENES.keys.sorted()}")
        val samples = render(encoding, Route.PER_SAMPLE)
        require(samples.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = null)
    }

    private fun rms(values: DoubleArray): Double =
        if (values.isEmpty()) Double.NaN else sqrt(values.sumOf { it * it } / values.size)

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "fn-sound-roundtrip").path)
        dir.mkdirs()
        val fn = contour()
        val whole = fn.certifiedRange(0.0, SECONDS, tolerance = 1e-9)
        println("f over [0, $SECONDS]: certified min ${whole.min!!.value} .. max ${whole.max!!.value}")

        val trips = Encoding.values().flatMap { e -> Route.values().map { r -> roundTrip(e, r, dir, fn) } }
        trips.forEach { println(it.describe()) }
        fun trip(e: Encoding, r: Route) = trips.single { it.encoding == e && it.route == r }

        // The structural claims the page makes, checked here so the smoke fails when one stops holding.
        check(trip(Encoding.LOUDNESS, Route.PER_SAMPLE).bestLagSamples == 0) { "FnSignal is no longer on time" }
        check(trip(Encoding.LOUDNESS, Route.PER_BLOCK).bestLagSamples == BLOCK) { "Param { } is no longer one block late" }
        check(abs(trip(Encoding.PITCH, Route.PER_SAMPLE).bestLagFromSupportSamples) <= 3) {
            "re-centring YIN frames on the samples they compared no longer removes their lag"
        }
        check(trips.all { it.measured.isNotEmpty() && it.insideCount > 0 }) { "a trip measured nothing" }

        // The published clips are the analysed files: the fence renderer writes the same bytes.
        SCENES.forEach { (name, encoding) ->
            val published = File(dir, "published-$name.wav")
            renderWav(name, published.path)
            val analysed = trips.single { it.encoding == encoding && it.route == Route.PER_SAMPLE }.wav
            check(published.readBytes().contentEquals(analysed.readBytes())) {
                "renderWav('$name') and the analysed $analysed differ"
            }
        }
        println("published clips are byte-identical to the analysed files")
        println("fn sound roundtrip measured")
    }
}

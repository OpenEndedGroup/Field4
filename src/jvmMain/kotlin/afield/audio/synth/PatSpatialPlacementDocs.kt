@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import java.io.File
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.pat-spatial.placement
runner: jvm-main
entrypoint: afield.audio.synth.PatSpatialPlacementDocs
tags: [smoke, audio, synth, steamaudio, docs, wasm, dough, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "pat spatial placement docs rendered"
*/

/**
 * The two placed, binaural clips on `pat_spatial.md`: the page's own `patSpatial` and
 * `showPatSpatial` examples, through Steam Audio's HRTF, with a still listener.
 *
 * Rendered offline exactly as [PatSpatialHeadTrackedDocs] renders the head-tracked clip: the real
 * [patSpatial] launch, its render thread filling the ring, and the bank pulled one block at a time,
 * so the file is what the launch would play. Needs `libphonon` (`./gradlew fetchSteamAudioNatives`).
 */
object PatSpatialPlacementDocs {

    /** [patSpatial]'s default rate. */
    const val SR = 48_000

    /** Frames pulled from the bank per block. */
    private const val BLOCK = 512

    /** One published clip: a pattern from the page, and how long to play it. */
    class Figure(val name: String, val seconds: Double, val cps: Double, val maxSources: Int, val pattern: () -> PatPattern<String>)

    /** The figures, in page order. */
    val FIGURES: List<Figure> = listOf(
        // "Placing a source": whole cycles alternate between the kit and hats groups.
        Figure("kit-hats", seconds = 6.0, cps = 2.0, maxSources = 2) {
            pat("bd ~ sd ~, hh*8")
                .with(
                    paramOf("spat", pat("<kit hats>")),
                    param("az", sine().range(-70.0, 70.0)),
                    param("dist", "1 3 1 6"),
                )
        },
        // "Hearing it in a box": a four-note line moving between two groups.
        Figure("four-note-line", seconds = 6.0, cps = 2.0, maxSources = 2) {
            pat("60 64 67 71")
                .with(
                    paramOf("spat", pat("a b a b")),
                    param("az", sine().range(-85.0, 85.0)),
                    paramOf("dist", pat("1.2 3 1.2 5")),
                    param("lpf", 2200),
                )
        },
    )

    private val byName = FIGURES.associateBy { it.name }

    /** Looks a figure up by name, with a message that lists the alternatives. */
    fun figure(name: String): Figure =
        byName[name] ?: error("unknown PatSpatialPlacementDocs figure '$name'; known: ${byName.keys.sorted()}")

    /** Renders figure [name] to interleaved stereo at [SR]. */
    fun render(name: String): FloatArray {
        val f = figure(name)
        val total = (f.seconds * SR).toInt()
        val out = FloatArray(total * 2)
        val launch = patSpatial(f.pattern(), cps = f.cps, sampleRate = SR, maxSources = f.maxSources,
            listenerPoseAt = { HeadPose() })
        try {
            check(launch.awaitReady()) { "the spatial room never became ready" }
            val ctx = SynthContext(SR.toFloat(), BLOCK)
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var at = 0
            while (at < total) {
                while (launch.room.ring.fill < BLOCK) {
                    launch.room.failure?.let { throw IllegalStateException("render thread failed", it) }
                    Thread.sleep(1L)
                }
                java.util.Arrays.fill(l, 0f)
                java.util.Arrays.fill(r, 0f)
                ctx.cycleId = at.toLong() / BLOCK + 1
                ctx.time = at.toFloat() / SR
                launch.bank.render(l, r, BLOCK, ctx)
                val n = minOf(BLOCK, total - at)
                for (i in 0 until n) {
                    out[2 * (at + i)] = l[i]
                    out[2 * (at + i) + 1] = r[i]
                }
                at += BLOCK
            }
            val underruns = launch.room.ring.underruns
            check(underruns == 0L) { "$underruns frames of underrun: the file would not be the render" }
        } finally {
            launch.close()
        }
        return out
    }

    /** Renders [name] to a stereo 16-bit WAV: the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val interleaved = render(name)
        val frames = interleaved.size / 2
        val left = FloatArray(frames) { interleaved[2 * it] }
        val right = FloatArray(frames) { interleaved[2 * it + 1] }
        require(left.any { it != 0f } || right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, left, right, SR, normalizePeak = 0.9f)
    }

    /** RMS of channel [channel] of [interleaved] over frames [from] until [to]. */
    private fun rms(interleaved: FloatArray, channel: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) {
            val v = interleaved[2 * i + channel].toDouble()
            sum += v * v
        }
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "pat-spatial-placement").path)
        outDir.mkdirs()
        FIGURES.forEach { f ->
            val a = render(f.name)
            val frames = a.size / 2
            // Left/right balance per quarter cycle: a source that sweeps in azimuth swings this. A
            // window of a whole cycle would average one sweep of `sine()` back to the middle.
            val half = (SR / f.cps / 4).toInt()
            val balances = (0 until frames / half).map { w ->
                rms(a, 0, w * half, (w + 1) * half) / rms(a, 1, w * half, (w + 1) * half).coerceAtLeast(1e-9)
            }
            println("  %-16s L/R per quarter cycle: %s".format(f.name, balances.joinToString(" ") { "%.2f".format(it) }))
            check(balances.max() / balances.min() > 1.5) { "${f.name}: the image does not move across the ears" }
            val file = File(outDir, "${f.name}.wav")
            renderWav(f.name, file.absolutePath)
            println("  wrote ${file.absolutePath}")
        }
        println("pat spatial placement docs rendered: ${FIGURES.size} figure(s)")
    }
}

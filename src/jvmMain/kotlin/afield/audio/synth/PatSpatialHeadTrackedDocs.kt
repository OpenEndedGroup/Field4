@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.examples.steamaudio.headTrackedPose
import afield.examples.steamaudio.renderHeadTracked
import afield.audio.engine.WavFloatArrayWriter
import java.io.File
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.pat-spatial.head-tracked
runner: jvm-main
entrypoint: afield.audio.synth.PatSpatialHeadTrackedDocs
tags: [smoke, audio, synth, steamaudio, docs, wasm, dough, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "pat spatial head-tracked docs rendered"
*/

/**
 * The binaural clip on `pat_spatial.md`: `HeadTrackedPatternDemo`'s four movements, through Steam
 * Audio's HRTF, at [MOVEMENT_SECONDS] a movement.
 *
 * This is the spatialised output itself, not the mono source [PatSpatialSoundDocs] publishes. It
 * renders offline because nothing in [patSpatial] is paced by a clock (see
 * [PatSpatialLaunch.advanceOffline]), and it repeats exactly because the head is a function of the
 * render frame ([headTrackedPose], through `listenerPoseAt`) rather than a pose posted from another
 * thread. It needs `libphonon`, which is not committed: `./gradlew fetchSteamAudioNatives`.
 *
 * The FIELD_TEST renders twice and checks:
 *
 *  * the two renders are **identical**, which is what [DocsAudioProvenance] needs of a producer;
 *  * leaning in and out (movement 4) changes the level, half-second to half-second, by at least
 *    [LEAN_SWING_FLOOR] where the still head (movement 1) stays within [STILL_SWING_CEILING]. That is
 *    the movement whose effect a level can show; turning and stepping move the image across the
 *    ears symmetrically and average out.
 */
object PatSpatialHeadTrackedDocs {

    /** Seconds per movement on the page; the Gradle demo uses eight. */
    const val MOVEMENT_SECONDS = 5.0

    /** The demo's own rate. */
    const val SR = 44_100

    /** Movement 4's loudest half-second over its quietest must reach this. */
    const val LEAN_SWING_FLOOR = 1.5

    /** Movement 1's loudest half-second over its quietest must stay under this. */
    const val STILL_SWING_CEILING = 1.15

    /** Renders [name] (only `head-tracked`) to a stereo 16-bit WAV: the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        require(name == "head-tracked") { "unknown PatSpatialHeadTrackedDocs figure '$name'; known: [head-tracked]" }
        val render = renderHeadTracked(MOVEMENT_SECONDS, SR)
        val frames = render.interleaved.size / 2
        val left = FloatArray(frames) { render.interleaved[2 * it] }
        val right = FloatArray(frames) { render.interleaved[2 * it + 1] }
        require(left.any { it != 0f } || right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, left, right, SR, normalizePeak = 0.9f)
    }

    /** Loudest half-second RMS over quietest, within movement [movement] of [interleaved]. */
    private fun swing(interleaved: FloatArray, movement: Int): Double {
        val movementFrames = (MOVEMENT_SECONDS * SR).toInt()
        val window = SR / 2
        val levels = (0 until movementFrames / window).map { w ->
            val from = movement * movementFrames + w * window
            var sum = 0.0
            for (i in from until from + window) {
                val l = interleaved[2 * i].toDouble()
                val r = interleaved[2 * i + 1].toDouble()
                sum += l * l + r * r
            }
            sqrt(sum / (2 * window))
        }
        return levels.max() / levels.min().coerceAtLeast(1e-12)
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val first = renderHeadTracked(MOVEMENT_SECONDS, SR)
        first.readout.forEach(::println)
        val second = renderHeadTracked(MOVEMENT_SECONDS, SR)
        check(first.interleaved.contentEquals(second.interleaved)) {
            "two renders differ: the head is not on the render's timeline"
        }

        val still = swing(first.interleaved, 0)
        val lean = swing(first.interleaved, 3)
        println("  half-second level swing: still %.2f, leaning %.2f".format(still, lean))
        check(still < STILL_SWING_CEILING) { "the still head's level moves by $still" }
        check(lean >= LEAN_SWING_FLOOR) { "leaning in and out barely changes the level: $lean" }

        val out = File(System.getProperty("java.io.tmpdir"), "pat-spatial-head-tracked.wav")
        renderWav("head-tracked", out.absolutePath)
        println("pat spatial head-tracked docs rendered: ${out.absolutePath}")
    }
}

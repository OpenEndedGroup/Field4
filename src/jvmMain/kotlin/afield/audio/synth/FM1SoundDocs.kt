@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter

/*
FIELD_TEST
id: synth.fm1.sound
runner: jvm-main
entrypoint: afield.audio.synth.FM1SoundDocs
tags: [smoke, synth, audio, wav, docs, wasm, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "fm1 sound docs rendered"
*/

/**
 * The clips on `fm1.md`: factory projects 0 and 1 of the FM-1 simulator, with the sequencer started
 * the way a person starts it, by tapping PLAY.
 *
 * Each clip gets its own [FM1Sim] from [FM1Sim.create], pulled through [FM1SimNode] in 128-frame
 * blocks at 48 kHz with no audio device, so it is the same render path a launched box plays.
 */
object FM1SoundDocs {

    /** Sample rate of every clip. */
    const val SR = 48_000

    /** Frames per block, the audio engine's. */
    const val BLOCK = 128

    /** Seconds the project is left to load before PLAY is tapped. */
    const val SETTLE_SECONDS = 0.5

    /** Seconds of the running sequencer in each clip. */
    const val PLAY_SECONDS = 6.0

    /** The clips, by the name their fences ask for, and the project slot each plays. */
    val FIGURES: Map<String, Int> = mapOf("project-0" to 0, "project-1" to 1)

    /** Renders clip [name] as left and right channels: the running sequencer, without the settle. */
    fun render(name: String): Pair<FloatArray, FloatArray> {
        val slot = FIGURES[name] ?: error("unknown FM1SoundDocs figure '$name'; known: ${FIGURES.keys}")
        FM1Sim.create().use { sim ->
            sim.preset(slot)
            val node = FM1SimNode(sim)
            val ctx = SynthContext(SR.toFloat(), BLOCK)
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var block = 0L
            fun pull() {
                ctx.cycleId = ++block
                ctx.time = (block - 1).toFloat() * BLOCK / SR
                node.render(l, r, BLOCK, ctx)
            }
            repeat((SETTLE_SECONDS * SR / BLOCK).toInt()) { pull() }
            sim.play()
            val frames = (PLAY_SECONDS * SR).toInt()
            val left = FloatArray(frames)
            val right = FloatArray(frames)
            var at = 0
            while (at < frames) {
                pull()
                val n = minOf(BLOCK, frames - at)
                System.arraycopy(l, 0, left, at, n)
                System.arraycopy(r, 0, right, at, n)
                at += BLOCK
            }
            return left to right
        }
    }

    /** Offline-renders clip [name] to a stereo 16-bit WAV at [outPath]: the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val (left, right) = render(name)
        require(left.any { it != 0f } || right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, left, right, SR, normalizePeak = 0.9f)
    }

    /** Renders each clip twice and checks it is sound and that the two renders are identical. */
    @JvmStatic
    fun main(args: Array<String>) {
        for (name in FIGURES.keys) {
            val (a, _) = render(name)
            val (b, _) = render(name)
            val peak = a.maxOf { kotlin.math.abs(it) }
            check(peak > 0.02f) { "$name: effectively silent (peak $peak)" }
            check(a.contentEquals(b)) { "$name: two renders differ, so a published clip could not be reproduced" }
            println("$name: ${a.size} frames, peak ${"%.3f".format(peak)}, deterministic")
        }
        println("fm1 sound docs rendered")
    }
}

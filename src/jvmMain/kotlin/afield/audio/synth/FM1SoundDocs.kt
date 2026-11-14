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
 * The clips on `fm1.md`, each rendered offline from a fresh [FM1Sim.create] through [FM1SimNode] in
 * 128-frame blocks at 48 kHz with no audio device: the same render path a launched box plays.
 *
 * `project-0` and `project-1` are factory projects with PLAY tapped. The others put Field in front
 * of the simulator: `pattern` lays a Field pattern on ACID ALLEY's DX7 track, `cutoff-lfo` lets a
 * slow sine turn the 303's filter, and `voice-morph` plays one phrase eight times while the voice
 * moves from the factory piano to a bell built in Field.
 */
object FM1SoundDocs {

    /** Sample rate of every clip. */
    const val SR = 48_000

    /** Frames per block, the audio engine's. */
    const val BLOCK = 128

    /** Seconds the project is left to load before anything starts. */
    const val SETTLE_SECONDS = 0.5

    /** Seconds of each clip. */
    const val PLAY_SECONDS = 6.0

    /** A clip: the factory project it starts from, how long it runs, and what it does once loaded. */
    private class Figure(val slot: Int, val seconds: Double = PLAY_SECONDS, val begin: (FM1Sim) -> Unit)

    /** The bell [voiceMorph] ends on: the factory piano with a brighter algorithm and inharmonic ratios. */
    fun bell(piano: Dx7Voice): Dx7Voice = piano.copy().apply {
        algorithm = 5
        feedback = 6
        op(2).coarse = 7
        op(4).coarse = 3
        op(4).fine = 41
        op(6).coarse = 11
        name = "BELLPIANO"
    }

    private val figures: Map<String, Figure> = linkedMapOf(
        "project-0" to Figure(0) { it.play() },
        "project-1" to Figure(1) { it.play() },
        "pattern" to Figure(1) { sim ->
            sim.start()
            sim.schedule(pat("<c5 ef5> ~ [g5 bf5] ~ c6 ~ g5 ~"), track = 2, cycles = 3, cps = sim.cps)
        },
        "cutoff-lfo" to Figure(1) { sim ->
            sim.modulate(0, FM1Sim.CC.CUTOFF, SineOsc(0.25.p) * 0.5 + 0.5)
            sim.start()
        },
        "voice-morph" to Figure(0, seconds = 8.0) { sim ->
            val piano = sim.voices(0)[0]
            val bell = bell(piano)
            for (k in 0 until 8) {
                val at = k * 1.0
                sim.morphStep(piano.lerp(bell, k / 7.0), at)
                sim.chord(0, listOf(60, 64, 67), seconds = 0.7, afterSeconds = at + 0.05)
            }
        },
    )

    /** Queue [voice] for track 0 at [atSeconds], on the simulator's clock. */
    private fun FM1Sim.morphStep(voice: Dx7Voice, atSeconds: Double) {
        val block = blocksRendered + FM1Sim.blocks(atSeconds)
        val sysex = voice.toSysex(0)
        midiAt(block, *IntArray(sysex.size) { sysex[it].toInt() and 0xff })
    }

    /** The clips, by the name their fences ask for. */
    val FIGURES: Set<String> get() = figures.keys

    /** Renders clip [name] as left and right channels, without the settle. */
    fun render(name: String): Pair<FloatArray, FloatArray> {
        val fig = figures[name] ?: error("unknown FM1SoundDocs figure '$name'; known: ${figures.keys}")
        FM1Sim.create().use { sim ->
            sim.preset(fig.slot)
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
            fig.begin(sim)
            val frames = (fig.seconds * SR).toInt()
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
        for (name in FIGURES) {
            val (a, _) = render(name)
            val (b, _) = render(name)
            val peak = a.maxOf { kotlin.math.abs(it) }
            check(peak > 0.02f) { "$name: effectively silent (peak $peak)" }
            check(a.contentEquals(b)) { "$name: two renders differ, so a published clip could not be reproduced" }
            println("$name: ${a.size} frames, peak ${"%.3f".format(peak)}, deterministic")
        }
        println("fm1 renders ${"%.1f".format(realtimeFactor())}x faster than real time")
        println("fm1 sound docs rendered")
    }

    /**
     * How many seconds of a playing factory project one second of this thread renders at 48 kHz,
     * after two warm-up seconds for GraalWasm's compiler: above 1 keeps up with the audio engine.
     */
    fun realtimeFactor(seconds: Double = 10.0, slot: Int = 1): Double = FM1Sim.create().use { sim ->
        sim.preset(slot)
        val l = FloatArray(4800)
        val r = FloatArray(4800)
        repeat(5) { sim.render(l, r, 4800, SR.toFloat()) }
        sim.start()
        repeat(20) { sim.render(l, r, 4800, SR.toFloat()) }
        val n = (seconds * 10).toInt()
        val t0 = System.nanoTime()
        repeat(n) { sim.render(l, r, 4800, SR.toFloat()) }
        seconds / ((System.nanoTime() - t0) / 1e9)
    }
}

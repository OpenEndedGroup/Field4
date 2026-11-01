@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import java.io.File
import kotlin.math.abs

/*
FIELD_TEST
id: synth.faust.single.sound
runner: jvm-main
entrypoint: afield.audio.synth.FaustSoundDocs
tags: [smoke, synth, faust, audio, wav, docs, wasm, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "faust sound docs rendered"
*/

/**
 * The single-program clips on `faust.md`: the sawtooth through a low-pass with its cutoff moved
 * halfway through, and the detuned pair of sawtooths dry and through zita reverb.
 *
 * Each renders the page's own program through the same [FaustDsp] and [FaustNode] a `faust(...)`
 * launch plays, in 128-frame blocks, with no audio device. [FaustPolySoundDocs] does the same for the
 * polyphonic clips.
 */
object FaustSoundDocs {

    /** Sample rate of every clip; zita's own `fs` argument in [REVERB] says 48000 too. */
    const val SR = 48_000

    /** Frames per block, the audio engine's. */
    const val BLOCK = 128

    /** The page's first program, without the `#faust` marker a box needs. */
    val LOWPASS: String = """
        import("stdfaust.lib");
        freq   = hslider("freq [unit:Hz] [scale:log]", 220, 50, 2000, 0.01);
        cutoff = hslider("cutoff [unit:Hz]", 1200, 50, 8000, 1);
        gain   = hslider("gain", 0.2, 0, 1, 0.001);
        process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * gain <: _,_;
    """.trimIndent()

    /** The page's effect program. */
    val REVERB: String = """
        import("stdfaust.lib");
        room = hslider("room", 0.5, 0, 1, 0.01);
        process = _,_ : re.zita_rev1_stereo(20, 200, 2000, 3.0, 2.0, 48000) : *(room), *(room);
    """.trimIndent()

    /** Seconds the low-pass clip plays at each cutoff. */
    const val LOWPASS_HALF_SECONDS = 2.0

    /** The cutoff the second half of the low-pass clip moves to, in Hz. */
    const val LOWER_CUTOFF_HZ = 300.0

    /** Seconds the dry pair plays before it stops, in both reverb clips. */
    const val DRY_SECONDS = 2.0

    /** Length of each reverb clip: the dry pair, then its tail. */
    const val REVERB_SECONDS = 5.0

    /** The clips, by the name their fences ask for. */
    val FIGURES = listOf("saw-lowpass", "reverb-dry", "reverb-wet")

    /** The page's dry input: two sawtooths half a hertz apart. */
    private fun dryPair(): StereoRenderable = StereoSignal(SawOsc(Param(110f)) * 0.2f, SawOsc(Param(110.5f)) * 0.2f)

    /** [inner] for its first [seconds], then silence: the pair stops so the tail can be heard. */
    private class Stops(val inner: StereoRenderable, val seconds: Double) : StereoRenderable {
        override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
            if (ctx.time < seconds) inner.render(outL, outR, numFrames, ctx)
        }
    }

    /** Pulls [node] for [seconds] in blocks, calling [atBlock] with each block's start time first. */
    private fun pull(node: StereoRenderable, seconds: Double, atBlock: (Double) -> Unit = {}): Pair<FloatArray, FloatArray> {
        val frames = (seconds * SR).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val ctx = SynthContext(SR.toFloat(), BLOCK)
        val l = FloatArray(BLOCK)
        val r = FloatArray(BLOCK)
        var at = 0
        while (at < frames) {
            java.util.Arrays.fill(l, 0f)
            java.util.Arrays.fill(r, 0f)
            ctx.cycleId = at.toLong() / BLOCK + 1
            ctx.time = at.toFloat() / SR
            atBlock(ctx.time.toDouble())
            node.render(l, r, BLOCK, ctx)
            val n = minOf(BLOCK, frames - at)
            System.arraycopy(l, 0, left, at, n)
            System.arraycopy(r, 0, right, at, n)
            at += BLOCK
        }
        return left to right
    }

    /** Renders clip [name] as left and right channels. */
    fun render(name: String): Pair<FloatArray, FloatArray> = when (name) {
        "saw-lowpass" -> {
            val program = FaustEngine.compile("faustSoundDocsLowpass", LOWPASS)
            val cutoff = program.ui.writable.first { it.label.startsWith("cutoff") }
            FaustDsp(program, SR).use { dsp ->
                var moved = false
                pull(FaustNode(dsp), 2 * LOWPASS_HALF_SECONDS) { t ->
                    if (!moved && t >= LOWPASS_HALF_SECONDS) { dsp.setParam(cutoff, LOWER_CUTOFF_HZ); moved = true }
                }
            }
        }
        "reverb-dry" -> pull(Stops(dryPair(), DRY_SECONDS), REVERB_SECONDS)
        "reverb-wet" -> {
            val program = FaustEngine.compile("faustSoundDocsReverb", REVERB)
            FaustDsp(program, SR).use { dsp -> pull(FaustNode(dsp, Stops(dryPair(), DRY_SECONDS)), REVERB_SECONDS) }
        }
        else -> error("unknown FaustSoundDocs figure '$name'; known: $FIGURES")
    }

    /** Offline-renders clip [name] to a stereo 16-bit WAV at [outPath]: the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val (left, right) = render(name)
        require(left.any { it != 0f } || right.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, left, right, SR, normalizePeak = 0.9f)
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double =
        Math.sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from).coerceAtLeast(1))

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "faust-sound").path)
        outDir.mkdirs()
        try {
            val (saw, _) = render("saw-lowpass")
            val half = (LOWPASS_HALF_SECONDS * SR).toInt()
            // Brightness as the RMS of the first difference over the RMS: a differentiator's gain rises
            // with frequency, so this falls when the filter takes the upper harmonics away.
            fun brightness(from: Int, to: Int): Double {
                val d = FloatArray(to - from - 1) { saw[from + it + 1] - saw[from + it] }
                return rms(d, 0, d.size) / rms(saw, from, to)
            }
            val before = brightness(half / 2, half)
            val after = brightness(half + half / 2, 2 * half)
            println("  saw-lowpass: brightness %.4f at 1200 Hz cutoff, %.4f at %.0f Hz".format(before, after, LOWER_CUTOFF_HZ))
            check(after < before * 0.8) { "moving the cutoff down did not darken the sound" }

            val (dry, _) = render("reverb-dry")
            val (wet, wetR) = render("reverb-wet")
            val tailFrom = (DRY_SECONDS * SR).toInt() + SR / 2
            val tailTo = tailFrom + SR / 2
            println("  reverb tail (0.5-1 s after the pair stops): dry rms %.5f, wet rms %.5f".format(rms(dry, tailFrom, tailTo), rms(wet, tailFrom, tailTo)))
            check(rms(dry, tailFrom, tailTo) == 0.0) { "the dry pair did not stop" }
            check(rms(wet, tailFrom, tailTo) > 1e-3) { "the reverb has no tail" }
            check((0 until wet.size).any { abs(wet[it] - wetR[it]) > 1e-4 }) { "the reverb output is not stereo" }

            FIGURES.forEach { name ->
                val file = File(outDir, "$name.wav")
                renderWav(name, file.absolutePath)
                println("  wrote ${file.absolutePath}")
            }
        } finally {
            FaustEngine.shutdown()
        }
        println("faust sound docs rendered: ${FIGURES.size} figure(s)")
    }
}

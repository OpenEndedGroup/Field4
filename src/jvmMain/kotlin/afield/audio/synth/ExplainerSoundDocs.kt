@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The listening clips on the Sound explainer page (`public/docs/explainer_sound.md`): five of the
 * talk's sounding figures, played offline through the same signal graph each figure box builds.
 *
 * A figure box in `dev_docs/agents/transcripts/explainer_sound.yaml` makes its sound by returning
 * `audio(...)` and lets the reader play it with keys, checkboxes and sliders. A page cannot run that
 * box, so each scene here builds the figure's graph again from the same oscillators, envelopes,
 * filters and constants, and drives it with a fixed performance: the keys pressed, the slider moved,
 * the string plucked, in an order the scene states. [FIGURE_LINES] holds, per scene, the lines of
 * the figure's code its graph is taken from; `ExplainerSoundDocsTest` fails when one of them is no
 * longer in the transcript, so a change to a figure's sound reaches this file.
 *
 * Every clip is rendered at [SR] in blocks of [BLOCK] frames with no engine and no device, the
 * figure's own output gain applied, and is then normalised to a peak of [PEAK] so the five play at
 * one level. The renders are deterministic.
 */
object ExplainerSoundDocs {

    /** Sample rate of every clip. */
    const val SR: Int = 48_000

    /** Frames per block; a `Param { }` supplier is read once per block, as in the engine. */
    const val BLOCK: Int = 128

    /** Peak level each clip is normalised to. */
    const val PEAK: Float = 0.7f

    /** The scenes `audio-spec=` can name, each with what it plays. */
    val SCENES: Map<String, String> = linkedMapOf(
        "keyboard" to "the low, middle and high keys one at a time for a second each, then all three",
        "repeated_jumps" to "the plate jumping 4, then 30, then 220 times a second, a second and a half each",
        "sine_sweep" to "the pure tone gliding from 20 Hz to 20,000 Hz over ten seconds at amplitude 0.8",
        "beats" to "220 Hz alone for a second and a half, then 223 Hz added for three seconds",
        "pluck" to "the string plucked at a quarter of its length, then near its end at 0.08, three seconds each",
    )

    /** For each scene, the figure box it comes from and the lines of that box's code it rebuilds. */
    val FIGURE_LINES: Map<String, Pair<String, List<String>>> = mapOf(
        "keyboard" to ("keyboard" to listOf(
            "val voices = listOf<Signal>(SineOsc(Param(196f)), TriOsc(Param(247f)), SawOsc(Param(294f)) / lpf(2400))",
            "audio(3 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0, gain = 0.15f)",
        )),
        "repeated_jumps" to ("repeated_jumps" to listOf(
            "checkbox(\"sound\") { level.base = if (it) 0.15f else 0f }",
            "audio((SquareOsc(pace) * swing + offset) / hpf(15, 0.1), gain = level)",
        )),
        "sine_sweep" to ("sine_sweep" to listOf(
            "level.base = if (on) (0.15 * amplitude).toFloat() else 0f",
            "audio(SineOsc(pitch), gain = level)",
        )),
        "beats" to ("beats" to listOf(
            "val tones = listOf(SineOsc(Param(220f)), SineOsc(second))",
            "audio(2 { i -> tones[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 2.0, gain = 0.15f)",
        )),
        "pluck" to ("pluck" to listOf(
            "val n = 30",
            "fun weight(k: Int) = depth * 2 / (k * k * PI * PI * at * (1 - at)) * sin(k * PI * at)",
            "fun decay(k: Int) = 2.5 / k.toDouble().pow(0.7)",
            "fun base() = 110 / length",
            "SineOsc(Param { (k * base()).toFloat() }) * Param {",
            "if (audioPluck < 0) 0f else (weight(k) * exp(-(time - audioPluck) / decay(k))).toFloat()",
            "} + audio(partials.reduce { a, b -> a + b }, gain = 0.15f)",
        )),
    )

    /** Renders [signal] for [seconds], calling [each] with the time of every block before it is rendered. */
    private fun render(signal: Signal, seconds: Double, each: (Double) -> Unit): FloatArray {
        val ctx = SynthContext(SR.toFloat(), BLOCK)
        val total = (seconds * SR).toInt()
        val out = FloatArray(total)
        val buffer = FloatArray(BLOCK)
        var written = 0
        while (written < total) {
            each(written.toDouble() / SR)
            signal.render(buffer, BLOCK, ctx)
            ctx.advanceCycle()
            val n = minOf(BLOCK, total - written)
            System.arraycopy(buffer, 0, out, written, n)
            written += n
        }
        return out
    }

    private fun keyboard(): FloatArray {
        val voices = listOf<Signal>(SineOsc(Param(196f)), TriOsc(Param(247f)), SawOsc(Param(294f)) / lpf(2400))
        val gates = List(3) { Gate() }
        val sum = List(3) { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0
        // Seconds 0-1 low, 1-2 middle, 2-3 high (each let go after 0.8 s), 3.2-5 all three, then the release.
        val held = { i: Int, t: Double -> (t >= i && t < i + 0.8) || (t >= 3.2 && t < 5.0) }
        return render(sum * 0.15f, 5.6) { t -> gates.forEachIndexed { i, g -> if (held(i, t)) g.open() else g.close() } }
    }

    private fun repeatedJumps(): FloatArray {
        val swing = Param(0.5f)
        val offset = Param(0f)
        val pace = Param(4f)
        val level = Param(0.15f)
        val sound = ((SquareOsc(pace) * swing + offset) / hpf(15, 0.1)) * level
        return render(sound, 4.5) { t -> pace.base = if (t < 1.5) 4f else if (t < 3.0) 30f else 220f }
    }

    private fun sineSweep(): FloatArray {
        val pitch = Param(20f)
        val amplitude = 0.8
        val sound = SineOsc(pitch) * (0.15 * amplitude).toFloat()
        // A glide even in pitch, as the figure's logarithmic frequency slider makes one.
        return render(sound, 10.0) { t -> pitch.base = (20.0 * 1000.0.pow(t / 10.0)).toFloat() }
    }

    private fun beats(): FloatArray {
        val second = Param(223f)
        val gates = List(2) { Gate() }
        val tones = listOf(SineOsc(Param(220f)), SineOsc(second))
        val sum = List(2) { i -> tones[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 2.0
        return render(sum * 0.15f, 4.8) { t ->
            if (t < 4.5) gates[0].open() else gates[0].close()
            if (t >= 1.5 && t < 4.5) gates[1].open() else gates[1].close()
        }
    }

    private fun pluck(): FloatArray {
        val n = 30
        var at = 0.25
        val depth = 0.8
        val length = 1.0
        var audioPluck = -1f
        fun weight(k: Int) = depth * 2 / (k * k * PI * PI * at * (1 - at)) * sin(k * PI * at)
        fun decay(k: Int) = 2.5 / k.toDouble().pow(0.7)
        fun base() = 110 / length
        val partials = (1..n).map { k ->
            SineOsc(Param { (k * base()).toFloat() }) * Param {
                if (audioPluck.isNaN()) audioPluck = time
                if (audioPluck < 0) 0f else (weight(k) * exp(-(time - audioPluck) / decay(k))).toFloat()
            }
        }
        var second = false
        return render(partials.reduce { a, b -> a + b } * 0.15f, 6.0) { t ->
            // Each pluck stamps its time on the audio side, as the figure's does.
            if (t == 0.0) audioPluck = Float.NaN
            if (t >= 3.0 && !second) { second = true; at = 0.08; audioPluck = Float.NaN }
        }
    }

    /** Renders the scene [name] (a key of [SCENES]) to a 16-bit mono WAV at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val samples = when (name) {
            "keyboard" -> keyboard()
            "repeated_jumps" -> repeatedJumps()
            "sine_sweep" -> sineSweep()
            "beats" -> beats()
            "pluck" -> pluck()
            else -> error("unknown ExplainerSoundDocs scene '$name'; known: ${SCENES.keys}")
        }
        require(samples.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = PEAK)
    }

    /** Writes every scene into the directory given as the first argument (default: a temp dir). */
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "explainer-sound").path)
        dir.mkdirs()
        SCENES.keys.forEach { renderWav(it, File(dir, "$it.wav").path); println("rendered $it") }
    }
}

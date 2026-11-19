package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import kotlin.math.pow

/**
 * The listening clips for the evieve-family filters ([TptOnePole], [TiltEQ], [Korg35],
 * [Resonator], and the nonlinear [AdaaShaper], [ZdfLadder], [ZdfSvf] and [Smoother]) on `spectral_examples.md`, each one signal graph rendered offline by
 * [renderSignalsOffline] with a filter parameter driven by an oscillator through `Param.mod`.
 * The docs runner calls [renderWav] for each `audio=` fence.
 */
object TptFilterDocs {
    const val SR: Int = 48_000

    private fun mod(base: Float, m: Signal) = Param(base).also { it.mod = m }

    fun scene(name: String): Signal = when (name) {
        "korg35" -> SawOsc(Param(55f)) / Korg35(
            mod(1200f, SineOsc(Param(0.5f)) * 900f + SineOsc(Param(110f)) * 250f),
            Param(0.9f), drive = Param(2f))
        "phaser" -> {
            val dry = SawOsc(Param(110f)) + SawOsc(Param(165.5f)) + SawOsc(Param(220.7f))
            var wet: Signal = dry
            repeat(4) { wet = wet / TptOnePole(mod(900f, SineOsc(Param(0.3f)) * 750f), TptOnePoleMode.AP) }
            dry + wet
        }
        "tilt" -> WhiteNoise(7L) / TiltEQ(Param(800f), mod(0f, SineOsc(Param(0.25f)) * 12f))
        "resonator" -> {
            val n = WhiteNoise(11L) * 0.3f
            (n / Resonator(mod(300f, SineOsc(Param(0.2f)) * 80f), Param(0.6f))) +
                (n / Resonator(mod(750f, SineOsc(Param(0.45f)) * 150f), Param(0.4f))) +
                (n / Resonator(mod(2100f, SineOsc(Param(0.7f)) * 300f), Param(0.3f)))
        }
        "tanh-naive" -> sweep() / Waveshaper.tanh(Param(6f))
        "tanh-adaa" -> sweep() / AdaaShaper(AdaaShape.TANH, Param(6f))
        "zdf-ladder" -> SawOsc(Param(55f)) * 0.5f / ZdfLadder(
            mod(700f, SineOsc(Param(0.25f)) * 500f),
            Param { 0.6f + 0.55f * (time / 4f).coerceAtMost(1f) }, drive = Param(1.5f))
        "zdf-svf" -> WhiteNoise(3L) * 0.02f / ZdfSvf(SVFMode.BP, stepped().smoothed(0.04f), Param(1.04f))
        "stepped-raw" -> SawOsc(Param(110f)) / ZdfLadder(stepped(), Param(0.7f))
        "stepped-smoothed" -> SawOsc(Param(110f)) / ZdfLadder(stepped().smoothed(0.03f), Param(0.7f))
        else -> error("unknown TptFilterDocs scene '$name'")
    }

    /** A sine sweeping 200 Hz to 8 kHz over the four seconds, for the ADAA comparison. */
    private fun sweep(): Signal = SineOsc(Param { 200f * 40f.pow((time / 4f).coerceAtMost(1f)) })

    /** A cutoff that jumps between five values eight times a second, as a sequencer or a slider would. */
    private fun stepped(): Param {
        val steps = floatArrayOf(300f, 1200f, 600f, 2400f, 900f)
        val cutoff = FloatArray(4 * SR) { steps[(it * 8 / SR) % steps.size] }
        return Param(0f).also { it.mod = SamplesSignal(cutoff) }
    }

    fun renderWav(name: String, outPath: String) {
        val samples = renderSignalsOffline(4.0, scene(name), sampleRate = SR)[0]
        require(samples.all { it.isFinite() } && samples.any { it != 0f }) { "scene '$name' rendered silence or NaN" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.7f)
    }
}

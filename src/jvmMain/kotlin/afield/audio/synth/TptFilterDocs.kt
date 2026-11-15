package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter

/**
 * The four listening clips for the evieve-family filters ([TptOnePole], [TiltEQ], [Korg35],
 * [Resonator]) on `spectral_examples.md`, each one signal graph rendered offline by
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
        else -> error("unknown TptFilterDocs scene '$name'")
    }

    fun renderWav(name: String, outPath: String) {
        val samples = renderSignalsOffline(4.0, scene(name), sampleRate = SR)[0]
        require(samples.all { it.isFinite() } && samples.any { it != 0f }) { "scene '$name' rendered silence or NaN" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.7f)
    }
}

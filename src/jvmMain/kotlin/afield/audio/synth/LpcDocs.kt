package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter

/**
 * The listening clips for linear prediction on `spectral_examples.md`. The voice is synthesized,
 * so it is license-clean: [vowelVoice] is a glottal pulse train with vibrato through a
 * [FormantFilter] sliding a-e-i-o-u, plus a little breath. Every other clip takes that voice's LPC
 * envelope ([LpcVocoder]) and drives it with something else. The docs runner calls [renderWav] for
 * each `audio=` fence.
 */
object LpcDocs {
    /** Sample rate of every clip. */
    const val SR: Int = 48_000

    private fun mod(base: Float, m: Signal) = Param(base).also { it.mod = m }

    /** The synthetic voice: 120 Hz buzz with vibrato, formants sweeping a-e-i-o-u every 2.5 s. */
    fun vowelVoice(): Signal {
        val pitch = mod(120f, SineOsc(Param(5f)) * 3f + SineOsc(Param(0.2f)) * 15f)
        val buzz = Impulse(pitch) * 4f + WhiteNoise(3L) * 0.02f
        return buzz / FormantFilter(mod(0f, Phasor(Param(0.4f)) * 4f))
    }

    /** One clip's signal graph, by name. */
    fun scene(name: String): Signal = when (name) {
        "voice" -> vowelVoice()
        "residual" -> vowelVoice() / LpcWhiten(order = 24)
        "robot" -> Impulse(Param(90f)) / LpcVocoder(vowelVoice(), whiten = false)
        "whisper" -> WhiteNoise(5L) / LpcVocoder(vowelVoice(), whiten = false)
        "talking-synth" -> (SawOsc(Param(110f)) + SawOsc(Param(138.6f)) + SawOsc(Param(164.8f)) + SawOsc(Param(220.5f))) /
            LpcVocoder(vowelVoice(), order = 32)
        else -> error("unknown LpcDocs scene '$name'")
    }

    /** Render scene [name] for five seconds to a mono WAV at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val samples = renderSignalsOffline(5.0, scene(name), sampleRate = SR)[0]
        require(samples.all { it.isFinite() } && lpcPeak(samples) > 0f) { "scene '$name' rendered silence or NaN" }
        WavFloatArrayWriter.writeMono16(outPath, samples, SR, normalizePeak = 0.7f)
    }
}

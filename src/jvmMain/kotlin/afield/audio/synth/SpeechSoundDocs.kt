package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import java.io.File

/**
 * The listening clips for "Speech: a box that talks" on `spectral_examples.md`, rendered offline
 * through the same [speak] the page's boxes call. The docs runner calls [renderWav] for each
 * `audio=` fence, and `DocsAudioProvenance` re-renders every clip on each build.
 *
 * - `hello` and `hello-m2`: the page's first sentence in the F2 and M2 voices, as [Speech.wav] gives it.
 * - `drums`: "Speech can play the drums" mixed with its own word pattern on pinned Dirt-Samples
 *   one-shots (needs `./gradlew fetchDocsDirtSamples`).
 * - `sung`: the M2 sentence's spectral envelope on an [Impulse] whose pitch a slow sine moves
 *   ([Speech.sungOn]), rendered by [renderSignalsOffline].
 *
 * Every clip needs the tiny-voca model in `~/.afield/onnx/models/tiny-voca/`, which [speak] fetches
 * on first use.
 */
object SpeechSoundDocs {
    /** The scenes, in page order. */
    val SCENES = listOf("hello", "hello-m2", "drums", "sung")

    const val HELLO = "Hello! I am a box in Field, and this is my voice."

    /** The page's "words as a pattern" mix, at 48 kHz. */
    fun drumsMix(): FloatArray {
        val said = speak("Speech can play the drums, if you let it.")
        val drums = said.pattern { if (it.stressed) "bd" else "hh" }
        val dirt = DirtRender.requirePinnedLibrary()
        val beat = patRenderStereoDirt(drums, 1, 1 / said.seconds, dirt, sampleRate = 48_000, strict = true).audio.mono
        val voice = said.resampled(48_000)
        return FloatArray(voice.size) { i -> 0.8f * voice[i] + 0.5f * beat.getOrElse(i) { 0f } }
    }

    /** The page's sung contour, at 48 kHz. */
    fun sung(): FloatArray {
        val said = speak("I can sing whatever pitch you give me.", voice = "M2")
        val pitch = Param(110f).also { it.mod = SineOsc(0.5.p) * 30f }
        return renderSignalsOffline(said.seconds, said.sungOn(Impulse(pitch)) * 0.5f, sampleRate = 48_000)[0]
    }

    /** Render scene [name] to a mono WAV at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        when (name) {
            "hello", "hello-m2" -> {
                val said = speak(HELLO, voice = if (name == "hello") "F2" else "M2")
                require(said.samples.any { it != 0f }) { "scene '$name' rendered silence" }
                File(outPath).apply { parentFile?.mkdirs() }.writeBytes(said.wav())
            }
            "drums", "sung" -> {
                val samples = if (name == "drums") drumsMix() else sung()
                require(samples.all { it.isFinite() } && samples.any { it != 0f }) { "scene '$name' rendered silence or NaN" }
                WavFloatArrayWriter.writeMono16(outPath, samples, 48_000, normalizePeak = 0.9f)
            }
            else -> error("unknown SpeechSoundDocs scene '$name'; known: $SCENES")
        }
    }
}

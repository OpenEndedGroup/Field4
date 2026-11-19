@file:afield.runtime.completion.CompletionCategory("Synthesis")

package afield.audio.synth

import afield.ai.inference.onnx.OnnxModel
import afield.ai.inference.onnx.OnnxRuntime
import ai.onnxruntime.OnnxTensor
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.ln

/**
 * Text to speech as a Field signal: [speak] renders an utterance with the
 * [tiny-voca](https://huggingface.co/n4ze3m/tiny-voca) model (Apache-2.0, 4.5M parameters, 24 kHz),
 * and the [Speech] it returns is audio, timing and a pattern at once.
 *
 * - [Speech.signal] plays it through the `Signal` graph, so it goes through any filter or effect and
 *   is rendered by [FieldAudioEngine] on the audio thread when a box returns it.
 * - [Speech.words] and [Speech.phones] carry the model's own per-token durations as seconds, and
 *   [Speech.pattern] turns them into a one-cycle [PatPattern] whose events are the words, so anything
 *   that consumes a pattern can be driven by what was said.
 * - [speak] takes `pitch` (semitones) and `pitchRange` (how far the intonation moves), which the model
 *   applies inside its source-filter vocoder. For a contour of your own, [Speech.sungOn] puts the
 *   speech's spectral envelope on any carrier with [LpcVocoder].
 *
 * Synthesis is offline: [speak] runs the whole utterance once (the model has no streaming) and is
 * called from box code, never from the audio thread. The ONNX session is cached by [OnnxRuntime], so
 * re-running a box costs only the inference. Weights (21 MB) and the phonemizer data (5 MB) are
 * fetched on first use into `~/.afield/onnx/models/tiny-voca/`.
 */
object TinyVoca {

    /** The model's output sample rate. */
    const val SAMPLE_RATE = 24_000

    /** Samples per duration frame: one `durations` unit is 256 samples, 10.7 ms. */
    const val HOP = 256

    /** The model reads at most this many tokens per call; longer text is split at sentence marks. */
    const val MAX_TOKENS = 512

    /** One semitone in the model's `pitch_shift` units (added to log F0). */
    val SEMITONE: Double = ln(2.0) / 12.0

    /** The ONNX graph, fp32, opset 17. */
    val MODEL = OnnxModel(
        id = "tiny-voca",
        url = "https://huggingface.co/n4ze3m/tiny-voca/resolve/1e73262226dbf4ef02e9df277e81aaa1db5ce16c/onnx/model.onnx",
        fileName = "tiny-voca/model.onnx",
        sha256 = "fe6d918791a15b82e37465981d7f27a070e46b46635e5a3482fc8dff87a1ae46",
        note = "tiny-voca TTS, 4.5M params, two voices, 24 kHz, Apache-2.0"
    )

    /** The two voices. */
    val VOICES = listOf("F2", "M2")

    /**
     * One model call on one sentence's tokens: audio at [SAMPLE_RATE] and each token's length in frames.
     */
    fun synthesize(
        tokens: SpeechPhonemizer.Tokens,
        voice: String = "F2",
        speed: Double = 1.0,
        pitch: Double = 0.0,
        pitchRange: Double = 1.0
    ): Pair<FloatArray, LongArray> {
        require(tokens.ids.size <= MAX_TOKENS) { "${tokens.ids.size} tokens; tiny-voca reads at most $MAX_TOKENS per call" }
        val voiceId = VOICES.indexOf(voice.uppercase()).also { require(it >= 0) { "voice must be one of $VOICES" } }
        val env = OnnxRuntime.env
        val session = MODEL.session().session
        val n = tokens.ids.size.toLong()
        val inputs = mapOf(
            "tokens" to OnnxTensor.createTensor(env, LongBuffer.wrap(tokens.ids), longArrayOf(1, n)),
            "stress" to OnnxTensor.createTensor(env, LongBuffer.wrap(tokens.stress), longArrayOf(1, n)),
            "voice" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(voiceId.toLong())), longArrayOf(1)),
            "speed" to OnnxTensor.createTensor(env, floatArrayOf(speed.toFloat())),
            "pitch_shift" to OnnxTensor.createTensor(env, floatArrayOf((pitch * SEMITONE).toFloat())),
            "pitch_range" to OnnxTensor.createTensor(env, floatArrayOf(pitchRange.toFloat())),
        )
        try {
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val audio = (result.get("audio").get().value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val durations = (result.get("durations").get().value as Array<LongArray>)[0]
                return audio to durations
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }
}

/** One phone as spoken: its symbol, lexical stress, and where it sits in the [Speech], in seconds. */
data class SpokenPhone(val symbol: String, val stress: Int, val start: Double, val end: Double, val word: Int)

/**
 * One word as spoken.
 *
 * @param index position in [Speech.words]
 * @param stressed true when a phone of the word carries primary stress after the sentence rules
 * @param source where the pronunciation came from: `dictionary`, `function`, `exception`, `ending`,
 * `model` (dizione's unknown-word network) or `letters`
 */
data class SpokenWord(
    val index: Int,
    val text: String,
    val start: Double,
    val end: Double,
    val phones: List<SpokenPhone>,
    val stressed: Boolean,
    val source: String
) {
    /** Length in seconds. */
    val duration: Double get() = end - start
}

/**
 * An utterance: [samples] at [sampleRate] (24 kHz) plus the timing of every word and phone.
 *
 * @param synthesisSeconds wall-clock time the model took, phonemizer included
 */
class Speech(
    val text: String,
    val voice: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val words: List<SpokenWord>,
    val phones: List<SpokenPhone>,
    val synthesisSeconds: Double
) {
    /** Length of the audio. */
    val seconds: Double get() = samples.size.toDouble() / sampleRate

    /** How many seconds of speech each second of synthesis produced. */
    val realTimeFactor: Double get() = seconds / synthesisSeconds.coerceAtLeast(1e-9)

    /** The word sounding at [t] seconds, or null in a pause. */
    fun wordAt(t: Double): SpokenWord? = words.firstOrNull { t >= it.start && t < it.end }

    /** The phone sounding at [t] seconds, or null in a pause. */
    fun phoneAt(t: Double): SpokenPhone? = phones.firstOrNull { t >= it.start && t < it.end }

    /** The audio at another rate (linear interpolation), e.g. 48000 for the engine or for analysis. */
    fun resampled(rate: Int): FloatArray {
        if (rate == sampleRate) return samples.copyOf()
        val ratio = sampleRate.toDouble() / rate
        return FloatArray((samples.size / ratio).toInt()) { i ->
            val at = i * ratio
            val k = at.toInt()
            val f = (at - k).toFloat()
            val a = samples[k]
            val b = if (k + 1 < samples.size) samples[k + 1] else 0f
            a + (b - a) * f
        }
    }

    /**
     * A [Signal] that plays this speech once and then silence; with [loop] it repeats every
     * [loopSeconds] (the speech's own length when 0). Rendered at whatever rate the context runs.
     */
    fun signal(loop: Boolean = false, loopSeconds: Double = 0.0, gain: Float = 1f): Signal = SpeechSignal(this, loop, loopSeconds, gain)

    /**
     * The speech's envelope on [carrier], with [LpcVocoder]: speech that follows any pitch you give it.
     * An [Impulse] at a `Param` is a robot that can sing; a saw chord is the talking synth.
     */
    fun sungOn(carrier: Signal, order: Int = 48): Signal = carrier / LpcVocoder(signal(), order = order, whiten = carrier !is Impulse)

    /**
     * The words as a one-cycle pattern: each event spans its word, valued with the word's spelling.
     * Played at `cps = 1 / seconds` its events land on the spoken words. Timing is rounded to
     * duration frames (10.7 ms), the model's own resolution.
     */
    fun pattern(value: (SpokenWord) -> String = { it.text }): PatPattern<String> {
        val total = samples.size.toLong().coerceAtLeast(1)
        fun q(t: Double) = PatQ.of(Math.round(t * sampleRate).coerceIn(0, total), total)
        val events = words.map { w -> Triple(q(w.start), q(w.end), value(w)) }
        return fromCycle { arc ->
            events.mapNotNull { (s, e, v) ->
                val whole = PatArc(s, e)
                whole.intersect(arc)?.takeIf { it.start < it.end }?.let { PatEvent(it, v, whole = whole) }
            }
        }
    }

    /** This speech as a 16-bit mono WAV file's bytes, peak-limited to 0.99. */
    fun wav(): ByteArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val scale = if (peak > 0.99f) 0.99f / peak else 1f
        val pcm = java.io.ByteArrayOutputStream()
        val data = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size * 2)
        pcm.write(header.array()); pcm.write(data.array())
        return pcm.toByteArray()
    }

    override fun toString(): String =
        "Speech(\"${text.take(40)}\", $voice, ${"%.2f".format(seconds)} s, ${words.size} words, ${"%.0f".format(realTimeFactor)}x real time)"
}

/** Plays a [Speech] buffer through a signal graph, resampling to the context's rate. */
private class SpeechSignal(private val speech: Speech, private val loop: Boolean, loopSeconds: Double, private val gain: Float) : Signal() {
    private val period = if (loopSeconds > 0.0) loopSeconds else speech.seconds
    private var position = 0.0 // seconds

    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val samples = speech.samples
        val rate = speech.sampleRate.toDouble()
        val step = 1.0 / ctx.sampleRate
        for (i in 0 until numFrames) {
            if (loop && position >= period) position -= period
            val at = position * rate
            val k = at.toInt()
            output[i] = if (k + 1 < samples.size) {
                val f = (at - k).toFloat()
                (samples[k] + (samples[k + 1] - samples[k]) * f) * gain
            } else 0f
            position += step
        }
    }
}

/**
 * Speak [text]: phonemize it, run tiny-voca sentence by sentence, and return the [Speech] with its
 * word and phone timing.
 *
 * @param voice `"F2"` or `"M2"`
 * @param speed speaking rate, 1.0 as predicted; larger is faster
 * @param pitch semitones added to the predicted pitch
 * @param pitchRange scales the intonation around its mean; 0 is a monotone
 */
fun speak(text: String, voice: String = "F2", speed: Double = 1.0, pitch: Double = 0.0, pitchRange: Double = 1.0): Speech {
    val started = System.nanoTime()
    val sentences = Regex("(?<=[.?!…])\\s+").split(text.trim()).filter { it.isNotBlank() }
    val audio = ArrayList<FloatArray>()
    val words = ArrayList<SpokenWord>()
    val phones = ArrayList<SpokenPhone>()
    var offset = 0
    for (sentence in sentences) {
        val tokens = SpeechPhonemizer.tokens(sentence)
        val (samples, durations) = TinyVoca.synthesize(tokens, voice, speed, pitch, pitchRange)
        val base = offset.toDouble() / TinyVoca.SAMPLE_RATE
        val hop = TinyVoca.HOP.toDouble() / TinyVoca.SAMPLE_RATE
        // The durations account for every frame the model generated, so a running sum is the clock.
        var frame = 0L
        val starts = LongArray(durations.size) { i -> frame.also { frame += durations[i] } }
        val firstWord = words.size
        val firstPhone = phones.size
        for (i in tokens.symbols.indices) {
            val w = tokens.wordOf[i]
            if (w < 0) continue
            phones += SpokenPhone(tokens.symbols[i], tokens.stress[i].toInt(), base + starts[i] * hop,
                base + (starts[i] + durations[i]) * hop, firstWord + w)
        }
        tokens.words.forEachIndexed { w, word ->
            val mine = phones.subList(firstPhone, phones.size).filter { it.word == firstWord + w }
            words += SpokenWord(firstWord + w, word.text, mine.first().start, mine.last().end, mine,
                word.phones.any { it.second == 1 }, word.source)
        }
        audio += samples
        offset += samples.size
    }
    val joined = FloatArray(offset)
    var at = 0
    audio.forEach { it.copyInto(joined, at); at += it.size }
    return Speech(text, voice.uppercase(), joined, TinyVoca.SAMPLE_RATE, words, phones, (System.nanoTime() - started) / 1e9)
}

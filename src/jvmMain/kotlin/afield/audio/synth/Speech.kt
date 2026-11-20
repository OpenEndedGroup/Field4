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
 * Speech is reproducible: the model draws its noise excitation from a `RandomNormalLike` node that
 * carries no seed, so [TinyVoca] runs a copy of the graph with a fixed `seed` on it ([seededModel]), in a fresh session per sentence so the
 * generator starts from that seed every time. The same text, voice and parameters give the same samples on every run, which is
 * what lets the docs' speech clips be re-rendered and compared; a different sentence still gets
 * different noise, because the noise tensor is shaped by its length.
 *
 * Synthesis is offline: [speak] runs the whole utterance once (the model has no streaming) and is
 * called from box code, never from the audio thread. The seeded graph is read once and
 * a session built from it for each sentence. Weights (21 MB) and the phonemizer data (5 MB) are
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

    /** The seed written into every `RandomNormalLike` node of the graph. */
    const val NOISE_SEED = 7f

    /**
     * The fetched model with [NOISE_SEED] set on its `RandomNormalLike` nodes, written once beside it
     * as `model.seeded.onnx`. Rewrites only those nodes' attribute lists; every weight is copied as is.
     */
    fun seededModel(): java.nio.file.Path {
        val source = MODEL.ensureLocal()
        val target = source.resolveSibling("model.seeded.onnx")
        if (java.nio.file.Files.isRegularFile(target) &&
            java.nio.file.Files.getLastModifiedTime(target) >= java.nio.file.Files.getLastModifiedTime(source)) return target
        val (bytes, seeded) = OnnxSeed.seedModel(java.nio.file.Files.readAllBytes(source), "RandomNormalLike", NOISE_SEED)
        check(seeded > 0) { "tiny-voca has no RandomNormalLike node to seed; the model changed" }
        val part = target.resolveSibling("model.seeded.onnx.part")
        java.nio.file.Files.write(part, bytes)
        java.nio.file.Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /** [seededModel]'s bytes, read once. */
    private val seededBytes: ByteArray by lazy { java.nio.file.Files.readAllBytes(seededModel()) }

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
        // A fresh session per call: a seeded RandomNormalLike advances its generator on every run,
        // so a shared session would make the noise depend on how many sentences came before.
        val session = env.createSession(seededBytes, ai.onnxruntime.OrtSession.SessionOptions())
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
            session.close()
        }
    }
}

/**
 * Adds a float `seed` attribute to every node of one op type in an ONNX `ModelProto`, by rewriting
 * the protobuf wire format directly: ModelProto.graph (7) -> GraphProto.node (1) -> NodeProto
 * op_type (4) and attribute (5), recursing into subgraph attributes (`g` 6, `graphs` 11). A node
 * that already has a `seed` is left alone. Everything else is copied byte for byte.
 */
internal object OnnxSeed {
    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p < end
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[p++].toInt(); r = r or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return r; s += 7 } }
    }

    private fun varint(out: java.io.ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    private fun lenField(out: java.io.ByteArrayOutputStream, field: Int, body: ByteArray) {
        varint(out, (field.toLong() shl 3) or 2); varint(out, body.size.toLong()); out.write(body)
    }

    /** Walks one message, handing each length-delimited field to [onBytes]; other fields are copied. */
    private fun rewrite(b: ByteArray, start: Int, end: Int, onBytes: (Int, ByteArray, Int, Int) -> ByteArray?): ByteArray {
        val out = java.io.ByteArrayOutputStream(end - start)
        val r = Reader(b, start, end)
        while (r.more()) {
            val tagAt = r.p
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> r.varint()
                1 -> r.p += 8
                5 -> r.p += 4
                2 -> {
                    val len = r.varint().toInt(); val at = r.p; r.p += len
                    val replaced = onBytes(field, b, at, at + len)
                    if (replaced != null) { lenField(out, field, replaced); continue }
                }
                else -> error("unsupported protobuf wire type in ONNX model")
            }
            out.write(b, tagAt, r.p - tagAt)
        }
        return out.toByteArray()
    }

    private fun strings(b: ByteArray, start: Int, end: Int, field: Int): List<String> {
        val found = ArrayList<String>()
        rewrite(b, start, end) { f, bb, s, e -> if (f == field) found += String(bb, s, e - s); null }
        return found
    }

    private class Seeder(val opType: String, val seed: Float) {
        var count = 0
        fun graph(b: ByteArray, s: Int, e: Int): ByteArray = rewrite(b, s, e) { f, bb, ns, ne -> if (f == 1) node(bb, ns, ne) else null }
        fun attribute(b: ByteArray, s: Int, e: Int): ByteArray = rewrite(b, s, e) { f, bb, gs, ge ->
            when (f) {
                6 -> graph(bb, gs, ge)
                11 -> graph(bb, gs, ge)
                else -> null
            }
        }
        fun node(b: ByteArray, s: Int, e: Int): ByteArray {
            val body = rewrite(b, s, e) { f, bb, as_, ae -> if (f == 5) attribute(bb, as_, ae) else null }
            if (strings(b, s, e, 4).firstOrNull() != opType) return body
            val names = ArrayList<String>()
            rewrite(b, s, e) { f, bb, as_, ae -> if (f == 5) names += strings(bb, as_, ae, 1); null }
            if ("seed" in names) return body
            val attr = java.io.ByteArrayOutputStream()
            lenField(attr, 1, "seed".toByteArray())
            attr.write(0x15) // f (2), fixed32
            val bits = java.lang.Float.floatToIntBits(seed)
            for (k in 0 until 4) attr.write((bits ushr (8 * k)) and 0xff)
            attr.write(0xa0); attr.write(0x01); attr.write(0x01) // type (20) = FLOAT
            val out = java.io.ByteArrayOutputStream()
            out.write(body); lenField(out, 5, attr.toByteArray())
            count++
            return out.toByteArray()
        }
    }

    fun seedModel(model: ByteArray, opType: String, seed: Float): Pair<ByteArray, Int> {
        val seeder = Seeder(opType, seed)
        val rewritten = rewrite(model, 0, model.size) { f, bb, s, e -> if (f == 7) seeder.graph(bb, s, e) else null }
        return rewritten to seeder.count
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

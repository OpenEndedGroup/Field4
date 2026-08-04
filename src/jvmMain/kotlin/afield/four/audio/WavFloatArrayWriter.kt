package afield.four.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object WavFloatArrayWriter {
    /**
     * Writes mono 16-bit PCM WAV from normalized floats.
     */
    fun writeMono16(path: String, samples: FloatArray, sampleRate: Int, normalizePeak: Float? = null) {
        writeMono16(File(path), samples, sampleRate, normalizePeak)
    }

    fun writeMono16(file: File, samples: FloatArray, sampleRate: Int, normalizePeak: Float? = null) {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        file.parentFile?.mkdirs()
        file.writeBytes(encodeMono16(samples, sampleRate, normalizePeak))
    }

    /**
     * Encodes mono 16-bit PCM WAV from normalized floats and returns the complete `.wav` bytes.
     *
     * Handy for `display(...)`: the value visualizer renders a WAV byte array as an inline audio
     * player, so `display(WavFloatArrayWriter.encodeMono16(signal, 8000))` plays a signal in a box.
     */
    fun encodeMono16(samples: FloatArray, sampleRate: Int, normalizePeak: Float? = null): ByteArray {
        require(sampleRate > 0) { "sampleRate must be > 0" }

        val working = samples.copyOf()
        if (normalizePeak != null) normalizeInPlace(working, normalizePeak)

        val dataSize = working.size * 2
        val riffSize = 36 + dataSize
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putAscii("RIFF")
        buffer.putInt(riffSize)
        buffer.putAscii("WAVE")

        buffer.putAscii("fmt ")
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)

        buffer.putAscii("data")
        buffer.putInt(dataSize)
        for (sample in working) {
            val clipped = sample.coerceIn(-1.0f, 1.0f)
            val intSample = (clipped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(intSample.toShort())
        }

        return buffer.array()
    }

    /**
     * Writes interleaved stereo 16-bit PCM WAV from two equal-length channels.
     *
     * Needed by anything whose whole point is the stereo image — a `jux`-panned pattern, a plate
     * reverb's decorrelated tail — where folding to mono destroys the thing being demonstrated.
     */
    fun writeStereo16(file: File, left: FloatArray, right: FloatArray, sampleRate: Int, normalizePeak: Float? = null) {
        file.parentFile?.mkdirs()
        file.writeBytes(encodeStereo16(left, right, sampleRate, normalizePeak))
    }

    /** [writeStereo16] to a path. */
    fun writeStereo16(path: String, left: FloatArray, right: FloatArray, sampleRate: Int, normalizePeak: Float? = null) {
        writeStereo16(File(path), left, right, sampleRate, normalizePeak)
    }

    /**
     * Encodes interleaved stereo 16-bit PCM WAV and returns the complete `.wav` bytes.
     *
     * When [normalizePeak] is set, both channels are scaled by the *same* factor — the peak of the
     * louder one — so normalisation cannot move the stereo image.
     */
    fun encodeStereo16(left: FloatArray, right: FloatArray, sampleRate: Int, normalizePeak: Float? = null): ByteArray {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(left.size == right.size) { "channels must be the same length: ${left.size} vs ${right.size}" }

        val l = left.copyOf()
        val r = right.copyOf()
        if (normalizePeak != null) {
            var peak = 0.0f
            for (v in l) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
            for (v in r) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
            if (peak > 0.0f) {
                val scale = normalizePeak / peak
                for (i in l.indices) { l[i] *= scale; r[i] *= scale }
            }
        }

        val frames = l.size
        val dataSize = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putAscii("RIFF")
        buffer.putInt(36 + dataSize)
        buffer.putAscii("WAVE")

        buffer.putAscii("fmt ")
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(2) // stereo
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 4)
        buffer.putShort(4)
        buffer.putShort(16)

        buffer.putAscii("data")
        buffer.putInt(dataSize)
        for (i in 0 until frames) {
            buffer.putShort(pcm16(l[i]))
            buffer.putShort(pcm16(r[i]))
        }

        return buffer.array()
    }

    private fun pcm16(sample: Float): Short {
        val clipped = sample.coerceIn(-1.0f, 1.0f)
        return (clipped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }

    private fun normalizeInPlace(values: FloatArray, targetPeak: Float) {
        require(targetPeak >= 0.0f) { "targetPeak must be >= 0" }
        var peak = 0.0f
        for (value in values) {
            val a = kotlin.math.abs(value)
            if (a > peak) peak = a
        }
        if (peak <= 0.0f) return
        val scale = targetPeak / peak
        for (i in values.indices) values[i] *= scale
    }
}

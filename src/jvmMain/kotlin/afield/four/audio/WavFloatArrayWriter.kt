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

        file.writeBytes(buffer.array())
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

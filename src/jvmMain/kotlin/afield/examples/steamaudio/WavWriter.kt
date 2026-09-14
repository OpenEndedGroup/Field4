package afield.examples.steamaudio

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal 16-bit PCM WAV writer, so the demos produce something you can just open and listen to
 * without dragging in an audio library.
 */
internal object WavWriter {

    /**
     * Writes interleaved float samples in `-1..1` to a 16-bit PCM WAV file.
     *
     * @param samples interleaved samples, `frames * channels` long.
     */
    fun write(path: Path, samples: FloatArray, channelCount: Int, samplingRate: Int) {
        require(channelCount > 0) { "channelCount must be positive" }
        require(samples.size % channelCount == 0) {
            "sample count ${samples.size} is not a multiple of $channelCount channels"
        }

        val bitsPerSample = 16
        val bytesPerFrame = channelCount * bitsPerSample / 8
        val dataBytes = samples.size * bitsPerSample / 8

        Files.newOutputStream(path).use { raw ->
            DataOutputStream(raw.buffered()).use { out ->
                out.writeAscii("RIFF")
                out.writeIntLE(36 + dataBytes)
                out.writeAscii("WAVE")

                out.writeAscii("fmt ")
                out.writeIntLE(16)
                out.writeShortLE(1)                                  // PCM, uncompressed
                out.writeShortLE(channelCount)
                out.writeIntLE(samplingRate)
                out.writeIntLE(samplingRate * bytesPerFrame)         // byte rate
                out.writeShortLE(bytesPerFrame)                      // block align
                out.writeShortLE(bitsPerSample)

                out.writeAscii("data")
                out.writeIntLE(dataBytes)

                // Convert in chunks so a long render does not need a second full-size buffer.
                val chunk = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    if (chunk.remaining() < 2) {
                        out.write(chunk.array(), 0, chunk.position())
                        chunk.clear()
                    }
                    chunk.putShort(toPcm16(sample))
                }
                if (chunk.position() > 0) {
                    out.write(chunk.array(), 0, chunk.position())
                }
            }
        }
    }

    /** Clamps to `-1..1` and scales to a signed 16-bit value. */
    private fun toPcm16(sample: Float): Short {
        val clamped = sample.coerceIn(-1f, 1f)
        // 32767 rather than 32768 so that +1.0 does not wrap to negative.
        return (clamped * 32767f).toInt().toShort()
    }

    private fun DataOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun OutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun OutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}

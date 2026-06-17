package afield.four.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class WavFloatData(
    val samples: FloatArray,
    val sampleRate: Int
) {
    /**
     * Calculates the Root Mean Square (RMS) of a chunk of audio at the given time.
     * 
     * @param timeSeconds The start time of the chunk in seconds.
     * @param windowSeconds The duration of the chunk in seconds (default 20ms).
     * @return The RMS value of the chunk, or 0.0f if the time is out of bounds.
     */
    fun getRMSAtTime(timeSeconds: Float, windowSeconds: Float = 0.02f): Float {
        val startIndex = (timeSeconds * sampleRate).toInt()
        val windowSamples = (windowSeconds * sampleRate).toInt()
        
        if (startIndex < 0 || startIndex >= samples.size) return 0.0f
        
        val endIndex = minOf(startIndex + windowSamples, samples.size)
        val actualWindowSamples = endIndex - startIndex
        
        if (actualWindowSamples <= 0) return 0.0f
        
        var sumSquares = 0.0
        for (i in startIndex until endIndex) {
            val sample = samples[i]
            sumSquares += sample * sample
        }
        
        return sqrt(sumSquares / actualWindowSamples).toFloat()
    }

    /**
     * Creates a new WavFloatData by applying a FloatFilter to all samples.
     * This is useful for pre-filtering the entire audio file before playback.
     */
    fun applyFilter(filter: afield.stdlib.FloatFilter): WavFloatData {
        val newSamples = FloatArray(samples.size)
        for (i in samples.indices) {
            newSamples[i] = filter(samples[i])
        }
        return WavFloatData(newSamples, sampleRate)
    }
}

object WavFloatArrayLoader {
    /**
     * Loads a WAV file into a FloatArray with values from -1.0 to 1.0.
     * If the WAV file is stereo, it takes only the left channel.
     */
    fun loadWavToFloatArray(path: String): WavFloatData {
        val file = File(path)
        require(file.exists()) { "WAV file not found: $path" }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun readFourCC(): String {
            val id = ByteArray(4)
            buf.get(id)
            return String(id, Charsets.US_ASCII)
        }

        fun skipChunkPadding(size: Int) {
            if (size % 2 == 1 && buf.remaining() > 0) {
                buf.position(buf.position() + 1)
            }
        }

        val riff = readFourCC()
        require(riff == "RIFF") { "Invalid WAV (missing RIFF header): $path" }
        buf.int // file size
        val wave = readFourCC()
        require(wave == "WAVE") { "Invalid WAV (missing WAVE header): $path" }

        var fmtFound = false
        var dataFound = false

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        var dataOffset = 0
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = readFourCC()
            val chunkSize = buf.int
            val chunkStart = buf.position()

            when (chunkId) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    fmtFound = true
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                    dataFound = true
                }
            }

            buf.position(chunkStart + chunkSize)
            skipChunkPadding(chunkSize)
            if (fmtFound && dataFound) break
        }

        require(fmtFound) { "Invalid WAV (missing fmt chunk): $path" }
        require(dataFound) { "Invalid WAV (missing data chunk): $path" }
        require(audioFormat == 1 || audioFormat == 3) { "Unsupported WAV format (only PCM and IEEE Float supported): $path" }
        require(bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32 || bitsPerSample == 64) { "Unsupported WAV bit depth: $bitsPerSample" }
        require(channels == 1 || channels == 2) { "Unsupported WAV channels: $channels" }

        require(dataOffset >= 0 && dataSize >= 0 && dataOffset + dataSize <= bytes.size) {
            "Invalid WAV data chunk bounds: offset=$dataOffset size=$dataSize total=${bytes.size}"
        }
        
        val raw = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        val bytesPerSample = bitsPerSample / 8
        val frameSize = channels * bytesPerSample
        val frameCount = raw.size / frameSize
        
        val floatSamples = FloatArray(frameCount)
        val rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        fun readPcm24(): Int {
            val b0 = rawBuf.get().toInt() and 0xFF
            val b1 = rawBuf.get().toInt() and 0xFF
            val b2 = rawBuf.get().toInt() and 0xFF
            val value = b0 or (b1 shl 8) or (b2 shl 16)
            return if ((value and 0x800000) != 0) value or -0x1000000 else value
        }
        
        if (audioFormat == 3 && bitsPerSample == 32) {
            for (i in 0 until frameCount) {
                val left = rawBuf.float
                if (channels == 2) {
                    rawBuf.float // skip right channel
                }
                floatSamples[i] = left
            }
        } else if (audioFormat == 3 && bitsPerSample == 64) {
            for (i in 0 until frameCount) {
                val left = rawBuf.double.toFloat()
                if (channels == 2) {
                    rawBuf.double // skip right channel
                }
                floatSamples[i] = left
            }
        } else if (audioFormat == 1 && bitsPerSample == 16) {
            for (i in 0 until frameCount) {
                val left = rawBuf.short.toInt()
                if (channels == 2) {
                    rawBuf.short // skip right channel
                }
                floatSamples[i] = left / 32768.0f
            }
        } else if (audioFormat == 1 && bitsPerSample == 24) {
            for (i in 0 until frameCount) {
                val left = readPcm24()
                if (channels == 2) {
                    readPcm24() // skip right channel
                }
                floatSamples[i] = left / 8388608.0f
            }
        } else if (audioFormat == 1 && bitsPerSample == 8) {
            for (i in 0 until frameCount) {
                val left = (rawBuf.get().toInt() and 0xFF) - 128
                if (channels == 2) {
                    rawBuf.get() // skip right channel
                }
                floatSamples[i] = left / 128.0f
            }
        } else {
            throw IllegalArgumentException("Unsupported combination of format=$audioFormat and bitsPerSample=$bitsPerSample")
        }
        
        return WavFloatData(floatSamples, sampleRate)
    }
}

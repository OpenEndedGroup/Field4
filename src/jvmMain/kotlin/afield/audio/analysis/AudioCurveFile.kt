@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.floor

/** A single oldest-to-newest curve. Coordinates are point-major, with [dimensions] floats per point. */
data class AudioCurveFrame(val timeSeconds: Double, val dimensions: Int, val coordinates: FloatArray) {
    val pointCount: Int get() = coordinates.size / dimensions
}

/**
 * Read a completed `fieldaudio_curve` v1 file using read-only memory maps. The channel stays open
 * until [close]; frames are copied out and remain usable afterwards. Large files are mapped one
 * record at a time, so their total size is not limited by a JVM ByteBuffer's integer capacity.
 */
class AudioCurveFile(path: Path) : Closeable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    val sampleRate: Int
    val fftSize: Int
    val hopSize: Int
    val history: Int
    val dimensions: Int
    val frameCount: Long
    private val recordSize: Int

    init {
        try {
            require(channel.size() >= 64) { "Truncated audio curve header" }
            val h = channel.map(FileChannel.MapMode.READ_ONLY, 0, 64).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8).also { h.get(it) }
            require(magic.contentEquals(byteArrayOf(70, 52, 67, 85, 82, 86, 69, 0))) { "Not an audio curve file" }
            require(h.int == 1) { "Unsupported audio curve version" }
            sampleRate = h.int
            fftSize = h.int
            hopSize = h.int
            history = h.int
            dimensions = h.int
            frameCount = h.long
            require(sampleRate > 0 && fftSize >= 4 && hopSize in 1..fftSize && history >= 3 && dimensions in 2..3 && frameCount >= 0) {
                "Invalid audio curve header"
            }
            val size = 16L + 4L * history * dimensions
            require(size <= Int.MAX_VALUE) { "Audio curve record is too large" }
            recordSize = size.toInt()
            require(frameCount <= (Long.MAX_VALUE - 64) / recordSize && channel.size() == 64 + frameCount * recordSize) {
                "Audio curve file length does not match its header"
            }
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    /** Read a curve by index. */
    fun frame(index: Long): AudioCurveFrame {
        require(index in 0 until frameCount) { "Audio curve frame index out of range: $index" }
        val b: ByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 64 + index * recordSize, recordSize.toLong())
            .order(ByteOrder.LITTLE_ENDIAN)
        val time = b.double
        val count = b.int
        b.int // reserved
        require(time.isFinite() && count in 1..history) { "Invalid audio curve record $index" }
        val coordinates = FloatArray(count * dimensions) { b.float }
        require(coordinates.all { it.isFinite() }) { "Non-finite audio curve coordinate in record $index" }
        return AudioCurveFrame(time, dimensions, coordinates)
    }

    /** The most recent completed analysis window at [seconds], or null before its center. */
    fun frameAt(seconds: Double): AudioCurveFrame? {
        require(seconds.isFinite()) { "Playback time must be finite" }
        if (frameCount == 0L) return null
        val index = floor((seconds * sampleRate - fftSize / 2.0) / hopSize).toLong()
        if (index < 0) return null
        return frame(index.coerceAtMost(frameCount - 1))
    }

    override fun close() = channel.close()
}

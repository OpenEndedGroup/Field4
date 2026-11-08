@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.floor

/**
 * A single curve. Coordinates are point-major, with [dimensions] floats per point.
 *
 * In a rolling or EMA file the points run oldest (or longest timescale) first. In a map file
 * (`AudioCurveSettings.somLearnSeconds`) they are the map's prototypes in chain order followed by
 * the present spectrum, and [bestMatch] is the index of the prototype nearest the present.
 */
data class AudioCurveFrame(
    val timeSeconds: Double,
    val dimensions: Int,
    val coordinates: FloatArray,
    val bestMatch: Int? = null,
    /** A map file's grid of prototypes (see [AudioCurveFile.somGrid]); null otherwise. */
    val somGrid: List<Int>? = null,
) {
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
    /**
     * Null for a rolling window, where point `j` is a spectrum `count - 1 - j` hops old. For an
     * EMA file (`AudioCurveSettings.emaLongestSeconds`), the longest time constant in seconds:
     * the points are running averages over timescales from this down to one hop.
     */
    val emaLongestSeconds: Double?
    /**
     * Null unless this is a self-organizing map file (`AudioCurveSettings.somLearnSeconds`), where it
     * is the best match's learning time constant in seconds. Each frame then holds [history] - 1
     * prototypes plus the present, and carries its [AudioCurveFrame.bestMatch].
     */
    val somLearnSeconds: Double?
    /**
     * A map file's grid of prototypes: one size for a chain (`[48]`), two for a sheet (`[12, 12]`),
     * three for a lattice (`[6, 6, 6]`). Prototypes are listed with the last axis varying fastest.
     * Null for other files.
     */
    val somGrid: List<Int>?
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
            val kind = h.getInt(40)
            require(kind in 0..2) { "Unknown audio curve kind $kind" }
            emaLongestSeconds = if (kind == 1) h.getDouble(44) else null
            somLearnSeconds = if (kind == 2) h.getDouble(44) else null
            somGrid = if (kind == 2) {
                val sizes = listOf(h.getInt(52), h.getInt(56), h.getInt(60))
                // A file with no grid recorded is a chain.
                if (sizes.all { it == 0 }) listOf(h.getInt(24) - 1) else sizes.dropLastWhile { it == 1 }.ifEmpty { listOf(1) }
            } else null
            require(sampleRate > 0 && fftSize >= 4 && hopSize in 1..fftSize && history >= 3 && dimensions in 2..3 && frameCount >= 0) {
                "Invalid audio curve header"
            }
            val size = 16L + 4L * history * dimensions
            require(size <= Int.MAX_VALUE) { "Audio curve record is too large" }
            recordSize = size.toInt()
            require(somGrid == null || (somGrid.all { it >= 1 } && somGrid.fold(1L) { a, b -> a * b } == history - 1L)) {
                "Audio curve grid $somGrid does not hold ${history - 1} prototypes"
            }
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
        val reserved = b.int // the best match, in a map file
        require(time.isFinite() && count in 1..history) { "Invalid audio curve record $index" }
        val coordinates = FloatArray(count * dimensions) { b.float }
        require(coordinates.all { it.isFinite() }) { "Non-finite audio curve coordinate in record $index" }
        val bestMatch = if (somLearnSeconds != null) reserved.also {
            require(it in 0 until count - 1) { "Invalid best match in audio curve record $index" }
        } else null
        return AudioCurveFrame(time, dimensions, coordinates, bestMatch, somGrid)
    }

    /** The most recent completed analysis window at [seconds], or null before its center. */
    fun frameAt(seconds: Double): AudioCurveFrame? {
        require(seconds.isFinite()) { "Playback time must be finite" }
        if (frameCount == 0L) return null
        val index = floor((seconds * sampleRate - fftSize / 2.0) / hopSize).toLong()
        if (index < 0) return null
        return frame(index.coerceAtMost(frameCount - 1))
    }

    /**
     * The best matches of the map frames in the [seconds] up to [at], oldest first: the path the
     * present took across the map, for `AudioCurveFrame.flines(trail = ...)`. Empty for other files.
     */
    fun recentMatches(at: Double, seconds: Double = 1.0): List<Int> {
        if (somLearnSeconds == null || frameCount == 0L) return emptyList()
        val hops = (seconds * sampleRate / hopSize).toLong().coerceAtLeast(1)
        val last = floor((at * sampleRate - fftSize / 2.0) / hopSize).toLong().coerceAtMost(frameCount - 1)
        if (last < 0) return emptyList()
        return (maxOf(0, last - hops + 1)..last).map { frame(it).bestMatch!! }
    }

    override fun close() = channel.close()
}

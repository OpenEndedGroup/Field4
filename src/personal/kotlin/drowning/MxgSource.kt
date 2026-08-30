package drowning

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class MxgSnapshot(val serial: Long, val frame: MxgFrame)

/**
 * Background decompression/MessagePack/contour decoding for a real-time Field render loop.
 *
 * [open] profiles the complete records currently in the file once, so the box can allocate exact
 * fixed-size Vulkan buffers before playback starts. The worker then stays one concern wide: decode a
 * frame, publish an immutable [MxgSnapshot], and pace publication from stored timestamps. Vulkan
 * upload remains on the renderer's loop via [MxgFrame.writeInto].
 *
 * Growing streams are supported with [follow]. Supply explicit capacities when future records may be
 * larger than the current prefix; an oversized frame is not partially uploaded—it is reported through
 * [failure] and playback stops.
 */
class MxgSource private constructor(
    val file: MxgFile,
    val profile: MxgProfile,
    val capacity: MxgCapacity,
    var loop: Boolean,
    var follow: Boolean,
    var playbackRate: Double
) : AutoCloseable {
    private val published = AtomicReference<MxgSnapshot?>()
    private val serial = AtomicLong()

    @Volatile var paused: Boolean = false
    @Volatile var running: Boolean = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var decodedFrames: Long = 0
        private set
    @Volatile var decodeMicros: Long = 0
        private set

    private var worker: Thread? = null
    private val seekRequest = AtomicLong(NO_SEEK)

    val latest: MxgSnapshot? get() = published.get()
    val header: MxgHeader get() = file.header

    fun start(): MxgSource {
        if (running) return this
        failure = null
        running = true
        worker = thread(name = "mxg-${file.path.fileName}", isDaemon = true) { runWorker() }
        return this
    }

    fun seek(recordIndex: Int) {
        require(recordIndex >= 0)
        seekRequest.set(recordIndex.toLong())
    }

    fun stop(): MxgSource {
        running = false
        worker?.interrupt()
        worker = null
        return this
    }

    private fun runWorker() {
        var index = 0
        var clockRecord = 0.0
        var clockNanos = System.nanoTime()
        try {
            while (running) {
                val seek = seekRequest.getAndSet(NO_SEEK)
                if (seek != NO_SEEK) {
                    index = seek.toInt().coerceAtMost((file.frameCount - 1).coerceAtLeast(0))
                    clockRecord = Double.NaN
                }
                var wasPaused = false
                while (paused && running) {
                    wasPaused = true
                    Thread.sleep(4)
                }
                if (!running) break
                if (wasPaused) clockRecord = Double.NaN

                if (index >= file.frameCount) {
                    if (follow && !file.ended) {
                        if (file.refresh() == 0) Thread.sleep(12)
                        continue
                    }
                    if (loop && file.frameCount > 0) {
                        index = 0
                        clockRecord = Double.NaN
                    } else break
                }

                val started = System.nanoTime()
                val frame = file.readFrame(index)
                decodeMicros = (System.nanoTime() - started) / 1_000L
                decodedFrames++
                require(frame.nodeCount <= capacity.nodes && frame.segmentCount <= capacity.segments) {
                    "${file.path}: frame ${frame.frameIndex} needs ${frame.nodeCount} nodes and " +
                        "${frame.segmentCount} segments, buffers allow ${capacity.nodes} and ${capacity.segments}"
                }

                val rate = playbackRate
                if (!clockRecord.isFinite() || rate <= 0.0) {
                    clockRecord = frame.timestampSeconds
                    clockNanos = System.nanoTime()
                } else {
                    val due = clockNanos + (((frame.timestampSeconds - clockRecord) / rate) * 1e9).toLong()
                    sleepUntil(due)
                }
                if (!running) break
                published.set(MxgSnapshot(serial.incrementAndGet(), frame))
                index++
            }
        } catch (_: InterruptedException) {
            // stop() is the normal interruption path.
        } catch (t: Throwable) {
            failure = t
        } finally {
            running = false
        }
    }

    private fun sleepUntil(due: Long) {
        while (running) {
            val remaining = due - System.nanoTime()
            if (remaining <= 0) return
            val millis = (remaining / 1_000_000L).coerceAtMost(20L)
            val nanos = (remaining % 1_000_000L).toInt()
            Thread.sleep(millis, nanos)
        }
    }

    override fun close() {
        stop()
        file.close()
    }

    override fun toString(): String =
        "MxgSource(${file.path.fileName}, ${profile.frames} frames, capacity=${capacity.nodes} nodes/" +
            "${capacity.segments} segments, rate=${playbackRate}x, running=$running)"

    companion object {
        private const val NO_SEEK = Long.MIN_VALUE

        fun open(
            path: String,
            loop: Boolean = true,
            follow: Boolean = false,
            playbackRate: Double = 1.0,
            capacityNodes: Int? = null,
            capacitySegments: Int? = null
        ): MxgSource {
            val file = MxgFile.open(path)
            try {
                val profile = file.profile()
                require(profile.frames > 0) { "$path: no complete frame records" }
                val capacity = MxgCapacity(
                    maxOf(profile.capacity.nodes, capacityNodes ?: 0),
                    maxOf(profile.capacity.segments, capacitySegments ?: 0)
                )
                return MxgSource(file, profile, capacity, loop, follow, playbackRate)
            } catch (t: Throwable) {
                file.close()
                throw t
            }
        }
    }
}

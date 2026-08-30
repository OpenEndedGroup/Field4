package drowning

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

data class MxgCueSnapshot(
    val serial: Long,
    val requestedFrame: Int,
    val frames: List<MxgFrame>,
    val offsets: IntArray = IntArray(frames.size)
)

/**
 * Random-access decoder for all 15 MXGs. A requested timeline frame is decoded on one worker and the
 * complete 15-frame set is published atomically, so the renderer never displays a half-new circle.
 */
class MxgCueBank private constructor(
    private val files: List<MxgFile>,
    val profiles: List<MxgProfile>,
    val kind: DrowningMxgKind
) : AutoCloseable {
    private data class Request(val generation: Long, val frame: Int, val offsets: IntArray)
    private val requestLock = Any()
    private val reads = AtomicLong()

    private val pending = AtomicReference<Request?>()
    private val requested = AtomicReference<Request?>()
    private val generation = AtomicLong()
    private val serial = AtomicLong()
    private val published = AtomicReference<MxgCueSnapshot?>()
    private var worker: Thread? = null

    @Volatile var running: Boolean = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var decodeMicros: Long = 0
        private set
    @Volatile var active: Boolean = true
        private set
    @Volatile var preparing: Boolean = false
        private set
    val decodedFrames: Long get() = reads.get()
    val completedSets: Long get() = serial.get()

    /** Cancel queued work and park. Leave file handles open so resuming never races a closed read. */
    fun setActive(value: Boolean) = synchronized(requestLock) {
        if (active == value) return@synchronized
        active = value
        requested.set(null)
        pending.set(null)
        published.set(null)
        LockSupport.unpark(worker)
    }

    val latest: MxgCueSnapshot? get() = published.get()
    val capacities: List<MxgCapacity> get() = profiles.map { it.capacity }

    fun start(): MxgCueBank {
        if (running) return this
        running = true
        failure = null
        worker = thread(name = "mxg-circle-${kind.name.lowercase()}", isDaemon = true) { runWorker() }
        return this
    }

    fun request(frame: Int, offsets: IntArray = ZERO_OFFSETS): Boolean = synchronized(requestLock) {
        require(offsets.size == files.size) { "need ${files.size} MXG offsets, got ${offsets.size}" }
        if (!active || !running) return@synchronized false
        val previous = requested.get()
        if (previous?.frame == frame && previous.offsets.contentEquals(offsets)) return@synchronized false
        val request = Request(generation.incrementAndGet(), frame, offsets.copyOf())
        requested.set(request)
        pending.set(request)
        LockSupport.unpark(worker)
        true
    }

    private fun current(request: Request) = running && active && requested.get() === request

    private fun runWorker() {
        try {
            while (running) {
                val request = pending.getAndSet(null)
                if (request == null) {
                    LockSupport.park()
                    continue
                }
                preparing = true
                try {
                    val started = System.nanoTime()
                    val frames = ArrayList<MxgFrame>(files.size)
                    for ((index, file) in files.withIndex()) {
                        if (!current(request)) break
                        reads.incrementAndGet()
                        frames += file.readFrame(Math.floorMod(request.frame + request.offsets[index], file.frameCount))
                    }
                    decodeMicros = (System.nanoTime() - started) / 1_000L
                    synchronized(requestLock) {
                        if (current(request) && frames.size == files.size) {
                            published.set(MxgCueSnapshot(serial.incrementAndGet(), request.frame, frames, request.offsets))
                        }
                    }
                } finally { preparing = false }
            }
        } catch (t: Throwable) {
            if (running) failure = t
        } finally {
            running = false
        }
    }

    override fun close() {
        running = false
        LockSupport.unpark(worker)
        worker?.interrupt()
        worker = null
        files.forEach(MxgFile::close)
    }

    override fun toString(): String =
        "MxgCueBank(${kind.name}, ${files.size} files, ${profiles.minOf { it.frames }}.." +
            "${profiles.maxOf { it.frames }} frames, running=$running)"

    companion object {
        private val ZERO_OFFSETS = IntArray(15)

        fun open(cue: DrowningCue, kind: DrowningMxgKind = DrowningMxgKind.Surface): MxgCueBank {
            val paths = cue.cells.map { it.mxg(kind) }
            val opened = ArrayList<MxgFile>(paths.size)
            try {
                paths.forEach { path -> opened += MxgFile.open(path.toPath()) }
                val profiles = opened.map(MxgFile::profile)
                return MxgCueBank(opened, profiles, kind)
            } catch (t: Throwable) {
                opened.forEach(MxgFile::close)
                throw t
            }
        }

        fun open(paths: List<File>, kind: DrowningMxgKind): MxgCueBank {
            require(paths.size == 15)
            val opened = paths.map { MxgFile.open(it.toPath()) }
            return try {
                MxgCueBank(opened, opened.map(MxgFile::profile), kind)
            } catch (t: Throwable) {
                opened.forEach(MxgFile::close)
                throw t
            }
        }
    }
}

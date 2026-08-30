package drowning

import afield.four.fabricated.SplatSequence
import afield.four.fabricated.GaussianSplatRenderer
import afield.four.fabricated.GaussianSplatRenderer.SplatSource
import afield.four.modules.CompressedSplatPly
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.min

data class SplatCueSnapshot(
    val serial: Long,
    val frames: IntArray,
    val floats: FloatBuffer,
    val packed: GaussianSplatRenderer.PackedFrame
)

/**
 * Reads and packs one frame from each splat sequence away from the render thread, then publishes the
 * complete circle atomically. Pending requests collapse to the newest timeline position without
 * ever showing a circle made from two positions.
 */
class SplatCueBank(
    private val sequences: List<SplatSequence>,
    private val capacity: Int,
    private val transforms: List<Matrix4f>,
    private val config: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
    private val sortAlong: Vector3f? = null,
    parallelism: Int = min(8, sequences.size)
) : AutoCloseable {
    private data class Request(val frames: IntArray, val epoch: Long)
    private val requestLock = Any()
    private val epoch = AtomicLong()
    private val reads = AtomicLong()

    init {
        require(sequences.isNotEmpty()) { "a splat cue bank needs at least one sequence" }
        require(capacity >= 0) { "capacity must not be negative" }
        require(transforms.size == sequences.size) {
            "need ${sequences.size} splat transforms, got ${transforms.size}"
        }
        require(parallelism >= 1) { "parallelism must be positive" }
    }

    private val pool = Executors.newFixedThreadPool(parallelism) { runnable ->
        Thread(runnable, "splat-circle-reader").apply { isDaemon = true }
    }
    private val pending = AtomicReference<Request?>()
    private val requested = AtomicReference<Request?>()
    private val serial = AtomicLong()
    private val published = AtomicReference<SplatCueSnapshot?>()
    private val freeBuffers = ArrayBlockingQueue<FloatBuffer>(2).apply {
        repeat(2) {
            add(
                ByteBuffer.allocateDirect(capacity * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            )
        }
    }
    private var coordinator: Thread? = null

    @Volatile var running: Boolean = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var decodeMicros: Long = 0
        private set
    @Volatile var packMicros: Long = 0
        private set
    @Volatile var active: Boolean = true
        private set
    @Volatile var preparing: Boolean = false
        private set
    val decodedFrames: Long get() = reads.get()
    val completedSets: Long get() = serial.get()

    /** Park without destroying caches. In-flight reads may finish; stale work cannot publish. */
    fun setActive(value: Boolean) = synchronized(requestLock) {
        if (active == value) return@synchronized
        active = value
        invalidate()
        LockSupport.unpark(coordinator)
    }

    /** Transfers ownership to the caller until [release] is called. */
    fun takeLatest(): SplatCueSnapshot? = published.getAndSet(null)

    /** Inspect the prepared source-frame identities without taking ownership of the upload buffer. */
    fun latestFrames(): IntArray? = published.get()?.frames?.copyOf()

    /** Reprepare the same frame after a presenter or GPU consumer has been replaced. */
    fun invalidate() = synchronized(requestLock) {
        epoch.incrementAndGet()
        requested.set(null)
        pending.set(null)
        published.getAndSet(null)?.let(::release)
    }

    fun release(snapshot: SplatCueSnapshot) {
        snapshot.floats.clear()
        check(freeBuffers.offer(snapshot.floats)) { "splat snapshot released twice" }
    }

    fun start(): SplatCueBank {
        if (running) return this
        running = true
        failure = null
        coordinator = thread(name = "splat-circle-coordinator", isDaemon = true) { runCoordinator() }
        return this
    }

    fun request(frames: IntArray): Boolean = synchronized(requestLock) {
        require(frames.size == sequences.size) {
            "need ${sequences.size} splat frames, got ${frames.size}"
        }
        if (!active || !running) return@synchronized false
        val previous = requested.get()
        if (previous != null && previous.frames.contentEquals(frames)) return@synchronized false
        val request = Request(frames.copyOf(), epoch.get())
        requested.set(request)
        pending.set(request)
        LockSupport.unpark(coordinator)
        true
    }

    private fun current(request: Request) = running && active && request.epoch == epoch.get()

    private fun runCoordinator() {
        try {
            while (running) {
                val request = pending.getAndSet(null)
                if (request == null) {
                    LockSupport.park()
                    continue
                }
                preparing = true
                try {
                    if (!current(request)) continue
                    val started = System.nanoTime()
                    val jobs: List<Future<CompressedSplatPly.RawSplats?>> = sequences.mapIndexed { index, sequence ->
                        pool.submit(Callable {
                            if (!current(request)) null else {
                                reads.incrementAndGet()
                                sequence.raw(request.frames[index])
                            }
                        })
                    }
                    val splats = jobs.map { it.get() }
                    decodeMicros = (System.nanoTime() - started) / 1_000L
                    if (!current(request) || splats.any { it == null }) continue
                    val floats = freeBuffers.take().apply { clear() }
                    var handedOff = false
                    try {
                        if (!current(request)) continue
                        val packStarted = System.nanoTime()
                        val sources = splats.mapIndexed { index, raw -> SplatSource(checkNotNull(raw), transforms[index]) }
                        val packed = GaussianSplatRenderer.packInto(
                            sources = sources, dest = floats, config = config,
                            sortAlong = sortAlong, parallelSort = true
                        )
                        packMicros = (System.nanoTime() - packStarted) / 1_000L
                        synchronized(requestLock) {
                            if (current(request)) {
                                val next = SplatCueSnapshot(serial.incrementAndGet(), request.frames, floats, packed)
                                published.getAndSet(next)?.let(::release)
                                handedOff = true
                            }
                        }
                    } finally {
                        if (!handedOff) { floats.clear(); check(freeBuffers.offer(floats)) }
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
        LockSupport.unpark(coordinator)
        coordinator?.interrupt()
        coordinator = null
        published.getAndSet(null)?.let(::release)
        pool.shutdownNow()
    }
}

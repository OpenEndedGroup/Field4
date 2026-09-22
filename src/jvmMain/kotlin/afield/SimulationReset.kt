package afield

import java.util.concurrent.atomic.AtomicLong

/**
 * Coalesced reset requests, consumed explicitly at an application's frame boundary.
 * This is not a launcher: create it in normal box setup and consume it inside the
 * existing frames/loop coroutine. Requests can arrive from UI/agent threads.
 *
 * The reset callback runs on the consuming thread. Keep GPU/audio allocations
 * outside it; reset their contents/history explicitly on their owning threads.
 */
class SimulationReset {
    private val requested = AtomicLong()
    @Volatile private var consumed = 0L
    private var lastFrame: Long? = null
    private var owner: Thread? = null
    @Volatile var completed: Long = 0
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var epoch: Any = Any()
        private set
    val pending: Boolean get() = requested.get() > consumed

    /** Returns a ticket. Multiple requests before the next boundary share one reset. */
    fun request(): Long = requested.updateAndGet { check(it < Long.MAX_VALUE); it + 1 }

    /**
     * At most once per frame id. A failed callback is reported and is not retried
     * silently; request again after repairing it. No state rollback is implied.
     * A request arriving during the callback remains pending for the next frame.
     */
    fun atFrame(frame: Long, reset: () -> Unit): Boolean {
        val thread = Thread.currentThread()
        check(owner == null || owner === thread) { "Consume resets on one owner thread" }
        owner = thread
        if (lastFrame == frame) return false
        lastFrame = frame
        val ticket = requested.get()
        if (ticket == consumed) return false
        consumed = ticket
        try {
            reset()
            epoch = Any()
            completed = ticket
            failure = null
            return true
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.name
            throw t
        }
    }
}

package afield.four.util

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * GC-driven disposal, with optional device ownership for deterministic shutdown.
 * Cleanup actions must capture handles, not the registered object itself.
 *
 * A renderer drains only its own device's collected objects onto its retirement queue.
 * At shutdown it also releases still-reachable resources, after waiting for the device.
 * Explicit cleanup and an already-enqueued GC action share one token and cannot both win.
 */
object AutoDisposer {
    class Token internal constructor(
        internal val ref: PhantomReference<Any>,
        internal val name: String,
        internal val site: StackTraceElement?,
        internal val owner: Any?,
        internal val order: Long,
    ) {
        private var cleanup: (() -> Unit)? = null

        @Synchronized
        fun updateCleanup(c: () -> Unit) {
            cleanup = c
            entries[ref] = this
        }

        /** Claim ownership for explicit cleanup; false means another path already released it. */
        fun disable(): Boolean = takeCleanup() != null

        @Synchronized
        private fun takeCleanup(): (() -> Unit)? {
            val action = cleanup
            cleanup = null
            entries.remove(ref, this)
            ready.remove(ref, this)
            return action
        }

        internal fun runCleanup() {
            val action = takeCleanup() ?: return
            action()
        }
    }

    private val queue = ReferenceQueue<Any>()
    private val entries = ConcurrentHashMap<PhantomReference<Any>, Token>()
    private val ready = ConcurrentHashMap<PhantomReference<Any>, Token>()
    private val nextOrder = AtomicLong()

    fun register(
        target: Any,
        name: String = target.javaClass.simpleName.ifEmpty { "unnamed" },
        owner: Any? = null,
        cleanup: () -> Unit,
    ): Token {
        val trace = Exception().stackTrace
        val site = trace.getOrNull(2) ?: trace.getOrNull(1)
        val ref = PhantomReference(target, queue)
        return Token(ref, name, site, owner, nextOrder.incrementAndGet()).also {
            it.updateCleanup(cleanup)
        }
    }

    /** Poll collected objects, keeping other owners' releases for their own render thread. */
    fun drain(owner: Any? = null, enqueue: (Runnable) -> Unit) {
        while (true) {
            val ref = queue.poll() ?: break
            entries[ref]?.let { ready[it.ref] = it }
        }
        if (ready.isEmpty()) return
        ready.values.filter { it.owner === owner }.sortedByDescending { it.order }.forEach { token ->
            if (ready.remove(token.ref, token)) enqueue(Runnable { token.runCleanup() })
        }
    }

    /** Release remaining resources, including those already awaiting deferred GC cleanup. */
    internal fun releaseOwner(owner: Any, enqueue: (Runnable) -> Unit) {
        // Reverse construction order puts views after their use and before their images.
        entries.values.filter { it.owner === owner }.sortedByDescending { it.order }.forEach { token ->
            enqueue(Runnable { token.runCleanup() })
        }
    }

    internal fun ownedCount(owner: Any): Int = entries.values.count { it.owner === owner }
}

package afield.four.util

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple GC-driven disposer. Register a PhantomReference for an object along with
 * a cleanup action that can be drained and executed later (e.g. on the render thread).
 *
 * Usage:
 *   val token = AutoDisposer.register(this, "MyBuffer") { /* destroy handles */ }
 *   token.updateCleanup { /* refresh handles after realloc */ }
 *   token.disable() // if you explicitly cleaned up already
 */
object AutoDisposer {
    class Token internal constructor(
        internal val ref: PhantomReference<Any>,
        internal val name: String,
        internal val site: StackTraceElement?
    ) {
        @Volatile internal var cleanup: () -> Unit = {}
        fun updateCleanup(c: () -> Unit) { cleanup = c }
        fun disable() { cleanup = {} }
    }

    private val queue = ReferenceQueue<Any>()
    private val entries = ConcurrentHashMap<PhantomReference<Any>, Token>()

    @Suppress("UNCHECKED_CAST")
    fun register(target: Any, name: String = target.javaClass.simpleName.ifEmpty { "unnamed" }, cleanup: () -> Unit): Token {
        val trace = Exception().stackTrace
        val site = trace.getOrNull(2) ?: trace.getOrNull(1)
        val ref = PhantomReference(target, queue)
        val token = Token(ref, name, site)
        token.cleanup = cleanup
        entries[ref] = token
        return token
    }

    /** Polls the queue and enqueues cleanup actions into the provided consumer. */
    fun drain(enqueue: (Runnable) -> Unit) {
        while (true) {
            val ref = queue.poll() ?: break
            val token = entries.remove(ref)
            val action = token?.cleanup
            if (action != null) enqueue(
                Runnable {
                    val site = token.site?.toString() ?: "unknown site"
                    println("AutoDisposer: ${token.name} @ $site")
                    action.invoke()
                }
            )
        }
    }
}

package afield.runtime.lifecycle

import afield.document.graph.Box
import java.util.concurrent.ConcurrentHashMap

/** A named callback slot on one object; removal always compares the exact subscription. */
private class ListenerKey(val owner: Any, val name: String) {
    override fun equals(other: Any?) = other is ListenerKey && owner === other.owner && name == other.name

    override fun hashCode() = 31 * System.identityHashCode(owner) + name.hashCode()
}

private val runListeners = ConcurrentHashMap<ListenerKey, AutoCloseable>()

/** Replace a named listener and release it on Stop, rerun or retirement of the registering run. */
internal fun <T> runOwnedListener(
    owner: Any,
    name: String,
    subscribe: ((T) -> Unit) -> (() -> Unit),
    callback: (T) -> Unit,
): () -> Unit {
    require(name.isNotBlank()) { "A listener needs a name" }
    val scope = requireNotNull(RunScope.current()) { "Register named listeners from a box run" }
    val key = ListenerKey(owner, name)
    val gate = Any()
    var closed = false
    var remove: (() -> Unit)? = null
    val registration = object : AutoCloseable {
        override fun close() = synchronized(gate) {
            if (!closed) {
                closed = true
                runListeners.remove(key, this)
                remove?.invoke()
                remove = null
            }
        }
    }
    runListeners.put(key, registration)?.close()
    try {
        synchronized(gate) {
            if (!closed)
                remove = subscribe { event ->
                    synchronized(gate) {
                        if (!closed && !scope.isClosed && !scope.isStopped && runListeners[key] === registration) {
                            Box.withBoxContextNow(scope.box) {
                                RunScope.withCurrent(scope) { callback(event) }
                            }
                        }
                    }
                }
        }
        scope.own(registration)
    } catch (failure: Throwable) {
        registration.close()
        throw failure
    }
    return registration::close
}

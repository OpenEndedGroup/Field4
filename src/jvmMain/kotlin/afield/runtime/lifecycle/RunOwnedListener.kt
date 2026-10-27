package afield.runtime.lifecycle

import afield.document.graph.Box
import java.util.concurrent.ConcurrentHashMap

/**
 * A box's callbacks on a long-lived object, registered so that stopping or rerunning the box takes
 * them off again.
 *
 * `runOwnedListener` subscribes under an owner object and a name, and both halves of that pair
 * matter: registering the same name again closes the previous subscription rather than adding a
 * second one, and the owner is compared by identity, so two objects with equal contents keep
 * separate slots. The registering box's [RunScope] owns the result, which is what releases it on
 * Stop, on a rerun, and when the run is retired; calling it from outside a box run fails.
 *
 * Events arrive with the registering box's context and run scope restored, so a callback behaves as
 * if it were still inside the run. One that arrives after the scope stopped, or after a later
 * registration took the same name, is dropped rather than delivered. Orca's listeners and
 * `PuppetBrowser`'s page events are registered this way.
 */

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

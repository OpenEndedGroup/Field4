@file:afield.runtime.completion.CompletionCategory("Execution")

package afield.runtime.lifecycle

import afield.document.graph.Box
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * A future-backed operation whose callbacks stop firing once the box run that started it ends.
 *
 * [AsyncOperation] wraps a [java.util.concurrent.CompletableFuture] so that progress, result and error
 * callbacks run in the starting box's context and are suppressed after that run is stopped, re-run
 * or retired. Closing it cancels the source future and runs any registered cancellation hooks.
 */

/**
 * One asynchronous operation owned by the run that started it. [progress] and the result/error
 * callbacks run only while that run is alive, in its box context, on the completing thread.
 * Keep these callbacks short and non-blocking. Shared clients and cached results are not owned here.
 *
 * [close] suppresses further publication, cancels the source future and invokes [onCancel] hooks.
 * Cancelling a future does not in general stop its worker: register an explicit hook if the
 * underlying API supports cancellation. [own] releases operation-local resources on every ending.
 */
class AsyncOperation<T> internal constructor(
    private val scope: RunScope?,
    private val onResult: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    /** The result, cancelled when its run ends. Cancelling it also releases this operation. */
    val future = CompletableFuture<T>()
    private val lock = Any()
    private var active = true
    private var abandoned = false
    private var source: CompletableFuture<T>? = null
    private val resources = mutableListOf<AutoCloseable>()
    private val cancellations = mutableListOf<() -> Unit>()

    init {
        future.whenComplete { _, _ -> if (future.isCancelled) close() }
    }

    /** Guard a progress callback with this operation's lifetime and originating box context. */
    fun <P> progress(callback: (P) -> Unit): (P) -> Unit = { value ->
        try {
            synchronized(lock) { if (isCurrent()) inContext { callback(value) } }
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    /** Release an operation-local resource on completion, failure or cancellation; never own a shared client. */
    fun <R : AutoCloseable> own(resource: R): R {
        synchronized(lock) {
            if (active) resources += resource else release(resource)
        }
        return resource
    }

    /** Request actual worker cancellation on abandonment, separately from cancelling its future. */
    fun onCancel(cancel: () -> Unit) {
        synchronized(lock) {
            if (active) cancellations += cancel else if (abandoned) runCatching(cancel)
        }
    }

    /** Fail this operation, including failures from separately arriving progress transports. */
    fun fail(failure: Throwable) = finish(null, unwrapAsyncFailure(failure))

    internal fun start(block: AsyncOperation<T>.() -> CompletableFuture<T>) {
        synchronized(lock) {
            if (!isCurrent()) { close(); return }
            try {
                val pending = inContext { block() }
                source = pending
                if (!active) pending.cancel(true)
                else pending.whenComplete { value, failure -> finish(value, failure?.let(::unwrapAsyncFailure)) }
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    private fun isCurrent(): Boolean = active && scope?.let { !it.isClosed && !it.isStopped } != false

    private fun <R> inContext(block: () -> R): R =
        if (scope == null) block()
        else Box.withBoxContextNow(scope.box) { RunScope.withCurrent(scope, block) }

    private fun finish(value: T?, failure: Throwable?) {
        synchronized(lock) {
            if (!active) return
            if (!isCurrent() || failure is CancellationException) { close(); return }
            var error = failure
            try {
                inContext {
                    if (error == null) {
                        @Suppress("UNCHECKED_CAST")
                        onResult(value as T)
                    } else onError(error!!)
                }
            } catch (callbackFailure: Throwable) {
                error = callbackFailure
                if (failure == null) runCatching { inContext { onError(callbackFailure) } }
            }
            if (!active) return
            active = false
            if (error != null) {
                abandoned = true
                cancellations.asReversed().forEach { runCatching(it) }
                source?.cancel(true)
            }
            cancellations.clear()
            releaseResources()
            scope?.disown(this)
            if (error == null) {
                @Suppress("UNCHECKED_CAST")
                future.complete(value as T)
            } else future.completeExceptionally(error)
        }
    }

    /** Abandon this operation without closing shared clients or removing cached service results. */
    override fun close() {
        synchronized(lock) {
            if (!active) return
            active = false
            abandoned = true
            cancellations.asReversed().forEach { runCatching(it) }
            cancellations.clear()
            source?.cancel(true)
            releaseResources()
            scope?.disown(this)
            future.cancel(false)
        }
    }

    private fun releaseResources() {
        resources.asReversed().forEach(::release)
        resources.clear()
    }

    private fun release(resource: AutoCloseable) {
        runCatching { resource.close() }.onFailure { failure ->
            scope?.box?.displayError("Async resource cleanup failed: ${failure.message}")
                ?: System.err.println("Async resource cleanup failed: $failure")
        }
    }
}

/**
 * Start background work with run-owned publication and cleanup. Capture progress before starting:
 *
 * ```kotlin
 * runAsync(onResult = { result: LorisResult -> display("audio", result.samples) }) {
 *     client.analyzeWavAsync(path, onProgress = progress { text: String -> println(text) })
 * }
 * ```
 * The start block must return promptly with a future. Use [AsyncOperation.onCancel] for an API's
 * supported worker cancellation and [AsyncOperation.own] for resources local to this request.
 */
fun <T> runAsync(
    onResult: (T) -> Unit = {},
    onError: ((Throwable) -> Unit)? = null,
    start: AsyncOperation<T>.() -> CompletableFuture<T>,
): AsyncOperation<T> {
    val scope = RunScope.current()
    val operation = AsyncOperation(scope, onResult, onError ?: { failure ->
        scope?.box?.displayError("Async operation failed: ${failure.message ?: failure.javaClass.simpleName}")
            ?: System.err.println("Async operation failed: $failure")
    })
    scope?.own(operation)
    operation.start(start)
    return operation
}

internal fun unwrapAsyncFailure(failure: Throwable): Throwable =
    if ((failure is CompletionException || failure is ExecutionException) && failure.cause != null)
        unwrapAsyncFailure(failure.cause!!) else failure

private val asyncWorkers = Executors.newCachedThreadPool { task ->
    Thread(task, "Field-async-worker").apply { isDaemon = true }
}

/** Interruptible worker bridge for blocking native clients that clean up their process in finally. */
internal fun <T> interruptibleFuture(block: () -> T): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    val task = FutureTask {
        try { result.complete(block()) }
        catch (failure: Throwable) { result.completeExceptionally(failure) }
    }
    result.whenComplete { _, _ -> if (result.isCancelled) task.cancel(true) }
    asyncWorkers.execute(task)
    return result
}

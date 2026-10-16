package afield.runtime.lifecycle

import afield.document.graph.Box
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AsyncOperationTest {
    private class Uncancellable<T> : CompletableFuture<T>() {
        var cancellationRequested = false
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancellationRequested = true
            return false
        }
    }

    @Test fun stopSuppressesLateProgressAndResultsWithoutOwningSharedClients() {
        val box = Box("async-stop")
        val scope = box.runScopes.begin("code", "test")
        val source = Uncancellable<Int>()
        val seen = mutableListOf<Int>()
        var cancelRequests = 0
        var released = 0
        lateinit var publish: (Int) -> Unit
        val operation = RunScope.withCurrent(scope) {
            runAsync<Int>(onResult = { seen += it }) {
                publish = progress { value: Int ->
                    assertSame(box, Box.context.get())
                    seen += value
                }
                own(AutoCloseable { released++ })
                onCancel { cancelRequests++ }
                source
            }
        }
        publish(1)
        scope.stop()
        publish(2)
        source.complete(3)
        operation.close()
        assertEquals(listOf(1), seen)
        assertTrue(operation.future.isCancelled)
        assertTrue(source.cancellationRequested)
        assertEquals(1, cancelRequests)
        assertEquals(1, released)
        assertNull(Box.context.get())
    }

    @Test fun rerunPublishesOnlyItsOwnResult() {
        val box = Box("async-rerun")
        val oldScope = box.runScopes.begin("code", "test")
        val oldSource = Uncancellable<Int>()
        val seen = mutableListOf<Int>()
        val old = RunScope.withCurrent(oldScope) { runAsync<Int>(onResult = { seen += it }) { oldSource } }
        val newScope = box.runScopes.begin("code", "test")
        val nextSource = CompletableFuture<Int>()
        val next = RunScope.withCurrent(newScope) { runAsync<Int>(onResult = { seen += it }) { nextSource } }
        box.runScopes.supersede(newScope)
        oldSource.complete(1)
        nextSource.complete(2)
        assertEquals(listOf(2), seen)
        assertTrue(old.future.isCancelled)
        assertEquals(2, next.future.join())
        box.runScopes.closeAll()
    }

    @Test fun completionReleasesResourcesAndDetachesFromTheRun() {
        val box = Box("async-complete")
        val scope = box.runScopes.begin("code", "test")
        val source = CompletableFuture<Int>()
        var closed = 0
        var cancelled = false
        val operation = RunScope.withCurrent(scope) {
            runAsync<Int> {
                own(AutoCloseable { closed++ })
                onCancel { cancelled = true }
                source
            }
        }
        source.complete(7)
        assertEquals(7, operation.future.join())
        assertEquals(0, scope.liveCount)
        operation.own(AutoCloseable { closed++ })
        scope.stop()
        assertEquals(2, closed)
        assertFalse(cancelled)
    }

    @Test fun failureUnwrapsAndReportsInTheOriginatingContext() {
        val box = Box("async-failure")
        val scope = box.runScopes.begin("code", "test")
        val source = CompletableFuture<Int>()
        val failure = IllegalStateException("worker broke")
        var reported: Throwable? = null
        val operation = RunScope.withCurrent(scope) {
            runAsync<Int>(onError = { assertSame(box, Box.context.get()); reported = it }) { source }
        }
        source.completeExceptionally(CompletionException(failure))
        assertSame(failure, reported)
        assertSame(failure, assertFailsWith<CompletionException> { operation.future.join() }.cause)
        box.runScopes.closeAll()
    }

    @Test fun cancellingTheReturnedFutureReleasesItsWorker() {
        val source = CompletableFuture<Int>()
        var cancelled = 0
        val operation = runAsync<Int> { onCancel { cancelled++ }; source }
        operation.future.cancel(false)
        assertTrue(source.isCancelled)
        assertEquals(1, cancelled)
    }

    @Test fun progressFailureFailsTheOperationAndCleansUp() {
        var released = 0
        var failure: Throwable? = null
        lateinit var publish: (Int) -> Unit
        val source = CompletableFuture<Int>()
        val operation = runAsync<Int>(onError = { failure = it }) {
            own(AutoCloseable { released++ })
            publish = progress { _: Int -> throw IllegalArgumentException("bad progress") }
            source
        }
        publish(1)
        assertTrue(operation.future.isCompletedExceptionally)
        assertEquals("bad progress", failure?.message)
        assertTrue(source.isCancelled)
        assertEquals(1, released)
    }

    @Test fun aStoppedScopeDoesNotStartNewWork() {
        val scope = Box("async-stillborn").runScopes.begin("code", "test")
        scope.stop()
        var started = false
        val operation = RunScope.withCurrent(scope) {
            runAsync<Int> { started = true; CompletableFuture.completedFuture(1) }
        }
        assertFalse(started)
        assertTrue(operation.future.isCancelled)
    }

    @Test fun cancellationInterruptsABlockingWorkerAndRunsFinally() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val source = interruptibleFuture {
            try { entered.countDown(); CountDownLatch(1).await(); 1 }
            finally { exited.countDown() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val operation = runAsync<Int> { source }
        operation.close()
        assertTrue(exited.await(5, TimeUnit.SECONDS), "worker must be interrupted, not merely hidden")
    }
}

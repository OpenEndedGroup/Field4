package afield

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflatedMessagePumpTest {
    @Test
    fun `a slow sender keeps one coroutine and only the newest value per control`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enteredFirstSend = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        val received = CopyOnWriteArrayList<String>()
        val activeSenders = AtomicInteger()
        val maxActiveSenders = AtomicInteger()

        val pump = ConflatedMessagePump(scope, sender = { message ->
            val active = activeSenders.incrementAndGet()
            maxActiveSenders.updateAndGet { maxOf(it, active) }
            try {
                received += message
                if (message == "meter-a:0") {
                    enteredFirstSend.complete(Unit)
                    releaseFirstSend.await()
                }
            } finally {
                activeSenders.decrementAndGet()
            }
        })

        pump.offer("meter-a", "meter-a:0")
        enteredFirstSend.await()
        repeat(1_000) { value -> pump.offer("meter-a", "meter-a:${value + 1}") }
        repeat(1_000) { value -> pump.offer("meter-b", "meter-b:${value + 1}") }

        assertEquals(2, pump.pendingCount(), "the queue grew with updates instead of control ids")
        assertTrue(pump.isDraining())
        releaseFirstSend.complete(Unit)

        withTimeout(5_000) {
            while (pump.isDraining() || pump.pendingCount() != 0) yield()
        }
        scope.cancel()

        assertEquals(1, maxActiveSenders.get(), "more than one drain coroutine entered the sender")
        assertEquals("meter-a:1000", received.last { it.startsWith("meter-a:") })
        assertEquals("meter-b:1000", received.last { it.startsWith("meter-b:") })
        assertTrue(received.size <= 3, "obsolete values were sent instead of conflated: $received")
    }
}

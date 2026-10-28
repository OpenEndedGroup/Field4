package afield.app.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderedSessionSenderTest {

    @Test
    fun `sends leave in the order they were submitted, even when an earlier one is slower`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val out = ConcurrentLinkedQueue<Int>()
        val sender = OrderedSessionSender(scope)
        val n = 200
        // A slow first send would be overtaken by every later one under one launch per message.
        for (i in 0 until n) sender.submit { if (i % 7 == 0) delay(2); out.add(i) }
        withTimeout(10_000) { while (out.size < n) delay(5) }
        assertEquals((0 until n).toList(), out.toList())
        scope.cancel()
    }

    @Test
    fun `a failing send does not stop the ones behind it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val out = ConcurrentLinkedQueue<String>()
        val sender = OrderedSessionSender(scope)
        sender.submit { out.add("a") }
        sender.submit { error("closed socket") }
        sender.submit { out.add("c") }
        withTimeout(10_000) { while (out.size < 2) delay(5) }
        assertEquals(listOf("a", "c"), out.toList())
        scope.cancel()
    }
}

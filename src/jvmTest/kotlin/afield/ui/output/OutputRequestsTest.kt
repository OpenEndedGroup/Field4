package afield.ui.output

import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.protocol.OutputRequest
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class OutputRequestsTest {
    private fun box(readOnly: Boolean = false): Box {
        val root = Box("root")
        val info = SessionInformation(Files.createTempDirectory("output-requests-").toString(),
            documentName = "test", root = root, ephemeral = true, readOnly = readOnly)
        root.inside = info
        return Box("model").also { it.inside = info; info.boxes[it.id] = it }
    }

    @Test fun sameNamesInDifferentDocumentsStaySeparateAndReplacementRevokesOldClients() = runBlocking<Unit> {
        val a = box(); val b = box()
        val first = bindOutputRequests(a, "design", Dispatchers.Unconfined) { JsonPrimitive("first") }
        val other = bindOutputRequests(b, "design", Dispatchers.Unconfined) { JsonPrimitive("other") }
        val next = bindOutputRequests(a, "design", Dispatchers.Unconfined) { JsonPrimitive("next") }
        try {
            assertEquals(next, a.outputRequests("design"))
            assertEquals(JsonPrimitive("next"), submitOutputRequest(OutputRequest(next.id)).await())
            assertEquals(JsonPrimitive("other"), b.outputRequests("design").request())
            assertFailsWith<IllegalStateException> { first.request() }
            assertFailsWith<IllegalStateException> { submitOutputRequest(OutputRequest(first.id)).await() }
            b.inside.boxes.remove(b.id)
            assertFailsWith<IllegalStateException> { other.request() }
        } finally { next.close(); other.close() }
    }

    @Test fun requestsAreSerializedAcrossSuspensionAndHaveTheirOwnerContext() = runBlocking<Unit> {
        val owner = box(); val entered = mutableListOf<Int>(); val gate = CompletableDeferred<Unit>()
        val binding = bindOutputRequests(owner, "design", Dispatchers.Unconfined) {
            assertSame(owner, Box.context.get()); assertSame(owner.inside, thisSessionInfo.get())
            val n = it.jsonPrimitive.int; entered += n
            if (n == 1) gate.await()
            it
        }
        try {
            val first = binding.submit(JsonPrimitive(1)); val second = binding.submit(JsonPrimitive(2))
            assertEquals(listOf(1), entered)
            gate.complete(Unit)
            assertEquals(JsonPrimitive(1), first.await()); assertEquals(JsonPrimitive(2), second.await())
            assertEquals(listOf(1, 2), entered)
        } finally { binding.close() }
    }

    @Test fun closeSettlesBothRunningAndQueuedRequestsAndErrorsDoNotPoisonQueue() = runBlocking<Unit> {
        val owner = box(); val gate = CompletableDeferred<Unit>()
        val binding = bindOutputRequests(owner, "design", Dispatchers.Unconfined) {
            when (it.jsonPrimitive.content) {
                "fail" -> kotlin.error("Choose a valid window")
                "wait" -> gate.await()
            }
            it
        }
        assertEquals("Choose a valid window", assertFailsWith<IllegalStateException> { binding.request(JsonPrimitive("fail")) }.message)
        assertEquals(JsonPrimitive("ok"), binding.request(JsonPrimitive("ok")))
        val running = binding.submit(JsonPrimitive("wait")); val queued = binding.submit(JsonPrimitive("later"))
        binding.close()
        assertFailsWith<CancellationException> { running.await() }
        assertFailsWith<IllegalStateException> { queued.await() }
        assertFailsWith<IllegalStateException> { owner.outputRequests("design") }
    }

    @Test fun readonlyAndOversizedRequestsNeverRunTheCallback() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { bindOutputRequests(box(true), "design", Dispatchers.Unconfined) { it } }
        var calls = 0
        val binding = bindOutputRequests(box(), "design", Dispatchers.Unconfined) { calls++; it }
        try {
            assertFailsWith<IllegalArgumentException> { binding.request(JsonPrimitive("x".repeat(6_000_001))) }
            assertEquals(0, calls)
        } finally { binding.close() }
    }
}

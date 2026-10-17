package afield.ai.tools

import afield.document.graph.Box
import afield.runtime.lifecycle.Runloop
import afield.ai.tools.BoxTool
import afield.ai.tools.checkToolRunloopDispatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class BoxToolDispatchTest {
    private fun tool() = BoxTool("dispatch.check", "Dispatch regression", "dispatch-test", Box("dispatch-test"), emptyList(), body = {
        fail("A blocked owner must be rejected before the body is dispatched")
    })

    private fun owningThread(body: () -> Unit) {
        val previous = Runloop.mainThread
        Runloop.mainThread = Thread.currentThread()
        try { body() } finally { Runloop.mainThread = previous }
    }

    @Test fun blockingOnOwnerFailsBeforeDispatch() = owningThread {
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { withTimeout(2000) { tool().call("{}") } }
        }
        assertTrue(error.message.orEmpty().contains("would deadlock"))
    }

    @Test fun ioBodyStillHasABlockedOwner() = owningThread {
        val error = assertFailsWith<IllegalStateException> {
            runBlocking(Dispatchers.IO) { withTimeout(2000) { tool().call("{}") } }
        }
        assertTrue(error.message.orEmpty().contains("even with Dispatchers.IO"))
    }

    @Test fun ordinaryWorkerWithoutBlockedOwnerCanDispatch() {
        val previous = Runloop.mainThread
        Runloop.mainThread = null
        try { runBlocking(Dispatchers.IO) { checkToolRunloopDispatch() } }
        finally { Runloop.mainThread = previous }
    }
}

package afield.four

import afield.AgentNativeWindowScreenshot
import afield.AgentScreenshotResponse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowCaptureQueueTest {
    @Test
    fun stoppedWindowReportsItsOwnFrameAndRemovesRequest() = runBlocking {
        val queue = WindowCaptureQueue()
        queue.takeForFrame(0)
        val request = queue.enqueue(0, "unused.png", System.nanoTime())
        // A different window's progress must not hide this one's stall.
        queue.takeForFrame(1)
        val error = assertFailsWith<NativeWindowCaptureStalledException> {
            queue.await(request, timeoutMs = 1_000, noFrameTimeoutMs = 20)
        }
        assertTrue(error.message!!.contains("windowIndex=0 frame=1 unchanged"))
        assertEquals(0, queue.pendingCount(0))
        assertTrue(queue.takeForFrame(0).isEmpty())
    }

    @Test
    fun capturedFrameCanFinishEncodingAfterWatchdogInterval() = runBlocking {
        val queue = WindowCaptureQueue()
        val request = queue.enqueue(0, "unused.png", System.nanoTime())
        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            queue.await(request, timeoutMs = 1_000, noFrameTimeoutMs = 20)
        }
        assertEquals(listOf(request), queue.takeForFrame(0))
        delay(50)
        request.completion.complete("real-result-path.png")
        assertEquals("real-result-path.png", capture.await())
        assertEquals(0, queue.pendingCount(0))
    }

    @Test
    fun repeatedBudgetExpiryDoesNotAccumulateRequests() = runBlocking {
        val queue = WindowCaptureQueue()
        repeat(8) {
            val request = queue.enqueue(0, "unused.png", System.nanoTime())
            assertFailsWith<TimeoutCancellationException> {
                queue.await(request, timeoutMs = 2, noFrameTimeoutMs = 1_000)
            }
            assertEquals(0, queue.pendingCount(0))
            assertFalse(request.completion.isActive)
        }
        assertTrue(queue.takeForFrame(0).isEmpty())
    }

    @Test
    fun callerCancellationRemovesOnlyItsOwnRequest() = runBlocking {
        val queue = WindowCaptureQueue()
        val cancelled = queue.enqueue(0, "cancelled.png", System.nanoTime())
        val retained = queue.enqueue(0, "retained.png", System.nanoTime())
        val capture = async(start = CoroutineStart.UNDISPATCHED) {
            queue.await(cancelled, timeoutMs = 1_000)
        }
        capture.cancelAndJoin()
        assertEquals(1, queue.pendingCount(0))
        assertEquals(listOf(retained), queue.takeForFrame(0))
        assertFalse(cancelled.completion.isActive)
        retained.completion.cancel()
    }

    @Test
    fun badBudgetAlsoReleasesRequest() = runBlocking {
        val queue = WindowCaptureQueue()
        val request = queue.enqueue(0, "unused.png", System.nanoTime())
        assertFailsWith<IllegalArgumentException> { queue.await(request, timeoutMs = 0) }
        assertEquals(0, queue.pendingCount(0))
    }

    @Test
    fun captureProtocolKeepsOldDefaultsAndAcceptsAuthoredBudget() {
        val old = Json.decodeFromString<AgentNativeWindowScreenshot>("""{"path":"unused.png"}""")
        assertEquals(15_000L, old.captureTimeoutMs)
        assertNull(old.uid)
        val authored = Json.decodeFromString<AgentNativeWindowScreenshot>(
            """{"path":"unused.png","captureTimeoutMs":240000,"uid":"renderer"}"""
        )
        assertEquals(240_000L, authored.captureTimeoutMs)
        assertEquals("renderer", authored.uid)
        val response = Json.decodeFromString<AgentScreenshotResponse>(
            """{"ok":false,"kind":"native-window","path":"unused.png","documentName":"scratch","scratch":true,"sessionKey":"scratch"}"""
        )
        assertFalse(response.terminal)
    }
}

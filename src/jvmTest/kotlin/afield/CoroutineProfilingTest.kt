package afield

import kotlinx.coroutines.*
import kotlin.test.*

class CoroutineProfilingTest {
    private fun owner(id: String = "work", document: String = "test-profile"): ProfileOrigin {
        val box = Box(id)
        box.inside = SessionInformation(document, root = box, ephemeral = true)
        box.properties["name"] = id
        box.properties["code"] = "loop { println(1) }"
        return ProfileOrigin(box, "animation")
    }

    @Test fun boundedHistoryKeepsSequenceAndReportsLoss() {
        val origin = owner()
        val capture = CoroutineCapture(origin.document, 100, 10000, capacity = 3)
        val lane = capture.lane(Job(), origin, 100)
        repeat(5) { capture.record(lane, it.toLong(), 100L+it*100, 120L+it*100, 10, 40) }
        val snapshot = capture.snapshot(-1, 1000)
        assertEquals(listOf(2L,3L,4L), snapshot.spans.map { it.sequence })
        assertEquals(2, snapshot.droppedSpans)
        assertEquals(5, snapshot.nextSequence)
        assertEquals(listOf(4L), capture.snapshot(3,1000).spans.map { it.sequence })
        assertTrue(capture.snapshot(4,1000).spans.isEmpty())
    }

    @Test fun coroutineIdentityAndLaunchSourceSurviveEdits() {
        val origin = owner()
        val first = Job(); val second = Job()
        val capture = CoroutineCapture(origin.document, 0, 10000)
        val a = capture.lane(first, origin, 1)
        origin.box.properties["code"] = "loop { println(2) }"
        val b = capture.lane(second, ProfileOrigin(origin.box), 2)
        assertEquals(a, capture.lane(first, origin, 3))
        assertNotEquals(a,b)
        first.complete()
        val snapshot = capture.snapshot(-1,100)
        assertEquals("completed", snapshot.lanes[a].state)
        assertNotEquals(snapshot.lanes[a].revision,snapshot.lanes[b].revision)
        capture.stop(100)
        second.cancel()
        assertEquals("active",capture.snapshot(-1,200).lanes[b].state, "Latched state must not follow live jobs")
    }

    @Volatile private var escaped: Any? = null

    @Test fun dispatcherRecordsResumesAndAllocationWithoutSuspensionGaps() {
        val origin = owner()
        val framer = Framer()
        CoroutineProfiles.request(origin.document, CoroutineProfileRequest("start",3.0))
        try {
            val scope = CoroutineScope(framer + origin)
            val job = scope.launch {
                repeat(3) { escaped = ByteArray(65536); yield() }
            }
            repeat(4) { framer.frame(); Thread.sleep(15) }
            assertTrue(job.isCompleted)
            val s = CoroutineProfiles.request(origin.document, CoroutineProfileRequest("stop"))
            assertEquals(1,s.lanes.size)
            assertEquals(4,s.spans.size)
            assertTrue(s.spans.take(3).all { it.allocatedBytes >= 65536 })
            assertTrue(s.spans.zipWithNext().all { (a,b) -> b.startMs-(a.startMs+a.wallMs) >= 10.0 }, "Sleep between pumps must be a gap, not charged execution")
            assertEquals("completed",s.lanes.single().state)
        } finally { CoroutineProfiles.request(origin.document, CoroutineProfileRequest("stop")) }
    }

    @Test fun failuresAreMeasuredAndOtherDocumentsStayPrivate() {
        val origin = owner()
        CoroutineProfiles.request(origin.document,CoroutineProfileRequest("start",3.0))
        try {
            assertFailsWith<IllegalStateException> {
                CoroutineProfiles.resume(origin + Job(), Runnable { error("failure") })
            }
            CoroutineProfiles.resume(owner("foreign","other") + Job(), Runnable {})
            val other = CoroutineProfiles.request("other",CoroutineProfileRequest("stop"))
            assertEquals("",other.captureId)
            val mine = CoroutineProfiles.request(origin.document,CoroutineProfileRequest())
            assertTrue(mine.recording)
            assertEquals(1,mine.spans.size)
        } finally { CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop")) }
    }

    @Test fun laneLimitIsVisibleAndUnattachedBoxesCanCarryContext() {
        Box.boxContextElement(Box("unattached"))
        val origin = owner()
        val c = CoroutineCapture(origin.document,0,1000,maximumLanes = 1)
        assertEquals(0,c.lane(Job(),origin,1))
        assertEquals(-1,c.lane(Job(),origin,2))
        assertEquals(1,c.snapshot(-1,3).droppedSpans)
    }

    @Test fun latchIncludesAResumeAlreadyInProgress() {
        val origin = owner()
        val job = Job()
        val c = CoroutineCapture(origin.document,0,1000)
        val lane = c.beginResume(job,origin,10)
        c.stop(1000)
        assertEquals(-1,c.beginResume(Job(),origin,1001))
        assertTrue(c.snapshot(-1,1001).recording, "Keep polling until the in-flight resume finishes")
        job.complete()
        c.endResume(lane,0,10,2000,500,65536)
        val s = c.snapshot(-1,3000)
        assertFalse(s.recording)
        assertEquals(0.002,s.elapsedMs)
        assertEquals(65536,s.spans.single().allocatedBytes)
        assertEquals("completed",s.lanes.single().state)
    }

    @Test fun jfrSamplesBackgroundWorkAndStopsAtDeadlineEvenDuringALongResume() {
        val origin = owner("jfr", "test-profile-jfr")
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val ready = java.util.concurrent.CountDownLatch(1)
        val worker = Thread({
            ready.countDown()
            var sum = 0.0
            while (running.get()) {
                repeat(40000) { sum += kotlin.math.sin(it.toDouble()) }
                escaped = doubleArrayOf(sum)
            }
        }, "profile-test-worker").apply { isDaemon = true; start() }
        ready.await()
        try {
            CoroutineProfiles.request(origin.document, CoroutineProfileRequest("start", 1.0, stacks = true))
            CoroutineProfiles.resume(origin + Job(), Runnable {
                val until = System.nanoTime() + 1_300_000_000L
                while (System.nanoTime() < until) { escaped = ByteArray(4096); Thread.sleep(1) }
            })
            var snapshot = CoroutineProfiles.request(origin.document, CoroutineProfileRequest("stop"))
            val until = System.nanoTime() + 15_000_000_000L
            while (snapshot.stackState == "processing" && System.nanoTime() < until) {
                Thread.sleep(30)
                snapshot = CoroutineProfiles.request(origin.document, CoroutineProfileRequest())
            }
            assertEquals("ready",snapshot.stackState,snapshot.stackError)
            assertTrue(snapshot.elapsedMs >= 1300)
            assertTrue(snapshot.stackSamples.any { it.threadName == "profile-test-worker" && it.kind == "cpu" })
            assertTrue(snapshot.stackSamples.all { it.timeMs in 0.0..1000.0 }, "JFR stops at its deadline while a resume may finish later")
            assertTrue(snapshot.spans.single().threadId > 0)
            assertTrue(snapshot.stackFrames.any { it.className.contains("CoroutineProfilingTest") })
            val tail = CoroutineProfiles.request(origin.document, CoroutineProfileRequest(captureId=snapshot.captureId,stackAfter=snapshot.stackSampleCount-1))
            assertTrue(tail.stackSamples.isEmpty())
            assertTrue(tail.stackFrames.isEmpty())
        } finally {
            running.set(false); worker.join(2000)
            CoroutineProfiles.request(origin.document,CoroutineProfileRequest("stop"))
        }
    }
}

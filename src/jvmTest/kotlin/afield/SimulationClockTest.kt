package afield

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class SimulationClockTest {
    @Test fun preparationPrecedesChildrenAndIntegrationAcrossCatchUp() {
        val events=mutableListOf<String>()
        SimulationClock(EmptyCoroutineContext, stepSeconds=.01).use { clock ->
            var sensed=-1
            clock.launch { repeat(3) { events += "child:$sensed@${clock.tick}"; clock.sleep(.01) } }
            val report=clock.advance(.03, beforeStep={ index,total ->
                assertEquals(3,total)
                sensed=index
                events += "prepare:$index@${clock.tick}"
                assertFailsWith<IllegalStateException> { clock.advance(.01) }
            }, onStep={ events += "integrate:$sensed@${clock.tick}" })
            assertEquals(3,report.steps)
            assertEquals(listOf("prepare:0@0","child:0@1","integrate:0@1",
                "prepare:1@1","child:1@2","integrate:1@2",
                "prepare:2@2","child:2@3","integrate:2@3"),events)
        }
    }

    @Test fun delayUsesOnlySimulationTimeAndTimersAreOrdered() {
        val clock = SimulationClock(EmptyCoroutineContext, stepSeconds = .01)
        val events = mutableListOf<String>()
        try {
            repeat(3) { i -> clock.launch { delay(20); events += "$i@${clock.tick}" } }
            clock.runCurrent()
            assertEquals(3, clock.pendingTimers)
            Thread.sleep(25)
            clock.runCurrent()
            assertTrue(events.isEmpty())
            clock.advance(.01); assertTrue(events.isEmpty())
            clock.advance(.01)
            assertEquals(listOf("0@2", "1@2", "2@2"), events)
        } finally { clock.close() }
    }

    @Test fun cancellationRemovesTimersAndRunsFinallyWithoutAnotherTick() {
        val parent = Job()
        val clock = SimulationClock(parent)
        var ended = false
        val job = clock.launch { try { delay(1_000_000) } finally { ended = true } }
        clock.runCurrent()
        parent.cancel()
        assertEquals(0, clock.pendingTimers)
        clock.close()
        assertTrue(ended)
        assertTrue(job.isCompleted)
        assertEquals(0,clock.pendingResumes)
        clock.close()
    }

    @Test fun standardFramesOwnerCancellationClosesEveryChild() {
        val framer = Framer()
        val parent = Job()
        var clock: SimulationClock? = null
        var cleanup = 0
        val program = frames {
            withSimulationClock {
                clock = this
                launch { try { delay(999_999) } finally { cleanup++ } }
                runCurrent()
                while (frame()) advance(stepSeconds)
            }
        }
        val job = CoroutineScope(framer + parent).launch { program.body() }
        framer.frame()
        assertEquals(1, clock!!.pendingTimers)
        job.cancel()
        repeat(3) { framer.frame() }
        assertTrue(job.isCompleted)
        assertEquals(1,cleanup)
        assertEquals(0,clock.pendingTimers)
        assertEquals(0,clock.pendingResumes)
    }

    @Test fun timeoutAndCooperativeBudgetsUseTheSameClock() {
        val clock = SimulationClock(EmptyCoroutineContext, stepSeconds=.01, maxStepsPerAdvance=2, maxResumesPerStep=8)
        var timedOut = false
        try {
            clock.launch { timedOut = withTimeoutOrNull(20) { delay(100); false } == null }
            clock.runCurrent()
            val advance=clock.advance(1.0)
            assertEquals(2,advance.steps)
            assertEquals(.98,advance.droppedSeconds,1e-9)
            assertTrue(timedOut)
            val busy=clock.launch { while(isActive) yield() }
            assertEquals(8,clock.runCurrent())
            assertTrue(clock.pendingResumes>0)
            busy.cancel()
        } finally { clock.close() }
    }

    @Test fun childFailureCancelsOwnerAndForeignThreadCannotStep() {
        val parent=Job()
        val errors=mutableListOf<Throwable>()
        val clock=SimulationClock(parent+CoroutineExceptionHandler { _,t -> errors += t })
        clock.launch { error("broken behaviour") }
        clock.runCurrent()
        assertTrue(parent.isCancelled)
        assertEquals("broken behaviour",errors.single().message)
        var failure: Throwable?=null
        Thread { try { clock.advance(.1) } catch(t:Throwable){ failure=t } }.apply { start();join() }
        assertIs<IllegalStateException>(failure)
        clock.close()
    }

    /**
     * Box stop cancels from a foreign thread. The owner can see its own job cancelled and reach
     * close() while that thread is still propagating to the children (2026-09-28 smoke flake,
     * `examples.demo-transcript.exec` / simulation_clock under load 100-160). The slow cancelling
     * handler stands in for the cancelling thread being preempted mid-propagation.
     */
    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun closeWaitsForCancellationStillPropagatingOnAnotherThread() {
        val parent = Job()
        val clock = SimulationClock(parent)
        var cleaned = false
        val propagating = CountDownLatch(1)
        clock.launch {
            coroutineContext.job.invokeOnCompletion(onCancelling = true) { propagating.countDown(); Thread.sleep(300) }
            try { delay(1_000_000) } finally { cleaned = true }
        }
        clock.runCurrent()
        val canceller = thread { parent.cancel() }
        propagating.await()
        clock.close()
        canceller.join()
        assertTrue(cleaned)
        assertEquals(0, clock.pendingResumes)
    }

    @Test fun finallyThatNeverResumesIsStillReported() {
        val clock = SimulationClock(EmptyCoroutineContext)
        clock.closeGraceMillis = 50
        clock.launch {
            try { delay(1_000_000) } finally { withContext(NonCancellable) { suspendCancellableCoroutine<Unit> { } } }
        }
        clock.runCurrent()
        val failure = assertFailsWith<IllegalStateException> { clock.close() }
        assertTrue("finally blocks must not suspend" in failure.message!!)
    }

    /** The scene's shape: two children, a frames owner on a Framer, stop from another thread, CPU contention. */
    @Test
    fun foreignThreadStopOfFramesOwnerUnderContention() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val hogs = (1..Runtime.getRuntime().availableProcessors() * 2).map {
            thread(isDaemon = true) { var x = 0.0; while (!Thread.currentThread().isInterrupted) x += Math.sin(x) }
        }
        try {
            repeat(ITERATIONS) { iteration ->
                val framer = Framer()
                var started = 0
                var cleanup = 0
                val program = frames {
                    try {
                        withSimulationClock {
                            launch(CoroutineName("Orbit")) { started++; try { while (isActive) sleep(stepSeconds) } finally { cleanup++ } }
                            launch(CoroutineName("Pulse")) { started++; try { while (isActive) { delay(120); delay(350) } } finally { cleanup++ } }
                            runCurrent()
                            while (frame()) advance(stepSeconds)
                        }
                    } catch (t: IllegalStateException) { if (t !is CancellationException) failures += t }
                }
                val job = CoroutineScope(framer + Job()).launch { program.body() }
                val canceller = thread { Thread.sleep(0, (iteration * 7919) % 50_000); job.cancel() }
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!job.isCompleted && System.nanoTime() < deadline) framer.frame()
                canceller.join()
                assertTrue(job.isCompleted, "iteration $iteration did not finish")
                if (failures.isEmpty()) assertEquals(started, cleanup, "iteration $iteration")
            }
        } finally { hogs.forEach { it.interrupt() } }
        assertTrue(failures.isEmpty(), "${failures.size}/$ITERATIONS closes failed: ${failures.firstOrNull()}")
    }

    private companion object { const val ITERATIONS = 2_000 }
}

package afield

import kotlinx.coroutines.*
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
        assertEquals(0,clock!!.pendingTimers)
        assertEquals(0,clock!!.pendingResumes)
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
}

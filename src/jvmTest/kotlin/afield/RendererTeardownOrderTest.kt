package afield

import afield.stdlib.actions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ordering `disposeBoxRenderer` depends on, exercised through [beginRendererTeardown] with a
 * fake device instead of a GPU.
 *
 * The bug this pins (reproduced 2/2 against a real Field on 2026-09-26, see
 * `dev_docs/renderer_teardown_order.md`): the renderer box was stopped, or its document's last
 * session detached, while a *child* box's launched `loop {}` wrote a VulkanBuffer every frame.
 * The device-owned buffer was freed under the loop and its next `mapAndWrite` was a SIGSEGV. The
 * fake device here records the same thing as a count — a write after dispose — instead of a crash.
 *
 * The render thread is modelled the way it really is: ONE thread runs both the loop and the
 * teardown, so a teardown that blocked waiting for the loop would deadlock, and one that did not
 * wait would dispose before the cancelled loop had unwound.
 */
class RendererTeardownOrderTest {

    private val renderThread = Executors.newSingleThreadExecutor { Thread(it, "fake-render-thread").apply { isDaemon = true } }
    private val dispatcher = renderThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @AfterTest
    fun shutdown() {
        renderThread.shutdownNow()
    }

    private class FakeDevice {
        @Volatile var alive = true
        @Volatile var writes = 0
        @Volatile var writesAfterDispose = 0

        /** A write that takes [millis]: the device must stay alive from its first byte to its last. */
        fun write(millis: Long = 0) {
            if (!alive) writesAfterDispose++
            if (millis > 0) Thread.sleep(millis)
            if (alive) writes++ else writesAfterDispose++
        }
    }

    /** A launched per-frame writer, like `loop { buf.mapAndWrite { ... }; frame() }`, owned by [box]. */
    private fun launchWriter(box: Box, device: FakeDevice, on: CoroutineScope = scope, writeMillis: Long = 0): Job =
        on.launch {
            while (isActive) {
                device.write(writeMillis)
                yield()
            }
        }.also { box.jobs.add(it) }

    /** The graph edge without `Box.connect`, which needs a live SessionInformation to resync. */
    private fun link(parent: Box, child: Box) {
        parent.children += child
        child.parents += parent
    }

    private fun waitFor(what: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    @Test
    fun dependentsStopAndFinishBeforeTheDeviceIsDisposed() {
        val owner = Box("renderer-owner")
        val child = Box("gpu-writer-child")
        val grandchild = Box("gpu-writer-grandchild")
        val unrelated = Box("unrelated-sibling")
        link(owner, child)
        link(child, grandchild)

        val device = FakeDevice()
        // The child writes on the render thread, like a box `loop {}`. The grandchild writes from a
        // worker thread, and each write takes 20 ms: cancelling it does not interrupt a write in
        // progress, so only a teardown that WAITS for the job keeps the device alive through it.
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val childJob = launchWriter(child, device)
        val grandchildJob = launchWriter(grandchild, device, on = CoroutineScope(worker), writeMillis = 20)
        val unrelatedJob = scope.launch { while (isActive) delay(5) }.also { unrelated.jobs.add(it) }

        // Premise first: the writers really were writing before teardown began.
        waitFor("the writers to be writing") { device.writes >= 100 }

        val disposed = CountDownLatch(1)
        var dependentsDoneAtDispose = false
        beginRendererTeardown(
            owner,
            onRenderThread = { block -> scope.launch { block() } },
        ) {
            dependentsDoneAtDispose = childJob.isCompleted && grandchildJob.isCompleted
            device.alive = false
            disposed.countDown()
        }
        assertTrue(disposed.await(5, TimeUnit.SECONDS), "dispose must run")
        Thread.sleep(50) // give any writer that was NOT stopped a chance to write after dispose

        assertTrue(dependentsDoneAtDispose, "both dependents' jobs must have completed before dispose ran")
        assertEquals(0, device.writesAfterDispose, "no dependent may touch the device after it is disposed")
        assertTrue(childJob.isCancelled && grandchildJob.isCancelled, "dependents are stopped, not merely waited for")
        assertTrue(unrelatedJob.isActive, "a box that is not a descendant of the renderer box is left running")
        assertEquals(listOf(child, grandchild), rendererDependents(owner), "dependents are the transitive children")

        runBlocking { unrelatedJob.cancelAndJoin() }
        worker.close()
    }

    /** The wait is bounded: a dependent that will not unwind delays teardown, but does not wedge it. */
    @Test
    fun aDependentThatIgnoresCancellationDelaysDisposeOnlyUpToTheBound() {
        val owner = Box("renderer-owner-bounded")
        val stubborn = Box("stubborn-child")
        link(owner, stubborn)
        // Runs on another thread so it cannot hold the render thread hostage; it is the join that is
        // being bounded, not the thread.
        val other = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val started = CountDownLatch(1)
        val stubbornJob = CoroutineScope(other).launch {
            withContext(NonCancellable) {
                started.countDown()
                delay(10_000)
            }
        }.also { stubborn.jobs.add(it) }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val begun = System.nanoTime()
        var elapsedMs = -1L
        val disposed = CountDownLatch(1)
        beginRendererTeardown(owner, joinTimeoutMs = 300, onRenderThread = { block -> scope.launch { block() } }) {
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
            disposed.countDown()
        }
        assertTrue(disposed.await(5, TimeUnit.SECONDS), "dispose must still run when a dependent will not stop")
        assertTrue(elapsedMs >= 250, "dispose waited for the bound before giving up, took ${elapsedMs} ms")
        assertTrue(elapsedMs < 3_000, "dispose did not wait for the stubborn job itself, took ${elapsedMs} ms")
        assertFalse(stubbornJob.isCompleted, "premise: the stubborn job really was still running")
        other.close()
    }

    /** An owner with no running dependents disposes promptly (no bound is spent). */
    @Test
    fun noDependentsMeansNoWait() {
        val owner = Box("renderer-owner-alone")
        link(owner, Box("idle-child"))
        val begun = System.nanoTime()
        var elapsedMs = -1L
        val disposed = CountDownLatch(1)
        beginRendererTeardown(owner, joinTimeoutMs = 2_000, onRenderThread = { block -> scope.launch { block() } }) {
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
            disposed.countDown()
        }
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
        assertTrue(elapsedMs in 0 until 1_000, "an idle child must not cost the join bound, took ${elapsedMs} ms")
    }

    /**
     * N0926-3: teardown reports the boxes it stopped, and the renderer box then offers to launch
     * them again, itself first when it has lost its renderer, so restarting only the renderer no
     * longer strands its children. The launch is faked; the order and the bookkeeping are the point.
     */
    @Test
    fun teardownRecordsWhatItStoppedAndOffersToRelaunchIt() {
        val owner = Box("renderer-owner-relaunch")
        val child = Box("relaunch-child")
        val grandchild = Box("relaunch-grandchild")
        val idle = Box("relaunch-idle-child")
        link(owner, child)
        link(child, grandchild)
        link(owner, idle)
        val device = FakeDevice()
        launchWriter(child, device)
        launchWriter(grandchild, device)
        waitFor("the writers to be writing") { device.writes >= 10 }

        val disposed = CountDownLatch(1)
        val stopped = beginRendererTeardown(owner, onRenderThread = { block -> scope.launch { block() } }) {
            disposed.countDown()
        }
        assertTrue(disposed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(child, grandchild), stopped, "teardown returns the running dependents it stopped, parents first")

        offerRelaunchOfStoppedDependents(owner, stopped)
        assertEquals(stopped, stoppedByRendererTeardown(owner))
        assertTrue(RELAUNCH_STOPPED_DEPENDENTS_ACTION in owner.actions, "the renderer box offers the relaunch as an action")

        val launched = mutableListOf<List<Box>>()
        val relaunched = relaunchRendererDependents(owner) { launched += it }
        assertEquals(listOf(owner, child, grandchild), relaunched, "no renderer on the owner: it launches first, then its dependents")
        assertEquals(listOf(relaunched), launched, "one ordered batch")
        assertTrue(stoppedByRendererTeardown(owner).isEmpty(), "the record is consumed")
        assertFalse(RELAUNCH_STOPPED_DEPENDENTS_ACTION in owner.actions, "and the action withdrawn")

        offerRelaunchOfStoppedDependents(owner, emptyList())
        assertFalse(RELAUNCH_STOPPED_DEPENDENTS_ACTION in owner.actions, "nothing stopped, nothing offered")
    }
}

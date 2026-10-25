package afield.runtime.lifecycle

import afield.app.protocol.computeIfAbsent
import afield.data.table.cell
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.introduction
import afield.document.graph.makeKI
import afield.document.graph.makeKIHere
import afield.document.workspace.copyBox
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.ui.notation.with
import afield.ui.output.history
import afield.ui.output.slider
import afield.ui.visualization.h

import afield.app.server.SessionInformation
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.captureLaunchEpoch
import afield.document.graph.hasSomethingToStop
import afield.document.graph.registerStopHook
import afield.document.graph.retireBoxes
import afield.runtime.kotlin.KotlinScriptEngine
import afield.ui.output.sliders
import kotlinx.coroutines.Job
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The teardown that `-delete-box`, history restore, external `metadata.edn` removal and a document's
 * last client leaving now share ([retireBoxes]), and the one way to swap a box's engine
 * ([Box.replaceKi]).
 *
 * Before these, deleting a box released its engine without stopping it, the other tombstoning paths
 * did neither, the last client leaving disposed without stopping, and `reset` / execute-all / copyBox
 * overwrote `box.ki` and leaked the K2 engine they dropped. The live-Field half of this — a real
 * launched `loop {}` deleted and reset through the backdoor — is recorded with the commit; this is the
 * part that needs no server.
 */
class BoxRetireAndReplaceKiTest {

    /** A stand-in engine that records [dispose]; every other member is unused by these paths. */
    private class FakeEngine {
        var disposeCount = 0
        val engine: KotlinScriptEngine = Proxy.newProxyInstance(
            KotlinScriptEngine::class.java.classLoader,
            arrayOf(KotlinScriptEngine::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "dispose" -> { disposeCount++; null }
                "isDisposed", "getIsDisposed" -> disposeCount > 0
                "toString" -> "FakeEngine@${System.identityHashCode(proxy)}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> throw UnsupportedOperationException("FakeEngine.${method.name}")
            }
        } as KotlinScriptEngine
    }

    private fun Box.seedExecutionCaches() {
        properties["_preamble"] = "val a = 1"
        properties["_introduction"] = "val b = 2"
        properties["_attic"] = "val c = 3"
    }

    private fun Box.assertExecutionCachesForgotten() {
        assertNull(properties["_preamble"], "_preamble must be forgotten with the engine that ran it")
        assertNull(properties["_introduction"], "_introduction too, or the next engine skips the introduction")
        assertNull(properties["_attic"], "_attic too")
    }

    @Test
    fun replaceKiDisposesTheEngineItReplacesAndForgetsWhatItRan() {
        val box = Box("replace-ki")
        val old = FakeEngine()
        val new = FakeEngine()
        box.ki = old.engine
        box.seedExecutionCaches()

        box.replaceKi(new.engine)

        assertSame(new.engine, box.ki)
        assertEquals(1, old.disposeCount, "the replaced engine is disposed exactly once")
        assertTrue(old.engine.isDisposed)
        assertEquals(0, new.disposeCount, "the installed engine is not")
        box.assertExecutionCachesForgotten()
    }

    /** `makeKIHere` hands back the current engine for a tombstoned box; disposing it would break the box. */
    @Test
    fun replaceKiWithTheCurrentEngineIsANoOp() {
        val box = Box("replace-ki-same")
        val only = FakeEngine()
        box.ki = only.engine
        box.seedExecutionCaches()

        box.replaceKi(only.engine)

        assertSame(only.engine, box.ki)
        assertEquals(0, only.disposeCount)
        assertEquals("val b = 2", box.properties["_introduction"], "a no-op keeps the caches that describe it")
    }

    @Test
    fun disposeKiForgetsIntroductionAndAtticToo() {
        val box = Box("dispose-ki-caches")
        val engine = FakeEngine()
        box.ki = engine.engine
        box.seedExecutionCaches()

        box.disposeKi()

        assertEquals(1, engine.disposeCount)
        assertNull(box.liveKi, "disposeKi leaves no engine until one is asked for")
        box.assertExecutionCachesForgotten()
    }

    @Test
    fun retireBoxesStopsWhatARunningBoxRunsThenReleasesItsEngine() {
        val box = Box("retire-running")
        val engine = FakeEngine()
        box.ki = engine.engine
        val job = Job()
        box.jobs.add(job)
        var hookRan = 0
        box.registerStopHook(Runnable { hookRan++ })
        // What a stop leaves in place and only retirement releases: a slider callback and a keyed hook.
        val sliders = OwnedCallbackRegistry<() -> Unit>("retire-running")
        val hooks = afield.graphics.vulkan.util.MapOfSequences<String>(LifecycleKinds.RUNLOOP_PRE_RUN)
        val hook = sequence<Any?> { while (true) yield(Unit) }
        Box.withBoxContextNow(box) {
            sliders.register("s", {})
            hooks["h"] = hook
        }
        assertTrue(box.hasSomethingToStop())

        retireBoxes(listOf(box), "test")

        assertNull(sliders["s"], "retiring releases the slider a stop would have left")
        assertFalse(hooks.isCurrent("h", hook), "and the keyed Runloop hook")
        assertTrue(job.isCancelled, "the running job is cancelled")
        assertTrue(box.jobs.isEmpty(), "and dropped from the box")
        assertEquals(1, hookRan, "stop hooks drain, so a process the box owned is released")
        assertFalse(box.hasSomethingToStop())
        assertEquals(1, engine.disposeCount, "the engine goes last")
        assertNull(box.liveKi)
    }

    /** A `box.ki` build held open on another thread, the way a cold `makeKI` holds the lazy for about a second. */
    private class HeldBuild(box: Box) {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val built = FakeEngine()
        val reasons = mutableListOf<String>()
        @Volatile
        var superseded: () -> Boolean = { error("no build started") }

        init {
            box.kiBuilder = { _, reason, isSuperseded ->
                reasons += reason
                superseded = isSuperseded
                started.countDown()
                check(finish.await(10, TimeUnit.SECONDS)) { "the test never let the build finish" }
                built.engine
            }
        }
    }

    /**
     * The 2026-10-04 defect: a document's last client left while `agent-create-box` was still building
     * the new box's engine, and the retire skipped the box because its lazy was not initialized yet.
     * The engine was stored once built, outlived the box, and its stdlib bootstrap still ran. The
     * retire runs on a pool thread with a timeout because it happens on a Ktor IO thread, which must
     * not wait out an engine build either.
     */
    @Test
    fun retiringABoxWhoseEngineIsStillBeingBuiltDisposesThatEngine() {
        val box = Box("retire-mid-build")
        val build = HeldBuild(box)
        val pool = Executors.newCachedThreadPool()
        try {
            val reader = pool.submit<KotlinScriptEngine> { box.ki }
            assertTrue(build.started.await(5, TimeUnit.SECONDS))
            assertFalse(build.superseded(), "nothing has retired the box yet")

            pool.submit { retireBoxes(listOf(box), "test") }.get(5, TimeUnit.SECONDS)
            assertTrue(build.superseded(), "makeKI's bootstrap now sees the retire and skips")
            assertEquals(0, build.built.disposeCount, "the build has not returned yet")

            build.finish.countDown()
            assertSame(build.built.engine, reader.get(5, TimeUnit.SECONDS), "the caller that asked still gets that engine")
            assertEquals(1, build.built.disposeCount, "disposed exactly once, by the thread that built it")
            assertNull(box.liveKi, "and never stored")

            val next = FakeEngine()
            box.kiBuilder = { _, reason, _ -> build.reasons += reason; next.engine }
            assertSame(next.engine, box.ki, "a later read builds a fresh engine")
            assertEquals(0, next.disposeCount)
            assertEquals(listOf("box.ki lazy initialization", "box.ki re-initialization after disposeKi"), build.reasons)
        } finally {
            build.finish.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * The other half of the same incident: `agent-create-box` builds the engine through [box], which
     * used to do it inside `boxes.computeIfAbsent`, so the box was not in its session until the build
     * returned and `SessionInformation.cleanup()` found nothing to retire. Measured on a fresh Field:
     * the cleanup ran mid-build with an empty box list, and the engine outlived its document.
     */
    @Test
    fun aSessionCleanedUpWhileBoxBuildsAnEngineRetiresThatBoxAndDisposesTheEngine() {
        val root = Box("cleanup-mid-build-root")
        val info = SessionInformation(filename = "<cleanup-mid-build>", documentName = "cleanup-mid-build", root = root, ephemeral = true)
        root.inside = info
        info.loading = false
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val built = FakeEngine()
        var superseded: () -> Boolean = { error("no build started") }
        val previous = Box.newBoxKiBuilder
        Box.newBoxKiBuilder = { _, _, isSuperseded ->
            superseded = isSuperseded
            started.countDown()
            check(finish.await(10, TimeUnit.SECONDS)) { "the test never let the build finish" }
            built.engine
        }
        val pool = Executors.newCachedThreadPool()
        try {
            val creating = pool.submit<Box> { box("mid-build", createIfMissing = true, initIfCreated = true, root = info) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNotNull(info.boxes["mid-build"], "the box is in its session while its engine is built")

            pool.submit { info.cleanup() }.get(5, TimeUnit.SECONDS)
            assertTrue(superseded(), "the cleanup retired the box, so its bootstrap will skip")

            finish.countDown()
            val made = creating.get(5, TimeUnit.SECONDS)
            assertEquals(1, built.disposeCount, "the engine the build returned is disposed")
            assertNull(made.liveKi)
        } finally {
            Box.newBoxKiBuilder = previous
            finish.countDown()
            pool.shutdownNow()
        }
    }

    /** The reset action's `replaceKi(makeKI(...))` racing a lazy build: the installed engine wins, the built one is released. */
    @Test
    fun replaceKiDuringALazyBuildDisposesTheEngineThatBuildReturns() {
        val box = Box("replace-mid-build")
        val build = HeldBuild(box)
        val replacement = FakeEngine()
        val pool = Executors.newCachedThreadPool()
        try {
            val reader = pool.submit<KotlinScriptEngine> { box.ki }
            assertTrue(build.started.await(5, TimeUnit.SECONDS))

            pool.submit { box.replaceKi(replacement.engine) }.get(5, TimeUnit.SECONDS)
            build.finish.countDown()

            assertSame(replacement.engine, reader.get(5, TimeUnit.SECONDS), "the reader gets the installed engine")
            assertSame(replacement.engine, box.ki)
            assertEquals(1, build.built.disposeCount, "the engine the lazy built is released, not leaked")
            assertEquals(0, replacement.disposeCount)
        } finally {
            build.finish.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun retireBoxesReleasesAnIdleBoxWithoutStoppingIt() {
        val box = Box("retire-idle")
        val engine = FakeEngine()
        box.ki = engine.engine
        val epoch = box.captureLaunchEpoch()
        val sliders = OwnedCallbackRegistry<() -> Unit>("retire-idle")
        Box.withBoxContextNow(box) { sliders.register("s", {}) }
        assertFalse(box.hasSomethingToStop(), "a slider alone is not something to stop")

        retireBoxes(listOf(box), "test")

        assertEquals(epoch, box.captureLaunchEpoch(), "nothing to stop, so no stop ran (an idle Python cell must not interrupt its shared interpreter)")
        assertNull(sliders["s"], "but its registrations are released")
        assertEquals(1, engine.disposeCount)
    }
}

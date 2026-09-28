package afield

import kotlinx.coroutines.Job
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(box.hasSomethingToStop())

        retireBoxes(listOf(box), "test")

        assertTrue(job.isCancelled, "the running job is cancelled")
        assertTrue(box.jobs.isEmpty(), "and dropped from the box")
        assertEquals(1, hookRan, "stop hooks drain, so a process the box owned is released")
        assertFalse(box.hasSomethingToStop())
        assertEquals(1, engine.disposeCount, "the engine goes last")
        assertNull(box.liveKi)
    }

    @Test
    fun retireBoxesReleasesAnIdleBoxWithoutStoppingIt() {
        val box = Box("retire-idle")
        val engine = FakeEngine()
        box.ki = engine.engine
        val epoch = box.captureLaunchEpoch()

        retireBoxes(listOf(box), "test")

        assertEquals(epoch, box.captureLaunchEpoch(), "nothing to stop, so no stop ran (an idle Python cell must not interrupt its shared interpreter)")
        assertEquals(1, engine.disposeCount)
    }
}

package afield

import kotlinx.coroutines.Job
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The teardown that `-delete-box`, history restore, external `metadata.edn` removal and a document's
 * last client leaving now share ([retireBoxes]).
 *
 * Before it, deleting a box released its engine without stopping it, the other tombstoning paths did
 * neither, and the last client leaving disposed without stopping. The live-Field half of this — a real
 * launched `loop {}` deleted, and its document's last client gone, through the backdoor — is recorded
 * with the commit; this is the part that needs no server.
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

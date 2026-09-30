package afield

import afield.four.targets.putOwned
import afield.four.util.MapOfSequences
import afield.stdlib.Once
import afield.stdlib.Onceing
import afield.stdlib.once
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val Once.runScopeTestSlider by Onceing<Any>()
private val Once.runScopeTestPatch by Onceing<Any>()
private val Once.runScopeTestPure by Onceing<String>()

/**
 * [RunScope]: one owner per (box, strand, run), releasing what the run registered by
 * [ReleasePolicy] — keyed things only when the box retires, accumulating things when the strand's
 * next run has executed, running things on stop as well.
 */
class RunScopeTest {

    private fun <T> inRun(scope: RunScope, block: () -> T): T =
        Box.withBoxContextNow(scope.box) { RunScope.withCurrent(scope, block) }

    /** A stand-in for an audio patch: a [ReleasePolicy.RUNNING] claim whose release clears [live]. */
    private fun fakePatch(key: String): AtomicBoolean {
        val live = AtomicBoolean(true)
        LifecycleLedger.claim(LifecycleKinds.AUDIO_PATCH, key, live = { live.get() }, release = { live.set(false) })
        return live
    }

    /**
     * Whether [box] still owns the render module [key]. The ownership, not the map: `putOwned`'s
     * release removes the map entry on the main runloop, which a unit test does not pump.
     */
    private fun moduleOwned(box: Box, key: String) = LifecycleLedger.entries()
        .any { it.ownerId == box.id && it.kind == LifecycleKinds.RENDER_MODULE && it.key == key }

    private val forever get() = sequence<Any?> { while (true) yield(Unit) }

    @Test
    fun theKindsCarryThePoliciesOfTheModel() {
        assertEquals(ReleasePolicy.KEYED, ReleasePolicy.forKind(LifecycleKinds.RENDER_MODULE))
        assertEquals(ReleasePolicy.KEYED, ReleasePolicy.forKind(LifecycleKinds.RUNLOOP_PRE_RUN))
        assertEquals(ReleasePolicy.KEYED, ReleasePolicy.forKind(LifecycleKinds.RUNLOOP_POST_RUN))
        assertEquals(ReleasePolicy.ACCUMULATING, ReleasePolicy.forKind(LifecycleKinds.CONTROL_CALLBACK))
        assertEquals(ReleasePolicy.RUNNING, ReleasePolicy.forKind(LifecycleKinds.AUDIO_PATCH))
        assertEquals(ReleasePolicy.RUNNING, ReleasePolicy.forKind(LifecycleKinds.PATSYNTH_FEEDER))
        assertEquals(ReleasePolicy.RUNNING, ReleasePolicy.forKind(LifecycleKinds.STREAM_PROCESS))
        assertEquals(ReleasePolicy.KEYED, ReleasePolicy.forKind("some-map", hasSlot = true))
        assertEquals(ReleasePolicy.RUNNING, ReleasePolicy.forKind("some-thread", hasSlot = false))
    }

    @Test
    fun stopReleasesOnlyWhatIsRunning() {
        val box = Box("runscope-stop")
        val sliders = OwnedCallbackRegistry<(Double) -> Unit>("rs-stop")
        val hooks = MapOfSequences<String>(LifecycleKinds.RUNLOOP_PRE_RUN)
        val modules = mutableMapOf<String, Any>()
        val module = Any()
        val hook = forever
        val run = box.runScopes.begin("default", "launch")
        val patch = inRun(run) {
            sliders.register("s", {})
            hooks["h"] = hook
            modules.putOwned("m", module)
            fakePatch("p")
        }
        assertTrue(box.hasSomethingToStop(), "a box whose run holds a running patch has something to stop")

        box.stopTrackedJobs()

        assertFalse(patch.get(), "stop releases the audio patch")
        assertNotNull(sliders["s"], "a slider made by a launch that returned outlives the stop")
        assertTrue(hooks.isCurrent("h", hook), "a keyed Runloop hook outlives the stop")
        assertSame(module, modules["m"], "a keyed render module outlives the stop")
        assertFalse(run.isClosed, "the stopped run scope stays open, holding the rest")
        assertTrue(run.isStopped)
        assertFalse(box.hasSomethingToStop(), "nothing running is left")

        retireBoxes(listOf(box), "test")

        assertNull(sliders["s"], "retiring the box releases the slider")
        assertFalse(hooks.isCurrent("h", hook), "and the hook")
        assertFalse(moduleOwned(box, "m"), "and the module")
        assertTrue(run.isClosed)
        assertEquals(0, box.runScopes.liveCount)
    }

    @Test
    fun aRelaunchReleasesTheOldSliderOnlyAfterTheNewOneIsRegistered() {
        val box = Box("runscope-relaunch")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-relaunch")
        val first = box.runScopes.begin("default", "launch")
        inRun(first) { sliders.register("uuid-1", {}) }
        box.runScopes.supersede(first)
        box.stopTrackedJobs()
        assertNotNull(sliders["uuid-1"], "stop leaves the first launch's slider")

        val second = box.runScopes.begin("default", "launch")
        inRun(second) { sliders.register("uuid-2", {}) }
        assertNotNull(sliders["uuid-1"], "while the second launch executes, the first one's slider still answers")
        assertNotNull(sliders["uuid-2"])

        box.runScopes.supersede(second)

        assertTrue(first.isClosed, "the earlier launch closes once the new one has executed")
        assertNull(sliders["uuid-1"], "an accumulating registration goes with the run that made it")
        assertNotNull(sliders["uuid-2"])
        assertFalse(second.isClosed)
    }

    @Test
    fun keyedRegistrationsSurviveReRunsUntilTheirKeyIsReusedOrTheBoxIsDeleted() {
        val box = Box("runscope-keyed")
        val hooks = MapOfSequences<String>(LifecycleKinds.RUNLOOP_PRE_RUN)
        val modules = mutableMapOf<String, Any>()
        val m1 = Any()
        val only1 = Any()
        val h1 = forever
        val first = box.runScopes.begin("default", "launch")
        inRun(first) {
            modules.putOwned("boo", m1)
            modules.putOwned("only-first", only1)
            hooks["key"] = h1
        }
        box.runScopes.supersede(first)
        box.stopTrackedJobs()
        assertSame(m1, modules["boo"])
        assertTrue(hooks.isCurrent("key", h1))

        val m2 = Any()
        val h2 = forever
        val second = box.runScopes.begin("default", "launch")
        inRun(second) {
            modules.putOwned("boo", m2)
            hooks["key"] = h2
        }
        box.runScopes.supersede(second)

        assertSame(m2, modules["boo"], "re-running with the same key replaces the module")
        assertTrue(hooks.isCurrent("key", h2), "and the hook")
        assertSame(only1, modules["only-first"], "a key the re-run did not reuse keeps drawing")
        assertEquals(
            1, box.runScopes.parent.liveCount(ReleasePolicy.KEYED),
            "the superseded run's surviving keyed registration now belongs to the box's parent scope",
        )

        box.setPropertyRaw("_tombstone", true)
        retireBoxes(listOf(box), "delete-box")

        assertFalse(moduleOwned(box, "boo"), "deleting the box releases the module")
        assertFalse(moduleOwned(box, "only-first"), "including the one the parent scope took over")
        assertFalse(hooks.isCurrent("key", h2), "and the hook")
    }

    @Test
    fun anotherStrandIsNotSuperseded() {
        val box = Box("runscope-strands")
        val a = box.runScopes.begin("default", "launch")
        val b = box.runScopes.begin("other", "launch")
        box.runScopes.supersede(b)
        assertFalse(a.isClosed, "superseding one strand leaves another strand's run alone")
    }

    @Test
    fun aLateRegistrationIsTreatedAsIfItHadBeenThereWhenTheScopeEnded() {
        val box = Box("runscope-late")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-late")
        val modules = mutableMapOf<String, Any>()

        val stopped = box.runScopes.begin("stopped", "launch")
        box.stopTrackedJobs()
        val patch = inRun(stopped) { fakePatch("late-stopped") }
        inRun(stopped) { sliders.register("late-stopped", {}) }
        assertFalse(patch.get(), "a running thing arriving at a stopped run is released at once")
        assertNotNull(sliders["late-stopped"], "a slider arriving at a stopped run is kept")

        val superseded = box.runScopes.begin("sup", "launch")
        box.runScopes.supersede(box.runScopes.begin("sup", "launch"))
        inRun(superseded) {
            sliders.register("late-superseded", {})
            modules.putOwned("late-keyed", Any())
        }
        assertNull(sliders["late-superseded"], "a slider arriving at a superseded run is released at once")
        assertNotNull(modules["late-keyed"], "a keyed thing arriving at a superseded run passes to the parent")
        assertEquals(1, box.runScopes.parent.liveCount(ReleasePolicy.KEYED))

        val retired = box.runScopes.begin("ret", "launch")
        retired.close()
        inRun(retired) { modules.putOwned("late-retired", Any()) }
        assertFalse(moduleOwned(box, "late-retired"), "anything arriving at a retired run is released at once")
    }

    @Test
    fun closeReleasesNewestFirst() {
        val box = Box("runscope-order")
        val run = box.runScopes.begin("default", "launch")
        val order = mutableListOf<String>()
        run.own { order += "first" }
        run.own { order += "second" }
        run.close()
        assertEquals(listOf("second", "first"), order)
    }

    @Test
    fun withNoInstalledScopeTheBoxsAmbientRunOwns() {
        val box = Box("runscope-ambient")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-ambient")
        Box.withBoxContextNow(box) { sliders.register("a", {}) }
        val ambient = box.runScopes.snapshot().single()
        assertEquals(BoxRunScopes.RUN_STRAND, ambient.strand)
        box.stopTrackedJobs()
        assertNotNull(sliders["a"], "stop leaves an ambient slider")
        val after = Box.withBoxContextNow(box) { RunScope.current() }
        assertTrue(after !== ambient && after != null, "after a stop, the ambient scope is a fresh one")
        val patch = Box.withBoxContextNow(box) { fakePatch("after-stop") }
        assertTrue(patch.get(), "so something a fragment starts after a stop is not released at once")
        box.runScopes.closeAll()
        assertNull(sliders["a"])
    }

    @Test
    fun theParentScopeReopensAfterRetireUnlessTheBoxIsDeleted() {
        val box = Box("runscope-parent")
        val first = box.runScopes.parent
        box.runScopes.closeAll()
        assertTrue(first.isClosed)
        val second = box.runScopes.parent
        assertFalse(second.isClosed, "a retired, undeleted box gets a fresh parent on demand")

        box.setPropertyRaw("_tombstone", true)
        box.runScopes.closeAll()
        val modules = mutableMapOf<String, Any>()
        box.runScopes.parent.withCurrent { modules.putOwned("straggler", Any()) }
        assertFalse(moduleOwned(box, "straggler"), "a deleted box's parent releases a straggler at once")
    }

    // --- once ---------------------------------------------------------------------------------

    @Test
    fun aOnceSliderSurvivesReRunsAndStopAndGoesOnlyWithTheBox() {
        val box = Box("once-slider")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-once")
        var computed = 0
        fun run(): Any {
            val scope = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "run")
            val v = inRun(scope) {
                box.once.runScopeTestSlider {
                    computed++
                    sliders.register("once-slider", {})
                    Any()
                }
            }
            box.runScopes.supersede(scope)
            return v
        }
        val v1 = run()
        val v2 = run()
        assertSame(v1, v2)
        assertEquals(1, computed)
        assertNotNull(sliders["once-slider"], "a re-run does not release what once registered")

        box.stopTrackedJobs()
        assertNotNull(sliders["once-slider"], "nor does stop: a callback is not running")
        assertSame(v1, run(), "and the cached value is kept")
        assertEquals(1, computed)

        box.runScopes.closeAll()
        assertNull(sliders["once-slider"], "retiring the box releases it")
        assertNull(box.getPropertyRaw("runScopeTestSlider"), "and forgets the value that depended on it")
    }

    @Test
    fun aOnceValueHoldingSomethingRunningIsRecomputedAfterStop() {
        val box = Box("once-patch")
        var computed = 0
        var lastPatch: AtomicBoolean? = null
        fun run(): Any {
            val scope = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "run")
            val v = inRun(scope) {
                box.once.runScopeTestPatch {
                    computed++
                    lastPatch = fakePatch("once-patch-$computed")
                    Any()
                }
            }
            box.runScopes.supersede(scope)
            return v
        }
        val v1 = run()
        assertSame(v1, run(), "a re-run returns the cached value")
        assertTrue(lastPatch!!.get(), "and leaves its patch playing")

        box.stopTrackedJobs()
        assertFalse(lastPatch.get(), "stop releases the patch inside once")
        assertNull(box.getPropertyRaw("runScopeTestPatch"), "and clears the value that depended on it")

        val v2 = run()
        assertTrue(v2 !== v1, "so the next run computes a fresh one")
        assertEquals(2, computed)
        assertTrue(lastPatch.get(), "whose patch is live: the parent scope does not latch a stop")
    }

    @Test
    fun aPureOnceValueIsNeverCleared() {
        val box = Box("once-pure")
        val scope = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "run")
        inRun(scope) { box.once.runScopeTestPure { "#version 450" } }
        box.stopTrackedJobs()
        box.runScopes.closeAll()
        assertEquals("#version 450", box.getPropertyRaw("runScopeTestPure"), "a value that registered nothing is a plain property")
    }
}

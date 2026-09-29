package afield

import afield.four.util.MapOfSequences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RunScope]: one owner per (box, strand, run). What a run registers is released when the run is
 * stopped or superseded, and a re-run that registers the same thing again keeps it.
 */
class RunScopeTest {

    private fun <T> inRun(scope: RunScope, block: () -> T): T =
        Box.withBoxContextNow(scope.box) { RunScope.withCurrent(scope, block) }

    @Test
    fun stopReleasesWhatTheRunRegistered() {
        val box = Box("runscope-stop")
        val sliders = OwnedCallbackRegistry<(Double) -> Unit>("rs-stop")
        val hooks = MapOfSequences<String>(LifecycleKinds.RUNLOOP_PRE_RUN)
        val run = box.runScopes.begin("default", "launch")
        inRun(run) {
            sliders.register("s", {})
            hooks["h"] = sequence { while (true) yield(Unit) }
        }
        assertTrue(box.hasSomethingToStop(), "a box whose run holds registrations has something to stop")

        box.stopTrackedJobs()

        assertNull(sliders["s"], "stop must remove the run's slider callback")
        assertFalse(hooks.isCurrent("h", sequence { }), "and its Runloop hook")
        assertTrue(run.isClosed)
        assertTrue(box.runScopes.snapshot().isEmpty(), "stop closes every scope the box had")
        assertFalse(box.hasSomethingToStop())
    }

    @Test
    fun aReRunKeepsWhatItRegistersAgainAndReleasesTheRest() {
        val box = Box("runscope-supersede")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-supersede")
        val first = box.runScopes.begin("default", "launch")
        val kept1: () -> Unit = {}
        inRun(first) {
            sliders.register("kept", kept1)
            sliders.register("dropped", {})
        }

        val second = box.runScopes.begin("default", "launch")
        val kept2: () -> Unit = {}
        inRun(second) { sliders.register("kept", kept2) }
        box.runScopes.supersede(second)

        assertTrue(first.isClosed, "the earlier run of the strand closes once the new one has run")
        assertFalse(second.isClosed)
        assertSame(kept2, sliders["kept"], "a name the new run registered again survives the old run's close")
        assertNull(sliders["dropped"], "a name only the old run registered is released")
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
    fun registeringIntoAClosedRunReleasesAtOnce() {
        val box = Box("runscope-closed")
        val sliders = OwnedCallbackRegistry<() -> Unit>("rs-closed")
        val run = box.runScopes.begin("default", "launch")
        run.close()
        inRun(run) { sliders.register("late", {}) }
        assertNull(sliders["late"], "a run stop has already closed must not keep what it registers afterwards")
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
        assertNull(sliders["a"])
    }
}

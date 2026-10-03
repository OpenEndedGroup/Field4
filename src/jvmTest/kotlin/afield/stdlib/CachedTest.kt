package afield.stdlib

import afield.Box
import afield.BoxRunScopes
import afield.RunScope
import afield.stopTrackedJobs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** [cached]: one value per box, type and keys, closed when its keys change or the box retires. */
class CachedTest {

    private class View(val key: Any?) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    /** A view that, like `ElectronRTTView`, registers itself with the current run scope. */
    private class SelfOwningView : AutoCloseable {
        var closes = 0
        init { RunScope.current()?.own(this, afield.ReleasePolicy.KEYED) }
        override fun close() { closes++ }
    }

    private fun <T> run(box: Box, block: () -> T): T {
        val scope = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "run")
        val v = Box.withBoxContextNow(box) { RunScope.withCurrent(scope, block) }
        box.runScopes.supersede(scope)
        return v
    }

    @Test
    fun sameKeysReturnTheCachedValueAcrossReRuns() {
        val box = Box("cached-same")
        var made = 0
        val a = run(box) { box.cached<View>("r1") { made++; View("r1") } }
        val b = run(box) { box.cached<View>("r1") { made++; View("r1") } }
        assertSame(a, b)
        assertEquals(1, made)
        assertEquals(0, a.closes, "a re-run does not close the cached value")
        box.stopTrackedJobs()
        assertSame(a, run(box) { box.cached<View>("r1") { View("r1") } }, "nor does stop")
    }

    @Test
    fun changedKeysMakeANewValueAndCloseTheOld() {
        val box = Box("cached-change")
        val a = run(box) { box.cached<View>("r1") { View("r1") } }
        val b = run(box) { box.cached<View>("r2") { View("r2") } }
        assertNotSame(a, b)
        assertEquals(1, a.closes)
        assertEquals(0, b.closes)
        assertEquals(1, BoxCache.size(box))
    }

    @Test
    fun oneRunMayHoldSeveralKeysOfOneType() {
        val box = Box("cached-several")
        val (a, b) = run(box) { box.cached<View>("r1") { View("r1") } to box.cached<View>("r2") { View("r2") } }
        val (a2, b2) = run(box) { box.cached<View>("r1") { View("r1") } to box.cached<View>("r2") { View("r2") } }
        assertSame(a, a2)
        assertSame(b, b2)
        assertEquals(0, a.closes + b.closes)
    }

    @Test
    fun theTypeIsPartOfTheKey() {
        val box = Box("cached-type")
        val v = run(box) { box.cached<View>("k") { View("k") } }
        val s = run(box) { box.cached<String>("k") { "text" } }
        assertEquals("text", s)
        assertEquals(0, v.closes, "a String under the same keys does not evict the View")
        assertEquals(2, BoxCache.size(box))
    }

    @Test
    fun retiringTheBoxClosesEachValueOnce() {
        val box = Box("cached-retire")
        val plain = run(box) { box.cached<View>("r") { View("r") } }
        val owning = run(box) { box.cached<SelfOwningView>("r") { SelfOwningView() } }
        box.runScopes.closeAll()
        assertEquals(1, plain.closes)
        assertEquals(1, owning.closes, "a value that owned itself is not closed twice")
        assertEquals(0, BoxCache.size(box))
        assertFalse(run(box) { box.cached<View>("r") { View("r") } } === plain, "and the next ask makes a fresh one")
    }
}

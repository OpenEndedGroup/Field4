package afield.runtime.lifecycle

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.close

import afield.data.collections.s
import afield.runtime.execution.begin
import afield.document.graph.Box
import afield.runtime.lifecycle.BoxRunScopes
import afield.runtime.lifecycle.RunScope
import afield.document.graph.stopTrackedJobs
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
        init { RunScope.current()?.own(this, afield.runtime.lifecycle.ReleasePolicy.KEYED) }
        override fun close() { closes++ }
    }

    private fun <T> run(box: Box, block: () -> T): T {
        val scope = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "run")
        val v = Box.withBoxContextNow(box) { RunScope.withCurrent(scope, block) }
        box.runScopes.supersede(scope)
        return v
    }

    /** Loads [RecompiledProbe] afresh, the way each compile of a box makes its declared classes anew. */
    private fun freshProbeClass(): Class<*> {
        val name = RecompiledProbe::class.java.name
        val bytes = RecompiledProbe::class.java.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.readBytes()
        val loader = object : ClassLoader(RecompiledProbe::class.java.classLoader) {
            override fun loadClass(n: String, resolve: Boolean): Class<*> =
                if (n == name) findLoadedClass(n) ?: defineClass(n, bytes, 0, bytes.size) else super.loadClass(n, resolve)
        }
        return loader.loadClass(name)
    }

    @Test
    fun aRecompiledBoxLocalClassClosesTheStaleValueAndSaysSo() {
        val box = Box("cached-recompiled")
        val printed = mutableListOf<String>()
        box.output.proxy = { m, _, _ -> printed += m.toString() }
        val first = freshProbeClass()
        val second = freshProbeClass()
        val a = run(box) { @Suppress("UNCHECKED_CAST") BoxCache.lookup(box, first.kotlin as kotlin.reflect.KClass<Any>, emptyList()) { first.getDeclaredConstructor().newInstance() as Any } }
        run(box) { @Suppress("UNCHECKED_CAST") BoxCache.lookup(box, second.kotlin as kotlin.reflect.KClass<Any>, emptyList()) { second.getDeclaredConstructor().newInstance() as Any } }
        assertEquals(1, first.getMethod("getCloses").invoke(a), "the previous compile's value is closed at once")
        assertEquals(1, BoxCache.size(box))
        kotlin.test.assertTrue(printed.any { "declared in this box's code" in it }, "and the box is told why: $printed")
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

/** Stand-in for a class declared in a box's own code; see [CachedTest.freshProbeClass]. */
class RecompiledProbe : AutoCloseable {
    var closes = 0
    override fun close() { closes++ }
}

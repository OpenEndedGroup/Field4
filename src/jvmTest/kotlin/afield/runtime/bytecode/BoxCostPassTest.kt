package afield.runtime.bytecode

import afield.runtime.lifecycle.BoxInterrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Not rewritten: catches what box code throws, so the exception skips instrumented frames' exits. */
object BoxCostCatcher {
    fun swallow(f: () -> Int): Int = try { f() } catch (e: IllegalStateException) { -1 }
}

/** Code for [BoxCostPassTest] to rewrite. The `// COST:` markers name lines the tests look up. */
class BoxCostShapes {
    @Volatile var stop = false

    fun leaf(x: Int): Int = x * 2 + 1

    fun inner(n: Int): Long {
        var s = 0L // COST:inner
        for (i in 0 until n) s += leaf(i)
        return s
    }

    fun outer(n: Int): Long = inner(n) + inner(n) // COST:outer

    fun fact(n: Int): Long = if (n <= 1) 1L else n * fact(n - 1)

    fun thrower(): Int {
        if (System.nanoTime() != 0L) throw IllegalStateException("boom")
        return 1
    }

    fun middle(): Int = thrower() + 1

    /** The exception leaves [thrower] and [middle] and is caught here, then this sleeps. */
    fun catches(sleepMs: Long): Int = try {
        middle()
    } catch (e: IllegalStateException) {
        Thread.sleep(sleepMs)
        7
    }

    /** The exception is caught by uninstrumented code; this method's own exit reconciles the frames above it. */
    fun viaLibrary(sleepMs: Long): Int {
        val r = BoxCostCatcher.swallow { middle() }
        Thread.sleep(sleepMs)
        return r
    }

    fun spin(): Long {
        var n = 0L
        while (!stop) n++ // COST:spin
        return n
    }

    fun spinCaller(): Long = spin() + 1

    fun step(i: Int): Int = Math.floorMod(i * 31, 1009)

    fun callHeavy(n: Int): Long {
        var s = 0L
        for (i in 0 until n) s += step(i)
        return s
    }
}

class BoxCostPassTest {
    private val path = "afield/runtime/bytecode/BoxCostPassTest.kt"
    private val shapes = BoxCostShapes::class.java
    private var serial = 0

    private fun freshBox() = "box-cost-test-${System.nanoTime()}-${serial++}"

    private fun origin(uid: String): SnippetOrigin {
        val text = BoxBytecodeTestSupport.testSource(path)
        return SnippetOrigin(uid, "code", "'cost' ($uid)", text, shapes.classLoader) { text }
    }

    private fun lineOf(marker: String): Int {
        val lines = BoxBytecodeTestSupport.testSource(path)!!.lines()
        val i = lines.indexOfFirst { it.trimEnd().endsWith("// COST:$marker") }
        check(i >= 0) { "no // COST:$marker" }
        return i + 1
    }

    private fun load(uid: String, passes: List<BoxBytecodePass> = listOf(BoxInterrupt.PollPass, BoxCostPass)): Any =
        BoxBytecodeTestSupport.rewriteAndLoad(shapes, passes, origin(uid)).getDeclaredConstructor().newInstance()

    private fun call(o: Any, name: String, vararg args: Any): Any? =
        o.javaClass.methods.first { it.name == name }.invoke(o, *args)

    private fun costs(uid: String): Map<String, BoxCost.FunctionCost> =
        BoxCost.functions(includeOpen = false) { it == uid }.associateBy { it.function }

    private fun sitesOf(uid: String): List<Site> =
        (0 until SnippetSites.size).mapNotNull { SnippetSites[it] }.filter { it.kind == BoxCostPass.KIND && it.origin.boxId == uid }

    @Test
    fun nestedCallsChargeSelfAndTotal() {
        val uid = freshBox()
        val o = load(uid)
        assertEquals(2 * (0 until 1000).sumOf { it * 2L + 1 }, call(o, "outer", 1000))
        val c = costs(uid)
        val outer = assertNotNull(c["outer"], c.keys.toString())
        val inner = assertNotNull(c["inner"], c.keys.toString())
        assertEquals(1, outer.calls)
        assertEquals(2, inner.calls)
        // An exact identity on one thread: a frame's total is its self time plus its callees' totals.
        assertEquals(outer.totalNs, outer.selfNs + inner.totalNs, "$outer / $inner")
        assertEquals(inner.totalNs, inner.selfNs, "inner calls only a leaf, which has no frame")
        assertEquals(lineOf("outer"), outer.line)
        assertEquals(lineOf("inner"), inner.line, "a function's line is its first statement's")
    }

    @Test
    fun aLeafMethodIsNotInstrumented() {
        val uid = freshBox()
        val o = load(uid)
        call(o, "inner", 10)
        val names = sitesOf(uid).map { it.methodName }.toSet()
        assertFalse("leaf" in names, "leaf has a site: $names")
        assertFalse("<init>" in names, "the constructor has a site: $names")
        assertFalse("getStop" in names || "setStop" in names, "accessors are leaves: $names")
        assertTrue("inner" in names && "outer" in names && "thrower" in names, names.toString())
        assertFalse("leaf" in costs(uid), costs(uid).keys.toString())
    }

    @Test
    fun recursionCountsTotalOnce() {
        val uid = freshBox()
        val o = load(uid)
        assertEquals(3628800L, call(o, "fact", 10))
        val f = assertNotNull(costs(uid)["fact"])
        assertEquals(10, f.calls)
        assertEquals(f.totalNs, f.selfNs, "a recursive function's total is its outermost activation's, the sum of every self")
    }

    @Test
    fun anExceptionThroughTwoFramesIsReconciledAtTheCatch() {
        val uid = freshBox()
        val o = load(uid)
        val mark = BoxCost.begin()
        assertEquals(7, call(o, "catches", 30L))
        BoxCost.end(mark)
        val c = costs(uid)
        val thrower = assertNotNull(c["thrower"], c.keys.toString())
        val middle = assertNotNull(c["middle"], c.keys.toString())
        val catches = assertNotNull(c["catches"], c.keys.toString())
        assertEquals(listOf(1L, 1L, 1L), listOf(thrower.calls, middle.calls, catches.calls))
        // Charged up to the handler, not up to the next return: the sleep in the catch block is the catcher's.
        assertTrue(catches.selfNs >= 30_000_000, "$catches")
        assertTrue(thrower.totalNs < 20_000_000 && middle.totalNs < 20_000_000, "$thrower / $middle")
        assertEquals(catches.totalNs, catches.selfNs + middle.totalNs, "$catches / $middle")
    }

    @Test
    fun anOuterExitPopsFramesAnExceptionLeft() {
        val uid = freshBox()
        val o = load(uid)
        // No boundary: the reconciliation must come from viaLibrary's own exit.
        assertEquals(-1, call(o, "viaLibrary", 0L))
        val c = costs(uid)
        assertEquals(1, c["viaLibrary"]?.calls, c.toString())
        assertEquals(1, c["middle"]?.calls, c.toString())
        assertEquals(1, c["thrower"]?.calls, c.toString())
        assertEquals(1, c["lambda in viaLibrary"]?.calls, c.toString())
        assertTrue(BoxCost.functions { it == uid }.none { it.running }, "nothing is left open")
    }

    @Test
    fun aRunBoundaryPopsWhatAnUncaughtExceptionLeft() {
        val uid = freshBox()
        val o = load(uid)
        val mark = BoxCost.begin()
        val thrown = runCatching { call(o, "middle") }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(BoxCost.functions { it == uid }.any { it.running }, "middle and thrower are open until the boundary")
        BoxCost.end(mark)
        val c = costs(uid)
        assertEquals(1, c["middle"]?.calls, c.toString())
        assertEquals(1, c["thrower"]?.calls, c.toString())
        assertTrue(BoxCost.functions { it == uid }.none { it.running }, "the boundary closed them")
    }

    @Test
    fun aStallReportNamesTheRunningFunction() {
        val uid = freshBox()
        val o = load(uid)
        val t = Thread({ call(o, "spinCaller") }, "box-cost-stall-test")
        t.isDaemon = true
        t.start()
        try {
            Thread.sleep(150)
            val report = BoxInterrupt.Watchdog.report(150, t, t.stackTrace, "'cost' ($uid)")
            println(report)
            assertTrue("hottest box function: 'cost' ($uid) spin (box line ${lineOf("spin")})" in report, report)
            assertTrue("running now" in report, report)
            assertTrue("open: spin (box line ${lineOf("spin")}) <- spinCaller" in report, report)
        } finally {
            o.javaClass.getMethod("setStop", Boolean::class.javaPrimitiveType).invoke(o, true)
            t.join(5000)
        }
        val c = costs(uid)
        assertEquals(1, c["spin"]?.calls, c.toString())
    }

    @Test
    fun overheadOnACallHeavyLoop() {
        // Printed, not gated: the cost per instrumented call (enter + exit) on this machine.
        val n = 20_000_000
        fun best(o: Any) = (1..5).minOf { val t0 = System.nanoTime(); call(o, "callHeavy", n); System.nanoTime() - t0 }
        val plain = shapes.getDeclaredConstructor().newInstance()
        val measured = load(freshBox())
        val off = best(plain)
        val on = best(measured)
        println("box cost: callHeavy($n) uninstrumented ${off / 1_000_000} ms, instrumented ${on / 1_000_000} ms; " +
            "%.1f ns per instrumented call".format((on - off).toDouble() / n))
    }
}

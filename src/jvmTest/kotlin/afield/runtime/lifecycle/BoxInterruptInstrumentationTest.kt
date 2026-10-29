package afield.runtime.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shapes of box code that never return, compiled by the test compiler and instrumented like a snippet. */
class RunawayShapes {
    fun spin(): Long { var i = 0L; while (true) { i++; if (i == -1L) return i } }
    fun fib(n: Int): Long = if (n < 2) n.toLong() else fib(n - 1) + fib(n - 2)
    fun swallow(): Int { var n = 0; while (true) { try { n++ ; if (n < 0) return n } catch (e: Exception) { } } }
    fun finite(): Int { var s = 0; for (i in 0 until 1000) s += i; return s }
}

class BoxInterruptInstrumentationTest {

    private fun instrumentedShapes(): Class<*> {
        val name = RunawayShapes::class.java.name
        val bytes = RunawayShapes::class.java.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.readBytes()
        val rewritten = BoxInterrupt.instrumentClass(bytes, "'test' (shapes)")!!
        val loader = object : ClassLoader(RunawayShapes::class.java.classLoader) {
            override fun loadClass(n: String, resolve: Boolean): Class<*> =
                if (n == name) synchronized(this) { findLoadedClass(n) ?: defineClass(n, rewritten, 0, rewritten.size) }
                else super.loadClass(n, resolve)
        }
        return loader.loadClass(name)
    }

    /** Runs [method] on its own thread, requests an interrupt after 200 ms, returns (error, ms to land). */
    private fun interruptAfter(method: String, vararg args: Any): Pair<Throwable?, Long> {
        val cls = instrumentedShapes()
        val instance = cls.getDeclaredConstructor().newInstance()
        val m = cls.methods.first { it.name == method }
        var error: Throwable? = null
        val t = Thread { error = runCatching { m.invoke(instance, *args) }.exceptionOrNull()?.let { it.cause ?: it } }
        t.isDaemon = true
        t.start()
        Thread.sleep(200)
        val t0 = System.nanoTime()
        BoxInterrupt.request("test", t)
        t.join(2000)
        return error to (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun whileTrueIsInterruptedWithinTwoSeconds() {
        val (e, ms) = interruptAfter("spin")
        assertTrue(e is BoxInterrupt.BoxInterruptedError, "got $e")
        assertTrue(ms < 2000, "took $ms ms")
        assertTrue(!BoxInterrupt.isPending())
    }

    @Test
    fun runawayRecursionIsInterrupted() {
        val (e, ms) = interruptAfter("fib", 90)
        assertTrue(e is BoxInterrupt.BoxInterruptedError, "got $e")
        assertTrue(ms < 2000, "took $ms ms")
    }

    @Test
    fun catchExceptionDoesNotSwallowIt() {
        val (e, _) = interruptAfter("swallow")
        assertTrue(e is BoxInterrupt.BoxInterruptedError, "got $e")
    }

    @Test
    fun instrumentedCodeStillComputesAndNamesItsBox() {
        val cls = instrumentedShapes()
        assertEquals(499500, cls.getMethod("finite").invoke(cls.getDeclaredConstructor().newInstance()))
        val stack = arrayOf(StackTraceElement("java.lang.Thread", "run", null, 0), StackTraceElement(cls.name, "spin", null, 0))
        assertEquals("'test' (shapes)", BoxInterrupt.culprit(stack))
    }

    @Test
    fun aRequestForAnotherThreadDoesNotFireHere() {
        val other = Thread { }
        BoxInterrupt.request("elsewhere", other)
        BoxInterrupt.poll() // must not throw on this thread
        BoxInterrupt.withdrawIfFrameAdvanced(Long.MIN_VALUE)
        assertTrue(!BoxInterrupt.isPending())
    }

    @Test
    fun aRequestFromAnEarlierFrameDoesNotFireInTheNextBox() {
        // e.g. "Interrupt Running Box" pressed while nothing ran, with the watchdog not running
        val cls = instrumentedShapes()
        BoxInterrupt.request("stale", Thread.currentThread())
        Thread.interrupted() // request() may set the flag; this test is about the pending slot
        Runloop.runLoop.frame++
        try {
            assertEquals(499500, cls.getMethod("finite").invoke(cls.getDeclaredConstructor().newInstance()))
            assertTrue(!BoxInterrupt.isPending())
        } finally {
            Runloop.runLoop.frame--
        }
    }
}

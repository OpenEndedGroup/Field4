package afield.runtime.bytecode

import afield.runtime.lifecycle.BoxInterrupt
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Code for [LineHeatPassTest] to rewrite. The `// HEAT:` markers name lines the tests look up. */
class LineHeatShapes {
    var field = 3 // HEAT:field-init

    fun loop(n: Int): Int {
        var s = 0
        for (i in 0 until n) {
            s += i // HEAT:loop-body
        }
        return s // HEAT:after-loop
    }

    fun caught(): Int {
        try {
            error("boom") // HEAT:throws
        } catch (e: IllegalStateException) {
            val m = e.message!! // HEAT:catch-first
            return m.length
        }
    }

    fun inlined(): Int {
        var n = 0
        repeat(4) { n++ } // HEAT:inlined
        return n
    }

    fun lambda(): Int {
        val f = { x: Int ->
            x * 2 // HEAT:lambda-body
        }
        return f(1) + f(2)
    }
}

class LineHeatPassTest {
    private val path = "afield/runtime/bytecode/LineHeatPassTest.kt"
    private val shapes = LineHeatShapes::class.java

    private var serial = 0

    /** An origin for box [uid] whose source and current code are this file, so box lines are file lines. */
    private fun origin(uid: String, text: String? = BoxBytecodeTestSupport.testSource(path), code: () -> String? = { text }) =
        SnippetOrigin(uid, "code", "'heat' ($uid)", text, shapes.classLoader, code)

    private fun freshBox() = "line-heat-test-${System.nanoTime()}-${serial++}"

    private fun lineOf(marker: String): Int {
        val lines = BoxBytecodeTestSupport.testSource(path)!!.lines()
        val i = lines.indexOfFirst { it.trimEnd().endsWith("// HEAT:$marker") }
        check(i >= 0) { "no // HEAT:$marker" }
        return i + 1
    }

    private fun countsByLine(uid: String): Map<Int, Int> =
        LineHeat.sitesOf(uid).groupBy { it.line }.mapValues { (_, s) -> s.sumOf { LineHeat.count(it.id) } }

    private fun load(uid: String, origin: SnippetOrigin = origin(uid), passes: List<BoxBytecodePass> = listOf(LineHeatPass)): Any {
        val cls = BoxBytecodeTestSupport.rewriteAndLoad(shapes, passes, origin)
        return cls.getDeclaredConstructor().newInstance()
    }

    private fun call(o: Any, name: String, vararg args: Any): Any? =
        o.javaClass.methods.first { it.name == name }.invoke(o, *args)

    @Test
    fun aLoopBodyCountsEachIteration() {
        val uid = freshBox()
        val o = load(uid)
        assertEquals(45, call(o, "loop", 10))
        val c = countsByLine(uid)
        assertEquals(10, c[lineOf("loop-body")], c.toString())
        assertEquals(1, c[lineOf("after-loop")], c.toString())
        // The constructor's property initialiser is a line too: probes in <init> before and after super() verify.
        assertEquals(1, c[lineOf("field-init")], c.toString())
        call(o, "loop", 5)
        assertEquals(15, countsByLine(uid)[lineOf("loop-body")])
    }

    @Test
    fun aCatchBlocksFirstLineVerifiesAndCounts() {
        // rewriteAndLoad links the class, which runs the verifier: a probe between the handler's
        // label and its frame would fail here ("Expecting a stackmap frame at branch target").
        val uid = freshBox()
        val o = load(uid, passes = listOf(BoxInterrupt.PollPass, LineHeatPass))
        assertEquals(4, call(o, "caught"))
        val c = countsByLine(uid)
        assertEquals(1, c[lineOf("throws")], c.toString())
        assertEquals(1, c[lineOf("catch-first")], c.toString())
    }

    @Test
    fun oneSitePerClassAndLine() {
        val uid = freshBox()
        val o = load(uid)
        call(o, "lambda")
        val sites = LineHeat.sitesOf(uid)
        assertEquals(sites.size, sites.map { it.line }.toSet().size, "a line has one site per class: ${sites.map { it.line }}")
        assertTrue(sites.all { it.kind == LineHeatPass.KIND && it.origin.boxId == uid })
        // A lambda body (its own synthetic method, or an invokedynamic body) counts per call.
        assertEquals(2, countsByLine(uid)[lineOf("lambda-body")])
    }

    @Test
    fun linesPastTheSourceGetNoProbe() {
        val text = BoxBytecodeTestSupport.testSource(path)!!
        val fileLines = SnippetOrigin.countLines(text)
        // The inlined `repeat` brings stdlib lines (the SMAP range, past the end of this file).
        val raw = bytecodeLines(BoxBytecodeTestSupport.bytesOf(shapes))
        assertTrue(raw.any { it > fileLines }, "expected inlined lines past $fileLines, saw ${raw.max()}")

        val uid = freshBox()
        val o = load(uid)
        assertEquals(4, call(o, "inlined"))
        val sites = LineHeat.sitesOf(uid)
        assertTrue(sites.isNotEmpty())
        assertTrue(sites.all { it.line in 1..fileLines }, sites.filter { it.line > fileLines }.toString())
        // Entered once for the call and once per inlined lambda body; kotlinc's second, fall-through
        // LINENUMBER for the same line after each body is not an entry (it counted 9 before that rule).
        assertEquals(5, countsByLine(uid)[lineOf("inlined")])

        // A source cut short above the loop: only the lines it has get probes.
        val cut = lineOf("loop-body") - 1
        val short = text.lines().take(cut).joinToString("\n")
        val uid2 = freshBox()
        call(load(uid2, origin(uid2, short)), "loop", 3)
        val shortSites = LineHeat.sitesOf(uid2)
        assertTrue(shortSites.isNotEmpty() && shortSites.all { it.line <= cut }, shortSites.map { it.line }.toString())
        assertFalse(lineOf("loop-body") in countsByLine(uid2))

        // No source at all: no line can be placed, so nothing is inserted.
        val uid3 = freshBox()
        call(load(uid3, origin(uid3, null)), "loop", 3)
        assertTrue(LineHeat.sitesOf(uid3).isEmpty())
    }

    @Test
    fun thePassWantsOnlyTheWatchedBox() {
        val uid = freshBox()
        val key = Any()
        assertFalse(LineHeatPass.wants(origin(uid)))
        LineHeat.watch(key, uid)
        try {
            assertTrue(LineHeatPass.wants(origin(uid)))
            assertFalse(LineHeatPass.wants(origin(freshBox())), "another box")
            assertFalse(LineHeatPass.wants(SnippetOrigin(uid, "fragment", "x", "", null)), "another property")
            assertFalse(LineHeatPass.wants(SnippetOrigin.detached("no box")))
        } finally {
            LineHeat.unwatch(key)
        }
        assertFalse(LineHeatPass.wants(origin(uid)))
    }

    @Test
    fun thePublisherSendsDeltasOnBoxLinesAndRestartsWhenTheTextChanges() {
        val uid = freshBox()
        val key = Any()
        var code: String? = BoxBytecodeTestSupport.testSource(path)
        val o = load(uid, origin(uid, code) { code })
        LineHeat.watch(key, uid)
        try {
            call(o, "loop", 7)
            val first = LineHeat.publish().single { it.first === key }.second
            assertEquals(uid, first.uid)
            assertEquals(LineHeat.codeHash(code!!), first.codeHash)
            val body = first.lines.single { it.line == lineOf("loop-body") }
            assertEquals(7, body.delta); assertEquals(7L, body.total)

            // Nothing ran: nothing to send.
            assertTrue(LineHeat.publish().none { it.first === key })

            call(o, "loop", 7)
            val second = LineHeat.publish().single { it.first === key }.second.lines.single { it.line == lineOf("loop-body") }
            assertEquals(7, second.delta); assertEquals(14L, second.total)

            // The box is edited (a line inserted at the top): lines are re-mapped, totals restart.
            code = "// a new first line\n" + code
            call(o, "loop", 2)
            val edited = LineHeat.publish().single { it.first === key }.second
            assertNotEquals(first.codeHash, edited.codeHash)
            val moved = edited.lines.single { it.line == lineOf("loop-body") + 1 }
            assertEquals(2, moved.delta); assertEquals(2L, moved.total)
        } finally {
            LineHeat.unwatch(key)
        }
        // Unwatched: counts still accumulate (the class keeps its probes) but nothing is published.
        call(o, "loop", 1)
        assertTrue(LineHeat.publish().none { it.first === key })
    }

    @Test
    fun aLineAMacroRewroteIsPlacedBetweenItsProvenNeighbours() {
        // The compiled text has the loop body rewritten in place (as `println(` becomes
        // `_printlnHere(n+1, `), so it is not proven by text; the lines around it agree on one offset.
        val box = BoxBytecodeTestSupport.testSource(path)!!
        val body = lineOf("loop-body")
        val compiled = box.lines().mapIndexed { i, t -> if (i == body - 1) t.replace("s += i", "s = s + i") else t }.joinToString("\n")
        val uid = freshBox()
        val o = load(uid, origin(uid, compiled) { box })
        val key = Any()
        LineHeat.watch(key, uid)
        try {
            call(o, "loop", 4)
            val site = LineHeat.sitesOf(uid).single { it.line == body }
            assertEquals(-1, site.boxLine(), "not proven by text")
            val lines = LineHeat.publish().single { it.first === key }.second.lines
            assertEquals(4, lines.single { it.line == body }.delta, lines.toString())
        } finally {
            LineHeat.unwatch(key)
        }
    }

    @Test
    fun aWatchStartedLaterDoesNotCountEarlierRuns() {
        val uid = freshBox()
        val o = load(uid)
        call(o, "loop", 9)
        val key = Any()
        LineHeat.watch(key, uid)
        try {
            assertTrue(LineHeat.publish().none { it.first === key })
            call(o, "loop", 1)
            val body = LineHeat.publish().single { it.first === key }.second.lines.single { it.line == lineOf("loop-body") }
            assertEquals(1, body.delta); assertEquals(1L, body.total)
        } finally {
            LineHeat.unwatch(key)
        }
    }

    @Test
    fun codeHashMatchesTheEditorsRule() {
        // lineHeatmap.js: FNV-1a over UTF-16 units, \r\n read as \n. Pinned values so the two stay equal.
        assertEquals(0x811c9dc5.toInt(), LineHeat.codeHash(""))
        assertEquals(LineHeat.codeHash("a\nb"), LineHeat.codeHash("a\r\nb"))
        assertEquals(0xe40c292c.toInt(), LineHeat.codeHash("a"))
    }

    @Test
    fun hitGrowsTheTableForAnUnregisteredSite() {
        val far = 1 shl 20
        val before = LineHeat.count(far)
        LineHeat.hit(far)
        LineHeat.hit(far)
        assertEquals(before + 2, LineHeat.count(far))
    }

    @Test
    fun theProbeCostOnATightLoopIsMeasured() {
        // Not a gate, a record (dev_docs/box_bytecode/LINE_HEAT.md quotes the jvm-main smoke's numbers,
        // which run real box code): best of several runs, rewritten with and without the pass.
        val plain = BoxBytecodeTestSupport.load(shapes.name, BoxBytecode.rewriteClass(BoxBytecodeTestSupport.bytesOf(shapes), origin(freshBox()), listOf(BoxInterrupt.PollPass))!!, shapes.classLoader)
            .getDeclaredConstructor().newInstance()
        val heat = load(freshBox(), passes = listOf(BoxInterrupt.PollPass, LineHeatPass))
        fun best(o: Any): Long = (1..7).minOf {
            val t0 = System.nanoTime()
            call(o, "loop", 20_000_000)
            System.nanoTime() - t0
        }
        val off = best(plain)
        val on = best(heat)
        println("line heat probe cost: loop of 2e7, off ${off / 1_000_000} ms, on ${on / 1_000_000} ms (x${"%.2f".format(on.toDouble() / off)})")
        assertNotNull(on)
    }

    private fun bytecodeLines(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.API_VERSION) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                object : MethodVisitor(Opcodes.API_VERSION) {
                    override fun visitLineNumber(line: Int, start: Label?) { out += line }
                }
        }, 0)
        return out
    }
}

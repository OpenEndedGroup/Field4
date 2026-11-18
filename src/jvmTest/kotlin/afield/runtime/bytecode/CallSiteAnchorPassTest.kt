package afield.runtime.bytecode

import afield.document.graph.Box
import afield.ui.output.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Box code for [CallSiteAnchorPassTest]: compiled by the test compiler, then rewritten by
 * [CallSiteAnchorPass] and reloaded (which links it, so the verifier checks the inserted code).
 * Each method's interesting call is on a line tagged with a comment the test finds by text.
 */
class AnchorBoxCode {
    fun twoOnOneLine(): Pair<Location?, Location?> {
        val a = history(1); val b = history(2, 5) // two-on-one-line
        return a to b
    }

    fun single(): Location? = history(3) // single-call

    fun throwsBeforeEntry(): Location? = pianoRoll(4) // throws-before-entry

    fun loop(n: Int): Int {
        var found = 0
        for (i in 0 until n) if (history(i) != null) found++ // timed-loop
        return found
    }

    fun loopByStack(n: Int): Int {
        var found = 0
        for (i in 0 until n) if (historyByStack(i) != null) found++ // timed-loop-by-stack
        return found
    }
}

class CallSiteAnchorPassTest {

    private val path = "afield/runtime/bytecode/CallSiteAnchorPassTest.kt"
    private val source = BoxBytecodeTestSupport.testSource(path) ?: error("no test source at $path")

    // The box id is part of this file's name, so the stack fallback finds this file's frames too.
    private val box = Box("CallSiteAnchorPassTest").also { it.properties["code"] = source; anchorBox = it }

    private fun origin() = SnippetOrigin(box.id, "code", "'test' (anchors)", source, AnchorBoxCode::class.java.classLoader) { box.properties["code"] as? String }

    private fun rewritten(): Any =
        BoxBytecodeTestSupport.rewriteAndLoad(AnchorBoxCode::class.java, listOf(CallSiteAnchorPass), origin()).getDeclaredConstructor().newInstance()

    private fun <T> call(o: Any, name: String, vararg args: Any): T {
        val m = o.javaClass.methods.first { it.name == name }
        try {
            @Suppress("UNCHECKED_CAST")
            return m.invoke(o, *args) as T
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    /** The 1-based line of the source line containing [tag]'s comment, and that line's text. */
    private fun tagged(tag: String): Pair<Int, String> {
        val lines = source.split("\n")
        val i = lines.indexOfFirst { it.trimEnd().endsWith("// $tag") }
        check(i >= 0) { "no line tagged $tag" }
        return i + 1 to lines[i]
    }

    @Test
    fun twoCallsOnOneLineGetTheirOwnColumnsIncludingADefaultStub() {
        val (line, text) = tagged("two-on-one-line")
        val first = text.indexOf("history")
        val second = text.indexOf("history", first + 1)
        val (a, b) = call<Pair<Location?, Location?>>(rewritten(), "twoOnOneLine")
        assertTrue(lastAnchored, "the second call was answered from the slot")
        assertNotNull(a); assertNotNull(b)
        assertEquals(line, a.line); assertEquals(line, b.line)
        // `history(1)` is a call to `history$default`, `history(2, 5)` to `history`: one name, ordinals 0 and 1.
        assertEquals(first + "history".length + 1, a.col)
        assertEquals(second + "history".length + 1, b.col)

        // The sites carry the target and the ordinal among same-named calls on the line.
        val anchors = (0 until SnippetSites.size).mapNotNull { SnippetSites[it] }
            .filter { it.kind == "callsite" && it.className == "afield/runtime/bytecode/AnchorBoxCode" && it.methodName == "twoOnOneLine" }
            .map { it.detail.toString() }
        assertEquals(listOf("history #0/2", "history #1/2"), anchors.takeLast(2))
    }

    @Test
    fun theStackFallbackAnswersUninstrumentedCallersButCannotTellTwoCallsApart() {
        val (line, text) = tagged("two-on-one-line")
        val (a, b) = AnchorBoxCode().twoOnOneLine()
        assertFalse(lastAnchored, "an uninstrumented caller is answered by the stack")
        assertNotNull(a); assertNotNull(b)
        assertEquals(line, a.line)
        // Both are the first occurrence: the old path cannot see which call on the line it is.
        assertEquals(text.indexOf("history") + "history".length + 1, b.col)
        assertEquals(a, b)

        val single = AnchorBoxCode().single()
        assertEquals(tagged("single-call").first, single?.line)
        val anchored = call<Location?>(rewritten(), "single")
        assertTrue(lastAnchored)
        assertEquals(single, anchored, "anchored and stack answers agree on a line with one call")
    }

    @Test
    fun aSlotLeftByACallThatThrewBeforeEntryIsRefused() {
        val code = rewritten()
        val refused = CallSites.refused
        assertFailsWith<IllegalStateException> { call<Location?>(code, "throwsBeforeEntry") }
        // The slot is still armed with the pianoRoll site. A history call that was not armed (made
        // from uninstrumented code) must not take it: wrong target name, refused, stack fallback.
        val l = history(9)
        assertFalse(lastAnchored)
        assertEquals(refused + 1, CallSites.refused)
        assertNotNull(l, "the fallback still answers")
        assertEquals(-1, CallSites.takeSite(), "take cleared the stale slot")

        // Armed for the right name but another box: refused too.
        assertFailsWith<IllegalStateException> { call<Location?>(code, "throwsBeforeEntry") }
        assertNull(CallSites.take(Box("someOtherBox"), "pianoRoll", ".pianoRoll("))
        assertEquals(refused + 2, CallSites.refused)
        assertEquals(-1, CallSites.takeSite())
    }

    @Test
    fun anEditedLineIsNotProvenAndFallsBack() {
        val code = rewritten()
        val (line, _) = tagged("single-call")
        val edited = source.split("\n").toMutableList().also { it[line - 1] = it[line - 1].replace("history(3)", "history(33)") }.joinToString("\n")
        box.properties["code"] = edited
        try {
            val l = call<Location?>(code, "single")
            assertFalse(lastAnchored, "the compiled line no longer matches the box's text")
            assertEquals(line, l?.line, "and the stack fallback answers as it always did")
        } finally {
            box.properties["code"] = source
        }
    }

    @Test
    fun theAnchorPassIsOffWhenSwitchedOff() {
        val was = CallSiteAnchorPass.enabled
        try {
            CallSiteAnchorPass.enabled = false
            assertFalse(CallSiteAnchorPass.wants(origin()))
        } finally {
            CallSiteAnchorPass.enabled = was
        }
        assertTrue(CallSiteAnchorPass.wants(origin()))
        assertFalse(CallSiteAnchorPass.wants(SnippetOrigin.detached("no loader")), "no loader, no annotations to read")
    }

    @Test
    fun theBoxHelpersAreStackAware() {
        val loader = javaClass.classLoader
        val desc = "Lafield/runtime/bytecode/StackAware;"
        fun aware(owner: String, name: String) =
            readMethods(loader, owner).filter { it.first == name }.any { (n, d) -> CallTargets.annotation(loader, owner, n, d, desc) != null }
        val expected = listOf(
            "afield/ui/output/DecorationsKt" to listOf("history", "histogram", "stats", "toggle", "beep", "liveNumber", "history\$default", "liveNumber\$default"),
            "afield/ui/output/BoppableString" to listOf("bop", "bop\$default"),
            "afield/math/distribution/DistViewKt" to listOf("distribution"),
            "afield/audio/synth/SequencingKt" to listOf("pianoRoll"),
            "afield/audio/synth/TidalPatternsKt" to listOf("pat"),
        )
        for ((owner, names) in expected) for (n in names) assertTrue(aware(owner, n), "$owner.$n is @StackAware")
    }

    @Test
    fun perCallCostOfTheAnchorAgainstTheStackSearch() {
        val code = rewritten()
        val n = 20_000
        // Warm both paths, then time them; the numbers go to the test's output.
        repeat(3) { call<Int>(code, "loop", n); call<Int>(AnchorBoxCode(), "loopByStack", 2_000) }
        val t0 = System.nanoTime()
        val anchoredFound = call<Int>(code, "loop", n)
        val t1 = System.nanoTime()
        val stackFound = call<Int>(AnchorBoxCode(), "loopByStack", n)
        val t2 = System.nanoTime()
        assertEquals(n, anchoredFound)
        assertEquals(n, stackFound)
        val anchoredNs = (t1 - t0).toDouble() / n
        val stackNs = (t2 - t1).toDouble() / n
        println("callsite-anchor cost: anchored ${"%.0f".format(anchoredNs)} ns/call, stack search ${"%.0f".format(stackNs)} ns/call (stack depth ${Throwable().stackTrace.size}, ${source.lines().size}-line box)")
        assertTrue(anchoredNs < stackNs, "the slot is cheaper than a stack trace")
    }

    private fun readMethods(loader: ClassLoader, owner: String): List<Pair<String, String>> {
        val bytes = loader.getResourceAsStream("$owner.class")!!.readBytes()
        val out = ArrayList<Pair<String, String>>()
        org.jetbrains.org.objectweb.asm.ClassReader(bytes).accept(object : org.jetbrains.org.objectweb.asm.ClassVisitor(org.jetbrains.org.objectweb.asm.Opcodes.API_VERSION) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): org.jetbrains.org.objectweb.asm.MethodVisitor? {
                out += name to descriptor
                return null
            }
        }, org.jetbrains.org.objectweb.asm.ClassReader.SKIP_CODE)
        return out
    }
}

package afield.runtime.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetClassReceiverCrashTest {
    private fun site(source: String) = SnippetClassReceiverCrash.locate(source)

    @Test
    fun theMinimalCrashIsFoundAtItsCall() {
        assertEquals(SnippetClassReceiverCrash.Site(1, 21, "println", "C"), site("""class C { fun g() { println("hi") } }"""))
    }

    /** The line a local model wrote on 2026-10-28; the crash reported the box's last line instead. */
    @Test
    fun theAgentsWriterLetterIsFoundThroughItsConstructorAndKDoc() {
        val source = """
            |/** The writer. Uses { braces } in prose. */
            |class Writer(
            |    val world: World,
            |    var x: Double,
            |) {
            |    val cursorMid: Point2 get() = point2(x, 0.0)
            |
            |    fun letter(name: String, body: Writer.() -> Unit) {
            |        try { body() } catch (e: Exception) { println("letter '${'$'}name' at x=${'$'}{"%.1f".format(x)} failed: ${'$'}e") }
            |        x += 1.0
            |    }
            |}
            |println("outside is fine")
        """.trimMargin()
        val found = site(source)!!
        assertEquals(9, found.line)
        assertEquals("println", found.member)
        assertEquals("Writer", found.declaration)
    }

    /** What the compiler is actually given: the macro pass has already rewritten `println`. */
    @Test
    fun theMacroRewrittenFormIsReportedAsWhatWasWritten() {
        assertEquals(SnippetClassReceiverCrash.Site(1, 21, "println", "C"),
            site("""class C { fun g() { _printlnHere(__line__+1, "hi") } }"""))
    }

    @Test
    fun displayErrorAndObjectsCountToo() {
        assertEquals("display", site("class C { fun g() { display(\"hi\") } }")!!.member)
        assertEquals("O", site("object O { fun g() { error(\"no\") } }")!!.declaration)
        assertEquals(2, site("class C {\n    val f = { println(1) }\n}")!!.line)
    }

    @Test
    fun qualifiedCallsOwnMembersStringsAndCodeOutsideClassesDoNotMatch() {
        assertNull(site("class C { fun g() { kotlin.io.println(\"x\") } }"))
        assertNull(site("class C(val box: Box) { fun g() { box.println(\"x\") } }"))
        assertNull(site("class C { fun println(m: Any?) {}; fun g() = 1 }"))
        assertNull(site("class C { val s = \"println(\" }"))
        assertNull(site("class C { /* println(1) */ val n = 1 }"))
        assertNull(site("class W\nfun W.x() = 1\nprintln(W().x())"))
        assertNull(site("println(\"top level\")"))
    }

    @Test
    fun theExplanationNamesTheSiteAndTheWayOut() {
        val text = SnippetClassReceiverCrash.explain(SnippetClassReceiverCrash.Site(127, 47, "println", "Writer"))
        assertTrue("`println(...)` in `Writer`, line 127" in text && "kotlin.io.println" in text, text)
        assertTrue(SnippetClassReceiverCrash.matches(listOf(null, "Unexpected script transformation state: ScriptLikeToClassTransformerContext(...)")))
    }
}

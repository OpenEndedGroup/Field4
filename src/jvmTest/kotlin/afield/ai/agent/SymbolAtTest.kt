package afield.ai.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SymbolAtTest {
    private val code = """
        |val curves = curves("0 1")
        |class World {
        |    fun curves(): List<Int> = listOf()
        |    fun draw() { println(curves()) }
        |}
        |val mycurves = 1
    """.trimMargin()

    @Test
    fun theFirstUseIsAWholeWordNotASubstring() {
        assertEquals(SymbolAt.Place(4, 1, 5), SymbolAt.locate(code, "curves"))
        assertEquals(6, SymbolAt.locate(code, "mycurves")!!.line)
    }

    @Test
    fun lineNearAndOccurrencePickThePosition() {
        assertEquals(3, SymbolAt.locate(code, "curves", line = 3)!!.line)
        assertEquals(4, SymbolAt.locate(code, "curves", near = "println(")!!.line)
        assertEquals(14, SymbolAt.locate(code, "curves", occurrence = 2)!!.column)
        assertNull(SymbolAt.locate(code, "curves", line = 5))
        assertNull(SymbolAt.locate(code, "curves", near = "not in the code"))
        assertNull(SymbolAt.locate(code, "absent"))
    }

    @Test
    fun declarationsListEveryDeclaringLine() {
        assertEquals(listOf("1: val curves = curves(\"0 1\")", "3: fun curves(): List<Int> = listOf()"), SymbolAt.declarations(code, "curves"))
        assertEquals(listOf("2: class World {"), SymbolAt.declarations(code, "World"))
    }

    @Test
    fun aMemberFindsItsReceiverAndTheReceiversType() {
        val src = "val f = FLine()\nf.color = 1\ncolor"
        assertEquals(16, SymbolAt.receiverStart(src, src.indexOf("color")))
        assertNull(SymbolAt.receiverStart(src, src.lastIndexOf("color")))
        assertEquals("FLine", SymbolAt.typeFromHover("f : FLine"))
        assertEquals("FLine", SymbolAt.typeFromHover("val f: afield.geometry.path.FLine?"))
        assertEquals("List", SymbolAt.typeFromHover("xs : List<Int>"))
        assertNull(SymbolAt.typeFromHover("no type here"))
    }

    @Test
    fun theHoverHtmlBecomesText() {
        assertEquals("fun color: Vec4 : FLine.() -> Vec4\nit's",
            SymbolAt.htmlToText("<span style='color:#ddd;'><code>fun color: Vec4</code> <span>: FLine.() -&gt; Vec4</span></span><br>it&#39;s"))
    }
}

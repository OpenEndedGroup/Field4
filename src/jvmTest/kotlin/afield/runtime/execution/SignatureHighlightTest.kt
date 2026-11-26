package afield.runtime.execution

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** The ambient badge colours a completion signature: name bright, parameter names italic, types warm. */
class SignatureHighlightTest {
    private val html = highlightKotlinSignatureHtml(
        "threeLines(lines: Collection<FLine>, onEditCommitted: ((List<FLine>) -> Unit)?, width: Int?)"
    )

    @Test
    fun theSymbolNameIsBoldAndParameterNamesAreItalic() {
        assertContains(html, "font-weight:600;'>threeLines<")
        assertContains(html, "font-style:italic;'>lines<")
        assertContains(html, "font-style:italic;'>onEditCommitted<")
        assertContains(html, "font-style:italic;'>width<")
    }

    @Test
    fun typesInsideAFunctionTypeAreTypesNotParameters() {
        assertContains(html, "color:#d9b97a;'>List<")
        assertContains(html, "color:#d9b97a;'>Unit<")
        assertFalse(html.contains("italic;'>List<"))
    }

    @Test
    fun aDeclarationKeepsItsKeywordAndBoldsTheName() {
        val decl = highlightKotlinSignatureHtml("val b: Banana2")
        assertContains(decl, "color:#c39ad8;'>val<")
        assertContains(decl, "font-weight:600;'>b<")
        assertContains(decl, "color:#d9b97a;'>Banana2<")
    }

    @Test
    fun aBareTypeSuffixHasNoName() {
        val type = highlightKotlinSignatureHtml("ThreeGeometryViewState", nameFirst = false)
        assertContains(type, "color:#d9b97a;'>ThreeGeometryViewState<")
    }
}

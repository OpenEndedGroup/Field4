package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Math in [aiChatMarkdownHtml] leaves as `<span class="ai-math">` for the browser to typeset (aiChatMath.js):
 * the TeX in `data-tex`, the source with its delimiters as the text. Math is literal the way a code span is,
 * and a dollar sign in ordinary prose stays a dollar sign.
 */
class AiChatMarkdownMathTest {

    private fun spans(html: String) =
        Regex("<span class=\"(ai-math[^\"]*)\" data-tex=\"([^\"]*)\">").findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()

    @Test
    fun `each delimiter is recognized as inline or display`() {
        val html = aiChatMarkdownHtml("Inline \\(a^2\\) and \$b^2\$, display \$\$c^2\$\$ and \\[d^2\\].")
        assertEquals(listOf(
            "ai-math" to "a^2",
            "ai-math" to "b^2",
            "ai-math ai-math-display" to "c^2",
            "ai-math ai-math-display" to "d^2",
        ), spans(html), html)
        assertTrue(">\\(a^2\\)</span>" in html, "the span keeps its source for anywhere that does not typeset: $html")
    }

    @Test
    fun `asterisks and underscores inside math are not emphasis`() {
        val html = aiChatMarkdownHtml("The form \\(a * x^2 * y\\) and *this* is emphasis.")
        assertEquals(listOf("ai-math" to "a * x^2 * y"), spans(html), html)
        assertTrue("<em>this</em>" in html, html)
        assertEquals(1, Regex("<em>").findAll(html).count(), html)
    }

    @Test
    fun `money and shell variables are not math`() {
        for (prose in listOf("It costs \$5 and \$10 later.", "Prices: \$ 5 or 6 \$", "Set \$HOME then run it.", "Escaped \\\$x\$ stays.")) {
            val html = aiChatMarkdownHtml(prose)
            assertFalse("ai-math" in html, "$prose -> $html")
        }
    }

    @Test
    fun `a dollar inside a code span is code`() {
        val html = aiChatMarkdownHtml("Use `\"\$x and \$y\"` in Kotlin, then \$z\$.")
        assertTrue("<code>&quot;\$x and \$y&quot;</code>" in html, html)
        assertEquals(listOf("ai-math" to "z"), spans(html), html)
    }

    @Test
    fun `display math over several lines joins into one equation and stays escaped`() {
        val html = aiChatMarkdownHtml("Where\n\n\$\$\n\\begin{aligned} Q &= a<b \\\\\nR &= \"c\" \\end{aligned}\n\$\$\n\nholds.")
        val (kind, tex) = spans(html).single()
        assertEquals("ai-math ai-math-display", kind)
        assertEquals("\\begin{aligned} Q &amp;= a&lt;b \\\\ R &amp;= &quot;c&quot; \\end{aligned}", tex)
        assertFalse("<b" in html.substringAfter("data-tex"), html)
    }

    @Test
    fun `math inside a list item and a table cell`() {
        val list = aiChatMarkdownHtml("- gap \\(p_{n+1} - p_n\\)\n- density \$\\rho\$")
        assertEquals(listOf("ai-math" to "p_{n+1} - p_n", "ai-math" to "\\rho"), spans(list), list)
        val table = aiChatMarkdownHtml("| map | value |\n| --- | --- |\n| inferno | \\(\\rho \\in [0,1]\\) |")
        assertEquals(listOf("ai-math" to "\\rho \\in [0,1]"), spans(table), table)
    }

    @Test
    fun `a math fence stays a code block for the fence renderer`() {
        val html = aiChatMarkdownHtml("```math\nx^2 + y^2 = 1\n```")
        assertTrue("data-lang=\"math\"" in html && "data-fence-closed=\"true\"" in html, html)
        assertFalse("ai-math" in html, html)
    }
}

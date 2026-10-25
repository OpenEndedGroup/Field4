package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Code spans in [aiChatMarkdownHtml] are literal. Box notes are full of code (`sin(r * 3.6)`,
 * `hsl(188 + y * 56)`), and two multiplications on one line used to become italics running between
 * them. Lists are [AiChatMarkdownListsTest]'s.
 */
class AiChatMarkdownCodeSpanTest {

    @Test
    fun `asterisks inside code spans are not emphasis`() {
        val html = aiChatMarkdownHtml("`3.6` in `sin(r * 3.6)` sets the ripples, `188` in `hsl(188 + y * 56)` the hue.")
        assertTrue("<code>sin(r * 3.6)</code>" in html, html)
        assertTrue("<code>hsl(188 + y * 56)</code>" in html, html)
        assertFalse("<em>" in html, html)
    }

    @Test
    fun `emphasis and strong still work between code spans`() {
        val html = aiChatMarkdownHtml("*breathing sheet* and **signal tear** animate; `t` is time.")
        assertTrue("<em>breathing sheet</em>" in html, html)
        assertTrue("<strong>signal tear</strong>" in html, html)
        assertTrue("<code>t</code>" in html, html)
    }

    @Test
    fun `a code span inside a list item is literal too`() {
        val html = aiChatMarkdownHtml("1. Change `sin(r * 3.6)` and\n   `hsl(188 + y * 56)`.")
        assertTrue("<ol>" in html && "<code>sin(r * 3.6)</code>" in html, html)
        assertFalse("<em>" in html, html)
    }
}

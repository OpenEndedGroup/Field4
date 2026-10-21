package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [aiChatMarkdownHtml] is the reader for box notes and the document guide as well as the AI4 chat, so a
 * writeup in a box's `notes` must come out as a person wrote it: numbered steps as a list, an item that
 * wraps over several lines as one item, and code spans left alone.
 */
class AiChatMarkdownTest {

    @Test
    fun `a numbered list is an ordered list, and a wrapped item stays one item`() {
        val html = aiChatMarkdownHtml(
            """
            1. **Run this box.** The terrain appears
               in its output.
            2. Change a number.
            """.trimIndent()
        )
        assertEquals(
            "<div class=\"ai-chat-markdown\"><ol><li><strong>Run this box.</strong> The terrain appears in its output.</li>" +
                "<li>Change a number.</li></ol></div>",
            html,
        )
    }

    @Test
    fun `a numbered list keeps its first number`() {
        assertTrue("<ol start=\"3\">" in aiChatMarkdownHtml("3. three\n4. four"))
    }

    @Test
    fun `a bullet that wraps is one item, not an item and a paragraph`() {
        val html = aiChatMarkdownHtml("- **Everything is triangles.** A surface and a box are\n  all loops.\n- next")
        assertEquals(
            "<div class=\"ai-chat-markdown\"><ul><li><strong>Everything is triangles.</strong> A surface and a box are all loops.</li>" +
                "<li>next</li></ul></div>",
            html,
        )
        assertFalse("<p>" in html)
    }

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
    fun `a list ends at a blank line and a paragraph follows`() {
        val html = aiChatMarkdownHtml("- one\n- two\n\nAfter the list.")
        assertTrue(html.endsWith("</ul><p>After the list.</p></div>"), html)
    }
}

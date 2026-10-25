package afield.ai.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lists in [aiChatMarkdownHtml], the renderer behind box notes and the AI chat panel. A numbered
 * "how to play" list in a starting point's notes came out as one run-on paragraph, and a bullet
 * that wrapped onto an indented line dropped its second half out of the list.
 */
class AiChatMarkdownListsTest {

    private fun html(markdown: String) = aiChatMarkdownHtml(markdown)

    @Test
    fun numberedItemsAreAnOrderedList() {
        val out = html("1. Launch **renderer**.\n2. Launch this box.\n3. Edit it.")
        assertTrue(out.contains("<ol><li>Launch <strong>renderer</strong>.</li><li>Launch this box.</li><li>Edit it.</li></ol>"), out)
    }

    @Test
    fun anIndentedLineContinuesTheItemAboveIt() {
        val out = html("- **The sort.** Splats blended\n  over each other.\n- Second.")
        assertTrue(out.contains("<ul><li><strong>The sort.</strong> Splats blended over each other.</li><li>Second.</li></ul>"), out)
        assertFalse(out.contains("<p>"), out)
    }

    @Test
    fun aParagraphAfterAListIsOutsideIt() {
        val out = html("- one\n- two\nAfter.")
        assertTrue(out.contains("<ul><li>one</li><li>two</li></ul><p>After.</p>"), out)
    }

    @Test
    fun aBulletListThenANumberedListAreTwoLists() {
        val out = html("- a\n1. b")
        assertTrue(out.contains("<ul><li>a</li></ul><ol><li>b</li></ol>"), out)
    }

    @Test
    fun plainParagraphsAreUnchanged() {
        val out = html("A line\nwrapped.\n\nAnother.")
        assertTrue(out.contains("<p>A line wrapped.</p><p>Another.</p>"), out)
        assertEquals(-1, out.indexOf("<li>"))
    }
}

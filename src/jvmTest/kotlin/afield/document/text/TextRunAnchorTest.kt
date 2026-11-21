package afield.document.text

import afield.document.graph.Box
import afield.document.persistence.SavingContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRunAnchorTest {
    private fun anchor(text: String, quote: String, start: Int = text.indexOf(quote)) =
        TextRunAnchor(1, "passage", "parent", "contents", start, start + quote.length, quote)

    private fun TextRunAnchor.rebase(before: String, after: String) = rebase(before, after, textMatches(before, after))

    @Test
    fun followsTypingBeforeAfterAndInsideWithStableIdentity() {
        val before = "Intro • selected words • end"
        val after = "New! Intro • selected extra words • end, too"
        val next = anchor(before, "selected words").rebase(before, after)
        assertEquals("passage", next.id)
        assertEquals("selected extra words", after.substring(next.start, next.end))
        assertEquals(next, TextRunAnchor.decode(TextRunAnchor.encode(next)))
    }

    @Test
    fun adjacentInsertsStayOutsideAndPartialDeletionShrinks() {
        val before = "abc wolves howl xyz"
        val after = "abc (wolves howl) xyz"
        val next = anchor(before, "wolves howl").rebase(before, after)
        assertEquals("wolves howl", next.excerpt)
        val shortened = "abc (howl) xyz"
        assertEquals("howl", next.rebase(after, shortened).excerpt)
    }

    @Test
    fun deletingOneOfTwoPassagesDoesNotJumpToTheOther() {
        val before = "first passage!\nother passage?"
        val next = anchor(before, "passage", before.lastIndexOf("passage")).rebase(before, "first passage!\nother ?")
        assertTrue(next.lost)
        assertEquals("passage", next.excerpt)
        assertEquals(next, next.rebase("first passage!\nother ?", before))
    }

    @Test
    fun replacingAnEntirePassageDoesNotClaimTheReplacement() {
        val before = "[abc] and more"
        val next = anchor(before, "abc").rebase(before, "[xyz] and more")
        assertTrue(next.lost)
        val indent = "x".repeat(600)
        val after = indent + before
        val shifted = anchor(before, "abc").rebase(before, after)
        assertFalse(shifted.lost)
        assertEquals(after.indexOf("abc"), shifted.start)
    }

    @Test
    fun largeEditsOnBothSidesKeepAUniqueUnchangedPassage() {
        val needle = "the named passage"
        val before = "a".repeat(400) + needle + "b".repeat(400)
        val after = "c".repeat(650) + needle + "d".repeat(700)
        val next = anchor(before, needle).rebase(before, after)
        assertFalse(next.lost)
        assertEquals(650, next.start)
        assertTrue(anchor(before, needle).rebase(before, after + needle).lost)
    }

    @Test
    fun graphAndLostAnchorSurviveTheDocumentSavePath() {
        val parent = Box(id = "parent")
        val child = Box(id = "linked-note")
        val before = "Here lies the passage."
        parent.setPropertyRaw("contents", before)
        parent.children.add(child)
        child.parents.add(parent)
        child.setPropertyRaw("contents", "My note")
        child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(anchor(before, "the passage")))
        parent.setPropertyRaw("contents", "Now: Here lies the long passage.")
        val live = TextRunAnchor.decode(child.getPropertyRaw(TEXT_RUN_ANCHOR))!!
        assertEquals("the long passage", live.excerpt)
        parent.setPropertyRaw("contents", "Now: Here lies .")
        val directory = Files.createTempDirectory("field-text-run").toFile()
        try {
            val context = SavingContext()
            context.save(directory.path, setOf(parent, child), inline = true)
            val restored = context.readEdnTransfers(directory.path).first { it.id == child.id }.properties
            val lost = TextRunAnchor.decode(restored[TEXT_RUN_ANCHOR])!!
            assertTrue(lost.lost)
            assertEquals(live.id, lost.id)
            assertEquals("My note", restored["contents"])
            assertTrue(child in parent.children)
        } finally { directory.deleteRecursively() }
    }
}

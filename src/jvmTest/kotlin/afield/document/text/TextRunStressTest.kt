package afield.document.text

import afield.document.graph.Box
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRunStressTest {
    @Test
    fun boundedDiffAgreesWithAnIndependentAlignmentOracle() {
        val random = Random(1337)
        repeat(1400) {
            fun text() = (1..random.nextInt(31)).map { "aa bb$#\n"[random.nextInt(8)] }.joinToString("")
            val before = text()
            val after = text()
            val diff = textMatches(before, after)
            assertFalse(diff.boundedOut)
            var oldAt = 0
            var newAt = 0
            for (run in diff.runs) {
                assertTrue(run.oldStart >= oldAt && run.newStart >= newAt && run.length > 0)
                oldAt = run.oldStart + run.length
                newAt = run.newStart + run.length
                assertEquals(before.substring(run.oldStart, oldAt), after.substring(run.newStart, newAt))
            }
            // This quadratic reference is deliberately independent of the production Myers walk.
            val table = Array(before.length + 1) { IntArray(after.length + 1) }
            for (a in before.indices) for (b in after.indices) {
                table[a + 1][b + 1] = if (before[a] == after[b]) table[a][b] + 1
                    else maxOf(table[a][b + 1], table[a + 1][b])
            }
            assertEquals(table.last().last(), diff.runs.sumOf { it.length }, "before=$before / after=$after")
        }
    }

    @Test
    fun aDifferentEmojiDoesNotKeepHalfOfADeletedPassageAlive() {
        val before = "Left 🍎 right"
        val after = "Left 🍏 right"
        val start = before.indexOf("🍎")
        val anchor = TextRunAnchor(1, "fruit", "parent", "contents", start, start + 2, "🍎")
        assertTrue(anchor.rebase(before, after, textMatches(before, after)).lost)
        val long = "a".repeat(119) + "🍎 retained"
        assertEquals("a".repeat(119), runExcerpt(long, 0, long.length))
    }

    @Test
    fun overlappingNotesAndEmbedsSurviveMultilineEditsIndependently() {
        val parent = Box(id = "parent")
        val note = Box(id = "note")
        val before = "Start\næon 🌱 passage\n終わり"
        parent.setPropertyRaw("contents", before.replace("\n", "\r\n"))
        val embedText = "æon 🌱 passage\n終わり"
        val noteText = "🌱 passage"
        fun anchor(id: String, quote: String) = TextRunAnchor(1, id, parent.id, "contents",
            before.indexOf(quote), before.indexOf(quote) + quote.length, quote)
        parent.children.add(note)
        note.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(anchor("note", noteText)))
        parent.writeTextRunSources(listOf(anchor("embed", embedText)))
        val middle = "Prefix\r\nStart\r\næon 🌱 new passage\r\n終わり\r\nSuffix"
        parent.setPropertyRaw("contents", middle)
        assertEquals("🌱 new passage", TextRunAnchor.decode(note.getPropertyRaw(TEXT_RUN_ANCHOR))?.excerpt)
        assertEquals("æon 🌱 new passage\n終わり", parent.textRunSources().single().excerpt)
        parent.setPropertyRaw("contents", middle.replace("🌱 new passage", ""))
        assertTrue(TextRunAnchor.decode(note.getPropertyRaw(TEXT_RUN_ANCHOR))!!.lost)
        assertFalse(parent.textRunSources().single().lost)
        assertEquals("æon \n終わり", parent.textRunSources().single().excerpt)
        assertTrue(note in parent.children)
    }

    @Test
    fun aLongSequenceOfOverlappingEditsDoesNotDriftOrReattachAfterDeletion() {
        val random = Random(7331)
        var left = "repeated outside "
        var middle = "repeated inside"
        var right = " repeated outside"
        fun value() = "$left‹$middle›$right"
        var before = value()
        var range = TextRunAnchor(1, "stable", "parent", "contents", left.length,
            left.length + middle.length + 2, "‹$middle›")
        fun edit(text: String): String {
            val at = random.nextInt(text.length + 1)
            val drop = random.nextInt(minOf(text.length - at, 4) + 1)
            val inserted = listOf("", " ", "a", "repeated", "🍎", "\n", "**")[random.nextInt(7)]
            // Keep the random splice out of a surrogate pair; an editor caret is not a half-emoji.
            fun boundary(i: Int) = i == 0 || i == text.length || !text[i].isLowSurrogate()
            return if (boundary(at) && boundary(at + drop)) text.replaceRange(at, at + drop, inserted) else text
        }
        repeat(300) { step ->
            if (step % 3 != 0) left = edit(left)
            if (step % 4 != 0) middle = edit(middle)
            if (step % 5 != 0) right = edit(right)
            val after = value()
            range = range.rebase(before, after, textMatches(before, after))
            assertFalse(range.lost, "step $step")
            assertEquals("stable", range.id)
            assertEquals(left.length to left.length + middle.length + 2, range.start to range.end, "step $step")
            assertEquals("‹$middle›", after.substring(range.start, range.end))
            before = after
        }
        val deleted = left + right
        val lost = range.rebase(before, deleted, textMatches(before, deleted))
        assertTrue(lost.lost)
        assertEquals(lost, lost.rebase(deleted, before, textMatches(deleted, before)))
    }
}

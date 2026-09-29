package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `chain_drawn_score.md` teaches two loops in its boxes — reading a score's events as notes, and
 * snapping those notes to a scale — and its published `.wav`s are rendered by
 * [DrawnScoreDocs.interpret] and [DrawnScoreDocs.pentatonic]. This holds the two copies equal, line
 * by line (indentation aside), so the audio on the page is made by the code on the page.
 */
class DrawnScorePageTest {
    private val page = File("public/docs/chain_drawn_score.md").readLines()
    private val source = File("src/jvmMain/kotlin/afield/four/synth/DrawnScoreDocs.kt").readLines().map { it.trim() }

    /** The page's lines from the one starting with [first] to the next line that is exactly `}`. */
    private fun block(first: String): List<String> {
        val start = page.indexOfFirst { it.startsWith(first) }
        assertTrue(start >= 0, "the page no longer has a line starting '$first'")
        val end = (start until page.size).first { page[it] == "}" }
        return page.subList(start, end + 1).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun assertInSource(block: List<String>, what: String) {
        val found = (0..source.size - block.size).any { i -> source.subList(i, i + block.size) == block }
        assertTrue(found, "$what on the page differs from DrawnScoreDocs.kt; the page's block is:\n${block.joinToString("\n")}")
    }

    @Test
    fun thePageReadsTheScoreWithTheLoopTheRendererRuns() =
        assertInSource(block("val notes = mutableListOf<TimedNote>()"), "the reading loop")

    @Test
    fun thePageSnapsToTheScaleWithTheLoopTheRendererRuns() =
        assertInSource(block("val minorPentatonic = "), "the pentatonic loop")
}

package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `chain_drawn_morph.md` shows the loop that turns a morph into notes, and its published `.wav`s
 * are rendered by [DrawnMorphDocs.morphNotes]. This holds the two copies equal line by line
 * (indentation aside), so the audio on the page is made by the code on the page.
 */
class DrawnMorphPageTest {
    @Test
    fun thePagePlaysTheMorphWithTheLoopTheRendererRuns() {
        val page = File("public/docs/chain_drawn_morph.md").readLines()
        val source = File("src/jvmMain/kotlin/afield/four/synth/DrawnMorphDocs.kt").readLines().map { it.trim() }
        val start = page.indexOfFirst { it.startsWith("val notes = mutableListOf<TimedNote>()") }
        assertTrue(start >= 0, "the page no longer shows the morph-to-notes loop")
        val end = (start until page.size).first { page[it] == "}" }
        val block = page.subList(start, end + 1).map { it.trim() }.filter { it.isNotEmpty() }
        val found = (0..source.size - block.size).any { i -> source.subList(i, i + block.size) == block }
        assertTrue(found, "the loop on the page differs from DrawnMorphDocs.morphNotes:\n${block.joinToString("\n")}")
    }
}

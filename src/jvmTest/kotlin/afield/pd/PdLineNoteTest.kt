package afield.pd

import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [pdLineNote]: a Pd box's line note split like every language's, with its results in bold. The
 * wiring and the not-playing case are read from the text alone; the live value needs libpd, and that
 * test is reported as skipped (not passed) when it is not built.
 */
class PdLineNoteTest {
    private val patch = """
        #N canvas 0 0 400 250 12;
        #X obj 20 20 r in;
        #X obj 20 60 + 1;
        #X obj 20 100 s out;
        #X obj 120 100 print out;
        #X connect 0 0 1 0;
        #X connect 1 0 2 0;
        #X connect 1 0 3 0;
    """.trimIndent()
    private val graph = PdPatchGraph.parse(patch)

    private fun valueText(note: afield.stdlib.LineNotes.Note) = note.value.joinToString("") { it.text }
    private fun strongOf(note: afield.stdlib.LineNotes.Note) = note.value.filter { it.strong }.map { it.text }

    @Test fun anObjectShowsItsWiringWithTheObjectsInBold() {
        val note = pdLineNote(graph, 2, null)!!
        assertEquals("fed by [r in] · feeds [s out], [print out]", valueText(note))
        assertEquals(listOf("[r in]", "[s out]", "[print out]"), strongOf(note))
        assertEquals("[+ 1] · object 1", note.meta)
        assertFalse(note.volatile, "wiring follows from the text alone")
        assertEquals(graph.describeLine(2), note.caption, "the caption stays the whole sentence")
    }

    @Test fun aConnectionNamesBothEnds() {
        val note = pdLineNote(graph, 6, null)!!
        assertEquals("[+ 1] outlet 0 → [s out] inlet 0", valueText(note))
        assertEquals(listOf("[+ 1]", "[s out]"), strongOf(note))
        assertEquals("a connection", note.meta)
    }

    @Test fun aSendOnAStoppedBoxSaysItIsNotPlaying() {
        val note = pdLineNote(graph, 3, null)!!
        assertEquals("nothing on 'out' yet", valueText(note))
        assertTrue(note.meta.contains("not playing"), note.meta)
        assertTrue(note.volatile, "what goes through a name changes while the text does not")
        assertNull(pdLineNote(graph, 0, null), "the canvas header says nothing")
    }

    @Test fun aSendOnAPlayingPatchShowsTheLastValueInBold() {
        Assume.assumeTrue("libpd is not built: ${PdLibrary.describe()}", PdLibrary.isAvailable)
        PdInstance().use { pd ->
            pd.openPatchText(patch)
            pd.receive("out") {}
            pd.send("in", 41)
            pd.flush()
            val note = pdLineNote(graph, 3, pd)!!
            assertEquals("'out' = 42.0", valueText(note))
            assertEquals(listOf("42.0"), strongOf(note))
            assertEquals("[s out] · the last message, live", note.meta)
            assertTrue(note.volatile)
        }
    }
}

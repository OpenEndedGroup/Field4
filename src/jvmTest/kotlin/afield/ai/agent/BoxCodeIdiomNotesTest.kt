package afield.ai.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two knight-tour slips (2026-11-06), as the notes a box write now carries, and what must stay quiet. */
class BoxCodeIdiomNotesTest {
    private val pointClass = """
        data class Point2(val x: Double, val y: Double) {
            operator fun plus(p: Point2) = Point2(x+p.x,y+p.y)
            fun rotated(r: Double) = Point2(x*cos(r)-y*sin(r),x*sin(r)+y*cos(r))
        }
    """.trimIndent()
    private val svgGrid = "val svg = buildString { append(\"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 832 928'>\") }\ndisplay(\"growth\", svg)"
    private val flineBox = "val tile = fline { moveTo(0, 0); lineTo(10, 0) }\nlines(tile)"

    @Test fun aHandMadePointClassIsNotedWithFieldsVector() {
        val note = BoxCodeIdiomNotes.notes(pointClass, emptySequence()).single()
        assertTrue("`Point2` is a hand-made 2D vector" in note && "vec(x, y)" in note && "quat(angle)" in note, note)
    }

    @Test fun aRecordWithTwoNumbersButNoArithmeticIsLeftAlone() {
        assertEquals(emptyList(), BoxCodeIdiomNotes.notes("data class Sample(val t: Double, val v: Double)", emptySequence()))
    }

    @Test fun svgIsNotedOnlyWhereTheDocumentAlreadyDrawsWithFLines() {
        assertTrue(BoxCodeIdiomNotes.notes(svgGrid, sequenceOf(flineBox)).single().contains("draw with FLines"))
        // An eval task that asks for an SVG, in a document with no FLines: nothing to say.
        assertEquals(emptyList(), BoxCodeIdiomNotes.notes(svgGrid, sequenceOf("display(\"main\", \"<svg width='300'></svg>\")")))
        // A box that draws with FLines and also emits a little SVG is its own business.
        assertEquals(emptyList(), BoxCodeIdiomNotes.notes(svgGrid + "\n" + flineBox, sequenceOf(flineBox)))
    }
}

package afield.rectsl.study

import afield.rectsl.compileRectSLWithSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor and the window pointing at each other ([RectSLWindowLinks]): which rectangles a line
 * outlines, and which line a click in the window lands on. No window is needed; the live path was
 * checked by hand against a running one.
 */
class RectSLWindowLinksTest {
    private val scene = """
        ->wall 16 9 meters
        ->duration 6
        ->background 0x101014

        # the gold square, turning
        "square" rect
          -3 0 ->xy
          2 ->square
          0xf0b040 solid ->fill
          '( 0=0 6=1.5707963 ) ->rot
        ;

        # a row of small blue squares
        "dot" rect 0.5 ->square 0x4080f0 solid ->fill
          6 '( fc 10 * ->x -2.5 ->y ) array ;

        "lamp" group
          "shade" rect 3 3 ->xy 1 0.6 ->wh 0xffffff solid ->fill ;
          "bulb" rect 3 2.6 ->xy 0.3 ->square 0xffee88 solid ->fill ;
        end
    """.trimIndent()

    private val compiled = compileRectSLWithSource(scene).also { assertTrue(it.ok, it.problems.joinToString { p -> p.summary }) }
    private val drawable = RectSLStudy.drawable(scene)
    private fun ids(line: Int) = RectSLWindowLinks.idsForLine(drawable, line)
    private fun lineOf(text: String) = scene.lines().indexOfFirst { text in it } + 1

    @Test
    fun aLineOutlinesWhatItMakesAndItsCopies() {
        assertEquals(setOf("square"), ids(lineOf("\"square\" rect")))
        val dots = ids(lineOf("\"dot\" rect"))
        assertEquals(6, dots.size, "the template's line outlines every dot the array made from it: $dots")
        assertEquals(dots, ids(lineOf("array ;")), "and so does the array's line")
        assertTrue(dots.all { RectSLWindowLinks.baseId(it) == "dot" }, "$dots")
    }

    @Test
    fun aGroupsLineOutlinesEveryPartAndAPartsLineOnlyThatPart() {
        assertEquals(setOf("shade", "bulb"), ids(lineOf("\"lamp\" group")))
        assertEquals(setOf("bulb"), ids(lineOf("\"bulb\" rect")))
    }

    @Test
    fun aCommentOutlinesTheStatementBelowAndAnEmptyLineAtTheEndNothing() {
        assertEquals(setOf("square"), ids(lineOf("# the gold square")))
        assertEquals(emptySet(), ids(lineOf("->duration 6")))
    }

    @Test
    fun theCursorLineComesFromMonacosViewState() {
        val state = """{"cursorState":[{"inSelectionMode":false,"selectionStart":{"lineNumber":7,"column":3},"position":{"lineNumber":9,"column":1}}],"viewState":{"scrollTop":0}}"""
        assertEquals(9, afield.stdlib.BoxStudies.lineOfViewState(state))
        assertNull(afield.stdlib.BoxStudies.lineOfViewState(null))
        assertNull(afield.stdlib.BoxStudies.lineOfViewState("not json"))
    }

    @Test
    fun aClickLandsOnTheTopmostRectangleUnderItAtThatTime() {
        val program = compiled.program!!
        // The window is 16:9 like the wall, so the click's fraction is the wall's: x -8..8 left to right, y 4.5..-4.5 top to bottom.
        val squareCentre = RectSLStudyShader.wallPoint(program, 1600, 900, 5.0 / 16, 0.5)
        assertEquals(-3.0, squareCentre.x, 1e-9); assertEquals(0.0, squareCentre.y, 1e-9)
        assertEquals("square", RectSLWindowLinks.hit(program, 0.0, squareCentre.x, squareCentre.y)?.id)
        // A corner of the unturned square, turned 45° at 3 s, is empty then.
        assertEquals("square", RectSLWindowLinks.hit(program, 0.0, -2.1, 0.9)?.id)
        assertNull(RectSLWindowLinks.hit(program, 3.0, -2.1, 0.9))
        // The bulb is drawn after the shade, over it.
        assertEquals("bulb", RectSLWindowLinks.hit(program, 0.0, 3.0, 2.72)?.id)
        assertNull(RectSLWindowLinks.hit(program, 0.0, 7.5, 4.0), "nothing but the background there")
    }

    @Test
    fun aClickInAWiderWindowMapsThroughTheBlackBars() {
        val program = compiled.program!!
        // A square window shows the whole 16-wide wall with bars above and below: its middle is still the wall's centre.
        val centre = RectSLStudyShader.wallPoint(program, 1000, 1000, 0.5, 0.5)
        assertEquals(0.0, centre.x, 1e-9); assertEquals(0.0, centre.y, 1e-9)
        val top = RectSLStudyShader.wallPoint(program, 1000, 1000, 0.5, 0.0)
        assertEquals(8.0, top.y, 1e-9, "the top of a square window is 8 above the centre, off the 9-high wall")
    }
}

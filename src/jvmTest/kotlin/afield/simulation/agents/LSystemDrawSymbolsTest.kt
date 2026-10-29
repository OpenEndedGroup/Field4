package afield.simulation.agents

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LSystemDrawSymbolsTest {
    @Test fun aSystemWithNoDrawingSymbolsSaysWhichLettersItUses() {
        val e = assertFailsWith<IllegalArgumentException> {
            drawLSystem(LSYSTEM_SIERPINSKI_ARROWHEAD, 4, TurtleParams(angle = PI / 3))
        }
        assertTrue("\"AB\"" in e.message!! && "drawSymbols" in e.message!!, e.message)
        assertTrue(drawLSystem(LSYSTEM_SIERPINSKI_ARROWHEAD, 4, TurtleParams(angle = PI / 3, drawSymbols = "AB")).drawnSegments > 0)
    }

    @Test fun aSystemThatHasNotGrownItsDrawingSymbolYetDrawsNothingQuietly() {
        val plant = lSystem("X", 'X' to "F[+X]F[-X]+X", 'F' to "FF")
        assertTrue(drawLSystem(plant, 0).drawnSegments == 0)
        assertTrue(drawLSystem(plant, 3).drawnSegments > 0)
    }
}

package afield.geometry.path

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringArtTest {

    private fun square(size: Double = 100.0): FLine =
        FLine().moveTo(0, 0).lineTo(size, 0).lineTo(size, size).lineTo(0, size).close()

    @Test
    fun pinsSitOnACircleAroundTheTarget() {
        val art = square().stringArt(strings = 0, pins = 40)
        assertEquals(40, art.pins.size)
        val radii = art.pins.map { hypot(it.x - 50.0, it.y - 50.0) }
        assertTrue(radii.all { it > 70.0 && it < 80.0 }, "pins on one circle outside the square: $radii")
        assertEquals(0, art.strings)
    }

    @Test
    fun theStringIsOneUnbrokenPathOfDistinctChords() {
        val art = square().stringArt(strings = 300, pins = 80)
        assertTrue(art.strings > 50, "a square outline should take many chords, got ${art.strings}")
        val chords = art.chords().map { (a, b) -> minOf(a, b) to maxOf(a, b) }
        assertEquals(chords.size, chords.toSet().size, "no chord is wound twice")
        val line = art.string()
        assertEquals(1, line.nodes.count { it is FLine.MoveTo })
        assertEquals(art.strings, line.nodes.count { it is FLine.LineTo })
        assertEquals(art.strings, art.chordLines().size, "one line per chord, so crossings composite")
        assertEquals(10, art.chordLines(upTo = 10).size)
    }

    @Test
    fun windsEveryStringAskedForUnlessToldToStop() {
        assertEquals(500, square().stringArt(strings = 500, pins = 100).strings)
        val stopped = square().stringArt(strings = 5000, pins = 60, stopWhenNoGain = true)
        assertTrue(stopped.strings < 5000, "stopWhenNoGain should end early, wound ${stopped.strings}")
    }

    @Test
    fun moreStringCoversMoreOfTheTarget() {
        val few = square().stringArt(strings = 20, pins = 120)
        val many = square().stringArt(strings = 400, pins = 120)
        assertTrue(many.remaining < few.remaining, "400 strings leave ${many.remaining}, 20 leave ${few.remaining}")
        assertTrue(many.remaining < 0.5, "a square's outline should be mostly covered, ${many.remaining} left")
    }

    @Test
    fun pinsFollowAnArbitraryFrame() {
        val frame = FLine().moveTo(-20, -20).lineTo(120, -20).lineTo(120, 120).lineTo(-20, 120).close()
        val art = square().stringArt(strings = 50, pins = 56, frame = frame)
        for (p in art.pins) {
            val onEdge = listOf(p.x + 20.0, p.x - 120.0, p.y + 20.0, p.y - 120.0).any { kotlin.math.abs(it) < 1e-3 }
            assertTrue(onEdge, "pin $p should lie on the frame")
        }
        // evenly spaced: 560 around, 10 apart
        val d = hypot(art.pins[1].x - art.pins[0].x.toDouble(), art.pins[1].y - art.pins[0].y.toDouble())
        assertEquals(10.0, d, 1e-3)
    }

    @Test
    fun filledTargetsWantChordsInside() {
        val outline = square().stringArt(strings = 200, pins = 100)
        val filled = square().stringArt(strings = 200, pins = 100, filled = true)
        // chord midpoints: a filled square pulls them into its middle, an outline toward its edges
        fun middling(a: StringArt) = a.chords().count { (i, j) ->
            val x = (a.pins[i].x + a.pins[j].x) / 2.0; val y = (a.pins[i].y + a.pins[j].y) / 2.0
            x in 25.0..75.0 && y in 25.0..75.0
        }
        assertTrue(middling(filled) > middling(outline), "filled ${middling(filled)} vs outline ${middling(outline)}")
    }
}

package afield

import afield.stdlib.__m
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [flipbox]: runs while ticked, and each evaluation unticks it. */
class FlipboxTest {
    @Test
    fun aBoxWithoutCodeKeepsThePropertyAbsentWhenRead() {
        val box = Box("flipbox-no-code")
        assertNull(box.code)
        assertNull(box.getPropertyRaw("code"), "reading absent code must not write an empty property")
    }

    @Test
    fun aTickedFlipboxRunsOnceThenStaysUnticked() {
        val box = Box("flipbox-true")
        var runs = 0
        assertTrue(box.__m("a").flipbox(true) { runs++ })
        assertFalse(box.__m("a").flipbox(true) { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun anUntickedFlipboxNeverRunsOnEvaluation() {
        val box = Box("flipbox-false")
        var runs = 0
        repeat(3) { box.__m("b").flipbox { runs++ } }
        assertEquals(0, runs)
    }

    @Test
    fun eachMarkerHasItsOwnTick() {
        val box = Box("flipbox-two")
        var runs = 0
        box.__m("c").flipbox(true) { runs++ }
        box.__m("d").flipbox(true) { runs++ }
        assertEquals(2, runs)
    }

    @Test
    fun theMarkerTextIsTheAnchor() {
        val box = Box("flipbox-anchor")
        box.code = "val x = 1\n  __m(\"q+/1\").flipbox(true) {\n}"
        val at = box.__m("q+/1").locationInSource(".flipbox(")!!
        assertEquals(2, at.line)
        assertEquals("  __m(\"q+/1\").flipbox(".length + 1, at.col)
        assertEquals(null, box.__m("absent").locationInSource())
    }
}

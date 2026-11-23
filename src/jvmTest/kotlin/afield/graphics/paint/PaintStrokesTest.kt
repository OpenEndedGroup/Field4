package afield.graphics.paint

import afield.geometry.path.FLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaintStrokesTest {

    private fun sheet() = PaintStrokes(200, aspect = 2.0).apply {
        add(listOf(100.0, 120.0, 400.0, 120.0), color = "#2a6a2a", width = 40.0, group = "leaves")
        add(listOf(600.0, 120.0, 900.0, 120.0), color = "#2a6a2a", width = 40.0, group = "leaves")
        add(listOf(100.0, 380.0, 900.0, 380.0), color = "#803020", width = 40.0)
    }

    @Test fun addressesAreStableAndNeverReused() {
        val s = sheet()
        assertEquals(listOf("s1", "s2", "s3"), s.strokes.map { it.address })
        s.apply("""{"op": "delete", "at": "s2"}""")
        assertEquals("s4", s.add(listOf(500.0, 250.0)))
        assertNull(s["s2"])
    }

    @Test fun selectorsNameStrokesGroupsAndAll() {
        val s = sheet()
        assertEquals(listOf("s1", "s2"), s.select("@leaves"))
        assertEquals(listOf("s3", "s1"), s.select("s3, s1"))
        assertEquals(3, s.select("*").size)
        assertFailsWith<IllegalArgumentException> { s.select("s99") }
    }

    @Test fun opsEditByAddressAndABatchIsOneUndoStep() {
        val s = sheet()
        val before = s.toJson()
        s.apply("""[{"op": "recolor", "at": "@leaves", "color": "#b5651d"},
                    {"op": "move", "at": "s3", "dx": 10, "dy": -20},
                    {"op": "bend", "at": "s1", "dx": 0, "dy": 50},
                    {"op": "reorder", "at": "s3", "to": 0}]""")
        assertEquals("#b5651d", s["s2"]!!.color)
        assertEquals(listOf(110.0, 360.0, 910.0, 360.0), s["s3"]!!.points)
        assertEquals("s3", s.strokes.first().address)
        assertEquals(listOf(100.0, 120.0, 400.0, 120.0), s["s1"]!!.points, "a two-point path keeps its ends when bent")
        assertTrue(s.undo())
        assertEquals(before, s.toJson())
        assertTrue(s.redo())
        assertEquals("#b5651d", s["s1"]!!.color)
    }

    @Test fun bendMovesTheMiddleAndKeepsTheEnds() {
        val s = PaintStrokes(200)
        s.add(listOf(0.0, 0.0, 50.0, 0.0, 100.0, 0.0))
        s.apply("""{"op": "bend", "at": "s1", "dy": 30}""")
        assertEquals(0.0, s["s1"]!!.points[1], 1e-9)
        assertEquals(30.0, s["s1"]!!.points[3], 1e-9)
        assertEquals(0.0, s["s1"]!!.points[5], 1e-9)
    }

    @Test fun aFailingOpLeavesTheSheetAsItWas() {
        val s = sheet()
        val before = s.toJson()
        val depth = s.undoDepth
        assertFailsWith<IllegalArgumentException> {
            s.apply("""[{"op": "recolor", "at": "s1", "color": "#ffffff"}, {"op": "recolor", "at": "s1", "color": "red"}]""")
        }
        assertEquals(before, s.toJson())
        assertEquals(depth, s.undoDepth)
    }

    @Test fun notesFindTheStrokesUnderThem() {
        val s = sheet()
        s.apply("""{"op": "pin", "x": 380, "y": 130, "text": "more moss on this side"}""")
        assertEquals("s1", s.near(380.0, 130.0).first())
        assertTrue(s.describe().contains("\"more moss on this side\" — strokes near it: s1"))
        s.undo()
        assertTrue(s.notes.isEmpty())
    }

    @Test fun jsonRoundTripsStrokesAndNotes() {
        val s = sheet()
        s.pin(1.0, 2.0, "hi")
        val again = PaintStrokes.fromJson(s.toJson(), 200, 2.0)
        assertEquals(s.strokes, again.strokes)
        assertEquals(s.notes, again.notes)
        assertEquals("s4", again.add(FLine().moveTo(0, 0).lineTo(100, 0)))
    }

    @Test fun theSameRecordsPaintTheSamePixels() {
        requirePaintLibrary()
        val a = sheet().render().use { it.render() }
        val b = sheet().render().use { it.render() }
        assertContentEquals(a, b)
    }

    @Test fun anEditChangesOnlyThePixelsItsStrokeTouches() {
        requirePaintLibrary()
        val s = sheet()
        val before = s.render().use { it.render() }
        s.apply("""{"op": "recolor", "at": "s3", "color": "#2040a0"}""")
        val after = s.render().use { it.render() }
        // the top half (y < 50 px of 100) holds only s1 and s2: unchanged to the bit
        for (i in 0 until 200 * 50) assertEquals(before[i], after[i], "pixel $i outside the recoloured stroke changed")
        assertFalse(before.contentEquals(after))
        s.undo()
        assertContentEquals(before, s.render().use { it.render() })
    }
}

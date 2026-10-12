package afield.paint

import afield.graphics.FLine
import afield.stdlib.Vec4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two canvases painted as a stereo pair: a stroke given the same seed on both carries the same noise
 * (the hand's shake, the bristles' wander, the knife's tears, a rag's creases, a pencil's tremor),
 * however many other strokes either canvas has painted first.
 */
class StereoTest {

    private fun available(): Boolean {
        if (!PaintLibrary.isAvailable) println("SKIPPED stereo tests: ${PaintLibrary.describe()}")
        return PaintLibrary.isAvailable
    }

    private val umber = Vec4(0.25f, 0.15f, 0.1f, 1f)
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double) = FLine().moveTo(x0, y0).lineTo(x1, y1)

    /** The pixels of the lower half of a 200 px canvas, where the stroke under test lies. */
    private fun lower(c: PaintCanvas): IntArray {
        val px = c.render(wet = true)
        val (w, h) = c.pixelSize
        return px.copyOfRange(w * (h / 2), w * h)
    }

    /**
     * [paint] on two fresh canvases, the first after an unrelated stroke in its upper half: the
     * lower halves, where [paint] lies.
     */
    private fun pair(paint: PaintCanvas.() -> Unit): Pair<IntArray, IntArray> {
        fun eye(extra: Boolean) = PaintCanvas(200, aspect = 1.0, groundColor = Vec4(0.95f, 0.94f, 0.9f, 1f), sizeMm = 100.0, seed = 3).use { c ->
            if (extra) {
                // something else first, far from the stroke under test: other brushes, other strokes
                c.brush(BrushKind.Filbert, 40.0).load(umber).let { b -> c.stroke(line(100.0, 150.0, 900.0, 150.0), b) }
                c.rag(40.0)
                c.stroke(line(100.0, 250.0, 900.0, 250.0), c.brush(BrushKind.Round, 30.0).load(umber))
            }
            c.paint()
            lower(c)
        }
        return eye(true) to eye(false)
    }

    @Test fun aSeededBrushStrokeIsTheSameWhateverCameBefore() {
        if (!available()) return
        val (a, b) = pair {
            val brush = brush(BrushKind.HogFlat, 80.0, seed = 11).load(umber, part = LoadPart(side = 1.0, share = 0.5, streak = 1.0, seed = 5))
            stroke(FLine().moveTo(150, 650).cubicTo(400, 550, 600, 800, 850, 700), brush, seed = 21)
            touch(brush, 300.0, 850.0, seed = 22)
        }
        assertContentEquals(a, b)
        // and without seeds the same calls differ: the canvas's own sequence has moved on
        val (c, d) = pair {
            stroke(FLine().moveTo(150, 650).cubicTo(400, 550, 600, 800, 850, 700), brush(BrushKind.HogFlat, 80.0).load(umber))
        }
        assertFalse(c.contentEquals(d), "unseeded strokes after different histories should differ")
    }

    @Test fun seededKnifeRagPencilAndMovingBrushCorrespondToo() {
        if (!available()) return
        val (a, b) = pair {
            val k = knife(70.0).load(umber, amount = 1.0)
            knife(line(150.0, 600.0, 850.0, 620.0), k, seed = 31)
            wipe(line(150.0, 700.0, 850.0, 700.0), rag(50.0, seed = 32), seed = 33)
            draw(line(150.0, 800.0, 850.0, 780.0), pencil("4B"), seed = 34)
            stroke(line(150.0, 900.0, 850.0, 900.0), dynamicBrush(BrushKind.Round, 40.0, hairs = 16, seed = 35).load(umber), seed = 36)
        }
        assertContentEquals(a, b)
    }

    @Test fun aSeededWatercolourStrokeCorresponds() {
        if (!available()) return
        fun eye(extra: Boolean) = PaintCanvas.paper(200, aspect = 1.0, paper = Paper.ColdPress, sizeMm = 100.0, seed = 3).use { c ->
            if (extra) c.wash(line(100.0, 150.0, 900.0, 150.0), c.waterBrush(40.0).load(Watercolors.ultramarine * 3.0))
            c.wash(line(150.0, 700.0, 850.0, 700.0), c.waterBrush(60.0).load(Watercolors.burntSienna * 3.0), seed = 41)
            lower(c)
        }
        val (a, b) = eye(true) to eye(false)
        // the water spreads across the sheet, so the strokes meet a little where the upper one's water
        // reaches: compare where the lower stroke alone lies
        val diff = a.indices.count { a[it] != b[it] }
        assertTrue(diff < a.size / 50, "$diff of ${a.size} pixels differ")
    }
}

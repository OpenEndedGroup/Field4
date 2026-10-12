package afield.paint

import afield.graphics.FLine
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import kotlin.math.abs
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

    /**
     * A stroke receding in depth, one world line (300 mm across and 300 deep) seen by two eyes: one
     * sees it evenly, the other foreshortened toward its far end, as perspective does. With its world
     * arc, the brush (and a brush of moving hairs) runs out at the same place along the line in both;
     * without it, where the canvas length says.
     */
    @Test fun aStrokeRecedingInDepthRunsOutAtTheSameWorldLengthInBothEyes() {
        if (!available()) return
        val world = FLine().moveTo(0.0, 0.0).lineTo(300.0, 0.0).also { l -> l.nodes.forEach { n -> n.storage[0] = Vec3(n.storage[0].x, 0f, n.storage[0].x) } }
        // where along the line, 0..1, each eye puts a world point
        val even: (Double) -> Double = { u -> u }
        val receding: (Double) -> Double = { u -> u / (u + 2.5 * (1 - u)) }
        fun proj(f: (Double) -> Double): (Vec3) -> Vec2 = { p -> Vec2((100 + 800 * f(p.x / 300.0)).toFloat(), 500f) }
        // the world length (mm) at which the paint along the stroke gives out
        fun runsOut(f: (Double) -> Double, worldArc: Boolean, moving: Boolean = false): Double = PaintCanvas(400, aspect = 1.0, groundColor = Vec4(0.95f, 0.94f, 0.9f, 1f), sizeMm = 300.0, seed = 3).use { c ->
            val g0 = c.gesture3d(world, proj(f), mmPerWorld = 1.0, speed = 150.0, pressure = listOf(if (moving) 0.6 else 0.8), tilt = 30.0)
            val g = if (worldArc) g0 else TimedGesture(g0.samples.map { it.copy(s = Double.NaN) })
            if (moving) c.stroke(g, c.dynamicBrush(BrushKind.HogFlat, 40.0, hairs = 24, config = BrushConfig(run = 60.0), seed = 7).load(umber, amount = 0.6), seed = 9)
            else c.stroke(g, c.brush(BrushKind.HogFlat, 40.0, config = BrushConfig(run = 60.0), seed = 7).load(umber, amount = 0.6), shake = 0.0, seed = 9)
            val px = c.render(wet = true)
            val (w, _) = c.pixelSize
            fun ink(x: Double): Double {
                val ix = (x / 1000 * w).toInt().coerceIn(0, w - 1); val iy = (500.0 / 1000 * w).toInt()
                return (-2..2).sumOf { dy -> 255 - ((px[(iy + dy) * w + ix] shr 8) and 0xff) } / 5.0
            }
            // the first world length past which the stroke stays pale
            val steps = (0..300).map { mm -> mm to ink(100 + 800 * f(mm / 300.0)) }
            val dark = steps.first().second
            steps.firstOrNull { (mm, v) -> mm > 20 && v < 0.3 * dark }?.first?.toDouble() ?: 300.0
        }
        val (a, b) = runsOut(even, true) to runsOut(receding, true)
        assertTrue(a in 30.0..270.0, "the brush runs out partway: at $a mm")
        assertTrue(abs(a - b) < 15.0, "with its world length the brush runs out at the same place in both eyes: $a vs $b mm")
        val (c, d) = runsOut(even, false) to runsOut(receding, false)
        assertTrue(abs(c - d) > 30.0, "without it, where each canvas's length says: $c vs $d mm")
        // and a brush of moving hairs
        val (e, g) = runsOut(even, true, moving = true) to runsOut(receding, true, moving = true)
        val (h, k) = runsOut(even, false, moving = true) to runsOut(receding, false, moving = true)
        assertTrue(e in 30.0..270.0, "the moving brush runs out partway: at $e mm")
        assertTrue(abs(e - g) < 20.0, "moving hairs with world length: $e vs $g mm")
        assertTrue(abs(h - k) > 30.0, "moving hairs without it: $h vs $k mm")
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

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
            sketch(gesture(line(150.0, 850.0, 850.0, 860.0), 120.0), pencil("2H"), passes = 5, wander = 3.0, seed = 37)
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

    /** A warp that moves every place [shift] canvas units across (a disparity the same everywhere). */
    private fun shifted(c: PaintCanvas, shift: Double) =
        c.warp(listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 1000.0, 1000.0 to 1000.0, 500.0 to 500.0).map { (x, y) -> StereoWarp.Pin(Vec2(x.toFloat(), y.toFloat()), Vec2((x + shift).toFloat(), y.toFloat())) }, frame = false)

    /** [a] and [b] (`w` px wide) where [b] is [a] moved [dx] px to the left, over [a]'s columns `from until to`. */
    private fun differing(a: IntArray, b: IntArray, w: Int, dx: Int, from: Int, to: Int): Int =
        (0 until a.size / w).sumOf { y -> (from until to).count { x -> a[y * w + x] != b[y * w + x - dx] } }

    @Test fun aWarpedGroundIsTheReferenceGroundMoved() {
        if (!available()) return
        // 200 px over 1000 units: 25 units is 5 px
        val (a, b) = listOf(false, true).map { warped ->
            PaintCanvas(200, aspect = 1.0, ground = PaintGround.Oil, sizeMm = 100.0, seed = 3).use { c ->
                if (warped) {
                    val w = shifted(c, 25.0)
                    assertTrue(abs(w.largest - 25.0) < 1e-3, "the sheet moves everything 25 units: ${w.largest}")
                }
                c.render(relief = 0.6)
            }
        }
        assertFalse(a.contentEquals(b), "the warped ground moved")
        assertTrue(differing(a, b, 200, 5, 10, 190) == 0, "the warped ground is the reference's, 5 px over")
    }

    @Test fun aPencilLineAndAWashThroughTheWarpFallOnTheSameGrainAndPaper() {
        if (!available()) return
        fun drawn(warped: Boolean) = PaintCanvas(200, aspect = 1.0, ground = PaintGround.Oil, sizeMm = 100.0, seed = 3).use { c ->
            val dx = if (warped) { shifted(c, 25.0); -25.0 } else 0.0
            c.draw(line(200.0 + dx, 300.0, 800.0 + dx, 700.0), c.pencil("4B"), seed = 51)
            c.render(relief = 0.6)
        }
        val (a, b) = drawn(false) to drawn(true)
        val d = differing(a, b, 200, 5, 10, 190)
        assertTrue(d < 200 * 180 / 200, "the pencil line through the warp is the reference's, moved: $d pixels differ")
        fun washed(warped: Boolean) = PaintCanvas.paper(200, aspect = 1.0, paper = Paper.ColdPress, sizeMm = 100.0, seed = 3).use { c ->
            val dx = if (warped) { shifted(c, 25.0); -25.0 } else 0.0
            c.wash(line(250.0 + dx, 500.0, 750.0 + dx, 500.0), c.waterBrush(60.0).load(Watercolors.burntSienna * 3.0), seed = 52)
            c.render()
        }
        val (e, f) = washed(false) to washed(true)
        val dw = differing(e, f, 200, 5, 10, 190)
        assertTrue(dw < 200 * 180 / 100, "the wash on the warped sheet is the reference's, moved: $dw pixels differ")
        // and without the warp the same moved line falls on different grain
        val g = PaintCanvas(200, aspect = 1.0, ground = PaintGround.Oil, sizeMm = 100.0, seed = 3).use { c ->
            c.draw(line(175.0, 300.0, 775.0, 700.0), c.pencil("4B"), seed = 51); c.render(relief = 0.6)
        }
        val dn = differing(a, g, 200, 5, 10, 190)
        println("pencil through the warp: $d px differ, without it: $dn; wash: $dw")
        assertTrue(dn > 4 * d, "without the warp the moved line lies on other grain: $dn vs $d")
    }
}

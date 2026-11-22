package afield.media.image

import afield.geometry.path.FLine
import afield.graphics.nile.Bezier
import afield.graphics.nile.FillRule
import afield.graphics.nile.Gezira
import afield.graphics.nile.GeziraStroke
import afield.graphics.render.circle
import afield.graphics.render.rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BufferCanvas.fill] and [BufferCanvas.stroke] of FLines: what lands in the buffer is the winding
 * rasteriser's coverage, in the canvas's channel, layout and coordinates, and a stroke covers its
 * own overlaps once.
 */
class BufferCanvasOutlineTest {

    @Test
    fun fillWritesExactCoverageIntoOneChannelOfAStridedBuffer() {
        val w = 40
        val h = 30
        val line = FLine().circle(18.3, 14.1, 9.7).rect(4.0, 3.0, 12.0, 9.0)
        // 2 channels, a padded row stride and an offset, cell 0.5 units starting at (10, 20).
        val stride = 2 * w + 6
        val mem = FloatArray(5 + stride * h)
        val canvas = BufferCanvas(w, h, 2, 5, stride, { mem[it] }, { i, v -> mem[i] = v })
        val world = FLine().circle(10 + 18.3 / 2, 20 + 14.1 / 2, 9.7 / 2).rect(12.0, 21.5, 6.0, 4.5)
        canvas.fill(world, 1f, FillRule.EVEN_ODD, channel = 1, x0 = 10.0, y0 = 20.0, cell = 0.5)
        val want = Gezira.rasterize(Gezira.beziers(line), w, h, FillRule.EVEN_ODD)
        var worst = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            worst = max(worst, abs(canvas[x, y, 1] - want[y * w + x]))
            assertEquals(0f, canvas[x, y, 0], "channel 0 must be untouched")
        }
        println("BufferCanvas.fill vs Gezira.rasterize, cell 0.5, even-odd: max |diff| $worst over ${want.count { it > 0 }} pixels")
        assertTrue(worst < 1e-5, "fill should write the rasteriser's coverage, worst $worst")
        assertEquals(0f, mem[0], "the offset must be respected")
    }

    @Test
    fun opacityAndValueBlendTowardTheTarget() {
        val mem = FloatArray(16 * 16) { 0.5f }
        val canvas = bufferCanvas(mem, width = 16)
        canvas.fill(FLine().rect(2.0, 2.0, 8.0, 8.0), 1f, opacity = 0.5)
        assertEquals(0.75f, canvas[5, 5], 1e-6f)
        assertEquals(0.5f, canvas[12, 12], 1e-6f)
        canvas.fill(FLine().rect(2.5, 2.0, 8.0, 8.0), 0f)
        assertEquals(0.375f, canvas[2, 5], 1e-6f) // half covered: 0.75 -> halfway to 0
    }

    /** A sharp V: the inner side of the bend overlaps itself, and must be covered once, as Skia does. */
    @Test
    fun strokeOfASharpBendMatchesSkia() {
        val w = 48
        val h = 40
        val v = FLine().moveTo(6.0, 6.0).lineTo(24.0, 34.0).lineTo(42.0, 6.0)
        val mem = FloatArray(w * h)
        bufferCanvas(mem, width = w).stroke(v, width = 3.0, value = 1f)
        val skia = GeziraStroke.skiaStrokeCoverage(Gezira.subpaths(v).single(), 3.0, w, h, closed = false)
        val got = DoubleArray(w * h) { mem[it].toDouble() }
        val (worst, mean) = Gezira.error(got, skia)
        println("stroke V vs Skia bevel/butt: max |diff| $worst, mean $mean over ${got.count { it > 0 }} pixels")
        assertTrue(worst < 0.1 && mean < 0.02, "stroke V off Skia: worst $worst mean $mean")
    }

    /**
     * A cubic that loops over itself, against the stroke as a set (sample points within `width / 2`
     * of the curve, 32 x 32 per pixel), away from the butt caps where that set is round instead.
     */
    @Test
    fun strokeOfASelfCrossingLoopMatchesTheTrueStroke() {
        val w = 48
        val h = 40
        val width = 3.0
        val loop = FLine().moveTo(8.0, 30.0)
        loop.cubicTo(30.0, 0.0, 44.0, 40.0, 20.0, 20.0)
        val mem = FloatArray(w * h)
        bufferCanvas(mem, width = w).stroke(loop, width = width, value = 1f)
        val b = Gezira.subpaths(loop).single().single()
        val pts = (0..1024).map { b.at(it / 1024.0, 0) to b.at(it / 1024.0, 1) }
        fun dist(x: Double, y: Double): Double {
            var best = Double.MAX_VALUE
            for (k in 0 until pts.size - 1) {
                val (ax, ay) = pts[k]
                val dx = pts[k + 1].first - ax
                val dy = pts[k + 1].second - ay
                val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                best = minOf(best, Math.hypot(x - ax - t * dx, y - ay - t * dy))
            }
            return best
        }
        var worst = 0.0
        var sum = 0.0
        var n = 0
        for (py in 0 until h) for (px in 0 until w) {
            if (Math.hypot(px + 0.5 - 8.0, py + 0.5 - 30.0) < 3 || Math.hypot(px + 0.5 - 20.0, py + 0.5 - 20.0) < 3) continue
            val centre = dist(px + 0.5, py + 0.5)
            val want = if (centre > width / 2 + 0.75) 0.0 else {
                var inside = 0
                for (j in 0 until 32) for (i in 0 until 32) if (dist(px + (i + 0.5) / 32, py + (j + 0.5) / 32) <= width / 2) inside++
                inside / 1024.0
            }
            val got = mem[py * w + px].toDouble()
            if (got == 0.0 && want == 0.0) continue
            worst = max(worst, abs(got - want))
            sum += abs(got - want)
            n++
        }
        println("stroke of a self-crossing loop vs true stroke (32 x 32 samples, caps excluded): max |diff| $worst, mean ${sum / n} over $n pixels")
        assertTrue(worst < 0.03, "the loop's stroke should be the true stroke, covered once where it crosses: worst $worst")
    }

    @Test
    fun subpathsKeepOpenPathsOpenAndCloseClosedOnes() {
        val open = Gezira.subpaths(FLine().moveTo(0.0, 0.0).lineTo(5.0, 0.0).lineTo(5.0, 5.0))
        assertEquals(listOf(2), open.map { it.size })
        val closed = Gezira.subpaths(FLine().rect(0.0, 0.0, 4.0, 4.0).moveTo(9.0, 9.0).lineTo(10.0, 10.0))
        assertEquals(listOf(4, 1), closed.map { it.size })
        val last: Bezier = closed[0].last()
        assertEquals(0.0, last.p[2])
        assertEquals(0.0, last.p[3])
    }
}

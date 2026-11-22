package afield.graphics.nile

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plain FLines through [GeziraFLine], painted by their own attributes, with exact pixel expectations. */
class GeziraFLineTest {

    private fun square(x0: Double, y0: Double, x1: Double, y1: Double) =
        FLine().moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).close()

    private fun px(c: Canvas, x: Int, y: Int) = c[x, y].let { listOf(it.r, it.g, it.b, it.a) }

    @Test
    fun fillColourAndOpacity() {
        val red = square(2.0, 2.0, 8.0, 8.0).also { it.filled = true; it.stroked = false; it.color = vec(1.0, 0.0, 0.0, 1.0) }
        val blue = square(5.0, 5.0, 10.0, 10.0).also { it.filled = true; it.stroked = false; it.color = vec(0.0, 0.0, 1.0, 0.5) }
        val r = GeziraFLine.render(listOf(red, blue), 12, 12)
        assertEquals(2, r.fills)
        assertEquals(0, r.strokes)
        assertEquals(listOf(1f, 0f, 0f, 1f), px(r.canvas, 3, 3))
        // Half-transparent blue over red, source-over.
        assertEquals(listOf(0.5f, 0f, 0.5f, 1f), px(r.canvas, 6, 6))
        assertEquals(listOf(0f, 0f, 0.5f, 0.5f), px(r.canvas, 9, 9))
        assertEquals(listOf(0f, 0f, 0f, 0f), px(r.canvas, 0, 0))
    }

    @Test
    fun strokeThicknessDefaultsToOneAndScalesWithTheTransform() {
        val line = FLine().moveTo(1.0, 5.0).lineTo(9.0, 5.0).also { it.color = vec(0.0, 1.0, 0.0, 1.0) }
        // Default: stroked, not filled, thickness 1 -> rows 4.5..5.5, half of rows 4 and 5.
        val thin = GeziraFLine.render(listOf(line), 10, 10).canvas
        assertEquals(0.5f, thin.alpha(5, 4), 1e-6f)
        assertEquals(0.5f, thin.alpha(5, 5), 1e-6f)
        assertEquals(0f, thin.alpha(5, 3))
        line.thickness = 2.0
        val thick = GeziraFLine.render(listOf(line), 10, 10).canvas
        assertEquals(1f, thick.alpha(5, 4), 1e-6f)
        assertEquals(1f, thick.alpha(5, 5), 1e-6f)
        // Scaled by 2: the line at y = 10, 4 wide -> rows 8..11 full.
        val scaled = GeziraFLine.render(listOf(line), 20, 20, SvgTransform.scale(2.0)).canvas
        for (y in 8..11) assertEquals(1f, scaled.alpha(10, y), 1e-6f, "row $y")
        assertEquals(0f, scaled.alpha(10, 7))
    }

    @Test
    fun fillRuleAttribute() {
        val ring = FLine().also { f ->
            f.moveTo(1.0, 1.0).lineTo(11.0, 1.0).lineTo(11.0, 11.0).lineTo(1.0, 11.0).close()
            f.moveTo(4.0, 4.0).lineTo(8.0, 4.0).lineTo(8.0, 8.0).lineTo(4.0, 8.0).close()
            f.filled = true
            f.stroked = false
            f.color = vec(1.0, 1.0, 1.0, 1.0)
        }
        assertEquals(1f, GeziraFLine.render(listOf(ring), 12, 12).canvas.alpha(6, 6), 1e-6f)
        ring.fillRule = FillRule.EVEN_ODD
        assertEquals(0f, GeziraFLine.render(listOf(ring), 12, 12).canvas.alpha(6, 6))
        assertEquals(1f, GeziraFLine.render(listOf(ring), 12, 12).canvas.alpha(2, 2), 1e-6f)
    }
}

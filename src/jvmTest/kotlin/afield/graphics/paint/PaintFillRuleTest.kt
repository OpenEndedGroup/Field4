package afield.graphics.paint

import afield.geometry.path.FLine
import afield.graphics.nile.FillRule
import afield.graphics.nile.GeziraFLine
import afield.graphics.nile.fillRule
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Paint areas honour the FLine `fillRule` attribute. Two overlapping squares in one line (winding 2
 * where they cross) are glazed opaque under each rule, and unset, and the painted mask is compared
 * pixel by pixel with Gezira's exact coverage of the same line, the reference every other FLine
 * filler is held to in `FillRuleConsumersTest`. Unset keeps the paint crate's old even-odd fill.
 */
class PaintFillRuleTest {

    private val white = Vec4(1f, 1f, 1f, 1f)
    private val blue = Vec4(0.05f, 0.1f, 0.6f, 1f)

    /** Two overlapping squares, coordinates scaled by [k] (1 = paint canvas units, 0.4 = pixels). */
    private fun overlapping(k: Double, rule: FillRule?) = FLine().apply {
        fun sq(x0: Double, y0: Double, x1: Double, y1: Double) =
            moveTo(x0 * k, y0 * k).lineTo(x1 * k, y0 * k).lineTo(x1 * k, y1 * k).lineTo(x0 * k, y1 * k).close()
        sq(150.0, 50.0, 550.0, 400.0)
        sq(400.0, 150.0, 850.0, 450.0)
        if (rule != null) fillRule = rule
    }

    private fun paintMask(rule: FillRule?): BooleanArray =
        PaintCanvas(400, aspect = 2.0, ground = PaintGround.Bare, groundColor = white).use { c ->
            c.glaze(blue, overlapping(1.0, rule), coats = 4.0, hiding = 1.0)
            c.render(wet = true).map { ((it shr 16) and 0xff) < 128 }.toBooleanArray()
        }

    private fun geziraMask(rule: FillRule): BooleanArray {
        val f = overlapping(0.4, rule).also { it.filled = true; it.stroked = false; it.color = vec(1.0, 1.0, 1.0, 1.0) }
        val canvas = GeziraFLine.render(listOf(f), 400, 200).canvas
        return BooleanArray(400 * 200) { canvas.alpha(it % 400, it / 400) > 0.5f }
    }

    private fun agree(rule: FillRule?, reference: FillRule) {
        val p = paintMask(rule)
        val g = geziraMask(reference)
        val differ = p.indices.count { p[it] != g[it] }
        val inside = g.count { it }
        // only edge pixels (anti-aliasing, the crate's 2-unit ring sampling) may disagree
        assertTrue(differ < inside / 50, "rule $rule: $differ of ${p.size} px differ from Gezira $reference ($inside inside)")
        // the contested overlap, (475, 275) units = (190, 110) px, decides the rule
        val at = 110 * 400 + 190
        assertTrue(p[at] == (reference == FillRule.NON_ZERO), "rule $rule at the overlap: painted=${p[at]}")
    }

    @Test fun nonZeroRegionPaintsTheOverlap() { requirePaintLibrary(); agree(FillRule.NON_ZERO, FillRule.NON_ZERO) }

    @Test fun evenOddRegionLeavesTheOverlapBare() { requirePaintLibrary(); agree(FillRule.EVEN_ODD, FillRule.EVEN_ODD) }

    @Test fun unsetRegionKeepsTheOldEvenOddFill() { requirePaintLibrary(); agree(null, FillRule.EVEN_ODD) }
}

package afield.graphics.nile

import afield.geometry.path.FLine
import afield.geometry.path.FLineFillRule
import afield.geometry.path.contains2D
import afield.geometry.path.java2d.FLinesAndJavaShapes
import afield.geometry.solid.manifold.Section
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.geometry.path.resolveFillRule
import afield.graphics.render.stroked
import afield.graphics.text.TextFillRule
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.media.image.BufferCanvas
import afield.ui.visualization.buildInteractiveSVG
import afield.ui.visualization.drawToSVG
import afield.ui.visualization.outputTo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.awt.geom.Area
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One fill rule, every FLine filler. Two figures whose fill depends on the rule — two nested squares
 * wound the same way, and two overlapping squares in one line (winding 2 where they cross) — are
 * filled under both rules by each consumer, and each consumer's verdict at a probe point inside the
 * contested region is compared with Gezira's exact coverage there, the reference.
 */
class FillRuleConsumersTest {

    private fun FLine.square(x0: Double, y0: Double, x1: Double, y1: Double) =
        moveTo(x0, y0).lineTo(x1, y0).lineTo(x1, y1).lineTo(x0, y1).close()

    /** Contested point (10, 10): inside both contours. Uncontested point (2.5, 2.5): inside one. */
    private fun nested() = FLine().square(1.0, 1.0, 19.0, 19.0).square(6.0, 6.0, 14.0, 14.0)
    private fun overlapping() = FLine().square(1.0, 1.0, 13.0, 13.0).square(7.0, 7.0, 19.0, 19.0)

    private val contested = 10 to 10
    private val single = 2 to 2

    private fun figures(rule: FillRule?) = listOf("nested" to nested(), "overlapping" to overlapping()).map { (name, f) ->
        f.filled = true
        f.stroked = false
        f.color = vec(1.0, 1.0, 1.0, 1.0)
        if (rule != null) f.fillRule = rule
        name to f
    }

    /** What each rule should fill at the contested pixel: non-zero yes, even-odd no. */
    private fun want(rule: FillRule) = rule == FillRule.NON_ZERO

    private fun gezira(f: FLine, x: Int, y: Int) = GeziraFLine.render(listOf(f), 20, 20).canvas.alpha(x, y) > 0.5f

    @Test
    fun geziraIsTheReference() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            assertEquals(want(rule), gezira(f, contested.first, contested.second), "$name $rule contested")
            assertTrue(gezira(f, single.first, single.second), "$name $rule single")
        }
    }

    @Test
    fun theTypeIsOne() {
        // Gezira's FillRule and typography's TextFillRule are FLineFillRule, and contains2D's old names still resolve.
        val a: FLineFillRule = FillRule.EVEN_ODD
        val b: FLineFillRule = TextFillRule.EVEN_ODD
        assertEquals(a, b)
        assertEquals(FLineFillRule.EVEN_ODD, FLineFillRule.EvenOdd)
        assertEquals(FLineFillRule.NON_ZERO, FLineFillRule.NonZero)
        assertEquals("evenodd", FLineFillRule.ofSvg("evenodd").svg)
        assertEquals(FLineFillRule.NON_ZERO, FLineFillRule.ofSvg(null))
    }

    @Test
    fun contains2DDefaultsToTheLinesRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            val c = Vec2(contested.first + 0.5f, contested.second + 0.5f)
            assertEquals(gezira(f, contested.first, contested.second), f.contains2D(c), "$name $rule")
        }
        // Unset: non-zero, as before.
        assertTrue(nested().contains2D(Vec2(10f, 10f)))
    }

    @Test
    fun skiaRasterHonoursTheRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32(20, 20, ColorAlphaType.PREMUL)) }
            SkCanvas(bmp).use { outputTo(f, it) }
            val alpha = bmp.getAlphaf(contested.first, contested.second)
            assertEquals(gezira(f, contested.first, contested.second), alpha > 0.5f, "$name $rule (alpha $alpha)")
        }
    }

    @Test
    fun svgOutputCarriesTheRule() {
        for (rule in FillRule.entries) for ((_, f) in figures(rule)) {
            val interactive = buildInteractiveSVG(listOf(f to f), 20.0, 20.0)
            val skiaSvg = drawToSVG(listOf(f), 20.0, 20.0)
            if (rule == FillRule.EVEN_ODD) {
                assertTrue("fill-rule=\"evenodd\"" in interactive, interactive)
                assertTrue("evenodd" in skiaSvg, skiaSvg)
            } else {
                // nonzero is SVG's default: written explicitly in the interactive renderer, never as evenodd.
                assertFalse("evenodd" in interactive, interactive)
                assertFalse("evenodd" in skiaSvg, skiaSvg)
            }
        }
        // An unset rule writes nothing: the bytes of every existing figure are unchanged.
        val (_, plain) = figures(null).first()
        assertFalse("fill-rule" in buildInteractiveSVG(listOf(plain to plain), 20.0, 20.0))
    }

    @Test
    fun java2dShapeHonoursTheRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            val area = Area(FLinesAndJavaShapes.flineToJavaShape_notThickened(f))
            assertEquals(gezira(f, contested.first, contested.second), area.contains(10.5, 10.5), "$name $rule")
        }
    }

    @Test
    fun vulkanTriangulationHonoursAnExplicitRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            val resolved = f.resolveFillRule()
            var hit = false
            resolved.tessellate { v, idx ->
                for (t in idx.indices step 3) {
                    if (inTriangle(10.5, 10.5, v, idx[t], idx[t + 1], idx[t + 2])) hit = true
                }
            }
            assertEquals(gezira(f, contested.first, contested.second), hit, "$name $rule")
        }
    }

    @Test
    fun bufferCanvasDefaultsToTheLinesRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            val mem = FloatArray(20 * 20)
            BufferCanvas(20, 20, 1, 0, 20, { mem[it] }, { i, v -> mem[i] = v }).fill(f, 1f)
            assertEquals(gezira(f, contested.first, contested.second), mem[contested.second * 20 + contested.first] > 0.5f, "$name $rule")
        }
    }

    @Test
    fun manifoldSectionTakesTheLinesRule() {
        for (rule in FillRule.entries) for ((name, f) in figures(rule)) {
            Section.ofFLines(listOf(f), 1.0, rule = f.fillRule).use { s ->
                // Both figures: union area minus, under even-odd, the contested overlap twice.
                val union = if (name == "nested") 18.0 * 18.0 else 2 * 12.0 * 12.0 - 6.0 * 6.0
                val overlap = if (name == "nested") 8.0 * 8.0 else 6.0 * 6.0
                val want = if (rule == FillRule.NON_ZERO) union else union - overlap
                assertEquals(want, s.area, 1e-6, "$name $rule")
            }
        }
    }

    private fun inTriangle(px: Double, py: Double, v: FloatArray, a: Int, b: Int, c: Int): Boolean {
        fun x(i: Int) = v[3 * i].toDouble()
        fun y(i: Int) = v[3 * i + 1].toDouble()
        fun s(i: Int, j: Int) = (x(j) - x(i)) * (py - y(i)) - (y(j) - y(i)) * (px - x(i))
        val d1 = s(a, b); val d2 = s(b, c); val d3 = s(c, a)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }
}

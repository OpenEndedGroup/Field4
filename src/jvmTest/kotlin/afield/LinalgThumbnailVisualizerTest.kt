package afield

import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [ValueVisualizer] renders the new Field linalg vectors & matrices as
 * their usual terse TEXT plus an appended, safe inline SVG thumbnail — an arrow for
 * vectors, a diverging heatmap grid for matrices — and that degenerate values (zero,
 * NaN) degrade gracefully rather than throwing. Mirrors [FnPlotVisualizerTest].
 */
class LinalgThumbnailVisualizerTest {

    private fun visualize(v: Any): String = ValueVisualizer.visualize(v)

    @Test
    fun `vec2 keeps its text and appends an arrow thumbnail`() {
        val html = visualize(Vector2f(1f, 2f))
        assertTrue("vec2" in html, "expected the vec2 text head")
        assertTrue("<svg" in html, "expected an appended SVG thumbnail")
        assertTrue("<polygon" in html, "expected an arrowhead")
        assertTrue("<line" in html, "expected faint axes / arrow shaft")
    }

    @Test
    fun `vec3 renders an isometric arrow with an xyz reference frame`() {
        val html = visualize(Vector3f(1f, 2f, 3f))
        assertTrue("vec3" in html, "expected the vec3 text head")
        assertTrue("<svg" in html, "expected an appended SVG thumbnail")
        assertTrue("<polygon" in html, "expected an arrowhead")
        // fixed-pixel reference frame carries x/y/z labels
        assertTrue(">x<" in html && ">y<" in html && ">z<" in html, "expected x/y/z axis labels")
    }

    /**
     * A vec4's panel has to account for all four components.
     *
     * This test used to assert only that *some* SVG appeared, which is how `formatVec4` came to draw
     * the `(x, y)` arrow and silently drop `z` and `w` — a 2D picture of a 4D value, with a passing
     * test over it. So the assertions are now about the four components specifically: a labelled bar
     * each, and the diverging sign colours the matrix grids already use.
     */
    @Test
    fun `vec4 draws a labelled signed bar for every component`() {
        val html = visualize(Vector4f(1f, 0f, -1f, 0.5f))
        assertTrue("vec4" in html, "expected the vec4 text head")
        assertTrue("<svg" in html, "expected an appended SVG thumbnail")
        listOf("x", "y", "z", "w").forEach {
            assertTrue(">$it<" in html, "expected a `$it` axis label — all four components are shown")
        }
        assertTrue(Regex("<rect").findAll(html).count() >= 4, "expected one bar per component")
        // 1f is positive and -1f negative at the same magnitude, so both ends of the ramp appear.
        assertTrue("#66ccff" in html, "expected the positive end of the diverging ramp for x = 1")
        assertTrue("#f88833" in html, "expected the negative end of the ramp for z = -1")
        assertFalse("<polygon" in html, "a vec4 is not an arrow; that was the bug")
    }

    /** Every component non-finite leaves the text alone rather than drawing an empty frame. */
    @Test
    fun `an all-NaN vec4 falls back to text only`() {
        val html = visualize(Vector4f(Float.NaN, Float.NaN, Float.NaN, Float.NaN))
        assertTrue("vec4" in html, "expected the vec4 text head")
        assertFalse("<svg" in html, "nothing to draw, so no panel")
    }

    /** One bad component still draws the others. */
    @Test
    fun `a partly NaN vec4 still draws the finite components`() {
        val html = visualize(Vector4f(2f, Float.NaN, -3f, 1f))
        assertTrue("<svg" in html, "expected a panel for the three finite components")
        assertTrue("#4a3030" in html, "expected the non-finite cell colour for y")
    }

    @Test
    fun `mat3 renders a colored cell grid`() {
        val m = Matrix3f().set(1f, 0.5f, -0.5f, 0.25f, -1f, 0.75f, 0f, 0.1f, 2f)
        val html = visualize(m)
        assertTrue("mat3" in html, "expected the mat3 text head with det")
        assertTrue("det" in html, "expected det in the text head")
        assertTrue("<svg" in html, "expected an appended heatmap grid")
        assertTrue("<rect" in html, "expected colored cells")
        // diverging palette: positive cells lean toward #6cf, negatives toward #f83
        assertTrue("fill='#" in html, "expected per-cell fills")
    }

    @Test
    fun `mat4 renders a 4x4 grid with per-cell numbers`() {
        val m = Matrix4f().translation(3f, 4f, 5f)
        val html = visualize(m)
        assertTrue("mat4" in html, "expected the mat4 text head")
        assertTrue("<svg" in html, "expected an appended heatmap grid")
        // 4x4 = 16 cells
        assertTrue(Regex("<rect").findAll(html).count() >= 16, "expected 16 heatmap cells")
        // every finite cell is labelled (like mat3), so all 16 cells carry a number
        assertTrue(Regex("<text").findAll(html).count() >= 16, "expected a number in each of the 16 mat4 cells")
        assertTrue(">3<" in html && ">4<" in html && ">5<" in html, "expected the translation values 3/4/5 in cells")
    }

    @Test
    fun `a zero vector keeps its text and draws an origin dot, never throws`() {
        val html = visualize(Vector2f(0f, 0f))
        assertTrue("vec2" in html, "expected the vec2 text head")
        assertTrue("<svg" in html, "zero vector still gets a (degenerate) thumbnail")
        assertTrue("<circle" in html, "expected an origin dot for the zero vector")
        assertFalse("<polygon" in html, "no arrowhead for a zero-magnitude vector")
    }

    @Test
    fun `a NaN vector shows the text but no thumbnail`() {
        val html = visualize(Vector3f(Float.NaN, 1f, 2f))
        assertTrue("vec3" in html, "text head should still render")
        assertFalse("<svg" in html, "no thumbnail for a non-finite vector")
    }

    @Test
    fun `an all-zero matrix renders neutral cells without throwing`() {
        val m = Matrix3f().zero()
        val html = visualize(m)
        assertTrue("mat3" in html, "expected the mat3 text head")
        assertTrue("<svg" in html && "<rect" in html, "all-zero matrix still draws neutral cells")
    }
}

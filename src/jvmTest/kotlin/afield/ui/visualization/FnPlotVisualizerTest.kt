package afield.ui.visualization

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.tools.docs.screenshot
import afield.ui.output.tree

import afield.ui.notation.with
import afield.math.functions.Functions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [ValueVisualizer] renders an [Functions.Fn] as its usual reflective
 * field tree PLUS an appended, safe SVG plot — and that pathological functions
 * degrade gracefully to the tree alone rather than throwing.
 */
class FnPlotVisualizerTest {

    private fun visualize(fn: Functions.Fn): String = ValueVisualizer.visualize(fn)

    @Test
    fun `sin over a finite domain renders the tree and a plot`() {
        // The exact function from the screenshot: sin(10) over 0..3.0
        val fn = with(Functions) { sin(10).over(0.0..3.0) }
        val html = visualize(fn)

        // reflective tree is preserved
        assertTrue("Fn" in html, "expected the Fn field tree")
        assertTrue("domain" in html, "expected the domain field in the tree")
        assertTrue("graph" in html, "expected the graph field in the tree")

        // a plot is appended
        assertTrue("<svg" in html, "expected an appended SVG plot")
        assertTrue("<path" in html, "expected a drawn curve path")
        // a sinusoid straddling zero should draw the dashed zero baseline
        assertTrue("stroke-dasharray" in html, "expected a zero baseline for a zero-straddling range")
    }

    @Test
    fun `parabola over a finite domain draws a curve`() {
        val fn = with(Functions) { (identity() * identity()).over(-2.0..2.0) }
        val html = visualize(fn)
        assertTrue("<svg" in html && "<path" in html, "expected a plotted parabola")
    }

    @Test
    fun `an all-non-finite function shows the tree but no plot`() {
        val nan = Functions.Fn(domain = Functions.domain(0.0..1.0)) { Double.NaN }
        val html = visualize(nan)
        assertTrue("Fn" in html, "tree should still render")
        assertFalse("<svg" in html, "no plot when nothing is finite")
    }

    @Test
    fun `a throwing evalFn never breaks the visualizer`() {
        val boom = Functions.Fn(domain = Functions.domain(0.0..1.0)) { x ->
            if (x > 0.5) throw RuntimeException("boom") else x
        }
        // must not throw; partial curve is fine
        val html = visualize(boom)
        assertTrue("Fn" in html, "tree should render despite the exception")
    }

    @Test
    fun `an unbounded oscillator falls back to a preview window and still plots`() {
        val fn = with(Functions) { sin(1) } // Domain.ALL
        val html = visualize(fn)
        assertTrue("<svg" in html && "<path" in html, "unbounded fn should preview over 0..1")
    }
}

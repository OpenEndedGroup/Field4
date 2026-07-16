package afield

import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [ValueVisualizer] appends a small, safe inline SVG sparkline to
 * numeric arrays and predominantly-numeric lists — and that degenerate inputs
 * (empty, single-element, all-NaN, non-numeric) degrade gracefully to no
 * sparkline rather than throwing.
 *
 * Mirrors the spirit of [FnPlotVisualizerTest]. It also writes a couple of the
 * emitted SVGs to `sparkline-proof/` so a human can eyeball the shapes.
 */
class ArraySparklineVisualizerTest {

    private fun viz(value: Any?): String = ValueVisualizer.visualize(value)

    private fun svgOf(html: String): String {
        val start = html.indexOf("<svg")
        if (start < 0) return ""
        val end = html.indexOf("</svg>", start)
        if (end < 0) return ""
        return html.substring(start, end + "</svg>".length)
    }

    @Test
    fun `a linear ramp DoubleArray draws a sparkline`() {
        val ramp = DoubleArray(64) { it.toDouble() }
        val html = viz(ramp)
        assertTrue("<svg" in html, "expected a sparkline for a ramp")
        assertTrue("<path" in html, "expected a drawn path")
        // ramp is entirely >= 0, so no zero baseline
        assertFalse("stroke-dasharray" in svgOf(html), "a non-negative ramp should not draw a zero baseline")
    }

    @Test
    fun `a sine DoubleArray straddling zero draws a sparkline with a zero baseline`() {
        val sine = DoubleArray(128) { sin(it / 128.0 * 2.0 * Math.PI) }
        val html = viz(sine)
        assertTrue("<svg" in html && "<path" in html, "expected a sine sparkline")
        assertTrue("stroke-dasharray" in svgOf(html), "a zero-straddling sine should draw a dashed zero baseline")
    }

    @Test
    fun `an all-NaN array shows no sparkline and does not throw`() {
        val nans = DoubleArray(32) { Double.NaN }
        val html = viz(nans)
        assertFalse("<svg" in html, "no sparkline when nothing is finite")
    }

    @Test
    fun `a single-element array shows no sparkline`() {
        val html = viz(doubleArrayOf(42.0))
        assertFalse("<svg" in html, "a single point is not a sparkline")
    }

    @Test
    fun `an empty array shows no sparkline and does not throw`() {
        assertFalse("<svg" in viz(DoubleArray(0)), "empty DoubleArray")
        assertFalse("<svg" in viz(IntArray(0)), "empty IntArray")
        assertFalse("<svg" in viz(FloatArray(0)), "empty FloatArray")
    }

    @Test
    fun `an IntArray and FloatArray both draw sparklines`() {
        assertTrue("<svg" in viz(IntArray(40) { it * it }), "IntArray sparkline")
        assertTrue("<svg" in viz(FloatArray(40) { it.toFloat() }), "FloatArray sparkline")
    }

    @Test
    fun `a mixed numeric List draws a sparkline`() {
        val mixed: List<Number> = listOf(1, 2.5, 3, 4L, 5.0f, 6.25, 7, 8.0)
        val html = viz(mixed)
        assertTrue("<svg" in html, "a predominantly-numeric list should get a sparkline")
    }

    @Test
    fun `a mostly non-numeric list shows no sparkline`() {
        val notNumbers = listOf("a", "b", "c", 1, 2)
        assertFalse("<svg" in viz(notNumbers), "not predominantly numeric -> no sparkline")
    }

    @Test
    fun `a huge array is downsampled and says so`() {
        val big = DoubleArray(5000) { sin(it / 50.0) }
        val html = viz(big)
        assertTrue("<svg" in html, "huge array still draws")
        assertTrue("downsampled" in html, "downsampling should be noted in the tooltip")
    }

    @Test
    fun `emit proof SVGs for a human to eyeball`() {
        val dir = File("sparkline-proof").apply { mkdirs() }
        File(dir, "ramp.svg").writeText(svgOf(viz(DoubleArray(64) { it.toDouble() })))
        File(dir, "sine.svg").writeText(svgOf(viz(DoubleArray(128) { sin(it / 128.0 * 2.0 * Math.PI) })))
        // an array with a NaN gap in the middle
        val gapped = DoubleArray(64) { if (it in 28..36) Double.NaN else sin(it / 10.0) }
        File(dir, "gapped.svg").writeText(svgOf(viz(gapped)))
        assertTrue(File(dir, "ramp.svg").readText().startsWith("<svg"), "proof SVGs were written")
    }
}

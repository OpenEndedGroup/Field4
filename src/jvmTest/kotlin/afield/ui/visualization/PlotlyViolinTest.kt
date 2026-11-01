package afield.ui.visualization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The named [violin] overload: one category per group, and one dashed reference per group's slot. */
class PlotlyViolinTest {
    /** Each group is its own named violin, and each reference spans only its own violin's slot. */
    @Test
    fun namedGroupsCarryPerGroupReferences() {
        val plot = violin(
            "8" to listOf(9.0, 10.0, 11.0),
            "12" to listOf(14.0, 15.0),
            "16" to listOf(19.0),
            references = listOf(8.0, Double.NaN, 16.0),
            yRange = 0.0..60.0,
            yTitle = "radius",
        )
        assertEquals(3, plot.data.size)
        // toHtml serialises the traces and the layout into its Plotly.react call.
        val layout = plot.toHtml().replace(" ", "").replace("\n", "")
        assertTrue("\"name\":\"12\"" in layout, layout)
        // Two shapes: the NaN reference is skipped.
        assertEquals(2, Regex("\"type\":\"line\"").findAll(layout).count(), layout)
        assertTrue("\"x0\":-0.45" in layout && "\"x0\":1.55" in layout, layout)
        assertTrue("\"y0\":16.0" in layout || "\"y0\":16" in layout, layout)
        assertTrue("radius" in layout, layout)
        assertTrue("\"showlegend\":false" in layout, layout)
    }

    /** A reference list that does not match the groups is refused rather than silently misaligned. */
    @Test
    fun mismatchedReferencesAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            violin("a" to listOf(1.0), "b" to listOf(2.0), references = listOf(1.0))
        }
    }
}

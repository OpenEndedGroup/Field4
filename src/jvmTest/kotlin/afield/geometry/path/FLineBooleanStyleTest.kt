package afield.geometry.path

import afield.graphics.render.thickness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FLineBooleanStyleTest {
    private fun square(x: Double, y: Double, s: Double) =
        FLine().moveTo(x, y, 0.0).lineTo(x + s, y, 0.0).lineTo(x + s, y + s, 0.0).lineTo(x, y + s, 0.0).close()

    @Test fun aStyledOperandIsReadAsItsFilledShapeUnlessStrokeIsAsked() {
        val a = square(0.0, 0.0, 100.0).apply { thickness = 4.0 }
        val b = square(50.0, 50.0, 100.0).apply { thickness = 4.0 }
        val filled = 100.0 * 100.0 * 2 - 50.0 * 50.0
        for (exact in listOf(false, true)) {
            val area = abs(a.byUnionWith(b, exact = exact).area2D().toDouble())
            assertTrue(abs(area - filled) < 1.0, "exact=$exact: union of two styled squares has area $area, not $filled")
        }
        // the stroke outline of two 4-wide rings is a different region altogether
        val rings = abs(a.byUnionWith(b, stroke = true).area2D().toDouble())
        assertTrue(abs(rings - filled) > 0.05 * filled, "stroke = true should read the stroke outline: $rings")
    }
}

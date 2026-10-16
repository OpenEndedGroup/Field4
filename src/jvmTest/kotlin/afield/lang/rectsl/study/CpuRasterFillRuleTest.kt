package afield.lang.rectsl.study

import afield.lang.rectsl.RectSLImager
import kotlin.test.Test
import kotlin.test.assertEquals

/** The CPU reference's half-open edge rule: a rectangle covers as many pixels as it is wide. */
class CpuRasterFillRuleTest {
    private fun covered(x: Double, y: Double): Pair<Int, Int> {
        val scene = """
            #rectsl
            ->wall 16 8 abstract
            ->background 0x000000
            ->duration 1
            "r" rect
              4 4 ->wh
              0xffffff solid ->fill
              $x ->x
              $y ->y
            ;
        """.trimIndent()
        val img = RectSLImager(scene).frame(0.0, 16, 8, linear = true)
        fun lit(col: Int, row: Int) = img.data[(row * 16 + col) * 3] > 0.5f
        val columns = (0 until 16).count { c -> (0 until 8).any { r -> lit(c, r) } }
        val rows = (0 until 8).count { r -> (0 until 16).any { c -> lit(c, r) } }
        return columns to rows
    }

    @Test
    fun `a four-unit rectangle covers four pixels wherever its edges land`() {
        for (offset in listOf(0.0, 0.25, 0.5, 0.75)) {
            assertEquals(4 to 4, covered(offset, offset), "rectangle at offset $offset")
        }
    }
}

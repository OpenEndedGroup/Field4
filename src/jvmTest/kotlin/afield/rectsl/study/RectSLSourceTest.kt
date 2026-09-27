package afield.rectsl.study

import afield.rectsl.compileRectSLWithSource
import afield.rectsl.rectSLKnobs
import afield.rectsl.setRectSLKnobs
import afield.stacksl.study.StackSLStudyShader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RectSL read as source — problems on their lines, rectangles traced to theirs, knobs — and the CPU reference's picture. */
class RectSLSourceTest {
    private val scene = """
        #rectsl
        ->wall 4 2 meters
        ->duration 4
        ->fps 20

        # the sun
        "sun" rect
          1 0.5 ->xy
          1 ->square
          0xff0000 solid ->fill
        ;

        "grey" rect
          -1 -0.5 ->xy
          1 ->square
          0x808080 solid ->fill
          '( 0 0   2 1   4 0 ) ->rot
        ;
    """.trimIndent()

    @Test
    fun aProblemIsReportedOnItsLine() {
        val broken = scene.replace("1 ->square\n  0x808080", "1 ->sqaure\n  0x808080")
        val compiled = compileRectSLWithSource(broken)
        val problem = compiled.problems.single()
        assertEquals(15, problem.line, problem.summary)
        assertTrue("sqaure" in problem.message, problem.message)
    }

    @Test
    fun anUnclosedQuoteIsReportedWhereItOpens() {
        val compiled = compileRectSLWithSource(scene.replace("'( 0 0   2 1   4 0 ) ->rot", "'( 0 0   2 1   4 0 ->rot"))
        assertEquals(17, compiled.problems.single().line, compiled.problems.single().summary)
    }

    @Test
    fun eachRectangleKnowsTheLineThatMadeIt() {
        val compiled = compileRectSLWithSource(scene)
        assertTrue(compiled.ok, compiled.problems.joinToString { it.summary })
        assertEquals(7, compiled.origins.getValue("sun").line)
        assertEquals(13, compiled.origins.getValue("grey").line)
    }

    @Test
    fun aClonedRectangleIsTracedToTheWordThatClonedIt() {
        val source = """
            ->wall 18 8 meters
            "bar" rect
              0 0 ->xy
              1 ->square
            ;
            3 '(
              "bar" ->select
                clone
                i 2 * ->x
            ) repeat
        """.trimIndent()
        val compiled = compileRectSLWithSource(source)
        assertTrue(compiled.ok, compiled.problems.joinToString { it.summary })
        val clones = compiled.origins.filterKeys { it != "bar" }
        assertEquals(3, clones.size, "${compiled.origins}")
        assertTrue(clones.values.all { it.line == 8 && it.statementLine == 10 }, "${clones.values}")
    }

    @Test
    fun knobsSayWhatReadsThem() {
        val knobs = rectSLKnobs(scene)
        fun role(text: String, line: Int) = knobs.first { it.text == text && it.line == line }.role
        assertEquals("->wall width", role("4", 2))
        assertEquals("->wall height", role("2", 2))
        assertEquals("->duration seconds", role("4", 3))
        assertEquals("->xy argument 1 of 2", role("1", 8))
        assertEquals("->xy argument 2 of 2", role("0.5", 8))
        assertEquals("->square", role("1", 9))
        assertEquals("#ff0000", knobs.first { it.text == "0xff0000" }.colour)
        assertEquals("keyframes of ->rot: value", role("1", 17))
        val body = rectSLKnobs("\"bar\" rect 1 ->square ;\n3 '( \"bar\" ->select clone  i 2 * ->x ) repeat")
        assertEquals("into ->x via * in the repeat body", body.first { it.text == "2" }.role)
        assertEquals("repeat", body.first { it.text == "3" }.role)
        val phase = rectSLKnobs("\"bar\" rect '( 0=0 6=6.28 ) linear sin ->y ;")
        assertEquals(listOf("keyframes before linear: time", "keyframes before linear: value", "keyframes before linear: time", "keyframes before linear: value"), phase.map { it.role })
        val eq = rectSLKnobs("linear-track \"rise\" '( 0=-2.6   3=0 )")
        assertEquals(listOf("keyframes of track rise: time", "keyframes of track rise: value", "keyframes of track rise: time", "keyframes of track rise: value"), eq.map { it.role })
        // A number a prefix word read out of the source is not also an argument of the next word
        // (the 30 is ->fps's, not the first of two colours pushed for ->background).
        val postfix = rectSLKnobs("->fps 30\n0x101014 ->background")
        assertEquals(listOf("->fps frames per second", "->background"), postfix.map { it.role })
    }

    @Test
    fun settingAKnobRewritesOnlyItsDigits() {
        val knobs = rectSLKnobs(scene)
        val x = knobs.first { it.line == 8 && it.text == "1" }
        val colour = knobs.first { it.text == "0xff0000" }
        val rewritten = setRectSLKnobs(scene, mapOf(x.knob to 1.25, colour.knob to 0x00ff88.toDouble()))
        assertTrue("  1.25 0.5 ->xy" in rewritten, rewritten)
        assertTrue("0x00ff88 solid" in rewritten, rewritten)
        assertEquals(scene.length + 3, rewritten.length)
    }

    @Test
    fun theCpuReferenceDrawsPlusYUpAndColoursAsTyped() {
        val drawable = RectSLStudy.drawable(scene)
        // 4 x 2 wall at 64 x 32: the sun (x 0.5..1.5, y 0..1) is right of centre, in the upper half.
        val raster = CpuRectSLRasterizer.renderAll(drawable, 64, 32, listOf(0.0)).single()
        fun at(x: Double, y: Double): FloatArray {
            val col = ((x + 2) / 4 * 64).toInt(); val row = ((1 - y) / 2 * 32).toInt()
            return FloatArray(3) { raster.rgba[(row * 64 + col) * 4 + it] }
        }
        assertEquals(1f, at(1.0, 0.5)[0], "the sun is red, up and to the right")
        assertEquals(0f, at(1.0, -0.5)[0], "below the sun is background")
        // 0x808080 is linear 0.2159, which the window encodes back to 128.
        val grey = at(-1.0, -0.5)
        assertTrue(abs(grey[0] - 0.2159f) < 0.002f, "grey is linear 0.216, got ${grey[0]}")
        val shown = StackSLStudyShader.presented(raster).toArgb()[((32 - 16 + 7) * 64 + 16)]
        assertEquals(128, (shown shr 16) and 0xff, "0x808080 shows as 128")
    }

    @Test
    fun aStackSLMaterialIsShadedOnTheCpu() {
        val source = """
            ->wall 2 2 meters
            material half '(
              uv x 0.5 * [1,1,1,0] * [0,0,0,1] +
            )
            "tile" rect
              0 0 ->xy
              2 ->square
              0xffffff solid ->fill
              half ->material
            ;
        """.trimIndent()
        val compiled = compileRectSLWithSource(source)
        assertTrue(compiled.ok, compiled.problems.joinToString { it.summary })
        val raster = CpuRectSLRasterizer.renderAll(RectSLStudy.drawable(source), 32, 32, listOf(0.0)).single()
        // The material's output is written as typed: half of uv.x, so the right edge shows about 0.46 × 255.
        val shown = StackSLStudyShader.presented(raster).toArgb()
        val left = (shown[16 * 32 + 2] shr 16) and 0xff; val right = (shown[16 * 32 + 29] shr 16) and 0xff
        assertTrue(left < 20 && right in 105..125, "the material ramps across the tile: left $left, right $right")
    }
}

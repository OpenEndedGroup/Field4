package afield.lang.solidsl

import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Profile.Fold] and the Koch snowflake built from it ([kochSnowflake]), against the naive six-copy
 * union ([kochSnowflakeNaive]): size linear vs exponential in the level, the same region, the same
 * numbers out of the emitted GLSL, and a unit gradient away from the medial axis.
 */
class SolidSLFoldTest {

    private val levels = 1..5

    @Test
    fun `the fold snowflake grows linearly in the level, the naive one by six`() {
        println("%5s %10s %10s %12s %12s".format("level", "fold nodes", "naive nodes", "fold glsl", "naive glsl"))
        val foldNodes = mutableListOf<Int>()
        val foldGlsl = mutableListOf<Int>()
        val naiveGlsl = mutableListOf<Int>()
        for (n in levels) {
            val f = kochSnowflake(n); val g = kochSnowflakeNaive(n)
            foldNodes += f.profileNodeCount(); foldGlsl += f.toGlsl2().length
            val gl = if (n <= 4) g.toGlsl2().length else -1
            naiveGlsl += gl
            println("%5d %10d %10d %12d %12d".format(n, f.profileNodeCount(), g.profileNodeCount(), foldGlsl.last(), gl))
            // Four folds, a union, a hexagram, a translate and a scale per level.
            assertEquals(1 + 8 * (n - 1), f.profileNodeCount(), "fold node count at level $n")
        }
        // Constant increments: every level adds the same number of nodes and the same GLSL text.
        val nodeSteps = foldNodes.zipWithNext { a, b -> b - a }.toSet()
        val glslSteps = foldGlsl.zipWithNext { a, b -> b - a }
        assertEquals(1, nodeSteps.size, "fold node increments $nodeSteps")
        assertTrue(glslSteps.max() - glslSteps.min() < glslSteps.min() / 10, "fold GLSL increments $glslSteps")
        // The naive tree multiplies by about six per level.
        for (n in 1..3) {
            val ratio = naiveGlsl[n].toDouble() / naiveGlsl[n - 1]
            assertTrue(ratio > 5.0, "naive GLSL ratio level ${n + 1}/$n was $ratio")
        }
        // Naive: 1 hexagram at level 1, then 1 union + 1 hexagram + 6 × (translate, scale, previous).
        assertEquals(6 * kochSnowflakeNaive(4).profileNodeCount() + 2 + 12, kochSnowflakeNaive(5).profileNodeCount())
    }

    @Test
    fun `fold and naive snowflakes are the same region`() {
        val random = Random(11L)
        for (n in 2..4) {
            val f = kochSnowflake(n).toField2(); val g = kochSnowflakeNaive(n).toField2()
            var disagreeSign = 0; var worstOutside = 0.0
            repeat(20_000) {
                val x = (random.nextDouble() - 0.5) * 2.6; val y = (random.nextDouble() - 0.5) * 2.6
                val a = f.at(x, y); val b = g.at(x, y)
                if (abs(a) > 1e-9 && abs(b) > 1e-9 && (a < 0) != (b < 0)) disagreeSign++
                if (b > 0) worstOutside = max(worstOutside, abs(a - b))
            }
            println("level $n: sign disagreements $disagreeSign, worst |fold - naive| outside $worstOutside")
            assertEquals(0, disagreeSign, "level $n inside/outside")
            assertTrue(worstOutside < 1e-9, "level $n: outside distances differ by $worstOutside")
        }
    }

    @Test
    fun `the emitted GLSL computes the Kotlin field and compiles`() {
        val random = Random(5L)
        for (n in levels) {
            val p = kochSnowflake(n)
            val src = p.toGlsl2("sdKoch")
            val program = GlslInterpreter.parse(src.replace("(vec2 p)", "(vec3 p)"), "sdKoch")
            val field = p.toField2()
            var worst = 0.0
            repeat(3000) {
                val x = (random.nextDouble() - 0.5) * 3.0; val y = (random.nextDouble() - 0.5) * 3.0
                worst = max(worst, abs(field.at(x, y) - program.eval(x, y, 0.0)))
            }
            println("level $n: worst |kotlin - glsl| $worst over 3000 samples")
            assertTrue(worst < 1e-12, "level $n differs by $worst")
            val frag = "#version 450\nlayout(location = 0) out vec4 o;\n$src\nvoid main() { o = vec4(sdKoch(gl_FragCoord.xy / 512.0 - 1.0)); }\n"
            val spirv = ShaderSPIRVUtils.compileShader("koch-$n.frag", frag, ShaderKind.FRAGMENT_SHADER)
            assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "level $n: no SPIR-V")
        }
    }

    @Test
    fun `the fold field has unit gradient away from the medial axis`() {
        val f = kochSnowflake(4).toField2()
        val g = kochSnowflakeNaive(4).toField2()
        val random = Random(7L)
        val h = 1e-5
        fun grad(field: Field2, x: Double, y: Double) = hypot(
            (field.at(x + h, y) - field.at(x - h, y)) / (2 * h),
            (field.at(x, y + h) - field.at(x, y - h)) / (2 * h),
        )
        val samples = mutableListOf<Double>()
        repeat(20_000) {
            val x = (random.nextDouble() - 0.5) * 3.0; val y = (random.nextDouble() - 0.5) * 3.0
            samples += abs(grad(f, x, y) - 1.0)
            // Outside, the union of exact fields is exact, so this is the oracle for the value.
            val a = f.at(x, y); val b = g.at(x, y)
            if (b > 0) assertTrue(abs(a - b) < 1e-9, "($x, $y): fold $a vs naive $b")
        }
        samples.sort()
        val within = samples.count { it < 1e-3 }.toDouble() / samples.size
        println("|∇d| within 1e-3 of 1 at ${"%.2f".format(100 * within)}% of 20000 samples; median error ${samples[samples.size / 2]}")
        // The misses are samples within h of a kink: the medial axis and the fold lines' seams.
        assertTrue(within > 0.99, "only $within of samples have a unit gradient")
    }
}

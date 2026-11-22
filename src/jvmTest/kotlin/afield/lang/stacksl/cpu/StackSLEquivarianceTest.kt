package afield.lang.stacksl.cpu

import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import afield.lang.stacksl.StackSLCompileOptions
import afield.lang.stacksl.StackSLInputAssumption
import afield.lang.stacksl.StackSLSemanticType.ComplexSpin
import afield.lang.stacksl.compileStackSL
import afield.math.autodiff.ADTape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackSLEquivarianceTest {
    private val program = """
        @types
        p assume-c1 >a
        ?pc.b assume-c1 >b
        ?pc.c assume-c1 >c
        <a dup * <b conj *
        <a conj <b * imag sin <c *
        +
    """.trimIndent()

    private val host = "layout(push_constant) uniform Parameters { vec2 b; vec2 c; } pc;"

    @Test
    fun emittedShadersCompileWithAndWithoutExternalizedConstants() {
        for (wires in listOf(false, true)) {
            val result = compileStackSL("$program 1 *", StackSLCompileOptions(externalizeConstants = wires))
            assertTrue(result.ok, result.diagnostics.joinToString("\n"))
            val glsl = """
                #version 450
                layout(location = 0) in vec4 inputColor;
                layout(location = 0) out vec4 outputColor;
                $host
                ${result.glsl}
                void main() { outputColor = stacksl(inputColor); }
            """.trimIndent()
            val spirv = ShaderSPIRVUtils.compileShader("stacksl-complex.frag", glsl, ShaderKind.FRAGMENT_SHADER)
            try {
                assertTrue(requireNotNull(spirv.bytecode()).hasRemaining())
            } finally {
                spirv.free()
            }
        }
    }

    @Test
    fun squaredMagnitudeHasItsAnalyticGradientAtAndNearZero() {
        val compiled = compileStackSL("@types assume-c1 norm2")
        assertTrue(compiled.ok, compiled.diagnostics.joinToString("\n"))
        val shader = parseGlslShader(compiled.glsl)
        for (magnitude in listOf(0.0, 1e-10, 1e-5)) {
            val tape = ADTape()
            val scalars = GlslAdScalars(tape)
            val x = scalars.variable(magnitude)
            val y = scalars.variable(-2.0 * magnitude)
            val input = AdValue(GlslType.VEC4, arrayOf(x, y, scalars.constant(3.0), scalars.constant(4.0)))
            val output = shader.newMachine(scalars).call("stacksl", listOf(input))
            val adjoints = tape.backward(output.c[0])
            assertEquals(2.0 * x.value, adjoints[x.index], 1e-15)
            assertEquals(2.0 * y.value, adjoints[y.index], 1e-15)
        }
    }

    @Test
    fun rotationLawAndJacobianAgreeWithTheReference() {
        val compiled = compileStackSL(program)
        assertTrue(compiled.ok, compiled.diagnostics.joinToString("\n"))
        val analysis = requireNotNull(compiled.analysis.semantics)
        assertTrue(analysis.equivarianceCertified, analysis.uncertifiedReasons.toString())
        assertEquals(ComplexSpin(1), analysis.outputType)
        assertEquals(listOf("p.xy", "?pc.b", "?pc.c").map { StackSLInputAssumption(it, ComplexSpin(1)) },
            analysis.assumptions)

        val shader = parseGlslShader("$host\n${compiled.glsl}")
        val random = Random(2781)
        val samples = listOf(
            DoubleArray(6),
            doubleArrayOf(0.25, -0.5, -0.5, 1.0, 0.125, -0.25), // collinear
            doubleArrayOf(2e-6, 3e-6, -1e-6, 0.0, 4e-6, -5e-6),
            doubleArrayOf(6.0, -4.0, -7.0, 6.0, 5.0, -7.0)
        ) + List(64) { DoubleArray(6) { random.nextDouble(-2.0, 2.0) } }

        for (x in samples) {
            val theta = random.nextDouble(-PI, PI)
            val rotatedX = DoubleArray(6)
            for (i in x.indices step 2) {
                val xy = rotate(x[i], x[i + 1], theta)
                rotatedX[i] = xy[0]
                rotatedX[i + 1] = xy[1]
            }

            val original = evaluate(shader, x)
            val rotated = evaluate(shader, rotatedX)
            val expected = reference(x)
            val expectedRotated = rotate(original[0].toDouble(), original[1].toDouble(), theta)
            for (row in 0..1) {
                assertClose(expected[row], original[row].toDouble(), "reference lane $row at ${x.toList()}")
                assertClose(expectedRotated[row], rotated[row].toDouble(), "rotation lane $row at ${x.toList()}")
            }

            val jacobian = jacobian(shader, x, original)
            val rotatedJacobian = jacobian(shader, rotatedX, rotated)
            for (col in x.indices) {
                val expectedColumn = rotate(jacobian[0][col], jacobian[1][col], theta)
                val offset = col - col % 2
                val (dx, dy) = if (col % 2 == 0) cos(theta) to sin(theta) else -sin(theta) to cos(theta)
                val h = 1e-5
                val lo = reference(x.copyOf().also { it[col] -= h })
                val hi = reference(x.copyOf().also { it[col] += h })
                for (row in 0..1) {
                    val actual = rotatedJacobian[row][offset] * dx + rotatedJacobian[row][offset + 1] * dy
                    assertClose(expectedColumn[row], actual, "rotated Jacobian [$row,$col] at ${x.toList()}")
                    assertClose((hi[row] - lo[row]) / (2 * h), jacobian[row][col],
                        "reference Jacobian [$row,$col] at ${x.toList()}")
                }
            }
        }
    }

    private fun evaluate(shader: GlslShader, x: DoubleArray): FloatArray {
        val input = GlslValue.vec4((0.5 + x[0]).toFloat(), (0.5 + x[1]).toFloat(), 5f, 7f)
        val machine = shader.newInvocation(mapOf(
            "pc.b" to GlslValue.vec2(x[2].toFloat(), x[3].toFloat()),
            "pc.c" to GlslValue.vec2(x[4].toFloat(), x[5].toFloat())
        ))
        return machine.call("stacksl", listOf(input)).c.also {
            assertEquals(0f, it[2])
            assertEquals(0f, it[3])
        }
    }

    private fun jacobian(shader: GlslShader, x: DoubleArray, primal: FloatArray): List<DoubleArray> {
        val tape = ADTape()
        val scalars = GlslAdScalars(tape)
        val seeds = Array(6) { i -> scalars.variable(x[i] + if (i < 2) 0.5 else 0.0) }
        val input = AdValue(GlslType.VEC4, arrayOf(seeds[0], seeds[1], scalars.constant(5.0), scalars.constant(7.0)))
        val machine = shader.newMachine(scalars, mapOf(
            "pc.b" to AdValue(GlslType.VEC2, arrayOf(seeds[2], seeds[3])),
            "pc.c" to AdValue(GlslType.VEC2, arrayOf(seeds[4], seeds[5]))
        ))
        val output = machine.call("stacksl", listOf(input))
        assertContentEquals(primal.map(Float::toRawBits), output.primal().c.map(Float::toRawBits))
        return (0..1).map { row ->
            val adjoints = tape.backward(output.c[row])
            DoubleArray(seeds.size) { col -> adjoints[seeds[col].index] }
        }
    }

    private fun reference(x: DoubleArray): DoubleArray {
        val (ax, ay, bx, by, cx) = x
        val cy = x[5]
        val xx = ax * ax - ay * ay
        val xy = 2.0 * ax * ay
        val phase = sin(ax * by - ay * bx)
        return doubleArrayOf(xx * bx + xy * by + cx * phase, xy * bx - xx * by + cy * phase)
    }

    private fun rotate(x: Double, y: Double, theta: Double): DoubleArray =
        doubleArrayOf(x * cos(theta) - y * sin(theta), x * sin(theta) + y * cos(theta))

    private fun assertClose(expected: Double, actual: Double, label: String) {
        val tolerance = 2e-4 + 8e-5 * abs(expected)
        assertTrue(abs(expected - actual) <= tolerance, "$label: expected $expected, got $actual (±$tolerance)")
    }
}

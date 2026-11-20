package afield.lang.stacksl.cpu

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GlslPartialEvaluator] against the interpreter it specializes: every residual must render to the
 * interpreter's **float bits**, through both of its back ends (the JVM register program and the
 * printed GLSL, read back by the same parser), across the whole StackSL gallery.
 */
class GlslPartialEvaluatorTest {

    private val root = File(System.getProperty("user.dir"))

    private val power = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;
        float power(float x, int n) {
            float r = 1.0;
            for (int i = 0; i < n; i++) r *= x;
            return r;
        }
        void main() { outColor = vec4(power(fragUV.x, 5), 0.0, 0.0, 1.0); }
    """.trimIndent()

    @Test
    fun `the power function specializes to four multiplies`() {
        val r = GlslPartialEvaluator.specialize(power)
        // fragUV.x once (shared), then x*x*x*x*x with the leading 1.0 * x folded away, then vec4().
        assertEquals(listOf("*" to 4, ".swizzle" to 1, "vec4()" to 1), r.operationHistogram())
        assertTrue("for" !in r.toGlsl() && "power" !in r.toGlsl(), r.toGlsl())
        assertSameBits(StackSLCpu.renderFragment(power, 16, 16), r.render(16, 16), "power")
    }

    @Test
    fun `a dynamic branch residualizes as a select`() {
        val src = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            void main() {
                float v = fragUV.x;
                if (v > 0.5 && fragUV.y < 0.3) { v = v * 2.0; } else { v = 1.0 - v; }
                outColor = vec4(v, v > 0.7 ? 1.0 : 0.0, 0.0, 1.0);
            }
        """.trimIndent()
        val r = GlslPartialEvaluator.specialize(src)
        assertTrue(r.operationHistogram().any { it.first == "select" }, r.toGlsl())
        val reference = StackSLCpu.renderFragment(src, 24, 24)
        assertSameBits(reference, r.render(24, 24), "branch/jvm")
        assertSameBits(reference, StackSLCpu.renderFragment(r.toGlsl(), 24, 24), "branch/glsl")
    }

    @Test
    fun `a dynamic loop bound is refused, not approximated`() {
        val src = power.replace("power(fragUV.x, 5)", "power(fragUV.x, int(fragUV.y * 8.0))")
        assertFailsWith<GlslUnsupported> { GlslPartialEvaluator.specialize(src) }
    }

    @Test
    fun `every gallery program's residual renders to the interpreter's bits`() {
        val entries = StackSLCpuGallery.load(root)
        assertTrue(entries.size >= 31, "gallery shrank to ${entries.size}")
        val failures = ArrayList<String>()
        for (e in entries) {
            val push = if (e.feedback || e.animate) floatArrayOf(0f, 0f, 0f, 0f) else null
            val samplers = if (e.feedback) StackSLCpuGallery.blackSampler else GlslSamplers.REFUSE
            try {
                val reference = StackSLCpuGallery.render(e, DIM)
                val residual = GlslPartialEvaluator.specializeStackSL(e.program, e.fragmentTemplate, push)
                val viaJvm = residual.render(DIM, DIM, samplers)
                val viaGlsl = StackSLCpu.renderFragment(residual.toGlsl(), DIM, DIM, samplers = samplers)
                if (!reference.rgba.bitsEqual(viaJvm.rgba)) failures += "${e.name}: JVM residual differs"
                if (!reference.rgba.bitsEqual(viaGlsl.rgba)) failures += "${e.name}: GLSL residual differs"
            } catch (t: RuntimeException) {
                failures += "${e.name}: ${t::class.simpleName}: ${t.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun FloatArray.bitsEqual(o: FloatArray) =
        size == o.size && indices.all { this[it].toRawBits() == o[it].toRawBits() }

    private fun assertSameBits(a: CpuRaster, b: CpuRaster, what: String) =
        assertTrue(a.rgba.bitsEqual(b.rgba), "$what: residual raster differs from the interpreter's")

    private companion object {
        const val DIM = 32
    }
}

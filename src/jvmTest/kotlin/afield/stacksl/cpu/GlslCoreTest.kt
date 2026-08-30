package afield.stacksl.cpu

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The GLSL semantics the CPU backend would have got wrong if they had been taken from habit.
 *
 * Every test here is a place where the nearest Kotlin equivalent of a GLSL operation is *not* the
 * GLSL operation, or where a plausible convention is the opposite of the real one. They are worth
 * having as unit tests rather than trusting the gallery comparison to catch them, for a specific
 * reason: the gallery comparison did **not** catch some of these. `mod` with a negative left
 * operand and `int` division never occur in the 31 committed programs, so an image-level
 * comparison is silent about them, and the first program that uses one would be quietly wrong
 * with nothing red.
 *
 * The ones the gallery *would* have caught are here anyway, because a whole-image comparison tells
 * you that something is wrong and this tells you what.
 */
class GlslCoreTest {

    /**
     * Evaluate one expression, by wrapping it in a function of the declared type and calling that.
     *
     * Deliberately not "assign it to `outColor` and read that back": packing a `vec2` or a `float`
     * into a `vec4` would put a constructor between the expression under test and the assertion,
     * and the constructor rules are themselves one of the things being tested here.
     */
    private fun evalExpr(expr: String, type: String = "vec4"): FloatArray {
        val source = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            $type probe() { return $expr; }
            void main() { outColor = vec4(0.0); }
        """.trimIndent()
        val invocation = parseGlslShader(source).newInvocation()
        invocation.setGlobal("fragUV", GlslValue.vec2(0.25f, 0.75f))
        return invocation.call("probe", emptyList()).c
    }

    private fun scalar(expr: String): Float = evalExpr(expr, "float")[0]

    // -----------------------------------------------------------------------------------------
    // Where Kotlin and GLSL disagree.
    // -----------------------------------------------------------------------------------------

    /**
     * `mod(x, y)` is `x - y * floor(x / y)`, so it takes the sign of `y`. Kotlin's `%` takes the
     * sign of `x`, which for `mod(-0.25, 1.0)` is the difference between 0.75 and -0.25 — a
     * whole period out of phase, not a rounding difference.
     */
    @Test
    fun `mod follows the sign of its right operand, unlike Kotlin's rem`() {
        assertEquals(0.75f, scalar("mod(-0.25, 1.0)"), 0f)
        assertEquals(0.25f, scalar("mod(1.25, 1.0)"), 0f)
        assertEquals(-0.75f, scalar("mod(0.25, -1.0)"), 0f)
        // The Kotlin answer, for the record, so the test says what it is guarding against.
        assertEquals(-0.25f, -0.25f % 1.0f, 0f)
    }

    /**
     * `mix(x, y, a)` is `x * (1 - a) + y * a`.
     *
     * The algebraically identical `x + a * (y - x)` is a different FLOATING-POINT expression, and
     * the difference is not academic: it is the entire residual between this evaluator and
     * `VolumetricNoiseReference` (see `StackSLCpuVolumetricCrossCheckTest`), about 1.8e-7 on
     * `noise3`. This test pins the spec's form by finding an input where the two disagree.
     */
    @Test
    fun `mix uses the spec's two-product form, not the one-product rewrite`() {
        // Find inputs where the two forms actually differ rather than hard-coding a triple and
        // hoping; if none of these differ, the test has stopped testing anything and says so.
        val candidates = listOf(
            Triple(0.1f, 0.9f, 0.3f), Triple(0.3f, 0.7f, 0.1f), Triple(0.21f, 0.87f, 0.37f),
            Triple(1f / 3f, 2f / 3f, 1f / 7f), Triple(0.4321f, 0.9876f, 0.6543f)
        )
        val differing = candidates.filter { (x, y, a) -> x * (1f - a) + y * a != x + a * (y - x) }
        assertTrue(differing.isNotEmpty(), "no candidate distinguishes the two forms of mix")
        differing.forEach { (x, y, a) ->
            val spec = x * (1f - a) + y * a
            assertEquals(spec, scalar("mix($x, $y, $a)"), 0f, "mix($x, $y, $a)")
        }
    }

    /** `int / int` truncates towards zero in GLSL; the float division is a different operator. */
    @Test
    fun `integer division truncates`() {
        assertEquals(2f, scalar("float(7 / 3)"), 0f)
        assertEquals(-2f, scalar("float(-7 / 3)"), 0f)
        assertEquals(7f / 3f, scalar("7.0 / 3.0"), 0f)
    }

    /** `fract` is `x - floor(x)`, so it is non-negative even for a negative argument. */
    @Test
    fun `fract of a negative number is positive`() {
        assertEquals(0.75f, scalar("fract(-0.25)"), 0f)
        assertEquals(0.25f, scalar("fract(1234.25)"), 0f)
    }

    // -----------------------------------------------------------------------------------------
    // Conventions where the plausible guess is the wrong one.
    // -----------------------------------------------------------------------------------------

    /**
     * `mat2(a, b, c, d)` is COLUMNS `(a,b)` and `(c,d)`, so `m * v` is
     * `(a*v.x + c*v.y, b*v.x + d*v.y)`.
     *
     * Reading it as rows transposes every `mat2` warp inside `_fbm` and every `mat3` inside the
     * OkLab helpers. Because the `_fbm` warp matrix is `mat2(1.6, 1.2, -1.2, 1.6)` — a rotation
     * times a scale, and therefore *almost* symmetric under transposition — the resulting image is
     * plausible rather than obviously broken, which is why this is a unit test and not left to a
     * glance at a contact sheet.
     */
    @Test
    fun `matrix constructors and products are column-major`() {
        val v = evalExpr("mat2(1.0, 2.0, 3.0, 4.0) * vec2(10.0, 100.0)", "vec2")
        assertEquals(1f * 10f + 3f * 100f, v[0], 0f)
        assertEquals(2f * 10f + 4f * 100f, v[1], 0f)

        // The `_fbm` warp, spelled out, because this is the one that matters.
        val warped = evalExpr("mat2(1.6, 1.2, -1.2, 1.6) * vec2(1.0, 0.0)", "vec2")
        assertEquals(1.6f, warped[0], 0f)
        assertEquals(1.2f, warped[1], 0f)
    }

    /**
     * `mix(x, y, bvec)` SELECTS. It is not an interpolation with the booleans coerced to 0 and 1
     * — which would give the same answer here, so the test uses operands where a component-wise
     * interpolation would blend and a select cannot.
     *
     * The emitter depends on this in `_safeDiv`:
     * `a / mix(vec4(1e-5), b, greaterThan(abs(b), vec4(1e-5)))`.
     */
    @Test
    fun `mix with a bool selector selects rather than interpolates`() {
        val v = evalExpr("mix(vec4(10.0), vec4(20.0), greaterThan(vec4(1.0, -1.0, 1.0, -1.0), vec4(0.0)))")
        assertEquals(listOf(20f, 10f, 20f, 10f), v.toList())
    }

    /**
     * A single scalar argument BROADCASTS; several arguments FLATTEN in order.
     * `vec4(program_in.xy, 0.0, 1.0)` is the emitter's most common line and relies entirely on
     * the second rule.
     */
    @Test
    fun `vector constructors broadcast one scalar and flatten several arguments`() {
        assertEquals(listOf(3f, 3f, 3f, 3f), evalExpr("vec4(3.0)").toList())
        assertEquals(listOf(0.25f, 0.75f, 0f, 1f), evalExpr("vec4(fragUV.xy, 0.0, 1.0)").toList())
        assertEquals(listOf(1f, 2f, 3f, 4f), evalExpr("vec4(vec2(1.0, 2.0), vec2(3.0, 4.0))").toList())
    }

    /** `mat3(1.0)` is the identity scaled, not a matrix full of ones. */
    @Test
    fun `a scalar matrix constructor fills the diagonal`() {
        assertEquals(listOf(2f, 2f, 2f), evalExpr("mat3(2.0) * vec3(1.0, 1.0, 1.0)", "vec3").toList())
        assertEquals(listOf(2f, 0f, 0f, 2f), evalExpr("mat2(2.0)", "mat2").toList())
    }

    /** `rgba` and `stpq` are the same components as `xyzw`; mixing sets in one selector is not. */
    @Test
    fun `swizzles accept three component sets but refuse a mixed one`() {
        assertEquals(listOf(0.75f, 0.25f), evalExpr("fragUV.yx", "vec2").toList())
        assertEquals(0.25f, scalar("vec4(0.25, 0.5, 0.75, 1.0).r"), 0f)
        assertEquals(0.5f, scalar("vec4(0.25, 0.5, 0.75, 1.0).t"), 0f)
        assertFailsWith<GlslEvalError> { evalExpr("vec4(1.0).xr", "vec2") }
    }

    // -----------------------------------------------------------------------------------------
    // Statements, control flow and the emitter's own idioms.
    // -----------------------------------------------------------------------------------------

    /** A `for` loop, a compound assignment and a matrix local: `_fbm`'s exact shape. */
    @Test
    fun `the fbm octave loop runs`() {
        val source = """
            #version 450
            layout(location = 0) out vec4 outColor;
            float sumOctaves(float seed) {
                float value = 0.0;
                float amplitude = 0.5;
                mat2 warp = mat2(1.6, 1.2, -1.2, 1.6);
                vec2 p = vec2(seed, seed);
                for (int i = 0; i < 5; i++) {
                    value += amplitude * p.x;
                    p = warp * p + 17.17;
                    amplitude *= 0.5;
                }
                return value;
            }
            void main() { outColor = vec4(sumOctaves(1.0)); }
        """.trimIndent()
        val invocation = parseGlslShader(source).newInvocation()
        invocation.runMain()
        // Recomputed here in Kotlin, in the same order, so the assertion is the arithmetic and
        // not a number copied out of a previous run.
        var value = 0f
        var amplitude = 0.5f
        var px = 1f
        var py = 1f
        repeat(5) {
            value += amplitude * px
            val nx = 1.6f * px + -1.2f * py + 17.17f
            val ny = 1.2f * px + 1.6f * py + 17.17f
            px = nx
            py = ny
            amplitude *= 0.5f
        }
        assertEquals(value, invocation.global("outColor")!!.c[0], 0f)
    }

    /** `&&` must not evaluate its right operand when the left is false. */
    @Test
    fun `logical and short-circuits`() {
        // `1.0 / 0.0` is an infinity, not a throw, so the observable is the RESULT of the guard:
        // a non-short-circuiting `&&` would make this true.
        assertEquals(0f, scalar("float(false && (1.0 / 0.0) > 0.0)"), 0f)
        assertEquals(1f, scalar("float(true || (1.0 / 0.0) > 0.0)"), 0f)
    }

    /** An inner block shadows an outer declaration and the outer one comes back. */
    @Test
    fun `block scoping shadows and restores`() {
        val source = """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
                float x = 1.0;
                if (true) { float x = 2.0; outColor = vec4(x); }
                outColor.y = x;
            }
        """.trimIndent()
        val invocation = parseGlslShader(source).newInvocation()
        invocation.runMain()
        assertEquals(2f, invocation.global("outColor")!!.c[0], 0f)
        assertEquals(1f, invocation.global("outColor")!!.c[1], 0f)
    }

    /** Writing through a swizzle writes only those components. */
    @Test
    fun `swizzle assignment is a partial write`() {
        val source = """
            #version 450
            layout(location = 0) out vec4 outColor;
            void main() {
                outColor = vec4(1.0, 2.0, 3.0, 4.0);
                outColor.zx = vec2(30.0, 10.0);
            }
        """.trimIndent()
        val invocation = parseGlslShader(source).newInvocation()
        invocation.runMain()
        assertEquals(listOf(10f, 2f, 30f, 4f), invocation.global("outColor")!!.c.toList())
    }

    // -----------------------------------------------------------------------------------------
    // The integer bit words, and the bound that makes storing them as Float safe.
    // -----------------------------------------------------------------------------------------

    /**
     * `uvec4` components are stored as `Float`, which is exact only below 2^24. Every `uvec4` the
     * StackSL emitter produces is masked to 16 bits by `toBits16`, so the bound holds — and an
     * operand outside it is refused rather than silently losing its low bits.
     */
    @Test
    fun `uvec bit operators are exact inside 16 bits and refuse beyond 24`() {
        assertEquals(61680f, scalar("float((uvec4(65535) & uvec4(61680)).x)"), 0f)
        assertEquals(43690f ,scalar("float((uvec4(65535) ^ uvec4(21845)).x)"), 0f)
        assertEquals(32767f, scalar("float((uvec4(65535) >> uvec4(1)).x)"), 0f)
        assertEquals(2f, scalar("float((uvec4(1) << uvec4(1)).x)"), 0f)

        // 2^25 is representable but its neighbours are not, so a bit operation there would be a
        // lie; the interpreter says so instead of producing a number.
        val tooBig = assertFailsWith<GlslEvalError> { scalar("float((uvec4(33554432) ^ uvec4(1)).x)") }
        assertTrue("lose low bits" in tooBig.message!!, tooBig.message!!)
    }

    /** Float-to-int conversion truncates towards zero. */
    @Test
    fun `integer constructors truncate rather than round`() {
        assertEquals(2f, scalar("float(uvec4(2.7).x)"), 0f)
        assertEquals(3f, scalar("float(uvec4(round(2.7)).x)"), 0f)
    }

    // -----------------------------------------------------------------------------------------
    // Quantisation, which is where a correct render becomes a wrong PNG.
    // -----------------------------------------------------------------------------------------

    /**
     * `VK_FORMAT_R8G8B8A8_UNORM` conversion ROUNDS. Truncating instead biases every channel down
     * by up to a level, which against a committed 8-bit ground truth turns a bit-exact render
     * into a whole-image miss — and it looks identical to the eye, so nothing but a number
     * catches it.
     */
    @Test
    fun `raster quantisation rounds to nearest`() {
        val raster = CpuRaster(2, 1, floatArrayOf(0.5f, 0.4f, 0.6f, 1f, 1.5f, -0.5f, 0f, 1f))
        val argb = raster.toArgb()
        assertEquals(128, (argb[0] shr 16) and 0xff, "0.5 * 255 = 127.5 rounds up")
        assertEquals(102, (argb[0] shr 8) and 0xff)
        assertEquals(153, argb[0] and 0xff)
        assertEquals(255, (argb[1] shr 16) and 0xff, "out of range clamps")
        assertEquals(0, (argb[1] shr 8) and 0xff)
    }

    /**
     * `fragUV` is sampled at PIXEL CENTRES and `y` grows upwards.
     *
     * The direction is not a matter of taste, but the obvious derivation gets it backwards. The
     * harness's fullscreen triangle sets `gl_Position.y = fragUV.y * 2 - 1` and Vulkan's clip
     * `y = -1` is the top of the framebuffer — which suggests `fragUV.y` grows downwards, and is
     * what this test asserted until 2026-08-30. It omits the **negative-height viewport Y-flip**
     * every afield.four render path applies, which maps clip `y = +1` to image row 0. So
     * `fragUV.y = 1` is the top row. See `f44227393`.
     *
     * The old empirical argument — "settled by 262144 bit-identical pixels in
     * `StackSLCpuGalleryTest`, a flip could not produce that" — was circular: those committed
     * PNGs were themselves inverted, so a flipped raster matched them exactly. An oracle can only
     * settle a convention it does not share.
     */
    @Test
    fun `fragUV runs from pixel centres with y growing upwards`() {
        val raster = StackSLCpu.renderFragment(
            """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = vec4(fragUV, 0.0, 1.0); }
            """.trimIndent(),
            width = 4, height = 4, parallel = false
        )
        assertFalse(StackSLCpu.FRAG_UV_ORIGIN_IS_TOP_LEFT)
        // x is unaffected: u still grows left to right.
        assertEquals(0.125f, raster.at(0, 0, 0), 0f)
        assertEquals(0.875f, raster.at(3, 3, 0), 0f)
        // v is flipped relative to PNG row order: row 0 is the top and carries v = 0.875.
        assertEquals(0.875f, raster.at(0, 0, 1), 0f)
        assertEquals(0.125f, raster.at(3, 3, 1), 0f)
        // y grows upwards: the green channel is larger at the top.
        assertTrue(raster.at(0, 0, 1) > raster.at(0, 3, 1))
    }

    // -----------------------------------------------------------------------------------------
    // Refusals. A subset that guesses is worse than one that says no.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an unsupported construct is refused by name rather than guessed`() {
        val arrays = assertFailsWith<GlslUnsupported> {
            parseGlslShader("void main() { float a[4]; }")
        }
        assertTrue("array" in arrays.message!!.lowercase(), arrays.message!!)

        val structs = assertFailsWith<GlslUnsupported> {
            parseGlslShader("struct S { float a; }; void main() { }")
        }
        assertTrue("struct" in structs.message!!.lowercase(), structs.message!!)

        val unknown = assertFailsWith<GlslUnsupported> {
            val s = parseGlslShader(
                """
                layout(location = 0) out vec4 outColor;
                void main() { outColor = vec4(dFdx(1.0)); }
                """.trimIndent()
            )
            s.newInvocation().runMain()
        }
        assertTrue("dFdx" in unknown.message!!, unknown.message!!)
    }

    /** Sampling with no texture supplied refuses; it does not quietly return black. */
    @Test
    fun `a shader that samples refuses without a sampler`() {
        val source = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D source;
            layout(location = 0) out vec4 outColor;
            void main() { outColor = texture(source, vec2(0.5)); }
        """.trimIndent()
        val refused = assertFailsWith<GlslUnsupported> { parseGlslShader(source).newInvocation().runMain() }
        assertTrue("GlslSamplers" in refused.message!!, refused.message!!)

        val texture = CpuTexture2D(1, 1, floatArrayOf(0.25f, 0.5f, 0.75f, 1f))
        val invocation = parseGlslShader(source).newInvocation(samplers = texture)
        invocation.runMain()
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), invocation.global("outColor")!!.c.toList())
    }

    /** A non-terminating loop is bounded rather than hanging a 262144-pixel render. */
    @Test
    fun `a runaway loop is bounded`() {
        val runaway = assertFailsWith<GlslEvalError> {
            parseGlslShader(
                """
                layout(location = 0) out vec4 outColor;
                void main() { float x = 0.0; for (int i = 0; i > -1; i++) { x += 1.0; } outColor = vec4(x); }
                """.trimIndent()
            ).newInvocation().runMain()
        }
        assertTrue("iterations" in runaway.message!!, runaway.message!!)
    }

    /** The push-constant block a template declares is reachable as a flat `pc.params`. */
    @Test
    fun `a push constant block member is bindable`() {
        val raster = StackSLCpu.renderFragment(
            """
            #version 450
            layout(location = 0) out vec4 outColor;
            layout(push_constant) uniform Push { vec4 params; } pc;
            void main() { outColor = vec4(pc.params.x, 0.0, 0.0, 1.0); }
            """.trimIndent(),
            width = 2, height = 2,
            uniforms = mapOf("pc.params" to GlslValue.vec4(0.375f, 0f, 0f, 0f)),
            parallel = false
        )
        assertEquals(0.375f, raster.at(1, 1, 0), 0f)
    }

    /** `#version` and other directives are recorded, not obeyed. */
    @Test
    fun `preprocessor lines are recorded`() {
        val shader = parseGlslShader("#version 450\nvoid main() { }")
        assertEquals(listOf("#version 450"), shader.preprocessorLines)
    }

    /** A double-checked sanity net on the whole tolerance story: `abs` is exact. */
    @Test
    fun `component-wise builtins keep their type`() {
        assertEquals(listOf(1f, 2f, 3f, 4f), evalExpr("abs(vec4(-1.0, 2.0, -3.0, 4.0))").toList())
        assertEquals(0f, abs(scalar("length(vec2(3.0, 4.0))") - 5f), 0f)
    }
}

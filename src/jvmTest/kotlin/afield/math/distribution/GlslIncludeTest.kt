package afield.math.distribution

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `#include "dist/<name>"` and `#include "gp/<name>"`: the GLSL they stand for compiles, alone and
 * several to a shader, in fragment and compute stages; names resolve or fail with a message; and
 * the Gaussian-process draw the shader makes, evaluated on the CPU the same way, has the exact
 * finite-feature posterior's mean and variance.
 */
class GlslIncludeTest {

    private fun compile(name: String, src: String, kind: ShaderKind) {
        val spirv = try { ShaderSPIRVUtils.compileShader(name, src, kind) } catch (t: Throwable) { throw AssertionError("$name did not compile: ${t.message}\n$src", t) }
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "$name: empty SPIR-V")
    }

    private val mu = normal(0, 10)
    private val posterior = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)
    private val ring = run {
        val r = normal(1, 0.08); val t = uniform(0, 2 * Math.PI)
        listOf(r * t.cos(), r * t.sin())
    }
    private val gp1 = GpShaderModel.of(doubleArrayOf(0.5, 2.0, 3.5, 6.0), doubleArrayOf(0.2, 1.1, 0.4, 0.9), lengthScale = 1.0, frequencies = 32)
    private val gp2 = GpShaderModel(arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.5)), doubleArrayOf(1.0, -1.0), doubleArrayOf(0.7, 0.7), frequencies = 16)

    @Test
    fun severalIncludesShareAFragmentShader() {
        val parts = listOf(
            (gamma(2, 1) + beta(2, 3)).toGlslInclude("grain").source,
            posterior.toGlslInclude("reading").source,
            ring.toGlslInclude("ring").source,
            ring.given(ring[0] + ring[1] gt 0.5).toGlslInclude("arc").source,
            gp1.toGlslInclude("ridge"),
            gp2.toGlslInclude("field"),
            (gamma(3, 1)).toGlslInclude("other").source, // a second include needing the same helpers
        )
        val src = buildString {
            append("#version 450\nlayout(location = 0) out vec4 color;\nlayout(push_constant) uniform P { uint frame; } pc;\n")
            for (p in parts) append(p)
            append(
                """
                void main() {
                    uint s = nlSeed(gl_FragCoord.xy, pc.frame);
                    float v[2];
                    ring(s, v);
                    float a[2];
                    arc(s, a);
                    float g = grain(s) + other(s) + reading(s);
                    float r = ridge(gl_FragCoord.x * 0.01, 3u) + ridge_mean(1.0) + ridge_sd(1.0);
                    float f = field(gl_FragCoord.xy * 0.01, 1u) + field_sd(vec2(0.2));
                    color = vec4(v[0] + a[1], g, r, f) * float(ring_SIZE);
                }
                """.trimIndent()
            )
        }
        // the gamma helper is in two includes' text, under one guard, so it is compiled once:
        // a second definition would be a compile error below
        val tag = Regex("#ifndef (NL_H_[0-9A-F]+)\n#define \\1\n\nfloat nlGamma").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(1, tag.size, "one guard for nlGamma")
        compile("noiselang-includes.frag", src, ShaderKind.FRAGMENT_SHADER)
    }

    @Test
    fun aComputeShaderCanDrawToo() {
        val src = "#version 450\nlayout(local_size_x = 64) in;\nlayout(std430, binding = 0) buffer B { float v[]; } b;\n" +
            posterior.toGlslInclude("reading", posteriorPool = 64).source + gp1.toGlslInclude("ridge") +
            "void main() { uint s = nlSeed(gl_GlobalInvocationID, 0u); b.v[gl_GlobalInvocationID.x] = reading(s) + ridge(1.0, gl_GlobalInvocationID.x); }\n"
        compile("noiselang-includes.comp", src, ShaderKind.COMPUTE_SHADER)
        val inc = posterior.toGlslInclude("reading")
        assertTrue(inc.weighted && "reading_weighted" in inc.source)
        assertEquals("float reading(inout uint s)", inc.signature)
        assertEquals("void ring(inout uint s, out float v[2])", ring.toGlslInclude("ring").signature)
    }

    @Test
    fun namesResolveOrSayWhatIsDefined() {
        distInclude("testGrain", normal(0, 1))
        assertTrue("float testGrain(inout uint s)" in distIncludeSource(null, "testGrain"))
        assertTrue("float testGrain(" in distIncludeSource(null, "testGrain.glsl"))
        val e = assertFailsWith<IllegalStateException> { distIncludeSource(null, "nope") }
        assertTrue("distHere(\"nope\"" in e.message!! && "testGrain" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { normal().toGlslInclude("gl_bad") }
        assertFailsWith<IllegalArgumentException> { normal().toGlslInclude("2x") }
        gpInclude("testRidge", gp1)
        assertTrue("float testRidge(float x, uint curve)" in gpIncludeSource(null, "testRidge"))
        assertFailsWith<IllegalStateException> { gpIncludeSource(null, "nope") }
    }

    @Test
    fun aBoxsIncludesReachItsDescendantsNearestFirst() {
        // a throwaway session, so connect has a document to record the edge in
        val root = Box("nl-include-root")
        val session = SessionInformation(filename = "<nl-include>", documentName = "nl-include", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        fun boxIn(id: String) = Box(id).also { it.inside = session; session.boxes[id] = it }
        try {
            val parent = boxIn("nl-include-parent")
            val child = boxIn("nl-include-child")
            parent.connect(child)
            parent.distHere("shared", die(6))
            child.distHere("own", normal(), posteriorPool = 8)
            child.gpHere("ridgeBox", gp1)
            assertTrue("float shared(inout uint s)" in distIncludeSource(child, "shared"), "a parent's include reaches its child")
            assertTrue("float own(inout uint s)" in distIncludeSource(child, "own"))
            assertFailsWith<IllegalStateException> { distIncludeSource(parent, "own") } // not upwards
            assertTrue("float ridgeBox(float x, uint curve)" in gpIncludeSource(child, "ridgeBox"))
            // the nearest definition wins
            child.distHere("shared", uniform(0, 1))
            assertTrue("floor" !in distIncludeSource(child, "shared").substringAfter("NL_DIST_shared"), "the child's own uniform, not the parent's die")
            assertTrue("floor" in distIncludeSource(parent, "shared").substringAfter("NL_DIST_shared"))
        } finally {
            thisSessionInfo.remove()
        }
    }

    @Test
    fun theShaderGpDrawHasThePosteriorsMoments() {
        val rng = SplittableRandom(5)
        val at = listOf(0.5, 1.2, 2.0, 5.0, 8.0)
        val draws = List(4000) { gp1.drawCpu(rng) }
        for (x in at) {
            val p = gp1.cpu.process.predict(x)
            val vals = DoubleArray(draws.size) { draws[it](doubleArrayOf(x)) }
            val m = vals.average()
            val v = vals.sumOf { (it - m) * (it - m) } / (vals.size - 1)
            val se = sqrt(p.variance / vals.size)
            assertTrue(abs(m - p.mean) < 4 * se + 1e-6, "mean at $x: $m vs ${p.mean} ± $se")
            assertEquals(p.variance, v, 0.12 * p.variance + 1e-4, "variance at $x")
        }
        // the mean the include computes without drawing is the finite-feature posterior mean
        assertEquals(gp1.cpu.mean(1.2), gp1.cpu.process.predict(1.2).mean, 1e-6)
    }
}

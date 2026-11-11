package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.distribution.GpShaderModel
import afield.math.distribution.given
import afield.math.distribution.mixture
import afield.math.distribution.normal
import afield.math.distribution.observed
import afield.math.distribution.toGlslInclude
import afield.math.distribution.uniform
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.noiselang.gpu-includes
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.NoiseLangGpuIncludes
tags: [smoke, four, gpu, compute, distribution, noiselang, gaussian-process]
timeoutSec: 480
expect:
  exitCode: 0
  stdoutContains:
    - "noiselang gpu includes PASSED"
*/
/**
 * `#include "dist/…"` and `#include "gp/…"` on the GPU: compute shaders that include the GLSL a
 * box's `distHere` and `gpHere` would serve, draw 65 536 values, and are checked against exact
 * answers: the moments of a mixture, a posterior drawn by weighted reservoir sampling, a joint
 * ring and the arc of it a condition keeps, and draws of a Gaussian process against its exact
 * finite-feature posterior mean and variance.
 */
object NoiseLangGpuIncludes {
    private const val THREADS = 16_384

    private fun header() = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer NlOut { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push { NlOut result; uint seed; uint threads; } pc;
"""

    /** Runs [body] (a `main` that writes `pc.result.v[id]`) after [includes]; returns 4 floats per invocation. */
    private fun run(renderer: Renderer, pump: () -> Unit, label: String, includes: String, body: String): FloatArray {
        val probe = GpuProbe(renderer, "noiselang-includes-$label", pump)
        try {
            val out = probe.fixture("out", THREADS * 4)
            probe.stage("draw", header() + includes + body).dispatch(groups = THREADS / 64) {
                bind("result") { out }
                bind("seed") { 7 }
                bind("threads") { THREADS }
            }
            probe.settle(out)
            probe.verdictLines("noiselang-includes").filter { "FAIL" in it }.forEach(::println)
            return probe.read(out)
        } finally { probe.close() }
    }

    private fun meanVar(v: DoubleArray): Pair<Double, Double> {
        val m = v.average(); return m to v.sumOf { (it - m) * (it - m) } / (v.size - 1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("NoiseLang GPU includes")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0
        fun check(label: String, ok: Boolean, detail: String) {
            println("%-34s %s  %s".format(label, if (ok) "ok  " else "FAIL", detail)); if (!ok) failures++
        }
        try {
            // 1. a mixture, four draws per invocation
            val mix = mixture(1 to normal(-2, 1), 3 to normal(4, 0.5))
            val a = run(renderer, pump, "mixture", mix.toGlslInclude("mix").source, """
void main() {
    uint id = gl_GlobalInvocationID.x; if (id >= pc.threads) return;
    uint s = nlSeed(gl_GlobalInvocationID, pc.seed);
    pc.result.v[id] = vec4(mix(s), mix(s), mix(s), mix(s));
}
""").map { it.toDouble() }.toDoubleArray()
            val (am, av) = meanVar(a)
            // exact: mean 2.5; variance = E[x^2] - 2.5^2 = (1/4)(4+1) + (3/4)(16+0.25) - 6.25
            val exactVar = 0.25 * 5 + 0.75 * 16.25 - 6.25
            check("mixture mean", abs(am - 2.5) < 4 * sqrt(exactVar / a.size), "%.4f vs 2.5".format(am))
            check("mixture variance", abs(av - exactVar) < 0.05 * exactVar, "%.4f vs %.4f".format(av, exactVar))

            // 2. a posterior, by weighted reservoir sampling over 256 draws
            val mu = normal(0, 10)
            val post = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)
            val b = run(renderer, pump, "posterior", post.toGlslInclude("reading", posteriorPool = 256).source, """
void main() {
    uint id = gl_GlobalInvocationID.x; if (id >= pc.threads) return;
    uint s = nlSeed(gl_GlobalInvocationID, pc.seed);
    pc.result.v[id] = vec4(reading(s), reading(s), reading(s), reading(s));
}
""").map { it.toDouble() }.toDoubleArray()
            val (bm, bv) = meanVar(b)
            val pm = 9.3 / 3.01; val psd = sqrt(1 / 3.01)
            check("posterior mean (pool 256)", abs(bm - pm) < 0.05, "%.4f vs %.4f".format(bm, pm))
            check("posterior sd (pool 256)", abs(sqrt(bv) - psd) < 0.25 * psd, "%.4f vs %.4f".format(sqrt(bv), psd))

            // 3. a joint ring, and the arc of it where x + y > 0.5
            val r = normal(1, 0.08); val t = uniform(0, 2 * Math.PI)
            val ring = listOf(r * t.cos(), r * t.sin())
            val arc = ring.given(ring[0] + ring[1] gt 0.5)
            val c = run(renderer, pump, "ring", ring.toGlslInclude("ring").source + arc.toGlslInclude("arc").source, """
void main() {
    uint id = gl_GlobalInvocationID.x; if (id >= pc.threads) return;
    uint s = nlSeed(gl_GlobalInvocationID, pc.seed);
    float p[2]; ring(s, p);
    float q[2]; arc(s, q);
    pc.result.v[id] = vec4(p[0], p[1], q[0], q[1]);
}
""")
            val r2 = DoubleArray(THREADS) { (c[4 * it] * c[4 * it] + c[4 * it + 1] * c[4 * it + 1]).toDouble() }
            val (rm, _) = meanVar(r2)
            check("ring E(x² + y²)", abs(rm - (1 + 0.0064)) < 0.005, "%.4f vs 1.0064".format(rm))
            val kept = (0 until THREADS).count { c[4 * it + 2] + c[4 * it + 3] > 0.5f }
            check("arc keeps x + y > 0.5", kept >= THREADS * 0.999, "$kept of $THREADS")

            // 4. Gaussian-process draws: invocation i is curve i, evaluated at four points
            val gp = GpShaderModel.of(doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5), doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3), lengthScale = 1.0, noiseStd = 0.1)
            val xs = doubleArrayOf(0.5, 1.2, 4.7, 9.0)
            val d = run(renderer, pump, "gp", gp.toGlslInclude("ridge"), """
void main() {
    uint id = gl_GlobalInvocationID.x; if (id >= pc.threads) return;
    pc.result.v[id] = vec4(ridge(${xs[0]}, id + pc.seed * 100000u), ridge(${xs[1]}, id + pc.seed * 100000u), ridge(${xs[2]}, id + pc.seed * 100000u), ridge(${xs[3]}, id + pc.seed * 100000u));
}
""")
            for ((k, x) in xs.withIndex()) {
                val v = DoubleArray(THREADS) { d[4 * it + k].toDouble() }
                val (m, va) = meanVar(v)
                val p = gp.cpu.process.predict(x)
                check("gp mean at $x", abs(m - p.mean) < 4 * sqrt(p.variance / THREADS) + 2e-3, "%.4f vs %.4f".format(m, p.mean))
                check("gp variance at $x", abs(va - p.variance) < 0.06 * p.variance + 2e-4, "%.5f vs %.5f".format(va, p.variance))
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        if (failures > 0) { println("noiselang gpu includes FAILED ($failures)"); exitProcess(1) }
        println("noiselang gpu includes PASSED")
        exitProcess(0)
    }

}

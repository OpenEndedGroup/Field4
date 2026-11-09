package afield.examples.four

import afield.graphics.sky.Atmosphere
import afield.graphics.sky.Atmosphere.V3
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.atmosphere.gpu-parity
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.AtmosphereGpuParitySmoke
tags: [smoke, four, gpu, compute, sky, probe]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "atmosphere GPU parity PASSED"
*/

/**
 * `sky/atmosphere.glsl` on the GPU against its CPU twin [Atmosphere]: `skyRadiance` evaluated by a
 * compute shader for a handful of views (noon, sunset, twilight, the planet from space, its limb and
 * its night side) must match [Atmosphere.radiance] within 0.2% per channel, relative to the
 * brightest channel of that view. Both march 32 view steps and 8 light steps, so the only
 * difference left is fp32 against fp64.
 *
 * ```
 * tools/run_field_tests.py --id four.atmosphere.gpu-parity
 * ```
 */
object AtmosphereGpuParitySmoke {
    // The packaged include's text, pasted in: a probe stage has no box, so the box include
    // resolver that serves `#include "sky/atmosphere.glsl"` is not in play.
    private val GLSL = """#version 450
#extension GL_EXT_buffer_reference : require
""" + Atmosphere.source("atmosphere.glsl") + """
layout(std430, buffer_reference) buffer Vec4s { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push {
    Vec4s views;
    Vec4s radiance;
    uint count;
} pc;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;
    vec3 ro = pc.views.v[3u * i].xyz;
    vec3 rd = pc.views.v[3u * i + 1u].xyz;
    vec3 sun = pc.views.v[3u * i + 2u].xyz;
    pc.radiance.v[i] = vec4(skyRadiance(earthAtmosphere(), ro, rd, sun, vec3(20.0), 0.00465, 32, 8), 1.0);
}
"""

    private data class View(val name: String, val ro: V3, val rd: V3, val sun: V3)

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val a = Atmosphere.Params()
        val ground = V3(0.0, a.planetRadius + 3.5, 0.0)
        val space = V3(0.0, a.planetRadius + 20000.0, 0.0)
        val down = V3(0.0, -1.0, 0.0)
        fun dir(e: Double, az: Double = 0.0) = Atmosphere.direction(Math.toRadians(e), Math.toRadians(az))
        val limb = (V3(a.planetRadius + 30.0, 0.0, 0.0) + space * -1.0).normalized()
        val views = listOf(
            View("noon zenith", ground, V3(0.0, 1.0, 0.0), dir(60.0)),
            View("noon horizon", ground, dir(2.0, 90.0), dir(60.0)),
            View("noon ground", ground, dir(-30.0), dir(60.0)),
            View("sunset toward sun", ground, dir(3.0), dir(2.0)),
            View("sunset away", ground, dir(10.0, 180.0), dir(2.0)),
            View("twilight", ground, dir(3.0), dir(-4.0)),
            View("space, day side", space, down, dir(20.0)),
            View("space, limb", space, limb, dir(20.0)),
            View("space, night side", space, down, V3(0.0, -1.0, 0.0)),
        )
        val n = views.size
        val input = FloatArray(n * 12)
        views.forEachIndexed { i, v ->
            listOf(v.ro, v.rd, v.sun).forEachIndexed { k, p ->
                input[i * 12 + k * 4] = p.x.toFloat(); input[i * 12 + k * 4 + 1] = p.y.toFloat(); input[i * 12 + k * 4 + 2] = p.z.toFloat()
            }
        }
        val window = Window("Atmosphere GPU parity")
        val renderer = Renderer(window)
        var failures: Int
        try {
            failures = gpuProbe(renderer, "atmosphere-parity", { window.pollEvents(); renderer.render() }) { probe ->
                val inBuf = probe.fixture("views", n * 12)
                val outBuf = probe.fixture("radiance", n * 4)
                probe.upload(inBuf, input)
                probe.stage("skyRadiance", GLSL).dispatch(groups = 1) {
                    bind("views") { inBuf }
                    bind("radiance") { outBuf }
                    bind("count") { n }
                }
                probe.settle(outBuf)
                val got = probe.read(outBuf)
                var worst = 0.0
                views.forEachIndexed { i, v ->
                    // The CPU twin takes the same float-rounded inputs the GPU saw.
                    fun f(p: V3) = V3(p.x.toFloat().toDouble(), p.y.toFloat().toDouble(), p.z.toFloat().toDouble())
                    val cpu = Atmosphere.radiance(a, f(v.ro), f(v.rd), f(v.sun))
                    val gpu = V3(got[i * 4].toDouble(), got[i * 4 + 1].toDouble(), got[i * 4 + 2].toDouble())
                    val scale = max(max(cpu.x, cpu.y), max(cpu.z, 1e-6))
                    val err = maxOf(abs(gpu.x - cpu.x), abs(gpu.y - cpu.y), abs(gpu.z - cpu.z)) / scale
                    worst = max(worst, err)
                    println(
                        "  %-18s cpu (%.5f, %.5f, %.5f)  gpu (%.5f, %.5f, %.5f)  rel err %.2e"
                            .format(v.name, cpu.x, cpu.y, cpu.z, gpu.x, gpu.y, gpu.z, err)
                    )
                }
                probe.atMost("worst relative error", worst, 2e-3, buffer = "radiance")
                probe.verdict("atmosphere GPU parity")
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        if (failures > 0) {
            println("atmosphere GPU parity FAILED ($failures)")
            exitProcess(1)
        }
        println("atmosphere GPU parity PASSED")
        exitProcess(0)
    }
}

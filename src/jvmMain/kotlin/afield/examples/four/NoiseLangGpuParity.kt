package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.distribution.Dist
import afield.math.distribution.E
import afield.math.distribution.Estimate
import afield.math.distribution.GpuMonteCarlo
import afield.math.distribution.die
import afield.math.distribution.draws
import afield.math.distribution.hasDuplicates
import afield.math.distribution.mixture
import afield.math.distribution.normal
import afield.math.distribution.plus
import afield.math.distribution.uniform
import afield.math.distribution.uniformInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.noiselang.gpu-parity
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.NoiseLangGpuParity
tags: [smoke, four, gpu, compute, distribution, noiselang]
timeoutSec: 480
expect:
  exitCode: 0
  stdoutContains:
    - "noiselang gpu parity PASSED"
*/
/**
 * The same [Dist] graphs estimated on the CPU and, lowered to GLSL, on the GPU; each pair must
 * agree within four combined standard errors, and each must agree with the exact answer.
 *
 * The GPU draws `16384 x 256 = 4.2 M` samples per graph in one dispatch, against 200 000 on the
 * CPU, so its SE is about 4.6x smaller. A disagreement can come from the lowering (a node written
 * wrong in GLSL), from the RNG (a biased or correlated stream), or from fp32 accumulation; the
 * exact value says which side is wrong.
 */
object NoiseLangGpuParity {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("NoiseLang GPU parity")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0

        val x = die(6); val y = die(6)
        val u = uniform(-1, 1); val v = uniform(-1, 1)
        val bdayExact = 1.0 - (0 until 23).fold(1.0) { a, k -> a * (365.0 - k) / 365.0 }
        val cases: List<Triple<String, Dist, Double>> = listOf(
            Triple("E(5 + d6 + d6)", 5 + x + y, 12.0),
            Triple("P(X + Y < 10)", x + y lt 10, 30.0 / 36),
            Triple("P(birthday, 23)", hasDuplicates(draws(23, uniformInt(1, 365))), bdayExact),
            Triple("P(u^2 + v^2 < 1)", u * u + v * v lt 1, PI / 4),
            Triple("E(x - x)", x - x, 0.0),
            Triple("E(N(2,3)^2)", normal(2, 3).squared(), 13.0),
            Triple("E(mixture)", mixture(1 to normal(-2, 1), 3 to normal(4, 0.5)), 2.5),
        )
        try {
            for ((i, c) in cases.withIndex()) {
                val (label, d, exact) = c
                val cpu = E(d)
                val gpuRun = GpuMonteCarlo(renderer, d, seed = 17 + i, pump = pump)
                val gpu: Estimate = try {
                    gpuRun.run().also { gpuRun.verdictLines().filter { "FAIL" in it }.forEach(::println) }
                } finally { gpuRun.close() }
                val combined = sqrt(cpu.se * cpu.se + gpu.se * gpu.se)
                val z = if (combined == 0.0) (if (cpu.value == gpu.value) 0.0 else 99.0) else abs(cpu.value - gpu.value) / combined
                val zExact = abs(gpu.zFrom(exact))
                val ok = z <= 4.0 && (zExact <= 4.0 || (gpu.se == 0.0 && abs(gpu.value - exact) < 1e-6)) && gpu.n == 16_384L * 256
                println(
                    "%-20s cpu %-28s gpu %-28s exact %.5f  |cpu-gpu| = %.2f SE, |gpu-exact| = %.2f SE  %s"
                        .format(label, cpu, gpu, exact, z, zExact, if (ok) "ok" else "FAIL")
                )
                if (!ok) failures++
            }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        if (failures > 0) {
            println("noiselang gpu parity FAILED ($failures)")
            exitProcess(1)
        }
        println("noiselang gpu parity PASSED")
        exitProcess(0)
    }
}

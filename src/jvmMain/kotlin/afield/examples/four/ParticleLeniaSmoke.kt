package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuNeighbourGrid
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector2f
import afield.simulation.agents.ParticleLenia
import afield.simulation.agents.ParticleLeniaParams
import afield.simulation.physics.ShaderParticleLenia
import kotlin.math.hypot
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.particle-lenia.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.ParticleLeniaSmoke
tags: [smoke, four, gpu, compute, particles, neighbours, alife]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "particle lenia GPU tests PASSED"
*/

/**
 * Grades [ShaderParticleLenia] against the CPU reference [ParticleLenia].
 *
 * Positions are seeded as several 200-particle clusters, the GPU step runs with `dt = 0` (nothing
 * moves), and every particle's GPU velocity is compared with `−∇E` from `ParticleLenia.sample`
 * over the same positions. A second check runs one cluster for a few hundred steps on both and
 * compares the mean energy, which a wrong integrator would move.
 *
 * ```
 * tools/run_field_tests.py --id four.particle-lenia.smoke
 * ```
 */
object ParticleLeniaSmoke {
    private const val S = ShaderParticleLenia.STRIDE_FLOATS

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Particle Lenia Smoke")
        val renderer = Renderer(window)
        try {
            var failures = 0
            failures += checkVelocities(renderer, window)
            failures += checkTrajectory(renderer, window)
            if (failures > 0) {
                println("particle lenia GPU tests FAILED ($failures)")
                exitProcess(1)
            }
            println("particle lenia GPU tests PASSED")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    private fun grid(renderer: Renderer, capacity: Int, tag: String) = GpuNeighbourGrid(
        capacity = capacity, cellsPerSide = 40, domainMin = Vector2f(-160f, -160f), domainSize = 320f,
        renderer = renderer, name = "plGrid$tag",
    )

    private fun run(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) { window.pollEvents(); renderer.render() }
        renderer.windowContext().computeFences.forEach { it.fenceWait() }
    }

    private fun checkVelocities(renderer: Renderer, window: Window): Int {
        val n = 1600
        val gpu = ShaderParticleLenia(grid(renderer, n, "v"), n, renderer, ParticleLeniaParams(dt = 0.0), name = "plV")
        gpu.substeps = 1
        gpu.reset(creatures = 8, perCreature = 200, spacing = 30f, seed = 5)
        val seeded = gpu.read()
        val xy = DoubleArray(2 * n) { seeded[(it / 2) * S + it % 2].toDouble() }
        gpu.install(renderer.renderList.others)
        run(renderer, window, 4)
        val state = gpu.read()
        val cpu = ParticleLenia(xy)
        // Absolute error over the largest speed: the GPU cuts the Gaussian kernel at muK + 3 sigmaK
        // and sums in fp32, so a particle whose velocity nearly cancels has a large *relative* error
        // that means nothing. A missed neighbour or a wrong sign is a gross error on this scale too.
        var worstAbs = 0.0; var largest = 0.0
        for (i in 0 until n) {
            val s = cpu.sample(xy[2 * i], xy[2 * i + 1])
            largest = maxOf(largest, hypot(s.gx, s.gy))
            worstAbs = maxOf(worstAbs, hypot(state[i * S + 2] + s.gx, state[i * S + 3] + s.gy))
        }
        val worst = worstAbs / largest
        println("--- velocities: $n particles, worst error ${"%.3g".format(worstAbs)} = ${"%.3g".format(worst)} of the largest |v| ${"%.3f".format(largest)}")
        gpu.removeFrom(renderer.renderList.others)
        gpu.grid.cleanup()
        return if (worst < 1e-3 && largest > 0.01) { println("  ok"); 0 } else { println("  FAIL"); 1 }
    }

    private fun checkTrajectory(renderer: Renderer, window: Window): Int {
        val n = 200
        val gpu = ShaderParticleLenia(grid(renderer, n, "t"), n, renderer, name = "plT")
        gpu.substeps = 10
        gpu.reset(seed = 1)
        val seeded = gpu.read()
        val cpu = ParticleLenia(DoubleArray(2 * n) { seeded[(it / 2) * S + it % 2].toDouble() })
        gpu.install(renderer.renderList.others)
        // renderer.render() may run more frames than asked before the fence; count what ran instead.
        run(renderer, window, 30)
        gpu.removeFrom(renderer.renderList.others)
        renderer.windowContext().computeFences.forEach { it.fenceWait() }
        val state = gpu.read()
        val gpuE = (0 until n).sumOf { state[it * S + 5].toDouble() } / n
        cpu.stepTimes(300)
        val cpuE = cpu.currentEnergy()
        println("--- trajectory: GPU mean E ${"%.4f".format(gpuE)} after ~300 steps, CPU ${"%.4f".format(cpuE)} after 300, seed E ${"%.4f".format(cpu.energyHistory.first())}")
        gpu.grid.cleanup()
        return if (kotlin.math.abs(gpuE - cpuE) < 0.05) { println("  ok"); 0 } else { println("  FAIL"); 1 }
    }
}

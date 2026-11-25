package afield.examples.four

import afield.geometry.mesh.boundsAround
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.topology
import afield.geometry.spectral.CotangentLaplacian
import afield.geometry.spectral.EpicycleGeometry
import afield.geometry.spectral.PhaseClock
import afield.geometry.spectral.PlaneWave
import afield.geometry.spectral.PlaneWaveField
import afield.geometry.spectral.PlaneWaveGrid
import afield.geometry.spectral.SpectralGpuEvaluator
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: four.spectral-geometry.gpu
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.SpectralGeometryGpuSmoke
tags: [smoke, four, gpu, compute, geometry]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "spectral GPU parity passed"
*/
object SpectralGeometryGpuSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        val window = Window("Spectral Geometry GPU")
        val renderer = Renderer(window)
        try {
            val pump = { window.pollEvents(); renderer.render() }
            GpuProbe(renderer, "spectral-guarantee", pump).use { probe ->
                val geometry = EpicycleGeometry.torus()
                val clock = PhaseClock(doubleArrayOf(1.0, sqrt(2.0)))
                val phases = List(131) { i -> clock.phase(i * 723.0) }
                SpectralGpuEvaluator.phase(renderer, geometry, phases).use { gpu ->
                    val cpu = phases.flatMap { phase ->
                        val p = geometry.evaluate(phase)
                        listOf(p.x, p.y, p.z)
                    }.toFloatArray()
                    verify(probe, gpu, cpu, pump, "phase")
                }

                val points = List(4099) { i -> Vec3(sin(i * 0.17).toFloat(), cos(i * 0.31).toFloat(), sin(i * 0.73).toFloat()) }
                for (waveCount in listOf(32, 128, 256)) {
                    val field = PlaneWaveField.random(waveCount, seed = 18, radialFrequency = { it.nextDouble(0.75, 6.0) })
                    SpectralGpuEvaluator.spatialValueGradient(renderer, field, points).use { gpu ->
                        val cpu = points.flatMap { p ->
                            val gradient = field.gradient(p)
                            listOf(gradient.x, gradient.y, gradient.z, field.evaluate(p).toFloat())
                        }.toFloatArray()
                        verify(probe, gpu, cpu, pump, "$waveCount waves + gradient")
                    }
                }

                val grid = PlaneWaveGrid(boundsAround(radius = 2.5), 32)
                val field = PlaneWaveField(0.0, List(3) { axis ->
                    PlaneWave(Vec3().also { it[axis] = 1f }, 1.0, 0.0)
                })
                SpectralGpuEvaluator.spatial(renderer, field, grid.points).use { gpu ->
                    verify(probe, gpu, grid.sample(field), pump, "33³ scalar grid")
                    val topology = grid.extract(gpu.read(), 1.8, positiveOutside = false).topology()
                    check(topology.isWatertight && topology.genus == 0) {
                        "Expected a closed genus-zero mesh from the GPU grid: $topology"
                    }
                }

                val mesh = icosphereMesh(subdivisions = 2)
                val basis = CotangentLaplacian(mesh).basis(14)
                val signal = DoubleArray(mesh.vertexCount * 5) { i -> cos(i * 0.031) }
                val spectrum = basis.analyze(signal, 5)
                SpectralGpuEvaluator.manifold(renderer, basis, spectrum).use { gpu ->
                    verify(probe, gpu, basis.reconstruct(spectrum).map(Double::toFloat).toFloatArray(), pump, "manifold with five channels")
                    val heated = basis.diffuse(spectrum, 0.3)
                    gpu.update(heated)
                    verify(probe, gpu, basis.reconstruct(heated).map(Double::toFloat).toFloatArray(), pump, "manifold coefficient edit")
                }
            }
            println("spectral GPU parity passed")
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
    }

    private fun verify(probe: GpuProbe, gpu: SpectralGpuEvaluator, cpu: FloatArray, pump: () -> Unit, label: String) {
        // Warm the pipeline, then time five submitted frames and their fence waits.
        repeat(3) { pump() }
        probe.idle()
        val start = System.nanoTime()
        repeat(5) { pump() }
        probe.idle()
        val ms = (System.nanoTime() - start) / 5e6
        val result = gpu.read()
        val error = result.indices.maxOf { abs(cpu[it] - result[it]) }
        check(error < 1e-4f) { "$label GPU max error $error" }
        println("$label: ${gpu.sampleCount} samples, peak error $error, %.2f ms/submitted frame incl. rendering".format(ms))
    }
}

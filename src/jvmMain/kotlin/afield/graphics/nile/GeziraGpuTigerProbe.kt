package afield.graphics.nile

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gezira-gpu-tiger.probe
requiresDisplay: true
runner: jvm-main
entrypoint: afield.graphics.nile.GeziraGpuTigerProbe
tags: [smoke, four, gpu, compute, probe, nile, gezira, svg, raster]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "gezira gpu tiger PASSED"
*/
/**
 * The Ghostscript tiger through [GeziraGpuScene], against the CPU's [GeziraSvg.render] and against
 * Skia's SVG renderer, then timed per frame at three sizes beside the CPU.
 *
 * Correctness is per channel of premultiplied RGBA: the largest and mean difference over all pixels.
 * The GPU flattens uniformly to [GeziraGpuScene.tolerance] pixels where the CPU halves curves to
 * 1/10,000, so the bound here, [MAX_MEAN] on the mean, is about that flattening; the largest
 * difference is reported, not bounded, since one pixel's colour can swing where a thin stroke's
 * flattening moves it. Timing is over [TIMED_FRAMES] frames: GPU time from the renderer's
 * timestamps around the compute pass, and wall time per frame with presentation in it.
 * Difference images go to `build/gezira-gpu-tiger/`.
 */
object GeziraGpuTigerProbe {

    /** Largest mean per-channel difference allowed between the GPU and the CPU tiger. */
    const val MAX_MEAN = 2e-3

    private const val TIMED_FRAMES = 60

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.vsync = false
        Options.profiling = true
        val window = Window("Gezira GPU Tiger Probe")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0
        val table = ArrayList<String>()
        val out = File("build/gezira-gpu-tiger").apply { mkdirs() }
        try {
            val svg = GeziraSvg.tiger()
            val doc = GeziraSvg.parse(svg)
            val t0 = System.nanoTime()
            val paths = GeziraGpuScene.paths(doc)
            val loadMs = (System.nanoTime() - t0) / 1e6
            println("tiger: ${paths.size} paths, ${paths.sumOf { it.beziers.size }} curves; strokes outlined at load in %.0f ms".format(loadMs))
            for (size in listOf(450, 900, 1800)) {
                val fit = doc.fit(size, size)
                failures += gpuProbe(renderer, "gezira-tiger-$size", pump = pump) { probe ->
                    val scene = GeziraGpuScene(probe, paths, size, size, transform = fit)
                    probe.settle(scene.image, seconds = 60.0)
                    val gpu = scene.read()
                    val over = scene.overflow()
                    probe.exactly("segments dropped ($size)", over[0], 0, buffer = "counts")
                    probe.exactly("band entries dropped ($size)", over[1], 0, buffer = "counts")
                    probe.exactly("row edges dropped ($size)", over[2], 0, buffer = "counts")
                    val cpuStart = System.nanoTime()
                    val cpu = GeziraSvg.render(doc, size, size).canvas.rgba
                    val cpuMs = (System.nanoTime() - cpuStart) / 1e6
                    val skia = GeziraSvg.skia(svg, size, size)
                    val (worstCpu, meanCpu) = diff(gpu, cpu)
                    val (worstSkia, meanSkia) = diff(gpu, skia)
                    val (worstCs, meanCs) = diff(cpu, skia)
                    println("  $size: gpu vs cpu max %.4f mean %.2e | gpu vs skia max %.4f mean %.2e | cpu vs skia max %.4f mean %.2e | binned %d".format(
                        worstCpu, meanCpu, worstSkia, meanSkia, worstCs, meanCs, scene.binned()))
                    if (size == 900) {
                        out.resolve("gpu.png").writeBytes(GeziraSvg.png(gpu, size, size))
                        out.resolve("cpu.png").writeBytes(GeziraSvg.png(cpu, size, size))
                        out.resolve("difference-x10.png").writeBytes(GeziraSvg.png(GeziraSvg.difference(gpu, cpu, 10.0), size, size, 0x000000))
                    }
                    probe.atMost("mean |gpu - cpu| ($size)", meanCpu, MAX_MEAN, buffer = "image")
                    // timing: the pipeline runs every frame anyway
                    probe.pumpFrames(10)
                    val gpuMs = ArrayList<Double>()
                    val start = System.nanoTime()
                    repeat(TIMED_FRAMES) {
                        pump()
                        val g = renderer.lastGpuComputeMs
                        if (!g.isNaN()) gpuMs += g
                    }
                    probe.idle()
                    val wallMs = (System.nanoTime() - start) / 1e6 / TIMED_FRAMES
                    gpuMs.sort()
                    val med = gpuMs.getOrElse(gpuMs.size / 2) { Double.NaN }
                    val p90 = gpuMs.getOrElse((gpuMs.size * 9) / 10) { Double.NaN }
                    table += "%5d x %-5d gpu compute median %7.2f ms (p90 %7.2f)  wall %7.2f ms/frame  cpu GeziraSvg.render %8.0f ms".format(
                        size, size, med, p90, wallMs, cpuMs)
                    probe.verdict("gezira gpu tiger $size")
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            failures++
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println("--- timing over $TIMED_FRAMES frames:")
        table.forEach { println("  $it") }
        if (failures > 0) {
            println("gezira gpu tiger FAILED ($failures)")
            exitProcess(1)
        }
        println("gezira gpu tiger PASSED")
        exitProcess(0)
    }

    /** Largest and mean per-channel absolute difference of two premultiplied RGBA images. */
    fun diff(a: FloatArray, b: FloatArray): Pair<Double, Double> {
        var w = 0.0
        var s = 0.0
        for (i in a.indices) {
            val d = abs(a[i] - b[i]).toDouble()
            w = max(w, d)
            s += d
        }
        return w to s / a.size
    }
}

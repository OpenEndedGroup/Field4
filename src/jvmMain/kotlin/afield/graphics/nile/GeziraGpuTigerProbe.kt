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
 * flattening moves it. Timing is over [TIMED_FRAMES] frames: wall time per frame with presentation
 * in it, and the pipeline's own GPU cost as (frames with it dispatched [REPEATS] times − frames with
 * it once) / ([REPEATS] − 1).
 * Difference images go to `build/gezira-gpu-tiger/`.
 */
object GeziraGpuTigerProbe {

    /** Largest mean per-channel difference allowed between the GPU and the CPU tiger. */
    const val MAX_MEAN = 2e-3

    private const val TIMED_FRAMES = 60
    private const val REPEATS = 5

    private fun timeFrames(probe: GpuProbe): Double {
        probe.pumpFrames(5)
        probe.idle()
        val t0 = System.nanoTime()
        probe.pumpFrames(TIMED_FRAMES)
        probe.idle()
        return (System.nanoTime() - t0) / 1e6 / TIMED_FRAMES
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.vsync = false
        val window = Window("Gezira GPU Tiger Probe")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0
        val table = ArrayList<String>()
        val gpuPerFrame = HashMap<Int, Double>()
        val cpuTimes = HashMap<Int, Double>()
        val out = File("build/gezira-gpu-tiger").apply { mkdirs() }
        try {
            val svg = GeziraSvg.tiger()
            val doc = GeziraSvg.parse(svg)
            val t0 = System.nanoTime()
            val paths = GeziraGpuScene.paths(doc.lines)
            val loadMs = (System.nanoTime() - t0) / 1e6
            println("tiger: ${paths.size} paths, ${paths.sumOf { it.beziers.size }} curves; strokes outlined at load in %.0f ms".format(loadMs))
            // Frames with no compute at all: what the window and presentation cost by themselves.
            repeat(10) { pump() }
            val idleStart = System.nanoTime()
            repeat(TIMED_FRAMES) { pump() }
            val idleMs = (System.nanoTime() - idleStart) / 1e6 / TIMED_FRAMES
            println("empty frame: %.2f ms".format(idleMs))
            for (size in listOf(450, 900, 1800)) {
                val fit = doc.fit(size, size)
                failures += gpuProbe(renderer, "gezira-tiger-$size", pump = pump) { probe ->
                    val scene = GeziraGpuScene(probe, paths, size, size, transform = fit)
                    probe.settle(scene.image, seconds = 60.0)
                    // A written image is not a finished chain: wait until all five stages have
                    // compiled, then let the chain run whole a few times.
                    var waited = 0
                    while (probe.stageList.any { !it.valid } && waited++ < 600) pump()
                    repeat(4) { pump() }
                    probe.idle()
                    // Count overflow only over whole frames: the first frames run on buffers the
                    // earlier stages have not written yet.
                    scene.resetOverflow()
                    repeat(3) { pump() }
                    probe.idle()
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
                    println("  $size: gpu vs cpu max %.4f mean %.2e | gpu vs skia max %.4f mean %.2e | cpu vs skia max %.4f mean %.2e | binned %d | widest run %d edges".format(
                        worstCpu, meanCpu, worstSkia, meanSkia, worstCs, meanCs, scene.binned(), scene.widestRun()))
                    if (size == 900) {
                        out.resolve("gpu.png").writeBytes(GeziraSvg.png(gpu, size, size))
                        out.resolve("cpu.png").writeBytes(GeziraSvg.png(cpu, size, size))
                        out.resolve("difference-x10.png").writeBytes(GeziraSvg.png(GeziraSvg.difference(gpu, cpu, 10.0), size, size, 0x000000))
                    }
                    probe.atMost("mean |gpu - cpu| ($size)", meanCpu, MAX_MEAN, buffer = "image")
                    gpuPerFrame[size] = timeFrames(probe)
                    // The renderer's per-module GPU timestamps, one module per stage: logged as
                    // "MOD '<probe>.gezira-scene-<stage>.<n>': x ms" while profiling is on.
                    println("profiling $size")
                    Options.profiling = true
                    repeat(12) { pump() }
                    probe.idle()
                    Options.profiling = false
                    repeat(3) { pump() }
                    cpuTimes[size] = cpuMs
                    probe.verdict("gezira gpu tiger $size")
                }
                // What the resolve stage spends its time on: frames with parts of it switched off.
                failures += gpuProbe(renderer, "gezira-tiger-$size-parts", pump = pump) { probe ->
                    val scene = GeziraGpuScene(probe, paths, size, size, transform = fit)
                    probe.settle(scene.image, seconds = 60.0)
                    var waited = 0
                    while (probe.stageList.any { !it.valid } && waited++ < 600) pump()
                    val parts = ArrayList<String>()
                    for ((bits, what) in listOf(0 to "all", 2 to "no area adds", 1 to "no band walk", 5 to "no boundaries or walk", 13 to "no boundaries, walk or blend")) {
                        scene.debug = bits
                        parts += "%s %.2f".format(what, timeFrames(probe))
                    }
                    scene.debug = 0
                    table += "        resolve parts, wall ms/frame: " + parts.joinToString(", ")
                    probe.verdict("gezira gpu tiger parts $size")
                }
                // The pipeline's own GPU cost: frames with it dispatched REPEATS times, against once;
                // then each stage alone, the same way.
                val perStage = ArrayList<String>()
                for (stage in -1..4) {
                    failures += gpuProbe(renderer, "gezira-tiger-$size-x$REPEATS-$stage", pump = pump) { probe ->
                        val scene = GeziraGpuScene(probe, paths, size, size, transform = fit, repeats = REPEATS, repeatStage = stage)
                        probe.settle(scene.image, seconds = 60.0)
                        var waited = 0
                        while (probe.stageList.any { !it.valid } && waited++ < 600) pump()
                        val heavy = timeFrames(probe)
                        val ms = (heavy - gpuPerFrame.getValue(size)) / (REPEATS - 1)
                        if (stage < 0) table += "%5d x %-5d  wall %7.2f ms/frame (empty frame %.2f)  pipeline on the GPU %7.2f ms  CPU GeziraSvg.render %6.0f ms".format(
                            size, size, gpuPerFrame.getValue(size), idleMs, ms, cpuTimes.getValue(size))
                        else perStage += "%s %.2f".format(listOf("count", "scan", "emit", "bin", "resolve")[stage], ms)
                        probe.verdict("gezira gpu tiger timing $size stage $stage")
                    }
                }
                table += "        stages (ms): " + perStage.joinToString(", ")
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

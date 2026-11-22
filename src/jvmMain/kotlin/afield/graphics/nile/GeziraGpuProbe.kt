package afield.graphics.nile

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.gezira-gpu.probe
requiresDisplay: true
runner: jvm-main
entrypoint: afield.graphics.nile.GeziraGpuProbe
tags: [smoke, four, gpu, compute, probe, nile, gezira, raster]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "gezira gpu PASSED"
*/
/**
 * [GeziraGpu] checked pixel for pixel against [Gezira.rasterize] on a corpus under both fill rules,
 * then timed against the CPU at four image sizes.
 *
 * The CPU computes in doubles and the GPU in float32, so the comparison is the float32 error of the
 * GPU's WindingSpans: the largest per-pixel difference must stay under [MAX_DIFF]. Timing follows
 * `SplatTilesGpuProbe`: frame time with one dispatch and with [REPEATS], the difference over
 * `REPEATS - 1` being the dispatch's own cost.
 */
object GeziraGpuProbe {

    /** Largest per-pixel coverage difference allowed between GPU (float32) and CPU (double). */
    const val MAX_DIFF = 1e-4

    private const val REPEATS = 9
    private const val TIMED_FRAMES = 30

    /** The comparison corpus: name, outline, width, height. */
    fun corpus(): List<Triple<String, List<Bezier>, Pair<Int, Int>>> = listOf(
        Triple("star", GeziraGpu.star(28.0, 22.0, 18.0, 7.5, rotation = 0.13), 56 to 44),
        Triple("seven-point star", GeziraGpu.star(40.3, 30.7, 27.0, 13.0, points = 7, rotation = 0.4), 80 to 64),
        Triple("pentagram", GeziraGpu.pentagram(28.2, 25.3, 21.0), 56 to 48),
        Triple("circle", Gezira.circle(20.4, 18.7, 15.0), 40 to 40),
        Triple("overlapping circles", Gezira.circle(22.0, 20.0, 14.0) + Gezira.circle(34.3, 24.6, 12.5), 56 to 44),
        Triple("star over circle", GeziraGpu.star(22.0, 20.0, 17.0, 7.0) + Gezira.circle(32.0, 24.0, 12.0), 48 to 40),
        Triple("holed square, hole reversed", GeziraGpu.holedSquare(4.3, 4.6, 30.0, 14.2, sameWay = false), 40 to 40),
        Triple("holed square, hole same way", GeziraGpu.holedSquare(4.3, 4.6, 30.0, 14.2, sameWay = true), 40 to 40),
        Triple("text", Gezira.glyphs("Pixels", 2.0, 30.0, 32.0), 104 to 40),
    )

    /** A scene for timing: [n] × [n] stars and circles tiled over an `s × s` image. */
    fun field(s: Int, n: Int = 8): List<Bezier> {
        val cell = s.toDouble() / n
        val out = ArrayList<Bezier>()
        for (i in 0 until n) for (j in 0 until n) {
            val cx = (i + 0.5) * cell
            val cy = (j + 0.5) * cell
            out += if ((i + j) % 2 == 0) GeziraGpu.star(cx, cy, 0.48 * cell, 0.2 * cell, rotation = 0.1 * i)
            else Gezira.circle(cx, cy, 0.42 * cell)
        }
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.vsync = false
        val window = Window("Gezira GPU Probe")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        var failures = 0
        val timing = ArrayList<String>()
        try {
            failures += gpuProbe(renderer, "gezira-gpu", pump = pump) { probe ->
                val stage = GeziraGpu.stage(probe)
                val jobs = ArrayList<Triple<String, FillRule, GeziraGpu.Job>>()
                for ((k, c) in corpus().withIndex()) for (rule in FillRule.entries) {
                    val (name, shape, size) = c
                    jobs += Triple(name, rule, GeziraGpu.dispatch(probe, stage, shape, size.first, size.second, rule, "c$k-${rule.name}"))
                }
                probe.settle(*jobs.map { it.third.coverage }.toTypedArray())
                var worst = 0.0
                var sum = 0.0
                var count = 0
                for ((name, rule, job) in jobs) {
                    val p = job.packed
                    val cpu = Gezira.rasterize(corpus().first { it.first == name }.second, p.width, p.height, rule)
                    val gpu = job.read()
                    var w = 0.0
                    var s = 0.0
                    var edge = 0
                    for (i in cpu.indices) {
                        val d = abs(cpu[i] - gpu[i])
                        w = max(w, d)
                        s += d
                    }
                    for (v in cpu) if (v > 0.0 && v < 1.0) edge++
                    worst = max(worst, w)
                    sum += s
                    count += cpu.size
                    println("  %-30s %-9s %dx%d  %5d row edges  %4d edge px  max %.2e  mean %.2e  ink cpu %.4f gpu %.4f".format(
                        name, rule, p.width, p.height, p.edgeCount, edge, w, s / cpu.size, cpu.sum(), gpu.sum()))
                    probe.atMost("max |gpu - cpu| ($name, $rule)", w, MAX_DIFF, buffer = "${name}.coverage")
                    probe.exactly("band boundaries dropped ($name, $rule)", job.dropped(), 0, buffer = "${name}.status")
                }
                println("corpus: ${jobs.size} images, ${count} pixels, largest difference %.3e, mean %.3e".format(worst, sum / count))
                probe.verdict("gezira gpu corpus")
            }
            for (s in listOf(128, 256, 512, 1024)) {
                val shape = field(s)
                val cpuMs = medianMs(5) { Gezira.rasterize(shape, s, s, FillRule.NON_ZERO) }
                val packMs = medianMs(5) { GeziraGpu.pack(shape, s, s) }
                val packed = GeziraGpu.pack(shape, s, s)
                val ms = DoubleArray(2)
                var diff = 0.0
                for ((k, repeats) in listOf(1, REPEATS).withIndex()) {
                    failures += gpuProbe(renderer, "gezira-time-$s-$repeats", pump = pump) { probe ->
                        val stage = GeziraGpu.stage(probe)
                        val jobs = (0 until repeats).map { GeziraGpu.dispatch(probe, stage, packed, "r$it") }
                        probe.settle(*jobs.map { it.coverage }.toTypedArray())
                        if (k == 0) {
                            val cpu = Gezira.rasterize(shape, s, s, FillRule.NON_ZERO)
                            val gpu = jobs[0].read()
                            for (i in cpu.indices) diff = max(diff, abs(cpu[i] - gpu[i]))
                            probe.atMost("max |gpu - cpu| (field $s)", diff, MAX_DIFF, buffer = "coverage")
                            probe.exactly("band boundaries dropped (field $s)", jobs[0].dropped(), 0, buffer = "status")
                        }
                        ms[k] = timeFrames(probe)
                        probe.verdict("gezira gpu timing $s x$repeats")
                    }
                }
                timing += "%5d x %-5d %6d row edges  cpu rasterize %8.2f ms  cpu pack %8.2f ms  gpu WindingSpans %7.3f ms  max diff %.1e".format(
                    s, s, packed.edgeCount, cpuMs, packMs, (ms[1] - ms[0]) / (REPEATS - 1), diff)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            failures++
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println("--- timing (gpu = (frame ms with $REPEATS dispatches - with 1) / ${REPEATS - 1}):")
        timing.forEach { println("  $it") }
        if (failures > 0) {
            println("gezira gpu FAILED ($failures)")
            exitProcess(1)
        }
        println("gezira gpu PASSED")
        exitProcess(0)
    }

    private fun medianMs(n: Int, f: () -> Unit): Double {
        f()
        val t = DoubleArray(n) {
            val t0 = System.nanoTime()
            f()
            (System.nanoTime() - t0) / 1e6
        }
        t.sort()
        return t[n / 2]
    }

    private fun timeFrames(probe: GpuProbe): Double {
        probe.pumpFrames(5)
        val t0 = System.nanoTime()
        probe.pumpFrames(TIMED_FRAMES)
        probe.idle()
        return (System.nanoTime() - t0) / 1e6 / TIMED_FRAMES
    }
}

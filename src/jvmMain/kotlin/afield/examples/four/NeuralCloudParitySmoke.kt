package afield.examples.four

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.gpuProbe
import afield.graphics.volume.CloudError
import afield.graphics.volume.CloudScene
import afield.graphics.volume.NeuralCloud
import afield.graphics.volume.cloudError
import afield.graphics.volume.writeCloudComparison
import java.io.File
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.neural-cloud-parity.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.NeuralCloudParitySmoke
tags: [smoke, four, gpu, compute, neural, mlp, clouds]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "neural cloud parity PASSED"
*/
/**
 * The neural cloud on the GPU, read back and compared with [CloudScene.render] on the CPU.
 *
 * `cloud_view` in the `neural_clouds` demo runs `CloudScene.glslScene()` in a fragment shader, and
 * until this test its agreement with the CPU raymarcher was judged by eye. Here the same GLSL
 * (`cloudRenderEvery`, with the trained network as constants) runs in a compute shader, one
 * invocation per pixel, with the CPU's pixel centres and the CPU's `ign` jitter seeds, so the two
 * images should differ only by fp32 against fp64.
 *
 * It reports, over the pixels the cloud touches (the [cloudError] mask):
 *
 * - **parity**: GPU network render against the CPU network render, at `networkEvery` 1, 2, 4, 8;
 * - **quality**: each GPU render against the 48-path CPU reference, beside the CPU network's own
 *   error, so the cost of evaluating the network every Nth step is a number;
 * - **time**: milliseconds per frame with the cloud dispatched at [TIME_W] x [TIME_H], minus an
 *   empty frame, for each N.
 *
 * It fails if GPU-vs-CPU parity at N = 1 is worse than [PARITY_TOLERANCE] relative MAE, or if a
 * deliberately wrong network (the outputs scaled by 1.5) is not at least ten times further off —
 * a tolerance is only meaningful beside the error it is meant to catch.
 *
 * Writes `build/neural_clouds/gpu_parity.png` (CPU network | GPU N=1 | GPU N=4, error row below)
 * and `gpu_parity.txt`.
 */
object NeuralCloudParitySmoke {
    private const val W = 200
    private const val H = 125
    private const val TIME_W = 1280
    private const val TIME_H = 800
    private const val LOCAL = 64

    /** fp32 sines and jitter against fp64; generous next to what a wrong network costs. */
    private const val PARITY_TOLERANCE = 0.02

    private val view = CloudScene.Camera(doubleArrayOf(4.6, 0.9, 3.6), doubleArrayOf(0.0, 2.05, 0.0), 40.0)
    private val trainCameras = listOf(
        CloudScene.Camera(doubleArrayOf(0.0, 2.2, 7.5), doubleArrayOf(0.0, 1.95, 0.0)),
        CloudScene.Camera(doubleArrayOf(-5.5, 3.4, 4.5), doubleArrayOf(0.0, 1.9, 0.0)),
        CloudScene.Camera(doubleArrayOf(4.0, 1.6, -6.0), doubleArrayOf(0.0, 2.0, 0.0)),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val scene = CloudScene()
        val t0 = System.nanoTime()
        val samples = NeuralCloud.samples(scene, trainCameras, count = 12000, pathsPerSample = 8)
        val net = NeuralCloud.train(samples, steps = 8000)
        val ambient = NeuralCloud.ambient(samples)
        println("trained in ${(System.nanoTime() - t0) / 1e9}s, ${net.weights}")

        val reference = scene.render(view, W, H, scene.reference(48))
        val mask = scene.render(view, W, H, CloudScene.InScatter { _, _, _, _, into -> into.fill(1e3) })
        val everies = intArrayOf(1, 2, 4, 8)
        val cpu = everies.associateWith { n ->
            val s = System.nanoTime()
            val image = scene.render(view, W, H, net.inScatter, networkEvery = n)
            println("cpu N=$n ${(System.nanoTime() - s) / 1e6} ms")
            image
        }
        val wrong = NeuralCloud(net.weights, net.outputScale * 1.5, net.trainingLoss)

        Options.enableVR = false
        val window = Window("Neural Cloud Parity Smoke")
        val renderer = Renderer(window)
        val pump = { window.pollEvents(); renderer.render() }
        val out = StringBuilder()
        fun say(s: String) { println(s); out.append(s).append('\n') }
        var failures = 0
        try {
            val baseline = gpuProbe(renderer, "cloud-baseline", pump) { probe -> probe.pumpFrames(30); timeFrames(probe, 120) }
            say("empty frame: ${"%.3f".format(baseline)} ms")
            val gpu = HashMap<Int, CloudScene.Image>()
            for (n in everies) {
                gpu[n] = dispatch(renderer, pump, scene, net, ambient, n, W, H, timed = false).first
                val ms = dispatch(renderer, pump, scene, net, ambient, n, TIME_W, TIME_H, timed = true).second
                val parity = cloudError(gpu[n]!!, cpu[n]!!, mask)
                val quality = cloudError(gpu[n]!!, reference, mask)
                val cpuQuality = cloudError(cpu[n]!!, reference, mask)
                say(
                    "N=$n  gpu-vs-cpu ${fmt(parity)}  gpu-vs-reference ${fmt(quality)}  cpu-vs-reference ${fmt(cpuQuality)}  " +
                        "frame ${"%.3f".format(ms)} ms at ${TIME_W}x$TIME_H (${"%.3f".format(ms - baseline)} ms over empty)",
                )
            }
            val wrongGpu = dispatch(renderer, pump, scene, wrong, ambient, 1, W, H, timed = false).first
            val parity = cloudError(gpu[1]!!, cpu[1]!!, mask)
            val wrongParity = cloudError(wrongGpu, cpu[1]!!, mask)
            say("wrong network (outputs x1.5) gpu-vs-cpu ${fmt(wrongParity)}")
            if (parity.relativeMeanAbs > PARITY_TOLERANCE) { say("FAIL: parity ${parity.relativeMeanAbs} > $PARITY_TOLERANCE"); failures++ }
            if (wrongParity.relativeMeanAbs < 10 * parity.relativeMeanAbs) { say("FAIL: a wrong network is not distinguishable"); failures++ }
            val dir = File("build/neural_clouds").apply { mkdirs() }
            writeCloudComparison(File(dir, "gpu_parity.png"), cpu[1]!!, listOf(gpu[1]!!, gpu[4]!!), errorGain = 16f)
            File(dir, "gpu_parity.txt").writeText(out.toString())
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println(if (failures == 0) "neural cloud parity PASSED" else "neural cloud parity FAILED ($failures)")
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun fmt(e: CloudError) = "relMAE ${"%.3e".format(e.relativeMeanAbs)} RMSE ${"%.3e".format(e.rmse)} (${e.pixels} px)"

    private fun timeFrames(probe: GpuProbe, n: Int): Double {
        val s = System.nanoTime()
        probe.pumpFrames(n)
        return (System.nanoTime() - s) / 1e6 / n
    }

    /** One compute stage rendering the cloud at [w] x [h]; returns the image and, if [timed], ms per frame. */
    private fun dispatch(
        renderer: Renderer, pump: () -> Unit, scene: CloudScene, net: NeuralCloud, ambient: afield.graphics.volume.AmbientFit,
        every: Int, w: Int, h: Int, timed: Boolean,
    ): Pair<CloudScene.Image, Double> = gpuProbe(renderer, "cloud-$every-$w", pump) { probe ->
        val pixels = w * h
        val groups = (pixels + LOCAL - 1) / LOCAL
        val output = probe.fixture("pixels", groups * LOCAL * 3)
        probe.stage("cloud", shader(scene, net, ambient, every, w, h)).dispatch(groups = groups) { bind("pixelsOut") { output } }
        probe.settle(output, seconds = 120.0)
        check(probe.verdict() == 0) { "cloud shader failed" }
        val ms = if (timed) { probe.pumpFrames(5); timeFrames(probe, 120) } else 0.0
        val got = probe.read(output, pixels * 3)
        Pair(CloudScene.Image(w, h, got), ms)
    }

    private fun shader(scene: CloudScene, net: NeuralCloud, ambient: afield.graphics.volume.AmbientFit, every: Int, w: Int, h: Int) = buildString {
        append("#version 460\n#extension GL_EXT_buffer_reference : require\n")
        append(NeuralCloud.glslInclude(net, ambient))
        append(scene.glslScene())
        append(
            """
            layout(std430, buffer_reference) buffer Pixels { float v[]; };
            layout(push_constant) uniform dat { Pixels pixelsOut; } pc;
            layout(local_size_x = $LOCAL) in;
            void main() {
                uint idx = gl_GlobalInvocationID.x;
                if (idx >= ${w * h}u) { pc.pixelsOut.v[idx * 3u] = 0.0; pc.pixelsOut.v[idx * 3u + 1u] = 0.0; pc.pixelsOut.v[idx * 3u + 2u] = 0.0; return; }
                float col = float(idx % ${w}u), row = float(idx / ${w}u);
                vec2 ndc = vec2((col + 0.5) / $w.0 * 2.0 - 1.0, 1.0 - (row + 0.5) / $h.0 * 2.0);
                vec3 e = ${v3(view.eye)}, t = ${v3(view.target)};
                vec3 c = cloudRenderEvery(e, t, ${CloudScene.glslLiteral(view.fovY)}, ndc, ${CloudScene.glslLiteral(w.toDouble() / h)}, ${CloudScene.MODE_NETWORK}, vec2(col, row), $every);
                pc.pixelsOut.v[idx * 3u] = c.r;
                pc.pixelsOut.v[idx * 3u + 1u] = c.g;
                pc.pixelsOut.v[idx * 3u + 2u] = c.b;
            }
            """.trimIndent(),
        )
        append("\n")
    }

    private fun v3(a: DoubleArray) = "vec3(${a.joinToString { CloudScene.glslLiteral(it) }})"
}

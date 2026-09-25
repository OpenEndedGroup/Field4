package afield.four.fabricated

import afield.Box
import afield.four.Renderer
import afield.four.Target
import afield.four.device.lightingCapabilityReport
import afield.four.lighting.LightingGpuTimer
import afield.four.util.Options
import afield.four.util.Window
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.raytracing.gpu-audit
runner: jvm-main
entrypoint: afield.four.fabricated.RayTracingGpuAudit
requiresDisplay: true
timeoutSec: 900
tags: [graphics, raytracing, pathtracing, gpu]
expect:
  exitCode: 0
  stdoutContains:
    - "RAY TRACING AUDIT DONE"
  stdoutNotContains: ["VUID-"]
  stderrNotContains: ["VUID-"]
*/
/** Runs the existing Field renderer and readback smokes on one thread, without a saved document.
 * Default: seams, base/glTF/glass, camera, temporal, fog, pipeline captures.
 * --all adds emitters, medium and GPU benchmarks.
 * --stage=NAME selects one stage. --require-ray-query and --require-pipeline fail closed on an
 * unsuitable device; otherwise unsupported pipeline captures are explicitly SKIPPED in report.json.
 * --fallback disables optional RT negotiation to exercise ordinary compute rendering.
 */
object RayTracingGpuAudit {
    @JvmStatic fun main(args: Array<String>) {
        val stages = linkedSetOf("seams", "base", "camera", "temporal", "fog", "pipeline")
        val extended = setOf("emitters", "medium", "benchmark")
        require(args.all { it in setOf("--all", "--fallback", "--require-ray-query", "--require-pipeline") ||
            it.startsWith("--stage=") || it.startsWith("--output=") }) { "Unknown audit argument" }
        val selected = args.filter { it.startsWith("--stage=") }.map { it.substringAfter('=') }
            .ifEmpty { stages.toList() + if ("--all" in args) extended else emptySet() }
        require(selected.all { it in stages || it in extended }) { "Stages: ${stages + extended}" }
        val out = File(args.firstOrNull { it.startsWith("--output=") }?.substringAfter('=')
            ?: "build/raytracing-audit/gpu").also { it.mkdirs() }
        Options.enableVR = false
        Options.enableHDR = false
        Options.logShadercSource = false
        if ("--fallback" in args) Options.enableRayTracing = false
        Options.doVulkanSDK()
        val report = JSONObject().put("startedAt", java.time.Instant.now().toString())
        val results = JSONArray()
        report.put("stages", results)
        fun save() = File(out, "report.json").writeText(report.toString(2))
        val window = Window("Field · ray tracing audit")
        val renderer = Renderer(window)
        val box = Box("ray-tracing-audit")
        box.setPropertyRaw("renderer", renderer, doResync = false)
        box.setPropertyRaw("window", window, doResync = false)
        try {
            report.put("capabilities", renderer.device.lightingCapabilityReport())
            save()
            check("--require-ray-query" !in args || renderer.device.supportsRayQuery) { "Ray queries required but not enabled" }
            check("--require-pipeline" !in args || renderer.device.supportsRayTracing) { "RT pipeline required but not enabled" }
            Box.withBoxContextNow(box) {
                // Renderer uses runBlocking internally while recording. A runBlocking event loop
                // around the audit would resume checks *inside* that recording, before submission.
                // Queue continuations explicitly and resume them only between complete frames.
                val pending = ConcurrentLinkedQueue<Runnable>()
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
                }
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    val checks = scope.async {
                        for (stage in selected) {
                            val result = JSONObject().put("stage", stage)
                            results.put(result)
                            save()
                            val start = System.nanoTime()
                            try {
                                println("AUDIT START $stage")
                                withTimeout(3_600_000L) {
                                    when (stage) {
                                        "seams" -> PathTraceGpuSmoke.runSeams(renderer)
                                        "base" -> PathTraceGpuSmoke.run(renderer)
                                        "camera" -> PathTraceGpuSmoke.runCamera(renderer)
                                        "temporal" -> PathTraceTemporalGpuSmoke.run(renderer)
                                        "fog" -> PathTraceTemporalGpuSmoke.runFog(renderer)
                                        "emitters" -> PathTraceEmitterGpuSmoke.run(renderer, File(out, "emitters").path)
                                        "medium" -> PathTraceMediumGpuSmoke.run(renderer, File(out, "medium").path)
                                        "benchmark" -> result.put("measurements", benchmark(renderer))
                                        "pipeline" -> if (renderer.device.supportsRayTracing) {
                                            capturePipeline(renderer, File(out, "pipeline"))
                                        } else {
                                            result.put("status", "SKIPPED").put("reason", "VK_KHR_ray_tracing_pipeline is not enabled")
                                            println("AUDIT SKIPPED pipeline: VK_KHR_ray_tracing_pipeline is not enabled")
                                        }
                                    }
                                }
                                if (!result.has("status")) result.put("status", "PASS")
                                println("AUDIT ${result.getString("status")} $stage")
                            } catch (t: Throwable) {
                                result.put("status", "FAIL").put("error", t.stackTraceToString())
                                throw t
                            } finally {
                                result.put("elapsedSeconds", (System.nanoTime() - start) / 1e9)
                                save()
                            }
                        }
                    }
                    while (!checks.isCompleted) {
                        while (true) (pending.poll() ?: break).run()
                        if (checks.isCompleted) break
                        check(!window.shouldClose()) { "Audit window closed before completion" }
                        window.pollEvents()
                        renderer.render()
                    }
                    runBlocking { checks.await() }
                } finally {
                    scope.cancel()
                }
            }
            report.put("complete", true)
            println("RAY TRACING AUDIT DONE: ${out.absolutePath}")
        } catch (t: Throwable) {
            report.put("complete", false).put("error", t.stackTraceToString())
            throw t
        } finally {
            save()
            renderer.cleanup()
            window.cleanup()
        }
        exitProcess(0)
    }

    /** Fresh files plus assertions on shader output: a screenshot of an empty box cannot pass. */
    suspend fun capturePipeline(renderer: Renderer, out: File) {
        out.mkdirs()
        val uv = RayTracingDocsHarness.renderUvGradient(File(out, "rt-uv-gradient.png").path, renderer)
        val image = ImageIO.read(uv)
        check(image.width == 512 && image.height == 512)
        for (y in 0 until 512 step 31) for (x in 0 until 512 step 31) {
            val rgb = image.getRGB(x, y)
            check(abs((rgb ushr 16 and 255) - (x + 0.5) * 255 / 512) < 1.1 &&
                abs((rgb ushr 8 and 255) - (y + 0.5) * 255 / 512) < 1.1 &&
                (rgb and 255) == 51 && (rgb ushr 24) == 255) { "UV dispatch/image binding mismatch at $x,$y" }
        }
        val triangle = RayTracingDocsHarness.renderTriangleScene(File(out, "rt-triangle-scene.png").path, renderer)
        val tiles = RayTracingDocsHarness.renderShaderTileScene(File(out, "rt-shader-tiles.png").path, renderer)
        val mirrors = RayTracingDocsHarness.renderMirrorBallsScene(File(out, "rt-mirror-balls.png").path, renderer)
        // Barycentric triangle must hit all three vertices' colour regions; misses alone cannot pass.
        val tri = ImageIO.read(triangle)
        for (channel in 0..2) {
            val shift = channel * 8
            val hits = (0 until tri.width).sumOf { x -> (0 until tri.height).count { y ->
                val rgb = tri.getRGB(x, y)
                (rgb ushr shift and 255) > 180 && (0..2).filter { it != channel }.all { (rgb ushr (it * 8) and 255) < 65 }
            } }
            check(hits > 150) { "Missing barycentric channel $channel: $hits pixels" }
        }
        for (file in listOf(tiles, mirrors)) {
            val rendered = ImageIO.read(file)
            var dark = 0; var light = 0
            for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                val r = rendered.getRGB(x, y) ushr 16 and 255
                if (r < 70) dark++
                if (r > 150) light++
            }
            check(dark > 1000 && light > 1000) { "${file.name}: checker geometry missing ($dark dark / $light light)" }
        }
        val scene = RayTracingDocsHarness.installMirrorBallsSceneInWindow(renderer,
            name="audit-live-mirrors", width=512, height=512)
        try {
            fun pixels(file: File): IntArray = ImageIO.read(file).let { it.getRGB(0,0,it.width,it.height,null,0,it.width) }
            val original=pixels(scene.capture(File(out,"mirrors-live.png").path))
            val sphere=scene.sphere(1)
            scene.setSphere(1,sphere.x+0.45f,sphere.y+0.35f,sphere.z+0.4f)
            val moved=pixels(scene.capture(File(out,"mirrors-moved.png").path))
            check(original.indices.count { original[it]!=moved[it] }>250) { "Procedural sphere transform did not change its traced image" }
            scene.setSphere(1,sphere.x,sphere.y,sphere.z)
            val restored=pixels(scene.capture(File(out,"mirrors-restored.png").path))
            check(original.contentEquals(restored)) { "Restored instance transform retained stale geometry" }
            scene.animate { seconds, live -> live.setSphere(1,sphere.x,sphere.y+0.2f*kotlin.math.sin(seconds*4).toFloat(),sphere.z) }
            scene.capture(File(out,"mirrors-animated.png").path)
            scene.clearAnimation()
            val replacement=RayTracingDocsHarness.installMirrorBallsSceneInWindow(renderer,
                name=scene.name,width=384,height=256)
            try {
                scene.cleanup() // Closing the replaced instance must not detach its successor.
                val replaced=ImageIO.read(replacement.capture(File(out,"mirrors-replaced.png").path))
                check(replaced.width==384 && replaced.height==256)
                check(renderer.computeList.rayTracingModules.containsKey(scene.name))
            } finally { replacement.cleanup() }
        } finally { scene.cleanup() }
        println("PASS ray pipeline: UV pixels, triangle hit/miss, shader tiles, procedural sphere SBT and captures")
        println("PASS live ray pipeline: moving/restored/animated TLAS, named replacement and resize")
    }

    /** GPU dispatch durations, excluding presentation/readback/AS construction. */
    private suspend fun benchmark(renderer: Renderer): JSONArray {
        val results=JSONArray()
        val backends=listOf(PathTracingRenderer.Backend.COMPUTE_BVH) +
            if(renderer.device.supportsRayQuery)listOf(PathTracingRenderer.Backend.RAY_QUERY) else emptyList()
        for(backend in backends) for(width in listOf(640,1280)) for(spp in listOf(1,4)) {
            val height=width*9/16
            PathTraceDemo.install(renderer,width=width,height=height,backend=backend).use { tracer ->
                val timer=LightingGpuTimer(renderer)
                tracer.samplesPerFrame=spp;tracer.maxBounces=5
                tracer.materialModule.current.before["audit-timer"] = Target { command,index -> timer.begin(command,index) }
                tracer.materialModule.current.after["audit-timer"] = Target { command,index -> timer.end(command,index) }
                try {
                    PathTraceGpuSmoke.awaitSamples(tracer,32*spp)
                    timer.samples.clear()
                    val deadline=System.nanoTime()+60_000_000_000L
                    while(timer.samples.size<256) { check(System.nanoTime()<deadline) { "GPU timestamps unavailable or benchmark stalled" };delay(1) }
                    tracer.paused=true
                    val values=timer.samples.toList().takeLast(256).sorted()
                    check(values.all { it.isFinite() && it > 0.0 }) { "Invalid GPU timestamp durations: $values" }
                    results.put(JSONObject().put("backend",backend.name).put("width",width).put("height",height)
                        .put("samplesPerFrame",spp).put("bounces",5).put("triangles",tracer.triangleCount)
                        .put("warmupFrames",32).put("timingSamples",values.size)
                        .put("gpuMs",JSONObject().put("p50",values[128]).put("p95",values[243])
                            .put("min",values.first()).put("max",values.last())))
                    println("BENCHMARK $backend ${width}x$height spp=$spp p50=${values[128]} p95=${values[243]} ms")
                } finally {
                    tracer.materialModule.current.before.remove("audit-timer")
                    tracer.materialModule.current.after.remove("audit-timer")
                    renderer.cleanup += Runnable { timer.close() }
                }
            }
        }
        return results
    }
}

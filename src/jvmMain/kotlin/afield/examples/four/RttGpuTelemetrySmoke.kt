package afield.examples.four

import afield.graphics.render.*
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.CPUSpan
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.rtt.gpu-telemetry
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.RttGpuTelemetrySmoke
tags: [smoke, four, gpu, profiling]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains: ["RTT GPU telemetry PASSED"]
  stdoutNotContains: ["VUID-", "Validation Error"]
  stderrNotContains: ["VUID-", "Validation Error"]
*/

/** Real driver queries over nested RTTs: nonblocking readback, positive draw counts and owner cleanup. */
object RttGpuTelemetrySmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR = false
        Options.validate = true
        Options.profiling = false
        val window = Window("RTT GPU telemetry")
        val renderer = Renderer(window)
        try {
            fun target(name: String) = RenderToTexture(renderer) {
                width = 64; height = 64; useDepth = false; debugName = name
            }.apply { profileGpu = true; profileGpuStatistics = true; profileGpuDraws = true }
            val child = target("probe-child")
            val parent = target("probe-parent")
            val stereo = MultiviewStereoTarget(renderer, eyeWidth = 64, eyeHeight = 64, withDepth = false)
            stereo.layered.profileGpuDraws = true
            fun draw(target: RenderToTexture, name: String = "draw") {
                val module = Module(parameters(renderer) {
                    +target
                    depthTest = false; depthWrite = false; blend = false
                    vertex = """#version 450
                    void main() {
                        vec2 q=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                        gl_Position=vec4(q*2.0-1.0,0,1);
                    }"""
                    fragment = """#version 450
                    layout(location=0) out vec4 color;
                    void main() { color=vec4(1,0,0,1); }"""
                }).pointer()
                val mesh = Mesh(renderer, module)
                mesh.resize(3, 0)
                module += "triangle" to mesh
                target += name to module
            }
            draw(child); draw(parent, "first"); draw(parent, "second")
            draw(stereo.layered)
            parent.modules.getValue("first").current.before["child"] = child
            renderer.renderList.preTargets["parent"] = parent
            renderer.desktopPresentationEnabled = false
            window.presentationEnabled = false
            CPUSpan.resetSamples()
            fun untilSample(predicate: () -> Boolean) {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!predicate()) {
                    check(System.nanoTime() < deadline) { "GPU sample timed out at renderer frame ${renderer.frameCounter}" }
                    window.pollEvents()
                    renderer.render()
                    // Nonblocking swapchain acquisition can skip a call; count completed samples,
                    // not a fixed number of CPU render attempts made before presentation catches up.
                    Thread.sleep(1)
                }
            }
            untilSample {
                listOf(child, parent).all { (it.gpuSample?.frame ?: -1L) >= 60L } &&
                    (stereo.layered.gpuDrawSamples["module:draw"]?.frame ?: -1L) >= 60L
            }
            check(parent.gpuDrawSamples.keys == setOf("module:first", "module:second"))
            check(parent.gpuDrawSamples.values.all { it.milliseconds >= 0.0 && it.invocations.isEmpty() })
            check(!renderer.supportsDirectWindowCapture(window))
            check(CPUSpan.snapshot().none { it.label == "acquire[0]" || it.label == "present[0]" }) {
                "Offscreen scene depended on desktop acquisition/presentation"
            }
            for (target in listOf(child, parent)) {
                val sample = checkNotNull(target.gpuSample) { "Driver query did not become available" }
                check(sample.milliseconds > 0) { "No GPU duration: $sample" }
                if (renderer.device.pipelineStatisticsQueryEnabled)
                    check((sample.invocations["fragment"] ?: 0L) > 0) { "No fragment invocations: $sample" }
                println("RTT sample $sample")
                check(sample.frame >= 60L) { "Query pool did not safely complete a second sample: $sample" }
            }
            child.profileGpuStatistics = false; parent.profileGpuStatistics = false
            untilSample { child.gpuSample!!.frame >= 120L && child.gpuSample!!.invocations.isEmpty() }
            check(child.gpuSample!!.invocations.isEmpty()) { "Timestamp-only mode retained invocation collection" }
            window.presentationEnabled = true
            renderer.desktopPresentationEnabled = true
            val switchFrame = renderer.frameCounter
            untilSample { renderer.frameCounter >= switchFrame + 6 }
            check(CPUSpan.snapshot().any { it.label == "present[0]" }) { "Desktop did not resume" }
            // Exercise mode changes after geometry has already reserved that frame's image/slot.
            renderer.beginGeometryWrites()
            renderer.endGeometryWrites()
            renderer.desktopPresentationEnabled = false
            renderer.render()
            renderer.beginGeometryWrites()
            renderer.endGeometryWrites()
            renderer.desktopPresentationEnabled = true
            renderer.render()
            renderer.desktopPresentationEnabled = false
            CPUSpan.resetSamples()
            val offscreenFrame = renderer.frameCounter
            untilSample { renderer.frameCounter >= offscreenFrame + 6 }
            check(CPUSpan.snapshot().none { it.label == "acquire[0]" || it.label == "present[0]" })
            child.profileGpu = false; parent.profileGpu = false
            repeat(3) { window.pollEvents(); renderer.render() }
        } finally {
            renderer.cleanup()
            window.cleanup()
        }
        println("RTT GPU telemetry PASSED")
        exitProcess(0)
    }
}

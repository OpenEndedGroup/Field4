package afield.examples.four

import afield.app.protocol.Rect
import afield.graphics.render.Module
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.Instance
import afield.graphics.vulkan.targets.SideBySideStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.Vector4f
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/*
FIELD_TEST
id: four.stereo-packing.smoke
runner: jvm-main
entrypoint: afield.examples.four.StereoPackingSmokeTest
requiresDisplay: true
tags: [smoke, vulkan, stereo, rtt]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "StereoPackingSmokeTest ok"
*/

/** Checks the first packed-image sampling, eye draw count, and eye aspect on a non-square window. */
object StereoPackingSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.enableVR = false
        Options.enableHDR = false
        val window = Window("Stereo packing smoke", initialDimensions = Rect(80.0, 80.0, 640.0, 320.0))
        val renderer = Renderer(window)
        val previousHandler = Instance.globalAsyncDebugHandler
        val diagnostics = CopyOnWriteArrayList<String>()
        try {
            // Establish the swapchain before building a stereo scene, as the starting point does.
            renderer.render()
            Instance.globalAsyncDebugHandler = { diagnostics.add(it) }
            val stereo = SideBySideStereoTarget(renderer, eyeWidth = 128, eyeHeight = 128)
            val drawnEyes = CopyOnWriteArrayList<Int>()
            stereo += "eye-colours" to {
                val pass = Module(parameters(renderer) {
                    name = "stereo-packing-smoke"
                    depth = 1
                    depthTest = false
                    depthWrite = false
                    blend = false
                    vertex = """
                        #version 450
                        const vec2 positions[3] = vec2[](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
                        void main() { gl_Position = vec4(positions[gl_VertexIndex], 0, 1); }
                    """.trimIndent()
                    fragment = """
                        #version 450
                        layout(push_constant) uniform Push { vec4 colour; } pc;
                        layout(location=0) out vec4 result;
                        void main() { result = pc.colour; }
                    """.trimIndent()
                }).pointer()
                val mesh = StandardRenderer.run { pass.ensureFullscreenTriangle("eye") }
                mesh.push["colour"] = Vector4f(0f, 0f, 0f, 1f)
                // Observe actual render-pass invocations, rather than a prefetched update sequence.
                pass.current.before["eye"] = Target { _, _ ->
                    drawnEyes.add(stereo.currentEyeIndex)
                    val projection = stereo.currentEyeProjectionMatrix()
                    check(abs(abs(projection[0] / projection[5]) - 1f) < 1e-4f) {
                        "Square eye inherited the non-square window's aspect"
                    }
                    mesh.push["colour"] = if (stereo.currentEyeIndex == 0)
                        Vector4f(1f, 0f, 0f, 1f) else Vector4f(0f, 1f, 0f, 1f)
                }
                pass
            }
            // Sampling in the same frame exercises the initial copy and descriptor preparation.
            stereo.packed.blitToScreen(renderer = renderer, target = renderer.renderList, name = "stereo")
            var renderedFrames = 0
            for (attempt in 0 until 30) {
                drawnEyes.clear()
                window.pollEvents()
                renderer.render()
                // Acquiring a resized/out-of-date swapchain can skip a frame without drawing.
                if (drawnEyes.isEmpty()) continue
                check(drawnEyes.toList() == listOf(0, 1)) { "Frame $attempt drew eyes $drawnEyes" }
                renderedFrames++
                if (renderedFrames == 6) break
            }
            check(renderedFrames == 6) { "Only $renderedFrames stereo frames rendered" }
            renderer.runOnQueueThread { org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(renderer.device.vkDevice) }
            check(diagnostics.isEmpty()) { "Vulkan diagnostics: ${diagnostics.joinToString("\n")}" }
            println("StereoPackingSmokeTest ok — six frames, one draw per eye, square projection, clean validation")
        } finally {
            Instance.globalAsyncDebugHandler = previousHandler
            renderer.cleanup()
            window.cleanup()
        }
    }
}

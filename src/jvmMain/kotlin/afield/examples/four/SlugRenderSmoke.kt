package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.bindPushConstant
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.graphics.FLine
import afield.graphics.slug.*
import afield.stdlib.linalg.*
import org.lwjgl.vulkan.VK10.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.slug.readback
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.SlugRenderSmoke
tags: [smoke, four, gpu, slug]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Slug rendering PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Real analytic coverage, alpha and immutable atlas replacement on the GPU. */
object SlugRenderSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val executable = File("native/field-slug/build/field-slug")
        fun rectangle(left: Double, right: Double) = FLine().moveTo(left, -0.8)
            .lineTo(right, -0.8).lineTo(right, 0.8).lineTo(left, 0.8).close()
        val shape = rectangle(-0.8, 0.8).moveTo(-0.25, -0.25)
            .lineTo(-0.25, 0.25).lineTo(0.25, 0.25).lineTo(0.25, -0.25).close()
        val prepared = SlugPath.compile(shape, executable)
        val replacement = SlugPath.compile(rectangle(0.4, 0.8), executable)
        Options.enableVR = false
        Options.validate = true
        val window = Window("Slug coverage regression")
        val renderer = Renderer(window)
        try {
            val target = RenderToTexture(renderer) {
                width = 128; height = 128; useDepth = false
                format = VK_FORMAT_R32G32B32A32_SFLOAT
                clearColor = Vector4f(0f, 0f, 0f, 0f)
            }
            renderer.renderList.preTargets["slug"] = target
            val module = Module(parameters(renderer) {
                vertex = SlugShaders.vertex; fragment = SlugShaders.fragment
                target.apply(); depthTest = false; depthWrite = false
                srcAlpha = VK_BLEND_FACTOR_ONE
            }).pointer()
            check(module.current.valid)
            val mesh = Mesh(renderer)
            module += "slug" to mesh
            target += "slug" to module
            mesh.resize(6)
            val data = SlugBuffer(renderer, prepared)
            var opacity = 1f
            var scale = 1f
            mesh.push.bindPushConstant("slugData", { data }) { error("slugData") }
            mesh.push.bindPushConstant("mvp", { Matrix4f().scaling(scale, scale, 1f) }) { error("mvp") }
            mesh.push.bindPushConstant("viewport", { Vector2f(128f, 128f) }) { error("viewport") }
            mesh.push.bindPushConstant("tint", { Vector4f(1f, 1f, 1f, opacity) }) { error("tint") }
            fun frame() { window.pollEvents(); renderer.render() }
            fun read(): FloatArray {
                repeat(4) { frame() }
                val result = target.readPixelsAsync()
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!result.isDone && System.nanoTime() < deadline) frame()
                check(result.isDone) { "Readback timeout" }
                val b = ByteBuffer.wrap(result.get()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(b.remaining()).also { b.get(it) }
            }
            fun alpha(p: FloatArray, x: Int, y: Int) = p[(y * 128 + x) * 4 + 3]
            fun near(actual: Float, expected: Float) = check(abs(actual - expected) < 0.02f) {
                "Expected $expected, got $actual"
            }
            val opaque = read()
            near(alpha(opaque, 64, 64), 0f)
            near(alpha(opaque, 32, 64), 1f)
            near(alpha(opaque, 2, 64), 0f)
            check(opaque.indices.any { it % 4 == 3 && opaque[it] in 0.05f..0.95f }) { "No antialiased edge pixels" }
            opacity = 0.5f
            val translucent = read()
            near(alpha(translucent, 32, 64), 0.5f)
            for (c in 0..2) near(translucent[(64 * 128 + 32) * 4 + c], opaque[(64 * 128 + 32) * 4 + c] * 0.5f)
            scale = 0.5f
            val smaller = read()
            near(alpha(smaller, 32, 64), 0f)
            near(alpha(smaller, 44, 64), 0.5f)
            scale = 1f
            val previous = data.getBuffer()
            data.replace(replacement)
            check(data.revision == 1L && data.getBuffer() != previous)
            val replaced = read()
            near(alpha(replaced, 32, 64), 0f)
            near(alpha(replaced, 104, 64), 0.5f)
            module.current.meshes.clear()
            data.close(); data.close()
            check(runCatching { data.getBuffer() }.isFailure)
            repeat(40) { frame() }
            println("Slug rendering PASSED: hole, edges, alpha, scale, replacement, close")
        } finally { renderer.cleanup(); window.cleanup() }
        exitProcess(0)
    }
}

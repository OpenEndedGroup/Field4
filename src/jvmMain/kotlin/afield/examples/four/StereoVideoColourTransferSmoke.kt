package afield.examples.four

import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.pointer
import afield.graphics.scene.video.StereoVideoSurface
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.stereo-video.colour-transfer
runner: jvm-main
entrypoint: afield.examples.four.StereoVideoColourTransferSmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, video]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "Stereo video colour transfer PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Actual decoded sharp video plus a controlled GPU accumulation, through the production presentation shader. */
object StereoVideoColourTransferSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = Files.createTempFile("field-colour-transfer", ".mkv")
        javaClass.getResourceAsStream("/afield/stereovideo/precision-ramp.mkv")!!.use {
            Files.copy(it, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Sharp colour transfer regression")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 1024, 64, withDepth = false)
        val movie = StereoVideoSurface(renderer, stereo)
        var accumulation: ModulePointer? = null
        val warmFragment = """
            #version 450
            layout(location=0) out vec4 color;
            void main() { color=vec4(.95,.2,.03,1); }
        """.trimIndent()
        movie.accumulationModuleFactory = { parameters ->
            parameters.vertex = javaClass.getResource("/afield/stereovideo/accumulation_vertex.glsl")!!.readText()
            parameters.fragment = warmFragment
            Module(parameters).pointer().also { accumulation = it }
        }
        fun frame() { window.pollEvents(); renderer.render(); check(renderer.lastRenderFailure == null) }
        fun read(): Array<FloatArray> {
            val request = stereo.layered.readPixelsAsync()
            val end = System.nanoTime() + 10_000_000_000L
            while (!request.isDone && System.nanoTime() < end) frame()
            val data = ByteBuffer.wrap(request.get()).order(ByteOrder.LITTLE_ENDIAN)
            return Array(1024) { x -> FloatArray(3) { c -> java.lang.Float.float16ToFloat(data.getShort((32 * 1024 + x) * 8 + c * 2)) } }
        }
        fun luminance(rgb: FloatArray) = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f
        try {
            check(movie.load(path.toString())) { movie.error ?: "No video" }
            val pointer = stereo.layered.modules.getValue("stereo-video")
            check(pointer.reload(pointer.current.parameters.copy(vertex = """
                #version 450
                #extension GL_EXT_multiview : require
                layout(location=0) out vec2 uv;
                layout(location=1) flat out int eye;
                void main() {
                    if(gl_VertexIndex>=3) { gl_Position=vec4(0,0,2,1);return; }
                    vec2 q=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                    uv=vec2(q.x,1-q.y);eye=gl_ViewIndex;gl_Position=vec4(q*2-1,0,1);
                }
            """.trimIndent())))
            val end = System.nanoTime() + 10_000_000_000L
            while (!movie.hasFrame && System.nanoTime() < end) frame()
            check(movie.hasFrame)
            movie.pause()
            movie.accumulationMix = 0f
            val original = read()
            check(movie.accumulationSamples == 0L)
            for (mix in listOf(.35f, 1f)) {
                movie.accumulationColourTransfer = mix
                frame()
                val tinted = read()
                check(movie.accumulationSamples == 1L) { "Sharp colour transfer failed to maintain a paused accumulation" }
                for (x in 0..1023) {
                    check(tinted[x].all { it.isFinite() && it in -.00001f..1.00001f }) { "Gamut or finite failure at $x" }
                    check(abs(luminance(tinted[x]) - luminance(original[x])) < .0007f) { "Transfer $mix changed luminance at $x" }
                }
                check(tinted[512][0] > tinted[512][1] * 1.5f && tinted[512][1] > tinted[512][2]) { "Warm chromaticity was not copied" }
            }
            // No transfer at the endpoints: exact bypass and a black reference with no chromaticity.
            movie.accumulationColourTransfer = 0f
            frame()
            val bypass = read()
            for (x in 0..1023) for (c in 0..2) check(abs(bypass[x][c] - original[x][c]) < .0001f)
            val fixture = accumulation!!
            check(fixture.reload(fixture.current.parameters.copy(fragment = warmFragment.replace(".95,.2,.03", "0,0,0"))))
            movie.accumulationColourTransfer = 1f
            movie.clearAccumulation()
            frame()
            val black = read()
            for (x in 0..1023) for (c in 0..2) check(abs(black[x][c] - original[x][c]) < .0001f) { "Black-reference fallback failed" }
            // Output cooling applies after both branches and brightness, and preserves their final luminance.
            movie.accumulationColourTransfer = 0f
            for ((mix, brightness) in listOf(0f to 1f, .6f to .7f)) {
                movie.accumulationMix = mix
                movie.brightness = brightness
                movie.shadowCooling = 0f
                frame()
                val neutral = read()
                movie.shadowCooling = 1f
                frame()
                val cooled = read()
                for (x in 0..1023) {
                    check(cooled[x].all { it.isFinite() && it in -.00001f..1.00001f })
                    check(abs(luminance(cooled[x]) - luminance(neutral[x])) < .0007f) { "Shadow cooling changed output luminance at $x" }
                    if (luminance(neutral[x]) >= .25f)
                        for (c in 0..2) check(cooled[x][c] == neutral[x][c]) { "Shadow cooling changed a highlight" }
                }
                check(cooled[256][2] > cooled[256][0] * 1.4f) { "No cool shadow tone" }
                movie.shadowCooling = 0f
                frame()
                val restored = read()
                for (x in 0..1023) for (c in 0..2) check(restored[x][c] == neutral[x][c]) { "Non-neutral cooling bypass" }
            }
            println("Stereo video colour transfer PASSED: sharp-only accumulation, chromaticity, luminance, gamut, bypass, black fallback")
        } finally {
            movie.close()
            renderer.cleanup()
            window.cleanup()
            Files.deleteIfExists(path)
        }
        exitProcess(0)
    }
}

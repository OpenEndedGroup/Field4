package afield.examples.four

import afield.graphics.scene.video.StereoVideoSurface
import afield.graphics.scene.video.VideoAutoExposure
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.pow
import kotlin.system.exitProcess
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT

/*
FIELD_TEST
id: four.stereo-video.precision
runner: jvm-main
entrypoint: afield.examples.four.StereoVideoPrecisionSmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, video]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "StereoVideo precision PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** A real lossless ten-bit video through P010 uploads, the movie shader and the shared eye target. */
object StereoVideoPrecisionSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Files.createTempFile("field-ten-bit-ramp", ".mkv")
        requireNotNull(javaClass.getResourceAsStream("/afield/stereovideo/precision-ramp.mkv")).use {
            Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Ten-bit stereo video regression")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 1024, 64, withDepth = false)
        val movie = StereoVideoSurface(renderer, stereo)
        try {
            check(movie.load(file.toString())) { movie.error ?: "Cannot load precision fixture" }
            check(movie.duration == 2.0) { "Container duration fallback failed: ${movie.duration}" }
            movie.seek(0.0)
            // A pixel-aligned test vertex replaces perspective only. The actual movie fragment,
            // decoder, upload, descriptor providers and eye target remain unchanged.
            val pointer = stereo.layered.modules.getValue("stereo-video")
            val vertex =
                """
                #version 450
                #extension GL_EXT_multiview : require
                layout(location=0) out vec2 uv;
                layout(location=1) flat out int eye;
                void main() {
                    if(gl_VertexIndex>=3) { uv=vec2(0);eye=gl_ViewIndex;gl_Position=vec4(0,0,2,1);return; }
                    vec2 q=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                    uv=vec2(q.x,1-q.y);eye=gl_ViewIndex;
                    gl_Position=vec4(q*2-1,0,1);
                }
                """
                    .trimIndent()
            check(pointer.reload(pointer.current.parameters.copy(vertex = vertex)))
            fun frame() {
                // StereoVideoSurface refreshes vertex count in beforeScene; the triangle uses the
                // first three vertices, and all later triangles are outside the viewport.
                window.pollEvents()
                renderer.render()
            }
            val deadline = System.nanoTime() + 15_000_000_000L
            while ((!movie.hasFrame || movie.timestamp != 0.0) && System.nanoTime() < deadline) frame()
            check(movie.hasFrame)
            check(stereo.layered.parameters.format == VK_FORMAT_R16G16B16A16_SFLOAT)
            fun read(): FloatArray {
                val request = stereo.layered.readPixelsAsync()
                val until = System.nanoTime() + 10_000_000_000L
                while (!request.isDone && System.nanoTime() < until) frame()
                val b = ByteBuffer.wrap(request.get()).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(1024) { x -> java.lang.Float.float16ToFloat(b.getShort((32 * 1024 + x) * 8)) }
            }
            val values = read()
            val distinct = values.map { java.lang.Float.floatToIntBits(it) }.toSet().size
            check(distinct == 1024) {
                "Ten-bit ramp reduced to $distinct distinct levels (${values.first()} .. ${values.last()})"
            }
            for (x in 0..1023) {
                val v = x / 1023f
                val expected = if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)
                check(abs(values[x] - expected) < .001f) { "Pixel $x: ${values[x]} versus $expected" }
            }
            movie.opacity = .25f
            frame()
            val translucent = read()
            for (x in 0..1023) check(abs(translucent[x] - values[x] * .25f) < .001f) {
                "Straight-alpha screen blend failed at $x"
            }
            movie.opacity = 1f
            // Zoetrope levels multiply linear light, preserving dim ramps and gains above one.
            for (gain in listOf(.125f, 2f, 0f)) {
                movie.brightness = gain
                frame()
                val exposed = read()
                for (x in 0..1023) check(abs(exposed[x] - values[x] * gain) < .002f) {
                    "Linear exposure $gain failed at $x: ${exposed[x]} versus ${values[x] * gain}"
                }
            }
            movie.brightness = 1f
            movie.swapEyes = true
            frame()
            val swapped = read()
            for (x in 0..1023) check(abs(swapped[x] - values[1023 - x]) < .001f) { "Eye swap failed at $x" }
            movie.seek(1.0)
            val seekDeadline = System.nanoTime() + 10_000_000_000L
            while (abs(movie.timestamp - 1.0) > .01 && System.nanoTime() < seekDeadline) frame()
            check(abs(movie.timestamp - 1.0) < .01 && !movie.playing) { "Paused seek was satisfied by an old frame" }
            movie.play()
            val loopDeadline = System.nanoTime() + 5_000_000_000L
            while (movie.timestamp >= .75 && System.nanoTime() < loopDeadline) frame()
            check(movie.playing && movie.timestamp < .75) { "Background playback did not loop" }
            // Seed a paused frame, then mix its inverse from the next source frame. Sampling
            // the held image at display cadence must not add it repeatedly to the exposure.
            movie.pause()
            movie.seek(0.0)
            while (movie.timestamp != 0.0) frame()
            movie.swapEyes = false
            movie.accumulationHalfLife = 60.0
            movie.accumulationMix = 1f
            frame()
            check(movie.accumulationSamples == 1L)
            repeat(10) { frame() }
            check(movie.accumulationSamples == 1L) { "Held source frame was accumulated repeatedly" }
            movie.play()
            val accumulationDeadline = System.nanoTime() + 5_000_000_000L
            while (movie.timestamp < .75 && System.nanoTime() < accumulationDeadline) frame()
            movie.pause()
            frame()
            check(movie.accumulationSamples == 2L) { "Expected two selected samples, got ${movie.accumulationSamples}" }
            val mixed = read()
            check(mixed[0] in .48f.. .54f && mixed[1023] in .46f.. .52f) {
                "Accumulation was not a linear-light average: ${mixed[0]}, ${mixed[1023]}"
            }
            movie.clearAccumulation()
            frame()
            val cleared = read()
            check(movie.accumulationSamples == 1L && cleared[0] > .99f && cleared[1023] < .001f) {
                "Clear did not replace the previous exposure with the held source frame"
            }
            movie.accumulationBlackLevel = .12f
            movie.accumulationWhiteLevel = .78f
            movie.accumulationGamma = 1.7f
            frame()
            val levelled = read()
            for (x in 0..1023) {
                val expected = ((cleared[x] - .12f) / .66f).coerceIn(0f, 1f).pow(1f / 1.7f)
                check(abs(levelled[x] - expected) < .002f) { "Accumulation levels failed at $x" }
            }
            check(movie.accumulationSamples == 1L) { "Presentation levels fed back into accumulation" }
            movie.accumulationMix = 0f
            frame()
            val sharpBypass = read()
            for (x in 0..1023) check(abs(sharpBypass[x] - cleared[x]) < .001f) { "Levels changed sharp input at $x" }
            movie.accumulationMix = 1f
            movie.accumulationBlackLevel = 1f
            movie.accumulationWhiteLevel = 0f
            movie.accumulationGamma = 1f
            frame()
            val inverted = read()
            for (x in 0..1023) check(abs(inverted[x] - (1f - cleared[x])) < .002f) { "Reversed levels failed at $x" }
            movie.accumulationBlackLevel = .4f
            movie.accumulationWhiteLevel = .4f
            frame()
            check(read().all { it.isFinite() && it in 0f..1f }) { "Coincident black/white levels produced invalid pixels" }
            movie.accumulationBlackLevel = 0f
            movie.accumulationWhiteLevel = 1f
            frame()
            val neutralLevels = read()
            for (x in 0..1023) check(abs(neutralLevels[x] - cleared[x]) < .001f) { "Presentation changed stored history at $x" }
            movie.autoExposure.settings = VideoAutoExposure.Settings(enabled = true, mode = VideoAutoExposure.Mode.PER_SCREEN,
                risingAlpha = 1f, risingBeta = 0f, fallingAlpha = 1f, fallingBeta = 0f,
                blackPadding = -.2f, whitePadding = -.2f, blackMaximum = 1f, whiteMinimum = 0f, whiteMaximum = 2f)
            val meterDeadline = System.nanoTime() + 10_000_000_000L
            while (movie.autoExposure.latest?.screens?.firstOrNull()?.valid != true && System.nanoTime() < meterDeadline) frame()
            check(movie.autoExposure.latest?.screens?.firstOrNull()?.valid == true) { "Video meter never became valid: ${movie.autoExposure.error}" }
            val automatic = read()
            for (x in 0..1023) check(abs(automatic[x] - ((cleared[x] - .2f) / .6f).coerceIn(0f, 1f)) < .002f) {
                "GPU automatic endpoints did not reach the presentation shader at $x"
            }
            movie.autoExposureMix = 0f
            frame()
            val manualBypass = read()
            for (x in 0..1023) check(abs(manualBypass[x] - cleared[x]) < .001f) { "Per-screen auto mix failed to bypass at $x" }
            movie.autoExposureMix = 1f
            movie.autoExposure.settings = movie.autoExposure.settings.copy(enabled = false, mode = VideoAutoExposure.Mode.OFF)
            // The real held-exposure + Gaussian path must preserve eye separation and precision.
            // A paused movie holds its first (soft/dark) half, making the readback deterministic.
            movie.exposureCadenceEnabled = true
            movie.exposureBlurMix = 1f
            movie.exposureBlurSigma = 4f
            movie.exposureDarkening = .25f
            movie.clearAccumulation()
            frame()
            val softened = read()
            check(softened[0] in .73f.. .751f && softened[1023] < .001f) {
                "Exposure darkening/seam clamp failed: ${softened[0]}, ${softened[1023]}"
            }
            // Blur reaches inward from the black endpoint of the ramp. Crossing the SBS seam
            // would instead introduce the neighbouring eye's white endpoint.
            check(softened[1023] > cleared[1023]) { "Gaussian did not soften the endpoint" }
            movie.exposureBlurMix = 0f
            movie.exposureDarkening = 0f
            frame()
            val held = read()
            check(held.map { it.toBits() }.toSet().size == 1024) { "Held exposure lost ten-bit precision" }
            for (x in 0..1023) check(abs(held[x] - cleared[x]) < .001f) { "Held copy changed pixel $x" }
            movie.exposureBlurMix = 1f
            movie.exposureBlurSigma = 0f
            frame()
            val zeroRadius = read()
            for (x in 0..1023) check(zeroRadius[x] == held[x]) { "Zero-radius blur softened pixel $x" }
            movie.exposureCadenceEnabled = false
            movie.accumulationMix = 0f
            frame()
            println("Video exposure cadence PASSED: held float16 copy, per-eye Gaussian, linear darkening, bypass")
            println("Video accumulation PASSED: float16 linear mean, held-frame gating, clear, sharp bypass")
            println(
                "StereoVideo precision PASSED: $distinct levels; P010 -> movie shader -> float eyes; paused seek held",
            )
        } finally {
            movie.close()
            renderer.cleanup()
            window.cleanup()
            Files.deleteIfExists(file)
        }
        exitProcess(0)
    }
}

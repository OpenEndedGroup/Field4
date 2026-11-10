package afield.examples.four

import afield.graphics.scene.video.StereoVideoCarousel
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

/*
FIELD_TEST
id: four.stereo-video.carousel
runner: jvm-main
entrypoint: afield.examples.four.StereoVideoCarouselSmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, video]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "StereoVideo carousel PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Real P010 GPU copies, eight independently accumulated surfaces, and a shared decoder through seek/refill. */
object StereoVideoCarouselSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val file = Files.createTempFile("field-carousel-ramp", ".mkv")
        javaClass.getResourceAsStream("/afield/stereovideo/precision-ramp.mkv")!!.use {
            Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Shared stereo carousel regression")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 1024, 64, withDepth = false)
        val carousel = StereoVideoCarousel(renderer, stereo)
        try {
            check(carousel.load(file.toString())) { carousel.source.error ?: "Cannot load fixture" }
            check(carousel.surfaces.all { it.stream === carousel.source })
            val modules = (0..7).map { stereo.layered.modules.getValue("carousel-screen-$it") }
            val vertex = """
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
            """.trimIndent()
            modules.forEach { check(it.reload(it.current.parameters.copy(vertex = vertex))) }
            carousel.surfaces.forEach {
                it.accumulationMix = 1f
                it.accumulationHalfLife = 0.0
                // The reference curves all lie on the same inward-facing cylinder.
                val p = it.placement
                for (u in listOf(0f, .5f, 1f)) {
                    val world = p.model().transformPosition(p.point(u, .5f))
                    check(abs(world.length() - carousel.radius) < .001f) { "Screen did not follow carousel cylinder" }
                }
            }
            fun frame() { window.pollEvents(); renderer.render() }
            fun until(condition: () -> Boolean) {
                val limit = System.nanoTime() + 10_000_000_000L
                while (!condition() && System.nanoTime() < limit) frame()
                check(condition()) { "Carousel timed out" }
            }
            fun read(screen: Int): FloatArray {
                modules.forEachIndexed { i, module -> module.current.enabled = i == screen }
                val request = stereo.layered.readPixelsAsync()
                until { request.isDone }
                val bytes = ByteBuffer.wrap(request.get()).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(1024) { x -> java.lang.Float.float16ToFloat(bytes.getShort((32 * 1024 + x) * 8)) }
            }
            carousel.source.seek(0.0)
            until { carousel.surfaces.all { it.hasFrame && it.timestamp == 0.0 } }
            val original = read(0)
            check(original.map { it.toBits() }.toSet().size == 1024) { "P010 history copy lost ten-bit levels" }
            carousel.source.play()
            until { carousel.source.timestamp >= 1.0 }
            carousel.source.pause()
            frame()
            check(carousel.source.retainedFrames == 2)
            check(carousel.surfaces.first().timestamp == 1.0)
            check(carousel.surfaces.drop(1).all { it.timestamp == 0.0 }) { "Delayed surfaces read the live decoder slot" }
            for (screen in 0..7) {
                val pixels = read(screen)
                for (x in 0..1023) {
                    val encoded = if (screen == 0) (1023 - x) / 1023f else x / 1023f
                    val expected = if (encoded <= .04045f) encoded / 12.92f else ((encoded + .055f) / 1.055f).pow(2.4f)
                    check(abs(pixels[x] - expected) < .001f) { "Screen $screen pixel $x: ${pixels[x]} vs $expected" }
                }
            }
            val samples = carousel.surfaces.map { it.accumulationSamples }
            repeat(8) { frame() }
            check(carousel.surfaces.map { it.accumulationSamples } == samples) { "Held delayed input accumulated repeatedly" }
            carousel.surfaces.first().apply {
                exposureCadenceEnabled = true
                exposureBlurSigma = 0f
                exposureDarkening = .25f
                clearAccumulation()
            }
            frame()
            check(read(0)[0] in .749f.. .751f)
            check(read(1)[1023] > .999f) { "A screen's cadence settings leaked into another screen" }
            carousel.source.seek(0.0)
            until { carousel.source.timestamp == 0.0 }
            check(carousel.source.retainedFrames == 1 && carousel.surfaces.all { it.timestamp == 0.0 })
            carousel.spacingFrames = 4
            frame()
            check(carousel.source.historyFrames == 29)
            check(carousel.surfaces.map { it.delayFrames } == (0..7).map { it * 4 })
            check(carousel.surfaces.all { it.hasFrame }) { "Spacing edit lost the held source frame" }
            println("StereoVideo carousel PASSED: one source, exact P010 history, eight independent exposures, cadence isolation, seek/refill")
        } finally {
            carousel.close()
            renderer.cleanup()
            window.cleanup()
            Files.deleteIfExists(file)
        }
        exitProcess(0)
    }
}

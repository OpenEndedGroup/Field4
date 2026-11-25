package afield.graphics.scene.reading

import afield.graphics.text.Typefaces
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.reading.text-blur-energy
runner: jvm-main
entrypoint: afield.graphics.scene.reading.ReadingTextBlurEnergySmoke
requiresDisplay: true
tags: [smoke, vulkan, stereo, reading]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Reading text blur energy PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Measures actual cached Gaussian energy and the production text draw before final grading. */
object ReadingTextBlurEnergySmoke {

    /** Largest gap between alpha and light (half of red plus blue) over [image]'s RGBA pixels. */
    private fun worstNeutral(image: FloatArray): Float =
        (image.indices step 4).maxOf { abs(image[it + 3] - .5f * (image[it] + image[it + 2])) / maxOf(1f, abs(image[it])) }

    /** With no palette every pixel is at the neutral position, so alpha is light; float16 readback rounds. */
    private fun neutral(image: FloatArray) = worstNeutral(image) < 4e-3f
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR = false
        Options.enableHDR = false
        Options.validate = true
        val window = Window("Reading text blur energy")
        val renderer = Renderer(window)
        val stereo = MultiviewStereoTarget(renderer, 800, 600, withDepth = false)
        stereo.stereoCamera.base.at = Vector3f(0f, 0f, 2.35f)
        stereo.stereoCamera.base.target = Vector3f()
        val graph = readingDiagram {
            text("word", "glow", point("p", 0, 0, pinned = true)) { blurCapacity = 1.5f }
        }
        val player = ReadingDiagramPlayer.prepare(graph, Typefaces.named("Serif", 100),
            File(args.firstOrNull() ?: "native/field-slug/build/field-slug"), diagramScore(graph, 10.0) {})
        player.playback.playing = false
        val drawing = StereoDiagramRenderer(renderer, stereo, player)
        drawing.followReader = false
        val cache = drawing.localBlurCaches.single()
        // Queue before the one-time preparation; readPixelsAsync copies the real R16F targets.
        val masks = cache.levels.map { it.readPixelsAsync() }
        fun frame() {
            val before = renderer.frameCounter
            val deadline = System.nanoTime() + 5_000_000_000L
            do { window.pollEvents(); renderer.render() } while (renderer.frameCounter == before && System.nanoTime() < deadline)
            check(renderer.lastRenderFailure == null && renderer.frameCounter > before)
        }
        fun decode(bytes: ByteArray): FloatArray {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 2) { java.lang.Float.float16ToFloat(b.getShort(it * 2)) }
        }
        fun scene(): FloatArray {
            val request = drawing.compositor.sharp.readPixelsAsync()
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!request.isDone && System.nanoTime() < deadline) frame()
            check(request.isDone)
            return decode(request.get())
        }
        fun energy(image: FloatArray) = (image.indices step 4).sumOf { image[it].toDouble() }
        try {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (masks.any { !it.isDone } && System.nanoTime() < deadline) frame()
            check(masks.all { it.isDone })
            val means = masks.map { decode(it.get()).average() }
            val ratios = means.map { it / means.first() }
            println("Gaussian mask energy ratios: $ratios")
            check(ratios.all { abs(it - 1.0) < .03 }) { "Cached blur does not preserve normalized energy" }
            val sharp = scene()
            graph.text("word").blur = .5f
            val linear = scene()
            val sceneRatio = energy(linear) / energy(sharp)
            println("Blurred/sharp scene energy before grading: $sceneRatio")
            check(abs(sceneRatio - 1.0) < .10) { "Cached and analytic Slug light disagree" }
            drawing.textBlurConfig.appearance.y = 1.8f
            val contrast = scene()
            check(energy(contrast) < energy(linear) * .8) { "Contrast did not trim blurred tails" }
            check(contrast.indices.filter { it % 4 == 1 }.all { contrast[it] == linear[it] }) {
                "Text appearance changed green control data"
            }
            // Alpha is light times palette position (DiagramPalette, e6f19d4f0d); with no palette it follows light.
            check(neutral(linear) && neutral(contrast)) {
                "Text appearance broke the palette position: ${maxOf(worstNeutral(linear), worstNeutral(contrast))}"
            }
            drawing.textBlurConfig.appearance.x = 2f
            val boosted = scene()
            check(abs(energy(boosted) / energy(contrast) - 2.0) < .005)
            drawing.textBlurConfig.appearance.set(1f, 1f, 0f, 0f)
            val restored = scene()
            check(restored.contentEquals(linear)) { "Neutral appearance did not restore the original blur" }
            println("Reading text blur energy PASSED: normalized cache, gain, contrast, unchanged green, alpha at the neutral palette position, exact neutral reset")
        } finally {
            drawing.close()
            repeat(40) { frame() }
            renderer.renderList.preTargets.remove("stereo-multiview", stereo)
            renderer.cleanup()
        }
        exitProcess(0)
    }
}

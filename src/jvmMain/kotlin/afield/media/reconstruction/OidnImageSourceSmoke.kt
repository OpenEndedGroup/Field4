@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.reconstruction

import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.oidn.image-source
requiresDisplay: true
runner: jvm-main
entrypoint: afield.media.reconstruction.OidnImageSourceSmoke
timeoutSec: 300
tags: [graphics, smoke, denoise]
expect:
  exitCode: 0
  stdoutContains:
    - "OIDN IMAGE SOURCE PASS"
*/
/**
 * [OidnComputeDenoiser] on an [OidnImageSource], with no path tracer: a soft disc kept per pixel with
 * probability equal to its opacity (stochastic transparency, one sample a pixel) must come back closer to the
 * smooth disc than the noisy input was, both through [OidnComputeDenoiser.denoiseImage] and, installed, once
 * per upload.
 */
object OidnImageSourceSmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR = false; Options.enableHDR = false; Options.enableRayTracing = false; Options.validate = false; Options.doVulkanSDK()
        val window = Window("Field OIDN image source"); val renderer = Renderer(window)
        val box = Box("oidn-image-source"); box.setPropertyRaw("renderer", renderer, doResync = false)
        try { Box.withBoxContextNow(box) {
            val w = 320; val h = 240; val rng = Random(7)
            val clean = FloatArray(w * h * 4); val noisy = FloatArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = (x - w / 2) / 90f; val dy = (y - h / 2) / 90f
                val opacity = (0.8f * kotlin.math.exp(-3f * (dx * dx + dy * dy))).coerceIn(0f, 1f)
                val i = (y * w + x) * 4
                for (c in 0..2) { val col = if (c == 0) .9f else if (c == 1) .6f else .3f
                    clean[i + c] = col * opacity; noisy[i + c] = if (rng.nextFloat() < opacity) col else 0f }
                clean[i + 3] = 1f; noisy[i + 3] = 1f
            }
            fun error(a: FloatArray) = a.indices.filter { it % 4 != 3 }.sumOf { abs(a[it] - clean[it]).toDouble() } / (w * h * 3)
            val image = OidnImageSource(renderer, w, h).upload(noisy)
            val denoiser = OidnComputeDenoiser(image)
            check(denoiser.tracer == null && denoiser.denoiseAt == 1)
            val denoised = denoiser.denoiseImage()
            val before = error(noisy); val after = error(denoised)
            println("noisy error %.4f, denoised error %.4f, %.0f ms".format(before, after, denoiser.lastDenoiseMs))
            check(after < before / 3) { "denoising did not bring the image near the smooth disc: $before -> $after" }

            // Installed: one denoise per upload, left in denoiser.output.
            denoiser.install()
            fun pump(n: Int) { val end = renderer.frameCounter + n; while (renderer.frameCounter < end) { window.pollEvents(); renderer.render() } }
            val count = denoiser.denoiseCount
            image.upload(noisy); pump(4)
            check(denoiser.denoiseCount == count + 1 && denoiser.current) { "expected one denoise after the upload, got ${denoiser.denoiseCount - count}" }
            val shown = ReconstructionCapture.read(renderer, denoiser.output)
            check(error(shown) < before / 3) { "denoiser.output does not hold the denoised image" }
            denoiser.close(); image.close(); pump(4)
        } } finally { renderer.cleanup(); window.cleanup() }
        println("OIDN IMAGE SOURCE PASS"); exitProcess(0)
    }
}

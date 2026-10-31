@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.vulkan

import afield.document.graph.Box
import afield.ui.output.displayOpen
import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Puts a picture of [renderer]'s window into this box's [channel] output, once [frames] frames have
 * been drawn after the call.
 *
 * Returns at once: the wait, the copy and the display happen in the background, so a lesson can end
 * with `snapshotWindow(renderer)` and its output area shows what the window drew a moment later. The
 * copy is the renderer's own swapchain image ([Renderer.captureWindowToFile]), not a screen grab, so the
 * window needs no focus and nothing is in front of it. The image is scaled to at most [width] pixels
 * wide and shown as an inline PNG, so it is saved with the box's output and published with the
 * document, which is how the offline viewer can show every lesson's result.
 *
 * Run it after the box has set up what it draws; a box that adds its module after this call would be
 * captured before it appears.
 */
fun Box.snapshotWindow(renderer: Renderer, frames: Int = 10, channel: String = "window", width: Int = 640): Job {
    val box = this
    return snapshotScope.launch {
        val target = renderer.frameCounter + frames
        val deadline = System.nanoTime() + 30_000_000_000L
        while (renderer.frameCounter < target) {
            if (System.nanoTime() >= deadline) throw IllegalStateException( "the renderer drew ${renderer.frameCounter - target + frames} of $frames frames in 30 s")
            delay(16)
        }
        val file = File.createTempFile("field-snapshot-", ".png")
        try {
            renderer.captureWindowToFile(outputPath = file.absolutePath)
            val full: BufferedImage = ImageIO.read(file) ?: throw IllegalStateException("the window capture wrote no image")
            val scale = minOf(1.0, width.toDouble() / full.width)
            val w = (full.width * scale).toInt().coerceAtLeast(1)
            val h = (full.height * scale).toInt().coerceAtLeast(1)
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { image ->
                val g = image.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(full, 0, 0, w, h, null)
                g.dispose()
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(small, "png", it) }.toByteArray()
            box.display(channel, "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(png)}' " +
                "alt='the renderer window' style='width:100%;max-width:${w}px;height:auto;display:block'/>")
            box.displayOpen(channel)
        } catch (t: Throwable) {
            lprintln("warning,graphics", "snapshotWindow for ${box.name.ifBlank { box.id }} failed: ${t.message}")
            box.display(channel, "<div>snapshotWindow failed: ${t.message}</div>")
        } finally {
            file.delete()
        }
    }
}

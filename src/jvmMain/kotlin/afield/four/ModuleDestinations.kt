@file:afield.CompletionCategory("Graphics")

package afield.four

import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.util.PngEncoder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Where a module's drawing ends up, and a picture of it there: the window it is added to, or the
 * render-to-texture that holds it — found by walking what the renderer draws, not by being told.
 *
 * `renderer += module` puts a module in a window's main pass; `rtt += module` in a render target, which
 * draws when something reaches it (usually the renderer's `preTargets`). Walking the windows' passes,
 * everything their `others` and `preTargets` reach, and the headset target finds every place that draws
 * the module, compared by identity. A target drawn by some other path is not found.
 */
object ModuleDestinations {
    /** A place that draws a module. */
    sealed class Destination {
        /** Window [index] of the renderer: its main pass. A capture of it holds everything the window draws. */
        data class Window(val renderer: Renderer, val index: Int) : Destination() {
            override fun toString() = if (index == 0) "the window" else "window ${index + 1}"
        }

        /** A render-to-texture, under the name something registered it by (or its own debug name). */
        data class Texture(val target: RenderToTexture, val name: String) : Destination() {
            override fun toString() = "render target '$name', ${target.width}×${target.height}"
        }

        /** The OpenXR headset's pass. */
        data class Headset(val renderer: Renderer) : Destination() {
            override fun toString() = "the headset"
        }
    }

    /** Every place [renderer] draws [module] — a graphics or mesh module pointer. Compute modules draw nowhere. */
    fun of(renderer: Renderer, module: Any): List<Destination> {
        if (module is ComputeModulePointer) return emptyList()
        val out = mutableListOf<Destination>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun holds(modules: Map<String, ModulePointer>, meshes: Map<String, MeshModulePointer> = emptyMap()) =
            modules.values.any { it === module } || meshes.values.any { it === module }
        fun visit(target: Any?, name: String, window: Int?) {
            if (target == null || !seen.add(target)) return
            when (target) {
                is RenderTarget -> {
                    if (holds(target.modules, target.meshModules)) out += Destination.Window(renderer, window ?: target.windowIndex)
                    (target.others + target.preTargets).forEach { (n, t) -> visit(t, n, null) }
                }
                is RenderToTexture -> if (holds(target.modules)) out += Destination.Texture(target, name)
            }
        }
        renderer.windows.indices.forEach { i -> visit(runCatching { renderer.windowContext(i).renderTarget }.getOrNull(), "window", i) }
        renderer.openXRHmdTarget?.let { hmd -> if (hmd.modules.values.any { it === module }) out += Destination.Headset(renderer) }
        return out
    }

    /**
     * A PNG of [destination] as it is being drawn: the window's swapchain, or the render target's first
     * colour attachment, both as displayed (sRGB-encoded bytes). Blocks until a frame has drawn it, so
     * call it off the main thread, which draws. Throws with the reason when it cannot: a render target
     * made without `colorTransferSrc`, a window that does not allow capture, or one that is not drawing.
     */
    fun capture(destination: Destination, timeoutMs: Long = 8000): ByteArray = when (destination) {
        is Destination.Window -> {
            val window = destination.renderer.windows[destination.index]
            require(destination.renderer.supportsDirectWindowCapture(window)) { "this window's swapchain does not allow capture" }
            val file = File.createTempFile("module-destination-", ".png")
            try {
                runBlocking { destination.renderer.captureWindowToFile(window, file.absolutePath, timeoutMs) }
                file.readBytes()
            } finally { file.delete() }
        }
        is Destination.Texture -> {
            val t = destination.target
            require(t.parameters.colorTransferSrc) { "render target '${destination.name}' was made without colorTransferSrc, so it cannot be read back" }
            val bytes = t.readPixelsAsync().get(timeoutMs, TimeUnit.MILLISECONDS)
            val rgba = GpuImageCapture.readRgba(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()), t.colorFormats.first(), t.width, t.height, destination.name)
            val argb = IntArray(t.width * t.height) { i ->
                val o = i * 4
                (0xff shl 24) or ((rgba[o].toInt() and 0xff) shl 16) or ((rgba[o + 1].toInt() and 0xff) shl 8) or (rgba[o + 2].toInt() and 0xff)
            }
            PngEncoder.encode(argb, t.width, t.height)
        }
        is Destination.Headset -> throw IllegalArgumentException("the headset's frames cannot be captured here")
    }
}

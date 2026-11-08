@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.ui.output

import afield.document.context.fromContext
import afield.graphics.texture.LoadedTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.RendersAfter
import afield.media.image.Img
import org.lwjgl.BufferUtils
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

/**
 * What a GPU version of [ShapeAscii] needs from the CPU side, for shaders that live in boxes.
 *
 * `ascii_shape.md` builds the GPU matcher out of boxes whose `fragment` properties are the shaders,
 * so they can be edited and re-run like any other: a scene box renders into an offscreen target a
 * whole number of font cells in size, a match box runs one fragment per cell into a second target
 * and writes the index of the nearest glyph, and a draw box turns those indices back into glyphs.
 * The shaders need two things only the CPU has, the glyphs' six numbers ([glyphVectorTexture]) and
 * their pixels ([glyphAtlasTexture]), and checking the GPU against [ShapeAscii.convert] needs both
 * targets read back from the same frame ([readCells]).
 */

private val vectorTextures = WeakHashMap<ShapeAscii, MutableMap<Renderer, LoadedTexture>>()
private val atlasTextures = WeakHashMap<ShapeAscii, MutableMap<Renderer, LoadedTexture>>()

/**
 * Every glyph's six numbers ([ShapeAscii.glyphVector]) as a float texture two texels high, one
 * column per glyph: texel `(g, 0)` holds the first four, `(g, 1)` the last two, then the glyph's
 * coverage ([ShapeAscii.glyphCoverage]) and 0. Read it with `texelFetch`. Made once per renderer.
 */
fun ShapeAscii.glyphVectorTexture(renderer: Renderer = fromContext()): LoadedTexture = synchronized(vectorTextures) {
    vectorTextures.getOrPut(this) { HashMap() }.getOrPut(renderer) {
        val n = glyphs.length
        val bytes = BufferUtils.createByteBuffer(n * 2 * 4 * 4)
        for (g in 0 until n) glyphVector(g).take(4).forEach { bytes.putFloat(it.toFloat()) }
        for (g in 0 until n) {
            val v = glyphVector(g)
            bytes.putFloat(v[4].toFloat()).putFloat(v[5].toFloat()).putFloat(glyphCoverage(g).toFloat()).putFloat(0f)
        }
        bytes.flip()
        LoadedTexture.fromRawRGBA("shape-ascii-vectors", n, 2, bytes, renderer, VK_FORMAT_R32G32B32A32_SFLOAT)
            .also { it.transferNow() }
    }
}

/**
 * Every glyph as [ShapeAscii.glyphImg] rasterised it, side by side, one cell each: `glyphs.length *
 * cellWidth` by `cellHeight` pixels, the glyph's ink in every channel, top row first. Made once per
 * renderer.
 */
fun ShapeAscii.glyphAtlasTexture(renderer: Renderer = fromContext()): LoadedTexture = synchronized(atlasTextures) {
    atlasTextures.getOrPut(this) { HashMap() }.getOrPut(renderer) {
        val cw = cellWidth
        val ch = cellHeight
        val n = glyphs.length
        val w = n * cw
        val bytes = BufferUtils.createByteBuffer(w * ch * 4)
        val rasters = Array(n) { glyphImg(it) }
        for (y in 0 until ch) for (x in 0 until w) {
            val v = (rasters[x / cw].data[y * cw + x % cw].coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()
            bytes.put(v).put(v).put(v).put(v)
        }
        bytes.flip()
        LoadedTexture.fromRawRGBA("shape-ascii-atlas", w, ch, bytes, renderer, VK_FORMAT_R8G8B8A8_UNORM)
            .also { it.transferNow() }
    }
}

/**
 * The next frame's [scene] and the glyphs a GPU matcher wrote into [cells] for it, read back
 * together. Both targets must be RGBA8 UNORM with `colorTransferSrc` on; [cells] holds one texel per
 * character with the glyph's index in red (index / 255), and [scene] is [cells]' size in this
 * charset's font cells. Both copies are queued before either target renders, so they come from the
 * same frame. Completes once that frame's GPU work has finished, so keep frames coming while waiting.
 */
fun ShapeAscii.readCells(scene: RenderToTexture, cells: RenderToTexture, renderer: Renderer = fromContext()): CompletableFuture<ShapeAsciiReadback> {
    val cols = cells.parameters.width
    val rows = cells.parameters.height
    val w = scene.parameters.width
    val h = scene.parameters.height
    require(w == cols * cellWidth && h == rows * cellHeight) {
        "the scene is ${w}x$h pixels; ${cols}x$rows cells of this font are ${cols * cellWidth}x${rows * cellHeight}"
    }
    val result = CompletableFuture<ShapeAsciiReadback>()
    PairedReads.on(renderer).queue {
        scene.readPixelsAsync(0).thenCombine(cells.readPixelsAsync(0)) { pixels, glyphBytes ->
            val data = FloatArray(w * h * 3)
            for (i in 0 until w * h) for (c in 0 until 3) data[i * 3 + c] = (pixels[i * 4 + c].toInt() and 0xff) / 255f
            val indices = IntArray(cols * rows) { glyphBytes[it * 4].toInt() and 0xff }
            ShapeAsciiReadback(Img(w, h, 3, data), AsciiFrame(this, cols, rows, indices))
        }.whenComplete { r, t -> if (t != null) result.completeExceptionally(t) else result.complete(r) }
    }
    return result
}

/**
 * One pre-target per renderer that runs queued readback requests at the start of a frame, before any
 * offscreen target has rendered, so several reads queued together land in one frame.
 */
private class PairedReads : Target {
    private val pending = ArrayList<() -> Unit>()

    fun queue(request: () -> Unit) = synchronized(pending) { pending += request }

    override fun render(command: CommandBuffer, index: Int) {
        val now = synchronized(pending) { pending.toList().also { pending.clear() } }
        now.forEach { it() }
    }

    companion object {
        private const val NAME = "shape-ascii-paired-reads"

        fun on(renderer: Renderer): PairedReads =
            renderer.renderList.preTargets.getOrPut(NAME) { PairedReads() } as PairedReads
    }
}

/**
 * This target, to be rendered after [first] wherever the two are added to the same render list:
 * `renderer.renderList += "cells" to cells.after(scene)` keeps a pass that reads `scene` behind it,
 * even when the box that added `scene` runs again and adds it at the end.
 */
fun Target.after(first: Target): Target = object : Target, RendersAfter {
    override val rendersAfter: Target = first

    override fun render(command: CommandBuffer, index: Int) = this@after.render(command, index)
}

/** One frame read back by [readCells]: the [scene] as the matcher saw it, and the characters it chose. */
data class ShapeAsciiReadback(
    /** The scene target's pixels, RGB in `0..1`, top row first. */
    val scene: Img,
    /** The characters the GPU chose for that scene. */
    val frame: AsciiFrame,
)

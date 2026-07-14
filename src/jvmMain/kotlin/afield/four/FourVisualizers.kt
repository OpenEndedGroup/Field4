package afield.four

import afield.RunsAt
import afield.H2ValueSnapshot
import afield.newRoot
import afield.registerH2Value
import afield.registerValuePrinter
import afield.four.buffers.*
import afield.four.device.*
import afield.four.modules.*
import afield.four.targets.MultiPingPongRenderToTexture
import afield.four.targets.RTTParameters
import afield.four.targets.RenderToTexture
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.LoadedTexture
import afield.four.texture.StorageImage
import afield.four.texture.image_layout_transition
import afield.four.util.Camera
import afield.four.util.SuperProjection
import afield.four.util.Window
import afield.util.PngEncoder
import afield.h
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkImageBlit
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val H2_FRAMEBUFFERGROUP_MAX_FLOATS = 65_536

// ── tiny helpers ──────────────────────────────────────────────────────

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun frameBufferGroupH2Snapshot(fb: FrameBufferGroup): H2ValueSnapshot {
    if (fb.buffers.isEmpty()) {
        return H2ValueSnapshot(
            value = FloatArray(0),
            summary = "FrameBufferGroup (empty)",
            title = "FrameBufferGroup floats"
        )
    }

    val buffer = fb.getLastWritten()
    val totalFloats = (buffer.requestedSize / Float.SIZE_BYTES)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val snapshotFloats = minOf(totalFloats, H2_FRAMEBUFFERGROUP_MAX_FLOATS)
    val snapshot = FloatArray(snapshotFloats)

    val summary = buildString {
        append("FrameBufferGroup read ")
        append(snapshotFloats)
        append("/")
        append(totalFloats)
        append(" floats")
        if (snapshotFloats < totalFloats) append(" (truncated)")
        append(" | ")
        append(formatBytes(buffer.requestedSize))
        append(" | n=")
        append(fb.n)
        append(" | lastWritten=")
        append(if (fb.lastWritten < 0) "none" else fb.lastWritten.toString())
    }

    return try {
        if (snapshotFloats > 0) {
            buffer.mapAndRead {
                position(0)
                get(snapshot, 0, minOf(snapshot.size, remaining()))
            }
        }
        H2ValueSnapshot(
            value = snapshot,
            summary = summary,
            title = "FrameBufferGroup floats"
        )
    } catch (e: Throwable) {
        H2ValueSnapshot(
            value = listOf("error" to (e.message ?: e::class.java.simpleName)),
            summary = "FrameBufferGroup read failed: ${e.message ?: e::class.java.simpleName}",
            title = "FrameBufferGroup read error"
        )
    }
}

private fun hex(v: Long) = "0x${java.lang.Long.toHexString(v)}"

private fun vkFormatName(fmt: Int): String = when (fmt) {
    VK_FORMAT_UNDEFINED -> "UNDEFINED"
    VK_FORMAT_R8_UNORM -> "R8"
    VK_FORMAT_R8G8_UNORM -> "RG8"
    VK_FORMAT_R8G8B8_UNORM -> "RGB8"
    VK_FORMAT_R8G8B8A8_UNORM -> "RGBA8"
    VK_FORMAT_R8G8B8A8_SRGB -> "RGBA8_SRGB"
    VK_FORMAT_B8G8R8A8_UNORM -> "BGRA8"
    VK_FORMAT_B8G8R8A8_SRGB -> "BGRA8_SRGB"
    VK_FORMAT_R16_SFLOAT -> "R16F"
    VK_FORMAT_R16G16_SFLOAT -> "RG16F"
    VK_FORMAT_R16G16B16A16_SFLOAT -> "RGBA16F"
    VK_FORMAT_R32_SFLOAT -> "R32F"
    VK_FORMAT_R32G32_SFLOAT -> "RG32F"
    VK_FORMAT_R32G32B32_SFLOAT -> "RGB32F"
    VK_FORMAT_R32G32B32A32_SFLOAT -> "RGBA32F"
    VK_FORMAT_R32_SINT -> "R32I"
    VK_FORMAT_R32G32_SINT -> "RG32I"
    VK_FORMAT_R32G32B32A32_SINT -> "RGBA32I"
    VK_FORMAT_R32_UINT -> "R32U"
    VK_FORMAT_R32G32_UINT -> "RG32U"
    VK_FORMAT_R32G32B32A32_UINT -> "RGBA32U"
    VK_FORMAT_R16_UNORM -> "R16"
    VK_FORMAT_R16G16_UNORM -> "RG16"
    VK_FORMAT_R16G16B16A16_UNORM -> "RGBA16"
    VK_FORMAT_D16_UNORM -> "D16"
    VK_FORMAT_D32_SFLOAT -> "D32F"
    VK_FORMAT_D24_UNORM_S8_UINT -> "D24S8"
    VK_FORMAT_D32_SFLOAT_S8_UINT -> "D32FS8"
    VK_FORMAT_R8_UINT -> "R8U"
    VK_FORMAT_R16_UINT -> "R16U"
    VK_FORMAT_R16_SINT -> "R16I"
    VK_FORMAT_R8G8B8A8_UINT -> "RGBA8U"
    VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> "A2BGR10"
    VK_FORMAT_B10G11R11_UFLOAT_PACK32 -> "B10G11R11F"
    VK_FORMAT_E5B9G9R9_UFLOAT_PACK32 -> "E5BGR9F"
    else -> "fmt#$fmt"
}

private fun bufferUsageFlags(usage: Int): String {
    val flags = mutableListOf<String>()
    if (usage and VK_BUFFER_USAGE_TRANSFER_SRC_BIT != 0) flags += "xferSrc"
    if (usage and VK_BUFFER_USAGE_TRANSFER_DST_BIT != 0) flags += "xferDst"
    if (usage and VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT != 0) flags += "UBO"
    if (usage and VK_BUFFER_USAGE_STORAGE_BUFFER_BIT != 0) flags += "SSBO"
    if (usage and VK_BUFFER_USAGE_INDEX_BUFFER_BIT != 0) flags += "index"
    if (usage and VK_BUFFER_USAGE_VERTEX_BUFFER_BIT != 0) flags += "vertex"
    if (usage and VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT != 0) flags += "indirect"
    if (usage and VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT != 0) flags += "devAddr"
    return if (flags.isEmpty()) hex(usage.toLong()) else flags.joinToString("+")
}

private fun memoryPropertyFlags(flags: Int, cpuOnly: Boolean = false): String {
    if (cpuOnly) return badge("#af6", "CPU_ONLY")
    if (flags == 0) return dim("auto")
    val names = mutableListOf<String>()
    if (flags and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT != 0) names += "device"
    if (flags and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0) names += "host"
    if (flags and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT != 0) names += "coherent"
    if (flags and VK_MEMORY_PROPERTY_HOST_CACHED_BIT != 0) names += "cached"
    if (flags and VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT != 0) names += "lazy"
    if (flags and VK_MEMORY_PROPERTY_PROTECTED_BIT != 0) names += "protected"
    return if (names.isEmpty()) hex(flags.toLong()) else names.joinToString("+")
}

private fun primitiveTopologyName(topo: Int): String = when (topo) {
    VK_PRIMITIVE_TOPOLOGY_POINT_LIST -> "points"
    VK_PRIMITIVE_TOPOLOGY_LINE_LIST -> "lines"
    VK_PRIMITIVE_TOPOLOGY_LINE_STRIP -> "lineStrip"
    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST -> "triangles"
    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP -> "triStrip"
    VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN -> "triFan"
    else -> "topo#$topo"
}

private fun fillModeName(mode: Int): String = when (mode) {
    VK_POLYGON_MODE_FILL -> "fill"
    VK_POLYGON_MODE_LINE -> "wireframe"
    VK_POLYGON_MODE_POINT -> "point"
    else -> "fill#$mode"
}

// ── html micro-DSL ───────────────────────────────────────────────────

private const val S = "style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.4'"
private const val CLR_CLASS = "#cc8"
private const val CLR_NUM = "#8cf"
private const val CLR_STR = "#8f8"
private const val CLR_BOOL = "#f88"
private const val CLR_DIM = "#888"
private const val CLR_PUNCT = "#666"
private const val CLR_ORANGE = "#fc8"

private fun span(color: String, text: String) = "<span style='color:$color'>$text</span>"
private fun cls(name: String) = span(CLR_CLASS, name)
private fun num(v: Any) = span(CLR_NUM, "$v")
private fun str(v: String) = span(CLR_STR, "\"${esc(v)}\"")
private fun bool(v: Boolean) = span(CLR_BOOL, "$v")
private fun dim(v: String) = span(CLR_DIM, v)
private fun punct(v: String) = span(CLR_PUNCT, v)

/** User-typed name (uniform, buffer, box, mesh slot) — single-quoted, green */
private fun name(v: String) = span(CLR_STR, "'${esc(v)}'")

/** Format a vec3 compactly */
private fun vec3(v: afield.stdlib.linalg.Vector3f) = "${span(CLR_NUM, "%.2f".format(v.x))}, ${span(CLR_NUM, "%.2f".format(v.y))}, ${span(CLR_NUM, "%.2f".format(v.z))}"

private fun row(label: String, value: String) =
    "<tr><td style='padding-right:8px;color:$CLR_DIM;white-space:nowrap'>$label</td><td>$value</td></tr>"

private fun table(vararg rows: String) =
    "<table style='border-collapse:collapse;margin:2px 0'>${ rows.joinToString("") }</table>"

private fun badge(color: String, text: String) =
    "<span style='background:${color}22;color:$color;padding:0 4px;border-radius:3px;font-size:11px'>$text</span>"

/** Format a list of user-typed keys (truncated if > maxItems) */
private fun nameList(keys: Collection<String>, maxItems: Int = 8): String {
    if (keys.isEmpty()) return dim("none")
    val shown = keys.take(maxItems)
    val items = shown.joinToString(punct(", ")) { name(it) }
    return if (keys.size > maxItems) "$items " + dim("…${keys.size - maxItems} more")
    else items
}

private fun shaderBadges(
    vertex: String = "", fragment: String = "", compute: String = "",
    mesh: String = "", task: String = ""
): String {
    val b = mutableListOf<String>()
    if (vertex.isNotBlank()) b += badge("#6cf", "VS")
    if (fragment.isNotBlank()) b += badge("#f8a", "FS")
    if (compute.isNotBlank()) b += badge("#af6", "CS")
    if (mesh.isNotBlank()) b += badge("#fc6", "MS")
    if (task.isNotBlank()) b += badge("#c8f", "TS")
    return b.joinToString(" ")
}

// ── texture preview cache & readback ─────────────────────────────────

private data class CachedPreview(val base64: String, val width: Int, val height: Int, val frameStamp: Long)

/**
 * Thread-safe cache of downsampled texture previews keyed by VkImage handle.
 * Call [invalidate] when an image is destroyed or recreated.
 */
object TexturePreviewCache {
    private val cache = ConcurrentHashMap<Long, CachedPreview>()
    private const val MAX_DIM = 128

    fun invalidate(vkImage: Long) { cache.remove(vkImage) }
    fun clear() { cache.clear() }

    /** Return cached `<img>` tag HTML, or null. */
    fun getCachedImgTag(vkImage: Long): String? {
        val c = cache[vkImage] ?: return null
        return imgTag(c)
    }

    private fun imgTag(c: CachedPreview) =
        "<img src='data:image/png;base64,${c.base64}' width='${c.width}' height='${c.height}' " +
        "style='image-rendering:pixelated;border:1px solid #444;margin:2px 0;display:block'/>"

    /**
     * Synchronous one-shot GPU readback of [vkImage] into a small PNG preview.
     *
     * Requirements:
     * - The image's usage flags MUST include `VK_IMAGE_USAGE_TRANSFER_SRC_BIT`.
     * - [currentLayout] must be the image's current layout (e.g. SHADER_READ_ONLY, COLOR_ATTACHMENT_OPTIMAL).
     * - Blocks the calling thread until GPU work completes (fence wait).
     *
     * Returns an `<img>` HTML tag or null on failure.
     */
    fun capture(
        renderer: Renderer,
        vkImage: Long,
        format: Int,
        srcWidth: Int,
        srcHeight: Int,
        currentLayout: Int,
        maxDim: Int = MAX_DIM
    ): String? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        // Compute thumbnail dimensions maintaining aspect ratio
        val scale = maxDim.toFloat() / maxOf(srcWidth, srcHeight).coerceAtLeast(1)
        val thumbW: Int
        val thumbH: Int
        if (scale >= 1f) {
            thumbW = srcWidth; thumbH = srcHeight
        } else {
            thumbW = (srcWidth * scale).toInt().coerceAtLeast(1)
            thumbH = (srcHeight * scale).toInt().coerceAtLeast(1)
        }

        val needsResize = thumbW != srcWidth || thumbH != srcHeight
        val directCopyFormat = when (format) {
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_B8G8R8A8_SRGB -> true
            else -> false
        }
        val displayEncodedSource = when (format) {
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_SRGB,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_R32G32B32A32_SFLOAT -> true
            else -> false
        }
        val needsBlit = needsResize || !directCopyFormat
        // Target format for readback. HDR/float and sRGB sources become display-encoded
        // RGBA8 so PNG previews match the swapchain path instead of storing linear bytes.
        val readbackFormat = if (displayEncodedSource) VK_FORMAT_R8G8B8A8_SRGB else VK_FORMAT_R8G8B8A8_UNORM
        val bpp = 4
        val stagingSize = thumbW.toLong() * thumbH.toLong() * bpp

        // Use a private command pool so we're thread-safe
        val pool = CommandPool(renderer.device, renderer.graphQueue.queueFamilyIndex)
        val cmd = CommandBuffer(pool, primary = true, oneTimeSubmit = true)
        val staging = VulkanBuffer(
            renderer = renderer,
            requestedSize = stagingSize,
            usage = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            reqMask = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )
        val fence = Fence(renderer.device, signaled = false)

        // Optional intermediate blit image when downsampling
        var blitImage: Image? = null
        var blitImageView: ImageView? = null

        try {
            cmd.beginRecording()
            MemoryStack.stackPush().use { stack ->
                val range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)

                // Transition source image to TRANSFER_SRC
                image_layout_transition(
                    cmd.vkCommandBuffer, vkImage,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT,
                    currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, range
                )

                val copySourceImage: Long
                val copySourceLayout: Int

                if (needsBlit) {
                    // Create a temporary RGBA8 image for resizing and/or display-format conversion.
                    val blitData = Image.ImageData()
                        .width(thumbW).height(thumbH)
                        .format(readbackFormat)
                        .mipLevels(1)
                        .usage(VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    blitImage = Image(renderer.device, blitData)

                    // Transition blit image to TRANSFER_DST
                    image_layout_transition(
                        cmd.vkCommandBuffer, blitImage.vkImage,
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        0, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range
                    )

                    // Blit (downsample)
                    val blit = VkImageBlit.calloc(1, stack)
                    blit[0]
                        .srcSubresource { it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                        .dstSubresource { it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    blit[0].srcOffsets(0).set(0, 0, 0)
                    blit[0].srcOffsets(1).set(srcWidth, srcHeight, 1)
                    blit[0].dstOffsets(0).set(0, 0, 0)
                    blit[0].dstOffsets(1).set(thumbW, thumbH, 1)

                    VK10.vkCmdBlitImage(
                        cmd.vkCommandBuffer,
                        vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        blitImage.vkImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, VK10.VK_FILTER_LINEAR
                    )

                    // Transition blit image to TRANSFER_SRC for the copy-to-buffer
                    image_layout_transition(
                        cmd.vkCommandBuffer, blitImage.vkImage,
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, range
                    )

                    copySourceImage = blitImage.vkImage
                    copySourceLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                } else {
                    copySourceImage = vkImage
                    copySourceLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                }

                // Copy image to staging buffer
                val region = VkBufferImageCopy.calloc(1, stack)
                region[0]
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource { it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                    .imageOffset { it.set(0, 0, 0) }
                    .imageExtent { it.set(thumbW, thumbH, 1) }

                VK10.vkCmdCopyImageToBuffer(
                    cmd.vkCommandBuffer,
                    copySourceImage, copySourceLayout,
                    staging.buffer, region
                )

                // Transition source image back to its original layout
                image_layout_transition(
                    cmd.vkCommandBuffer, vkImage,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, currentLayout, range
                )
            }
            cmd.endRecording()

            // Submit and wait
            MemoryStack.stackPush().use { stack ->
                val pCmd = stack.mallocPointer(1).put(0, cmd.vkCommandBuffer)
                renderer.graphQueue.submit(pCmd, null, null, null, fence)
            }
            fence.fenceWait()

            // Read pixels from staging buffer
            var result: String? = null
            staging.mapAndRead_bytes {
                // Handle BGR→RGB swap for BGRA source formats
                val isBGR = format == VK_FORMAT_B8G8R8A8_UNORM || format == VK_FORMAT_B8G8R8A8_SRGB
                if (isBGR && !needsBlit) {
                    // When we blit to RGBA8, the GPU already converts. But when copying directly
                    // from a BGRA source without blit, we need to swap B and R channels.
                    val pixelCount = thumbW * thumbH
                    for (i in 0 until pixelCount) {
                        val off = i * 4
                        val b = this[off]
                        this.put(off, this[off + 2])
                        this.put(off + 2, b)
                    }
                }
                val png = PngEncoder.encodeFromBuffer(this, thumbW, thumbH, flipY = true)
                result = Base64.getEncoder().encodeToString(png)
            }

            if (result != null) {
                val cached = CachedPreview(result, thumbW, thumbH, System.nanoTime())
                cache[vkImage] = cached
                return imgTag(cached)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            fence.cleanup()
            staging.cleanup()
            cmd.cleanup()
            pool.cleanup()
            blitImage?.cleanup()
        }
    }
}

/**
 * Attempt to read a [LoadedTexture] preview by re-reading from the file on disk.
 * No GPU access needed — just STB decode + downsample + PNG encode.
 */
private fun loadedTextureFilePreview(lt: LoadedTexture, maxDim: Int = 128): String? {
    return try {
        val path = java.io.File(lt.fileName)
        if (!path.exists() || !path.isFile) return null
        val bytes = path.readBytes()
        val buf = org.lwjgl.system.MemoryUtil.memAlloc(bytes.size)
        buf.put(bytes).flip()
        org.lwjgl.system.MemoryStack.stackPush().use { stack ->
            val w = stack.mallocInt(1)
            val h = stack.mallocInt(1)
            val ch = stack.mallocInt(1)
            val decoded = org.lwjgl.stb.STBImage.stbi_load_from_memory(buf, w, h, ch, 4) ?: return null
            try {
                val srcW = w[0]; val srcH = h[0]
                val scale = maxDim.toFloat() / maxOf(srcW, srcH).coerceAtLeast(1)
                val thumbW: Int; val thumbH: Int
                if (scale >= 1f) { thumbW = srcW; thumbH = srcH }
                else { thumbW = (srcW * scale).toInt().coerceAtLeast(1); thumbH = (srcH * scale).toInt().coerceAtLeast(1) }

                // Simple bilinear downsample
                val pixels = IntArray(thumbW * thumbH)
                for (ty in 0 until thumbH) {
                    for (tx in 0 until thumbW) {
                        val sx = (tx.toFloat() / thumbW * srcW).toInt().coerceIn(0, srcW - 1)
                        val sy = (ty.toFloat() / thumbH * srcH).toInt().coerceIn(0, srcH - 1)
                        val off = (sy * srcW + sx) * 4
                        val r = decoded[off].toInt() and 0xFF
                        val g = decoded[off + 1].toInt() and 0xFF
                        val b = decoded[off + 2].toInt() and 0xFF
                        val a = decoded[off + 3].toInt() and 0xFF
                        pixels[tx + ty * thumbW] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                val png = PngEncoder.encode(pixels, thumbW, thumbH)
                val b64 = Base64.getEncoder().encodeToString(png)
                "<img src='data:image/png;base64,$b64' width='$thumbW' height='$thumbH' " +
                "style='image-rendering:pixelated;border:1px solid #444;margin:2px 0;display:block'/>"
            } finally {
                org.lwjgl.stb.STBImage.stbi_image_free(decoded)
            }
        }
    } catch (_: Exception) { null }
    finally {}
}

// ── registrations ────────────────────────────────────────────────────

@RunsAt(newRoot)
fun registerFourVisualizers() {

    // ── Renderer ─────────────────────────────────────────────────────
    registerValuePrinter<Renderer> { r ->
        val deviceName = try { r.physicalDevice.deviceName } catch (_: Exception) { "?" }
        val windowCount = r.windows.size
        val stats = r.lastVertexUploadFrameStats
        val sc = try { r.swapChain } catch (_: Exception) { null }
        table(
            row("Renderer", cls("Renderer") + " " + dim("frame ${r.frameCounter}")),
            row("device", str(deviceName)),
            row("windows", num(windowCount)),
            row("swapchain", "${num(r.numImages)} images, current=${num(r.currentImage)}"),
            sc?.let { row("extent", "${num(it.swapChainExtent.width())}×${num(it.swapChainExtent.height())}") } ?: "",
            row("upload", "${formatBytes(stats.totalBytes)} " + dim("(rec ${formatBytes(stats.recordedBytes)} + imm ${formatBytes(stats.immediateBytes)})"))
        )
    }

    // ── Camera ───────────────────────────────────────────────────────
    registerValuePrinter<Camera> { cam ->
        val dist = "%.2f".format(cam.distanceToTarget())
        val proj = cam.p
        table(
            row("Camera", cls("Camera")),
            row("at", vec3(cam.at)),
            row("target", vec3(cam.target)),
            row("up", vec3(cam.up)),
            row("distance", num(dist)),
            row("fov", num("%.1f°".format(proj.fov))),
            row("near / far", "${num("%.3f".format(proj.zNear))} / ${num("%.1f".format(proj.zFar))}"),
            if (proj.orthoness > 0.0) row("orthoness", num("%.2f".format(proj.orthoness))) else "",
            if (proj.ox != 0.0 || proj.oy != 0.0) row("offset", "${num("%.2f".format(proj.ox))}, ${num("%.2f".format(proj.oy))}") else ""
        )
    }

    // ── Window ───────────────────────────────────────────────────────
    registerValuePrinter<Window> { w ->
        table(
            row("Window", cls("Window") + " " + str(w.title)),
            row("size", "${num(w.width)}×${num(w.height)}" + if (w.isResized) " " + badge("#f88", "resized") else ""),
            row("cursor", span(CLR_ORANGE, "%.1f, %.1f".format(w.cursor.x, w.cursor.y)))
        )
    }

    // ── Device ───────────────────────────────────────────────────────
    registerValuePrinter<Device> { d ->
        val flags = mutableListOf<String>()
        if (d.supportsPushDescriptors) flags += "pushDesc"
        if (d.supportsExtendedDynamicState) flags += "dynState"
        if (d.supportsExtendedDynamicState3) flags += "dynState3"
        if (d.enableMeshShading) flags += "meshShading"
        table(
            row("Device", cls("Device")),
            row("features", if (flags.isEmpty()) dim("none") else flags.joinToString(" ") { badge("#6cf", it) }),
            row("pushDescMax", num(d.pushDescriptorMax))
        )
    }

    // ── PhysicalDevice ───────────────────────────────────────────────
    registerValuePrinter<PhysicalDevice> { pd ->
        table(
            row("PhysicalDevice", cls("PhysicalDevice")),
            row("name", str(pd.deviceName)),
            row("memoryTypes", num(pd.vkMemoryProperties.memoryTypeCount())),
            row("memoryHeaps", num(pd.vkMemoryProperties.memoryHeapCount())),
            row("queueFamilies", num(pd.vkQueueFamilyProps.remaining()))
        )
    }

    // ── SwapChain ────────────────────────────────────────────────────
    registerValuePrinter<SwapChain> { sc ->
        table(
            row("SwapChain", cls("SwapChain")),
            row("format", badge("#fc8", vkFormatName(sc.surfaceFormat.imageFormat))),
            row("extent", "${num(sc.swapChainExtent.width())}×${num(sc.swapChainExtent.height())}"),
            row("images", num(sc.imageViews.size)),
            row("transferSrc", bool(sc.supportsTransferSrc))
        )
    }

    // ── Image ────────────────────────────────────────────────────────
    registerValuePrinter<Image> { img ->
        val d = img.imageData
        table(
            row("Image", cls("Image") + " " + badge("#fc8", vkFormatName(d.format))),
            row("size", "${num(d.width)}×${num(d.height)}"),
            row("mips", num(d.mipLevels)),
            row("layers", num(d.arrayLayers)),
            row("samples", num(d.sampleCount))
        )
    }

    // ── ImageView ────────────────────────────────────────────────────
    registerValuePrinter<ImageView> { iv ->
        val d = iv.imageViewData
        table(
            row("ImageView", cls("ImageView") + " " + badge("#fc8", vkFormatName(d.format))),
            row("mips", num(d.mipLevels)),
            row("layers", "${num(d.layerCount)} " + dim("base=${d.baseArrayLayer}"))
        )
    }

    // ── VulkanBuffer ─────────────────────────────────────────────────
    registerValuePrinter<VulkanBuffer> { vb ->
        val usageStr = bufferUsageFlags(vb.usage)
        val requestedMemory = memoryPropertyFlags(vb.reqMask, vb.cpuOnly)
        val actualMemory = memoryPropertyFlags(vb.allocationMemoryFlags, vb.cpuOnly)
        val memoryType = if (vb.allocationMemoryType >= 0) " " + dim("#${vb.allocationMemoryType}") else ""
        table(
            row("VulkanBuffer", cls("VulkanBuffer") + " " + badge("#6cf", formatBytes(vb.requestedSize))),
            row("usage", usageStr),
            row("memory", "$requestedMemory ${punct("→")} $actualMemory$memoryType"),
            row("watermark", "${formatBytes(vb.watermark)} " + dim("/ ${formatBytes(vb.requestedSize)}")),
            if (vb.readWatermark >= 0 || vb.writeWatermark >= 0)
                row("rw marks", "r=${formatBytes(vb.readWatermark)}  w=${formatBytes(vb.writeWatermark)}")
            else "",
            if (vb.gpuAddress > 0) row("devAddr", bool(true)) else "",
            if (vb.cpuOnly) row("cpuOnly", bool(true)) else "",
            if (vb.zeroBeforeComputation) row("zeroBefore", bool(true)) else ""
        )
    }

    // ── FrameBufferGroup ─────────────────────────────────────────────
    registerH2Value<FrameBufferGroup> { fb ->
        frameBufferGroupH2Snapshot(fb)
    }

    registerValuePrinter<FrameBufferGroup> { fb ->
        val sizes = fb.buffers.map { formatBytes(it.requestedSize) }
        val memories = fb.buffers.map { memoryPropertyFlags(it.allocationMemoryFlags, it.cpuOnly) }.distinct()
        val mode = fb.accessPattern.name.lowercase() +
                (if (fb.usesStagedUploads) " " + badge("#6cf", "staged write") else "") +
                (if (fb.usesReadbackStaging) " " + badge("#fc8", "readback") else "")
        table(
            row("FrameBufferGroup", cls("FrameBufferGroup") + " " + dim("n=${fb.n}")),
            row("mode", mode),
            row("lastWritten", if (fb.lastWritten < 0) dim("none") else num(fb.lastWritten)),
            row("buffers", if (fb.buffers.isEmpty()) dim("empty")
                    else sizes.joinToString(punct(", "))),
            row("memory", if (memories.isEmpty()) dim("none") else memories.joinToString(punct(", ")))
        )
    }

    // ── VertexBuffer ─────────────────────────────────────────────────
    registerValuePrinter<VertexBuffer> { vb ->
        val dirty = vb.watermark != vb.transferWatermark
        table(
            row("VertexBuffer", cls("VertexBuffer") + " " + badge("#6cf", formatBytes(vb.size))),
            row("usage", bufferUsageFlags(vb.usage)),
            row("watermark", "${num(vb.watermark)} " + if (dirty) badge("#f88", "dirty") else dim("clean")),
            if (vb.cpu != null) row("cpu", badge("#af6", "mapped")) else ""
        )
    }

    // ── Structure ────────────────────────────────────────────────────
    registerValuePrinter<Structure> { s ->
        val memberRows = s.members.take(12).joinToString("") { m ->
            val off = s.offsets[m.name] ?: m.offset
            val sz = s.sizes[m.name] ?: m.memberSize().toInt()
            val bound = if (m.name in s.bindings) badge("#af6", "bound")
                else if (m.name in s.values) badge("#fc8", "set")
                else ""
            "<tr><td style='padding-right:6px;color:$CLR_DIM'>+$off</td>" +
            "<td style='padding-right:6px'>${name(m.name)}</td>" +
            "<td style='padding-right:6px;color:$CLR_NUM'>${sz}B</td>" +
            "<td>$bound</td></tr>"
        }
        val overflow = if (s.members.size > 12) "<tr><td colspan='4' style='color:$CLR_DIM'>…${s.members.size - 12} more</td></tr>" else ""
        val proxyNote = if (s.proxying != null) " → " + dim("proxied") else ""
        "<div $S>${cls("Structure")} ${dim("${s.members.size} members")}$proxyNote" +
        (if (s.dirty) " " + badge("#f88", "dirty") else "") +
        "<table style='border-collapse:collapse;margin:2px 0;font-size:11px'>" +
        memberRows + overflow +
        "</table></div>"
    }

    // ── Parameters ───────────────────────────────────────────────────
    registerValuePrinter<Parameters> { p ->
        table(
            row("Parameters", cls("Parameters") + " " + name(p.name)),
            row("shaders", shaderBadges(vertex = p.vertex, fragment = p.fragment, compute = p.compute, mesh = p.mesh, task = p.task)),
            row("topology", primitiveTopologyName(p.primitiveType)),
            row("fill", fillModeName(p.fillMode)),
            row("blend", bool(p.blend) + if (p.blend) " " + dim("src=${p.srcColor} dst=${p.dstColor}") else ""),
            row("depthTest", bool(p.depthTest)),
            row("colors", "${num(p.color)} " + if (p.colorFormats.isNotEmpty()) dim(p.colorFormats.joinToString(",") { vkFormatName(it) }) else ""),
            row("imageFormat", badge("#fc8", vkFormatName(p.imageFormat))),
            p.viewport?.let { row("viewport", "${num(it.x.toInt())},${num(it.y.toInt())} ${num(it.w.toInt())}×${num(it.h.toInt())}") } ?: ""
        )
    }

    // ── Module ───────────────────────────────────────────────────────
    registerValuePrinter<Module> { m ->
        table(
            row("Module", cls("Module") + " " + name(m.parameters.name) +
                    (if (!m.valid) " " + badge("#f88", "INVALID") else "") +
                    (if (!m.enabled) " " + badge("#888", "disabled") else "")),
            row("shaders", shaderBadges(vertex = m.parameters.vertex, fragment = m.parameters.fragment)),
            row("meshes", nameList(m.meshes.keys)),
            row("uniforms", nameList(m.liveUniformStructures.keys)),
            if (m.pushConstantSize > 0) row("pushConst", "${num(m.pushConstantSize)}B") else ""
        )
    }

    // ── ModulePointer ────────────────────────────────────────────────
    registerValuePrinter<ModulePointer> { mp -> mp.current.h }

    // ── MeshModule ───────────────────────────────────────────────────
    registerValuePrinter<MeshModule> { m ->
        table(
            row("MeshModule", cls("MeshModule") + " " + name(m.parameters.name) +
                    (if (!m.valid) " " + badge("#f88", "INVALID") else "") +
                    (if (!m.enabled) " " + badge("#888", "disabled") else "")),
            row("shaders", shaderBadges(fragment = m.parameters.fragment, mesh = m.parameters.mesh, task = m.parameters.task)),
            row("meshes", nameList(m.meshes.keys)),
            row("uniforms", nameList(m.liveUniformStructures.keys)),
            if (m.pushConstantSize > 0) row("pushConst", "${num(m.pushConstantSize)}B") else ""
        )
    }

    // ── MeshModulePointer ────────────────────────────────────────────
    registerValuePrinter<MeshModulePointer> { mp -> mp.current.h }

    // ── ComputeModule ────────────────────────────────────────────────
    registerValuePrinter<ComputeModule> { m ->
        table(
            row("ComputeModule", cls("ComputeModule") + " " + name(m.parameters.name) +
                    (if (!m.valid) " " + badge("#f88", "INVALID") else "") +
                    (if (!m.enabled) " " + badge("#888", "disabled") else "")),
            row("shaders", shaderBadges(compute = m.parameters.compute)),
            row("tasks", nameList(m.meshes.keys)),
            row("uniforms", nameList(m.liveUniformStructures.keys)),
            if (m.pushConstantSize > 0) row("pushConst", "${num(m.pushConstantSize)}B") else "",
            row("zeroBefore", if (m.zeroBefore.isEmpty()) dim("none") else nameList(m.zeroBefore.keys))
        )
    }

    // ── ComputeModulePointer ─────────────────────────────────────────
    registerValuePrinter<ComputeModulePointer> { mp -> mp.current.h }

    // ── Mesh ─────────────────────────────────────────────────────────
    registerValuePrinter<Mesh> { m ->
        table(
            row("Mesh", cls("Mesh") + if (m.notInitialized) " " + badge("#f88", "uninit") else ""),
            row("vertices", num(m.numVert)),
            row("triangles", num(m.numTriangle)),
            row("instances", num(m.instanceCount)),
            row("inputs", nameList(m.vn.keys)),
            if (m.t != null) row("indexed", bool(true)) else "",
            if (m.push.members.isNotEmpty()) row("pushConst", "${num(m.push.members.size)} members") else ""
        )
    }

    // ── Introspection ────────────────────────────────────────────────
    registerValuePrinter<Introspection> { intro ->
        if (!intro.valid) {
            cls("Introspection") + " " + badge("#f88", "INVALID")
        } else {
            val i = intro as Introspected
            val parts = mutableListOf<String>()
            if (i.inputs.isNotEmpty()) parts += "${i.inputs.size} inputs"
            if (i.outputs.isNotEmpty()) parts += "${i.outputs.size} outputs"
            if (i.ubos.isNotEmpty()) parts += "${i.ubos.size} UBOs"
            if (i.ssbo.isNotEmpty()) parts += "${i.ssbo.size} SSBOs"
            if (i.textures.isNotEmpty()) parts += "${i.textures.size} textures"
            if (i.images.isNotEmpty()) parts += "${i.images.size} images"
            if (i.pushConstants.isNotEmpty()) parts += "${i.pushConstants.size} push"
            cls("Introspection") + " " + dim(parts.joinToString(" · "))
        }
    }

    // ── RTTParameters ────────────────────────────────────────────────
    registerValuePrinter<RTTParameters> { p ->
        val fmtList = if (p.colorFormats.isNotEmpty()) p.colorFormats.joinToString(", ") { vkFormatName(it) }
                      else vkFormatName(p.format)
        table(
            row("RTTParameters", cls("RTTParameters")),
            row("size", "${num(p.width)}×${num(p.height)}"),
            row("mipLevels", num(p.mipLevels)),
            row("colorAttachments", num(p.colorAttachments) + " " + dim(fmtList)),
            row("layers", num(p.colorLayerCount)),
            row("depth", if (p.useDepth) bool(true) + " " + dim(vkFormatName(p.depthFormat)) else bool(false)),
            row("clear", if (p.clearFirst) bool(true) + " " + dim("(${p.clearColor})") else bool(false)),
            row("sampled", bool(p.colorSampled)),
            row("transferSrc", bool(p.colorTransferSrc)),
            row("transferDst", bool(p.colorTransferDst)),
            if (p.mipLevels > 1) row("genMipmaps", bool(p.generateColorMipmaps)) else ""
        )
    }

    // ── RenderToTexture ──────────────────────────────────────────────
    registerValuePrinter<RenderToTexture> { rtt ->
        val p = rtt.parameters
        val fmts = rtt.colorFormats.joinToString(", ") { vkFormatName(it) }
        val hasTransferSrc = p.colorTransferSrc

        val preview = if (hasTransferSrc && rtt.colorImageRefs.isNotEmpty()) {
            val img = rtt.colorImageRefs[0]
            if (img != null) {
                // color attachment 0 is typically in SHADER_READ_ONLY after a frame, or COLOR_ATTACHMENT_OPTIMAL
                val layout = if (p.colorSampled) VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                             else VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                TexturePreviewCache.getCachedImgTag(img.vkImage)
                    ?: TexturePreviewCache.capture(rtt.renderer, img.vkImage, rtt.colorFormats[0], rtt.width, rtt.height, layout)
            } else null
        } else null

        val previewRow = if (preview != null) row("preview", preview)
                         else if (!hasTransferSrc) row("preview", dim("set colorTransferSrc=true to preview"))
                         else ""

        table(
            row("RenderToTexture", cls("RenderToTexture") + " " + badge("#fc8", fmts)),
            row("size", "${num(rtt.width)}×${num(rtt.height)}" + if (p.mipLevels > 1) " " + dim("mip×${p.mipLevels}") else ""),
            row("attachments", num(p.colorAttachments) + " " + dim("layers=${rtt.colorLayerCount}")),
            row("depth", if (p.useDepth) bool(true) + " " + dim(vkFormatName(p.depthFormat)) else bool(false)),
            row("modules", nameList(rtt.modules.keys)),
            row("flags", listOfNotNull(
                if (p.colorSampled) "sampled" else null,
                if (hasTransferSrc) "xferSrc" else null,
                if (p.colorTransferDst) "xferDst" else null,
                if (p.clearFirst) "clear" else null
            ).joinToString(" ") { badge("#6cf", it) }.ifEmpty { dim("none") }),
            previewRow
        )
    }

    // ── MultiPingPongRenderToTexture ─────────────────────────────────
    registerValuePrinter<MultiPingPongRenderToTexture> { mpp ->
        val p = mpp.parameters
        val fmts = p.colorFormats.ifEmpty { listOf(p.format) }.joinToString(", ") { vkFormatName(it) }
        val hasTransferSrc = p.colorTransferSrc

        val preview = if (hasTransferSrc) {
            val iv = try { mpp.imageView } catch (_: Exception) { null }
            val img = iv?.underlyingImage
            if (img != null) {
                val layout = if (p.colorSampled) VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                             else VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                TexturePreviewCache.getCachedImgTag(img.vkImage)
                    ?: TexturePreviewCache.capture(mpp.renderer, img.vkImage, img.format, p.width, p.height, layout)
            } else null
        } else null

        val previewRow = if (preview != null) row("preview", preview)
                         else if (!hasTransferSrc) row("preview", dim("set colorTransferSrc=true to preview"))
                         else ""

        table(
            row("MultiPingPongRTT", cls("MultiPingPongRTT") + " " + badge("#fc8", fmts)),
            row("size", "${num(p.width)}×${num(p.height)}" + if (p.mipLevels > 1) " " + dim("mip×${p.mipLevels}") else ""),
            row("passes", "${num(mpp.otherPassCount)} other " + dim("(init=${mpp.runInitPass}, final=${mpp.runFinalPass})")),
            row("flags", listOfNotNull(
                if (p.colorSampled) "sampled" else null,
                if (hasTransferSrc) "xferSrc" else null,
                if (p.colorTransferDst) "xferDst" else null
            ).joinToString(" ") { badge("#6cf", it) }.ifEmpty { dim("none") }),
            previewRow
        )
    }

    // ── LoadedTexture ────────────────────────────────────────────────
    registerValuePrinter<LoadedTexture> { lt ->
        val preview = loadedTextureFilePreview(lt)

        table(
            row("LoadedTexture", cls("LoadedTexture") + " " + str(lt.fileName.substringAfterLast('/'))),
            row("size", "${num(lt.width)}×${num(lt.height)}"),
            row("mips", num(lt.mipLevels)),
            row("format", badge("#fc8", vkFormatName(lt.image.format))),
            if (preview != null) row("preview", preview)
            else row("preview", dim("file not found"))
        )
    }

    // ── StorageImage ─────────────────────────────────────────────────
    registerValuePrinter<StorageImage> { si ->
        table(
            row("StorageImage", cls("StorageImage") + " " + badge("#fc8", vkFormatName(si.imageFormat))),
            row("size", "${num(si.width)}×${num(si.height)}"),
            row("preview", dim("storage-only (no TRANSFER_SRC)"))
        )
    }
}

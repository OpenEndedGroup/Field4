@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.io.syphon

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Device
import afield.graphics.texture.HasDefaultTextureSampler
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.IOSurfaceHostBuffer
import afield.graphics.texture.Image
import afield.graphics.texture.ImageView
import afield.graphics.texture.ImageView.ImageViewData
import afield.graphics.texture.MetalIOSurface
import afield.graphics.texture.PreparesSampledImages
import afield.graphics.texture.StreamingTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.texture.image_layout_transition
import afield.graphics.vulkan.util.vkCheck
import afield.app.diagnostics.logging.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT
import org.lwjgl.vulkan.EXTExternalMemoryHost.nvkGetMemoryHostPointerPropertiesEXT
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceImageFormatProperties2
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COPY_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
import org.lwjgl.vulkan.VkExternalImageFormatProperties
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageFormatProperties2
import org.lwjgl.vulkan.VkImageSubresource
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImportMemoryHostPointerInfoEXT
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryHostPointerPropertiesEXT
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceExternalImageFormatInfo
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkSubresourceLayout
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * A [SyphonClient]'s frames as a texture for a shader to sample: bind this object itself as a
 * `sampler2D` (`prop<SyphonTexture>(name) = client.texture()`). Made by [SyphonClient.texture], and
 * kept current by the renderer's list, which calls [render] each frame.
 *
 * Syphon's frame is an IOSurface that the sending process draws into. Where the driver allows it,
 * Vulkan samples that IOSurface's own memory, with no copy of any kind ([path] says which route):
 *
 * - [Path.IMPORTED_IMAGE]: MoltenVK. The IOSurface backs a `VkImage` (`VK_EXT_metal_objects`).
 * - [Path.HOST_LINEAR_IMAGE]: KosmicKrisp (and MoltenVK if the first is refused). The IOSurface's
 *   pages are imported as host memory (`VK_EXT_external_memory_host`) and bound to a linearly tiled
 *   `VkImage` whose row pitch is the surface's own.
 * - [Path.HOST_BUFFER_COPY]: the pages are imported as a buffer and copied into an image on the GPU,
 *   once per new frame; used when the driver will not lay a linear image over them.
 * - [Path.CPU_COPY]: the old route, kept as the fallback: a blit into a staging buffer, a swizzle on
 *   the CPU, and an upload. Also what `texture(zeroCopy = false)` asks for.
 *
 * The texture is `B8G8R8A8_SRGB` on the zero-copy paths and `R8G8B8A8_SRGB` on the copy path; either
 * way the shader sees RGBA, top row first (v = 0 is the top of the sender's picture). When the sender
 * changes size Syphon replaces the IOSurface, and the texture re-imports the new one; the old one is
 * released only after the frame that last sampled it has completed. (The copy path keeps the first
 * frame's size, as before, and skips frames of another.) Like every Syphon client, the zero-copy paths
 * read the sender's surface while it may be drawing the next frame into it; Syphon has no cross-process
 * fence to wait on.
 */
class SyphonTexture internal constructor(
    val client: SyphonClient,
    private val renderer: Renderer,
    zeroCopy: Boolean = true,
) : Target, HasImageView, HasImageViewArray, PreparesSampledImages, HasDefaultTextureSampler, AutoCloseable {

    enum class Path { IMPORTED_IMAGE, HOST_LINEAR_IMAGE, HOST_BUFFER_COPY, CPU_COPY }

    /** One imported IOSurface and everything Vulkan made over it. */
    private inner class Import(val ref: Long, val path: Path) {
        val surfaceId = MetalIOSurface.ioSurfaceId(ref)
        val width = MetalIOSurface.width(ref)
        val height = MetalIOSurface.height(ref)
        var image: Image? = null
        var linearImage = 0L
        var linearMemory = 0L
        var locked = false
        var host: IOSurfaceHostBuffer? = null
        var view: ImageView? = null
        var vkImage = 0L
        var layout = VK_IMAGE_LAYOUT_UNDEFINED

        init {
            try {
                when (path) {
                    Path.IMPORTED_IMAGE -> {
                        val im = Image(device, Image.ImageData().width(width).height(height).format(FORMAT).mipLevels(1)
                            .usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                            .debugName("syphon-in iosurface=$surfaceId")
                            .also { it.importMetalIOSurface = ref })
                        image = im
                        vkImage = im.vkImage
                    }
                    Path.HOST_LINEAR_IMAGE -> createLinear()
                    Path.HOST_BUFFER_COPY -> {
                        host = IOSurfaceHostBuffer(device, ref, readOnly = true)
                        val im = Image(device, Image.ImageData().width(width).height(height).format(FORMAT).mipLevels(1)
                            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                            .debugName("syphon-in staging iosurface=$surfaceId"))
                        image = im
                        vkImage = im.vkImage
                    }
                    Path.CPU_COPY -> error("not an import path")
                }
                view = ImageView(device, vkImage, ImageViewData().format(FORMAT).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevels(1)
                    .debugName("syphon-in view iosurface=$surfaceId"))
            } catch (t: Throwable) {
                release()
                throw t
            }
        }

        private fun createLinear() {
            val vk = device.vkDevice
            val alignment = device.minImportedHostPointerAlignment
            check(device.supportsExternalMemoryHost && alignment > 0) { "no VK_EXT_external_memory_host" }
            MemoryStack.stackPush().use { stack ->
                val externalInfo = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).`sType$Default`()
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                val formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack).`sType$Default`().pNext(externalInfo.address())
                    .format(FORMAT).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_LINEAR).usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                val externalProps = VkExternalImageFormatProperties.calloc(stack).`sType$Default`()
                val props = VkImageFormatProperties2.calloc(stack).`sType$Default`().pNext(externalProps.address())
                val r = vkGetPhysicalDeviceImageFormatProperties2(device.physicalDevice.vkPhysicalDevice, formatInfo, props)
                check(r == VK_SUCCESS) { "a linear, host-memory B8G8R8A8_SRGB sampled image is not supported (VkResult $r)" }
                check(externalProps.externalMemoryProperties().externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT != 0) {
                    "host memory is not importable for a linear B8G8R8A8_SRGB image"
                }

                val external = VkExternalMemoryImageCreateInfo.calloc(stack).`sType$Default`()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                val info = VkImageCreateInfo.calloc(stack).`sType$Default`().pNext(external.address())
                    .imageType(VK_IMAGE_TYPE_2D).format(FORMAT)
                    .extent { it.width(width).height(height).depth(1) }
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_LINEAR).usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED) // required for external memory
                val out = stack.mallocLong(1)
                vkCheck(vkCreateImage(vk, info, null, out), "vkCreateImage(Syphon linear)")
                linearImage = out[0]
                vkImage = linearImage

                val sub = VkImageSubresource.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).arrayLayer(0)
                val layoutOut = VkSubresourceLayout.calloc(stack)
                vkGetImageSubresourceLayout(vk, linearImage, sub, layoutOut)
                val pitch = MetalIOSurface.bytesPerRow(ref)
                check(layoutOut.offset() == 0L && layoutOut.rowPitch() == pitch) {
                    "the driver lays a ${width}x$height linear image out at row pitch ${layoutOut.rowPitch()} (offset ${layoutOut.offset()}), " +
                        "the IOSurface at $pitch"
                }
                val requirements = VkMemoryRequirements.calloc(stack)
                vkGetImageMemoryRequirements(vk, linearImage, requirements)

                MetalIOSurface.lock(ref, MetalIOSurface.LOCK_READ_ONLY)
                locked = true
                val base = MetalIOSurface.baseAddress(ref)
                val size = MetalIOSurface.allocSize(ref)
                check(base != 0L && base % alignment == 0L && size % alignment == 0L && requirements.size() <= size &&
                    base % requirements.alignment() == 0L) {
                    "IOSurface $surfaceId cannot be imported: base 0x${base.toString(16)}, size $size, host alignment $alignment, " +
                        "image needs ${requirements.size()} bytes aligned to ${requirements.alignment()}"
                }
                val pointer = VkMemoryHostPointerPropertiesEXT.calloc(stack).`sType$Default`()
                vkCheck(nvkGetMemoryHostPointerPropertiesEXT(vk, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, base, pointer.address()),
                    "vkGetMemoryHostPointerPropertiesEXT(Syphon IOSurface)")
                val import = VkImportMemoryHostPointerInfoEXT.calloc(stack).`sType$Default`()
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT).pHostPointer(base)
                val allocate = VkMemoryAllocateInfo.calloc(stack).`sType$Default`().pNext(import.address())
                    .allocationSize(size).memoryTypeIndex(memoryType(requirements.memoryTypeBits() and pointer.memoryTypeBits()))
                vkCheck(vkAllocateMemory(vk, allocate, null, out), "vkAllocateMemory(Syphon IOSurface host import)")
                linearMemory = out[0]
                vkCheck(vkBindImageMemory(vk, linearImage, linearMemory, 0), "vkBindImageMemory(Syphon linear)")
            }
        }

        private fun memoryType(bits: Int): Int {
            MemoryStack.stackPush().use { stack ->
                val available = VkPhysicalDeviceMemoryProperties.calloc(stack)
                vkGetPhysicalDeviceMemoryProperties(device.physicalDevice.vkPhysicalDevice, available)
                for (i in 0 until available.memoryTypeCount()) if (bits and (1 shl i) != 0) return i
            }
            error("no memory type can hold the imported IOSurface (bits 0x${bits.toString(16)})")
        }

        fun release() {
            view?.cleanup()
            image?.cleanup()
            host?.close()
            if (linearImage != 0L) vkDestroyImage(device.vkDevice, linearImage, null)
            if (linearMemory != 0L) vkFreeMemory(device.vkDevice, linearMemory, null)
            if (locked) MetalIOSurface.unlock(ref, MetalIOSurface.LOCK_READ_ONLY)
            MetalIOSurface.release(ref)
        }
    }

    private val device: Device get() = renderer.device
    private var current: Import? = null
    private var pendingCopy = false
    private var seen = -1L
    @Volatile private var closed = false

    // The copy path, made only when it is used.
    private var copySource: ByteBuffer? = null
    private var copySegment: MemorySegment? = null
    private var streaming: StreamingTexture? = null
    private var copyWidth = 0
    private var copyHeight = 0

    /** How the current frame reaches the shader. */
    @Volatile
    var path: Path = Path.CPU_COPY
        private set

    /** Why the zero-copy routes were not taken, when [path] is not one of them; else empty. */
    @Volatile
    var fallbackReason: String = ""
        private set

    /** New frames seen, frames that went through memory (CPU copies), and IOSurface (re-)imports. */
    @Volatile
    var frames: Long = 0
        private set
    @Volatile
    var cpuCopies: Long = 0
        private set
    @Volatile
    var imports: Long = 0
        private set

    /**
     * Nanoseconds this object spent on the render thread handling new frames, summed over
     * [frames]: on the copy path the blit, wait, swizzle and staging write; on the zero-copy paths
     * asking Syphon for the surface and comparing its id.
     */
    @Volatile
    var recordNanos: Long = 0
        private set

    /** Frames skipped on the copy path because their size changed. */
    @Volatile
    var skipped: Long = 0
        private set

    val width: Int get() = current?.width ?: copyWidth
    val height: Int get() = current?.height ?: copyHeight

    override var defaultSampler: TextureSampler? = TextureSampler(renderer, 1, false)

    override val imageView: ImageView
        get() = current?.view ?: streaming?.imageView ?: error("SyphonTexture has no frame yet")

    override val imageViewArray: List<HasImageView> get() = listOf(this)

    private val forceCopy = !zeroCopy || System.getProperty("afield.syphon.receive") == "copy"

    init {
        val tried = mutableListOf<String>()
        if (!forceCopy) refreshZeroCopy(tried)
        if (current == null) {
            fallbackReason = if (forceCopy) "zero-copy not requested" else tried.joinToString("; ")
            if (!forceCopy) lprintln("warning,syphon", "Syphon receive falls back to copying through memory: $fallbackReason")
            startCopyPath()
        }
        // Get the first picture in place before anything samples it.
        record(null)
    }

    private fun candidates(): List<Path> = buildList {
        if (device.supportsMetalObjects) add(Path.IMPORTED_IMAGE)
        if (device.supportsExternalMemoryHost) { add(Path.HOST_LINEAR_IMAGE); add(Path.HOST_BUFFER_COPY) }
        System.getProperty("afield.syphon.receive")?.let { want -> retainAll { it.name.equals(want, ignoreCase = true) } }
    }

    /** Import the client's current IOSurface if it is not the one already imported. */
    private fun refreshZeroCopy(tried: MutableList<String>? = null): Boolean {
        val handle = client.handleForTexture() ?: return false
        val ref = SyphonNative.frameSurface(handle)
        if (ref == 0L) return false
        val cur = current
        if (cur != null && MetalIOSurface.ioSurfaceId(ref) == cur.surfaceId) {
            MetalIOSurface.release(ref)
            return false
        }
        val order = if (cur != null) listOf(cur.path) + (candidates() - cur.path) else candidates()
        for ((i, p) in order.withIndex()) {
            // Each attempt owns a retain, which a refused Import releases.
            val retained = if (i == 0) ref else SyphonNative.frameSurface(handle).takeIf { it != 0L } ?: break
            try {
                val next = Import(retained, p)
                current = next
                path = p
                imports++
                if (cur != null) retire(cur)
                return true
            } catch (t: Throwable) {
                tried?.add("${p.name}: ${t.message}")
                lprintln("debug,syphon", "Syphon receive: ${p.name} refused: ${t.message}")
            }
        }
        if (tried != null && order.isEmpty()) tried.add("this Vulkan driver offers neither VK_EXT_metal_objects nor VK_EXT_external_memory_host")
        return false
    }

    private fun retire(old: Import) {
        val r = runCatching { renderer.afterCurrentGraphicsFrameComplete { old.release() } }
        if (r.isFailure) deferredRelease(old)
    }

    private fun deferredRelease(old: Import) {
        Thread({ Thread.sleep(250); old.release() }, "syphon-texture-release").apply { isDaemon = true }.start()
    }

    private fun startCopyPath() {
        val first = client.latest() ?: throw IllegalStateException("Syphon: no frame from ${client.server}")
        copyWidth = first.width
        copyHeight = first.height
        val src = ByteBuffer.allocateDirect(copyWidth * copyHeight * 4)
        src.put(first.rgba).flip()
        copySource = src
        copySegment = MemorySegment.ofBuffer(src)
        streaming = StreamingTexture(src, copyWidth, copyHeight, renderer, VK_FORMAT_R8G8B8A8_SRGB, "syphon_${copyWidth}x$copyHeight")
        path = Path.CPU_COPY
        cpuCopies++
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed) return
        val t0 = System.nanoTime()
        val before = frames
        record(command)
        if (frames != before) recordNanos += System.nanoTime() - t0
    }

    private fun record(command: CommandBuffer?) {
        val n = client.frameCount
        val isNew = n != seen
        seen = n
        if (isNew) frames++
        if (streaming != null) {
            if (!isNew && command != null) return
            val size = if (command == null) copyWidth to copyHeight else client.copyInto(copySegment!!)
            if (command == null) { streaming!!.uploadNow(copySource!!); return }
            if (size == null) return
            if (size.first != copyWidth || size.second != copyHeight) { skipped++; return }
            cpuCopies++
            streaming!!.recordUpload(command, copySource!!)
            return
        }
        if (isNew && command != null) refreshZeroCopy()
        val cur = current ?: return
        if (cur.path == Path.HOST_BUFFER_COPY && (isNew || command == null)) pendingCopy = true
        if (command == null) {
            oneShot { recordTransitions(it, cur) }
        } else recordTransitions(command, cur)
    }

    private fun oneShot(block: (CommandBuffer) -> Unit) {
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        block(cmd)
        cmd.endRecording()
        val fence = afield.graphics.vulkan.device.Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack -> renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence) }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
    }

    private fun recordTransitions(command: CommandBuffer, cur: Import) {
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
            if (cur.path == Path.HOST_BUFFER_COPY && pendingCopy) {
                pendingCopy = false
                image_layout_transition(command.vkCommandBuffer, cur.vkImage,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_COPY_BIT, 0, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range)
                cur.host!!.copyToImage(command.vkCommandBuffer, cur.vkImage, 4)
                image_layout_transition(command.vkCommandBuffer, cur.vkImage,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_ACCESS_2_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, range)
                cur.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            } else if (cur.layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && cur.path != Path.HOST_BUFFER_COPY) {
                // Once per import. Vulkan requires external-memory images to start UNDEFINED, and lets a
                // transition out of UNDEFINED discard contents; neither driver touches memory here
                // (Metal has no layouts), and the sender redraws the surface every frame regardless.
                image_layout_transition(command.vkCommandBuffer, cur.vkImage,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0, VK_ACCESS_2_SHADER_READ_BIT,
                    cur.layout, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, range)
                cur.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            }
        }
    }

    override fun prepareSampledImagesFor(command: CommandBuffer) {}

    /** The image currently sampled, its format, and its layout (for readback in tests). */
    internal val currentImage: Triple<Long, Int, Int>?
        get() = current?.let { Triple(it.vkImage, FORMAT, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) }
            ?: streaming?.let { Triple(it.image.vkImage, VK_FORMAT_R8G8B8A8_SRGB, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) }

    override fun close() {
        if (closed) return
        closed = true
        renderer.renderList.others.entries.removeIf { it.value === this }
        current?.let(::deferredRelease)
    }

    override fun toString(): String =
        "SyphonTexture(${client.server}, ${width}x$height, $path, $frames frames, $imports imports, $cpuCopies CPU copies)"

    companion object {
        /** The format of the zero-copy paths: the IOSurface's own bytes, read as sRGB. */
        const val FORMAT = VK_FORMAT_B8G8R8A8_SRGB
    }
}

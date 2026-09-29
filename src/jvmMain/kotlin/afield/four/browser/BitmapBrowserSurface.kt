package afield.four.browser

import afield.four.device.vkLayout
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.texture.image_layout_transition
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties

/**
 * The deliberately non-zero-copy browser receiver: frames arrive as CPU pixels and are uploaded,
 * instead of arriving as a shared GPU allocation and being imported.
 *
 * This exists because Chromium's Linux offscreen shared-texture export hands over a dma-buf it
 * never fills (electron#53271), which leaves the accelerated path with a correct handle and a black
 * picture. Everything above the seam — conflation, the stable resident texture, the fence-delayed
 * release, stream resets — is shared with the accelerated backends; only the way pixels reach the
 * GPU differs, which is the whole point of [CopiedExternalBrowserSurface.recordPreCopy].
 *
 * It is opt-in and should stay that way. A 1280x720 BGRA frame is 3.7 MB, so 30 fps is about
 * 110 MB/s crossing the process boundary and the PCIe bus that the accelerated path does not spend.
 * That is affordable for a UI panel and wrong for anything full-screen or high-rate, and a fallback
 * that selects itself silently is one nobody notices paying for.
 */
class BitmapBrowserSurface(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : CopiedExternalBrowserSurface(renderer, spec, releaseListener) {

    /** One slot's staging buffer and the device-local image the resident copy reads from. */
    private inner class Slot {
        val bytes: Long = spec.width.toLong() * spec.height.toLong() * bytesPerPixel
        val buffer: Long
        val bufferMemory: Long
        val mapped: java.nio.ByteBuffer
        val image: Long
        val imageMemory: Long

        init {
            MemoryStack.stackPush().use { stack ->
                val device = renderer.device
                val bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bytes)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                val out = stack.mallocLong(1)
                vkCheck(vkCreateBuffer(device.vkDevice, bufferInfo, null, out), "vkCreateBuffer(browser bitmap staging)")
                buffer = out[0]

                val requirements = VkMemoryRequirements.calloc(stack)
                vkGetBufferMemoryRequirements(device.vkDevice, buffer, requirements)
                val allocate = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(
                        memoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                        )
                    )
                vkCheck(vkAllocateMemory(device.vkDevice, allocate, null, out), "vkAllocateMemory(browser bitmap staging)")
                bufferMemory = out[0]
                vkCheck(vkBindBufferMemory(device.vkDevice, buffer, bufferMemory, 0), "vkBindBufferMemory(browser bitmap staging)")

                // Mapped once for the slot's lifetime: a per-frame map/unmap would be pure overhead
                // on a path that is already paying for the copy.
                val address = stack.mallocPointer(1)
                vkCheck(
                    vkMapMemory(device.vkDevice, bufferMemory, 0, bytes, 0, address),
                    "vkMapMemory(browser bitmap staging)"
                )
                mapped = MemoryUtil.memByteBuffer(address[0], bytes.toInt())

                val imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(importVkFormat)
                    .extent { it.set(spec.width, spec.height, 1) }
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                vkCheck(vkCreateImage(device.vkDevice, imageInfo, null, out), "vkCreateImage(browser bitmap)")
                image = out[0]
                vkGetImageMemoryRequirements(device.vkDevice, image, requirements)
                val imageAllocate = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
                vkCheck(vkAllocateMemory(device.vkDevice, imageAllocate, null, out), "vkAllocateMemory(browser bitmap)")
                imageMemory = out[0]
                vkCheck(vkBindImageMemory(device.vkDevice, image, imageMemory, 0), "vkBindImageMemory(browser bitmap)")
            }
        }

        fun cleanup() {
            val device = renderer.device
            vkUnmapMemory(device.vkDevice, bufferMemory)
            vkDestroyBuffer(device.vkDevice, buffer, null)
            vkFreeMemory(device.vkDevice, bufferMemory, null)
            vkDestroyImage(device.vkDevice, image, null)
            vkFreeMemory(device.vkDevice, imageMemory, null)
        }
    }

    private val bytesPerPixel = when (spec.pixelFormat) {
        BrowserSurfacePixelFormat.RGBA16_FLOAT -> 8
        else -> 4
    }
    private val slots = mutableMapOf<Int, Slot>()

    /** Set by [importFrame] and consumed by the very next [recordPreCopy]; prepare is not reentrant. */
    private var pendingUpload: Slot? = null

    override fun importFrame(frame: ExternalBrowserFrame): ImportedImage? {
        val handle = frame.handle as? ExternalBrowserImageHandle.CpuBitmap ?: return null
        val slot = slots.getOrPut(frame.slot) { Slot() }
        val expected = slot.bytes
        require(handle.pixels.remaining().toLong() == expected) {
            "browser bitmap frame is ${handle.pixels.remaining()} bytes, expected $expected"
        }

        // Straight into the mapped staging allocation, then the producer's copy is dead to us: the
        // bytes belong to the GPU now, so free them here rather than leaving a 3.7 MB buffer alive
        // until whatever else might have closed the handle gets round to it.
        slot.mapped.clear()
        MemoryUtil.memCopy(handle.pixels, slot.mapped)
        handle.close()

        pendingUpload = slot
        return ImportedImage(
            identity = frame.slot,
            image = slot.image,
            cleanupAction = { },
            layout = VK_IMAGE_LAYOUT_UNDEFINED
        )
    }

    override fun recordPreCopy(command: CommandBuffer, source: ImportedImage) {
        val slot = pendingUpload ?: return
        pendingUpload = null
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            // The previous frame left this image in GENERAL; the first leaves it UNDEFINED. Either
            // way its contents are about to be entirely overwritten, so discarding them is correct.
            image_layout_transition(
                command.vkCommandBuffer,
                slot.image,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                0,
                VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                range
            )

            val region = VkBufferImageCopy.calloc(1, stack)
            region[0]
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                .imageOffset { it.set(0, 0, 0) }
                .imageExtent { it.set(spec.width, spec.height, 1) }
            vkCmdCopyBufferToImage(
                command.vkCommandBuffer,
                slot.buffer,
                slot.image,
                vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
                region
            )
        }
        // recordCopy transitions from here to TRANSFER_SRC_OPTIMAL for the copy into the resident
        // texture, so tell it where the image actually is rather than where importFrame guessed.
        source.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
    }

    override fun cleanupImports() {
        pendingUpload = null
        slots.values.forEach { it.cleanup() }
        slots.clear()
    }

    private fun memoryType(bits: Int, properties: Int): Int {
        MemoryStack.stackPush().use { stack ->
            val available = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(renderer.device.physicalDevice.vkPhysicalDevice, available)
            for (index in 0 until available.memoryTypeCount()) {
                if ((bits and (1 shl index)) != 0 &&
                    (available.memoryTypes(index).propertyFlags() and properties) == properties
                ) return index
            }
        }
        error("no Vulkan memory type for browser bitmap staging: bits=0x${bits.toString(16)}")
    }
}

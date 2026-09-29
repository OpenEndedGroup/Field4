package afield.four.texture

import afield.four.device.Device
import afield.four.device.vkLayout
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT
import org.lwjgl.vulkan.EXTExternalMemoryHost.nvkGetMemoryHostPointerPropertiesEXT
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkExternalMemoryBufferCreateInfo
import org.lwjgl.vulkan.VkImportMemoryHostPointerInfoEXT
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryHostPointerPropertiesEXT
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties

/**
 * An IOSurface's own pages as a `VkBuffer`, through `VK_EXT_external_memory_host`.
 *
 * This is how an IOSurface reaches Vulkan on KosmicKrisp. That driver imports image memory only as
 * an `MTLHeap` (measured, SDK 1.4.363: `MTLTEXTURE` import is `VK_ERROR_FORMAT_NOT_SUPPORTED` for
 * every format and usage, and there is no heap-over-IOSurface in Metal), and it has no
 * `VK_EXT_metal_objects`, so an IOSurface cannot back a `VkImage` there. It can back a buffer: on
 * Apple silicon an IOSurface is ordinary unified memory, so one GPU copy per frame between this
 * buffer and an ordinary image moves the pixels without the CPU touching them — [copyToImage] for a
 * surface someone else fills (a browser frame), [copyFromImage] for one someone else reads (the
 * `field-encode` ring). Verified both directions on KosmicKrisp and MoltenVK at 1920×1080 and at
 * 1366×768, whose 5504-byte row pitch is not width × 4.
 *
 * The surface stays locked for this object's lifetime: `IOSurfaceGetBaseAddress` is only defined
 * while it is. The caller keeps its own retain on the IOSurfaceRef and releases it after [close].
 * [close] must run only once the GPU has finished with the buffer.
 */
class IOSurfaceHostBuffer(
    private val device: Device,
    val ioSurfaceRef: Long,
    private val readOnly: Boolean,
) : AutoCloseable {
    val width: Int = MetalIOSurface.width(ioSurfaceRef)
    val height: Int = MetalIOSurface.height(ioSurfaceRef)
    val bytesPerRow: Long = MetalIOSurface.bytesPerRow(ioSurfaceRef)
    private val size: Long = MetalIOSurface.allocSize(ioSurfaceRef)
    val buffer: Long
    private val memory: Long
    private var closed = false

    init {
        check(device.supportsExternalMemoryHost) { "IOSurfaceHostBuffer needs VK_EXT_external_memory_host" }
        MetalIOSurface.lock(ioSurfaceRef, if (readOnly) MetalIOSurface.LOCK_READ_ONLY else 0)
        try {
            val base = MetalIOSurface.baseAddress(ioSurfaceRef)
            val alignment = device.minImportedHostPointerAlignment
            check(base != 0L && alignment > 0 && base % alignment == 0L && size % alignment == 0L) {
                "IOSurface ${MetalIOSurface.ioSurfaceId(ioSurfaceRef)} cannot be imported: base 0x${base.toString(16)}, " +
                    "size $size, host-pointer alignment $alignment"
            }
            MemoryStack.stackPush().use { stack ->
                val vkDevice = device.vkDevice
                val external = VkExternalMemoryBufferCreateInfo.calloc(stack).`sType$Default`()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                val info = VkBufferCreateInfo.calloc(stack).`sType$Default`().pNext(external.address())
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                val out = stack.mallocLong(1)
                vkCheck(vkCreateBuffer(vkDevice, info, null, out), "vkCreateBuffer(IOSurface host buffer)")
                buffer = out[0]

                val pointerProperties = VkMemoryHostPointerPropertiesEXT.calloc(stack).`sType$Default`()
                vkCheck(
                    nvkGetMemoryHostPointerPropertiesEXT(vkDevice, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT,
                        base, pointerProperties.address()),
                    "vkGetMemoryHostPointerPropertiesEXT(IOSurface)"
                )
                val requirements = VkMemoryRequirements.calloc(stack)
                vkGetBufferMemoryRequirements(vkDevice, buffer, requirements)
                val import = VkImportMemoryHostPointerInfoEXT.calloc(stack).`sType$Default`()
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT)
                    .pHostPointer(base)
                val allocate = VkMemoryAllocateInfo.calloc(stack).`sType$Default`().pNext(import.address())
                    .allocationSize(size)
                    .memoryTypeIndex(memoryType(requirements.memoryTypeBits() and pointerProperties.memoryTypeBits()))
                vkCheck(vkAllocateMemory(vkDevice, allocate, null, out), "vkAllocateMemory(IOSurface host import)")
                memory = out[0]
                vkCheck(vkBindBufferMemory(vkDevice, buffer, memory, 0), "vkBindBufferMemory(IOSurface host buffer)")
            }
        } catch (t: Throwable) {
            MetalIOSurface.unlock(ioSurfaceRef, if (readOnly) MetalIOSurface.LOCK_READ_ONLY else 0)
            throw t
        }
    }

    /** Record surface → [image] (in `TRANSFER_DST_OPTIMAL`), [bytesPerPixel] per texel. */
    fun copyToImage(commandBuffer: org.lwjgl.vulkan.VkCommandBuffer, image: Long, bytesPerPixel: Int) =
        MemoryStack.stackPush().use { stack ->
            vkCmdCopyBufferToImage(commandBuffer, buffer, image, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL), region(stack, bytesPerPixel))
        }

    /** Record [image] (in `TRANSFER_SRC_OPTIMAL`) → surface, [bytesPerPixel] per texel. */
    fun copyFromImage(commandBuffer: org.lwjgl.vulkan.VkCommandBuffer, image: Long, bytesPerPixel: Int) =
        MemoryStack.stackPush().use { stack ->
            vkCmdCopyImageToBuffer(commandBuffer, image, vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL), buffer, region(stack, bytesPerPixel))
        }

    // Rows at the surface's own pitch, which IOSurface pads (1366 × 4 = 5464 is laid out at 5504).
    private fun region(stack: MemoryStack, bytesPerPixel: Int): VkBufferImageCopy.Buffer {
        check(bytesPerRow % bytesPerPixel == 0L) { "IOSurface row pitch $bytesPerRow is not a whole number of $bytesPerPixel-byte texels" }
        val region = VkBufferImageCopy.calloc(1, stack)
        region[0]
            .bufferOffset(0)
            .bufferRowLength((bytesPerRow / bytesPerPixel).toInt())
            .bufferImageHeight(height)
            .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
            .imageOffset { it.set(0, 0, 0) }
            .imageExtent { it.set(width, height, 1) }
        return region
    }

    override fun close() {
        if (closed) return
        closed = true
        vkDestroyBuffer(device.vkDevice, buffer, null)
        vkFreeMemory(device.vkDevice, memory, null)
        MetalIOSurface.unlock(ioSurfaceRef, if (readOnly) MetalIOSurface.LOCK_READ_ONLY else 0)
    }

    private fun memoryType(bits: Int): Int {
        MemoryStack.stackPush().use { stack ->
            val available = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(device.physicalDevice.vkPhysicalDevice, available)
            for (index in 0 until available.memoryTypeCount()) if ((bits and (1 shl index)) != 0) return index
        }
        error("no Vulkan memory type can hold an imported IOSurface: bits=0x${bits.toString(16)}")
    }
}

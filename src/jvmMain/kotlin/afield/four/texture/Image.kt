package afield.four.texture

import afield.four.device.nameObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.device.Device
import afield.four.util.memoryTypeFromProperties
import afield.four.util.vkCheck
import afield.four.util.AutoDisposer
import org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2
import org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2

class Image(private val device: Device, val imageData: ImageData) {
    var format: Int = 0
    var mipLevels: Int = 0
    var vkImage: Long = 0
    var vkMemory: Long = 0
    private val autoDisposeToken = AutoDisposer.register(this, "Image(format=${imageData.format}, ${imageData.width}x${imageData.height})") { /* set after init */ }

    init {
        MemoryStack.stackPush().use { stack ->
            this.format = imageData.format
            this.mipLevels = imageData.mipLevels

            val imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .flags(
                    imageData.flags or
                        if (imageData.aliasSingleLayerDescriptors && device.supportsMaintenance11) {
                            KHRMaintenance11.VK_IMAGE_CREATE_ALIAS_SINGLE_LAYER_DESCRIPTOR_BIT_KHR
                        } else {
                            0
                        }
                )
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(format)
                .extent { it: VkExtent3D ->
                    it
                        .width(imageData.width)
                        .height(imageData.height)
                        .depth(1)
                }
                .mipLevels(mipLevels)
                .arrayLayers(imageData.arrayLayers)
                .samples(imageData.sampleCount)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(imageData.usage)

            // Make the backing MTLTexture IOSurface-exportable (MoltenVK) so the image can be
            // handed zero-copy to a hardware video encoder. Opt-in; inert otherwise.
            if (imageData.exportMetalIOSurface) {
                val exportInfo = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECT_CREATE_INFO_EXT)
                    .exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_IOSURFACE_BIT_EXT)
                imageCreateInfo.pNext(exportInfo.address())
            }

            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateImage(device.vkDevice, imageCreateInfo, null, lp),
                "Failed to create image"
            )
            vkImage = lp[0]

            // Get memory requirements for this object
            val memReqs = VkMemoryRequirements.calloc(stack)
            VK10.vkGetImageMemoryRequirements(device.vkDevice, vkImage, memReqs)

            // Select memory size and type
            val memAlloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(
                    memoryTypeFromProperties(
                        device.physicalDevice,
                        memReqs.memoryTypeBits(),
                        imageData.memoryPropertyFlags
                    )
                )

            // Allocate memory
            vkCheck(VK10.vkAllocateMemory(device.vkDevice, memAlloc, null, lp), "Failed to allocate memory")
            vkMemory = lp[0]

            // Bind memory
            vkCheck(
                VK10.vkBindImageMemory(device.vkDevice, vkImage, vkMemory, 0),
                "Failed to bind image memory"
            )
        }
        imageData.debugName.takeIf { it.isNotBlank() }?.let {
            device.nameObject(vkImage, VK10.VK_OBJECT_TYPE_IMAGE, it)
        }
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    fun cleanup() {
        autoDisposeToken.disable()
        VK10.vkDestroyImage(device.vkDevice, vkImage, null)
        VK10.vkFreeMemory(device.vkDevice, vkMemory, null)
    }

    private fun snapshotCleanup(): () -> Unit {
        val dev = device.vkDevice
        val img = vkImage
        val mem = vkMemory
        return {
            VK10.vkDestroyImage(dev, img, null)
            VK10.vkFreeMemory(dev, mem, null)
        }
    }

    // Per-command-buffer layout tracking (optional; best-effort)
    @Volatile
    private var layoutByCommandBuffer = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun setLayoutFor(commandBufferHandle: Long, layout: Int) {
        layoutByCommandBuffer[commandBufferHandle] = layout
    }

    fun getLayoutFor(commandBufferHandle: Long): Int? = layoutByCommandBuffer[commandBufferHandle]

    /**
     * Export the IOSurfaceRef backing this image's MTLTexture (MoltenVK, VK_EXT_metal_objects).
     * Requires [ImageData.exportMetalIOSurface] = true at creation and the device to have enabled
     * VK_EXT_metal_objects. Returns the IOSurfaceRef pointer (pass to [afield.four.texture.MetalIOSurface.ioSurfaceId]),
     * or 0 if the surface could not be exported.
     */
    fun exportMetalIOSurfaceRef(): Long {
        MemoryStack.stackPush().use { stack ->
            val ioInfo = VkExportMetalIOSurfaceInfoEXT.calloc(stack)
                .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_IO_SURFACE_INFO_EXT)
                .image(vkImage)
            val objects = VkExportMetalObjectsInfoEXT.calloc(stack)
                .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT)
                .pNext(ioInfo.address())
            EXTMetalObjects.vkExportMetalObjectsEXT(device.vkDevice, objects)
            return ioInfo.ioSurface()
        }
    }

    class ImageData {
        var arrayLayers: Int = 1
        internal var format: Int
        var flags: Int = 0
        var height: Int = 0
        var aliasSingleLayerDescriptors: Boolean = false
        var debugName: String = ""
        var mipLevels: Int = 1
        var memoryPropertyFlags: Int = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        var sampleCount: Int = 1
        var usage: Int = 0

        /**
         * MoltenVK only: create the backing MTLTexture as IOSurface-exportable
         * (chains VkExportMetalObjectCreateInfoEXT). Enables [Image.exportMetalIOSurfaceRef].
         */
        var exportMetalIOSurface: Boolean = false
        var width: Int = 0

        init {
            this.format = VK10.VK_FORMAT_R8G8B8A8_SRGB
        }

        fun arrayLayers(arrayLayers: Int): ImageData {
            this.arrayLayers = arrayLayers
            return this
        }

        fun format(format: Int): ImageData {
            this.format = format
            return this
        }

        fun flags(flags: Int): ImageData {
            this.flags = flags
            return this
        }

        fun height(height: Int): ImageData {
            this.height = height
            return this
        }

        fun debugName(debugName: String): ImageData {
            this.debugName = debugName
            return this
        }

        fun aliasSingleLayerDescriptors(aliasSingleLayerDescriptors: Boolean = true): ImageData {
            this.aliasSingleLayerDescriptors = aliasSingleLayerDescriptors
            return this
        }

        fun mipLevels(mipLevels: Int): ImageData {
            this.mipLevels = mipLevels
            return this
        }

        fun memoryPropertyFlags(memoryPropertyFlags: Int): ImageData {
            this.memoryPropertyFlags = memoryPropertyFlags
            return this
        }

        fun sampleCount(sampleCount: Int): ImageData {
            this.sampleCount = sampleCount
            return this
        }

        fun usage(usage: Int): ImageData {
            this.usage = usage
            return this
        }

        fun width(width: Int): ImageData {
            this.width = width
            return this
        }

        val bytesPerPixel: Int
            get() = when (format) {
                VK10.VK_FORMAT_R8_UNORM -> 1
                VK10.VK_FORMAT_R8G8_UNORM -> 2
                VK10.VK_FORMAT_R8G8B8_UNORM,
                VK10.VK_FORMAT_B8G8R8_UNORM -> 3
                VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VK10.VK_FORMAT_R8G8B8A8_SRGB,
                VK10.VK_FORMAT_B8G8R8A8_UNORM,
                VK10.VK_FORMAT_B8G8R8A8_SRGB -> 4
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT -> 8
                VK10.VK_FORMAT_R32G32B32A32_SFLOAT -> 16
                else -> 4 // sensible default for common color formats
            }
    }
}


// --- barrier util (now sync2) ---

fun image_layout_transition(
    command_buffer: VkCommandBuffer,
    image: Long,
    src_stage_mask: Long,
    dst_stage_mask: Long,
    src_access_mask: Long,
    dst_access_mask: Long,
    old_layout: Int,
    new_layout: Int,
    sub_resource_range: VkImageSubresourceRange
) {
    MemoryStack.stackPush().use { stack ->
        val barrier2 = VkImageMemoryBarrier2.calloc(1, stack)
        barrier2[0]
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(src_stage_mask)
            .srcAccessMask(src_access_mask)
            .dstStageMask(dst_stage_mask)
            .dstAccessMask(dst_access_mask)
            .oldLayout(old_layout)
            .newLayout(new_layout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
            .subresourceRange(sub_resource_range)

        val dep = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier2)

        vkCmdPipelineBarrier2(command_buffer, dep)
    }
}

fun image_layout_transition(
    command_buffer: VkCommandBuffer,
    image: Image,
    src_stage_mask: Long,
    dst_stage_mask: Long,
    src_access_mask: Long,
    dst_access_mask: Long,
    old_layout: Int,
    new_layout: Int,
    sub_resource_range: VkImageSubresourceRange
) {
    image_layout_transition(
        command_buffer,
        image.vkImage,
        src_stage_mask,
        dst_stage_mask,
        src_access_mask,
        dst_access_mask,
        old_layout,
        new_layout,
        sub_resource_range
    )
    image.setLayoutFor(command_buffer.address(), new_layout)
}

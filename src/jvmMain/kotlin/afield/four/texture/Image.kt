package afield.four.texture

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.device.Device
import afield.four.util.memoryTypeFromProperties
import afield.four.util.vkCheck

class Image(private val device: Device, imageData: ImageData) {
    var format: Int = 0
    var mipLevels: Int = 0
    var vkImage: Long = 0
    var vkMemory: Long = 0

    init {
        MemoryStack.stackPush().use { stack ->
            this.format = imageData.format
            this.mipLevels = imageData.mipLevels

            val imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
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
                        memReqs.memoryTypeBits(), 0
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
    }

    fun cleanup() {
        VK10.vkDestroyImage(device.vkDevice, vkImage, null)
        VK10.vkFreeMemory(device.vkDevice, vkMemory, null)
    }

    class ImageData {
        var arrayLayers: Int = 1
        internal var format: Int
        var height: Int = 0
        var mipLevels: Int = 1
        var sampleCount: Int = 1
        var usage: Int = 0
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

        fun height(height: Int): ImageData {
            this.height = height
            return this
        }

        fun mipLevels(mipLevels: Int): ImageData {
            this.mipLevels = mipLevels
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
    }
}
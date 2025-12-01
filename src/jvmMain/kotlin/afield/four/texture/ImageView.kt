package afield.four.texture

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import afield.four.device.Device
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck

interface HasImageView {
    val imageView: ImageView
}

interface HasImageViewArray {
    val imageViewArray: List<ImageView>
}

class ImageView(private val device: Device, val image: Long, val imageViewData: ImageViewData) : HasImageView{
    val aspectMask: Int
    val mipLevels: Int
    var vkImageView: Long = 0

    var underlyingImage : Image? = null
    private val autoDisposeToken = AutoDisposer.register(
        this,
        "ImageView(fmt=${imageViewData.format}, layers=${imageViewData.layerCount}, mips=${imageViewData.mipLevels})"
    ) { /* set after init */ }

    constructor(device: Device, image: Image, imageViewData: ImageViewData) : this(device, image.vkImage, imageViewData) {
        source(image)
    }

    init {
        this.aspectMask = imageViewData.aspectMask
        this.mipLevels = imageViewData.mipLevels
        MemoryStack.stackPush().use { stack ->
            val lp = stack.mallocLong(1)
            val viewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .pNext(0)
                .flags(imageViewData.flags)
                .image(image)
                .viewType(imageViewData.viewType)
                .format(imageViewData.format)
                .subresourceRange { it: VkImageSubresourceRange ->
                    it
                        .aspectMask(aspectMask)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(imageViewData.baseArrayLayer)
                        .layerCount(imageViewData.layerCount)
                }

            vkCheck(
                VK10.vkCreateImageView(device.vkDevice, viewCreateInfo, null, lp),
                "Failed to create image view"
            )
            vkImageView = lp[0]
        }
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    fun cleanup() {
        autoDisposeToken.disable()
        VK10.vkDestroyImageView(device.vkDevice, vkImageView, null)
    }

    fun source(i : Image) : ImageView {
        underlyingImage = i
        return this
    }

    private fun snapshotCleanup(): () -> Unit {
        val dev = device.vkDevice
        val view = vkImageView
        return { VK10.vkDestroyImageView(dev, view, null) }
    }

    override val imageView: ImageView
        get() = this

    class ImageViewData {
        var aspectMask: Int = 0
        var baseArrayLayer: Int = 0
        var format: Int = 0
        var layerCount: Int = 1
        var mipLevels: Int = 1
        var viewType: Int
        var flags: Int = 0

        init {
            this.viewType = VK10.VK_IMAGE_VIEW_TYPE_2D
        }

        fun aspectMask(aspectMask: Int): ImageViewData {
            this.aspectMask = aspectMask
            return this
        }

        fun baseArrayLayer(baseArrayLayer: Int): ImageViewData {
            this.baseArrayLayer = baseArrayLayer
            return this
        }

        fun format(format: Int): ImageViewData {
            this.format = format
            return this
        }

        fun layerCount(layerCount: Int): ImageViewData {
            this.layerCount = layerCount
            return this
        }

        fun flags(flags: Int): ImageViewData {
            this.flags = flags
            return this
        }

        fun mipLevels(mipLevels: Int): ImageViewData {
            this.mipLevels = mipLevels
            return this
        }

        fun viewType(viewType: Int): ImageViewData {
            this.viewType = viewType
            return this
        }
    }


}

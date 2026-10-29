/**
 * [ImageView] wraps a `VkImageView` over an image, and the small interfaces Field's shader bindings use to find views and samplers.
 *
 * [HasImageView] and [HasImageViewArray] are what a texture binding accepts; [PreparesSampledImages]
 * lets a source record its layout transitions before sampling, and [HasDefaultTextureSampler] carries
 * the sampler a binding uses when none is given.
 */

package afield.graphics.texture

import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.nameObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkImageViewCreateInfo
import afield.graphics.vulkan.device.Device
import afield.graphics.vulkan.util.AutoDisposer
import afield.graphics.vulkan.util.vkCheck

interface HasImageView {
    /** The view to bind. Implementations that follow a ring or a ping-pong swap return a different view on each read, which is what lets a binding made once keep pointing at the current image. */
    val imageView: ImageView
}

interface HasImageViewArray {
    /** The views to bind, in the order the shader's array takes them. Every one is written, so the list's length is the descriptor count the array binding receives; an empty list is refused. */
    val imageViewArray: List<HasImageView>
}

interface PreparesSampledImages {
    fun prepareSampledImagesFor(command: CommandBuffer)
}

interface HasDefaultTextureSampler {
    /** The sampler a texture binding uses when the module does not name one, or null to leave that to the binding. The ping-pong targets forward this to the target itself, so setting it once covers every view they hand out. */
    var defaultSampler: TextureSampler?
}

class ImageView(
    private val device: Device,
    /** The `VkImage` this is a view of. The constructor taking an [Image] passes its handle and records the image in [underlyingImage]; this one is for a handle whose owner is elsewhere, a swapchain image among them. */
    val image: Long,
    /** The view's format, aspect, layer range, mip count and debug name, read once here and kept for anything that needs to know what the view covers. */
    val imageViewData: ImageViewData
) : HasImageView {
    /** Which aspects of the image the view covers, from [imageViewData] — colour, or depth with or without stencil. A combined depth+stencil view cannot be sampled, which is why a sampled depth attachment gets a second, depth-only view. */
    val aspectMask: Int

    /** How many mip levels the view covers, starting at level 0. A view of one level over a mipped image is how a render pass writes a single level. */
    val mipLevels: Int

    /** The `VkImageView` handle, 0 until the constructor has created it. */
    var vkImageView: Long = 0

    /** The [Image] this views, when the view was made from one. It stays null for a view built over a bare handle, so a caller that needs the image's layout or memory has to hold it separately. */
    var underlyingImage : Image? = null
    private val autoDisposeToken = AutoDisposer.register(
        this,
        "ImageView(fmt=${imageViewData.format}, layers=${imageViewData.layerCount}, mips=${imageViewData.mipLevels})",
        owner = device,
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
        imageViewData.debugName.takeIf { it.isNotBlank() }?.let {
            device.nameObject(vkImageView, VK10.VK_OBJECT_TYPE_IMAGE_VIEW, it)
        }
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    fun cleanup() {
        if (!autoDisposeToken.disable()) return
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
        var debugName: String = ""

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

        fun debugName(debugName: String): ImageViewData {
            this.debugName = debugName
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

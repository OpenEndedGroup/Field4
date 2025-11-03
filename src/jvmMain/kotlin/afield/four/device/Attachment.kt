package afield.four.device

import org.lwjgl.vulkan.VK10
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData

class Attachment(device: Device, width: Int, height: Int, format: Int, usage: Int) {
    val image: Image
    val imageView: ImageView

    var isDepthAttachment: Boolean = false

    init {
        val imageData =
            ImageData().width(width).height(height).usage(usage or VK10.VK_IMAGE_USAGE_SAMPLED_BIT).format(format)
        image = Image(device, imageData)

        var aspectMask = 0
        if ((usage and VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) > 0) {
            aspectMask = VK10.VK_IMAGE_ASPECT_COLOR_BIT
            isDepthAttachment = false
        }
        if ((usage and VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT) > 0) {
            aspectMask = VK10.VK_IMAGE_ASPECT_DEPTH_BIT
            isDepthAttachment = true
        }

        val imageViewData = ImageViewData().format(image.format).aspectMask(aspectMask)
        imageView = ImageView(device, image.vkImage, imageViewData)
    }

    fun cleanup() {
        imageView.cleanup()
        image.cleanup()
    }
}

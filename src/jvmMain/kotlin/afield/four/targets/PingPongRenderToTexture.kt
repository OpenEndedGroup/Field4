package afield.four.targets

import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.texture.HasImageView
import afield.four.texture.Image
import afield.four.texture.Image.ImageData
import afield.four.texture.ImageView
import afield.four.texture.ImageView.ImageViewData
import org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY

/**
 * RenderToTexture variant that double-buffers the first color attachment.
 * Each frame it renders into one image while exposing the other via [otherImageView]
 * so modules can sample the previous frame without managing two RTT instances.
 */
class PingPongRenderToTexture(
    renderer: Renderer = fromContext(),
    parameters: RTTParameters.() -> Unit = {}
) : RenderToTexture(renderer, parameters) {

    private val pingImages: List<Image>
    private val pingImageViews: List<HasImageView>
    private val pingImageHandles: List<Long>
    private val pingLayouts = intArrayOf(VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_UNDEFINED)
    private val pingMipsInitialized = booleanArrayOf(false, false)
    private var writeIndex = 0

    val otherImageView: HasImageView = object : HasImageView{
        override val imageView: ImageView
            get() = pingImageViews[otherIndex].imageView
    }

    val thisImageView: HasImageView = object : HasImageView{
        override val imageView: ImageView
            get() = pingImageViews[writeIndex].imageView
    }


    private val otherIndex: Int
        get() = 1 - writeIndex

    init {
        require(colorImageHandles.isNotEmpty()) { "PingPongRenderToTexture requires at least one color attachment" }
        require(!this.parameters.externalColorViews.containsKey(0)) {
            "PingPongRenderToTexture does not support an external color view for attachment 0"
        }

        val baseImage = requireNotNull(colorImageRefs[0]) {
            "PingPongRenderToTexture attachment 0 must be internally owned"
        }

        val wantsMips = this.parameters.mipLevels > 1 && this.parameters.generateColorMipmaps
        val needsTransfer = wantsMips && colorFormatCanBlit.getOrElse(0) { false }

        val imageData = ImageData()
            .width(width)
            .height(height)
            .arrayLayers(colorLayerCount.coerceAtLeast(1))
            .usage(
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                        (if (this.parameters.colorSampled) VK_IMAGE_USAGE_SAMPLED_BIT else 0) or
                        (if (needsTransfer) VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT else 0)
            )
            .format(colorFormats.first())
            .mipLevels(mipLevels)

        val pingImage = Image(renderer.device, imageData)
        val pingViewData = ImageViewData()
            .format(imageData.format)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .layerCount(imageData.arrayLayers)
            .mipLevels(mipLevels)

        if (pingViewData.layerCount > 1) pingViewData.viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)

        val pingView = ImageView(renderer.device, pingImage, pingViewData)

        pingImages = listOf(baseImage, pingImage)
        pingImageViews = listOf(mutableImageViewArray[0], pingView)
        pingImageHandles = listOf(colorImageHandles[0], pingImage.vkImage)

        pingLayouts[0] = colorLayout.getOrElse(0) { VK_IMAGE_LAYOUT_UNDEFINED }
        pingMipsInitialized[0] = colorMipsInitialized.getOrElse(0) { false }
    }

    override fun beforeRender(index: Int) {
        val write = writeIndex
        colorImageRefs[0] = pingImages[write]
        colorImageHandles[0] = pingImageHandles[write]
        mutableImageViewArray[0] = pingImageViews[write]
        colorLayout[0] = pingLayouts[write]
        colorMipsInitialized[0] = pingMipsInitialized[write]
    }

    override fun afterRender(index: Int) {
        val write = writeIndex
        pingLayouts[write] = colorLayout[0]
        pingMipsInitialized[write] = colorMipsInitialized[0]
        writeIndex = otherIndex
    }
}

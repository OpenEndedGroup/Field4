package afield.four.texture
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.vkCreateSampler
import org.lwjgl.vulkan.VkSamplerCreateInfo
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.vkCheck


class TextureSampler(val renderer: Renderer = fromContext(null), mipLevels: Int, anisotropyEnable: Boolean) {
    var vkSampler: Long = 0

    init {
        println(" making sampler")
        MemoryStack.stackPush().use { stack ->
            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK10.VK_FILTER_LINEAR)
                .minFilter(VK10.VK_FILTER_LINEAR)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                .borderColor(VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .unnormalizedCoordinates(false)
                .compareEnable(false)
                .compareOp(VK10.VK_COMPARE_OP_ALWAYS)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .minLod(0.0f)
                .maxLod(mipLevels.toFloat())
                .mipLodBias(0.0f)

                samplerInfo
                    .anisotropyEnable(false)
//                    .maxAnisotropy(MAX_ANISOTROPY.toFloat())

            val lp = stack.mallocLong(1)
            vkCheck(vkCreateSampler(renderer.device.vkDevice, samplerInfo, null, lp), "Failed to create sampler")
            vkSampler = lp[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroySampler(renderer.device.vkDevice, vkSampler, null)
    }

    companion object {
        private const val MAX_ANISOTROPY = 16
    }
}

package afield.four.texture
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.vkCreateSampler
import org.lwjgl.vulkan.VkSamplerCreateInfo
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.util.AutoDisposer
import afield.four.util.vkCheck
import kotlin.math.max


class TextureSampler(
    val renderer: Renderer = fromContext(null),
    mipLevels: Int,
    anisotropyEnable: Boolean,
    filter: Int = VK10.VK_FILTER_LINEAR,
    mipmapMode: Int = if (filter == VK10.VK_FILTER_LINEAR) VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR else VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST,
    addressModeU: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
    addressModeV: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
    addressModeW: Int = VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
) {
    var vkSampler: Long = 0
    private val autoDisposeToken = AutoDisposer.register(this, "TextureSampler(mips=$mipLevels)") { /* set after init */ }

    init {
        println(" making sampler")
        MemoryStack.stackPush().use { stack ->
            val samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(filter)
                .minFilter(filter)
                .addressModeU(addressModeU)
                .addressModeV(addressModeV)
                .addressModeW(addressModeW)
                .borderColor(VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .unnormalizedCoordinates(false)
                .compareEnable(false)
                .compareOp(VK10.VK_COMPARE_OP_ALWAYS)
                .mipmapMode(mipmapMode)
                .minLod(0.0f)
                .maxLod(max(0, mipLevels - 1).toFloat())
                .mipLodBias(0.0f)

                samplerInfo
                    .anisotropyEnable(false)
//                    .maxAnisotropy(MAX_ANISOTROPY.toFloat())

            val lp = stack.mallocLong(1)
            vkCheck(vkCreateSampler(renderer.device.vkDevice, samplerInfo, null, lp), "Failed to create sampler")
            vkSampler = lp[0]
        }
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    fun cleanup() {
        autoDisposeToken.disable()
        VK10.vkDestroySampler(renderer.device.vkDevice, vkSampler, null)
    }

    private fun snapshotCleanup(): () -> Unit {
        val dev = renderer.device.vkDevice
        val sampler = vkSampler
        return { VK10.vkDestroySampler(dev, sampler, null) }
    }

    companion object {
        private const val MAX_ANISOTROPY = 16
    }
}

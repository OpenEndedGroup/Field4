package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo
import afield.four.util.vkCheck

class PipelineCache(device: Device) {
    val device: Device
    var vkPipelineCache: Long = 0

    init {
        this.device = device
        MemoryStack.stackPush().use { stack ->
            val createInfo = VkPipelineCacheCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO)
            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreatePipelineCache(device.vkDevice, createInfo, null, lp),
                "Error creating pipeline cache"
            )
            vkPipelineCache = lp[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroyPipelineCache(device.vkDevice, vkPipelineCache, null)
    }
}

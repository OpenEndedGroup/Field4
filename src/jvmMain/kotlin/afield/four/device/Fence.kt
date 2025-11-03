package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkFenceCreateInfo
import afield.four.util.vkCheck

class Fence(private val device: afield.four.device.Device, signaled: Boolean) {
    @JvmField
    var vkFence: Long = 0

    init {
        MemoryStack.stackPush().use { stack ->
            val fenceCreateInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(if (signaled) VK10.VK_FENCE_CREATE_SIGNALED_BIT else 0)
            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateFence(device.vkDevice, fenceCreateInfo, null, lp),
                "Failed to create semaphore"
            )
            vkFence = lp[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroyFence(device.vkDevice, vkFence, null)
    }

    fun fenceWait() {
        VK10.vkWaitForFences(device.vkDevice, vkFence, true, Long.MAX_VALUE)
    }

    fun reset() {
        VK10.vkResetFences(device.vkDevice, vkFence)
    }
}

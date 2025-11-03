package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import afield.four.util.vkCheck

class Semaphore(private val device: Device) {
    var vkSemaphore: Long = 0

    init {
        MemoryStack.stackPush().use { stack ->
            val semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateSemaphore(device.vkDevice, semaphoreCreateInfo, null, lp),
                "Failed to create semaphore"
            )
            vkSemaphore = lp[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroySemaphore(device.vkDevice, vkSemaphore, null)
    }
}

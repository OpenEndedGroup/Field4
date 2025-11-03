package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import afield.four.util.vkCheck

class CommandPool(device: Device, queueFamilyIndex: Int) {
    val device: Device
    var vkCommandPool: Long = 0

    init {
        this.device = device
        MemoryStack.stackPush().use { stack ->
            val cmdPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamilyIndex)
            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateCommandPool(device.vkDevice, cmdPoolInfo, null, lp),
                "Failed to create command pool"
            )
            vkCommandPool = lp[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroyCommandPool(device.vkDevice, vkCommandPool, null)
    }
}

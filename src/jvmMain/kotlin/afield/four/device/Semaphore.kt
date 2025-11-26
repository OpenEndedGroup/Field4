package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo
import afield.four.util.vkCheck

/**
 * Semaphore wrapper that can be either binary or timeline.
 */
class Semaphore(private val device: Device, private val timeline: Boolean = true) {
    var vkSemaphore: Long = 0
    private var value: Long = 0

    init {
        MemoryStack.stackPush().use { stack ->
            val semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)

            if (timeline) {
                val typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO)
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(value)
                semaphoreCreateInfo.pNext(typeInfo.address())
            }

            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateSemaphore(device.vkDevice, semaphoreCreateInfo, null, lp),
                "Failed to create semaphore"
            )
            vkSemaphore = lp[0]
        }
    }

    /** Value to wait on (current signaled value). For binary this is always 0. */
    fun currentValue(): Long = if (timeline) value else 0

    /** Value to signal next; increments internal counter for timelines, returns 0 for binary. */
    fun nextSignalValue(): Long {
        if (timeline) {
            value += 1
            return value
        }
        return 0
    }

    /** Mark externally signaled (e.g., vkAcquireNextImageKHR). */
    fun markExternallySignaled() {
        if (timeline) value += 1
    }

    fun cleanup() {
        VK10.vkDestroySemaphore(device.vkDevice, vkSemaphore, null)
    }
}

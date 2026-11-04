package afield.graphics.vulkan.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkFenceCreateInfo
import afield.graphics.vulkan.util.vkCheck
import afield.graphics.vulkan.util.AutoDisposer

/**
 * A Vulkan fence the CPU waits on to learn that submitted GPU work has finished.
 *
 * [Fence] creates the fence, optionally already signalled, and registers it with `AutoDisposer`
 * under its [Device], so a fence that is never cleaned up is still destroyed. `fenceWait` blocks
 * without a timeout, `reset` re-arms it, and `isSignaled` polls it.
 */

class Fence(private val device: afield.graphics.vulkan.device.Device, signaled: Boolean) {
    /** The raw `VkFence` handle, for the LWJGL calls Field has no wrapper for. [cleanup] zeroes it. */
    @JvmField
    var vkFence: Long = 0
    private val autoDisposeToken: AutoDisposer.Token

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
        val dev = device.vkDevice
        val fence = vkFence
        autoDisposeToken = AutoDisposer.register(this, "Fence", owner = device) {
            VK10.vkDestroyFence(dev, fence, null)
        }
    }

    /**
     * Destroy the fence and clear [vkFence]. Safe to call twice: the second call finds the
     * `AutoDisposer` registration already taken and returns without a second `vkDestroyFence`.
     */
    fun cleanup() {
        if (!autoDisposeToken.disable()) return
        VK10.vkDestroyFence(device.vkDevice, vkFence, null)
        vkFence = 0L
    }

    /**
     * Block the calling thread until the fence signals, with no timeout. A fence that nothing
     * submits work against never signals, so this hangs rather than returning an error.
     */
    fun fenceWait() {
        VK10.vkWaitForFences(device.vkDevice, vkFence, true, Long.MAX_VALUE)
    }

    /** Return the fence to unsignalled, ready for the next submission to signal it. */
    fun reset() {
        VK10.vkResetFences(device.vkDevice, vkFence)
    }
    /** Whether the fence has signalled, polled without blocking. */
    fun isSignaled(): Boolean =
        VK10.vkGetFenceStatus(device.vkDevice, vkFence) == VK10.VK_SUCCESS

}

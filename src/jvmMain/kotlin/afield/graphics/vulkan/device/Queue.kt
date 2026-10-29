package afield.graphics.vulkan.device

/**
 * Vulkan device queues: [Queue] fetches a queue from a family and submits command buffers with wait and signal semaphores and an optional fence.
 *
 * The subclasses [Queue.GraphicsQueue], [Queue.ComputeQueue] and [Queue.PresentQueue] choose the queue family for graphics, compute and presentation to a surface.
 */

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSubmitInfo
import afield.graphics.vulkan.util.vkCheck
import java.nio.IntBuffer
import java.nio.LongBuffer

open class Queue(private val device: Device, queueFamilyIndex: Int, queueIndex: Int) {
    /**
     * The Vulkan queue family this queue was taken from. [SwapChain] compares it against the
     * presentation queue's: images are shared `CONCURRENT` when they differ and `EXCLUSIVE` when
     * every queue touching them is from one family.
     */
    val queueFamilyIndex: Int

    /** The raw `VkQueue` handle, for the LWJGL calls Field has no wrapper for. */
    var vkQueue: VkQueue

    init {
        this.queueFamilyIndex = queueFamilyIndex
        MemoryStack.stackPush().use { stack ->
            val pQueue = stack.mallocPointer(1)
            VK10.vkGetDeviceQueue(device.vkDevice, queueFamilyIndex, queueIndex, pQueue)
            val queue = pQueue[0]
            vkQueue = VkQueue(queue, device.vkDevice)
        }
    }

    /**
     * Submit command buffers, waiting on `waitSemaphores` at the stages `dstStageMasks` names and
     * signalling `signalSemaphores` when they complete. `fence` is optional and is what the CPU
     * waits on to learn the work has finished. Taken under `Queue.globalQueueLock`, and refused
     * outright once [Device.closeSubmissions] has run, so work is never queued against buffers a renderer
     * teardown is about to free.
     */
    fun submit(
        commandBuffers: PointerBuffer?, waitSemaphores: LongBuffer?, dstStageMasks: IntBuffer?,
        signalSemaphores: LongBuffer?, fence: afield.graphics.vulkan.device.Fence?
    ) {
        MemoryStack.stackPush().use { stack ->
            val submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(commandBuffers)
                .pSignalSemaphores(signalSemaphores)
            if (waitSemaphores != null) {
                submitInfo.waitSemaphoreCount(waitSemaphores.capacity())
                    .pWaitSemaphores(waitSemaphores)
                    .pWaitDstStageMask(dstStageMasks)
            } else {
                submitInfo.waitSemaphoreCount(0)
            }
            val fenceHandle = fence?.vkFence ?: VK10.VK_NULL_HANDLE
            synchronized(globalQueueLock) {
                // Under the same lock Renderer.cleanup closes submissions with, so a submit either
                // lands before its waitIdle (and completes) or is refused here.
                check(device.acceptingSubmissions) {
                    "Queue.submit after its Renderer began disposing: the command buffer may reference " +
                        "buffers that teardown is about to free. Stop the box submitting before the " +
                        "renderer (or its window, or its document) goes away"
                }
                vkCheck(
                    VK10.vkQueueSubmit(vkQueue, submitInfo, fenceHandle),
                    "Failed to submit command to queue"
                )
            }
        }
    }

    /**
     * Block until everything already submitted to this queue has finished. Held under
     * `Queue.globalQueueLock`, so a submit from another thread either lands before the wait or
     * blocks until it returns.
     */
    fun waitIdle() {
        synchronized(globalQueueLock) {
            VK10.vkQueueWaitIdle(vkQueue)
        }
    }

    companion object {
        // Vulkan queues require external synchronization; share a global lock for all Queue usages outside Renderer.
        val globalQueueLock = Any()
    }

    /**
     * A queue from the first family that offers graphics **and** compute. Field asks for both
     * together so one queue can run a draw and a dispatch, and construction fails rather than
     * falling back if no family offers both.
     */
    class GraphicsQueue(device: Device, queueIndex: Int) :
        Queue(device, getGraphicsQueueFamilyIndex(device), queueIndex) {
        companion object {
            private fun getGraphicsQueueFamilyIndex(device: Device): Int {
                var index = -1
                val physicalDevice = device.physicalDevice
                val queuePropsBuff = physicalDevice.vkQueueFamilyProps
                val numQueuesFamilies = queuePropsBuff.capacity()
                for (i in 0 until numQueuesFamilies) {
                    val props = queuePropsBuff[i]
                    val graphicsQueue = (props.queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT) != 0 && (props.queueFlags() and VK10.VK_QUEUE_COMPUTE_BIT) != 0
                    if (graphicsQueue) {
                        index = i
                        break
                    }
                }

                if (index < 0) {
                    throw RuntimeException("Failed to get graphics Queue family index")
                }
                return index
            }
        }
    }

    /**
     * A queue from the first family that offers compute, graphics or not. On a device whose first
     * compute family is also its graphics family this is the same family [Queue.GraphicsQueue] picks.
     */
    class ComputeQueue(device: Device, queueIndex: Int) :
        Queue(device, getGraphicsQueueFamilyIndex(device), queueIndex) {
        companion object {
            private fun getGraphicsQueueFamilyIndex(device: Device): Int {
                var index = -1
                val physicalDevice = device.physicalDevice
                val queuePropsBuff = physicalDevice.vkQueueFamilyProps
                val numQueuesFamilies = queuePropsBuff.capacity()
                for (i in 0 until numQueuesFamilies) {
                    val props = queuePropsBuff[i]
                    val graphicsQueue = (props.queueFlags() and VK10.VK_QUEUE_COMPUTE_BIT) != 0
                    if (graphicsQueue) {
                        index = i
                        break
                    }
                }

                if (index < 0) {
                    throw RuntimeException("Failed to get graphics Queue family index")
                }
                return index
            }
        }
    }

    /**
     * A queue from the first family that can present to `surface`. Presentation support is a
     * property of the family and the surface together, so this is asked per window rather than
     * read off the device.
     */
    class PresentQueue(device: Device, surface: Surface, queueIndex: Int) :
        Queue(device, getPresentQueueFamilyIndex(device, surface), queueIndex) {
        companion object {
            private fun getPresentQueueFamilyIndex(device: Device, surface: Surface): Int {
                var index = -1
                MemoryStack.stackPush().use { stack ->
                    val physicalDevice = device.physicalDevice
                    val queuePropsBuff = physicalDevice.vkQueueFamilyProps
                    val numQueuesFamilies = queuePropsBuff.capacity()
                    val intBuff = stack.mallocInt(1)
                    for (i in 0 until numQueuesFamilies) {
                        KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(
                            physicalDevice.vkPhysicalDevice,
                            i, surface.vkSurface, intBuff
                        )
                        val supportsPresentation = intBuff[0] == VK10.VK_TRUE
                        if (supportsPresentation) {
                            index = i
                            break
                        }
                    }
                }
                if (index < 0) {
                    throw RuntimeException("Failed to get Presentation Queue family index")
                }
                return index
            }
        }
    }
}

package afield.four.device

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSubmitInfo
import afield.four.util.vkCheck
import java.nio.IntBuffer
import java.nio.LongBuffer

open class Queue(private val device: Device, queueFamilyIndex: Int, queueIndex: Int) {
    val queueFamilyIndex: Int
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

    fun submit(
        commandBuffers: PointerBuffer?, waitSemaphores: LongBuffer?, dstStageMasks: IntBuffer?,
        signalSemaphores: LongBuffer?, fence: afield.four.device.Fence?
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

    fun waitIdle() {
        synchronized(globalQueueLock) {
            VK10.vkQueueWaitIdle(vkQueue)
        }
    }

    companion object {
        // Vulkan queues require external synchronization; share a global lock for all Queue usages outside Renderer.
        val globalQueueLock = Any()
    }

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

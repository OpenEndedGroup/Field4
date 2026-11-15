/**
 * A Vulkan timeline semaphore exported as an opaque POSIX fd, the synchronisation half of sharing
 * GPU memory with CUDA or native OIDN on Linux, and the queue submission that waits on and signals it.
 *
 * Vulkan and the importer count on one shared 64-bit timeline: Field's submission signals N once
 * its writes to a shared buffer are done, the importer's stream waits for N, works, and signals
 * N+1, and Field's next submission waits for N+1 before reading. Nothing blocks on the CPU in
 * between. [ExportedTimelineSemaphore.submit] is that Vulkan submission (`vkQueueSubmit2` with
 * semaphore values, under the same queue lock as every other submit), and
 * [ExportedTimelineSemaphore.releaseToExternal] / [ExportedTimelineSemaphore.acquireFromExternal]
 * record the queue-family ownership transfers the Vulkan spec asks for around a buffer the other API
 * touches.
 *
 * [ExportedTimelineSemaphore.deviceUuid] is the key for finding the same GPU on the other side
 * (`cuDeviceGetUuid`, `oidnNewDeviceByUUID`). Used by `afield.cuda`; design notes in
 * `dev_docs/graphics/CUDA_DRIVER_FFM.md`.
 */

package afield.graphics.vulkan.device

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*

/** A timeline semaphore whose fd can be imported by CUDA or OIDN. Linux with `VK_KHR_external_semaphore_fd`. */
class ExportedTimelineSemaphore(val renderer: Renderer, initialValue: Long = 0) : AutoCloseable {
    private val device = renderer.device
    /** The `VkSemaphore`. */
    val handle: Long
    private var closed = false

    init {
        check(device.supportsExternalSemaphoreFd) { "Exporting a semaphore needs VK_KHR_external_semaphore_fd (Linux); this device does not enable it" }
        handle = MemoryStack.stackPush().use { s ->
            val timeline = VkSemaphoreTypeCreateInfo.calloc(s).`sType$Default`().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(initialValue)
            val caps = VkExternalSemaphoreProperties.calloc(s).`sType$Default`()
            vkGetPhysicalDeviceExternalSemaphoreProperties(device.physicalDevice.vkPhysicalDevice,
                VkPhysicalDeviceExternalSemaphoreInfo.calloc(s).`sType$Default`().pNext(timeline.address())
                    .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), caps)
            check(caps.externalSemaphoreFeatures() and VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT != 0) { "This driver cannot export a timeline semaphore as an opaque fd" }
            val export = VkExportSemaphoreCreateInfo.calloc(s).`sType$Default`()
                .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT).pNext(timeline.address())
            val out = s.mallocLong(1)
            vkCheck(vkCreateSemaphore(device.vkDevice, VkSemaphoreCreateInfo.calloc(s).`sType$Default`().pNext(export.address()), null, out), "Create exportable timeline semaphore")
            out[0]
        }
    }

    /** A new fd for this semaphore, owned by the caller until an importer takes it (CUDA and OIDN do on success). */
    fun exportFd(): Int = MemoryStack.stackPush().use { s ->
        check(!closed)
        val out = s.mallocInt(1)
        vkCheck(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device.vkDevice, VkSemaphoreGetFdInfoKHR.calloc(s).`sType$Default`().semaphore(handle)
            .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), out), "vkGetSemaphoreFdKHR")
        out[0]
    }

    /** The counter as the device sees it now. */
    val value: Long get() = MemoryStack.stackPush().use { s ->
        val out = s.mallocLong(1); vkCheck(vkGetSemaphoreCounterValue(device.vkDevice, handle, out), "vkGetSemaphoreCounterValue"); out[0]
    }

    /** Block the calling thread until the counter reaches [target], or throw after [timeoutNs]. */
    fun hostWait(target: Long, timeoutNs: Long = 10_000_000_000L) = MemoryStack.stackPush().use { s ->
        val r = vkWaitSemaphores(device.vkDevice, VkSemaphoreWaitInfo.calloc(s).`sType$Default`().pSemaphores(s.longs(handle)).pValues(s.longs(target)), timeoutNs)
        check(r == VK_SUCCESS) { "Timeline semaphore did not reach $target within ${timeoutNs / 1e6} ms (result $r, value $value)" }
    }

    /**
     * Submit [command] (may be null for a pure wait/signal) to the renderer's graphics queue, first
     * waiting for this semaphore to reach [waitValue] (if not null), and signalling [signalValue]
     * (if not null) once it completes. [fence], when given, signals too.
     */
    fun submit(command: CommandBuffer?, waitValue: Long?, signalValue: Long?, fence: Fence? = null) = MemoryStack.stackPush().use { s ->
        val info = VkSubmitInfo2.calloc(1, s).`sType$Default`()
        if (command != null) info.pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, s).`sType$Default`().commandBuffer(command.vkCommandBuffer))
        if (waitValue != null) info.pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, s).`sType$Default`()
            .semaphore(handle).value(waitValue).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT))
        if (signalValue != null) info.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, s).`sType$Default`()
            .semaphore(handle).value(signalValue).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT))
        synchronized(Queue.globalQueueLock) {
            check(device.acceptingSubmissions) { "Submit after the renderer began disposing" }
            vkCheck(vkQueueSubmit2(renderer.graphQueue.vkQueue, info, fence?.vkFence ?: VK_NULL_HANDLE), "vkQueueSubmit2 (external timeline)")
        }
    }

    override fun close() {
        if (closed) return; closed = true
        vkDestroySemaphore(device.vkDevice, handle, null)
    }

    companion object {
        /** `VkPhysicalDeviceIDProperties.deviceUUID` of the renderer's GPU: the same 16 bytes CUDA's `cuDeviceGetUuid` reports. */
        fun deviceUuid(renderer: Renderer): ByteArray = MemoryStack.stackPush().use { s ->
            val id = VkPhysicalDeviceIDProperties.calloc(s).`sType$Default`()
            vkGetPhysicalDeviceProperties2(renderer.physicalDevice.vkPhysicalDevice, VkPhysicalDeviceProperties2.calloc(s).`sType$Default`().pNext(id.address()))
            ByteArray(VK_UUID_SIZE) { id.deviceUUID(it) }
        }

        private fun transfer(command: CommandBuffer, buffers: List<VulkanBuffer>, toExternal: Boolean) = MemoryStack.stackPush().use { s ->
            val family = buffers.first().renderer.graphQueue.queueFamilyIndex
            val b = VkBufferMemoryBarrier2.calloc(buffers.size, s)
            buffers.forEachIndexed { i, buf ->
                b[i].`sType$Default`().buffer(buf.buffer).offset(0).size(VK_WHOLE_SIZE)
                    .srcQueueFamilyIndex(if (toExternal) family else VK_QUEUE_FAMILY_EXTERNAL)
                    .dstQueueFamilyIndex(if (toExternal) VK_QUEUE_FAMILY_EXTERNAL else family)
                    .srcStageMask(if (toExternal) VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT else VK_PIPELINE_STAGE_2_NONE)
                    .srcAccessMask(if (toExternal) VK_ACCESS_2_MEMORY_WRITE_BIT else 0)
                    .dstStageMask(if (toExternal) VK_PIPELINE_STAGE_2_NONE else VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(if (toExternal) 0 else VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
            }
            vkCmdPipelineBarrier2(command.vkCommandBuffer, VkDependencyInfo.calloc(s).`sType$Default`().pBufferMemoryBarriers(b))
        }

        /** Record the release of [buffers] from the graphics queue family to `VK_QUEUE_FAMILY_EXTERNAL`, making prior writes available. */
        fun releaseToExternal(command: CommandBuffer, buffers: List<VulkanBuffer>) = transfer(command, buffers, true)

        /** Record the acquire of [buffers] back from `VK_QUEUE_FAMILY_EXTERNAL`, before Vulkan reads or writes them again. */
        fun acquireFromExternal(command: CommandBuffer, buffers: List<VulkanBuffer>) = transfer(command, buffers, false)
    }
}

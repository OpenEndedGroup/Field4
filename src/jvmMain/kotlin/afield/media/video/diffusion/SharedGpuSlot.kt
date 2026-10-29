package afield.media.video.diffusion

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.texture.UnixFdPass
import afield.graphics.vulkan.util.memoryTypeFromProperties
import afield.graphics.vulkan.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*

/**
 * One slot of the GPU memory a diffusion session shares with its worker: a device-local buffer and
 * a timeline semaphore, both exported to the worker as file descriptors.
 *
 * A [StreamDiffusionSession] allocates a ring of these for the frames it sends and another for the
 * results it reads back, passes their descriptors over a Unix socket, and afterwards exchanges
 * only slot indices and semaphore values with the worker. Pixels are written and read by the GPU at
 * both ends: a slot is a dedicated device-local allocation and is never mapped for the CPU. Its
 * barrier moves the buffer between Field's graphics queue and the external queue CUDA works on, and
 * the device UUID it reports names the GPU the worker is to import onto. Construction asks the
 * driver whether it can export this buffer and this kind of semaphore, and fails rather than
 * handing over a descriptor the worker cannot use.
 */

/** One dedicated device-local buffer and one exportable timeline semaphore. Never host mapped. */
internal class SharedGpuSlot(private val renderer: Renderer, val bytes: Long) : AutoCloseable {
    val device = renderer.device
    var buffer = 0L; private set
    private var memory = 0L
    var semaphore = 0L; private set
    var allocationSize = 0L; private set
    @Volatile var value = 0L
    var used = false
    private var closed = false

    init {
        check(device.supportsExternalMemoryFd && device.supportsExternalSemaphoreFd) {
            "StreamDiffusion requires Linux Vulkan external memory and semaphore FD support"
        }
        require(bytes > 0)
        try {
            MemoryStack.stackPush().use { s ->
                val usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT
                val capabilities = VkExternalBufferProperties.calloc(s).`sType$Default`()
                vkGetPhysicalDeviceExternalBufferProperties(device.physicalDevice.vkPhysicalDevice,
                    VkPhysicalDeviceExternalBufferInfo.calloc(s).`sType$Default`().usage(usage)
                        .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT), capabilities)
                check(capabilities.externalMemoryProperties().externalMemoryFeatures() and VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT != 0)
                val timeline = VkSemaphoreTypeCreateInfo.calloc(s).`sType$Default`()
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0)
                val semCaps = VkExternalSemaphoreProperties.calloc(s).`sType$Default`()
                vkGetPhysicalDeviceExternalSemaphoreProperties(device.physicalDevice.vkPhysicalDevice,
                    VkPhysicalDeviceExternalSemaphoreInfo.calloc(s).`sType$Default`()
                        .pNext(timeline.address()).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), semCaps)
                check(semCaps.externalSemaphoreFeatures() and VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT != 0) {
                    "Driver cannot export a timeline semaphore for CUDA"
                }
                val exportBuffer = VkExternalMemoryBufferCreateInfo.calloc(s).`sType$Default`()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
                val ci = VkBufferCreateInfo.calloc(s).`sType$Default`().pNext(exportBuffer.address())
                    .size(bytes).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                val out = s.mallocLong(1)
                vkCheck(vkCreateBuffer(device.vkDevice, ci, null, out), "Create shared GPU buffer")
                buffer = out[0]
                val req = VkMemoryRequirements.calloc(s)
                vkGetBufferMemoryRequirements(device.vkDevice, buffer, req)
                allocationSize = req.size()
                val dedicated = VkMemoryDedicatedAllocateInfo.calloc(s).`sType$Default`().buffer(buffer)
                val exportMemory = VkExportMemoryAllocateInfo.calloc(s).`sType$Default`()
                    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT).pNext(dedicated.address())
                val ai = VkMemoryAllocateInfo.calloc(s).`sType$Default`().pNext(exportMemory.address())
                    .allocationSize(allocationSize).memoryTypeIndex(memoryTypeFromProperties(
                        device.physicalDevice, req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
                vkCheck(vkAllocateMemory(device.vkDevice, ai, null, out), "Allocate shared GPU memory")
                memory = out[0]
                vkCheck(vkBindBufferMemory(device.vkDevice, buffer, memory, 0), "Bind shared GPU buffer")
                val exportSem = VkExportSemaphoreCreateInfo.calloc(s).`sType$Default`()
                    .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT).pNext(timeline.address())
                vkCheck(vkCreateSemaphore(device.vkDevice, VkSemaphoreCreateInfo.calloc(s).`sType$Default`()
                    .pNext(exportSem.address()), null, out), "Create shared GPU semaphore")
                semaphore = out[0]
            }
        } catch (e: Throwable) { close(); throw e }
    }

    /** Fresh descriptors; caller closes after SCM_RIGHTS duplication, including failure paths. */
    fun exportFds(): IntArray = MemoryStack.stackPush().use { s ->
        check(UnixFdPass.available)
        val out = s.mallocInt(1)
        vkCheck(KHRExternalMemoryFd.vkGetMemoryFdKHR(device.vkDevice,
            VkMemoryGetFdInfoKHR.calloc(s).`sType$Default`().memory(memory)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT), out), "Export GPU memory")
        val memoryFd = out[0]
        try {
            vkCheck(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device.vkDevice,
                VkSemaphoreGetFdInfoKHR.calloc(s).`sType$Default`().semaphore(semaphore)
                    .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT), out), "Export GPU semaphore")
            intArrayOf(memoryFd, out[0])
        } catch (e: Throwable) { UnixFdPass.closeFd(memoryFd); throw e }
    }

    /** Ownership transfer between the graphics queue and CUDA's external queue. */
    fun barrier(command: CommandBuffer, acquire: Boolean, access: Long) {
        MemoryStack.stackPush().use { s ->
            val b = VkBufferMemoryBarrier2.calloc(1, s).`sType$Default`()
                .buffer(buffer).offset(0).size(bytes)
                .srcQueueFamilyIndex(if (acquire) VK_QUEUE_FAMILY_EXTERNAL else renderer.graphQueue.queueFamilyIndex)
                .dstQueueFamilyIndex(if (acquire) renderer.graphQueue.queueFamilyIndex else VK_QUEUE_FAMILY_EXTERNAL)
                .srcStageMask(if (acquire) VK_PIPELINE_STAGE_2_NONE else VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstStageMask(if (acquire) VK_PIPELINE_STAGE_2_COPY_BIT else VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(if (acquire) 0 else access).dstAccessMask(if (acquire) access else 0)
            vkCmdPipelineBarrier2(command.vkCommandBuffer, VkDependencyInfo.calloc(s).`sType$Default`().pBufferMemoryBarriers(b))
        }
    }

    fun copyRegion(s: MemoryStack, width: Int, height: Int): VkBufferImageCopy.Buffer =
        VkBufferImageCopy.calloc(1, s).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            .imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
            .imageExtent { it.width(width).height(height).depth(1) }

    override fun close() {
        if (closed) return
        closed = true
        if (semaphore != 0L) vkDestroySemaphore(device.vkDevice, semaphore, null)
        if (buffer != 0L) vkDestroyBuffer(device.vkDevice, buffer, null)
        if (memory != 0L) vkFreeMemory(device.vkDevice, memory, null)
    }

    companion object {
        fun deviceUuid(renderer: Renderer): String = MemoryStack.stackPush().use { s ->
            val id = VkPhysicalDeviceIDProperties.calloc(s).`sType$Default`()
            vkGetPhysicalDeviceProperties2(renderer.physicalDevice.vkPhysicalDevice,
                VkPhysicalDeviceProperties2.calloc(s).`sType$Default`().pNext(id.address()))
            (0 until VK_UUID_SIZE).joinToString("") { "%02x".format(id.deviceUUID(it).toInt() and 255) }
        }
    }
}

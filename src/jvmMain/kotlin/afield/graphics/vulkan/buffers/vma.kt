package afield.graphics.vulkan.buffers

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.Device
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT
import org.lwjgl.util.vma.Vma.vmaCreateAllocator
import org.lwjgl.util.vma.Vma.vmaDestroyAllocator
import org.lwjgl.util.vma.Vma.vmaCalculateStatistics
import org.lwjgl.util.vma.VmaAllocatorCreateInfo
import org.lwjgl.util.vma.VmaTotalStatistics
import org.lwjgl.util.vma.VmaVulkanFunctions
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3

/**
 * The live VMA allocator handles, one per [Renderer] that has allocated a buffer.
 *
 * A renderer is absent until something asks [allocatorForRenderer] for it, and absent again once
 * the renderer has been cleaned up. The map is a plain unsynchronised one, so two threads first
 * asking for the same renderer's allocator at the same moment is not safe.
 */
val allocators = mutableMapOf<Renderer, Long>()

/**
 * The VMA allocator for [d], created on the first ask and shared by every [VulkanBuffer] allocated
 * on that renderer afterwards.
 *
 * The allocator is created against the renderer's own instance, physical device and device, for
 * Vulkan 1.3 and with buffer device addresses enabled — which is what lets a buffer hand a shader a
 * pointer rather than a descriptor. A failure to create one throws rather than returning zero.
 */
fun allocatorForRenderer(d : Renderer): Long {

    return allocators.computeIfAbsent(d) {
        MemoryStack.stackPush().use { stack ->
            val funcs = VmaVulkanFunctions.calloc(stack).set(d.instance.vkInstance, d.device.vkDevice)
            val pAllocator = stack.mallocPointer(1)

            val ci = VmaAllocatorCreateInfo.calloc(stack).apply {
                instance(d.instance.vkInstance)           // VkInstance
                physicalDevice(d.physicalDevice.vkPhysicalDevice)     // VkPhysicalDevice
                device(d.device.vkDevice)              // VkDevice
                flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                pVulkanFunctions(funcs)
                vulkanApiVersion(VK_API_VERSION_1_3)
            }

            val err = vmaCreateAllocator(ci, pAllocator)
            require(err == VK_SUCCESS) { "vmaCreateAllocator failed: $err" }

            pAllocator[0]               // <- this is your allocator handle (a pointer value)
        }


    }
}

/** All owned buffers must be released before destroying the allocator or its device. */
internal fun cleanupAllocatorForRenderer(renderer: Renderer) {
    val allocator = allocators[renderer] ?: return
    MemoryStack.stackPush().use { stack ->
        val statistics = VmaTotalStatistics.calloc(stack)
        vmaCalculateStatistics(allocator, statistics)
        val remaining = statistics.total().statistics().allocationCount()
        check(remaining == 0) { "Renderer shutdown left $remaining VMA allocations alive" }
    }
    vmaDestroyAllocator(allocator)
    allocators.remove(renderer)
}

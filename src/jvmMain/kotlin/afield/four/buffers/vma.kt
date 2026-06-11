package afield.four.buffers

import afield.four.Renderer
import afield.four.device.Device
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT
import org.lwjgl.util.vma.Vma.vmaCreateAllocator
import org.lwjgl.util.vma.VmaAllocatorCreateInfo
import org.lwjgl.util.vma.VmaVulkanFunctions
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3

val allocators = mutableMapOf<Renderer, Long>()

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
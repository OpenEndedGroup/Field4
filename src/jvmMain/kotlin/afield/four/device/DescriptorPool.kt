package afield.four.device

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.vkCreateDescriptorPool
import org.lwjgl.vulkan.VK10.vkFreeDescriptorSets
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import afield.four.util.vkCheck


class DescriptorPool(device: Device, descriptorTypeCounts: List<DescriptorTypeCount>) {
    val device: Device
    var vkDescriptorPool: Long = 0

    init {
        this.device = device
        MemoryStack.stackPush().use { stack ->
            var maxSets = 0
            val numTypes = descriptorTypeCounts.size
            val typeCounts = VkDescriptorPoolSize.calloc(numTypes, stack)
            for (i in 0 until numTypes) {
                maxSets += descriptorTypeCounts[i].count
                typeCounts[i]
                    .type(descriptorTypeCounts[i].descriptorType)
                    .descriptorCount(descriptorTypeCounts[i].count)
            }

            val descriptorPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                .pPoolSizes(typeCounts)
                .maxSets(maxSets)

            val pDescriptorPool = stack.mallocLong(1)
            vkCheck(
                vkCreateDescriptorPool(device.vkDevice, descriptorPoolInfo, null, pDescriptorPool),
                "Failed to create descriptor pool"
            )
            vkDescriptorPool = pDescriptorPool[0]
        }
    }

    fun cleanup() {
        VK10.vkDestroyDescriptorPool(device.vkDevice, vkDescriptorPool, null)
    }

    fun freeDescriptorSet(vkDescriptorSet: Long) {
        MemoryStack.stackPush().use { stack ->
            val longBuffer = stack.mallocLong(1)
            longBuffer.put(0, vkDescriptorSet)
            vkCheck(
                vkFreeDescriptorSets(device.vkDevice, vkDescriptorPool, longBuffer),
                "Failed to free descriptor set"
            )
        }
    }

    @JvmRecord
    data class DescriptorTypeCount(val count: Int, val descriptorType: Int)
}
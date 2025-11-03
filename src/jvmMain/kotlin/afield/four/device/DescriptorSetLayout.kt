package afield.four.device

import afield.four.device.DescriptorSet.SimpleDescriptorSet
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo

abstract class DescriptorSetLayout protected constructor(private val device: Device) {
    var vkDescriptorLayout: Long = 0
        protected set

    fun cleanup() {
//        Logger.debug("Destroying descriptor set layout")
        VK10.vkDestroyDescriptorSetLayout(device.vkDevice, vkDescriptorLayout, null)
    }

    open class SimpleDescriptorSetLayout(
        val device: Device, val descriptorType: List<Int>, val set: Int, val binding: List<Int>, val stage: Int, val descriptorCount: List<Int> = List(binding.size) { 1 }
    ) : DescriptorSetLayout(device) {
        init {
            MemoryStack.stackPush().use { stack ->
                val layoutBindings = VkDescriptorSetLayoutBinding.calloc(binding.size, stack)
                binding.forEachIndexed { index, it ->
                    layoutBindings[index].binding(it).descriptorType(descriptorType[index]).descriptorCount(descriptorCount[index])
                        .stageFlags(stage)

                }

                val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(layoutBindings)


                val pSetLayout = stack.mallocLong(1)
                vkCheck(
                    vkCreateDescriptorSetLayout(device.vkDevice, layoutInfo, null, pSetLayout),
                    "Failed to create descriptor set layout"
                )
                super.vkDescriptorLayout = pSetLayout[0]
            }
        }

        override fun toString(): String {
            return "SimpleDescriptorSetLayout(descriptorType=$descriptorType, set=$set, binding=$binding, stage=$stage)"
        }

        fun makeDescriptorSet(pool: DescriptorPool) = SimpleDescriptorSet(device, pool, this)

    }

}

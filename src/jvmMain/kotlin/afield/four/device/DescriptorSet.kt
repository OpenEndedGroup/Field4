package afield.four.device

import afield.four.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.modules.Introspected
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*


abstract class DescriptorSet {
    var vkDescriptorSet: Long = 0
        protected set

    interface UpdatesDescriptorSet {
        val binding : Introspected.Bindable
        val target : SimpleDescriptorSet
        val source : BufferParameters?
        fun updateDescriptorSet()
    }

    open class SimpleDescriptorSet(
        val device: Device,
        val descriptorPool: DescriptorPool,
        val descriptorSetLayout: DescriptorSetLayout.SimpleDescriptorSetLayout,
    ) : DescriptorSet() {
        init {
            MemoryStack.stackPush().use { stack ->
                val device: Device = descriptorPool.device
                val pDescriptorSetLayout = stack.mallocLong(1)
                pDescriptorSetLayout.put(0, descriptorSetLayout.vkDescriptorLayout)
                val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool.vkDescriptorPool)
                    .pSetLayouts(pDescriptorSetLayout)

                val pDescriptorSet = stack.mallocLong(1)
                vkCheck(
                    vkAllocateDescriptorSets(device.vkDevice, allocInfo, pDescriptorSet),
                    "Failed to create descriptor set"
                )

                vkDescriptorSet = pDescriptorSet[0]


            }
        }

        override fun toString(): String {
            return "SimpleDescriptorSet(descriptorPool=$descriptorPool, descriptorSetLayout=$descriptorSetLayout)"
        }

        fun updateBuffer(binding: Int, buffer: VulkanBuffer, type: Int) {
            MemoryStack.stackPush().use { stack ->
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.buffer)
                    .offset(0)
                    .range(buffer.requestedSize)

                val descrBuffer = VkWriteDescriptorSet.calloc(1, stack)

                descrBuffer[0]
                    .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding) // SPECULATIVE
                    .descriptorType(type)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo)
                VK10.vkUpdateDescriptorSets(device.vkDevice, descrBuffer, null)
            }
        }

        fun updateTexture(binding: Int, texture: ImageView, textureSampler: TextureSampler) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.vkImageView)
                    .sampler(textureSampler.vkSampler)

                val descrBuffer = VkWriteDescriptorSet.calloc(1, stack)
                descrBuffer[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)
                vkUpdateDescriptorSets(device.vkDevice, descrBuffer, null)
            }
        }

        fun updateTextures(binding: Int, textures: Array<ImageView>, textureSampler: TextureSampler) {
            MemoryStack.stackPush().use { stack ->
                textures.forEachIndexed { index, iv ->
                    val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(iv.vkImageView)
                        .sampler(textureSampler.vkSampler)

                    val descrBuffer = VkWriteDescriptorSet.calloc(1, stack)
                    descrBuffer[0]
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(vkDescriptorSet)
                        .dstBinding(binding)
                        .dstArrayElement(index)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imageInfo)
                    vkUpdateDescriptorSets(device.vkDevice, descrBuffer, null)
                }
            }
        }

        fun updateImage(binding: Int, texture: ImageView) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(texture.vkImageView)

                val descrBuffer = VkWriteDescriptorSet.calloc(1, stack)
                descrBuffer[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)
                vkUpdateDescriptorSets(device.vkDevice, descrBuffer, null)
            }
        }
    }


}

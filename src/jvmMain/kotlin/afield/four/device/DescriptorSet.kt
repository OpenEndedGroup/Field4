package afield.four.device

import afield.four.buffers.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.modules.Introspected
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.* // variable descriptor count allocate info
import org.lwjgl.vulkan.*

abstract class DescriptorSet {
    var vkDescriptorSet: Long = 0
        protected set

    interface UpdatesDescriptorSet {
        val binding: Introspected.Bindable
        val target: SimpleDescriptorSet
        val source: BufferParameters?
        fun updateDescriptorSet()
    }

    open class SimpleDescriptorSet(
        val device: Device,
        val descriptorPool: DescriptorPool,
        val descriptorSetLayout: DescriptorSetLayout.SimpleDescriptorSetLayout,
        private val variableDescriptorCount: Int? = null
    ) : DescriptorSet() {

        init {
            MemoryStack.stackPush().use { stack ->
                val dev = descriptorPool.device

                val pDescriptorSetLayout = stack.longs(descriptorSetLayout.vkDescriptorLayout)

                val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool.vkDescriptorPool)
                    .pSetLayouts(pDescriptorSetLayout)

                // If the layout declared a variable descriptor binding, plumb the actual count.
                if (descriptorSetLayout.variableBindingIndex >= 0 && variableDescriptorCount != null) {
                    val varInfo = VkDescriptorSetVariableDescriptorCountAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_VARIABLE_DESCRIPTOR_COUNT_ALLOCATE_INFO)
                        .pDescriptorCounts(stack.ints(variableDescriptorCount))
                    allocInfo.pNext(varInfo.address())
                }

                val pDescriptorSet = stack.mallocLong(1)
                vkCheck(
                    vkAllocateDescriptorSets(dev.vkDevice, allocInfo, pDescriptorSet),
                    "Failed to create descriptor set"
                )
                vkDescriptorSet = pDescriptorSet[0]
            }
        }

        override fun toString(): String {
            return "SimpleDescriptorSet(pool=$descriptorPool, layout=$descriptorSetLayout)"
        }

        // --------------------- Buffers ---------------------

        fun updateBuffer(binding: Int, buffer: VulkanBuffer, type: Int) {
            MemoryStack.stackPush().use { stack ->
                val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.buffer)
                    .offset(0)
                    .range(buffer.requestedSize)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(type)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        // --------------------- Images + Samplers ---------------------
        // We support both "combined image sampler" (legacy) and the separated
        // SAMPLED_IMAGE + SAMPLER model (preferred for portability/limits).

        /** (Legacy) Write a single combined image+sampler descriptor. */
        fun updateTexture(binding: Int, texture: ImageView, textureSampler: TextureSampler) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.vkImageView)
                    .sampler(textureSampler.vkSampler)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** (Legacy) Write an array of combined image+sampler descriptors in one call. */
        fun updateTextures(binding: Int, textures: Array<ImageView>, textureSampler: TextureSampler) {
            MemoryStack.stackPush().use { stack ->
                val infos = VkDescriptorImageInfo.calloc(textures.size, stack)
                for (i in textures.indices) {
                    infos[i]
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(textures[i].vkImageView)
                        .sampler(textureSampler.vkSampler)
                }

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(textures.size)
                    .pImageInfo(infos)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** Write a single SAMPLED_IMAGE descriptor (no sampler). */
        fun updateSampledImage(binding: Int, texture: ImageView) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(texture.vkImageView)
                    .sampler(0L) // ignored for SAMPLED_IMAGE

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** Write an array of SAMPLED_IMAGE descriptors (no samplers). */
        fun updateSampledImages(binding: Int, textures: Array<ImageView>) {
            MemoryStack.stackPush().use { stack ->
                val infos = VkDescriptorImageInfo.calloc(textures.size, stack)
                for (i in textures.indices) {
                    infos[i]
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(textures[i].vkImageView)
                        .sampler(0L) // ignored for SAMPLED_IMAGE
                }

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                    .descriptorCount(textures.size)
                    .pImageInfo(infos)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** Write a single SAMPLER descriptor. */
        fun updateSampler(binding: Int, sampler: TextureSampler) {
            MemoryStack.stackPush().use { stack ->
                val info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_UNDEFINED) // ignored
                    .imageView(0L)                           // ignored
                    .sampler(sampler.vkSampler)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(info)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** Write an array of SAMPLER descriptors. */
        fun updateSamplers(binding: Int, samplers: Array<TextureSampler>) {
            MemoryStack.stackPush().use { stack ->
                val infos = VkDescriptorImageInfo.calloc(samplers.size, stack)
                for (i in samplers.indices) {
                    infos[i]
                        .imageLayout(VK_IMAGE_LAYOUT_UNDEFINED) // ignored
                        .imageView(0L)                           // ignored
                        .sampler(samplers[i].vkSampler)
                }

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER)
                    .descriptorCount(samplers.size)
                    .pImageInfo(infos)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }

        /** STORAGE_IMAGE (unchanged). */
        fun updateImage(binding: Int, texture: ImageView) {
            MemoryStack.stackPush().use { stack ->
                val imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(texture.vkImageView)

                val write = VkWriteDescriptorSet.calloc(1, stack)
                write[0]
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(vkDescriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo)

                vkUpdateDescriptorSets(device.vkDevice, write, null)
            }
        }
    }
}

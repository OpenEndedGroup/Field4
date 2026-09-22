package afield.four.device

import afield.four.buffers.BufferParameters
import afield.four.modules.Introspected
import afield.four.util.vkCheck
import afield.four.util.AutoDisposer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRPushDescriptor
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK12.* // descriptor indexing enums/structs promoted to core in 1.2
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo

interface UpdatesDescriptorSet {
    val binding: Introspected.Bindable
    val source: BufferParameters?
    fun pushDescriptorSet(
        command: CommandBuffer,
        pipelineLayout: Long,
        bindPoint: Int = VK_PIPELINE_BIND_POINT_GRAPHICS
    )
}


abstract class DescriptorSetLayout protected constructor(private val device: Device) {
    var vkDescriptorLayout: Long = 0
        protected set
    private val autoDisposeToken = AutoDisposer.register(this, "DescriptorSetLayout", owner = device) { }

    protected fun registerCleanup() {
        val dev = device.vkDevice
        val layout = vkDescriptorLayout
        autoDisposeToken.updateCleanup { vkDestroyDescriptorSetLayout(dev, layout, null) }
    }

    fun cleanup() {
        if (!autoDisposeToken.disable()) return
        vkDestroyDescriptorSetLayout(device.vkDevice, vkDescriptorLayout, null)
        vkDescriptorLayout = 0L
    }

    /**
     * Simple layout with optional descriptor-indexing bells & whistles.
     *
     * - [descriptorCount] is the **max** count per binding (for arrays). Defaults to 1s.
     * - If you want a runtime-sized/bindless binding, set [variableBindingIndex] to the index in
     *   the *lists above* (not the binding number). That binding must be the numerically largest
     *   Vulkan binding number in this set (spec rule).
     * - [enablePartiallyBound] adds PARTIALLY_BOUND on array bindings so you don't have to fill every slot.
     * - [enableUpdateAfterBind] turns on UPDATE_AFTER_BIND bits and will also set the layout create flag.
     *   (Remember to also create your pool with VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT.)
     * - [immutableSamplers] optionally bakes one or more immutable samplers into a binding. The map keys are
     *   **Vulkan binding numbers** (not indices into the lists). Only valid for SAMPLER or COMBINED_IMAGE_SAMPLER
     *   descriptor types. The array length must equal the descriptorCount for that binding.
     */
    open class SimpleDescriptorSetLayout(
        val device: Device,
        val descriptorType: List<Int>,
        val set: Int,
        val binding: List<Int>,
        val stage: Int,
        val descriptorCount: List<Int> = List(binding.size) { 1 },
        val enablePartiallyBound: Boolean = true,
        val variableBindingIndex: Int = -1,
        val enableUpdateAfterBind: Boolean = false,
        val immutableSamplers: Map<Int, LongArray>? = null,
        val pushDescriptor: Boolean = false
    ) : DescriptorSetLayout(device) {

        init {
            require(descriptorType.size == binding.size && descriptorCount.size == binding.size) {
                "descriptorType, binding, descriptorCount must have same size. ${descriptorType.size} != ${binding.size} != ${descriptorCount.size}"
            }
            if (variableBindingIndex >= 0) {
                require(variableBindingIndex in binding.indices) {
                    "variableBindingIndex ($variableBindingIndex) is out of range 0..${binding.lastIndex}"
                }
                // Spec: the variable-count binding must be the numerically highest binding number
                val maxBindingNumber = binding.maxOrNull()!!
                require(binding[variableBindingIndex] == maxBindingNumber) {
                    "Variable descriptor binding must be the numerically largest binding in the set " +
                            "(have=${binding[variableBindingIndex]}, max=$maxBindingNumber)"
                }
            }

            // Validate immutable sampler usage up-front (cheap checks).
            immutableSamplers?.forEach { (vkBinding, samplers) ->
                val idx = binding.indexOf(vkBinding)
                require(idx >= 0) {
                    "immutableSamplers provided for binding=$vkBinding but that binding isn't in this layout"
                }
                val type = descriptorType[idx]
                require(type == VK_DESCRIPTOR_TYPE_SAMPLER || type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
                    "immutableSamplers only valid for SAMPLER or COMBINED_IMAGE_SAMPLER (binding=$vkBinding)"
                }
                val declaredCount = descriptorCount[idx]
                require(samplers.size == declaredCount) {
                    "immutableSamplers count (${samplers.size}) must match descriptorCount ($declaredCount) for binding=$vkBinding"
                }
            }

            if (pushDescriptor) {
                require(device.supportsPushDescriptors) {
                    "VK_KHR_push_descriptor not supported on this device"
                }
                val totalDescriptors = descriptorCount.sum()
                require(totalDescriptors <= device.pushDescriptorMax) {
                    "Requested $totalDescriptors push descriptors, but device maxPushDescriptors=${device.pushDescriptorMax}"
                }
            }

            MemoryStack.stackPush().use { stack ->
                // Per-binding core info
                val layoutBindings = VkDescriptorSetLayoutBinding.calloc(binding.size, stack)
                for (i in binding.indices) {
                    layoutBindings[i]
                        .binding(binding[i])
                        .descriptorType(descriptorType[i])
                        .descriptorCount(descriptorCount[i])
                        .stageFlags(stage)

                    // Optional immutable samplers for this binding (if any)
                    immutableSamplers?.get(binding[i])?.let { samplers ->
                        val buf = stack.mallocLong(samplers.size)
                        for (j in samplers.indices) buf.put(j, samplers[j])
                        layoutBindings[i].pImmutableSamplers(buf)
                    }
                }

                // Optional: per-binding flags (descriptor indexing)
                val hasIndexingFlags =
                    enablePartiallyBound || variableBindingIndex >= 0 || enableUpdateAfterBind
                val flagsBuf = if (hasIndexingFlags) {
                    val perBindingFlags = IntArray(binding.size) { 0 }
                    for (i in binding.indices) {
                        // Partially-bound helps for arrays (including bindless) so you can leave holes.
                        if (enablePartiallyBound && descriptorCount[i] > 1) {
                            perBindingFlags[i] = perBindingFlags[i] or VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
                        }
                        // Variable descriptor count (bindless-style).
                        if (i == variableBindingIndex) {
                            perBindingFlags[i] = perBindingFlags[i] or VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                        }
                        // Update-after-bind if requested globally for this layout.
                        if (enableUpdateAfterBind) {
                            perBindingFlags[i] = perBindingFlags[i] or VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                        }
                    }
                    VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack).apply {
                        sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO)
                        pBindingFlags(stack.ints(*perBindingFlags))
                    }
                } else null

                val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(layoutBindings)

                if (enableUpdateAfterBind) {
                    layoutInfo.flags(layoutInfo.flags() or VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                }
                if (pushDescriptor) {
                    layoutInfo.flags(layoutInfo.flags() or KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                }
                if (flagsBuf != null) {
                    layoutInfo.pNext(flagsBuf.address())
                }

                val pSetLayout = stack.mallocLong(1)
                vkCheck(
                    vkCreateDescriptorSetLayout(device.vkDevice, layoutInfo, null, pSetLayout),
                    "Failed to create descriptor set layout"
                )
                super.vkDescriptorLayout = pSetLayout[0]
                registerCleanup()
            }
        }

        override fun toString(): String {
            return "SimpleDescriptorSetLayout(set=$set, binding=$binding, types=$descriptorType, " +
                    "count=$descriptorCount, varIdx=$variableBindingIndex, stage=$stage, " +
                    "updateAfterBind=$enableUpdateAfterBind, partiallyBound=$enablePartiallyBound, " +
                    "pushDescriptor=$pushDescriptor)"
        }
    }
}

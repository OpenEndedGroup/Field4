package afield.four.device

import afield.four.device.DescriptorPool.DescriptorTypeCount
import afield.four.modules.parameters
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import org.lwjgl.system.Struct
import org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
import org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
import org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
import org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER

class Device(val physicalDevice: PhysicalDevice) {
    var pool: DescriptorPool

    @JvmField
    var vkDevice: VkDevice

    var usePresentIndexing = false
    var enableMeshShading = false

    init {

        MemoryStack.stackPush().use { stack ->

            // Define required extensions
            val deviceExtensions = deviceExtensions
            val usePortability =
                deviceExtensions.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME) && OS == OSType.MACOS

            val useExternalMemory = OS == OSType.WINDOWS
            usePresentIndexing = OS == OSType.WINDOWS
            enableMeshShading = OS == OSType.WINDOWS
            var numExtensions = if (usePortability) 7 else 6
            if (useExternalMemory) numExtensions+=2
            if (usePresentIndexing) numExtensions+=2
            if (enableMeshShading) numExtensions+=1

            val requiredExtensions = stack.mallocPointer(numExtensions)
            requiredExtensions.put(stack.ASCII(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME))
            requiredExtensions.put(stack.ASCII(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME))
            requiredExtensions.put(stack.ASCII(KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME))
            requiredExtensions.put(stack.ASCII(KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME))
            requiredExtensions.put(stack.ASCII(KHRSeparateDepthStencilLayouts.VK_KHR_SEPARATE_DEPTH_STENCIL_LAYOUTS_EXTENSION_NAME))
            requiredExtensions.put(stack.ASCII(EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME))

            requiredExtensions.put(stack.ASCII(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME))

//            requiredExtensions.put(stack.ASCII(EXTMemoryPriority.VK_EXT_MEMORY_PRIORITY_EXTENSION_NAME))


            if (useExternalMemory) {
                requiredExtensions.put(stack.ASCII(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME))
                requiredExtensions.put(stack.ASCII(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME))
            }

            if (usePresentIndexing) {
                requiredExtensions.put(stack.ASCII(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME))
                requiredExtensions.put(stack.ASCII(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME))

            }

            // not on MVK yet?
//            requiredExtensions.put(stack.ASCII(EXTDynamicRenderingUnusedAttachments.VK_EXT_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_EXTENSION_NAME))

            if (usePortability) {
                requiredExtensions.put(stack.ASCII(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME))
            }
            requiredExtensions.flip()

//            val features0 = VkPhysicalDeviceFeatures.calloc(stack)

            // Set up required features
            val features = VkPhysicalDeviceFeatures2.calloc(stack)
            features.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            features.features().fragmentStoresAndAtomics(true)
            features.features().vertexPipelineStoresAndAtomics(true)
            features.features().robustBufferAccess(Options.robustness)


            val features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
            features11.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)

            val features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
            features12.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
            features12.bufferDeviceAddress(true)

            val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
            features13.sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
            features13.dynamicRendering(true)

            val f0 = VkPhysicalDeviceDynamicRenderingUnusedAttachmentsFeaturesEXT.calloc()
            f0.sType(EXTDynamicRenderingUnusedAttachments.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_FEATURES_EXT)
            f0.dynamicRenderingUnusedAttachments(true) // not on mac

            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, features)
//            f.dynamicRendering(true)

            val f2 = VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc()
            f2.sType(EXTExtendedDynamicState.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT)
            f2.extendedDynamicState(true)


            // Enable all the queue families
            val queuePropsBuff = physicalDevice.vkQueueFamilyProps
            val numQueuesFamilies = queuePropsBuff.capacity()
            val queueCreationInfoBuf = VkDeviceQueueCreateInfo.calloc(numQueuesFamilies, stack)
            for (i in 0 until numQueuesFamilies) {
                val priorities = stack.callocFloat(queuePropsBuff[i].queueCount())
                queueCreationInfoBuf[i].sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(i)
                    .pQueuePriorities(priorities)
            }

            val deviceCreateInfo = VkDeviceCreateInfo.calloc(stack).sType(VK12.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .ppEnabledExtensionNames(requiredExtensions)
                .pQueueCreateInfos(queueCreationInfoBuf)
                .pNext(features)



            features.pNext(f2.address())
            f2.pNext(f0.address())
            f0.pNext(features12.address())
            features12.pNext(features13.address())

            // type system doesn't know about pNext
            if (enableMeshShading && usePresentIndexing) {
                val meshShaderFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT).meshShader(true)
                    .multiviewMeshShader(true).taskShader(true)

                val mv = VkPhysicalDeviceMultiviewFeaturesKHR.calloc(stack).sType(KHRMultiview.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTIVIEW_FEATURES_KHR).multiview(true)

                features13.pNext(meshShaderFeatures.address())
                meshShaderFeatures.pNext(mv.address())

                val presentIdFeat = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR)

                val presentWaitFeat = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack)
                    .sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR)

                mv.pNext(presentIdFeat.address())
                presentIdFeat.pNext(presentWaitFeat.address())

                presentIdFeat.presentId(true)
                presentWaitFeat.presentWait(true)
            }

            val pp = stack.mallocPointer(1)
            vkCheck(
                VK10.vkCreateDevice(physicalDevice.vkPhysicalDevice, deviceCreateInfo, null, pp),
                "Failed to create device"
            )
            vkDevice = VkDevice(pp[0], physicalDevice.vkPhysicalDevice, deviceCreateInfo)

            // TODO — this is daft
            // we've all but removed pressure on ubo (using pushconstants) and ssbo (using bindless instead)
            // aim towards the single array of textures trick I suppose
            pool = DescriptorPool(
                this, listOf(
                    DescriptorTypeCount(100, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                    DescriptorTypeCount(100, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                    DescriptorTypeCount(100, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                    DescriptorTypeCount(100, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                )
            )

        }
    }

    fun cleanup() {
        VK10.vkDestroyDevice(vkDevice, null)
    }

    private val deviceExtensions: Set<String>
        get() {
            val deviceExtensions: MutableSet<String> = HashSet()
            MemoryStack.stackPush().use { stack ->
                val numExtensionsBuf = stack.callocInt(1)
                VK10.vkEnumerateDeviceExtensionProperties(
                    physicalDevice.vkPhysicalDevice, null as String?, numExtensionsBuf, null
                )
                val numExtensions = numExtensionsBuf[0]
                val propsBuff = VkExtensionProperties.calloc(numExtensions, stack)
                VK10.vkEnumerateDeviceExtensionProperties(
                    physicalDevice.vkPhysicalDevice, null as String?, numExtensionsBuf, propsBuff
                )
                for (i in 0 until numExtensions) {
                    val props = propsBuff[i]
                    val extensionName = props.extensionNameString()
                    deviceExtensions.add(extensionName)
                }
            }
            return deviceExtensions
        }

    fun waitIdle() {
        VK10.vkDeviceWaitIdle(vkDevice)
    }
}

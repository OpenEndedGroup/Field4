package afield.four.device

import afield.four.device.DescriptorPool.DescriptorTypeCount
import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.EXTCalibratedTimestamps
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR
import org.lwjgl.vulkan.KHRCalibratedTimestamps


class Device(val physicalDevice: PhysicalDevice) {
    var pool: DescriptorPool

    @JvmField
    var vkDevice: VkDevice

    var usePresentIndexing = false
    var enableMeshShading = false

    init {

        MemoryStack.stackPush().use { stack ->

            val deviceExts = deviceExtensions
            val usePortability =
                deviceExts.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME) && OS == OSType.MACOS
            val useExternalMemory = OS == OSType.WINDOWS
            usePresentIndexing = OS == OSType.WINDOWS
            enableMeshShading = OS == OSType.WINDOWS

// --- Profiling master switch (change to a ctor param if you prefer)
            val profilingEnabled = afield.four.util.Options.profiling

// These two must exist on the selected device for profiling extras
            val hasPerfQuery = deviceExts.contains(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
            val hasHostQueryReset = deviceExts.contains(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME)
            val hasCalibrated = deviceExts.contains(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)

// --- Build the device extension name list dynamically
            val extNames = ArrayList<String>(12)
            extNames += KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
            extNames += KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME
            extNames += KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME
            extNames += KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME
            extNames += KHRSeparateDepthStencilLayouts.VK_KHR_SEPARATE_DEPTH_STENCIL_LAYOUTS_EXTENSION_NAME
            extNames += EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME

            if (enableMeshShading) {
                extNames += EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME
            }

            if (useExternalMemory) {
                extNames += KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME
                extNames += KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
            }

            if (usePresentIndexing) {
                extNames += KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME
                extNames += KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME
            }

            if (usePortability) {
                extNames += KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME
            }

// --- Profiling-related extensions (only when requested AND supported)
            if (profilingEnabled && hasPerfQuery) {
                extNames += KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME
            }
            if (profilingEnabled && hasHostQueryReset) {
                extNames += EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME
            }
            if (profilingEnabled && hasCalibrated) {
                extNames += EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME
            }

// Convert to LWJGL pointer buffer
            val requiredExtensions = stack.mallocPointer(extNames.size)
            for (name in extNames) requiredExtensions.put(stack.ASCII(name))
            requiredExtensions.flip()

//            val features0 = VkPhysicalDeviceFeatures.calloc(stack)

            // Set up required features
            // --- Core features via Features2 ---
            val features = VkPhysicalDeviceFeatures2.calloc(stack)
            features.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            features.features().fragmentStoresAndAtomics(true)
            features.features().vertexPipelineStoresAndAtomics(true)
            features.features().robustBufferAccess(Options.robustness)

// Enable pipeline statistics if profiling is on
            features.features().pipelineStatisticsQuery(profilingEnabled)

// Vulkan 1.1/1.2/1.3 feature structs
            val features11 = VkPhysicalDeviceVulkan11Features.calloc(stack)
            features11.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)

            val features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
            features12.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
            features12.bufferDeviceAddress(true)

            val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
            features13.sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
            features13.dynamicRendering(true)

// Optional (you had these)
            val f0 = VkPhysicalDeviceDynamicRenderingUnusedAttachmentsFeaturesEXT.calloc(stack)
            f0.sType(EXTDynamicRenderingUnusedAttachments.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_FEATURES_EXT)
            f0.dynamicRenderingUnusedAttachments(true) // may be ignored on mac

            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, features)

            val f2 = VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc(stack)
            f2.sType(EXTExtendedDynamicState.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT)
            f2.extendedDynamicState(true)

// --- Profiling feature structs (conditionally set) ---
            val perfFeat = VkPhysicalDevicePerformanceQueryFeaturesKHR.calloc(stack)
                .sType(KHRPerformanceQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PERFORMANCE_QUERY_FEATURES_KHR)
            if (profilingEnabled && hasPerfQuery) {
                perfFeat.performanceCounterQueryPools(true)
                perfFeat.performanceCounterMultipleQueryPools(true) // optional convenience
            }

            val hostResetFeat = VkPhysicalDeviceHostQueryResetFeatures.calloc(stack)
                .sType(EXTHostQueryReset.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES_EXT)
            if (profilingEnabled && hasHostQueryReset) {
                hostResetFeat.hostQueryReset(true)
            }

// --- Build a safe pNext chain ---
// Base chain
            features.pNext(f2.address())
            f2.pNext(f0.address())
            f0.pNext(features12.address())
            features12.pNext(features13.address())

// Optional mesh/present features (as you had), but keep a handle to the tail:
            var tailAddr = features13.address()

            if (enableMeshShading && usePresentIndexing) {
                val meshShaderFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                    .meshShader(true).multiviewMeshShader(true).taskShader(true)

                val mv = VkPhysicalDeviceMultiviewFeaturesKHR.calloc(stack)
                    .sType(KHRMultiview.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTIVIEW_FEATURES_KHR)
                    .multiview(true)

                val presentIdFeat = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR)
                    .presentId(true)

                val presentWaitFeat = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack)
                    .sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR)
                    .presentWait(true)

                // Chain them
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, meshShaderFeatures.address()); tailAddr =
                    meshShaderFeatures.address()
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, mv.address()); tailAddr = mv.address()
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, presentIdFeat.address()); tailAddr =
                    presentIdFeat.address()
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, presentWaitFeat.address()); tailAddr =
                    presentWaitFeat.address()
            }

// Append profiling features (if any) AFTER mesh/present tail (or features13 if that branch didn’t run)
            if (profilingEnabled && hasPerfQuery) {
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, perfFeat.address())
                tailAddr = perfFeat.address()
            }
            if (profilingEnabled && hasHostQueryReset) {
                memPutAddress(tailAddr + VkBaseOutStructure.PNEXT, hostResetFeat.address())
                tailAddr = hostResetFeat.address()
            }

// --- Queues (unchanged)
            val queuePropsBuff = physicalDevice.vkQueueFamilyProps
            val numQueuesFamilies = queuePropsBuff.capacity()
            val queueCreationInfoBuf = VkDeviceQueueCreateInfo.calloc(numQueuesFamilies, stack)
            for (i in 0 until numQueuesFamilies) {
                val priorities = stack.callocFloat(queuePropsBuff[i].queueCount())
                queueCreationInfoBuf[i]
                    .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(i)
                    .pQueuePriorities(priorities)
            }

// --- DeviceCreateInfo
            val deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .ppEnabledExtensionNames(requiredExtensions)
                .pQueueCreateInfos(queueCreationInfoBuf)
                .pNext(features.address()) // <- features2 is the head of our chain


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
    /** True if both DEVICE and CLOCK_MONOTONIC time domains are calibrateable. */
    fun hasCalibrateableDomains(): Boolean {
        MemoryStack.stackPush().use { stack ->
            val countBuf = stack.mallocInt(1)
            EXTCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsEXT(
                physicalDevice.vkPhysicalDevice, countBuf, null as java.nio.IntBuffer?
            )
            val count = countBuf[0]
            if (count == 0) return false

            val domains = stack.mallocInt(count)
            EXTCalibratedTimestamps.vkGetPhysicalDeviceCalibrateableTimeDomainsEXT(
                physicalDevice.vkPhysicalDevice, countBuf, domains
            )
            var hasDevice = false
            var hasMono = false
            for (i in 0 until count) {
                when (domains[i]) {
                    VK_TIME_DOMAIN_DEVICE_KHR -> hasDevice = true
                    VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR -> hasMono = true
                }
            }
            return hasDevice && hasMono
        }
    }

    /** Returns (hostMonotonicNS, gpuNS). Requires VK_EXT_calibrated_timestamps enabled on the device. */
    fun calibratedHostGpuTimeNS(): Pair<Long, Long> {
        MemoryStack.stackPush().use { stack ->
            val infos = VkCalibratedTimestampInfoKHR.calloc(2, stack)
            infos[0]
                .sType(KHRCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_KHR)
                .timeDomain(VK_TIME_DOMAIN_DEVICE_KHR)
            infos[1]
                .sType(KHRCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_KHR)
                .timeDomain(VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR)

            val timestamps = stack.mallocLong(2)
            val maxDeviation = stack.mallocLong(1) // spec allows single entry as an upper bound

            val res = EXTCalibratedTimestamps.vkGetCalibratedTimestampsEXT(
                vkDevice, // DEVICE-level call
                infos,
                timestamps,
                maxDeviation
            )
            vkCheck(res, "vkGetCalibratedTimestampsEXT failed")

            val gpuNS = timestamps[0]
            val hostNS = timestamps[1]
            return hostNS to gpuNS
        }
    }

}

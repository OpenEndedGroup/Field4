package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import afield.logger.lprint
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memGetAddress
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2
import org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES

class Device(val physicalDevice: PhysicalDevice, private val instance: Instance) {
    val vkDevice: VkDevice
    val supportsPushDescriptors: Boolean
    val pushDescriptorMax: Int
    val supportsExtendedDynamicState3: Boolean
    val supportsExtendedDynamicState: Boolean

    var usePresentIndexing = !Options.isMac
    var enableMeshShading = !Options.isMac

    // Cache the device's supported extensions once.
    private val supportedExtensions: Set<String> by lazy { enumerateDeviceExtensions() }

    init {
        MemoryStack.stackPush().use { stack ->
            val hasExt = { name: String -> supportedExtensions.contains(name) }
            val ascii = { name: String -> stack.ASCII(name) }

            val usePortability =
                hasExt(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME) && OS == OSType.MACOS
            val useExternalMemory = OS == OSType.WINDOWS
            usePresentIndexing = OS == OSType.WINDOWS
            enableMeshShading =
                OS == OSType.WINDOWS && hasExt(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)

            val profilingEnabled = Options.profiling
            val hasPerfQuery = hasExt(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
            val hasHostQueryReset = hasExt(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME)
            val hasCalibrated = hasExt(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
            val hasPushDescriptor = hasExt(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)
            supportsPushDescriptors = hasPushDescriptor
            val hasExtDynamicState =
                hasExt(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME)
            val hasExtDynamicState3 =
                hasExt(org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME)
            supportsExtendedDynamicState = hasExtDynamicState
            supportsExtendedDynamicState3 = hasExtDynamicState3

            // Build extension list defensively.
            val extNames = ArrayList<String>(12).apply {
                add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME) // not optional
                addIfSupported(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                addIfSupported(KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME)
                addIfSupported(KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME)
                addIfSupported(KHRSeparateDepthStencilLayouts.VK_KHR_SEPARATE_DEPTH_STENCIL_LAYOUTS_EXTENSION_NAME)
                addIfSupported(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)

                addIfSupported(KHR16bitStorage.VK_KHR_16BIT_STORAGE_EXTENSION_NAME)

                if (enableMeshShading) {
                    add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)
                }

                if (OS == OSType.WINDOWS) {
                    addIfSupported(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)
                    addIfSupported(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
                    addIfSupported(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME)
                    addIfSupported(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME)
                }

                if (OS == OSType.MACOS) {
                    addIfSupported(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
                }

                if (Options.profiling) {
                    addIfSupported(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
                    addIfSupported(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME)
                    addIfSupported(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
                }

                addIfSupported(KHRShaderNonSemanticInfo.VK_KHR_SHADER_NON_SEMANTIC_INFO_EXTENSION_NAME)
                addIfSupported(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME)
                addIfSupported(org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME)

                if (Options.enableVR) {
                    val d = Instance.getOpenXRRequiredDeviceExts(physicalDevice.vkPhysicalDevice.address())
                    lprint("info,openxr", "required device extensions are $d")
                    addAll(d)
                    addIfSupported(KHRMultiview.VK_KHR_MULTIVIEW_EXTENSION_NAME)
                }

                // VK_EXT_hdr_metadata lets us advertise mastering-display primaries to the OS/compositor
                // for HDR10 swap chains.  Opt-in only; safe to skip when unsupported.
                if (Options.enableHDR) {
                    addIfSupported(EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME)
                }

            }

            val requiredExtensions = stack.mallocPointer(extNames.size).apply {
                extNames.forEach { put(ascii(it)) }
                flip()
            }

            // --- Core features via VkPhysicalDeviceFeatures2 + Vulkan12/13
            val features = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                features().fragmentStoresAndAtomics(true)
                features().vertexPipelineStoresAndAtomics(true)
                features().shaderInt64(true)
                features().robustBufferAccess(Options.robustness)
                // Request pipeline statistics only when profiling is on; driver may ignore if unsupported
                features().pipelineStatisticsQuery(profilingEnabled)
            }

            val features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).apply {
                `sType$Default`()
                shaderDrawParameters(true)
            }

            val features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).apply {
                sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)

                shaderOutputLayer(true)
                shaderOutputViewportIndex(true)

                shaderFloat16(true)

                bufferDeviceAddress(true)
                timelineSemaphore(true)

                // Descriptor indexing (promoted to 1.2) — bindless-friendly bits
                descriptorIndexing(true)
                runtimeDescriptorArray(true)
                descriptorBindingPartiallyBound(true)
                descriptorBindingVariableDescriptorCount(true)

                // Non-uniform indexing (use as needed)
                shaderSampledImageArrayNonUniformIndexing(true)
                shaderStorageImageArrayNonUniformIndexing(true)
                shaderUniformBufferArrayNonUniformIndexing(true)
                shaderStorageBufferArrayNonUniformIndexing(true)
                // If you use texel buffers with non-uniform indexing, enable these too:
                // shaderUniformTexelBufferArrayNonUniformIndexing(true)
                // shaderStorageTexelBufferArrayNonUniformIndexing(true)

                // Leave all *UpdateAfterBind* = false unless your pool/layouts use the corresponding flags.
                // descriptorBindingSampledImageUpdateAfterBind(true)
                // descriptorBindingStorageImageUpdateAfterBind(true)
                // descriptorBindingUniformBufferUpdateAfterBind(true)
                // descriptorBindingStorageBufferUpdateAfterBind(true)
                // descriptorBindingUniformTexelBufferUpdateAfterBind(true)
                // descriptorBindingStorageTexelBufferUpdateAfterBind(true)
                // descriptorBindingUpdateUnusedWhilePending(true)
            }

            val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).apply {
                sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                dynamicRendering(true)
                synchronization2(true)
            }

            val eds3 = VkPhysicalDeviceExtendedDynamicState3FeaturesEXT.calloc(stack)
                .sType(org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT)
                .extendedDynamicState3PolygonMode(true)
                .extendedDynamicState3DepthClampEnable(true)

            val eds = VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc(stack)
                .sType(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT)
                .extendedDynamicState(true)

            // Link pNext chain: features2 -> v11 -> v12 -> v13
            features.pNext(features11.address())
            features11.pNext(features12.address())
            features12.pNext(features13.address())
            var tail = features13.address()

            if (supportsExtendedDynamicState) {
                memPutAddress(tail + VkBaseOutStructure.PNEXT, eds.address()); tail = eds.address()
            }

            if (supportsExtendedDynamicState3) {
                memPutAddress(tail + VkBaseOutStructure.PNEXT, eds3.address()); tail = eds3.address()
            }

            val storage16 = VkPhysicalDevice16BitStorageFeatures.calloc(stack).apply {
                sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES)
                storagePushConstant16(true)
            }

            memPutAddress(tail + VkBaseOutStructure.PNEXT, storage16.address()); tail = storage16.address()


            // Optional: mesh shader + present features (Windows path).
            if (enableMeshShading && usePresentIndexing) {
                val mesh = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                    .meshShader(true).taskShader(true).multiviewMeshShader(true)

                val mv = VkPhysicalDeviceMultiviewFeaturesKHR.calloc(stack)
                    .sType(KHRMultiview.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTIVIEW_FEATURES_KHR)
                    .multiview(true)

                val presentId = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR)
                    .presentId(true)

                val presentWait = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack)
                    .sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR)
                    .presentWait(true)

                memPutAddress(tail + VkBaseOutStructure.PNEXT, mesh.address()); tail = mesh.address()
                memPutAddress(tail + VkBaseOutStructure.PNEXT, mv.address()); tail = mv.address()
                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentId.address()); tail = presentId.address()
                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentWait.address()); tail = presentWait.address()
            }

            // Profiling features (conditionally).
            if (profilingEnabled && hasPerfQuery) {
                val perf = VkPhysicalDevicePerformanceQueryFeaturesKHR.calloc(stack)
                    .sType(KHRPerformanceQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PERFORMANCE_QUERY_FEATURES_KHR)
                    .performanceCounterQueryPools(true)
                    .performanceCounterMultipleQueryPools(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, perf.address()); tail = perf.address()
            }

            if (profilingEnabled && hasHostQueryReset) {
                val hostReset = VkPhysicalDeviceHostQueryResetFeatures.calloc(stack)
                    .sType(EXTHostQueryReset.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES_EXT)
                    .hostQueryReset(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, hostReset.address()); tail = hostReset.address()
            }

            // Strip any VkPhysicalDeviceTimelineSemaphoreFeatures if a loader/runtime appended one; spec forbids
            // having both VkPhysicalDeviceVulkan12Features and VkPhysicalDeviceTimelineSemaphoreFeatures.
            fun stripTimelineFeature(headAddr: Long) {
                var prevAddr = headAddr
                var nextAddr = memGetAddress(prevAddr + VkBaseOutStructure.PNEXT)
                while (nextAddr != 0L) {
                    val nxt = VkBaseOutStructure.create(nextAddr)
                    if (nxt.sType() == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES) {
                        val after = memGetAddress(nextAddr + VkBaseOutStructure.PNEXT)
                        memPutAddress(prevAddr + VkBaseOutStructure.PNEXT, after)
                        break
                    }
                    prevAddr = nextAddr
                    nextAddr = memGetAddress(prevAddr + VkBaseOutStructure.PNEXT)
                }
            }

            stripTimelineFeature(features.address())

            // Enable all queue families.
            val qProps = physicalDevice.vkQueueFamilyProps
            val qInfos = VkDeviceQueueCreateInfo.calloc(qProps.capacity(), stack)
            for (i in 0 until qProps.capacity()) {
                val priorities = stack.callocFloat(qProps[i].queueCount())
                qInfos[i]
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(i)
                    .pQueuePriorities(priorities)
            }

            val ci = VkDeviceCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .ppEnabledExtensionNames(requiredExtensions)
                .pQueueCreateInfos(qInfos)
                .pNext(features.address())

            if (Options.enableVR && Instance.hasOpenXRBootstrap()) {
                vkDevice = Instance.createVkDeviceViaXR(ci, instance.vkInstance, physicalDevice.vkPhysicalDevice)
            } else {
                val pDev = stack.mallocPointer(1)
                vkCheck(vkCreateDevice(physicalDevice.vkPhysicalDevice, ci, null, pDev), "Failed to create device")
                vkDevice = VkDevice(pDev[0], physicalDevice.vkPhysicalDevice, ci)
            }

            // Query push-descriptor limits (0 if unsupported)
            pushDescriptorMax = if (hasPushDescriptor) {
                val props2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                val pushProps = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack)
                    .sType(KHRPushDescriptor.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PUSH_DESCRIPTOR_PROPERTIES_KHR)
                props2.pNext(pushProps.address().toLong())
                vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice, props2)
                pushProps.maxPushDescriptors()
            } else 0
            lprintln("info,device", "Device push descriptors: supported=$supportsPushDescriptors, maxPushDescriptors=$pushDescriptorMax")
        }
    }

    fun waitIdle() {
        vkDeviceWaitIdle(vkDevice)
    }

    fun cleanup() {
        vkDestroyDevice(vkDevice, null)
    }

    private fun MutableList<String>.addIfSupported(name: String) {
        if (name in supportedExtensions) add(name)
    }

    private fun enumerateDeviceExtensions(): Set<String> {
        val out = HashSet<String>()
        MemoryStack.stackPush().use { stack ->
            val countBuf = stack.callocInt(1)
            vkEnumerateDeviceExtensionProperties(physicalDevice.vkPhysicalDevice, null as String?, countBuf, null)
            val props = VkExtensionProperties.calloc(countBuf[0], stack)
            vkEnumerateDeviceExtensionProperties(physicalDevice.vkPhysicalDevice, null as String?, countBuf, props)
            repeat(props.capacity()) { i -> out += props[i].extensionNameString() }
        }
        return out
    }

    /** True if DEVICE and CLOCK_MONOTONIC time domains are calibrateable (guarded by extension presence). */
    fun hasCalibrateableDomains(): Boolean {
        if (!supportedExtensions.contains(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)) {
            return false
        }
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

    /**
     * Returns (hostMonotonicNS, gpuNS). Requires VK_EXT_calibrated_timestamps.
     * Callers should gate on [hasCalibrateableDomains].
     */
    fun calibratedHostGpuTimeNS(): Pair<Long, Long> {
        require(supportedExtensions.contains(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)) {
            "VK_EXT_calibrated_timestamps not enabled"
        }
        MemoryStack.stackPush().use { stack ->
            val infos = VkCalibratedTimestampInfoKHR.calloc(2, stack)
            infos[0]
                .sType(KHRCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_KHR)
                .timeDomain(VK_TIME_DOMAIN_DEVICE_KHR)
            infos[1]
                .sType(KHRCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_KHR)
                .timeDomain(VK_TIME_DOMAIN_CLOCK_MONOTONIC_KHR)

            val timestamps = stack.mallocLong(2)
            val maxDeviation = stack.mallocLong(1)

            val res = EXTCalibratedTimestamps.vkGetCalibratedTimestampsEXT(
                vkDevice, infos, timestamps, maxDeviation
            )
            vkCheck(res, "vkGetCalibratedTimestampsEXT failed")

            val gpuNS = timestamps[0]
            val hostNS = timestamps[1]
            return hostNS to gpuNS
        }
    }
}

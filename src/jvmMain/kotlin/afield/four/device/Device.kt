package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import afield.logger.lprint
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT
import org.lwjgl.vulkan.EXTCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_EXT
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2

class Device(val physicalDevice: PhysicalDevice, private val instance: Instance) {
    private val ngxDelegate = lazy { afield.four.reconstruction.NgxRuntime.open(this, instance) }
    internal val ngxRuntime by ngxDelegate
    data class RayTracingLimits(
        val shaderGroupHandleSize: Int,
        val shaderGroupHandleAlignment: Int,
        val shaderGroupBaseAlignment: Int,
        val maxRayRecursionDepth: Int,
        val maxRayDispatchInvocationCount: Int,
        val maxRayHitAttributeSize: Int,
        val minAccelerationStructureScratchOffsetAlignment: Int,
        val maxGeometryCount: Long,
        val maxInstanceCount: Long,
        val maxPrimitiveCount: Long,
        val maxPerStageDescriptorAccelerationStructures: Int,
        val maxDescriptorSetAccelerationStructures: Int
    )

    val vkDevice: VkDevice
    val supportsPushDescriptors: Boolean
    val pushDescriptorMax: Int
    val supportsExtendedDynamicState3: Boolean
    val supportsExtendedDynamicState: Boolean
    val supportsMaintenance7: Boolean
    val supportsMaintenance11: Boolean
    val supportsDynamicRenderingUnusedAttachments: Boolean
    val supportsMultiview: Boolean
    val raySupport: RayDeviceSupport
    val enabledExtensions: Set<String>
    val supportsAccelerationStructures: Boolean
    val supportsRayTracing: Boolean
    val supportsRayQuery: Boolean
    val rayTracingLimits: RayTracingLimits?
    /** Optional shader/vision features. SDKs are not required to enable these Vulkan features. */
    val supportsOpticalFlow: Boolean
    val supportsCooperativeVector: Boolean
    val supportsCooperativeVectorTraining: Boolean
    val supportsClusterAccelerationStructure: Boolean

    var usePresentIndexing = !Options.isMac
    var enableMeshShading = !Options.isMac
    var enableRayTracing = !Options.isMac
    /** True when VK_EXT_present_timing was successfully enabled on the device. Never true on macOS/MoltenVK. */
    var usesPresentTiming = false

    // Cache the device's supported extensions once.
    private val supportedExtensions: Set<String> by lazy { enumerateDeviceExtensions() }

    /** VK_EXT_metal_objects (MoltenVK) — required to export an RTT color image's backing IOSurface. */
    val supportsMetalObjects: Boolean
        get() = supportedExtensions.contains(org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME)

    /**
     * Refuse, naming the way out, when [supportsMetalObjects] is false. That is KosmicKrisp — the
     * default Mac driver since 2026-09-29 — until the IOSurface paths move to
     * VK_EXT_external_memory_metal (`dev_docs/KOSMICKRISP.md`).
     */
    fun requireMetalObjects(what: String) = check(supportsMetalObjects) {
        "$what needs VK_EXT_metal_objects, which the ${physicalDevice.driverName.ifEmpty { "current" }} Vulkan driver " +
            "does not have. Run Field on MoltenVK for it: AFIELD_VULKAN_DRIVER=moltenvk ./field.sh"
    }

    /** VK_KHR_external_memory_fd (Linux) — required to export an RTT color image's backing memory
     *  as an opaque fd for CUDA/NVENC import (see VulkanExternalMemory.kt / field-encode-linux). */
    val supportsExternalMemoryFd: Boolean
        get() = supportedExtensions.contains(org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)

    /** Extensions needed to import Electron nativePixmap dma-bufs with explicit DRM modifiers. */
    val supportsDmaBufImport: Boolean
        get() = supportsExternalMemoryFd &&
            supportedExtensions.contains(EXTExternalMemoryDmaBuf.VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME) &&
            supportedExtensions.contains(EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME) &&
            supportedExtensions.contains(EXTQueueFamilyForeign.VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)

    /** VK_KHR_external_memory_win32, used for duplicated Electron D3D11 NT handles. */
    val supportsExternalMemoryWin32: Boolean
        get() = supportedExtensions.contains(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)

    init {
        MemoryStack.stackPush().use { stack ->
            val hasExt = { name: String -> supportedExtensions.contains(name) }
            val ascii = { name: String -> stack.ASCII(name) }

            val usePortability =
                hasExt(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME) && OS == OSType.MACOS
            val useExternalMemory = OS == OSType.WINDOWS
            usePresentIndexing = OS == OSType.WINDOWS &&
                hasExt(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME) &&
                hasExt(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME)
            enableMeshShading =
                OS == OSType.WINDOWS && hasExt(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)

            // VK_EXT_present_timing has a strict dependency bundle. Enabling the extension
            // without every member is invalid even if presentTiming itself is supported.
            val hasPresentTimingBundle = OS != OSType.MACOS &&
                hasExt(EXTPresentTiming.VK_EXT_PRESENT_TIMING_EXTENSION_NAME) &&
                hasExt(KHRPresentId2.VK_KHR_PRESENT_ID_2_EXTENSION_NAME) &&
                hasExt(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME) &&
                KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME in instance.enabledExtensions
            usesPresentTiming = if (hasPresentTimingBundle) {
                val ptFeatures = VkPhysicalDevicePresentTimingFeaturesEXT.calloc(stack)
                    .`sType$Default`()
                val presentId2Features = VkPhysicalDevicePresentId2FeaturesKHR.calloc(stack)
                    .`sType$Default`()
                ptFeatures.pNext(presentId2Features.address())
                val probe = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                    sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    pNext(ptFeatures.address())
                }
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, probe)
                ptFeatures.presentTiming() && presentId2Features.presentId2()
            } else false

            val profilingEnabled = Options.profiling
            val hasPerfQuery = hasExt(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
            val hasHostQueryReset = hasExt(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME)
            val hasCalibrated = hasExt(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
            val hasPushDescriptor = hasExt(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)
            supportsPushDescriptors = hasPushDescriptor
            val supportedCoreFeatures = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            }
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, supportedCoreFeatures)
            val enablePipelineStatisticsQuery =
                profilingEnabled && supportedCoreFeatures.features().pipelineStatisticsQuery()
            if (profilingEnabled && !enablePipelineStatisticsQuery) {
                lprint(
                    "info,graphics",
                    "profiling enabled without pipelineStatisticsQuery; using timestamp timing only"
                )
            }
            val hasExtDynamicState =
                hasExt(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME)
            val hasExtDynamicState3 =
                hasExt(org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME)
            val hasMaintenance7 = hasExt(KHRMaintenance7.VK_KHR_MAINTENANCE_7_EXTENSION_NAME)
            supportsMaintenance7 = if (hasMaintenance7) {
                val m7Features = VkPhysicalDeviceMaintenance7FeaturesKHR.calloc(stack)
                    .sType(KHRMaintenance7.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_7_FEATURES_KHR)
                val m7FeatureProbe = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                    sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    pNext(m7Features.address())
                }
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, m7FeatureProbe)
                m7Features.maintenance7()
            } else false
            val hasMaintenance11 = hasExt(KHRMaintenance11.VK_KHR_MAINTENANCE_11_EXTENSION_NAME)
            supportsMaintenance11 = if (hasMaintenance11) {
                val m11Features = VkPhysicalDeviceMaintenance11FeaturesKHR.calloc(stack)
                    .sType(KHRMaintenance11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_11_FEATURES_KHR)
                val m11FeatureProbe = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                    sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    pNext(m11Features.address())
                }
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, m11FeatureProbe)
                m11Features.maintenance11()
            } else false
            val hasDynamicRenderingUnusedAttachments =
                hasExt(EXTDynamicRenderingUnusedAttachments.VK_EXT_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_EXTENSION_NAME)
            supportsDynamicRenderingUnusedAttachments = if (hasDynamicRenderingUnusedAttachments) {
                val unusedAttachmentFeatures = VkPhysicalDeviceDynamicRenderingUnusedAttachmentsFeaturesEXT.calloc(stack)
                    .sType(EXTDynamicRenderingUnusedAttachments.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_FEATURES_EXT)
                val unusedAttachmentFeatureProbe = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                    sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    pNext(unusedAttachmentFeatures.address())
                }
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice, unusedAttachmentFeatureProbe)
                unusedAttachmentFeatures.dynamicRenderingUnusedAttachments()
            } else false
            // Field sets only the depth-clamp dynamic state, so that is the EDS3 feature that decides
            // support. polygonMode is requested only where offered: KosmicKrisp has EDS3 without it
            // (and without fillModeNonSolid), and asking for it anyway fails vkCreateDevice.
            val eds3Supported = VkPhysicalDeviceExtendedDynamicState3FeaturesEXT.calloc(stack).`sType$Default`()
            if (hasExtDynamicState3) probeFeatures(stack, eds3Supported.address())
            supportsExtendedDynamicState = hasExtDynamicState
            supportsExtendedDynamicState3 = hasExtDynamicState3 && eds3Supported.extendedDynamicState3DepthClampEnable()
            val eds3PolygonMode = eds3Supported.extendedDynamicState3PolygonMode()
            supportsMultiview = hasExt(KHRMultiview.VK_KHR_MULTIVIEW_EXTENSION_NAME)
            raySupport = RayDeviceSupport.probe(physicalDevice.vkPhysicalDevice, supportedExtensions)
            supportsAccelerationStructures = Options.enableRayTracing && raySupport.accelerationUsable
            enableRayTracing = Options.enableRayTracing && raySupport.pipelineUsable
            supportsRayTracing = enableRayTracing
            supportsRayQuery = Options.enableRayTracing && raySupport.queryUsable
            rayTracingLimits = if (raySupport.accelerationUsable) queryRayTracingLimits(stack) else null
            val clusterFeatures=VkPhysicalDeviceClusterAccelerationStructureFeaturesNV.calloc(stack).`sType$Default`()
            val opticalFeatures=VkPhysicalDeviceOpticalFlowFeaturesNV.calloc(stack).`sType$Default`()
            val vectorFeatures=VkPhysicalDeviceCooperativeVectorFeaturesNV.calloc(stack).`sType$Default`()
            val replicatedFeatures=VkPhysicalDeviceShaderReplicatedCompositesFeaturesEXT.calloc(stack).`sType$Default`()
            val memoryModelFeatures=VkPhysicalDeviceVulkanMemoryModelFeatures.calloc(stack).`sType$Default`()
            fun probeExtra(address: Long) = VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice,
                VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`().pNext(address))
            if(hasExt(NVOpticalFlow.VK_NV_OPTICAL_FLOW_EXTENSION_NAME))probeExtra(opticalFeatures.address())
            if(hasExt(NVCooperativeVector.VK_NV_COOPERATIVE_VECTOR_EXTENSION_NAME))probeExtra(vectorFeatures.address())
            if(hasExt(EXTShaderReplicatedComposites.VK_EXT_SHADER_REPLICATED_COMPOSITES_EXTENSION_NAME))probeExtra(replicatedFeatures.address())
            probeExtra(memoryModelFeatures.address())
            if(hasExt(NVClusterAccelerationStructure.VK_NV_CLUSTER_ACCELERATION_STRUCTURE_EXTENSION_NAME))probeExtra(clusterFeatures.address())
            supportsClusterAccelerationStructure=supportsAccelerationStructures && clusterFeatures.clusterAccelerationStructure() &&
                !java.lang.Boolean.getBoolean("afield.clusterAccelerationStructure.disabled")
            supportsOpticalFlow=opticalFeatures.opticalFlow() && !java.lang.Boolean.getBoolean("afield.opticalFlow.disabled")
            supportsCooperativeVector=vectorFeatures.cooperativeVector() && replicatedFeatures.shaderReplicatedComposites() &&
                memoryModelFeatures.vulkanMemoryModel() && !java.lang.Boolean.getBoolean("afield.cooperativeVector.disabled")
            supportsCooperativeVectorTraining=supportsCooperativeVector && vectorFeatures.cooperativeVectorTraining() &&
                !java.lang.Boolean.getBoolean("afield.cooperativeVectorTraining.disabled")

            // SteamVR's Linux XR_KHR_vulkan_enable2 implementation appends a
            // VkPhysicalDeviceTimelineSemaphoreFeatures while creating the device. Vulkan forbids
            // that promoted struct alongside VkPhysicalDeviceVulkan12Features, so the Linux XR
            // path enables the other promoted features individually below.
            val useSplitVulkan12Features =
                OS == OSType.LINUX && Options.enableVR && Instance.hasOpenXRBootstrap()

            // Build extension list defensively.
            val extNames = ArrayList<String>(12).apply {
                add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME) // not optional
                if(supportsOpticalFlow)add(NVOpticalFlow.VK_NV_OPTICAL_FLOW_EXTENSION_NAME)
                if(supportsCooperativeVector){add(NVCooperativeVector.VK_NV_COOPERATIVE_VECTOR_EXTENSION_NAME);add(EXTShaderReplicatedComposites.VK_EXT_SHADER_REPLICATED_COMPOSITES_EXTENSION_NAME)}
                addIfSupported(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                addIfSupported(KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME)
                addIfSupported(KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME)
                addIfSupported(KHRSeparateDepthStencilLayouts.VK_KHR_SEPARATE_DEPTH_STENCIL_LAYOUTS_EXTENSION_NAME)
                addIfSupported(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)
                if (supportsMaintenance7) add(KHRMaintenance7.VK_KHR_MAINTENANCE_7_EXTENSION_NAME)
                if (supportsMaintenance11) add(KHRMaintenance11.VK_KHR_MAINTENANCE_11_EXTENSION_NAME)
                if (supportsDynamicRenderingUnusedAttachments) {
                    add(EXTDynamicRenderingUnusedAttachments.VK_EXT_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_EXTENSION_NAME)
                }

                addIfSupported(KHR16bitStorage.VK_KHR_16BIT_STORAGE_EXTENSION_NAME)

                // shaderOutputLayer/shaderOutputViewportIndex normally come from
                // VkPhysicalDeviceVulkan12Features. The split Linux XR feature chain cannot carry
                // that aggregate struct, so expose the equivalent pre-promotion capability.
                if (useSplitVulkan12Features) {
                    check(hasExt(EXTShaderViewportIndexLayer.VK_EXT_SHADER_VIEWPORT_INDEX_LAYER_EXTENSION_NAME)) {
                        "Linux OpenXR requires ${EXTShaderViewportIndexLayer.VK_EXT_SHADER_VIEWPORT_INDEX_LAYER_EXTENSION_NAME}"
                    }
                    add(EXTShaderViewportIndexLayer.VK_EXT_SHADER_VIEWPORT_INDEX_LAYER_EXTENSION_NAME)
                }

                if (enableMeshShading) {
                    add(EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME)
                }

                if (supportsAccelerationStructures) {
                    add(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME)
                    add(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                    if(supportsClusterAccelerationStructure)add(NVClusterAccelerationStructure.VK_NV_CLUSTER_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                    if (supportsRayTracing) add(KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)
                    addIfSupported(KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME)
                    addIfSupported(KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME)
                    if (supportsRayQuery) {
                        add(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME)
                    }
                }

                if (OS == OSType.WINDOWS) {
                    addIfSupported(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)
                    addIfSupported(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
                    addIfSupported(KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME)
                    addIfSupported(KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME)
                }

                if (OS == OSType.MACOS) {
                    addIfSupported(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)
                    // Lets an RTT color image export its backing IOSurface for GPU video streaming.
                    addIfSupported(org.lwjgl.vulkan.EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME)
                }

                if (OS == OSType.LINUX) {
                    // Lets an RTT color image export its backing memory as an opaque fd, for
                    // zero-copy CUDA/NVENC video streaming (field-encode-linux). VK_KHR_external_memory
                    // is the base capability VK_KHR_external_memory_fd builds on.
                    addIfSupported(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)
                    addIfSupported(org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)
                    addIfSupported(EXTExternalMemoryDmaBuf.VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME)
                    addIfSupported(EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME)
                    addIfSupported(EXTQueueFamilyForeign.VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)
                }

                // VK_EXT_present_timing: scanout-jitter logging via actual display timestamps.
                // Explicitly excluded from macOS/MoltenVK where the extension is absent/broken.
                if (usesPresentTiming) {
                    add(EXTPresentTiming.VK_EXT_PRESENT_TIMING_EXTENSION_NAME)
                    add(KHRPresentId2.VK_KHR_PRESENT_ID_2_EXTENSION_NAME)
                    add(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
                }

                if (Options.profiling) {
                    addIfSupported(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
                    addIfSupported(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME)
                    addIfSupported(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)
                }

                addIfSupported(KHRShaderNonSemanticInfo.VK_KHR_SHADER_NON_SEMANTIC_INFO_EXTENSION_NAME)
                addIfSupported(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME)
                if (supportsExtendedDynamicState3) add(org.lwjgl.vulkan.EXTExtendedDynamicState3.VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME)

                if (Options.enableVR) {
                    val d = Instance.getOpenXRRequiredDeviceExts(physicalDevice.vkPhysicalDevice.address())
                    lprint("info,openxr", "required device extensions are $d")
                    addAll(d)
                }
                // this is useful outside of the context of VR as well
                addIfSupported(KHRMultiview.VK_KHR_MULTIVIEW_EXTENSION_NAME)

                // VK_EXT_hdr_metadata lets us advertise mastering-display primaries to the OS/compositor
                // for HDR10 swap chains.  Opt-in only; safe to skip when unsupported.
                if (Options.enableHDR) {
                    addIfSupported(EXTHdrMetadata.VK_EXT_HDR_METADATA_EXTENSION_NAME)
                }

            }
            extNames.addAll(afield.four.reconstruction.NgxSupport.deviceExtensions(instance, physicalDevice.vkPhysicalDevice.address(), supportedExtensions))
            val dedupedExtNames = extNames.distinct()
            enabledExtensions = dedupedExtNames.toSet()

            val requiredExtensions = stack.mallocPointer(dedupedExtNames.size).apply {
                dedupedExtNames.forEach { put(ascii(it)) }
                flip()
            }

            // --- Core features via VkPhysicalDeviceFeatures2 + Vulkan12/13
            val features = VkPhysicalDeviceFeatures2.calloc(stack).apply {
                sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                // Requested where offered, and named when not, so a driver missing one reports it here
                // rather than failing vkCreateDevice with VK_ERROR_FEATURE_NOT_PRESENT. KosmicKrisp
                // before SDK 1.4.357 lacked vertexPipelineStoresAndAtomics.
                val core = supportedCoreFeatures.features()
                val missing = listOfNotNull(
                    "fragmentStoresAndAtomics".takeUnless { core.fragmentStoresAndAtomics() },
                    "vertexPipelineStoresAndAtomics".takeUnless { core.vertexPipelineStoresAndAtomics() },
                    "shaderInt64".takeUnless { core.shaderInt64() },
                )
                if (missing.isNotEmpty()) {
                    lprintln("warning,vulkan", "${physicalDevice.driverName} lacks $missing; shaders that need them will fail to build")
                }
                features().fragmentStoresAndAtomics(core.fragmentStoresAndAtomics())
                features().vertexPipelineStoresAndAtomics(core.vertexPipelineStoresAndAtomics())
                features().shaderInt64(core.shaderInt64())
                features().shaderInt16(supportedCoreFeatures.features().shaderInt16())
                // Guard: geometryShader is unsupported on Apple Silicon / MoltenVK.
                features().geometryShader(physicalDevice.vkPhysicalDeviceFeatures.geometryShader())
                features().robustBufferAccess(Options.robustness)
                features().pipelineStatisticsQuery(enablePipelineStatisticsQuery)
            }

            val features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).apply {
                `sType$Default`()
                multiview(supportsMultiview)
                shaderDrawParameters(true)
                storagePushConstant16(true)
            }

            val features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).apply {
                sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)

                shaderOutputLayer(true)
                shaderOutputViewportIndex(true)

                shaderFloat16(true)

                bufferDeviceAddress(true)
                timelineSemaphore(true)
                vulkanMemoryModel(supportsCooperativeVector)

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
                .extendedDynamicState3PolygonMode(eds3PolygonMode)
                .extendedDynamicState3DepthClampEnable(true)

            val eds = VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc(stack)
                .sType(org.lwjgl.vulkan.EXTExtendedDynamicState.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT)
                .extendedDynamicState(true)

            features.pNext(features11.address())
            var featureTail = features11.address()
            if (useSplitVulkan12Features) {
                val shaderFloat16 = VkPhysicalDeviceShaderFloat16Int8Features.calloc(stack)
                    .`sType$Default`()
                    .shaderFloat16(true)
                memPutAddress(featureTail + VkBaseOutStructure.PNEXT, shaderFloat16.address())
                featureTail = shaderFloat16.address()

                val bufferDeviceAddress = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                    .`sType$Default`()
                    .bufferDeviceAddress(true)
                memPutAddress(featureTail + VkBaseOutStructure.PNEXT, bufferDeviceAddress.address())
                featureTail = bufferDeviceAddress.address()

                val descriptorIndexing = VkPhysicalDeviceDescriptorIndexingFeatures.calloc(stack)
                    .`sType$Default`()
                    .runtimeDescriptorArray(true)
                    .descriptorBindingPartiallyBound(true)
                    .descriptorBindingVariableDescriptorCount(true)
                    .shaderSampledImageArrayNonUniformIndexing(true)
                    .shaderStorageImageArrayNonUniformIndexing(true)
                    .shaderUniformBufferArrayNonUniformIndexing(true)
                    .shaderStorageBufferArrayNonUniformIndexing(true)
                memPutAddress(featureTail + VkBaseOutStructure.PNEXT, descriptorIndexing.address())
                featureTail = descriptorIndexing.address()
                if(supportsCooperativeVector) {
                    memoryModelFeatures.vulkanMemoryModelDeviceScope(false).vulkanMemoryModelAvailabilityVisibilityChains(false).pNext(0L)
                    memPutAddress(featureTail+VkBaseOutStructure.PNEXT,memoryModelFeatures.address());featureTail=memoryModelFeatures.address()
                }
            } else {
                memPutAddress(featureTail + VkBaseOutStructure.PNEXT, features12.address())
                featureTail = features12.address()
            }
            memPutAddress(featureTail + VkBaseOutStructure.PNEXT, features13.address())
            var tail = features13.address()
            if(supportsOpticalFlow) {
                opticalFeatures.pNext(0L);memPutAddress(tail+VkBaseOutStructure.PNEXT,opticalFeatures.address());tail=opticalFeatures.address()
            }
            if(supportsCooperativeVector) {
                vectorFeatures.cooperativeVectorTraining(supportsCooperativeVectorTraining).pNext(0L)
                memPutAddress(tail+VkBaseOutStructure.PNEXT,vectorFeatures.address());tail=vectorFeatures.address()
                replicatedFeatures.pNext(0L);memPutAddress(tail+VkBaseOutStructure.PNEXT,replicatedFeatures.address());tail=replicatedFeatures.address()
            }

            if(supportsClusterAccelerationStructure) {
                clusterFeatures.pNext(0L);memPutAddress(tail+VkBaseOutStructure.PNEXT,clusterFeatures.address());tail=clusterFeatures.address()
            }
            if (supportsExtendedDynamicState) {
                memPutAddress(tail + VkBaseOutStructure.PNEXT, eds.address()); tail = eds.address()
            }

            if (supportsExtendedDynamicState3) {
                memPutAddress(tail + VkBaseOutStructure.PNEXT, eds3.address()); tail = eds3.address()
            }

            if (supportsMaintenance7) {
                val m7 = VkPhysicalDeviceMaintenance7FeaturesKHR.calloc(stack)
                    .sType(KHRMaintenance7.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_7_FEATURES_KHR)
                    .maintenance7(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, m7.address()); tail = m7.address()
            }

            if (supportsMaintenance11) {
                val m11 = VkPhysicalDeviceMaintenance11FeaturesKHR.calloc(stack)
                    .sType(KHRMaintenance11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_11_FEATURES_KHR)
                    .maintenance11(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, m11.address()); tail = m11.address()
            }

            if (supportsDynamicRenderingUnusedAttachments) {
                val unusedAttachments = VkPhysicalDeviceDynamicRenderingUnusedAttachmentsFeaturesEXT.calloc(stack)
                    .sType(EXTDynamicRenderingUnusedAttachments.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_UNUSED_ATTACHMENTS_FEATURES_EXT)
                    .dynamicRenderingUnusedAttachments(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, unusedAttachments.address()); tail = unusedAttachments.address()
            }

            // Optional: mesh shader + present features (Windows path).
            if (enableMeshShading) {
                val mesh = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                    .meshShader(true).taskShader(true).multiviewMeshShader(supportsMultiview)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, mesh.address()); tail = mesh.address()
            }

            if (usePresentIndexing) {
                val presentId = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack)
                    .sType(KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR)
                    .presentId(true)

                val presentWait = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack)
                    .sType(KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR)
                    .presentWait(true)

                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentId.address()); tail = presentId.address()
                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentWait.address()); tail = presentWait.address()
            }

            // VK_EXT_present_timing feature enable — never on macOS/MoltenVK.
            if (usesPresentTiming) {
                val presentTiming = VkPhysicalDevicePresentTimingFeaturesEXT.calloc(stack)
                    .`sType$Default`()
                    .presentTiming(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentTiming.address()); tail = presentTiming.address()

                val presentId2 = VkPhysicalDevicePresentId2FeaturesKHR.calloc(stack)
                    .`sType$Default`()
                    .presentId2(true)
                memPutAddress(tail + VkBaseOutStructure.PNEXT, presentId2.address()); tail = presentId2.address()
            }

            if (supportsAccelerationStructures) {
                val accelerationStructure = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                    .accelerationStructure(true)

                val rayTracingPipeline = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                    .sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                    .rayTracingPipeline(true)

                memPutAddress(tail + VkBaseOutStructure.PNEXT, accelerationStructure.address()); tail =
                    accelerationStructure.address()
                if (supportsRayTracing) {
                    memPutAddress(tail + VkBaseOutStructure.PNEXT, rayTracingPipeline.address())
                    tail = rayTracingPipeline.address()
                }

                if (supportsRayQuery) {
                    val rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                        .sType(KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR)
                        .rayQuery(true)
                    memPutAddress(tail + VkBaseOutStructure.PNEXT, rayQuery.address()); tail = rayQuery.address()
                }
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
                props2.pNext(pushProps.address())
                vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice, props2)
                pushProps.maxPushDescriptors()
            } else 0
            lprintln(
                "info,device",
                "Device push descriptors: supported=$supportsPushDescriptors, maxPushDescriptors=$pushDescriptorMax"
            )
            lprintln(
                "info,device",
                "Device ray tracing: pipeline=$supportsRayTracing, rayQuery=$supportsRayQuery, limits=$rayTracingLimits"
            )
            lprintln(
                "info,device",
                "Device maintenance: m7=$supportsMaintenance7, m11=$supportsMaintenance11, dynamicRenderingUnusedAttachments=$supportsDynamicRenderingUnusedAttachments"
            )
            lprintln(
                "info,device",
                "Device present timing: usesPresentTiming=$usesPresentTiming"
            )
        }
    }

    fun waitIdle() {
        vkDeviceWaitIdle(vkDevice)
    }

    /**
     * False once the owning Renderer's teardown has begun ([closeSubmissions]); [Queue.submit]
     * then refuses by name instead of queuing work against buffers that are about to be freed.
     */
    @Volatile
    var acceptingSubmissions = true
        private set

    /** Refuse every later [Queue.submit]; taken under [Queue.globalQueueLock] so none is mid-flight. */
    fun closeSubmissions() {
        synchronized(Queue.globalQueueLock) { acceptingSubmissions = false }
    }

    fun cleanup() {
        if (ngxDelegate.isInitialized()) ngxRuntime.getOrNull()?.close()
        vkDestroyDevice(vkDevice, null)
    }

    /** Fill the feature struct at [address] (its sType set, pNext 0) with what the device supports. */
    private fun probeFeatures(stack: MemoryStack, address: Long) {
        VK11.vkGetPhysicalDeviceFeatures2(
            physicalDevice.vkPhysicalDevice,
            VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`().pNext(address)
        )
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

    private fun queryRayTracingLimits(stack: MemoryStack): RayTracingLimits {
        val props2 = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
        val rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
            .sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR)
        val asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
            .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR)

        props2.pNext(asProps.address())
        if (raySupport.pipelineExtension) asProps.pNext(rtProps.address())
        VK11.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice, props2)

        return RayTracingLimits(
            shaderGroupHandleSize = rtProps.shaderGroupHandleSize(),
            shaderGroupHandleAlignment = rtProps.shaderGroupHandleAlignment(),
            shaderGroupBaseAlignment = rtProps.shaderGroupBaseAlignment(),
            maxRayRecursionDepth = rtProps.maxRayRecursionDepth(),
            maxRayDispatchInvocationCount = rtProps.maxRayDispatchInvocationCount(),
            maxRayHitAttributeSize = rtProps.maxRayHitAttributeSize(),
            minAccelerationStructureScratchOffsetAlignment = asProps.minAccelerationStructureScratchOffsetAlignment(),
            maxGeometryCount = asProps.maxGeometryCount(),
            maxInstanceCount = asProps.maxInstanceCount(),
            maxPrimitiveCount = asProps.maxPrimitiveCount(),
            maxPerStageDescriptorAccelerationStructures = asProps.maxPerStageDescriptorAccelerationStructures(),
            maxDescriptorSetAccelerationStructures = asProps.maxDescriptorSetAccelerationStructures()
        )
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
                    VK_TIME_DOMAIN_DEVICE_EXT -> hasDevice = true
                    VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT -> hasMono = true
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
            val infos = VkCalibratedTimestampInfoEXT.calloc(2, stack)
            infos[0]
                .sType(EXTCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT)
                .timeDomain(VK_TIME_DOMAIN_DEVICE_EXT)
            infos[1]
                .sType(EXTCalibratedTimestamps.VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT)
                .timeDomain(VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT)

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

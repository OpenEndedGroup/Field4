package afield.graphics.vulkan.device

/**
 * What the GPU supports for hardware ray tracing: [RayDeviceSupport] lists the acceleration-structure, ray-query and ray-tracing-pipeline extensions and features, and derives whether each path is usable.
 *
 * [lightingCapabilityReport] turns that into a JSON report for a [Device].
 */

import org.json.JSONObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*

/** Physical support, separate from Device's enabled features. Field already requires Vulkan 1.3,
 * which supplies buffer-device-address, descriptor-indexing, SPIR-V 1.4 and float-control dependencies. */
data class RayDeviceSupport(
    /** `VK_KHR_acceleration_structure` is in the device's extension list. */
    val accelerationExtension: Boolean,
    /** `VK_KHR_deferred_host_operations` is present, which acceleration-structure builds depend on. */
    val deferredHostOperations: Boolean,
    /** `VK_KHR_ray_query` is in the device's extension list. */
    val queryExtension: Boolean,
    /** `VK_KHR_ray_tracing_pipeline` is in the device's extension list. */
    val pipelineExtension: Boolean,
    /** The `bufferDeviceAddress` feature, which shader-side acceleration-structure access needs. */
    val bufferDeviceAddress: Boolean,
    /**
     * The `accelerationStructure` feature. Only queried when [accelerationExtension] holds, so a
     * device missing the extension reports this false as well.
     */
    val accelerationStructure: Boolean,
    /**
     * The `rayQuery` feature — tracing from an ordinary compute or fragment shader. Only queried
     * when [queryExtension] holds.
     */
    val rayQuery: Boolean,
    /**
     * The `rayTracingPipeline` feature — raygen, hit and miss shader stages. Only queried when
     * [pipelineExtension] holds.
     */
    val rayTracingPipeline: Boolean
) {
    val accelerationUsable get() = accelerationExtension && deferredHostOperations && bufferDeviceAddress && accelerationStructure
    val queryUsable get() = accelerationUsable && queryExtension && rayQuery
    val pipelineUsable get() = accelerationUsable && pipelineExtension && rayTracingPipeline

    companion object {
        fun probe(device: VkPhysicalDevice, extensions: Set<String>): RayDeviceSupport = MemoryStack.stackPush().use { stack ->
            val asExt = KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME in extensions
            val queryExt = KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME in extensions
            val pipelineExt = KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME in extensions
            val address = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).`sType$Default`()
            val accel = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).`sType$Default`()
            val query = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).`sType$Default`()
            val pipeline = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).`sType$Default`()
            val features = VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`().pNext(address.address())
            var tail = address.address()
            for (next in listOfNotNull(accel.takeIf { asExt }?.address(), query.takeIf { queryExt }?.address(), pipeline.takeIf { pipelineExt }?.address())) {
                org.lwjgl.system.MemoryUtil.memPutAddress(tail + VkBaseOutStructure.PNEXT, next)
                tail = next
            }
            VK11.vkGetPhysicalDeviceFeatures2(device, features)
            RayDeviceSupport(asExt, KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME in extensions,
                queryExt, pipelineExt, address.bufferDeviceAddress(), accel.accelerationStructure(), query.rayQuery(), pipeline.rayTracingPipeline())
        }
    }
}

/** JSON snapshot from the actual selected physical/logical device; safe on non-ray-query devices. */
fun Device.lightingCapabilityReport(): JSONObject = MemoryStack.stackPush().use { stack ->
    val driver = VkPhysicalDeviceDriverProperties.calloc(stack).`sType$Default`()
    val props = VkPhysicalDeviceProperties2.calloc(stack).`sType$Default`().pNext(driver.address())
    VK11.vkGetPhysicalDeviceProperties2(physicalDevice.vkPhysicalDevice, props)
    val p = props.properties()
    val format = VkFormatProperties.calloc(stack)
    VK10.vkGetPhysicalDeviceFormatProperties(physicalDevice.vkPhysicalDevice, VK10.VK_FORMAT_R32G32B32_SFLOAT, format)
    JSONObject().put("device", p.deviceNameString()).put("driver", driver.driverNameString())
        .put("driverInfo", driver.driverInfoString()).put("apiVersion", "${VK10.VK_VERSION_MAJOR(p.apiVersion())}.${VK10.VK_VERSION_MINOR(p.apiVersion())}.${VK10.VK_VERSION_PATCH(p.apiVersion())}")
        .put("java", System.getProperty("java.runtime.version"))
        .put("supported", JSONObject().put("accelerationStructure", raySupport.accelerationStructure)
            .put("rayQuery", raySupport.rayQuery).put("rayTracingPipeline", raySupport.rayTracingPipeline)
            .put("bufferDeviceAddress", raySupport.bufferDeviceAddress).put("deferredHostOperations", raySupport.deferredHostOperations))
        .put("enabled", JSONObject().put("accelerationStructure", supportsAccelerationStructures)
            .put("rayQuery", supportsRayQuery).put("rayTracingPipeline", supportsRayTracing)
            .put("bufferDeviceAddress", true).put("extensions", enabledExtensions.sorted()))
        .put("limits", rayTracingLimits?.let { JSONObject().put("scratchAlignment", it.minAccelerationStructureScratchOffsetAlignment)
            .put("maxGeometryCount", it.maxGeometryCount).put("maxInstanceCount", it.maxInstanceCount)
            .put("maxPrimitiveCount", it.maxPrimitiveCount).put("maxPerStageAccelerationStructures", it.maxPerStageDescriptorAccelerationStructures)
            .put("maxDescriptorSetAccelerationStructures", it.maxDescriptorSetAccelerationStructures) } ?: JSONObject.NULL)
        .put("rgb32fAccelerationVertices", format.bufferFeatures() and KHRAccelerationStructure.VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR != 0)
        .put("maxStorageBufferRange", p.limits().maxStorageBufferRange().toUInt().toLong()).put("maxPushConstantsSize", p.limits().maxPushConstantsSize())
        .put("timestampPeriodNs", p.limits().timestampPeriod())
        .put("queueTimestampValidBits", (0 until physicalDevice.vkQueueFamilyProps.capacity()).map { physicalDevice.vkQueueFamilyProps[it].timestampValidBits() })
}

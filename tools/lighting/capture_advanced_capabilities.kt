import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.json.JSONObject
import java.io.File

MemoryStack.stackPush().use { stack ->
    val physical=renderer.device.physicalDevice
    val cluster=VkPhysicalDeviceClusterAccelerationStructureFeaturesNV.calloc(stack).`sType$Default`()
    val vectors=VkPhysicalDeviceCooperativeVectorFeaturesNV.calloc(stack).`sType$Default`()
    cluster.pNext(vectors.address())
    VK11.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice,
        VkPhysicalDeviceFeatures2.calloc(stack).`sType$Default`().pNext(cluster.address()))
    val props=physical.vkPhysicalDeviceProperties
    val queues=physical.vkQueueFamilyProps
    val report=JSONObject().put("gpu",physical.deviceName).put("java",System.getProperty("java.runtime.version"))
        .put("os",System.getProperty("os.name")).put("vulkanApiEncoded",props.apiVersion()).put("driverEncoded",props.driverVersion())
        .put("cluster",JSONObject().put("supported",cluster.clusterAccelerationStructure()).put("enabled",renderer.device.supportsClusterAccelerationStructure))
        .put("cooperativeVectors",JSONObject().put("supported",vectors.cooperativeVector()).put("enabled",renderer.device.supportsCooperativeVector))
        .put("cooperativeTraining",JSONObject().put("supported",vectors.cooperativeVectorTraining()).put("enabled",renderer.device.supportsCooperativeVectorTraining))
        .put("rayQueryEnabled",renderer.device.supportsRayQuery).put("rayPipelineEnabled",renderer.device.supportsRayTracing)
        .put("queues",(0 until queues.limit()).map { i -> JSONObject().put("family",i).put("flags",queues[i].queueFlags()).put("count",queues[i].queueCount()) })
        .put("toolchains",JSONObject(File("optional/lighting/toolchains.json").readText()))
    File("build/neural-tools/advanced-capabilities.json").writeText(report.toString(2)+"\n")
    println("ADVANCED CAPABILITY CHECK DONE")
}

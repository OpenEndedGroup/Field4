package afield.four.device

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.util.vkCheck

class PhysicalDevice private constructor(vkPhysicalDevice: VkPhysicalDevice) {
    private var vkDeviceExtensions: VkExtensionProperties.Buffer
    var vkMemoryProperties: VkPhysicalDeviceMemoryProperties
    var vkPhysicalDevice: VkPhysicalDevice
    var vkPhysicalDeviceFeatures: VkPhysicalDeviceFeatures
    var vkPhysicalDeviceProperties: VkPhysicalDeviceProperties
    var vkQueueFamilyProps: VkQueueFamilyProperties.Buffer

    init {
        MemoryStack.stackPush().use { stack ->
            this.vkPhysicalDevice = vkPhysicalDevice
            val intBuffer = stack.mallocInt(1)

            // Get device properties
            vkPhysicalDeviceProperties = VkPhysicalDeviceProperties.calloc()
            VK10.vkGetPhysicalDeviceProperties(vkPhysicalDevice, vkPhysicalDeviceProperties)

            // Get device extensions
            vkCheck(
                VK10.vkEnumerateDeviceExtensionProperties(vkPhysicalDevice, null as String?, intBuffer, null),
                "Failed to get number of device extension properties"
            )
            vkDeviceExtensions = VkExtensionProperties.calloc(intBuffer[0])
            vkCheck(
                VK10.vkEnumerateDeviceExtensionProperties(
                    vkPhysicalDevice,
                    null as String?,
                    intBuffer,
                    vkDeviceExtensions
                ),
                "Failed to get extension properties"
            )

            // Get Queue family properties
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, intBuffer, null)
            vkQueueFamilyProps = VkQueueFamilyProperties.calloc(intBuffer[0])
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDevice, intBuffer, vkQueueFamilyProps)

            vkPhysicalDeviceFeatures = VkPhysicalDeviceFeatures.calloc()
            VK10.vkGetPhysicalDeviceFeatures(vkPhysicalDevice, vkPhysicalDeviceFeatures)


            // Get Memory information and properties
            vkMemoryProperties = VkPhysicalDeviceMemoryProperties.calloc()
            VK10.vkGetPhysicalDeviceMemoryProperties(vkPhysicalDevice, vkMemoryProperties)
        }
    }

    fun cleanup() {
        vkMemoryProperties.free()
        vkPhysicalDeviceFeatures.free()
        vkQueueFamilyProps.free()
        vkDeviceExtensions.free()
        vkPhysicalDeviceProperties.free()
    }

    val deviceName: String
        get() = vkPhysicalDeviceProperties.deviceNameString()

    private fun hasGraphicsQueueFamily(): Boolean {
        var result = false
        val numQueueFamilies = vkQueueFamilyProps.capacity()
        for (i in 0 until numQueueFamilies) {
            val familyProps = vkQueueFamilyProps[i]
            if ((familyProps.queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
                result = true
                break
            }
        }
        return result
    }

    private fun hasKHRSwapChainExtension(): Boolean {
        var result = false
        val numExtensions = vkDeviceExtensions.capacity()
        for (i in 0 until numExtensions) {
            val extensionName = vkDeviceExtensions[i].extensionNameString()
            if (KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME == extensionName) {
                result = true
                break
            }
        }
        return result
    }

    companion object {
        fun createPhysicalDevice(instance: Instance, prefferredDeviceName: String?): PhysicalDevice {
            var selectedPhysicalDevice: PhysicalDevice? = null
            MemoryStack.stackPush().use { stack ->
                // Get available devices
                val pPhysicalDevices = getPhysicalDevices(instance, stack)
                val numDevices = pPhysicalDevices.capacity()
                if (numDevices <= 0) {
                    throw RuntimeException("No physical devices found")
                }

                // Populate available devices
                val devices: MutableList<PhysicalDevice> = ArrayList()
                for (i in 0 until numDevices) {
                    val vkPhysicalDevice = VkPhysicalDevice(pPhysicalDevices[i], instance.vkInstance)
                    val physicalDevice = PhysicalDevice(vkPhysicalDevice)

                    val deviceName = physicalDevice.deviceName
                    if (physicalDevice.hasGraphicsQueueFamily() && physicalDevice.hasKHRSwapChainExtension()) {
                        if (prefferredDeviceName != null && prefferredDeviceName == deviceName) {
                            selectedPhysicalDevice = physicalDevice
                            break
                        }
                        devices.add(physicalDevice)
                    } else {
                        println("ERROR: Device $deviceName does not support required extensions")
                        physicalDevice.cleanup()
                    }
                }

                // No preferred device or it does not meet requirements, just pick the first one
                selectedPhysicalDevice =
                    if (selectedPhysicalDevice == null && !devices.isEmpty()) devices.removeAt(0) else selectedPhysicalDevice

                // Clean up non-selected devices
                for (physicalDevice in devices) {
                    physicalDevice.cleanup()
                }

                if (selectedPhysicalDevice == null) {
                    throw RuntimeException("No suitable physical devices found")
                }
            }
            return selectedPhysicalDevice ?: throw IllegalStateException(" no device ")
        }

        protected fun getPhysicalDevices(instance: Instance, stack: MemoryStack): PointerBuffer {
            val pPhysicalDevices: PointerBuffer
            // Get number of physical devices
            val intBuffer = stack.mallocInt(1)
            vkCheck(
                VK10.vkEnumeratePhysicalDevices(instance.vkInstance, intBuffer, null),
                "Failed to get number of physical devices"
            )
            val numDevices = intBuffer[0]
            println("Detected $numDevices physical device(s)")

            // Populate physical devices list pointer
            pPhysicalDevices = stack.mallocPointer(numDevices)
            vkCheck(
                VK10.vkEnumeratePhysicalDevices(instance.vkInstance, intBuffer, pPhysicalDevices),
                "Failed to get physical devices"
            )
            return pPhysicalDevices
        }
    }

    fun hasDeviceExtension(name: String): Boolean {
        val count = vkDeviceExtensions.capacity()
        for (i in 0 until count) {
            if (vkDeviceExtensions[i].extensionNameString() == name) return true
        }
        return false
    }

    fun requireExtensionsIfProfiling(profilingEnabled: Boolean): Boolean {
        if (!profilingEnabled) return true
        // swapchain already required by your code
        val perfOK = hasDeviceExtension(KHRPerformanceQuery.VK_KHR_PERFORMANCE_QUERY_EXTENSION_NAME)
        val hostResetOK = hasDeviceExtension(EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME) // optional
        if (!perfOK) {
            println("ERROR: Profiling requested, but device '${deviceName}' lacks VK_KHR_performance_query")
            return false
        }
        // Host query reset is optional; just report:
        if (!hostResetOK) {
            println("WARN: No VK_EXT_host_query_reset; will use cmd-side resets or avoid reuse.")
        }
        return true
    }


}

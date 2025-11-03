package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.vkCheck
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT
import org.lwjgl.vulkan.EXTLayerSettings.VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT

class Instance(validate: Boolean) {
    var vkInstance: VkInstance

    private var debugUtils: VkDebugUtilsMessengerCreateInfoEXT? = null
    private var vkDebugHandle: Long = 0


    init {
        MemoryStack.stackPush().use { stack ->
            // Create application information
            val appShortName = stack.UTF8("VulkanBook")
            val appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(appShortName)
                .applicationVersion(1)
                .pEngineName(appShortName)
                .engineVersion(0)
                .apiVersion(VK13.VK_API_VERSION_1_3)

            // Validation layers
            val validationLayers = supportedValidationLayers
            val numValidationLayers = validationLayers.size
            var supportsValidation = validate
            if (validate && numValidationLayers == 0) {
                supportsValidation = false
                println(" Requested validation, but no supported layers found")
//                Logger.warn("Request validation but no supported validation layers found. Falling back to no validation")
            }

            // Set required  layers
            var requiredLayers: PointerBuffer? = null
            if (supportsValidation) {
                requiredLayers = stack.mallocPointer(numValidationLayers)
                for (i in 0 until numValidationLayers) {
                    requiredLayers.put(i, stack.ASCII(validationLayers[i]))
                }
            }

            val instanceExtensions = instanceExtensions

            // GLFW Extension
            val glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions()
                ?: throw RuntimeException("Failed to find the GLFW platform surface extensions")

            val requiredExtensions: PointerBuffer

            val usePortability = instanceExtensions.contains(PORTABILITY_EXTENSION) &&
                    OS == OSType.MACOS
            if (supportsValidation) {
                val vkDebugUtilsExtension = stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
                val numExtensions =
                    if (usePortability) glfwExtensions.remaining() + 2 else glfwExtensions.remaining() + 1
                requiredExtensions = stack.mallocPointer(numExtensions)
                requiredExtensions.put(glfwExtensions).put(vkDebugUtilsExtension)
                if (usePortability) {
                    requiredExtensions.put(stack.UTF8(PORTABILITY_EXTENSION))
                }
            } else {
                val numExtensions = if (usePortability) glfwExtensions.remaining() + 1 else glfwExtensions.remaining()
                requiredExtensions = stack.mallocPointer(numExtensions)
                requiredExtensions.put(glfwExtensions)
                if (usePortability) {
                    requiredExtensions.put(stack.UTF8(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME))
                }
            }
            requiredExtensions.flip()

            var extension = MemoryUtil.NULL
            if (supportsValidation) {
                debugUtils = createDebugCallBack()
                extension = debugUtils?.address() ?: 0
            }

            val settings = VkLayerSettingEXT.calloc(1)
            settings.get(0).pLayerName(stack.UTF8("VK_LAYER_KHRONOS_validation"))
                .pSettingName(stack.UTF8("enable_message_limit")).type(VK_LAYER_SETTING_TYPE_BOOL32_EXT).valueCount(1)
                .pValues(stack.bytes(0, 0, 0, 0))


            val layerSettings =
                VkLayerSettingsCreateInfoEXT.calloc(stack).sType(VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT)
                    .pSettings(settings)

            layerSettings.pNext(extension)

            // Create instance info
            val instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(layerSettings)
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(requiredLayers)
                .ppEnabledExtensionNames(requiredExtensions)

            if (usePortability) {
                instanceInfo.flags(0x00000001) // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
            }

            val pInstance = stack.mallocPointer(1)
            vkCheck(VK10.vkCreateInstance(instanceInfo, null, pInstance), "Error creating instance")
            vkInstance = VkInstance(pInstance[0], instanceInfo)

            vkDebugHandle = VK10.VK_NULL_HANDLE
            if (supportsValidation) {
                val longBuff = stack.mallocLong(1)
                vkCheck(
                    EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                        vkInstance,
                        debugUtils!!,
                        null,
                        longBuff
                    ), "Error creating debug utils"
                )
                vkDebugHandle = longBuff[0]
            }
        }
    }

    fun cleanup() {
        if (vkDebugHandle != VK10.VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vkInstance, vkDebugHandle, null)
        }
        VK10.vkDestroyInstance(vkInstance, null)
        if (debugUtils != null) {
            debugUtils?.pfnUserCallback()?.free()
            debugUtils?.free()
        }
    }

    private val instanceExtensions: Set<String>
        get() {
            val instanceExtensions: MutableSet<String> = HashSet()
            MemoryStack.stackPush().use { stack ->
                val numExtensionsBuf = stack.callocInt(1)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, numExtensionsBuf, null)
                val numExtensions = numExtensionsBuf[0]

                val instanceExtensionsProps = VkExtensionProperties.calloc(numExtensions, stack)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, numExtensionsBuf, instanceExtensionsProps)
                for (i in 0 until numExtensions) {
                    val props = instanceExtensionsProps[i]
                    val extensionName = props.extensionNameString()
                    instanceExtensions.add(extensionName)
                }
            }
            return instanceExtensions
        }

    private val supportedValidationLayers: List<String>
        get() {
            MemoryStack.stackPush().use { stack ->
                val numLayersArr = stack.callocInt(1)
                VK10.vkEnumerateInstanceLayerProperties(numLayersArr, null)
                val numLayers = numLayersArr[0]

                val propsBuf = VkLayerProperties.calloc(numLayers, stack)
                VK10.vkEnumerateInstanceLayerProperties(numLayersArr, propsBuf)
                val supportedLayers: MutableList<String> = ArrayList()
                for (i in 0 until numLayers) {
                    val props = propsBuf[i]
                    val layerName = props.layerNameString()
                    supportedLayers.add(layerName)
                }

                val layersToUse: MutableList<String> = ArrayList()

                // Main validation layer
                if (supportedLayers.contains("VK_LAYER_KHRONOS_validation")) {
                    layersToUse.add("VK_LAYER_KHRONOS_validation")
                    return layersToUse
                }

                // Fallback 1
                if (supportedLayers.contains("VK_LAYER_LUNARG_standard_validation")) {
                    layersToUse.add("VK_LAYER_LUNARG_standard_validation")
                    return layersToUse
                }

                // Fallback 2 (set)
                val requestedLayers: MutableList<String> = ArrayList()
                requestedLayers.add("VK_LAYER_GOOGLE_threading")
                requestedLayers.add("VK_LAYER_LUNARG_parameter_validation")
                requestedLayers.add("VK_LAYER_LUNARG_object_tracker")
                requestedLayers.add("VK_LAYER_LUNARG_core_validation")
                requestedLayers.add("VK_LAYER_GOOGLE_unique_objects")
                return requestedLayers.stream().filter { o: String -> supportedLayers.contains(o) }.toList()
            }
        }

    companion object {
        const val MESSAGE_SEVERITY_BITMASK: Int = EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or
                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
        const val MESSAGE_TYPE_BITMASK: Int = EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
        private const val PORTABILITY_EXTENSION = "VK_KHR_portability_enumeration"

        var globalAsyncDebugHandler: ((String) -> Unit)? = null

        private fun createDebugCallBack(): VkDebugUtilsMessengerCreateInfoEXT {
            return VkDebugUtilsMessengerCreateInfoEXT
                .calloc()
                .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(MESSAGE_SEVERITY_BITMASK)
                .messageType(MESSAGE_TYPE_BITMASK)
                .pfnUserCallback { messageSeverity: Int, _: Int, pCallbackData: Long, _: Long ->
                    val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
                    if ((messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) != 0) {
                        println("INFO: VkDebugUtilsCallback, ${callbackData.pMessageString()}")
                    } else if ((messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                        println("WARNING: VkDebugUtilsCallback, ${callbackData.pMessageString()}")
                    } else if ((messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        println("ERROR: VkDebugUtilsCallback, ${callbackData.pMessageString()}")
                    } else {
                        println("VkDebugUtilsCallback, ${callbackData.pMessageString()}")
                    }

                    globalAsyncDebugHandler?.let {
                        it(callbackData.pMessageString()!!)
                    }
                    VK10.VK_FALSE
                }
        }
    }
}

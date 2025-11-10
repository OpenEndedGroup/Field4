package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.openvr.OpenVR.VRCompositor
import org.lwjgl.openvr.VRCompositor.VRCompositor_GetVulkanInstanceExtensionsRequired
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT
import org.lwjgl.vulkan.EXTLayerSettings.VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT
import org.lwjgl.system.MemoryUtil.memPutAddress
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.VkBaseOutStructure
import org.lwjgl.vulkan.EXTCalibratedTimestamps
import org.lwjgl.vulkan.EXTToolingInfo.*
import org.lwjgl.vulkan.KHRCalibratedTimestamps // for KHR constants (domains)
import org.lwjgl.vulkan.VK10.VK_INCOMPLETE
import org.lwjgl.vulkan.VK10.VK_SUCCESS

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

// --- Discover supported instance extensions
            val supported = instanceExtensions

// --- GLFW-required instance extensions
            val glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions()
                ?: throw RuntimeException("Failed to find the GLFW platform surface extensions. This only happens if something is seriously wrong with Vulkan on your machine, or you are launching this graphics system in an environment that has impossible requests of the Vulkan API (like asking for MoltenVK on Linux).")

            val usePortability = OS == OSType.MACOS // save us KosmicKrisp
            val hasDebugUtils = supported.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
            val hasLayerSettings = supported.contains("VK_EXT_layer_settings")

// --- Build list dynamically
            val extNames = ArrayList<String>(glfwExtensions.remaining() + 4)

// 1) Always add GLFW surface extensions
            while (glfwExtensions.hasRemaining()) {
                extNames += MemoryUtil.memUTF8(glfwExtensions.get())
            }

// 2) Debug utils if validating AND supported
            if (supportsValidation && hasDebugUtils) {
                extNames += EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME
            }

// 3) Portability (MoltenVK/macOS)
            if (usePortability) {
                extNames += PORTABILITY_EXTENSION // VK_KHR_portability_enumeration
            }

// 4) Layer settings only if supported AND validating (since we’d attach its pNext)
            if (supportsValidation && hasLayerSettings) {
                extNames += "VK_EXT_layer_settings"
            }
            if (Options.enableVR) {
                extNames += VRCompositor_GetVulkanInstanceExtensionsRequired(4096)
                    .split(' ').filter { it.isNotBlank() }
            }

// Convert to LWJGL pointer buffer
            val requiredExtensions = stack.mallocPointer(extNames.size)
            for (name in extNames) requiredExtensions.put(stack.ASCII(name))
            requiredExtensions.flip()

// --- Optional pNext chain pieces
            var pNextHead: Long = MemoryUtil.NULL

// Debug utils create-info only if extension present AND validating
            if (supportsValidation && hasDebugUtils) {
                debugUtils = createDebugCallBack()
                pNextHead = debugUtils!!.address()
            }


// Layer settings only if extension present AND validating
            if (supportsValidation && hasLayerSettings) {
                val settings = VkLayerSettingEXT.calloc(1, stack)
                settings[0]
                    .pLayerName(stack.UTF8("VK_LAYER_KHRONOS_validation"))
                    .pSettingName(stack.UTF8("enable_message_limit"))
                    .type(VK_LAYER_SETTING_TYPE_BOOL32_EXT)
                    .valueCount(1)
                    .pValues(stack.bytes(0, 0, 0, 0))

                val layerSettings = VkLayerSettingsCreateInfoEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT)
                    .pSettings(settings)
                    .pNext(pNextHead)

                pNextHead = layerSettings.address()
            }

// --- Create instance info
            val instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(pNextHead) // may be 0, or layer_settings -> debug_utils, or just debug_utils
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(requiredLayers)
                .ppEnabledExtensionNames(requiredExtensions)

            if (usePortability) {
                instanceInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
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


    fun dumpToolingInfo(instance: VkInstance, phys: VkPhysicalDevice, deviceOrNull: VkDevice? = null) {
        MemoryStack.stackPush().use { stack ->
            fun purposesToString(bits: Int): String {
                val out = ArrayList<String>(7)
                if ((bits and VK_TOOL_PURPOSE_VALIDATION_BIT_EXT) != 0) out += "validation"
                if ((bits and VK_TOOL_PURPOSE_PROFILING_BIT_EXT) != 0) out += "profiling"
                if ((bits and VK_TOOL_PURPOSE_TRACING_BIT_EXT) != 0) out += "tracing"
                if ((bits and VK_TOOL_PURPOSE_ADDITIONAL_FEATURES_BIT_EXT) != 0) out += "adds_features"
                if ((bits and VK_TOOL_PURPOSE_MODIFYING_FEATURES_BIT_EXT) != 0) out += "modifies_features"
                if ((bits and VK_TOOL_PURPOSE_DEBUG_REPORTING_BIT_EXT) != 0) out += "debug_reporting"
                if ((bits and VK_TOOL_PURPOSE_DEBUG_MARKERS_BIT_EXT) != 0) out += "debug_markers"
                return if (out.isEmpty()) "none" else out.joinToString("|")
            }

            // --- Physical-device query (instance-level extension) ---
            if (instance.capabilities.vkGetPhysicalDeviceToolPropertiesEXT != 0L) {
                val pCount = stack.mallocInt(1)
                var res = EXTToolingInfo.vkGetPhysicalDeviceToolPropertiesEXT(
                    phys, pCount, null as VkPhysicalDeviceToolPropertiesEXT.Buffer?
                )
                if (res == VK_SUCCESS || res == VK_INCOMPLETE) {
                    val count = pCount[0]
                    if (count > 0) {
                        val props = VkPhysicalDeviceToolPropertiesEXT.calloc(count, stack)
                        res = EXTToolingInfo.vkGetPhysicalDeviceToolPropertiesEXT(phys, pCount, props)
                        if (res == VK_SUCCESS) {
                            println("== Tooling (physical device) ==")
                            for (i in 0 until count) {
                                val t = props[i]
                                println(" - ${t.nameString()} ${t.versionString()}")
                                println("    layer='${t.layerString() ?: ""}'")
                                println("    desc='${t.descriptionString() ?: ""}'")
                                println("    purposes=${purposesToString(t.purposes())}")
                            }
                        }
                    }
                }
            }
        }
    }

}

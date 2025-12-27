package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.openxr.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils
import org.lwjgl.vulkan.EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT
import org.lwjgl.vulkan.EXTLayerSettings.VK_STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO_EXT
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.VK10.VK_INCOMPLETE
import org.lwjgl.vulkan.VK10.VK_SUCCESS
import java.nio.ByteBuffer
import java.nio.IntBuffer

class Instance(validate: Boolean) {

    var vkInstance: VkInstance
        private set

    private var debugUtilsCreateInfo: VkDebugUtilsMessengerCreateInfoEXT? = null
    private var vkDebugHandle: Long = 0

    init {
        MemoryStack.stackPush().use { stack ->
            // --- Application info
            val appShortName = stack.UTF8("VulkanBook")
            val appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(appShortName)
                .applicationVersion(1)
                .pEngineName(appShortName)
                .engineVersion(0)
                .apiVersion(VK13.VK_API_VERSION_1_3)

            // --- Layers
            val validationLayers = supportedValidationLayers
            var supportsValidation = validate && validationLayers.isNotEmpty()
            if (validate && !supportsValidation) {
                println("Requested validation, but no supported layers found")
            }

            var requiredLayers: PointerBuffer? = null
            if (supportsValidation) {
                requiredLayers = stack.mallocPointer(validationLayers.size)
                validationLayers.forEachIndexed { i, name -> requiredLayers.put(i, stack.ASCII(name)) }
            }

            // --- Discover supported instance extensions
            val supportedExts = instanceExtensions

            // --- GLFW-required instance extensions
            val glfwExts = GLFWVulkan.glfwGetRequiredInstanceExtensions()
                ?: throw RuntimeException(
                    "Failed to find GLFW platform surface extensions. " +
                            "Either Vulkan is not set up or this platform configuration is unsupported."
                )

            val usePortability = (OS == OSType.MACOS)
            val hasDebugUtils = EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME in supportedExts
            val hasLayerSettings = "VK_EXT_layer_settings" in supportedExts
            val hasValidationFeatures =
                EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME in supportedExts

            // --- Build extension list
            val extNames = ArrayList<String>(glfwExts.remaining() + 6).apply {
                while (glfwExts.hasRemaining()) add(MemoryUtil.memUTF8(glfwExts.get()))
                if (supportsValidation && hasDebugUtils) add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
                if (supportsValidation && hasValidationFeatures) add(EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME)
                if (supportsValidation && hasLayerSettings) add("VK_EXT_layer_settings")
                if (usePortability) add(PORTABILITY_EXTENSION) // VK_KHR_portability_enumeration
                if (Options.enableVR) addAll(getOpenXRRequiredInstanceExts())
            }

            val requiredExtensions = stack.mallocPointer(extNames.size).apply {
                extNames.forEach { put(stack.ASCII(it)) }
                flip()
            }

            // --- Optional pNext chain
            var pNextHead: Long = MemoryUtil.NULL

            // Debug utils callback create-info
            if (supportsValidation && hasDebugUtils) {
                debugUtilsCreateInfo = createDebugCallBack()
                pNextHead = debugUtilsCreateInfo!!.address()
            }

            // Validation features (enable shader printf)
            if (supportsValidation && hasValidationFeatures) {
                val enables = stack.ints(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT)
                val vfeat = VkValidationFeaturesEXT.calloc(stack)
                    .sType(EXTValidationFeatures.VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT)
                    .pEnabledValidationFeatures(enables)
                    .pNext(pNextHead)
                pNextHead = vfeat.address()
            }

            // Layer settings (optional)
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

            // --- Instance create info
            val instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pNext(pNextHead)
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(requiredLayers)
                .ppEnabledExtensionNames(requiredExtensions)

            if (usePortability) {
                instanceInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
            }

            val pInstance = stack.mallocPointer(1)
            vkCheck(VK10.vkCreateInstance(instanceInfo, null, pInstance), "Error creating instance")
            vkInstance = VkInstance(pInstance[0], instanceInfo)

            // --- Debug messenger (if validation on)
            vkDebugHandle = VK10.VK_NULL_HANDLE
            if (supportsValidation && hasDebugUtils) {
                val p = stack.mallocLong(1)
                vkCheck(
                    EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                        vkInstance, debugUtilsCreateInfo!!, null, p
                    ),
                    "Error creating debug utils messenger"
                )
                vkDebugHandle = p[0]
            }
        }
    }

    fun cleanup() {
        if (vkDebugHandle != VK10.VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vkInstance, vkDebugHandle, null)
        }
        VK10.vkDestroyInstance(vkInstance, null)
        debugUtilsCreateInfo?.pfnUserCallback()?.free()
        debugUtilsCreateInfo?.free()
    }

    // --- Helpers -------------------------------------------------------------

    private val instanceExtensions: Set<String>
        get() {
            val out = HashSet<String>()
            MemoryStack.stackPush().use { stack ->
                val count = stack.callocInt(1)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, count, null)
                val props = VkExtensionProperties.calloc(count[0], stack)
                VK10.vkEnumerateInstanceExtensionProperties(null as String?, count, props)
                repeat(props.capacity()) { i -> out += props[i].extensionNameString() }
            }
            return out
        }

    private val supportedValidationLayers: List<String>
        get() {
            MemoryStack.stackPush().use { stack ->
                val count = stack.callocInt(1)
                VK10.vkEnumerateInstanceLayerProperties(count, null)
                val props = VkLayerProperties.calloc(count[0], stack)
                VK10.vkEnumerateInstanceLayerProperties(count, props)
                val supported =
                    buildList(props.capacity()) { repeat(props.capacity()) { i -> add(props[i].layerNameString()) } }

                return when {
                    "VK_LAYER_KHRONOS_validation" in supported -> listOf("VK_LAYER_KHRONOS_validation")
                    "VK_LAYER_LUNARG_standard_validation" in supported -> listOf("VK_LAYER_LUNARG_standard_validation")
                    else -> listOf(
                        "VK_LAYER_GOOGLE_threading",
                        "VK_LAYER_LUNARG_parameter_validation",
                        "VK_LAYER_LUNARG_object_tracker",
                        "VK_LAYER_LUNARG_core_validation",
                        "VK_LAYER_GOOGLE_unique_objects"
                    ).filter { it in supported }
                }
            }
        }

    companion object {
        private const val PORTABILITY_EXTENSION = "VK_KHR_portability_enumeration"

        var globalAsyncDebugHandler: ((String) -> Unit)? = null

        fun getOpenXRRequiredInstanceExts(): List<String> {
            if (!Options.enableVR) return emptyList()
            ensureOpenXRExtensions()
            return xrCachedInstanceExts
        }

        fun getOpenXRRequiredDeviceExts(@Suppress("unused_parameter") physicalDeviceAddr: Long): List<String> {
            if (!Options.enableVR) return emptyList()
            ensureOpenXRExtensions()
            return xrCachedDeviceExts
        }

        private var xrCachedInstanceExts: List<String> = emptyList()
        private var xrCachedDeviceExts: List<String> = emptyList()
        private var xrInitialized = false

        private fun ensureOpenXRExtensions() {
            if (xrInitialized) return
            xrInitialized = true
            MemoryStack.stackPush().use { stack ->
                // Build minimal OpenXR instance with Vulkan enable2 so we can query required Vulkan extensions
                val appInfo = XrApplicationInfo.calloc(stack)
                appInfo.applicationName(stack.UTF8("afield"))
                appInfo.applicationVersion(1)
                appInfo.engineName(stack.UTF8("afield"))
                appInfo.engineVersion(1)
                appInfo.apiVersion(XR10.XR_CURRENT_API_VERSION)

                val extNames = stack.pointers(stack.UTF8(org.lwjgl.openxr.KHRVulkanEnable2.XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME))

                val createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type(XR10.XR_TYPE_INSTANCE_CREATE_INFO)
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(extNames)

                val pInstance = stack.mallocPointer(1)
                val created = XR10.xrCreateInstance(createInfo, pInstance) == XR10.XR_SUCCESS
                if (!created) {
                    xrCachedInstanceExts = emptyList()
                    xrCachedDeviceExts = emptyList()
                    return
                }

                val xrInstance = XrInstance(pInstance[0], createInfo)
                try {
                    val sysInfo = XrSystemGetInfo.calloc(stack)
                        .type(XR10.XR_TYPE_SYSTEM_GET_INFO)
                        .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY)
                    val pSys = stack.mallocLong(1)
                    val sysRes = XR10.xrGetSystem(xrInstance, sysInfo, pSys)
                    if (sysRes != XR10.XR_SUCCESS) {
                        xrCachedInstanceExts = emptyList()
                        xrCachedDeviceExts = emptyList()
                        return
                    }
                    val systemId = pSys[0]

                    xrCachedInstanceExts = queryExts(stack) { cap, buf ->
                        org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR(xrInstance, systemId, cap, buf)
                    }
                    xrCachedDeviceExts = queryExts(stack) { cap, buf ->
                        org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR(xrInstance, systemId, cap, buf)
                    }
                } finally {
                    XR10.xrDestroyInstance(xrInstance)
                }
            }
        }

        private fun queryExts(
            stack: MemoryStack,
            call: (IntBuffer, ByteBuffer?) -> Int
        ): List<String> {
            val size = stack.mallocInt(1)
            call(size, null)
            val byteCount = size[0]
            if (byteCount <= 1) return emptyList()
            val buf = stack.malloc(byteCount)
            size.put(0, byteCount)
            call(size, buf)
            val s = MemoryUtil.memUTF8(buf, byteCount - 1)
            return s.split(' ').filter { it.isNotBlank() }
        }

        private fun createDebugCallBack(): VkDebugUtilsMessengerCreateInfoEXT {
            fun objectTypeName(t: Int) = when (t) {
                VK10.VK_OBJECT_TYPE_INSTANCE -> "Instance"
                VK10.VK_OBJECT_TYPE_DEVICE -> "Device"
                VK10.VK_OBJECT_TYPE_QUEUE -> "Queue"
                VK10.VK_OBJECT_TYPE_COMMAND_BUFFER -> "CmdBuf"
                VK10.VK_OBJECT_TYPE_IMAGE -> "Image"
                VK10.VK_OBJECT_TYPE_IMAGE_VIEW -> "ImageView"
                VK10.VK_OBJECT_TYPE_BUFFER -> "Buffer"
                VK10.VK_OBJECT_TYPE_BUFFER_VIEW -> "BufferView"
                VK10.VK_OBJECT_TYPE_SHADER_MODULE -> "ShaderModule"
                VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT -> "PipelineLayout"
                VK10.VK_OBJECT_TYPE_PIPELINE -> "Pipeline"
                VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT -> "DSL"
                VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET -> "DS"
                else -> "Object($t)"
            }

            return VkDebugUtilsMessengerCreateInfoEXT
                .calloc()
                .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(
                    EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT // optional: nice during bring-up
                )
                .messageType(
                    EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
                )
                .pfnUserCallback { severity: Int, _: Int, pCallbackData: Long, _: Long ->
                    val cb = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)

                    val msg = cb.pMessageString() ?: ""
                    val idName = cb.pMessageIdNameString() ?: ""
                    val isPrintf = idName.contains("DEBUG-PRINTF") ||
                            msg.contains("DEBUG-PRINTF") ||  // some layers put it only in the text
                            idName.contains("DebugPrintf", ignoreCase = true)


                    // Active label stacks (outer..inner)

                    val queueLabels: List<String> = buildList {
                        val buf = cb.pQueueLabels()
                        val n = cb.queueLabelCount()
                        for (i in 0 until n) add(buf?.get(i)?.pLabelNameString() ?: "")
                    }

                    val cmdLabels: List<String> = buildList {
                        val buf = cb.pCmdBufLabels()
                        val n = cb.cmdBufLabelCount()
                        for (i in 0 until n) add(buf?.get(i)?.pLabelNameString() ?: "")
                    }

// attached objects (if any)

                    // It’s handy to render as “outer > inner”
                    val labelTrail = (queueLabels + cmdLabels).filter { it.isNotEmpty() }.joinToString(" > ")

                    // Mention any named objects the layer attached to this message
                    val objectsStr: String = buildList {
                        val buf = cb.pObjects()
                        if (buf != null) {
                            val n = cb.objectCount()
                            for (i in 0 until n) {
                                val o = buf.get(i)
                                val name = o.pObjectNameString()
                                val handle = java.lang.Long.toUnsignedString(o.objectHandle())
                                add("${objectTypeName(o.objectType())}:${name ?: "0x$handle"}")
                            }
                        }
                    }.joinToString(", ")

                    val where = buildString {
                        if (labelTrail.isNotEmpty()) append(" | labels: $labelTrail")
                        if (objectsStr.isNotEmpty()) append(" | objects: $objectsStr")
                    }

                    when {
                        isPrintf -> println("PRINTF$where: $msg")
                        (severity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0 ->
                            println("ERROR$where: $msg")

                        (severity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0 ->
                            println("WARNING$where: $msg")

                        else -> println("INFO$where: $msg")
                    }

                    if (msg.contains(
                            "VK_ERROR_FEATURE_NOT_PRESENT: Metal does not support disabling primitive restart",
                            ignoreCase = true
                        )
                    ) {
                    } else {
                        globalAsyncDebugHandler?.invoke((if (isPrintf) "PRINTF" else "VK") + where + ": " + msg)
                    }


                    VK10.VK_FALSE
                }
        }



    }

// --- Tooling info dump (optional) ----------------------------------------

    fun dumpToolingInfo(instance: VkInstance, phys: VkPhysicalDevice) {
        MemoryStack.stackPush().use { stack ->
            fun purposes(bits: Int): String {
                val out = ArrayList<String>(7)
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_VALIDATION_BIT_EXT) != 0) out += "validation"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_PROFILING_BIT_EXT) != 0) out += "profiling"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_TRACING_BIT_EXT) != 0) out += "tracing"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_ADDITIONAL_FEATURES_BIT_EXT) != 0) out += "adds_features"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_MODIFYING_FEATURES_BIT_EXT) != 0) out += "modifies_features"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_DEBUG_REPORTING_BIT_EXT) != 0) out += "debug_reporting"
                if ((bits and EXTToolingInfo.VK_TOOL_PURPOSE_DEBUG_MARKERS_BIT_EXT) != 0) out += "debug_markers"
                return if (out.isEmpty()) "none" else out.joinToString("|")
            }

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
                                println("    purposes=${purposes(t.purposes())}")
                            }
                        }
                    }
                }
            }
        }
    }
}

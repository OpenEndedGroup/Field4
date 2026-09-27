package afield.four.device

import afield.four.util.OS
import afield.four.util.OSType
import afield.four.util.Options
import afield.four.util.vkCheck
import afield.logger.lprint
import afield.logger.lprintln
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.openxr.*
import org.lwjgl.openxr.KHRVulkanEnable2.xrGetVulkanGraphicsRequirements2KHR
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
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class Instance(private val validate: Boolean) {

    var vkInstance: VkInstance
        private set

    /** Vulkan instance extensions actually enabled at creation time. */
    val enabledExtensions: Set<String>

    private var debugUtilsCreateInfo: VkDebugUtilsMessengerCreateInfoEXT? = null
    private var vkDebugHandle: Long = 0
    internal val validationActive: Boolean get() = validate && vkDebugHandle != VK10.VK_NULL_HANDLE

    init {
        if (Options.enableVR) {
            prepareOpenXRBootstrap()
        }
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
            val wantsValidationLayer = validate || Options.shaderDebugPrintf
            val wantsDebugPrintf = validate || Options.shaderDebugPrintf
            val supportsValidation = wantsValidationLayer && validationLayers.isNotEmpty()
            if (wantsValidationLayer && !supportsValidation) {
                lprintln("warning,vulkan", "Requested validation layer features, but no supported layers found")
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

            val hasPortabilityEnumeration = PORTABILITY_EXTENSION in supportedExts
            val usePortability = (OS == OSType.MACOS) && hasPortabilityEnumeration
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
                if (usePortability && PORTABILITY_EXTENSION !in this) add(PORTABILITY_EXTENSION) // VK_KHR_portability_enumeration
                if (Options.enableVR) addAll(getOpenXRRequiredInstanceExts())
                // Required instance-side dependency of VK_EXT_present_timing. Device decides
                // whether the complete device-side dependency bundle is available.
                if (KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME in supportedExts) {
                    add(KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME)
                }
                // VK_EXT_swapchain_colorspace enables HDR surface format enumeration (scRGB, HDR10, etc.)
                if (Options.enableHDR && SWAPCHAIN_COLORSPACE_EXTENSION in supportedExts) add(SWAPCHAIN_COLORSPACE_EXTENSION)
            }
            extNames.addAll(afield.four.reconstruction.NgxSupport.instanceExtensions(supportedExts))
            val dedupedExtNames = extNames.distinct()
            enabledExtensions = dedupedExtNames.toSet()

            val requiredExtensions = stack.mallocPointer(dedupedExtNames.size).apply {
                dedupedExtNames.forEach { put(stack.ASCII(it)) }
                flip()
            }

            // --- Optional pNext chain
            var pNextHead: Long = MemoryUtil.NULL

            // Debug utils callback create-info
            if (supportsValidation && hasDebugUtils) {
                debugUtilsCreateInfo = createDebugCallBack()
                pNextHead = debugUtilsCreateInfo!!.address()
            }

            // Validation features. Shader printf is implemented by the validation layer; when
            // full validation is off, keep the layer loaded but disable core checks.
            if (supportsValidation && hasValidationFeatures) {
                val enables = if (wantsDebugPrintf) {
                    stack.ints(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT)
                } else {
                    null
                }
                val disables = if (!validate && wantsDebugPrintf) {
                    stack.ints(EXTValidationFeatures.VK_VALIDATION_FEATURE_DISABLE_CORE_CHECKS_EXT)
                } else {
                    null
                }
                val vfeat = VkValidationFeaturesEXT.calloc(stack)
                    .sType(EXTValidationFeatures.VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT)
                    .pEnabledValidationFeatures(enables)
                    .pDisabledValidationFeatures(disables)
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
                instanceInfo.flags(instanceInfo.flags() or VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
            } else if (OS == OSType.MACOS && !hasPortabilityEnumeration) {
                lprintln("warning,vulkan", "VK_KHR_portability_enumeration not available; continuing without portability enumeration")
            }

            if (Options.enableVR && xrBootstrap != null) {
                lprint("openxr", "using xrBootstrap")
                vkInstance = createVkInstanceViaXR(instanceInfo)
            } else {
                lprint("openxr", "not using xrBootstrap")
                val pInstance = stack.mallocPointer(1)
                vkCheck(VK10.vkCreateInstance(instanceInfo, null, pInstance), "Error creating instance")
                vkInstance = VkInstance(pInstance[0], instanceInfo)
            }

            if (Options.enableVR) {
                updateOpenXRBootstrapPhys(vkInstance)
            }

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
        // Instance extension that exposes extended colorspaces (scRGB, HDR10, Display-P3, …)
        internal const val SWAPCHAIN_COLORSPACE_EXTENSION = "VK_EXT_swapchain_colorspace"

        data class OpenXRBootstrap(
            val xrInstance: XrInstance,
            val systemId: Long,
            var xrPhys: Long?,
            val minApi: Int,
            val maxApi: Int,
            var consumed: Boolean = false
        )

        var globalAsyncDebugHandler: ((String) -> Unit)? = null
        private val reportedSteamVrBlankEyeValidationNoise = AtomicBoolean(false)

        private val openXRVulkanExtensions = listOf(
            KHRVulkanEnable2.XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME
        ) + if (OS == OSType.LINUX) emptyList() else listOf(
            KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME
        )

        private val openXRValidationLayerCandidates = listOf(
            "XR_APILAYER_LUNARG_core_validation",
            "XR_APILAYER_KHRONOS_validation"
        )

        fun getOpenXRRequiredInstanceExts(): List<String> {
            if (!Options.enableVR) return emptyList()
            // XR_KHR_vulkan_enable2 lets the runtime amend VkInstanceCreateInfo itself.
            // Do not mix in the legacy XR_KHR_vulkan_enable extension-query path on Linux.
            if (OS == OSType.LINUX) return emptyList()
            ensureOpenXRExtensions()
            return xrCachedInstanceExts
        }

        fun getOpenXRRequiredDeviceExts(@Suppress("unused_parameter") physicalDeviceAddr: Long): List<String> {
            if (!Options.enableVR) return emptyList()
            if (OS == OSType.LINUX) return emptyList()
            ensureOpenXRExtensions()
            return xrCachedDeviceExts
        }

        /** Create and cache an OpenXR instance/system before VkInstance/VkDevice creation (spec requires ordering). */
        fun prepareOpenXRBootstrap() {
            if (!Options.enableVR) return
            if (xrBootstrap != null) return
            MemoryStack.stackPush().use { stack ->
                val extNames = requiredOpenXRExtensions() ?: return
                val apiLayers = enabledOpenXRApiLayers()
                val appInfo = XrApplicationInfo.calloc(stack)
                appInfo.applicationName(stack.UTF8("afield"))
                appInfo.applicationVersion(1)
                appInfo.engineName(stack.UTF8("afield"))
                appInfo.engineVersion(1)
                appInfo.apiVersion(XR10.XR_API_VERSION_1_0)

                val createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type(XR10.XR_TYPE_INSTANCE_CREATE_INFO)
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(pointerBuffer(stack, extNames))
                    .enabledApiLayerNames(pointerBuffer(stack, apiLayers))

                val pInstance = stack.mallocPointer(1)
                val createdRes = XR10.xrCreateInstance(createInfo, pInstance)
                if (createdRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrCreateInstance (bootstrap) returned ${createdRes}")
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
                        lprint("error,openxr", "xrGetSystem (bootstrap) returned ${sysRes}")
                        XR10.xrDestroyInstance(xrInstance)
                        return
                    }
                    val systemId = pSys[0]

                    val gfxReq = XrGraphicsRequirementsVulkan2KHR.calloc(stack)
                        // LWJGL exposes Vulkan2 as an alias struct, but its Vulkan2 type constant
                        // is not the alias value accepted by current OpenXR validation/runtime.
                        .type(KHRVulkanEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR)
                    val gfxReqRes = xrGetVulkanGraphicsRequirements2KHR(xrInstance, systemId, gfxReq)
                    check(gfxReqRes == XR10.XR_SUCCESS) {
                        "xrGetVulkanGraphicsRequirements2KHR failed: $gfxReqRes"
                    }

                    val physInfo = XrVulkanGraphicsDeviceGetInfoKHR.calloc(stack)
                    if (OS != OSType.LINUX) {
                        // Legacy compatibility path retained for the existing Windows setup.
                        xrCachedInstanceExts = queryExts(stack) { cap, buf ->
                            org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanInstanceExtensionsKHR(xrInstance, systemId, cap, buf)
                        }
                        xrCachedDeviceExts = queryExts(stack) { cap, buf ->
                            org.lwjgl.openxr.KHRVulkanEnable.xrGetVulkanDeviceExtensionsKHR(xrInstance, systemId, cap, buf)
                        }
                    }

                    xrInitialized = true
                    xrBootstrap = OpenXRBootstrap(
                        xrInstance = xrInstance,
                        systemId = systemId,
                        xrPhys = null,
                        minApi = gfxReq.minApiVersionSupported().toInt(),
                        maxApi = gfxReq.maxApiVersionSupported().toInt()
                    )
                    lprintln(
                        "info,openxr",
                        "XR bootstrap ready: minApi=${VK10.VK_API_VERSION_MAJOR(gfxReq.minApiVersionSupported().toInt())}.${VK10.VK_API_VERSION_MINOR(gfxReq.minApiVersionSupported().toInt())} maxApi=${VK10.VK_API_VERSION_MAJOR(gfxReq.maxApiVersionSupported().toInt())}.${VK10.VK_API_VERSION_MINOR(gfxReq.maxApiVersionSupported().toInt())}"
                    )
                } catch (t: Throwable) {
                    XR10.xrDestroyInstance(xrInstance)
                    throw t
                }
            }
        }

        /** After creating VkInstance via XR, query and cache the runtime-selected VkPhysicalDevice. */
        fun updateOpenXRBootstrapPhys(vkInstance: VkInstance): Long? {
            if (!Options.enableVR) return null
            val b = xrBootstrap ?: return null
            MemoryStack.stackPush().use { stack ->
                val physInfo = XrVulkanGraphicsDeviceGetInfoKHR.calloc(stack)
                    .type(KHRVulkanEnable2.XR_TYPE_VULKAN_GRAPHICS_DEVICE_GET_INFO_KHR)
                    .systemId(b.systemId)
                    .vulkanInstance(vkInstance)
                val pPhys = stack.mallocPointer(1)
                val physRes = KHRVulkanEnable2.xrGetVulkanGraphicsDevice2KHR(b.xrInstance, physInfo, pPhys)
                if (physRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrGetVulkanGraphicsDevice2KHR (phys update) returned ${physRes}")
                    return null
                }
                b.xrPhys = pPhys[0]
                return b.xrPhys
            }
        }

        /** Create VkInstance through XR_KHR_vulkan_enable2 for VR path. */
        fun createVkInstanceViaXR(ci: VkInstanceCreateInfo): VkInstance {
            val b = xrBootstrap ?: throw IllegalStateException("XR bootstrap missing for xrCreateVulkanInstanceKHR")
            MemoryStack.stackPush().use { stack ->
                val pfnGetInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr")
                val xrCi = XrVulkanInstanceCreateInfoKHR.calloc(stack)
                    .type(KHRVulkanEnable2.XR_TYPE_VULKAN_INSTANCE_CREATE_INFO_KHR)
                    .systemId(b.systemId)
                    .createFlags(0)
                    .pfnGetInstanceProcAddr(pfnGetInstanceProcAddr)
                    .vulkanCreateInfo(ci)
                    .vulkanAllocator(null)

                val pOut = stack.mallocPointer(1)
                val pRes = stack.mallocInt(1)
                val res = KHRVulkanEnable2.xrCreateVulkanInstanceKHR(b.xrInstance, xrCi, pOut, pRes)
                if (res != XR10.XR_SUCCESS) {
                    throw RuntimeException("xrCreateVulkanInstanceKHR failed: ${res} (vkResult=${pRes[0]})")
                }
                return VkInstance(pOut[0], ci)
            }
        }

        /** Create VkDevice through XR_KHR_vulkan_enable2 for VR path. */
        fun createVkDeviceViaXR(ci: VkDeviceCreateInfo, vkInstance: VkInstance, vkPhysicalDevice: VkPhysicalDevice): VkDevice {
            val b = xrBootstrap ?: throw IllegalStateException("XR bootstrap missing for xrCreateVulkanDeviceKHR")
            MemoryStack.stackPush().use { stack ->
                val pfnGetInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr")
                val xrCi = XrVulkanDeviceCreateInfoKHR.calloc(stack)
                    .type(KHRVulkanEnable2.XR_TYPE_VULKAN_DEVICE_CREATE_INFO_KHR)
                    .systemId(b.systemId)
                    .createFlags(0)
                    .pfnGetInstanceProcAddr(pfnGetInstanceProcAddr)
                    .vulkanPhysicalDevice(vkPhysicalDevice)
                    .vulkanCreateInfo(ci)
                    .vulkanAllocator(null)

                val pOut = stack.mallocPointer(1)
                val pRes = stack.mallocInt(1)
                val res = KHRVulkanEnable2.xrCreateVulkanDeviceKHR(b.xrInstance, xrCi, pOut, pRes)
                if (res != XR10.XR_SUCCESS) {
                    throw RuntimeException("xrCreateVulkanDeviceKHR failed: ${res} (vkResult=${pRes[0]})")
                }
                return VkDevice(pOut[0], vkPhysicalDevice, ci)
            }
        }

        /** Consume the bootstrap (ownership of xrInstance transfers to caller). */
        fun consumeOpenXRBootstrap(): OpenXRBootstrap? {
            val b = xrBootstrap ?: return null
            xrBootstrap = null
            b.consumed = true
            return b
        }

        /** Peek the XR-selected physical device if bootstrap already prepared. */
        fun peekOpenXRBootstrapPhys(): Long? = xrBootstrap?.xrPhys

        fun cleanupOpenXRBootstrap() {
            xrBootstrap?.let { XR10.xrDestroyInstance(it.xrInstance) }
            xrBootstrap = null
        }

        fun hasOpenXRBootstrap(): Boolean = xrBootstrap != null

        /** Query the runtime-selected VkPhysicalDevice for this system (via XR_KHR_vulkan_enable2). */
        fun getOpenXRRuntimePhysicalDevice(vkInstance: VkInstance): Long? {
            if (!Options.enableVR) return null
            peekOpenXRBootstrapPhys()?.let { return it }
            MemoryStack.stackPush().use { stack ->
                val extNames = requiredOpenXRExtensions() ?: return null
                val apiLayers = enabledOpenXRApiLayers()
                val appInfo = XrApplicationInfo.calloc(stack)
                appInfo.applicationName(stack.UTF8("afield"))
                appInfo.applicationVersion(1)
                appInfo.engineName(stack.UTF8("afield"))
                appInfo.engineVersion(1)
                appInfo.apiVersion(XR10.XR_API_VERSION_1_0)

                val createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type(XR10.XR_TYPE_INSTANCE_CREATE_INFO)
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(pointerBuffer(stack, extNames))
                    .enabledApiLayerNames(pointerBuffer(stack, apiLayers))

                val pInstance = stack.mallocPointer(1)
                val createdRes = XR10.xrCreateInstance(createInfo, pInstance)
                if (createdRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrCreateInstance (phys query) returned ${createdRes}")
                    return null
                }

                val xrInstance = XrInstance(pInstance[0], createInfo)
                try {
                    val sysInfo = XrSystemGetInfo.calloc(stack)
                        .type(XR10.XR_TYPE_SYSTEM_GET_INFO)
                        .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY)
                    val pSys = stack.mallocLong(1)
                    val sysRes = XR10.xrGetSystem(xrInstance, sysInfo, pSys)
                    if (sysRes != XR10.XR_SUCCESS) {
                        lprint("error,openxr", "xrGetSystem (phys query) returned ${sysRes}")
                        return null
                    }
                    val systemId = pSys[0]

                    val physInfo = XrVulkanGraphicsDeviceGetInfoKHR.calloc(stack)
                        .type(KHRVulkanEnable2.XR_TYPE_VULKAN_GRAPHICS_DEVICE_GET_INFO_KHR)
                        .systemId(systemId)
                        .vulkanInstance(vkInstance)
                    val pPhys = stack.mallocPointer(1)
                    val physRes = KHRVulkanEnable2.xrGetVulkanGraphicsDevice2KHR(xrInstance, physInfo, pPhys)
                    if (physRes != XR10.XR_SUCCESS) {
                        lprint("error,openxr", "xrGetVulkanGraphicsDevice2KHR returned ${physRes}")
                        return null
                    }
                    return pPhys[0]
                } finally {
                    XR10.xrDestroyInstance(xrInstance)
                }
            }
        }

        private var xrCachedInstanceExts: List<String> = emptyList()
        private var xrCachedDeviceExts: List<String> = emptyList()
        private var xrInitialized = false
        private var xrBootstrap: OpenXRBootstrap? = null

        /**
         * The OpenXR instance extensions we actually enabled. The optional ones are only present
         * when the runtime advertised them, so consumers that build on them (hand tracking, body
         * tracking, eye tracking) check here rather than guessing.
         */
        var enabledOpenXRInstanceExtensions: Set<String> = emptySet()
            private set

        private fun pointerBuffer(stack: MemoryStack, names: List<String>): PointerBuffer? {
            if (names.isEmpty()) return null
            return stack.mallocPointer(names.size).apply {
                names.forEach { put(stack.UTF8(it)) }
                flip()
            }
        }

        private fun requiredOpenXRExtensions(): List<String>? {
            val supported = enumerateOpenXRInstanceExtensions()
            val missing = openXRVulkanExtensions.filter { it !in supported }
            if (missing.isNotEmpty()) {
                lprint("error,openxr", "missing required OpenXR extensions: $missing; supported=$supported")
                return null
            }
            val optional = listOf(
                EXTHandTracking.XR_EXT_HAND_TRACKING_EXTENSION_NAME,
                EXTHandInteraction.XR_EXT_HAND_INTERACTION_EXTENSION_NAME,
                EXTEyeGazeInteraction.XR_EXT_EYE_GAZE_INTERACTION_EXTENSION_NAME,
                afield.four.targets.HtcEyeTracker.EXTENSION_NAME,
                // Monado vendor extension; the only route to WiVRn's body trackers.
                afield.four.targets.MndxXDevSpace.EXTENSION_NAME,
            ).filter { it in supported }
            val enabled = (openXRVulkanExtensions + optional).distinct()
            enabledOpenXRInstanceExtensions = enabled.toSet()
            return enabled
        }

        private fun enabledOpenXRApiLayers(): List<String> {
            if (!Options.validate) return emptyList()
            val supported = enumerateOpenXRApiLayers()
            val selected = openXRValidationLayerCandidates.firstOrNull { it in supported }
            if (selected == null) {
                lprintln(
                    "warning,openxr",
                    "OpenXR validation requested but no known validation layer was found; supported layers=$supported"
                )
                return emptyList()
            }
            lprintln("info,openxr", "OpenXR validation layer enabled: $selected")
            return listOf(selected)
        }

        private fun enumerateOpenXRInstanceExtensions(): Set<String> {
            val out = linkedSetOf<String>()
            MemoryStack.stackPush().use { stack ->
                val count = stack.callocInt(1)
                val countRes = XR10.xrEnumerateInstanceExtensionProperties(null as String?, count, null)
                if (countRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrEnumerateInstanceExtensionProperties(count) returned $countRes")
                    return emptySet()
                }
                val props = XrExtensionProperties.calloc(count[0], stack)
                for (i in 0 until props.capacity()) {
                    props[i].type(XR10.XR_TYPE_EXTENSION_PROPERTIES)
                }
                val propsRes = XR10.xrEnumerateInstanceExtensionProperties(null as String?, count, props)
                if (propsRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrEnumerateInstanceExtensionProperties returned $propsRes")
                    return emptySet()
                }
                repeat(props.capacity()) { i -> out += props[i].extensionNameString() }
            }
            return out
        }

        private fun enumerateOpenXRApiLayers(): Set<String> {
            val out = linkedSetOf<String>()
            MemoryStack.stackPush().use { stack ->
                val count = stack.callocInt(1)
                val countRes = XR10.xrEnumerateApiLayerProperties(count, null)
                if (countRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrEnumerateApiLayerProperties(count) returned $countRes")
                    return emptySet()
                }
                val props = XrApiLayerProperties.calloc(count[0], stack)
                for (i in 0 until props.capacity()) {
                    props[i].type(XR10.XR_TYPE_API_LAYER_PROPERTIES)
                }
                val propsRes = XR10.xrEnumerateApiLayerProperties(count, props)
                if (propsRes != XR10.XR_SUCCESS) {
                    lprint("error,openxr", "xrEnumerateApiLayerProperties returned $propsRes")
                    return emptySet()
                }
                repeat(props.capacity()) { i -> out += props[i].layerNameString() }
            }
            return out
        }

        private fun ensureOpenXRExtensions() {
            if (xrInitialized) return
            xrInitialized = true
            MemoryStack.stackPush().use { stack ->
                val extNames = requiredOpenXRExtensions()
                if (extNames == null) {
                    xrCachedInstanceExts = emptyList()
                    xrCachedDeviceExts = emptyList()
                    return
                }
                val apiLayers = enabledOpenXRApiLayers()
                // Build minimal OpenXR instance with Vulkan enable2 so we can query required Vulkan extensions
                val appInfo = XrApplicationInfo.calloc(stack)
                appInfo.applicationName(stack.UTF8("afield"))
                appInfo.applicationVersion(1)
                appInfo.engineName(stack.UTF8("afield"))
                appInfo.engineVersion(1)
                appInfo.apiVersion(XR10.XR_API_VERSION_1_0)

                val createInfo = XrInstanceCreateInfo.calloc(stack)
                    .type(XR10.XR_TYPE_INSTANCE_CREATE_INFO)
                    .applicationInfo(appInfo)
                    .enabledExtensionNames(pointerBuffer(stack, extNames))
                    .enabledApiLayerNames(pointerBuffer(stack, apiLayers))

                val pInstance = stack.mallocPointer(1)
                val createdError = XR10.xrCreateInstance(createInfo, pInstance)
                val created = createdError == XR10.XR_SUCCESS
                if (!created) {
                    lprint("error,openxr", "xrCreateInstance returned ${createdError}")
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

                    // Per spec: must be called before creating VkDevice for this system.
                    val gfxReq = XrGraphicsRequirementsVulkan2KHR.calloc(stack)
                        .type(KHRVulkanEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_VULKAN_KHR)
                    val gfxReqRes = xrGetVulkanGraphicsRequirements2KHR(xrInstance, systemId, gfxReq)
                    check(gfxReqRes == XR10.XR_SUCCESS) {
                        "xrGetVulkanGraphicsRequirements2KHR failed: $gfxReqRes"
                    }

                    lprintln(
                        "info,openxr",
                        "XR gfx requirements: minApi=${VK10.VK_API_VERSION_MAJOR(gfxReq.minApiVersionSupported().toInt())}.${VK10.VK_API_VERSION_MINOR(gfxReq.minApiVersionSupported().toInt())} maxApi=${VK10.VK_API_VERSION_MAJOR(gfxReq.maxApiVersionSupported().toInt())}.${VK10.VK_API_VERSION_MINOR(gfxReq.maxApiVersionSupported().toInt())}"
                    )

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

            // Start with a small buffer; if XR reports size insufficient, reallocate using the reported size.
            var capacity = 256
            var buf = stack.malloc(capacity)
            size.put(0, capacity)

            var res = call(size, buf)
            if (res == XR10.XR_ERROR_SIZE_INSUFFICIENT) {
                capacity = size[0]
                if (capacity <= 0) return emptyList()
                buf = stack.malloc(capacity)
                size.put(0, capacity)
                res = call(size, buf)
            }

            if (res != XR10.XR_SUCCESS) {
                lprint("error,openxr", "xrGetVulkan*ExtensionsKHR returned ${res}")
                return emptyList()
            }

            val byteCount = size[0]
            if (byteCount <= 1) return emptyList()

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
                .pfnUserCallback callback@{ severity: Int, _: Int, pCallbackData: Long, _: Long ->
                    val cb = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)

                    val msg = cb.pMessageString() ?: ""
                    // SteamVR records internal commands against an image it names
                    // "BlankEyeBuffer" with inconsistent transfer/source layout tracking. This is
                    // a long-standing runtime validation defect which also reproduces in minimal
                    // OpenXR Vulkan applications. Keep validation enabled for Field objects, but
                    // suppress only this runtime-owned, object-specific flood.
                    val isSteamVrBlankEyeNoise =
                        OS == OSType.LINUX && Options.enableVR &&
                                msg.contains("BlankEyeBuffer") &&
                                msg.contains("VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL") &&
                                msg.contains("current layout")
                    if (isSteamVrBlankEyeNoise) {
                        if (reportedSteamVrBlankEyeValidationNoise.compareAndSet(false, true)) {
                            lprintln(
                                "info,openxr",
                                "Suppressing known SteamVR BlankEyeBuffer Vulkan validation noise"
                            )
                        }
                        return@callback VK10.VK_FALSE
                    }
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
                        isPrintf -> lprintln("debug,vulkan", "PRINTF$where: $msg")
                        (severity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0 ->
                            lprintln("error,vulkan", "ERROR$where: $msg")

                        (severity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0 ->
                            lprintln("warning,vulkan", "WARNING$where: $msg")

                        else -> lprintln("info,vulkan", "INFO$where: $msg")
                    }

                    // Only what a person must act on reaches a box. INFO and VERBOSE messages — the
                    // loader's layer-configuration dump among them — land on whichever box built a
                    // module last, as errors, so every relaunch of a window reported a failed run.
                    val forBox = isPrintf || (severity and (EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)) != 0
                    if (!forBox || msg.contains(
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

            fun boundedUtf8(buf: ByteBuffer): String {
                val view = buf.duplicate()
                val start = view.position()
                val end = (start until view.limit()).firstOrNull { view.get(it).toInt() == 0 } ?: view.limit()
                view.limit(end)
                val bytes = view.slice()
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(bytes)
                    .toString()
            }

            if (instance.capabilities.vkGetPhysicalDeviceToolPropertiesEXT != 0L) {
                val capacity = 64
                val pCount = stack.mallocInt(1)
                pCount.put(0, capacity)
                // Allocate a little extra space without advertising it to the driver/layers.
                // This keeps a minor overrun by a broken tool from immediately corrupting
                // the surrounding stack frame while still preserving the Vulkan contract.
                val props = VkPhysicalDeviceToolPropertiesEXT.calloc(capacity + 4, stack)
                for (i in 0 until capacity) {
                    props[i]
                        .sType(EXTToolingInfo.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TOOL_PROPERTIES_EXT)
                        .pNext(MemoryUtil.NULL)
                }
                val res = EXTToolingInfo.vkGetPhysicalDeviceToolPropertiesEXT(phys, pCount, props)
                if (res == VK_SUCCESS || res == VK_INCOMPLETE) {
                    val written = pCount[0].coerceIn(0, capacity)
                    lprintln("info,vulkan", "== Tooling (physical device) ==")
                    if (res == VK_INCOMPLETE) {
                        lprintln("warning,vulkan", "Tooling query returned VK_INCOMPLETE; showing first $written tools")
                    }
                    for (i in 0 until written) {
                        val t = props[i]
                        lprintln("info,vulkan", " - ${boundedUtf8(t.name())} ${boundedUtf8(t.version())}")
                        lprintln("info,vulkan", "    layer='${boundedUtf8(t.layer())}'")
                        lprintln("info,vulkan", "    desc='${boundedUtf8(t.description())}'")
                        lprintln("info,vulkan", "    purposes=${purposes(t.purposes())}")
                    }
                } else {
                    lprintln("warning,vulkan", "Tooling query failed with VkResult $res")
                }
            }
        }
    }
}

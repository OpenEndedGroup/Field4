package afield.four.util

import afield.logger.lprintln
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM
import org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
import org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties
import org.lwjgl.vulkan.VkFormatProperties
import org.lwjgl.vulkan.VkPhysicalDevice
import java.io.File

object Options {

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    val isMac = System.getProperty("os.name").lowercase().contains("mac")

    val target = System.getProperty("os.name").lowercase().let {
        if ("mac" in it) "macosx"
        else if ("win" in it) "win-x64"
        else if ("linux" in it) "linux-x64"
        else throw IllegalStateException("Unsupported OS: $it")
    }

    var vulkan_library_name = "/Users/marc/VulkanSDK/1.4.335.1/macOS/lib/libvulkan.1.dylib"
    val useVulkanSDK = true
    val robustness = false
    var profiling = System.getProperty(
        "afield.four.profiling",
        System.getenv("AFIELD_FOUR_PROFILING") ?: "false"
    ).toBoolean()
    var framePresentationLog = System.getProperty(
        "afield.four.framePresentationLog",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG") ?: "false"
    ).toBoolean()
    var framePresentationLogPath: String? = System.getProperty(
        "afield.four.framePresentationLogPath",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PATH")
    )
    var framePresentationLogWaitAfterPresent = System.getProperty(
        "afield.four.framePresentationLogWaitAfterPresent",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_WAIT_AFTER_PRESENT") ?: "false"
    ).toBoolean()
    var framePresentationLogFlushEvery = System.getProperty(
        "afield.four.framePresentationLogFlushEvery",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_FLUSH_EVERY") ?: "120"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 120
    var framePresentationLogPresentWaitTimeoutNs = System.getProperty(
        "afield.four.framePresentationLogPresentWaitTimeoutNs",
        System.getenv("AFIELD_FOUR_FRAME_PRESENTATION_LOG_PRESENT_WAIT_TIMEOUT_NS") ?: "50000000"
    ).toLongOrNull()?.coerceAtLeast(1L) ?: 50_000_000L
    // Number of past-presentation-timing slots pre-reserved on the swapchain for
    // VK_EXT_present_timing.  Increase if high-frequency drain calls miss entries.
    var presentTimingQueueSize = System.getProperty(
        "afield.four.presentTimingQueueSize",
        System.getenv("AFIELD_FOUR_PRESENT_TIMING_QUEUE_SIZE") ?: "8"
    ).toIntOrNull()?.coerceAtLeast(1) ?: 8
    var logShadercSource = System.getProperty(
        "afield.four.logShadercSource",
        System.getenv("AFIELD_FOUR_LOG_SHADERC_SOURCE") ?: "true"
    ).toBoolean()
    var computeOncePerFrame = System.getProperty(
        "afield.four.computeOncePerFrame",
        System.getenv("AFIELD_FOUR_COMPUTE_ONCE_PER_FRAME") ?: "true"
    ).toBoolean()
    // Requested by default; actual use is gated by Device.supportsMaintenance7.
    var secondaryGraphicsCommandBuffers = System.getProperty(
        "afield.four.secondaryGraphicsCommandBuffers",
        System.getenv("AFIELD_FOUR_SECONDARY_GRAPHICS_COMMAND_BUFFERS") ?: "true"
    ).toBoolean()
    var captureCompatibility = System.getProperty(
        "afield.four.captureCompatibility",
        System.getenv("AFIELD_FOUR_CAPTURE_COMPAT") ?: "false"
    ).toBoolean()
    // vkGetPhysicalDeviceToolPropertiesEXT crosses into injected tooling/layer code and
    // has crashed JVM startup under Nsight; keep it opt-in.
    var dumpToolingInfo = !captureCompatibility && System.getProperty(
        "afield.four.dumpToolingInfo",
        System.getenv("AFIELD_FOUR_DUMP_TOOLING_INFO") ?: "false"
    ).toBoolean()
    var debugObjectNames = !captureCompatibility && System.getProperty(
        "afield.four.debugObjectNames",
        System.getenv("AFIELD_FOUR_DEBUG_OBJECT_NAMES") ?: "true"
    ).toBoolean()
    var debugLabels = !captureCompatibility && System.getProperty(
        "afield.four.debugLabels",
        System.getenv("AFIELD_FOUR_DEBUG_LABELS") ?: "true"
    ).toBoolean()
    var enableVR = false

    fun doVulkanSDK() {
        if (useVulkanSDK && isMac && File(vulkan_library_name).exists()) Configuration.VULKAN_LIBRARY_NAME.set(vulkan_library_name)

        if (isWindows) // too many vulkan extensions on latest nvidia!!
            Configuration.STACK_SIZE.set(512)
    }

    private val requestedDepthFormat = System.getProperty(
        "afield.four.depthFormat",
        System.getenv("AFIELD_FOUR_DEPTH_FORMAT")
    )?.trim()?.takeIf { it.isNotEmpty() }?.let { parseDepthFormat(it) }

    var defaultDepthFormat: Int = requestedDepthFormat ?: VK_FORMAT_D32_SFLOAT

    fun chooseDefaultDepthFormat(physicalDevice: VkPhysicalDevice): Int {
        requestedDepthFormat?.let {
            defaultDepthFormat = it
            lprintln("info,graphics", "Using requested depth format ${depthFormatName(it)}")
            return it
        }

        val candidates = listOf(
            VK_FORMAT_D32_SFLOAT,
            VK_FORMAT_D16_UNORM,
            VK_FORMAT_D24_UNORM_S8_UINT,
            VK_FORMAT_D32_SFLOAT_S8_UINT
        )

        MemoryStack.stackPush().use { stack ->
            val props = VkFormatProperties.calloc(stack)
            candidates.forEach { format ->
                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props)
                if ((props.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    defaultDepthFormat = format
                    lprintln("info,graphics", "Selected depth format ${depthFormatName(format)}")
                    return format
                }
            }
        }

        error("No supported depth attachment format found")
    }

    fun depthHasStencil(format: Int): Boolean =
        format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D32_SFLOAT_S8_UINT

    fun depthAspectMask(format: Int): Int =
        VK10.VK_IMAGE_ASPECT_DEPTH_BIT or
                (if (depthHasStencil(format)) VK10.VK_IMAGE_ASPECT_STENCIL_BIT else 0)

    fun depthFormatName(format: Int): String = when (format) {
        VK_FORMAT_D16_UNORM -> "D16_UNORM"
        VK_FORMAT_D32_SFLOAT -> "D32_SFLOAT"
        VK_FORMAT_D24_UNORM_S8_UINT -> "D24_UNORM_S8_UINT"
        VK_FORMAT_D32_SFLOAT_S8_UINT -> "D32_SFLOAT_S8_UINT"
        else -> "format-$format"
    }

    private fun parseDepthFormat(raw: String): Int {
        return when (raw.uppercase().replace("-", "_")) {
            "D16", "D16_UNORM", "VK_FORMAT_D16_UNORM" -> VK_FORMAT_D16_UNORM
            "D32", "D32F", "D32_SFLOAT", "VK_FORMAT_D32_SFLOAT" -> VK_FORMAT_D32_SFLOAT
            "D24S8", "D24_UNORM_S8_UINT", "VK_FORMAT_D24_UNORM_S8_UINT" -> VK_FORMAT_D24_UNORM_S8_UINT
            "D32S8", "D32FS8", "D32_SFLOAT_S8_UINT", "VK_FORMAT_D32_SFLOAT_S8_UINT" -> VK_FORMAT_D32_SFLOAT_S8_UINT
            else -> error("Unknown depth format '$raw'. Use D16, D32, D24S8, or D32S8.")
        }
    }

    val validateOverride: Boolean? = System.getProperty(
        "afield.validate",
        System.getenv("AFIELD_VALIDATE")
    )?.trim()?.takeIf { it.isNotEmpty() }?.toBooleanStrictOrNull()

    var validate = if (captureCompatibility) false else validateOverride ?: true
    var shaderDebugPrintf = !captureCompatibility && System.getProperty(
        "afield.four.shaderDebugPrintf",
        System.getenv("AFIELD_FOUR_SHADER_DEBUG_PRINTF") ?: "false"
    ).toBoolean()
    var preferredDevice: String? = null
    var enableRayTracing: Boolean = !isMac
    var requestedImages = 3
    var vsync = true
    val fov: Double = 30.0
    val zNear: Double = 0.001
    val zFar: Double = 1000.0

    var openXRClearColor: FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    // When true, the swap chain will prefer an HDR surface format (scRGB or HDR10) over the
    // default 8-bit sRGB.  On macOS a small JVM→ObjC FFI call also sets
    // CAMetalLayer.wantsExtendedDynamicRangeContent = YES so the OS actually delivers EDR
    // headroom.  Requires VK_EXT_swapchain_colorspace on the instance and a display that
    // advertises an HDR colorspace; falls back silently to sRGB when the hardware doesn't
    // support any HDR format.
    var enableHDR: Boolean = false

    // When true, prints OpenXR input diagnostics (interaction profiles, bound sources).
    var openXRDebugInput: Boolean = true

    // When true, we wait for the GPU to finish rendering into the XR swapchain images before
    // calling xrReleaseSwapchainImage(). This is the most conservative/correct approach but can
    // introduce stutter by forcing a hard GPU sync.
    //
    // When false, we submit and release immediately (no explicit GPU wait). This is much faster
    // and is commonly used in practice, but relies on runtime/driver behavior; test on your
    // target runtimes.
    var openXRWaitForGpuBeforeRelease: Boolean = false
}

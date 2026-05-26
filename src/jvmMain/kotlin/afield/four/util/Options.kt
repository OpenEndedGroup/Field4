package afield.four.util

import org.lwjgl.system.Configuration
import org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT
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
    val profiling = System.getProperty(
        "afield.four.profiling",
        System.getenv("AFIELD_FOUR_PROFILING") ?: "false"
    ).toBoolean()
    var enableVR = true && !isMac

    fun doVulkanSDK() {
        if (useVulkanSDK && isMac && File(vulkan_library_name).exists()) Configuration.VULKAN_LIBRARY_NAME.set(vulkan_library_name)

        if (isWindows) // too many vulkan extensions on latest nvidia!!
            Configuration.STACK_SIZE.set(512)
    }

    var defaultDepthFormat: Int = if (isWindows || isLinux) VK_FORMAT_D24_UNORM_S8_UINT else VK_FORMAT_D32_SFLOAT_S8_UINT

    var validate = true
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

package afield.four.util

import org.lwjgl.system.Configuration
import org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT

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

    val spirv_cross_path = "./bin/spirv-cross-$target" + (if (isWindows) ".exe"  else "")

    var vulkan_library_name = "/Users/marc/VulkanSDK/1.4.328.1/macOS/lib/libvulkan.1.dylib"
    val useVulkanSDK = true
    val robustness = false
    val profiling = false
<<<<<<< HEAD
    val enableVR = false
=======
>>>>>>> 4866be3 (major debugging / introspection / keyframe push; major timing and profiling and validation push for Four)

    fun doVulkanSDK() {
        if (useVulkanSDK && isMac) Configuration.VULKAN_LIBRARY_NAME.set(vulkan_library_name)
    }

    var defaultDepthFormat: Int = if (isWindows || isLinux) VK_FORMAT_D24_UNORM_S8_UINT else VK_FORMAT_D32_SFLOAT_S8_UINT

    var validate = true
    var preferredDevice: String? = null
    var requestedImages = 3
    var vsync = true
    val fov: Double = 30.0
    val zNear: Double = 0.001
    val zFar: Double = 1000.0
}


package afield.four.util

import org.lwjgl.system.Configuration
import org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT
import org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT

object Options {

    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    val target = System.getProperty("os.name").lowercase().let {
        if ("mac" in it) "macosx"
        else if ("win" in it) "win-x64"
        else throw IllegalStateException("Unsupported OS: $it")
    }


    val spirv_cross_path = "./bin/spirv-cross-$target" + (if (isWindows) ".exe"  else "")

    var vulkan_library_name = "/Users/marc/VulkanSDK/1.4.328.1/macOS/lib/libvulkan.1.dylib"
    var useVulkanSDK = true
    var robustness = true

    fun doVulkanSDK() {
        if (useVulkanSDK && !isWindows) Configuration.VULKAN_LIBRARY_NAME.set(vulkan_library_name)
    }

    var defaultDepthFormat: Int = if (isWindows) VK_FORMAT_D24_UNORM_S8_UINT else VK_FORMAT_D32_SFLOAT_S8_UINT

    var validate = true
    var preferredDevice: String? = null
    var requestedImages = 3
    var vsync = true
    val fov: Double = 30.0
    val zNear: Double = 0.001
    val zFar: Double = 1000.0
}


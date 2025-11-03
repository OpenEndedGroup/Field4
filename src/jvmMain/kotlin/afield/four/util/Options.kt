package afield.four.util

import org.lwjgl.system.Configuration

object Options {

    val target = System.getProperty("os.name").lowercase().let {
        if ("mac" in it) "macosx"
        else if ("win" in it) "win-x64"
        else throw IllegalStateException("Unsupported OS: $it")
    }

    val spirv_cross_path = "./bin/spirv-cross-$target"

    var vulkan_library_name = "/Users/marc/VulkanSDK/1.4.328.1/macOS/lib/libvulkan.1.dylib"
    var useVulkanSDK = true
    var robustness = true

    fun doVulkanSDK() {
        if (useVulkanSDK) Configuration.VULKAN_LIBRARY_NAME.set(vulkan_library_name)
    }

    var validate = true
    var preferredDevice: String? = null
    var requestedImages = 3
    var vsync = true
    val fov: Double = 30.0
    val zNear: Double = 0.001
    val zFar: Double = 1000.0
}


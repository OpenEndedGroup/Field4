package afield.graphics.vulkan.targets

import org.lwjgl.vulkan.VK10.*

/** Prefer a linear high-precision eye image; use legacy formats only when the runtime requires it. */
internal object OpenXRColorFormats {
    fun select(formats: IntArray, highPrecision: Boolean = true): Int {
        require(formats.isNotEmpty()) { "No OpenXR swapchain formats" }
        val legacy = intArrayOf(
            VK_FORMAT_B8G8R8A8_SRGB,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM,
        )
        val precise = intArrayOf(
            VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_FORMAT_R16G16B16A16_UNORM,
            VK_FORMAT_A2B10G10R10_UNORM_PACK32,
            VK_FORMAT_A2R10G10B10_UNORM_PACK32,
        )
        return ((if (highPrecision) precise + legacy else legacy + precise).firstOrNull { it in formats }) ?: formats[0]
    }

    fun highPrecision(format: Int) =
        format in
            intArrayOf(
                VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_R16G16B16A16_UNORM,
                VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                VK_FORMAT_A2R10G10B10_UNORM_PACK32,
            )
}

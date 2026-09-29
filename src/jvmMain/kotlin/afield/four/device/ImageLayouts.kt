package afield.four.device

import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_PREINITIALIZED
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED

/**
 * `VK_KHR_unified_image_layouts`: with it, `GENERAL` is as good as any specialised layout for
 * every use, so a driver that offers it has nothing to gain from Field's per-use layouts, and a
 * layered driver such as KosmicKrisp has work to lose (a transition it must honour for Metal).
 *
 * Field keeps its *logical* layouts everywhere: [afield.four.texture.Image]'s per-command-buffer
 * tracking, every comparison that decides whether a barrier is needed, and every barrier itself.
 * Those still carry the synchronisation. Only the value handed to Vulkan changes, through [vkLayout],
 * at each call that takes a layout — barriers, descriptor and attachment infos, copies, blits,
 * clears. Because the mapping is a pure function of the logical layout, the two ends of every
 * transition agree, which is the one thing a partial conversion would get wrong.
 *
 * Kept as they are: `UNDEFINED` and `PREINITIALIZED` (they discard or describe initial contents)
 * and `PRESENT_SRC_KHR` (presentation still requires it).
 */
object ImageLayouts {
    /** Set by [Device] once `unifiedImageLayouts` is enabled; off with OpenXR, whose runtimes expect
     *  specific layouts on swapchain release. */
    @Volatile
    var unified: Boolean = false
        internal set

    fun vk(layout: Int): Int =
        if (!unified || layout == VK_IMAGE_LAYOUT_UNDEFINED || layout == VK_IMAGE_LAYOUT_PREINITIALIZED ||
            layout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        ) layout else VK_IMAGE_LAYOUT_GENERAL
}

/** The layout to hand Vulkan for logical [layout]; see [ImageLayouts]. */
fun vkLayout(layout: Int): Int = ImageLayouts.vk(layout)

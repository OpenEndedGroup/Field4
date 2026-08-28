package afield.four.browser

import afield.four.Renderer
import afield.four.util.OS
import afield.four.util.OSType
import org.lwjgl.vulkan.EXTQueueFamilyForeign.VK_QUEUE_FAMILY_FOREIGN_EXT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL

/**
 * Linux receiver for Electron nativePixmap dma-bufs. The producer must transfer every fd with
 * `SCM_RIGHTS`; copying the integer values from Electron's process is not a transfer.
 *
 * The first implementation supports one dma-buf allocation with one or more DRM layout planes,
 * which covers the accelerated RGB/BGRA browser surfaces this API negotiates. Distinct fds per
 * plane fail explicitly until disjoint multi-allocation binding is implemented and tested on Linux.
 */
class LinuxExternalBrowserSurface(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : CopiedExternalBrowserSurface(renderer, spec, releaseListener) {
    private val importedSlots = mutableMapOf<Int, ImportedImage>()
    private var importedStreamId: Long? = null

    init {
        require(OS == OSType.LINUX) { "LinuxExternalBrowserSurface requires Linux" }
        require(renderer.device.supportsDmaBufImport) {
            "LinuxExternalBrowserSurface requires VK_KHR_external_memory_fd, VK_EXT_external_memory_dma_buf, VK_EXT_image_drm_format_modifier, and VK_EXT_queue_family_foreign"
        }
    }

    override fun importFrame(frame: ExternalBrowserFrame): ImportedImage? {
        if (frame.streamId != importedStreamId) {
            importedSlots.values.forEach(::retire)
            importedSlots.clear()
            importedStreamId = frame.streamId
        }
        if (frame.handle == null) return importedSlots[frame.slot]
        val handle = frame.handle as? ExternalBrowserImageHandle.LinuxDmaBuf
            ?: return null

        val native = try {
            ExternalBrowserVulkanImports.dmaBuf(
                renderer.device,
                handle,
                spec.width,
                spec.height,
                vkFormat
            )
        } catch (t: Throwable) {
            // Vulkan consumes the fd only when allocation succeeds. Before that, it remains ours.
            runCatching { handle.close() }
            throw t
        }
        val imported = ImportedImage(
            frame.frameId,
            native.image,
            native::cleanup,
            layout = VK_IMAGE_LAYOUT_GENERAL,
            externalQueueFamily = VK_QUEUE_FAMILY_FOREIGN_EXT
        )
        importedSlots.put(frame.slot, imported)?.let(::retire)
        return imported
    }

    override fun cleanupImports() {
        importedSlots.values.forEach { it.cleanup() }
        importedSlots.clear()
    }
}

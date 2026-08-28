package afield.four.browser

import afield.four.Renderer
import afield.four.util.OS
import afield.four.util.OSType
import afield.logger.lprintln
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL
import org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL

/**
 * Windows receiver for Electron D3D11 shared textures. The producer must duplicate Electron's
 * process-local NT handle into Field before constructing
 * [ExternalBrowserImageHandle.WindowsD3D11NtHandle].
 *
 * This implementation is compile-reviewed from macOS and deliberately retains the proven shape of
 * the `src/personal` prototype. It still needs a Windows Vulkan validation/readback smoke before it
 * should be called runtime-proven.
 */
class WindowsExternalBrowserSurface(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : CopiedExternalBrowserSurface(renderer, spec, releaseListener) {
    private val importedSlots = mutableMapOf<Int, ImportedImage>()
    private var importedStreamId: Long? = null

    init {
        require(OS == OSType.WINDOWS) { "WindowsExternalBrowserSurface requires Windows" }
        require(renderer.device.supportsExternalMemoryWin32) {
            "WindowsExternalBrowserSurface requires VK_KHR_external_memory_win32"
        }
    }

    override fun importFrame(frame: ExternalBrowserFrame): ImportedImage? {
        if (frame.streamId != importedStreamId) {
            importedSlots.values.forEach(::retire)
            importedSlots.clear()
            importedStreamId = frame.streamId
        }
        if (frame.handle == null) return importedSlots[frame.slot]
        val handle = frame.handle as? ExternalBrowserImageHandle.WindowsD3D11NtHandle
            ?: return null

        val native = try {
            ExternalBrowserVulkanImports.d3d11Texture(
                renderer.device,
                handle.value,
                spec.width,
                spec.height,
                vkFormat
            )
        } finally {
            // Win32 imports retain the underlying allocation but do not consume the HANDLE.
            runCatching { handle.close() }
                .onFailure {
                    lprintln("warning,graphics", "could not close imported browser NT handle: ${it.message}")
                }
        }
        val imported = ImportedImage(
            frame.frameId,
            native.image,
            native::cleanup,
            layout = VK_IMAGE_LAYOUT_GENERAL,
            externalQueueFamily = VK_QUEUE_FAMILY_EXTERNAL
        )
        importedSlots.put(frame.slot, imported)?.let(::retire)
        return imported
    }

    override fun cleanupImports() {
        importedSlots.values.forEach { it.cleanup() }
        importedSlots.clear()
    }
}

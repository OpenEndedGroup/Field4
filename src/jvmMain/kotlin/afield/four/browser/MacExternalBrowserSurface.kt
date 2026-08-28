package afield.four.browser

import afield.four.Renderer
import afield.four.texture.Image
import afield.four.texture.MetalIOSurface
import afield.four.util.OS
import afield.four.util.OSType
import afield.logger.lprintln
import org.lwjgl.vulkan.EXTMetalObjects
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
import org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT

/**
 * MoltenVK implementation of [ExternalBrowserSurface]. A native producer transfers a Mach send
 * right for Electron's process-local IOSurface; this receiver resolves and imports it, while
 * [CopiedExternalBrowserSurface] copies into a stable image and acknowledges only after the fence.
 */
class MacExternalBrowserSurface(
    renderer: Renderer,
    spec: ExternalBrowserSurfaceSpec,
    releaseListener: BrowserFrameReleaseListener = BrowserFrameReleaseListener { }
) : CopiedExternalBrowserSurface(renderer, spec, releaseListener) {
    private val importedSlots = mutableMapOf<Int, ImportedImage>()
    private var importedStreamId: Long? = null

    init {
        require(OS == OSType.MACOS) { "MacExternalBrowserSurface requires macOS/MoltenVK" }
        require(renderer.device.supportsMetalObjects) {
            "MacExternalBrowserSurface requires ${EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME}"
        }
    }

    override fun importFrame(frame: ExternalBrowserFrame): ImportedImage? {
        if (frame.streamId != importedStreamId) {
            importedSlots.values.forEach(::retire)
            importedSlots.clear()
            importedStreamId = frame.streamId
        }
        if (frame.handle == null) return importedSlots[frame.slot]

        val (identity, ioSurfaceRef) = try {
            when (val handle = frame.handle) {
                is ExternalBrowserImageHandle.MacIOSurfaceMachPort -> {
                    val ref = MetalIOSurface.lookupFromMachPort(handle.value)
                    MetalIOSurface.ioSurfaceId(ref) to ref
                }

                is ExternalBrowserImageHandle.MacIOSurfaceId ->
                    handle.value to MetalIOSurface.lookup(handle.value)

                else -> return null
            }
        } finally {
            // Lookup retained the IOSurface; the transport right is no longer needed here.
            runCatching { frame.handle.close() }
                .onFailure {
                    lprintln("warning,graphics", "could not release browser transport handle: ${it.message}")
                }
        }

        importedSlots[frame.slot]?.takeIf { it.identity == identity }?.let {
            // A repeated Mach port still transferred a fresh send right and lookup retain.
            if (ioSurfaceRef != 0L) MetalIOSurface.release(ioSurfaceRef)
            return it
        }
        if (ioSurfaceRef == 0L) return null

        val image = try {
            Image(
                renderer.device,
                Image.ImageData()
                    .width(spec.width)
                    .height(spec.height)
                    .format(vkFormat)
                    .mipLevels(1)
                    .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                    .debugName("external-browser-iosurface slot=${frame.slot} handle=$identity")
                    .also { it.importMetalIOSurface = ioSurfaceRef }
            )
        } catch (t: Throwable) {
            MetalIOSurface.release(ioSurfaceRef)
            throw t
        }
        val imported = ImportedImage(identity, image.vkImage, {
            image.cleanup()
            MetalIOSurface.release(ioSurfaceRef)
        })
        importedSlots.put(frame.slot, imported)?.let(::retire)
        return imported
    }

    override fun cleanupImports() {
        importedSlots.values.forEach { it.cleanup() }
        importedSlots.clear()
    }
}

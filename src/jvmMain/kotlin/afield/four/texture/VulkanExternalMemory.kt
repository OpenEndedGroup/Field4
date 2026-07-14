package afield.four.texture

import afield.four.targets.RenderToTexture

/**
 * Bridges a Field RTT color image to the fd handle the `field-encode-linux` co-process needs to
 * import it into CUDA (see `native/field-encode-linux/` and `dev_docs/vision_frontend.md` -> "The
 * sender seam", Linux row).
 *
 * The chain is: render into an RTT created with `exportOpaqueFd = true`
 * -> [Image.exportOpaqueFd] (VK_KHR_external_memory_fd) yields a POSIX fd, open in *this* JVM
 * process -> [handle] packages it with the exact allocation size (CUDA's cuImportExternalMemory
 * requires an exact match) and the row pitch (NVENC's NV_ENC_REGISTER_RESOURCE.pitch — the
 * image's *actual*, possibly alignment-padded, linear layout, not width*bytesPerPixel).
 *
 * The fd number itself never travels over the JSON control channel — a raw fd is a per-process
 * file descriptor number, meaningless to another process by value alone (unlike a Mac
 * IOSurfaceID, which is a real system-wide handle). [FdHandle.fd] instead feeds
 * [UnixFdPass.sendFds], which hands the real kernel object to the helper via SCM_RIGHTS; only
 * `size`/`rowPitch` travel in `FieldEncodeProcess.open(fdSurfaces = ...)`'s JSON.
 *
 * Unlike the Mac side's `MetalIOSurface` (which wraps raw IOSurface-framework calls reached via
 * dynamic symbol lookup, because they aren't Vulkan calls), everything here — `vkGetMemoryFdKHR` —
 * is a normal LWJGL Vulkan binding, so it lives directly on [Image]; this object is just the
 * `RenderToTexture`-level convenience, mirroring [MetalIOSurface.ioSurfaceIds]'s role.
 *
 * Linux/NVIDIA only. The fd stays open in this process for the streamer's session; the helper
 * only ever receives a duplicate of the underlying kernel object via SCM_RIGHTS — this process
 * must not close its own fd while streaming.
 */
object VulkanExternalMemory {

    data class FdHandle(val fd: Int, val size: Long, val rowPitch: Long)

    /** [image] must have been created with [Image.ImageData.exportOpaqueFd] = true. */
    fun handle(image: Image): FdHandle = FdHandle(
        fd = image.exportOpaqueFd(),
        size = image.memorySize,
        rowPitch = image.linearRowPitch
    )

    /**
     * The fd handles for all of an RTT's internally-created color attachments, in slot order
     * (skipping any slot backed by an external view, which has no Image of its own to export).
     * This is the list to hand to `FieldEncodeProcess.open(fdSurfaces = ...)`.
     */
    fun handles(rtt: RenderToTexture): List<FdHandle> =
        rtt.colorImageRefs.mapNotNull { image -> image?.let { handle(it) } }
}

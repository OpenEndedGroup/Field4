package afield.graphics.texture

import afield.graphics.vulkan.targets.RenderToTexture

/**
 * Exports a render-to-texture color image as a POSIX file descriptor, so the Linux
 * `field-encode-linux` helper can import it into CUDA and encode it with NVENC.
 *
 * The chain: render into an RTT created with `exportOpaqueFd = true`, then [Image.exportOpaqueFd]
 * (VK_KHR_external_memory_fd) yields an fd open in this JVM process, and
 * [VulkanExternalMemory.handle] packages it with the exact allocation size (CUDA's
 * cuImportExternalMemory requires an exact match) and the row pitch (NVENC's
 * NV_ENC_REGISTER_RESOURCE.pitch, which is the image's actual, possibly alignment-padded, linear
 * layout rather than width times bytes per pixel).
 *
 * The fd number never travels over the JSON control channel, because a raw fd means nothing to
 * another process by value; a Mac IOSurfaceID, by contrast, is a system-wide handle.
 * [VulkanExternalMemory.FdHandle.fd] feeds [UnixFdPass.sendFds], which passes the kernel object to
 * the helper over SCM_RIGHTS, and only `size` and `rowPitch` go into the JSON of
 * `FieldEncodeProcess.open(fdSurfaces = ...)`.
 *
 * `vkGetMemoryFdKHR` is an ordinary LWJGL Vulkan binding, so the export itself lives on [Image];
 * this object is the `RenderToTexture`-level convenience, the counterpart of
 * [MetalIOSurface.ioSurfaceIds] on macOS.
 *
 * Linux/NVIDIA only. The fd stays open in this process for the streamer's session, and the helper
 * receives a duplicate of the kernel object, so this process must not close its own fd while
 * streaming. Design notes: `dev_docs/vision_frontend.md`, "The sender seam", Linux row.
 */

/** Packages an RTT's exported color images as fd handles for the Linux encode helper. */
object VulkanExternalMemory {

    /** An exported image: its fd, exact allocation [size] in bytes, and linear [rowPitch]. */
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

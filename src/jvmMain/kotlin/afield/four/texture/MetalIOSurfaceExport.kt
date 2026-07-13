package afield.four.texture

import afield.four.targets.RenderToTexture
import org.lwjgl.system.APIUtil.apiCreateLibrary
import org.lwjgl.system.JNI
import org.lwjgl.system.SharedLibrary

/**
 * Bridges a Field RTT color image to a global `IOSurfaceID` that the `field-encode` co-process
 * can attach to with `IOSurfaceLookup` (see `native/field-encode/` and
 * `dev_docs/vision_frontend.md` -> "The sender seam").
 *
 * The chain is: render into an RTT created with `exportMetalIOSurface = true`
 * -> [Image.exportMetalIOSurfaceRef] (VK_EXT_metal_objects) yields the `IOSurfaceRef` ->
 * [ioSurfaceId] returns its process-global `IOSurfaceID`. Pass that id to
 * `FieldEncodeProcess.open(surfaces = ...)`. macOS / MoltenVK only.
 *
 * `IOSurfaceGetID` is reached via a tiny dynamic-lookup call (LWJGL [JNI]) into the already
 * process-loaded IOSurface framework — no JNI shim library of our own.
 */
object MetalIOSurface {

    private val ioSurfaceLibrary: SharedLibrary by lazy {
        // The framework is already loaded in-process by MoltenVK; open it for symbol lookup.
        // Absolute path first (robust), bare name as a fallback.
        runCatching { apiCreateLibrary("/System/Library/Frameworks/IOSurface.framework/IOSurface") }
            .getOrElse { apiCreateLibrary("IOSurface") }
    }

    private val pIOSurfaceGetID: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetID") }

    /**
     * `IOSurfaceID IOSurfaceGetID(IOSurfaceRef)` — returns the process-global id as an unsigned
     * Long (the raw C return is a uint32). 0 for a null ref.
     */
    fun ioSurfaceId(ioSurfaceRef: Long): Long {
        if (ioSurfaceRef == 0L) return 0L
        return JNI.callPI(ioSurfaceRef, pIOSurfaceGetID).toLong() and 0xFFFF_FFFFL
    }

    /** Export + resolve the global id for a single RTT color [image]. 0 if export failed. */
    fun ioSurfaceId(image: Image): Long = ioSurfaceId(image.exportMetalIOSurfaceRef())

    /**
     * The global `IOSurfaceID`s for all of an RTT's internally-created color attachments, in slot
     * order (0 for any slot backed by an external view or that failed to export). This is the list
     * to hand to `FieldEncodeProcess.open(surfaces = ...)`.
     *
     * The RTT must have been created with `exportMetalIOSurface = true`.
     */
    fun ioSurfaceIds(rtt: RenderToTexture): List<Long> =
        rtt.colorImageRefs.map { image -> if (image == null) 0L else ioSurfaceId(image) }
}

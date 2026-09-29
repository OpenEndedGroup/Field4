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
    private val pIOSurfaceLookup: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceLookup") }
    private val pIOSurfaceLookupFromMachPort: Long by lazy {
        ioSurfaceLibrary.getFunctionAddress("IOSurfaceLookupFromMachPort")
    }
    private val pIOSurfaceCreateMachPort: Long by lazy {
        ioSurfaceLibrary.getFunctionAddress("IOSurfaceCreateMachPort")
    }

    private val pIOSurfaceLock: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceLock") }
    private val pIOSurfaceUnlock: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceUnlock") }
    private val pIOSurfaceGetBaseAddress: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetBaseAddress") }
    private val pIOSurfaceGetBytesPerRow: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetBytesPerRow") }
    private val pIOSurfaceGetAllocSize: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetAllocSize") }
    private val pIOSurfaceGetWidth: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetWidth") }
    private val pIOSurfaceGetHeight: Long by lazy { ioSurfaceLibrary.getFunctionAddress("IOSurfaceGetHeight") }

    /** `kIOSurfaceLockReadOnly`: the lock's owner promises not to write through the CPU mapping. */
    const val LOCK_READ_ONLY = 0x1

    /**
     * `IOSurfaceLock(ref, options, NULL)`: pin the surface's CPU mapping so [baseAddress] is valid.
     * The address is only defined while the surface is locked. Balance with [unlock].
     */
    fun lock(ioSurfaceRef: Long, options: Int = 0) {
        val result = JNI.callPPI(ioSurfaceRef, options, 0L, pIOSurfaceLock)
        check(result == 0) { "IOSurfaceLock failed: $result" }
    }

    fun unlock(ioSurfaceRef: Long, options: Int = 0) {
        val result = JNI.callPPI(ioSurfaceRef, options, 0L, pIOSurfaceUnlock)
        check(result == 0) { "IOSurfaceUnlock failed: $result" }
    }

    fun baseAddress(ioSurfaceRef: Long): Long = JNI.callPP(ioSurfaceRef, pIOSurfaceGetBaseAddress)
    fun bytesPerRow(ioSurfaceRef: Long): Long = JNI.callPP(ioSurfaceRef, pIOSurfaceGetBytesPerRow)
    fun allocSize(ioSurfaceRef: Long): Long = JNI.callPP(ioSurfaceRef, pIOSurfaceGetAllocSize)
    fun width(ioSurfaceRef: Long): Int = JNI.callPP(ioSurfaceRef, pIOSurfaceGetWidth).toInt()
    fun height(ioSurfaceRef: Long): Int = JNI.callPP(ioSurfaceRef, pIOSurfaceGetHeight).toInt()

    private val coreFoundationLibrary: SharedLibrary by lazy {
        runCatching { apiCreateLibrary("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation") }
            .getOrElse { apiCreateLibrary("CoreFoundation") }
    }
    private val pCFRelease: Long by lazy { coreFoundationLibrary.getFunctionAddress("CFRelease") }

    private val systemLibrary: SharedLibrary by lazy { apiCreateLibrary("/usr/lib/libSystem.B.dylib") }
    private val pMachTaskSelf: Long by lazy { systemLibrary.getFunctionAddress("mach_task_self") }
    private val pMachPortDeallocate: Long by lazy { systemLibrary.getFunctionAddress("mach_port_deallocate") }

    /**
     * `IOSurfaceRef IOSurfaceLookup(IOSurfaceID)` — resolve a process-global id back to a local
     * IOSurfaceRef pointer (0 if not found). The inverse of [ioSurfaceId]: use it to *import* a
     * surface another process created (e.g. one the `field-encode` helper allocated and returned),
     * then pass the pointer to `RTTParameters.importMetalIOSurface`.
     */
    fun lookup(id: Long): Long {
        if (id == 0L) return 0L
        return JNI.callPP(id and 0xFFFF_FFFFL, pIOSurfaceLookup)
    }

    /**
     * Resolve an IOSurface send right that has already been transferred into this process. The
     * returned IOSurfaceRef is retained; [release] balances it. This does not consume [port].
     */
    fun lookupFromMachPort(port: Int): Long {
        if (port == 0) return 0L
        return JNI.callP(port, pIOSurfaceLookupFromMachPort)
    }

    /**
     * Create a send right for [ioSurfaceRef]. The returned port name is local to this process;
     * transfer the right in a Mach message rather than sending its integer value over ordinary IPC.
     * The caller owns the right and must eventually call [deallocateMachPort].
     */
    fun createMachPort(ioSurfaceRef: Long): Int {
        if (ioSurfaceRef == 0L) return 0
        return JNI.callPI(ioSurfaceRef, pIOSurfaceCreateMachPort)
    }

    /** Drop this process's send right after [lookupFromMachPort] retained the IOSurface. */
    fun deallocateMachPort(port: Int) {
        if (port == 0) return
        val task = JNI.callI(pMachTaskSelf)
        val result = JNI.callI(task, port, pMachPortDeallocate)
        check(result == 0) { "mach_port_deallocate failed: $result" }
    }

    /**
     * Balances the retain performed by [lookup] or [lookupFromMachPort]. Call only after every
     * Vulkan object importing the returned IOSurfaceRef has been destroyed.
     */
    fun release(ioSurfaceRef: Long) {
        if (ioSurfaceRef != 0L) JNI.callPV(ioSurfaceRef, pCFRelease)
    }

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

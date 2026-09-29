package afield.four.texture

import afield.four.device.nameObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import afield.four.device.Device
import afield.four.util.memoryTypeFromProperties
import afield.four.util.vkCheck
import afield.four.util.AutoDisposer
import org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2
import org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2

class Image(private val device: Device, val imageData: ImageData) {
    var format: Int = 0
    var mipLevels: Int = 0
    var vkImage: Long = 0
    var vkMemory: Long = 0

    // Set only when [ImageData.exportOpaqueFd] is true (Linux/NVENC path — see
    // VulkanExternalMemory.kt). [memorySize] is the exact allocation size CUDA's
    // cuImportExternalMemory requires; [linearRowPitch] is the row pitch NVENC's
    // NV_ENC_REGISTER_RESOURCE.pitch needs, queried from the image's actual (possibly
    // alignment-padded) linear layout rather than assumed as width*bytesPerPixel.
    var memorySize: Long = 0
        private set
    var linearRowPitch: Long = 0
        private set

    // Set only when [ImageData.exportDmaBuf] is true (Linux/dma-buf path — see
    // LinuxDmaBufExport.kt). The driver, not this code, chooses which of the offered DRM format
    // modifiers to use, so the choice has to be read back before the handle can describe itself to
    // an importer; [drmPlaneLayouts] is the memory-plane layout that modifier implies.
    var drmFormatModifier: Long = 0
        private set
    var drmPlaneLayouts: List<DmaBufPlaneLayout> = emptyList()
        private set

    private val autoDisposeToken = AutoDisposer.register(this, "Image(format=${imageData.format}, ${imageData.width}x${imageData.height})", owner = device) { /* set after init */ }

    init {
        MemoryStack.stackPush().use { stack ->
            this.format = imageData.format
            this.mipLevels = imageData.mipLevels

            val imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .flags(
                    imageData.flags or
                        if (imageData.aliasSingleLayerDescriptors && device.supportsMaintenance11) {
                            KHRMaintenance11.VK_IMAGE_CREATE_ALIAS_SINGLE_LAYER_DESCRIPTOR_BIT_KHR
                        } else {
                            0
                        }
                )
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(format)
                .extent { it: VkExtent3D ->
                    it
                        .width(imageData.width)
                        .height(imageData.height)
                        .depth(1)
                }
                .mipLevels(mipLevels)
                .arrayLayers(imageData.arrayLayers)
                .samples(imageData.sampleCount)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                // NV_ENC_INPUT_RESOURCE_TYPE_CUDADEVICEPTR (the Linux capture path — see
                // VulkanExternalMemory.kt / native/field-encode-linux) needs a flat linear buffer +
                // row pitch, not NVIDIA's private optimal-tiling layout, so exported images use
                // LINEAR tiling. Costs a little render throughput vs OPTIMAL; irrelevant at the
                // resolutions/framerates this streams (see dev_docs/vision_frontend.md's "ship the
                // fence first" posture).
                .tiling(
                    when {
                        imageData.exportOpaqueFd -> VK10.VK_IMAGE_TILING_LINEAR
                        // A dma-buf is only interpretable by another driver if a DRM format
                        // modifier describes its tiling, and that tiling mode is how the image
                        // acquires one. Unlike LINEAR above this costs no render throughput: the
                        // modifier the driver picks is normally its own optimal layout.
                        imageData.exportDmaBuf -> EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT
                        else -> VK10.VK_IMAGE_TILING_OPTIMAL
                    }
                )
                .usage(imageData.usage)
            if(imageData.queueFamilies.distinct().size>1)imageCreateInfo
                .sharingMode(VK10.VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(*imageData.queueFamilies))

            if(imageData.opticalFlowUsage!=0)imageCreateInfo.pNext(VkOpticalFlowImageFormatInfoNV.calloc(stack).`sType$Default`().usage(imageData.opticalFlowUsage).address())

            // Back the image with an externally-created IOSurface (MoltenVK), or make its backing
            // MTLTexture IOSurface-exportable. Both are for zero-copy hand-off to a hardware video
            // encoder; import wins because a surface born via IOSurfaceCreate carries a real
            // pixelFormat (CoreVideo-wrappable), whereas MoltenVK's export leaves pixelFormat = 0.
            if (imageData.importMetalIOSurface != 0L || imageData.exportMetalIOSurface) {
                device.requireMetalObjects("An IOSurface-backed image")
            }
            if (imageData.importMetalIOSurface != 0L) {
                val importInfo = VkImportMetalIOSurfaceInfoEXT.calloc(stack)
                    .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_IMPORT_METAL_IO_SURFACE_INFO_EXT)
                    .ioSurface(imageData.importMetalIOSurface)
                imageCreateInfo.pNext(importInfo.address())
            } else if (imageData.exportMetalIOSurface) {
                val exportInfo = VkExportMetalObjectCreateInfoEXT.calloc(stack)
                    .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECT_CREATE_INFO_EXT)
                    .exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_IOSURFACE_BIT_EXT)
                imageCreateInfo.pNext(exportInfo.address())
            } else if (imageData.exportOpaqueFd) {
                // Linux/NVENC path: VK_KHR_external_memory_fd, same-vendor "opaque fd" handle type
                // (not VK_EXT_external_memory_dma_buf's generic cross-vendor DMA_BUF_BIT — that one
                // needs a DRM format modifier to describe tiling to a non-NVIDIA importer; opaque_fd
                // is the NVIDIA-Vulkan-to-NVIDIA-CUDA same-GPU handshake and needs none of that).
                val extImageInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
                imageCreateInfo.pNext(extImageInfo.address())
            } else if (imageData.exportDmaBuf) {
                // Linux/dma-buf path: the generic cross-vendor handle type a browser compositor
                // publishes (see LinuxDmaBufExport.kt). Offer every modifier this device can both
                // build a `usage` image from and share as a dma-buf, and let the driver choose —
                // naming one modifier here would be guessing on the importer's behalf.
                val modifiers = LinuxDmaBufExport.shareableModifiers(device, format, imageData.usage)
                check(modifiers.isNotEmpty()) {
                    "no DRM format modifier on this device can export format $format with usage 0x${imageData.usage.toString(16)} as a dma-buf"
                }
                val modifierList = VkImageDrmFormatModifierListCreateInfoEXT.calloc(stack)
                    .sType(EXTImageDrmFormatModifier.VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT)
                    .pDrmFormatModifiers(stack.longs(*modifiers.toLongArray()))
                val extImageInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                    .handleTypes(EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
                    .pNext(modifierList.address())
                imageCreateInfo.pNext(extImageInfo.address())
            }

            val lp = stack.mallocLong(1)
            vkCheck(
                VK10.vkCreateImage(device.vkDevice, imageCreateInfo, null, lp),
                "Failed to create image"
            )
            vkImage = lp[0]

            // Get memory requirements for this object
            val memReqs = VkMemoryRequirements.calloc(stack)
            VK10.vkGetImageMemoryRequirements(device.vkDevice, vkImage, memReqs)
            memorySize = memReqs.size()

            // For an imported IOSurface, MoltenVK backs the MTLTexture's pixels with the surface, so
            // this allocate+bind may be redundant. Log the reported size once so we can decide
            // whether to skip it (0 ⇒ safe to skip; non-zero ⇒ MoltenVK still wants bound memory).
            if (imageData.importMetalIOSurface != 0L) {
                afield.logger.lprintln("info,field-stream",
                    "imported-IOSurface image: memReqs.size=${memReqs.size()} (binding anyway; pixels live in the IOSurface)")
            }

            // Select memory size and type
            val memAlloc = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(
                    memoryTypeFromProperties(
                        device.physicalDevice,
                        memReqs.memoryTypeBits(),
                        imageData.memoryPropertyFlags
                    )
                )

            // The image-create-info chain above only declares the image *shareable*; the backing
            // memory's own allocation must separately opt in to export, or vkGetMemoryFdKHR fails.
            if (imageData.exportOpaqueFd) {
                // Dedicated allocation is required here, not optional: NVIDIA's Vulkan driver
                // internally tags opaque-fd memory born from an image export as dedicated, and
                // cuImportExternalMemory (CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD) rejects the
                // import with CUDA_ERROR_UNKNOWN (999) if the Vulkan side didn't also declare it
                // dedicated via this struct — verified by hitting exactly that error without it.
                val dedicatedAllocInfo = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                    .image(vkImage)
                val exportAllocInfo = VkExportMemoryAllocateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
                    .pNext(dedicatedAllocInfo.address())
                memAlloc.pNext(exportAllocInfo.address())
            } else if (imageData.exportDmaBuf) {
                // Dedicated is not optional here either: NVIDIA reports its block-linear modifiers
                // as VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT for the dma-buf handle type, so
                // a suballocated export would be invalid on exactly the modifiers worth using.
                val dedicatedAllocInfo = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                    .image(vkImage)
                val exportAllocInfo = VkExportMemoryAllocateInfo.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                    .handleTypes(EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
                    .pNext(dedicatedAllocInfo.address())
                memAlloc.pNext(exportAllocInfo.address())
            }

            // Allocate memory
            vkCheck(VK10.vkAllocateMemory(device.vkDevice, memAlloc, null, lp), "Failed to allocate memory")
            vkMemory = lp[0]

            // Bind memory
            vkCheck(
                VK10.vkBindImageMemory(device.vkDevice, vkImage, vkMemory, 0),
                "Failed to bind image memory"
            )

            if (imageData.exportOpaqueFd) {
                val subresource = VkImageSubresource.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .arrayLayer(0)
                val layout = VkSubresourceLayout.calloc(stack)
                VK10.vkGetImageSubresourceLayout(device.vkDevice, vkImage, subresource, layout)
                linearRowPitch = layout.rowPitch()
            }

            if (imageData.exportDmaBuf) {
                val chosen = VkImageDrmFormatModifierPropertiesEXT.calloc(stack)
                    .sType(EXTImageDrmFormatModifier.VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT)
                vkCheck(
                    EXTImageDrmFormatModifier.vkGetImageDrmFormatModifierPropertiesEXT(
                        device.vkDevice,
                        vkImage,
                        chosen
                    ),
                    "vkGetImageDrmFormatModifierPropertiesEXT"
                )
                drmFormatModifier = chosen.drmFormatModifier()
                // shareableModifiers only offers single-memory-plane layouts, so the driver cannot
                // have chosen one that needs more.
                drmPlaneLayouts = LinuxDmaBufExport.planeLayouts(device, vkImage, planeCount = 1)
            }
        }
        imageData.debugName.takeIf { it.isNotBlank() }?.let {
            device.nameObject(vkImage, VK10.VK_OBJECT_TYPE_IMAGE, it)
        }
        autoDisposeToken.updateCleanup(snapshotCleanup())
    }

    fun cleanup() {
        if (!autoDisposeToken.disable()) return
        // cachedOpaqueFd (if any) is intentionally not close()'d here: plain JVM code has no public
        // way to close a raw fd number (FileDescriptor has no public int constructor pre-reflection
        // hacks). Ring sizes are 2-3 per streamer, so this leaks a handful of fds for the process's
        // lifetime rather than per-stream-restart — acceptable; revisit only if that stops being true.
        cachedOpaqueFd = null
        VK10.vkDestroyImage(device.vkDevice, vkImage, null)
        VK10.vkFreeMemory(device.vkDevice, vkMemory, null)
    }

    private fun snapshotCleanup(): () -> Unit {
        val dev = device.vkDevice
        val img = vkImage
        val mem = vkMemory
        return {
            VK10.vkDestroyImage(dev, img, null)
            VK10.vkFreeMemory(dev, mem, null)
        }
    }

    // Per-command-buffer layout tracking (optional; best-effort)
    @Volatile
    private var layoutByCommandBuffer = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    // Vulkan image layout is global to the image subresource, not to a command buffer. Keep the
    // most recently recorded transition as well so consumers recording on another command buffer
    // (notably OpenXR) can transition from the producer's actual layout.
    @Volatile
    private var lastKnownLayout: Int? = null

    fun setLayoutFor(commandBufferHandle: Long, layout: Int) {
        layoutByCommandBuffer[commandBufferHandle] = layout
        lastKnownLayout = layout
    }

    fun getLayoutFor(commandBufferHandle: Long): Int? = layoutByCommandBuffer[commandBufferHandle]

    fun getLastKnownLayout(): Int? = lastKnownLayout

    /**
     * Export the IOSurfaceRef backing this image's MTLTexture (MoltenVK, VK_EXT_metal_objects).
     * Requires [ImageData.exportMetalIOSurface] = true at creation and the device to have enabled
     * VK_EXT_metal_objects. Returns the IOSurfaceRef pointer (pass to [afield.four.texture.MetalIOSurface.ioSurfaceId]),
     * or 0 if the surface could not be exported.
     */
    fun exportMetalIOSurfaceRef(): Long {
        device.requireMetalObjects("Exporting an image's IOSurface")
        MemoryStack.stackPush().use { stack ->
            val ioInfo = VkExportMetalIOSurfaceInfoEXT.calloc(stack)
                .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_IO_SURFACE_INFO_EXT)
                .image(vkImage)
            val objects = VkExportMetalObjectsInfoEXT.calloc(stack)
                .sType(EXTMetalObjects.VK_STRUCTURE_TYPE_EXPORT_METAL_OBJECTS_INFO_EXT)
                .pNext(ioInfo.address())
            EXTMetalObjects.vkExportMetalObjectsEXT(device.vkDevice, objects)
            return ioInfo.ioSurface()
        }
    }

    private var cachedOpaqueFd: Int? = null

    /**
     * Export this image's backing memory as an opaque POSIX fd (Linux, VK_KHR_external_memory_fd —
     * see VulkanExternalMemory.kt). Requires [ImageData.exportOpaqueFd] = true at creation. Per the
     * spec each call to vkGetMemoryFdKHR transfers ownership of a *new* fd to the caller, so this
     * caches and returns the same fd on repeated calls — call [close] to release it, don't just
     * drop the value. The fd stays valid (and open in this JVM process) for the streamer's whole
     * session; the helper process only ever gets a *duplicate* via /proc/<pid>/fd/<fd> (see
     * WIRE_FORMAT.md's Linux addendum), never this one.
     */
    fun exportOpaqueFd(): Int {
        cachedOpaqueFd?.let { return it }
        MemoryStack.stackPush().use { stack ->
            val info = VkMemoryGetFdInfoKHR.calloc(stack)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                .memory(vkMemory)
                .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            val fd = IntArray(1)
            vkCheck(KHRExternalMemoryFd.vkGetMemoryFdKHR(device.vkDevice, info, fd), "vkGetMemoryFdKHR")
            cachedOpaqueFd = fd[0]
            return fd[0]
        }
    }

    /**
     * Export this image's backing memory as a POSIX dma-buf fd (Linux, VK_EXT_external_memory_dma_buf
     * — see LinuxDmaBufExport.kt). Requires [ImageData.exportDmaBuf] = true at creation.
     *
     * Unlike [exportOpaqueFd] this deliberately does *not* cache. A dma-buf fd is normally handed
     * to an importer that consumes ownership of it, so one cached fd shared between two imports
     * would be a double free; each call instead returns a fresh fd the caller owns. Pair it with
     * [drmFormatModifier] and [drmPlaneLayouts], without which no other driver can interpret the
     * bytes.
     */
    fun exportDmaBufFd(): Int {
        require(imageData.exportDmaBuf) { "exportDmaBufFd requires ImageData.exportDmaBuf = true" }
        return LinuxDmaBufExport.exportFd(device, vkMemory)
    }

    class ImageData {
        var arrayLayers: Int = 1
        internal var format: Int
        var flags: Int = 0
        var height: Int = 0
        var aliasSingleLayerDescriptors: Boolean = false
        var debugName: String = ""
        var mipLevels: Int = 1
        var memoryPropertyFlags: Int = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        var sampleCount: Int = 1
        var usage: Int = 0
        /** Concurrent ownership for explicit cross-queue adapters; empty keeps exclusive sharing. */
        var queueFamilies: IntArray = intArrayOf()
        /** Optional VK_NV_optical_flow format usage, independent of ordinary image usage. */
        var opticalFlowUsage: Int = 0

        /**
         * MoltenVK only: create the backing MTLTexture as IOSurface-exportable
         * (chains VkExportMetalObjectCreateInfoEXT). Enables [Image.exportMetalIOSurfaceRef].
         */
        var exportMetalIOSurface: Boolean = false

        /**
         * MoltenVK only: back this image with the given externally-created IOSurface (its
         * IOSurfaceRef pointer), via VkImportMetalIOSurfaceInfoEXT. Takes precedence over
         * [exportMetalIOSurface]. The IOSurface's format/size must match this image's.
         */
        var importMetalIOSurface: Long = 0

        /**
         * Linux/NVENC only: create this image's backing memory as exportable via
         * VK_KHR_external_memory_fd (opaque-fd handle type), forces VK_IMAGE_TILING_LINEAR (see
         * [Image.linearRowPitch]), and enables [Image.exportOpaqueFd]. Analogous to
         * [exportMetalIOSurface] on the Mac path — see VulkanExternalMemory.kt.
         */
        var exportOpaqueFd: Boolean = false

        /**
         * Linux only: create this image with a driver-chosen DRM format modifier and dma-buf
         * exportable backing memory (VK_EXT_image_drm_format_modifier +
         * VK_EXT_external_memory_dma_buf), and enable [Image.exportDmaBufFd]. This is the handle a
         * browser compositor publishes, and the one the external-browser receiver imports; see
         * LinuxDmaBufExport.kt for why it is not the same thing as [exportOpaqueFd].
         */
        var exportDmaBuf: Boolean = false
        var width: Int = 0

        init {
            this.format = VK10.VK_FORMAT_R8G8B8A8_SRGB
        }

        fun arrayLayers(arrayLayers: Int): ImageData {
            this.arrayLayers = arrayLayers
            return this
        }

        fun format(format: Int): ImageData {
            this.format = format
            return this
        }

        fun flags(flags: Int): ImageData {
            this.flags = flags
            return this
        }

        fun height(height: Int): ImageData {
            this.height = height
            return this
        }

        fun debugName(debugName: String): ImageData {
            this.debugName = debugName
            return this
        }

        fun aliasSingleLayerDescriptors(aliasSingleLayerDescriptors: Boolean = true): ImageData {
            this.aliasSingleLayerDescriptors = aliasSingleLayerDescriptors
            return this
        }

        fun mipLevels(mipLevels: Int): ImageData {
            this.mipLevels = mipLevels
            return this
        }

        fun memoryPropertyFlags(memoryPropertyFlags: Int): ImageData {
            this.memoryPropertyFlags = memoryPropertyFlags
            return this
        }

        fun sampleCount(sampleCount: Int): ImageData {
            this.sampleCount = sampleCount
            return this
        }

        fun usage(usage: Int): ImageData {
            this.usage = usage
            return this
        }

        fun width(width: Int): ImageData {
            this.width = width
            return this
        }

        val bytesPerPixel: Int
            get() = vkFormatBytesPerPixel(format, debugName.ifEmpty { "ImageData" })
    }
}


// --- format size ---

/**
 * Bytes occupied by one pixel of [format], or null if this table does not know the format.
 *
 * Null means "no answer", not "zero": block-compressed formats (BC/ETC/ASTC) have no per-pixel
 * size at all, and depth/stencil formats are not addressable by the flat `width * height *
 * bytesPerPixel` arithmetic every caller here performs. Both must fall through rather than be
 * given a plausible-looking number.
 *
 * This is the single source of truth. [Image.ImageData.bytesPerPixel], `GpuImageCapture` and
 * VideoTexture's plane staging each carried their own copy of this table, and they had already
 * drifted apart once — the capture copy was missing every one- and two-channel float format,
 * VideoTexture's was the only one that knew R16G16_UNORM.
 */
fun vkFormatBytesPerPixelOrNull(format: Int): Int? = when (format) {
    VK10.VK_FORMAT_R8_UNORM -> 1
    VK10.VK_FORMAT_R8G8_UNORM -> 2
    VK10.VK_FORMAT_R16_SFLOAT,
    VK10.VK_FORMAT_R16_UNORM,
    VK10.VK_FORMAT_R16_SNORM -> 2
    VK10.VK_FORMAT_R8G8B8_UNORM,
    VK10.VK_FORMAT_B8G8R8_UNORM -> 3
    VK10.VK_FORMAT_R8G8B8A8_UNORM,
    VK10.VK_FORMAT_R8G8B8A8_SRGB,
    VK10.VK_FORMAT_B8G8R8A8_UNORM,
    VK10.VK_FORMAT_B8G8R8A8_SRGB -> 4
    VK10.VK_FORMAT_R16G16_SFLOAT,
    VK10.VK_FORMAT_R16G16_UNORM,
    VK10.VK_FORMAT_R16G16_SNORM,
    VK10.VK_FORMAT_R32_SFLOAT -> 4
    VK10.VK_FORMAT_R16G16B16_SFLOAT -> 6
    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
    VK10.VK_FORMAT_R32G32_SFLOAT -> 8
    VK10.VK_FORMAT_R32G32B32_SFLOAT -> 12
    VK10.VK_FORMAT_R32G32B32A32_SFLOAT -> 16
    else -> null
}

/**
 * [vkFormatBytesPerPixelOrNull], throwing for formats it does not know. [usage] names the caller
 * so the message says which capture or upload failed.
 *
 * Callers all size a staging buffer or validate a source buffer against the result, so a default
 * is worse than a failure: too small a guess lets `vkCmdCopyImageToBuffer` write past the end of
 * the allocation, and the corruption surfaces somewhere unrelated to the format that caused it.
 */
fun vkFormatBytesPerPixel(format: Int, usage: String): Int =
    vkFormatBytesPerPixelOrNull(format)
        ?: throw IllegalArgumentException("$usage: no bytes-per-pixel known for VkFormat $format")


// --- barrier util (now sync2) ---

fun image_layout_transition(
    command_buffer: VkCommandBuffer,
    image: Long,
    src_stage_mask: Long,
    dst_stage_mask: Long,
    src_access_mask: Long,
    dst_access_mask: Long,
    old_layout: Int,
    new_layout: Int,
    sub_resource_range: VkImageSubresourceRange,
    src_queue_family_index: Int = VK_QUEUE_FAMILY_IGNORED,
    dst_queue_family_index: Int = VK_QUEUE_FAMILY_IGNORED
) {
    MemoryStack.stackPush().use { stack ->
        val barrier2 = VkImageMemoryBarrier2.calloc(1, stack)
        barrier2[0]
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
            .srcStageMask(src_stage_mask)
            .srcAccessMask(src_access_mask)
            .dstStageMask(dst_stage_mask)
            .dstAccessMask(dst_access_mask)
            .oldLayout(old_layout)
            .newLayout(new_layout)
            .srcQueueFamilyIndex(src_queue_family_index)
            .dstQueueFamilyIndex(dst_queue_family_index)
            .image(image)
            .subresourceRange(sub_resource_range)

        val dep = VkDependencyInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
            .pImageMemoryBarriers(barrier2)

        vkCmdPipelineBarrier2(command_buffer, dep)
    }
}

fun image_layout_transition(
    command_buffer: VkCommandBuffer,
    image: Image,
    src_stage_mask: Long,
    dst_stage_mask: Long,
    src_access_mask: Long,
    dst_access_mask: Long,
    old_layout: Int,
    new_layout: Int,
    sub_resource_range: VkImageSubresourceRange,
    src_queue_family_index: Int = VK_QUEUE_FAMILY_IGNORED,
    dst_queue_family_index: Int = VK_QUEUE_FAMILY_IGNORED
) {
    image_layout_transition(
        command_buffer,
        image.vkImage,
        src_stage_mask,
        dst_stage_mask,
        src_access_mask,
        dst_access_mask,
        old_layout,
        new_layout,
        sub_resource_range,
        src_queue_family_index,
        dst_queue_family_index
    )
    image.setLayoutFor(command_buffer.address(), new_layout)
}

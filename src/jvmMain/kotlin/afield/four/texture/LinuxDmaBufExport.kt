package afield.four.texture

import afield.four.device.Device
import afield.four.targets.RenderToTexture
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier.*
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.*
import org.lwjgl.vulkan.VkDrmFormatModifierPropertiesEXT
import org.lwjgl.vulkan.VkDrmFormatModifierPropertiesListEXT
import org.lwjgl.vulkan.VkExternalImageFormatProperties
import org.lwjgl.vulkan.VkFormatProperties2
import org.lwjgl.vulkan.VkImageFormatProperties2
import org.lwjgl.vulkan.VkImageSubresource
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR
import org.lwjgl.vulkan.VkPhysicalDeviceExternalImageFormatInfo
import org.lwjgl.vulkan.VkPhysicalDeviceImageDrmFormatModifierInfoEXT
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2
import org.lwjgl.vulkan.VkSubresourceLayout

/**
 * One DRM-modifier memory plane of a dma-buf image, as the importer must be told it: a byte offset
 * into the allocation and a row pitch. These are *memory* planes of the modifier's layout, not
 * colour planes of the format.
 */
data class DmaBufPlaneLayout(val offset: Long, val rowPitch: Long)

/**
 * An exported dma-buf image: one POSIX fd owning the allocation, the DRM format modifier that
 * describes its tiling, and every memory plane's layout. This is the same information an Electron
 * `nativePixmap` carries, and it maps directly onto
 * `afield.four.browser.ExternalBrowserImageHandle.LinuxDmaBuf`.
 *
 * [fd] is owned by the receiver of this value. A successful Vulkan dma-buf import consumes it; if
 * the handle is dropped for any other reason the fd must be closed.
 */
data class DmaBufHandle(
    val fd: Int,
    val modifier: Long,
    val planes: List<DmaBufPlaneLayout>,
    val size: Long
)

/**
 * The Linux/dma-buf counterpart of [MetalIOSurface]: turns a Field image into the cross-process,
 * cross-vendor handle a dma-buf consumer needs.
 *
 * The chain is: render into an RTT created with `exportDmaBuf = true` -> [Image.exportDmaBufFd]
 * yields a fresh POSIX dma-buf fd -> [handle] packages it with the modifier the driver chose and
 * the plane layouts it implies.
 *
 * This is deliberately *not* the `exportOpaqueFd` path in [VulkanExternalMemory]. Opaque fd is the
 * same-vendor NVIDIA-Vulkan-to-NVIDIA-CUDA handshake and carries no tiling description, so only
 * the exporting driver can interpret it. A dma-buf plus an explicit DRM modifier is the generic
 * handle a browser compositor publishes and any Vulkan driver can import — which is why the
 * external-browser receiver speaks this dialect and not the other one.
 *
 * Linux only, and only useful on a device where [Device.supportsDmaBufImport] holds.
 */
object LinuxDmaBufExport {

    /**
     * The DRM format modifiers this device can both create [usage] images with *and* share as a
     * dma-buf, restricted to single-memory-plane layouts. Asked with
     * `vkGetPhysicalDeviceImageFormatProperties2` rather than inferred from
     * `drmFormatModifierTilingFeatures`, because only the former accounts for the external handle
     * type actually being used.
     *
     * Pass the result to `VkImageDrmFormatModifierListCreateInfoEXT` and let the driver pick; a
     * producer that hard-codes one modifier is guessing on the consumer's behalf.
     */
    fun shareableModifiers(
        device: Device,
        format: Int,
        usage: Int,
        externalFeature: Int = VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT
    ): List<Long> = MemoryStack.stackPush().use { stack ->
        val list = VkDrmFormatModifierPropertiesListEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT)
        val properties = VkFormatProperties2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2)
            .pNext(list.address())
        vkGetPhysicalDeviceFormatProperties2(device.physicalDevice.vkPhysicalDevice, format, properties)
        val count = list.drmFormatModifierCount()
        if (count == 0) return emptyList()

        // Second pass with storage; the first only reported how much of it to provide. LWJGL models
        // this list as purely output — it generates no setter for the array pointer — so the one
        // field the caller is required to fill in has to be written at its own offset.
        val storage = stack.calloc(
            VkDrmFormatModifierPropertiesEXT.ALIGNOF,
            VkDrmFormatModifierPropertiesEXT.SIZEOF * count
        )
        MemoryUtil.memPutAddress(
            list.address() + VkDrmFormatModifierPropertiesListEXT.PDRMFORMATMODIFIERPROPERTIES,
            MemoryUtil.memAddress(storage)
        )
        vkGetPhysicalDeviceFormatProperties2(device.physicalDevice.vkPhysicalDevice, format, properties)
        val modifierProperties = list.pDrmFormatModifierProperties()!!

        (0 until count).mapNotNull { index ->
            val candidate = modifierProperties[index]
            val modifier = candidate.drmFormatModifier()
            when {
                // The browser receiver imports one allocation, so a multi-memory-plane layout is
                // not a handle it can accept; refuse to offer one as a producer either.
                candidate.drmFormatModifierPlaneCount() != 1 -> null
                !supportsModifier(device, format, usage, modifier, externalFeature) -> null
                else -> modifier
            }
        }
    }

    /**
     * Whether [modifier] can back a [format]/[usage] image that is shareable as a dma-buf in the
     * direction [externalFeature] describes ([VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT] to
     * produce one, [VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT] to receive one).
     */
    fun supportsModifier(
        device: Device,
        format: Int,
        usage: Int,
        modifier: Long,
        externalFeature: Int
    ): Boolean = MemoryStack.stackPush().use { stack ->
        val modifierInfo = VkPhysicalDeviceImageDrmFormatModifierInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_DRM_FORMAT_MODIFIER_INFO_EXT)
            .drmFormatModifier(modifier)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val externalInfo = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO)
            .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
            .pNext(modifierInfo.address())
        val formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2)
            .format(format)
            .type(VK_IMAGE_TYPE_2D)
            .tiling(VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT)
            .usage(usage)
            .flags(0)
            .pNext(externalInfo.address())
        val externalProperties = VkExternalImageFormatProperties.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES)
        val imageProperties = VkImageFormatProperties2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2)
            .pNext(externalProperties.address())

        val result = vkGetPhysicalDeviceImageFormatProperties2(
            device.physicalDevice.vkPhysicalDevice,
            formatInfo,
            imageProperties
        )
        result == VK_SUCCESS &&
            (externalProperties.externalMemoryProperties().externalMemoryFeatures() and externalFeature) != 0
    }

    /**
     * Export [image] — which must have been created with [Image.ImageData.exportDmaBuf] — as a
     * fresh dma-buf handle. Each call produces a new fd, so each call yields a handle that a
     * consumer may import (and thereby consume) independently.
     */
    fun handle(image: Image): DmaBufHandle = DmaBufHandle(
        fd = image.exportDmaBufFd(),
        modifier = image.drmFormatModifier,
        planes = image.drmPlaneLayouts,
        size = image.memorySize
    )

    /**
     * Handles for all of an RTT's internally-created color attachments, in slot order (skipping any
     * slot backed by an external view, which has no [Image] of its own to export). Mirrors
     * [VulkanExternalMemory.handles] and [MetalIOSurface.ioSurfaceIds].
     */
    fun handles(rtt: RenderToTexture): List<DmaBufHandle> =
        rtt.colorImageRefs.mapNotNull { image -> image?.let { handle(it) } }

    /**
     * The plane layouts of a DRM-modifier [image] with [planeCount] memory planes, queried through
     * `VK_IMAGE_ASPECT_MEMORY_PLANE_n_BIT_EXT`. Used by [Image] itself right after binding.
     */
    internal fun planeLayouts(device: Device, image: Long, planeCount: Int): List<DmaBufPlaneLayout> =
        MemoryStack.stackPush().use { stack ->
            (0 until planeCount).map { plane ->
                val subresource = VkImageSubresource.calloc(stack)
                    .aspectMask(memoryPlaneAspect(plane))
                    .mipLevel(0)
                    .arrayLayer(0)
                val layout = VkSubresourceLayout.calloc(stack)
                vkGetImageSubresourceLayout(device.vkDevice, image, subresource, layout)
                DmaBufPlaneLayout(layout.offset(), layout.rowPitch())
            }
        }

    private fun memoryPlaneAspect(plane: Int): Int = when (plane) {
        0 -> VK_IMAGE_ASPECT_MEMORY_PLANE_0_BIT_EXT
        1 -> VK_IMAGE_ASPECT_MEMORY_PLANE_1_BIT_EXT
        2 -> VK_IMAGE_ASPECT_MEMORY_PLANE_2_BIT_EXT
        3 -> VK_IMAGE_ASPECT_MEMORY_PLANE_3_BIT_EXT
        else -> error("a DRM format modifier cannot have more than four memory planes (asked for $plane)")
    }

    /** `vkGetMemoryFdKHR` with the dma-buf handle type; ownership of the returned fd is the caller's. */
    internal fun exportFd(device: Device, memory: Long): Int = MemoryStack.stackPush().use { stack ->
        val info = VkMemoryGetFdInfoKHR.calloc(stack)
            .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
            .memory(memory)
            .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
        val fd = stack.mallocInt(1)
        vkCheck(
            KHRExternalMemoryFd.vkGetMemoryFdKHR(device.vkDevice, info, fd),
            "vkGetMemoryFdKHR(dma-buf export)"
        )
        fd[0]
    }
}

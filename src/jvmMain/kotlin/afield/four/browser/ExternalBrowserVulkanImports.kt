package afield.four.browser

import afield.four.device.Device
import afield.four.texture.LinuxDmaBufExport
import afield.four.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier.*
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.KHRExternalMemoryWin32
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.*
import org.lwjgl.vulkan.*

/** Raw Vulkan image/memory pair used only for external imports. */
internal data class NativeExternalImage(val image: Long, val memory: Long, private val device: Device) {
    fun cleanup() {
        vkDestroyImage(device.vkDevice, image, null)
        vkFreeMemory(device.vkDevice, memory, null)
    }
}

internal object ExternalBrowserVulkanImports {
    fun d3d11Texture(
        device: Device,
        handle: Long,
        width: Int,
        height: Int,
        format: Int
    ): NativeExternalImage = MemoryStack.stackPush().use { stack ->
        val external = VkExternalMemoryImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
            .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT)
        val create = imageCreateInfo(stack, width, height, format)
            .pNext(external.address())

        val imageOut = stack.mallocLong(1)
        vkCheck(vkCreateImage(device.vkDevice, create, null, imageOut), "vkCreateImage(browser D3D11 import)")
        val image = imageOut[0]
        var memory = 0L
        try {
            val requirements = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(device.vkDevice, image, requirements)
            val handleProperties = VkMemoryWin32HandlePropertiesKHR.calloc(stack)
                .sType(KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR)
            vkCheck(
                KHRExternalMemoryWin32.vkGetMemoryWin32HandlePropertiesKHR(
                    device.vkDevice,
                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT,
                    handle,
                    handleProperties
                ),
                "vkGetMemoryWin32HandlePropertiesKHR(browser D3D11 import)"
            )

            val memoryType = compatibleMemoryType(
                device,
                requirements.memoryTypeBits() and handleProperties.memoryTypeBits()
            )
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(image)
            val import = VkImportMemoryWin32HandleInfoKHR.calloc(stack)
                .sType(KHRExternalMemoryWin32.VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT)
                .handle(handle)
                .pNext(dedicated.address())
            val allocate = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(import.address())
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryType)
            val memoryOut = stack.mallocLong(1)
            vkCheck(vkAllocateMemory(device.vkDevice, allocate, null, memoryOut), "vkAllocateMemory(browser D3D11 import)")
            memory = memoryOut[0]
            vkCheck(vkBindImageMemory(device.vkDevice, image, memory, 0), "vkBindImageMemory(browser D3D11 import)")
            NativeExternalImage(image, memory, device)
        } catch (t: Throwable) {
            if (memory != 0L) vkFreeMemory(device.vkDevice, memory, null)
            vkDestroyImage(device.vkDevice, image, null)
            throw t
        }
    }

    fun dmaBuf(
        device: Device,
        handle: ExternalBrowserImageHandle.LinuxDmaBuf,
        width: Int,
        height: Int,
        format: Int
    ): NativeExternalImage = MemoryStack.stackPush().use { stack ->
        val uniqueFds = handle.planes.map { it.fd }.distinct()
        require(uniqueFds.size == 1) {
            "Electron nativePixmap used ${uniqueFds.size} distinct dma-bufs; the first Linux backend supports one allocation with one or more layout planes"
        }

        // Ask whether this driver can actually import a dma-buf of this format/modifier/usage tuple
        // before building anything from it. Without this an unsupported combination surfaces as a
        // bare vkCreateImage failure, which names neither the modifier nor the reason.
        require(
            LinuxDmaBufExport.supportsModifier(
                device,
                format,
                importUsage,
                handle.modifier,
                VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT
            )
        ) {
            "this device cannot import a dma-buf with format $format and DRM modifier 0x${handle.modifier.toString(16)}"
        }

        val layouts = VkSubresourceLayout.calloc(handle.planes.size, stack)
        handle.planes.forEachIndexed { index, plane ->
            layouts[index]
                .offset(plane.offset.toLong())
                .size(0)
                .rowPitch(plane.stride.toLong())
                .arrayPitch(0)
                .depthPitch(0)
        }
        val external = VkExternalMemoryImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
            .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
        val modifier = VkImageDrmFormatModifierExplicitCreateInfoEXT.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT)
            .pNext(external.address())
            .drmFormatModifier(handle.modifier)
            .pPlaneLayouts(layouts)
        val create = imageCreateInfo(stack, width, height, format)
            .pNext(modifier.address())
            .tiling(VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT)

        val imageOut = stack.mallocLong(1)
        vkCheck(vkCreateImage(device.vkDevice, create, null, imageOut), "vkCreateImage(browser dma-buf import)")
        val image = imageOut[0]
        var memory = 0L
        try {
            val requirements = VkMemoryRequirements.calloc(stack)
            vkGetImageMemoryRequirements(device.vkDevice, image, requirements)
            val fdProperties = VkMemoryFdPropertiesKHR.calloc(stack)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR)
            vkCheck(
                KHRExternalMemoryFd.vkGetMemoryFdPropertiesKHR(
                    device.vkDevice,
                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                    uniqueFds.single(),
                    fdProperties
                ),
                "vkGetMemoryFdPropertiesKHR(browser dma-buf import)"
            )

            val memoryType = compatibleMemoryType(
                device,
                requirements.memoryTypeBits() and fdProperties.memoryTypeBits()
            )
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(image)
            val import = VkImportMemoryFdInfoKHR.calloc(stack)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR)
                .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
                .fd(uniqueFds.single())
                .pNext(dedicated.address())
            val allocate = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(import.address())
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryType)
            val memoryOut = stack.mallocLong(1)
            vkCheck(vkAllocateMemory(device.vkDevice, allocate, null, memoryOut), "vkAllocateMemory(browser dma-buf import)")
            memory = memoryOut[0]
            // A successful fd import transfers ownership to the Vulkan implementation.
            handle.markConsumedByVulkan()
            vkCheck(vkBindImageMemory(device.vkDevice, image, memory, 0), "vkBindImageMemory(browser dma-buf import)")
            NativeExternalImage(image, memory, device)
        } catch (t: Throwable) {
            if (memory != 0L) vkFreeMemory(device.vkDevice, memory, null)
            vkDestroyImage(device.vkDevice, image, null)
            throw t
        }
    }

    /** What a received browser image is used for: copied out of, and (via the copy) sampled. */
    private const val importUsage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_SAMPLED_BIT

    private fun imageCreateInfo(
        stack: MemoryStack,
        width: Int,
        height: Int,
        format: Int
    ) = VkImageCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
        .imageType(VK_IMAGE_TYPE_2D)
        .format(format)
        .extent { it.set(width, height, 1) }
        .mipLevels(1)
        .arrayLayers(1)
        .samples(VK_SAMPLE_COUNT_1_BIT)
        .tiling(VK_IMAGE_TILING_OPTIMAL)
        .usage(importUsage)
        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)

    private fun compatibleMemoryType(device: Device, bits: Int): Int {
        require(bits != 0) { "external image and handle have no compatible Vulkan memory type" }
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceMemoryProperties.calloc(stack)
            vkGetPhysicalDeviceMemoryProperties(device.physicalDevice.vkPhysicalDevice, properties)
            for (index in 0 until properties.memoryTypeCount()) {
                if ((bits and (1 shl index)) != 0 &&
                    (properties.memoryTypes(index).propertyFlags() and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0
                ) return index
            }
            for (index in 0 until properties.memoryTypeCount()) {
                if ((bits and (1 shl index)) != 0) return index
            }
        }
        error("external image has no compatible Vulkan memory type: 0x${bits.toString(16)}")
    }
}

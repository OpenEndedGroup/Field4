# Vulkan Framebuffer Capture Example

This document provides a complete example of how to capture a Vulkan swapchain image and save it to a temporary PNG file using the new `Window.saveFramebufferToTempFile()` method.

## Quick Example

```kotlin
import afield.four.util.Window
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*

// Assuming you have these from your Vulkan setup:
val window: Window
val device: VkDevice
val physicalDevice: VkPhysicalDevice
val commandPool: Long
val queue: VkQueue

// In your render loop:
fun handleKeyPress() {
    if (window.isKeyPressed(GLFW.GLFW_KEY_F12)) {
        captureFramebufferToFile()
    }
}

fun captureFramebufferToFile() {
    // Get the current swapchain image
    val currentImage = getCurrentSwapchainImage()
    
    // Capture pixels from Vulkan
    val pixels = readVulkanImage(currentImage, window.width, window.height)
    
    try {
        // Save to temp file
        val screenshotFile = window.saveFramebufferToTempFile(pixels, window.width, window.height)
        println("Screenshot saved to: ${screenshotFile.absolutePath}")
    } finally {
        // Always free the pixel buffer
        MemoryUtil.memFree(pixels)
    } finally {
        // Always free the pixel buffer
        MemoryUtil.memFree(pixels)
    }
}
```

## Complete Implementation

### Step 1: Read Vulkan Image Data

```kotlin
fun readVulkanImage(image: Long, width: Int, height: Int): ByteBuffer {
    val imageSize = width * height * 4L  // RGBA = 4 bytes per pixel
    val pixelBuffer = MemoryUtil.memAlloc(imageSize.toInt())
    
    // Create a staging buffer in host-visible memory
    val stagingBuffer = createHostVisibleBuffer(imageSize)
    val stagingMemory = allocateBufferMemory(stagingBuffer)
    
    // Copy image to staging buffer
    val cmdBuffer = beginOneTimeCommands()
    try {
        // Transition image to TRANSFER_SRC layout
        transitionImageLayout(
            cmdBuffer, image,
            oldLayout = VK10.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            newLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        )
        
        // Copy image to buffer
        copyImageToBuffer(cmdBuffer, image, stagingBuffer, width, height)
        
        // Transition back to PRESENT layout
        transitionImageLayout(
            cmdBuffer, image,
            oldLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            newLayout = VK10.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        )
    } finally {
        endOneTimeCommands(cmdBuffer)
    }
    
    // Map staging buffer and copy to pixelBuffer
    val pData = MemoryUtil.memAllocPointer(1)
    VK10.vkMapMemory(device, stagingMemory, 0, imageSize, 0, pData)
    val mapped = pData.getByteBuffer(0, imageSize.toInt())
    pixelBuffer.put(mapped)
    pixelBuffer.flip()
    VK10.vkUnmapMemory(device, stagingMemory)
    MemoryUtil.memFree(pData)
    
    // Cleanup staging resources
    VK10.vkDestroyBuffer(device, stagingBuffer, null)
    VK10.vkFreeMemory(device, stagingMemory, null)
    
    return pixelBuffer
}
```

### Step 2: Helper Functions

```kotlin
fun createHostVisibleBuffer(size: Long): Long {
    val bufferInfo = VkBufferCreateInfo.calloc()
        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
        .size(size)
        .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
    
    val pBuffer = MemoryUtil.memAllocLong(1)
    VK10.vkCreateBuffer(device, bufferInfo, null, pBuffer)
    val buffer = pBuffer.get(0)
    
    bufferInfo.free()
    MemoryUtil.memFree(pBuffer)
    return buffer
}

fun allocateBufferMemory(buffer: Long): Long {
    val memReqs = VkMemoryRequirements.calloc()
    VK10.vkGetBufferMemoryRequirements(device, buffer, memReqs)
    
    val allocInfo = VkMemoryAllocateInfo.calloc()
        .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        .allocationSize(memReqs.size())
        .memoryTypeIndex(findMemoryType(
            memReqs.memoryTypeBits(),
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or 
            VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        ))
    
    val pMemory = MemoryUtil.memAllocLong(1)
    VK10.vkAllocateMemory(device, allocInfo, null, pMemory)
    val memory = pMemory.get(0)
    VK10.vkBindBufferMemory(device, buffer, memory, 0)
    
    memReqs.free()
    allocInfo.free()
    MemoryUtil.memFree(pMemory)
    return memory
}

fun transitionImageLayout(
    cmdBuffer: VkCommandBuffer,
    image: Long,
    oldLayout: Int,
    newLayout: Int
) {
    val barrier = VkImageMemoryBarrier.calloc(1)
        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        .oldLayout(oldLayout)
        .newLayout(newLayout)
        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
        .image(image)
        .subresourceRange { it
            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(1)
        }
    
    val srcStage: Int
    val dstStage: Int
    
    when (oldLayout to newLayout) {
        VK10.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR to VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
            barrier.srcAccessMask(0)
            barrier.dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
            srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        }
        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL to VK10.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
            barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
            barrier.dstAccessMask(0)
            srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            dstStage = VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
        }
        else -> error("Unsupported layout transition")
    }
    
    VK10.vkCmdPipelineBarrier(cmdBuffer, srcStage, dstStage, 0, null, null, barrier)
    barrier.free()
}

fun copyImageToBuffer(
    cmdBuffer: VkCommandBuffer,
    image: Long,
    buffer: Long,
    width: Int,
    height: Int
) {
    val region = VkBufferImageCopy.calloc(1)
        .bufferOffset(0)
        .bufferRowLength(0)
        .bufferImageHeight(0)
        .imageSubresource { it
            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0)
            .baseArrayLayer(0)
            .layerCount(1)
        }
        .imageOffset { it.set(0, 0, 0) }
        .imageExtent { it.set(width, height, 1) }
    
    VK10.vkCmdCopyImageToBuffer(
        cmdBuffer,
        image,
        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        buffer,
        region
    )
    
    region.free()
}

fun beginOneTimeCommands(): VkCommandBuffer {
    val allocInfo = VkCommandBufferAllocateInfo.calloc()
        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
        .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
        .commandPool(commandPool)
        .commandBufferCount(1)
    
    val pCmdBuffer = MemoryUtil.memAllocPointer(1)
    VK10.vkAllocateCommandBuffers(device, allocInfo, pCmdBuffer)
    val cmdBuffer = VkCommandBuffer(pCmdBuffer.get(0), device)
    
    val beginInfo = VkCommandBufferBeginInfo.calloc()
        .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
    
    VK10.vkBeginCommandBuffer(cmdBuffer, beginInfo)
    
    allocInfo.free()
    beginInfo.free()
    MemoryUtil.memFree(pCmdBuffer)
    
    return cmdBuffer
}

fun endOneTimeCommands(cmdBuffer: VkCommandBuffer) {
    VK10.vkEndCommandBuffer(cmdBuffer)
    
    val submitInfo = VkSubmitInfo.calloc()
        .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
        .pCommandBuffers(MemoryUtil.memAllocPointer(1).put(0, cmdBuffer))
    
    VK10.vkQueueSubmit(queue, submitInfo, VK10.VK_NULL_HANDLE)
    VK10.vkQueueWaitIdle(queue)
    VK10.vkFreeCommandBuffers(device, commandPool, cmdBuffer)
    
    MemoryUtil.memFree(submitInfo.pCommandBuffers())
    submitInfo.free()
}
```

## Alternative: Export as Base64

If you want to send the image over the network instead of saving to a file:

```kotlin
fun captureFramebufferAsBase64(): String? {
    val currentImage = getCurrentSwapchainImage()
    val pixels = readVulkanImage(currentImage, window.width, window.height)
    
    try {
        return window.getFramebufferAsBase64(pixels, window.width, window.height)
    } finally {
        MemoryUtil.memFree(pixels)
    }
}

// Then send via WebSocket:
val base64Image = captureFramebufferAsBase64()
if (base64Image != null) {
    sendToChat(base64Image)
}
```

// Then send via WebSocket:
val base64Image = captureFramebufferAsBase64()
if (base64Image != null) {
    sendToChat(base64Image)
}
```

## Integration with Field4 Boxes

You can add a keyboard shortcut to any box:

```kotlin
// In a Box's code property:
val window = lastWindowToOpen
window?.onPoll?.add("screenshot") { 
    if (window.isKeyPressed(GLFW.GLFW_KEY_F12)) {
        captureToFile()
    }
}
```

Or create a dedicated screenshot box:

```kotlin
// Box name: "Screenshot Manager"
val captureKey = GLFW.GLFW_KEY_F12
var lastCapture = 0L
val minInterval = 500 // milliseconds between captures

lastWindowToOpen?.onPoll?.add("screenshot") {
    val now = System.currentTimeMillis()
    if (window.isKeyPressed(captureKey) && now - lastCapture > minInterval) {
        lastCapture = now
        try {
            val image = getCurrentSwapchainImage()
            val pixels = readVulkanImage(image, window.width, window.height)
            val file = window.saveFramebufferToTempFile(pixels, window.width, window.height)
            MemoryUtil.memFree(pixels)
            println("✓ Screenshot saved: ${file.absolutePath}")
        } catch (e: Exception) {
            println("✗ Screenshot failed: ${e.message}")
        }
    }
}
```
            MemoryUtil.memFree(pixels)
            println("✓ Screenshot copied!")
        } catch (e: Exception) {
            println("✗ Screenshot failed: ${e.message}")
        }
    }
}
```

## Troubleshooting

### "Failed to save framebuffer to temp file"
- Make sure the ByteBuffer contains valid RGBA data
- Verify the width and height match the buffer size (width * height * 4 bytes)
- Check that the temp directory is writable
- Ensure imageData is not null

### Image appears upside down
- The `bufferToPngBytes()` method in Window.kt automatically flips the image
- If your image appears upside down, you may be double-flipping
- Check your Vulkan image coordinate system (typically bottom-to-top)

### Memory leaks
- Always call `MemoryUtil.memFree()` on pixel buffers after use
- Clean up staging buffers and memory allocations
- Use try-finally blocks to ensure cleanup

### Performance issues
- Capturing framebuffers is expensive - don't do it every frame
- Use a cooldown timer (see integration example above)
- Consider capturing at lower resolution for preview purposes

### PNG encoding issues
- The custom PNG encoder handles standard RGBA format
- If you get corrupted images, verify pixel byte order
- Check that buffer position is reset before reading

## See Also

- `src/jvmMain/kotlin/afield/four/util/Window.kt` - Window screenshot methods
- `IMAGE_UPLOAD_FEATURE.md` - Complete image upload documentation
- LWJGL Vulkan documentation: https://www.lwjgl.org/guide
- `IMAGE_UPLOAD_FEATURE.md` - Complete image upload documentation
- LWJGL Vulkan documentation: https://www.lwjgl.org/guide

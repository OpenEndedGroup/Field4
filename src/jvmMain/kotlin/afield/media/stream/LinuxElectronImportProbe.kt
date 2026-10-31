package afield.media.stream

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.VulkanBuffer
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Fence
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.texture.LinuxDmaBufExport
import afield.graphics.texture.UnixFdPass
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import afield.math.linalg.vec
import org.lwjgl.system.MemoryStack
import java.io.File

/**
 * A standalone Linux probe that hands Electron one finished Vulkan image and reads back its verdict
 * on the pixels.
 *
 * It clears a 64×64 dma-buf-exportable [RenderToTexture] to a known colour, copies that back through
 * a staging buffer to confirm the Field side is not blank, releases the image to
 * `VK_QUEUE_FAMILY_EXTERNAL` behind a waited fence, and sends the descriptor over [UnixFdPass] with
 * the plane's stride and offset and the allocation's DRM modifier written beside it as
 * `texture.json`. Then it waits up to a minute for the Node side's `result.json`. Start
 * `tools/electron_dmabuf_probe/main.js` first; the single argument is the directory the two sides
 * exchange evidence in, and `native/field-fdpass` has to be built.
 */

/** One immutable, fence-completed Vulkan image for the Electron nativePixmap import. */
object LinuxElectronImportProbe {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args.single()).apply { mkdirs() }
        Options.enableHDR = false
        val window = Window("Field Vulkan → Electron import probe")
        val renderer = Renderer(window)
        val rtt = RenderToTexture(renderer) {
            width = 64; height = 64; useDepth = false
            exportDmaBuf = true
            clearColor = vec(1.0, 0.25, 0.125, 1.0)
        }
        try {
            println("PROBE DEVICE ${renderer.device.physicalDevice.driverName}")
            check(UnixFdPass.available) { "Build native/field-fdpass first" }
            val command = CommandBuffer(renderer.commandPool, primary = true, oneTimeSubmit = true)
            val fence = Fence(renderer.device, signaled = false)
            val staging = VulkanBuffer(renderer, 64L * 64 * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            try {
                command.beginRecording()
                rtt.render(command, 0)
                rtt.recordColorTransferSource(command) { image ->
                    MemoryStack.stackPush().use { stack ->
                        val region = VkBufferImageCopy.calloc(1, stack)
                        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)
                        region.imageExtent().set(64, 64, 1)
                        vkCmdCopyImageToBuffer(command.vkCommandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            staging.buffer, region)
                    }
                }
                // Release the immutable image to the external consumer in GENERAL layout.
                // Fence completion provides execution ordering; queue-family release provides ownership.
                MemoryStack.stackPush().use { stack ->
                    val barrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(0)
                        .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(renderer.graphQueue.queueFamilyIndex).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                        .image(rtt.colorImageHandles.single())
                    barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
                    vkCmdPipelineBarrier(command.vkCommandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier)
                }
                command.endRecording()
                MemoryStack.stackPush().use {
                    renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer), null, null, null, fence)
                }
                fence.fenceWait()
                val bytes = ByteArray(64 * 64 * 4)
                staging.mapAndRead_bytes { get(bytes) }
                directory.resolve("vulkan.bgra").writeBytes(bytes)
                val nonBlack = (bytes.indices step 4).count { bytes[it] != 0.toByte() || bytes[it+1] != 0.toByte() || bytes[it+2] != 0.toByte() }
                check(nonBlack == 4096) { "Vulkan source was blank: $nonBlack pixels" }
                println("PROBE SOURCE $nonBlack/4096 RGB pixels, BGRA ${bytes.take(4).map { it.toInt() and 255 }}")
                val handle = LinuxDmaBufExport.handles(rtt).single()
                try {
                    val plane = handle.planes.single()
                    directory.resolve("texture.json").writeText(
                        """{"width":64,"height":64,"modifier":"${java.lang.Long.toUnsignedString(handle.modifier)}","stride":${plane.rowPitch},"offset":${plane.offset},"size":${handle.size},"fenceCompleted":true,"externalOwnership":true}"""
                    )
                    check(UnixFdPass.sendFds(directory.resolve("fds.sock").path, intArrayOf(handle.fd)))
                } finally { UnixFdPass.closeFd(handle.fd) }
                val deadline = System.nanoTime() + 60_000_000_000L
                while (!directory.resolve("result.json").exists() && System.nanoTime() < deadline) Thread.sleep(50)
                check(directory.resolve("result.json").exists()) { "Electron produced no pixel verdict" }
                println("PROBE RESULT ${directory.resolve("result.json").readText()}")
            } finally { staging.cleanup(); fence.cleanup(); command.cleanup() }
        } finally { renderer.cleanup(); window.cleanup() }
    }
}

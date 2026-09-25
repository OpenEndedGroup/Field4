@file:afield.CompletionCategory("Internal")
package afield.four.reconstruction

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.texture.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow

/** Blocking diagnostic readback only. Neither NGX evaluation nor presentation uses this path. */
internal object ReconstructionCapture {
    fun read(renderer: Renderer,source: HasImageView): FloatArray {
        val view=source.imageView;val image=requireNotNull(view.underlyingImage)
        val width=image.imageData.width;val height=image.imageData.height
        val bytes=vkFormatBytesPerPixel(image.format,"reconstruction capture")
        val half=image.format==VK_FORMAT_R16G16B16A16_SFLOAT || image.format==VK_FORMAT_R16G16_SFLOAT
        val channels=bytes/if(half)2 else 4
        val staging=VulkanBuffer(renderer,width.toLong()*height*bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val cmd=CommandBuffer(renderer.commandPool,true,true);val fence=Fence(renderer.device,false)
        try {
            cmd.beginRecording()
            MemoryStack.stackPush().use { s ->
                val range=VkImageSubresourceRange.calloc(s).aspectMask(view.aspectMask).levelCount(1).layerCount(1)
                val old=requireNotNull(image.getLastKnownLayout())
                image_layout_transition(cmd.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_MEMORY_WRITE_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,old,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,range)
                val region=VkBufferImageCopy.calloc(1,s)
                region.imageSubresource().aspectMask(view.aspectMask).layerCount(1)
                region.imageExtent().set(width,height,1)
                vkCmdCopyImageToBuffer(cmd.vkCommandBuffer,image.vkImage,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,staging.buffer,region)
                image_layout_transition(cmd.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT,VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,old,range)
                val barrier=VkMemoryBarrier2.calloc(1,s).`sType$Default`().srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                vkCmdPipelineBarrier2(cmd.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(barrier))
            }
            cmd.endRecording();MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(cmd.vkCommandBuffer),null,null,null,fence) };fence.fenceWait()
            return FloatArray(width*height*channels).also { pixels ->
                staging.mapAndRead_bytes { for(i in pixels.indices)pixels[i]=if(half)java.lang.Float.float16ToFloat(short) else float }
            }
        } finally { fence.cleanup();cmd.cleanup();staging.cleanup() }
    }
    fun save(file: File,pixels: FloatArray,width: Int,height: Int,channels: Int=4) {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
        fun srgb(x: Float): Int { val v=if(x<=.0031308f)x*12.92f else 1.055f*x.toDouble().pow(1/2.4).toFloat()-.055f
            return (v*255).toInt().coerceIn(0,255) }
        for(y in 0 until height)for(x in 0 until width){val i=(y*width+x)*channels
            image.setRGB(x,y,(srgb(pixels[i]) shl 16) or (srgb(pixels[i+minOf(1,channels-1)]) shl 8) or srgb(pixels[i+minOf(2,channels-1)]))}
        file.parentFile.mkdirs();ImageIO.write(image,"png",file)
    }
}

@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.graphics.render.neural

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.device.vkLayout
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Fence
import afield.graphics.texture.HasImageView
import afield.graphics.texture.image_layout_transition
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*
import java.awt.image.BufferedImage
import java.nio.ByteOrder
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.pow

internal object OpenDlssNrDiagnostics {
    fun submit(renderer: Renderer,record: (CommandBuffer)->Unit) {
        val cmd=CommandBuffer(renderer.commandPool,primary=true,oneTimeSubmit=true);val fence=Fence(renderer.device,signaled=false)
        try {cmd.beginRecording();record(cmd);cmd.endRecording();MemoryStack.stackPush().use {renderer.graphQueue.submit(it.pointers(cmd.vkCommandBuffer),null,null,null,fence)};fence.fenceWait()}
        finally {fence.cleanup();cmd.cleanup()}
    }
    fun pixels(renderer: Renderer,source: HasImageView): FloatArray {
        val image=requireNotNull(source.imageView.underlyingImage);val width=image.imageData.width;val height=image.imageData.height
        require(image.format==VK_FORMAT_R16G16B16A16_SFLOAT)
        val bytes=width.toLong()*height*8
        val staging=VulkanBuffer(renderer,bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        try {
            submit(renderer) {cmd -> MemoryStack.stackPush().use {s ->
                val range=VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
                val old=requireNotNull(image.getLastKnownLayout())
                image_layout_transition(cmd.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_MEMORY_READ_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,old,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,range)
                val copy=VkBufferImageCopy.calloc(1,s).imageSubresource {it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)}.imageExtent {it.width(width).height(height).depth(1)}
                vkCmdCopyImageToBuffer(cmd.vkCommandBuffer,image.vkImage,vkLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),staging.buffer,copy)
                image_layout_transition(cmd.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT,VK_ACCESS_2_MEMORY_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,old,range)
            } }
            return FloatArray(width*height*4).also {data -> staging.mapAndRead_bytes {order(ByteOrder.nativeOrder());for(i in data.indices)data[i]=java.lang.Float.float16ToFloat(getShort(i*2))} }
        } finally {staging.cleanup()}
    }
    fun png(pixels: FloatArray,width: Int,height: Int,path: Path) {
        check(pixels.all {it.isFinite()}) {"Non-finite output"}
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
        fun encode(v: Float): Int {val x=v.coerceIn(0f,1f);return ((if(x<=0.0031308f)x*12.92f else 1.055f*x.pow(1f/2.4f)-0.055f)*255f+0.5f).toInt().coerceIn(0,255)}
        for(y in 0 until height)for(x in 0 until width){val i=(y*width+x)*4;image.setRGB(x,y,(pixels[i+3].coerceIn(0f,1f)*255+0.5f).toInt().shl(24) or encode(pixels[i]).shl(16) or encode(pixels[i+1]).shl(8) or encode(pixels[i+2]))}
        ImageIO.write(image,"png",path.toFile())
    }
}

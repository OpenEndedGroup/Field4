package afield.four.neuralrendering

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.texture.LoadedTexture
import afield.four.util.Window
import afield.four.util.Options
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkMemoryBarrier
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.*

/** Managed-runner entry point: real Field device, native pass, caller-owned submission and head readback.
 * Arguments: image, output-directory, [tone], [structure], [style]. Diagnostics intentionally use CPU
 * readback; the normal pass does not. Pair the resulting head with the standalone render for parity.
 */
object OpenDlssNrGpuSmoke {
    @JvmStatic fun main(args: Array<String>) {
        if(args.firstOrNull()=="--temporal") {OpenDlssNrTemporalSmoke.main(args.drop(1).toTypedArray());return}
        require(args.size>=2)
        Options.enableHDR=false
        val window=Window("Field OpenDLSS-NR GPU verification")
        val renderer=Renderer(window)
        try {
            val available=OpenDlssNrSupport.availability(renderer)
            check(available.ready) {available.reason}
            println("NR DEVICE ${renderer.device.physicalDevice.driverName}: ${available.reason}")
            val texture=LoadedTexture.fromFile(args[0],renderer,VK_FORMAT_R8G8B8A8_UNORM)
            try {
                OpenDlssNrPass(renderer,texture.width,texture.height).use { pass ->
                    pass.controls=OpenDlssNrControls(tone=args.getOrNull(2)?.toFloat() ?: 1f,structure=args.getOrNull(3)?.toFloat() ?: 1f,style=args.getOrNull(4)?.toInt() ?: 1)
                    fun render(index: Long) {
                        val command=CommandBuffer(renderer.commandPool,primary=true,oneTimeSubmit=true)
                        val fence=Fence(renderer.device,signaled=false)
                        try {
                            command.beginRecording();texture.recordTextureTransition(command)
                            pass.evaluate(command,OpenDlssNrInputs(texture,OpenDlssNrColorEncoding.SRGB_CODE),OpenDlssNrFrame(index))
                            command.endRecording()
                            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
                            fence.fenceWait()
                        } finally {fence.cleanup();command.cleanup()}
                    }
                    render(0)
                    val padded=pass.paddedWidth()
                    val bytes=padded.toLong()*texture.height*16
                    fun head(): ByteArray {
                        val staging=VulkanBuffer(renderer,bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                        val cmd=CommandBuffer(renderer.commandPool,primary=true,oneTimeSubmit=true);val fence=Fence(renderer.device,signaled=false)
                        try {
                            cmd.beginRecording()
                            MemoryStack.stackPush().use { s ->
                                val barrier=VkMemoryBarrier.calloc(1,s).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                vkCmdPipelineBarrier(cmd.vkCommandBuffer,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,barrier,null,null)
                                vkCmdCopyBuffer(cmd.vkCommandBuffer,pass.headBuffer(),staging.buffer,VkBufferCopy.calloc(1,s).size(bytes))
                            }
                            cmd.endRecording();MemoryStack.stackPush().use {renderer.graphQueue.submit(it.pointers(cmd.vkCommandBuffer),null,null,null,fence)};fence.fenceWait()
                            return ByteArray(bytes.toInt()).also { data -> staging.mapAndRead_bytes {get(data)} }
                        } finally {fence.cleanup();cmd.cleanup();staging.cleanup()}
                    }
                    val first=head();val floats=ByteBuffer.wrap(first).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    check((0 until floats.limit()).all {floats[it].isFinite()}) {"Non-finite Field head"}
                    render(1);check(first.contentEquals(head())) {"Fixed-seed single-frame result changed"}
                    for(index in 2L..7L)render(index)
                    val output=Path.of(args[1]);output.createDirectories();output.resolve("field.head.f32").writeBytes(first)
                    OpenDlssNrDiagnostics.png(OpenDlssNrDiagnostics.pixels(renderer,pass.output),texture.width,texture.height,output.resolve("field.png"))
                    output.resolve("field.txt").writeText("width=${texture.width}\nheight=${texture.height}\npaddedWidth=$padded\ncontrols=${pass.controls}\ngpuMilliseconds=${pass.gpuMilliseconds}\nnativeAllocatedBytes=${pass.nativeAllocatedBytes}\noutputAllocatedBytes=${pass.outputAllocatedBytes}\n")
                    check(pass.resetCount==1) {"Unchanged input reset unexpectedly"}
                    pass.reset("smoke reset");render(8);check(pass.resetCount==2)
                    println("PASS NR: finite head, identical fixed seed, explicit reset; ${texture.width}x${texture.height}; ${pass.gpuMilliseconds}")
                }
            } finally {texture.cleanup()}
        } finally {renderer.cleanup();window.cleanup()}
    }
}

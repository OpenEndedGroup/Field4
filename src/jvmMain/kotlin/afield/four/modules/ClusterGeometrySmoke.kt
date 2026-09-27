package afield.four.modules

import afield.Box
import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.reconstruction.ReconstructionCapture
import afield.four.util.Options
import afield.four.util.Window
import org.json.JSONObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

object ClusterGeometrySmoke {
    @JvmStatic fun main(args: Array<String>) {
        Options.enableVR=false;Options.enableHDR=false;Options.enableRayTracing=true;Options.doVulkanSDK()
        val folder=File("build/neural-tools/clusters").also { it.mkdirs() }
        val window=Window("Field computed Mega Geometry");val renderer=Renderer(window)
        val box=Box("cluster-check");box.setPropertyRaw("renderer",renderer,doResync=false)
        try { Box.withBoxContextNow(box) {
            fun pump(n: Int=1){val end=renderer.frameCounter+n;while(renderer.frameCounter<end){window.pollEvents();renderer.render()}}
            val report=JSONObject().put("enabled",renderer.device.supportsClusterAccelerationStructure)
            ClusterGeometryDemo(renderer).use { demo ->
                demo.install();pump(32)
                val a=ReconstructionCapture.read(renderer,demo.output)
                check(a.all { it.isFinite() } && a.max()>.15f)
                val status=readBuffer(renderer,demo.geometry.status).asIntBuffer();check((0 until status.remaining()).all { status[it]==0 })
                // A GPU-instance owner cannot silently resize storage that earlier frames use.
                val guardCommand=CommandBuffer(renderer.commandPool,true,false)
                try {
                    check(runCatching { demo.tlas.buildTopLevel(guardCommand,demo.instances,2) }.exceptionOrNull()?.message?.contains("fixed")==true)
                    check(runCatching { demo.tlas.buildTopLevel(guardCommand,demo.instances,1,0) }.exceptionOrNull()?.message?.contains("fixed")==true)
                    check(runCatching { demo.tlas.buildTopLevel(guardCommand,listOf(RayTracingInstance(demo.geometry))) }.isFailure)
                } finally { guardCommand.cleanup() }
                val recordBytes=readBuffer(renderer,demo.geometry.records)
                MemoryStack.stackPush().use { stack ->
                    val nativeRecord=VkClusterAccelerationStructureBuildTriangleClusterInfoNV.calloc(stack)
                    recordBytes.limit(64)
                    org.lwjgl.system.MemoryUtil.memByteBuffer(nativeRecord.address(),64).put(recordBytes)
                    check(nativeRecord.indexBufferStride().toInt()==4 && nativeRecord.vertexBufferStride().toInt()==16)
                    check(nativeRecord.triangleCount()==128 && nativeRecord.vertexCount()==81)
                    check(nativeRecord.indexBuffer()>0 && nativeRecord.vertexBuffer()>0)
                }
                ReconstructionCapture.save(File(folder,"computed.png"),a,demo.width,demo.height)
                demo.animate=false;demo.time+=1;pump(4)
                val b=ReconstructionCapture.read(renderer,demo.output)
                check(a.indices.sumOf { abs(a[it]-b[it]).toDouble() }/a.size>.01)
                val before=readBuffer(renderer,demo.indices)
                demo.topology=1;pump(4)
                val after=readBuffer(renderer,demo.indices);check(before!=after) { "GPU topology edit had no effect" }
                demo.view=1;pump(4)
                ReconstructionCapture.save(File(folder,"clusters.png"),ReconstructionCapture.read(renderer,demo.output),demo.width,demo.height)
                demo.invalidCluster=0
                for(mode in listOf(1,2,4)) {
                    demo.invalidMode=mode;pump(4)
                    check(readBuffer(renderer,demo.geometry.status).asIntBuffer()[0]==mode) { "Invalid input escaped guard: $mode" }
                }
                demo.invalidCluster=-1;demo.view=0;demo.animate=true;pump(32)
                repeat(120){i->pump();if("--clip" in args)ReconstructionCapture.save(File(folder,"clip/%04d.png".format(i)),
                    ReconstructionCapture.read(renderer,demo.output),demo.width,demo.height)}
                fun distribution(times: List<Double>): JSONObject {val t=times.takeLast(100).sorted();check(t.isNotEmpty())
                    return JSONObject().put("count",t.size).put("p50",t[t.size/2]).put("p95",t[(t.size*.95).toInt()])}
                report.put("clusterCount",demo.count).put("triangles",demo.count*128).put("limits",demo.geometry.limits.toString())
                    .put("builderBytes",demo.geometry.allocatedBytes).put("buildMs",distribution(demo.geometry.gpuMilliseconds))
                    .put("wholePassMs",distribution(demo.gpuMilliseconds))
            }
            pump(32)
            File(folder,"measurements.json").writeText(report.toString(2));println(report.toString(2))
        } } finally { renderer.cleanup();window.cleanup() }
        println("COMPUTED CLUSTERS CHECK DONE");exitProcess(0)
    }
    /** Blocking test helper, never the frame path. */
    internal fun readBuffer(renderer: Renderer,source: VulkanBuffer): java.nio.ByteBuffer {
        val staging=VulkanBuffer(renderer,source.requestedSize,VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val command=CommandBuffer(renderer.commandPool,true,true);val fence=Fence(renderer.device,false)
        try {
            command.beginRecording()
            MemoryStack.stackPush().use { s ->
                fun barrier(src: Long,access: Long,dst: Long,dstAccess: Long) {
                    vkCmdPipelineBarrier2(command.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(
                        VkMemoryBarrier2.calloc(1,s).`sType$Default`().srcStageMask(src).srcAccessMask(access).dstStageMask(dst).dstAccessMask(dstAccess)))
                }
                barrier(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT)
                vkCmdCopyBuffer(command.vkCommandBuffer,source.buffer,staging.buffer,VkBufferCopy.calloc(1,s).size(source.requestedSize))
                barrier(VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_2_HOST_BIT,VK_ACCESS_2_HOST_READ_BIT)
            }
            command.endRecording();MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) };fence.fenceWait()
            return java.nio.ByteBuffer.allocate(source.requestedSize.toInt()).order(java.nio.ByteOrder.nativeOrder()).also { out ->staging.mapAndRead_bytes { out.put(this).flip() } }
        } finally { fence.cleanup();command.cleanup();staging.cleanup() }
    }
}

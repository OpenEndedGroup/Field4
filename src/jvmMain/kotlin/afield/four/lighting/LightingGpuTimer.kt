package afield.four.lighting

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.util.vkCheck
import afield.four.util.AutoDisposer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkQueryPoolCreateInfo

/** Per-swapchain-image timestamps. Reuse occurs after Renderer waits for that image's fence.
 * Read availability without a CPU wait; bounded samples are inspectable from a live box. */
internal class LightingGpuTimer(private val renderer: Renderer,queueFamily: Int=renderer.graphQueue.queueFamilyIndex) : AutoCloseable {
    private val validBits=renderer.device.physicalDevice.vkQueueFamilyProps[queueFamily].timestampValidBits()
    private val pool: Long
    private val disposer: AutoDisposer.Token?
    private val used=BooleanArray(64)
    val samples=ArrayDeque<Double>()
    private val period=renderer.device.physicalDevice.vkPhysicalDeviceProperties.limits().timestampPeriod().toDouble()
    init {
        pool=if(validBits==0) 0L else MemoryStack.stackPush().use { stack ->
            val out=stack.mallocLong(1)
            vkCheck(vkCreateQueryPool(renderer.device.vkDevice,VkQueryPoolCreateInfo.calloc(stack).`sType$Default`()
                .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(128),null,out),"lighting timestamp pool")
            out[0]
        }
        val device=renderer.device.vkDevice
        val handle=pool
        disposer=if(handle==0L)null else AutoDisposer.register(this,"lighting timestamps",owner=renderer.device) {
            vkDestroyQueryPool(device,handle,null)
        }
    }
    fun begin(cmd: CommandBuffer,index: Int) {
        if(pool==0L)return
        require(index in used.indices)
        if(used[index]) MemoryStack.stackPush().use { stack ->
            val values=stack.callocLong(4)
            val result=vkGetQueryPoolResults(renderer.device.vkDevice,pool,index*2,2,values,16,
                VK_QUERY_RESULT_64_BIT or VK_QUERY_RESULT_WITH_AVAILABILITY_BIT)
            if(result==VK_SUCCESS && values[1]!=0L && values[3]!=0L) {
                val ticks=values[2]-values[0]
                val masked=if(validBits==64)ticks else ticks and ((1L shl validBits)-1)
                samples.addLast(masked*period/1e6)
                while(samples.size>1024)samples.removeFirst()
            }
        }
        vkCmdResetQueryPool(cmd.vkCommandBuffer,pool,index*2,2)
        vkCmdWriteTimestamp(cmd.vkCommandBuffer,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,pool,index*2)
        used[index]=true
    }
    fun end(cmd: CommandBuffer,index: Int) { if(pool!=0L)vkCmdWriteTimestamp(cmd.vkCommandBuffer,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pool,index*2+1) }
    override fun close() { if(disposer?.disable()==true)vkDestroyQueryPool(renderer.device.vkDevice,pool,null) }
}

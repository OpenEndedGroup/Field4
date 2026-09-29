package afield.four.lighting

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Device
import afield.four.util.AutoDisposer
import afield.logger.lprintln
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkQueryPoolCreateInfo
import java.util.WeakHashMap

/** Per-swapchain-image timestamps. Reuse occurs after Renderer waits for that image's fence.
 * Read availability without a CPU wait; bounded samples are inspectable from a live box.
 *
 * The queries are a slice of a pool shared with other timers ([TimestampSlabs]), not a pool of
 * their own: KosmicKrisp backs every `VkQueryPool` with a Metal counter heap, and a process gets
 * 32 of those, each at most 4096 timestamps (measured, SDK 1.4.363, M1 Max). One pool per timer
 * ran out inside a single optical-flow smoke. A timer that cannot get a slice records nothing. */
internal class LightingGpuTimer(private val renderer: Renderer,queueFamily: Int=renderer.graphQueue.queueFamilyIndex) : AutoCloseable {
    private val validBits=renderer.device.physicalDevice.vkQueueFamilyProps[queueFamily].timestampValidBits()
    private val slice: TimestampSlabs.Slice?=if(validBits==0) null else TimestampSlabs.allocate(renderer.device)
    private val pool: Long=slice?.pool ?: 0L
    private val base: Int=slice?.first ?: 0
    private val disposer: AutoDisposer.Token?
    private val used=BooleanArray(64)
    val samples=ArrayDeque<Double>()
    private val period=renderer.device.physicalDevice.vkPhysicalDeviceProperties.limits().timestampPeriod().toDouble()
    init {
        val held=slice
        // The GC path already runs on the renderer's retirement queue, after in-flight frames.
        disposer=if(held==null)null else AutoDisposer.register(this,"lighting timestamps",owner=renderer.device) { held.free() }
    }
    fun begin(cmd: CommandBuffer,index: Int) {
        if(pool==0L)return
        require(index in used.indices)
        if(used[index]) MemoryStack.stackPush().use { stack ->
            val values=stack.callocLong(4)
            val result=vkGetQueryPoolResults(renderer.device.vkDevice,pool,base+index*2,2,values,16,
                VK_QUERY_RESULT_64_BIT or VK_QUERY_RESULT_WITH_AVAILABILITY_BIT)
            if(result==VK_SUCCESS && values[1]!=0L && values[3]!=0L) {
                val ticks=values[2]-values[0]
                val masked=if(validBits==64)ticks else ticks and ((1L shl validBits)-1)
                samples.addLast(masked*period/1e6)
                while(samples.size>1024)samples.removeFirst()
            }
        }
        vkCmdResetQueryPool(cmd.vkCommandBuffer,pool,base+index*2,2)
        vkCmdWriteTimestamp(cmd.vkCommandBuffer,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,pool,base+index*2)
        used[index]=true
    }
    fun end(cmd: CommandBuffer,index: Int) { if(pool!=0L)vkCmdWriteTimestamp(cmd.vkCommandBuffer,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pool,base+index*2+1) }
    /** The slice goes back after in-flight frames retire: a new owner resets its queries, which is
     *  invalid while a submitted frame still writes them. */
    override fun close() { if(disposer?.disable()==true) slice?.let { s -> renderer.cleanup.add { s.free() } } }
}

/**
 * Timestamp query pools shared by every [LightingGpuTimer] on a device, handed out in slices of
 * [SLICE] queries. A pool is [POOL] queries — KosmicKrisp's largest — so 32 timers share one where
 * they used to take 32 of the process's 32 counter heaps. Pools are destroyed with the device.
 */
internal object TimestampSlabs {
    const val SLICE=128
    const val POOL=4096
    private const val PER_POOL=POOL/SLICE

    class Slice internal constructor(val pool: Long,val first: Int,private val slab: Slab) {
        fun free()=synchronized(TimestampSlabs) { slab.taken[first/SLICE]=false }
    }

    class Slab internal constructor(val pool: Long) { internal val taken=BooleanArray(PER_POOL) }

    private class DeviceSlabs { val slabs=mutableListOf<Slab>(); var exhausted=false }
    private val byDevice=WeakHashMap<Device,DeviceSlabs>()

    @Synchronized
    fun allocate(device: Device): Slice? {
        val slabs=byDevice.getOrPut(device) { DeviceSlabs() }
        for(slab in slabs.slabs) {
            val free=slab.taken.indexOfFirst { !it }
            if(free>=0) { slab.taken[free]=true; return Slice(slab.pool,free*SLICE,slab) }
        }
        if(slabs.exhausted) return null
        val pool=MemoryStack.stackPush().use { stack ->
            val out=stack.mallocLong(1)
            val result=vkCreateQueryPool(device.vkDevice,VkQueryPoolCreateInfo.calloc(stack).`sType$Default`()
                .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(POOL),null,out)
            if(result==VK_SUCCESS) out[0] else {
                lprintln("warning,vulkan","No timestamp query pool for GPU timers (vkCreateQueryPool returned $result, " +
                    "${slabs.slabs.size} shared pools already held); further timers on ${device.physicalDevice.driverName} record nothing")
                slabs.exhausted=true
                0L
            }
        }
        if(pool==0L) return null
        val slab=Slab(pool)
        slabs.slabs+=slab
        val vkDevice=device.vkDevice
        // Owned by the device: released at renderer shutdown, after every timer registered later.
        AutoDisposer.register(slab,"shared timestamp pool",owner=device) { vkDestroyQueryPool(vkDevice,pool,null) }
        slab.taken[0]=true
        return Slice(pool,0,slab)
    }
}

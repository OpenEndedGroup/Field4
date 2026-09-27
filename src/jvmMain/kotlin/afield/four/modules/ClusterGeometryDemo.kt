package afield.four.modules

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.lighting.LightingGpuTimer
import afield.four.targets.RenderToTexture
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRAccelerationStructure.*
import org.lwjgl.vulkan.VK13.*

/** Computed terrain, using exactly the public ClusterGeometry input contract. The producer shader
 * owns all vertices, indices and descriptions, and is replaceable through ordinary live reload.
 * Each patch has 8x8 cells; [topology] switches diagonals without changing allocations.
 */
class ClusterGeometryDemo(val renderer: Renderer,val columns: Int=32,val rows: Int=24,
    val width: Int=1920,val height: Int=1080,val name: String="computed-clusters",
    producerFactory: (Parameters)->ComputeModulePointer={ComputeModule(it).pointer()}) : Target,AutoCloseable {
    init { require(columns in 1..512 && rows in 1..512 && width>0 && height>0 && width.toLong()*height<=16_777_216) }
    private val owned=mutableListOf<VulkanBuffer>()
    val count=columns*rows
    val geometry=ClusterGeometry(renderer,count,81,128,maxGeometryId=count-1)
    val positions=allocate(count.toLong()*81*16)
    val indices=allocate(count.toLong()*128*12)
    val descriptions=allocate(count.toLong()*32)
    internal val instances=allocate(64)
    private val pixels=allocate(width.toLong()*height*16)
    internal val tlas=AccelerationStructure(renderer,"$name-tlas")
    val producer=producerFactory(parameters(renderer){this.name="$name-producer";compute=resource("terrain.comp")})
    private val produce=ComputeMesh(renderer,producer)
    private val trace=RayTracingModule(parameters(renderer) {
        this.name="$name-rays";allowClusterAccelerationStructure=true
        raygen=resource("terrain.rgen");miss=resource("terrain.rmiss");closesthit=resource("terrain.rchit")
        accelerationStructureResolver={tlas}
    })
    private val rays=RayTracingDispatch(trace.pointer(),width,height)
    val output=RenderToTexture(renderer){this.width=this@ClusterGeometryDemo.width;this.height=this@ClusterGeometryDemo.height;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT}
    private val resolve=Module(parameters(renderer) {
        this.name="$name-resolve";output.apply();depthTest=false;depthWrite=false;blend=false
        vertex="""#version 460
            layout(location=0) out vec2 uv;void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);uv=vec2(p.x,1-p.y);}
        """
        fragment="""#version 460
            #extension GL_EXT_buffer_reference : require
            layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Pixels {vec4 data[];};
            layout(push_constant) uniform Resolve {Pixels pixels;int width;int height;} pc;
            layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
            void main(){ivec2 p=clamp(ivec2(uv*vec2(pc.width,pc.height)),ivec2(0),ivec2(pc.width-1,pc.height-1));color=pc.pixels.data[p.y*pc.width+p.x];}
        """
    })
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    var time=0f
    var animate=true
    var topology=0
    var view=0
    var invalidCluster=-1
    var invalidMode=1
    private var closed=false
    init {
        require(columns in 1..512 && rows in 1..512 && width>0 && height>0)
        check(producer.current.valid && trace.valid && resolve.valid)
        produce.x=(count+63)/64;producer+="generate" to produce
        produce.push.setMembers(producer.current.ci.pushConstants.single().members)
        produce.push["positions"]=positions;produce.push["indices"]=indices;produce.push["descriptions"]=descriptions
        produce.push["columns"]=columns;produce.push["rows"]=rows
        trace.pointer()+="trace" to rays;rays.initializePushConstantsFromModule(trace.pointer())
        rays.push["pixels"]=pixels;rays.push["positions"]=positions;rays.push["indices"]=indices
        val p=resolve.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p);mesh.push.setMembers(resolve.fi.pushConstants.single().members)
        mesh.push["pixels"]=pixels;mesh.push["width"]=width;mesh.push["height"]=height;resolve.meshes["quad"]=mesh;output.modules["resolve"]=p
    }
    private fun allocate(n: Long)=VulkanBuffer(renderer,n,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true).also { owned+=it }
    fun install(): ClusterGeometryDemo {
        val old=renderer.renderList.preTargets[name];require(old==null || old===this || old is ClusterGeometryDemo)
        if(old is ClusterGeometryDemo && old!==this)old.close()
        renderer.renderList.preTargets[name]=this;output.blitToScreen(name=name);return this
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        barrier(command)
        timer.begin(command,index)
        produce.push["time"]=time;produce.push["topology"]=topology;produce.push["invalidCluster"]=invalidCluster;produce.push["invalidMode"]=invalidMode
        producer.current.render(command,Rectanglef())
        geometry.build(command,trace,ClusterGeometryInput(positions,indices,descriptions))
        MemoryStack.stackPush().use { s ->
            val data=s.calloc(64);for(i in 0 until 12)data.putFloat(if(i==0 || i==5 || i==10)1f else 0f)
            data.putInt(0xff shl 24).putInt(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR shl 24).putLong(geometry.deviceAddress()).flip()
            vkCmdUpdateBuffer(command.vkCommandBuffer,instances.buffer,0,data)
        }
        barrier(command);tlas.buildTopLevel(command,instances,1)
        rays.push["time"]=time;rays.push["view"]=view
        trace.render(command,Rectanglef());barrier(command);output.render(command,index)
        timer.end(command,index);if(animate)time+=1f/60
    }
    override fun close() {
        if(closed)return;closed=true;renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name)
        geometry.close();renderer.cleanup+=Runnable {
            producer.current.cleanup();trace.cleanup();resolve.cleanup();tlas.cleanup();owned.asReversed().forEach { it.cleanup() };timer.close()
            output.imageViewArray.forEach { it.imageView.cleanup() };output.colorImageRefs.forEach { it?.cleanup() };output.defaultSampler?.cleanup()
        }
    }
    companion object {
        private fun resource(name: String)=ClusterGeometryDemo::class.java.getResource("/afield/four/geometry/$name")!!.readText()
        private fun barrier(c: CommandBuffer)=MemoryStack.stackPush().use { s ->
            vkCmdPipelineBarrier2(c.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(
                VkMemoryBarrier2.calloc(1,s).`sType$Default`().srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_MEMORY_READ_BIT).dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_MEMORY_READ_BIT)))
        }
    }
}

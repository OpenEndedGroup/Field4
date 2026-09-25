package afield.four.neural

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.lighting.LightingGpuTimer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.stdlib.linalg.Rectanglef
import org.json.JSONObject
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*
import java.io.File
import java.security.MessageDigest

/** A frozen, user-trained shader function. Weights are owned by this view; replace the view at a
 * frame boundary to change weights. The ordinary arithmetic variant needs no cooperative vectors.
 * Architecture/layout are explicit and checked, not a general ML graph runtime.
 */
class LearnedShader(val renderer: Renderer,val width: Int=1920,val height: Int=1080,
    val cooperative: Boolean=renderer.device.supportsCooperativeVector,
    weightsFile: File=File("public/docs/assets/learned_shader/weights.f16"),
    manifestFile: File=File("public/docs/assets/learned_shader/weights.json"),val name: String="learned-shader",attach: Boolean=true) : Target,AutoCloseable {
    private val manifest=JSONObject(manifestFile.readText())
    private val weights=weightsFile.readBytes()
    private val offsets=List(6){manifest.getJSONArray("offsets").getInt(it)}
    private val bindings=mutableMapOf<String,BufferParameters>()
    private val owned=mutableListOf<VulkanBuffer>()
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    private val hasAnalyticReference=manifest.optString("sourceKind","builtin")=="builtin"
    val parameterRange: ClosedFloatingPointRange<Float> = manifest.optJSONArray("parameterRange")?.let {
        require(it.length()==2);it.getDouble(0).toFloat()..it.getDouble(1).toFloat()
    } ?: (-1f..1f)
    var time=0f
    /** Map the original capture parameter (for example seconds) into the network's normalized time. */
    fun setParameter(value: Float) {
        require(value.isFinite() && value in parameterRange) { "Parameter $value outside captured domain $parameterRange" }
        time=(value-parameterRange.start)/(parameterRange.endInclusive-parameterRange.start)*2-1
    }
    /** 0 original function, 1 learned function, 2 original / learned / absolute error x8. */
    var view=if(hasAnalyticReference)2 else 1
        set(v){require(v in 0..2 && (hasAnalyticReference || v==1)) { "Captured models have no embedded analytic reference; use ShaderLearningView to compare with the real source" };field=v}
    val output=RenderToTexture(renderer){this.width=this@LearnedShader.width;this.height=this@LearnedShader.height;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT}
    private val compute: ComputeModule
    private val task: ComputeMesh
    private val resolve: Module
    private var closed=false
    val allocatedBytes get()=owned.sumOf { it.requestedSize }+output.colorImageRefs.sumOf { it?.memorySize ?: 0 }
    init {
        require(parameterRange.start.isFinite() && parameterRange.endInclusive.isFinite() && parameterRange.start<parameterRange.endInclusive)
        require(width>0 && height>0 && width.toLong()*height<=16_777_216)
        require(manifest.getInt("schema")==1 && manifest.getJSONArray("shape").toList()==listOf(16,32,32,3))
        require(offsets.all { it%64==0 } && offsets==listOf(0,1024,1088,3136,3200,3392) && weights.size==3456) { "Frozen MLP layout mismatch" }
        require(MessageDigest.getInstance("SHA-256").digest(weights).joinToString(""){"%02x".format(it)}==manifest.getString("sha256")) { "Weight checksum mismatch" }
        if(cooperative) {
            check(renderer.device.supportsCooperativeVector) { "Cooperative vectors were not enabled" }
            MemoryStack.stackPush().use { s ->
                val count=s.mallocInt(1);NVCooperativeVector.vkGetPhysicalDeviceCooperativeVectorPropertiesNV(renderer.device.physicalDevice.vkPhysicalDevice,count,null)
                val props=VkCooperativeVectorPropertiesNV.calloc(count[0],s)
                for(i in 0 until count[0])props[i].`sType$Default`()
                NVCooperativeVector.vkGetPhysicalDeviceCooperativeVectorPropertiesNV(renderer.device.physicalDevice.vkPhysicalDevice,count,props)
                check((0 until count[0]).any { val p=props[it];p.inputType()==0 && p.inputInterpretation()==0 && p.matrixInterpretation()==0 && p.biasInterpretation()==0 && p.resultType()==0 }) { "FP16 cooperative-vector combination unavailable" }
            }
        }
        val binary=File("build/lighting/learned-shader/learned-${if(cooperative)"coop" else "scalar"}.spv")
        check(binary.isFile) { "Build optional learned shaders with: ./gradlew buildLearnedShaders" }
        val reflection=Introspection.fromSpirv("Field learned function",ShaderKind.COMPUTE_SHADER,binary.readBytes())
        check(reflection.valid && reflection.fixedWorkgroupSize==listOf(8,8,1))
        // Field rounds the 40-byte std430 payload to a 16-byte block boundary.
        check(reflection.pushConstants.single().size==48 && reflection.pushConstants.single().members.map { it.offset }==(0..36 step 4).toList()) { "Learned shader push ABI mismatch" }
        check(reflection.ssbo.map { it.binding }.sorted()==listOf(0,1) && reflection.ssbo.all { it.set==0 })
        reflection.ssbo.replaceAll { b ->
            check(b.members.single().arrayStride==if(b.binding==0)4 else 16)
            Introspected.UBO(if(b.binding==0)"weights" else "pixels",b.size,b.set,b.binding,b.members)
        }
        compute=ComputeModule(Parameters(renderer,name=name,computeIntrospection=reflection,ssboResolver={ requested ->
            bindings.getOrPut(requested.name) { requested.customize.add {
                size=when(name){"weights"->weights.size.toLong();"pixels"->width.toLong()*height*16;else->error("Unexpected learned shader binding $name")}
                if(name=="pixels"){notHosted();memory=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    usage=usage or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;needsDeviceAddress=true}
            };requested }
        }))
        check(compute.valid)
        bindings.values.flatMap { it.result!!.buffers }.forEach { owned+=it }
        bindings.getValue("weights").result!!.get().mapAndWrite_bytes { put(weights) }
        val p=compute.pointer();task=ComputeMesh(renderer,p);task.x=(width+7)/8;task.y=(height+7)/8
        task.push.setMembers(reflection.pushConstants.single().members);p+="mlp" to task
        task.push["width"]=width;task.push["height"]=height
        listOf("w0","b0","w1","b1","w2","b2").forEachIndexed { i,key -> task.push[key]=offsets[i] }
        resolve=Module(parameters(renderer){this.name="$name-resolve";output.apply();vertex=VERTEX;fragment=FRAGMENT;depthTest=false;depthWrite=false;blend=false})
        check(resolve.valid);val rp=resolve.pointer();val mesh=Mesh(renderer,rp);mesh.resize(3,0,rp)
        mesh.push.setMembers(resolve.fi.pushConstants.single().members);mesh.push["pixels"]=bindings.getValue("pixels").result!!.get();mesh.push["width"]=width
        resolve.meshes["quad"]=mesh;output.modules["resolve"]=rp
        if(attach)attach()
    }
    fun attach(): LearnedShader {check(!closed);renderer.renderList.preTargets[name]=this;output.blitToScreen(name=name);return this}
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        require(time.isFinite() && time in -1f..1f) { "Training domain is [-1,1] in x, y and time" }
        task.push["time"]=time;task.push["view"]=view
        barrier(command,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
        timer.begin(command,index);compute.render(command,Rectanglef());timer.end(command,index)
        barrier(command,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT)
        output.render(command,index)
    }
    private fun barrier(c: CommandBuffer,src: Long,access: Long,dst: Long,dstAccess: Long)=MemoryStack.stackPush().use { s ->
        vkCmdPipelineBarrier2(c.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(VkMemoryBarrier2.calloc(1,s).`sType$Default`()
            .srcStageMask(src).srcAccessMask(access).dstStageMask(dst).dstAccessMask(dstAccess)))
    }
    override fun close() {
        if(closed)return;closed=true;renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name)
        renderer.cleanup+=Runnable {compute.cleanup();resolve.cleanup();owned.forEach { it.cleanup() };timer.close()
            output.imageViewArray.forEach { it.imageView.cleanup() };output.colorImageRefs.forEach { it?.cleanup() };output.defaultSampler?.cleanup()}
    }
    companion object {
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val FRAGMENT="""#version 460
        #extension GL_EXT_buffer_reference : require
        #extension GL_EXT_scalar_block_layout : require
        layout(buffer_reference,std430) readonly buffer Pixels {vec4 data[];};
        layout(push_constant,scalar) uniform P {Pixels pixels;uint width;} p;
        layout(location=0) out vec4 color;
        void main(){uvec2 xy=uvec2(gl_FragCoord.xy);color=p.pixels.data[xy.y*p.width+xy.x];}
        """
    }
}

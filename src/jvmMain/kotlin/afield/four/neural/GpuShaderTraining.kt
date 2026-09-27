package afield.four.neural

import afield.four.Renderer
import afield.four.buffers.*
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.lighting.LightingGpuTimer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.vkCheck
import afield.stdlib.fromViews
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.NVCooperativeVector.*
import org.lwjgl.vulkan.KHRCooperativeMatrix.VK_COMPONENT_TYPE_FLOAT16_KHR
import org.lwjgl.vulkan.VK13.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.random.Random

/** A small reusable learned RGB function, trained directly from Field images on the graphics queue.
 * Architecture matches LearnedShader: 16 positional features -> 32 ReLU -> 32 ReLU -> RGB.
 * Slang differentiates the network; cooperative-vector outer products accumulate its gradients.
 * FP32 Adam master weights/moments, loss-scaled FP16 gradients and inference matrices.
 * The source remains caller-owned. Train/evaluate/close only between or within render-thread work.
 * No CPU image/weight readback is used in train/evaluate. This is not a general graph ML runtime.
 */
class GpuShaderTraining(val renderer: Renderer,val width: Int,val height: Int,val batchSize: Int=256,val seed: Int=42):AutoCloseable {
    init {
        require(renderer.device.supportsCooperativeVectorTraining) { "Cooperative-vector training is not enabled; offline ShaderTraining remains available" }
        require(width>0 && height>0 && width.toLong()*height<=16_777_216 && batchSize in 32..4096)
        for(entry in listOf("train","optimize","evaluate"))require(File("build/lighting/learned-shader/training-$entry.spv").isFile) {
            "Build optional shaders with ./gradlew buildLearnedShaders"
        }
        MemoryStack.stackPush().use { s ->
            val p=VkPhysicalDeviceCooperativeVectorPropertiesNV.calloc(s).`sType$Default`()
            VK11.vkGetPhysicalDeviceProperties2(renderer.device.physicalDevice.vkPhysicalDevice,VkPhysicalDeviceProperties2.calloc(s).`sType$Default`().pNext(p.address()))
            require(p.cooperativeVectorTrainingFloat16Accumulation()) { "FP16 cooperative-vector gradient accumulation unavailable" }
        }
    }
    data class Layer(val inputs: Int,val outputs: Int,val matrixOffset: Int,val biasOffset: Int,val matrixBytes: Int)
    val layers: List<Layer>
    val trainingBytes: Int
    private val owned=mutableListOf<VulkanBuffer>()
    private val modules=mutableListOf<ComputeModule>()
    private val tasks=mutableMapOf<String,ComputeMesh>()
    private val parameters=mutableMapOf<String,BufferParameters>()
    private val initial: VulkanBuffer
    internal val weights: VulkanBuffer
    internal val gradients: VulkanBuffer
    internal val optimizer: VulkanBuffer
    private val samples: VulkanBuffer
    private val pixels: VulkanBuffer
    val output=RenderToTexture(renderer){this.width=this@GpuShaderTraining.width;this.height=this@GpuShaderTraining.height;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT}
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
    private var source: HasImageView=output
    private val sourceProvider=object:HasImageViewArray,PreparesSampledImages {
        override val imageViewArray get()=listOf(source)
        override fun prepareSampledImagesFor(command: CommandBuffer) { (source as? PreparesSampledImages)?.prepareSampledImagesFor(command) }
    }
    private val gather: ComputeModule
    private val gatherTask: ComputeMesh
    private val resolve: Module
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    val allocatedBytes get()=owned.sumOf { it.requestedSize }+output.colorImageRefs.sumOf { it?.memorySize ?: 0 }
    var steps=0;private set
    var learningRate=.001f
        set(value){require(value.isFinite() && value>0 && value<=.1f);field=value}
    private var initialized=false
    private var closed=false
    init {
        var cursor=0
        layers=listOf(16 to 32,32 to 32,32 to 3).map { (inputs,outputs) ->
            val bytes=MemoryStack.stackPush().use { s ->
                val size=s.callocPointer(1);val info=conversion(s,inputs,outputs).pDstSize(size)
                vkCheck(vkConvertCooperativeVectorMatrixNV(renderer.device.vkDevice,info),"training matrix layout query")
                size[0].toInt().also { require(it>0) }
            }
            val m=align(cursor);val b=align(m+bytes);cursor=b+outputs*2;Layer(inputs,outputs,m,b,bytes)
        }
        trainingBytes=align(cursor)
        initial=allocate(3456,host=true)
        val random=Random(seed);initial.mapAndWrite_bytes {
            for(i in 0 until 3456)put(i,0)
            val offsets=listOf(0,1088,3200)
            layers.forEachIndexed { layer,l ->val range=sqrt(6f/(l.inputs+l.outputs))
                repeat(l.inputs*l.outputs){n->putShort(offsets[layer]+n*2,java.lang.Float.floatToFloat16((random.nextFloat()*2-1)*range))} }
        }
        weights=allocate(trainingBytes.toLong());gradients=allocate(trainingBytes.toLong())
        optimizer=allocate(trainingBytes.toLong()/2*16);samples=allocate(batchSize.toLong()*32);pixels=allocate(width.toLong()*height*16)
        val bindings=mapOf(0 to weights,1 to gradients,2 to optimizer,3 to samples,4 to pixels,5 to weights)
        for(entry in listOf("train","optimize","evaluate")) {
            val file=File("build/lighting/learned-shader/training-$entry.spv")
            check(file.isFile) { "Build optional shaders with ./gradlew buildLearnedShaders" }
            val reflection=Introspection.fromSpirv("GPU learning $entry",ShaderKind.COMPUTE_SHADER,file.readBytes())
            check(reflection.valid && reflection.pushConstants.single().members.map { it.offset }==(0..52 step 4).toList()) { "GPU training push ABI mismatch" }
            reflection.ssbo.replaceAll { b ->check(b.set==0 && b.binding in bindings);Introspected.UBO("buffer${b.binding}",b.size,b.set,b.binding,b.members) }
            val module=ComputeModule(Parameters(renderer,name="learning-$entry",computeIntrospection=reflection,ssboResolver={ requested ->
                val buffer=bindings.getValue(requested.source!!.binding)
                requested.size=buffer.requestedSize;requested.usage=buffer.usage;requested.memory=buffer.reqMask
                buffer.source=requested.store();requested.result=FrameBufferGroup(renderer,n=1,buffers=mutableListOf(buffer));parameters["$entry-${requested.name}"]=requested
                requested
            }))
            check(module.valid);modules+=module
            val p=module.pointer();val task=ComputeMesh(renderer,p);p+=entry to task;task.push.setMembers(reflection.pushConstants.single().members)
            task.push["step"]=0;task.push["rate"]=learningRate;task.push["time"]=0f;task.push["view"]=0
            task.push["count"]=batchSize;task.push["width"]=width;task.push["height"]=height;task.push["words"]=trainingBytes/4
            layers.forEachIndexed { i,l ->task.push["w$i"]=l.matrixOffset;task.push["b$i"]=l.biasOffset };tasks[entry]=task
        }
        tasks.getValue("train").x=(batchSize+63)/64;tasks.getValue("optimize").x=(trainingBytes/4+63)/64
        tasks.getValue("evaluate").apply {x=(width+7)/8;y=(height+7)/8}
        gather=ComputeModule(parameters(renderer){name="learning-sample-source";compute=GATHER
            textureResolver={fromViews(sourceProvider,it.name,sampler)} })
        check(gather.valid);val g=gather.pointer();gatherTask=ComputeMesh(renderer,g);g+="samples" to gatherTask
        gatherTask.x=(batchSize+63)/64;gatherTask.push.setMembers(gather.ci.pushConstants.single().members)
        gatherTask.push["samples"]=samples;gatherTask.push["count"]=batchSize
        resolve=Module(parameters(renderer){name="learning-resolve";output.apply();vertex=VERTEX;fragment=FRAGMENT;depthTest=false;depthWrite=false;blend=false})
        check(resolve.valid);val p=resolve.pointer();val quad=Mesh(renderer,p);quad.resize(3,0,p);quad.push.setMembers(resolve.fi.pushConstants.single().members)
        quad.push["pixels"]=pixels;quad.push["width"]=width;resolve.meshes["quad"]=quad;output.modules["resolve"]=p
    }
    private fun allocate(size: Long,host: Boolean=false)=VulkanBuffer(renderer,size,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        if(host)VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT else VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        needsDeviceAddress=true).also { owned+=it }
    /** Restart the deterministic initial model, including optimizer state, on the next recorded call. */
    fun reset() { check(!closed);initialized=false;steps=0 }
    private fun initialize(command: CommandBuffer) {
        if(initialized)return
        barrier(command)
        vkCmdFillBuffer(command.vkCommandBuffer,weights.buffer,0,weights.requestedSize,0)
        vkCmdFillBuffer(command.vkCommandBuffer,optimizer.buffer,0,optimizer.requestedSize,0)
        barrier(command)
        MemoryStack.stackPush().use { s ->
            val matrixOffsets=listOf(0,1088,3200);val biasOffsets=listOf(1024,3136,3392)
            layers.forEachIndexed { i,l ->
                val info=VkConvertCooperativeVectorMatrixInfoNV.calloc(1,s);info[0].set(conversion(s,l.inputs,l.outputs))
                info.srcData().deviceAddress(initial.getGPUAddress(command)+matrixOffsets[i]);info.dstData().deviceAddress(weights.getGPUAddress(command)+l.matrixOffset)
                info.pDstSize(s.pointers(l.matrixBytes.toLong()));vkCmdConvertCooperativeVectorMatrixNV(command.vkCommandBuffer,info)
                vkCmdCopyBuffer(command.vkCommandBuffer,initial.buffer,weights.buffer,VkBufferCopy.calloc(1,s).srcOffset(biasOffsets[i].toLong())
                    .dstOffset(l.biasOffset.toLong()).size(l.outputs*2L))
            }
        }
        barrier(command);initialized=true
    }
    /** Sample the already-rendered source at this normalized parameter, then perform [iterations]
     * Adam updates. Each step uses a new reproducible minibatch. Every eighth hashed pixel is
     * held out; evaluate that subset separately when judging generalization. */
    fun train(command: CommandBuffer,image: HasImageView,parameter: Float,iterations: Int=1) {
        check(!closed && !command.inRenderPassInstance);require(parameter.isFinite() && parameter in -1f..1f && iterations in 1..64)
        val data=requireNotNull(image.imageView.underlyingImage).imageData
        require(data.width.toLong()*data.height>=32 && data.usage and VK_IMAGE_USAGE_SAMPLED_BIT!=0)
        require(image.imageView.underlyingImage!!.format in setOf(VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT)) { "Train on linear RGBA16F/RGBA32F; keep coverage separate" }
        source=image;initialize(command);timer.begin(command,renderer.currentImage)
        repeat(iterations) {
            barrier(command);vkCmdFillBuffer(command.vkCommandBuffer,gradients.buffer,0,gradients.requestedSize,0)
            gatherTask.push["parameter"]=parameter;gatherTask.push["seed"]=seed+steps;gather.render(command,Rectanglef());barrier(command)
            tasks.getValue("train").push["step"]=steps+1;modules[0].render(command,Rectanglef());barrier(command)
            val task=tasks.getValue("optimize");task.push["step"]=steps+1;task.push["rate"]=learningRate
            modules[1].render(command,Rectanglef());barrier(command);steps++
        }
        timer.end(command,renderer.currentImage)
    }
    /** Evaluate the learned function at any point in the trained parameter interval; source is not rendered. */
    fun evaluate(command: CommandBuffer,index: Int,parameter: Float) {
        check(!closed);require(parameter.isFinite() && parameter in -1f..1f);initialize(command);barrier(command)
        tasks.getValue("evaluate").push["time"]=parameter;modules[2].render(command,Rectanglef());barrier(command);output.render(command,index)
    }
    /** Blocking, explicit checkpoint BETWEEN frames. Converts opaque training matrices to the
     * existing portable LearnedShader row-major format. No training-optimal layout escapes into
     * the file. The caller records the source digest and fixed-state description it actually used.
     * A new directory prevents a partial checkpoint from replacing a working model. */
    fun save(directory: File,sourceShaderDigest: String,fixedState: String): File {
        check(!closed && initialized && steps>0 && renderer.currentCommandBuffer==null)
        require(sourceShaderDigest.matches(Regex("[0-9a-f]{64}")) && fixedState.isNotBlank())
        require(!directory.exists() || directory.listFiles().isNullOrEmpty()) { "Choose a new model directory: $directory" }
        directory.mkdirs()
        val packed=VulkanBuffer(renderer,3456,VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,needsDeviceAddress=true)
        val command=CommandBuffer(renderer.commandPool,true,false);val fence=Fence(renderer.device,false)
        val bytes=ByteArray(3456)
        try {
            command.beginRecording();barrier(command);vkCmdFillBuffer(command.vkCommandBuffer,packed.buffer,0,3456,0);barrier(command)
            MemoryStack.stackPush().use { s ->
                val matrices=listOf(0,1088,3200);val biases=listOf(1024,3136,3392)
                layers.forEachIndexed { i,l ->
                    val info=VkConvertCooperativeVectorMatrixInfoNV.calloc(1,s)
                    info[0].set(conversion(s,l.inputs,l.outputs).srcSize(l.matrixBytes.toLong())
                        .srcLayout(VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_TRAINING_OPTIMAL_NV).srcStride(0)
                        .dstLayout(VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_ROW_MAJOR_NV).dstStride(l.inputs*2L)
                        .pDstSize(s.pointers(l.inputs*l.outputs*2L)))
                    info.srcData().deviceAddress(weights.getGPUAddress(command)+l.matrixOffset)
                    info.dstData().deviceAddress(packed.getGPUAddress(command)+matrices[i])
                    vkCmdConvertCooperativeVectorMatrixNV(command.vkCommandBuffer,info)
                    vkCmdCopyBuffer(command.vkCommandBuffer,weights.buffer,packed.buffer,VkBufferCopy.calloc(1,s)
                        .srcOffset(l.biasOffset.toLong()).dstOffset(biases[i].toLong()).size(l.outputs*2L))
                }
            }
            barrier(command);command.endRecording()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
            fence.fenceWait();packed.mapAndRead_bytes { get(bytes) }
        } finally { fence.cleanup();command.cleanup();packed.cleanup() }
        val values=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        require((0 until values.remaining()).all { java.lang.Float.float16ToFloat(values[it]).isFinite() }) { "Model contains nonfinite weights" }
        File(directory,"weights.f16").writeBytes(bytes)
        File(directory,"weights.json").writeText(JSONObject().put("schema",1).put("shape",listOf(16,32,32,3))
            .put("offsets",listOf(0,1024,1088,3136,3200,3392)).put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            .put("sourceKind","captured-module").put("sourceShaderDigest",sourceShaderDigest).put("fixedState",fixedState)
            .put("parameterRange",listOf(-1,1)).put("steps",steps).put("seed",seed).put("batchSize",batchSize)
            .put("training","Vulkan cooperative vectors; Slang autodiff; FP32 Adam; FP16 gradients and published weights")
            .put("validation","Not measured by save; inspect held-out frames separately").toString(2)+"\n")
        return directory
    }
    override fun close() { if(closed)return;closed=true;renderer.cleanup+=Runnable {
        modules.forEach { it.cleanup() };gather.cleanup();resolve.cleanup();sampler.cleanup();timer.close();owned.asReversed().forEach { it.cleanup() }
        output.imageViewArray.forEach { it.imageView.cleanup() };output.colorImageRefs.forEach { it?.cleanup() };output.defaultSampler?.cleanup()
    } }
    companion object {
        private fun align(x: Int)=(x+63)/64*64
        private fun conversion(s: MemoryStack,inputs: Int,outputs: Int)=VkConvertCooperativeVectorMatrixInfoNV.calloc(s).`sType$Default`()
            .srcSize(inputs*outputs*2L).srcComponentType(VK_COMPONENT_TYPE_FLOAT16_KHR).dstComponentType(VK_COMPONENT_TYPE_FLOAT16_KHR)
            .numRows(outputs).numColumns(inputs).srcLayout(VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_ROW_MAJOR_NV).srcStride(inputs*2L)
            .dstLayout(VK_COOPERATIVE_VECTOR_MATRIX_LAYOUT_TRAINING_OPTIMAL_NV).dstStride(0)
        private fun barrier(c: CommandBuffer)=MemoryStack.stackPush().use { s ->
            vkCmdPipelineBarrier2(c.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(VkMemoryBarrier2.calloc(1,s).`sType$Default`()
                .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT)))
        }
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val FRAGMENT="""#version 460
        #extension GL_EXT_buffer_reference : require
        layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Pixels {vec4 data[];};
        layout(push_constant) uniform P {Pixels pixels;uint width;} p;
        layout(location=0) out vec4 color;void main(){uvec2 xy=uvec2(gl_FragCoord.xy);color=p.pixels.data[xy.y*p.width+xy.x];}
        """
        private const val GATHER="""#version 460
        #extension GL_EXT_buffer_reference : require
        layout(local_size_x=64) in;
        layout(buffer_reference,std430,buffer_reference_align=16) buffer Samples {vec4 data[];};
        layout(set=0,binding=0) uniform sampler2D source;
        layout(push_constant) uniform P {Samples samples;uint count;float parameter;uint seed;} p;
        uint hash(uint x){x^=x>>16;x*=0x7feb352du;x^=x>>15;x*=0x846ca68bu;return x^(x>>16);}
        void main(){uint id=gl_GlobalInvocationID.x;if(id>=p.count)return;ivec2 size=textureSize(source,0);uint total=uint(size.x*size.y);
            uint pixel=hash(id^hash(p.seed))%total;for(int n=0;n<32 && (hash(pixel)&7)==0;n++)pixel=(pixel+1)%total;
            ivec2 xy=ivec2(pixel%uint(size.x),pixel/uint(size.x));vec4 target=texelFetch(source,xy,0);
            bool valid=(hash(pixel)&7)!=0 && abs(target.a-1)<.001 && !any(isnan(target)) && !any(isinf(target));
            p.samples.data[id*2]=vec4((vec2(xy)+.5)/vec2(size)*2-1,p.parameter,1);
            p.samples.data[id*2+1]=vec4(valid?target.rgb:vec3(0),valid?1:0);}
        """
    }
}

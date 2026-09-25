package afield.four.vision

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.*
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.vkCheck
import afield.four.util.AutoDisposer
import afield.stdlib.fromViews
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.NVOpticalFlow.*

/** Dedicated hardware image correspondence. Call [evaluate] between Field frames, after producers
 * have submitted. Images stay on the GPU. This first implementation waits for one final fence;
 * it does not overlap pairs, and includes conversion/synchronization in [milliseconds].
 *
 * [first] -> [second]: output.xy is displacement in INPUT pixels, X right/Y down. One output texel
 * covers [grid] input pixels. output.z is raw hardware cost (lower is better), NOT confidence.
 * Inputs are opaque linear RGB; both are tone-mapped identically to BGRA8 for the motion engine.
 * Concurrent-sharing private images and binary semaphores connect graphics and optical queues.
 *
 * [first] and [second] are borrowed [HasImageView] providers from this renderer's device.
 * A [RenderToTexture] supplies its first color attachment; use `imageViewArray[index]` for another.
 * Keep both input images alive and at the construction size until this operation is closed.
 * [output] is an owned [RenderToTexture], usable as an ordinary sampled texture after [evaluate].
 * The operation itself is not a texture and is not automatically scheduled by the renderer.
 */
class OpticalFlow(val renderer: Renderer,val first: HasImageView,val second: HasImageView,
    val grid: Int=4,val quality: Quality=Quality.SLOW) : AutoCloseable {
    enum class Quality(internal val vk: Int) { FAST(VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_FAST_NV),MEDIUM(VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_MEDIUM_NV),SLOW(VK_OPTICAL_FLOW_PERFORMANCE_LEVEL_SLOW_NV) }
    private val device=renderer.device.also {
        check(it.supportsOpticalFlow) { "VK_NV_optical_flow is not enabled" }
        require(grid in listOf(1,2,4,8))
        require(first.imageView.underlyingImage!=null && second.imageView.underlyingImage!=null)
    }
    val width=first.imageView.underlyingImage!!.imageData.width
    val height=first.imageView.underlyingImage!!.imageData.height
    val flowWidth=(width+grid-1)/grid
    val flowHeight=(height+grid-1)/grid
    private val family=(0 until device.physicalDevice.vkQueueFamilyProps.capacity()).firstOrNull {
        device.physicalDevice.vkQueueFamilyProps[it].queueFlags() and VK_QUEUE_OPTICAL_FLOW_BIT_NV!=0
    } ?: error("No optical-flow queue family")
    private val opticalQueue=Queue(device,family,0)
    val opticalTimestampBits=device.physicalDevice.vkQueueFamilyProps[family].timestampValidBits()
    private val conversionTimer=afield.four.lighting.LightingGpuTimer(renderer)
    private val opticalTimer=afield.four.lighting.LightingGpuTimer(renderer,family)
    private val unpackTimer=afield.four.lighting.LightingGpuTimer(renderer)
    val gpuTimings get()=mapOf("conversion" to conversionTimer.samples.toList(),"opticalFlow" to opticalTimer.samples.toList(),"unpack" to unpackTimer.samples.toList())
    private val pool=CommandPool(device,family)
    private val commands=listOf(CommandBuffer(renderer.commandPool,true,false),CommandBuffer(pool,true,false),CommandBuffer(renderer.commandPool,true,false))
    private val ready=Semaphore(device,false)
    private val done=Semaphore(device,false)
    private val fence=Fence(device,false)
    private var session=0L
    private val nativeDisposer: AutoDisposer.Token
    private val inputs=mutableListOf<Image>()
    private val outputs=mutableListOf<Image>()
    private val views=mutableListOf<ImageView>()
    private val conversion=RenderToTexture(renderer) {
        this.width=this@OpticalFlow.width;this.height=this@OpticalFlow.height;colorAttachments=2;useDepth=false
        colorFormats=listOf(VK_FORMAT_B8G8R8A8_UNORM,VK_FORMAT_B8G8R8A8_UNORM);debugName="optical RGB conversion"
    }
    /** Sampled RGBA32F texture: XY displacement in input pixels, Z matching cost, W = 1.
     * Dimensions are ceil(input width/grid) by ceil(input height/grid), owned by this operation. */
    val output=RenderToTexture(renderer) { width=flowWidth;height=flowHeight;format=VK_FORMAT_R32G32B32A32_SFLOAT;useDepth=false;debugName="motion field and cost" }
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
    private val convert=Module(parameters(renderer) {
        name="optical input conversion";conversion.apply();vertex=VERTEX;fragment=CONVERT;depthTest=false;depthWrite=false;blend=false
        textureResolver={ t -> fromViews(listOf(if(t.name=="firstImage")first else second),t.name,sampler) }
    })
    private val packed=VulkanBuffer(renderer,flowWidth.toLong()*flowHeight*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true)
    private val costs=VulkanBuffer(renderer,flowWidth.toLong()*flowHeight*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true)
    private val resolve=Module(parameters(renderer) {name="optical unpack";output.apply();vertex=VERTEX;fragment=UNPACK;depthTest=false;depthWrite=false;blend=false})
    private var closed=false
    private var firstPair=true
    val milliseconds=ArrayDeque<Double>()
    val allocatedBytes get()=inputs.sumOf { it.memorySize }+outputs.sumOf { it.memorySize }+
        conversion.colorImageRefs.sumOf { it?.memorySize ?: 0 }+output.colorImageRefs.sumOf { it?.memorySize ?: 0 }+packed.requestedSize+costs.requestedSize
    init {
        check(device.supportsOpticalFlow) { "VK_NV_optical_flow is not enabled" }
        require(grid in listOf(1,2,4,8))
        require(second.imageView.underlyingImage!!.imageData.let { it.width==width && it.height==height })
        MemoryStack.stackPush().use { s ->
            val props=VkPhysicalDeviceOpticalFlowPropertiesNV.calloc(s).`sType$Default`()
            vkGetPhysicalDeviceProperties2(device.physicalDevice.vkPhysicalDevice,VkPhysicalDeviceProperties2.calloc(s).`sType$Default`().pNext(props.address()))
            require(width in props.minWidth()..props.maxWidth() && height in props.minHeight()..props.maxHeight())
            require(props.supportedOutputGridSizes() and gridFlag()!=0 && props.costSupported())
            fun supported(usage: Int,format: Int): Boolean {
                val info=VkOpticalFlowImageFormatInfoNV.calloc(s).`sType$Default`().usage(usage);val count=s.mallocInt(1)
                vkCheck(vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.physicalDevice.vkPhysicalDevice,info,count,null),"flow formats")
                val formats=VkOpticalFlowImageFormatPropertiesNV.calloc(count[0],s)
                for(i in 0 until count[0])formats[i].`sType$Default`()
                vkCheck(vkGetPhysicalDeviceOpticalFlowImageFormatsNV(device.physicalDevice.vkPhysicalDevice,info,count,formats),"flow formats")
                return (0 until count[0]).any { formats[it].format()==format }
            }
            check(supported(VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV,VK_FORMAT_B8G8R8A8_UNORM) &&
                supported(VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,VK_FORMAT_R16G16_S10_5_NV) && supported(VK_OPTICAL_FLOW_USAGE_COST_BIT_NV,VK_FORMAT_R32_UINT)) { "Required optical-flow image formats unavailable" }
            val create=VkOpticalFlowSessionCreateInfoNV.calloc(s).`sType$Default`().width(width).height(height)
                .imageFormat(VK_FORMAT_B8G8R8A8_UNORM).flowVectorFormat(VK_FORMAT_R16G16_S10_5_NV).costFormat(VK_FORMAT_R32_UINT)
                .outputGridSize(gridFlag()).performanceLevel(quality.vk).flags(VK_OPTICAL_FLOW_SESSION_CREATE_ENABLE_COST_BIT_NV)
            val result=s.mallocLong(1);vkCheck(vkCreateOpticalFlowSessionNV(device.vkDevice,create,null,result),"create optical flow");session=result[0]
        }
        fun image(w: Int,h: Int,format: Int,usage: Int,opticalUsage: Int)=Image(device,Image.ImageData().format(format).width(w).height(h).usage(usage or VK_IMAGE_USAGE_SAMPLED_BIT).apply {
            queueFamilies=intArrayOf(renderer.graphQueue.queueFamilyIndex,family);opticalFlowUsage=opticalUsage
        })
        repeat(2){inputs+=image(width,height,VK_FORMAT_B8G8R8A8_UNORM,VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV)}
        outputs+=image(flowWidth,flowHeight,VK_FORMAT_R16G16_S10_5_NV,VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV)
        outputs+=image(flowWidth,flowHeight,VK_FORMAT_R32_UINT,VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_OPTICAL_FLOW_USAGE_COST_BIT_NV)
        (inputs+outputs).forEach { im -> views+=ImageView(device,im,ImageView.ImageViewData().format(im.format).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)) }
        listOf(VK_OPTICAL_FLOW_SESSION_BINDING_POINT_INPUT_NV,VK_OPTICAL_FLOW_SESSION_BINDING_POINT_REFERENCE_NV,
            VK_OPTICAL_FLOW_SESSION_BINDING_POINT_FLOW_VECTOR_NV,VK_OPTICAL_FLOW_SESSION_BINDING_POINT_COST_NV).forEachIndexed { i,binding ->
            vkCheck(vkBindOpticalFlowSessionImageNV(device.vkDevice,session,binding,views[i].vkImageView,
                VK_IMAGE_LAYOUT_GENERAL),"bind optical image")
        }
        check(convert.valid && resolve.valid)
        fun quad(module: Module,target: RenderToTexture): Mesh {val p=module.pointer();val m=Mesh(renderer,p);m.resize(3,0,p);module.meshes["quad"]=m;target.modules["quad"]=p;return m}
        quad(convert,conversion)
        quad(resolve,output).apply { push.setMembers(resolve.fi.pushConstants.single().members);push["flow"]=packed;push["cost"]=costs;push["width"]=flowWidth }
        // Unlike ordinary Field images/buffers, these extension/queue objects have no automatic
        // wrapper lifetime. Capture only native handles so shutdown also covers an unclosed view.
        val nativeDevice=device.vkDevice;val nativeSession=session;val nativePool=pool.vkCommandPool
        val nativeReady=ready.vkSemaphore;val nativeDone=done.vkSemaphore
        nativeDisposer=AutoDisposer.register(this,"optical flow session and queue",owner=device) {
            vkDestroyOpticalFlowSessionNV(nativeDevice,nativeSession,null)
            vkDestroyCommandPool(nativeDevice,nativePool,null)
            vkDestroySemaphore(nativeDevice,nativeReady,null);vkDestroySemaphore(nativeDevice,nativeDone,null)
        }
    }
    private fun gridFlag()=when(grid){1->VK_OPTICAL_FLOW_GRID_SIZE_1X1_BIT_NV;2->VK_OPTICAL_FLOW_GRID_SIZE_2X2_BIT_NV;4->VK_OPTICAL_FLOW_GRID_SIZE_4X4_BIT_NV;else->VK_OPTICAL_FLOW_GRID_SIZE_8X8_BIT_NV}
    /** Reset temporal hints for a cut, image-order change or nonconsecutive pair. */
    fun evaluate(reset: Boolean=false) {
        check(!closed && renderer.currentCommandBuffer==null) { "Evaluate optical flow between frames, after input producers submit" }
        val start=System.nanoTime()
        val prep=commands[0];val flow=commands[1];val finish=commands[2]
        commands.forEach { vkResetCommandBuffer(it.vkCommandBuffer,0);it.beginRecording() }
        (first as? PreparesSampledImages)?.prepareSampledImagesFor(prep);(second as? PreparesSampledImages)?.prepareSampledImagesFor(prep)
        conversionTimer.begin(prep,0)
        conversion.render(prep,0)
        MemoryStack.stackPush().use { s ->
            for(i in 0..1) {
                val source=conversion.colorImageRefs[i]!!
                transition(prep,source,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                transition(prep,inputs[i],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                val copy=VkImageCopy.calloc(1,s).srcSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1) }
                    .dstSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1) }.extent { it.set(width,height,1) }
                vkCmdCopyImage(prep.vkCommandBuffer,source.vkImage,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,inputs[i].vkImage,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy)
                transition(prep,source,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                transition(prep,inputs[i],VK_IMAGE_LAYOUT_GENERAL)
            }
            outputs.forEach { transition(prep,it,VK_IMAGE_LAYOUT_GENERAL) }
            conversionTimer.end(prep,0);opticalTimer.begin(flow,0)
            vkCmdOpticalFlowExecuteNV(flow.vkCommandBuffer,session,VkOpticalFlowExecuteInfoNV.calloc(s).`sType$Default`()
                .flags(if(reset||firstPair)VK_OPTICAL_FLOW_EXECUTE_DISABLE_TEMPORAL_HINTS_BIT_NV else 0))
            opticalTimer.end(flow,0);unpackTimer.begin(finish,0)
            outputs.forEachIndexed { i,im ->
                transition(finish,im,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                val copy=VkBufferImageCopy.calloc(1,s).imageSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1) }.imageExtent { it.set(flowWidth,flowHeight,1) }
                vkCmdCopyImageToBuffer(finish.vkCommandBuffer,im.vkImage,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,if(i==0)packed.buffer else costs.buffer,copy)
            }
            val barrier=VkMemoryBarrier2.calloc(1,s).`sType$Default`().srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)
            vkCmdPipelineBarrier2(finish.vkCommandBuffer,VkDependencyInfo.calloc(s).`sType$Default`().pMemoryBarriers(barrier))
            output.render(finish,0)
            unpackTimer.end(finish,0)
        }
        commands.forEach { it.endRecording() }
        MemoryStack.stackPush().use { s ->
            fun submit(queue: Queue,command: CommandBuffer,wait: Semaphore?,signal: Semaphore?,last: Boolean) {
                val info=VkSubmitInfo2.calloc(1,s).`sType$Default`().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,s).`sType$Default`().commandBuffer(command.vkCommandBuffer))
                if(wait!=null)info.pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,s).`sType$Default`().semaphore(wait.vkSemaphore).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT))
                if(signal!=null)info.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,s).`sType$Default`().semaphore(signal.vkSemaphore).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT))
                synchronized(Queue.globalQueueLock){vkCheck(vkQueueSubmit2(queue.vkQueue,info,if(last)fence.vkFence else VK_NULL_HANDLE),"optical queue submit")}
            }
            submit(renderer.graphQueue,prep,null,ready,false);submit(opticalQueue,flow,ready,done,false);submit(renderer.graphQueue,finish,done,null,true)
        }
        fence.fenceWait();fence.reset();firstPair=false
        milliseconds+= (System.nanoTime()-start)/1e6;while(milliseconds.size>1024)milliseconds.removeFirst()
    }
    private fun transition(command: CommandBuffer,image: Image,layout: Int)=MemoryStack.stackPush().use { s ->
        val old=image.getLastKnownLayout() ?: VK_IMAGE_LAYOUT_UNDEFINED
        image_layout_transition(command.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            if(old==VK_IMAGE_LAYOUT_UNDEFINED)0 else VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,old,layout,VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1))
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.cleanup+=Runnable {
            commands.forEach { it.cleanup() }
            if(nativeDisposer.disable()) {
                vkDestroyOpticalFlowSessionNV(device.vkDevice,session,null)
                pool.cleanup();ready.cleanup();done.cleanup()
            }
            views.forEach { it.cleanup() };(inputs+outputs).forEach { it.cleanup() }
            fence.cleanup();packed.cleanup();costs.cleanup()
            convert.cleanup();resolve.cleanup();sampler.cleanup()
            conversionTimer.close();opticalTimer.close();unpackTimer.close()
            listOf(conversion,output).forEach { r -> r.imageViewArray.forEach { it.imageView.cleanup() };r.colorImageRefs.forEach { it?.cleanup() };r.defaultSampler?.cleanup() }
        }
    }
    companion object {
        /** CPU diagnostic counterpart to the GPU unpack pass; signed 16-bit lanes with five fraction bits. */
        fun decodePacked(vector: Int): afield.stdlib.linalg.Vector2f = afield.stdlib.linalg.Vector2f(
            vector.toShort().toFloat()/32f,(vector shr 16).toShort().toFloat()/32f)
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val CONVERT="""#version 460
        layout(set=0,binding=0) uniform sampler2D firstImage;layout(set=0,binding=1) uniform sampler2D secondImage;
        layout(location=0) out vec4 a;layout(location=1) out vec4 b;
        vec4 encode(vec3 v){v=max(v,vec3(0));return vec4(pow(v/(1+v),vec3(1.0/2.2)),1);}
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);a=encode(texelFetch(firstImage,p,0).rgb);b=encode(texelFetch(secondImage,p,0).rgb);}
        """
        private const val UNPACK="""#version 460
        #extension GL_EXT_buffer_reference : require
        #extension GL_EXT_scalar_block_layout : require
        layout(buffer_reference,std430) readonly buffer Words {uint data[];};
        layout(push_constant,scalar) uniform P {Words flow;Words cost;uint width;} p;
        layout(location=0) out vec4 result;
        void main(){uvec2 xy=uvec2(gl_FragCoord.xy);uint i=xy.y*p.width+xy.x,w=p.flow.data[i];
            result=vec4(float(int(w<<16)>>16)/32.0,float(int(w)>>16)/32.0,float(p.cost.data[i]),1);}
        """
    }
}

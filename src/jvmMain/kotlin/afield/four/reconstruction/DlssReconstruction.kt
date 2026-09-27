package afield.four.reconstruction

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.lighting.LightingGpuTimer
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import kotlin.math.ceil

/** Input pixel coordinates: origin top left, X right, Y down, current-to-previous, without jitter.
 * Color is opaque linear HDR RGB, pre-exposure explicitly supplied; coverage/latent channels stay outside
 * reconstruction. Depth is standard Vulkan [0,1], background=1. All inputs are render-resolution,
 * sampled images, mip 0 / layer 0; producer and consumer must use the renderer's graphics queue.
 */
data class ReconstructionInputs(val color: HasImageView,val depth: HasImageView,val motion: HasImageView)
/** RR guides at input resolution. Diffuse/specular albedo are the actual BRDF response;
 * normals.xyz are world-space unit normals and .w is linear roughness. Matrices are unjittered
 * column-major right-multiply (equivalent to NGX's row-major left-multiply convention).
 * Optional specular hit distance is world-space distance from the primary surface; sky=65504. */
data class RayReconstructionGuides(val diffuseAlbedo: HasImageView,val specularAlbedo: HasImageView,
    val normalRoughness: HasImageView,val worldToView: afield.stdlib.linalg.Matrix4f,val viewToClip: afield.stdlib.linalg.Matrix4f,val specularHitDistance: HasImageView?=null)
data class ReconstructionFrame(val index: Long,val jitter: ReconstructionJitter,val milliseconds: Float=16.6667f,
    val preExposure: Float=1f,val reset: Boolean=false,val revision: Long=0) {
    init { require(milliseconds.isFinite() && milliseconds>0 && preExposure.isFinite() && preExposure>0) }
}
/** Projection displacement in input pixels, positive right/down. Sample the scene at pixel-jitter. */
data class ReconstructionJitter(val x: Float,val y: Float) {
    init { require(x.isFinite() && y.isFinite() && x in -0.5f..0.5f && y in -0.5f..0.5f) }
    companion object {
        fun halton(frame: Long,input: ReconstructionSize,output: ReconstructionSize): ReconstructionJitter {
            require(frame>=0)
            val phases=ceil(8.0*output.width/input.width*output.height/input.height).toInt().coerceAtLeast(8)
            fun radical(i: Int,base: Int): Float { var n=i;var f=1f;var value=0f
                while(n>0){f/=base;value+=f*(n%base);n/=base};return value }
            val i=(frame%phases).toInt()+1
            return ReconstructionJitter(radical(i,2)-0.5f,radical(i,3)-0.5f)
        }
        /** Field direct-lighting UV velocity has the opposite sign. */
        fun fieldMotionToPixels(currentMinusPrevious: Float,dimension: Int): Float = -currentMinusPrevious*dimension
    }
}

/** One mono view. Construct/edit/evaluate/close on the render thread, outside a render pass.
 * Query [recommend] before allocating the scene targets. Recreate on resolution/mode/format change;
 * the old instance retires behind Field's GPU completion queue. Failure leaves caller's source intact.
 * SR is independent of ray tracing. UI and unsupported animated layers belong after this pass.
 */
class DlssReconstruction(val renderer: Renderer,val inputSize: ReconstructionSize,val outputSize: ReconstructionSize,
    val mode: ReconstructionMode,val rayReconstruction: Boolean=false) : AutoCloseable {
    private val runtime=renderer.device.ngxRuntime.getOrThrow()
    private val native=runtime.native
    val recommendation=runtime.recommend(outputSize,mode,rayReconstruction).also { rec ->
        rec.validate(inputSize,outputSize,mode)
    }
    private val arena=Arena.ofShared()
    private val images=arena.allocate(256,8)
    private val matrices=arena.allocate(128,4)
    private val frameData=arena.allocate(16,4)
    private val handleSlot=arena.allocate(ADDRESS)
    private var handle=MemorySegment.NULL
    private val storage=StorageOutput(renderer,outputSize)
    val output: RenderToTexture get()=storage
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    /** SDK-reported device allocation, shared by all views; 0 means statistic unavailable. */
    val sdkAllocatedBytes get()=runtime.allocatedBytes
    val outputAllocatedBytes get()=storage.colorImageRefs.sumOf { it?.memorySize ?: 0 }
    private var closed=false
    private var lastPresentationRevision=-1L
    private var lastFrame=-1L
    private var lastRevision=Long.MIN_VALUE
    private var pendingReset=true
    private var lastInputs=longArrayOf()
    var resetCount=0;private set
    var resetReason="initial";private set
    fun reset(reason: String="camera cut or live edit") { pendingReset=true;resetReason=reason }
    fun evaluate(command: CommandBuffer,inputs: ReconstructionInputs,frame: ReconstructionFrame,guides: RayReconstructionGuides?=null) {
        check(!closed && !command.inRenderPassInstance)
        require(frame.index>=0 && frame.index!=lastFrame) { "A view must evaluate once per frame" }
        require(rayReconstruction==(guides!=null)) { "RR requires its BRDF guides and camera matrices on every frame" }
        images.fill(0)
        val views=listOf(inputs.color,inputs.depth,inputs.motion,storage)+
            (guides?.let { listOf(it.diffuseAlbedo,it.specularAlbedo,it.normalRoughness)+listOfNotNull(it.specularHitDistance) } ?: emptyList())
        views.forEachIndexed { i,source ->
            if(i!=3)(source as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            val v=source.imageView;val image=requireNotNull(v.underlyingImage) { "NGX inputs require a Field-owned image" }
            val size=if(i==3)outputSize else inputSize
            require(image.imageData.width==size.width && image.imageData.height==size.height)
            require(v.mipLevels==1 && v.imageViewData.layerCount==1 && v.imageViewData.baseArrayLayer==0)
            if(i!=3) {
                require(image.imageData.usage and VK_IMAGE_USAGE_SAMPLED_BIT!=0)
                require(image.getLastKnownLayout() in setOf(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)) {
                    "NGX input $i is not in sampled-read layout; finish its producer before evaluation"
                }
            }
            require(when(i) {
                0,3,4,5,6 -> image.format==VK_FORMAT_R16G16B16A16_SFLOAT || image.format==VK_FORMAT_R32G32B32A32_SFLOAT
                7 -> image.format==VK_FORMAT_R32_SFLOAT
                1 -> image.format==VK_FORMAT_R32_SFLOAT || image.format==VK_FORMAT_D32_SFLOAT
                else -> image.format==VK_FORMAT_R16G16_SFLOAT || image.format==VK_FORMAT_R32G32_SFLOAT
            }) { "Unsupported NGX input format ${image.format} at slot $i" }
            val o=i*32L;images.set(JAVA_LONG,o,image.vkImage);images.set(JAVA_LONG,o+8,v.vkImageView)
            images.set(JAVA_INT,o+16,image.format);images.set(JAVA_INT,o+20,size.width);images.set(JAVA_INT,o+24,size.height);images.set(JAVA_INT,o+28,v.aspectMask)
        }
        timer.begin(command,renderer.currentImage)
        if(handle==MemorySegment.NULL) {
            // SDK guide 3.2.2: create at the optimal size; evaluate at the legal render subrect size.
            native.check((if(rayReconstruction)native.rrCreate else native.create).invokeWithArguments(runtime.handle,command.vkCommandBuffer.address(),recommendation.optimal.width,recommendation.optimal.height,
                outputSize.width,outputSize.height,mode.sdkValue,handleSlot))
            handle=handleSlot.get(ADDRESS,0)
        }
        storage.transition(command,VK_IMAGE_LAYOUT_GENERAL)
        frameData.set(JAVA_FLOAT,0,frame.jitter.x);frameData.set(JAVA_FLOAT,4,frame.jitter.y)
        frameData.set(JAVA_FLOAT,8,frame.milliseconds);frameData.set(JAVA_FLOAT,12,frame.preExposure)
        val inputIds=views.filterIndexed { i,_ -> i!=3 }.map { it.imageView.image }.toLongArray()
        if(lastPresentationRevision!=renderer.presentationRevision)reset("presentation resumed")
        val reset=pendingReset || frame.reset || frame.index!=lastFrame+1 || lastRevision!=frame.revision || !inputIds.contentEquals(lastInputs)
        if(reset && !pendingReset)resetReason=when {
            frame.reset -> "explicit frame reset"
            frame.index!=lastFrame+1 -> "skipped frame"
            lastRevision!=frame.revision -> "scene revision"
            else -> "input resources replaced"
        }
        if(guides!=null) {
            val bytes=matrices.asByteBuffer().order(java.nio.ByteOrder.nativeOrder())
            guides.worldToView.get(0,bytes);guides.viewToClip.get(64,bytes)
            require((0 until 32).all { matrices.get(JAVA_FLOAT,it*4L).isFinite() }) { "RR camera matrices must be finite" }
            native.check(native.rrEvaluate.invokeWithArguments(handle,command.vkCommandBuffer.address(),images,frameData,matrices,if(reset)1 else 0))
        } else native.check(native.evaluate.invokeWithArguments(handle,command.vkCommandBuffer.address(),images,frameData,if(reset)1 else 0))
        // Modules bind pipeline, descriptors, push constants, and dynamic graphics state every draw.
        // No cached command state may be carried across the SDK call.
        storage.transition(command,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        timer.end(command,renderer.currentImage)
        if(reset)resetCount++
        lastPresentationRevision=renderer.presentationRevision
        pendingReset=false;lastFrame=frame.index;lastRevision=frame.revision;lastInputs=inputIds
    }
    override fun close() {
        if(closed)return;closed=true
        val old=handle
        renderer.cleanup+=Runnable {
            if(old!=MemorySegment.NULL)native.destroy.invokeWithArguments(old)
            timer.close();storage.imageViewArray.forEach { it.imageView.cleanup() };storage.colorImageRefs.forEach { it?.cleanup() }
            storage.defaultSampler?.cleanup();arena.close()
        }
    }
    companion object {
        fun recommend(renderer: Renderer,output: ReconstructionSize,mode: ReconstructionMode,rayReconstruction: Boolean=false): ReconstructionRecommendation =
            renderer.device.ngxRuntime.getOrThrow().recommend(output,mode,rayReconstruction)
    }
}

private class StorageOutput(renderer: Renderer,size: ReconstructionSize) : RenderToTexture(renderer,{
    width=size.width;height=size.height;format=VK_FORMAT_R16G16B16A16_SFLOAT;useDepth=false;colorStorage=true;colorTransferDst=true;debugName="NGX output"
}) {
    fun transition(command: CommandBuffer,layout: Int) = MemoryStack.stackPush().use { s ->
        val image=colorImageRefs[0]!!;val old=image.getLastKnownLayout() ?: VK_IMAGE_LAYOUT_UNDEFINED
        image_layout_transition(command.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            if(old==VK_IMAGE_LAYOUT_UNDEFINED)0 else VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,old,layout,
            VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1))
        colorLayout[0]=layout
    }
}

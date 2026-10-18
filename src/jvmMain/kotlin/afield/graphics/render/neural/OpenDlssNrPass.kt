/**
 * The Vulkan pass that runs the native neural-rendering (NR) network over a sampled image.
 *
 * [OpenDlssNrPass.evaluate] takes [OpenDlssNrInputs] (colour with its [OpenDlssNrColorEncoding], and
 * optional motion and depth guides for temporal mode), an [OpenDlssNrFrame] and the current
 * [OpenDlssNrControls] (seed, style, tone, structure, intensity), and writes the result to its output
 * texture. [OpenDlssNrPass.postEffect] wraps it as a post effect, and [OpenDlssNrPass.reset] drops
 * temporal history after a cut or edit.
 */

package afield.graphics.render.neural

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.vkLayout
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.Queue
import afield.graphics.scene.fabricated.FabricatePostEffect
import afield.graphics.render.lighting.LightingGpuTimer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.texture.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import kotlin.math.abs

/** What a Vulkan sampler returns. SRGB-format views already decode, so use LINEAR_LDR for those. */
enum class OpenDlssNrColorEncoding { SRGB_CODE, LINEAR_LDR, LINEAR_HDR }
/** Style is the network condition, independently of grading after the pass. Tone/structure are
 * deliberately unrestricted within finite binary16 range: -10..30 is useful for image exploration.
 * Skin is active only with autoMask; negative skin inherits structure. Fixed seed is the default. */
data class OpenDlssNrControls(val seed: Int=1,val style: Int=1,val tone: Float=1f,val structure: Float=1f,
    val skin: Float=-1f,val autoMask: Boolean=false,val intensity: Float=1f,val temporal: Boolean=false) {
    init {
        require(style in 0..2)
        require(listOf(tone,structure,skin).all { it.isFinite() && abs(it)<=65504f })
        require(intensity.isFinite() && intensity in 0f..1f)
    }
}
/** Top-left origin, same-sized sampled images. Motion is UV displacement, by default Field's
 * current-minus-previous convention; depth is standard Vulkan depth with background 1.
 * Temporal mode needs both guides. An arbitrary image/video must supply real motion to use history. */
data class OpenDlssNrInputs(val color: HasImageView,val encoding: OpenDlssNrColorEncoding,
    val motion: HasImageView?=null,val depth: HasImageView?=null,val paperWhite: Float=1f,
    val motionCurrentMinusPrevious: Boolean=true,val preserveAlpha: Boolean=true,val motionInPixels: Boolean=false) {
    init {require(paperWhite.isFinite() && paperWhite>0)}
}
data class OpenDlssNrFrame(val index: Long,val revision: Long=0,val reset: Boolean=false) {
    init {require(index>=0)}
}

/** Optional native Vulkan pass, recorded outside render passes on Field's graphics queue.
 * Construct/evaluate/edit/close on the renderer thread. Recreate at a new resolution; close retires
 * behind Field's completion fences. The output is linear RGBA16F, ready for normal Field grading.
 * No CPU frame transfer or private per-frame submit occurs. Failure is explicit; callers can use
 * availability() before creating the pass and present the source when unavailable.
 */
class OpenDlssNrPass(val renderer: Renderer,val width: Int,val height: Int) : AutoCloseable {
    private val native=OpenDlssNrNative.loaded.getOrThrow().also {
        require(width in 33..8192 && height in 33..8192)
        val available=OpenDlssNrSupport.availability(renderer)
        check(available.ready) {available.reason}
    }
    private val arena=Arena.ofShared()
    private val images=arena.allocate(128,8)
    private val controlsData=arena.allocate(64,8)
    private val slot=arena.allocate(ADDRESS)
    private var handle=MemorySegment.NULL
    private val storage=NrOutput(renderer,width,height)
    val output: RenderToTexture get()=storage
    private val timer=LightingGpuTimer(renderer)
    val gpuMilliseconds get()=timer.samples.toList()
    /** Native VkDeviceMemory allocations, including the shared model/cache and upload staging;
     * excludes driver-internal pipeline/PTX allocations and other passes' history images. */
    val nativeAllocatedBytes: Long get()=if(handle==MemorySegment.NULL)0L else native.allocatedBytes.invokeWithArguments(handle) as Long
    val outputAllocatedBytes: Long get()=storage.colorImageRefs.sumOf {it?.memorySize ?: 0L}
    var controls=OpenDlssNrControls()
    private var closed=false
    private val closeWithRenderer=Runnable { close() }
    /** Whether this pass has retired, explicitly or with its renderer. */
    val isClosed: Boolean get()=closed
    private var previousFrame=-1L
    private var previousRevision=Long.MIN_VALUE
    private var previousPresentation=-1L
    private var previousControls: OpenDlssNrControls?=null
    private var previousInputs: OpenDlssNrInputs?=null
    private var inputIds=longArrayOf()
    private var pendingReset=true
    var resetCount=0;private set
    var resetReason="initial";private set
    init { renderer.beforeDeviceCleanup+=closeWithRenderer }
    fun reset(reason: String="camera cut or live edit") {pendingReset=true;resetReason=reason}
    /** Configure an editable Fabricate post effect; its native target calls the same evaluate path. */
    fun postEffect(name: String,inputs: () -> OpenDlssNrInputs,frame: () -> OpenDlssNrFrame): FabricatePostEffect {
        storage.evaluate={ command -> evaluate(command,inputs(),frame()) }
        return FabricatePostEffect(name,storage,null,FabricatePostEffect.Kind.NATIVE)
    }
    fun evaluate(command: CommandBuffer,inputs: OpenDlssNrInputs,frame: OpenDlssNrFrame) {
        check(!closed && !command.inRenderPassInstance)
        require(frame.index!=previousFrame) {"NR must evaluate once per view/frame"}
        require(!controls.temporal || (inputs.motion!=null && inputs.depth!=null)) {"Temporal NR needs motion and depth"}
        val sources=listOf(inputs.color,storage,inputs.motion,inputs.depth)
        images.fill(0)
        val ids=sources.mapIndexed { i,source ->
            if(source==null)return@mapIndexed 0L
            if(i!=1)(source as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            val view=source.imageView;val image=requireNotNull(view.underlyingImage) {"NR needs Field-owned images"}
            require(image.imageData.width==width && image.imageData.height==height)
            require(view.mipLevels==1 && view.imageViewData.layerCount==1 && view.imageViewData.baseArrayLayer==0)
            if(i!=1) {
                require(image.imageData.usage and VK_IMAGE_USAGE_SAMPLED_BIT!=0)
                require(image.getLastKnownLayout() in setOf(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)) {"Finish input producers before NR"}
            }
            if(i==0)require(inputs.encoding!=OpenDlssNrColorEncoding.SRGB_CODE || image.format !in setOf(VK_FORMAT_R8G8B8A8_SRGB,VK_FORMAT_B8G8R8A8_SRGB)) {"SRGB views decode automatically; declare LINEAR_LDR"}
            if(i==2)require(image.format in setOf(VK_FORMAT_R16G16_SFLOAT,VK_FORMAT_R32G32_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT))
            if(i==3)require(image.format in setOf(VK_FORMAT_D32_SFLOAT,VK_FORMAT_R32_SFLOAT))
            val o=i*32L;images.set(JAVA_LONG,o,image.vkImage);images.set(JAVA_LONG,o+8,view.vkImageView)
            images.set(JAVA_INT,o+16,image.format);images.set(JAVA_INT,o+20,width);images.set(JAVA_INT,o+24,height);images.set(JAVA_INT,o+28,vkLayout(image.getLastKnownLayout() ?: VK_IMAGE_LAYOUT_UNDEFINED))
            view.vkImageView
        }.toLongArray()
        if(handle!=MemorySegment.NULL && !ids.contentEquals(inputIds)) {
            val old=handle
            renderer.cleanup+=Runnable {native.check(native.destroy.invokeWithArguments(old))}
            handle=MemorySegment.NULL;reset("input resources replaced")
        }
        if(handle==MemorySegment.NULL) {
            // Bootstrap uploads share the graphics queue's external synchronization lock.
            synchronized(Queue.globalQueueLock) {
                val model=arena.allocateFrom(OpenDlssNrSupport.model().toString())
                val shaders=arena.allocateFrom(OpenDlssNrSupport.shaders().toString())
                native.check(native.create.invokeWithArguments(renderer.instance.vkInstance.address(),renderer.physicalDevice.vkPhysicalDevice.address(),
                    renderer.device.vkDevice.address(),renderer.graphQueue.queueFamilyIndex,model,shaders,images,slot))
            }
            handle=slot.get(ADDRESS,0)
        }
        val historyValid=!pendingReset && !frame.reset && frame.index==previousFrame+1 && frame.revision==previousRevision &&
            previousPresentation==renderer.presentationRevision && previousControls==controls && previousInputs==inputs
        if(!historyValid && !pendingReset)resetReason=when {
            frame.reset -> "explicit frame reset"
            frame.index!=previousFrame+1 -> "skipped frame"
            frame.revision!=previousRevision -> "scene revision"
            previousPresentation!=renderer.presentationRevision -> "presentation resumed"
            else -> "parameters changed"
        }
        controlsData.fill(0)
        controlsData.set(JAVA_INT,0,controls.seed);controlsData.set(JAVA_INT,4,controls.style)
        controlsData.set(JAVA_FLOAT,8,controls.tone);controlsData.set(JAVA_FLOAT,12,controls.structure)
        controlsData.set(JAVA_FLOAT,16,controls.skin);controlsData.set(JAVA_INT,20,if(controls.autoMask)1 else 0)
        controlsData.set(JAVA_FLOAT,24,controls.intensity);controlsData.set(JAVA_INT,28,inputs.encoding.ordinal)
        controlsData.set(JAVA_FLOAT,32,inputs.paperWhite);controlsData.set(JAVA_INT,36,if(controls.temporal)1 else 0)
        controlsData.set(JAVA_INT,40,if(historyValid)1 else 0);controlsData.set(JAVA_FLOAT,44,if(inputs.motionCurrentMinusPrevious)-1f else 1f)
        controlsData.set(JAVA_INT,52,if(inputs.preserveAlpha)1 else 0)
        controlsData.set(JAVA_INT,56,if(inputs.motionInPixels)1 else 0)
        timer.begin(command,renderer.currentImage)
        storage.transition(command,VK_IMAGE_LAYOUT_GENERAL)
        native.check(native.record.invokeWithArguments(handle,command.vkCommandBuffer.address(),controlsData))
        storage.transition(command,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        timer.end(command,renderer.currentImage)
        storage.recordCaptures(command)
        if(!historyValid)resetCount++
        pendingReset=false;previousFrame=frame.index;previousRevision=frame.revision;previousPresentation=renderer.presentationRevision
        previousControls=controls;previousInputs=inputs;inputIds=ids
    }
    /** Diagnostics only: borrowed native buffer, valid until this pass retires. Padded f32 RGBA head. */
    internal fun headBuffer(): Long {check(handle!=MemorySegment.NULL);return native.headBuffer.invokeWithArguments(handle) as Long}
    internal fun paddedWidth(): Int {check(handle!=MemorySegment.NULL);return native.paddedWidth.invokeWithArguments(handle) as Int}
    override fun close() {
        if(closed)return;closed=true
        renderer.beforeDeviceCleanup.remove(closeWithRenderer)
        storage.evaluate=null
        val old=handle
        renderer.cleanup+=Runnable {
            if(old!=MemorySegment.NULL)native.check(native.destroy.invokeWithArguments(old))
            timer.close();storage.imageViewArray.forEach {it.imageView.cleanup()};storage.colorImageRefs.forEach {it?.cleanup()}
            storage.defaultSampler?.cleanup();arena.close()
        }
    }
}

private class NrOutput(renderer: Renderer,width: Int,height: Int) : RenderToTexture(renderer,{
    this.width=width;this.height=height;format=VK_FORMAT_R16G16B16A16_SFLOAT;useDepth=false;colorStorage=true;debugName="OpenDLSS-NR output"
}) {
    var evaluate: ((CommandBuffer)->Unit)?=null
    fun recordCaptures(command: CommandBuffer)=recordPendingFileSaves(command)
    override fun render(command: CommandBuffer,index: Int) {checkNotNull(evaluate) {"Configure the NR post effect before attaching it"}(command)}
    fun transition(command: CommandBuffer,layout: Int)=MemoryStack.stackPush().use { stack ->
        val image=colorImageRefs[0]!!;val old=image.getLastKnownLayout() ?: VK_IMAGE_LAYOUT_UNDEFINED
        image_layout_transition(command.vkCommandBuffer,image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            if(old==VK_IMAGE_LAYOUT_UNDEFINED)0 else VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,old,layout,
            VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1))
        colorLayout[0]=layout
    }
}

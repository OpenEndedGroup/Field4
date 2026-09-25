package afield.four.lighting

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.*
import afield.four.device.*
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.stdlib.fromViews
import afield.stdlib.linalg.Rectanglef
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*

/** One-sided rectangle: center plus full edge vectors. cross(edgeU,edgeV) faces the emitting side.
 * Radiance is unclamped, linear RGB. Values are snapshotted by [DirectLighting.render]. */
data class AreaEmitter(val center: Vector3f, val edgeU: Vector3f, val edgeV: Vector3f, val radiance: Vector3f) {
    fun validate() {
        require(listOf(center,edgeU,edgeV,radiance).all { v -> v.x.isFinite() && v.y.isFinite() && v.z.isFinite() })
        require(radiance.x >= 0 && radiance.y >= 0 && radiance.z >= 0)
        val area = Vector3f(edgeU).cross(edgeV).length()
        require(area.isFinite() && area > 1e-8f) { "Area emitter edges must span a nonzero finite area" }
    }
}

/** Raster surface contract. XYZ world position + Vulkan depth [0,1] (clear=1, not reversed);
 * XYZ world shading normal; linear RGB albedo. Motion is currentUV-previousUV, Y down, unjittered.
 * Point sampling only. Material identity, emission, eligibility and temporal validity are NOT alpha.
 * The optional RTXDI bridge snapshots these inputs; material IDs are not encoded in alpha. */
data class DirectLightSurfaces(val position: HasImageView, val normal: HasImageView,
    val albedo: HasImageView, val source: HasImageView, val motion: HasImageView)

/**
 * Experimental, mono, raster-primary direct light, with optional pinned RTXDI resampling.
 * Insert after the producer of [surfaces] on the graphics queue.
 * All mutations, reload and close occur on Field's render thread between frames.
 */
class DirectLighting(val renderer: Renderer, val surfaces: DirectLightSurfaces, val visibility: OpaqueRayScene,
    val width: Int, val height: Int, val name: String = "direct-light") : Target, AutoCloseable {
    enum class Mode { SOURCE, UNSHADOWED, RAY_QUERY, RTXDI_REFERENCE, RTXDI }
    enum class View { RADIANCE, NORMALS, DEPTH, VISIBILITY, MOTION, LIGHT_ID, RESERVOIR_M, RESERVOIR_WEIGHT, HISTORY_REJECTION, RAW_RADIANCE }
    var mode = if (visibility.available) Mode.RAY_QUERY else Mode.SOURCE
        set(value) { require(value != Mode.RAY_QUERY || visibility.available) { "Ray queries are not enabled; use SOURCE or UNSHADOWED" };
            require(value.ordinal<3 || rtxdi!=null) { "Call enableRtxdi first" }
            if(field!=value)rtxdi?.invalidate("mode switch");field=value }
    var view = View.RADIANCE
    var samples = 16
        set(value) { require(value in 1..256); field=value }
    var seed = 1
    var epsilon = 0.001f
        set(value) { require(value.isFinite() && value > 0f); field=value }
    var effectWeight = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); field=value }
    var emitter = AreaEmitter(Vector3f(0f,4f,0f),Vector3f(1f,0f,0f),Vector3f(0f,0f,1f),Vector3f(20f))
    val output: RenderToTexture
    private var rtxdi: RtxdiEngine? = null
    val rtxdiTimings get() = rtxdi?.timings ?: emptyMap()
    val rtxdiHistoryValid get() = rtxdi?.historyValid ?: false
    val rtxdiResetReason get() = rtxdi?.resetReason
    val rtxdiResetCount get() = rtxdi?.resetCount ?: 0
    private val pixels: VulkanBuffer
    private val sampler: TextureSampler
    private var compute: ComputeModule
    private var invocation: ComputeMesh
    private val resolve: Module
    private val resolveMesh: Mesh
    private var closed = false
    private var frames = 0
    private val attached = mutableSetOf<afield.four.targets.RenderTarget>()
    private val timers: LightingGpuTimer
    val gpuMilliseconds get() = timers.samples.toList()
    val workingBytes get() = width.toLong()*height*32 + (rtxdi?.workingBytes ?: 0L) // raw buffer + FP32 output image (excludes driver padding)

    init {
        require(width in 1..8192 && height in 1..8192 && width.toLong()*height <= 16_777_216)
        require(visibility.renderer === renderer && !visibility.closed)
        if (visibility.available && visibility.vkAccelerationStructure == 0L) visibility.commit(emptyList())
        pixels = VulkanBuffer(renderer,width.toLong()*height*16,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,needsDeviceAddress=true)
        sampler = TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
        compute = compile(shader(visibility.available))
        invocation = mesh(compute)
        timers = LightingGpuTimer(renderer)
        output = RenderToTexture(renderer) { this.width=this@DirectLighting.width; this.height=this@DirectLighting.height
            format=VK_FORMAT_R32G32B32A32_SFLOAT; useDepth=false; debugName="$name-output" }
        resolve = Module(parameters(renderer) {
            this.name="${this@DirectLighting.name}-resolve"; output.apply()
            vertex=VERTEX; fragment=RESOLVE; depthTest=false; depthWrite=false; blend=false
        })
        check(resolve.valid) { "Direct-light resolve shader failed" }
        val pointer=resolve.pointer()
        val screen=Mesh(renderer,pointer).also { it.resize(3,0,pointer) }
        resolveMesh=screen
        screen.push.setMembers(resolve.fi.pushConstants.single().members)
        screen.push["pixels"]=pixels; screen.push["width"]=width
        resolve.meshes["resolve"]=screen
        output.modules["resolve"]=pointer
    }

    private fun compile(source: String): ComputeModule {
        val errors=mutableListOf<String>()
        val textures=mapOf("positions" to surfaces.position,"normals" to surfaces.normal,"albedo" to surfaces.albedo,
            "source" to surfaces.source,"motion" to surfaces.motion)
        val module=ComputeModule(Parameters(renderer,name=name,compute=source,
            textureResolver={ tex -> textures[tex.name]?.let { fromViews(listOf(it),tex.name,sampler) } },
            accelerationStructureResolver={ visibility },errorHandler={ message,_ -> errors+=message }))
        if (!module.valid) { module.cleanup(); error(errors.joinToString("\n")) }
        if (module.ci.fixedWorkgroupSize != listOf(8,8,1)) {
            module.cleanup()
            error("DirectLighting requires a fixed 8 x 8 x 1 workgroup; recreate a different pass for another dispatch contract")
        }
        return module
    }
    private fun mesh(module: ComputeModule): ComputeMesh {
        val pointer=module.pointer()
        return ComputeMesh(renderer,pointer).also {
            it.x=(width+7)/8; it.y=(height+7)/8
            it.push.setMembers(module.ci.pushConstants.single().members)
            pointer += "lighting" to it
        }
    }

    /** Compile first, reject changed layouts, then swap. Any failure preserves the working pipeline. */
    fun reload(source: String): Result<Unit> = runCatching {
        check(!closed)
        val candidate=compile(source)
        val abi=computeShaderAbiCheck(compute.ci,candidate.ci)
        if (!abi.compatible) { candidate.cleanup(); error(abi.message) }
        val next=mesh(candidate)
        val old=compute
        compute=candidate; invocation=next
        renderer.cleanup += Runnable { old.cleanup() }
    }

    /** Optional SDK activation is transactional; a failed compile preserves the current pass. */
    fun enableRtxdi(program: RtxdiProgram, settings: RtxdiSettings): Result<Unit> = runCatching {
        check(!closed)
        val next=RtxdiEngine(this,program,settings,pixels);val old=rtxdi
        rtxdi=next
        if(old!=null)renderer.cleanup+=Runnable { old.close() }
    }
    fun clearRtxdiTimings() { rtxdi?.clearTimings() }
    fun reloadRtxdi(program: RtxdiProgram): Result<Unit> = rtxdi?.reload(program)
        ?: Result.failure(IllegalStateException("RTXDI is not enabled"))

    override fun render(command: CommandBuffer,index: Int) {
        if (closed) return
        check(!visibility.closed)
        if(mode==Mode.RTXDI || mode==Mode.RTXDI_REFERENCE) {
            rtxdi!!.render(command,index,mode==Mode.RTXDI_REFERENCE)
            resolveMesh.push["pixels"]=rtxdi!!.displayPixels
            barrier(command,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT)
            output.render(command,index);frames++;return
        }
        resolveMesh.push["pixels"]=pixels
        emitter.validate()
        invocation.push.apply {
            this["pixels"]=pixels; this["width"]=width; this["height"]=height
            this["center"]=Vector4f(emitter.center,0f); this["edgeU"]=Vector4f(emitter.edgeU,0f)
            this["edgeV"]=Vector4f(emitter.edgeV,0f); this["radiance"]=Vector4f(emitter.radiance,0f)
            this["mode"]=mode.ordinal; this["view"]=view.ordinal; this["samples"]=samples; this["seed"]=seed
            this["epsilon"]=epsilon; this["weight"]=effectWeight
        }
        barrier(command,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_MEMORY_READ_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
        timers.begin(command,index)
        compute.render(command,Rectanglef())
        timers.end(command,index)
        barrier(command,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT)
        output.render(command,index)
        frames++
    }

    fun attach(target: afield.four.targets.RenderTarget = renderer.renderList): DirectLighting {
        check(!closed)
        target.preTargets[name]=this; attached+=target
        return this
    }

    /** Blocking raw FP32 readback: RGB is linear lighting (or diagnostic), W is visibility (-1 for background), not coverage. SOURCE preserves input alpha. */
    fun readLinear(): FloatArray = readPixels(pixels)

    /** The linear RGB presented by [output], including optional reconstruction. Raw readback is unchanged. */
    fun readPresentedLinear(): FloatArray = readPixels(
        if(mode==Mode.RTXDI || mode==Mode.RTXDI_REFERENCE)rtxdi!!.displayPixels else pixels)

    private fun readPixels(buffer: VulkanBuffer): FloatArray {
        check(!closed && frames>0)
        val staging=VulkanBuffer(renderer,buffer.requestedSize,VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        val cmd=CommandBuffer(renderer.commandPool,true,true); val fence=Fence(renderer.device,false)
        try {
            cmd.beginRecording()
            barrier(cmd,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT)
            MemoryStack.stackPush().use { stack -> vkCmdCopyBuffer(cmd.vkCommandBuffer,buffer.buffer,staging.buffer,VkBufferCopy.calloc(1,stack).size(buffer.requestedSize)) }
            barrier(cmd,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_2_HOST_BIT,VK_ACCESS_2_HOST_READ_BIT)
            cmd.endRecording()
            MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(cmd.vkCommandBuffer),null,null,null,fence) }
            fence.fenceWait()
            return FloatArray(width*height*4).also { data -> staging.mapAndRead { get(data) } }
        } finally { fence.cleanup();cmd.cleanup();staging.cleanup() }
    }

    override fun close() {
        if(closed)return
        closed=true
        attached.forEach { it.preTargets.remove(name,this) }; attached.clear()
        output.modules.clear()
        val old=compute
        renderer.cleanup += Runnable {
            rtxdi?.close(); old.cleanup(); resolve.cleanup(); pixels.cleanup(); sampler.cleanup(); timers.close()
            output.defaultSampler?.cleanup()
            output.imageViewArray.forEach { it.imageView.cleanup() } // single mip, shared attachment/sample view
            output.colorImageRefs.forEach { it?.cleanup() }
        }
    }
    companion object {
        fun shader(hardware: Boolean): String {
            val source=DirectLighting::class.java.getResource("/afield/four/lighting/direct.comp")!!.readText()
            return if(hardware)source.replace("#version 460","#version 460\n#define HARDWARE_QUERY") else source
        }
        internal fun barrier(cmd: CommandBuffer,srcStage: Long,srcAccess: Long,dstStage: Long,dstAccess: Long) {
            MemoryStack.stackPush().use { stack ->
                val b=VkMemoryBarrier2.calloc(1,stack).`sType$Default`().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                vkCmdPipelineBarrier2(cmd.vkCommandBuffer,VkDependencyInfo.calloc(stack).`sType$Default`().pMemoryBarriers(b))
            }
        }
        internal const val VERTEX="""#version 460
        void main(){ vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2); gl_Position=vec4(p*2.0-1.0,0,1); }
        """
        internal const val RESOLVE="""#version 460
        #extension GL_EXT_buffer_reference : require
        layout(buffer_reference,std430) readonly buffer Pixels { vec4 values[]; };
        layout(push_constant) uniform Display { Pixels pixels; int width; } p;
        layout(location=0) out vec4 color;
        void main(){ color=vec4(p.pixels.values[int(gl_FragCoord.y)*p.width+int(gl_FragCoord.x)].rgb,1); }
        """
    }
}

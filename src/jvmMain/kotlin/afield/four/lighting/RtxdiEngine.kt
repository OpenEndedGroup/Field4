package afield.four.lighting

import afield.four.buffers.*
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.vulkan.VK13.*
import kotlin.math.*

/** One graphics queue, two surface pages, three SDK reservoir slices. No native CPU SDK wrapper. */
@Suppress("UNNECESSARY_LATEINIT") // cleanup also handles partially constructed pipelines
internal class RtxdiEngine(private val owner: DirectLighting, program: RtxdiProgram, val settings: RtxdiSettings,
    private val pixels: VulkanBuffer) : AutoCloseable {
    private val renderer=owner.renderer
    private val count=owner.width*owner.height
    init {
        require(owner.visibility.available) { "RTXDI requires enabled ray queries for this bridge" }
        val limit=renderer.device.physicalDevice.vkPhysicalDeviceProperties.limits().maxStorageBufferRange().toUInt().toLong()
        require(reservoirBytes(owner.width,owner.height)<=limit) { "RTXDI reservoir descriptor exceeds maxStorageBufferRange=$limit; reduce the render resolution" }
    }
    private val owned=mutableListOf<VulkanBuffer>()
    private val bindings=mutableMapOf<String,BufferParameters>()
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
    private val surfaces=allocate(count*160L)
    // Host snapshots are indexed by acquired swapchain image, whose fence has already completed.
    private val matrices=Array(renderer.numImages) { allocate(192,true) }
    private val lights=Array(renderer.numImages) { allocate(4096*80L,true) }
    private val previousLights=Array(renderer.numImages) { allocate(4096*80L,true) }
    private val timers=List(7) { LightingGpuTimer(renderer) }
    private lateinit var reconstruction: ComputeModule
    private lateinit var reconstructionTask: ComputeMesh
    private var filterBuffers: List<VulkanBuffer> = emptyList()
    var displayPixels: VulkanBuffer = pixels; private set
    private lateinit var snapshot: ComputeModule
    private lateinit var shader: ComputeModule
    private lateinit var snapshotTask: ComputeMesh
    private lateinit var task: ComputeMesh
    private var frame=0
    private var hasHistory=false
    private var epoch=-1L
    private var geometry=-1
    private var signature=""
    private var previousView=Matrix4f()
    private var previousProjection=Matrix4f()
    private var previous=emptyList<IdentifiedAreaLight>()
    var historyValid=false; private set
    var resetCount=0; private set
    var resetReason="initial"; private set
    val workingBytes get()=owned.sumOf { it.requestedSize }
    val timings get()=listOf("surfaceSnapshot","initialCandidates","temporalReuse","spatialReuse","visibilityShading","reference","reconstruction")
        .zip(timers.map { it.samples.toList() }).toMap()

    init {
        try {
            val textures=mapOf("positions" to owner.surfaces.position,"normals" to owner.surfaces.normal,
                "albedo" to owner.surfaces.albedo,"source" to owner.surfaces.source)
            snapshot=ComputeModule(Parameters(renderer,name="rtxdi-surface-snapshot",
                compute=javaClass.getResource("/afield/four/lighting/rtxdi/snapshot.comp")!!.readText(),
                textureResolver={ t -> textures[t.name]?.let { fromViews(listOf(it),t.name,sampler) } }))
            check(snapshot.valid) { "RTXDI surface snapshot compilation failed" }
            snapshotTask=mesh(snapshot)
            shader=compile(program)
            task=mesh(shader)
            reconstruction=ComputeModule(Parameters(renderer,name="direct-light-reconstruction",
                compute=javaClass.getResource("/afield/four/lighting/reconstruct.comp")!!.readText()))
            check(reconstruction.valid) { "Direct-light reconstruction compilation failed" }
            reconstructionTask=mesh(reconstruction)
            // A fixed disk sequence; the SDK rotates/indexes this per pixel. Unlike its optional
            // SNORM texture path, StructuredBuffer<float2> needs no quantization or C++ helper.
            bindings.getValue("offsets").result!!.get().mapAndWrite {
                repeat(256) { i -> val r=sqrt((i+0.5)/256.0);val a=i*2.399963229728653
                    put((r*cos(a)).toFloat());put((r*sin(a)).toFloat()) }
            }
        } catch(t: Throwable) { close();throw t }
    }
    private fun allocate(size: Long,host: Boolean=false): VulkanBuffer = VulkanBuffer(renderer,size,
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        if(host) VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT else VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        needsDeviceAddress=true).also { owned+=it }

    private fun compile(program: RtxdiProgram): ComputeModule {
        val errors=mutableListOf<String>()
        val module=ComputeModule(Parameters(renderer,name="Field-RTXDI",computeIntrospection=program.reflection,
            ssboResolver={ requested -> bindings.getOrPut(requested.name) {
                requested.customize.add {
                    size=when(name) { "reservoirs" -> reservoirBytes(owner.width,owner.height); "offsets" -> 256*8L;else -> error("Unknown RTXDI binding $name") }
                    if(name=="reservoirs") { notHosted();memory=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT }
                }
                requested
            } },accelerationStructureResolver={ owner.visibility },errorHandler={ m,_ -> errors+=m }))
        if(!module.valid) { module.cleanup();error(errors.joinToString("\n")) }
        bindings.values.flatMap { it.result!!.buffers }.forEach { if(it !in owned)owned+=it }
        return module
    }
    private fun mesh(module: ComputeModule): ComputeMesh {
        val pointer=module.pointer()
        return ComputeMesh(renderer,pointer).also { it.x=(owner.width+7)/8;it.y=(owner.height+7)/8
            it.push.setMembers(module.ci.pushConstants.single().members);pointer += "dispatch" to it }
    }
    fun reload(program: RtxdiProgram): Result<Unit> = runCatching {
        val abi=computeShaderAbiCheck(shader.ci,program.reflection)
        require(abi.compatible) { abi.message }
        val next=compile(program);val nextTask=mesh(next);val old=shader
        shader=next;task=nextTask;invalidate("shader reload")
        renderer.cleanup+=Runnable { old.cleanup() }
    }
    fun clearTimings() { timers.forEach { it.samples.clear() } }
    fun invalidate(reason: String) { hasHistory=false;resetReason=reason }

    fun render(cmd: CommandBuffer,index: Int,reference: Boolean) {
        val view=Matrix4f(settings.view());val projection=Matrix4f(settings.projection())
        val nextSignature="${settings.temporal}/${settings.spatialSamples}/${settings.spatialRadius}/${settings.maxHistory}/${settings.shadows}/${owner.samples}/${owner.seed}"
        val reason=when {
            !hasHistory -> resetReason
            epoch!=settings.epoch -> settings.resetReason
            geometry!=owner.visibility.tlasBuilds -> "geometry or instance revision"
            matrixDifference(projection,previousProjection)>1e-6f -> "projection change"
            matrixDifference(view,previousView)>0.75f -> "camera cut"
            signature!=nextSignature -> "sampling settings changed"
            else -> null
        }
        historyValid=reason==null && !reference
        if(reason!=null) { resetReason=reason;resetCount++ }
        val current=settings.lights
        writeLights(lights[index],current,previous)
        writeLights(previousLights[index],previous,current)
        matrices[index].mapAndWrite_bytes {
            view.get(0,this);previousView.get(64,this);Matrix4f(previousProjection).mul(previousView).get(128,this)
        }
        val page=frame and 1
        snapshotTask.push.apply {
            this["surfaces"]=surfaces;this["matrices"]=matrices[index]
            this["width"]=owner.width;this["height"]=owner.height;this["page"]=page
        }
        DirectLighting.barrier(cmd,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT or VK_ACCESS_2_MEMORY_READ_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
        timers[0].begin(cmd,index);snapshot.render(cmd,Rectanglef());timers[0].end(cmd,index)
        task.push.apply {
            this["surfaces"]=surfaces.getGPUAddress(cmd);this["lights"]=lights[index].getGPUAddress(cmd)
            this["previousLights"]=previousLights[index].getGPUAddress(cmd);this["pixels"]=pixels.getGPUAddress(cmd)
            this["width"]=owner.width;this["height"]=owner.height;this["frameIndex"]=if(settings.animateSeed)frame else 0
            this["page"]=page;this["candidates"]=owner.samples;this["lightCount"]=current.size;this["previousLightCount"]=previous.size
            this["temporal"]=if(settings.temporal)1 else 0;this["spatial"]=settings.spatialSamples
            this["historyValid"]=if(historyValid)1 else 0;this["maxHistory"]=settings.maxHistory
            this["radius"]=settings.spatialRadius;this["normalThreshold"]=settings.normalThreshold;this["depthThreshold"]=settings.depthThreshold
            this["epsilon"]=owner.epsilon;this["diagnostic"]=owner.view.ordinal;this["seed"]=owner.seed;this["weight"]=owner.effectWeight
            this["shadows"]=if(settings.shadows)1 else 0
        }
        for(stage in if(reference)4..4 else 0..3) {
            DirectLighting.barrier(cmd,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_WRITE_BIT or VK_ACCESS_2_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
            task.push["stage"]=stage
            timers[stage+1].begin(cmd,index);shader.render(cmd,Rectanglef());timers[stage+1].end(cmd,index)
        }
        displayPixels=pixels
        // Reconstruction never feeds the estimator or reservoir history. Diagnostics bypass it.
        if(!reference && settings.denoisePasses>0 && owner.view==DirectLighting.View.RADIANCE && owner.effectWeight>0f) {
            if(filterBuffers.isEmpty())filterBuffers=List(2) { allocate(count*16L) }
            timers[6].begin(cmd,index)
            repeat(settings.denoisePasses) { pass ->
                val destination=filterBuffers[pass and 1]
                DirectLighting.barrier(cmd,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_WRITE_BIT or VK_ACCESS_2_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                reconstructionTask.push.apply {
                    this["surfaces"]=surfaces;this["inputPixels"]=displayPixels;this["outputPixels"]=destination
                    this["width"]=owner.width;this["height"]=owner.height;this["page"]=page;this["stepWidth"]=1 shl pass
                    this["weight"]=owner.effectWeight;this["firstPass"]=if(pass==0)1 else 0
                    this["lastPass"]=if(pass==settings.denoisePasses-1)1 else 0
                }
                reconstruction.render(cmd,Rectanglef());displayPixels=destination
            }
            timers[6].end(cmd,index)
        }
        hasHistory=!reference;epoch=settings.epoch;geometry=owner.visibility.tlasBuilds;signature=nextSignature
        previous=current;previousView=view;previousProjection=projection;frame++
    }
    private fun writeLights(buffer: VulkanBuffer,current: List<IdentifiedAreaLight>,other: List<IdentifiedAreaLight>) {
        val indices=other.withIndex().associate { it.value.id to it.index }
        buffer.mapAndWrite_bytes { current.forEach { light ->
            val e=light.emitter
            listOf(e.center,e.edgeU,e.edgeV,e.radiance).forEach { putFloat(it.x);putFloat(it.y);putFloat(it.z);putFloat(0f) }
            putInt(light.id);putInt(indices[light.id] ?: -1);putLong(0L)
        } }
    }
    override fun close() {
        if(::reconstruction.isInitialized)reconstruction.cleanup()
        if(::shader.isInitialized)shader.cleanup()
        if(::snapshot.isInitialized)snapshot.cleanup()
        bindings.values.flatMap { it.result?.buffers ?: emptyList() }.forEach { if(it !in owned)owned+=it }
        owned.forEach { it.cleanup() };owned.clear();sampler.cleanup();timers.forEach { it.close() }
    }
    companion object {
        internal fun reservoirBytes(width: Int,height: Int)=((width+15)/16).toLong()*((height+15)/16)*256*24*3
        private fun matrixDifference(a: Matrix4f,b: Matrix4f): Float {
            val x=FloatArray(16);val y=FloatArray(16);a.get(x);b.get(y)
            return x.indices.maxOf { abs(x[it]-y[it]) }
        }
    }
}

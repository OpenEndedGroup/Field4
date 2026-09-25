package afield.four.reconstruction

import afield.four.Renderer
import afield.four.Target
import afield.four.modules.Mesh
import afield.four.device.CommandBuffer
import afield.four.modules.Module
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.four.modules.graphicsShaderAbiCheck
import afield.four.targets.RenderToTexture
import afield.stdlib.linalg.Vector4f
import org.lwjgl.vulkan.VK13.*

/** Editable mono raster experiment; no ray tracing is needed. Replace at a frame boundary when
 * changing mode/resolution. Leave [mode] null for the unjittered source at native output resolution.
 */
class ReconstructionDemo(val renderer: Renderer,val mode: ReconstructionMode?=ReconstructionMode.DLAA,
    val outputSize: ReconstructionSize=ReconstructionSize(1920,1080),val name: String="neural-aa",attach: Boolean=true,
    inputSize: ReconstructionSize?=null) : Target,AutoCloseable {
    val inputSize=if(mode==null) {
        require(inputSize==null || inputSize==outputSize) { "Source mode uses native output resolution" };outputSize
    } else DlssReconstruction.recommend(renderer,outputSize,mode).let { rec ->
        (inputSize ?: rec.optimal).also { rec.validate(it,outputSize,mode) }
    }
    private val scene=RenderToTexture(renderer) {
        width=this@ReconstructionDemo.inputSize.width;height=this@ReconstructionDemo.inputSize.height;colorAttachments=3
        colorFormats=listOf(VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT)
        clearColor=afield.stdlib.vec(0,0,0,1);useDepth=true;debugName="$name-input"
    }
    val color get()=scene.imageViewArray[0]
    val depth get()=scene.imageViewArray[1]
    val motion get()=scene.imageViewArray[2]
    val reconstruction=mode?.let { DlssReconstruction(renderer,this.inputSize,outputSize,it) }
    val output get()=reconstruction?.output ?: scene
    private val module=Module(parameters(renderer) {
        this.name="$name-raster";scene.apply();depthTest=true;depthWrite=true;blend=false
        vertex=shader("scene.vert");fragment=shader("scene.frag")
    })
    private val mesh=Mesh(renderer,module.pointer()).also {
        it.resize(3+135*36,0,module.pointer());it.push.setMembers(module.fi.pushConstants.single().members)
        module.meshes["scene"]=it
    }
    var time=0f
    var abstractScene=false
    var cameraMotion=true
    var animate=true
    var horizontalShiftPixels=0f
    private var previousShift=0f
    private var previousTime=0f
    private var frame=0L
    private var revision=0L
    private var closed=false
    init {
        check(module.valid) { "Reconstruction scene shader compilation failed" }
        scene.modules["scene"]=module.pointer()
        if(attach)attach()
    }
    fun attach(): ReconstructionDemo {
        check(!closed);renderer.renderList.preTargets[name]=this;output.blitToScreen(name=name);return this
    }
    fun reset(reason: String="scene edit") { revision++;reconstruction?.reset(reason);previousTime=time }
    /** Compile and reflect both shaders, reject changed layouts, then commit at a frame boundary. */
    fun reload(vertex: String=shader("scene.vert"),fragment: String=shader("scene.frag")): Result<Unit> = runCatching {
        check(!closed)
        val errors=mutableListOf<String>()
        val v=Introspection(vertex,ShaderKind.VERTEX_SHADER,errorHandler={ message,_ -> errors+=message })
        val f=Introspection(fragment,ShaderKind.FRAGMENT_SHADER,errorHandler={ message,_ -> errors+=message })
        check(v.valid && f.valid) { errors.joinToString("\n") }
        val abi=graphicsShaderAbiCheck(module.vi,module.fi,v,f);check(abi.compatible) { abi.message }
        module.installPipelineUpdate(module.buildPipelineUpdate(v,f));reset("shader reload")
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        val jitter=if(mode==null)ReconstructionJitter(0f,0f) else ReconstructionJitter.halton(frame,inputSize,outputSize)
        mesh.push["sizeJitter"]=Vector4f(inputSize.width.toFloat(),inputSize.height.toFloat(),jitter.x,jitter.y)
        mesh.push["times"]=Vector4f(time,previousTime,horizontalShiftPixels,previousShift)
        mesh.push["settings"]=Vector4f(if(abstractScene)1f else 0f,if(cameraMotion)1f else 0f,0f,0f)
        scene.render(command,index)
        reconstruction?.evaluate(command,ReconstructionInputs(color,depth,motion),ReconstructionFrame(frame,jitter,revision=revision))
        previousTime=time;previousShift=horizontalShiftPixels;if(animate)time+=1f/60;frame++
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name)
        reconstruction?.close()
        renderer.cleanup+=Runnable {
            module.cleanup();scene.imageViewArray.forEach { it.imageView.cleanup() };scene.colorImageRefs.forEach { it?.cleanup() }
            scene.depthImageView?.cleanup();scene.depthImage?.cleanup();scene.defaultSampler?.cleanup()
        }
    }
    companion object { private fun shader(file: String)=ReconstructionDemo::class.java.getResource("/afield/four/reconstruction/$file")!!.readText() }
}

package afield.four.reconstruction

import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.fabricated.PathTracingRenderer
import afield.four.lighting.LightingGpuTimer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews

/** One ordered path-trace -> raw guides -> NGX pass. Pass an uninstalled tracer: this owner records
 * it exactly once, and closes it. The raw and progressive sources remain inspectable at all times.
 * Camera motion reprojects; call [reset] on cuts and appearance changes. Tracer property and
 * shader revisions reset RR automatically. Recreate this owner to change input/output sizes.
 */
class PathTracedReconstruction(val tracer: PathTracingRenderer,
    val outputSize: ReconstructionSize=ReconstructionSize(tracer.width,tracer.height),
    val mode: ReconstructionMode=ReconstructionMode.DLAA,val name: String="path-reconstruction") : Target,AutoCloseable {
    init {
        require(tracer.renderer.computeList.others.values.none { it===tracer }) { "Pass an uninstalled tracer; this adapter owns dispatch order" }
    }
    enum class View { RAW, PROGRESSIVE, RECONSTRUCTED }
    private val renderer=tracer.renderer
    val source=tracer.enableFrameOutput()
    private val inputSize=ReconstructionSize(tracer.width,tracer.height)
    val reconstruction=DlssReconstruction(renderer,inputSize,outputSize,mode,rayReconstruction=true)
    val output get()=reconstruction.output
    var view=View.RECONSTRUCTED
    private var frame=0L
    private var revision=0L
    private var closed=false
    private val timer=LightingGpuTimer(renderer)
    /** Includes path tracing, guide conversion and NGX; excludes presentation. */
    val gpuMilliseconds get()=timer.samples.toList()
    private val sampler=TextureSampler(renderer,1,false)
    private val present=Module(parameters(renderer) {
        this.name="$name-display";depthTest=false;depthWrite=false;blend=false
        vertex="""#version 460
            layout(location=0) out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);uv=vec2(p.x,1-p.y);}
        """
        fragment="""#version 460
            layout(set=0,binding=0) uniform sampler2D rawImage;
            layout(set=0,binding=1) uniform sampler2D progressiveImage;
            layout(set=0,binding=2) uniform sampler2D reconstructedImage;
            layout(push_constant) uniform Display {int view;float exposure;} pc;
            layout(location=0) in vec2 uv;layout(location=0) out vec4 color;
            void main(){vec3 c=pc.view==0?texture(rawImage,uv).rgb:pc.view==1?texture(progressiveImage,uv).rgb:texture(reconstructedImage,uv).rgb;
                c=max(c*pc.exposure,vec3(0));color=vec4(c/(1+c),1);}
        """
        textureResolver={ req -> when(req.name) {
            "rawImage"->fromViews(listOf(source.color),req.name,sampler)
            "progressiveImage"->tracer.output to sampler
            "reconstructedImage"->output to sampler
            else->null
        } }
    })
    init {
        check(present.valid)
        val p=present.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p)
        mesh.push.setMembers(present.fi.pushConstants.single().members)
        mesh.push.bind("view"){view.ordinal};mesh.push.bind("exposure"){tracer.exposure};present.meshes["quad"]=mesh
    }
    fun install(): PathTracedReconstruction {
        check(!closed)
        val previous=renderer.renderList.preTargets[name]
        require(previous==null || previous===this || previous is PathTracedReconstruction)
        if(previous is PathTracedReconstruction && previous!==this)previous.close()
        renderer.renderList.preTargets[name]=this;renderer.renderList.modules[name]=present.pointer()
        return this
    }
    fun reset(reason: String="camera cut or appearance edit") { revision++;tracer.reset(reason);reconstruction.reset(reason) }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed || tracer.paused)return
        timer.begin(command,index)
        source.jitter=ReconstructionJitter.halton(frame,inputSize,outputSize)
        tracer.render(command,index);tracer.output.render(command,index);source.render(command,index)
        reconstruction.evaluate(command,source.inputs(),ReconstructionFrame(frame,source.jitter,
            revision=revision+tracer.resetGeneration),source.guides())
        timer.end(command,index);frame++
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.renderList.preTargets.remove(name,this)
        if(renderer.renderList.modules[name]?.current===present)renderer.renderList.modules.remove(name)
        reconstruction.close();tracer.close()
        renderer.cleanup+=Runnable { present.cleanup();sampler.cleanup();timer.close() }
    }
}

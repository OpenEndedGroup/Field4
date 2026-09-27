package afield.four.neural

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews

/** An editable RTT teacher and its live GPU learner. Source lifetime remains with the caller.
 * View 1 with [learning]=false stops executing the source entirely; its learned function remains.
 * [parameter] is normalized to [-1,1], with the same value supplied to the teacher and network.
 */
class GpuShaderLearningView(val renderer: Renderer,val source: RenderToTexture,val name: String="gpu-learning",
    private val setSourceParameter: (Float)->Unit):Target,AutoCloseable {
    val model=GpuShaderTraining(renderer,source.width,source.height)
    var learning=true
    var iterationsPerFrame=8
        set(value){require(value in 1..64);field=value}
    var parameter=0f
    var view=2
        set(value){require(value in 0..2);field=value}
    var sourceRenderCount=0L;private set
    val output=RenderToTexture(renderer){width=source.width;height=source.height;useDepth=false;format=org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32B32A32_SFLOAT}
    private val sampler=TextureSampler(renderer,1,false)
    private val display=Module(parameters(renderer){this.name="$name-comparison";output.apply();depthTest=false;depthWrite=false;blend=false
        vertex="""#version 460
            void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        fragment="""#version 460
            layout(set=0,binding=0) uniform sampler2D teacher;
            layout(set=0,binding=1) uniform sampler2D learned;
            layout(push_constant) uniform P {int view;} p;
            layout(location=0) out vec4 color;
            void main(){vec2 uv=gl_FragCoord.xy/vec2(textureSize(teacher,0));int panel=min(int(uv.x*3),2);
                if(p.view==2)uv.x=fract(uv.x*3);
                vec3 a=texture(teacher,uv).rgb,b=texture(learned,uv).rgb;
                color=vec4(p.view==0?a:p.view==1?b:panel==0?a:panel==1?b:abs(a-b)*8,1);}
        """
        textureResolver={fromViews(listOf(if(it.name=="teacher")source else model.output),it.name,sampler)}
    })
    private val quad: Mesh
    private var closed=false
    init { check(display.valid);val p=display.pointer();quad=Mesh(renderer,p);quad.resize(3,0,p)
        quad.push.setMembers(display.fi.pushConstants.single().members);display.meshes["quad"]=quad;output.modules["quad"]=p }
    fun install():GpuShaderLearningView {
        val old=renderer.renderList.preTargets[name];require(old==null || old===this || old is GpuShaderLearningView)
        if(old is GpuShaderLearningView && old!==this)old.close()
        renderer.renderList.preTargets[name]=this;output.blitToScreen(name=name);return this
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        require(parameter.isFinite() && parameter in -1f..1f)
        if(learning || view!=1) {setSourceParameter(parameter);source.render(command,index);sourceRenderCount++}
        if(learning)model.train(command,source,parameter,iterationsPerFrame)
        model.evaluate(command,index,parameter);quad.push["view"]=view;output.render(command,index)
    }
    override fun close() { if(closed)return;closed=true;renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name);model.close()
        renderer.cleanup+=Runnable {display.cleanup();sampler.cleanup();output.imageViewArray.forEach { it.imageView.cleanup() }
            output.colorImageRefs.forEach { it?.cleanup() };output.defaultSampler?.cleanup()}
    }
}

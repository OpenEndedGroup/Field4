package afield.four.neural

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.lighting.LightingGpuTimer
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.stdlib.fromViews
import org.json.JSONObject
import org.lwjgl.vulkan.VK13.*
import java.io.File

/** Compare a real module's render target with its learned replacement. View 1 skips the source pass
 * completely; view 2 renders source / learned / absolute RGB error x8. Source resources stay caller-owned.
 * Construct, load and close between frames. A rejected model leaves the last working model attached.
 */
class ShaderLearningView(val renderer: Renderer,val source: RenderToTexture,
    val name: String="shader-learning",private val setSourceParameter: (Float)->Unit): Target,AutoCloseable {
    var parameter=0f
    var view=0
        set(value){require(value in 0..2);field=value}
    var model: LearnedShader?=null
        private set
    private val timer=LightingGpuTimer(renderer)
    private val totalTimer=LightingGpuTimer(renderer)
    val viewGpuMilliseconds get()=totalTimer.samples.toList()
    var sourceRenderCount=0L
        private set
    val sourceGpuMilliseconds get()=timer.samples.toList()
    val output=RenderToTexture(renderer){width=source.width;height=source.height;useDepth=false;format=VK_FORMAT_R32G32B32A32_SFLOAT}
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_LINEAR)
    private val predicted=object: HasImageViewArray,PreparesSampledImages {
        override val imageViewArray get()=(model?.output ?: source).imageViewArray
        override fun prepareSampledImagesFor(command: CommandBuffer)=(model?.output ?: source).prepareSampledImagesFor(command)
    }
    private val display=Module(parameters(renderer){this.name="$name-display";output.apply();vertex=VERTEX;fragment=FRAGMENT
        depthTest=false;depthWrite=false;blend=false
        textureResolver={t->if(t.name=="originalImage")fromViews(source as HasImageViewArray,t.name,sampler) else fromViews(predicted,t.name,sampler)}
    })
    private val quad: Mesh
    private var closed=false
    init {
        check(display.valid)
        val p=display.pointer();quad=Mesh(renderer,p);quad.resize(3,0,p);quad.push.setMembers(display.fi.pushConstants.single().members)
        display.meshes["quad"]=quad;output.modules["quad"]=p
        renderer.renderList.preTargets[name]=this;output.blitToScreen(name=name)
    }
    fun load(directory: File,cooperative: Boolean=renderer.device.supportsCooperativeVector) {
        check(!closed && renderer.currentCommandBuffer==null)
        val manifest=JSONObject(File(directory,"weights.json").readText())
        require(manifest.getString("sourceKind")=="captured-module") { "Choose weights trained from ShaderTraining.capture" }
        check(manifest.getString("sourceShaderDigest")==ShaderTraining.sourceDigest(source)) { "The source shader changed since capture; capture and train again" }
        val next=LearnedShader(renderer,source.width,source.height,cooperative,
            File(directory,"weights.f16"),File(directory,"weights.json"),name="$name-model",attach=false)
        next.view=1
        model?.close();model=next
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        totalTimer.begin(command,index)
        if(view!=1 || model==null) {
            setSourceParameter(parameter)
            timer.begin(command,index);source.render(command,index);timer.end(command,index);sourceRenderCount++
        }
        if(view!=0)model?.let { it.setParameter(parameter);it.render(command,index) }
        quad.push["view"]=if(model==null)0 else view
        output.render(command,index);totalTimer.end(command,index)
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name);model?.close()
        renderer.cleanup+=Runnable {display.cleanup();sampler.cleanup();timer.close();totalTimer.close();output.imageViewArray.forEach { it.imageView.cleanup() }
            output.colorImageRefs.forEach { it?.cleanup() };output.defaultSampler?.cleanup()}
    }
    companion object {
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val FRAGMENT="""#version 460
        layout(set=0,binding=0) uniform sampler2D originalImage;
        layout(set=0,binding=1) uniform sampler2D learnedImage;
        layout(push_constant) uniform P {int view;} p;
        layout(location=0) out vec4 color;
        void main(){vec2 uv=gl_FragCoord.xy/vec2(textureSize(originalImage,0));int panel=min(int(uv.x*3),2);
            if(p.view==2)uv.x=fract(uv.x*3);
            if(p.view==0){color=vec4(texture(originalImage,uv).rgb,1);return;}
            if(p.view==1){color=vec4(texture(learnedImage,uv).rgb,1);return;}
            vec3 a=texture(originalImage,uv).rgb,b=texture(learnedImage,uv).rgb;
            color=vec4(panel==0?a:panel==1?b:abs(a-b)*8,1);}
        """
    }
}

package afield.four.vision

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import afield.stdlib.linalg.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*

/** Two generated images, their motion/cost, and a feedback image driven by that motion.
 * [backend] defaults to [OpticalFlowBackend.AUTO]: the NV engine where it exists, [PyramidOpticalFlow] elsewhere. */
class OpticalFlowDemo(val renderer: Renderer,val width: Int=960,val height: Int=540,val name: String="optical-flow",
    backend: OpticalFlowBackend=OpticalFlowBackend.AUTO) : AutoCloseable {
    val pair=RenderToTexture(renderer) { this.width=this@OpticalFlowDemo.width;this.height=this@OpticalFlowDemo.height;colorAttachments=2;useDepth=false;format=VK_FORMAT_R16G16B16A16_SFLOAT }
    private val source=Module(parameters(renderer) {this.name="$name-inputs";pair.apply();vertex=VERTEX;fragment=SOURCE;depthTest=false;depthWrite=false;blend=false})
    private val sourceMesh=quad(source,pair)
    val flow: OpticalFlowOperation=opticalFlow(renderer,pair.imageViewArray[0],pair.imageViewArray[1],backend=backend)
    private val feedback=List(2){RenderToTexture(renderer){this.width=this@OpticalFlowDemo.width;this.height=this@OpticalFlowDemo.height;useDepth=false;format=VK_FORMAT_R16G16B16A16_SFLOAT}}
    val output=RenderToTexture(renderer){this.width=this@OpticalFlowDemo.width*2;this.height=this@OpticalFlowDemo.height*2;useDepth=false;format=VK_FORMAT_R16G16B16A16_SFLOAT}
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_LINEAR)
    private val advectors=List(2){i -> Module(parameters(renderer) {
        this.name="$name-advection-$i";feedback[i].apply();vertex=VERTEX;fragment=ADVECT;depthTest=false;depthWrite=false;blend=false
        textureResolver={ t -> fromViews(listOf(when(t.name){"motion"->flow.output;"source"->pair.imageViewArray[1];else->feedback[1-i]}),t.name,sampler) }
    })}
    private val advectionMeshes=advectors.mapIndexed { i,m -> quad(m,feedback[i]) }
    private val displays=List(2){i -> Module(parameters(renderer) {
        this.name="$name-display-$i";output.apply();vertex=VERTEX;fragment=DISPLAY;depthTest=false;depthWrite=false;blend=false
        textureResolver={ t -> fromViews(listOf(when(t.name){"motion"->flow.output;"firstImage"->pair.imageViewArray[0];"secondImage"->pair.imageViewArray[1];else->feedback[i]}),t.name,sampler) }
    })}
    private val displayMeshes=displays.map { m -> val p=m.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p);m.meshes["quad"]=mesh;mesh.push.setMembers(m.fi.pushConstants.single().members);mesh }
    private val command=CommandBuffer(renderer.commandPool,true,true)
    private val fence=Fence(renderer.device,false)
    var dx=12f
    var dy=-8f
    var gain=1f
    var damping=.98f
    private val hardware=flow.backend==OpticalFlowBackend.HARDWARE
    /** Cost at which the COST strip saturates. The backends' costs are on different scales (see [OpticalFlowOperation]):
     * the hardware engine's raw cost, or [PyramidOpticalFlow]'s mean window residual in grey levels. */
    val costDisplayRange=if(hardware)100f else 25f
    /** Advection keeps the previous image only where cost is below this; defaults are per backend, see [costDisplayRange]. */
    var costThreshold=if(hardware)80f else 8f
    var deformation=false
    /** Separation of the two source frames, in seconds. Advance update(time) by this amount. */
    var pairStep=1f/30f
    /** Only affects arrows/color intensity, never the displacement delivered to another shader. */
    var displayRange=3f
    private var frame=0
    private var closed=false
    init { output.blitToScreen(name=name) }
    fun update(time: Float=0f,reset: Boolean=false) {
        check(!closed)
        sourceMesh.push["state"]=Vector4f(dx,dy,time,if(deformation)1f else 0f)
        sourceMesh.push["step"]=pairStep
        submit { pair.render(command,0) }
        flow.evaluate(reset||frame==0)
        val i=frame%2
        advectionMeshes[i].push["settings"]=Vector4f(gain,damping,costThreshold,if(reset||frame==0)1f else 0f)
        displayMeshes[i].push["range"]=displayRange
        displayMeshes[i].push["cost"]=Vector4f(costDisplayRange,if(hardware)4f else 10f,if(hardware)6f else 5f,0f)
        output.modules["display"]=displays[i].pointer()
        submit {
            // Both feedback images need a defined initial layout even when the shader resets.
            if(frame==0)feedback[1-i].render(command,0)
            feedback[i].render(command,0);output.render(command,0)
        }
        frame++
    }
    private fun submit(record: ()->Unit) {
        vkResetCommandBuffer(command.vkCommandBuffer,0);command.beginRecording();record();command.endRecording()
        MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
        fence.fenceWait();fence.reset()
    }
    private fun quad(m: Module,target: RenderToTexture): Mesh {
        check(m.valid);val p=m.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p)
        m.fi.pushConstants.singleOrNull()?.let { mesh.push.setMembers(it.members) }
        m.meshes["quad"]=mesh;target.modules["quad"]=p;return mesh
    }
    override fun close() {
        if(closed)return;closed=true;renderer.renderList.modules.remove(name);flow.close()
        renderer.cleanup+=Runnable {source.cleanup();advectors.forEach { it.cleanup() };displays.forEach { it.cleanup() };sampler.cleanup();command.cleanup();fence.cleanup()
            (listOf(pair,output)+feedback).forEach { t->t.imageViewArray.forEach { it.imageView.cleanup() };t.colorImageRefs.forEach { it?.cleanup() };t.defaultSampler?.cleanup() }}
    }
    /** Invert the known source warp: a feature at (x,y) in the first image moves to this offset.
     * Diagnostic only; the runtime motion field comes exclusively from [flow].
     */
    fun expectedDisplacement(x: Float,y: Float,time: Float): afield.stdlib.linalg.Vector2f {
        if(!deformation)return afield.stdlib.linalg.Vector2f(dx,dy)
        fun warp(a: Float,b: Float,t: Float)=afield.stdlib.linalg.Vector2f(
            a+kotlin.math.sin(b*.013f+t*3)*24,b+kotlin.math.cos(a*.011f+t*2.3f)*20)
        val q=warp(x,y,time);var a=x;var b=y;val t=time+pairStep
        repeat(6) {
            val z=warp(a,b,t);val rx=z.x-q.x;val ry=z.y-q.y
            val j01=kotlin.math.cos(b*.013f+t*3)*24*.013f
            val j10=-kotlin.math.sin(a*.011f+t*2.3f)*20*.011f
            val det=1-j01*j10
            a-=(rx-j01*ry)/det;b-=(ry-j10*rx)/det
        }
        return afield.stdlib.linalg.Vector2f(a+dx-x,b+dy-y)
    }
    companion object {
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val SOURCE="""#version 460
        layout(push_constant) uniform P {vec4 state;float step;} p;
        layout(location=0) out vec4 firstImage;layout(location=1) out vec4 secondImage;
        float hash(vec2 v){return fract(sin(dot(v,vec2(127.1,311.7)))*43758.5453);}
        vec3 pattern(vec2 xy,float t){
            if(p.state.w>0){xy+=vec2(sin(xy.y*.013+t*3)*24,cos(xy.x*.011+t*2.3)*20);}
            vec2 cell=floor(xy/32),uv=fract(xy/32)-.5;float h=hash(cell);
            vec3 c=.08+.8*(.5+.5*cos(vec3(0,2,4)+h*6.28));
            float shape=smoothstep(.32,.26,length(uv));float checker=mod(cell.x+cell.y,2);
            return mix(vec3(.05+.15*checker),c,shape)+.04*sin(xy.x*.27)*sin(xy.y*.23);
        }
        void main(){vec2 xy=gl_FragCoord.xy;firstImage=vec4(pattern(xy,p.state.z),1);secondImage=vec4(pattern(xy-p.state.xy,p.state.z+p.step),1);}
        """
        private const val ADVECT="""#version 460
        layout(set=0,binding=0) uniform sampler2D motion;layout(set=0,binding=1) uniform sampler2D previous;layout(set=0,binding=2) uniform sampler2D source;
        layout(push_constant) uniform P {vec4 settings;} p;layout(location=0) out vec4 color;
        void main(){vec2 size=vec2(textureSize(source,0)),uv=gl_FragCoord.xy/size;vec3 v=texture(motion,uv).xyz;
            float trust=float(v.z<p.settings.z && length(v.xy)<128);vec2 back=uv-v.xy/size*p.settings.x*trust;
            vec3 fresh=texture(source,uv).rgb;
            color=vec4(p.settings.w>.5?fresh:mix(fresh,texture(previous,back).rgb,p.settings.y*trust),1);}
        """
        private const val DISPLAY="""#version 460
        layout(set=0,binding=0) uniform sampler2D motion;layout(set=0,binding=1) uniform sampler2D firstImage;
        layout(set=0,binding=2) uniform sampler2D secondImage;layout(set=0,binding=3) uniform sampler2D feedback;
        layout(location=0) out vec4 color;
        layout(push_constant) uniform P {float range;vec4 cost;} settings;
        // COST, "0 100+" and "0 25+": 3x5 diagnostic glyphs, without an extra texture or font dependency.
        // cost = (saturating cost, first glyph of the scale label, its glyph count, unused).
        const uint labelGlyphs[15]=uint[](29263u,31599u,31183u,9367u,31599u,0u,29850u,31599u,31599u,1488u,31599u,0u,29671u,31183u,1488u);
        float label(vec2 pixel,int start,int count,float scale) {
            ivec2 p=ivec2(floor(pixel/scale));
            if(p.x<0 || p.y<0 || p.y>=5 || p.x>=count*4)return 0;
            int column=p.x%4;if(column==3)return 0;
            return float((labelGlyphs[start+p.x/4]>>uint(p.y*3+column))&1u);
        }
        void main(){vec2 size=vec2(textureSize(firstImage,0)),p=gl_FragCoord.xy/size,uv=fract(p);vec3 rgb;
            if(p.y<1)rgb=p.x<1?texture(firstImage,uv).rgb:texture(secondImage,uv).rgb;
            else if(p.x>=1)rgb=texture(feedback,uv).rgb;
            else {vec3 flow=texture(motion,uv).xyz;float angle=atan(flow.y,flow.x);rgb=(.5+.5*cos(angle+vec3(0,2.094,4.189)))*min(length(flow.xy)/settings.range,1);
                vec2 cell=(fract(uv*size/40)-.5)*40;vec2 d=normalize(flow.xy+vec2(.0001));float along=dot(cell,d),across=abs(dot(cell,vec2(-d.y,d.x)));
                float arrow=float((abs(along)<12 && across<.8)||(along>6 && along<13 && abs(across-(13-along))<1));
                rgb=mix(rgb,vec3(1),arrow*.8*clamp(length(flow.xy)*4,0,1));if(uv.x>.85) {
                    rgb=vec3(clamp(flow.z/settings.cost.x,0,1));
                    vec2 q=vec2((uv.x-.85)*size.x,uv.y*size.y);float stripWidth=size.x*.15;
                    if(q.y<62) {
                        rgb=vec3(.025,.035,.05);
                        float ink=max(label(q-vec2(8,8),0,4,3),label(q-vec2(8,31),int(settings.cost.y),int(settings.cost.z),2));
                        rgb=mix(rgb,vec3(.92),ink);
                        if(q.y>=47 && q.y<55 && q.x>=8 && q.x<stripWidth-8)
                            rgb=vec3((q.x-8)/max(stripWidth-16,1));
                    }
                }}
            color=vec4(rgb,1);}
        """
    }
}

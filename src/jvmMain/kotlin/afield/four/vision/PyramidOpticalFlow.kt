package afield.four.vision

import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.stdlib.fromViews
import afield.stdlib.linalg.Vector4f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*

/** Portable dense optical flow: coarse-to-fine Lucas–Kanade in ordinary fragment passes.
 *
 * Runs on any Vulkan device Field runs on — MoltenVK included — because it asks for nothing beyond
 * float render targets and `texelFetch`. Same contract as the hardware [OpticalFlow] (see [OpticalFlowOperation]):
 * `output.xy` is [first] → [second] displacement in input pixels (X right, Y down), one texel per [grid] × [grid].
 *
 * The chain, recorded into one graphics command buffer per [evaluate]:
 * 1. **convert** — both inputs get the NV path's tone map and gamma, then Rec. 709 luma and one red–blue opponent
 *    channel, packed into one RGBA32F image (tracking uses luma; the coarse search uses both, see below);
 * 2. **downsample** — a [1 3 3 1]² binomial filter halves it [levels] − 1 times; each level also gets its
 *    first-image central-difference gradient, once;
 * 3. **track** — from the coarsest level down, every flow sample starts from the bilinearly upsampled coarser field
 *    and takes up to [iterations] Gauss–Newton steps ([finestIterations] at the finest level, which starts sub-pixel
 *    close and is the expensive one) over a (2·[radius]+1)² window, using first-image gradients
 *    (the inverse-compositional simplification: the structure tensor is computed once per sample);
 * 4. **smooth** — a 3 × 3 vector median after every level rejects isolated wrong matches: before they are
 *    upsampled, and, at the finest level, before they are published in [output].
 *
 * The coarsest level first takes the best integer offset within ±[search] of zero (sum of absolute luma and chroma
 * differences over the window — chroma because a coarse level is often a periodic luminance pattern that only
 * colour disambiguates), so displacements of up to about ([search] + [radius]) × 2^([levels] − 1) input pixels are
 * reachable even on texture whose coarse levels are too busy for Gauss–Newton alone.
 *
 * Sample spacing at level L is max([grid], 2^L) input pixels, so the finest level is exactly ceil(w/grid) × ceil(h/grid)
 * and writes straight into [output].
 *
 * `output.z` is the mean absolute residual over the final window, in 8-bit grey levels (0–255) — lower is better.
 * It is NOT on the same scale as the hardware engine's cost; a threshold tuned for one does not transfer.
 * [evaluate]`(reset)` is accepted for contract parity; this backend keeps no temporal hints, so every pair is independent.
 *
 * With [consistency], every [evaluate] also tracks [second] → [first] in the same command buffer and fills
 * [consistencyMap]: `.x` is the forward–backward endpoint error |f(p) + b(p + f(p))| in input pixels, a geometric
 * confidence independent of the photometric residual (occluded, disoccluded or mismatched samples score high);
 * `.y` is the backward residual; `.w` is 1. It roughly doubles the cost of a pair.
 */
class PyramidOpticalFlow(override val renderer: Renderer,override val first: HasImageView,override val second: HasImageView,
    override val grid: Int=4,val levels: Int=5,val radius: Int=4,val iterations: Int=8,val finestIterations: Int=2,val search: Int=4,
    val consistency: Boolean=false) : OpticalFlowOperation {
    override val backend get()=OpticalFlowBackend.PYRAMID
    init {
        require(grid in listOf(1,2,4,8)) { "grid must be 1, 2, 4 or 8, not $grid" }
        require(levels in 1..8 && radius in 1..8 && iterations in 1..32 && finestIterations in 1..32 && search in 0..8)
        require(first.imageView.underlyingImage!=null && second.imageView.underlyingImage!=null)
    }
    override val width=first.imageView.underlyingImage!!.imageData.width
    override val height=first.imageView.underlyingImage!!.imageData.height
    override val flowWidth=(width+grid-1)/grid
    override val flowHeight=(height+grid-1)/grid
    init { require(second.imageView.underlyingImage!!.imageData.let { it.width==width && it.height==height }) { "Both images must be ${width}×$height" } }

    private fun scale(level: Int)=1 shl level
    private fun spacing(level: Int)=maxOf(grid,scale(level))
    private fun target(w: Int,h: Int,label: String)=RenderToTexture(renderer) {
        this.width=w;this.height=h;format=VK_FORMAT_R32G32B32A32_SFLOAT;useDepth=false;debugName=label
    }
    /** (first grey, second grey, first chroma, second chroma) at each level; then (first, second, d first/dx, d first/dy). */
    private val pyramid=List(levels){ l -> target((width+scale(l)-1)/scale(l),(height+scale(l)-1)/scale(l),"flow pyramid $l") }
    private val gradients=List(levels){ l -> target(pyramid[l].width,pyramid[l].height,"flow gradients $l") }
    /** The median of the finest level is [output]; every level has a raw and a median-filtered field. */
    override val output=target(flowWidth,flowHeight,"motion field and residual")
    private val raw=List(levels){ l -> target((width+spacing(l)-1)/spacing(l),(height+spacing(l)-1)/spacing(l),"flow level $l") }
    private val smoothed=List(levels){ l -> if(l==0)output else target(raw[l].width,raw[l].height,"flow level $l median") }
    /** Coarsest level only: one texel per (sample, integer offset), holding that offset's window SAD. A separate pass
     * because the coarsest level has few samples: searching inside the tracking pass leaves the GPU nearly idle. */
    private val searchSide=2*search+1
    private val candidates=if(search==0)null else target(raw[levels-1].width*searchSide,raw[levels-1].height*searchSide,"flow search")
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
    private val modules=mutableListOf<Module>()
    private fun pass(label: String,into: RenderToTexture,fragment: String,textures: Map<String,HasImageView>): Mesh {
        val m=Module(parameters(renderer) {
            name=label;into.apply();vertex=VERTEX;this.fragment=fragment;depthTest=false;depthWrite=false;blend=false
            textureResolver={ t -> fromViews(listOf(textures[t.name] ?: error("$label: no texture ${t.name}")),t.name,sampler) }
        })
        check(m.valid) { "$label failed to build" }
        modules+=m
        val p=m.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p)
        m.fi.pushConstants.singleOrNull()?.let { mesh.push.setMembers(it.members) }
        m.meshes["quad"]=mesh;into.modules["quad"]=p
        return mesh
    }
    init {
        pass("optical pyramid convert",pyramid[0],CONVERT,mapOf("firstImage" to first,"secondImage" to second))
        for(l in 1 until levels)pass("optical pyramid $l",pyramid[l],DOWNSAMPLE,mapOf("finer" to pyramid[l-1]))
        for(l in 0 until levels)pass("optical gradients $l",gradients[l],GRADIENT,mapOf("level" to pyramid[l]))
        val top=levels-1
        candidates?.let { pass("optical search",it,SEARCH,mapOf("pyramid" to pyramid[top])).push["level"]=
            Vector4f(scale(top).toFloat(),spacing(top).toFloat(),search.toFloat(),radius.toFloat()) }
        for(l in top downTo 0) {
            val coarser=if(l==top)null else smoothed[l+1]
            pass("optical track $l",raw[l],TRACK,mapOf("pyramid" to gradients[l],"coarse" to (coarser ?: candidates ?: gradients[l]))).apply {
                push["level"]=Vector4f(scale(l).toFloat(),spacing(l).toFloat(),if(coarser==null)0f else spacing(l+1).toFloat(),(if(l==0)finestIterations else iterations).toFloat())
                // Only the coarsest level searches: every finer level starts within a pixel or two of its answer.
                push["window"]=Vector4f(radius.toFloat(),1e-6f*(2*radius+1)*(2*radius+1),if(coarser==null && candidates!=null)search.toFloat() else 0f,0f)
            }
            pass("optical median $l",smoothed[l],MEDIAN,mapOf("field" to raw[l]))
        }
    }
    /** The backward chain, sharing no resources with this one. */
    private val reverse=if(consistency) PyramidOpticalFlow(renderer,second,first,grid,levels,radius,iterations,finestIterations,search) else null
    /** Forward–backward check, same dimensions as [output]; null unless [consistency]. See the class comment. */
    val consistencyMap: RenderToTexture?=reverse?.let { back ->
        target(flowWidth,flowHeight,"flow forward-backward error").also { pass("optical consistency",it,CONSISTENCY,mapOf("forward" to output,"backward" to back.output)).push["grid"]=grid.toFloat() }
    }
    private val command=CommandBuffer(renderer.commandPool,true,true)
    private val fence=Fence(renderer.device,false)
    private val pyramidTimer=afield.four.lighting.LightingGpuTimer(renderer)
    private val levelTimers=List(levels){ afield.four.lighting.LightingGpuTimer(renderer) }
    /** "pyramid" covers conversion, downsampling and gradients; "levelN" covers level N's search, track and median. */
    override val gpuTimings: Map<String,List<Double>> get()=mapOf("pyramid" to pyramidTimer.samples.toList())+levelTimers.mapIndexed { l,t -> "level$l" to t.samples.toList() }+
        (reverse?.gpuTimings?.mapKeys { "backward-"+it.key } ?: emptyMap())
    override val milliseconds=ArrayDeque<Double>()
    override val allocatedBytes: Long get()=(pyramid+gradients+raw+smoothed+listOfNotNull(candidates,consistencyMap)).sumOf { r -> r.colorImageRefs.sumOf { it?.memorySize ?: 0L } }+
        (reverse?.allocatedBytes ?: 0L)
    private var closed=false

    override fun evaluate(reset: Boolean) {
        check(!closed && renderer.currentCommandBuffer==null) { "Evaluate optical flow between frames, after input producers submit" }
        val start=System.nanoTime()
        vkResetCommandBuffer(command.vkCommandBuffer,0);command.beginRecording()
        record(command)
        reverse?.record(command);consistencyMap?.render(command,0)
        command.endRecording()
        MemoryStack.stackPush().use { renderer.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
        fence.fenceWait();fence.reset()
        milliseconds+=(System.nanoTime()-start)/1e6;while(milliseconds.size>1024)milliseconds.removeFirst()
    }

    /** Every pass of this chain, into a command buffer someone else submits. */
    private fun record(command: CommandBuffer) {
        (first as? PreparesSampledImages)?.prepareSampledImagesFor(command);(second as? PreparesSampledImages)?.prepareSampledImagesFor(command)
        pyramidTimer.begin(command,0)
        pyramid.forEach { it.render(command,0) };gradients.forEach { it.render(command,0) }
        pyramidTimer.end(command,0)
        for(l in levels-1 downTo 0) {
            levelTimers[l].begin(command,0)
            if(l==levels-1)candidates?.render(command,0)
            raw[l].render(command,0);smoothed[l].render(command,0)
            levelTimers[l].end(command,0)
        }
    }

    override fun close() {
        if(closed)return;closed=true
        reverse?.close()
        renderer.cleanup+=Runnable {
            modules.forEach { it.cleanup() };sampler.cleanup();command.cleanup();fence.cleanup()
            pyramidTimer.close();levelTimers.forEach { it.close() }
            (pyramid+gradients+raw+smoothed+listOfNotNull(candidates,consistencyMap)).forEach { r -> r.imageViewArray.forEach { it.imageView.cleanup() };r.colorImageRefs.forEach { it?.cleanup() };r.defaultSampler?.cleanup() }
        }
    }

    companion object {
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        /** Same tone map and gamma as the hardware adapter's conversion, then luma: identical inputs for both backends. */
        private const val CONVERT="""#version 460
        layout(set=0,binding=0) uniform sampler2D firstImage;layout(set=0,binding=1) uniform sampler2D secondImage;
        layout(location=0) out vec4 result;
        vec2 encode(vec3 v){v=max(v,vec3(0));v=pow(v/(1+v),vec3(1.0/2.2));return vec2(dot(v,vec3(.2126,.7152,.0722)),.5*(v.r-v.b));}
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec2 a=encode(texelFetch(firstImage,p,0).rgb),b=encode(texelFetch(secondImage,p,0).rgb);
            result=vec4(a.x,b.x,a.y,b.y);}
        """
        private const val DOWNSAMPLE="""#version 460
        layout(set=0,binding=0) uniform sampler2D finer;layout(location=0) out vec4 result;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy),m=textureSize(finer,0)-1;vec4 s=vec4(0);const float w[4]=float[](1,3,3,1);
            for(int j=0;j<4;j++)for(int i=0;i<4;i++)s+=w[i]*w[j]*texelFetch(finer,clamp(2*p+ivec2(i-1,j-1),ivec2(0),m),0);
            result=s/64;}
        """
        private const val GRADIENT="""#version 460
        layout(set=0,binding=0) uniform sampler2D level;layout(location=0) out vec4 result;
        float at(ivec2 q){return texelFetch(level,clamp(q,ivec2(0),textureSize(level,0)-1),0).x;}
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);
            result=vec4(texelFetch(level,p,0).xy,.5*(at(p+ivec2(1,0))-at(p-ivec2(1,0))),.5*(at(p+ivec2(0,1))-at(p-ivec2(0,1))));}
        """
        /** level = (2^L, sample spacing in input px, search radius s, window radius). Texel (i·n + a, j·n + b), n = 2s+1:
         * SAD of sample (i, j) at integer offset (a − s, b − s), plus a small preference for shorter offsets. */
        private const val SEARCH="""#version 460
        layout(set=0,binding=0) uniform sampler2D pyramid;layout(push_constant) uniform P {vec4 level;} p;
        layout(location=0) out vec4 result;
        void main(){ivec2 size=textureSize(pyramid,0),t=ivec2(gl_FragCoord.xy);int s=int(p.level.z),n=2*s+1,r=int(p.level.w);
            ivec2 o=t/n,e=t-o*n-s;
            ivec2 c=min(ivec2(floor((vec2(o)+.5)*p.level.y/p.level.x)),size-1);float sad=0;
            for(int y=-r;y<=r;y++)for(int x=-r;x<=r;x++){ivec2 q=c+ivec2(x,y);
                vec4 a=texelFetch(pyramid,clamp(q,ivec2(0),size-1),0),b=texelFetch(pyramid,clamp(q+e,ivec2(0),size-1),0);
                sad+=abs(b.y-a.x)+abs(b.w-a.z);}
            result=vec4(sad+1e-4*float(dot(e,e)),0,0,1);}
        """
        /** level = (2^L, sample spacing in input px, coarser spacing or 0, iterations);
         * window = (radius, tensor ridge, integer search radius or 0). pyramid = (first, second, d first/dx, d first/dy).
         * With a search radius, `coarse` is the SEARCH result instead of a coarser field. A refinement that ends with a
         * larger window residual than its starting point is discarded: Gauss–Newton on busy texture can walk away. */
        private const val TRACK="""#version 460
        layout(set=0,binding=0) uniform sampler2D pyramid;layout(set=0,binding=1) uniform sampler2D coarse;
        layout(push_constant) uniform P {vec4 level;vec4 window;} p;
        layout(location=0) out vec4 result;
        ivec2 size;
        vec4 first(ivec2 q){return texelFetch(pyramid,clamp(q,ivec2(0),size-1),0);}
        // Exact fp32 bilinear from one gather (clamp-to-edge sampler): no linear-filter format support needed.
        // Gather order: x=(i,j+1) y=(i+1,j+1) z=(i+1,j) w=(i,j).
        float second(vec2 x){x=clamp(x,vec2(0),vec2(size-1));vec2 i=floor(x),f=x-i;
            vec4 g=textureGather(pyramid,(i+1)/vec2(size),1);
            return mix(mix(g.w,g.z,f.x),mix(g.x,g.y,f.x),f.y);}
        vec2 upsampled(vec2 inputPixel){ivec2 n=textureSize(coarse,0);vec2 u=clamp(inputPixel/p.level.z-.5,vec2(0),vec2(n-1));
            ivec2 i=ivec2(floor(u)),j=min(i+1,n-1);vec2 f=u-vec2(i);
            return mix(mix(texelFetch(coarse,i,0).xy,texelFetch(coarse,ivec2(j.x,i.y),0).xy,f.x),
                       mix(texelFetch(coarse,ivec2(i.x,j.y),0).xy,texelFetch(coarse,j,0).xy,f.x),f.y);}
        void main(){
            size=textureSize(pyramid,0);float scale=p.level.x;int r=int(p.window.x);
            ivec2 c=min(ivec2(floor((floor(gl_FragCoord.xy)+.5)*p.level.y/scale)),size-1);
            vec2 d=p.level.z>0?upsampled((vec2(c)+.5)*scale)/scale:vec2(0);
            int s=int(p.window.z);
            if(s>0){int n=2*s+1;float best=1e30;ivec2 o=ivec2(gl_FragCoord.xy)*n;
                for(int b=0;b<n;b++)for(int a=0;a<n;a++){float sad=texelFetch(coarse,o+ivec2(a,b),0).x;if(sad<best){best=sad;d=vec2(a-s,b-s);}}}
            vec2 start=d;float before=0;
            for(int y=-r;y<=r;y++)for(int x=-r;x<=r;x++){ivec2 q=c+ivec2(x,y);before+=abs(second(vec2(q)+d)-first(q).x);}
            float gxx=p.window.y,gxy=0,gyy=p.window.y;
            for(int y=-r;y<=r;y++)for(int x=-r;x<=r;x++){vec2 g=first(c+ivec2(x,y)).zw;gxx+=g.x*g.x;gxy+=g.x*g.y;gyy+=g.y*g.y;}
            float det=gxx*gyy-gxy*gxy;
            for(int k=0;k<int(p.level.w);k++){vec2 b=vec2(0);
                for(int y=-r;y<=r;y++)for(int x=-r;x<=r;x++){ivec2 q=c+ivec2(x,y);vec4 t=first(q);
                    b+=t.zw*(second(vec2(q)+d)-t.x);}
                vec2 step=-vec2(gyy*b.x-gxy*b.y,gxx*b.y-gxy*b.x)/det;
                float len=length(step);if(len>1)step/=len;
                d+=step;if(len<.01)break;}
            float residual=0;
            for(int y=-r;y<=r;y++)for(int x=-r;x<=r;x++){ivec2 q=c+ivec2(x,y);residual+=abs(second(vec2(q)+d)-first(q).x);}
            if(residual>before){d=start;residual=before;}
            result=vec4(d*scale,255*residual/float((2*r+1)*(2*r+1)),1);}
        """
        /** Forward flow texel at input position p = (texel + .5)·grid; the backward field sampled bilinearly at p + f. */
        private const val CONSISTENCY="""#version 460
        layout(set=0,binding=0) uniform sampler2D forward;layout(set=0,binding=1) uniform sampler2D backward;
        layout(push_constant) uniform P {float grid;} p;layout(location=0) out vec4 result;
        void main(){ivec2 t=ivec2(gl_FragCoord.xy),n=textureSize(backward,0);vec4 f=texelFetch(forward,t,0);
            vec2 u=clamp((vec2(t)+.5)+f.xy/p.grid-.5,vec2(0),vec2(n-1));ivec2 i=ivec2(floor(u)),j=min(i+1,n-1);vec2 w=u-vec2(i);
            vec4 b=mix(mix(texelFetch(backward,i,0),texelFetch(backward,ivec2(j.x,i.y),0),w.x),
                       mix(texelFetch(backward,ivec2(i.x,j.y),0),texelFetch(backward,j,0),w.x),w.y);
            result=vec4(length(f.xy+b.xy),b.z,0,1);}
        """
        /** Vector median of the 3 × 3 neighbourhood: the member minimising summed distance to the other eight. */
        private const val MEDIAN="""#version 460
        layout(set=0,binding=0) uniform sampler2D field;layout(location=0) out vec4 result;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy),m=textureSize(field,0)-1;vec4 v[9];
            for(int i=0;i<9;i++)v[i]=texelFetch(field,clamp(p+ivec2(i%3-1,i/3-1),ivec2(0),m),0);
            int best=4;float bestCost=1e30;
            for(int i=0;i<9;i++){float s=0;for(int j=0;j<9;j++)s+=length(v[i].xy-v[j].xy);if(s<bestCost){bestCost=s;best=i;}}
            result=vec4(v[best].xyz,1);}
        """
    }
}

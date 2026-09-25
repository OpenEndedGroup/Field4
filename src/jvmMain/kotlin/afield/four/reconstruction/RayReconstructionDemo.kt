package afield.four.reconstruction

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.lighting.*
import afield.four.modules.*
import afield.four.modules.Module
import afield.four.targets.RenderToTexture
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import org.lwjgl.vulkan.VK13.*

/** Existing Fabricate G-buffer and RTXDI, with interchangeable reconstruction at native resolution.
 * The direct-lighting bridge is Lambertian: specular albedo=0 and roughness=1 describe that BRDF.
 * Do not reuse these guides for a glossy/path-traced signal. All editing happens between frames.
 */
class RayReconstructionDemo(val renderer: Renderer,val size: ReconstructionSize=ReconstructionSize(1920,1080),
    val name: String="ray-reconstruction") : Target,AutoCloseable {
    enum class Path { RAW, SPATIAL, SPATIAL_DLAA, RAY_RECONSTRUCTION }
    val scene=DirectLightingDemo(renderer,size.width,size.height)
    val settings=RtxdiSettings({scene.bundle.scene.camera.modelView()},{scene.bundle.scene.projection.evaluateUniform()})
        .apply { temporal=true;spatialSamples=4;denoisePasses=0 }
    val rr=DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA,rayReconstruction=true)
    val sr=DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA)
    var path=Path.RAY_RECONSTRUCTION
        set(value) { if(field!=value){field=value;reset("pipeline switch")};showOutput() }
    var animate=true
    var cameraMotion=true
    var time=0.0
    val raw get()=scene.lighting.output
    val output get()=when(path){Path.RAY_RECONSTRUCTION->rr.output;Path.SPATIAL_DLAA->sr.output;else->raw}
    private val guides=RenderToTexture(renderer) {
        width=size.width;height=size.height;colorAttachments=5;useDepth=false;debugName="$name-guides"
        colorFormats=listOf(VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT)
    }
    val depth get()=guides.imageViewArray[0]
    val motion get()=guides.imageViewArray[1]
    val diffuseAlbedo get()=guides.imageViewArray[2]
    val specularAlbedo get()=guides.imageViewArray[3]
    val normalRoughness get()=guides.imageViewArray[4]
    private val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
    private val convert=Module(parameters(renderer) {
        this.name="$name-guides";guides.apply();depthTest=false;depthWrite=false;blend=false
        vertex=VERTEX;fragment=FRAGMENT
        val sources=mapOf("positions" to scene.lighting.surfaces.position,"normals" to scene.lighting.surfaces.normal,
            "albedo" to scene.lighting.surfaces.albedo,"velocity" to scene.lighting.surfaces.motion)
        textureResolver={ tex -> sources[tex.name]?.let { fromViews(listOf(it),tex.name,sampler) } }
    })
    private var frame=0L
    private var revision=0L
    private var closed=false
    init {
        scene.lighting.enableRtxdi(RtxdiProgram.load(),settings).getOrThrow()
        scene.lighting.mode=DirectLighting.Mode.RTXDI
        scene.lighting.samples=8
        check(convert.valid)
        val p=convert.pointer();val mesh=Mesh(renderer,p);mesh.resize(3,0,p);convert.meshes["quad"]=mesh;guides.modules["convert"]=p
        // Own the ordering of these existing passes, rather than running Fabricate's unrelated post effects.
        scene.bundle.frameGraph().passNames().forEach { renderer.renderList.preTargets.remove(it) }
        renderer.renderList.preTargets.remove(scene.lighting.name)
        renderer.renderList.modules.remove("direct-light-demo")
        renderer.renderList.preTargets[name]=this;showOutput()
    }
    private fun showOutput() { output.blitToScreen(name=name) }
    fun reset(reason: String="camera cut or edit") {
        revision++;rr.reset(reason);sr.reset(reason);settings.resetHistory(reason);scene.bundle.invalidateFrameHistory()
    }
    override fun render(command: CommandBuffer,index: Int) {
        if(closed)return
        val jitter=if(path==Path.RAW || path==Path.SPATIAL)ReconstructionJitter(0f,0f) else ReconstructionJitter.halton(frame,size,size)
        scene.bundle.scene.rasterJitterNdc.set(2*jitter.x/size.width,-2*jitter.y/size.height)
        if(animate)scene.animate(time)
        if(animate || cameraMotion)time+=1.0/60
        if(cameraMotion)scene.bundle.scene.camera.at.x=(5+kotlin.math.sin(time*.4)*1.5).toFloat()
        settings.commitLights(listOf(IdentifiedAreaLight(1,scene.lighting.emitter)))
        settings.denoisePasses=if(path==Path.SPATIAL || path==Path.SPATIAL_DLAA)3 else 0
        scene.bundle.gBuffer.render(command,index);scene.lighting.render(command,index);guides.render(command,index)
        val inputs=ReconstructionInputs(raw,depth,motion)
        val state=ReconstructionFrame(frame,jitter,revision=revision)
        when(path) {
            Path.RAY_RECONSTRUCTION->rr.evaluate(command,inputs,state,RayReconstructionGuides(diffuseAlbedo,specularAlbedo,normalRoughness,
                scene.bundle.scene.camera.modelView(),scene.bundle.scene.projection.evaluateUniform()))
            Path.SPATIAL_DLAA->sr.evaluate(command,inputs,state)
            else->Unit
        }
        frame++
    }
    override fun close() {
        if(closed)return;closed=true
        renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name)
        rr.close();sr.close();scene.close()
        renderer.cleanup+=Runnable { convert.cleanup();sampler.cleanup();guides.imageViewArray.forEach { it.imageView.cleanup() }
            guides.colorImageRefs.forEach { it?.cleanup() };guides.defaultSampler?.cleanup() }
    }
    companion object {
        private const val VERTEX="""#version 460
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val FRAGMENT="""#version 460
        layout(set=0,binding=0) uniform sampler2D positions;
        layout(set=0,binding=1) uniform sampler2D normals;
        layout(set=0,binding=2) uniform sampler2D albedo;
        layout(set=0,binding=3) uniform sampler2D velocity;
        layout(location=0) out float depth;
        layout(location=1) out vec2 motion;
        layout(location=2) out vec4 diffuse;
        layout(location=3) out vec4 specular;
        layout(location=4) out vec4 normalRoughness;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec4 pos=texelFetch(positions,p,0);
            depth=pos.w;motion=-texelFetch(velocity,p,0).xy*vec2(textureSize(velocity,0));
            vec3 n=texelFetch(normals,p,0).xyz;bool valid=pos.w<1 && dot(n,n)>.01;
            diffuse=vec4(valid?texelFetch(albedo,p,0).rgb:vec3(0),1);
            specular=vec4(0,0,0,1);normalRoughness=vec4(valid?normalize(n):vec3(0,0,1),1);
        }
        """
    }
}

# Field Document: rr_plumbing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["rr_plumbing"] "properties" {"children" #{#afield/box "rr_plumbing"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableVR = false
Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. rr plumbing

<!-- field-box id="rr_plumbing" index="1" -->

```edn [field-box id="rr_plumbing" role="properties"]
{"id" "rr_plumbing" "index" 1 "name" "rr plumbing" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "rr plumbing" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rr_plumbing_code_.kt" "lines" 112 "characters" 5425 "sha256" "a89e27370b30354cdb5371b88ed14b5f1f8d540e13fecfaa7e5078430ec3f9e8"} {"name" "vertex" "language" "glsl" "path" "rr_plumbing_vertex_.glsl" "lines" 2 "characters" 107 "sha256" "11e9110bea13bafec6292e2218890a34f8a60769aafc44ef4f811e59f72cf262"} {"name" "fragment" "language" "glsl" "path" "rr_plumbing_fragment_.glsl" "lines" 16 "characters" 779 "sha256" "9f5d344f7f005d1a674ec6f3bdcb68a761270cdfa225b2179db42c4778fabec9"}]}
```

```kotlin [field-property box="rr_plumbing" name="code" path="rr_plumbing_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.lighting.*
import afield.four.modules.fromContextWithNameOrNull
import afield.four.reconstruction.*
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import org.lwjgl.vulkan.VK13.*

// This guide conversion describes the existing opaque Lambertian RTXDI bridge.
// It is not a template for inventing missing glossy or transmission guides.
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
prop<AutoCloseable>(plumbing)
fromContextWithNameOrNull<AutoCloseable>("plumbing")?.close()
val size=ReconstructionSize(1920,1080)
prop<DirectLightingDemo>(scene)
scene=DirectLightingDemo(renderer,size.width,size.height)
val settings=RtxdiSettings({scene.bundle.scene.camera.modelView()},{scene.bundle.scene.projection.evaluateUniform()})
    .apply { temporal=true;spatialSamples=4;denoisePasses=0 }
scene.lighting.enableRtxdi(RtxdiProgram.load(),settings).getOrThrow()
scene.lighting.mode=DirectLighting.Mode.RTXDI
scene.lighting.samples=8
val rr=DlssReconstruction(renderer,size,size,ReconstructionMode.DLAA,rayReconstruction=true)
prop<RenderToTexture>(guides)
guides=RenderToTexture(renderer) {
    width=size.width;height=size.height;useDepth=false;colorAttachments=5
    colorFormats=listOf(VK_FORMAT_R32_SFLOAT,VK_FORMAT_R32G32_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT)
}
val sampler=TextureSampler(renderer,1,false,filter=VK_FILTER_NEAREST)
val surfaces=scene.lighting.surfaces
val inputs=mapOf("positions" to surfaces.position,"normals" to surfaces.normal,
    "albedo" to surfaces.albedo,"velocity" to surfaces.motion)
val guideModule=moduleHere {
    guides.apply();depthTest=false;depthWrite=false;blend=false
    textureResolver={ texture -> inputs[texture.name]?.let { fromViews(listOf(it),texture.name,sampler) } }
}
val quad=Mesh(renderer,guideModule)
quad.resize(3,0,guideModule)
guideModule += "convert" to quad
guides += "guides" to guideModule

// Remove independent scheduling: this pass now owns their order.
scene.bundle.frameGraph().passNames().forEach { renderer.renderList.preTargets.remove(it) }
renderer.renderList.preTargets.remove(scene.lighting.name)
renderer.renderList.modules.remove("direct-light-demo")
var bypass=false
var animate=true
var cut=false
var time=0.0
var frameIndex=0L
var revision=0L
var shaderVersion=guideModule.current.fi
val pass=object: Target {
    override fun render(command: CommandBuffer,index: Int) {
        if(shaderVersion!==guideModule.current.fi) {shaderVersion=guideModule.current.fi;revision++;rr.reset("guide edit")}
        val jitter=if(bypass)ReconstructionJitter(0f,0f) else ReconstructionJitter.halton(frameIndex,size,size)
        scene.bundle.scene.rasterJitterNdc.set(2*jitter.x/size.width,-2*jitter.y/size.height)
        scene.animate(time)
        if(animate)time+=1.0/60
        settings.commitLights(listOf(IdentifiedAreaLight(1,scene.lighting.emitter)))

        scene.bundle.gBuffer.render(command,index)  // geometry, unjittered motion
        scene.lighting.render(command,index)       // raw RTXDI RGB
        guides.render(command,index)               // moduleHere format / convention conversion
        if(!bypass)rr.evaluate(command,
            ReconstructionInputs(scene.lighting.output,guides.imageViewArray[0],guides.imageViewArray[1]),
            ReconstructionFrame(frameIndex,jitter,revision=revision),
            RayReconstructionGuides(guides.imageViewArray[2],guides.imageViewArray[3],guides.imageViewArray[4],
                scene.bundle.scene.camera.modelView(),scene.bundle.scene.projection.evaluateUniform()))
        frameIndex++
    }
}
renderer.renderList.preTargets["RR plumbing"]=pass
rr.output.blitToScreen(name="RR plumbing")
// Capture this allocation, not the mutable box prop that a rerun replaces.
val ownedTarget=guides
val owner=renderer // Cleanup must not depend on a still-running parent box.
var closed=false
plumbing=AutoCloseable {
    if(!closed) {
        closed=true;owner.renderList.preTargets.remove("RR plumbing",pass)
        owner.renderList.modules.remove("RR plumbing")
        rr.close();scene.close()
        owner.cleanup+=Runnable {
            guideModule.current.cleanup();sampler.cleanup()
            ownedTarget.imageViewArray.forEach { it.imageView.cleanup() }
            ownedTarget.colorImageRefs.forEach { it?.cleanup() };ownedTarget.defaultSampler?.cleanup()
        }
    }
}
controlGroup("RTXDI to RR") {
    checkbox("Bypass to raw lighting",initialValue=false) { bypass=it }
    checkbox("Move light",initialValue=true) { animate=it }
    button("Cut / reset history") { if(it)cut=true }
}
displayOpen("RTXDI to RR")
println("Read render(): G-buffer → raw RTXDI → five guide attachments → RR → screen. Edit fragment to inspect the guide mapping.")
frames {
    var wasBypass=bypass
    while(true) {
        if(wasBypass!=bypass) {
            rr.reset("bypass changed");settings.resetHistory("bypass changed")
            (if(bypass)scene.lighting.output else rr.output).blitToScreen(name="RR plumbing")
            wasBypass=bypass
        }
        if(cut) {time+=4;revision++;rr.reset("live cut");settings.resetHistory("live cut");scene.bundle.invalidateFrameHistory();cut=false}
        frame()
    }
}.end { plumbing.close() }
```

```glsl [field-property box="rr_plumbing" name="vertex" path="rr_plumbing_vertex_.glsl"]
#version 460
void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
```

```glsl [field-property box="rr_plumbing" name="fragment" path="rr_plumbing_fragment_.glsl"]
#version 460
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
```


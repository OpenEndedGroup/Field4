# Field Document: optical_flow_pingpong

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["optical_flow_pingpong"] "properties" {"children" #{#afield/box "optical_flow_pingpong"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 289 "sha256" "48b4c5aeec967fc6ec5dc83b23de5c1e6e911c61acc39c811a8d1eac77f6020a"}]}
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

## 2. optical_flow_pingpong

<!-- field-box id="optical_flow_pingpong" index="1" -->

```edn [field-box id="optical_flow_pingpong" role="properties"]
{"id" "optical_flow_pingpong" "index" 1 "name" "optical_flow_pingpong" "parents" ["root" "agent_graphics_context"] "children" ["flow_texture"] "properties" {"children" #{#afield/box "flow_texture"} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "optical_flow_pingpong" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "optical_flow_pingpong_code_.kt" "lines" 98 "characters" 4390 "sha256" "252837dcec2bb580c684c372b9800bb0282ef98c9e56f81a78cc767b0a34f27f"} {"name" "vertex" "language" "glsl" "path" "optical_flow_pingpong_vertex_.glsl" "lines" 6 "characters" 148 "sha256" "15ab8178d1aef2acf5d8bd95e36711503c7d8eb87f1698920f9a4e6c99275242"} {"name" "fragment" "language" "glsl" "path" "optical_flow_pingpong_fragment_.glsl" "lines" 30 "characters" 1327 "sha256" "ddc1d84de3d017302dbfe5eaf20e2f0e257f27e2a19a7f793b439dfe544b4e58"}]}
```

```kotlin [field-property box="optical_flow_pingpong" name="code" path="optical_flow_pingpong_code_.kt"]
import afield.controlGroup
import afield.displayOpen
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.fromContextWithNameOrNull
import afield.four.targets.PingPongRenderToTexture
import afield.four.texture.HasImageView
import afield.four.vision.opticalFlow
import afield.stdlib.linalg.Vector2f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*

prop<AutoCloseable>(flowSession)
fromContextWithNameOrNull<AutoCloseable>("flowSession")?.close()

// One editable module, two retained frames. Rendering rotates the ring automatically.
prop<PingPongRenderToTexture>(history) = PingPongRenderToTexture(renderer) {
    width=960; height=540; useDepth=false; format=VK_FORMAT_R16G16B16A16_SFLOAT
}
val scene = moduleHere { history.apply(); depthTest=false; depthWrite=false; blend=false }
val triangle = Mesh(renderer,scene)
triangle.resize(3,0,scene)
triangle.push.setMembers(scene.current.fi.pushConstants.single().members)
scene += "image" to triangle
history += "image" to scene

// These providers follow the ring. Do not snapshot their .imageView handles.
prop<HasImageView>(previousImage) = history.previousImageView
prop<HasImageView>(currentImage) = history.latestImageView
// The NV hardware engine where the device has one; portable pyramidal Lucas–Kanade everywhere else.
// Fully qualified: prop<T> finds T's import in a classpath index that lists classes, not interfaces.
prop<afield.four.vision.OpticalFlowOperation>(flow) = opticalFlow(renderer,previousImage,currentImage,grid=4)
println("Optical flow backend: ${flow.backend}")
prop<RenderToTexture>(motion) = flow.output

// evaluate() submits (to the optical-flow queue, or the graphics queue) and waits, so run it between Field frames.
// Submit the source first. The final fence makes the produced pair ready to sample.
val owner=renderer
val ownedHistory=history
val ownedFlow=flow
val command=CommandBuffer(owner.commandPool,true,false)
val fence=Fence(owner.device,false)
fun renderSource() {
    vkResetCommandBuffer(command.vkCommandBuffer,0)
    command.beginRecording(); ownedHistory.render(command,0); command.endRecording()
    MemoryStack.stackPush().use { owner.graphQueue.submit(it.pointers(command.vkCommandBuffer),null,null,null,fence) }
    fence.fenceWait(); fence.reset()
}
var animate=true
var reset=true
var time=0f
var previousTime=0f
// Analytical displacement of this example, useful for checking the measured texture.
prop<Vector2f>(expectedMotion)=Vector2f()
var shaderVersion=scene.current.fi
var closed=false
flowSession=AutoCloseable {
    if(!closed) {
        closed=true
        // A consumer must stop sampling before its parent's resources retire.
        owner.renderList.modules.remove("pingpong flow texture")
        if(!owner.isClosed) {
            ownedFlow.close()
            val views=ownedHistory.frameImageViews.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }
            owner.cleanup+=Runnable {
                scene.current.cleanup(); command.cleanup(); fence.cleanup()
                views.forEach { it.cleanup(); it.underlyingImage?.cleanup() }
                ownedHistory.defaultSampler?.cleanup()
            }
        }
    }
}
controlGroup("pingpong motion") {
    checkbox("Animate source",initialValue=true) { animate=it; reset=true }
    button("Reset the pair") { if(it)reset=true }
}
displayOpen("pingpong motion")
println("Render source → previous/current pair → optical flow → motion texture. Edit fragment here; edit the child to sample motion.")
val ownedSession=flowSession
frames {
    while(!closed && !owner.isClosed) {
        if(shaderVersion!==scene.current.fi) { shaderVersion=scene.current.fi; reset=true }
        triangle.push["time"]=time
        if(reset)previousTime=time
        expectedMotion=Vector2f(
            (kotlin.math.sin(time)-kotlin.math.sin(previousTime))*180,
            (kotlin.math.cos(time*1.3f)-kotlin.math.cos(previousTime*1.3f))*120)
        // Seed both slots on the first frame, a cut, or a shader edit: no old/new comparison.
        if(reset)renderSource()
        renderSource()
        // A paused pair is repeated, not a new consecutive observation.
        ownedFlow.evaluate(reset=reset || !animate)
        reset=false
        previousTime=time
        if(animate)time+=1f/30f
        frame()
    }
}.end { ownedSession.close() }
```

```glsl [field-property box="optical_flow_pingpong" name="vertex" path="optical_flow_pingpong_vertex_.glsl"]
#version 460
layout(location=0) out vec2 uv;
void main() {
    uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(uv*2-1,0,1);
}
```

```glsl [field-property box="optical_flow_pingpong" name="fragment" path="optical_flow_pingpong_fragment_.glsl"]
#version 460
layout(location=0) out vec4 color;
layout(push_constant) uniform Frame { float time; } frame;
// Optical flow follows brightness. Hue changes alone did not disambiguate the old
// repeating checkerboard: neighboring cells could produce convincing wrong matches.
float brightnessKey(ivec2 cell) {
    uvec2 c=uvec2(cell);
    uint h=c.x*1597334677u ^ c.y*3812015801u;
    h=(h ^ (h>>16u))*2246822519u;
    h=(h ^ (h>>13u))*3266489917u;
    return float((h ^ (h>>16u)) & 65535u)/65535.0;
}
float textureKey(vec2 p) {
    vec2 q=p/11.0, f=fract(q);
    ivec2 c=ivec2(floor(q));
    f=f*f*(3.0-2.0*f);
    return mix(mix(brightnessKey(c),brightnessKey(c+ivec2(1,0)),f.x),
               mix(brightnessKey(c+ivec2(0,1)),brightnessKey(c+ivec2(1,1)),f.x),f.y);
}
void main() {
    vec2 p=gl_FragCoord.xy-vec2(sin(frame.time)*180,cos(frame.time*1.3)*120);
    vec2 cell=floor(p/34),local=fract(p/34)-.5;
    float seed=brightnessKey(ivec2(cell));
    vec3 pigment=.35+.3*cos(seed*6.283+vec3(0,2.094,4.189));
    float disk=1-smoothstep(.20+.12*seed,.24+.12*seed,length(local));
    // A fixed, aperiodic luminance texture moves with the circles, even in the gaps.
    vec3 background=vec3(.035+.26*textureKey(p));
    pigment*=.5+brightnessKey(ivec2(cell)+ivec2(71,103));
    color=vec4(mix(background,pigment,disk),1);
}
```

## 3. flow_texture

<!-- field-box id="flow_texture" index="2" -->

### Narrative

From `code`

The parent publishes these three props: previousImage, currentImage, motion.
moduleHere() resolves the samplers by those names, just like any other texture.

```edn [field-box id="flow_texture" role="properties"]
{"id" "flow_texture" "index" 2 "name" "flow_texture" "parents" ["root" "optical_flow_pingpong"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "optical_flow_pingpong"} "name" "flow_texture" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_texture_code_.kt" "lines" 12 "characters" 593 "sha256" "c68d3eb4588337642d9f1e132bdc821930fe9a382c1604ba489b1d21ba53f5b0"} {"name" "vertex" "language" "glsl" "path" "flow_texture_vertex_.glsl" "lines" 6 "characters" 148 "sha256" "15ab8178d1aef2acf5d8bd95e36711503c7d8eb87f1698920f9a4e6c99275242"} {"name" "fragment" "language" "glsl" "path" "flow_texture_fragment_.glsl" "lines" 23 "characters" 1080 "sha256" "5f46665a6416948d2710a8cf37d47611243c67129e8ea92fc2f8f4cd0234eb3b"}]}
```

```kotlin [field-property box="flow_texture" name="code" path="flow_texture_code_.kt"]
// The parent publishes these three props: previousImage, currentImage, motion.
// moduleHere() resolves the samplers by those names, just like any other texture.
val view = moduleHere { depthTest=false; depthWrite=false; blend=false }
val triangle = Mesh(renderer,view)
triangle.resize(3,0,view)
view += "display" to triangle
renderer.renderList.modules["pingpong flow texture"]=view
val owner=renderer
frames { while(!owner.isClosed)frame() }.end {
    owner.renderList.modules.remove("pingpong flow texture",view)
    if(!owner.isClosed)owner.cleanup+=Runnable { view.current.cleanup() }
}
```

```glsl [field-property box="flow_texture" name="vertex" path="flow_texture_vertex_.glsl"]
#version 460
layout(location=0) out vec2 uv;
void main() {
    uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(uv*2-1,0,1);
}
```

```glsl [field-property box="flow_texture" name="fragment" path="flow_texture_fragment_.glsl"]
#version 460
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
layout(set=0,binding=0) uniform sampler2D previousImage;
layout(set=0,binding=1) uniform sampler2D currentImage;
layout(set=0,binding=2) uniform sampler2D motion;
void main() {
    vec2 panel=vec2(uv.x,1-uv.y)*2,st=fract(panel);
    vec4 field=texture(motion,st); // xy: input pixels, z: hardware matching cost
    vec3 rgb;
    if(panel.y<1)rgb=panel.x<1?texture(previousImage,st).rgb:texture(currentImage,st).rgb;
    else if(panel.x>=1)rgb=vec3(clamp(field.z/100,0,1));
    else {
        float angle=atan(field.y,field.x);
        rgb=(.5+.5*cos(angle+vec3(0,2.094,4.189)))*clamp(length(field.xy)/8,0,1);
        vec2 cell=(fract(st*vec2(textureSize(currentImage,0))/40)-.5)*40;
        vec2 d=normalize(field.xy+vec2(.0001));
        float along=dot(cell,d),across=abs(dot(cell,vec2(-d.y,d.x)));
        float arrow=float((abs(along)<12&&across<.8)||(along>6&&along<13&&abs(across-(13-along))<1));
        rgb=mix(rgb,vec3(1),arrow*.8*clamp(length(field.xy),0,1));
    }
    color=vec4(rgb,1);
}
```


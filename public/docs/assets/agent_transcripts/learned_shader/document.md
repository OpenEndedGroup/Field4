# Field Document: learned_shader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["learned_shader" "learned_material"] "properties" {"children" #{#afield/box "learned_shader" #afield/box "learned_material"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 16 "characters" 299 "sha256" "db2fbe76f4bfbd4a9afe212fe7ab2ca48a7e6d35fea5cf93ca547a065409ecb3"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

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

## 2. learned shader

<!-- field-box id="learned_shader" index="1" -->

```edn [field-box id="learned_shader" role="properties"]
{"id" "learned_shader" "index" 1 "name" "learned shader" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "learned shader" "_tombstone" false "frame" #afield/rect [400.0 160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "learned_shader_code_.kt" "lines" 53 "characters" 1915 "sha256" "f4a1cbde258272b07fca4f62aac4cafd93b32783a187548957da055f749e8405"}]}
```

```kotlin [field-property box="learned_shader" name="code" path="learned_shader_code_.kt"]
import afield.graphics.shader.learning.*

prop<LearnedShader>(lab)
val runScope = requireNotNull(RunScope.current()) { "Run this example in a Field box." }
var cooperative = renderer.device.supportsCooperativeVector
var active = cooperative
var requestedView = 2
var animate = true
var reload = false
var ownedLab = LearnedShader(renderer, cooperative = cooperative)
lab = ownedLab
runScope.own { ownedLab.close() }
controlGroup("learned shader") {
    checkbox("Cooperative vectors", initialValue = cooperative) { cooperative = it }
    slider("0: Original · 1: Learned · 2: Compare", min = 0.0, max = 2.0, step = 1.0, initialValue = 2.0) {
        requestedView = it.toInt()
    }
    checkbox("Animate time", initialValue = true) { animate = it }
    button("Reload trained weights") { if (it) reload = true }
}
displayOpen("learned shader")
println("Compare: original / learned / absolute RGB error × 8. Weights describe a 16 → 32 → 32 → 3 network.")
frames {
    var time = 0.0
    while (true) {
        if (cooperative != active || reload) {
            runCatching {
                val next = LearnedShader(renderer, cooperative = cooperative, attach = false)
                try {
                    next.attach()
                } catch (failure: Throwable) {
                    next.close()
                    throw failure
                }
                val previous = ownedLab
                ownedLab = next
                lab = next
                previous.close()
                active = cooperative
            }
                .onFailure {
                    println("Keeping working shader: ${it.message}")
                    cooperative = active
                }
            reload = false
        }
        ownedLab.view = requestedView
        if (animate) time += .01
        ownedLab.time = kotlin.math.sin(time).toFloat()
        frame()
    }
}
    .end { ownedLab.close() }
```

## 3. editable learned material

<!-- field-box id="learned_material" index="2" -->

```edn [field-box id="learned_material" role="properties"]
{"id" "learned_material" "index" 2 "name" "editable learned material" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "editable learned material" "_tombstone" false "frame" #afield/rect [400.0 340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "learned_material_code_.kt" "lines" 40 "characters" 1501 "sha256" "95009f63277f0a57ecc804b2f9ac5dc66883a82606a77ce567271d094d6aa18e"} {"name" "vertex" "language" "glsl" "path" "learned_material_vertex_.glsl" "lines" 7 "characters" 174 "sha256" "bcd4c2889657a59af7bc9ae25c65886d166f90b889f154b640ffd93e4f1c35ab"} {"name" "fragment" "language" "glsl" "path" "learned_material_fragment_.glsl" "lines" 12 "characters" 436 "sha256" "231fe59291fb8d75063c17d80688cd3c7c36b0bd1708b3abadc19c49df20f12e"}]}
```

```kotlin [field-property box="learned_material" name="code" path="learned_material_code_.kt"]
import afield.graphics.shader.learning.LearnedShader

// A separate scene: stop the original learned-shader box before launching this one.
// Edit this box's fragment property while it runs; the network stays allocated.
prop<AutoCloseable>(materialSession)
fromContextWithNameOrNull<AutoCloseable>("materialSession")?.close()
val owner = renderer
val passName = "editable learned material"
val learned = LearnedShader(owner, name = passName, attach = false)
learned.view = 1
learned.setParameter(.25f)
prop<RenderToTexture>(learnedImage) = learned.output
val material = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val triangle = Mesh(owner, material)
triangle.resize(3, 0, material)
material += "fullscreen" to triangle

// Compute and resolve run before this ordinary fragment shader samples their RTT.
owner.renderList.preTargets[passName] = learned
owner.renderList.modules[passName] = material
var closed = false
materialSession = AutoCloseable {
    if (!closed) {
        closed = true
        owner.renderList.modules.remove(passName, material)
        if (!owner.isClosed) {
            learned.close()
            owner.cleanup += Runnable { material.current.cleanup() }
        }
    }
}
val ownedSession = materialSession
println(
    "Edit fragment: the learnedImage texture comes from the unchanged network. Stop the original comparison box to avoid rendering it too.",
)
frames { while (!closed && !owner.isClosed) frame() }.end { ownedSession.close() }
```

```glsl [field-property box="learned_material" name="vertex" path="learned_material_vertex_.glsl"]
#version 460
layout(location=0) out vec2 uv;
void main() {
    vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    uv=vec2(p.x,1-p.y);
    gl_Position=vec4(p*2-1,0,1);
}
```

```glsl [field-property box="learned_material" name="fragment" path="learned_material_fragment_.glsl"]
#version 460
layout(set=0,binding=0) uniform sampler2D learnedImage;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    // The left half is the model's RGB; the right is an editable treatment.
    vec2 st=vec2(fract(uv.x*2),uv.y);
    vec3 base=texture(learnedImage,st).rgb;
    vec3 treatment=mix(base,base.gbr,.65);
    treatment*=.85+.15*sin(st.y*160);
    color=vec4(uv.x<.5 ? base : treatment,1);
}
```


# Field Document: syphon_gpu

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="syphon_gpu_renderer" index="0" -->

```edn [field-box id="syphon_gpu_renderer" role="properties"]
{"id" "syphon_gpu_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["syphon_gpu_publish" "syphon_gpu_receive"] "properties" {"children" #{#afield/box "syphon_gpu_publish" #afield/box "syphon_gpu_receive"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_gpu_renderer_code_.kt" "lines" 11 "characters" 257 "sha256" "03e8713c75d9569dc75c7d1d3c498e4f5a7666b33feae447c434503726e16e8a"}]}
```

```kotlin [field-property box="syphon_gpu_renderer" name="code" path="syphon_gpu_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Syphon")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. Syphon — publish a render target from the GPU

<!-- field-box id="syphon_gpu_publish" index="1" -->

```edn [field-box id="syphon_gpu_publish" role="properties"]
{"id" "syphon_gpu_publish" "index" 1 "name" "Syphon — publish a render target from the GPU" "parents" ["root" "syphon_gpu_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "syphon_gpu_renderer"} "name" "Syphon — publish a render target from the GPU" "_tombstone" false "frame" #afield/rect [40.0 480.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_gpu_publish_code_.kt" "lines" 25 "characters" 732 "sha256" "3334efbb4a312b69bd0bef23ae72a392b301221dd653e9d5ca9756a3e9389ebd"} {"name" "vertex" "language" "glsl" "path" "syphon_gpu_publish_vertex_.glsl" "lines" 8 "characters" 187 "sha256" "87f01eaf76f00f64181f4a1b96be43770f2a57c9787b78cfa701a414ac3a1a17"} {"name" "fragment" "language" "glsl" "path" "syphon_gpu_publish_fragment_.glsl" "lines" 7 "characters" 213 "sha256" "99450bd3f231e3203edb11cdbf1847d9068c4ff35114fcaf8d9c6daca7063abe"}]}
```

```kotlin [field-property box="syphon_gpu_publish" name="code" path="syphon_gpu_publish_code_.kt"]
import afield.io.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

prop<RenderToTexture>(syphonTarget) = RenderToTexture {
    width = 640
    height = 360
}
renderer.renderList += "syphonTarget" to syphonTarget

// a quad over the whole target, coloured by its texture coordinates
val module = moduleHere()
val quad = Mesh()
module += "quad" to quad
syphonTarget += "gradient" to module
quad.set(4, 2) {
    pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}

// every frame syphonTarget renders goes to Syphon, copied on the GPU
syphonServer("Field GPU").followOnGpu(syphonTarget)
```

```glsl [field-property box="syphon_gpu_publish" name="vertex" path="syphon_gpu_publish_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 1) out vec2 ftc;
void main() {
    gl_Position = vec4(pos.xy, 0.5, 1.0);
    ftc = tc;
}
```

```glsl [field-property box="syphon_gpu_publish" name="fragment" path="syphon_gpu_publish_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
void main() {
    // red grows to the right, green upwards; bottom-left is dark blue
    color = vec4(ftc.x, ftc.y, 0.3, 1.0);
}
```

## 3. Syphon — receive into a texture

<!-- field-box id="syphon_gpu_receive" index="2" -->

```edn [field-box id="syphon_gpu_receive" role="properties"]
{"id" "syphon_gpu_receive" "index" 2 "name" "Syphon — receive into a texture" "parents" ["root" "syphon_gpu_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "syphon_gpu_renderer"} "name" "Syphon — receive into a texture" "_tombstone" false "frame" #afield/rect [40.0 920.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_gpu_receive_code_.kt" "lines" 21 "characters" 599 "sha256" "ae49a03594ddaee0a716364936085f8d9ca8b3845a1ab63e3f336b2dc35a844f"} {"name" "vertex" "language" "glsl" "path" "syphon_gpu_receive_vertex_.glsl" "lines" 8 "characters" 187 "sha256" "87f01eaf76f00f64181f4a1b96be43770f2a57c9787b78cfa701a414ac3a1a17"} {"name" "fragment" "language" "glsl" "path" "syphon_gpu_receive_fragment_.glsl" "lines" 7 "characters" 208 "sha256" "324ba797f122d6e6d6a8714f5f90b60e11fd32536c1ef0b0f84bf6d608d8b21a"}]}
```

```kotlin [field-property box="syphon_gpu_receive" name="code" path="syphon_gpu_receive_code_.kt"]
import afield.io.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// a server's name, app name or uuid
val inp = syphonClient("Field GPU")
prop<SyphonTexture>(syphonIn) = inp.texture()

val module = moduleHere()
val quad = Mesh()
module += "quad" to quad
renderer += "syphonView" to module
quad.set(4, 2) {
    pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
    // the texture's v = 0 is the top of the picture, so the top corners get v = 0
    tc.add(0, 1, 1, 1, 1, 0, 0, 0)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
inp
```

```glsl [field-property box="syphon_gpu_receive" name="vertex" path="syphon_gpu_receive_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 1) out vec2 ftc;
void main() {
    gl_Position = vec4(pos.xy, 0.5, 1.0);
    ftc = tc;
}
```

```glsl [field-property box="syphon_gpu_receive" name="fragment" path="syphon_gpu_receive_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
layout(set = 0, binding = 0) uniform sampler2D syphonIn;
void main() {
    color = vec4(texture(syphonIn, ftc).rgb, 1.0);
}
```


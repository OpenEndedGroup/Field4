# Field Document: webrtc_shader_jam

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. WebRTC window + renderer

<!-- field-box id="webrtc_renderer" index="0" -->

```edn [field-box id="webrtc_renderer" role="properties"]
{"id" "webrtc_renderer" "index" 0 "name" "WebRTC window + renderer" "parents" ["root"] "children" ["webrtc_publish_shader" "webrtc_receive_stream"] "properties" {"children" #{#afield/box "webrtc_publish_shader" #afield/box "webrtc_receive_stream"} "parents" #{#afield/box "root"} "name" "WebRTC window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "webrtc_renderer_code_.kt" "lines" 15 "characters" 330 "sha256" "e729b6b924dc409b9f468545e0dd172e82d9ac9797adc03cbbfa38dd0be53675"}]}
```

```kotlin [field-property box="webrtc_renderer" name="code" path="webrtc_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.enableVR = false

once.renderer {
    window = Window("WebRTC shader jam", initialDimensions = Rect(80.0, 80.0, 960.0, 540.0))
    Renderer(window)
}

println("WebRTC renderer ready")
start(renderer::run)
```

## 2. publish a shader

<!-- field-box id="webrtc_publish_shader" index="1" -->

```edn [field-box id="webrtc_publish_shader" role="properties"]
{"id" "webrtc_publish_shader" "index" 1 "name" "publish a shader" "parents" ["root" "webrtc_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "webrtc_renderer"} "name" "publish a shader" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "webrtc_publish_shader_code_.kt" "lines" 23 "characters" 708 "sha256" "d86fd6fe7825042629ad63c3f8b54c76cb834062650d752bbc20ed7e5f271e51"} {"name" "vertex" "language" "glsl" "path" "webrtc_publish_shader_vertex_.glsl" "lines" 6 "characters" 106 "sha256" "7270a97f3cbaae098ffd43be2431591d87417f94d449cd9a01207a1c60fc2eb6"} {"name" "fragment" "language" "glsl" "path" "webrtc_publish_shader_fragment_.glsl" "lines" 16 "characters" 489 "sha256" "74159cc699e630c09c9c75ba30480e36684adb01e78c9034d655d3cc0c640d7b"}]}
```

```kotlin [field-property box="webrtc_publish_shader" name="code" path="webrtc_publish_shader_code_.kt"]
import afield.media.webrtc.webrtcSink

// Published as login/field-shader: your aaaframe login, or this computer's user name on
// Field's own relay. A browser ShaderTrinket receives it by that name. To join a class,
// run WebRTCNetwork.join("https://<your aaaframe>") once; Field asks you to sign in.
@exported val sink = webrtcSink("field-shader", 640, 360)

val module = moduleHere { +sink }
val quad = Mesh()
module += "quad" to quad
sink += "content" to module

quad.clock = { vec(System.nanoTime() / 1e9, 0.0, 0.0, 0.0) }
quad.set(4, 2) {
    pos.add(-1, -1, 0)
    pos.add(1, -1, 0)
    pos.add(1, 1, 0)
    pos.add(-1, 1, 0)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}

display("main", sink.html())
```

```glsl [field-property box="webrtc_publish_shader" name="vertex" path="webrtc_publish_shader_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;

void main() {
    gl_Position = vec4(pos.xy, 0.0, 1.0);
}
```

```glsl [field-property box="webrtc_publish_shader" name="fragment" path="webrtc_publish_shader_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;

layout(push_constant) uniform constants {
    vec4 clock;
} pc;

void main() {
    vec2 uv = (gl_FragCoord.xy - vec2(320.0, 180.0)) / 180.0;
    float t = pc.clock.x;
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float rings = 0.5 + 0.5 * sin(10.0 * r - 3.0 * t + 4.0 * sin(3.0 * a + t));
    vec3 tint = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + t + 2.0 * r);
    color = vec4(tint * rings * smoothstep(1.6, 0.2, r), 1.0);
}
```

## 3. receive a stream as a texture

<!-- field-box id="webrtc_receive_stream" index="2" -->

```edn [field-box id="webrtc_receive_stream" role="properties"]
{"id" "webrtc_receive_stream" "index" 2 "name" "receive a stream as a texture" "parents" ["root" "webrtc_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "webrtc_renderer"} "name" "receive a stream as a texture" "_tombstone" false "frame" #afield/rect [640.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "webrtc_receive_stream_code_.kt" "lines" 34 "characters" 1043 "sha256" "c9acbc12123dddb1984b233adbf10c91975de46ef0e5c3d61f709dec791cfa30"} {"name" "vertex" "language" "glsl" "path" "webrtc_receive_stream_vertex_.glsl" "lines" 9 "characters" 180 "sha256" "ba17dfa14020b0511f5632daad6bb904ff815014cb1504734d7c12283faa537d"} {"name" "fragment" "language" "glsl" "path" "webrtc_receive_stream_fragment_.glsl" "lines" 8 "characters" 226 "sha256" "96ff1ce71f270dc3ca242ecd7e93c902db2f0d18057971a3cadc81bfe9c3d5f7"}]}
```

```kotlin [field-property box="webrtc_receive_stream" name="code" path="webrtc_receive_stream_code_.kt"]
import afield.media.webrtc.webrtcPublishers
import afield.media.webrtc.webrtcSource

// Until you pick someone, show the first publisher on the network (on Field's own relay,
// that is the box beside this one).
val publishers = webrtcPublishers()
val source = webrtcSource(publishers.names.firstOrNull() ?: "nobody", 640, 360)
publishers.onChange { names ->
    if (source.target == "nobody" && names.isNotEmpty()) source.watch(names.first())
}
publishers.onPick { name -> source.watch(name) }
display("publishers", publishers.html { source.target })

val material = moduleHere { textures("remote" to source) }
val screen = Mesh()
material += "screen" to screen
renderer += "webrtc-remote" to material

screen.set(4, 2) {
    pos.add(-0.9, -0.9, 0.0)
    pos.add(0.9, -0.9, 0.0)
    pos.add(0.9, 0.9, 0.0)
    pos.add(-0.9, 0.9, 0.0)
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2, 0, 2, 3)
}

var said = ""
loop {
    val line = "@${source.target}: ${source.status}"
    if (line != said) println(line)
    said = line
    frame()
}
```

```glsl [field-property box="webrtc_receive_stream" name="vertex" path="webrtc_receive_stream_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 1) out vec2 ftc;

void main() {
    gl_Position = vec4(pos, 1.0);
    ftc = tc;
}
```

```glsl [field-property box="webrtc_receive_stream" name="fragment" path="webrtc_receive_stream_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
layout(set = 0, binding = 0) uniform sampler2D remote;

void main() {
    color = vec4(texture(remote, vec2(ftc.x, 1.0 - ftc.y)).rgb, 1.0);
}
```


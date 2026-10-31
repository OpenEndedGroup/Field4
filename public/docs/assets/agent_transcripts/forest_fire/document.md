# Field Document: forest_fire

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="fire_renderer" index="0" -->

```edn [field-box id="fire_renderer" role="properties"]
{"id" "fire_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["fire_forest"] "properties" {"children" #{#afield/box "fire_forest"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fire_renderer_code_.kt" "lines" 14 "characters" 464 "sha256" "6b9ca406519899b8050ae670f73d96e07a8f64bd0691504897eadeebbd54009f"}]}
```

```kotlin [field-property box="fire_renderer" name="code" path="fire_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Forest fire", initialDimensions = Rect(80.0, 80.0, 768.0, 768.0))
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. forest

<!-- field-box id="fire_forest" index="1" -->

### Narrative

From `code`

A forest fire: a fixed scatter of trees, and four kinds of fire (red, green, blue,
magenta) that compete for them. Burnt trees recover, so a fire front can be
followed by another. Nothing burns until something is lit; the box beneath this
one lights it.
The forest is 768 trees high and as wide as the window's shape needs, so it fills
the window without being stretched.

```edn [field-box id="fire_forest" role="properties"]
{"id" "fire_forest" "index" 1 "name" "forest" "parents" ["root" "fire_renderer"] "children" ["fire_starter"] "properties" {"children" #{#afield/box "fire_starter"} "parents" #{#afield/box "root" #afield/box "fire_renderer"} "name" "forest" "_tombstone" false "frame" #afield/rect [340.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fire_forest_code_.kt" "lines" 17 "characters" 655 "sha256" "4c6e9946df4151b615c288bc2ea1f880430058a48e6e4a9f80fab1ef88e879bf"}]}
```

```kotlin [field-property box="fire_forest" name="code" path="fire_forest_code_.kt"]
/*
A forest fire: a fixed scatter of trees, and four kinds of fire (red, green, blue,
magenta) that compete for them. Burnt trees recover, so a fire front can be
followed by another. Nothing burns until something is lit; the box beneath this
one lights it.
The forest is 768 trees high and as wide as the window's shape needs, so it fills
the window without being stretched.
*/
val treesHigh = 768
prop<ShaderForestFireSystem>(fire) = ShaderForestFireSystem() {
    height = treesHigh
    width = treesHigh * window.width / window.height
    treeDensity = 0.6f
}

// this installs the editable spreading rule in this box's fragment tab
fire.installInto()
```

## 3. fire starter

<!-- field-box id="fire_starter" index="2" -->

### Narrative

From `code`

Draws into the fire's ignition texture: two points that burn for ever, one red
and one green. Each relights its neighbourhood as soon as the trees recover, so
each sends out a train of rings, and where the trains meet one kind takes over.

```edn [field-box id="fire_starter" role="properties"]
{"id" "fire_starter" "index" 2 "name" "fire starter" "parents" ["root" "fire_forest"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fire_forest"} "name" "fire starter" "_tombstone" false "frame" #afield/rect [340.0 160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fire_starter_code_.kt" "lines" 20 "characters" 704 "sha256" "e76657c59135025617b0c5b17989311a5cc27128d9c2b2f7dd03a9e774e3ddc2"} {"name" "vertex" "language" "glsl" "path" "fire_starter_vertex_.glsl" "lines" 12 "characters" 345 "sha256" "c0ac9cc065ddd6b8287a1f4e4fa5deda869d6ebda87289989aa92f59ebdc6e54"} {"name" "fragment" "language" "glsl" "path" "fire_starter_fragment_.glsl" "lines" 17 "characters" 442 "sha256" "42593d907e70917fc34ef6dca29a58ac2d8345b6d39dbbff5cf0b30e1bfef648"}]}
```

```kotlin [field-property box="fire_starter" name="code" path="fire_starter_code_.kt"]
/*
Draws into the fire's ignition texture: two points that burn for ever, one red
and one green. Each relights its neighbourhood as soon as the trees recover, so
each sends out a train of rings, and where the trains meet one kind takes over.
*/
fire.clearFireInput()
fire.clearState()

var module = moduleHere(fire.fireInputParameters("starter"))
fire.addFireInput("starter", module)

val quad = fullscreenTriangle(module)
module += "quad" to quad

// positions are in trees, so place them as fractions of the forest's size
val w = fire.settings.width.toFloat()
val h = fire.settings.height.toFloat()
quad.red = { vec(w * 0.33f, h * 0.5f) }
quad.green = { vec(w * 0.68f, h * 0.5f) }
quad.radius = { 4f }
```

```glsl [field-property box="fire_starter" name="vertex" path="fire_starter_vertex_.glsl"]
#version 450

// one triangle that covers the whole target; positions come from the vertex index
vec2 fullscreenVertex(uint index) {
    if (index == 0u) return vec2(-1.0, -1.0);
    if (index == 1u) return vec2(3.0, -1.0);
    return vec2(-1.0, 3.0);
}

void main() {
    gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
}
```

```glsl [field-property box="fire_starter" name="fragment" path="fire_starter_fragment_.glsl"]
#version 450

layout(push_constant) uniform Push {
    vec2 red;
    vec2 green;
    float radius;
} push;

layout(location = 0) out vec4 outColor;

// one kind of fire per channel; additive, so writing 1 into a channel lights that kind here
void main() {
    vec2 p = gl_FragCoord.xy;
    outColor = vec4(0.0);
    if (distance(p, push.red) < push.radius) outColor.r = 1.0;
    if (distance(p, push.green) < push.radius) outColor.g = 1.0;
}
```


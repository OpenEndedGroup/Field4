# Field Document: renderer_browsing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["triangle" "browse_renderer"] "properties" {"children" #{#afield/box "triangle" #afield/box "browse_renderer"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
```

```kotlin [field-property box="agent_graphics_context" name="code" path="agent_graphics_context_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field")
    Renderer(window)
}

println("agent graphics renderer ready")
start(renderer::run)
```

## 2. browse the renderer

<!-- field-box id="browse_renderer" index="1" -->

### Narrative

From `code`

`renderer` is the parent box's Renderer. column(...) opens it as Finder-style columns:
render (the main target, its passes in draw order), compute, windows, cpu (CPU span timings),
device. Each node with children starts with a `summary` row: its card.

```edn [field-box id="browse_renderer" role="properties"]
{"id" "browse_renderer" "index" 1 "name" "browse the renderer" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "browse the renderer" "_tombstone" false "frame" #afield/rect [380.0 40.0 820.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "browse_renderer_code_.kt" "lines" 7 "characters" 376 "sha256" "7b1f1be05bbbe9384786c4eacb942fd207f6a22cb7c3e111efda5d10da8bb197"}]}
```

```kotlin [field-property box="browse_renderer" name="code" path="browse_renderer_code_.kt"]
// `renderer` is the parent box's Renderer. column(...) opens it as Finder-style columns:
// render (the main target, its passes in draw order), compute, windows, cpu (CPU span timings),
// device. Each node with children starts with a `summary` row: its card.
column("renderer", renderer, width = 1100)

// The same value as an indented tree.
tree("renderer tree", renderer)
```

## 3. triangle

<!-- field-box id="triangle" index="2" -->

### Narrative

From `code`

A module is one draw pipeline: this box's `vertex` and `fragment` shaders plus the meshes it
draws. `renderer` is inherited from the renderer parent box, and `+=` puts the module on screen.

```edn [field-box id="triangle" role="properties"]
{"id" "triangle" "index" 2 "name" "triangle" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "triangle" "_tombstone" false "frame" #afield/rect [40.0 180.0 320.0 100.0]} "blockProperties" [{"name" "vertex" "language" "glsl" "path" "triangle_vertex_.glsl" "lines" 11 "characters" 186 "sha256" "0786269bdd841b7a894d38a9382444d70e52fe7b2a8c49fcb7e15ca0d38fb855"} {"name" "fragment" "language" "glsl" "path" "triangle_fragment_.glsl" "lines" 8 "characters" 157 "sha256" "a6663786f9a3e5144723c0435505bff863a92a858a5c09f615da9a110d14dcb3"} {"name" "code" "language" "kotlin" "path" "triangle_code_.kt" "lines" 21 "characters" 900 "sha256" "b34d97c5573a180a943ad78712267c7da19e3adf77e15cfb37f89a7ab7cac85b"}]}
```

```glsl [field-property box="triangle" name="vertex" path="triangle_vertex_.glsl"]
#version 450
layout (location = 0) in vec3 pos;

layout (location = 1) in vec4 col;
layout (location = 0) out vec4 fcol;

void main()
{
    fcol = col;
    gl_Position = vec4(pos, 1);
}
```

```glsl [field-property box="triangle" name="fragment" path="triangle_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 uFragColor;

layout(location = 0) in vec4 fcol;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0) * fcol;
}
```

```kotlin [field-property box="triangle" name="code" path="triangle_code_.kt"]
// A module is one draw pipeline: this box's `vertex` and `fragment` shaders plus the meshes it
// draws. `renderer` is inherited from the renderer parent box, and `+=` puts the module on screen.
renderer += moduleHere {
    primitiveType = triangle_strip

    // A mesh holds vertex buffers whose names come from the vertex shader's `in` attributes
    // (`pos`, `col`), plus the index buffer `tri`. Re-running the box replaces it by name.
    +Mesh("my mesh") {
        // Room for 3 vertices and 3 indices; inside, `pos`, `col` and `tri` append data.
        set(3, 3) {
            // x, y, z for each of the three corners, in clip space (-1..1)
            pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)

            // one RGBA colour per vertex, `it` running 0, 1, 2
            3 {
                col.add(0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0, 1, 2)
        }
    }
}
```


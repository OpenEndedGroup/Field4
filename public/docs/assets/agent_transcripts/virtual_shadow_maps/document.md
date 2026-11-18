# Field Document: virtual_shadow_maps

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="agent_graphics_context" index="0" -->

```edn [field-box id="agent_graphics_context" role="properties"]
{"id" "agent_graphics_context" "index" 0 "name" "renderer" "parents" ["root"] "children" ["virtual_shadow_maps"] "properties" {"children" #{#afield/box "virtual_shadow_maps"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_graphics_context_code_.kt" "lines" 15 "characters" 275 "sha256" "e90393b044932ad7e022f5505bec768466f0d819aaa553c877bfe061d981c6d2"}]}
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

## 2. virtual shadow maps

<!-- field-box id="virtual_shadow_maps" index="1" -->

```edn [field-box id="virtual_shadow_maps" role="properties"]
{"id" "virtual_shadow_maps" "index" 1 "name" "virtual shadow maps" "parents" ["root" "agent_graphics_context"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "agent_graphics_context"} "name" "virtual shadow maps" "_tombstone" false "frame" #afield/rect [400.0 40.0 320.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "virtual_shadow_maps_code_.kt" "lines" 16 "characters" 696 "sha256" "ba7d8b0109204688e962fd6031e54c56321df38a360ff6cb0c44f0018f7401ab"}]}
```

```kotlin [field-property box="virtual_shadow_maps" name="code" path="virtual_shadow_maps_code_.kt"]
import afield.graphics.scene.fabricated.StandardRendererDocsHarness
import java.io.File

/*
Fixed 1024² shadow map | virtual shadow map | the virtual map's debug view, close to the
contact edge of a torus standing on a disk. Debug colours: green is the finest level, then
blue, orange, magenta; brightness steps from one physical page to the next.
*/
val out = "/tmp/field-virtual-shadows.png"

val compare: suspend () -> Unit = {
    val file = StandardRendererDocsHarness.renderVirtualShadowComparison(out, renderer = renderer)
    display("main", StandardRendererDocsHarness.htmlForImage(file, "fixed | virtual | virtual debug view"))
    println(File("$out.txt").readText())
}
start(compare)
```


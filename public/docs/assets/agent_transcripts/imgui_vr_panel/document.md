# Field Document: imgui_vr_panel

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. window + renderer

<!-- field-box id="imgui_vr_renderer" index="0" -->

```edn [field-box id="imgui_vr_renderer" role="properties"]
{"id" "imgui_vr_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["imgui_vr_panel"] "properties" {"children" #{#afield/box "imgui_vr_panel"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imgui_vr_renderer_code_.kt" "lines" 14 "characters" 241 "sha256" "d2064d03c2114a4503c04ef314fc55ee0014752e13806df525cdf028c8489067"}]}
```

```kotlin [field-property box="imgui_vr_renderer" name="code" path="imgui_vr_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("ImGui VR panel")
    Renderer(window)
}

println("imgui vr renderer ready")
start(renderer::run)
```

## 2. imgui vr panel

<!-- field-box id="imgui_vr_panel" index="1" -->

### Narrative

From `code`

ImGui as a VR panel.

ImGuiVrPanelDemo wires the whole pipeline: an ImGuiRenderToTexture drawn into an RTT,
textured onto a world-space ImGuiPanel quad, rendered once per eye by
SideBySideStereoTarget, packed side-by-side, and blitted to the window. The panel is
interactive: the mock controller (here driven by the mouse) casts a ray, the hit is
converted to panel UV, and injected into ImGui.

Move the mouse to aim the ray; left-click to press. To bind the panel to a hand instead
of a fixed point, call panel.place(handPos, handRight, handUp) each frame (see ImGuiPanel).

```edn [field-box id="imgui_vr_panel" role="properties"]
{"id" "imgui_vr_panel" "index" 1 "name" "imgui vr panel" "parents" ["root" "imgui_vr_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "imgui_vr_renderer"} "name" "imgui vr panel" "_tombstone" false "frame" #afield/rect [300.0 140.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imgui_vr_panel_code_.kt" "lines" 24 "characters" 1147 "sha256" "816154c0fb6fec6aeed652154673cc5813035ee8ec23127668c794a448ce9b3d"}]}
```

```kotlin [field-property box="imgui_vr_panel" name="code" path="imgui_vr_panel_code_.kt"]
// ImGui as a VR panel.
//
// ImGuiVrPanelDemo wires the whole pipeline: an ImGuiRenderToTexture drawn into an RTT,
// textured onto a world-space ImGuiPanel quad, rendered once per eye by
// SideBySideStereoTarget, packed side-by-side, and blitted to the window. The panel is
// interactive: the mock controller (here driven by the mouse) casts a ray, the hit is
// converted to panel UV, and injected into ImGui.
//
// Move the mouse to aim the ray; left-click to press. To bind the panel to a hand instead
// of a fixed point, call panel.place(handPos, handRight, handUp) each frame (see ImGuiPanel).
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.four.targets.SideBySideStereoTarget
import afield.examples.four.ImGuiVrPanelDemo

val renderer: Renderer = fromContext()
val sbs = SideBySideStereoTarget(renderer)
val demo = ImGuiVrPanelDemo(renderer.window, renderer, sbs)
demo.build()

// Mouse drives the mock controller ray on the desktop (a real controller drives it in XR).
sbs.enableMouseController(distanceMeters = 0.001f)

println("imgui vr panel ready — move the mouse to aim the ray, click to press")
```


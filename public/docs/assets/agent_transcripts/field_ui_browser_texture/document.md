# Field Document: field_ui_browser_texture

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. renderer — Field UI texture

<!-- field-box id="field_ui_texture_renderer" index="0" -->

```edn [field-box id="field_ui_texture_renderer" role="properties"]
{"id" "field_ui_texture_renderer" "index" 0 "name" "renderer — Field UI texture" "parents" ["root"] "children" ["field_ui_texture_panel"] "properties" {"children" #{#afield/box "field_ui_texture_panel"} "parents" #{#afield/box "root"} "name" "renderer — Field UI texture" "_tombstone" false "frame" #afield/rect [140.0 180.0 250.0 55.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "field_ui_texture_renderer_code_.kt" "lines" 15 "characters" 282 "sha256" "5b0b7c04956e2b8e82b26d18494e0da6da04e4721dc109f98e48a6df9598c923"}]}
```

```kotlin [field-property box="field_ui_texture_renderer" name="code" path="field_ui_texture_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Field UI on a texture")
    Renderer(window)
}

println("Field UI texture renderer ready")
start(renderer::run)
```

## 2. Field's UI — live Chromium texture

<!-- field-box id="field_ui_texture_panel" index="1" -->

```edn [field-box id="field_ui_texture_panel" role="properties"]
{"id" "field_ui_texture_panel" "index" 1 "name" "Field's UI — live Chromium texture" "parents" ["root" "field_ui_texture_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "field_ui_texture_renderer"} "name" "Field's UI — live Chromium texture" "_tombstone" false "frame" #afield/rect [460.0 180.0 330.0 55.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "field_ui_texture_panel_code_.kt" "lines" 44 "characters" 1205 "sha256" "cf432419f663c310bb865cad63dadd8da52bd9613b0854666f324a0e886f96bc"} {"name" "vertex" "language" "glsl" "path" "field_ui_texture_panel_vertex_.glsl" "lines" 9 "characters" 180 "sha256" "ba17dfa14020b0511f5632daad6bb904ff815014cb1504734d7c12283faa537d"} {"name" "fragment" "language" "glsl" "path" "field_ui_texture_panel_fragment_.glsl" "lines" 8 "characters" 228 "sha256" "4a06f58cb3aac101a544fdcd7c0e0a26970362071d68fc232f1874f49598d604"}]}
```

```kotlin [field-property box="field_ui_texture_panel" name="code" path="field_ui_texture_panel_code_.kt"]
import afield.four.browser.FieldUiBrowserTexture
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 1280,
    height = 720,
    readyBoxUid = thisBox.id
)
renderer.renderList.preTargets["field-ui-browser-upload"] =
    afield.four.Target { command, _ -> browser.prepareSampledImagesFor(command) }

val material = moduleHere {
    textures("fieldUi" to browser)
}
val panel = Mesh()
material += "field-ui-panel" to panel
renderer += "field-ui-browser-texture" to material

panel.set(4, 2) {
    pos.add(
        -0.96, -0.96, 0.0,
         0.96, -0.96, 0.0,
         0.96,  0.96, 0.0,
        -0.96,  0.96, 0.0
    )
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2, 0, 2, 3)
}

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println("Field UI browser texture is live: ${browser.presentedFrame}")
    }
    frame()
}.end {
    renderer.renderList.modules.remove("field-ui-browser-texture")
    renderer.renderList.preTargets.remove("field-ui-browser-upload")
    browser.close()
}
```

```glsl [field-property box="field_ui_texture_panel" name="vertex" path="field_ui_texture_panel_vertex_.glsl"]
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec2 tc;
layout(location = 1) out vec2 ftc;

void main() {
    gl_Position = vec4(pos, 1.0);
    ftc = tc;
}
```

```glsl [field-property box="field_ui_texture_panel" name="fragment" path="field_ui_texture_panel_fragment_.glsl"]
#version 450
layout(location = 0) out vec4 color;
layout(location = 1) in vec2 ftc;
layout(set = 0, binding = 0) uniform sampler2D fieldUi;

void main() {
    color = vec4(texture(fieldUi, vec2(ftc.x, 1.0 - ftc.y)).rgb, 1.0);
}
```


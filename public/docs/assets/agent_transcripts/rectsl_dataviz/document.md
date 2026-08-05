# Field Document: rectsl_dataviz

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. window + renderer

<!-- field-box id="dataviz_renderer" index="0" -->

```edn [field-box id="dataviz_renderer" role="properties"]
{"id" "dataviz_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["dataviz_figure"] "properties" {"children" #{#afield/box "dataviz_figure"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [-640.0 -360.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataviz_renderer_code_.kt" "lines" 14 "characters" 253 "sha256" "d517b9ba4734050ce0a0765e2eb427f89da9b9f223b8b352ca9659644f21aad1"}]}
```

```kotlin [field-property box="dataviz_renderer" name="code" path="dataviz_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — data visualization")
    Renderer(window)
}

println("dataviz renderer ready")
start(renderer::run)
```

## 2. rectsl dataviz

<!-- field-box id="dataviz_figure" index="1" -->

### Narrative

From `code` tags: `#rectsl`

RectSL data-visualization demo.

The #rectsl program draws a viridis colour-bar legend (array + colormap)
and a bar chart bound to a data list (data / normalize / bars), animated in
with a staggered eased `->alpha`. Edit `rectsl` and re-run to change it live.
See rectsl_cheatsheet.md ("Data visualization") / rectsl_examples.md.

```edn [field-box id="dataviz_figure" role="properties"]
{"id" "dataviz_figure" "index" 1 "name" "rectsl dataviz" "parents" ["root" "dataviz_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "dataviz_renderer"} "name" "rectsl dataviz" "_tombstone" false "frame" #afield/rect [-300.0 -220.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dataviz_figure_code_.kt" "lines" 62 "characters" 1928 "sha256" "11f06834d358726f328e54e395772c59b0e1862f0d90d6eb48e04eacf6d8e2ea"}]}
```

```kotlin [field-property box="dataviz_figure" name="code" path="dataviz_figure_code_.kt"]
// RectSL data-visualization demo.
//
// The #rectsl program draws a viridis colour-bar legend (array + colormap)
// and a bar chart bound to a data list (data / normalize / bars), animated in
// with a staggered eased `->alpha`. Edit `rectsl` and re-run to change it live.
// See rectsl_cheatsheet.md ("Data visualization") / rectsl_examples.md.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 20 10 meters
->duration 6
->fps 30

# colour legend across the top: 40 swatches sweeping viridis
"leg" rect  0.5 0.8 ->wh
  40 '( fc 9.6 *  4.3 ->offset  "viridis" f colormap solid ->fill ) array
;

# bar chart from data, viridis by value, staggered grow-in
'( 4 7 2 9 5 8 3 6 1 6 5 9 4 7 3 8 ) data  normalize  "viridis" bars
  '( '( 0=0 0.7=1 ) ease-out ->alpha ) each
  0.05 ->stagger-time
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

// Seed this box's shader-source properties before `moduleHere {}` runs; the
// `//%-` fragment break makes this fragment execute first (see the playground
// transcript for the why).
once.vertex { RECT_ANIMATION_VERTEX_SHADER }
once.fragment { shader.exampleFragmentShader!! }

//%-

val module = moduleHere {
    blend = true
    depthTest = false
    primitiveType = triangles
}
val m = Mesh()
module += "scene" to m
renderer += "rectsl-dataviz" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

val duration = program.duration.toDouble()
var t = 0.0
loop {
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(t % duration, m)
    t += 1.0 / 60.0
    true
}
```


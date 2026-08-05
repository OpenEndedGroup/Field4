# Field Document: rectsl_plot_axes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. window + renderer

<!-- field-box id="plot_renderer" index="0" -->

```edn [field-box id="plot_renderer" role="properties"]
{"id" "plot_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["plot_figure"] "properties" {"children" #{#afield/box "plot_figure"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [-640.0 -360.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "plot_renderer_code_.kt" "lines" 14 "characters" 246 "sha256" "6dcaf0e33cf2b68fee73361b58603108a1644aca5a5f244daecdf0632b314526"}]}
```

```kotlin [field-property box="plot_renderer" name="code" path="plot_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — plotting layer")
    Renderer(window)
}

println("plot renderer ready")
start(renderer::run)
```

## 2. rectsl plot + axes

<!-- field-box id="plot_figure" index="1" -->

### Narrative

From `code` tags: `#rectsl`

RectSL plotting-layer demo.

The #rectsl program sets ONE shared domain (`->domain` + `->frame`), draws
`axes` (rules, nice-number ticks, numeric rect-numeral labels, gridlines),
then overlays three series in that domain (`area`, `line`, `points`) and
finishes with a `colorbar` and a discrete swatch `legend`. Edit `rectsl`
and re-run to change it live. See rectsl_cheatsheet.md ("Plotting layer")
/ rectsl_examples.md ("A plotted figure").

```edn [field-box id="plot_figure" role="properties"]
{"id" "plot_figure" "index" 1 "name" "rectsl plot + axes" "parents" ["root" "plot_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "plot_renderer"} "name" "rectsl plot + axes" "_tombstone" false "frame" #afield/rect [-300.0 -220.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "plot_figure_code_.kt" "lines" 68 "characters" 2177 "sha256" "c3ef48bfdf8df61388382bd15ac32330a4eaafb9437694dee6a15f8d4b921ad9"}]}
```

```kotlin [field-property box="plot_figure" name="code" path="plot_figure_code_.kt"]
// RectSL plotting-layer demo.
//
// The #rectsl program sets ONE shared domain (`->domain` + `->frame`), draws
// `axes` (rules, nice-number ticks, numeric rect-numeral labels, gridlines),
// then overlays three series in that domain (`area`, `line`, `points`) and
// finishes with a `colorbar` and a discrete swatch `legend`. Edit `rectsl`
// and re-run to change it live. See rectsl_cheatsheet.md ("Plotting layer")
// / rectsl_examples.md ("A plotted figure").
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 16 10 abstract
->duration 3
->fps 30

# shared coordinate frame: explicit ->domain + auto ->margins, then axes underneath
0 10  0 1  ->domain
->frame
axes

# three series overlaid in the ONE shared domain
0 10 40 linspace  dup '( 0.7 * sin 1 + 2 / ) data-map   "viridis" area
0 10 40 linspace  dup '( 0.55 * cos 1 + 2 / ) data-map  "plasma"  line
0 10 12 linspace  dup '( 0.9 * sin 1 + 2 / ) data-map   "turbo"   points

# a colour-bar legend (numeric labels) and a discrete swatch key
"viridis" colorbar
5 "turbo" legend
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
renderer += "rectsl-plot-axes" to module

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


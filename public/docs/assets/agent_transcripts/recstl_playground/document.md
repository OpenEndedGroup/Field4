# Field Document: recstl_playground

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. window + renderer

<!-- field-box id="recstl_renderer" index="0" -->

```edn [field-box id="recstl_renderer" role="properties"]
{"id" "recstl_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["recstl_playground"] "properties" {"children" #{#afield/box "recstl_playground"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "recstl_renderer_code_.kt" "lines" 14 "characters" 242 "sha256" "f00fbee0e27d6e919acebae74f4ff51a8589f27bcdd5c99bcee0cf2abef8dc29"}]}
```

```kotlin [field-property box="recstl_renderer" name="code" path="recstl_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL playground")
    Renderer(window)
}

println("recstl renderer ready")
start(renderer::run)
```

## 2. recstl

<!-- field-box id="recstl_playground" index="1" -->

### Narrative

From `code`

RectSL playground.

Edit the `rectsl` program below and re-run this box to change it live.
The transport controls in this box's output area drive the clock:
play  — advance time every frame and loop the animation
loop  — wrap at the end (off = run once and hold on the last frame)
speed — playback rate multiplier
time  — scrub to a frame (while paused); moving it freezes the clock there

See the "RectSL examples" docs page / rectsl_cheatsheet.md for the language.

```edn [field-box id="recstl_playground" role="properties"]
{"id" "recstl_playground" "index" 1 "name" "recstl" "parents" ["root" "recstl_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "recstl_renderer"} "name" "recstl" "_tombstone" false "frame" #afield/rect [300.0 140.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "recstl_playground_code_.kt" "lines" 96 "characters" 3039 "sha256" "49e58beb5031ff61739153575d31939dfd6c243f1b3a2de460691f3501342c68"}]}
```

```kotlin [field-property box="recstl_playground" name="code" path="recstl_playground_code_.kt"]
// RectSL playground.
//
// Edit the `rectsl` program below and re-run this box to change it live.
// The transport controls in this box's output area drive the clock:
//   play  — advance time every frame and loop the animation
//   loop  — wrap at the end (off = run once and hold on the last frame)
//   speed — playback rate multiplier
//   time  — scrub to a frame (while paused); moving it freezes the clock there
//
// See the "RectSL examples" docs page / rectsl_cheatsheet.md for the language.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 16 9 meters
->duration 6
->fps 30

material spectrum '(
  uv 3.0 * time 0.5 * + fbm palette
)

"bar" rect
  0 0 ->xy
  0.9 1 ->wh
  '( 0 0.6  0.75 6.5  1.5 0.6 ) 1.5 loop-time ->h
  0xffffff solid ->fill
  spectrum ->material
;

16 '(
  "bar" ->select clone
    i 7.5 - 1.2 * 0 ->xy
    i 0.14 * ->delay-all-time
) repeat
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val shader  = rectMaterialShaderCode(program)

// Seed this box's shader-source properties before `moduleHere {}` runs.
// moduleHere builds its module from the box's `vertex`/`fragment` properties
// (not from assignments in the lambda); `once.vertex`/`once.fragment` set them
// once. The `//%-` fragment break is load-bearing: fragments run in order, each
// expanded only after the previous ran, so this fragment executes first and the
// moduleHere fragment then prepares its shaders from what was set here.
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
renderer += "rectsl" to module

val ww = program.wall.width.toFloat()
val wh = program.wall.height.toFloat()
m.push["worldToClip"] = afield.stdlib.linalg.Matrix4f().ortho(-ww / 2f, ww / 2f, -wh / 2f, wh / 2f, -1f, 1f)
m.push["wallWidth"] = ww
m.push["wallHeight"] = wh

// --- transport state, driven by the controls below ---
val duration = program.duration.toDouble()
var playing = true
var looping = true
var speed = 1.0
var t = 0.0

controlGroup("transport") {
    checkbox("play", initialValue = playing) { playing = it }
    checkbox("loop", initialValue = looping) { looping = it }
    slider("speed", min = 0.0, max = 3.0, step = 0.05, initialValue = speed) { speed = it }
    slider("time", min = 0.0, max = duration, step = 0.01, initialValue = 0.0) {
        // Scrubbing the timeline while paused sets the clock; while playing it is ignored.
        if (!playing) t = it
    }
}

loop {
    if (playing) {
        t += (1.0 / 60.0) * speed
        if (t > duration) t = if (looping) t - duration else duration
    }
    val sampleTime = if (looping) t % duration else t.coerceIn(0.0, duration)
    m.push["time"] = t.toFloat()
    program.writeFrameToMesh(sampleTime, m)
    true
}
```


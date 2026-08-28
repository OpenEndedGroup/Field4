# Field Document: rectsl_musicality

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. window + renderer

<!-- field-box id="music_renderer" index="0" -->

```edn [field-box id="music_renderer" role="properties"]
{"id" "music_renderer" "index" 0 "name" "window + renderer" "parents" ["root"] "children" ["music_groove"] "properties" {"children" #{#afield/box "music_groove"} "parents" #{#afield/box "root"} "name" "window + renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "music_renderer_code_.kt" "lines" 14 "characters" 248 "sha256" "873d83fc20d7d6ebad68f9c26ca854438315e9f96ce94fb55c76151b4f76375c"}]}
```

```kotlin [field-property box="music_renderer" name="code" path="music_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false

once.renderer {
    window = Window("RectSL — musicality")
    Renderer(window)
}

println("musicality renderer ready")
start(renderer::run)
```

## 2. rectsl musicality

<!-- field-box id="music_groove" index="1" -->

### Narrative

From `code`

RectSL's musical layer: the pattern language as scene structure.

Two facts do all the work. A RectSL track is `sample(t)` and a pattern is
`events(arc)` — both pure functions of time — so (1) a pattern IS a track,
which is why `saw ->rotate` needs no conversion word, and (2) a pattern
event carries an onset and a real duration, which is exactly what
`->live birth life` wants, so an event IS a life window. `pat` is `array`
indexed by rhythm instead of by ordinal.

Nothing below is written in seconds. Edit `rectsl` and re-run to change the
groove live. See rectsl_cheatsheet.md ("Musicality") / rectsl_examples.md.

```edn [field-box id="music_groove" role="properties"]
{"id" "music_groove" "index" 1 "name" "rectsl musicality" "parents" ["root" "music_renderer"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "music_renderer"} "name" "rectsl musicality" "_tombstone" false "frame" #afield/rect [300.0 140.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "music_groove_code_.kt" "lines" 107 "characters" 3295 "sha256" "28616872584d4cd39bd95104a9fba00d12a2dcaaea77d3750f89e1a59d279aa2"}]}
```

```kotlin [field-property box="music_groove" name="code" path="music_groove_code_.kt"]
// RectSL's musical layer: the pattern language as scene structure.
//
// Two facts do all the work. A RectSL track is `sample(t)` and a pattern is
// `events(arc)` — both pure functions of time — so (1) a pattern IS a track,
// which is why `saw ->rotate` needs no conversion word, and (2) a pattern
// event carries an onset and a real duration, which is exactly what
// `->live birth life` wants, so an event IS a life window. `pat` is `array`
// indexed by rhythm instead of by ordinal.
//
// Nothing below is written in seconds. Edit `rectsl` and re-run to change the
// groove live. See rectsl_cheatsheet.md ("Musicality") / rectsl_examples.md.
import afield.rectsl.RectAnimationOracle
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.writeFrameToMesh
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER

val rectsl = """
#rectsl
->wall 16 9 abstract
->tempo 120
->beats-per-cycle 4
->duration-cycles 4
->fps 30
->background 0x0a0a12

# The accent: a full-wall flash, behind everything, on every fourth bar only.
# This is the thing a loop cannot do and a groove must — `->every` keeps both
# versions of every track and chooses between them per sample.
"accent" rect
  16 9 ->wh
  0 0 ->xy
  0xffffff solid ->fill
  0 ->alpha
  ->back
  selected 4 '( 0.14 ->alpha ) ->every
;

# The ring: sixteen slots in space, five of them sounding, the whole figure
# turning once a bar because `saw` is a pattern read as a rotation track.
"ring" group
  "hat" rect
    0.26 1.0 ->wh
    0x7cf5d0 solid ->fill
    16 '( f0 tau * ->rot  f0 tau * 3.2 ->polar ) array
    5 16 0 ->euclid
  ;
end
"ring" ->select
  saw tau * ->rotate
;

# The kick: one copy per event, each ->live for exactly its own quarter and
# authored in ITS OWN local time — so sixteen strikes, not a synchronised
# chorus. `step` places each one across the wall, restarting at every bar line.
"kick" rect
  1.3 ->square
  0xff5a3c solid ->fill
  "bd*4" '(
      step 1.5 - 4.2 *  ->x
      '( 0=5.5  0.34=0 ) ease-out ->y
      '( 0=0  0.10=1  0.5=0 ) linear ->alpha
  ) pat
;

# The score: the same kind of string, read as a ruler instead of as a rhythm.
# `pat` spends the subdivision on WHEN, `strip` spends it on WHERE.
"tick" rect
  0.34 ->square
  0x3c8cff solid ->fill
  "bd [sd sd] hh*4" '( -3.9 ->y  0.55 ->alpha ) strip
;
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
renderer += "rectsl-musicality" to module

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

